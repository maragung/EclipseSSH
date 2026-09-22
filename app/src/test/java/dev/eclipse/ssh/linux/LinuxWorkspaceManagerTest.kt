package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The workspace's own mechanisms, without the install pipeline around them: measuring the tree,
 * snapshotting it, and restoring it.
 *
 * The workspace is the one tree in this feature that is the *user's* data — created by whatever
 * they run inside Ubuntu, links and all — and the keep-workspace uninstall is the promise that it
 * survives an uninstall. Both halves are tested here against links, because links are what a
 * project tree is full of and what a follow-the-link walk gets wrong: it counts another tree's
 * bytes as this one's, it never terminates on a loop, and it archives a linked directory as
 * something no restore can reproduce.
 */
class LinuxWorkspaceManagerTest {

    private class Harness {
        val rootDir: File = Files.createTempDirectory("linux-workspace").toFile().apply { deleteOnExit() }
        val workspace = LinuxWorkspaceManager(ProotRuntime(rootDir, fakeNativeLibraryDir(), ScriptedPtySpawner()))
        val dir: File get() = workspace.workspaceDir

        fun file(path: String, content: String = "x\n"): File =
            dir.resolve(path).apply { parentFile?.mkdirs() }.also { it.writeText(content) }

        fun link(path: String, target: String): File =
            dir.resolve(path).apply { parentFile?.mkdirs() }.also {
                Files.createSymbolicLink(it.toPath(), Path.of(target))
            }
    }

    @Test
    fun `a linked directory survives the round trip as a link`() = runTest {
        // The shape a project tree has: the real directory, and a link to it. Archiving the link
        // as its target's contents is what makes a restore impossible - the entry becomes a
        // zero-byte file and then its "children" cannot be created under it.
        val harness = Harness()
        harness.file("projects/site.txt", "the user's work\n")
        harness.link("latest", "projects")

        // Inside the harness root, which is per-test: a fixed /tmp name would be one file shared
        // by every test (and by every parallel worker) that ever takes a snapshot.
        val archive = harness.workspace.snapshotTo(File(harness.rootDir, "ws-backup.tar.gz"))
        deleteTreeNoFollow(harness.dir)
        harness.workspace.restoreFrom(archive)

        assertThat(Files.isSymbolicLink(harness.dir.resolve("latest").toPath())).isTrue()
        assertThat(Files.readSymbolicLink(harness.dir.resolve("latest").toPath()).toString())
            .isEqualTo("projects")
        assertThat(harness.dir.resolve("latest/site.txt").readText()).isEqualTo("the user's work\n")
        assertThat(harness.dir.resolve("projects/site.txt").readText()).isEqualTo("the user's work\n")
    }

    @Test
    fun `the size of the workspace is its own, not what its links point at`() = runTest {
        // A link out of the workspace is normal (a project linked to somewhere else on the
        // device). Counting through it reports another tree's bytes as the user's workspace, and
        // a link back into the tree counts the same bytes twice.
        val harness = Harness()
        val notes = harness.file("notes.txt", "12345")
        val outside = Files.createTempDirectory("outside-the-workspace").toFile().apply { deleteOnExit() }
        File(outside, "big.bin").writeBytes(ByteArray(64 * 1024))
        harness.link("outside", outside.absolutePath)
        harness.link("self", ".")

        // The expectation is the fixture's own length, not a number typed beside it: this assertion
        // read 6 against a 5-byte file for exactly as long as nobody re-read it.
        assertThat(harness.workspace.sizeBytes()).isEqualTo(notes.length())
        // Three entries: the file, and the two links as themselves. The links are here for the
        // count and not for the size, which is the distinction the keep-workspace decision turns
        // on - a workspace of nothing but links is still a workspace with something to keep.
        assertThat(harness.workspace.fileCount()).isEqualTo(3L)
    }

    @Test
    fun `a link loop does not stop the snapshot from finishing`() = runTest {
        // `take` bounds the walk so that a regression to a follow-the-link walk fails this test
        // instead of hanging the suite: a looping walk yields entries forever, and the assertion
        // on the count is where that shows up.
        val harness = Harness()
        harness.link("loop", ".")
        harness.file("work.txt")

        val entries = walkTreeNoFollow(harness.dir).take(16).map { it.name }.toList()

        assertThat(entries).hasSize(2)
        assertThat(entries).containsExactly("loop", "work.txt")
    }

    @Test
    fun `a workspace that is there is left alone, and one that is gone comes back`() = runTest {
        val harness = Harness()
        harness.file("work.txt")

        // The common case costs a `stat`: the directory exists, so nothing is deleted and no file
        // in it is touched. "Did something" is the answer, not "is it there now" - a repair that
        // reported the second would claim a victory it had not checked.
        assertThat(harness.workspace.ensureExists()).isFalse()
        assertThat(harness.dir.resolve("work.txt").readText()).isEqualTo("x\n")

        // What a user who deleted the folder in their own terminal left: `/home` is a preserved
        // member, so no rung writes it back, and every session from here on opens in a home with no
        // workspace in it.
        deleteTreeNoFollow(harness.dir)

        assertThat(harness.workspace.ensureExists()).isTrue()
        assertThat(harness.dir.isDirectory).isTrue()
        assertThat(harness.dir.listFiles()).isEmpty()
    }

    @Test
    fun `a workspace that is a link is replaced by a real directory, and its target is untouched`() = runTest {
        // The one shape where "it is a directory" is not enough: a link makes `clear()` empty
        // whatever it points at, which may be outside the rootfs entirely. Following it here would
        // hand the user's project tree - or their whole home - to a delete they did not ask for.
        val harness = Harness()
        val outside = Files.createTempDirectory("outside-the-workspace").toFile().apply { deleteOnExit() }
        File(outside, "keep.txt").writeText("not the workspace's to delete\n")
        Files.createDirectories(harness.dir.parentFile.toPath())
        Files.createSymbolicLink(harness.dir.toPath(), outside.toPath())

        assertThat(harness.workspace.ensureExists()).isTrue()

        assertThat(Files.isSymbolicLink(harness.dir.toPath())).isFalse()
        assertThat(harness.dir.isDirectory).isTrue()
        assertThat(File(outside, "keep.txt").readText()).isEqualTo("not the workspace's to delete\n")
    }
}
