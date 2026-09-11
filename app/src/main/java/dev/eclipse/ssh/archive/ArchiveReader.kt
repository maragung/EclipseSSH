package dev.eclipse.ssh.archive

import java.util.zip.Inflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The format-agnostic front door: name an archive, get its entries.
 *
 * Everything below this class knows a specific container; everything above it knows none. The
 * UI asks for a listing with a filename and a byte source and receives [ArchiveEntry]s plus a
 * [Format] tag for the properties sheet - the choice of parser, and the knowledge that a ZIP is
 * random-access while a TAR streams, live here and nowhere else.
 *
 * Not a dependency-injected service because there is nothing to inject: both engines are pure
 * over an [ArchiveByteSource], and the SFTP-backed source is constructed by the caller who owns
 * the session (see the browser layer). One object, no state, callable from anywhere.
 */
object ArchiveReader {

    /** The container formats View Archive can browse. The label is what the properties sheet shows. */
    enum class Format(val label: String, val extensions: List<String>) {
        ZIP("ZIP", listOf("zip", "jar")),
        TAR("TAR", listOf("tar")),
        TAR_GZ("TAR.GZ", listOf("tar.gz", "tgz")),
        TAR_BZ2("TAR.BZ2", listOf("tar.bz2", "tbz2")),
        TAR_XZ("TAR.XZ", listOf("tar.xz", "txz")),
    }

    /** The format a filename suggests, or null when it suggests none of the browsable ones. */
    fun formatFor(name: String): Format? {
        val lower = name.lowercase()
        // Longest-suffix first: ".tar.gz" must win over ".gz"-less ".tar" and over a hypothetical
        // ".gz" rule. Format.entries order is declaration order, so the sorted copy is what makes
        // the precedence explicit at the call site.
        return Format.entries.sortedByDescending { it.extensions.first().length }.firstOrNull { format ->
            format.extensions.any { lower.endsWith(it) }
        }
    }

    /**
     * Whether an entry's bytes can be fetched by range - the preview path's promise that opening
     * one 18 KB file inside a 50 GB archive moves 18 KB (plus a local header), not the archive.
     *
     * ZIP: yes, by [ZipArchive.dataOffsetOf]. Uncompressed TAR: yes - the streaming scan tracks
     * each entry's data offset from the record-aligned parse, and [readEntry] reads it with one
     * ranged read. Compressed TAR: never - seeking into a gzip stream means re-decompressing from
     * the start, which is the full download the feature forbids.
     */
    fun supportsRandomAccess(format: Format): Boolean =
        format == Format.ZIP || format == Format.TAR

    /**
     * Lists the archive's entries, dispatching to the engine [format] names.
     *
     * The TAR engine takes its compression as a parameter and this is the only place that maps
     * between the two vocabularies; keeping the mapping here means adding a format is a change to
     * this file and one engine, never to the UI.
     */
    suspend fun list(
        format: Format,
        source: ArchiveByteSource,
        onProgress: (ArchiveScanProgress) -> Unit = {},
    ): List<ArchiveEntry> = when (format) {
        Format.ZIP -> ZipArchive(source).listEntries(onProgress)
        Format.TAR -> TarArchive(source).listEntries(TarCompression.NONE, onProgress)
        Format.TAR_GZ -> TarArchive(source).listEntries(TarCompression.GZIP, onProgress)
        Format.TAR_BZ2 -> TarArchive(source).listEntries(TarCompression.BZIP2, onProgress)
        Format.TAR_XZ -> TarArchive(source).listEntries(TarCompression.XZ, onProgress)
    }

    /**
     * The compression a [Format]'s streaming extract needs, or null when the format is not the
     * TAR family. The twin of the mapping inside [list] - same single place to add a format.
     */
    fun tarCompressionOf(format: Format): TarCompression? = when (format) {
        Format.TAR -> TarCompression.NONE
        Format.TAR_GZ -> TarCompression.GZIP
        Format.TAR_BZ2 -> TarCompression.BZIP2
        Format.TAR_XZ -> TarCompression.XZ
        Format.ZIP -> null
    }

