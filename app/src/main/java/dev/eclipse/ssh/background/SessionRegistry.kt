package dev.eclipse.ssh.background

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.security.SecureVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The active-session store, holding vault-encrypted credentials for the hosts the service reconnects.
 *
 * Resetting on corruption is the safe direction here as well as the recoverable one: the app forgets
 * which hosts were live and forgets their stored credentials, so the user is asked again. The
 * alternative is a `CorruptionException` from every read — which would take the foreground service's
 * reconnect loop with it and leave no way to clear the file from inside the app.
 */
private val Context.sessionRegistryDataStore by preferencesDataStore(
    name = "session_registry",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class SessionRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: SecureVault,
) {
    private val activeHostsKey = stringSetPreferencesKey("active_host_ids")

    val activeHostIds: Flow<Set<String>> = context.sessionRegistryDataStore.data.map { it[activeHostsKey].orEmpty() }

    /**
     * Records [hostId] as live and stores whatever credentials came with it, vault-encrypted.
     *
     * A null or blank value leaves any stored one alone rather than clearing it. Adopting a live
     * session passes whatever the caller happens to be holding, which for a session the background
     * service dialled is nothing at all, and that is not a reason to forget a credential that works.
     * Forgetting is [unregister]'s job and is asked for explicitly.
     */
    suspend fun register(hostId: String, password: String?, keyBytes: ByteArray? = null, keyPassphrase: String? = null) {
        write { prefs ->
            prefs[activeHostsKey] = prefs[activeHostsKey].orEmpty() + hostId
            password?.takeIf(String::isNotBlank)?.let { prefs[credentialKey(hostId)] = vault.encrypt(it) }
            keyBytes?.takeIf { it.isNotEmpty() }?.let {
                prefs[keyKey(hostId)] = vault.encrypt(Base64.encodeToString(it, Base64.NO_WRAP))
            }
            keyPassphrase?.takeIf(String::isNotBlank)?.let { prefs[passphraseKey(hostId)] = vault.encrypt(it) }
        }
    }

    suspend fun unregister(hostId: String) {
        write { prefs ->
            prefs[activeHostsKey] = prefs[activeHostsKey].orEmpty() - hostId
            prefs.remove(credentialKey(hostId))
            prefs.remove(keyKey(hostId))
            prefs.remove(passphraseKey(hostId))
        }
    }

    suspend fun credential(hostId: String): String? = context.sessionRegistryDataStore.data
        .map { prefs -> prefs[credentialKey(hostId)]?.let { encrypted -> runCatching { vault.decrypt(encrypted) }.getOrNull() } }
        .first()

    suspend fun keyBytes(hostId: String): ByteArray? = context.sessionRegistryDataStore.data
        .map { prefs ->
            prefs[keyKey(hostId)]?.let { encrypted ->
                runCatching { Base64.decode(vault.decrypt(encrypted), Base64.NO_WRAP) }.getOrNull()
            }
        }
        .first()

    suspend fun keyPassphrase(hostId: String): String? = context.sessionRegistryDataStore.data
        .map { prefs -> prefs[passphraseKey(hostId)]?.let { encrypted -> runCatching { vault.decrypt(encrypted) }.getOrNull() } }
        .first()

    suspend fun clear() {
        write { prefs ->
            prefs[activeHostsKey] = emptySet()
            prefs.asMap().keys.filter { it.name.startsWith("credential_") || it.name.startsWith("key_") || it.name.startsWith("passphrase_") }.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                prefs.remove(key as Preferences.Key<Any>)
            }
        }
    }

    /**
     * Every write here goes through this, and none of them can be cancelled halfway.
     *
     * Who calls them decides that. The writer is a connect attempt, and the first thing the reconnect
     * ladder does when a session dies is cancel that attempt - so the cancellation and the write race
     * each other, and the write is the slower of the two: an `edit` reads the file, encrypts, writes a
     * scratch copy, fsyncs and renames, all of it on a background thread, all of it likeliest to be
     * slow exactly when the app is busiest. Losing that race abandoned the write, and the reconnect
     * the cancel existed to start then had nothing to authenticate with: it ended the session with
     * "No more authentication methods available" instead of recovering, which is indistinguishable
     * from a wrong password and was blamed on one for three releases.
     *
     * [unregister] and [clear] need it for the other reason: a half-done forget leaves a credential on
     * disk that the user asked the app to drop.
     *
     * Cancellation is not swallowed, only deferred - the caller observes it as soon as the write
     * returns, having lost nothing.
     */
    private suspend fun write(block: suspend (MutablePreferences) -> Unit) {
        withContext(NonCancellable) { context.sessionRegistryDataStore.edit(block) }
    }

    private fun credentialKey(hostId: String) = stringPreferencesKey("credential_$hostId")
    private fun keyKey(hostId: String) = stringPreferencesKey("key_$hostId")
    private fun passphraseKey(hostId: String) = stringPreferencesKey("passphrase_$hostId")
}
