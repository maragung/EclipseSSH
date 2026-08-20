package dev.eclipse.ssh.data.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class LocalFile(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * Thrown when the folder behind a picked tree URI can no longer be read.
 *
 * A persisted SAF grant is not permanent: the user can revoke it from the app's storage settings, the
 * folder can be deleted, and a removable volume can be unmounted with the grant still on record. In
 * every one of those cases `listFiles()` returns an empty array, which is exactly what an empty folder
 * returns - so the caller has to be able to tell the two apart, or it shows an empty pane forever.
 */
class LocalAccessUnavailableException(uri: Uri) :
    Exception("No read access to $uri")

object LocalFileBrowser {
    /** @throws LocalAccessUnavailableException when the grant for [directoryUri] is gone. */
    fun list(context: Context, directoryUri: Uri): List<LocalFile> {
        // runCatching because fromTreeUri rejects anything that is not a tree URI with
        // IllegalArgumentException - a value left over from an older build, or a single document
        // arriving through a share. To the caller that is the same situation as a revoked grant:
        // this folder cannot be opened. Letting the raw exception out instead produced the generic
        // "cannot read that folder" message, without the one hint that helps (pick it again).
        val dir = runCatching { DocumentFile.fromTreeUri(context, directoryUri) }.getOrNull()
            ?: throw LocalAccessUnavailableException(directoryUri)
        // canRead() is a permission check plus a query for the document, which is what distinguishes
        // "revoked, deleted or unmounted" from "readable and empty".
        if (!dir.canRead()) throw LocalAccessUnavailableException(directoryUri)
        return dir.listFiles().map { file ->
            LocalFile(
                name = file.name ?: "unknown",
                uri = file.uri,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
            )
        }.sortedWith(compareByDescending<LocalFile> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** The folder above [directoryUri], or null when there is none to go up to. */
    fun parent(context: Context, directoryUri: Uri): Uri? =
        runCatching { DocumentFile.fromTreeUri(context, directoryUri)?.parentFile?.uri }.getOrNull()
}
