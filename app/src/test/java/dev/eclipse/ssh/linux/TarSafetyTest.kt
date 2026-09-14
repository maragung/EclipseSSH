package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Test

/**
 * The shared archive guards' own contracts, independent of the pipelines that use them.
 *
 * [resolveLinkInsideRoot]'s absolute-target rule is tested from both sides because it is a
 * deliberate deviation from blanket rejection: the pinned Ubuntu Base images ship 21 absolute
 * symlinks (`var/run -> /run`, `usr/bin/pidof -> /sbin/killall5`, …), all rootfs-internal, so an
 * absolute target is *mapped onto the root*, not refused — refused is only what escapes it.
 *
 * [deleteTreeNoFollow] is tested with a symlink to a directory outside the tree, because that is
 * the shape [File.deleteRecursively] gets wrong: it follows the link and deletes the target's
 * contents, which in this app's layout is the user's real data one directory over.
 */
class TarSafetyTest {

    private fun newRoot(): File = Files.createTempDirectory("tar-safety").toFile().apply { deleteOnExit() }

    @Test
    fun `a relative link that stays inside the root is accepted`() {
        val root = newRoot()
        File(root, "usr/bin").mkdirs()
        // usr/bin/env -> ../../bin/bash resolves to the root's own bin/bash.
        resolveLinkInsideRoot(root, "usr/bin/env", "../../bin/bash")
    }

    @Test
    fun `a relative link that climbs out of the root is refused`() {
        val root = newRoot()
        File(root, "home/ubuntu").mkdirs()
        var thrown: IOException? = null
        try {
            resolveLinkInsideRoot(root, "home/ubuntu/x", "../../../../etc/passwd")
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("outside")
    }

    @Test
    fun `an absolute link is mapped onto the root, not refused`() {
        val root = newRoot()
        File(root, "run").mkdirs()
        // var/run -> /run means the rootfs's own /run — exactly what the pinned Ubuntu Base
        // images ship, and what proot translates at runtime.
        resolveLinkInsideRoot(root, "var/run", "/run")
    }

    @Test
    fun `an absolute link that still escapes after mapping is refused`() {
        val root = newRoot()
        var thrown: IOException? = null
        try {
            resolveLinkInsideRoot(root, "home/ubuntu/x", "/../../outside")
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("outside")
    }

    @Test
    fun `an empty link target is refused`() {
        val root = newRoot()
        var thrown: IOException? = null
        try {
            resolveLinkInsideRoot(root, "home/ubuntu/x", "")
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("empty target")
    }

    @Test
    fun `deleting a tree does not follow a symlinked directory out of it`() {
        val root = newRoot()
        val outside = Files.createTempDirectory("tar-safety-outside").toFile().apply { deleteOnExit() }
        val precious = File(outside, "precious.txt").apply { writeText("user data\n") }
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "inside.txt").writeText("extraction content\n")
        // The shape deleteRecursively mishandles: a link to a directory, which it treats as a
        // directory and recurses into.
        java.nio.file.Files.createSymbolicLink(File(tree, "link-to-outside").toPath(), outside.toPath())

        assertThat(deleteTreeNoFollow(tree)).isTrue()
        assertThat(tree.exists()).isFalse()
        // The link is gone; what it pointed at is untouched.
        assertThat(precious.exists()).isTrue()
        assertThat(precious.readText()).isEqualTo("user data\n")
    }

    @Test
    fun `deleting a nonexistent tree reports success`() {
        assertThat(deleteTreeNoFollow(File(newRoot(), "absent"))).isTrue()
    }
}
