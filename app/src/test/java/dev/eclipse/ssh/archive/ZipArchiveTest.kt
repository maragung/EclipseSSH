package dev.eclipse.ssh.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The ZIP engine's contract, which is mostly about what it refuses to read.
 *
 * A listing must cost the size of the metadata and nothing more: the EOCD window plus the
 * central directory, never the entry payloads - the whole feature exists so a huge archive on
 * the server can be browsed without downloading it, so every test about bounds is a test about
 * the feature's reason for existing. The rest of the coverage walks the corrupt and hostile
 * shapes a real network hands over eventually: truncated records, lying offsets, saturated
 * fields, path-escaping names.
 *
 * Fixtures come from two places on purpose. Well-formed archives are made with
 * [ZipOutputStream], because interop with the platform's own writer is the cheapest proof the
 * parsing matches reality. The broken and hostile shapes are built byte-by-byte through
 * [HandZip], because no library writer will emit a central directory that lies about where it
 * lives - which is precisely the input the bounds exist for.
 */
class ZipArchiveTest {

    @Test
    fun `a two entry zip lists both entries with names sizes and methods`() {
        val deflated = "Hello, archive!".toByteArray()
        val stored = "0123456789".toByteArray()
        val zip = zipOf(
            ZipEntry("hello.txt") to deflated,
            storedEntry("data.bin", stored),
        )

        val entries = list(zip)

        assertThat(entries.map { it.path }).containsExactly("hello.txt", "data.bin").inOrder()
        val hello = entries.first { it.path == "hello.txt" }
        assertThat(hello.size).isEqualTo(deflated.size.toLong())
        assertThat(hello.method).isEqualTo(ZipEntry.DEFLATED)
        assertThat(hello.isDirectory).isFalse()
        assertThat(hello.encrypted).isFalse()
        // Deliberately lazy: the data offset is not paid for at listing time, only when an
        // entry is actually opened (see the dataOffsetOf tests below).
        assertThat(hello.dataOffset).isNull()
        assertThat(hello.modifiedEpochMillis).isNotNull()
        val data = entries.first { it.path == "data.bin" }
        assertThat(data.size).isEqualTo(stored.size.toLong())
        assertThat(data.compressedSize).isEqualTo(stored.size.toLong())
        assertThat(data.method).isEqualTo(ZipEntry.STORED)
    }

    @Test
    fun `nested paths normalize and trailing slash entries are directories`() {
        val zip = zipOf(
            ZipEntry("a/b/c.txt") to "deep".toByteArray(),
            ZipEntry("dir/") to ByteArray(0),
            ZipEntry("top.txt") to "top".toByteArray(),
        )

        val entries = list(zip)

        // Normalized shape: no trailing '/' on the directory - directory-ness is a flag, not
        // punctuation the rest of the feature has to keep stripping.
        assertThat(entries.map { it.path }).containsExactly("a/b/c.txt", "dir", "top.txt").inOrder()
        val dir = entries.first { it.path == "dir" }
        assertThat(dir.isDirectory).isTrue()
        assertThat(dir.size).isEqualTo(0L)
        assertThat(dir.compressedSize).isEqualTo(0L)
        assertThat(dir.dataOffset).isNull()

        // Some writers mark a directory only through the external attributes, with no trailing
        // slash in the name at all - the one shape ZipOutputStream cannot produce.
        val handMade = HandZip().apply {
            add("attr-dir".toByteArray(), externalAttrs = 0x10)
        }
        val attrEntry = list(handMade.build()).single()
        assertThat(attrEntry.path).isEqualTo("attr-dir")
        assertThat(attrEntry.isDirectory).isTrue()
    }

    @Test
    fun `a three hundred entry archive spanning read chunks lists every entry`() {
        // Names of 244 characters push the central directory past one 64 KB read chunk
        // (300 x ~290 bytes is about 87 KB), so the scan has to carry entries across a chunk
        // boundary to finish - the case a single-buffer parser would pass and a streaming one
        // could quietly get wrong.
        val entriesToAdd = (0 until 300).map { index ->
            val name = "%03d-".format(index) + "x".repeat(240)
            ZipEntry(name) to "c$index".toByteArray()
        }
        val zip = zipOf(*entriesToAdd.toTypedArray())

        val entries = list(zip)

        assertThat(entries).hasSize(300)
        assertThat(entries.first().path).isEqualTo(entriesToAdd.first().first.name)
        assertThat(entries.last().path).isEqualTo(entriesToAdd.last().first.name)
    }

