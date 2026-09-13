package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException

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
