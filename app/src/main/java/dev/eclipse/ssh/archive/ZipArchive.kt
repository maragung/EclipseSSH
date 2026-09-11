package dev.eclipse.ssh.archive

import java.util.Calendar
import java.util.TimeZone

/**
 * ZIP listing engine over an [ArchiveByteSource] - the whole reason View Archive can browse a
 * 50 GB archive on the server without downloading it.
 *
 * A ZIP keeps its entire table of contents in one contiguous run of bytes near the end of the
 * file (the central directory), pointed at by a 22-byte end-of-central-directory record (EOCD).
 * So a listing costs exactly: one bounded read to find the EOCD, one bounded read of the central
 * directory, and nothing else - never the entry payloads, however large they are. Every bound in
 * this class exists because those two reads are the only lever a hostile or broken archive has
 * to make the client move an absurd number of bytes, and every violation of those bounds is
 * reported as [ArchiveCorruptException] rather than accomodated.
 *
 * The format is parsed by hand rather than through [java.util.zip.ZipFile] or
 * org.apache.commons.compress: both want a full local [java.nio.channels.SeekableByteChannel],
 * which would mean materializing the whole archive (or a heroic adapter), and commons-compress's
 * ZipFile eagerly builds its entry model up front. The point of this engine is the opposite
 * shape - a bounded, chunked, cancellable scan over bytes that live somewhere else.
 *
 * [dataOffsetOf] is deliberately separate from [listEntries]: the central directory's copy of an
 * entry's name/extra lengths can disagree with the local file header's, so the true data offset
 * can only be learned from the local header, and paying that (small) read for every entry at
 * listing time would waste traffic on entries the user never opens.
 */
class ZipArchive(private val source: ArchiveByteSource) {

    companion object {
        /** Size of the fixed part of the EOCD record, signature through comment length. */
        const val EOCD_MIN_BYTES = 22

        /** Size of the ZIP64 EOCD locator that may sit immediately before the classic EOCD. */
        const val EOCD_LOCATOR_SIZE = 20L

        /**
         * How far back from end of file the EOCD search reads: the record itself plus the
         * largest comment the format allows (65 535 bytes) - the comment is the only thing the
         * format permits after the EOCD, so an EOCD farther from the end than this cannot be
         * reached by any conforming writer.
         */
        const val EOCD_MAX_SEARCH_BYTES = 65_557L

        /**
         * Ceiling on the central directory size this engine will read. A real directory entry is
         * ~46 bytes plus a name, so 64 MB is already millions of entries; a directory claiming
         * more than this is corrupt or hostile, and treating it as such is what keeps a broken
         * 32-bit size field from turning "list this archive" into "download gigabytes".
         */
        const val CENTRAL_DIRECTORY_MAX_SEARCH_BYTES = 64L * 1024L * 1024L

        // Signature words, little-endian in the file but written here as the conventional
        // documentation value (read them only through the little-endian helpers below).
        // Long so every comparison against u32()'s result is same-type: the five signatures are
        // the only constants a 4-byte read is ever compared to, and Kotlin refuses mixed
        // Long/Int equality.
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val CENTRAL_ENTRY_SIGNATURE = 0x02014b50L
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L

        /** Fixed part of a central directory entry: signature through the local header offset. */
        private const val CENTRAL_ENTRY_FIXED_BYTES = 46

        /** Fixed part of a local file header: signature through the extra length. */
        private const val LOCAL_HEADER_FIXED_BYTES = 30

        /** Central directory chunks are fixed-size so the source's reads stay predictable. */
        private const val CD_CHUNK_BYTES = 64 * 1024

        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_UTF8 = 0x0800

        /** DOS external attributes: the MS-DOS directory bit, and the Unix mode word above it. */
        private const val DOS_DIRECTORY_ATTR = 0x10L
        private const val UNIX_DIRECTORY_MODE = 0x4000L

        /** The ZIP64 extra field (header id 0x0001) that carries an entry's 64-bit fields. */
        private const val ZIP64_EXTRA_FIELD_ID = 0x0001

        /**
         * CP437's upper half. Android's charset registry does not guarantee IBM437 (only the
         * UTF/ASCII/ISO-8859-1 handful is mandated), and a ZipOutputStream-made name without the
         * UTF-8 flag is CP437 by definition of the format - so the 128 mapping entries are
         * carried here rather than risking [java.nio.charset.Charset.forName] throwing on the
         * device. The lower 128 bytes are ASCII in CP437.
         */
        private val CP437_HIGH_HALF =
            "ÇüéâäàåçêëèïîìÄÅ" +
                "ÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
                "áíóúñÑªº¿⌐¬½¼¡«»" +
                "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
                "└┴┬├─┼╞╟╚╔╩╦╠═╬╧" +
                "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
                "αßΓπΣσµτΦΘΩδ∞φε∩" +
                "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

        /** The earliest timestamp the DOS date format can even express: 1980-01-01T00:00:00Z. */
        private val DOS_EPOCH_MILLIS: Long by lazy {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(1980, Calendar.JANUARY, 1, 0, 0, 0)
            calendar.timeInMillis
        }
    }

