package dev.eclipse.ssh.archive

/**
 * Read access to an archive's bytes, on whichever side of the network they live.
 *
 * View Archive's promise is that the archive is inspected where it is: a 50 GB ZIP on the server
 * must not be downloaded to show its file list. Everything between the SFTP channel and the
 * format parsers narrows to this one interface, and its shape is chosen for exactly those two
 * readers:
 *
 *  - **ZIP** is random access. The central directory sits at a known offset near the end of the
 *    file, so a listing needs [size] plus one bounded [read] of the directory (and, when a local
 *    file header must be validated, a few dozen bytes more). Total bytes moved for a listing is
 *    the size of the metadata, not of the archive.
 *  - **TAR family** is sequential. The parser consumes forward-only [read]s in large chunks and
 *    never asks [readAt], because a compressed stream cannot be jumped around in; asking would
 *    mean either a full download (forbidden) or a decompression restart per entry (absurd).
 *
 * The interface is deliberately *not* a [java.io.InputStream] or a
 * [org.apache.commons.compress.SeekableByteChannel]: both are synchronous, and every byte here
 * crosses the network on a suspend path. Wrapping this in those shapes is the bridge layer's
 * job (on Dispatchers.IO, bounded, cancellable), not the parser's.
 *
 * Implementations must be safe to call from any coroutine and must not buffer the whole file.
 */
interface ArchiveByteSource {

    /** Total size of the archive in bytes, as the source's own stat reports it. */
    val size: Long

    /**
     * Reads up to [maxBytes] starting at [offset], advancing a cursor only this function sees.
     *
     * Returns fewer bytes than asked for only at end of file. Sequential readers call this
     * repeatedly without an offset argument, which is why the cursor exists: to a TAR parser,
     * "read the next chunk" and "read at a fixed offset" must look different, so the intent
     * survives into the implementation.
     *
     * @throws java.io.EOFException-equivalent behaviour: an [offset] past the end yields an
     * empty read, not an error, so a scan can run to the end without an off-by-one case.
     */
    suspend fun read(maxBytes: Int): ByteArray

    /**
     * Reads exactly [length] bytes at [offset]. Random access - the ZIP path only.
     *
     * Short reads (fewer than [length] bytes before end of file) throw
     * [ArchiveCorruptException]: a ZIP central directory that claims to extend past the end of
     * the file is a corrupt archive, not a normal short read.
     */
    suspend fun readAt(offset: Long, length: Int): ByteArray

    /** Repositions the sequential cursor. Never called by the TAR reader; kept for symmetry. */
    suspend fun seek(offset: Long)
}

/**
 * A [ByteArray] as an [ArchiveByteSource] - the in-memory backing for tests and for small
 * archives already on the device.
 *
 * The real production backing is SFTP-ranged (it reads [readAt] as one bounded channel read);
 * this one exists so every parser's tests run against plain bytes, and so the extract path can
 * be exercised without a network.
 */
class ByteArrayByteSource(private val bytes: ByteArray) : ArchiveByteSource {
    override val size: Long = bytes.size.toLong()
    private var cursor = 0L

    override suspend fun read(maxBytes: Int): ByteArray {
        val from = cursor
        val to = (cursor + maxBytes).coerceAtMost(bytes.size.toLong())
        cursor = to
        return bytes.copyOfRange(from.toInt(), to.toInt())
    }

    override suspend fun readAt(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        if (offset < 0 || length < 0) throw ArchiveCorruptException("Negative range $offset+$length")
        val end = (offset + length).coerceAtMost(bytes.size.toLong())
        if (end - offset < length) {
            // The same rule the SFTP backing enforces: a range that runs off the end of the
            // file is a corrupt archive, not a short read. Tests depend on both backings
            // disagreeing with the parser the same way.
            throw ArchiveCorruptException("Range $offset+$length runs past end (${bytes.size})")
        }
        return bytes.copyOfRange(offset.toInt(), end.toInt())
    }

    override suspend fun seek(offset: Long) {
        cursor = offset.coerceIn(0L, bytes.size.toLong())
    }
}
