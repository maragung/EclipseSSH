package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The two repairs that need nothing but the rootfs, tested as the primitives they are: the
 * package-manager locks an interrupted run left behind, the apt state that is regenerable and that a
 * repair now gives up only when the evidence names it, the guest directories nothing else puts back,
 * and the package database that no other rung may touch.
 *
 * These are the four failures the ladder used to be blind to. A killed apt leaves a lock *file* the
 * kernel has already released, and every dpkg and apt call after it refuses in a second — so every
 * rung failed identically and the deepest one rebuilt a userspace that was never broken. The
 * database is the other direction entirely: `var/lib/dpkg` is a preserved member, so no rung may
 * write it, and every rung needs it — the deadlock the last test below is about.
 *
 * Every test installs a real rootfs from a real archive first, for the reason the other repair suite
 * does: the state each repair is defined against is an installed userspace, and the archive's own
 * copy of a file is what half of these repairs end up writing.
 */
class RootfsLocalRepairTest {

    private class Installed(val installer: RootfsInstaller)

    private fun newRoot(): File = Files.createTempDirectory("linux-local-repair").toFile().apply { deleteOnExit() }

    /** Writes [content] at [file], creating the directories above it — tarball entries do not. */
    private fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /** Writes [size] bytes at [file], creating the directories above it. */
    private fun writeFile(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }

    /** The entries directly under [dir], for assertions about what a reclaim left behind. */
    private fun childrenOf(dir: File): List<String> = dir.listFiles()?.map { it.name } ?: emptyList()

    /**
     * A rootfs installed from the repair fixture whose archive carries [dpkgStatus] as its package
     * database — null for an archive that carries none at all, which is a different test and the
     * reason this is a parameter.
     */
    private suspend fun installFixture(dpkgStatus: String? = TestTarballs.dpkgStatusText()): Installed {
        val root = newRoot()
        val tarball = TestTarballs.writeRepairFixture(
            Files.createTempDirectory("linux-local-repair-fixture").toFile().resolve("rootfs.tar.gz"),
            dpkgStatus,
        )
        val installer = RootfsInstaller(
            root,
            TestTarballs.fixtureDistro(TARBALL_URL, TestTarballs.sha256(tarball)),
            HttpDownloader { url, target, onChunk -> TestTarballs.serving(tarball).download(url, target, onChunk) },
        )
        installer.install()
        return Installed(installer)
    }

    // ------------------------------------------------------------------ the locks

    @Test
    fun `a lock nothing holds is cleared, and one something holds is left where it is`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val stale = listOf("var/lib/dpkg/lock", "var/lib/apt/lists/lock")
        val live = "var/lib/dpkg/lock-frontend"
        for (name in stale + live) writeFile(File(rootfs, name), "")