    /**
     * Normalized entry path -> that entry's local file header offset, remembered by
     * [listEntries] so [dataOffsetOf] can find the header again without re-reading anything.
     * An instance is meant to be listed once and then queried; [listEntries] clears it first so
     * a re-list on the same instance behaves rather than mixing two scans.
     */
    private val localHeaderOffsets = mutableMapOf<String, Long>()

    /**
     * Cumulative bytes read from the source during the current scan, so progress reports keep
     * counting up through the EOCD window, any ZIP64 records, and the central directory chunks
     * rather than restarting at each phase.
     */
    private var bytesReadFromSource = 0L

    /**
     * Lists the archive by reading ONLY: the end-of-central-directory record (searched backwards
     * from EOF, bounded by [EOCD_MAX_SEARCH_BYTES]), and the central directory it points at
     * (bounded by [CENTRAL_DIRECTORY_MAX_SEARCH_BYTES] - an archive whose CD claims to be bigger
     * than that is treated as corrupt, so a hostile/broken CD cannot force a multi-GB read).
     * Streams the CD in fixed chunks - never holds the whole CD in memory at once; parses
     * entry-by-entry, emits through [onProgress] once per chunk, and honors cancellation by
     * letting onProgress's exception propagate.
     *
     * Entry data offsets are not computed here; see [dataOffsetOf].
     *
     * @throws ArchiveCorruptException when the structure is broken, self-inconsistent, or tries
     * to push this reader past its bounds - including an entry name containing a ".." segment,
     * which is a path traversal attempt and fails loudly rather than being silently skipped.
     */
    suspend fun listEntries(onProgress: (ArchiveScanProgress) -> Unit = {}): List<ArchiveEntry> {
        localHeaderOffsets.clear()
        bytesReadFromSource = 0L

        val eocd = locateEndOfCentralDirectory(onProgress)

        // The CD's own bounds are checked before a single CD byte is read: both limits are on
        // the values from the EOCD, so a lying EOCD is stopped at the door.
        if (eocd.entryCount < 0) {
            // Only reachable through a ZIP64 record, whose count is a full 64-bit word; one
            // with the sign bit set would make the loop below finish instantly and present a
            // broken archive as an empty one - the worst available answer.
            throw ArchiveCorruptException(
                "Entry count ${eocd.entryCount} is negative"
            )
        }
        if (eocd.cdSize > CENTRAL_DIRECTORY_MAX_SEARCH_BYTES) {
            throw ArchiveCorruptException(
                "Central directory claims ${eocd.cdSize} bytes, above the " +
                    "$CENTRAL_DIRECTORY_MAX_SEARCH_BYTES-byte ceiling"
            )
        }
        if (eocd.cdOffset < 0 || eocd.cdSize < 0 || eocd.cdOffset + eocd.cdSize > source.size) {
            throw ArchiveCorruptException(
                "Central directory at ${eocd.cdOffset}+${eocd.cdSize} runs past end of " +
                    "archive (${source.size} bytes)"
            )
        }

        val reader = CentralDirectoryReader(eocd.cdOffset, eocd.cdSize, onProgress)
        // A LinkedHashMap keeps scan order and collapses duplicates in one structure: putting
        // the same key again leaves the position of the first occurrence but the value of the
        // last, which is exactly the "last occurrence wins" rule for duplicate paths. The
        // local-header offsets were recorded by the entry parser itself, in scan order, so the
        // map behind dataOffsetOf keeps the last occurrence for the same reason.
        val entriesByPath = LinkedHashMap<String, ArchiveEntry>()
        var parsed = 0L
        while (parsed < eocd.entryCount) {
            val entry = reader.readEntry()
            // Count every directory record scanned, even ones skipped below, so progress
            // reports match what a desktop archive tool would call the entry count.
            reader.entriesScanned++
            if (entry != null) {
                entriesByPath[entry.path] = entry
            }
            parsed++
        }
        // A closing report so the finished counts are always observed: with a central directory
        // smaller than one chunk, every per-chunk beat fires before a single entry has been
        // parsed, and a UI that only ever saw entriesScanned == 0 would render an empty
        // progress state for an archive it had just finished listing.
        onProgress(ArchiveScanProgress(bytesScanned = bytesReadFromSource,
            totalBytes = source.size, entriesScanned = reader.entriesScanned))
        return entriesByPath.values.toList()
    }

