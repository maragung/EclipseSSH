package dev.eclipse.ssh.linux

import java.io.File

/**
 * Post-extraction, pre-swap validation of a rootfs staging tree: the manifest a Ubuntu Base image
 * must satisfy before it is allowed to become *the* rootfs.
 *
 * The installer's own `isExtracted()` is an existence probe — it says the extraction produced
 * *something*, not that it produced a working userspace. This is the difference: a shell that
 * exists and is executable, the dynamic linker without which every binary in the tree dies at
 * exec, the account and apt files the setup pipeline writes into, and enough bytes on disk that a
 * tarball which unpacked to a few text files cannot pass for a rootfs. Validation runs on the
 * staging directory before [RootfsInstaller] moves it into place, so a rootfs that exists is not
 * only complete but *valid*.
 */
class RootfsValidator(
    /**
     * The floor for the extracted tree's total size. A real Ubuntu Base image unpacks to tens of
     * megabytes; the floor is far below that, catching "the extraction produced almost nothing"
     * without ever arguing about what a real image weighs.
     */
    private val minExtractedBytes: Long = DEFAULT_MIN_EXTRACTED_BYTES,
) {
    /** One broken expectation: [path] on disk does not satisfy [problem]. */
    data class Finding(val path: String, val problem: String)

    /**
     * Validates the extracted tree at [root]; an empty list is a valid rootfs. Each finding names
     * its path exactly as it appears inside the rootfs, so the install screen can say what is
     * wrong rather than that something is.
     */
    fun validate(root: File): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (required in REQUIRED_PATHS) {
            val file = File(root, required)
            when {
                !file.exists() -> findings += Finding(required, "is missing")
                required in REQUIRED_BINARIES && !file.canExecute() ->
                    findings += Finding(required, "is not executable")
            }
        }
        if (findDynamicLinker(root) == null) {
            findings += Finding("lib/ld-linux*", "no dynamic linker was found under lib/ or usr/lib/")
        }
        val total = extractedBytes(root)
        if (total < minExtractedBytes) {
            findings +=
                Finding(
                    "<the whole tree>",
                    "unpacked to $total bytes, less than the $minExtractedBytes-byte floor for a rootfs",
                )
        }
        return findings
    }

    /**
     * The dynamic linker, without which every dynamically linked binary in the rootfs — bash
     * included — fails at exec. Its name carries the architecture (ld-linux-aarch64.so.1,
     * ld-linux-x86-64.so.2), so the search is for the prefix, under the two directories the
     * Ubuntu filesystem hierarchy puts it in.
     */
    private fun findDynamicLinker(root: File): File? =
        listOf(File(root, "lib"), File(root, "usr/lib"))
            .asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name.startsWith("ld-linux") }
            .firstOrNull()

    private fun extractedBytes(root: File): Long =
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }

    companion object {
        /** Everything setup and the first shell need to exist before the rootfs is worth keeping. */
        internal val REQUIRED_PATHS =
            listOf(
                "/bin/sh",
                "/bin/bash",
                "/usr/bin/env",
                "/usr/bin/apt-get",
                "/etc/passwd",
                "/etc/apt/sources.list",
            )

        /** The manifest entries that must not merely exist but be runnable. */
        private val REQUIRED_BINARIES = setOf("/bin/sh", "/bin/bash", "/usr/bin/env", "/usr/bin/apt-get")

        /** A real image unpacks to tens of MB; anything under this did not really unpack. */
        const val DEFAULT_MIN_EXTRACTED_BYTES = 2L * 1024 * 1024
    }
}
