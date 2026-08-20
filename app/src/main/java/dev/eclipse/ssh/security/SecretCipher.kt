package dev.eclipse.ssh.security

/**
 * Authenticated encryption for a single short secret — a password, a passphrase, a private key.
 *
 * [SecureVault] is the only implementation the app ships, and everything that stores a credential
 * depends on this rather than on the vault directly. The reason is not layering for its own sake: the
 * vault's key lives in `AndroidKeyStore`, which does not exist under Robolectric, so any store that
 * named `SecureVault` in its constructor would be unreachable from a JVM test and its behaviour — what
 * it writes, what it removes, what it refuses to hand back — could only be checked on a device. That
 * is why [dev.eclipse.ssh.security.SecureVaultInstrumentedTest] is in `androidTest`, and it is the
 * reason this interface exists: the *storage* rules are testable on the JVM against a stand-in
 * cipher, while the real key handling stays in the one class that is instrumented on a device.
 *
 * Implementations must be safe to call from several threads at once, and [decrypt] must reject
 * anything it did not produce rather than returning plausible garbage.
 */
interface SecretCipher {
    /** Encrypts [value], returning an opaque string safe to persist. */
    fun encrypt(value: String): String

    /** Reverses [encrypt]. Throws if [payload] is malformed, truncated or was tampered with. */
    fun decrypt(payload: String): String
}
