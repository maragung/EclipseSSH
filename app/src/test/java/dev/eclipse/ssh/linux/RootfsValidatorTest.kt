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
}
