package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assume
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
    fun `an absolute link whose target is another extracted absolute link is accepted`() {
        val root = newRoot()
        File(root, "etc/alternatives").mkdirs()
        // The pinned jammy image's pager chain, in extraction order: the alternatives link lands
        // on disk first, so usr/bin/pager's target *exists* as a device-side symlink to /bin/more
        // when it is checked. Walking that on-disk graph rejects the link as an escape (the
        // device really does resolve it to /bin/more), which aborted every real install at
        // usr/bin/pager; the guard must judge the linkName's own components instead, because
        // only inside the rootfs does /etc/alternatives/pager mean the rootfs's own file.
        Files.createSymbolicLink(
            File(root, "etc/alternatives/pager").toPath(),
            File("/bin/more").toPath(),
        )
        resolveLinkInsideRoot(root, "usr/bin/pager", "/etc/alternatives/pager")
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

    @Test
    fun `walking a tree yields a symlinked directory as a link, never as its target`() {
        val root = newRoot()
        val outside = Files.createTempDirectory("tar-safety-walk-outside").toFile().apply { deleteOnExit() }
        File(outside, "elsewhere.txt").writeText("another tree's data\n")
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "own.txt").writeText("own data\n")
        Files.createSymbolicLink(File(tree, "link-to-outside").toPath(), outside.toPath())

        val entries = walkTreeNoFollow(tree).map { it.name }.toList()

        // The link is yielded as itself and not descended into, which is the difference between
        // measuring this tree and measuring this tree plus whatever it points at.
        assertThat(entries).containsExactly("own.txt", "link-to-outside")
    }

    @Test
    fun `a symlink to a directory is not a directory of its own`() {
        val root = newRoot()
        val real = File(root, "real").apply { mkdirs() }
        val link = File(root, "link").also {
            Files.createSymbolicLink(it.toPath(), real.toPath())
        }
        val linkToFile = File(root, "link-to-file").also {
            File(root, "file.txt").writeText("content\n")
            Files.createSymbolicLink(it.toPath(), File(root, "file.txt").toPath())
        }

        // Followed, both links answer as what they point at - which is exactly why the no-follow
        // pair exists, and why they disagree here.
        assertThat(link.isDirectory).isTrue()
        assertThat(linkToFile.isFile).isTrue()
        assertThat(link.isDirectoryNoFollow()).isFalse()
        assertThat(linkToFile.isRegularFileNoFollow()).isFalse()
        assertThat(real.isDirectoryNoFollow()).isTrue()
        assertThat(File(root, "file.txt").isRegularFileNoFollow()).isTrue()
    }

    @Test
    fun `a walk that loses a directory to a permissions error still yields what it has`() {
        val root = newRoot()
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "own.txt").writeText("own data\n")
        val locked = File(tree, "locked").apply { mkdirs() }
        File(locked, "secret.txt").writeText("unreadable\n")
        // Directories need both bits off to be unreadable: 0o644 still lists. Running as root (CI
        // containers) defeats the premise rather than the walk — there is no permission to lose —
        // so the test states its premise and steps aside where it does not hold.
        val denied = locked.setReadable(false) && locked.setExecutable(false)
        Assume.assumeTrue(denied && !locked.canRead())
        try {
            val entries = walkTreeNoFollow(tree).map { it.name }.toList()

            // Losing one directory must not end the walk: what was already yielded is still true,
            // and every caller here measures rather than accounts.
            assertThat(entries).containsAtLeast("own.txt", "locked")
            assertThat(entries).doesNotContain("secret.txt")
        } finally {
            locked.setReadable(true)
            locked.setExecutable(true)
        }
    }

    @Test
    fun `a progress-reporting open counts the compressed bytes it hands over`() {
        val fixture = TestTarballs.writeRootfsFixture(
            newRoot().resolve("rootfs.tar.gz"),
        )
        val readings = mutableListOf<Long>()

        // Drained in full, because the count is of what the *decompressor* read: a stream opened
        // and abandoned would honestly report a partial reading, which is the property the install
        // screen's extraction bar depends on.
        openTarStream(fixture, 64 * 1024) { readings += it }.use { tar ->
            while (tar.nextTarEntry != null) {
                tar.copyTo(OutputStream.nullOutputStream())
            }
        }

        assertThat(readings).isNotEmpty()
        assertThat(readings.zipWithNext().filter { (before, after) -> after < before }).isEmpty()
        assertThat(readings.last()).isEqualTo(fixture.length())
    }

    @Test
    fun `the counter sees both ways of reading it`() {
        // The buffered stream above the counter calls one read entry point for its fills and a
        // direct caller may use the other; a count that saw only one of them reads as an extraction
        // stuck at 0%, so both are exercised rather than assumed.
        val file = newRoot().resolve("bytes.bin").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        val oneAtATime = mutableListOf<Long>()
        CountingFileStream(file) { oneAtATime += it }.use { stream ->
            while (stream.read() >= 0) Unit
        }
        val inBulk = mutableListOf<Long>()
        CountingFileStream(file) { inBulk += it }.use { stream ->
            stream.read(ByteArray(4))
            stream.read(ByteArray(16))
        }

        assertThat(oneAtATime.last()).isEqualTo(10L)
        assertThat(inBulk.last()).isEqualTo(10L)
    }
}
