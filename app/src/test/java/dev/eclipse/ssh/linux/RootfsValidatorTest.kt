package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * The rootfs validator's manifest, one broken expectation at a time: each finding must name its
 * path exactly as it appears inside the rootfs, because the install screen relays that text —
 * "what is wrong" is the difference between a repairable install and a mystifying one.
 *
 * The fixtures are hand-built directories (not extracted tarballs) because the validator's
 * contract is about the tree on disk; the pipeline that produces that tree is RootfsInstaller's
 * own test's job. The byte floor is lowered per test so the fixtures stay bytes, not megabytes.
 */
class RootfsValidatorTest {

    private val validator = RootfsValidator(minExtractedBytes = 1)

    /** A tree satisfying every manifest entry; the baseline the broken variants mutate. */
    private fun validTree(): File {
        val root = Files.createTempDirectory("rootfs-validate").toFile().apply { deleteOnExit() }
        for (path in RootfsValidator.REQUIRED_PATHS) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText("placeholder\n")
            file.setExecutable(true, true)
        }
        val lib = File(root, "lib")
        lib.mkdirs()
        File(lib, "ld-linux-aarch64.so.1").apply {
            writeText("linker\n")
            setExecutable(true, true)
        }
        return root
    }

    @Test
    fun `a complete rootfs validates clean`() {
        assertThat(validator.validate(validTree())).isEmpty()
    }

    @Test
    fun `a missing binary is named by its path`() {
        val root = validTree()
        assertThat(File(root, "bin/bash").delete()).isTrue()

        val findings = validator.validate(root)
        assertThat(findings).hasSize(1)
        assertThat(findings.single().path).isEqualTo("/bin/bash")
        assertThat(findings.single().problem).contains("missing")
    }

    @Test
    fun `a shell without the executable bit is named`() {
        val root = validTree()
        File(root, "bin/sh").setExecutable(false, false)

        val findings = validator.validate(root)
        assertThat(findings.single().path).isEqualTo("/bin/sh")
        // The one mode bit userspace actually reads: losing it turns every binary in the rootfs
        // into a permission denied, so it is validated, not assumed.
        assertThat(findings.single().problem).contains("not executable")
    }

    @Test
    fun `a rootfs with no dynamic linker is named`() {
        val root = validTree()
        assertThat(File(root, "lib/ld-linux-aarch64.so.1").delete()).isTrue()

        val findings = validator.validate(root)
        assertThat(findings.single().path).contains("ld-linux")
    }

    @Test
    fun `a linker under usr lib satisfies the manifest too`() {
        // Newer multiarch layouts move the linker; both hierarchy positions are legitimate.
        val root = validTree()
        assertThat(File(root, "lib/ld-linux-aarch64.so.1").delete()).isTrue()
        val usrLib = File(root, "usr/lib")
        usrLib.mkdirs()
        File(usrLib, "ld-linux-x86-64.so.2").apply {
            writeText("linker\n")
            setExecutable(true, true)
        }

        assertThat(validator.validate(root)).isEmpty()
    }

    @Test
    fun `a tree below the byte floor is named`() {
        // The default floor is what production uses; this fixture is bytes, not megabytes, which
        // is exactly the "the extraction produced almost nothing" shape it exists to catch.
        val findings = RootfsValidator().validate(validTree())
        assertThat(findings.map { it.path }).contains("<the whole tree>")
        assertThat(findings.single { it.path == "<the whole tree>" }.problem).contains("floor")
    }

    @Test
    fun `the pinned architecture's linker satisfies the manifest`() {
        val validator = RootfsValidator(minExtractedBytes = 1, expectedArch = "arm64")
        assertThat(validator.validate(validTree())).isEmpty()
    }

    @Test
    fun `a linker for a different architecture is named as a mismatch`() {
        // The shape the architecture check exists for: an x86 tarball that downloaded and
        // extracted perfectly onto an arm64 device. Without the check it passes every existence
        // test and dies at the first exec, with only proot's word for why.
        val validator = RootfsValidator(minExtractedBytes = 1, expectedArch = "arm64")
        val root = validTree()
        assertThat(File(root, "lib/ld-linux-aarch64.so.1").delete()).isTrue()
        File(root, "lib/ld-linux-x86-64.so.2").apply {
            writeText("wrong-arch linker\n")
            setExecutable(true, true)
        }

        val findings = validator.validate(root)
        assertThat(findings.single().path).isEqualTo("/lib/ld-linux-x86-64.so.2")
        assertThat(findings.single().problem).contains("different architecture")
        assertThat(findings.single().problem).contains("ld-linux-aarch64.so.1")
    }

    @Test
    fun `a missing linker under a pinned architecture names the one that was expected`() {
        val validator = RootfsValidator(minExtractedBytes = 1, expectedArch = "amd64")
        val root = validTree()
        assertThat(File(root, "lib/ld-linux-aarch64.so.1").delete()).isTrue()

        val findings = validator.validate(root)
        assertThat(findings.single().path).isEqualTo("lib/ld-linux-x86-64.so.2")
        assertThat(findings.single().problem).contains("no dynamic linker")
    }

    @Test
    fun `a linker in the multiarch position satisfies the pinned architecture`() {
        // Modern Ubuntu puts the linker under lib/<triplet>/; the exact-name search must walk
        // there too, or every current image would be refused.
        val validator = RootfsValidator(minExtractedBytes = 1, expectedArch = "arm64")
        val root = validTree()
        assertThat(File(root, "lib/ld-linux-aarch64.so.1").delete()).isTrue()
        val multiarch = File(root, "lib/aarch64-linux-gnu")
        multiarch.mkdirs()
        File(multiarch, "ld-linux-aarch64.so.1").apply {
            writeText("linker\n")
            setExecutable(true, true)
        }

        assertThat(validator.validate(root)).isEmpty()
    }

    @Test
    fun `an unrecognized architecture falls back to accepting any linker`() {
        // expectedArch is a string from the catalog, not an enum; an unknown value must degrade
        // to the prefix search, not refuse every rootfs ever after.
        val validator = RootfsValidator(minExtractedBytes = 1, expectedArch = "riscv64")
        assertThat(validator.validate(validTree())).isEmpty()
    }
}
