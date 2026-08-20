package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.util.Base64
import org.junit.Test

class SshKeyGeneratorTest {

    @Test
    fun `rsa public key blob matches OpenSSH wire format`() {
        val pair = SshKeyAlgorithm.RSA_2048.generate()
        val blob = Base64.getDecoder().decode(pair.publicLine.substringAfter(' ').substringBefore(' '))
        val fields = readFields(blob)

        assertThat(fields).hasSize(3)
        assertThat(fields[0].toString(Charsets.UTF_8)).isEqualTo("ssh-rsa")
        // e and n must be non-empty mpints.
        assertThat(fields[1]).isNotEmpty()
        assertThat(fields[2]).isNotEmpty()
        // readFields consumed every byte, so the blob has no trailing garbage.
    }

    @Test
    fun `ecdsa public key blob matches RFC 5656 wire format`() {
        val pair = SshKeyAlgorithm.ECDSA_P256.generate()
        val blob = Base64.getDecoder().decode(pair.publicLine.substringAfter(' ').substringBefore(' '))
        val fields = readFields(blob)

        assertThat(fields).hasSize(3)
        assertThat(fields[0].toString(Charsets.UTF_8)).isEqualTo("ecdsa-sha2-nistp256")
        assertThat(fields[1].toString(Charsets.UTF_8)).isEqualTo("nistp256")
        // Uncompressed point: 0x04 || X(32) || Y(32) for P-256.
        assertThat(fields[2].size).isEqualTo(65)
        assertThat(fields[2][0]).isEqualTo(4)
    }

    /**
     * The guarantee that matters: whatever the app writes to the user's storage, the app can read
     * back. ECDSA failed this on every device before the private key started carrying its public
     * point — the file was a valid PKCS#8 key that sshd's own EC parser refused, so the generated
     * key was rejected by the Add Host form that was meant to consume it.
     */
    @Test
    fun `every generated key loads back through the app key loader`() {
        SshKeyAlgorithm.entries.forEach { algorithm ->
            val pair = algorithm.generate()

            val loaded = SshKeyLoader.load(pair.privatePem.toByteArray(Charsets.UTF_8), algorithm.defaultPrivateName)

            assertThat(loaded.private).isNotNull()
            // Same key, not merely a parseable one: the public half has to match the line that was
            // written alongside it, or the user would install an authorized_keys entry for a
            // different key than the one they hold.
            assertThat("${algorithm.opensshName} ${encodePublicKey(loaded)}")
                .isEqualTo(pair.publicLine.substringBeforeLast(' '))
        }
    }

    @Test
    fun `loaded key reports the type the algorithm promises`() {
        val expected = mapOf(
            SshKeyAlgorithm.RSA_2048 to "RSA 2048",
            SshKeyAlgorithm.RSA_4096 to "RSA 4096",
            SshKeyAlgorithm.ECDSA_P256 to "ECDSA 256",
        )
        // Fails the build when an algorithm is added without deciding what the UI calls it.
        assertThat(expected.keys).containsExactlyElementsIn(SshKeyAlgorithm.entries)

        expected.forEach { (algorithm, label) ->
            val loaded = SshKeyLoader.load(algorithm.generate().privatePem.toByteArray(Charsets.UTF_8), "generated")
            assertThat(sshKeyTypeLabel(loaded)).isEqualTo(label)
        }
    }

    @Test
    fun `the probe accepts every generated key without a passphrase`() {
        // The Add Host form's gate. A generated key has no passphrase, so anything other than Ready
        // here is the form refusing a key the app just made.
        SshKeyAlgorithm.entries.forEach { algorithm ->
            val pair = algorithm.generate()

            val probe = probeSshKey(pair.privatePem.toByteArray(Charsets.UTF_8), algorithm.defaultPrivateName, passphrase = null)

            assertThat(probe).isInstanceOf(SshKeyProbe.Ready::class.java)
        }
    }

    /**
     * The comment lands on the public line, and only there.
     *
     * That line is pasted into `authorized_keys`, where the format is `<type> <base64> <comment>`
     * and the comment is how a user tells one of their keys from another. It also has to stay out of
     * the private PEM, which is base64 of DER and has nowhere to put free text.
     */
    @Test
    fun `the comment is appended to the public line only`() {
        SshKeyAlgorithm.entries.forEach { algorithm ->
            val pair = algorithm.generate("someone@example")

            assertThat(pair.publicLine).endsWith(" someone@example")
            assertThat(pair.publicLine.split(' ')).hasSize(3)
            assertThat(pair.publicLine).startsWith("${algorithm.opensshName} ")
            assertThat(pair.privatePem).doesNotContain("someone@example")
        }
    }

    @Test
    fun `rsa private key is exported as a PKCS8 pem`() {
        listOf(SshKeyAlgorithm.RSA_2048, SshKeyAlgorithm.RSA_4096).forEach { algorithm ->
            assertThat(algorithm.generate().privatePem).startsWith("-----BEGIN PRIVATE KEY-----\n")
        }
    }

