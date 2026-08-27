package dev.eclipse.ssh.feature.integrity

import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * File integrity verification for SFTP transfers.
 *
 * A transferred file's bytes are the only thing the user has at the end of a
 * download, and a transfer that flips a few bytes in flight is the one silent
 * failure mode no UI feedback catches. SHA-256 over the byte stream is the
 * same check the user can run on the remote side (`sha256sum path`) to
 * confirm the two files match, and the only cost is the hashing work itself
 * — the bytes are read once for hashing and once for the SFTP write, and the
 * hash runs on the same thread the bytes already take.
 *
 * The streaming API reads in 64 KiB chunks, the same size SFTP's `read` uses,
 * so the hashing pass does not add a buffer allocation per chunk. The pure
 * [sha256Hex] helper is what the unit tests cover; the streaming
 * [hashStream] is what the transfer coordinator calls.
 */
object FileIntegrity {

    /**
     * Reads [input] to exhaustion and returns the SHA-256 of its bytes as a
     * lowercase hex string.
     *
     * Cooperative cancellation: the loop yields between chunks so a cancelled
     * transfer does not have to read the whole file to discover it. The
     * coroutine context's `ensureActive()` is the same check `InputStream.read`
     * would need, but the explicit `yield()` between chunks is what makes a
     * cancellation land inside the read on a 1 GB file rather than only after
     * the last byte.
     */
    suspend fun hashStream(input: InputStream): String {
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            // yield() so cancellation can land between chunks. A transfer
            // on a 4 GB file reads millions of buffers, and `ensureActive`
            // alone is only checked at suspend points the runtime knows
            // about; the explicit yield makes this loop cancellable in
            // chunks of work that are reasonable for a UI thread to wait
            // out.
            yield()
        }
        return digest.hex()
    }

    /**
     * Hashes [bytes] in one call. Used for the small files (host keys,
     * snippets, vault secrets) where streaming is overkill.
     */
    fun sha256Bytes(bytes: ByteArray): String =
        newSha256().apply { update(bytes) }.hex()

    /**
     * Hashes the UTF-8 bytes of [text]. Convenience wrapper for hashing the
     * known-hosts file or a config blob.
     */
    fun sha256Text(text: String): String =
        sha256Bytes(text.toByteArray(Charsets.UTF_8))

    private const val BUFFER_SIZE = 64 * 1024

    private fun newSha256(): MessageDigest = try {
        MessageDigest.getInstance("SHA-256")
    } catch (e: NoSuchAlgorithmException) {
        // Every Android JVM since API 21 ships SHA-256 in the platform
        // provider. The only way this throws is a stripped provider,
        // which is a "this build is broken" condition, not a user-
        // facing error.
        error("SHA-256 is not available on this platform")
    }

    private fun MessageDigest.hex(): String {
        val bytes = digest()
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            hex.append(HEX_CHARS[v ushr 4])
            hex.append(HEX_CHARS[v and 0x0f])
        }
        return hex.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}

/**
 * Convenience extension: `InputStream.sha256()` is the streaming SHA-256 used
 * by the transfer coordinator.
 */
suspend fun InputStream.sha256(): String = FileIntegrity.hashStream(this)
