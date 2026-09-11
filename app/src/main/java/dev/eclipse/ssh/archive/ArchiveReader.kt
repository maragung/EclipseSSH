package dev.eclipse.ssh.archive

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
     * ZIP: yes, by [ZipArchive.dataOffsetOf]. Uncompressed TAR: yes in principle, but the TAR
     * engine does not expose per-entry offsets yet (the streaming scan tracks them; surfacing
     * them is only worth doing once a preview path exists for TAR - until then the honest answer
     * is no). Compressed TAR: never - seeking into a gzip stream means re-decompressing from the
     * start, which is the full download the feature forbids.
     */
    fun supportsRandomAccess(format: Format): Boolean = format == Format.ZIP

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
     * Reads one entry's bytes - the single-file preview and selective-extract path.
     *
     * ZIP: a bounded range read at the entry's data offset (the local header is validated on the
     * way). Everything else: null, telling the caller that this entry needs the streaming path
     * ([TarArchive] re-scans forward); refusing to pretend is why the UI can label the TAR
     * preview honestly ("reading through the archive") instead of discovering a hidden full
     * download.
     */
    suspend fun readEntry(format: Format, source: ArchiveByteSource, entry: ArchiveEntry): ByteArray? {
        if (!supportsRandomAccess(format)) return null
        val zip = ZipArchive(source)
        val offset = zip.dataOffsetOf(entry)
        return source.readAt(offset, entry.compressedSize?.toInt() ?: return null)
    }
}