    /**
     * Reads one entry's bytes - the single-file preview and selective-extract path.
     *
     * ZIP: a bounded range read at the entry's data offset (the local header is validated on the
     * way), then decompressed per the entry's method - STORED bytes pass through, DEFLATED ones
     * are inflated to the entry's declared [ArchiveEntry.size] and refused when they do not match,
     * because a mismatch is a corrupt archive, not a preview to render anyway. Anything else the
     * method field can name is unsupported, honestly rather than guessed.
     *
     * Uncompressed TAR: a bounded range read at the entry's [ArchiveEntry.dataOffset], which the
     * record-aligned listing tracked while it scanned. Compressed TAR: null, telling the caller
     * that this entry needs the streaming pass instead; refusing to pretend is why the UI can
     * label the compressed-TAR preview honestly ("extract it instead") rather than discovering a
     * hidden full download.
     */
    suspend fun readEntry(format: Format, source: ArchiveByteSource, entry: ArchiveEntry): ByteArray? {
        if (format == Format.TAR) {
            // An uncompressed TAR's entry carries the byte offset its data starts at, tracked by
            // the record-aligned listing - so one ranged read moves exactly the entry's bytes,
            // the same promise the ZIP path makes. A null offset (compressed container, or a
            // listing that could not track it) falls through to the null answer below, which the
            // UI renders as the honest "cannot preview, extract instead".
            val offset = entry.dataOffset ?: return null
            if (entry.size < 0 || entry.size > Int.MAX_VALUE) {
                throw ArchiveCorruptException("Entry '${entry.path}' spans ${entry.size} byte(s) of data")
            }
            return source.readAt(offset, entry.size.toInt())
        }
        if (!supportsRandomAccess(format)) return null
        // A fresh instance must re-list before dataOffsetOf: the local-header offset bookkeeping
        // lives in the instance that scanned the central directory, so querying an instance that
        // never listed would refuse every entry as unknown. The re-list costs the metadata again
        // (EOCD window plus the central directory) and never the payloads - the same bounded cost
        // the original scan paid, which is the price of readEntry being a stateless one-shot
        // rather than a browser-session-scoped engine instance.
        val zip = ZipArchive(source)
        zip.listEntries()
        val offset = zip.dataOffsetOf(entry)
        val compressedSize = entry.compressedSize ?: return null
        // The Int bound is the source contract's (a range read's length is an array length); an
        // entry whose compressed size cannot fit one is past every ceiling this feature has and
        // reads as corrupt here rather than OOM'ing somewhere more scenic.
        if (compressedSize > Int.MAX_VALUE) {
            throw ArchiveCorruptException("Entry '${entry.path}' spans $compressedSize compressed byte(s)")
        }
        val compressed = source.readAt(offset, compressedSize.toInt())
        return when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATED -> inflateDeflate(compressed, entry)
            // The one honest answer for a method this reader cannot decode: null, which the
            // callers already treat as "no range read for this entry".
            else -> null
        }
    }

    /**
     * Inflates a deflated entry, verifying it lands on the entry's declared size.
     *
     * Size is checked because an inflater will happily return a prefix of the data (or an empty
     * array) for a truncated stream, and a preview that quietly shows half a file is worse than
     * the error that names it. [Inflater] is fed on the caller's dispatcher by its own
     * `withContext` - the call is CPU work on what may be megabytes.
     */
    private suspend fun inflateDeflate(compressed: ByteArray, entry: ArchiveEntry): ByteArray =
        withContext(Dispatchers.Default) {
        // Above 2 GB the size cannot be a ByteArray length at all, and the entry is past every
        // preview/extract ceiling this feature has - corrupt-or-oversized reads identically here.
        val declared = entry.size
        if (declared < 0 || declared > Int.MAX_VALUE) {
            throw ArchiveCorruptException("Entry '${entry.path}' declares $declared byte(s) of data")
        }
        // Inflater implements AutoCloseable (JNI-held native state), and the stdlib's use{} runs on
        // Closeable only - so the release is spelled by hand. Same shape as ByteArray above: the
        // block's exceptions must not leak the inflater's native memory.
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            // The declared size is the output bound as well as the check: allocating it up front
            // means no growing reallocations, and an archive that declares something absurd
            // (a 4 GB "size" over 200 compressed bytes) fails on the check below rather than by
            // OOM'ing the app - but only after allocating, so the size cap is the caller's job
            // (the preview sheet refuses oversized entries before reading).
            val output = ByteArray(declared.toInt())
            var produced = 0
            while (produced < output.size) {
                if (inflater.needsInput() && inflater.remaining == 0) break
                val count = inflater.inflate(output, produced, output.size - produced)
                if (count == 0) {
                    if (inflater.finished()) break
                    if (inflater.needsDictionary() || inflater.needsInput()) break
                }
                produced += count
            }
            if (!inflater.finished() || produced != output.size) {
                throw ArchiveCorruptException(
                    "Entry '${entry.path}' decompresses to $produced byte(s), not the " +
                        "${entry.size} its header declares",
                )
            }
            output
        } finally {
            inflater.end()
        }
    }

    /** ZIP method codes this reader can decode; see [readEntry]. */
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8
}
