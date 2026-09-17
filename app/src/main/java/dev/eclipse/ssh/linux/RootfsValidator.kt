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
    /**
     * The Ubuntu architecture the tarball was pinned for ("arm64", "amd64", "armhf"). The rootfs's
     * dynamic linker carries the ABI in its name, so the validation is a name match: an arm64
     * tarball on an x86 device extracts cleanly, passes every existence check, and then dies at
     * the first exec with only proot's word for why — here it is refused at the staging directory,
     * named as an architecture mismatch. Null (or an unrecognized value) accepts any `ld-linux*`,
     * the pre-architecture behavior, for callers with no expectation to state.
     */
    private val expectedArch: String? = null,
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
        val expectedLinker = expectedArch?.let { LINKER_BY_ARCH[it] }
        when {
            // The architecture is pinned, so the linker must be that architecture's — and when a
            // *different* one is present, saying so ("an x86-64 linker in an arm64 rootfs") beats
            // the generic missing-file finding, because it names the actual mistake: the tarball
            // does not match the device it was installed on.
            expectedLinker != null && findFileNamed(root, expectedLinker) == null -> {
                val present = findDynamicLinker(root)
                findings +=
                    if (present != null) {
                        Finding(
                            pathInsideRoot(root, present),
                            "is the dynamic linker for a different architecture; " +
                                "an $expectedArch rootfs needs $expectedLinker — the tarball " +
                                "does not match this device",
                        )
                    } else {
                        Finding("lib/$expectedLinker", "no dynamic linker was found under lib/ or usr/lib/")
                    }
            }
            expectedLinker == null && findDynamicLinker(root) == null ->
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
        findNamed(root) { it.startsWith("ld-linux") }

    /** The one file the manifest wants when the architecture is pinned: the linker by exact name. */
    private fun findFileNamed(root: File, name: String): File? = findNamed(root) { it == name }

    private fun findNamed(root: File, matches: (String) -> Boolean): File? =
        listOf(File(root, "lib"), File(root, "usr/lib"))
            .asSequence()
            // isDirectory follows deliberately: on a merged-/usr rootfs `lib` *is* a link to
            // `usr/lib`, and the linker is still inside it.
            .filter { it.isDirectory }
            // walkTreeNoFollow past that point: a crafted rootfs with `usr/lib/loop -> usr/lib`
            // would otherwise make this search during validation never return.
            .flatMap { walkTreeNoFollow(it) }
            // isFile follows deliberately too — the question is whether the linker is *reachable*
            // under this name, which is what exec will ask, not whether the name is a plain file.
            .filter { it.isFile && matches(it.name) }
            .firstOrNull()

    /** A rootfs-inside path ("/lib/ld-linux-aarch64.so.1"), the form findings name paths in. */
    private fun pathInsideRoot(root: File, file: File): String =
        "/" + root.toPath().relativize(file.toPath()).joinToString("/")

    /**
     * Bytes of real content under [root], for the floor that notices a truncated extraction.
     *
     * walkTreeNoFollow, not walkTopDown: the rootfs is merged-/usr, so `bin -> usr/bin`,
     * `lib -> usr/lib` and `sbin -> usr/sbin` made every file under them count twice, roughly
     * doubling the measured size. The floor is a fraction of the expected size, so the inflation
     * pushed it above what a genuinely truncated extraction produces — the check passed anyway.
     */
    private fun extractedBytes(root: File): Long =
        if (root.isDirectory) {
            walkTreeNoFollow(root).filter { it.isRegularFileNoFollow() }.sumOf { it.length() }
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

        /**
         * The dynamic linker each Ubuntu architecture ships, by the exact name it carries. The
         * name is ABI-specific (a kernel execs only its own), which is what makes it an
         * architecture check that needs no ELF parsing.
         */
        internal val LINKER_BY_ARCH =
            mapOf(
                "amd64" to "ld-linux-x86-64.so.2",
                "arm64" to "ld-linux-aarch64.so.1",
                "armhf" to "ld-linux-armhf.so.3",
            )
    }
}
