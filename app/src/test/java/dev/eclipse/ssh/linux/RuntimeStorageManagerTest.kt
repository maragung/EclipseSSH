package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Test

/**
 * What [RuntimeStorageManager.ensureReady] promises when a runtime directory cannot be created:
 * not a bare "cannot create <path>", but the observable state of the path plus the underlying
 * errno, so an on-device failure names its own cause. The three shapes here are the three
 * causes the first E2E install could not tell apart: a regular file parked at the path, a
 * dangling symlink at the path, and the happy path of everything simply missing.
 *
 * The second group is [RuntimeStorageManager.sweepOrphanTempDirs] and the one rule that makes it
 * safe to run against a device in use: a directory proot named after a *live* pid belongs to a
 * running session and is left alone, and nothing under a swept directory can lead the delete out of
 * it — the loader symlink these directories exist to hold is exactly the shape that would.
 */
class RuntimeStorageManagerTest {

    private fun newRoot(): File = Files.createTempDirectory("linux-storage").toFile().apply { deleteOnExit() }

    /** A `tmp` directory holding one directory per name, ready to be swept. */
    private fun tmpWith(root: File, vararg names: String): File {
        val tmp = File(root, "tmp").apply { mkdirs() }
        names.forEach { File(tmp, it).mkdirs() }
        return tmp
    }

    @Test
    fun `ensureReady creates every missing directory and proves tmp writable`() {
        val root = newRoot()

        RuntimeStorageManager(root).ensureReady()

        for (name in listOf("tmp", "downloads")) {
            val dir = File(root, name)
            assertThat(dir.isDirectory).isTrue()
        }
    }

    @Test
    fun `a regular file parked at tmp fails with the path state in the message`() {
        val root = newRoot()
        RuntimeStorageManager(root).ensureReady()
        val tmp = File(root, "tmp")
        tmp.deleteRecursively()
        tmp.writeText("not a directory")

        val error = runCatching { RuntimeStorageManager(root).requireReady() }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        val message = error!!.message!!
        assertThat(message).contains("cannot create $tmp")
        assertThat(message).contains("exists=true")
        assertThat(message).contains("isDirectory=false")
        // The wrapped failure is the errno-bearing NIO exception, kept for the log's cause chain.
        assertThat(error.cause).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a dangling symlink at tmp fails naming the symlink`() {
        val root = newRoot()
        RuntimeStorageManager(root).ensureReady()
        val tmp = File(root, "tmp")
        tmp.deleteRecursively()
        val link = Files.createSymbolicLink(
            tmp.toPath(),
            root.toPath().resolve("tmp-does-not-exist"),
        )
        val error = runCatching { RuntimeStorageManager(root).requireReady() }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        val message = error!!.message!!
        assertThat(message).contains("symlink=true")
        // The symlink itself must survive the failed attempts: nothing here may "fix" the path
        // by deleting the obstruction, because on a real device the obstruction is the evidence.
        assertThat(Files.isSymbolicLink(link)).isTrue()
    }

    @Test
    fun `an existing directory tree passes without touching it`() {
        val root = newRoot()
        val preexisting = File(root, "tmp").apply { mkdirs() }
        val marker = File(preexisting, "kept").apply { writeText("kept") }

        RuntimeStorageManager(root).ensureReady()

        assertThat(marker.readText()).isEqualTo("kept")
    }

    @Test
    fun `the sweep clears a gone process's directory and keeps a running process's`() {
        val root = newRoot()
        val mine = TestPids.thisProcess()
        val gone = TestPids.nothingRuns()
        val tmp = tmpWith(root, "exec-$gone-bervFx", "exec-$mine-bervFx")
        // A session in progress: its directory holds the links it is executing through.
        val inUse = File(tmp, "exec-$mine-bervFx/bash").apply { writeText("in use") }

        // The real liveness answer, not an injected one: /proc is what the app reads on a device.
        val swept = RuntimeStorageManager(root).sweepOrphanTempDirs()

        assertThat(swept).isEqualTo(1)
        assertThat(File(tmp, "exec-$gone-bervFx").exists()).isFalse()
        assertThat(inUse.readText()).isEqualTo("in use")
    }

    @Test
    fun `the sweep never follows a symlink out of the directory it is clearing`() {
        val root = newRoot()
        val gone = TestPids.nothingRuns()
        val tmp = tmpWith(root, "exec-$gone-bervFx")
        // What is in one of these for real: a link to the loader, whose target is not ours to
        // delete. Here the target is a directory, which is the case a following delete would empty.
        val outside = File(root, "outside").apply { mkdirs() }
        val kept = File(outside, "keep-me").apply { writeText("not scratch") }
        val link = File(tmp, "exec-$gone-bervFx/loader")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        val swept = RuntimeStorageManager(root).sweepOrphanTempDirs()

        assertThat(swept).isEqualTo(1)
        assertThat(link.exists()).isFalse()
        assertThat(kept.readText()).isEqualTo("not scratch")
    }

    @Test
    fun `the sweep removes a stale probe file and keeps a fresh one`() {
        val root = newRoot()
        val tmp = tmpWith(root)
        val stale = File(tmp, ".probe-100").apply { writeText("probe") }
        // Two hours: past the sweep's hour, and set explicitly rather than by touching the file,
        // so the test does not depend on the filesystem storing a pre-epoch timestamp.
        stale.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        val fresh = File(tmp, ".probe-200").apply { writeText("probe") }

        val swept = RuntimeStorageManager(root).sweepOrphanTempDirs()

        assertThat(swept).isEqualTo(1)
        assertThat(stale.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
    }

    @Test
    fun `the sweep leaves entries that are not proot's alone`() {
        val root = newRoot()
        val gone = TestPids.nothingRuns()
        // A name proot could not have made, and one that looks like a pid and a suffix but has no
        // six-character tail to complete the shape.
        val tmp = tmpWith(root, "notes", "exec-$gone-bervFx-extra")
        val notes = File(tmp, "notes/kept").apply { writeText("the user's") }

        val swept = RuntimeStorageManager(root).sweepOrphanTempDirs()

        assertThat(swept).isEqualTo(0)
        assertThat(notes.readText()).isEqualTo("the user's")
    }

    @Test
    fun `a sweep of a tmp directory that is not there removes nothing and does not fail`() {
        val root = newRoot()

        assertThat(RuntimeStorageManager(root).sweepOrphanTempDirs()).isEqualTo(0)
    }
}
