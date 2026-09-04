package dev.eclipse.ssh.data.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.data.saf.LocalAccessUnavailableException
import dev.eclipse.ssh.data.settings.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The device's own storage, behind the Storage Access Framework.
 *
 * Every [path] this provider hands out is a `content://` document URI that SAF itself minted — from
 * `DocumentFile.listFiles()`, `createFile`, or `createDirectory` — and the provider gives it back to
 * SAF verbatim. There is no path arithmetic anywhere in this class: a document URI's slashes are URL
 * structure, not directory boundaries, so "the parent of a URI" is a question only SAF's own
 * `parentFile` can answer and "join a URI and a name" is not a question at all. That is why the
 * [FileSystemProvider] operations that place things all take a parent *path* plus a *name*.
 *
 * What SAF cannot know, this class reports as null: permissions (a tree grant has no POSIX mode),
 * and a modification time before the provider started reporting one (`lastModified` answers 0, which
 * means "unknown" and is passed on as exactly that). A directory's `length()` is a documented lie —
 * it returns 0 rather than a recursive size — so directories report a null size instead of a wrong one.
 */
@Singleton
class LocalFileSystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : FileSystemProvider {

    override val providerId: String = "local"

    /** SAF has no permission bits to report or set; the UI hides those affordances. */
    override val supportsPermissions: Boolean = false

    /**
     * The folder the user last picked as the Local root, or null when they never have.
     *
     * The grant itself is recorded by the OS when the picker returns; what this answers is *which*
     * granted folder to reopen, which is a preference and not a permission, so it comes from the
     * settings the same way the terminal's font size does.
     */
    override suspend fun homePath(): String? =
        settingsRepository.settings.first().localRootUri

    /**
     * Remembers (or forgets, on null) which granted folder the Local browser treats as its root.
     *
     * Called when the user picks a folder in the SAF picker, and never anywhere else: a grant the
     * user has not just given is not the app's to re-select.
     */
    suspend fun setRoot(uri: Uri?) = settingsRepository.setLocalRootUri(uri?.toString())