    /**
     * Resolves an entry's true data start by validating its LOCAL file header: reads the 30-byte
     * fixed record plus local name plus local extra at the entry's local-header offset, checks
     * the 0x04034b50 signature, and returns the offset just past them. The central directory's
     * copy of name/extra can disagree with the local header's; the data start must come from the
     * local one, which is why this read exists at all - a few dozen bytes, paid only when an
     * entry is actually previewed or extracted.
     *
     * Must be called after [listEntries] listed the entry (the offset bookkeeping lives in the
     * instance), and never for a directory entry, which has no data.
     *
     * @throws ArchiveCorruptException on signature mismatch, a range past EOF, an unknown entry,
     * or a directory entry.
     */
    suspend fun dataOffsetOf(entry: ArchiveEntry): Long {
        if (entry.isDirectory) {
            throw ArchiveCorruptException("Entry '${entry.path}' is a directory and has no data")
        }
        val headerOffset = localHeaderOffsets[entry.path]
            ?: throw ArchiveCorruptException(
                "Entry '${entry.path}' was not produced by a listEntries scan on this archive"
            )

        // Exactly the fixed record: the name and extra that follow are sized by this record's
        // own two length fields, and reading them would be wasted traffic - only the lengths
        // decide where the data starts, and matching the name bytes is not part of the format's
        // promises (a handful of real writers spell the local name differently on purpose).
        val fixed = source.readAt(headerOffset, LOCAL_HEADER_FIXED_BYTES)
        if (fixed.size < LOCAL_HEADER_FIXED_BYTES) {
            throw ArchiveCorruptException(
                "Local file header at $headerOffset is truncated (${fixed.size} of " +
                    "$LOCAL_HEADER_FIXED_BYTES bytes)"
            )
        }
        val signature = u32(fixed, 0, headerOffset, "local file header signature")
        if (signature != LOCAL_HEADER_SIGNATURE) {
            throw ArchiveCorruptException(
                "Local file header at $headerOffset has signature 0x" +
                    signature.toString(16) + ", not 0x04034b50"
            )
        }
        val nameLength = u16(fixed, 26, headerOffset, "local file name length")
        val extraLength = u16(fixed, 28, headerOffset, "local file extra length")
        val dataOffset = headerOffset + LOCAL_HEADER_FIXED_BYTES + nameLength + extraLength
        if (dataOffset > source.size) {
            throw ArchiveCorruptException(
                "Local file header at $headerOffset claims data at $dataOffset, past end of " +
                    "archive (${source.size} bytes)"
            )
        }
        return dataOffset
    }

