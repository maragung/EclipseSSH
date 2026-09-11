package dev.eclipse.ssh.archive

import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Selective extraction: writing chosen entries' bytes out through a sink, without the archive
 * ever being downloaded as a whole.
 *
 * The layer's promise mirrors the browser's: extracting one 2 KB file from a 50 GB ZIP moves that
 * file's compressed bytes and nothing else (the entry's data is fetched by the same range read the
 * preview uses), and extracting from a TAR means walking the stream - forward, once - reading the
 * entries asked for as they pass, because a compressed TAR cannot be jumped around in and
 * re-reading it per entry would be the full download the feature forbids.
 *
 * The safety layer is [SafeArchivePath]: every entry's path is vetted before anything is created,
 * and a hostile member (`..` segments, absolute names, reserved device names) is refused with a
 * logged outcome while the honest entries around it still extract. The destination is addressed
 * through [Destination] - an interface of two operations (ensure a folder chain, open a file for
 * writing) so the engine is pure enough to test against an in-memory tree and the UI layer backs
 * it with SAF's [androidx.documentfile.provider.DocumentFile]s.
 */
object ArchiveExtractor {

    /**
     * The destination contract: what the engine needs a filesystem to do, and nothing more.
     *
     * Paths arrive in [SafeArchivePath.safeDestinationName]'s shape - '/'-separated, relative, no
     * `..` - and the implementation joins them under its own root with its own rules. [openFile]
     * returning null is the destination saying "cannot create this" (a name the provider refuses,
     * a full disk before the first byte): the engine records it as a failure for that entry and
     * keeps going, because one refusal must not abandon the honest entries behind it.
     */
    interface Destination {
        /** Ensures the folder chain for [path] exists, or returns false when it cannot. */
        suspend fun ensureFolder(path: String): Boolean

        /**
         * Opens [path] for writing. The returned stream is closed by the engine; null means the
         * file could not be created.
         */
        suspend fun openFile(path: String): OutputStream?
    }

    /** One entry's extract outcome, in the vocabulary a person can be told something useful about. */
    sealed interface Outcome {
        /** The entry's bytes were written (a folder entry needs no bytes and reports this too). */
        data class Extracted(val bytes: Long) : Outcome

        /** The entry's path was refused by [SafeArchivePath] - hostile or unrepresentable. */
        data class Refused(val path: String) : Outcome

        /** The destination could not create the file or folder. */
        data class DestinationRefused(val path: String) : Outcome

        /** The entry's bytes could not be read or written. */
        data class Failed(val path: String, val reason: String) : Outcome
    }

    /**
     * Extracts [entries] from the archive [source] into [destination].
     *
     * ZIP extracts entry by entry through the same range read the preview performs. The TAR
     * family cannot: its bytes sit in one forward-only stream, so the whole set is extracted by
     * ONE streaming pass ([TarArchive.extractEntries]) that takes each wanted entry's payload as
     * it passes and skips the rest - N entries cost one walk, never N walks.
     *
     * Returns one [Outcome] per entry, in the order asked for. An entry the pass never met (the
     * archive changed under the extract, or the caller passed a path the listing never saw)
     * still gets an outcome - [Outcome.Failed] naming the miss - so the tally always adds up.
     */
    suspend fun extract(
        format: ArchiveReader.Format,
        source: ArchiveByteSource,
        entries: List<ArchiveEntry>,
        destination: Destination,
        onProgress: suspend (bytesWritten: Long, entry: ArchiveEntry) -> Unit = { _, _ -> },
    ): List<Outcome> {
        val compression = ArchiveReader.tarCompressionOf(format)
        if (compression == null) {
            return extractZipEntries(source, entries, destination, onProgress)
        }
        return extractStreaming(compression, source, entries, destination, onProgress)
    }

    /**
     * The ZIP walk: per entry, the same range read the preview performs. An entry's bytes move
     * only when it is asked for - the family's whole reason to exist.
     */
    private suspend fun extractZipEntries(
        source: ArchiveByteSource,
        entries: List<ArchiveEntry>,
        destination: Destination,
        onProgress: suspend (bytesWritten: Long, entry: ArchiveEntry) -> Unit,
    ): List<Outcome> = withContext(Dispatchers.IO) {
        val outcomes = mutableListOf<Outcome>()
        var written = 0L
        for (entry in entries) {
            coroutineContext.ensureActive()
            outcomes += extractOne(source, entry, destination) { bytes ->
                written += bytes
            }
            // Reported per entry rather than per chunk: the chunk-level number belongs to the
            // transfer UI once extraction rides it, and a per-entry count is already enough to
            // keep a browser-side label honest ("3 of 10 files").
            onProgress(written, entry)
        }
        outcomes
    }

