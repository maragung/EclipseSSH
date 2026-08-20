package dev.eclipse.ssh.ssh

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.interfaces.DSAKey
import java.security.interfaces.ECKey
import java.security.interfaces.RSAKey

/**
 * What happened when the app tried to read a private key the user picked.
 *
 * The Add Host form asks before it will store a key, because the alternative is saving a file that
 * cannot authenticate and only discovering it at the next connection attempt — by which point the
 * picker is long closed and the failure is indistinguishable from the server rejecting the account.
 */
sealed interface SshKeyProbe {
    /** The key parsed. [type] is a short label such as `RSA 3072` or `Ed25519`. */
    data class Ready(val type: String) : SshKeyProbe

    /**
     * It looks like a private key but would not parse, and no passphrase was supplied.
     *
     * Kept apart from [Unreadable] because it is the ordinary case — an encrypted key with the
     * passphrase field still empty — and it asks the user for one more field rather than telling them
     * the file is wrong.
     */
    data object PassphraseRequired : SshKeyProbe

    /** The key would not parse. [reason] is safe to show: it never quotes the file's contents. */
    data class Unreadable(val reason: String) : SshKeyProbe
}

/**
 * Reads [bytes] as an SSH private key and reports what it is. The key itself is not returned and
 * nothing is retained, so the caller cannot accidentally hold key material it did not ask for.
 *
 * [displayName] only names the resource in MINA's parser errors; it is never persisted here.
 */
fun probeSshKey(bytes: ByteArray, displayName: String, passphrase: String?): SshKeyProbe {
    if (bytes.isEmpty()) return SshKeyProbe.Unreadable("That file is empty")
    // Checked before parsing, because picking `id_ed25519.pub` instead of `id_ed25519` is the
    // mistake people actually make, and MINA's failure for a public key is the same failure as for
    // an encrypted private one. Reporting "enter the passphrase" for a public key would send the
    // user looking for a passphrase that does not exist.
    if (looksLikePublicKey(bytes)) {
        return SshKeyProbe.Unreadable("That is a public key. Pick the private key file instead")
    }
    val supplied = passphrase?.takeIf { it.isNotBlank() }
    runCatching { SshKeyLoader.load(bytes, displayName, supplied) }
        .getOrNull()
        ?.let { return SshKeyProbe.Ready(sshKeyTypeLabel(it)) }
    // Deliberately not reporting the parser's own message. It is written for a developer reading a
    // stack trace, and on the encrypted-key path it is a decryption error that says nothing about
    // passphrases. Worse, some parsers include a prefix of the input in the message, which would put
    // key material into the UI and into any log that captured it.
    return if (supplied == null) {
        SshKeyProbe.PassphraseRequired
    } else {
        SshKeyProbe.Unreadable("Wrong passphrase, or a key format this app cannot read")
    }
}

/**
 * A short human label for a key pair: algorithm plus size, e.g. `RSA 3072`, `ECDSA 256`, `Ed25519`.
 *
 * Sizes come from the standard JCA key interfaces rather than the algorithm name, because the name
 * alone does not distinguish an RSA-1024 key from an RSA-4096 one and that is the distinction worth
 * showing. Ed25519 has a single size, so stating it would be noise.
 */
fun sshKeyTypeLabel(keyPair: KeyPair): String {
    val public = keyPair.public
    val name = when (public.algorithm.uppercase()) {
        "RSA" -> "RSA"
        "EC", "ECDSA" -> "ECDSA"
        // MINA reports Ed25519 keys as `EdDSA` through net.i2p.crypto, and as `Ed25519` on a JDK
        // with native support. Both mean the same curve to a user.
        "EDDSA", "ED25519" -> "Ed25519"
        "DSA" -> "DSA"
        // An algorithm this build does not know about is still worth naming: a key the app can load
        // is a key the app can use, and inventing "Unknown" would look like a failure.
        else -> public.algorithm
    }
    val bits = when (public) {
        is RSAKey -> public.modulus.bitLength()
        is ECKey -> public.params.order.bitLength()
        is DSAKey -> public.params.p.bitLength()
        else -> null
    }
    return if (bits == null) name else "$name $bits"
}

/**
 * True when [bytes] is an OpenSSH or PEM *public* key.
 *
 * Only positive evidence counts. A DER-encoded private key is legitimate binary with no header to
 * match, so anything that is merely unrecognised falls through to the parser and gets the benefit of
 * the doubt; only a file that positively announces itself as a public key is rejected outright.
 */
private fun looksLikePublicKey(bytes: ByteArray): Boolean {
    // Bounded: a key file's first line is short, and a picked file could be a multi-gigabyte video.
    val head = String(bytes.copyOf(minOf(bytes.size, HEAD_BYTES)), StandardCharsets.US_ASCII).trimStart()
    return PUBLIC_KEY_PREFIXES.any { head.startsWith(it) } || head.startsWith("-----BEGIN PUBLIC KEY-----")
}

private const val HEAD_BYTES = 256

private val PUBLIC_KEY_PREFIXES = listOf("ssh-rsa", "ssh-ed25519", "ssh-dss", "ecdsa-sha2-", "sk-ssh-", "sk-ecdsa-")
