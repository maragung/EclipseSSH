package dev.eclipse.ssh.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * The compression a TAR's filename says is wrapped around it.
 *
 * The filename is the only signal the remote side offers: unlike ZIP, a TAR has no central
 * directory to peek at before committing to a reader, so the compression layer is known from
 * the name or not knowable at all. [forFileName] turns a name into the member that answers
 * "which decompressor sits between the network and the tar stream" (or null for "not a TAR
 * family archive"), and [TarArchive.listEntries] takes that member as its second argument
 * rather than sniffing bytes - a wrong guess costs a full restart of a sequential scan.
 */
enum class TarCompression {
    NONE, GZIP, BZIP2, XZ;

    companion object {
        /** The compression a filename suggests, or null when the name is not a tar at all. */
        fun forFileName(name: String): TarCompression? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".tar") -> NONE
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> GZIP
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> BZIP2
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> XZ
                else -> null
            }
        }
    }
}

/**
 * Streaming listing engine for the TAR family over an [ArchiveByteSource].
 *
 * A TAR is a forward-only format: headers and payloads alternate in one long stream with no
 * index, so a listing is a full sequential pass that *skips* each payload rather than reading
 * it. The engine therefore owns three jobs the format libraries deliberately do not:
 *
 *  - **Bounded**: at most [READ_CHUNK_BYTES] are in flight from the source at a time (a small
 *    channel between producer and parser keeps it that way), and at most [MAX_ENTRIES] headers
 *    are ever believed, so a hostile archive cannot turn a scan into unbounded memory.
 *  - **Cancellable**: the parse loop surfaces [ArchiveScanProgress] on a callback, and any
 *    exception that callback throws (in practice a coroutines cancellation) aborts the scan.
 *  - **Honest about offsets**: for an uncompressed TAR, each entry records the byte offset of
 *    its data, so a later layer can fetch a member with one ranged read instead of another
 *    full pass. Compressed containers cannot offer that and say so with null.
 *
 * Format internals - header checksums, GNU long names, PAX extensions, the gzip/bzip2/xz
 * codecs - belong to commons-compress and xz; this class only bridges them onto the
 * suspending, chunked, progress-reporting world of [ArchiveByteSource].
 *
 * @param source the bytes as the network sees them: compressed bytes for a compressed
 * container, plain tar bytes for an uncompressed one.
 */
class TarArchive(private val source: ArchiveByteSource) {

    companion object {
        /** Read chunk for the streaming loop. 64 KB: big enough that per-chunk suspend+parse
         * overhead vanishes, small enough that progress stays live on a slow link. */
        const val READ_CHUNK_BYTES = 64 * 1024

        /** Hard entry cap: an archive with more entries than this is treated as corrupt, so a
         * hostile tarbomb cannot make the scan unbounded in count even where it is bounded in bytes. */
        const val MAX_ENTRIES = 2_000_000L
    }

