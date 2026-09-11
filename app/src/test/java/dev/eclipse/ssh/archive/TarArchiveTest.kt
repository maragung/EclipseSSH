package dev.eclipse.ssh.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The engine's contract, which is everything the format libraries deliberately leave to it.
 *
 * commons-compress already knows how to parse a tar header and how to inflate a gzip member;
 * what these tests pin down is the streaming bridge wrapped around that knowledge: payloads
 * are skipped rather than read, bytes stay bounded while a 5 MB member streams past, progress
 * is reported as compressed bytes and stays live during a long skip, the scan aborts when the
 * callback says so, and a hostile or truncated archive fails loudly instead of half-listing.
 * Every fixture is built with commons-compress's own output streams, so the bytes on the far
 * side of the bridge are exactly what a real `tar czf` would have produced.
 */
class TarArchiveTest {

    @Test
    fun `a plain tar with three files and a directory lists all four with names, sizes and mtimes`() {
        val tar = buildTar(
            listOf(
                file("hello.txt", "hello".toByteArray()),
                file("data.bin", ByteArray(600) { 'z'.code.toByte() }),
                file("nested/deep/last.txt", "bye".toByteArray()),
                dir("folder/"),
            ),
        )
        val entries = list(tar)

        assertThat(entries.map { it.path })
            .containsExactly("hello.txt", "data.bin", "nested/deep/last.txt", "folder")
            .inOrder()

        val hello = entries.single { it.path == "hello.txt" }
        assertThat(hello.size).isEqualTo(5)
        assertThat(hello.isDirectory).isFalse()
        assertThat(hello.modifiedEpochMillis).isEqualTo(FIXED_MTIME)
        assertThat(hello.compressedSize).isNull()
        assertThat(hello.method).isNull()
        assertThat(hello.encrypted).isFalse()

        val folder = entries.single { it.path == "folder" }
        assertThat(folder.isDirectory).isTrue()
        assertThat(folder.size).isEqualTo(0)
        // A directory has no data to point at, even in a seekable tar.
        assertThat(folder.dataOffset).isNull()
    }

    @Test
    fun `the listing contains exactly the real entries - no parent folders are synthesized`() {
        val tar = buildTar(listOf(file("a/b/c.txt", "x".toByteArray())))
        val entries = list(tar)
        // Synthesizing "a" and "a/b" here would double-count once the tree layer does exactly
        // that from implied parents; the engine must report only what the archive holds.
        assertThat(entries).hasSize(1)
        assertThat(entries.single().path).isEqualTo("a/b/c.txt")
    }

    @Test
    fun `uncompressed entries carry the real byte offset of their data in the archive`() {
        val first = ByteArray(100) { 'a'.code.toByte() }
        val second = ByteArray(600) { 'b'.code.toByte() }
        val tar = buildTar(listOf(file("first.txt", first), file("second.txt", second)))
        val entries = list(tar)

        val firstEntry = entries.single { it.path == "first.txt" }
        val secondEntry = entries.single { it.path == "second.txt" }

        // Arithmetic on the fixture: a header is 512 bytes, a payload is padded up to a
        // multiple of 512, and the next header follows. second.txt's data therefore starts
        // after first.txt's header, its padded payload, and its own header.
        assertThat(firstEntry.dataOffset).isEqualTo(512L)
        assertThat(secondEntry.dataOffset).isEqualTo(512L + padded(firstEntry.size) + 512L)

        // And the proof against the fixture bytes themselves: what sits AT that offset in the
        // archive is exactly the entry's content, so a ranged read of dataOffset..+size would
        // return the file without a second full pass.
        val offset = secondEntry.dataOffset!!.toInt()
        assertThat(tar.copyOfRange(offset, offset + second.size)).isEqualTo(second)
    }

    @Test
    fun `tar gz, tar bz2 and tar xz round trip to the same entries with null data offsets`() {
        val plain = buildTar(
            listOf(
                file("hello.txt", "hello".toByteArray()),
                file("nested/data.bin", ByteArray(600)),
                dir("folder/"),
            ),
        )
        // The compressed listings must equal the plain one except that no offset into a
        // compressed stream is a seek target.
        val expected = list(plain).map { it.copy(dataOffset = null) }

        for (compression in listOf(TarCompression.GZIP, TarCompression.BZIP2, TarCompression.XZ)) {
            val entries = list(compress(plain, compression), compression)
            assertThat(entries).isEqualTo(expected)
        }
    }

