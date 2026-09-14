package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createSymbolicLink
import org.junit.Test

/**
 * What [RuntimeStorageManager.ensureReady] promises when a runtime directory cannot be created:
 * not a bare "cannot create <path>", but the observable state of the path plus the underlying
 * errno, so an on-device failure names its own cause. The three shapes here are the three
 * causes the first E2E install could not tell apart: a regular file parked at the path, a
 * dangling symlink at the path, and the happy path of everything simply missing.
 */
class RuntimeStorageManagerTest {

    private fun newRoot(): File = Files.createTempDirectory("linux-storage").toFile().apply { deleteOnExit() }

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
}
