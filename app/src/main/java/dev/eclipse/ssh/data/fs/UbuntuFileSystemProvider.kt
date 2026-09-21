package dev.eclipse.ssh.data.fs

import dev.eclipse.ssh.linux.RootfsPaths
import dev.eclipse.ssh.ssh.formatPermissions
import dev.eclipse.ssh.ssh.requirePermissionBits
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where an installed rootfs is, or null on a device that has none to browse.
 *
 * A function rather than a `File?` because the answer changes while the app runs: installing,
 * uninstalling and repairing all move the tree the Files tab is pointed at, and a provider holding
 * the directory it saw at construction would keep browsing a rootfs the userspace manager has already
 * replaced. It is also the seam this provider's tests use — they hand it a tree in a temp directory,
 * because a real rootfs is a 30 MB download.
 *
 * Null is a normal answer, not a failure: a device whose ABI maps to no Ubuntu architecture has no
 * rootfs and never will, and a chip that cannot open is worse than no chip.
 */
fun interface UbuntuRootfsLocator {
    fun rootfsDir(): File?
}

/**
 * One installed Ubuntu userspace, browsed as files.
 *
 * This is the third [FileSystemProvider], and it is the one that is not a foreign filesystem
 * pretending otherwise: the rootfs is an ordinary directory tree inside the app's own sandbox, owned
 * by the app's own uid, so every POSIX verb the SFTP provider has to ask a server for is a syscall
 * here. That is why [supportsPermissions] is true — a real `chmod` on the device's own filesystem,
 * which is exactly what the userspace manual tells people to do from inside the shell, and the one
 * provider in this app for which the action is not an approximation.
 *
 * What it is *not* is a plain `File(rootfs, path)` walk, and the difference is the whole reason
 * [RootfsPaths] exists: a rootfs' absolute symlinks mean the guest's `/run`, not the host's, and
 * following them with host semantics would browse — and write — outside the sandbox.
 *
 * Paths handed to the UI are **guest** paths (`/home/ubuntu/workspace`), never host ones. The user is
 * looking at Ubuntu, and a row reading
 * `/data/data/dev.eclipse.ssh/files/linux/rootfs/home/ubuntu` would be the app leaking its own storage
 * layout into a screen that is supposed to be a machine.
 *
 * Everything here is the app's own filesystem, so there is no session, no channel and no host: the
 * provider works whether or not a shell is running, which is deliberate. A user who has installed
 * Ubuntu should be able to move a file into it before ever opening a terminal.
 */