    @Test
    fun `a five megabyte entry followed by a small one both list, with progress advancing through the skip`() {
        val tar = buildTar(
            listOf(file("big.bin", ByteArray(5_000_000)), file("tail.txt", "t".toByteArray())),
        )
        val progress = ArrayList<ArchiveScanProgress>()
        val entries = list(tar) { progress.add(it) }

        assertThat(entries.map { it.path }).containsExactly("big.bin", "tail.txt").inOrder()
        assertThat(entries.first().size).isEqualTo(5_000_000L)

        // The payload skip is where a progress report matters most - no header boundary fires
        // for megabytes - so there must be many reports, never moving backwards, and they must
        // have crossed most of the payload by the end. The total is the source's own size,
        // i.e. compressed bytes, which for an uncompressed tar is the tar itself.
        assertThat(progress.size).isAtLeast(10)
        assertThat(progress.map { it.bytesScanned }).isInOrder()
        assertThat(progress.last().bytesScanned).isAtLeast(4_000_000L)
        progress.forEach { assertThat(it.totalBytes).isEqualTo(tar.size.toLong()) }
    }

    @Test
    fun `an exception thrown from the progress callback aborts the scan`() {
        val tar = buildTar(listOf(file("a.txt", "hello".toByteArray())))
        val boom = IllegalStateException("stop right there")
        val thrown = runCatching { list(tar) { throw boom } }.exceptionOrNull()
        // The scan must not swallow the callback's exception - it is the cancellation path.
        assertThat(thrown).isSameInstanceAs(boom)
    }

    @Test
    fun `a gzip tar cut in half is corrupt, not a half listing`() {
        // Incompressible payload, so the cut lands inside the deflate stream's data rather
        // than letting the inflater finish early on compressible bytes.
        val random = Random(42)
        val payload = ByteArray(100_000).also { random.nextBytes(it) }
        val full = compress(
            buildTar(listOf(file("big.bin", payload), file("after.txt", "t".toByteArray()))),
            TarCompression.GZIP,
        )
        val cut = full.copyOfRange(0, full.size / 2)

        val thrown = runCatching { list(cut, TarCompression.GZIP) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ArchiveCorruptException::class.java)
    }

    @Test
    fun `an entry that climbs out of the archive root with dot dot is corrupt`() {
        val tar = buildTar(listOf(file("../evil.txt", "x".toByteArray()), file("ok.txt", "y".toByteArray())))
        val thrown = runCatching { list(tar) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ArchiveCorruptException::class.java)
        assertThat(thrown).hasMessageThat().contains("evil")
    }

    @Test
    fun `an archive of nothing but the two end blocks lists nothing`() {
        val tar = buildTar(emptyList())
        // Sanity on the fixture: a finished empty tar is exactly the two 512-byte zero blocks.
        assertThat(tar).hasLength(1024)
        assertThat(list(tar)).isEmpty()
    }

    @Test
    fun `gnu long names longer than one hundred characters round trip with honest offsets`() {
        val longName = "deeply/nested/" + "l".repeat(120) + ".txt"
        val tar = buildTar(listOf(file(longName, "x".toByteArray())), longFileMode = TarArchiveOutputStream.LONGFILE_GNU)
        val entries = list(tar)

        assertThat(entries).hasSize(1)
        assertThat(entries.single().path).isEqualTo(longName)

        // The GNU extension stores the long name as a hidden header + padded payload record
        // before the real header, so the data starts at 512 (extension header) + 512 (the
        // name) + 512 (the real header). The offset must have followed what the parser really
        // consumed, hidden records included - arithmetic that ignores them would be wrong.
        assertThat(entries.single().dataOffset).isEqualTo(1_536L)
    }

    @Test
    fun `symlinks and hard links are listed as size zero non directory entries`() {
        val tar = buildTar(
            listOf(
                symlink("shortcut.txt", "real.txt"),
                hardLink("alias.txt", "real.txt"),
                file("real.txt", "data".toByteArray()),
            ),
        )
        val entries = list(tar)

        assertThat(entries.map { it.path }).containsExactly("shortcut.txt", "alias.txt", "real.txt").inOrder()
        // Extraction safety is a later layer's concern; the listing's job is to admit they
        // exist, as files of no payload, without inventing a directory for them.
        assertThat(entries[0].isDirectory).isFalse()
        assertThat(entries[0].size).isEqualTo(0)
        assertThat(entries[1].isDirectory).isFalse()
        assertThat(entries[1].size).isEqualTo(0)
    }

    @Test
    fun `entry counts advance in the progress report as headers stream past`() {
        val fixtures = (0 until 1_500).map { file("f$it.txt", ByteArray(0)) }
        val tar = buildTar(fixtures)
        val progress = ArrayList<ArchiveScanProgress>()

        val entries = list(tar) { progress.add(it) }

        assertThat(entries).hasSize(1_500)
        // The 1000-entry reporting rule has fired somewhere past the first thousand headers.
        assertThat(progress.map { it.entriesScanned }.maxOf { it }).isAtLeast(1_000L)
        assertThat(progress.map { it.entriesScanned }).isInOrder()
    }

