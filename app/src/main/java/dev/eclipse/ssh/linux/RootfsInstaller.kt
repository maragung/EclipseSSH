package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Downloads, verifies and extracts a pinned rootfs tarball — the "RootfsManager" of the userspace.
 *
 * Security posture: the tarball's SHA256 is checked *before* extraction begins, so a tampered or
 * truncated download never reaches the filesystem. The extractor then refuses any entry that
 * tries to escape the target directory — `../` components or absolute paths — which is the
 * classic tar-parsing attack surface, and while the source here is hash-pinned rather than
 * hostile, the guard means the pin and the parser agree on what they guarantee.
 *
 * Extraction lands in a staging directory and is moved into place with a rename, so a rootfs that
 * exists is always a complete one: a failed or interrupted install leaves nothing half-extracted
 * under the real name.
 *
 * Permissions: Android forbids `chown`, so every extracted file is owned by the app uid no matter
 * what the tarball claims — which is also why the rootfs needs no privilege model of its own (see
 * [ProotRuntime]). What the extractor *does* preserve is the mode bits that matter to userspace:
 * the executable bit on binaries, and the read/write bits the tools inside check.
 */
class RootfsInstaller(
    private val rootDir: File,
    private val distro: LinuxDistro,
    private val downloader: HttpDownloader = UrlConnectionDownloader(),
) {
    /** Where the tarball is downloaded to before verification. */
    val tarballFile: File get() = File(rootDir, "rootfs-${distro.ubuntuArch}.tar.gz")

    /** Where the rootfs is unpacked before being moved into place. */
    private val stagingDir: File get() = File(rootDir, "rootfs.staging")

    /** The completed, in-place rootfs. */
    val rootfsDir: File get() = File(rootDir, "rootfs")

    /**
     * Progress through the install, for the UI's install screen. Byte-precise for the download
     * (which has a known total), step-precise for extraction.
     */
    sealed interface Progress {
        /** Downloading; [received] of [total] bytes. */
        data class Downloading(val received: Long, val total: Long) : Progress

        /** Verifying the tarball's SHA256. */
        data object Verifying : Progress

        /** Extracting; [entries] unpacked so far. */
        data class Extracting(val entries: Int) : Progress
    }

    /**
     * Whether the rootfs is present and plausibly complete: the staging directory is gone and the
     * rootfs holds a `/bin` directory. Deliberately cheap — the real health check is the
     * distribution manager's probe, which actually runs a shell.
     */
    fun isExtracted(): Boolean = !stagingDir.exists() && File(rootfsDir, "bin").isDirectory

    /**
     * Deletes the rootfs and every installer artifact (tarball, staging).
     *
     * Does not touch the workspace: workspace survival across uninstall is the manager's decision
     * to make, and this class has no opinion about it.
     */
    fun deleteRootfs() {
        tarballFile.delete()
        rootfsDir.deleteRecursively()
        stagingDir.deleteRecursively()
    }

    /**
     * The full rootfs install: download → verify → extract → move into place.
     *
     * @param onProgress invoked on [Dispatchers.IO] with each progress update; must be cheap
     * @throws IOException on any network, verification or extraction failure
     */
    suspend fun install(onProgress: suspend (Progress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        download(onProgress)
        onProgress(Progress.Verifying)
        verify()
        extract(onProgress)
        moveIntoPlace()
        rootfsDir
    }

    private suspend fun download(onProgress: suspend (Progress) -> Unit) {
        if (tarballFile.isFile && verifyFileSha256(tarballFile, distro.rootfsSha256)) {
            // A previously verified tarball is reused, not re-downloaded: an interrupted install
            // resumes rather than starting its 30 MB download again.
            return
        }
        val temp = File(rootDir, tarballFile.name + ".part")
        try {
            downloader.download(
                distro.rootfsTarballUrl,
                temp,
                onChunk = { received, total ->
                    ensureActive()
                    onProgress(Progress.Downloading(received, total))
                },
            )
            verifyFileSha256(temp, distro.rootfsSha256)
            if (!temp.renameTo(tarballFile)) {
                temp.copyTo(tarballFile, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    private fun verify() {
        if (!verifyFileSha256(tarballFile, distro.rootfsSha256)) {
            throw IOException("Rootfs checksum mismatch for ${distro.displayName}")
        }
    }

    /**
     * Extracts the tarball into staging, then swaps staging into place. A rootfs that exists is
     * therefore always complete — see the class doc.
     */
    private suspend fun extract(onProgress: suspend (Progress) -> Unit) {
        onProgress(Progress.Extracting(0))
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        var entries = 0
        TarArchiveInputStream(tarballFile.inputStream().buffered(DOWNLOAD_BUFFER)).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = resolveInside(stagingDir, entry.name)
                // 0 is LF_OLDNORM - NUL is the pre-POSIX encoding of "regular file" and real
                // tarballs (Ubuntu Base included) still emit it for early entries.
                when (entry.linkFlag) {
                    TarArchiveEntry.LF_DIR -> {
                        target.mkdirs()
                    }
                    TarArchiveEntry.LF_SYMLINK -> {
                        target.parentFile?.mkdirs()
                        // Created and deleted: if a previous extraction (or the rootfs itself)
                        // already put something there, the symlink must replace it.
                        target.delete()
                        kotlin.runCatching { target.deleteRecursively() }
                        java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                    }
                    TarArchiveEntry.LF_LINK -> {
                        target.parentFile?.mkdirs()
                        val source = resolveInside(stagingDir, entry.linkName)
                        // Hardlinks inside one tarball are duplicates of an earlier entry; copying
                        // is the portable equivalent and costs one file.
                        if (source.isFile) source.copyTo(target, overwrite = true)
                    }
                    TarArchiveEntry.LF_NORMAL, 0 -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> tar.copyTo(output) }
                        applyMode(target, entry.mode)
                    }
                    else -> {
                        // Device nodes, fifos and the like: the rootfs does not need them — proot
                        // binds the host's /dev — and creating them would require privileges the
                        // app does not have anyway.
                    }
                }
                entries++
                if (entries % PROGRESS_EVERY_ENTRIES == 0) {
                    ensureActive()
                    onProgress(Progress.Extracting(entries))
                }
            }
        }
        onProgress(Progress.Extracting(entries))
    }

    private fun moveIntoPlace() {
        rootfsDir.deleteRecursively()
        if (!stagingDir.renameTo(rootfsDir)) {
            // Same-filesystem rename only fails for reasons we cannot fix here; report rather than
            // half-move.
            throw IOException("Could not move the extracted rootfs into place at $rootfsDir")
        }
        // The tarball has served its purpose; keeping it would pin 30 MB for nothing.
        tarballFile.delete()
    }

    /**
     * Resolves [name] inside [root], refusing anything that escapes it — the shared guard in
     * [resolveInsideRoot], whose doc explains why extraction and checking live apart.
     */
    private fun resolveInside(root: File, name: String): File = resolveInsideRoot(root, name)

    /**
     * Applies the mode bits that matter under Android: the executable bit from the tar entry, read
     * and write for the owner. Group/other bits are dropped — they are meaningless when every
     * file has the same owner, and `setExecutable(false, false)` on a directory would break
     * traversal.
     */
    private fun applyMode(target: File, mode: Int) {
        if (target.isDirectory) return
        val executable = (mode and 0b001_001_001) != 0
        target.setExecutable(executable, true)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun verifyFileSha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        return actual == expected
    }

    companion object {
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val PROGRESS_EVERY_ENTRIES = 200
    }
}

/** A byte-range-progressing HTTP(S) GET; an interface so tests never touch the network. */
fun interface HttpDownloader {
    /**
     * Downloads [url] into [target], reporting each [onChunk] with the bytes received so far and
     * the total when the server names one (0 when it does not).
     */
    fun download(url: String, target: File, onChunk: (received: Long, total: Long) -> Unit)
}

/** The production downloader, on `HttpsURLConnection`. */
class UrlConnectionDownloader : HttpDownloader {
    override fun download(url: String, target: File, onChunk: (received: Long, total: Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("downloading $url failed: HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onChunk(received, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
