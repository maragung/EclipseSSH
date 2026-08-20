package dev.eclipse.ssh.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.security.keystore.KeyInfo
import com.google.common.truth.Truth.assertThat
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SecureVault] is the only place host passwords and key passphrases are stored, and it delegates
 * to the AndroidKeyStore — which Robolectric cannot emulate. These have to run on a device.
 */
@RunWith(AndroidJUnit4::class)
class SecureVaultInstrumentedTest {

    private lateinit var vault: SecureVault

    @Before
    fun setUp() {
        vault = SecureVault()
    }

    @Test
    fun encryptedSecretsComeBackUnchanged() {
        val secret = "correct horse battery staple"

        val payload = vault.encrypt(secret)

        assertThat(payload).doesNotContain(secret)
        assertThat(vault.decrypt(payload)).isEqualTo(secret)
    }

    @Test
    fun theKeyLivesInTheKeystoreAndIsNotExportable() {
        vault.encrypt("anything")

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertThat(keyStore.containsAlias("eclipse_vault_aes256")).isTrue()
        // A hardware- or TEE-backed AES key must never hand out its bytes.
        assertThat(keyStore.getKey("eclipse_vault_aes256", null).encoded).isNull()
    }

    @Test
    fun theGeneratedKeyIsActuallyAes256() {
        // KeyGenParameterSpec defaults AES to 128 bits when setKeySize is omitted, so the alias
        // "eclipse_vault_aes256" and the "AES-256-GCM · Android Keystore" line on the Settings
        // screen were both describing a key the app had never asked for. The key is only
        // generated when the alias is absent (existing installs keep working with whatever they
        // already have), so this drops the entry first to assert on a freshly generated one.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("eclipse_vault_aes256") }

        SecureVault().encrypt("force key generation")

        val reloaded = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = reloaded.getKey("eclipse_vault_aes256", null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertThat(key.algorithm).isEqualTo("AES")
        assertThat(info.keySize).isEqualTo(256)
    }

    @Test
    fun aSecondVaultInstanceDecryptsWhatTheFirstWrote() {
        // Each injection site gets its own object; they must share the keystore entry, otherwise
        // saved passwords would stop decrypting after a process restart.
        val payload = vault.encrypt("shared across instances")

        assertThat(SecureVault().decrypt(payload)).isEqualTo("shared across instances")
    }

    @Test
    fun everyEncryptionUsesAFreshInitialisationVector() {
        val first = vault.encrypt("same plaintext")
        val second = vault.encrypt("same plaintext")

        assertThat(first).isNotEqualTo(second)
        assertThat(first.substringBefore('.')).isNotEqualTo(second.substringBefore('.'))
        assertThat(vault.decrypt(first)).isEqualTo("same plaintext")
        assertThat(vault.decrypt(second)).isEqualTo("same plaintext")
    }

    @Test
    fun aTamperedPayloadIsRejectedByTheGcmTag() {
        val payload = vault.encrypt("do not modify")
        val (iv, body) = payload.split('.')
        val flipped = (if (body[0] == 'A') "B" else "A") + body.substring(1)

        assertThrows(Exception::class.java) { vault.decrypt("$iv.$flipped") }
    }

    @Test
    fun aMalformedPayloadFailsWithTheDocumentedError() {
        // A truncated or hand-edited value must not be mistaken for ciphertext.
        listOf("", "no-delimiter", "a.b.c").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { vault.decrypt(bad) }
        }
    }

    @Test
    fun concurrentFirstUseGeneratesExactlyOneKey() {
        // The regression test for a silent data-loss race. `secretKey()` looked the alias up and, on
        // a miss, generated one — with no mutual exclusion. Two threads arriving together on a fresh
        // install (the UI saving a proxy password while SessionRegistry stores session credentials
        // from the service) each generated under the same alias, and the second generateKey()
        // replaced the first. Everything the loser had already written became undecryptable.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("eclipse_vault_aes256") }

        val vault = SecureVault()
        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val payloads = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val workers = (0 until threads).map { index ->
            Thread {
                runCatching {
                    // Released together so the lookup-then-generate window is actually contended.
                    start.await()
                    payloads[index] = vault.encrypt("secret-$index")
                }.onFailure(failures::add)
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }

        assertThat(failures).isEmpty()
        assertThat(payloads).hasSize(threads)
        // The real assertion: every payload still decrypts. If two keys had been generated, the
        // ones written under the replaced key would throw here instead.
        payloads.forEach { (index, payload) ->
            assertThat(SecureVault().decrypt(payload)).isEqualTo("secret-$index")
        }
    }

    @Test
    fun awkwardSecretsSurviveTheRoundTrip() {
        // Passphrases really do contain emoji, newlines and non-Latin text.
        val awkward = listOf(
            "",
            " ",
            "sandi-üñïçø∂é",
            "kata sandi 🔐 rahasia",
            "line one\nline two\ttabbed",
            "x".repeat(8_192),
        )

        awkward.forEach { secret ->
            assertThat(vault.decrypt(vault.encrypt(secret))).isEqualTo(secret)
        }
    }
}