        // A lock this process really holds — the one case the sweep must never delete. A FileChannel
        // takes the same POSIX record lock dpkg takes, so this is the kernel's own answer rather than
        // a mock of it, and the JVM's OverlappingFileLockException is what a second attempt in the
        // same process gets.
        val holder = RandomAccessFile(File(rootfs, live), "rw")
        val lock = holder.channel.lock()
        try {
            val sweep = installed.installer.clearStalePackageLocks()

            assertThat(sweep.removed).containsExactlyElementsIn(stale)
            assertThat(sweep.held).containsExactly(live)
            for (name in stale) assertThat(File(rootfs, name).exists()).isFalse()
            // Still there, still held: deleting a lock something is using would let two package tools
            // work on one database, which is the one thing in this feature that must never happen.
            assertThat(File(rootfs, live).isFile).isTrue()
        } finally {
            lock.release()
            holder.close()
        }
    }

    @Test
    fun `the probe reports a stale lock and takes nothing`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        writeFile(File(rootfs, "var/lib/dpkg/lock-frontend"), "")

        assertThat(installed.installer.stalePackageLocks()).containsExactly("var/lib/dpkg/lock-frontend")

        // Reported, not removed: the probe is a question, and the rung is the answer. A health check
        // that repaired what it found could not be run at any moment, which is what makes it useful.
        assertThat(File(rootfs, "var/lib/dpkg/lock-frontend").isFile).isTrue()
    }

    // ------------------------------------------------------------------ the guest directories

    @Test
    fun `a tmp that is a file is reported unusable and recreated as a real directory`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        // An installed userspace has both, so the fixture builds them here: a test that damaged a
        // directory the install never made would be testing its own fixture rather than the repair.
        writeFile(File(rootfs, "tmp/left-by-a-tool"), "scratch\n")
        File(rootfs, "run").mkdirs()
        assertThat(installed.installer.unusableGuestTemp()).isEmpty()

        // The two shapes a directory can be wrong in, and neither is "missing": a file where the
        // directory was, and a link that would take a write out of the rootfs entirely.
        deleteTreeNoFollow(File(rootfs, "tmp"))
        writeFile(File(rootfs, "tmp"), "not a directory\n")
        val outside = Files.createTempDirectory("linux-local-repair-outside").toFile().apply { deleteOnExit() }
        deleteTreeNoFollow(File(rootfs, "run"))
        Files.createSymbolicLink(File(rootfs, "run").toPath(), outside.toPath())

        assertThat(installed.installer.unusableGuestTemp()).containsExactly("tmp", "run")

        val recreated = installed.installer.ensureGuestTemp()

        assertThat(recreated).containsExactly("tmp", "run")
        assertThat(installed.installer.unusableGuestTemp()).isEmpty()
        assertThat(File(rootfs, "tmp").isDirectory).isTrue()
        assertThat(Files.isSymbolicLink(File(rootfs, "run").toPath())).isFalse()
        assertThat(File(rootfs, "run").isDirectory).isTrue()
        // Nothing was written through the link, and the tree it pointed at is exactly as it was.
        assertThat(childrenOf(outside)).isEmpty()
    }

    // ------------------------------------------------------------------ apt's own state

    /** The three trees this section is about, at sizes the byte counts below are built from. */
    private fun plantAptState(rootfs: File): Long {
        val caches = listOf(
            "var/cache/apt/archives/foo.deb" to 4096,
            "var/lib/apt/lists/bar" to 2048,
            "var/cache/apt/pkgcache.bin" to 64,
        )
        var bytes = 0L
        for ((path, size) in caches) {
            writeFile(File(rootfs, path), size)
            bytes += size
        }
        return bytes
    }

    @Test
    fun `apt's own state is left alone when nothing named it`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val planted = plantAptState(rootfs)

        // The premise of the zero below: there was something there to take, and the common repair
        // does not take it.
        assertThat(planted).isEqualTo(4096L + 2048L + 64L)

        // The whole point of the verdict, and the answer for the common repair: a failure that did not
        // name apt's trees leaves them where they are. This is what a press of Repair no longer costs
        // the user — the index was tens of megabytes to re-fetch, and the package cache is every byte
        // apt had already downloaded.
        val freed = installed.installer.clearAptState(AptDamage.NONE)

        assertThat(freed).isEqualTo(0L)
        assertThat(File(rootfs, "var/cache/apt/archives/foo.deb").length()).isEqualTo(4096)
        assertThat(File(rootfs, "var/lib/apt/lists/bar").length()).isEqualTo(2048)
        assertThat(File(rootfs, "var/cache/apt/pkgcache.bin").length()).isEqualTo(64)
        assertThat(installed.installer.aptIndexLooksDamaged()).isFalse()
        assertThat(File(rootfs, "home/ubuntu/notes.txt").readText()).isEqualTo("my notes\n")
    }

    @Test
    fun `an index apt cannot read costs one update, not the packages already downloaded`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val planted = plantAptState(rootfs)
        // What apt does not own, which is the whole difference between this and reclaimSpace: a
        // repair that is fetching a fresh index must not empty a scratch directory a running tool is
        // using, and a rotated log the user may still want to read is not its to take.
        writeFile(File(rootfs, "tmp/scratch"), 1024)
        writeFile(File(rootfs, "var/tmp/scratch"), 512)
        writeFile(File(rootfs, "var/log/syslog.1"), 128)

        // The index verdict: the lists and the binary caches built from them are re-fetched either
        // way, and the update that rebuilds them is nearly free when the lists are the only thing
        // missing. The `.deb` is not, which is why it stays.
        val freed = installed.installer.clearAptState(AptDamage.INDEX)

        assertThat(freed).isEqualTo(planted - 4096)
        assertThat(childrenOf(File(rootfs, "var/lib/apt/lists"))).isEmpty()
        assertThat(File(rootfs, "var/cache/apt/pkgcache.bin").exists()).isFalse()
        assertThat(File(rootfs, "var/cache/apt/archives/foo.deb").length()).isEqualTo(4096)
        // Emptied, never deleted: apt expects the directory to exist, and the next update fills it.
        assertThat(File(rootfs, "var/lib/apt/lists").isDirectory).isTrue()
        assertThat(File(rootfs, "tmp/scratch").length()).isEqualTo(1024)
        assertThat(File(rootfs, "var/tmp/scratch").length()).isEqualTo(512)
        assertThat(File(rootfs, "var/log/syslog.1").isFile).isTrue()
        assertThat(File(rootfs, "home/ubuntu/notes.txt").readText()).isEqualTo("my notes\n")
    }

    @Test
    fun `a package apt says is wrong is given up with the index, because it will not be rewritten`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val planted = plantAptState(rootfs)

        // `Hash Sum mismatch` is the case where the bytes are the failure: apt reports it for an index
        // and for a `.deb` in the same words, a corrupt `.deb` makes every install of that package fail
        // identically until it is gone, and unlike the index apt will not rewrite it on its own.
        val freed = installed.installer.clearAptState(AptDamage.INDEX_AND_CACHE)

        assertThat(freed).isEqualTo(planted)
        assertThat(childrenOf(File(rootfs, "var/lib/apt/lists"))).isEmpty()
        assertThat(childrenOf(File(rootfs, "var/cache/apt/archives"))).isEmpty()
        assertThat(File(rootfs, "var/cache/apt/archives").isDirectory).isTrue()
        assertThat(File(rootfs, "var/lib/apt/lists").isDirectory).isTrue()
    }

    @Test
    fun `the phrases apt prints for its own bookkeeping are the only ones that count`() {
        // apt's own refusal, verbatim, in the three shapes it takes. Matched case-insensitively
        // because the step's output is not normalised before it reaches this.
        assertThat(AptDamage.of("E: Unable to parse package file /var/lib/apt/lists/foo_Packages (1)"))
            .isEqualTo(AptDamage.INDEX)
        assertThat(AptDamage.of("E: Problem with MergeList /var/lib/apt/lists/bar"))
            .isEqualTo(AptDamage.INDEX)
        assertThat(AptDamage.of("E: Encountered a section with no Package: header"))
            .isEqualTo(AptDamage.INDEX)
        // A mismatch blames the bytes as well: it is the only phrase that justifies the second half.
        assertThat(AptDamage.of("E: Failed to fetch foo.deb\n  Hash Sum mismatch"))
            .isEqualTo(AptDamage.INDEX_AND_CACHE)
        assertThat(AptDamage.of("Size mismatch for bar.deb")).isEqualTo(AptDamage.INDEX_AND_CACHE)

        // Everything else leaves apt's trees alone, and these are the failures where that matters
        // most: each one is a repair the ladder goes on to answer some other way, and clearing the
        // index for any of them would buy a re-download of tens of megabytes and nothing else.
        assertThat(AptDamage.of("Could not resolve 'archive.ubuntu.com'")).isEqualTo(AptDamage.NONE)
        assertThat(AptDamage.of("Unable to fetch some archives, maybe run apt-get update")).isEqualTo(AptDamage.NONE)
        assertThat(AptDamage.of("E: Could not get lock /var/lib/dpkg/lock-frontend")).isEqualTo(AptDamage.NONE)
        assertThat(AptDamage.of("No space left on device")).isEqualTo(AptDamage.NONE)
        assertThat(AptDamage.of(null)).isEqualTo(AptDamage.NONE)
        assertThat(AptDamage.of("")).isEqualTo(AptDamage.NONE)
    }

    @Test
    fun `an index that was killed mid-update or half-written is damage the host can see`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val lists = File(rootfs, "var/lib/apt/lists")
        writeFile(File(lists, "archive.ubuntu.com_ubuntu_dists_noble_InRelease"), 4096)

        assertThat(installed.installer.aptIndexLooksDamaged()).isFalse()

        // A killed `apt-get update` leaves its staging directory populated, and the index it was
        // writing is exactly the one apt then refuses to parse. This is the shape no failure message
        // has to report, because it is answerable by looking.
        writeFile(File(lists, "partial/archive.ubuntu.com_ubuntu_dists_noble_main_binary-amd64_Packages"), 2048)
        assertThat(installed.installer.aptIndexLooksDamaged()).isTrue()

        // And the other shape: a write that never finished, which leaves the file it was writing.
        childrenOf(File(lists, "partial")).forEach { File(lists, "partial/$it").delete() }
        assertThat(installed.installer.aptIndexLooksDamaged()).isFalse()
        writeFile(File(lists, "archive.ubuntu.com_ubuntu_dists_noble_main_binary-amd64_Packages"), "")
        assertThat(installed.installer.aptIndexLooksDamaged()).isTrue()
    }

    // ------------------------------------------------------------------ the package database

    @Test
    fun `a database that reads as a database is left alone`() = runBlocking {
        val installed = installFixture()
        val status = File(installed.installer.rootfsDir, "var/lib/dpkg/status")
        val shipped = status.readText()

        // The common case, and the reason this rung has no opinion about most repairs: a database
        // that dpkg can open is not this rung's business, whatever else is broken.
        assertThat(installed.installer.restorePackageDatabase()).isNull()
        assertThat(status.readText()).isEqualTo(shipped)
        assertThat(File(installed.installer.rootfsDir, "var/lib/dpkg/status.broken").exists()).isFalse()
    }

    @Test
    fun `an unreadable database comes back from dpkg's own previous copy, which costs nothing`() = runBlocking {
        val installed = installFixture()
        val dpkg = File(installed.installer.rootfsDir, "var/lib/dpkg")
        val shipped = File(dpkg, "status").readText()
        File(dpkg, "status-old").writeText(shipped)
        // What a killed dpkg leaves: half a record, no final newline, far under the size a real
        // database has. Truncated rather than absent, which is the shape the preserved-member rule
        // made unfixable — no rung may write `var/lib/dpkg`, and every rung needs what is in it.
        val truncated = "Package: bash\nStatus: install ok instal"
        File(dpkg, "status").writeText(truncated)

        val source = installed.installer.restorePackageDatabase()

        // dpkg's own previous generation: the same database one write ago, so every package the user
        // installed is still recorded and nothing has to be reinstalled.
        assertThat(source).isEqualTo(PackageDatabaseSource.PREVIOUS)
        assertThat(File(dpkg, "status").readText()).isEqualTo(shipped)
        // Kept, not overwritten in place: it is the only remaining evidence of what the user had
        // installed, and a repair that threw it away to fix a file would be destroying the record.
        assertThat(File(dpkg, "status.broken").readText()).isEqualTo(truncated)
    }

    @Test
    fun `with no previous copy the archive's database is written instead`() = runBlocking {
        val archived = TestTarballs.dpkgStatusText("base-files")
        val installed = installFixture(dpkgStatus = archived)
        val dpkg = File(installed.installer.rootfsDir, "var/lib/dpkg")
        File(dpkg, "status").writeText("Package: bash\n")
        // No `status-old` anywhere: the state of a rootfs whose database was truncated by something
        // that had already overwritten dpkg's own backup — an image restore, a bad copy.

        val source = installed.installer.restorePackageDatabase()

        // The archive's copy, which is the base system's records and nothing else: the price the
        // rung's sentence tells the user about, and the reason it is a last resort rather than the
        // first thing tried. `/home` is untouched either way — the archive is only read here.
        assertThat(source).isEqualTo(PackageDatabaseSource.ARCHIVE)
        assertThat(File(dpkg, "status").readText()).isEqualTo(archived)
        assertThat(File(dpkg, "status.broken").readText()).isEqualTo("Package: bash\n")
    }

    @Test
    fun `a database no source can supply is refused, not quietly emptied`() = runBlocking {
        // An archive that carries no such member at all — a hand-built rootfs, or one whose dpkg was
        // stripped. Nothing else in the userspace has a copy, so there is no third source.
        val installed = installFixture(dpkgStatus = null)
        val dpkg = File(installed.installer.rootfsDir, "var/lib/dpkg")
        File(dpkg, "status").writeText("Package: bash\n")

        val failure = runCatching { installed.installer.restorePackageDatabase() }.exceptionOrNull()

        // Typed, because the ladder's gate reads exactly this to stop: no rung can supply what is
        // missing, so the honest answer is the rebuild it refuses to dress up as a repair.
        assertThat(failure).isInstanceOf(UserspaceFailure.PackageDatabaseUnreadable::class.java)
        assertThat(failure!!.message).contains("status")
        // The unreadable file was still parked rather than deleted: a repair that cannot replace what
        // it found does not get to throw it away first.
        assertThat(File(dpkg, "status.broken").readText()).isEqualTo("Package: bash\n")
    }

    private companion object {
        private const val TARBALL_URL = "https://fixtures.invalid/rootfs.tar.gz"
    }
}
