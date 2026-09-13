package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.io.PushbackInputStream
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