@Singleton
class UbuntuFileSystemProvider @Inject constructor(
    private val rootfs: UbuntuRootfsLocator,
) : FileSystemProvider {

    override val providerId: String = "ubuntu"

    /**
     * True, and the one place in this app where it is not a lie.
     *
     * The files are the device's own, under the app's uid, so `setPermissions` is an ordinary
     * `chmod` on a real mode word rather than an SFTP round trip or a SAF approximation. The rootfs
     * extractor preserves the executable bit for exactly this reason (see `RootfsInstaller`).
     */
    override val supportsPermissions: Boolean = true

    /**
     * Whether there is a rootfs to browse at all, for the session list to decide on a chip.
     *
     * Cheap and synchronous on purpose: it is asked while building a list, and the answer is a
     * directory test in the app's own sandbox. Deliberately *not* a suspend function, so a caller
     * cannot accidentally turn the chip into a wait — the list is rebuilt on every Files resume.
     */
    fun isAvailable(): Boolean = rootfs.rootfsDir()?.isDirectory == true

    /**
     * The user's home inside the guest, which is also where the workspace lives.
     *
     * Falls back to the root: this can be opened between extraction and `setup()`, when the `ubuntu`
     * account does not exist yet, and a home path that is not there would open the tab on an error
     * instead of on the tree.
     */
    override suspend fun homePath(): String? = withRoot { paths ->
        if (isDirectory(paths, "/home/ubuntu")) "/home/ubuntu" else "/"
    }

    override suspend fun parentPath(path: String): String? = withRoot { paths -> paths.parentOf(path) }

    override suspend fun list(path: String): List<FsEntry> = withRoot { paths ->
        val directory = paths.hostPath(path)
        if (!directory.isDirectory) throw IOException("$path is not a directory in this Ubuntu userspace")
        val names = directory.list() ?: throw IOException("Could not read $path")
        names
            // Only at the root: proot binds the device's own /dev, /proc and /sys there, so the names
            // under them in the tarball are mount-point stubs. A directory *called* proc somewhere in
            // the user's own tree is theirs, and hiding it would be a listing that lies.
            .filter { path != "/" || it !in RootfsPaths.HIDDEN }
            .mapNotNull { name -> entryIn(paths, directory, paths.childOf(path, name), name) }
            .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): FsEntry? = withRoot { paths ->
        entryAt(paths, path)
    }

    override suspend fun read(path: String): ByteArray = withRoot { paths ->
        val target = paths.hostPath(path)
        if (target.isDirectory) throw IOException("$path is a directory")
        target.readBytes()
    }

    override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) = withRoot { paths ->
        val target = paths.hostPath(path)
        if (target.isDirectory) throw IOException("$path is a directory")
        if (onlyIfUnmodifiedSince != null) {
            // The contract every provider holds: a file that changed after the editor read it is a
            // question for the user, never a silent overwrite. A file deleted since answers "no
            // conflict" — writing it back is what the user asked for, and it is what `>` would do.
            val current = lastModifiedOrNull(target)
            if (current != null && current > onlyIfUnmodifiedSince) {
                throw FsModificationConflictException(path)
            }
        }
        target.parentFile?.mkdirs()
        target.writeBytes(data)
    }

    override suspend fun createFile(parentPath: String, name: String): FsEntry = withRoot { paths ->
        requireSafeName(name)
        val guestPath = paths.childOf(parentPath, name)
        val target = paths.hostPath(guestPath)
        if (target.isDirectory) throw IOException("$name is already a directory here")
        target.parentFile?.mkdirs()
        // Truncate, matching the contract and what `> file` in the shell would have done.
        target.writeBytes(ByteArray(0))
        entryAt(paths, guestPath) ?: throw IOException("Could not create $name in $parentPath")
    }

    override suspend fun createDirectory(parentPath: String, name: String) = withRoot { paths ->
        requireSafeName(name)
        val target = paths.hostPath(paths.childOf(parentPath, name))
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("Could not create the folder $name in $parentPath")
        }
    }

    override suspend fun rename(path: String, newName: String) = withRoot { paths ->
        requireSafeName(newName)
        val source = paths.hostPath(path)
        if (!source.exists()) throw IOException("$path is no longer there")
        val destination = File(source.parentFile, newName)
        if (destination.exists()) throw IOException("$newName already exists here")
        if (!source.renameTo(destination)) throw IOException("Could not rename $path to $newName")
    }

    override suspend fun copy(sourcePath: String, targetDirectoryPath: String) = withRoot { paths ->
        val source = paths.hostPath(sourcePath)
        val targetDirectory = paths.hostPath(targetDirectoryPath)
        if (!source.exists()) throw IOException("$sourcePath is no longer there")
        if (!targetDirectory.isDirectory) throw IOException("$targetDirectoryPath is not a directory")
        val destination = File(targetDirectory, nameOf(sourcePath))
        if (destination.exists()) {
            throw IOException("${nameOf(sourcePath)} already exists in $targetDirectoryPath")
        }
        copyTree(source, destination)
    }

    override suspend fun move(sourcePath: String, targetDirectoryPath: String) = withRoot { paths ->
        val source = paths.hostPath(sourcePath)
        val targetDirectory = paths.hostPath(targetDirectoryPath)
        if (!source.exists()) throw IOException("$sourcePath is no longer there")
        if (!targetDirectory.isDirectory) throw IOException("$targetDirectoryPath is not a directory")
        // Into the folder it is already in is a no-op rather than a duplicate: "move to here" is what
        // the destination picker says even when "here" is where the entry already is.
        if (source.parentFile?.absolutePath == targetDirectory.absolutePath) return@withRoot
        val destination = File(targetDirectory, nameOf(sourcePath))
        if (destination.exists()) {
            throw IOException("${nameOf(sourcePath)} already exists in $targetDirectoryPath")
        }
        // A rename first, because both ends are one filesystem and it is atomic when it works —
        // moving a large tree inside the sandbox should not copy it. The recursive copy is the
        // fallback for what rename cannot serve, which on one filesystem is a directory moved onto a
        // name the check above has already ruled out.
        if (source.renameTo(destination)) return@withRoot
        copyTree(source, destination)
        deleteTree(source)
    }

    override suspend fun delete(path: String) = withRoot { paths ->
        val target = paths.hostPath(path)
        if (!target.exists()) throw IOException("$path is no longer there")
        deleteTree(target)
    }

    /**
     * Sets the nine POSIX permission bits, refusing anything outside them.
     *
     * `requirePermissionBits` is the guard the SFTP path uses, and it matters more here rather than
     * less: this is a real `chmod` on the user's own filesystem, so a decimal `644` read as a mode
     * would set the sticky bit and take the owner's own read access away — from a file they could then
     * only fix from inside the shell.
     */
    override suspend fun setPermissions(path: String, mode: Int) {
        withRoot { paths ->
            val target = paths.hostPath(path)
            if (!target.exists()) throw IOException("$path is no longer there")
            // A statement body at the call site rather than an expression one: `setPosixFilePermissions`
            // answers the `Path` it just changed, and an expression-bodied override would therefore
            // return it where the contract promises Unit.
            Files.setPosixFilePermissions(target.toPath(), permissionsOf(requirePermissionBits(mode)))
        }
    }

    /**
     * Walks guest paths rather than the host tree, so the search sees exactly what a listing sees:
     * links resolved inside the root, the device's own /dev, /proc and /sys left out, and an
     * unreadable directory skipped rather than ending the walk. Depth-capped for the same reason the
     * other two providers are — a tree that reports a directory inside itself must not become a loop.
     */
    override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> =
        withRoot { paths ->
            val results = ArrayList<FsEntry>()
            val pending = ArrayDeque<Pair<String, Int>>()
            pending.add(root to 0)
            while (pending.isNotEmpty() && results.size < maxEntries) {
                val (directory, depth) = pending.removeFirst()
                if (depth > MAX_SEARCH_DEPTH) continue
                // One unreadable folder does not end the search; the readable rest still matters.
                val entries = runCatching { list(paths, directory) }.getOrNull() ?: continue
                for (entry in entries) {
                    if (results.size >= maxEntries) break
                    if (entry.name.contains(query, ignoreCase = true)) results.add(entry)
                    if (entry.isDirectory) pending.add(entry.path to depth + 1)
                }
            }
            results
        }

    /** Runs [block] against the live rootfs, off the caller's dispatcher. */
    private suspend fun <T> withRoot(block: (RootfsPaths) -> T): T = withContext(Dispatchers.IO) {
        val directory = rootfs.rootfsDir()
            ?: throw IOException("No Ubuntu userspace is installed on this device")
        if (!directory.isDirectory) {
            throw IOException("The Ubuntu userspace is not installed properly. Reinstall it to browse its files.")
        }
        block(RootfsPaths(directory))
    }

    /**
     * Opens [guestPath] for reading, so a caller can stream a file instead of holding it.
     *
     * Outside the [FileSystemProvider] contract on purpose: that interface is whole-file because its
     * only caller is the editor, which already refuses files above its size cap. A transfer has no
     * such cap — a user moving a 2 GB disk image into the userspace should not need 2 GB of heap — so
     * the streaming pair lives here, on the one class that knows how to map a guest path.
     *
     * The stream is opened on [Dispatchers.IO] and then handed over; a `File` stream has no thread
     * affinity, so the caller reads it from wherever it is. Closing it is the caller's job.
     */
    suspend fun openInput(guestPath: String): InputStream = withRoot { paths ->
        val target = paths.hostPath(guestPath)
        if (target.isDirectory) throw IOException("$guestPath is a directory")
        target.inputStream()
    }

    /**
     * Opens [guestPath] for writing, truncating whatever is there.
     *
     * The guest path is resolved before the stream is opened, so writing through a symlinked
     * directory lands where the guest would land — and a path that escapes the rootfs is refused
     * before a single byte is written. The parent directory is created if it is missing, which is
     * what makes "copy a file into a folder I just made in the shell" work when the listing has not
     * caught up yet.
     */
    suspend fun openOutput(guestPath: String): OutputStream = withRoot { paths ->
        val target = paths.hostPath(guestPath)
        if (target.isDirectory) throw IOException("$guestPath is a directory")
        target.parentFile?.mkdirs()
        target.outputStream()
    }

    /** The size of [guestPath] in bytes, or null when it is not a readable file. */
    suspend fun sizeOf(guestPath: String): Long? = withRoot { paths ->
        runCatching { paths.hostPath(guestPath).length() }.getOrNull()
    }

    /** [list] without the rootfs lookup, so [search] can walk the same rules it lists by. */
    private fun list(paths: RootfsPaths, path: String): List<FsEntry> {
        val directory = paths.hostPath(path)
        if (!directory.isDirectory) throw IOException("$path is not a directory in this Ubuntu userspace")
        val names = directory.list() ?: throw IOException("Could not read $path")
        return names
            .filter { path != "/" || it !in RootfsPaths.HIDDEN }
            .mapNotNull { name -> entryIn(paths, directory, paths.childOf(path, name), name) }
    }

    /**
     * The entry for one name inside a directory that has already been resolved.
     *
     * The row describes the name, not what it points at: a link whose target cannot be resolved —
     * a dangling one, one that climbs out of the root, or a loop — is still an entry in the
     * directory, and dropping it would give a listing with holes in it. Where the target *is*
     * readable its attributes are shown instead, which is how every file manager draws a link and
     * is what makes `bin` sort and label as the directory it is.
     */
    private fun entryIn(paths: RootfsPaths, directory: File, guestPath: String, name: String): FsEntry? {
        val own = attributesOf(File(directory, name)) ?: return null
        if (!own.isSymbolicLink) return entry(guestPath, name, File(directory, name), own)
        val resolved = runCatching { paths.hostPath(guestPath) }.getOrNull() ?: return entry(guestPath, name, null, own)
        return entry(guestPath, name, resolved, attributesOf(resolved) ?: own)
    }

    /** The entry a caller named directly, resolved through the rootfs before it is described. */
    private fun entryAt(paths: RootfsPaths, guestPath: String): FsEntry? {
        val resolved = runCatching { paths.hostPath(guestPath) }.getOrNull() ?: return null
        val attributes = attributesOf(resolved) ?: return null
        return entry(guestPath, nameOf(guestPath), resolved, attributes)
    }

    /**
     * The [FsEntry] for a file whose attributes are already known.
     *
     * [described] is where the size, the time and the mode come from, and [modeFrom] is the file they
     * are read off — the same file, except for a link, whose row is drawn from its target the way
     * `ls -lL` would.
     */
    private fun entry(
        guestPath: String,
        name: String,
        modeFrom: File?,
        described: BasicFileAttributes,
    ): FsEntry = FsEntry(
        name = name,
        path = guestPath,
        isDirectory = described.isDirectory,
        size = described.size().takeIf { !described.isDirectory },
        modifiedEpochMillis = described.lastModifiedTime().toMillis(),
        // A real mode word off the device's own filesystem, which is what lets this provider offer
        // chmod at all. Read from the file itself, so it is the guest's answer rather than the app's
        // guess about which bits an owner is likely to have; null where the filesystem has no POSIX
        // view to read, which is the same "unknown" every other null in this type means.
        permissions = modeFrom?.let { modeOf(it) }?.let { runCatching { formatPermissions(it) }.getOrNull() },
        mimeType = null,
    )

    /**
     * Attributes of [file], never through a final symlink.
     *
     * NOFOLLOW because the caller has already decided what to follow: a link's own attributes are
     * what tells [entryIn] that it is one, and following the host's idea of where it points is the
     * escape [RootfsPaths] exists to prevent.
     */
    private fun attributesOf(file: File): BasicFileAttributes? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    /** The nine permission bits of [file], or null where the filesystem cannot report them. */
    private fun modeOf(file: File): Int? {
        val permissions = runCatching {
            Files.readAttributes(file.toPath(), PosixFileAttributes::class.java).permissions()
        }.getOrNull() ?: return null
        var mode = 0
        if (PosixFilePermission.OWNER_READ in permissions) mode = mode or 0b100_000_000
        if (PosixFilePermission.OWNER_WRITE in permissions) mode = mode or 0b010_000_000
        if (PosixFilePermission.OWNER_EXECUTE in permissions) mode = mode or 0b001_000_000
        if (PosixFilePermission.GROUP_READ in permissions) mode = mode or 0b000_100_000
        if (PosixFilePermission.GROUP_WRITE in permissions) mode = mode or 0b000_010_000
        if (PosixFilePermission.GROUP_EXECUTE in permissions) mode = mode or 0b000_001_000
        if (PosixFilePermission.OTHERS_READ in permissions) mode = mode or 0b000_000_100
        if (PosixFilePermission.OTHERS_WRITE in permissions) mode = mode or 0b000_000_010
        if (PosixFilePermission.OTHERS_EXECUTE in permissions) mode = mode or 0b000_000_001
        return mode
    }

    /** The nine bits as the [PosixFilePermission] set the JDK sets them with — the inverse of [modeOf]. */
    private fun permissionsOf(bits: Int): Set<PosixFilePermission> = buildSet {
        if (bits and 0b100_000_000 != 0) add(PosixFilePermission.OWNER_READ)
        if (bits and 0b010_000_000 != 0) add(PosixFilePermission.OWNER_WRITE)
        if (bits and 0b001_000_000 != 0) add(PosixFilePermission.OWNER_EXECUTE)
        if (bits and 0b000_100_000 != 0) add(PosixFilePermission.GROUP_READ)
        if (bits and 0b000_010_000 != 0) add(PosixFilePermission.GROUP_WRITE)
        if (bits and 0b000_001_000 != 0) add(PosixFilePermission.GROUP_EXECUTE)
        if (bits and 0b000_000_100 != 0) add(PosixFilePermission.OTHERS_READ)
        if (bits and 0b000_000_010 != 0) add(PosixFilePermission.OTHERS_WRITE)
        if (bits and 0b000_000_001 != 0) add(PosixFilePermission.OTHERS_EXECUTE)
    }

    private fun isDirectory(paths: RootfsPaths, guestPath: String): Boolean =
        runCatching { paths.hostPath(guestPath).isDirectory }.getOrDefault(false)

    private fun lastModifiedOrNull(target: File): Long? =
        runCatching { Files.getLastModifiedTime(target.toPath()).toMillis() }.getOrNull()

    /**
     * Copies a file or a directory tree, breadth-first so the queue — not the call stack — carries the
     * recursion. A rootfs holds trees thousands of entries deep (`usr/share`, `var/lib/dpkg`), and the
     * shape is deliberately the SAF provider's, because the failure it avoids is the same one.
     *
     * Symlinks are copied as links, never followed. Following one here would be the escape
     * [RootfsPaths] prevents, arriving by another road: a link out of the tree is exactly what a copy
     * must not walk through.
     */
    private fun copyTree(source: File, destination: File) {
        val pending = ArrayDeque<Pair<File, File>>()
        pending.add(source to destination)
        while (pending.isNotEmpty()) {
            val (from, to) = pending.removeFirst()
            when {
                Files.isSymbolicLink(from.toPath()) -> {
                    Files.deleteIfExists(to.toPath())
                    Files.createSymbolicLink(to.toPath(), Files.readSymbolicLink(from.toPath()))
                }

                from.isDirectory -> {
                    if (!to.mkdirs() && !to.isDirectory) throw IOException("Could not create ${to.name}")
                    from.listFiles()?.forEach { child -> pending.add(child to File(to, child.name)) }
                }

                else -> {
                    to.parentFile?.mkdirs()
                    from.copyTo(to, overwrite = true)
                    // The mode is carried over, not just the bytes: the executable bit is the one the
                    // guest actually reads, so a copy that dropped it would break every binary it moved.
                    runCatching { Files.setPosixFilePermissions(to.toPath(), Files.getPosixFilePermissions(from.toPath())) }
                }
            }
        }
    }

    /**
     * Deletes children before parents, iterative post-order on an explicit stack, and never through a
     * symlink: `File.isDirectory` follows links, so a tree walked with it can be made to delete
     * whatever a link points at. The link itself is removed; what it points at is not.
     */
    private fun deleteTree(target: File) {
        val stack = ArrayDeque<DeleteNode>()
        stack.add(DeleteNode(target))
        while (stack.isNotEmpty()) {
            val node = stack.last()
            if (!node.expanded) {
                node.expanded = true
                val isLink = Files.isSymbolicLink(node.file.toPath())
                if (!isLink && node.file.isDirectory) {
                    node.file.listFiles()?.forEach { stack.add(DeleteNode(it)) }
                }
            } else {
                stack.removeLast()
                if (!node.file.delete() && node.file.exists()) {
                    throw IOException("Could not delete ${node.file.name}")
                }
            }
        }
    }

    /** One file still to delete, and whether its children have been queued. */
    private class DeleteNode(val file: File) {
        var expanded = false
    }

    /** A name is one path segment; anything else would let a rename or a create climb out of the guest. */
    private fun requireSafeName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
            throw IOException("\"$name\" is not a usable file name")
        }
    }

    private fun nameOf(guestPath: String): String =
        guestPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    private companion object {
        /** The deepest a search will walk, matching the SFTP and SAF providers' own bound. */
        const val MAX_SEARCH_DEPTH = 64
    }
}
