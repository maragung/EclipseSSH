package dev.eclipse.ssh.ui.archive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.eclipse.ssh.archive.ArchiveExtractor
import dev.eclipse.ssh.archive.SafeArchivePath
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [ArchiveExtractor.Destination] a picked SAF folder becomes.
 *
 * Two SAF realities shape this bridge, and both are handled here rather than pushed into the
 * engine: [DocumentFile.createFile] is not idempotent (a second extract of the same name creates
 * `name (1)`-style duplicates on most providers, so an existing file is deleted first - extract
 * means "make it so", not "make it so twice"), and a folder chain is created one segment at a
 * time because SAF exposes no mkdir -p.
 *
 * The paths arrive already vetted by [SafeArchivePath] - relative, no `..` - so walking them
 * segment by segment cannot escape [root]; the refusal logic stays in the engine, where it is
 * tested, and this class only translates.
 */
class SafArchiveDestination(
    context: Context,
    /** The tree URI the SAF folder picker returned, with its persistable grant already taken. */
    rootUri: Uri,
) : ArchiveExtractor.Destination {

    private val appContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(appContext, rootUri)
        ?: throw IllegalArgumentException("The picked folder cannot be opened")

    override suspend fun ensureFolder(path: String): Boolean = withContext(Dispatchers.IO) {
        ensureFolderInternal(path) != null
    }

    override suspend fun openFile(path: String): OutputStream? = withContext(Dispatchers.IO) {
        val name = path.substringAfterLast('/')
        val parent = ensureFolderInternal(path.substringBeforeLast('/', "")) ?: return@withContext null        // Replace, not duplicate: a second extract of the same archive (or an extract over a
        // partial one) would otherwise litter the destination with "name (1)" copies on the
        // providers that auto-uniquify. The file being replaced is one this extract is about to
        // (re)write - not the user's data, which is why deleting it is in-bounds here.
        val existing = parent.findFile(name)
        if (existing != null && !existing.isDirectory) {
            if (!existing.delete()) return@withContext null
        }
        val created = parent.createFile(mimeTypeFor(name), name) ?: return@withContext null
        // The extension dance from LocalFileSystemProvider.createFile: providers may append an
        // extension for the MIME they were given, which changes the name the user asked for.
        if (created.name != null && created.name != name) {
            created.renameTo(name)
        }
        appContext.contentResolver.openOutputStream(created.uri, "wt")
    }

    private fun ensureFolderInternal(path: String): DocumentFile? {
        var folder = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            val existing = folder.findFile(segment)
            folder = when {
                existing == null -> folder.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                // A file where a folder is needed: the archive says a/b is a folder and a file
                // named b sits in the way. Refused rather than deleted - that file is the user's,
                // and an extract must never delete something it was not asked to extract.
                else -> return null
            }
        }
        return folder
    }

    private fun mimeTypeFor(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
