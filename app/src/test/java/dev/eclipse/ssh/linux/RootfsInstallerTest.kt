package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The rootfs installer's three promises: only verified bytes are extracted, extraction cannot
 * escape the userspace directory, and a rootfs that exists is always a complete one.
 *
 * The fixtures are real tarballs built with the same commons-compress the installer reads with, so
 * these tests exercise the actual parsing path - a bug in the installer's entry handling shows up
 * here as a failed assertion about files, not as an agreement between two wrong implementations.
 */
class RootfsInstallerTest {

    private fun newRoot(): File = Files.createTempDirectory("linux-rootfs").toFile().apply { deleteOnExit() }

    private fun installInto(
        root: File,
        serving: File,
        sha256: String = TestTarballs.sha256(serving),
        distro: LinuxDistro = TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", sha256),
        downloader: HttpDownloader = TestTarballs.serving(serving),
    ): RootfsInstaller = RootfsInstaller(root, distro, downloader)

    @Test
    fun `a verified tarball extracts with files, modes and symlinks`() = runBlocking {
        val root = newRoot()
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        val installer = installInto(root, fixture)

        val progress = mutableListOf<RootfsInstaller.Progress>()
        installer.install { progress += it }

        assertThat(installer.isExtracted()).isTrue()
        val bash = installer.rootfsDir.resolve("bin/bash")
        assertThat(bash.readText()).isEqualTo("fake shell\n")
        // The executable bit is the one mode bit userspace actually reads; losing it turns every
        // binary in the rootfs into a permission denied.
        assertThat(bash.canExecute()).isTrue()
        assertThat(bash.canRead()).isTrue()
        assertThat(bash.canWrite()).isTrue()
        val env = installer.rootfsDir.resolve("usr/bin/env")
        assertThat(java.nio.file.Files.isSymbolicLink(env.toPath())).isTrue()
        assertThat(java.nio.file.Files.readSymbolicLink(env.toPath()).toString())
            .isEqualTo("../../bin/bash")

        // The tarball is consumed: a completed install must not pin its 30 MB for nothing.
        assertThat(installer.tarballFile.exists()).isFalse()
        assertThat(root.resolve("rootfs.staging").exists()).isFalse()

        // Progress covered the whole arc - download, verify, extract - so the install screen this
        // feeds can render a real trajectory instead of a spinner.
        assertThat(progress.first()).isInstanceOf(RootfsInstaller.Progress.Downloading::class.java)
        assertThat(progress.any { it is RootfsInstaller.Progress.Verifying }).isTrue()
        assertThat(progress.last()).isInstanceOf(RootfsInstaller.Progress.Extracting::class.java)
    }

    @Test
    fun `a checksum mismatch extracts nothing`() = runBlocking {
        val root = newRoot()
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        val installer = installInto(root, fixture, sha256 = "00".repeat(32))

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("checksum mismatch")
        // Nothing was extracted and nothing looks installed - the failure leaves no half-state.
        assertThat(installer.rootfsDir.exists()).isFalse()
        assertThat(installer.isExtracted()).isFalse()
    }

    @Test
    fun `a tar entry that escapes the extraction directory is refused`() = runBlocking {
        val root = newRoot()
        val escapeParent = Files.createTempDirectory("linux-escape").toFile()
        val fixture = TestTarballs.writeEscapingFixture(escapeParent.resolve("escape.tar.gz"))

        val installer = installInto(root, fixture)

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        // The guard fires even though this tarball was hash-verified: the pin and the path check
        // are independent promises, and this is the one that survives a loosened pin.
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("escapes")
        assertThat(root.resolve("escaped.txt").exists()).isFalse()
        assertThat(installer.rootfsDir.exists()).isFalse()
    }

    @Test
    fun `a failed download never looks extracted`() = runBlocking {
        val root = newRoot()
        var downloads = 0
        val failingDownloader = HttpDownloader { _, target, _ ->
            downloads++
            target.outputStream().use { it.write("truncated".toByteArray()) }
            throw IOException("connection reset mid-download")
        }
        val installer = RootfsInstaller(
            root,
            TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", "00".repeat(32)),
            failingDownloader,
        )

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(downloads).isEqualTo(1)
        assertThat(installer.isExtracted()).isFalse()
        // The partial .part file is cleaned up, not left to be mistaken for a resume point.
        assertThat(root.resolve("downloads/rootfs-arm64.tar.gz.part").exists()).isFalse()
    }

    @Test
    fun `an unwritable root fails before any download starts`() = runBlocking {
        val root = newRoot()
        root.mkdirs()
        root.setWritable(false)
        try {
            var downloads = 0
            val installer = RootfsInstaller(
                root,
                TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", "00".repeat(32)),
            ) { _, _, _ -> downloads++ }

            var thrown: IOException? = null
            try {
                installer.install { }
            } catch (e: IOException) {
                thrown = e
            }
            // The message prefix is a contract: the error taxonomy reads "runtime storage not
            // ready" as the storage failure, distinct from anything proot itself would report.
            assertThat(thrown).isNotNull()
            assertThat(thrown!!.message).startsWith("runtime storage not ready")
            // Nothing was fetched — the failure is named before the first byte, not after 30 MB.
            assertThat(downloads).isEqualTo(0)
        } finally {
            // The temp root's deleteOnExit cleanup needs the writability back.
            root.setWritable(true)
        }
    }

