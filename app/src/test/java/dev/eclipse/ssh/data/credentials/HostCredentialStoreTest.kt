package dev.eclipse.ssh.data.credentials

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.security.SecretCipher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules that decide what a saved credential *is*.
 *
 * Robolectric only for `android.util.Base64`, which the store uses to make key bytes storable as a
 * string. Everything else here is plain JVM: the [DataStore] is built over a [TemporaryFolder] file
 * rather than through a `Context.preferencesDataStore` delegate, which is exactly why the store takes
 * one as a constructor parameter — see its KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostCredentialStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `nothing is stored for an unknown host`() = withStore { store, _ ->
        assertThat(store.stored("nobody")).isEqualTo(StoredCredentials())
        assertThat(store.password("nobody")).isNull()
        assertThat(store.keyBytes("nobody")).isNull()
        assertThat(store.passphrase("nobody")).isNull()
        assertThat(store.credentials.first()).isEmpty()
    }

    @Test
    fun `a saved password round-trips and is reported without being exposed`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("s3cret")))

        assertThat(store.password(HOST)).isEqualTo("s3cret")
        val stored = store.stored(HOST)
        assertThat(stored.hasPassword).isTrue()
        assertThat(stored.hasKey).isFalse()
        // The metadata the UI sees must not contain the secret in any field.
        assertThat(stored.toString()).doesNotContain("s3cret")
    }

    @Test
    fun `secrets are not written to disk in the clear`() = withStore { store, file ->
        store.apply(
            HOST,
            HostCredentialUpdate(
                password = SecretEdit.Replace("plaintext-password"),
                key = KeyEdit.Replace("PRIVATE-KEY-BYTES".toByteArray(), "id_ed25519", "Ed25519"),
                passphrase = SecretEdit.Replace("plaintext-passphrase"),
            ),
        )

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertThat(raw).doesNotContain("plaintext-password")
        assertThat(raw).doesNotContain("plaintext-passphrase")
        assertThat(raw).doesNotContain("PRIVATE-KEY-BYTES")
        // The label is stored in the clear on purpose: it is the file name the user picked.
        assertThat(raw).contains("id_ed25519")
    }

    @Test
    fun `Keep leaves every other field alone`() = withStore { store, _ ->
        store.apply(
            HOST,
            HostCredentialUpdate(
                password = SecretEdit.Replace("pw"),
                key = KeyEdit.Replace(KEY_BYTES, "id_rsa", "RSA 2048"),
                passphrase = SecretEdit.Replace("pp"),
            ),
        )

        // The shape of "user edited the port and pressed Save".
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("pw2")))

        assertThat(store.password(HOST)).isEqualTo("pw2")
        assertThat(store.keyBytes(HOST)).isEqualTo(KEY_BYTES)
        assertThat(store.passphrase(HOST)).isEqualTo("pp")
    }

    @Test
    fun `an all-Keep update is a no-op and does not touch the file`() = withStore { store, file ->
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("pw")))
        val before = file.readBytes()

        store.apply(HOST, HostCredentialUpdate())

        assertThat(file.readBytes()).isEqualTo(before)
        assertThat(store.password(HOST)).isEqualTo("pw")
    }

    @Test
    fun `Forget removes only the field it names`() = withStore { store, _ ->
        store.apply(
            HOST,
            HostCredentialUpdate(
                password = SecretEdit.Replace("pw"),
                key = KeyEdit.Replace(KEY_BYTES, "id_rsa", "RSA 2048"),
            ),
        )

        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Forget))

        assertThat(store.password(HOST)).isNull()
        assertThat(store.stored(HOST).hasPassword).isFalse()
        assertThat(store.keyBytes(HOST)).isEqualTo(KEY_BYTES)
        assertThat(store.stored(HOST).keyLabel).isEqualTo("id_rsa")
    }

    @Test
    fun `an empty Replace means Forget`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("pw")))

        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("")))

        assertThat(store.password(HOST)).isNull()
    }

    @Test
    fun `an empty key Replace means Forget and clears the label`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "id_rsa", "RSA 2048")))

        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Replace(ByteArray(0), "id_rsa", "RSA 2048")))

        assertThat(store.stored(HOST)).isEqualTo(StoredCredentials())
        assertThat(store.keyBytes(HOST)).isNull()
    }

    @Test
    fun `forgetting a key forgets its passphrase in the same write`() = withStore { store, _ ->
        store.apply(
            HOST,
            HostCredentialUpdate(
                key = KeyEdit.Replace(KEY_BYTES, "id_rsa", "RSA 2048"),
                passphrase = SecretEdit.Replace("pp"),
            ),
        )
        assertThat(store.passphrase(HOST)).isEqualTo("pp")

        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Forget))

        // A passphrase with no key left to unlock is a secret at rest that nothing can ever use.
        assertThat(store.passphrase(HOST)).isNull()
        assertThat(store.stored(HOST).hasPassphrase).isFalse()
    }

    @Test
    fun `a passphrase offered with no key at all is dropped`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(passphrase = SecretEdit.Replace("orphan")))

        assertThat(store.passphrase(HOST)).isNull()
        assertThat(store.credentials.first()).isEmpty()
    }

    @Test
    fun `replacing a key keeps a passphrase written in the same update`() = withStore { store, _ ->
        store.apply(
            HOST,
            HostCredentialUpdate(
                key = KeyEdit.Replace(KEY_BYTES, "id_ed25519", "Ed25519"),
                passphrase = SecretEdit.Replace("pp"),
            ),
        )

        assertThat(store.passphrase(HOST)).isEqualTo("pp")
        assertThat(store.stored(HOST)).isEqualTo(
            StoredCredentials(keyLabel = "id_ed25519", keyType = "Ed25519", hasPassphrase = true),
        )
    }

    @Test
    fun `a key with no detected type is stored without one`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "id_weird", null)))

        assertThat(store.stored(HOST).keyType).isNull()
        assertThat(store.stored(HOST).keyLabel).isEqualTo("id_weird")
    }

    @Test
    fun `replacing a typed key with an untyped one clears the stale type`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "a", "RSA 2048")))

        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "b", null)))

        // Reporting "RSA 2048" beside a key that is no longer the RSA one would be a lie the user
        // cannot check.
        assertThat(store.stored(HOST).keyType).isNull()
        assertThat(store.stored(HOST).keyLabel).isEqualTo("b")
    }

    @Test
    fun `a blank label falls back to a generic one`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "   ", null)))

        // Non-null label is the invariant the UI relies on to mean "a usable key is stored".
        assertThat(store.stored(HOST).keyLabel).isEqualTo("Private key")
    }

    @Test
    fun `credentials are kept apart per host`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("one")))
        store.apply(OTHER, HostCredentialUpdate(password = SecretEdit.Replace("two")))

        assertThat(store.password(HOST)).isEqualTo("one")
        assertThat(store.password(OTHER)).isEqualTo("two")
        assertThat(store.credentials.first().keys).containsExactly(HOST, OTHER)
    }

    @Test
    fun `host ids that share a prefix do not collide`() = withStore { store, _ ->
        // The reason the preference prefixes are `secret_key_` and `meta_keylabel_` rather than `key_`
        // and `key_label_`: with the shorter pair, the label of host "x" and the key of host
        // "label_x" are the same preference name.
        store.apply("x", HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "x-key", "RSA 2048")))
        store.apply("label_x", HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "label-key", "Ed25519")))

        assertThat(store.stored("x").keyLabel).isEqualTo("x-key")
        assertThat(store.stored("label_x").keyLabel).isEqualTo("label-key")
    }

    @Test
    fun `forget removes everything for one host and nothing for another`() = withStore { store, _ ->
        store.apply(
            HOST,
            HostCredentialUpdate(
                password = SecretEdit.Replace("pw"),
                key = KeyEdit.Replace(KEY_BYTES, "id_rsa", "RSA 2048"),
                passphrase = SecretEdit.Replace("pp"),
            ),
        )
        store.apply(OTHER, HostCredentialUpdate(password = SecretEdit.Replace("keep me")))

        store.forget(HOST)

        assertThat(store.credentials.first().keys).containsExactly(OTHER)
        assertThat(store.password(HOST)).isNull()
        assertThat(store.keyBytes(HOST)).isNull()
        assertThat(store.passphrase(HOST)).isNull()
        assertThat(store.password(OTHER)).isEqualTo("keep me")
    }

    @Test
    fun `forgetAll clears every host`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("pw")))
        store.apply(OTHER, HostCredentialUpdate(key = KeyEdit.Replace(KEY_BYTES, "id_rsa", "RSA 2048")))

        store.forgetAll()

        assertThat(store.credentials.first()).isEmpty()
        assertThat(store.keyBytes(OTHER)).isNull()
    }

    @Test
    fun `forgetAll leaves unrelated preferences alone`() = withStoreAndPrefs { store, _, dataStore ->
        val unrelated = stringPreferencesKey("some_other_feature")
        dataStore.edit { it[unrelated] = "untouched" }
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("pw")))

        store.forgetAll()

        assertThat(dataStore.data.first()[unrelated]).isEqualTo("untouched")
    }

    @Test
    fun `an undecryptable secret reads as absent rather than throwing`() = withStoreAndPrefs(
        cipher = FailingCipher(),
    ) { store, _, dataStore ->
        // What a backup restored onto another device looks like: the payload is intact, the vault key
        // that produced it is gone. Asking the user again is recoverable; crashing the connect is not.
        dataStore.edit { it[stringPreferencesKey("secret_password_$HOST")] = "garbage" }

        assertThat(store.password(HOST)).isNull()
        // The metadata still reports it, because the field is there — the UI offers to replace it.
        assertThat(store.stored(HOST).hasPassword).isTrue()
    }

    @Test
    fun `key bytes that are not valid base64 read as absent`() = withStoreAndPrefs { store, _, dataStore ->
        dataStore.edit {
            it[stringPreferencesKey("secret_key_$HOST")] = RecordingCipher().encrypt("not %% base64")
            it[stringPreferencesKey("meta_keylabel_$HOST")] = "id_rsa"
        }

        assertThat(store.keyBytes(HOST)).isNull()
    }

    @Test
    fun `a label with no key secret is not reported as a key`() = withStoreAndPrefs { store, _, dataStore ->
        // A hand-edited file, or a restore that stopped half way.
        dataStore.edit { it[stringPreferencesKey("meta_keylabel_$HOST")] = "id_rsa" }

        assertThat(store.credentials.first()).isEmpty()
    }

    @Test
    fun `a key secret with no label still counts as a key`() = withStoreAndPrefs { store, _, dataStore ->
        dataStore.edit {
            it[stringPreferencesKey("secret_key_$HOST")] =
                RecordingCipher().encrypt(android.util.Base64.encodeToString(KEY_BYTES, android.util.Base64.NO_WRAP))
        }

        assertThat(store.stored(HOST).hasKey).isTrue()
        assertThat(store.stored(HOST).keyLabel).isEqualTo("Private key")
    }

    @Test
    fun `an empty stored value is ignored`() = withStoreAndPrefs { store, _, dataStore ->
        dataStore.edit { it[stringPreferencesKey("secret_password_$HOST")] = "" }

        assertThat(store.credentials.first()).isEmpty()
        assertThat(store.password(HOST)).isNull()
    }

    @Test
    fun `a blank host id is refused`() = withStore { store, _ ->
        val thrown = runCatching {
            store.apply("  ", HostCredentialUpdate(password = SecretEdit.Replace("pw")))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.credentials.first()).isEmpty()
    }

    @Test
    fun `Replace does not print its secret`() {
        assertThat(SecretEdit.Replace("hunter2").toString()).doesNotContain("hunter2")
    }

    // ---------------------------------------------------------------- the RDP credential

    @Test
    fun `a saved RDP credential round-trips and stays out of the SSH metadata`() = withStore { store, _ ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(
                username = SecretEdit.Replace("administrator"),
                domain = SecretEdit.Replace("CORP"),
                password = SecretEdit.Replace("rdp-secret"),
            ),
        )

        val rdp = store.rdpCredentials(HOST)
        assertThat(rdp?.username).isEqualTo("administrator")
        assertThat(rdp?.domain).isEqualTo("CORP")
        assertThat(rdp?.password).isEqualTo("rdp-secret")
        // The SSH form's view of the host is untouched: an RDP credential is not an SSH password,
        // and reporting it there would put "Password" on a host whose terminal login asks for one.
        assertThat(store.stored(HOST)).isEqualTo(StoredCredentials())
        assertThat(store.credentials.first()).isEmpty()
    }

    @Test
    fun `an RDP credential without a domain round-trips with a null one`() = withStore { store, _ ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(
                username = SecretEdit.Replace("administrator"),
                domain = SecretEdit.Replace(""),
                password = SecretEdit.Replace("rdp-secret"),
            ),
        )

        assertThat(store.rdpCredentials(HOST)?.domain).isNull()
    }

    @Test
    fun `RDP secrets are not written to disk in the clear`() = withStore { store, file ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(
                username = SecretEdit.Replace("plaintext-user"),
                domain = SecretEdit.Replace("plaintext-domain"),
                password = SecretEdit.Replace("plaintext-password"),
            ),
        )

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertThat(raw).doesNotContain("plaintext-user")
        assertThat(raw).doesNotContain("plaintext-domain")
        assertThat(raw).doesNotContain("plaintext-password")
    }

    @Test
    fun `Keep leaves the other RDP fields alone`() = withStore { store, _ ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(
                username = SecretEdit.Replace("administrator"),
                domain = SecretEdit.Replace("CORP"),
                password = SecretEdit.Replace("old"),
            ),
        )

        // The shape of "the viewer's NLA prompt replaced the password and nothing else".
        store.applyRdp(HOST, RdpCredentialUpdate(password = SecretEdit.Replace("new")))

        val rdp = store.rdpCredentials(HOST)
        assertThat(rdp?.username).isEqualTo("administrator")
        assertThat(rdp?.domain).isEqualTo("CORP")
        assertThat(rdp?.password).isEqualTo("new")
    }

    @Test
    fun `forgetting the RDP password forgets the whole credential`() = withStore { store, _ ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(
                username = SecretEdit.Replace("administrator"),
                domain = SecretEdit.Replace("CORP"),
                password = SecretEdit.Replace("rdp-secret"),
            ),
        )

        store.applyRdp(HOST, RdpCredentialUpdate(password = SecretEdit.Forget))

        // A username with no password to authenticate it is a secret at rest that nothing can use.
        assertThat(store.rdpCredentials(HOST)).isNull()
    }

    @Test
    fun `a password offered with no username at all is dropped`() = withStore { store, _ ->
        store.applyRdp(HOST, RdpCredentialUpdate(password = SecretEdit.Replace("orphan")))

        assertThat(store.rdpCredentials(HOST)).isNull()
    }

    @Test
    fun `forgetRdp leaves the SSH credentials alone`() = withStore { store, _ ->
        store.apply(HOST, HostCredentialUpdate(password = SecretEdit.Replace("pw")))
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(username = SecretEdit.Replace("u"), password = SecretEdit.Replace("rdp-secret")),
        )

        store.forgetRdp(HOST)

        assertThat(store.rdpCredentials(HOST)).isNull()
        assertThat(store.password(HOST)).isEqualTo("pw")
    }

    @Test
    fun `forget removes the RDP credential with everything else`() = withStore { store, _ ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(
                username = SecretEdit.Replace("administrator"),
                domain = SecretEdit.Replace("CORP"),
                password = SecretEdit.Replace("rdp-secret"),
            ),
        )
        store.apply(OTHER, HostCredentialUpdate(password = SecretEdit.Replace("keep me")))

        store.forget(HOST)

        assertThat(store.rdpCredentials(HOST)).isNull()
        assertThat(store.password(OTHER)).isEqualTo("keep me")
    }

    @Test
    fun `forgetAll clears the RDP credential too`() = withStore { store, _ ->
        store.applyRdp(
            HOST,
            RdpCredentialUpdate(username = SecretEdit.Replace("administrator"), password = SecretEdit.Replace("rdp-secret")),
        )

        store.forgetAll()

        assertThat(store.rdpCredentials(HOST)).isNull()
    }

    @Test
    fun `an undecryptable RDP credential reads as absent rather than throwing`() = withStoreAndPrefs(
        cipher = FailingCipher(),
    ) { store, _, dataStore ->
        // The same restored-onto-another-device case as the SSH secret: the payload is intact, the
        // vault key that produced it is gone, and the fallback is to ask the user again.
        dataStore.edit { it[stringPreferencesKey("secret_rdp_password_$HOST")] = "garbage" }

        assertThat(store.rdpCredentials(HOST)).isNull()
    }

    @Test
    fun `RDP credentials are kept apart per host`() = withStore { store, _ ->
        store.applyRdp(HOST, RdpCredentialUpdate(username = SecretEdit.Replace("one"), password = SecretEdit.Replace("one")))
        store.applyRdp(OTHER, RdpCredentialUpdate(username = SecretEdit.Replace("two"), password = SecretEdit.Replace("two")))

        assertThat(store.rdpCredentials(HOST)?.username).isEqualTo("one")
        assertThat(store.rdpCredentials(OTHER)?.username).isEqualTo("two")
    }

    @Test
    fun `RdpCredentials does not print its password`() {
        assertThat(RdpCredentials("administrator", "CORP", "hunter2").toString()).doesNotContain("hunter2")
    }

    @Test
    fun `describe names what is saved without naming a secret`() {
        assertThat(StoredCredentials().describe()).isEqualTo("Asked at every connect")
        assertThat(StoredCredentials(hasPassword = true).describe()).isEqualTo("Password")
        assertThat(StoredCredentials(keyLabel = "id_rsa").describe()).isEqualTo("id_rsa")
        assertThat(StoredCredentials(keyLabel = "id_rsa", keyType = "RSA 2048").describe())
            .isEqualTo("id_rsa (RSA 2048)")
        assertThat(
            StoredCredentials(hasPassword = true, keyLabel = "id_ed25519", keyType = "Ed25519", hasPassphrase = true)
                .describe(),
        ).isEqualTo("Password + id_ed25519 (Ed25519, passphrase)")
    }

    private fun withStore(
        cipher: SecretCipher = RecordingCipher(),
        body: suspend (HostCredentialStore, File) -> Unit,
    ) = withStoreAndPrefs(cipher) { store, file, _ -> body(store, file) }

    private fun withStoreAndPrefs(
        cipher: SecretCipher = RecordingCipher(),
        body: suspend (HostCredentialStore, File, DataStore<Preferences>) -> Unit,
    ) = runTest {
        val file = File(folder.root, "credentials-${counter++}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
            produceFile = { file },
        )
        body(HostCredentialStore(dataStore, cipher), file, dataStore)
    }

    /**
     * Stands in for [dev.eclipse.ssh.security.SecureVault], which cannot run here: it holds its key in
     * the AndroidKeyStore, and Robolectric has no keystore to hold it in.
     *
     * Reversible but not readable, so `secrets are not written to disk in the clear` is a real check
     * of the store's behaviour rather than of the cipher's strength — that the store hands *everything*
     * secret to the cipher and nothing else, which is the property this class is responsible for.
     */
    private class RecordingCipher : SecretCipher {
        override fun encrypt(value: String): String =
            "enc:" + android.util.Base64.encodeToString(
                value.toByteArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
                android.util.Base64.NO_WRAP,
            )

        override fun decrypt(payload: String): String {
            require(payload.startsWith("enc:")) { "Not a payload from this cipher" }
            val raw = android.util.Base64.decode(payload.removePrefix("enc:"), android.util.Base64.NO_WRAP)
            return String(raw.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
        }
    }

    /** A vault whose key is gone: encryption still works, nothing already stored can be read back. */
    private class FailingCipher : SecretCipher {
        override fun encrypt(value: String): String = "enc:$value"
        override fun decrypt(payload: String): String = error("Key not available")
    }

    private companion object {
        const val HOST = "host-1"
        const val OTHER = "host-2"
        val KEY_BYTES = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()
        var counter = 0
    }
}
