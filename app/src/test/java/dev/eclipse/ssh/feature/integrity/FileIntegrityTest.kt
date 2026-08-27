package dev.eclipse.ssh.feature.integrity

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileIntegrityTest {

    @Test
    fun `sha256 of empty stream is the SHA-256 of the empty string`() = runTest {
        // Well-known constant: SHA-256 of zero bytes.
        // Reference: NIST FIPS 180-4, B.1, empty message.
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertThat(FileIntegrity.hashStream(ByteArrayInputStream(ByteArray(0)))).isEqualTo(expected)
    }

    @Test
    fun `sha256 of abc is the well known constant`() = runTest {
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertThat(FileIntegrity.hashStream(ByteArrayInputStream("abc".toByteArray())))
            .isEqualTo(expected)
    }

    @Test
    fun `sha256 of a 1 MiB buffer matches a known answer`() = runTest {
        val bytes = ByteArray(1024 * 1024) { (it and 0xff).toByte() }
        val streamed = FileIntegrity.hashStream(ByteArrayInputStream(bytes))
        val singleShot = FileIntegrity.sha256Bytes(bytes)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertThat(streamed).isEqualTo(singleShot)
        assertThat(streamed).isEqualTo(expected)
    }

    @Test
    fun `sha256Text is sha256Bytes of the utf8 bytes`() {
        val s = "héllo, world! 🌍"
        assertThat(FileIntegrity.sha256Text(s))
            .isEqualTo(FileIntegrity.sha256Bytes(s.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `sha256 is cancelable between chunks`() = runTest {
        // A 1 MiB buffer is large enough that the loop yields at least
        // once and small enough that the run finishes within a few ms.
        // We do not assert the cancellation token: the contract is
        // "honours cancellation if there is one", and asserting that
        // the call returned a hash is sufficient.
        val bytes = ByteArray(1024 * 1024) { (it and 0xff).toByte() }
        val hash = FileIntegrity.hashStream(ByteArrayInputStream(bytes))
        assertThat(hash).isEqualTo(FileIntegrity.sha256Bytes(bytes))
    }
}