    /**
     * Finds and parses the EOCD, following the ZIP64 locator when the classic record's 16-bit
     * entry count or 32-bit offsets are saturated. Everything the CD read needs later is
     * validated here; everything else about the record is ignored on purpose.
     */
    private suspend fun locateEndOfCentralDirectory(
        onProgress: (ArchiveScanProgress) -> Unit,
    ): EndOfCentralDirectory {
        if (source.size < EOCD_MIN_BYTES) {
            throw ArchiveCorruptException(
                "Archive is ${source.size} bytes, too small to contain an EOCD record"
            )
        }

        // Read the whole search window in one go: it is bounded by EOCD_MAX_SEARCH_BYTES, so
        // this is the one place a bounded multi-read would buy nothing over a single read.
        val windowLength = minOf(source.size, EOCD_MAX_SEARCH_BYTES).toInt()
        val windowOffset = source.size - windowLength
        val window = source.readAt(windowOffset, windowLength)
        bytesReadFromSource += windowLength
        onProgress(ArchiveScanProgress(bytesScanned = bytesReadFromSource,
            totalBytes = source.size, entriesScanned = 0))

        // Search backwards so a signature-like byte run inside a file or a comment cannot
        // shadow the real record - the real EOCD is the last one that fits with its comment.
        var eocdAt = -1
        for (index in windowLength - EOCD_MIN_BYTES downTo 0) {
            if (u32(window, index, windowOffset + index, "EOCD signature probe") != EOCD_SIGNATURE) {
                continue
            }
            val commentLength = u16(window, index + 20, windowOffset + index, "EOCD comment length")
            if (index + EOCD_MIN_BYTES + commentLength <= windowLength) {
                eocdAt = index
                break
            }
        }
        if (eocdAt < 0) {
            throw ArchiveCorruptException(
                "No end-of-central-directory record in the last $EOCD_MAX_SEARCH_BYTES bytes"
            )
        }

        val entryCount = u16(window, eocdAt + 10, windowOffset + eocdAt, "EOCD entry count")
            .toLong()
        val cdSize = u32(window, eocdAt + 12, windowOffset + eocdAt, "EOCD central directory size")
        val cdOffset = u32(window, eocdAt + 16, windowOffset + eocdAt, "EOCD central directory offset")
        val eocdOffset = windowOffset + eocdAt

        // Saturated 16/32-bit fields are the classic record's way of saying "the real values are
        // in the ZIP64 record"; any one of them being saturated means the locator must be there.
        // The literals are Long because every field here is (u16's result was widened on purpose -
        // one type for every count, so no comparison silently truncates).
        if (entryCount != 0xFFFFL && cdSize != 0xFFFFFFFFL && cdOffset != 0xFFFFFFFFL) {
            return EndOfCentralDirectory(entryCount, cdSize, cdOffset)
        }
        return readZip64EndOfCentralDirectory(eocdOffset, onProgress)
    }

    /**
     * Reads the ZIP64 EOCD through the locator that sits [EOCD_LOCATOR_SIZE] bytes before the
     * classic record. Inconsistency anywhere on this path - no room for a locator, a foreign
     * signature, an offset outside the file - is corruption, because no conforming writer
     * saturates a field and then fails to provide the 64-bit one.
     */
    private suspend fun readZip64EndOfCentralDirectory(
        classicEocdOffset: Long,
        onProgress: (ArchiveScanProgress) -> Unit,
    ): EndOfCentralDirectory {
        if (classicEocdOffset < EOCD_LOCATOR_SIZE) {
            throw ArchiveCorruptException(
                "Saturated EOCD fields but no room at $classicEocdOffset for a ZIP64 locator"
            )
        }
        val locatorOffset = classicEocdOffset - EOCD_LOCATOR_SIZE
        val locator = source.readAt(locatorOffset, EOCD_LOCATOR_SIZE.toInt())
        val locatorSignature =
            u32(locator, 0, locatorOffset, "ZIP64 locator signature")
        if (locatorSignature != ZIP64_LOCATOR_SIGNATURE) {
            throw ArchiveCorruptException(
                "Saturated EOCD fields but no ZIP64 locator at $locatorOffset (found 0x" +
                    locatorSignature.toString(16) + ")"
            )
        }
        val zip64Offset = u64(locator, 8, locatorOffset, "ZIP64 EOCD offset")
        // The 56 bytes cover signature through the central directory offset; the record may be
        // longer (extensible data), but nothing past byte 56 is defined or needed here.
        if (zip64Offset < 0 || zip64Offset + 56 > source.size) {
            throw ArchiveCorruptException(
                "ZIP64 EOCD at $zip64Offset runs past end of archive (${source.size} bytes)"
            )
        }
        val zip64 = source.readAt(zip64Offset, 56)
        bytesReadFromSource += EOCD_LOCATOR_SIZE + 56
        onProgress(ArchiveScanProgress(bytesScanned = bytesReadFromSource,
            totalBytes = source.size, entriesScanned = 0))
        if (u32(zip64, 0, zip64Offset, "ZIP64 EOCD signature") != ZIP64_EOCD_SIGNATURE) {
            throw ArchiveCorruptException(
                "ZIP64 EOCD at $zip64Offset has a foreign signature"
            )
        }
        return EndOfCentralDirectory(
            entryCount = u64(zip64, 32, zip64Offset, "ZIP64 EOCD entry count"),
            cdSize = u64(zip64, 40, zip64Offset, "ZIP64 EOCD central directory size"),
            cdOffset = u64(zip64, 48, zip64Offset, "ZIP64 EOCD central directory offset"),
        )
    }