    /**
     * Streams the archive forward from the source, parsing TAR headers as they arrive, WITHOUT
     * reading entry PAYLOADS into memory: each entry's data is skipped by reading and
     * discarding it (the decompressed stream forces the skip - you cannot jump).
     *
     * Progress is reported once per chunk read from the source and once per 1000 headers, as
     * [ArchiveScanProgress.bytesScanned] = cumulative compressed bytes consumed, with
     * [ArchiveScanProgress.entriesScanned] headers parsed so far. Any exception thrown by
     * [onProgress] aborts the scan and propagates to the caller.
     *
     * [ArchiveEntry.dataOffset] is always null for compressed containers (you cannot seek
     * into them); for an UNCOMPRESSED tar it is the byte offset of the entry's data within the
     * archive, tracked while streaming. Directories never carry one - they have no data.
     *
     * @throws ArchiveCorruptException for a truncated stream, an entry that climbs out of the
     * archive root with `..`, or an archive that exceeds [MAX_ENTRIES] entries.
     */
    suspend fun listEntries(
        compression: TarCompression,
        onProgress: (ArchiveScanProgress) -> Unit = {},
    ): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        // Capacity 2, not conflation: the parser sets the pace, and the source must never run
        // more than a couple of chunks ahead of it or the scan's memory stops being bounded.
        val channel = Channel<ByteArray>(capacity = 2)
        val scan = TarScanState(source.size)
        val bridge = SourceChannelInputStream(channel, scan, onProgress)
        coroutineScope {
            val producer = launch {
                try {
                    while (true) {
                        val chunk = source.read(READ_CHUNK_BYTES)
                        // The contract: a short read only happens at end of file.
                        if (chunk.isEmpty()) break
                        channel.send(chunk)
                    }
                    channel.close()
                } catch (cause: Throwable) {
                    // Close WITH the cause: the parser is parked in the bridge's runBlocking,
                    // and the only way it can learn the source died is the cause arriving on
                    // its next receive. Deliberately not rethrown - a source that fails after
                    // the parser already saw the end-of-archive marker must not fail a scan
                    // that had already succeeded.
                    channel.close(cause)
                }
            }
            try {
                readEntries(bridge, compression, scan, onProgress)
            } finally {
                // The parse is over, well or badly. Nothing may keep the producer reading
                // ahead into a channel nobody will drain anymore.
                producer.cancel()
            }
        }
    }

    /**
     * The blocking parse loop. Runs on the Dispatchers.IO thread [listEntries] borrowed,
     * pulling bytes through [bridge] until the tar ends, and never touches [source] itself.
     */
    private fun readEntries(
        bridge: InputStream,
        compression: TarCompression,
        scan: TarScanState,
        onProgress: (ArchiveScanProgress) -> Unit,
    ): List<ArchiveEntry> {
        val decompressed: InputStream = when (compression) {
            TarCompression.NONE -> bridge
            TarCompression.GZIP -> GzipCompressorInputStream(bridge)
            TarCompression.BZIP2 -> BZip2CompressorInputStream(bridge)
            TarCompression.XZ -> XZInputStream(bridge)
        }
        // blockSize == recordSize == 512: the tar record size is fixed by the format, and
        // matching the block to it is what makes the parser never read ahead of the record it
        // is currently on - the property [dataOffset] below is built on.
        val tar = TarArchiveInputStream(decompressed, 512, 512)
        val entries = ArrayList<ArchiveEntry>()
        var currentName: String? = null
        try {
            while (true) {
                val header = tar.getNextEntry() ?: break
                currentName = header.name
                scan.entriesScanned++
                if (scan.entriesScanned > MAX_ENTRIES) {
                    throw ArchiveCorruptException(
                        "archive holds more than $MAX_ENTRIES entries; refusing to list it",
                    )
                }
                // For a plain tar, the parser has consumed exactly the 512 header bytes at this
                // instant (see the constructor note), so the bridge's count is the offset of
                // the first data byte - GNU long-name and PAX records included, because the
                // count follows what the parser really read, not what arithmetic would predict.
                // A compressed stream cannot offer the same honesty: its byte position is
                // meaningless as a seek target.
                val dataOffset =
                    if (compression == TarCompression.NONE && !header.isDirectory) scan.bytesConsumed
                    else null
                val normalized = normalizeArchivePath(header.name)
                if (normalized != null) {
                    if (normalized.split('/').any { it == ".." }) {
                        // Same policy as the ZIP engine: a member that names a path outside the
                        // archive root is an attack, not a quirk to quietly normalize away.
                        throw ArchiveCorruptException(
                            "entry '$normalized' climbs out of the archive root",
                        )
                    }
                    entries.add(
                        ArchiveEntry(
                            path = normalized,
                            isDirectory = header.isDirectory,
                            size = header.size,
                            compressedSize = null, // the whole stream is compressed; no per-entry figure exists
                            modifiedEpochMillis = header.modTime.time, // tar stores seconds; Date already widened them to millis
                            method = null, // TAR has no compression method field
                            dataOffset = dataOffset,
                            encrypted = false, // TAR has no encryption
                        ),
                    )
                }
                // The payload skip. Looping on skip() rather than reading into arrays keeps
                // payloads out of memory; a 0 return before the payload is exhausted means the
                // stream ended inside the entry, which is a corrupt archive, not a short read.
                var remaining = header.size
                while (remaining > 0) {
                    val skipped = tar.skip(remaining)
                    if (skipped <= 0) {
                        throw ArchiveCorruptException(
                            "entry '$currentName' is truncated: ${remaining}B of data missing",
                        )
                    }
                    remaining -= skipped
                }
                if (scan.entriesScanned % 1_000L == 0L) {
                    onProgress(ArchiveScanProgress(scan.bytesConsumed, scan.totalBytes, scan.entriesScanned))
                }
            }
        } catch (e: IOException) {
            // The one net for everything the codec and the tar parser can throw mid-stream:
            // truncation, checksum failures, a decompressor that hits its own garbage. The
            // name in flight tells the person which entry the archive died on.
            val where = currentName?.let { " while reading '$it'" } ?: ""
            throw ArchiveCorruptException("TAR stream ended early$where", e)
        }
        // No tar.close(): every resource here is the channel and the coroutines, which
        // listEntries owns and disposes. Closing the commons-compress chain would only push
        // reads through the bridge after the producer is already cancelled.

        // A closing report so the finished counts are always observed: the parser stops at the
        // end-of-archive blocks without draining the last chunk, so the final per-chunk beat
        // predates the last headers. The ZIP engine reports the same closing beat, and the UI
        // watching both should never see a scan finish on stale numbers.
        onProgress(ArchiveScanProgress(scan.bytesConsumed, scan.totalBytes, scan.entriesScanned))
        return entries
    }

    /**
     * Streams the archive forward ONCE, writing the payloads of the entries [wants] accepts out
     * through [destination] - the extract twin of [listEntries].
     *
     * A compressed TAR cannot be seeked, so extracting N entries is not N reads but one forward
     * pass that takes each wanted payload as it passes and skips the rest; re-walking per entry
     * would be the full download the feature forbids, and this method is the reason it never
     * happens. The pass is started from wherever the source's sequential cursor sits, so the
     * CALLER must rewind ([ArchiveByteSource.seek] to 0) when the source has already been
     * walked - the extract entry point in [ArchiveExtractor] does exactly that.
     *
     * Payload copying is this method's to do, not the caller's: the TAR stream the copy reads
     * from lives inside the pass, and only the pass knows which entry is in flight. The caller
     * supplies the [ArchiveExtractor.Destination] (where bytes go) and [onEntry] (what to tell
     * the person about each one); path safety, folder chains, and refusals are handled here in
     * the same vocabulary [ArchiveExtractor]'s ZIP path uses, so both formats answer the same
     * questions the same way.
     */
    suspend fun extractEntries(
        compression: TarCompression,
        wants: (String) -> Boolean,
        destination: ArchiveExtractor.Destination,
        onEntry: (ArchiveEntry, ArchiveExtractor.Outcome) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        // The same machinery as listEntries, verbatim: a small channel keeps at most a couple of
        // chunks in flight, and the bridge parks this IO thread while the producer feeds it.
        val channel = Channel<ByteArray>(capacity = 2)
        val scan = TarScanState(source.size)
        val bridge = SourceChannelInputStream(channel, scan) { }
        coroutineScope {
            val producer = launch {
                try {
                    while (true) {
                        val chunk = source.read(READ_CHUNK_BYTES)
                        // The contract: a short read only happens at end of file.
                        if (chunk.isEmpty()) break
                        channel.send(chunk)
                    }
                    channel.close()
                } catch (cause: Throwable) {
                    // Close WITH the cause: the parser may be parked in the bridge's runBlocking,
                    // and the only way it can learn the source died is the cause arriving on its
                    // next receive. Deliberately not rethrown - a source that fails after the
                    // parser already saw the end-of-archive marker must not fail an extract that
                    // had already written its wanted entries.
                    channel.close(cause)
                }
            }
            try {
                extractThroughBridge(bridge, compression, wants, destination, onEntry)
            } finally {
                // The pass is over, well or badly. Nothing may keep the producer reading ahead
                // into a channel nobody will drain anymore.
                producer.cancel()
            }
        }
    }

    /**
     * The blocking extract loop: the same walk [readEntries] takes, but a wanted entry's payload
     * is carried out to the destination chunk by chunk instead of being discarded. Unwanted
     * payloads are skipped exactly as the listing skips them - one pass serves both audiences.
     */
    private fun extractThroughBridge(
        bridge: InputStream,
        compression: TarCompression,
        wants: (String) -> Boolean,
        destination: ArchiveExtractor.Destination,
        onEntry: (ArchiveEntry, ArchiveExtractor.Outcome) -> Unit,
    ) {
        val decompressed: InputStream = when (compression) {
            TarCompression.NONE -> bridge
            TarCompression.GZIP -> GzipCompressorInputStream(bridge)
            TarCompression.BZIP2 -> BZip2CompressorInputStream(bridge)
            TarCompression.XZ -> XZInputStream(bridge)
        }
        val tar = TarArchiveInputStream(decompressed, 512, 512)
        var currentName: String? = null
        try {
            while (true) {
                val header = tar.getNextEntry() ?: break
                currentName = header.name
                // The same cap the listing enforces: the pass reads every header whether wanted
                // or not, and a hostile tarbomb must not be able to make the walk unbounded in
                // count even where it is bounded in bytes.
                scan.entriesScanned++
                if (scan.entriesScanned > MAX_ENTRIES) {
                    throw ArchiveCorruptException(
                        "archive holds more than $MAX_ENTRIES entries; refusing to extract from it",
                    )
                }
                val normalized = normalizeArchivePath(header.name)
                if (normalized != null && wants(normalized)) {
                    val entry = ArchiveEntry(
                        path = normalized,
                        isDirectory = header.isDirectory,
                        size = header.size,
                        compressedSize = null,
                        modifiedEpochMillis = header.modTime.time,
                        method = null,
                        dataOffset = null,
                        encrypted = false,
                    )
                    // The same refusal vocabulary the ZIP path in ArchiveExtractor uses: a
                    // hostile name is refused with an outcome while the honest entries around
                    // it still extract - a hostile archive must not be able to abort the pass.
                    val safe = SafeArchivePath.safeDestinationName(entry.path)
                    val outcome = if (safe == null) {
                        ArchiveExtractor.Outcome.Refused(entry.path)
                    } else if (entry.isDirectory) {
                        if (runBlocking { destination.ensureFolder(safe) }) {
                            ArchiveExtractor.Outcome.Extracted(0L)
                        } else {
                            ArchiveExtractor.Outcome.DestinationRefused(safe)
                        }
                    } else if (!runBlocking { destination.ensureFolder(safe.substringBeforeLast('/', "")) }) {
                        ArchiveExtractor.Outcome.DestinationRefused(safe)
                    } else {
                        copyPayload(tar, entry, safe, destination)
                    }
                    onEntry(entry, outcome)
                    // Only an extracted FILE has had its payload carried out already; every
                    // other wanted entry falls through to the skip loop below, which for
                    // directories and refusals is a no-op (size 0 or bytes left unread).
                    if (outcome is ArchiveExtractor.Outcome.Extracted && !entry.isDirectory) {
                        continue
                    }
                }
                // The payload skip, shared by every entry that did not just have its bytes
                // carried out. Looping on skip() keeps payloads out of memory; a 0 return
                // before exhaustion is a corrupt archive, not a short read.
                var remaining = header.size
                while (remaining > 0) {
                    val skipped = tar.skip(remaining)
                    if (skipped <= 0) {
                        throw ArchiveCorruptException(
                            "entry '$currentName' is truncated: ${remaining}B of data missing",
                        )
                    }
                    remaining -= skipped
                }
            }
        } catch (e: IOException) {
            // The same net as the listing's: truncation, checksum failures, a codec that hits
            // its own garbage - wrapped so the person is told which entry the archive died on.
            val where = currentName?.let { " while reading '$it'" } ?: ""
            throw ArchiveCorruptException("TAR stream ended early$where", e)
        }
    }

    /**
     * Carries one wanted payload from the TAR stream to the destination, chunk by chunk.
     *
     * The destination's [ArchiveExtractor.Destination.openFile] is suspend, and this loop runs
     * inside the pass's blocking walk - so each suspend call parks in its own runBlocking the
     * way the bridge's reads do, which on Dispatchers.IO is the intended trade (a parked pool
     * thread per call, bounded by the entry's lifetime). A short read inside the payload is a
     * truncated archive and names the entry; a destination write failure is the destination's
     * to describe, and becomes the entry's [ArchiveExtractor.Outcome.Failed] without aborting
     * the pass - the honest entries behind it still get their turn.
     */
    private fun copyPayload(
        tar: TarArchiveInputStream,
        entry: ArchiveEntry,
        safe: String,
        destination: ArchiveExtractor.Destination,
    ): ArchiveExtractor.Outcome {
        val target = try {
            runBlocking { destination.openFile(safe) }
        } catch (failure: Throwable) {
            return ArchiveExtractor.Outcome.Failed(safe, failure.message ?: "could not be created")
        } ?: return ArchiveExtractor.Outcome.DestinationRefused(safe)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var written = 0L
        try {
            target.use { stream ->
                var remaining = entry.size
                while (remaining > 0) {
                    val count = tar.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count <= 0) {
                        throw EOFException("entry '${entry.path}' is truncated: ${remaining}B of data missing")
                    }
                    stream.write(buffer, 0, count)
                    written += count
                    remaining -= count
                }
                stream.flush()
            }
        } catch (failure: Throwable) {
            // A half-copied entry leaves its unread bytes in the stream, and the walk after this
            // must land on the NEXT header - not on payload garbage it would then parse as one.
            // Draining the remainder (best-effort: the stream may already be dead on a read
            // failure, where the whole walk is about to fail anyway) keeps every later entry's
            // position honest; only then is the failure reported instead of thrown, so the
            // honest entries behind this one still get their turn.
            var drain = entry.size - written
            while (drain > 0) {
                val skipped = tar.skip(drain)
                if (skipped <= 0) break
                drain -= skipped
            }
            return ArchiveExtractor.Outcome.Failed(safe, failure.message ?: "the destination could not be written")
        }
        return ArchiveExtractor.Outcome.Extracted(written)
    }
}

