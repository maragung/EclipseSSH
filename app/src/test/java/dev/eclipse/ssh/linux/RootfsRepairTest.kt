package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The repairs a broken userspace can be given from its own pinned archive: what comparing the rootfs
 * with the archive reports, what putting the archive's members back writes, and what reclaiming space
 * is allowed to take.
 *
 * Every test installs a real rootfs from a real tarball first, because that is the state each repair
 * is defined against — and because a successful install deletes the tarball on purpose, so the
 * repairs that need the archive's bytes have to fetch it again from the pin. The download counter in
 * [Installed] is what says whether they did.
 *
 * The fixture is [TestTarballs.writeRepairFixture]: the stock rootfs re-written with the trees a real
 * Ubuntu Base image ships and no repair may write (`/home`, `/var/lib/dpkg`, `/etc/ssh`, `/root`).
 * That is not decoration: "the archive carries this member and the scan still calls the rootfs whole"
 * is the only shape in which the preservation rule can be tested at all, because a name the archive
 * never mentions is a name the scan never looks at.
 */
class RootfsRepairTest {

    /**
     * A rootfs installed from [FIXTURE], the installer built over it, and how many times that
     * installer has fetched the pinned tarball — including the install's own download, so the count
     * starts at one.
     */
    private class Installed(val root: File, val installer: RootfsInstaller, val downloads: () -> Int)

    private fun newRoot(): File = Files.createTempDirectory("linux-repair").toFile().apply { deleteOnExit() }

