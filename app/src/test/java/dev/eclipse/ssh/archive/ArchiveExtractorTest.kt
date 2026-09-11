package dev.eclipse.ssh.archive

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Test

/**
 * The extract layer's contract: chosen entries out, the archive stays remote-shaped, and a
 * hostile member is refused without abandoning the honest ones beside it.
 *
 * The destination is an in-memory tree ([MemoryDestination]) because the contract under test is
 * the engine's - which entries move, which are refused and why - not SAF's; the destination
 * interface exists precisely so the two concerns stay separable. The ZIP fixtures are the same
 * [ZipOutputStream] interop the listing tests use, and the hostile names come from the same
 * hand-built central directory ([HandZip]) the bounds tests use, for the same reason: no library
 * writer will emit a `..` path, which is exactly the shape the safety layer exists to refuse.
 *
 * What is deliberately NOT here yet: extracting from the TAR family. That lands with the
 * streaming pass, and until it does the engine's answer is an explicit Failed naming the gap -
 * a contract this suite pins, so the interim answer cannot quietly become permanent without a
 * test noticing.
 */
class ArchiveExtractorTest {

    /** The in-memory destination: created files keyed by their safe paths, folders as a set. */
    private class MemoryDestination : ArchiveExtractor.Destination {
        val files = mutableMapOf<String, ByteArray>()
        val folders = mutableSetOf<String>()

        override suspend fun ensureFolder(path: String): Boolean {
            if (path.isNotEmpty()) folders.add(path)
            return true
        }

        override suspend fun openFile(path: String): OutputStream? {
            val buffer = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) {
                    buffer.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    buffer.write(b, off, len)
                }

                override fun close() {
                    files[path] = buffer.toByteArray()
                }
            }
        }
    }

    @Test
    fun `a stored entry extracts to its exact bytes`() = runBlocking {
        val zip = zipOf(storedEntry("data.bin", "0123456789".toByteArray()))
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(10L))
        assertThat(destination.files["data.bin"]!!.decodeToString()).isEqualTo("0123456789")
    }

    @Test
    fun `a deflated entry extracts to its original bytes`() = runBlocking {
        val zip = zipOf(ZipEntry("hello.txt") to "Hello, archive!".toByteArray())
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(15L))
        assertThat(destination.files["hello.txt"]!!.decodeToString()).isEqualTo("Hello, archive!")
    }

    @Test
    fun `a nested entry creates its folder chain`() = runBlocking {
        val zip = zipOf(ZipEntry("a/b/c.txt") to "deep".toByteArray())
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(4L))
        assertThat(destination.files["a/b/c.txt"]!!.decodeToString()).isEqualTo("deep")
        assertThat(destination.folders).contains("a/b")
    }

    @Test
    fun `a directory entry reports extracted without any bytes`() = runBlocking {
        val zip = zipOf(ZipEntry("dir/") to ByteArray(0))
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(0L))
        assertThat(destination.folders).contains("dir")
        assertThat(destination.files).isEmpty()
    }

    @Test
    fun `extraction is selective - only the asked-for entries move`() = runBlocking {
        val zip = zipOf(
            ZipEntry("wanted.txt") to "yes".toByteArray(),
            ZipEntry("skipped.txt") to "no".toByteArray(),
        )
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(
            ArchiveReader.Format.ZIP,
            source,
            listOf(entries.first { it.path == "wanted.txt" }),
            destination,
        )

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(3L))
        assertThat(destination.files.keys).containsExactly("wanted.txt")
    }

    @Test
    fun `a hostile path is refused while the honest entry beside it extracts`() = runBlocking {
        // Hand-built, because no library writer emits a path-escaping name - the very shape the
        // safety layer exists to refuse.
        val zip = HandZip().apply {
            add("../../escape.txt".toByteArray(), "escape".toByteArray())
            add("honest.txt".toByteArray(), "honest".toByteArray())
        }.build()
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes[0]).isInstanceOf(ArchiveExtractor.Outcome.Refused::class.java)
        assertThat(outcomes[1]).isEqualTo(ArchiveExtractor.Outcome.Extracted(6L))
        assertThat(destination.files.keys).containsExactly("honest.txt")
    }

    @Test
    fun `an absolute path is refused as well`() = runBlocking {
        val zip = HandZip().apply {
            add("/etc/absolute.txt".toByteArray(), "abs".toByteArray())
        }.build()
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Refused("/etc/absolute.txt"))
        assertThat(destination.files).isEmpty()
    }

    @Test
    fun `a destination that cannot create a file reports destination refused and continues`() = runBlocking {
        val zip = zipOf(
            ZipEntry("first.txt") to "one".toByteArray(),
            ZipEntry("second.txt") to "two".toByteArray(),
        )
        val source = ByteArrayByteSource(zip)
        val entries = ArchiveReader.list(ArchiveReader.Format.ZIP, source)
        val destination = object : ArchiveExtractor.Destination {
            override suspend fun ensureFolder(path: String) = true
            override suspend fun openFile(path: String): OutputStream? =
                if (path == "first.txt") null else ByteArrayOutputStream()
        }

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.ZIP, source, entries, destination)

        assertThat(outcomes[0]).isInstanceOf(ArchiveExtractor.Outcome.DestinationRefused::class.java)
        assertThat(outcomes[1]).isInstanceOf(ArchiveExtractor.Outcome.Extracted::class.java)
    }

    @Test
    fun `a compressed tar reports the honest streaming answer for now`() = runBlocking {
        // TAR extract lands with the streaming pass; until then the contract is an explicit
        // Failed that names the gap, never a silent no-op and never a full download.
        val tar = compress(buildTar(listOf(file("file.txt", "tar content".toByteArray()))), TarCompression.GZIP)
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR_GZ, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR_GZ, source, entries, destination)

        assertThat(outcomes).hasSize(1)
        assertThat(outcomes[0]).isInstanceOf(ArchiveExtractor.Outcome.Failed::class.java)
        assertThat(destination.files).isEmpty()
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures - the same shapes the listing tests use, so the extract suite cannot drift from
    // what the engines actually produce.
    // ---------------------------------------------------------------------------------------

    private fun zipOf(vararg entries: Pair<ZipEntry, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (entry, bytes) ->
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun storedEntry(name: String, bytes: ByteArray): Pair<ZipEntry, ByteArray> {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        entry.crc = crc.value
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        return entry to bytes
    }

    private fun file(name: String, content: ByteArray) = TarEntryFixture(name, content)

    private data class TarEntryFixture(val name: String, val content: ByteArray)

    private fun buildTar(entries: List<TarEntryFixture>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            for (fixture in entries) {
                val entry = TarArchiveEntry(fixture.name).apply { size = fixture.content.size.toLong() }
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
            else -> throw IllegalArgumentException("This suite only compresses with gzip")
        }
        return out.toByteArray()
    }
}