/**
 * The mutable scan counters the bridge and the parse loop share, so a progress report is a
 * consistent snapshot taken from one thread while the other advances it.
 */
private class TarScanState(val totalBytes: Long) {
    /** Bytes handed to the parser so far: the source's own (compressed) bytes, not inflated ones. */
    var bytesConsumed = 0L
    /** Headers parsed so far, listed or not. */
    var entriesScanned = 0L
}

/**
 * The coroutine-to-blocking bridge: a plain [InputStream] whose bytes arrive over a
 * [ReceiveChannel] fed by a suspending producer.
 *
 * commons-compress's readers are blocking `java.io` consumers, while [ArchiveByteSource] only
 * speaks suspend. The whole blocking parse runs inside one `withContext(Dispatchers.IO)`
 * block, and when the parser needs bytes, this bridge parks that IO thread with
 * `runBlocking { channel.receive() }` until the producer coroutine (running on another IO
 * thread, fed by the source's own suspends) delivers the next chunk. runBlocking inside
 * Dispatchers.IO is the trade-off being bought here, knowingly: a dedicated pool thread
 * parked per archive scan, bounded by the scan's lifetime - which is exactly what IO threads
 * are for, and it is what keeps the source's suspension points intact instead of freezing
 * them inside a blocking read. On a host running many simultaneous scans, the cost is one
 * parked thread each.
 *
 * Cancellation cannot strand the parked thread: the producer is a child of the same scope, so
 * whatever ends the scan - success, corruption, or cancellation - ends the producer too, and
 * its channel close (with cause, on failure) is what wakes the parked receive.
 *
 * Progress is reported from [fetch] as well as from the parse loop, because the bytes that
 * matter most to a person watching a scan are the ones crossing a multi-megabyte payload
 * skip, where no header boundary ever fires.
 */
