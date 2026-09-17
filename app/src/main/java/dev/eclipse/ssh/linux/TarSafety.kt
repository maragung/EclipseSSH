package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.io.PushbackInputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Resolves a tar entry name inside [root], refusing anything that escapes it — `../` components,
 * absolute paths, anything that lands outside after canonicalization.
 *
 * Every archive this feature extracts goes through this one check: the pinned rootfs tarball (hash
 * verified, so this is defense in depth there) and the workspace backup (written by this app, but
 * kept to the same rule so a moved or edited backup cannot become an escape hatch). One guard,
 * tested once, trusted everywhere — a second copy of it would only ever drift.
 *
 * [root] must exist; `canonicalFile` is what makes symlinked directories inside the archive
 * unable to route the resolution outside.
 */
internal fun resolveInsideRoot(root: File, name: String): File {
    val cleaned = name.removePrefix("./")
    val resolved = File(root, cleaned).canonicalFile
    if (!resolved.path.startsWith(root.canonicalPath + File.separator)) {
        throw IOException("Archive entry escapes the extraction directory: $name")
    }
    return resolved
}

/**
 * Opens [file] as a tar stream, unwrapping gzip when the file's magic bytes say it is gzipped.
 *
 * [TarArchiveInputStream] parses tar and nothing else — it neither sniffs nor decompresses, so a
 * `.tar.gz` fed to it raw reads as garbage headers and the archive looks empty: an install or a
 * restore would "succeed" while extracting nothing at all. Every tar this feature reads (the
 * pinned rootfs tarball, the workspace snapshot) is written gzipped, so the gzip magic
 * (0x1f 0x8b) decides the wrap; anything else passes through untouched, so a plain tar still
 * works.
 */
internal fun openTarStream(file: File, bufferSize: Int): TarArchiveInputStream {
    val probe = PushbackInputStream(file.inputStream().buffered(bufferSize), 2)
    val magic = ByteArray(2)
    val read = probe.read(magic)
    if (read > 0) probe.unread(magic, 0, read)
    val source = if (read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
        GZIPInputStream(probe, bufferSize)
    } else {
        probe
    }
    return TarArchiveInputStream(source)
}

/**
 * Validates a tar symlink entry's target the way [resolveInsideRoot] validates entry names: the
 * entry itself must resolve inside [root] (checked separately), and where the link *points* must
 * resolve inside [root] too.
 *
 * The target is resolved **lexically** — [linkName]'s own components walked from the entry's
 * parent (or from the root when the target is absolute), never a filesystem lookup. That is not
 * just an implementation choice: `canonicalFile` answers about the *device's* filesystem, and an
 * absolute link an earlier entry already extracted (`etc/alternatives/pager -> /bin/more`) walks,
 * on the device, to `/bin/more` — an "escape" the rootfs never meant, because inside a rootfs
 * that link means the rootfs's own /bin/more and proot translates it at runtime. The question
 * this guard can honestly ask is whether the linkName's own path, component by component, stays
 * under the root; what it cannot do is judge a chain through already-extracted links, whose
 * meaning only exists inside the rootfs. The pinned rootfs carries its own guarantee (the
 * sha256 pin), so this guard is defense in depth there — for the app-written backup it still
 * refuses every link that declares an outside target.
 *
 * A relative [linkName] is resolved against the entry's parent directory — `../../bin/bash` from
 * `usr/bin/env` lands inside the root and is fine; the same climb from a deeper directory escapes
 * and is refused. An absolute [linkName] is mapped onto the root, because that is what it means
 * inside a rootfs: the pinned Ubuntu Base images ship 21 absolute links (`var/run -> /run`,
 * `usr/bin/pager -> /etc/alternatives/pager`, …). Refused — either way — is a target whose
 * components climb above the root.
 */
internal fun resolveLinkInsideRoot(root: File, name: String, linkName: String) {
    if (linkName.isEmpty()) {
        throw IOException("Archive entry is a symlink with an empty target: $name")
    }
    val entryParent = resolveInsideRoot(root, name).parentFile
        ?: throw IOException("Archive entry has no parent directory: $name")
    // Where the link lives, as components relative to the root. resolveInsideRoot hands back a
    // canonical path under the root's, so the relativize is well-defined; empty is the root itself.
    val where =
        root.canonicalFile.toPath().relativize(entryParent.toPath()).map { it.toString() }
    val stack = ArrayDeque(where)
    if (linkName.startsWith("/")) {
        // An absolute target means the rootfs's root: the walk starts there, discarding the
        // entry's own location.
        stack.clear()
    }
    for (part in linkName.split('/')) {
        when (part) {
            "", "." -> {}
            ".." ->
                if (stack.removeLastOrNull() == null) {
                    throw IOException(
                        "Archive symlink points outside the extraction directory: $name -> $linkName"
                    )
                }
            else -> stack.addLast(part)
        }
    }
}

