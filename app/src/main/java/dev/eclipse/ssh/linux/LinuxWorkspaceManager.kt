package dev.eclipse.ssh.linux

import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants

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

    /** Total size of the workspace's own content, for the settings screen's storage line. Walks the tree; call off the UI thread. */
    fun sizeBytes(): Long = content().sumOf { it.length() }

    /**
     * How many entries the workspace holds — the settings screen's file count, and the test the
     * keep-workspace uninstall uses to decide whether there is anything worth keeping.
     *
     * Links count here even though they add no bytes to [sizeBytes]: a workspace can hold nothing
     * but links (a project of symlinked directories), and a count that skipped them would read that
     * as an empty workspace and let a keep-workspace uninstall discard the user's data. Directories
     * do not count — they are structure, not content, and whatever writes into them recreates them.
     */
    fun fileCount(): Long = entries().count { !it.isDirectoryNoFollow() }.toLong()

    // walkTreeNoFollow rather than walkTopDown: `isFile` follows links, so a link pointing back
    // into the tree — or at a directory full of files — was counted as this workspace's own bytes,
    // and a self-referential link (`ln -s . loop`, which project tooling does leave behind) made
    // the walk never terminate.
    private fun entries(): Sequence<File> =
        if (workspaceDir.isDirectory) walkTreeNoFollow(workspaceDir) else emptySequence()

    // A symlink is not content: it holds a target path, and its target's bytes are this workspace's
    // only when the target is a real entry the walk also yields. Counting through the link is how
    // another tree's bytes — or one set of bytes twice, for a link pointing back inside — ended up
    // in the storage line.
    private fun content(): Sequence<File> = entries().filter { it.isRegularFileNoFollow() }

    /** Empties the workspace but keeps the directory itself, so the mounted home never loses it. */
    fun clear() {
        // NOFOLLOW deletion: workspace content can contain symlinks (a project's node_modules
        // always does), and a follow-the-link delete would chase them out of the workspace.
        workspaceDir.listFiles()?.forEach { deleteTreeNoFollow(it) }
        workspaceDir.mkdirs()
    }

    /**
     * Makes sure the workspace directory is there, and answers whether it had to be created.
     *
     * `/home` is a preserved member — no repair writes it, in place or from the archive — so a
     * workspace the user deleted stays deleted for as long as the userspace lives: every session then
     * opens in a home with no workspace in it, and nothing in the app puts it back short of an install
     * that rebuilds the whole rootfs. This is the cheap answer to that, and it belongs beside [clear]
     * because it is the same directory's lifecycle.
     *
     * A path that is a *symlink* is replaced rather than written through: the workspace is the one
     * directory the rest of the app assumes it can delete inside, and a link makes [clear] empty
     * whatever it points at — potentially something outside the rootfs entirely. The link is deleted
     * NOFOLLOW, so only the link goes.
     *
     * The answer is "it was not a plain directory and this had to do something", not "the directory
     * is there now": whether the `mkdirs` worked is a second question, and the caller has to ask it
     * anyway to know whether to tell the user their projects are gone or that even an empty
     * replacement could not be made.
     */
    fun ensureExists(): Boolean {
        val linked = java.nio.file.Files.isSymbolicLink(workspaceDir.toPath())
        if (workspaceDir.isDirectory && !linked) return false
        deleteTreeNoFollow(workspaceDir)
        workspaceDir.mkdirs()
        return true
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
                    // A link is written as a link, never as what it points at. Following one here
                    // would copy another tree's bytes into the user's snapshot, count them twice
                    // when the link points back inside, or — for `ln -s . loop` — never finish,
                    // writing an unbounded tar until the device fills up. restoreFrom already
                    // reads LF_SYMLINK back, so the round trip is unchanged for real trees.
                    walkTreeNoFollow(workspaceDir)
                        .filter { it != workspaceDir }
                        .forEach { file ->
                            val name = file.relativeTo(workspaceDir).path
                            val linkTarget = if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                                // toString, not a resolved path: the entry must carry the target as
                                // the link stores it (relative links stay relative, so a restore
                                // lands in the same shape it was archived in).
                                java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
                            } else {
                                null
                            }
                            val entry = if (linkTarget != null) {
                                TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply {
                                    linkName = linkTarget
                                }
                            } else {
                                TarArchiveEntry(file, name).apply {
                                    if (file.canExecute()) mode = mode or 0b001_001_001
                                }
                            }
                            tar.putArchiveEntry(entry)
                            // Regular files only: opening anything else is a hazard rather than an
                            // omission — a fifo the workspace holds would block this read until a
                            // writer appeared, hanging the snapshot (and the uninstall behind it).
                            if (linkTarget == null && file.isRegularFileNoFollow()) {
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
