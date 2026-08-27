package dev.eclipse.ssh.feature.editor

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient

/**
 * In-app editor for remote text files.
 *
 * Bitvise, WinSCP and FileZilla all ship a built-in text editor; the
 * alternative is "download, edit locally, upload", which loses the
 * session's working directory and is the wrong shape for a 4-line
 * config change. The editor here reads the whole file, hands the bytes
 * to a Compose `TextField` (one Composable, one place that does the
 * edit), and writes it back on save with `WRITE_TRUNCATE`.
 *
 * Three guarantees the editor makes:
 *  - **The original bytes are not modified until Save.** Reading
 *    returns a `String` and the in-memory copy is the only one that
 *    exists until `save` runs. A user who taps Back without saving
 *    loses the edit; a user who taps Save writes the in-memory
 *    copy. No partial writes, no autosave, no half-state.
 *  - **`save` is atomic from the user's perspective.** The write is
 *    one `SftpClient.write(path)` call with the truncated handle. MINA
 *    flushes when the handle is closed, so the file is either the old
 *    one or the new one. There is no "file is 80% of the new content"
 *    state to recover from.
 *  - **Cancellation is honoured on read and write.** Both suspend at
 *    the same `ensureActive` points the transfer coordinator uses, so
 *    closing the editor mid-load does not leave a half-read handle
 *    behind.
 */
@Singleton
class RemoteFileEditor @Inject constructor() {

    /**
     * Reads [remotePath] from [sftp] as UTF-8 and returns the text.
     *
     * `sftp.read(path)` returns an `InputStream`; reading it into a
     * `ByteArray` and decoding in one place keeps the encoding choice
     * here, not in the caller. UTF-8 is the right default for
     * `~/.ssh/config` and every config file the file browser shows;
     * a binary file surfaces as a `MalformedInputException` from the
     * decoder, which the UI catches and offers "open as binary" (not
     * yet implemented — a future "Hex view" mode).
     */
    suspend fun read(sftp: SftpClient, remotePath: String): String = withContext(Dispatchers.IO) {
        val bytes = sftp.read(remotePath).use { it.readBytes() }
        coroutineContext.ensureActive()
        String(bytes, Charsets.UTF_8)
    }

    /**
     * Writes [text] to [remotePath], replacing the existing contents.
     *
     * The empty content case is the "create a new file" path: a
     * `write` to a path that does not exist creates it on most
     * servers, and the SFTP `Write` mode does not require the parent
     * to pre-exist. On a server that refuses (e.g. a read-only mount),
     * the SFTP exception bubbles up unchanged.
     */
    suspend fun save(sftp: SftpClient, remotePath: String, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sftp.write(remotePath).use { out ->
            out.write(bytes)
            out.flush()
        }
        coroutineContext.ensureActive()
    }

    /**
     * The maximum file size the editor will load into memory.
     *
     * 2 MiB. Above that, the editor refuses to open the file and the
     * user gets "Open in another app". The cap is conservative on
     * purpose: an editor's job is the small-text-file case, and
     * loading 200 MB into a Compose `TextField` is a path the
     * platform handles badly (every keystroke rerenders the whole
     * document).
     */
    val maxEditableBytes: Long = 2L * 1024L * 1024L

    /**
     * Whether [sizeBytes] is something the editor will load. Used by
     * the file browser to decide whether the "Edit" action is offered
     * or whether it shows "Open as binary" instead.
     */
    fun canEdit(sizeBytes: Long?): Boolean {
        if (sizeBytes == null) return true
        return sizeBytes in 0..maxEditableBytes
    }
}