    @Test
    fun `low free space refuses before the download starts`() = runBlocking {
        val root = newRoot()
        var downloads = 0
        val installer = RootfsInstaller(
            root,
            TestTarballs.fixtureDistro(
                "https://fixtures.invalid/rootfs.tar.gz",
                "00".repeat(32),
                rootfsSizeBytes = 30L * 1024 * 1024,
            ),
            HttpDownloader { _, _, _ -> downloads++ },
            storage = RuntimeStorageManager(root, freeBytesProbe = { 100L * 1024 * 1024 }),
        )

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        // The prefix is the taxonomy's DiskFull marker — a phone out of space must be told that,
        // not handed a download that dies of ENOSPC half way through.
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).startsWith("Ubuntu needs")
        assertThat(downloads).isEqualTo(0)
    }

    @Test
    fun `a server reporting a size the pin does not recognize is refused`() = runBlocking {
        val root = newRoot()
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        // The pin claims a tarball four times the size of what is served — the shape of a URL
        // that stopped serving the file it was pinned to.
        val distro = TestTarballs.fixtureDistro(
            "https://fixtures.invalid/rootfs.tar.gz",
            TestTarballs.sha256(fixture),
            rootfsSizeBytes = fixture.length() * 4,
        )
        val installer = installInto(root, fixture, distro = distro)

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("serving something else")
        assertThat(installer.isExtracted()).isFalse()
    }

    @Test
    fun `a truncated download is refused before it can look verified`() = runBlocking {
        val root = newRoot()
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        val truncated = HttpDownloader { _, target, onChunk ->
            val all = fixture.readBytes()
            val delivered = all.size / 2
            target.outputStream().use { output -> output.write(all, 0, delivered) }
            onChunk(delivered.toLong(), all.size.toLong())
        }
        val installer = installInto(root, fixture, downloader = truncated)

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("truncated")
        assertThat(installer.isExtracted()).isFalse()
    }

    @Test
    fun `a tarball that extracts to an incomplete tree is refused before it is moved into place`() = runBlocking {
        val root = newRoot()
        // Downloads, verifies and extracts cleanly - and is still not a rootfs (no shell, no
        // linker, no apt). Only the validator can tell, and this is where its verdict lands.
        val sparse = TestTarballs.writeSparseFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("sparse.tar.gz"),
        )
        val installer = installInto(root, sparse)

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("failed validation")
        // The findings are named, so the failure is readable rather than mysterious.
        assertThat(thrown!!.message).contains("/bin/bash")
        // Nothing was moved into place, and the rejected staging tree is reclaimed rather than
        // left pinning the space the retry needs.
        assertThat(root.resolve("rootfs").exists()).isFalse()
        assertThat(root.resolve("rootfs.staging").exists()).isFalse()
        assertThat(installer.isExtracted()).isFalse()
    }

    @Test
    fun `a tarball that expands far beyond its pin is refused mid-extraction`() = runBlocking {
        val root = newRoot()
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        // The fixture gzips 3 MB of zeros down to almost nothing; a pin claiming half that
        // compressed size passes the content-length band (exactly at its 2x boundary) while the
        // extraction budget - 10x the pin - lands far below what actually unpacks. That is the
        // gzip-bomb shape: small download, huge tree.
        val distro = TestTarballs.fixtureDistro(
            "https://fixtures.invalid/rootfs.tar.gz",
            TestTarballs.sha256(fixture),
            rootfsSizeBytes = fixture.length() / 2,
        )
        val installer = installInto(root, fixture, distro = distro)

        var thrown: IOException? = null
        try {
            installer.install { }
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("expands beyond the expected size")
        // Nothing moved into place; the half-extracted staging tree stays for the retry to
        // reclaim, but the disk is not filled to the end of the bomb.
        assertThat(installer.rootfsDir.exists()).isFalse()
        assertThat(installer.isExtracted()).isFalse()
    }

    @Test
    fun `a verified tarball left by a failed install is not downloaded again`() = runBlocking {
        val root = newRoot()
        val escapeParent = Files.createTempDirectory("linux-escape").toFile()
        val fixture = TestTarballs.writeEscapingFixture(escapeParent.resolve("escape.tar.gz"))
        var downloads = 0
        val countingDownloader = HttpDownloader { url, target, onChunk ->
            downloads++
            TestTarballs.serving(fixture).download(url, target, onChunk)
        }
        val installer = installInto(root, fixture, downloader = countingDownloader)

        // First attempt: downloads, verifies, then fails at extraction (the escaping entry).
        runCatching { installer.install { } }
        assertThat(downloads).isEqualTo(1)
        assertThat(installer.tarballFile.isFile).isTrue()

        // Second attempt: the verified tarball is reused - the retry does not restart the download,
        // which on a metered phone connection is the difference between "retry" and "pay again".
        runCatching { installer.install { } }
        assertThat(downloads).isEqualTo(1)
    }
}
