package dev.eclipse.ssh.linux

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Tar fixtures for the userspace tests: a small but structurally complete rootfs tarball, built
 * with the same library the installer extracts with, so the tests exercise the real parsing path
 * rather than a hand-rolled byte layout that could agree with the installer's bugs.
 */
internal object TestTarballs {

    /**
     * Writes a minimal Ubuntu-Base-shaped tar.gz: `bin/`, `etc/{passwd,group,shadow}`,
     * `etc/apt/` (a directory Ubuntu Base ships and setup writes `sources.list` into), a
     * `/bin/bash` with the executable bit, and a symlink — everything the installer's entry-type
     * handling, the rootfs validator's manifest and the distribution manager's setup rewrites
     * touch.
     *
     * The trailing pad of zeros exists solely to carry the fixture past [RootfsValidator]'s
     * extracted-size floor: it gzip-compresses to almost nothing on disk but unpacks to more
     * bytes than a real rootfs would ever be mistaken for.
     *
     * @param linkerName which architecture's dynamic linker to ship; the wrong one on purpose is
     *   how the validator's architecture check is tested
     */
    fun writeRootfsFixture(target: File, linkerName: String = "ld-linux-aarch64.so.1"): File {
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                putDirectory(tar, "bin")
                putDirectory(tar, "etc")
                putDirectory(tar, "etc/apt")
                putDirectory(tar, "lib")
                putDirectory(tar, "usr")
                putDirectory(tar, "usr/bin")
                putDirectory(tar, "var/lib")
                putDirectory(tar, "usr/sbin")
                putFile(tar, "bin/bash", "fake shell\n".toByteArray(), mode = 0b111_101_101)
                putSymlink(tar, "bin/sh", "bash")
                putSymlink(tar, "usr/bin/env", "../../bin/bash")
                putFile(tar, "usr/bin/apt-get", "fake apt\n".toByteArray(), mode = 0b111_101_101)
                // Every program the repair prologue stats before it asks dpkg anything, read off the
                // same list the check uses so the two cannot drift. A real install put them there by
                // unpacking; a fixture that says it is Ubuntu-Base-shaped and has no `rm` is not, and
                // the check is right to say so on every test that runs setup.
                val shipped = setOf("usr/bin/env", "usr/bin/apt-get")
                UbuntuDistributionManager.ESSENTIAL_PROGRAMS
                    .map { it.removePrefix("/") }
                    .filterNot { it in shipped }
                    .forEach { putFile(tar, it, "fake ${it.substringAfterLast('/')}\n".toByteArray(), mode = 0b111_101_101) }
                putFile(tar, "lib/$linkerName", "fake linker\n".toByteArray(), mode = 0b111_101_101)
                putFile(tar, "etc/passwd", "root:x:0:0:root:/root:/bin/bash\ndaemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n".toByteArray())
                putFile(tar, "etc/group", "root:x:0:\ndaemon:x:1:\n".toByteArray())
                putFile(tar, "etc/shadow", "root:*:19850:0:99999:7:::\ndaemon:*:19850:0:99999:7:::\n".toByteArray())
                putFile(tar, "etc/apt/sources.list", "deb https://fixtures.invalid/ubuntu jammy main\n".toByteArray())
                putFile(tar, "var/lib/rootfs-fixture.pad", ByteArray(3 * 1024 * 1024))
            }
        }
        return target
    }

    /**
     * A tarball that downloads, verifies and extracts cleanly — and is still not a rootfs: it has
     * no shell, no linker, no apt. What [RootfsValidator] exists to catch, in one fixture.
     */
    fun writeSparseFixture(target: File): File {
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                putDirectory(tar, "etc")
                putFile(tar, "etc/passwd", "root:x:0:0:root:/root:/bin/bash\n".toByteArray())
            }
        }
        return target
    }

    /**
     * A tarball whose one entry tries to climb out of the extraction directory. The hash is
     * computed by the caller, because the point of the test is that a *verified* archive still
     * cannot escape — the guard must hold on its own, not as a side effect of the pin.
     */
    fun writeEscapingFixture(target: File): File {
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                putFile(tar, "../escaped.txt", "outside\n".toByteArray())
            }
        }
        return target
    }

    /**
     * The rootfs fixture plus the trees a real Ubuntu Base image ships and no repair may write:
     * the user's own `/home` and `/root`, the package database under `/var/lib/dpkg`, and the SSH
     * host keys under `/etc/ssh`.
     *
     * Built by copying [writeRootfsFixture]'s entries through one by one rather than by hand-building
     * a second rootfs, so the repair suites stay on the same base tree as the installer's own: a
     * change there reaches them, instead of the two drifting until a repair test passes against a
     * rootfs no install produces.
     *
     * @param dpkgStatus what the archive's `var/lib/dpkg/status` holds, or null to ship no such
     *   member. The two are different tests — a database the repair rung has to take from the archive,
     *   and an archive that cannot supply one at all — and this is the switch between them.
     */
    fun writeRepairFixture(target: File, dpkgStatus: String? = dpkgStatusText()): File {
        val base = writeRootfsFixture(target.parentFile!!.resolve("base.tar.gz"))
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { out ->
                openTarStream(base, 64 * 1024).use { source ->
                    while (true) {
                        val entry = source.nextTarEntry ?: break
                        out.putArchiveEntry(entry)
                        // Exactly the entry's own bytes: the writer refuses to close an entry whose
                        // size does not match what was written under it.
                        if (entry.isFile) source.copyTo(out)
                        out.closeArchiveEntry()
                    }
                }
                putDirectory(out, "home")
                putDirectory(out, "home/ubuntu")
                putFile(out, "home/ubuntu/notes.txt", "my notes\n".toByteArray())
                putDirectory(out, "root")
                putFile(out, "root/.bashrc", "alias ll='ls -l'\n".toByteArray())
                putDirectory(out, "var/lib/dpkg")
                if (dpkgStatus != null) putFile(out, "var/lib/dpkg/status", dpkgStatus.toByteArray())
                putDirectory(out, "etc/ssh")
                putFile(out, "etc/ssh/sshd_config", "Port 22\n".toByteArray())
            }
        }
        return target
    }

    /**
     * A `status` file with the shape the installer's reader demands of one: records, past its
     * 512-byte floor, ending where a record ends. A test that needs a *readable* database should not
     * have to know what that floor is — or to discover, as one 21-byte fixture did, that a file dpkg
     * would accept is one this reader calls truncated.
     *
     * The padding is the description field's own continuation lines rather than filler bytes outside
     * a field: a file whose records do not parse is the opposite of what this is for.
     */
    fun dpkgStatusText(vararg packages: String): String = buildString {
        for (name in packages.ifEmpty { arrayOf("base-files") }) {
            append("Package: $name\n")
            append("Status: install ok installed\n")
            append("Priority: required\n")
            append("Architecture: arm64\n")
            append("Version: 12ubuntu4\n")
            append("Description: fixture package $name\n")
            append(" " + "a continuation line long enough to carry the file past the reader's floor. ".repeat(8) + "\n")
        }
    }

    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

    /**
     * A distro pointing at the fixture: the URL never resolves, the downloader is faked. The size
     * defaults to 0 — "unknown", which skips the size gates — because most tests only care about
     * the extraction; the gate tests pass a real number.
     */
    fun fixtureDistro(
        url: String,
        sha256: String,
        rootfsSizeBytes: Long = 0L,
        ubuntuArch: String = "arm64",
    ): LinuxDistro =
        LinuxDistro(
            id = "ubuntu-22.04",
            displayName = "Ubuntu 22.04 LTS",
            release = "jammy",
            ubuntuArch = ubuntuArch,
            rootfsTarballUrl = url,
            rootfsSha256 = sha256,
            rootfsSizeBytes = rootfsSizeBytes,
        )

    /** A downloader that serves [source] regardless of the URL asked for. */
    fun serving(source: File): HttpDownloader =
        HttpDownloader { _, target, onChunk ->
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onChunk(received, source.length())
                    }
                }
            }
        }

    private fun putDirectory(tar: TarArchiveOutputStream, name: String) {
        val entry = TarArchiveEntry("$name/")
        entry.mode = 0b111_101_101
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }

    private fun putFile(tar: TarArchiveOutputStream, name: String, content: ByteArray, mode: Int = 0b110_100_100) {
        val entry = TarArchiveEntry(name)
        entry.size = content.size.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    private fun putSymlink(tar: TarArchiveOutputStream, name: String, target: String) {
        // The link-type constructor is what makes this a symlink entry; setting linkName alone
        // would leave it typed as a regular file, which is exactly the bug the installer's
        // per-type handling would silently forgive.
        val entry = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
        entry.linkName = target
        entry.size = 0
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }

    /**
     * A hardlink entry whose target appears *later* in the same tarball. GNU tar emits these for
     * files that were hardlinked at packaging time but ordered after their link names; the
     * installer copies from an already-extracted target, so a forward link is skipped — and the
     * skip must be named, not silent.
     */
    private fun putForwardHardlink(tar: TarArchiveOutputStream, name: String, notYetExtractedTarget: String) {
        val entry = TarArchiveEntry(name, TarArchiveEntry.LF_LINK)
        entry.linkName = notYetExtractedTarget
        entry.size = 0
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }

    /**
     * The rootfs fixture with one forward hardlink added before the entry it names — the shape
     * [RootfsInstaller]'s warning path exists for.
     */
    fun writeRootfsFixtureWithForwardHardlink(target: File): File {
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                putDirectory(tar, "bin")
                putDirectory(tar, "etc")
                putDirectory(tar, "etc/apt")
                putDirectory(tar, "lib")
                putDirectory(tar, "usr")
                putDirectory(tar, "usr/bin")
                putDirectory(tar, "var/lib")
                putFile(tar, "bin/bash", "fake shell\n".toByteArray(), mode = 0b111_101_101)
                putSymlink(tar, "bin/sh", "bash")
                putSymlink(tar, "usr/bin/env", "../../bin/bash")
                // The link precedes its target: extraction sees it before bin/later-entry exists.
                putForwardHardlink(tar, "bin/hardlinked-later", "bin/later-entry")
                putFile(tar, "bin/later-entry", "later\n".toByteArray())
                putFile(tar, "usr/bin/apt-get", "fake apt\n".toByteArray(), mode = 0b111_101_101)
                putFile(tar, "lib/ld-linux-aarch64.so.1", "fake linker\n".toByteArray(), mode = 0b111_101_101)
                putFile(tar, "etc/passwd", "root:x:0:0:root:/root:/bin/bash\ndaemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n".toByteArray())
                putFile(tar, "etc/group", "root:x:0:\ndaemon:x:1:\n".toByteArray())
                putFile(tar, "etc/shadow", "root:*:19850:0:99999:7:::\ndaemon:*:19850:0:99999:7:::\n".toByteArray())
                putFile(tar, "etc/apt/sources.list", "deb https://fixtures.invalid/ubuntu jammy main\n".toByteArray())
                putFile(tar, "var/lib/rootfs-fixture.pad", ByteArray(3 * 1024 * 1024))
            }
        }
        return target
    }
}