    @Test
    fun `forFileName recognizes every tar spelling and rejects what is not a tar`() {
        assertThat(TarCompression.forFileName("backup.tar")).isEqualTo(TarCompression.NONE)
        assertThat(TarCompression.forFileName("backup.tar.gz")).isEqualTo(TarCompression.GZIP)
        assertThat(TarCompression.forFileName("backup.tgz")).isEqualTo(TarCompression.GZIP)
        assertThat(TarCompression.forFileName("backup.tar.bz2")).isEqualTo(TarCompression.BZIP2)
        assertThat(TarCompression.forFileName("backup.tbz2")).isEqualTo(TarCompression.BZIP2)
        assertThat(TarCompression.forFileName("backup.tar.xz")).isEqualTo(TarCompression.XZ)
        assertThat(TarCompression.forFileName("backup.txz")).isEqualTo(TarCompression.XZ)
        // Names arrive from the wire with any case the uploader had.
        assertThat(TarCompression.forFileName("PHOTOS.TGZ")).isEqualTo(TarCompression.GZIP)
        assertThat(TarCompression.forFileName("archive.zip")).isNull()
        assertThat(TarCompression.forFileName("photo.jpeg")).isNull()
    }

    @Test
    fun `the entry cap is the documented two million - a bound, not a suggestion`() {
        // Far too many to exercise with real entries; what is being pinned is the number the
        // tarbomb guard refuses to go past, so a hostile archive cannot make the scan
        // unbounded in count.
        assertThat(TarArchive.MAX_ENTRIES).isEqualTo(2_000_000L)
        assertThat(TarArchive.READ_CHUNK_BYTES).isEqualTo(64 * 1024)
    }

    // --- fixtures --------------------------------------------------------------------------------

    private companion object {
        /** Tar stores seconds, so a millis value divisible by 1000 round trips exactly. */
        const val FIXED_MTIME = 1_700_000_012_000L
    }

    private class TarEntryFixture(
        val name: String,
        val content: ByteArray = ByteArray(0),
        val mtimeMillis: Long = FIXED_MTIME,
        val symbolicLinkTo: String? = null,
        val hardLinkTo: String? = null,
    )

    private fun file(name: String, content: ByteArray) = TarEntryFixture(name, content)

    private fun dir(name: String) = TarEntryFixture(name)

    private fun symlink(name: String, target: String) = TarEntryFixture(name, symbolicLinkTo = target)

    private fun hardLink(name: String, target: String) = TarEntryFixture(name, hardLinkTo = target)

    private fun buildTar(
        entries: List<TarEntryFixture>,
        longFileMode: Int = TarArchiveOutputStream.LONGFILE_ERROR,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(longFileMode)
            for (fixture in entries) {
                val entry = when {
                    fixture.symbolicLinkTo != null ->
                        TarArchiveEntry(fixture.name, TarArchiveEntry.LF_SYMLINK)
                            .apply { linkName = fixture.symbolicLinkTo }
                    fixture.hardLinkTo != null ->
                        TarArchiveEntry(fixture.name, TarArchiveEntry.LF_LINK)
                            .apply { linkName = fixture.hardLinkTo }
                    else ->
                        TarArchiveEntry(fixture.name).apply { size = fixture.content.size.toLong() }
                }
                // The explicit long overload: the synthetic modTime property would be the Date
                // variant, and the fixture carries epoch millis.
                entry.setModTime(fixture.mtimeMillis)
                tar.putArchiveEntry(entry)
                if (fixture.content.isNotEmpty()) tar.write(fixture.content)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun compress(tar: ByteArray, compression: TarCompression): ByteArray {
        if (compression == TarCompression.NONE) return tar
        val out = ByteArrayOutputStream()
        when (compression) {
            TarCompression.GZIP -> GzipCompressorOutputStream(out).use { it.write(tar) }
            TarCompression.BZIP2 -> BZip2CompressorOutputStream(out).use { it.write(tar) }
            TarCompression.XZ -> XZOutputStream(out, LZMA2Options()).use { it.write(tar) }
            TarCompression.NONE -> Unit
        }
        return out.toByteArray()
    }

    private fun list(
        bytes: ByteArray,
        compression: TarCompression = TarCompression.NONE,
        onProgress: (ArchiveScanProgress) -> Unit = {},
    ): List<ArchiveEntry> = runBlocking {
        TarArchive(ByteArrayByteSource(bytes)).listEntries(compression, onProgress)
    }

    /** Tar pads every payload up to a multiple of the 512-byte record. */
    private fun padded(size: Long): Long = ((size + 511) / 512) * 512
}
