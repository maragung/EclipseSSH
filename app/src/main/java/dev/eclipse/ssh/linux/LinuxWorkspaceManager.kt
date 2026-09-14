package dev.eclipse.ssh.linux

import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * The workspace's own lifecycle: how big it is, what clearing it means, and how it survives an
 * uninstall.
 *
 * The workspace lives *inside* the rootfs (`/home/ubuntu/workspace`), which is what makes it a
 * workspace — every tool in the Ubuntu environment sees it as a plain directory in the user's
 * home. The consequence is that uninstalling the rootfs would delete it, so the keep-workspace
 * uninstall works by snapshot: [snapshotTo] tars the workspace to a file *outside* the userspace
 * directory, the uninstall deletes everything else, and the next install [restoreFrom]s the
 * snapshot before the first shell opens. The user's projects skip exactly one install.
 *
 * The snapshot is a plain gzipped tar the user could open on a desktop — a deliberate format
 * choice over anything opaque; it is their data.
 */
class LinuxWorkspaceManager(
    private val runtime: ProotRuntime,
    private val storage: RuntimeStorageManager = RuntimeStorageManager(runtime.rootDir),
) {
    /** The workspace, as an outer (Android) path. */
    val workspaceDir: File get() = File(storage.rootfsDir, "home/ubuntu/workspace")

    fun exists(): Boolean = workspaceDir.isDirectory

    /** Total size on disk, for the settings screen's storage line. Walks the tree; call off the UI thread. */
    fun sizeBytes(): Long = files().sumOf { it.length() }

    fun fileCount(): Long = files().count().toLong()

    private fun files(): Sequence<File> =
        if (workspaceDir.isDirectory) workspaceDir.walkTopDown().filter { it.isFile } else emptySequence()

    /** Empties the workspace but keeps the directory itself, so the mounted home never loses it. */
    fun clear() {
        // NOFOLLOW deletion: workspace content can contain symlinks (a project's node_modules
        // always does), and a follow-the-link delete would chase them out of the workspace.
        workspaceDir.listFiles()?.forEach { deleteTreeNoFollow(it) }
        workspaceDir.mkdirs()
    }

    /**
     * Writes the workspace to [target] as a gzipped tar. POSIX long-name mode: Android paths are
     * short but project trees are not, and GNU's `./PaxHeaders` noise would show up on desktop
     * extractors.
     */
    suspend fun snapshotTo(target: File): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        GZIPOutputStream(tmp.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown()
                        .filter { it != workspaceDir }
                        .forEach { file ->
                            val entry = TarArchiveEntry(file, file.relativeTo(workspaceDir).path)
                            if (file.canExecute()) entry.mode = entry.mode or 0b001_001_001
                            tar.putArchiveEntry(entry)
                            if (file.isFile) {
                                file.inputStream().use { input -> input.copyTo(tar) }
                            }
                            tar.closeArchiveEntry()
                        }
                }
            }
        }
        check(tmp.renameTo(target)) { "could not move the workspace snapshot into place at $target" }
        target
    }

    /**
     * Replaces the workspace's contents with [archive]'s. The directory is cleared first, so a
     * restore is deterministic; extraction goes through the shared [resolveInsideRoot] guard like
     * every other archive this app opens.
     */
    suspend fun restoreFrom(archive: File): Unit = withContext(Dispatchers.IO) {
        check(archive.isFile) { "no workspace snapshot at $archive" }
        clear()
        // Through the shared gzip-aware opener: this snapshot is written gzipped, and a raw
        // TarArchiveInputStream would read it as empty — after clear(), that is the user's
        // workspace deleted with nothing restored in its place.
        openTarStream(archive, 64 * 1024).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = resolveInsideRoot(workspaceDir, entry.name)
                when (entry.linkFlag) {
                    TarArchiveEntry.LF_DIR -> target.mkdirs()
                    TarArchiveEntry.LF_SYMLINK -> {
                        target.parentFile?.mkdirs()
                        // The snapshot is written by this app, but a moved or edited backup file
                        // must not become an escape hatch: same link-target rule as the rootfs.
                        resolveLinkInsideRoot(workspaceDir, entry.name, entry.linkName)
                        target.delete()
                        deleteTreeNoFollow(target)
                        java.nio.file.Files.createSymbolicLink(
                            target.toPath(),
                            java.nio.file.Path.of(entry.linkName),
                        )
                    }
                    TarArchiveEntry.LF_NORMAL, 0.toByte() -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> tar.copyTo(output) }
                        target.setExecutable((entry.mode and 0b001_001_001) != 0, true)
                    }
                    else -> {
                        // Device nodes and fifos cannot exist under filesDir and have no business
                        // in a workspace snapshot; skip rather than fail the whole restore.
                    }
                }
            }
        }
    }
}
