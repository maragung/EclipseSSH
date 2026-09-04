package dev.eclipse.ssh.data.fs

/**
 * One entry of a directory listing, wherever it came from.
 *
 * This is the single shape the Files Explorer UI knows. A local entry arrives from the Storage
 * Access Framework, a remote one from SFTP, and the UI must not care which — the whole point of
 * [FileSystemProvider] is that the explorer is written once against this type. The nullable fields
 * are the honest ones: a backend that cannot know a value leaves it null and the UI omits the
 * column rather than showing an invented number.
 *
 * [path] is opaque to the UI and only ever meaningful to the [FileSystemProvider] that produced it
 * (a `content://` document URI locally, a POSIX path over SFTP). The UI passes it back verbatim;
 * joining, parenting and canonicalising paths are provider duties, never UI arithmetic.
 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    /** Null when the backend cannot report a size (a directory, or a stream that does not stat). */
    val size: Long?,
    /** Null when the backend cannot report a modification time. */
    val modifiedEpochMillis: Long?,
    /** Octal POSIX permissions such as "755", or null when the backend has no such notion (local SAF). */
    val permissions: String?,
    /** A resolved MIME type from the backend, or null when the name alone has to decide. */
    val mimeType: String?,
)

/**
 * Thrown by [FileSystemProvider.write] when the file changed on the backend after the caller read
 * it — the guard behind the editor's "the file was modified on the server" question.
 *
 * Carrying it as a type (rather than a boolean result) lets the UI catch exactly the conflict case
 * and offer reload/overwrite/cancel, while every other failure keeps flowing through the ordinary
 * error path.
 */
class FsModificationConflictException(path: String) :
    Exception("The file at $path changed after it was opened")

/**
 * A filesystem the Files Explorer can browse: the device's own storage behind the Storage Access
 * Framework, or one host's SFTP tree.
 *
 * One abstraction serves both because the explorer's questions are the same everywhere — "what is
 * in this directory", "read this file", "rename this to that" — while the answers are not: SAF
 * speaks document URIs and reports no permissions, SFTP speaks POSIX paths and reports everything.
 * Implementations translate; the UI never sees a `DocumentFile` or an `SftpClient`.
 *
 * Contract rules every implementation must hold:
 *  - Every function is safe to call from any thread and suspends; none blocks the caller's
 *    dispatcher by doing I/O on it.
 *  - A [path] argument is a string this provider previously handed out (or, for roots, one the
 *    provider itself resolves — see [homePath]). Passing a foreign provider's path is a
 *    programming error and may throw anything.
 *  - Failures throw with a message a person can read; the UI shows them. Nothing here returns
 *    sentinel values for failure.
 *  - Null in [FsEntry] means "unknown", never "zero" or "empty".
 */
interface FileSystemProvider {
    /** Identifies this provider in session state and logs; "local" for the device filesystem. */
    val providerId: String

    /** The directory browsing starts in, or null when there is no sensible root yet (no SAF grant). */
    suspend fun homePath(): String?

    /** The directory above [path], or null at the root of the provider's world. */
    suspend fun parentPath(path: String): String?

    suspend fun list(path: String): List<FsEntry>

    /** The entry at [path], or null when nothing is there. */
    suspend fun stat(path: String): FsEntry?

    /**
     * Reads the whole file at [path]. The editor is the caller, and it already refuses files above
     * its size cap, so whole-file reads are the intended shape here.
     */
    suspend fun read(path: String): ByteArray

    /**
     * Writes [data] to [path], creating the file when absent.
     *
     * [onlyIfUnmodifiedSince] is the editor's conflict guard: when non-null and the file's
     * modification time on the backend is newer, the write must not happen and
     * [FsModificationConflictException] is thrown instead — never a silent overwrite. Backends
     * that cannot compare times ignore the guard rather than fail the write.
     */
    suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long? = null)

    /** Creates an empty file (or truncates an existing one) and returns its entry. */
    suspend fun createFile(path: String): FsEntry

    suspend fun createDirectory(path: String)

    suspend fun rename(from: String, to: String)

    /** Copies [from] to [to]; a directory copies recursively. */
    suspend fun copy(from: String, to: String)

    /** Moves [from] to [to], falling back to copy-then-delete when the backend cannot rename across the gap. */
    suspend fun move(from: String, to: String)

    /** Deletes the file or (recursively) the directory at [path]. */
    suspend fun delete(path: String)

    /**
     * Sets POSIX permission bits. Throws [UnsupportedOperationException] when the backend has no
     * such notion (local SAF) — the UI hides the action for those providers rather than calling.
     */
    suspend fun setPermissions(path: String, mode: Int)

    /**
     * Names under [root] matching [query], each with the path it was found at. Bounded by
     * [maxEntries] because a search walks a tree the provider does not control; over-limit results
     * are cut, not fatal. Case-insensitive by the implementation's own locale rules.
     */
    suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry>

    /** Whether this provider can report and set POSIX permissions; drives UI affordances. */
    val supportsPermissions: Boolean
        get() = false
}