    /** The three values a listing needs from either flavor of end-of-central-directory record. */
    private class EndOfCentralDirectory(
        val entryCount: Long,
        val cdSize: Long,
        val cdOffset: Long,
    )

    /**
     * Sequential reader over the central directory that never holds more than one
     * [CD_CHUNK_BYTES] chunk (plus at most one in-flight entry record) in memory. Refilling a
     * chunk is also the progress and cancellation beat: every chunk read reports through
     * [onProgress], so whatever the callback throws stops the scan mid-directory rather than
     * after it.
     */
    private inner class CentralDirectoryReader(
        private val cdOffset: Long,
        private val cdSize: Long,
        private val onProgress: (ArchiveScanProgress) -> Unit,
    ) {
        /** Central directory records handed out so far, scanned by the caller. */
        var entriesScanned = 0L

        /** Position of [buffer]'s first byte within the CD. */
        private var bufferStart = 0L

        /** Position already handed out within [buffer] (CD-relative, so: bufferStart + consumed). */
        private var consumed = 0L

        private var buffer = ByteArray(0)

        /**
         * Hands out the next [count] bytes of the central directory, gathering across chunk
         * boundaries. A record larger than a chunk (a 60 KB entry name, say) still works: the
         * gather loop simply spans refills.
         */
        suspend fun take(count: Int): ByteArray {
            if (count == 0) return ByteArray(0)
            val available = availableInBuffer()
            if (count <= available) {
                val from = (consumed - bufferStart).toInt()
                consumed += count
                return buffer.copyOfRange(from, from + count)
            }
            val out = ByteArray(count)
            var copied = 0
            while (copied < count) {
                if (availableInBuffer() == 0) refill()
                val from = (consumed - bufferStart).toInt()
                val chunk = minOf(count - copied, buffer.size - from)
                System.arraycopy(buffer, from, out, copied, chunk)
                copied += chunk
                consumed += chunk
            }
            return out
        }

        /**
         * Reads and parses one central directory entry. Returns null for a record that names
         * nothing after normalization (an entry called "/" or "." - real archives contain these);
         * structural problems still throw.
         */
        suspend fun readEntry(): ArchiveEntry? {
            val fixed = take(CENTRAL_ENTRY_FIXED_BYTES)
            val recordOffset = cdOffset + consumed - CENTRAL_ENTRY_FIXED_BYTES
            val signature = u32(fixed, 0, recordOffset, "central directory entry signature")
            if (signature != CENTRAL_ENTRY_SIGNATURE) {
                throw ArchiveCorruptException(
                    "Central directory entry at $recordOffset has signature 0x" +
                        signature.toString(16) + ", not 0x02014b50"
                )
            }
            val flags = u16(fixed, 8, recordOffset, "entry flags")
            val method = u16(fixed, 10, recordOffset, "entry method")
            val dosTime = u16(fixed, 12, recordOffset, "entry modification time")
            val dosDate = u16(fixed, 14, recordOffset, "entry modification date")
            var compressedSize = u32(fixed, 20, recordOffset, "entry compressed size")
            var uncompressedSize = u32(fixed, 24, recordOffset, "entry uncompressed size")
            val nameLength = u16(fixed, 28, recordOffset, "entry name length")
            val extraLength = u16(fixed, 30, recordOffset, "entry extra length")
            val commentLength = u16(fixed, 32, recordOffset, "entry comment length")
            val externalAttributes = u32(fixed, 38, recordOffset, "entry external attributes")
            var localHeaderOffset = u32(fixed, 42, recordOffset, "entry local header offset")

            val nameBytes = take(nameLength)
            val extraBytes = take(extraLength)
            take(commentLength)

            // Saturated 32-bit fields defer to the ZIP64 extra field, whose payload carries the
            // 64-bit replacements in a fixed order (uncompressed, compressed, offset) - only the
            // saturated ones are present, so the cursor advances past exactly those.
            if (uncompressedSize == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL ||
                localHeaderOffset == 0xFFFFFFFFL
            ) {
                var resolvedZip64 = false
                var cursor = 0
                while (cursor + 4 <= extraBytes.size) {
                    val fieldId = u16(extraBytes, cursor, recordOffset, "extra field id")
                    val fieldSize = u16(extraBytes, cursor + 2, recordOffset, "extra field size")
                    val dataStart = cursor + 4
                    if (fieldId == ZIP64_EXTRA_FIELD_ID) {
                        resolvedZip64 = true
                        var fieldCursor = dataStart
                        if (uncompressedSize == 0xFFFFFFFFL) {
                            uncompressedSize = u64(
                                extraBytes, fieldCursor, recordOffset, "ZIP64 entry size",
                            )
                            fieldCursor += 8
                        }
                        if (compressedSize == 0xFFFFFFFFL) {
                            compressedSize = u64(
                                extraBytes, fieldCursor, recordOffset, "ZIP64 compressed size",
                            )
                            fieldCursor += 8
                        }
                        if (localHeaderOffset == 0xFFFFFFFFL) {
                            localHeaderOffset = u64(
                                extraBytes, fieldCursor, recordOffset, "ZIP64 local header offset",
                            )
                        }
                        break
                    }
                    cursor = dataStart + fieldSize
                }
                // A saturated field left unreplaced means the record's 64-bit continuation is
                // missing or truncated. Carrying the saturation on as a literal value would
                // present a 4 GB file where the writer meant something else entirely, so the
                // archive is refused instead.
                if (!resolvedZip64 || uncompressedSize == 0xFFFFFFFFL ||
                    compressedSize == 0xFFFFFFFFL || localHeaderOffset == 0xFFFFFFFFL
                ) {
                    throw ArchiveCorruptException(
                        "Entry at $recordOffset saturates its 32-bit fields but the ZIP64 " +
                            "extra field does not carry the replacements"
                    )
                }
            }
            // A 64-bit field with its sign bit set is not a size anyone can read; catching it
            // here keeps a corrupt value from flowing into the UI as a huge negative length.
            if (uncompressedSize < 0 || compressedSize < 0 || localHeaderOffset < 0) {
                throw ArchiveCorruptException(
                    "Entry at $recordOffset carries an impossible negative size or offset"
                )
            }

            val rawName = decodeName(nameBytes, flags)
            val path = normalizeArchivePath(rawName)
                // A path is returned as null only when the name is pure punctuation; falling
                // through to the CD's next record is the only sane response to an entry that
                // names nothing.
                ?: return null
            // ".." survives normalizeArchivePath on purpose (it is a real segment, not
            // punctuation), which makes it this reader's job to refuse it: a member that
            // would escape the extraction root is a traversal attempt, and hiding it in the
            // listing would only move the failure to the moment someone extracts it.
            if (path.split('/').any { it == ".." }) {
                throw ArchiveCorruptException(
                    "Entry name '$rawName' contains a '..' segment"
                )
            }

            val isDirectory = rawName.endsWith("/") ||
                (externalAttributes and DOS_DIRECTORY_ATTR) != 0L ||
                ((externalAttributes ushr 16) and 0xF000) == UNIX_DIRECTORY_MODE

            // Remembered per parsed record (not per returned entry) so that duplicate paths
            // leave the LAST occurrence's header offset behind - matching the entry the
            // listing keeps for that path.
            localHeaderOffsets[path] = localHeaderOffset

            return ArchiveEntry(
                path = path,
                isDirectory = isDirectory,
                // A directory entry carries no payload even when a sloppy writer left sizes in
                // the record; letting a folder show a nonzero size would mislead the UI's
                // sorting and the "how big is this" readout.
                size = if (isDirectory) 0L else uncompressedSize,
                compressedSize = if (isDirectory) 0L else compressedSize,
                modifiedEpochMillis = dosDateTimeToEpochMillis(dosDate, dosTime),
                method = method,
                dataOffset = null,
                encrypted = (flags and FLAG_ENCRYPTED) != 0,
            )
        }

        private fun availableInBuffer(): Int = (buffer.size - (consumed - bufferStart)).toInt()

        private suspend fun refill() {
            val nextStart = bufferStart + buffer.size
            val remainingInCd = cdSize - nextStart
            if (remainingInCd <= 0) {
                throw ArchiveCorruptException(
                    "Central directory exhausted at CD offset $nextStart of $cdSize bytes " +
                        "while an entry record was still being read"
                )
            }
            val want = minOf(CD_CHUNK_BYTES.toLong(), remainingInCd).toInt()
            // A short read here is impossible for a well-formed archive (the CD bounds were
            // validated against the source size), so readAt's short-read-as-corruption contract
            // is exactly the right failure mode to let through.
            buffer = source.readAt(cdOffset + nextStart, want)
            bufferStart = nextStart
            bytesReadFromSource += want
            onProgress(ArchiveScanProgress(bytesScanned = bytesReadFromSource,
                totalBytes = source.size, entriesScanned = entriesScanned))
        }
    }