    @Test
    fun `a trailing archive comment does not defeat the eocd search`() {
        val zip = ByteArrayOutputStream().let { buffer ->
            ZipOutputStream(buffer).use { stream ->
                stream.setComment("a comment far too long to sit between the signature and " +
                    "the file's end unnoticed - " + "x".repeat(5_000))
                stream.putNextEntry(ZipEntry("file.txt"))
                stream.write("data".toByteArray())
            }
            buffer.toByteArray()
        }

        val entries = list(zip)

        assertThat(entries.single().path).isEqualTo("file.txt")
    }

    @Test
    fun `an eocd beyond the search window is corrupt`() {
        val zip = zipOf(ZipEntry("file.txt") to "data".toByteArray())
        // Only the format's own maximum comment (65 535 bytes) may follow the EOCD, so an
        // EOCD pushed 70 000 bytes from the end is unreachable by any conforming writer -
        // the shape a truncated download or a concatenated file produces.
        val withTrailingJunk = zip + ByteArray(70_000)

        assertThat(corruptWhenListing(withTrailingJunk))
            .isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `a central directory pointing past the end of the archive is corrupt`() {
        val builder = HandZip().apply { add("file.txt".toByteArray(), "data".toByteArray()) }
        val zip = builder.build(eocdPatch = { eocd -> poke32(eocd, 16, 0x7FFF_0000L) })

        assertThat(corruptWhenListing(zip)).isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `a central directory above the size ceiling is corrupt`() {
        val builder = HandZip().apply { add("file.txt".toByteArray(), "data".toByteArray()) }
        // A size field above the ceiling is the hostile archive's one lever to turn "list me"
        // into "download me"; it must be refused before a single directory byte is read.
        val zip = builder.build(eocdPatch = { eocd ->
            poke32(eocd, 12, ZipArchive.CENTRAL_DIRECTORY_MAX_SEARCH_BYTES + 1)
        })

        assertThat(corruptWhenListing(zip)).isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `a dot dot escaping entry name is corrupt`() {
        val builder = HandZip().apply { add("../evil.txt".toByteArray()) }

        assertThat(corruptWhenListing(builder.build()))
            .isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `an entry that normalizes to nothing is skipped rather than listed`() {
        val builder = HandZip().apply {
            add("/".toByteArray())
            add(".".toByteArray())
            add("real.txt".toByteArray(), "data".toByteArray())
        }
        var finalEntriesScanned = 0L

        val entries = list(builder.build()) { progress ->
            finalEntriesScanned = progress.entriesScanned
        }

        // The punctuation entries name nothing, so they cannot appear in the tree - but they
        // are still directory records, and the entry count a person sees must include them.
        assertThat(entries.map { it.path }).containsExactly("real.txt")
        assertThat(finalEntriesScanned).isEqualTo(3L)
    }

    @Test
    fun `duplicate paths keep the last entry`() {
        val first = "first!".toByteArray()
        val second = "second!".toByteArray()
        val zip = zipOf(
            storedEntry("same.txt", first) to first,
            storedEntry("same.txt", second) to second,
        )
        val source = ByteArrayByteSource(zip)
        val archive = ZipArchive(source)

        val entries = runBlocking { archive.listEntries() }

        // ZIP permits the same name twice; a listing that showed both would render a tree with
        // two indistinguishable rows, so the later record wins - matching what every desktop
        // tool extracts.
        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.size).isEqualTo(second.size.toLong())
        val dataOffset = runBlocking { archive.dataOffsetOf(entry) }
        val bytesAtOffset = runBlocking { source.readAt(dataOffset, second.size) }
        assertThat(bytesAtOffset).isEqualTo(second)
    }

    @Test
    fun `listing traffic stays bounded regardless of payload size`() {
        val payload = ByteArray(1_048_576)
        val zip = zipOf(storedEntry("huge.bin", payload) to payload)
        val counting = CountingByteSource(ByteArrayByteSource(zip))

        val entries = runBlocking { ZipArchive(counting).listEntries() }

        assertThat(entries.single().size).isEqualTo(payload.size.toLong())
        // The EOCD window plus the whole central directory plus a name's worth of slack. The
        // number that matters is the second assertion: a 1 MB payload moved less than a tenth
        // of itself, because the entry bytes are never touched by a listing.
        assertThat(counting.bytesReturned)
            .isAtMost(ZipArchive.EOCD_MAX_SEARCH_BYTES + 2_000)
        assertThat(counting.bytesReturned).isLessThan(payload.size / 10)
    }

    @Test
    fun `the encrypted flag surfaces when bit zero is set`() {
        val builder = HandZip().apply {
            add("secret.txt".toByteArray(), "data".toByteArray(), flags = 0x0001)
        }

        val entry = list(builder.build()).single()

        // The listing itself succeeds - the point of the flag is that the UI can offer the
        // unlock dialog instead of a corrupt-file dead end.
        assertThat(entry.encrypted).isTrue()
        assertThat(entry.path).isEqualTo("secret.txt")
    }

    @Test
    fun `progress reports scanned entries against the archive size`() {
        val zip = zipOf(
            ZipEntry("a.txt") to "a".toByteArray(),
            ZipEntry("b.txt") to "b".toByteArray(),
        )
        val reports = mutableListOf<ArchiveScanProgress>()

        list(zip) { progress -> reports.add(progress) }

        assertThat(reports).isNotEmpty()
        assertThat(reports.first().entriesScanned).isEqualTo(0L)
        assertThat(reports.last().entriesScanned).isEqualTo(2L)
        assertThat(reports.last().totalBytes).isEqualTo(zip.size.toLong())
        assertThat(reports.last().bytesScanned).isGreaterThan(0L)
        // The count only ever moves forward: a UI animating it must never see it jump back.
        assertThat(reports.map { it.entriesScanned }).isInOrder()
    }

    @Test
    fun `an exception from onProgress aborts the scan partway`() {
        val entriesToAdd = (0 until 300).map { index ->
            ZipEntry("%03d-".format(index) + "x".repeat(240)) to "c$index".toByteArray()
        }
        val zip = zipOf(*entriesToAdd.toTypedArray())
        var maxEntriesScanned = 0L

        val thrown = runCatching {
            list(zip) { progress ->
                maxEntriesScanned = maxOf(maxEntriesScanned, progress.entriesScanned)
                // Fired once entries have been parsed but before the 300-entry directory is
                // done: the callback is the cancellation surface, so its exception must stop
                // the scan right here rather than after the last chunk.
                if (progress.entriesScanned >= 1L) throw IllegalStateException("stop now")
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(maxEntriesScanned).isLessThan(300L)
    }

    @Test
    fun `zip64 counts and offsets parse through the locator`() {
        // The classic EOCD below says 65 535 entries at offset 4 GB - saturated placeholders
        // for the real values in the ZIP64 record the locator points at. Archives with more
        // than 65 535 entries or a 4 GB-plus prefix look exactly like this, so the fallback
        // has to be transparent to the caller.
        val builder = HandZip().apply {
            add("big.bin".toByteArray(), "payload".toByteArray())
        }

        val entries = list(builder.build(zip64 = true))

        assertThat(entries).hasSize(1)
        assertThat(entries.single().path).isEqualTo("big.bin")
        assertThat(entries.single().size).isEqualTo("payload".length.toLong())
    }

    @Test
    fun `zip64 saturated entry sizes resolve through the extra field`() {
        val zip64Extra = le16(0x0001) + le16(16) + le64(12_345) + le64(9_999)
        val builder = HandZip().apply {
            add(
                "large.txt".toByteArray(),
                cdUncompressedSize = 0xFFFFFFFFL,
                cdCompressedSize = 0xFFFFFFFFL,
                cdExtra = zip64Extra,
            )
        }

        val entry = list(builder.build()).single()

        assertThat(entry.size).isEqualTo(12_345L)
        assertThat(entry.compressedSize).isEqualTo(9_999L)
    }

    @Test
    fun `dataOffsetOf returns the local header's data start`() {
        val localName = "a-much-longer-local-name.txt".toByteArray()
        val localExtra = ByteArray(6)
        val builder = HandZip()
        val headerOffset = builder.add(
            name = "short.txt".toByteArray(),
            data = "payload".toByteArray(),
            // The two headers deliberately disagree: a 6-byte local extra where the central
            // directory records none. A data start computed from the central lengths would
            // land 6 bytes early, inside the header - only the local one is the truth.
            localName = localName,
            localExtra = localExtra,
        )
        val archive = ZipArchive(ByteArrayByteSource(builder.build()))

        val entry = runBlocking { archive.listEntries() }.single()

        val expected = headerOffset + 30 + localName.size + localExtra.size
        assertThat(runBlocking { archive.dataOffsetOf(entry) }).isEqualTo(expected)
    }

    @Test
    fun `dataOffsetOf lands on the payload of a real zip entry`() {
        val content = "0123456789".repeat(3).toByteArray()
        val zip = zipOf(storedEntry("real.bin", content) to content)
        val source = ByteArrayByteSource(zip)
        val archive = ZipArchive(source)

        val entry = runBlocking { archive.listEntries() }.single()
        val dataOffset = runBlocking { archive.dataOffsetOf(entry) }

        // The strongest available check: the bytes at the resolved offset are the entry's own
        // content, in an archive written by the platform's writer rather than by hand.
        val bytesAtOffset = runBlocking { source.readAt(dataOffset, content.size) }
        assertThat(bytesAtOffset).isEqualTo(content)
    }

    @Test
    fun `dataOffsetOf rejects a foreign local header signature`() {
        val builder = HandZip().apply { add("file.txt".toByteArray(), "data".toByteArray()) }
        val zip = builder.build()
        // The first entry's local header starts at 0; stamping junk over its signature is the
        // truncated-download shape. Listing still succeeds (it never reads local headers) -
        // only the resolve step notices.
        val corrupted = zip.copyOf().also { bytes ->
            for (index in 0 until 4) bytes[index] = 0x7F
        }
        val archive = ZipArchive(ByteArrayByteSource(corrupted))
        val entry = runBlocking { archive.listEntries() }.single()

        val thrown = runCatching { runBlocking { archive.dataOffsetOf(entry) } }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `dataOffsetOf rejects an entry it never listed`() {
        val zip = zipOf(ZipEntry("file.txt") to "data".toByteArray())
        val entry = list(zip).single()
        // A fresh instance has no scan behind it, so there is no local header offset to
        // consult - resolving against one would be reading a byte the engine never validated.
        val archive = ZipArchive(ByteArrayByteSource(zip))

        val thrown = runCatching { runBlocking { archive.dataOffsetOf(entry) } }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `dos timestamps convert to epoch millis and clamp impossible dates`() {
        val validDate = ((2024 - 1980) shl 9) or (5 shl 5) or 6
        val validTime = (12 shl 11) or (34 shl 5) or (56 / 2)
        val builder = HandZip().apply {
            add("ok.txt".toByteArray(), dosTime = validTime, dosDate = validDate)
            // Month 13 and February 31st pass no real calendar; the all-zero pair is the
            // format's "no timestamp at all". Three different wrong shapes, three different
            // honest answers: clamp, clamp, null.
            add("bad-month.txt".toByteArray(), dosTime = validTime,
                dosDate = ((2024 - 1980) shl 9) or (13 shl 5) or 6)
            add("feb31.txt".toByteArray(), dosTime = validTime,
                dosDate = ((2024 - 1980) shl 9) or (2 shl 5) or 31)
            add("none.txt".toByteArray(), dosTime = 0, dosDate = 0)
        }

        val entries = list(builder.build()).associateBy { it.path }

        assertThat(entries.getValue("ok.txt").modifiedEpochMillis)
            .isEqualTo(utcMillis(2024, Calendar.MAY, 6, 12, 34, 56))
        assertThat(entries.getValue("bad-month.txt").modifiedEpochMillis)
            .isEqualTo(DOS_EPOCH_MILLIS)
        assertThat(entries.getValue("feb31.txt").modifiedEpochMillis)
            .isEqualTo(DOS_EPOCH_MILLIS)
        assertThat(entries.getValue("none.txt").modifiedEpochMillis).isNull()
    }

    @Test
    fun `names decode as cp437 without the utf8 flag and as utf8 with it`() {
        // 0x81 is 'ü' and 0xB0 is '░' in CP437 - the charset every pre-Unicode ZIP tool wrote,
        // and the one Android's charset registry does not guarantee, which is why the engine
        // carries its own table.
        val cp437Name = byteArrayOf(0x4D, 0x81.toByte(), 0x6E) + "chen.txt".toByteArray()
        val boxDrawingName = "art".toByteArray() + byteArrayOf(0xB0.toByte()) + ".txt".toByteArray()
        val utf8Name = "résumé.txt".toByteArray(Charsets.UTF_8)
        val builder = HandZip().apply {
            add(cp437Name, flags = 0)
            add(boxDrawingName, flags = 0)
            add(utf8Name, flags = 0x0800)
        }

        val paths = list(builder.build()).map { it.path }

        assertThat(paths).containsExactly("München.txt", "art░.txt", "résumé.txt")
    }

    /**
     * Runs a listing to completion (or to its first exception), because the engine's whole API
     * is suspend and every test boils down to "list these bytes, then assert".
     */
    private fun list(
        bytes: ByteArray,
        onProgress: (ArchiveScanProgress) -> Unit = {},
    ): List<ArchiveEntry> = runBlocking { ZipArchive(ByteArrayByteSource(bytes)).listEntries(onProgress) }

    /** The exception a listing died with, or null when it did not - Truth asserts on the type. */
    private fun corruptWhenListing(bytes: ByteArray): Throwable? =
        runCatching { list(bytes) }.exceptionOrNull()

    /** A STORED [ZipEntry] with the sizes and checksum the platform writer insists on. */
    private fun storedEntry(name: String, data: ByteArray): ZipEntry =
        ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }

    /** A well-formed in-memory ZIP from the platform's own writer, in add order. */
    private fun zipOf(vararg entries: Pair<ZipEntry, ByteArray>): ByteArray =
        ByteArrayOutputStream().let { buffer ->
            ZipOutputStream(buffer).use { stream ->
                entries.forEach { (entry, data) ->
                    stream.putNextEntry(entry)
                    stream.write(data)
                }
            }
            buffer.toByteArray()
        }

    /** Epoch millis the way the engine computes them: a UTC calendar, no local timezone. */
    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day, hour, minute, second)
    }.timeInMillis

    /**
     * Overwrites a little-endian 32-bit field in place, for patching one EOCD value in an
     * otherwise valid hand-built archive.
     */
    private fun poke32(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) {
            target[at + index] = ((value ushr (8 * index)) and 0xFF).toByte()
        }
    }

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun le64(value: Long): ByteArray =
        (0 until 8).map { index -> ((value ushr (8 * index)) and 0xFF).toByte() }.toByteArray()

    /**
     * A byte-level ZIP writer for the shapes no real writer will emit: lying EOCD fields,
     * path-escaping names, saturated sizes, headers that disagree with each other. Every field
     * this class writes is one the engine under test has to read, and being able to spell any
     * value into any of them is the point.
     */
    private class HandZip {
        private val local = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var entryCount = 0

        /**
         * Appends one entry (local file header, payload, and a central directory record) and
         * returns the local header's offset, which the data-offset tests need for arithmetic.
         *
         * The central and local headers are written from separate arguments on purpose: the
         * format allows them to disagree, and one test depends on building a disagreement.
         */
        fun add(
            name: ByteArray,
            data: ByteArray = ByteArray(0),
            flags: Int = 0,
            method: Int = ZipEntry.STORED,
            localName: ByteArray = name,
            localExtra: ByteArray = ByteArray(0),
            cdExtra: ByteArray = ByteArray(0),
            dosTime: Int = 0,
            dosDate: Int = 0,
            externalAttrs: Long = 0,
            cdUncompressedSize: Long = data.size.toLong(),
            cdCompressedSize: Long = data.size.toLong(),
        ): Long {
            val headerOffset = local.size().toLong()

            local.write(le32(0x04034b50))
            local.write(le16(20)) // version needed
            local.write(le16(flags))
            local.write(le16(method))
            local.write(le16(dosTime))
            local.write(le16(dosDate))
            local.write(le32(0)) // crc - never read by the listing engine
            local.write(le32(data.size.toLong()))
            local.write(le32(data.size.toLong()))
            local.write(le16(localName.size))
            local.write(le16(localExtra.size))
            local.write(localName)
            local.write(localExtra)
            local.write(data)

            central.write(le32(0x02014b50))
            central.write(le16(20)) // version made by
            central.write(le16(20)) // version needed
            central.write(le16(flags))
            central.write(le16(method))
            central.write(le16(dosTime))
            central.write(le16(dosDate))
            central.write(le32(0)) // crc
            central.write(le32(cdCompressedSize))
            central.write(le32(cdUncompressedSize))
            central.write(le16(name.size))
            central.write(le16(cdExtra.size))
            central.write(le16(0)) // comment length
            central.write(le16(0)) // disk number
            central.write(le16(0)) // internal attributes
            central.write(le32(externalAttrs))
            central.write(le32(headerOffset))
            central.write(name)
            central.write(cdExtra)

            entryCount++
            return headerOffset
        }

        /**
         * Assembles the archive. With [zip64] the classic EOCD's count and offsets are left
         * saturated and a ZIP64 EOCD plus locator sit between the central directory and it,
         * which is how real writers spell "this archive is past the 32-bit limits". The
         * [eocdPatch] lambda mutates the 22-byte classic EOCD before it is appended, so a test
         * can corrupt exactly one field of an otherwise valid archive.
         */
        fun build(
            zip64: Boolean = false,
            eocdPatch: (ByteArray) -> Unit = {},
        ): ByteArray {
            val localBytes = local.toByteArray()
            val centralBytes = central.toByteArray()
            val cdOffset = localBytes.size.toLong()
            val out = ByteArrayOutputStream()
            out.write(localBytes)
            out.write(centralBytes)

            if (zip64) {
                val zip64Offset = cdOffset + centralBytes.size
                out.write(le32(0x06064b50))
                out.write(le64(44)) // size of this record past this field
                out.write(le16(45)) // version made by
                out.write(le16(45)) // version needed
                out.write(le32(0)) // disk number
                out.write(le32(0)) // central directory start disk
                out.write(le64(entryCount.toLong())) // entries on this disk
                out.write(le64(entryCount.toLong())) // entries total
                out.write(le64(centralBytes.size.toLong()))
                out.write(le64(cdOffset))
                out.write(le32(0x07064b50))
                out.write(le32(0)) // disk with the ZIP64 EOCD
                out.write(le64(zip64Offset))
                out.write(le32(1)) // total disks
            }

            val eocd = ByteArray(ZipArchive.EOCD_MIN_BYTES)
            // Classic EOCD layout: signature, this disk, CD start disk, records on this disk,
            // records total (16-bit, saturated under ZIP64), CD size (32-bit), CD offset
            // (32-bit), comment length - each field at the offset the format fixes it at.
            writeField(eocd, 0, le32(0x06054b50))
            writeField(eocd, 4, le16(0)) // disk number
            writeField(eocd, 6, le16(0)) // central directory start disk
            writeField(eocd, 8, le16(if (zip64) 0xFFFF else entryCount)) // records on this disk
            writeField(eocd, 10, le16(if (zip64) 0xFFFF else entryCount)) // records total
            writeField(eocd, 12, le32(if (zip64) 0xFFFFFFFFL else centralBytes.size.toLong()))
            writeField(eocd, 16, le32(if (zip64) 0xFFFFFFFFL else cdOffset))
            writeField(eocd, 20, le16(0)) // comment length
            eocdPatch(eocd)
            out.write(eocd)
            return out.toByteArray()
        }

        private fun writeField(target: ByteArray, at: Int, bytes: ByteArray) {
            bytes.forEachIndexed { index, byte -> target[at + index] = byte }
        }
    }

    /**
     * An [ArchiveByteSource] that counts every byte handed out, so a test can assert what a
     * listing cost - the difference between "bounded scan" as a claim and as a number.
     */
    private class CountingByteSource(private val inner: ArchiveByteSource) : ArchiveByteSource by inner {
        var bytesReturned = 0L
            private set

        override suspend fun readAt(offset: Long, length: Int): ByteArray =
            inner.readAt(offset, length).also { bytesReturned += it.size }

        override suspend fun read(maxBytes: Int): ByteArray =
            inner.read(maxBytes).also { bytesReturned += it.size }
    }

    private companion object {
        /** 1980-01-01T00:00:00Z - the DOS epoch the engine clamps impossible dates to. */
        val DOS_EPOCH_MILLIS: Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(1980, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
    }
}
