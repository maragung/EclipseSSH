package dev.eclipse.ssh.archive

/**
 * One file or folder inside an archive, in the shape every archive format fills in.
 *
 * The whole View Archive feature is written against this type so the UI never learns which
 * container an entry came from: a ZIP central directory record and a TAR header both become an
 * [ArchiveEntry], and the tree, search and extract paths are format-blind from there.
 *
 * [path] is normalized the same way regardless of what the container spelled: '/'-separated,
 * no leading '/' (an archive root is not an absolute filesystem root), no '.' or empty segments,
 * and no trailing '/' - directory-ness is [isDirectory], not punctuation. Normalizing here, in
 * the one type everything shares, is what lets [SafeArchivePath] be a pure function of a path
 * string rather than a per-format negotiation.
 *
 * The nullable fields are the honest ones: TAR reports no compression method and no per-entry
 * compressed size (the whole stream is compressed), and a format that cannot report a
 * modification time leaves it null rather than inventing one.
 */
data class ArchiveEntry(
    /** Normalized '/'-separated path inside the archive, as described on the class. */
    val path: String,
    /** True for a directory entry (or one synthesized from another entry's parent folders). */
    val isDirectory: Boolean,
    /** Uncompressed size in bytes. */
    val size: Long,
    /** Compressed size in bytes, or null when the format does not know it (TAR family). */
    val compressedSize: Long?,
    /** Modification time, or null when the container does not carry one. */
    val modifiedEpochMillis: Long?,
    /** Compression method code (0 stored, 8 deflate, ...), or null when the format has none. */
    val method: Int?,
    /**
     * Byte offset of the entry's data inside the archive, or null when the entry cannot be read
     * by seeking (a TAR inside a compressed stream) or has no data (a directory).
     *
     * For ZIP this is the offset of the compressed bytes, past the local file header - the
     * reader computes it once so the preview path can fetch exactly this range and nothing else.
     */
    val dataOffset: Long?,
    /** True when the entry is encrypted and needs a password before its data can be read. */
    val encrypted: Boolean,
)

/** Parent folders implied by an entry's path but not present as entries of their own. */
internal fun ArchiveEntry.impliedParents(): List<String> {
    val segments = path.split('/')
    // The last segment is the entry itself; every prefix above it is an implied parent. An entry
    // at the archive root implies nothing.
    return (1 until segments.size).map { index -> segments.subList(0, index).joinToString("/") }
}

/**
 * Normalizes a raw container member name to the [ArchiveEntry.path] shape.
 *
 * Containers spell paths with their own accidents - a trailing '/' for ZIP directories, a
 * leading './' from some TAR writers, backslashes from a few Windows-made ZIPs - and the rest of
 * the feature reasons about paths only in the normalized shape. Returns null for names that
 * name nothing (empty, or only punctuation), which real archives do contain.
 */
fun normalizeArchivePath(raw: String): String? {
    val forward = raw.replace('\\', '/')
    val segments = forward.split('/')
        .filterNot { it.isEmpty() || it == "." }
    if (segments.isEmpty()) return null
    return segments.joinToString("/")
}

/**
 * The archive failures a person can be told something useful about.
 *
 * One hierarchy (rather than format-specific exceptions leaking out of the engines) because the
 * UI's answers are the same for every format: corrupt -> "unable to read, retry or close",
 * password -> the unlock dialog, unsupported -> "this archive cannot be browsed". The type, not
 * a message string, is what the UI switches on.
 */
open class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The container is structurally broken: no end record, a bad checksum, a truncated header. */
class ArchiveCorruptException(message: String, cause: Throwable? = null) : ArchiveException(message, cause)

/** The container is well-formed but uses something the reader refuses (e.g. an unknown method). */
class ArchiveUnsupportedException(message: String, cause: Throwable? = null) : ArchiveException(message, cause)

/** The archive (or one entry asked for) is encrypted and no password has been supplied. */
class ArchivePasswordRequiredException(message: String) : ArchiveException(message)

/**
 * Scan progress, reported periodically while a container's structure is being read.
 *
 * For ZIP the scan is the central directory - a bounded number of bytes at a known offset, so
 * [totalBytes] is exact. For the TAR family the scan is the whole stream, and the total is the
 * archive's size on the server when that is known, null when it is not.
 *
 * Cancellation rides on the reporting callback: the engine calls [onProgress] regularly and
 * treats any exception it throws (in practice a coroutines CancellationException) as a stop.
 */
data class ArchiveScanProgress(
    val bytesScanned: Long,
    val totalBytes: Long?,
    val entriesScanned: Long,
)
