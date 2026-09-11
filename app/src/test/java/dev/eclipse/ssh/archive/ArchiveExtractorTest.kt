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
 * The TAR family is pinned through the same destination and the same fixtures: extraction is one
 * forward pass over the stream - each wanted payload carried out as it passes, the rest skipped -
 * so N entries cost one walk, never N. The contract this half of the suite holds down: outcomes
 * come back in the order the entries were ASKED for (the archive's own order is its business),
 * and an entry the pass never meets is named as a Failed miss, never silently dropped from the
 * tally. A hostile `..` path cannot be asked for here because the TAR listing refuses to list one
 * at all - the ZIP hand-built fixture above stays the safety layer's coverage for both families.
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
        // runBlocking returns its last expression; JUnit needs void, so land on Unit explicitly.
        Unit
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
        Unit
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
    fun `a compressed tar entry extracts to its exact bytes`() = runBlocking {
        // The streaming pass, pinned from the outside: what comes out of the .tar.gz is exactly
        // what went into it, and the outcome says how many bytes moved.
        val tar = compress(buildTar(listOf(file("file.txt", "tar content".toByteArray()))), TarCompression.GZIP)
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR_GZ, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR_GZ, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(11L))
        assertThat(destination.files["file.txt"]!!.decodeToString()).isEqualTo("tar content")
    }

    @Test
    fun `a plain tar extracts its entries in the order asked`() = runBlocking {
        // Uncompressed TAR rides the same one-pass walk, and the outcomes follow the CALLER's
        // order, not the archive's - so asking in reverse is the honest way to pin that.
        val tar = buildTar(
            listOf(
                file("first.txt", "one".toByteArray()),
                file("second.txt", "two bytes".toByteArray()),
            ),
        )
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR, source, entries.reversed(), destination)

        assertThat(outcomes).containsExactly(
            ArchiveExtractor.Outcome.Extracted(9L),
            ArchiveExtractor.Outcome.Extracted(3L),
        ).inOrder()
        assertThat(destination.files["first.txt"]!!.decodeToString()).isEqualTo("one")
        assertThat(destination.files["second.txt"]!!.decodeToString()).isEqualTo("two bytes")
    }

    @Test
    fun `tar extraction is selective - the skipped entry costs a skip, not a copy`() = runBlocking {
        // The pass walks past every entry either way; the contract is that an unwanted payload is
        // discarded on the way by, never written to the destination.
        val tar = compress(
            buildTar(
                listOf(
                    file("wanted.txt", "yes".toByteArray()),
                    file("skipped.txt", "no".toByteArray()),
                ),
            ),
            TarCompression.GZIP,
        )
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR_GZ, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(
            ArchiveReader.Format.TAR_GZ,
            source,
            listOf(entries.first { it.path == "wanted.txt" }),
            destination,
        )

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(3L))
        assertThat(destination.files.keys).containsExactly("wanted.txt")
        Unit
    }

    @Test
    fun `a nested tar entry creates its folder chain`() = runBlocking {
        val tar = buildTar(listOf(file("a/b/c.txt", "deep".toByteArray())))
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(4L))
        assertThat(destination.files["a/b/c.txt"]!!.decodeToString()).isEqualTo("deep")
        assertThat(destination.folders).contains("a/b")
    }

    @Test
    fun `a directory member in a tar reports extracted without any bytes`() = runBlocking {
        val tar = buildTar(listOf(dirEntry("dir")))
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR, source)
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR, source, entries, destination)

        assertThat(outcomes).containsExactly(ArchiveExtractor.Outcome.Extracted(0L))
        assertThat(destination.folders).contains("dir")
        assertThat(destination.files).isEmpty()
    }

    @Test
    fun `a tar destination that cannot create a file reports destination refused and continues`() = runBlocking {
        val tar = compress(
            buildTar(
                listOf(
                    file("first.txt", "one".toByteArray()),
                    file("second.txt", "two".toByteArray()),
                ),
            ),
            TarCompression.GZIP,
        )
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR_GZ, source)
        val destination = object : ArchiveExtractor.Destination {
            override suspend fun ensureFolder(path: String) = true
            override suspend fun openFile(path: String): OutputStream? =
                if (path == "first.txt") null else ByteArrayOutputStream()
        }

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR_GZ, source, entries, destination)

        assertThat(outcomes[0]).isInstanceOf(ArchiveExtractor.Outcome.DestinationRefused::class.java)
        assertThat(outcomes[1]).isInstanceOf(ArchiveExtractor.Outcome.Extracted::class.java)
    }

    @Test
    fun `an entry the tar pass never meets is reported as failed`() = runBlocking {
        // Not a hostile archive - a listing that no longer matches the bytes under it. The pass
        // ends without meeting the path, and the tally still adds up: one Failed naming the miss,
        // never a silent drop.
        val tar = compress(buildTar(listOf(file("file.txt", "tar content".toByteArray()))), TarCompression.GZIP)
        val source = ByteArrayByteSource(tar)
        val entries = ArchiveReader.list(ArchiveReader.Format.TAR_GZ, source)
        val ghost = entries.first().copy(path = "ghost.txt")
        val destination = MemoryDestination()

        val outcomes = ArchiveExtractor.extract(ArchiveReader.Format.TAR_GZ, source, listOf(ghost), destination)

        assertThat(outcomes).hasSize(1)
        val failed = outcomes[0]
        assertThat(failed).isInstanceOf(ArchiveExtractor.Outcome.Failed::class.java)
        assertThat((failed as ArchiveExtractor.Outcome.Failed).reason).contains("not in the archive")
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

    /**
     * A directory member. The trailing slash is not decoration - it is the only thing that makes
     * commons-compress's [TarArchiveEntry] String constructor write the link flag that says
     * "directory", which is what the listing and the extract pass both key on.
     */
    private fun dirEntry(name: String) = TarEntryFixture("$name/", ByteArray(0))

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