    /**
     * The TAR-family walk: one forward pass over the whole stream, keyed by the set of wanted
     * normalized paths. The pass itself is [TarArchive.extractEntries] - it owns the stream, so
     * it carries each wanted payload out chunk by chunk as it passes - and this method only
     * accounts the outcomes, re-ordering them to the caller's asked order. The caller's contract
     * is "one outcome per entry, in the order asked for", and the stream's order is the
     * archive's business, not the caller's.
     */
    private suspend fun extractStreaming(
        compression: TarCompression,
        source: ArchiveByteSource,
        entries: List<ArchiveEntry>,
        destination: Destination,
        onProgress: suspend (bytesWritten: Long, entry: ArchiveEntry) -> Unit,
    ): List<Outcome> {
        // The listing walked this source already; a second walk must start at the beginning, or
        // it would see only the tail of the archive and answer "missing" for entries the user
        // is looking right at. Rewinding is the source's own supported move.
        source.seek(0)
        val wanted = entries.associateBy { entry -> entry.path }
        val landed = mutableMapOf<String, Outcome>()
        var written = 0L
        TarArchive(source).extractEntries(
            compression,
            wants = { path -> path in wanted },
            destination = destination,
            onEntry = { entry, outcome ->
                landed[entry.path] = outcome
                // The same per-entry byte count the ZIP walk feeds its progress: an Extracted
                // folder is zero bytes, and a refused or failed entry moves none to count.
                if (outcome is Outcome.Extracted) written += outcome.bytes
            },
        )
        // Tally by the asked order, with every never-met entry named as a miss. The progress
        // callback rides the tally so a caller that never looks at outcomes still sees bytes.
        return entries.map { entry ->
            val outcome = landed.remove(entry.path)
                // The pass ended without meeting this path: the archive under the extract is
                // not the archive that was listed. Said plainly, not guessed around.
                ?: Outcome.Failed(entry.path, "the entry is not in the archive any more")
            onProgress(written, entry)
            outcome
        }
    }

    /** One entry's outcome, with its byte count (when any moved) fed back for progress. */
    private suspend fun extractOne(
        source: ArchiveByteSource,
        entry: ArchiveEntry,
        destination: Destination,
        onBytes: (Long) -> Unit,
    ): Outcome {
        val safe = SafeArchivePath.safeDestinationName(entry.path)
        if (safe == null) return Outcome.Refused(entry.path)
        if (entry.isDirectory) {
            return if (destination.ensureFolder(safe)) {
                Outcome.Extracted(0L)
            } else {
                Outcome.DestinationRefused(safe)
            }
        }
        // A file's parent chain is implied by its own path; an empty parent is the archive root,
        // which the destination guarantees by existing.
        if (!destination.ensureFolder(safe.substringBeforeLast('/', ""))) {
            return Outcome.DestinationRefused(safe)
        }
        return extractZipEntry(source, entry, safe, destination, onBytes)
    }

    /**
     * One ZIP entry: the same range read the preview performs, then the same method dispatch
     * ([ArchiveReader.readEntry] is the single implementation of both paths' decompression - the
     * extractor reuses it so an inflate fix lands in the preview and the extract at once).
     */
    private suspend fun extractZipEntry(
        source: ArchiveByteSource,
        entry: ArchiveEntry,
        safe: String,
        destination: Destination,
        onBytes: (Long) -> Unit,
    ): Outcome {
        val bytes = try {
            ArchiveReader.readEntry(ArchiveReader.Format.ZIP, source, entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ArchiveException) {
            return Outcome.Failed(safe, failure.message ?: "the entry could not be read")
        } catch (failure: Throwable) {
            return Outcome.Failed(safe, failure.message ?: "the entry could not be read")
        } ?: return Outcome.Failed(safe, "the compression method is not supported")
        val target = destination.openFile(safe) ?: return Outcome.DestinationRefused(safe)
        try {
            target.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A write that fails halfway is a destination problem (disk full, provider revoked)
            // named as such, not an archive problem - the bytes were read fine.
            return Outcome.Failed(safe, failure.message ?: "the destination could not be written")
        }
        onBytes(bytes.size.toLong())
        return Outcome.Extracted(bytes.size.toLong())
    }
}
