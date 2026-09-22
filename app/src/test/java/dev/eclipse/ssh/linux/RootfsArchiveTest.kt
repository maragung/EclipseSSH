package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.junit.Test

/**
 * The archive half of import and export, on trees and streams rather than on a device.
 *
 * That it can be tested this way at all is the reason [RootfsArchive] holds no Android type: every
 * property these tests check — the borrowed mounts staying out of an export, a symlink surviving as
 * a link, a traversal being refused, a tarball of photographs being refused — is a property of the
 * bytes. A test that needed a running userspace to ask about bytes would be testing the wrong
 * layer, and would not be able to ask the questions that matter about untrusted input at all.
 */
class RootfsArchiveTest {

    /** One archive member as it was read: the header fields these tests assert on. */
    private data class Member(
        val name: String,
        val mode: Int,
        val linkFlag: Byte,
        val linkName: String,
        val size: Long,
    )

    private fun newTree(): File =
        Files.createTempDirectory("rootfs-archive").toFile().apply { deleteOnExit() }

    /**
     * A rootfs-shaped tree on disk: every manifest path [RootfsValidator] requires, one of each file
     * shape an archive has to carry (a plain file, an executable, a relative symlink), the user's
     * workspace, and a pad.
     *
     * The pad is what makes the tree a tree rather than a handful of text files: the validator
     * refuses anything under two mebibytes, and the real images it was written for unpack to tens.
     */
    private fun rootfsTree(
        target: File,
        osRelease: String? = null,
        padBytes: Int = 3 * 1024 * 1024,
    ): File {
        target.mkdirs()
        writeExecutable(File(target, "bin/bash"), "fake shell\n")
        // bin/sh is a link to bin/bash because that is the shape the real images ship, and it is
        // the one shape an archive can get wrong: a link written as a copy of its target still
        // passes every existence check and is only visible as a difference in the restored tree.
        Files.createSymbolicLink(File(target, "bin/sh").toPath(), Paths.get("bash"))
        writeExecutable(File(target, "usr/bin/apt-get"), "fake apt\n")
        writeExecutable(File(target, "usr/bin/env"), "fake env\n")
        writeExecutable(File(target, "usr/lib/ld-linux-aarch64.so.1"), "fake linker\n")
        File(target, "etc/apt").mkdirs()
        File(target, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(target, "etc/apt/sources.list").writeText("deb https://fixtures.invalid/ubuntu jammy main\n")
        File(target, "home/ubuntu/workspace").mkdirs()
        File(target, "home/ubuntu/workspace/notes.txt").writeText("the user's own file\n")
        if (osRelease != null) File(target, "etc/os-release").writeText(osRelease)
        if (padBytes > 0) {
            // Under `var/lib`, where a real rootfs keeps the package database this tree is standing in
            // for — and created first, because the pad is the one member here no helper makes a
            // directory for.
            File(target, "var/lib").mkdirs()
            File(target, "var/lib/pad").writeBytes(ByteArray(padBytes))
        }
        return target
    }

    /**
     * The three names proot binds the device's own over, as a tree really holds them: a few stubs no
     * session ever sees. This is what an export must leave out, so the tests put them in.
     */
    private fun addBorrowedMounts(tree: File) {
        for (path in listOf("dev/null", "proc/uptime", "sys/kernel/notes")) {
            val file = File(tree, path)
            file.parentFile?.mkdirs()
            file.writeText("the device's own $path\n")
        }
    }

    private fun writeExecutable(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setExecutable(true, false)
    }

    private suspend fun exportBytes(tree: File, onReport: (RootfsArchive.Report) -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        onReport(RootfsArchive(tree).export(output))
        return output.toByteArray()
    }

    /**
     * The archive's members, read back through the same gzip-aware opener the import uses — so a
     * test cannot pass because it read the file differently from the code under test.
     *
     * Only headers are read: the tar stream skips over an entry's content on the next call, and
     * nothing these tests assert on is inside the bytes.
     */
    private fun membersOf(bytes: ByteArray): List<Member> =
        openTarStream(ByteArrayInputStream(bytes), 64 * 1024).use { tar ->
            buildList {
                while (true) {
                    val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                    add(
                        Member(
                            name = entry.name,
                            mode = entry.mode,
                            linkFlag = entry.linkFlag,
                            linkName = entry.linkName ?: "",
                            size = entry.size,
                        ),
                    )
                }
            }
        }

    /**
     * A budget no fixture here can reach. The budget has its own test; every other test wants it out
     * of the way, because a tree that unpacks to more than ten times the archive it came from is
     * exactly what a padded fixture is.
     */
    private val generousBudget = 64L * 1024 * 1024

    @Test
    fun `export carries the guest tree and the workspace, and none of the borrowed mounts`() = runTest {
        val tree = rootfsTree(newTree())
        addBorrowedMounts(tree)
        var report: RootfsArchive.Report? = null

        val members = membersOf(exportBytes(tree) { report = it })
        // Names as the archive writes them carry a directory's trailing slash, and this test is about
        // which *paths* are in the file: the borrowed mounts' contents are what must not be, while
        // the three names themselves must be, as directories. Compared without the slash so the two
        // are told apart by what they mean rather than by a spelling the format adds.
        val names = members.map { it.name.removeSuffix("/") }

        assertThat(names).containsAtLeast(
            "bin/bash",
            "bin/sh",
            "etc/passwd",
            "home/ubuntu/workspace/notes.txt",
            "usr/lib/ld-linux-aarch64.so.1",
        )
        // The user's own file is the most valuable thing in the tree by a distance; an export that
        // left the workspace out would be a backup of the operating system instead of the userspace.
        assertThat(names).contains("home/ubuntu/workspace/notes.txt")
        // The device's own /dev, /proc and /sys never reach the file, in either direction: the
        // stubs stay out, and the three names come back as the empty mount points proot's binds
        // land on, so a restored tree still has somewhere for them to land.
        assertThat(names.filter { it.startsWith("dev/") || it.startsWith("proc/") || it.startsWith("sys/") })
            .isEmpty()
        assertThat(names).containsAtLeast("dev", "proc", "sys")
        assertThat(members.filter { it.name.removeSuffix("/") in setOf("dev", "proc", "sys") }.map { it.linkFlag })
            .containsExactly(TarArchiveEntry.LF_DIR, TarArchiveEntry.LF_DIR, TarArchiveEntry.LF_DIR)

        // The executable bit is the one mode that matters inside a userspace, and it is the one the
        // host's own permissions cannot be trusted to carry over a copy owned by a single uid.
        val bash = members.first { it.name == "bin/bash" }
        assertThat(bash.mode and 0b001_001_001).isNotEqualTo(0)
        // A link is an entry, not a detour into its target's files, and it stays a link.
        val sh = members.first { it.name == "bin/sh" }
        assertThat(sh.linkFlag).isEqualTo(TarArchiveEntry.LF_SYMLINK)
        assertThat(sh.linkName).isEqualTo("bash")

        assertThat(report?.warnings).isEmpty()
        assertThat(report?.entries).isEqualTo(members.size)
        assertThat(report?.bytes).isGreaterThan(0L)
    }

    @Test
    fun `a round trip restores the tree, its modes and its symlinks`() = runTest {
        val source = rootfsTree(newTree())
        addBorrowedMounts(source)
        val target = newTree()

        val report = RootfsArchive(target).extract(ByteArrayInputStream(exportBytes(source)), generousBudget)

        assertThat(File(target, "bin/bash").readText()).isEqualTo("fake shell\n")
        assertThat(File(target, "bin/bash").canExecute()).isTrue()
        assertThat(File(target, "home/ubuntu/workspace/notes.txt").readText())
            .isEqualTo("the user's own file\n")
        // A link, not a copy of what it named: identical bytes on both sides would be a tree that
        // grows with every hardlinked-a-large-file case, and a `bin/sh` that is a real file is one
        // apt will not upgrade the way it upgrades the shell it points at.
        assertThat(Files.isSymbolicLink(File(target, "bin/sh").toPath())).isTrue()
        assertThat(Files.readSymbolicLink(File(target, "bin/sh").toPath()).toString()).isEqualTo("bash")
        // The borrowed names are back as directories and nothing else: what proot mounts over them
        // arrives at session start, and a stub archived from the exporting device would be a file
        // the restoring device's session never sees and never removes.
        assertThat(File(target, "dev").isDirectory).isTrue()
        assertThat(File(target, "dev/null").exists()).isFalse()
        assertThat(File(target, "sys/kernel/notes").exists()).isFalse()

        assertThat(report.warnings).isEmpty()
        assertThat(report.bytes).isAtLeast(3L * 1024 * 1024)
    }

    @Test
    fun `extract refuses an entry that climbs out of the root`() = runTest {
        // One directory up, held privately, so "did anything land outside?" has an unambiguous
        // answer: the escape target would be this directory's own escaped.txt.
        val parent = newTree()
        val staging = File(parent, "staging")
        val escape = TestTarballs.writeEscapingFixture(File(parent, "escaping.tar.gz"))

        val failure = runCatching {
            RootfsArchive(staging).extract(escape.inputStream(), generousBudget)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("escapes the extraction directory")
        assertThat(File(parent, "escaped.txt").exists()).isFalse()
    }

    @Test
    fun `extract refuses an archive that is not a userspace`() = runTest {
        val staging = newTree()
        val sparse = TestTarballs.writeSparseFixture(File(staging.parentFile, "sparse-${staging.name}.tar.gz"))

        val failure = runCatching {
            RootfsArchive(staging).extract(sparse.inputStream(), generousBudget)
        }.exceptionOrNull()

        // A tarball of somebody's photographs extracts perfectly and is not a userspace. Refused
        // here, before anything is swapped, rather than five minutes later as apt failing in a
        // rootfs with no shell.
        assertThat(failure).isInstanceOf(IOException::class.java)
        val message = failure!!.message.orEmpty()
        assertThat(message).contains("not a Linux userspace")
        assertThat(message).contains("bin/sh")
    }

    @Test
    fun `extract refuses an archive that expands past its budget`() = runTest {
        val staging = newTree()
        // The pinned fixture is a padded one: three mebibytes of zeros that gzip to a few hundred
        // bytes, which is precisely the shape the budget exists for.
        val fixture = TestTarballs.writeRootfsFixture(File(staging.parentFile, "padded-${staging.name}.tar.gz"))

        val failure = runCatching {
            RootfsArchive(staging).extract(
                fixture.inputStream(),
                budgetBytes = 1024L * 1024,
                sourceBytes = fixture.length(),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("expands beyond the expected size")
    }

    @Test
    fun `export refuses a tree that is not there`() = runTest {
        val absent = File(newTree(), "no-such-rootfs")

        val failure = runCatching { RootfsArchive(absent).export(ByteArrayOutputStream()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("there is no root filesystem")
    }

    @Test
    fun `verify refuses an archive of another Ubuntu release, and of another system`() = runTest {
        // jammy is what the fixture distro is; the archive says noble, so the setup pipeline would
        // rewrite noble's apt sources into it and produce a rootfs that installs nothing.
        val otherRelease = rootfsTree(
            newTree(),
            osRelease = "ID=ubuntu\nVERSION_CODENAME=noble\n",
        )
        val otherSystem = rootfsTree(
            newTree(),
            osRelease = "ID=debian\nVERSION_CODENAME=bookworm\n",
        )
        val distro = TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", sha256 = "unused")

        val releaseFindings = RootfsArchive(otherRelease).verify(distro)
        val systemFindings = RootfsArchive(otherSystem).verify(distro)

        assertThat(releaseFindings.joinToString("; ")).contains("noble")
        assertThat(releaseFindings.joinToString("; ")).contains("jammy")
        assertThat(systemFindings.joinToString("; ")).contains("debian")
    }

    @Test
    fun `verify accepts a tree that does not say which release it is`() {
        // "Cannot tell" must not read as "wrong system": a hand-built or hand-trimmed userspace has
        // no os-release at all, and this check is not entitled to refuse it for that.
        val tree = rootfsTree(newTree())
        val distro = TestTarballs.fixtureDistro("https://fixtures.invalid/rootfs.tar.gz", sha256 = "unused")

        assertThat(File(tree, "etc/os-release").exists()).isFalse()
        assertThat(RootfsArchive(tree).verify(distro)).isEmpty()
    }
}
