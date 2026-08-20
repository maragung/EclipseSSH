package dev.eclipse.ssh.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure PIN hashing used by the in-app lock fallback. The PIN is never stored
 * in plaintext — only a PBKDF2-HMAC-SHA256 hash plus a per-enrollment salt.
 */
object PinHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 60_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(key)
    }

    fun verify(pin: String, salt: ByteArray, expectedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(pin, salt).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
}