/**
 * Walks [root] depth-first without following symlinks — the counterpart to [deleteTreeNoFollow]
 * for the paths that *measure* or *archive* a tree instead of deleting it.
 *
 * `File.walkTopDown()` cannot serve either job here, because it asks `File.isDirectory`, which
 * follows links and therefore descends through them. Three consequences, all of them real data in
 * this app rather than hypothetical:
 *
 *  - The workspace (`/home/ubuntu/workspace`) is the user's own tree. `ln -s . loop` inside it —
 *    or any of the self-referential links project tooling leaves behind — makes the walk never
 *    terminate, which on the snapshot path means an unbounded tar written until the device fills.
 *  - A link out of the tree is counted as the tree's own bytes: the settings screen's storage line
 *    reports another directory's size, and a link back inside counts the same bytes twice.
 *  - The rootfs is merged-/usr, so its own `bin -> usr/bin`, `lib -> usr/lib` and `sbin -> usr/sbin`
 *    links make every file under them count twice — inflating the extracted-size floor
 *    [RootfsValidator] uses to notice a truncated extraction.
 *
 * Entries are yielded parent-first, and a symlink is yielded as itself, never as its target: the
 * caller decides what a link means, and for archiving that decision is "write a link".
 */
internal fun walkTreeNoFollow(root: File): Sequence<File> = sequence {
    val pending = ArrayDeque<File>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        // Losing a directory to a permissions error must not end the walk: the entries already
        // yielded are still true, and the callers here measure rather than account.
        val children = pending.removeLast().listFiles() ?: continue
        for (child in children) {
            yield(child)
            // NOFOLLOW asks about the entry itself, which is the whole point: a symlink to a
            // directory is yielded as a link and never descended into, so no walk can cycle
            // and no walk can leave the tree.
            if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) pending.addLast(child)
        }
    }
}

/**
 * Whether [this] is a regular file *itself* — a symlink to a file is a link here, not the file it
 * points at. The distinction [walkTreeNoFollow] exists to make possible: a link is not content, so
 * it adds nothing to a total, and its target's bytes count only when the target is itself a real
 * entry of the tree.
 *
 * Note what it deliberately does *not* say: a link's own length is not content either, even though
 * `File.length()` answers for a link (the size of its target path). A total built that way answers
 * a question — "how many bytes do the links' targets' names take up" — that no caller asked.
 */
internal fun File.isRegularFileNoFollow(): Boolean =
    Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

/**
 * Whether [this] is a directory *itself*, on the same terms as [isRegularFileNoFollow]: a symlink
 * to a directory is a link, not a directory, so callers that mean "the entries a user sees" count
 * it rather than treating it as structure.
 */
internal fun File.isDirectoryNoFollow(): Boolean =
    Files.isDirectory(toPath(), LinkOption.NOFOLLOW_LINKS)

/**
 * Deletes a tree of extracted archive content without following symlinks, replacing
 * [File.deleteRecursively] wherever the tree came out of an archive. With `NOFOLLOW_LINKS`, a
 * symlink — to a file or a directory — is visited as a file and deleted as a link; its target is
 * never touched and no traversal crosses a link.
 *
 * @return true when the whole tree is gone; false when the walk could not complete (individual
 *   deletions are best-effort, as deleteRecursively's are)
 */
internal fun deleteTreeNoFollow(root: File): Boolean {
    val path = root.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) return true
    val visitor = object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
            // One undeletable file must not preserve the whole tree: keep walking so the rest
            // is reclaimed, and let the caller learn the outcome from the return value.
            FileVisitResult.CONTINUE
    }
    return runCatching {
        // walkFileTree never follows symlinks unless FileVisitOption.FOLLOW_LINKS is passed,
        // so an empty options set IS the no-follow walk: the second parameter is visit
        // options, not [LinkOption]s, and passing NOFOLLOW_LINKS there does not even compile.
        // Link targeting stays the visitor's concern: it deletes entries as files, so a
        // link's target is never touched and no traversal crosses one.
        Files.walkFileTree(path, emptySet<FileVisitOption>(), Int.MAX_VALUE, visitor)
        true
    }.getOrDefault(false)
}