    /** Writes [content] at [file], creating the directories above it — tarball entries do not. */
    private fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /** Writes [content] at [file], creating the directories above it. */
    private fun writeFile(file: File, content: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    /** The entry names directly under [dir], for assertions about what a reclaim left behind. */
    private fun childrenOf(dir: File): List<String> = dir.listFiles()?.map { it.name } ?: emptyList()

    /** A rootfs installed from [FIXTURE], with the download counter that install started. */
    private suspend fun installFixture(): Installed {
        val root = newRoot()
        var downloads = 0
        val installer = RootfsInstaller(
            root,
            TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)),
            HttpDownloader { url, target, onChunk ->
                downloads++
                TestTarballs.serving(FIXTURE).download(url, target, onChunk)
            },
        )
        installer.install()
        return Installed(root, installer) { downloads }
    }

    @Test
    fun `a deleted program is reported damaged, and a healthy rootfs scans whole`() = runBlocking {
        val installed = installFixture()
        val installer = installed.installer

        // What the scan is about is checked rather than assumed: if the archive stopped carrying the
        // programs dpkg needs before it will run, every assertion below would pass for the wrong reason.
        assertThat(archiveMemberNames(FIXTURE)).containsAtLeast("usr/bin/rm", "bin/bash")

        val healthy = installer.inspectAgainstPinnedArchive()
        assertThat(healthy.whole).isTrue()
        assertThat(healthy.damaged).isEmpty()
        assertThat(healthy.examined).isGreaterThan(0)

        val rm = installer.rootfsDir.resolve("usr/bin/rm")
        assertThat(rm.delete()).isTrue()

        val damaged = installer.inspectAgainstPinnedArchive()
        assertThat(damaged.whole).isFalse()
        // Names are archive-relative, without a leading slash or "./": that is the spelling
        // restoreFromPinnedTarball takes them back in, so no call site has to translate.
        assertThat(damaged.damaged).containsExactly("usr/bin/rm")
        // The scan walks the archive, not the disk, so a missing file costs nothing but its own lookup.
        assertThat(damaged.examined).isEqualTo(healthy.examined)
        assertThat(damaged.describe()).contains("usr/bin/rm")

        // Two fetches for three passes over the archive: the install downloaded (and then deleted) the
        // tarball, the first scan re-fetched it, and the second scan found it still there. That is the
        // repair path's deliberate difference from the install path — re-downloading 30 MB on every
        // rung would be worse than pinning it.
        assertThat(installed.downloads()).isEqualTo(2)
    }

    @Test
    fun `a deleted file under a preserved tree is not damage`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val userFiles = listOf(
            "home/ubuntu/notes.txt",
            "var/lib/dpkg/status",
            "etc/ssh/sshd_config",
            "root/.bashrc",
        )

        // The premise of the whole test: the archive really does carry these members, so their absence
        // from `damaged` below is the exclusion doing its work and not a name it never examined.
        assertThat(archiveMemberNames(FIXTURE)).containsAtLeastElementsIn(userFiles)

        for (name in userFiles) {
            assertThat(File(rootfs, name).delete()).isTrue()
        }

        val integrity = installed.installer.inspectAgainstPinnedArchive()

        // A rootfs missing four of the archive's members and still called whole — because those four
        // are exactly the ones a repair must never write: the user's files, the package database that
        // records what they installed, and the host keys every `known_hosts` entry outside points at.
        assertThat(integrity.damaged).isEmpty()
        assertThat(integrity.whole).isTrue()
        assertThat(integrity.describe()).contains("all present")
    }

    @Test
    fun `restoring a deleted program puts back its bytes and its mode, and only it`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        assertThat(File(rootfs, "usr/bin/rm").delete()).isTrue()
        assertThat(File(rootfs, "usr/bin/tar").delete()).isTrue()

        val restored = installed.installer.restoreFromPinnedTarball(setOf("usr/bin/rm"))

        assertThat(restored).containsExactly("usr/bin/rm")
        val rm = File(rootfs, "usr/bin/rm")
        assertThat(rm.readText()).isEqualTo("fake rm\n")
        // The executable bit is half of what "not found in PATH or not executable" means, so a restore
        // that wrote the bytes without the mode would leave dpkg exactly as broken as it found it.
        assertThat(rm.canExecute()).isTrue()
        // Only the named member: a restore that swept the whole archive would be the overlay, and the
        // member it was not asked for is how that difference becomes visible.
        assertThat(File(rootfs, "usr/bin/tar").exists()).isFalse()
        // Kept, not consumed: the next rung of the ladder works from the same file.
        assertThat(installed.installer.tarballFile.isFile).isTrue()
        assertThat(installed.downloads()).isEqualTo(2)
    }

    @Test
    fun `a file whose bytes were replaced still scans whole, and the overlay writes the archive's back`() =
        runBlocking {
            val installed = installFixture()
            val passwd = installed.installer.rootfsDir.resolve("etc/passwd")
            val shipped = passwd.readText()
            passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
            assertThat(passwd.readText()).isNotEqualTo(shipped)

            // Presence, kind and the executable bit are the whole scan. A rootfs the user has run
            // `apt-get upgrade` in legitimately differs from the pin byte for byte, so a byte
            // comparison would condemn a healthy, updated system — and the repair it triggered would
            // rewrite that system's base for no fault.
            val integrity = installed.installer.inspectAgainstPinnedArchive()
            assertThat(integrity.whole).isTrue()
            assertThat(integrity.damaged).doesNotContain("etc/passwd")

            // Which is why the repaired state needs the rung that does not care what the scan thinks.
            val written = installed.installer.overlayFromPinnedArchive()
            assertThat(written).isGreaterThan(0)
            assertThat(passwd.readText()).isEqualTo(shipped)
        }

    @Test
    fun `a directory standing where the archive names a file is replaced by the file`() = runBlocking {
        val installed = installFixture()
        val rm = installed.installer.rootfsDir.resolve("usr/bin/rm")
        assertThat(rm.delete()).isTrue()
        // A half-finished unpack, or the user's own mkdir: either way a tree now stands under a name
        // the archive spells as a file, and File.delete() cannot clear a non-empty one.
        writeFile(File(rm, "junk"), "junk\n")
        assertThat(rm.isDirectory).isTrue()

        installed.installer.overlayFromPinnedArchive()

        // The one case where the archive's own bytes cannot be written any other way, so the tree goes:
        // a repair that skipped it would leave the member exactly as damaged as it found it.
        assertThat(rm.isFile).isTrue()
        assertThat(rm.readText()).isEqualTo("fake rm\n")
        assertThat(rm.canExecute()).isTrue()
        assertThat(File(rm, "junk").exists()).isFalse()
    }

    @Test
    fun `the overlay rewrites the base system and leaves the user's data alone`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val project = File(rootfs, "home/ubuntu/workspace/project.txt")
        writeFile(project, "my project\n")
        // A directory the archive names and does not own the contents of, so an overlay that "made
        // sure" the directory existed by clearing it would take this file with it.
        val installedPackage = File(rootfs, "var/lib/left-by-a-package")
        writeFile(installedPackage, "installed\n")
        // And the one name the archive *does* carry: /var/lib/dpkg is the record of what is installed,
        // so writing the archive's copy back would untrack every package the user ever added while
        // leaving those packages' files on disk — a worse state than the one being repaired.
        val status = File(rootfs, "var/lib/dpkg/status")
        writeFile(status, "Package: mine\n")
        val passwd = File(rootfs, "etc/passwd")
        val shipped = passwd.readText()
        passwd.writeText("broken\n")

        installed.installer.overlayFromPinnedArchive()

        // The base system is the archive's again...
        assertThat(passwd.readText()).isEqualTo(shipped)
        // ...and nothing the user owns moved, byte for byte.
        assertThat(project.readText()).isEqualTo("my project\n")
        assertThat(installedPackage.readText()).isEqualTo("installed\n")
        assertThat(status.readText()).isEqualTo("Package: mine\n")
    }

    @Test
    fun `reclaiming space empties the caches and keeps the directories and the user's files`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val project = File(rootfs, "home/ubuntu/workspace/project.txt")
        writeFile(project, "my project\n")
        val status = File(rootfs, "var/lib/dpkg/status")
        writeFile(status, "Package: mine\n")

        // What apt rebuilds for itself on its next run, at the sizes the count below is about.
        val caches = listOf(
            "var/cache/apt/archives/foo.deb" to 4096,
            "var/cache/apt/pkgcache.bin" to 64,
            "var/cache/apt/srcpkgcache.bin" to 32,
            "var/lib/apt/lists/bar" to 2048,
            "tmp/scratch" to 1024,
            "var/tmp/scratch" to 512,
            "var/log/apt/term.log.1" to 256,
            "var/log/syslog.1" to 128,
        )
        var cacheBytes = 0L
        for ((path, size) in caches) {
            writeFile(File(rootfs, path), ByteArray(size))
            cacheBytes += size
        }
        // A live log is evidence of the failure being repaired; only rotation's leftovers are expendable.
        val liveLog = File(rootfs, "var/log/syslog")
        writeFile(liveLog, ByteArray(8192))
        // A download fragment a failed fetch left behind: never a resume point, only bytes pinned.
        val fragment = File(File(installed.root, "downloads"), "rootfs-arm64.tar.gz.part")
        writeFile(fragment, ByteArray(777))

        val freed = installed.installer.reclaimSpace()

        // The answer is what is actually gone, not what was attempted: a repair must not claim space
        // it did not free, and it must not fail over a file that will not delete.
        assertThat(freed).isEqualTo(cacheBytes + 777L)
        // Emptied, never deleted: both directories are part of the base system's structure and apt
        // expects them to exist, and a repair that removed them would leave a rootfs no restore puts
        // back — tmp and var/tmp are the archive's own members.
        assertThat(File(rootfs, "var/cache/apt/archives").isDirectory).isTrue()
        assertThat(File(rootfs, "var/lib/apt/lists").isDirectory).isTrue()
        assertThat(File(rootfs, "tmp").isDirectory).isTrue()
        assertThat(File(rootfs, "var/tmp").isDirectory).isTrue()
        assertThat(childrenOf(File(rootfs, "var/cache/apt/archives"))).isEmpty()
        assertThat(childrenOf(File(rootfs, "var/lib/apt/lists"))).isEmpty()
        assertThat(childrenOf(File(rootfs, "tmp"))).isEmpty()
        assertThat(childrenOf(File(rootfs, "var/tmp"))).isEmpty()
        assertThat(File(rootfs, "var/log/apt/term.log.1").exists()).isFalse()
        assertThat(File(rootfs, "var/log/syslog.1").exists()).isFalse()
        assertThat(liveLog.isFile).isTrue()
        assertThat(fragment.exists()).isFalse()
        // And nothing the user owns was touched.
        assertThat(project.readText()).isEqualTo("my project\n")
        assertThat(status.readText()).isEqualTo("Package: mine\n")
    }

    @Test
    fun `reclaiming space never follows a link out of the rootfs`() = runBlocking {
        val installed = installFixture()
        val rootfs = installed.installer.rootfsDir
        val outside = Files.createTempDirectory("linux-outside").toFile().apply { deleteOnExit() }
        writeFile(File(outside, "secret.gz"), ByteArray(4096))
        writeFile(File(outside, "scratch"), ByteArray(2048))

        // A rootfs' links are absolute and guest-rooted: `/var/log -> /run` inside the guest names the
        // *device's* /run to every call the host can make, so following one takes a walk that means to
        // empty the guest's tmp out of the root and into the phone's own filesystem.
        Files.createSymbolicLink(File(rootfs, "var/log").toPath(), outside.toPath())
        Files.createSymbolicLink(File(rootfs, "tmp").toPath(), outside.toPath())

        val freed = installed.installer.reclaimSpace()

        // Both trees are still there, whole: the rotated log would have been found and deleted through
        // the link, and the count is the second half of the same fact — nothing outside the root was
        // mistaken for something the walk had found.
        assertThat(File(outside, "secret.gz").isFile).isTrue()
        assertThat(File(outside, "scratch").isFile).isTrue()
        assertThat(childrenOf(outside)).containsExactly("scratch", "secret.gz")
        assertThat(Files.isSymbolicLink(File(rootfs, "var/log").toPath())).isTrue()
        assertThat(Files.isSymbolicLink(File(rootfs, "tmp").toPath())).isTrue()
        assertThat(freed).isEqualTo(0L)
    }

    @Test
    fun `a parked rootfs is reclaimed only when a rootfs is in place`() = runBlocking {
        val installed = installFixture()
        val parked = File(installed.root, "rootfs.old")
        writeFile(File(parked, "lib/libc.so.6"), ByteArray(1024))
        writeFile(File(parked, "bin/bash"), ByteArray(2048))

        // The swap parked the previous rootfs beside the new one and then crashed; the new one is in
        // place and in use, so the parked tree is a duplicate holding a megabyte of nothing.
        assertThat(installed.installer.rootfsInPlace()).isTrue()
        assertThat(installed.installer.reclaimSpace()).isEqualTo(3072L)
        assertThat(parked.exists()).isFalse()

        // The other state entirely: no rootfs in place, so a parked tree is not a duplicate — it is the
        // user's only copy of everything they have, and deleting it would be the worst thing this
        // function could do.
        val bare = newRoot()
        val parkedBare = File(bare, "rootfs.old")
        writeFile(File(parkedBare, "home/ubuntu/keep.txt"), "keep me\n")
        val freshInstaller = RootfsInstaller(
            bare,
            TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)),
            HttpDownloader { _, _, _ -> throw IOException("this test never downloads") },
        )

        assertThat(freshInstaller.rootfsInPlace()).isFalse()
        assertThat(freshInstaller.reclaimSpace()).isEqualTo(0L)
        assertThat(File(parkedBare, "home/ubuntu/keep.txt").readText()).isEqualTo("keep me\n")
    }

    @Test
    fun `a repair that cannot fetch the archive says so instead of reporting the rootfs broken`() = runBlocking {
        val installed = installFixture()
        val offline = RootfsInstaller(
            installed.root,
            TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", TestTarballs.sha256(FIXTURE)),
            HttpDownloader { _, _, _ -> throw IOException("no route to host") },
        )
        // The state a phone with no network is in when the user asks it to repair: the install deleted
        // the tarball on purpose and the archive cannot be got again.
        assertThat(offline.tarballFile.exists()).isFalse()

        var thrown: PinnedArchiveUnavailable? = null
        try {
            offline.inspectAgainstPinnedArchive()
        } catch (e: PinnedArchiveUnavailable) {
            thrown = e
        }

        // Typed rather than a bare IOException because the ladder keys on exactly this to tell "this
        // rootfs is broken" from "this rootfs cannot be repaired right now" — and rebuilding for the
        // second would wipe a user's base system to no purpose.
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("no route to host")
    }

    companion object {
        /**
         * The stock [TestTarballs] rootfs, re-written with the preserved trees a real Ubuntu Base
         * image ships: the user's own `/home` and `/root`, the package database under `/var/lib/dpkg`,
         * and the SSH host keys under `/etc/ssh`.
         *
         * Built once, because a tarball is read-only and every test installs from the same bytes — which
         * is also what makes the pin in each test's distro the same pin.
         */
        private val FIXTURE: File by lazy {
            TestTarballs.writeRepairFixture(
                Files.createTempDirectory("linux-repair-fixture").toFile().resolve("rootfs.tar.gz"),
            )
        }

        /** Every member name the archive carries, as the installer spells them: archive-relative, no
         * leading slash, and a directory without its trailing one. */
        private fun archiveMemberNames(tarball: File): Set<String> {
            val names = mutableSetOf<String>()
            openTarStream(tarball, 64 * 1024).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    names += entry.name.removePrefix("./").trimEnd('/')
                }
            }
            return names
        }
    }
}
