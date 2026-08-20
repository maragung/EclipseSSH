package dev.eclipse.ssh.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureVault @Inject constructor() : SecretCipher {
    /**
     * The vault key, resolved once.
     *
     * Caching it is not just an optimisation. [secretKey] used to look the alias up and, finding
     * nothing, generate a key — with no mutual exclusion. Two threads reaching that on a fresh
     * install (the UI saving a proxy password while [dev.eclipse.ssh.background.SessionRegistry]
     * stores session credentials from the service, which is exactly the pair that runs concurrently)
     * would each generate under the same alias, and the second `generateKey()` replaces the first.
     * Whatever the loser had already encrypted becomes permanently undecryptable — the passwords and
     * private keys the vault exists to protect, lost with no error anywhere.
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(cipher.iv, ciphertext).joinToString(DELIMITER) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    override fun decrypt(payload: String): String {
        val parts = payload.split(DELIMITER)
        require(parts.size == 2) { "Invalid encrypted vault payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    /**
     * Returns the vault key, creating it on first use.
     *
     * Double-checked around a monitor: the fast path is a volatile read, and generation happens once
     * per process no matter how many threads arrive together. The lookup is repeated *inside* the
     * lock because the loser of the race must find the winner's key rather than make its own.
     */
    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Stated explicitly rather than left to the platform default, which is 128 bits
                    // for AES — the alias and the "AES-256-GCM" line on the Settings screen both
                    // promised 256, so the app was describing a key it had not actually asked for.
                    .setKeySize(KEY_SIZE_BITS)
                    // No user-authentication requirement on the key itself: the app gates access with
                    // its own PIN/biometric lock, and the session service has to decrypt credentials
                    // to reconnect in the background, where no user is present to authenticate.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "eclipse_vault_aes256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val DELIMITER = "."
    }
}
