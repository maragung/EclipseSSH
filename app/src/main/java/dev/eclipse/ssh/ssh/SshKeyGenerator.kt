package dev.eclipse.ssh.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

enum class SshKeyAlgorithm(
    val label: String,
    val opensshName: String,
    private val keyPairAlgorithm: String,
    private val bits: Int,
    val defaultPrivateName: String,
    /**
     * The named-curve OID, for an EC algorithm, or null for one that is not EC.
     *
     * Present because the private key has to be written out as an explicit curve reference rather
     * than left to the provider's own encoding — see [encodeSec1EcPrivatePem]. Doubling as the
     * "is this EC" discriminator keeps the two decisions from drifting apart: an EC entry added
     * without an OID would not compile.
     */
    private val curveOid: String? = null,
) {
    RSA_2048("RSA 2048-bit", "ssh-rsa", "RSA", 2048, "id_rsa"),
    RSA_4096("RSA 4096-bit", "ssh-rsa", "RSA", 4096, "id_rsa"),
    ECDSA_P256("ECDSA P-256", "ecdsa-sha2-nistp256", "EC", 256, "id_ecdsa", SECP256R1_OID),
    ;

    fun generate(comment: String = "eclipse@ssh"): GeneratedKeyPair {
        val keyPair = when (keyPairAlgorithm) {
            "EC" -> KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(SECP256R1_NAME)) }.generateKeyPair()
            else -> KeyPairGenerator.getInstance(keyPairAlgorithm).apply { initialize(bits) }.generateKeyPair()
        }
        val privatePem = when (curveOid) {
            null -> encodePkcs8PrivatePem(keyPair)
            else -> encodeSec1EcPrivatePem(keyPair, curveOid)
        }
        val publicLine = "$opensshName ${encodePublicKey(keyPair)} $comment"
        return GeneratedKeyPair(privatePem = privatePem, publicLine = publicLine, defaultPrivateName = defaultPrivateName)
    }
}

data class GeneratedKeyPair(val privatePem: String, val publicLine: String, val defaultPrivateName: String)

/** `prime256v1` / `secp256r1` / NIST P-256 — the one curve this app generates. */
private const val SECP256R1_OID = "1.2.840.10045.3.1.7"
private const val SECP256R1_NAME = "secp256r1"

/**
 * A PKCS#8 `PRIVATE KEY` PEM, exactly as the provider encoded the key.
 *
 * Correct for RSA, whose PKCS#8 body carries the whole private key with nothing optional about it.
 * Deliberately not used for EC — see [encodeSec1EcPrivatePem].
 */
internal fun encodePkcs8PrivatePem(keyPair: KeyPair): String = encodePem("PRIVATE KEY", keyPair.private.encoded)

/**
 * An RFC 5915 `EC PRIVATE KEY` PEM, assembled here rather than taken from the provider.
 *
 * PKCS#8 was the obvious choice for EC as well, and it was wrong. The `ECPrivateKey` structure
 * inside it carries the public point in an OPTIONAL field, and SunEC leaves that field out: a
 * generated P-256 key encodes to 67 bytes with no point in it at all. Apache MINA SSHD's own EC
 * parser requires it and fails with `StreamCorruptedException: No public key data bytes`, so the
 * app was handing the user a private key that the app itself could not read back — picked in Add
 * Host it reported "Wrong passphrase, or a key format this app cannot read", for a key with no
 * passphrase that had just been generated on the same screen.
 *
 * It looked fine under test only by accident: Bouncy Castle is on the unit-test classpath (pulled
 * in by Robolectric) and MINA hands PEM parsing to Bouncy Castle when it finds it, which recomputes
 * the point from the curve. Nothing in the APK provides Bouncy Castle, so MINA always used its own
 * parser on a device and every generated ECDSA key was unusable there.
 *
 * Writing the point out explicitly takes the provider out of the question — this is the shape
 * `openssl ecparam -genkey` produces, and OpenSSH, OpenSSL and MINA all read it.
 */
internal fun encodeSec1EcPrivatePem(keyPair: KeyPair, curveOid: String): String {
    val private = keyPair.private as? ECPrivateKey ?: error("Not an EC private key: ${keyPair.private.algorithm}")
    val public = keyPair.public as? ECPublicKey ?: error("Not an EC public key: ${keyPair.public.algorithm}")
    val body = derSequence(
        // version: ecPrivkeyVer1
        der(DER_INTEGER, byteArrayOf(1)),
        // privateKey: the scalar, fixed-width for the curve, as RFC 5915 requires
        der(DER_OCTET_STRING, fixedWidth(private.s, ecFieldBytes(public))),
        // parameters [0]: the curve, by OID rather than spelled out
        der(DER_CONTEXT_0, der(DER_OID, encodeObjectIdentifier(curveOid))),
        // publicKey [1]: the point, with the BIT STRING's leading "no unused bits" octet
        der(DER_CONTEXT_1, der(DER_BIT_STRING, byteArrayOf(0) + ecPointBytes(public))),
    )
    return encodePem("EC PRIVATE KEY", body)
}

internal fun encodePublicKey(keyPair: KeyPair): String = when (val public = keyPair.public) {
    is RSAPublicKey -> {
        val out = ByteArrayOutputStream()
        writeString(out, "ssh-rsa")
        writeMpint(out, public.publicExponent)
        writeMpint(out, public.modulus)
        Base64.getEncoder().encodeToString(out.toByteArray())
    }
    is ECPublicKey -> {
        // OpenSSH wire format (RFC 5656): exactly three fields —
        // string algorithm, string curve name, string point Q. The point already
        // contains the X and Y coordinates (0x04 || X || Y); writing them again
        // as mpints would produce a non-standard blob some parsers reject.
        val out = ByteArrayOutputStream()
        writeString(out, "ecdsa-sha2-nistp256")
        writeString(out, "nistp256")
        writeBytes(out, ecPointBytes(public))
        Base64.getEncoder().encodeToString(out.toByteArray())
    }
    else -> error("Unsupported public key type: ${public.javaClass.simpleName}")
}

