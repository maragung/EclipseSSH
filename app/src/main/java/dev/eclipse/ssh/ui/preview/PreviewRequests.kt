package dev.eclipse.ssh.ui.preview

import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One file waiting to be previewed, as the window that shows it needs it.
 *
 * The pair travels together because neither half is enough: an [FsEntry] alone names a file in a
 * listing the preview window has never seen, and a provider alone cannot say which of its files. It
 * is also, deliberately, the same pair the editor is opened with (`EditorRequest`), so the preview's
 * Edit button hands the workspace the two things it already knows how to take.
 */
data class FilePreviewRequest(
    val entry: FsEntry,
    val provider: FileSystemProvider,
)

/**
 * One archive entry waiting to be previewed, and the way to read it.
 *
 * [readEntry] is a closure over the open archive rather than a path, because the entry's bytes come
 * from the *archive's* byte source — a ranged read of one entry, never the file system — and that
 * source is a live object holding an SFTP channel. Null means the format or the entry cannot serve a
 * ranged read; the window says so rather than showing an empty body, which would read as an empty
 * file.
 */
data class ArchivePreviewRequest(
    val entry: ArchiveEntry,
    val readEntry: (suspend () -> ByteArray?)?,
)

/**
 * The one-shot handoff of a preview to its window, the same shape the editor and the Add-forward form
 * use and for the same reason: neither payload can be parcelled into an intent.
 *
 * A [FileSystemProvider] is a live object — for a remote session, one holding the channel the preview
 * reads through — and an archive entry's reader is a closure over the open archive. Neither survives
 * a `putExtra`, and both activities live in this process, so the intent carries a token instead and
 * the request itself waits here.
 *
 * One shot, like the editor's: [takeFile] and [takeArchiveEntry] remove as they read, so an intent
 * the system re-delivers — a rotation, the recents screen, a crash and relaunch — finds nothing and
 * shows nothing. A preview is a look at a file that was on screen once; reopening one behind the
 * user's back would be reading a file they have already dismissed, and for a remote one it would open
 * a channel to do it.
 */
object PreviewRequests {

    private val files = ConcurrentHashMap<String, FilePreviewRequest>()
    private val archiveEntries = ConcurrentHashMap<String, ArchivePreviewRequest>()

    /** Stores [request] under a fresh token, which the caller puts in the intent. */
    fun putFile(request: FilePreviewRequest): String = token().also { files[it] = request }

    /** Reads and clears the request the token names, or null when it was spent or never existed. */
    fun takeFile(token: String?): FilePreviewRequest? = token?.let(files::remove)

    /** As [putFile], for an archive entry. */
    fun putArchiveEntry(request: ArchivePreviewRequest): String = token().also { archiveEntries[it] = request }

    /** As [takeFile], for an archive entry. */
    fun takeArchiveEntry(token: String?): ArchivePreviewRequest? = token?.let(archiveEntries::remove)

    /**
     * A token nobody can guess, so a hand-written intent cannot name somebody else's file.
     *
     * Nothing sweeps the maps besides the takes, and one entry can be stranded: a request stored and
     * then never launched, because the process died between the two. That is one small object per
     * such death, in a map that only ever holds what the user asked to look at — cheaper than a
     * reaper for a case that needs a crash to happen at all.
     */
    private fun token(): String = UUID.randomUUID().toString()
}
