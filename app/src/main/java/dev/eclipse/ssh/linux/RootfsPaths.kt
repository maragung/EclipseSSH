package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.LinkedList

/**
 * Turns a path inside an installed rootfs into a path on the host, and refuses everything that would
 * leave it.
 *
 * A rootfs is not a directory tree the host can walk with `File(root, path)`, because a rootfs'
 * symlinks mean something the host does not. Ubuntu ships absolute links — `var/run -> /run`,
 * `bin -> usr/bin` in the usrmerged images, `usr/bin/pager -> /etc/alternatives/pager` — and `/run`
 * on the host is the *host's* /run, not the guest's. `File(root, "var/run").canonicalFile` therefore
 * escapes the rootfs and lands in the device's own filesystem; a Files tab built on that would browse
 * the phone's `/run` while claiming to browse Ubuntu's, and could be made to write there by a link
 * planted inside the guest. So this class resolves links the way the kernel does *inside the root*:
 * a relative target is resolved against the link's own directory, an absolute one against the rootfs
 * root, and `..` is applied after the link is expanded rather than before it. That last rule is not
 * pedantry — `/var/run/..` is `/var` under a kernel and something else entirely under a textual
 * cleaner, and the difference is the whole class of bugs this file exists to not have.
 *
 * Nothing here consults [File.getCanonicalFile]: the question "does this stay inside the root" is
 * answered by construction, because every component of the result came either from the caller's own
 * path or from a link target, and both are walked through the same component-by-component loop that
 * refuses to go above the root.
 *
 * [HIDDEN] is the other half of the same honesty. `/dev`, `/proc` and `/sys` exist *inside* every
 * session — proot bind-mounts the device's own — so a rootfs that has never been booted still has
 * those three names on disk, and whatever is under them in the tarball is a handful of empty mount
 * points rather than the device's real /proc. Listing them would offer the user the phone's process
 * table behind an Ubuntu-looking breadcrumb; the Files tab has its own "This device" session for
 * that, and it does not pretend to be a rootfs.
 *
 * Pure in [root] and free of Android types, so the whole mapping is testable against a tree built in
 * a temp directory — which is what the provider's tests do, because a real rootfs is a 30 MB download.
 */
internal class RootfsPaths(val root: File) {

    /**
     * The host file [guestPath] names, with every link along the way expanded inside [root].
     *
     * The file need not exist: this is a mapping, not a lookup, and `createFile`/`createDirectory`
     * call it for paths that are about to. A path whose *existing* prefix is a link is resolved
     * through it — which is what makes `/bin/bash` and `/usr/bin/bash` the same file here, exactly as
     * they are under proot.
     *
     * @throws IOException when the path climbs above the root, names a directory the guest does not
     *   own ([HIDDEN]), contains a null byte, or links to itself more than [MAX_SYMLINK_HOPS] times.
     */
    fun hostPath(guestPath: String): File {
        val segments = resolveSegments(guestPath)
        if (segments.firstOrNull() in HIDDEN) {
            throw IOException(
                "\"$guestPath\" is the device's own ${segments.first()}, shared into the session " +
                    "rather than stored in it. Open it under This device instead.",
            )
        }
        return join(segments)
    }

    /**
     * The same mapping for a path that may legitimately be hidden — the root itself, and the
     * bookkeeping the repair path does. Private callers only; the Files tab never sees it.
     */
    private fun resolveSegments(guestPath: String): List<String> {
        if (guestPath.isEmpty()) throw IOException("Not a path: the empty string")
        if (guestPath.contains('\u0000')) throw IOException("A path cannot contain a null byte")
        // A work queue rather than an index into the split, because expanding a link inserts that
        // link's own target components at the front of what is still to be walked.
        val pending = LinkedList<String>()
        guestPath.split('/').forEach { pending.addLast(it) }
        val resolved = ArrayList<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            when (part) {
                "", "." -> continue
                ".." -> {
                    if (resolved.isEmpty()) {
                        throw IOException("\"$guestPath\" climbs above the root filesystem")
                    }
                    resolved.removeAt(resolved.size - 1)
                    continue
                }
            }
            val candidate = join(resolved + part)
            // isSymbolicLink does not follow, which is the point: following it is the kernel's job,
            // and this is where it is done by hand because the kernel's answer would be the host's.
            if (!Files.isSymbolicLink(candidate.toPath())) {
                resolved.add(part)
                continue
            }
            if (++hops > MAX_SYMLINK_HOPS) {
                throw IOException("\"$guestPath\" follows more than $MAX_SYMLINK_HOPS symbolic links")
            }
            val target = runCatching { Files.readSymbolicLink(candidate.toPath()).toString() }
                .getOrElse { throw IOException("\"$guestPath\" links to something unreadable") }
            if (target.startsWith('/')) resolved.clear()
            val targetParts = target.split('/')
            // addFirst in reverse, so the target's own components are walked in order.
            for (index in targetParts.indices.reversed()) pending.addFirst(targetParts[index])
        }
        return resolved
    }

    /** Whether [guestPath] names a directory, following a final symlink to one. */
    fun isDirectory(guestPath: String): Boolean =
        runCatching { hostPath(guestPath).isDirectory }.getOrDefault(false)

    /** The guest path of [name] inside the directory [guestDir] — never a host path. */
    fun childOf(guestDir: String, name: String): String =
        if (guestDir == "/") "/$name" else "${guestDir.trimEnd('/')}/$name"

    /** The directory above [guestPath], or null at the root — POSIX parenting, no filesystem read. */
    fun parentOf(guestPath: String): String? {
        val trimmed = guestPath.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        return when {
            cut < 0 -> null
            cut == 0 -> "/"
            else -> trimmed.substring(0, cut)
        }
    }

    private fun join(segments: List<String>): File =
        if (segments.isEmpty()) root else File(root, segments.joinToString("/"))

    companion object {
        /**
         * How many links one path may follow before it is called a loop.
         *
         * Linux' own ceiling is 40 (`MAXSYMLINKS`), and a rootfs is built by tools that assume it, so
         * the same number is the one that never refuses a path the guest itself would accept.
         */
        const val MAX_SYMLINK_HOPS = 40

        /**
         * The three directories every session borrows from the device rather than owning.
         *
         * proot bind-mounts the host's own over these names, so what a rootfs holds under them is
         * mount-point stubs, not the live trees a user would expect to find. See the class doc.
         */
        val HIDDEN = setOf("dev", "proc", "sys")
    }
}