    /** The folder above [path], or null at the picked root — SAF's answer, never URI arithmetic. */
    override suspend fun parentPath(path: String): String? = withContext(Dispatchers.IO) {
        docOrNull(path)?.parentFile?.uri?.toString()
    }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val directory = docOrNull(path) ?: throw LocalAccessUnavailableException(Uri.parse(path))
        // canRead() is what tells a revoked, deleted or unmounted grant apart from an empty folder;
        // without it the pane would show "nothing here" for a folder that is merely unreadable.
        if (!directory.canRead()) throw LocalAccessUnavailableException(Uri.parse(path))
        directory.listFiles().map { it.toFsEntry() }
            .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): FsEntry? = withContext(Dispatchers.IO) {
        docOrNull(path)?.takeIf { it.exists() }?.toFsEntry()
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val entry = docOrNull(path) ?: throw LocalAccessUnavailableException(Uri.parse(path))
        context.contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read ${entry.name}")
    }

    override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) =
        withContext(Dispatchers.IO) {
            val entry = docOrNull(path) ?: throw LocalAccessUnavailableException(Uri.parse(path))
            if (onlyIfUnmodifiedSince != null) {
                val current = entry.lastModified()
                if (current > 0 && current > onlyIfUnmodifiedSince) {
                    throw FsModificationConflictException(path)
                }
            }
            // "wt" (write + truncate) rather than "w": some providers implement "w" as an overlay
            // that leaves the old tail in place when the new content is shorter, so a rewritten file
            // would end in whatever the previous version happened to end with.
            context.contentResolver.openOutputStream(entry.uri, "wt")?.use { output ->
                output.write(data)
                output.flush()
            } ?: throw IOException("Could not write ${entry.name}")
        }

    override suspend fun createFile(parentPath: String, name: String): FsEntry =
        withContext(Dispatchers.IO) {
            val parent = docOrNull(parentPath) ?: throw LocalAccessUnavailableException(Uri.parse(parentPath))
            // The MIME type is passed so a provider can pick a sensible extension, but the name the
            // user typed is the name they asked for, so if SAF adjusted it the file is renamed back —
            // `notes.md` coming back as `notes.md.txt` is the classic case.
            val created = parent.createFile(mimeTypeForName(name), name)
                ?: throw IOException("Could not create $name in ${parent.name ?: "this folder"}")
            if (created.name != null && created.name != name && created.renameTo(name)) {
                return@withContext created.toFsEntry().let { entry ->
                    // renameTo kept the same document; re-stat so the entry carries what SAF now says.
                    docOrNull(created.uri.toString())?.toFsEntry() ?: entry
                }
            }
            created.toFsEntry()
        }

    override suspend fun createDirectory(parentPath: String, name: String) =
        withContext(Dispatchers.IO) {
            val parent = docOrNull(parentPath) ?: throw LocalAccessUnavailableException(Uri.parse(parentPath))
            // `if` rather than `?:` so the block's last statement is Unit, like the contract says.
            if (parent.createDirectory(name) == null) {
                throw IOException("Could not create folder $name in ${parent.name ?: "this folder"}")
            }
        }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val entry = docOrNull(path) ?: throw LocalAccessUnavailableException(Uri.parse(path))
        if (!entry.renameTo(newName)) {
            throw IOException("Could not rename ${entry.name ?: "this item"} to $newName")
        }
    }

    override suspend fun copy(sourcePath: String, targetDirectoryPath: String) =
        withContext(Dispatchers.IO) {
            val source = docOrNull(sourcePath) ?: throw LocalAccessUnavailableException(Uri.parse(sourcePath))
            val targetDirectory =
                docOrNull(targetDirectoryPath) ?: throw LocalAccessUnavailableException(Uri.parse(targetDirectoryPath))
            if (source.isDirectory) copyTree(source, targetDirectory) else copyDocument(source, targetDirectory)
        }

    override suspend fun move(sourcePath: String, targetDirectoryPath: String) =
        withContext(Dispatchers.IO) {
            val source = docOrNull(sourcePath) ?: throw LocalAccessUnavailableException(Uri.parse(sourcePath))
            val targetDirectory =
                docOrNull(targetDirectoryPath) ?: throw LocalAccessUnavailableException(Uri.parse(targetDirectoryPath))
            // SAF has no rename-across-parents: a move is a copy followed by a delete, even when both
            // ends sit in the same tree. Moving into the folder the entry is already in is a no-op
            // rather than a duplicate, because "move to here" is what the destination picker says
            // even when "here" is where it already is.
            val currentParent = source.parentFile?.uri
            if (currentParent != null && currentParent == targetDirectory.uri) return@withContext
            if (source.isDirectory) copyTree(source, targetDirectory) else copyDocument(source, targetDirectory)
            deleteTree(source)
        }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        deleteTree(docOrNull(path) ?: throw LocalAccessUnavailableException(Uri.parse(path)))
    }

    /**
     * Names under [root] whose name contains [query], bounded by [maxEntries] because the walk holds
     * a queue of folders still to read and SAF's answer about how deep a tree goes is not checked by
     * anything on this side. Depth-capped for the same reason the SFTP walk is: a hostile or broken
     * provider can report a folder that contains itself.
     */
    override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> =
        withContext(Dispatchers.IO) {
            val results = ArrayList<FsEntry>()
            val pending = ArrayDeque<Pair<DocumentFile, Int>>()
            pending.add((docOrNull(root) ?: throw LocalAccessUnavailableException(Uri.parse(root))) to 0)
            while (pending.isNotEmpty() && results.size < maxEntries) {
                val (directory, depth) = pending.removeFirst()
                if (depth > MAX_SEARCH_DEPTH) continue
                // One unreadable folder does not end the search; the readable rest still matters.
                val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (results.size >= maxEntries) break
                    if ((child.name ?: "").contains(query, ignoreCase = true)) {
                        results.add(child.toFsEntry())
                    }
                    if (child.isDirectory) pending.add(child to depth + 1)
                }
            }
            results
        }

    /** SAF has no permission bits; the UI never offers the action for this provider. */
    override suspend fun setPermissions(path: String, mode: Int) {
        throw UnsupportedOperationException("The device's own storage has no permission bits to set")
    }

    /**
     * The document at [path], or null when SAF cannot open it — a value left from an older build, a
     * share intent, anything that is not a tree document URI.
     */
    private fun docOrNull(path: String): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(path)) }.getOrNull()

    private fun DocumentFile.toFsEntry(): FsEntry = FsEntry(
        name = name ?: "unknown",
        path = uri.toString(),
        isDirectory = isDirectory,
        size = if (isDirectory) null else length().takeIf { it >= 0 },
        modifiedEpochMillis = lastModified().takeIf { it > 0 },
        permissions = null,
        mimeType = type,
    )

    /** Copies one file [source] into [targetDirectory] as a new document. */
    private fun copyDocument(source: DocumentFile, targetDirectory: DocumentFile) {
        val created = targetDirectory.createFile(source.type ?: "application/octet-stream", source.name ?: "file")
            ?: throw IOException("Could not copy ${source.name ?: "this file"} into ${targetDirectory.name ?: "the target folder"}")
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(created.uri, "wt")?.use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
            } ?: throw IOException("Could not write the copy of ${source.name ?: "this file"}")
        } ?: throw IOException("Could not read ${source.name ?: "this file"} to copy it")
    }

    /**
     * Copies a directory tree, breadth-first so the queue — not the call stack — carries the
     * recursion. A deep tree overflowing the stack is the failure this shape exists to avoid.
     */
    private fun copyTree(source: DocumentFile, targetDirectory: DocumentFile) {
        val pending = ArrayDeque<Pair<DocumentFile, DocumentFile>>()
        val root = targetDirectory.createDirectory(source.name ?: "folder")
            ?: throw IOException("Could not create the folder ${source.name ?: ""} in ${targetDirectory.name ?: "the target"}")
        pending.add(source to root)
        while (pending.isNotEmpty()) {
            val (from, to) = pending.removeFirst()
            for (child in from.listFiles()) {
                if (child.isDirectory) {
                    val childDir = to.createDirectory(child.name ?: "folder")
                        ?: throw IOException("Could not create the folder ${child.name ?: ""} while copying")
                    pending.add(child to childDir)
                } else {
                    copyDocument(child, to)
                }
            }
        }
    }

    /**
     * Deletes children before parents, because a provider may refuse to delete a folder that still
     * holds any. Iterative post-order via an explicit stack; `delete()` on the leaf documents first.
     */
    private fun deleteTree(target: DocumentFile) {
        val stack = ArrayDeque<DeleteNode>()
        stack.add(DeleteNode(target))
        while (stack.isNotEmpty()) {
            val node = stack.last()
            if (!node.expanded) {
                node.expanded = true
                for (child in node.document.listFiles()) {
                    stack.add(DeleteNode(child))
                }
            } else {
                stack.removeLast()
                if (!node.document.delete()) {
                    throw IOException("Could not delete ${node.document.name ?: "this item"}")
                }
            }
        }
    }

    /** One document still to delete, and whether its children have been queued. */
    private class DeleteNode(val document: DocumentFile) {
        var expanded = false
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * The deepest folder relationship the search will believe. Generous next to any folder a
         * person would search — and the bound that keeps a provider reporting a
         * folder-inside-itself from turning the walk into a loop.
         */
        const val MAX_SEARCH_DEPTH = 64

        /** The obvious extension → MIME pairs a file someone edits in a terminal client will hit. */
        fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "log", "json", "xml", "yml", "yaml", "conf", "ini", "sh", "kt", "java",
            "py", "js", "ts", "css", "html", "csv", "properties", "gradle", "kts", "toml",
                -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
