package dev.eclipse.ssh.data.credentials

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.eclipse.ssh.di.HostCredentialsDataStore
import dev.eclipse.ssh.security.SecretCipher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Everything the UI is allowed to know about a host's saved credentials: that they exist, what the
 * key file was called, and what kind of key it is.
 *
 * No secret is ever part of this. A saved password is never rendered back into the field it came
 * from — not masked, not as a placeholder of the right length — because a field that can be
 * populated can be revealed, by a screen reader, by an accessibility service, by the next person to
 * pick up an unlocked phone, or by an autofill provider offering to save it somewhere else. The form
 * shows "Saved" and offers to replace or forget it, which is everything a user needs and nothing an
 * onlooker can use.
 *
 * [keyLabel] is the one piece of a credential that is stored in the clear, and deliberately: it is
 * the file name the user picked, it is what makes "which key is on this host?" answerable at a
 * glance, and a file name is not a secret. A non-null [keyLabel] always means a key is stored.
 */
data class StoredCredentials(
    val hasPassword: Boolean = false,
    val keyLabel: String? = null,
    /** Short label from [dev.eclipse.ssh.ssh.sshKeyTypeLabel], e.g. `RSA 3072`. Null if unknown. */
    val keyType: String? = null,
    val hasPassphrase: Boolean = false,
) {
    val hasKey: Boolean get() = keyLabel != null

    /** Nothing is saved for this host, so the form has nothing to offer forgetting. */
    val isEmpty: Boolean get() = !hasPassword && !hasKey
}

/**
 * A one-line, secret-free description of what is saved for a host, for the details sheet.
 *
 * Deliberately built from metadata only: [StoredCredentials] is the widest view of a credential that
 * ever leaves this package, and it carries no key bytes, no password and no passphrase. The key's
 * *label* is the filename the user picked, which they chose and which is already on their device, and
 * the type is derived from the public half.
 */
fun StoredCredentials.describe(): String {
    val parts = buildList {
        if (hasPassword) add("Password")
        keyLabel?.let { label ->
            val suffix = listOfNotNull(keyType, "passphrase".takeIf { hasPassphrase })
            add(if (suffix.isEmpty()) label else "$label (${suffix.joinToString(", ")})")
        }
    }
    return if (parts.isEmpty()) "Asked at every connect" else parts.joinToString(" + ")
}

/**
 * One credential field's fate when a host is saved.
 *
 * Three states rather than a nullable string, because "the user left the field empty" and "the user
 * asked to remove what was stored" are opposite intentions that the form cannot tell apart from the
 * text alone: a saved secret is never rendered back into its field (see [StoredCredentials]), so an
 * untouched field is *always* blank. Collapsing the two would erase a saved password every time
 * somebody edited a host's port.
 */
sealed interface SecretEdit {
    /** Leave whatever is stored exactly as it is. */
    data object Keep : SecretEdit

    /** Remove the stored value. */
    data object Forget : SecretEdit

    /** Replace the stored value. An empty [value] is treated as [Forget]. */
    data class Replace(val value: String) : SecretEdit {
        /** Redacted, for the same reason [KeyEdit.Replace] is not a data class at all. */
        override fun toString(): String = "Replace(***)"
    }
}

/** [SecretEdit] for the private key, which carries its file name and detected type alongside. */
sealed interface KeyEdit {
    data object Keep : KeyEdit

    data object Forget : KeyEdit

    /**
     * Stores [bytes] as the host's key. [label] is the picked file's name and [type] the label from
     * [dev.eclipse.ssh.ssh.sshKeyTypeLabel]; an empty [bytes] is treated as [Forget].
     *
     * Not a `data class`: the generated `equals`/`hashCode`/`toString` would compare a key by
     * identity and, worse, print it. `toString` on a credential is how key material reaches a crash
     * report.
     */
    class Replace(val bytes: ByteArray, val label: String, val type: String?) : KeyEdit
}

/**
 * What to change about one host's credentials. Every field defaults to [SecretEdit.Keep] /
 * [KeyEdit.Keep], so a caller that only means to change the password cannot silently drop the key.
 */
data class HostCredentialUpdate(
    val password: SecretEdit = SecretEdit.Keep,
    val key: KeyEdit = KeyEdit.Keep,
    val passphrase: SecretEdit = SecretEdit.Keep,
) {
    /** True when applying this would not touch anything, so the caller can skip the write entirely. */
    val isNoop: Boolean = password == SecretEdit.Keep && key == KeyEdit.Keep && passphrase == SecretEdit.Keep
}

