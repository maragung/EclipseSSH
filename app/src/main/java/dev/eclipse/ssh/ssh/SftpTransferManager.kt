package dev.eclipse.ssh.ssh

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient

@Singleton
class SftpTransferManager @Inject constructor() {
    /**
     * Uploads [source] to [remotePath].
     *
     * The body is inside `withContext(Dispatchers.IO)` because `sftp.write(remotePath)` is a blocking
     * round-trip to the server that opens a remote handle, and as an argument expression it used to
     * be evaluated on whatever dispatcher the caller was on — [copy] only switched to IO once it was
     * already holding the result. Every caller today launches on IO, so this was latent rather than
     * live, but the one that did not would have blocked the main thread on the network.
     *
     * `source.use` even though [copy] closes it as well: that same `sftp.write` throws for an
     * unwritable path, a missing parent directory or a full remote disk, and on that path [copy] is
     * never entered, so the caller's `ContentResolver` descriptor stayed open. Closing twice is a
     * no-op.
     */
    suspend fun upload(
        sftp: SftpClient,
        source: InputStream,
        remotePath: String,
        totalBytes: Long? = null,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        source.use { copy(it, sftp.write(remotePath), totalBytes, onProgress) }
    }

    /**
     * Downloads [remotePath] into [destination].
     *
     * `destination.use` even though [copy] closes it as well, for the same reason [upload] wraps its
     * source: `sftp.read` throws for a file that has been deleted or made unreadable since the
     * transfer was queued, and on that path [copy] is never entered, so the caller's
     * `ContentResolver` descriptor stayed open. One leaked descriptor per failed download, on the
     * path the retry ladder walks most often. Closing twice is a no-op.
     */
    suspend fun download(
        sftp: SftpClient,
        remotePath: String,
        destination: OutputStream,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val total = runCatching { sftp.stat(remotePath).size }.getOrNull()
        destination.use { target -> copy(sftp.read(remotePath), target, total, onProgress) }
    }

    suspend fun resumeDownload(
        sftp: SftpClient,
        remotePath: String,
        destination: OutputStream,
        existingBytes: Long,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val total = runCatching { sftp.stat(remotePath).size }.getOrNull()
        // Outermost, so the destination is released even when the open below throws — which is what
        // happens whenever the remote file was deleted or made unreadable while the transfer sat
        // queued. That path used to leak a descriptor per failed resume.
        destination.use { target ->
            val handle = sftp.open(remotePath, EnumSet.of(SftpClient.OpenMode.Read))
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var offset = existingBytes
                while (true) {
                    coroutineContext.ensureActive()
                    val count = sftp.read(handle, offset, buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    offset += count
                    onProgress(offset, total)
                }
                target.flush()
            } finally {
                handle.close()
            }
        }
    }

    /**
     * Continues an interrupted upload.
     *
     * The offset is taken from the remote file's real size when the server can report it, so
     * a stale [existingBytes] (progress is persisted at most every 200 ms) cannot re-send a
     * region that already arrived. Writes are absolute-offset, so the untouched prefix
     * survives — the handle is opened without Truncate.
     */
    suspend fun resumeUpload(
        sftp: SftpClient,
        source: InputStream,
        remotePath: String,
        existingBytes: Long,
        totalBytes: Long? = null,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val startAt = runCatching { sftp.stat(remotePath).size }.getOrNull()?.takeIf { it >= 0 } ?: existingBytes
        // Outermost for the same reason as in resumeDownload: sftp.open throws for a path that has
        // become unwritable, and the source descriptor has to be released on that path too.
        source.use { input ->
            input.advanceExactly(startAt)
            val handle = sftp.open(remotePath, EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create))
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var offset = startAt
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sftp.write(handle, offset, buffer, 0, count)
                    offset += count
                    onProgress(offset, totalBytes)
                }
            } finally {
                handle.close()
            }
        }
    }

    /**
     * Advances this stream by exactly [bytes], or throws.
     *
     * [InputStream.skip] is the fast path — on a local file descriptor it is one `lseek` — but it is
     * permitted to return short, or 0, *without* being at EOF, and on Android it throws outright for
     * a stream that cannot seek. A resumed upload always reads its source from
     * `ContentResolver.openInputStream`, and a document served by a cloud provider rather than by
     * local storage arrives as a pipe, which is precisely that stream. Reading and discarding is the
     * fallback that works everywhere.
     *
     * The previous version gave up quietly on a short skip and then wrote whatever the stream handed
     * it at the resumed offset, so the file on the server got a hole followed by a duplicated block
     * and the transfer was still reported COMPLETE. Silent corruption of the user's data, on the one
     * code path whose entire purpose is a network that is already misbehaving.
     *
     * Running out of input means the remote file is longer than the source, so the bytes the server
     * is missing do not exist locally and there is nothing honest to continue with. That is raised
     * rather than swallowed: the caller's retry ladder handles it and, if it keeps happening, tells
     * the user — where the old behaviour was to return normally and mark the transfer complete.
     */
    private suspend fun InputStream.advanceExactly(bytes: Long) {
        var remaining = bytes
        // Only allocated when there is something to skip, and only when skip() will not do it.
        var scratch: ByteArray? = null
        while (remaining > 0) {
            coroutineContext.ensureActive()
            // A stream that cannot seek reports it by throwing here; that is not a failure of the
            // transfer, just of the shortcut, so fall through to reading. A genuine read error is
            // still raised by the read below rather than hidden.
            val skipped = runCatching { skip(remaining) }.getOrDefault(0L).coerceIn(0L, remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val buffer = scratch ?: ByteArray(DEFAULT_BUFFER_SIZE).also { scratch = it }
            val count = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) {
                throw IOException(
                    "Cannot resume this upload: the source ends $remaining byte(s) short of the " +
                        "$bytes byte(s) already on the server.",
                )
            }
            remaining -= count
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long?,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        input.use { source ->
            output.use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                // Always from zero: this is the whole-file path. Resuming from an offset is
                // resumeUpload/resumeDownload, which position their own handles and never come
                // through here - an `initialBytes` parameter lived here for that case and no caller
                // could reach it, so a reader could believe this function knew how to resume.
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    copied += count
                    onProgress(copied, totalBytes)
                }
                target.flush()
            }
        }
    }
}