    @Test
    fun `ecdsa private key is exported as a SEC1 pem carrying its public point`() {
        val pair = SshKeyAlgorithm.ECDSA_P256.generate()
        assertThat(pair.privatePem).startsWith("-----BEGIN EC PRIVATE KEY-----\n")
        assertThat(pair.privatePem).contains("\n-----END EC PRIVATE KEY-----\n")

        // The point has to be in the DER, not merely derivable from it: sshd's EC parser reads it
        // and fails outright when the optional field is missing.
        val der = Base64.getDecoder().decode(pemBody(pair.privatePem))
        val point = Base64.getDecoder().decode(pair.publicLine.substringAfter(' ').substringBefore(' '))
            .let { blob -> readFields(blob)[2] }
        assertThat(der.asList()).containsAtLeastElementsIn(point.asList()).inOrder()
        // parameters [0] names the curve by OID rather than spelling it out.
        assertThat(der.asList()).containsAtLeastElementsIn(PRIME256V1_DER.asList()).inOrder()
    }

    @Test
    fun `every pem ends its base64 on its own line and decodes`() {
        SshKeyAlgorithm.entries.forEach { algorithm ->
            val pem = algorithm.generate().privatePem
            val lines = pem.lineSequence().filter { it.isNotBlank() }.toList()
            // The END marker must sit on its own line so parsers do not drop the final base64 line.
            assertThat(lines.last()).startsWith("-----END ")
            assertThat(Base64.getDecoder().decode(pemBody(pem))).isNotEmpty()
        }
    }

    @Test
    fun `generated point is a full width uncompressed point`() {
        val loaded = SshKeyLoader.load(
            SshKeyAlgorithm.ECDSA_P256.generate().privatePem.toByteArray(Charsets.UTF_8),
            "id_ecdsa",
        )

        val point = ecPointBytes(loaded.public as ECPublicKey)

        assertThat(point).hasLength(65)
        assertThat(point[0]).isEqualTo(4)
    }

    // --- Fixed-width coordinates ---

    @Test
    fun `a short value is padded on the left`() {
        assertThat(fixedWidth(BigInteger.valueOf(0x0102), 4).toList())
            .containsExactly(0.toByte(), 0.toByte(), 1.toByte(), 2.toByte()).inOrder()
    }

    @Test
    fun `the two's complement sign octet is dropped rather than counted`() {
        // 0xFF80 needs a leading 0x00 in two's complement; as a coordinate it is exactly two octets.
        assertThat(fixedWidth(BigInteger.valueOf(0xFF80), 2).toList())
            .containsExactly(0xFF.toByte(), 0x80.toByte()).inOrder()
    }

    @Test
    fun `zero is all zero octets rather than a single one`() {
        assertThat(fixedWidth(BigInteger.ZERO, 3).toList()).containsExactly(0.toByte(), 0.toByte(), 0.toByte())
    }

    @Test
    fun `a value too large for the width is refused rather than truncated`() {
        val error = runCatching { fixedWidth(BigInteger.valueOf(0x10000), 2) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a negative value is refused`() {
        val error = runCatching { fixedWidth(BigInteger.valueOf(-1), 4) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- DER ---

    @Test
    fun `object identifiers are encoded arc by arc`() {
        // prime256v1, whose 10045 arc needs two base-128 octets.
        assertThat(encodeObjectIdentifier("1.2.840.10045.3.1.7").toList())
            .containsExactlyElementsIn(PRIME256V1_CONTENT.toList()).inOrder()
        // rsaEncryption, the textbook case.
        assertThat(encodeObjectIdentifier("1.2.840.113549.1.1.1").toList())
            .containsExactly(
                0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(),
                0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
            ).inOrder()
    }

    @Test
    fun `a malformed object identifier is refused`() {
        listOf("", "1", "1.2.x", "3.2.840", "1.40.1").forEach { candidate ->
            assertThat(runCatching { encodeObjectIdentifier(candidate) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `der lengths switch to long form past 127 octets`() {
        assertThat(der(0x04, ByteArray(127)).take(2).map { it.toInt() and 0xFF }).containsExactly(0x04, 0x7F).inOrder()
        assertThat(der(0x04, ByteArray(128)).take(3).map { it.toInt() and 0xFF }).containsExactly(0x04, 0x81, 0x80).inOrder()
        assertThat(der(0x04, ByteArray(300)).take(4).map { it.toInt() and 0xFF }).containsExactly(0x04, 0x82, 0x01, 0x2C).inOrder()
        // Length prefix aside, the content is untouched.
        assertThat(der(0x04, ByteArray(300)).size).isEqualTo(304)
    }

    private fun pemBody(pem: String): String =
        pem.lineSequence().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")

    /** Reads all length-prefixed SSH wire-format fields. */
    private fun readFields(blob: ByteArray): List<ByteArray> {
        val input = ByteArrayInputStream(blob)
        val fields = mutableListOf<ByteArray>()
        while (input.available() > 0) {
            val length = readInt(input)
            val field = ByteArray(length)
            check(input.read(field) == length) { "Truncated wire-format field" }
            fields += field
        }
        return fields
    }

    private fun readInt(input: ByteArrayInputStream): Int =
        ((input.read() and 0xFF) shl 24) or
            ((input.read() and 0xFF) shl 16) or
            ((input.read() and 0xFF) shl 8) or
            (input.read() and 0xFF)

    private companion object {
        /** The DER content octets of the prime256v1 OID, 1.2.840.10045.3.1.7. */
        val PRIME256V1_CONTENT = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

        /** The same, as a complete OBJECT IDENTIFIER, which is what appears in the key. */
        val PRIME256V1_DER = byteArrayOf(0x06, PRIME256V1_CONTENT.size.toByte()) + PRIME256V1_CONTENT
    }
}