/**
 * Durable per-host credentials: the password, private key and passphrase a user chooses to save on a
 * host profile so that connecting is one tap instead of a form.
 *
 * Separate from [dev.eclipse.ssh.background.SessionRegistry], which looks like it could do this job
 * and cannot: the registry's whole purpose is to describe the sessions that are *live* right now for
 * the foreground service to rebuild, and `unregister` — which runs on every ordinary disconnect —
 * deletes the credentials along with the entry. Saving a key there would mean the key vanished the
 * first time the user closed the tab. This store's lifetime is the host profile's: written when the
 * profile is saved, deleted when the profile is deleted, and untouched by anything a session does.
 *
 * Secrets are held only as [SecretCipher] payloads, so the file on disk carries no usable credential
 * even if it is read directly. Key bytes are base64-encoded before encryption because preferences
 * store strings and a private key is not valid UTF-8 once it is DER.
 *
 * The [DataStore] arrives by injection rather than through a `Context.preferencesDataStore` delegate.
 * That delegate caches one instance per property per classloader, keyed on nothing — under
 * Robolectric, where every test method gets a fresh `filesDir`, the second test in a class silently
 * shares the first one's store and reads a file that no longer exists. Taking the store as a
 * parameter is what makes the rules below testable at all; see `HostCredentialStoreTest`.
 */
