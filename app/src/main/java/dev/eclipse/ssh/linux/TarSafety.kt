package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.io.PushbackInputStream
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
 * A relative [linkName] is resolved against the entry's parent directory — `../../bin/bash` from
 * `usr/bin/env` lands inside the root and is fine; the same climb from a deeper directory escapes
 * and is refused. An absolute [linkName] is mapped onto the root, because that is what it means
 * inside a rootfs: the pinned Ubuntu Base images ship 21 absolute links (`var/run -> /run`,
 * `usr/bin/pidof -> /sbin/killall5`, …), all of them rootfs-internal, and proot translates them
 * at runtime. What is refused — either way — is a link whose resolved target lands outside the
 * extraction tree, which is the one an app-side writer or walker could follow out.
 */
internal fun resolveLinkInsideRoot(root: File, name: String, linkName: String) {
    if (linkName.isEmpty()) {
        throw IOException("Archive entry is a symlink with an empty target: $name")
    }
    val target =
        if (linkName.startsWith("/")) {
            File(root, linkName.removePrefix("/"))
        } else {
            val entryParent = resolveInsideRoot(root, name).parentFile
                ?: throw IOException("Archive entry has no parent directory: $name")
            File(entryParent, linkName)
        }
    val resolved = target.canonicalFile
    if (!resolved.path.startsWith(root.canonicalPath + File.separator)) {
        throw IOException("Archive symlink points outside the extraction directory: $name -> $linkName")
    }
}

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
        Files.walkFileTree(path, setOf(LinkOption.NOFOLLOW_LINKS), Int.MAX_VALUE, visitor)
        true
    }.getOrDefault(false)
}