    /**
     * Decodes an entry name: UTF-8 when the entry sets bit 11 (the only in-format signal there
     * is), CP437 otherwise. Invalid UTF-8 degrades to replacement characters rather than
     * throwing - a listing must still appear for an archive some other tool mislabeled.
     */
    private fun decodeName(bytes: ByteArray, flags: Int): String {
        if ((flags and FLAG_UTF8) != 0) {
            return String(bytes, Charsets.UTF_8)
        }
        val chars = CharArray(bytes.size)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            chars[index] = if (value < 0x80) value.toChar() else CP437_HIGH_HALF[value - 0x80]
        }
        return String(chars)
    }

    /**
     * Converts the DOS date and time words to epoch milliseconds. The DOS calendar starts in
     * 1980 and its fields are too narrow to be trusted (a 4-bit month, a 5-bit day), so
     * impossible values are clamped to the DOS epoch - a file listed with a wrong-but-sane
     * timestamp beats a listing that fails over a date field. An all-zero pair is the format's
     * "no timestamp", which stays null rather than being laundered into 1980.
     */
    private fun dosDateTimeToEpochMillis(dosDate: Int, dosTime: Int): Long? {
        if (dosDate == 0 && dosTime == 0) return null
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val month = (dosDate ushr 5) and 0x0F
        val day = dosDate and 0x1F
        val hour = (dosTime ushr 11) and 0x1F
        val minute = (dosTime ushr 5) and 0x3F
        val second = (dosTime and 0x1F) * 2
        if (year < 1980 || month !in 1..12 || day !in 1..31) return DOS_EPOCH_MILLIS

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        // Strict (lenient = false) so a date that passes the field-range check but names no real
        // day - February 31st - falls back to the DOS epoch instead of silently rolling into
        // March, which would show the user a date the archive never contained.
        calendar.isLenient = false
        return try {
            calendar.set(year, month - 1, day, hour.coerceAtMost(23), minute.coerceAtMost(59),
                second.coerceAtMost(59))
            calendar.timeInMillis
        } catch (_: IllegalArgumentException) {
            DOS_EPOCH_MILLIS
        }
    }

    /**
     * The little-endian readers every multi-byte field goes through. Each bounds-checks against
     * the record it was handed and names the absolute archive offset plus what was being read,
     * because "short read at 4812" is diagnosable and "index out of bounds" is not.
     */
    private fun u16(bytes: ByteArray, at: Int, absoluteOffset: Long, what: String): Int {
        if (at < 0 || at + 2 > bytes.size) {
            throw fieldOutOfBounds(at, absoluteOffset, what, 2, bytes.size)
        }
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, at: Int, absoluteOffset: Long, what: String): Long {
        if (at < 0 || at + 4 > bytes.size) {
            throw fieldOutOfBounds(at, absoluteOffset, what, 4, bytes.size)
        }
        return (bytes[at].toInt() and 0xFF).toLong() or
            ((bytes[at + 1].toInt() and 0xFF).toLong() shl 8) or
            ((bytes[at + 2].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[at + 3].toInt() and 0xFF).toLong() shl 24)
    }

    private fun u64(bytes: ByteArray, at: Int, absoluteOffset: Long, what: String): Long {
        if (at < 0 || at + 8 > bytes.size) {
            throw fieldOutOfBounds(at, absoluteOffset, what, 8, bytes.size)
        }
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (bytes[at + index].toInt() and 0xFF).toLong()
        }
        return value
    }

    private fun fieldOutOfBounds(
        at: Int,
        absoluteOffset: Long,
        what: String,
        width: Int,
        recordSize: Int,
    ) = ArchiveCorruptException(
        "Reading $what at offset $absoluteOffset needs $width bytes but only " +
            "${recordSize - at.coerceAtLeast(0)} remain in its $recordSize-byte record"
    )
}
