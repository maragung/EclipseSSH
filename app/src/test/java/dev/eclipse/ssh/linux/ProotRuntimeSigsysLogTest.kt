package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * The blocked-syscall log's read path, which is the app's only view of what the proot fork decided.
 *
 * The log is append-only for the life of the userspace root — every trapped syscall in an apt
 * install adds a line, and the fork now also records each link it emulated (patch 0003) — so the
 * reader cannot be `readLines()`: it would pull an unbounded file into memory to show ten lines of
 * it, and the ten lines that matter are the ones at the end. These tests pin the window: the
 * newest complete records, nothing older, and never half a record.
 */
class ProotRuntimeSigsysLogTest {

    private fun runtime(root: File): ProotRuntime =
        ProotRuntime(root, "/fake/native/lib", ScriptedPtySpawner())

    private fun root(): File =
        Files.createTempDirectory("proot-sigsys").toFile().apply { deleteOnExit() }

    @Test
    fun `the tail is the newest ten records, oldest first`() {
        val root = root()
        File(root, "sigsys-log.txt").writeText((1..50).joinToString("\n") { "SIGSYS: trap $it" } + "\n")

        val tail = runtime(root).sigsysLogTail()

        assertThat(tail).hasSize(10)
        assertThat(tail.first()).isEqualTo("SIGSYS: trap 41")
        assertThat(tail.last()).isEqualTo("SIGSYS: trap 50")
    }

    @Test
    fun `a log larger than the read window drops the fragment at its start`() {
        val root = root()
        // ~160 KiB of 40-byte lines: the 64 KiB window cannot open on a line boundary here, so
        // its first entry is the tail of a record whose beginning is outside the window.
        val line = { n: Int -> "SIGSYS: trap %05d padded padding pad".format(n) }
        File(root, "sigsys-log.txt").writeText((1..4000).joinToString("\n") { line(it) } + "\n")

        val tail = runtime(root).sigsysLogTail()

        assertThat(tail).hasSize(10)
        assertThat(tail.last()).isEqualTo(line(4000))
        // Every entry is a whole record: a fragment would be shorter than a line and would not
        // match the shape the fork writes.
        val shape = Regex("SIGSYS: trap \\d{5} padded padding pad")
        assertThat(tail.all { shape.matches(it) }).isTrue()
    }

    @Test
    fun `a missing or empty log reads as no records`() {
        val root = root()
        val runtime = runtime(root)

        assertThat(runtime.sigsysLogTail()).isEmpty()

        File(root, "sigsys-log.txt").writeText("")
        assertThat(runtime.sigsysLogTail()).isEmpty()
    }

    @Test
    fun `resetting the log starts a fresh record`() {
        val root = root()
        val runtime = runtime(root)
        File(root, "sigsys-log.txt").writeText("SIGSYS: from an earlier run\n")

        runtime.resetSigsysLog()

        // Gone, not emptied: the next run's fork recreates it as it writes, and a reader between
        // the two sees no log rather than the previous run's evidence.
        assertThat(File(root, "sigsys-log.txt").exists()).isFalse()
        assertThat(runtime.sigsysLogTail()).isEmpty()
    }
}