@Singleton
class HostCredentialStore @Inject constructor(
    @HostCredentialsDataStore private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    /**
     * Saved-credential metadata per host id, for the hosts that have any.
     *
     * An unreadable file yields "nothing is saved" rather than terminating the flow. This one is
     * combined into the state the whole UI renders from, and a flow that throws is a flow that has
     * ended: letting the [IOException] through would freeze the host list, not just the credential
     * badges. Falling back means the user is asked for a password they had saved — recoverable, and
     * the safe direction for a store of secrets.
     */
    val credentials: Flow<Map<String, StoredCredentials>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::summarise)

    /** Metadata for one host. Never returns secret material. */
    suspend fun stored(hostId: String): StoredCredentials =
        credentials.first()[hostId] ?: StoredCredentials()

    /**
     * Applies [update], leaving every field it does not mention alone.
     *
     * A single `edit` so the write is atomic: a passphrase that landed without its key, or a key that
     * landed without its label, would both be states the reads below have to defend against forever.
     */
    suspend fun apply(hostId: String, update: HostCredentialUpdate) {
        require(hostId.isNotBlank()) { "Cannot store credentials for a host with no id" }
        if (update.isNoop) return
        editPrefs { prefs ->
            applySecret(prefs, passwordKey(hostId), update.password)
            applyKey(prefs, hostId, update.key)
            applySecret(prefs, passphraseKey(hostId), update.passphrase)
            // A passphrase with no key left to unlock is a secret nobody can account for, so
            // forgetting a key forgets its passphrase in the same transaction. Enforced here rather
            // than asked of every caller, because the caller that forgets is the one about to
            // navigate away.
            if (prefs[keyKey(hostId)] == null) prefs.remove(passphraseKey(hostId))
        }
    }

    /** The saved password, or null if none is stored or it cannot be decrypted. */
    suspend fun password(hostId: String): String? = secret(passwordKey(hostId))

    /** The saved passphrase for the stored key, or null. */
    suspend fun passphrase(hostId: String): String? = secret(passphraseKey(hostId))

    /** The saved private key's bytes, or null if none is stored or it cannot be decoded. */
    suspend fun keyBytes(hostId: String): ByteArray? = secret(keyKey(hostId))?.let { encoded ->
        runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Removes everything stored for [hostId]. Called when a host profile is deleted. */
    suspend fun forget(hostId: String) {
        editPrefs { prefs ->
            prefs.remove(passwordKey(hostId))
            prefs.remove(keyKey(hostId))
            prefs.remove(passphraseKey(hostId))
            prefs.remove(keyLabelKey(hostId))
            prefs.remove(keyTypeKey(hostId))
        }
    }

    /** Removes every stored credential, for the Settings "forget all saved credentials" action. */
    suspend fun forgetAll() {
        editPrefs { prefs ->
            prefs.asMap().keys.filter { it.name.isCredentialKey() }.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                prefs.remove(key as Preferences.Key<Any>)
            }
        }
    }

    /**
     * Decrypts one stored field, or returns null.
     *
     * Failure is swallowed on purpose, and it is a case that really happens: a payload encrypted
     * under a vault key that no longer exists — the app's data restored onto another device, or the
     * keystore cleared — cannot be decrypted by anyone, ever. Throwing would take the connection
     * attempt down with it; returning null falls back to asking the user, which is what the app did
     * before the credential was saved.
     */
    /**
     * Every write goes through here so none of them runs on the caller's dispatcher.
     *
     * DataStore invokes an `edit` transform with `withContext(callerContext)`, so a transform started
     * from `viewModelScope` runs on the main thread -- AES encryption plus a file write on the UI thread. Worse, writes are
     * serialised through a single actor, so one transform parked on a stalled dispatcher blocks every
     * later write to this store for the life of the process.
     */
    private suspend fun editPrefs(block: suspend (MutablePreferences) -> Unit): Preferences =
        withContext(Dispatchers.IO) { dataStore.edit(block) }

    private suspend fun secret(key: Preferences.Key<String>): String? {
        val payload = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[key] ?: return null
        return runCatching { cipher.decrypt(payload) }.getOrNull()?.takeIf(String::isNotEmpty)
    }

    private fun applySecret(prefs: MutablePreferences, key: Preferences.Key<String>, edit: SecretEdit) {
        when (edit) {
            SecretEdit.Keep -> Unit
            SecretEdit.Forget -> prefs.remove(key)
            is SecretEdit.Replace ->
                if (edit.value.isEmpty()) prefs.remove(key) else prefs[key] = cipher.encrypt(edit.value)
        }
    }

    private fun applyKey(prefs: MutablePreferences, hostId: String, edit: KeyEdit) {
        when (edit) {
            KeyEdit.Keep -> Unit
            KeyEdit.Forget -> {
                prefs.remove(keyKey(hostId))
                prefs.remove(keyLabelKey(hostId))
                prefs.remove(keyTypeKey(hostId))
            }
            is KeyEdit.Replace -> if (edit.bytes.isEmpty()) {
                applyKey(prefs, hostId, KeyEdit.Forget)
            } else {
                prefs[keyKey(hostId)] = cipher.encrypt(Base64.encodeToString(edit.bytes, Base64.NO_WRAP))
                prefs[keyLabelKey(hostId)] = edit.label.ifBlank { DEFAULT_KEY_LABEL }
                edit.type?.let { prefs[keyTypeKey(hostId)] = it } ?: prefs.remove(keyTypeKey(hostId))
            }
        }
    }

    private companion object {
        // Distinct prefixes, none a prefix of another, so splitting a key name back into
        // (field, hostId) is unambiguous. `key_` and `key_label_` would not have been: a host called
        // `label_x` and the label of a host called `x` produce the same preference name.
        const val PASSWORD = "secret_password_"
        const val KEY = "secret_key_"
        const val PASSPHRASE = "secret_passphrase_"
        const val KEY_LABEL = "meta_keylabel_"
        const val KEY_TYPE = "meta_keytype_"
        const val DEFAULT_KEY_LABEL = "Private key"

        val PREFIXES = listOf(PASSWORD, KEY, PASSPHRASE, KEY_LABEL, KEY_TYPE)

        fun passwordKey(hostId: String) = stringPreferencesKey("$PASSWORD$hostId")
        fun keyKey(hostId: String) = stringPreferencesKey("$KEY$hostId")
        fun passphraseKey(hostId: String) = stringPreferencesKey("$PASSPHRASE$hostId")
        fun keyLabelKey(hostId: String) = stringPreferencesKey("$KEY_LABEL$hostId")
        fun keyTypeKey(hostId: String) = stringPreferencesKey("$KEY_TYPE$hostId")

        fun String.isCredentialKey() = PREFIXES.any { startsWith(it) }

        /**
         * Folds the raw preferences into per-host metadata.
         *
         * The key secret decides whether a host "has a key"; the label and type are only reported
         * alongside one. A label without its secret is what a half-finished restore or a hand-edited
         * file looks like, and reporting it would put a key on the screen that no connection could
         * use. A secret without a label gets a generic one, so the invariant the UI relies on —
         * non-null label means a usable key — holds from either direction.
         */
        fun summarise(prefs: Preferences): Map<String, StoredCredentials> {
            val passwords = mutableSetOf<String>()
            val keys = mutableSetOf<String>()
            val passphrases = mutableSetOf<String>()
            val labels = mutableMapOf<String, String>()
            val types = mutableMapOf<String, String>()
            prefs.asMap().forEach { (key, value) ->
                val name = key.name
                val text = value as? String ?: return@forEach
                if (text.isEmpty()) return@forEach
                when {
                    name.startsWith(PASSWORD) -> passwords += name.removePrefix(PASSWORD)
                    name.startsWith(PASSPHRASE) -> passphrases += name.removePrefix(PASSPHRASE)
                    name.startsWith(KEY) -> keys += name.removePrefix(KEY)
                    name.startsWith(KEY_LABEL) -> labels[name.removePrefix(KEY_LABEL)] = text
                    name.startsWith(KEY_TYPE) -> types[name.removePrefix(KEY_TYPE)] = text
                }
            }
            return (passwords + keys)
                .filter(String::isNotEmpty)
                .associateWith { hostId ->
                    val hasKey = hostId in keys
                    StoredCredentials(
                        hasPassword = hostId in passwords,
                        keyLabel = if (hasKey) labels[hostId] ?: DEFAULT_KEY_LABEL else null,
                        keyType = if (hasKey) types[hostId] else null,
                        hasPassphrase = hasKey && hostId in passphrases,
                    )
                }
        }
    }
}