private class SourceChannelInputStream(
    private val channel: ReceiveChannel<ByteArray>,
    private val scan: TarScanState,
    private val onProgress: (ArchiveScanProgress) -> Unit,
) : InputStream() {

    private var buffer = ByteArray(0)
    private var position = 0
    private var exhausted = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset + length > destination.size) {
            throw IndexOutOfBoundsException("read range $offset+$length into ${destination.size}")
        }
        if (length == 0) return 0
        if (position == buffer.size) {
            if (exhausted || !fetch()) return -1
        }
        val count = minOf(length, buffer.size - position)
        System.arraycopy(buffer, position, destination, offset, count)
        position += count
        scan.bytesConsumed += count.toLong()
        return count
    }

    /**
     * Pulls the next chunk from the producer. Returns false on the normal end of the source
     * (the channel's plain close); a producer failure rethrows its cause from receive(), which
     * is how a source error surfaces inside the parser's blocking read.
     */
    private fun fetch(): Boolean {
        val chunk = try {
            runBlocking { channel.receive() }
        } catch (e: ClosedReceiveChannelException) {
            exhausted = true
            return false
        }
        buffer = chunk
        position = 0
        // Reported per chunk, before these bytes are parsed: the count is everything the
        // parser has fully consumed so far, which is the most honest figure available at the
        // moment the scan is still inside the previous chunk's work.
        onProgress(ArchiveScanProgress(scan.bytesConsumed, scan.totalBytes, scan.entriesScanned))
        return true
    }
}
