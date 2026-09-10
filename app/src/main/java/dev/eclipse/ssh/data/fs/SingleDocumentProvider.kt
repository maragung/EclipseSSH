package dev.eclipse.ssh.data.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One picked document, as a [FileSystemProvider].
 *
 * A transfer's local file — the target a download was saved to, the source an upload was read
 * from — is a single `content://` document URI minted by a picker, not a document inside the
 * folder tree [LocalFileSystemProvider] browses (`fromTreeUri` cannot even resolve it). But the
 * preview sheet and the editor speak [FileSystemProvider], and re-implementing either for one
 * document would fork the whole "one editor serves both backends" shape this interface exists for.
 * So this class is the thinnest provider that can be: one URI, and only the operations a viewer
 * and an editor of a single file actually need — [stat], [read], [write]. Everything else is a
 * browsing or organising verb that has no meaning for one picked document, and says so.
 *
 * [providerId] is "local" on purpose: the document *is* on this device, and the preview sheet keys
 * its own "open this in another app" affordance on that id. A fresh id would silently hide the
 * button for exactly the files it works best for.
 *
 * Every operation dispatches to IO like the tree providers do — the contract forbids blocking the
 * caller's dispatcher, and [DocumentFile] queries are disk IPC on a real device.
 */
class SingleDocumentProvider(
    private val context: Context,
    /** The one document this provider can read. A [path] argument equal to this is the only valid one. */
    val uri: Uri,
) : FileSystemProvider {

    override val providerId: String = "local"

    override val supportsPermissions: Boolean = false

    override suspend fun homePath(): String? = null

    /** A single document has nothing above it this provider can name — SAF owns that question. */
    override suspend fun parentPath(path: String): String? = null

    override suspend fun stat(path: String): FsEntry? = withContext(Dispatchers.IO) {
        document(path)?.takeIf { it.exists() }?.let { document ->
            FsEntry(
                name = document.name ?: uri.lastPathSegment ?: "document",
                path = uri.toString(),
                isDirectory = false,
                size = document.length().takeIf { it >= 0 },
                modifiedEpochMillis = document.lastModified().takeIf { it > 0 },
                permissions = null,
                mimeType = document.type,
            )
        }
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        document(path)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read ${uri.lastPathSegment ?: "this document"}")
    }

    override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) =
        withContext(Dispatchers.IO) {
            val document = document(path)
            if (onlyIfUnmodifiedSince != null) {
                val current = document.lastModified()
                if (current > 0 && current > onlyIfUnmodifiedSince) {
                    throw FsModificationConflictException(path)
                }
            }
            // "wt" for the same reason the tree provider writes with it: "w" is an overlay on some
            // providers and would leave the old tail behind when the new content is shorter.
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(data)
                output.flush()
            } ?: throw IOException("Could not write ${uri.lastPathSegment ?: "this document"}")
        }

    override suspend fun list(path: String): List<FsEntry> {
        document(path)
        throw UnsupportedOperationException("This is one picked document, not a folder to browse")
    }

    override suspend fun createFile(parentPath: String, name: String): FsEntry =
        throw UnsupportedOperationException("A single picked document cannot create files")

    override suspend fun createDirectory(parentPath: String, name: String) {
        throw UnsupportedOperationException("A single picked document cannot create folders")
    }

    override suspend fun rename(path: String, newName: String) {
        throw UnsupportedOperationException("Renaming is not offered for a transfer's file")
    }

    override suspend fun copy(sourcePath: String, targetDirectoryPath: String) {
        throw UnsupportedOperationException("Copying is not offered for a transfer's file")
    }

    override suspend fun move(sourcePath: String, targetDirectoryPath: String) {
        throw UnsupportedOperationException("Moving is not offered for a transfer's file")
    }

    override suspend fun delete(path: String) {
        throw UnsupportedOperationException("Deleting is not offered for a transfer's file")
    }

    override suspend fun setPermissions(path: String, mode: Int) {
        throw UnsupportedOperationException("A single picked document has no permission bits to set")
    }

    override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> {
        throw UnsupportedOperationException("A single picked document cannot be searched")
    }

    /**
     * The document, after checking the caller asked for the one URI this provider holds.
     *
     * The check is cheap and catches the wiring mistakes this class is most exposed to: a caller
     * passing an explorer path (a tree URI or a POSIX path) into a provider that can only ever
     * answer about one document.
     */
    private fun document(path: String): DocumentFile {
        check(path == uri.toString()) {
            "This provider serves only $uri, and was asked for $path"
        }
        return DocumentFile.fromSingleUri(context, uri)
            ?: throw IOException("The document at $uri is no longer available")
    }
}
