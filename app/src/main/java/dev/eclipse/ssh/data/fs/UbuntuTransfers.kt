package dev.eclipse.ssh.data.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moving a file between the device's shared storage and the on-device Ubuntu userspace.
 *
 * Deliberately not the SFTP transfer pipeline, and the reason is the whole shape of this class. That
 * pipeline exists to solve problems this copy does not have: a host-keyed queue, WorkManager
 * resumption across a process death, resumable ranged reads, progress notifications — all of it
 * machinery for moving bytes across a network that can drop. Both ends of *this* copy are the same
 * disk, under the same uid, so the honest implementation is a `copyTo` with a progress callback, and
 * routing it through the queue would file a "download" that never left the device, under a host that
 * does not exist, on a screen that exists to report network transfers.
 *
 * What it shares with the SFTP path is where the bytes *come from* and *go to*: a Storage Access
 * Framework document on the device side, and the rootfs on the other. Both directions stream, because
 * a userspace is a place people move large files into — an `apt`-installed toolchain, an image, a
 * dataset — and buffering one in the heap is the failure this class must not have.
 *
 * The guest side goes through [UbuntuFileSystemProvider]'s own stream pair rather than touching a
 * `File` directly, so the one class that knows how a guest path maps onto the sandbox stays the only
 * one that does. A copy is exactly where that matters: writing to `/home/ubuntu/x` must land inside
 * the rootfs even when a component along the way is a symlink.
 */
@Singleton
class UbuntuTransfers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ubuntu: UbuntuFileSystemProvider,
) {

    /**
     * Copies one guest file out to the Storage Access Framework document at [destination].
     *
     * [destination] is a document the platform picker already created — a `CreateDocument` answer —
     * so this writes into it rather than making a second file beside it. [onProgress] is called with
     * a fraction of the total, or with 0 when the source's size cannot be known, in which case the
     * UI should show an indeterminate row rather than a bar at zero.
     */
    suspend fun copyOut(
        guestPath: String,
        name: String,
        destination: Uri,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val total = ubuntu.sizeOf(guestPath)?.takeIf { it > 0 }
        try {
            ubuntu.openInput(guestPath).use { input ->
                context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    copyStreaming(input, output, total, onProgress)
                } ?: throw IOException("Could not open $name on the device")
            }
        } catch (failure: Throwable) {
            // A half-written document left behind by a failed copy is worse than no file: the user
            // would keep a truncated version of what they were trying to save out. Best-effort, since
            // the failure that got us here may be the very provider refusing to delete.
            runCatching { DocumentFile.fromSingleUri(context, destination)?.delete() }
            throw failure
        }
    }

    /**
     * Copies the Storage Access Framework document [source] into the guest directory [guestDir],
     * keeping [name], and answers the guest path it landed at.
     *
     * The name is resolved by the caller rather than from the URI, because a `content://` URI's last
     * segment is an opaque document id: a file the user picked as `notes.md` can arrive as
     * `content://…/document/1234`, and naming the copy after that would put a number in their home
     * directory. [UbuntuTransfers.displayName] is what resolves it.
     */
    suspend fun copyIn(
        source: Uri,
        name: String,
        guestDir: String,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val guestPath = if (guestDir == "/") "/$name" else "${guestDir.trimEnd('/')}/$name"
        val total = runCatching { DocumentFile.fromSingleUri(context, source)?.length() }
            .getOrNull()?.takeIf { it > 0 }
        context.contentResolver.openInputStream(source)?.use { input ->
            // The guest side truncates, which is the right reading of "copy this here": a second
            // copy of a file that changed on the device replaces the first rather than appending.
            ubuntu.openOutput(guestPath).use { output ->
                copyStreaming(input, output, total, onProgress)
            }
        } ?: throw IOException("Could not read $name from the device")
        guestPath
    }

    /**
     * Copies one guest file into the Storage Access Framework *folder* [tree].
     *
     * The sibling of [copyOut] for the case where the user picked a destination folder rather than a
     * destination file — what a selection of several rows needs, since `CreateDocument` can only
     * answer with one document and a folder is the one destination that serves any number of them.
     * The document is created here, so the name is the guest file's own.
     */
    suspend fun copyOutToTree(
        guestPath: String,
        name: String,
        tree: Uri,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(context, tree)
            ?: throw IOException("That folder is no longer available")
        val created = folder.createFile(mimeTypeForName(name), name)
            ?: throw IOException("Could not create $name in that folder")
        copyOut(guestPath, name, created.uri, onProgress)
    }

    /**
     * The name a picked document should have inside the guest.
     *
     * From the document itself — `DocumentFile.name` — rather than from the URI, for the reason
     * [copyIn] gives. Falls back to the URI's last segment where a provider will not answer, and to a
     * fixed name where even that is empty, because a copy has to be called something.
     */
    fun displayName(source: Uri): String =
        runCatching { DocumentFile.fromSingleUri(context, source)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: source.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"

    /**
     * Copies [input] to [output] in buffer-sized reads, reporting the fraction done.
     *
     * [total] is null when the size is unknown — a provider that will not answer, or a pipe — and the
     * progress callback is then driven by bytes moved rather than by a fraction of a total nothing
     * knows. Reported at most once per buffer, which is what keeps a 2 GB copy from spending its time
     * recomposing a progress row.
     */
    private fun copyStreaming(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long?,
        onProgress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var moved = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            moved += read
            onProgress(if (total != null) (moved.toFloat() / total).coerceIn(0f, 1f) else 0f)
        }
        output.flush()
        onProgress(1f)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /**
         * The MIME type a copied-out file is created with.
         *
         * The same table the SAF provider uses, and for the same reason: a document provider given
         * `application/octet-stream` for a `.md` may append its own extension, so the file the user
         * finds is not the one they copied. Only the types a person actually moves out of a userspace
         * are listed; anything else is correctly an opaque stream.
         */
        fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "log", "json", "xml", "yml", "yaml", "conf", "ini", "sh", "kt", "java",
            "py", "js", "ts", "css", "html", "csv", "properties", "gradle", "kts", "toml",
                -> "text/plain"

            else -> "application/octet-stream"
        }
    }
}