/** Octets needed for one coordinate on this key's curve: 32 for P-256, 48 for P-384, 66 for P-521. */
private fun ecFieldBytes(public: ECPublicKey): Int = (public.params.curve.field.fieldSize + 7) / 8

/** The uncompressed SEC 1 point encoding, `0x04 || X || Y`, with both coordinates padded to width. */
internal fun ecPointBytes(public: ECPublicKey): ByteArray {
    val width = ecFieldBytes(public)
    return byteArrayOf(4) + fixedWidth(public.w.affineX, width) + fixedWidth(public.w.affineY, width)
}

/**
 * [value] as exactly [width] big-endian octets, zero-padded on the left.
 *
 * The padding is the point. This used to be `arraycopy(x, x.size - width, …)`, which assumed every
 * coordinate occupies the full curve width — true for all but roughly one key in 128, where a
 * coordinate happens to start with a zero byte and `BigInteger.toByteArray` returns something
 * shorter. The negative offset threw `ArrayIndexOutOfBoundsException` out of key generation, so a
 * small share of "Generate key" taps crashed with nothing to distinguish them from the ones that
 * worked. Short values are padded and over-long ones are rejected rather than silently truncated.
 */
internal fun fixedWidth(value: BigInteger, width: Int): ByteArray {
    require(value.signum() >= 0) { "Cannot encode a negative value as $width unsigned octets" }
    // toByteArray() is two's complement, so a value whose top bit is set gains a 0x00 sign octet.
    val raw = value.toByteArray()
    val firstSignificant = raw.indexOfFirst { it != ZERO_BYTE }.takeIf { it >= 0 } ?: raw.size
    val significant = raw.size - firstSignificant
    require(significant <= width) { "Value needs $significant octets, which does not fit in $width" }
    return ByteArray(width).also { out -> raw.copyInto(out, width - significant, firstSignificant, raw.size) }
}

private const val ZERO_BYTE = 0.toByte()

private fun encodePem(label: String, der: ByteArray): String {
    // The MIME encoder does not guarantee a trailing line separator, so trim any
    // and always place the END marker on its own line. A glued marker would make
    // the last base64 line disappear during parsing (OpenSSH and SSHD both
    // truncate the DER, producing "stream too short" failures).
    val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der).trimEnd('\n')
    return "-----BEGIN $label-----\n$base64\n-----END $label-----\n"
}

private const val DER_INTEGER = 0x02
private const val DER_BIT_STRING = 0x03
private const val DER_OCTET_STRING = 0x04
private const val DER_OID = 0x06
private const val DER_SEQUENCE = 0x30
private const val DER_CONTEXT_0 = 0xA0
private const val DER_CONTEXT_1 = 0xA1

private fun derSequence(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    parts.forEach(out::write)
    return der(DER_SEQUENCE, out.toByteArray())
}

/** One DER tag-length-value, definite length, long form once the content passes 127 octets. */
internal fun der(tag: Int, content: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(tag)
    if (content.size < 0x80) {
        out.write(content.size)
    } else {
        // Big-endian length, minimal number of octets, preceded by 0x80 + that count.
        val length = BigInteger.valueOf(content.size.toLong()).toByteArray().dropWhile { it == ZERO_BYTE }
        out.write(0x80 or length.size)
        length.forEach { out.write(it.toInt()) }
    }
    out.write(content)
    return out.toByteArray()
}

/**
 * A dotted-decimal OID as DER content octets.
 *
 * Hand-rolled because the only alternatives are Bouncy Castle (not in the APK) or
 * `sun.security.util.ObjectIdentifier` (not public API, and gone on Android).
 */
internal fun encodeObjectIdentifier(oid: String): ByteArray {
    val arcs = oid.split('.').map { part ->
        requireNotNull(part.toLongOrNull()) { "Not an object identifier: $oid" }
    }
    require(arcs.size >= 2) { "Not an object identifier: $oid" }
    require(arcs[0] in 0L..2L && arcs[1] < 40L) { "Not an object identifier: $oid" }
    val out = ByteArrayOutputStream()
    // The first two arcs share one value; the rest are base-128, high bit set on all but the last.
    writeBase128(out, arcs[0] * 40 + arcs[1])
    arcs.drop(2).forEach { writeBase128(out, it) }
    return out.toByteArray()
}

private fun writeBase128(out: ByteArrayOutputStream, value: Long) {
    val septets = ArrayDeque<Int>()
    var remaining = value
    do {
        septets.addFirst((remaining and 0x7F).toInt())
        remaining = remaining ushr 7
    } while (remaining != 0L)
    septets.forEachIndexed { index, septet ->
        out.write(if (index == septets.size - 1) septet else septet or 0x80)
    }
}

private fun writeString(out: ByteArrayOutputStream, value: String) = writeBytes(out, value.toByteArray(Charsets.UTF_8))

private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
    writeUInt32(out, bytes.size)
    out.write(bytes)
}

private fun writeMpint(out: ByteArrayOutputStream, value: BigInteger) {
    var bytes = value.toByteArray()
    if (bytes.size > 1 && bytes[0] == ZERO_BYTE && bytes[1] >= ZERO_BYTE) {
        bytes = bytes.copyOfRange(1, bytes.size)
    }
    writeUInt32(out, bytes.size)
    out.write(bytes)
}

private fun writeUInt32(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}
