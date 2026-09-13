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
     * Writes a minimal Ubuntu-Base-shaped tar.gz: `bin/`, `etc/{passwd,group,shadow}`, a
     * `/bin/bash` with the executable bit, and a symlink — everything the installer's entry-type
     * handling and the distribution manager's setup rewrites touch.
     */
    fun writeRootfsFixture(target: File): File {
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                putDirectory(tar, "bin")
                putDirectory(tar, "etc")
                putDirectory(tar, "usr")
                putDirectory(tar, "usr/bin")
                putFile(tar, "bin/bash", "fake shell\n".toByteArray(), mode = 0b111_101_101)
                putFile(tar, "etc/passwd", "root:x:0:0:root:/root:/bin/bash\ndaemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n".toByteArray())
                putFile(tar, "etc/group", "root:x:0:\ndaemon:x:1:\n".toByteArray())
                putFile(tar, "etc/shadow", "root:*:19850:0:99999:7:::\ndaemon:*:19850:0:99999:7:::\n".toByteArray())
                putSymlink(tar, "usr/bin/env", "../../bin/bash")
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

    /** A distro pointing at the fixture: the URL never resolves, the downloader is faked. */
    fun fixtureDistro(url: String, sha256: String): LinuxDistro =
        LinuxDistro(
            id = "ubuntu-22.04",
            displayName = "Ubuntu 22.04 LTS",
            ubuntuArch = "arm64",
            rootfsTarballUrl = url,
            rootfsSha256 = sha256,
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
}
