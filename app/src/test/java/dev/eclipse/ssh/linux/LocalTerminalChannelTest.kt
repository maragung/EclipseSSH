package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.ssh.SessionEnd
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The local terminal channel against a scripted pty: that it keeps the [dev.eclipse.ssh.ssh.TerminalChannel]
 * contract the terminal tab already relies on — ordered output ending in the in-band marker, an
 * honest [SessionEnd], queued writes that never block the caller.
 *
 * The contract is the point: the tab, the collector and the buffer treat SSH and local terminals
 * identically, so a divergence here is invisible until the exact moment a local shell ends — which
 * is why each ending path (clean exit, signal death, app close) is asserted on its own.
 */
class LocalTerminalChannelTest {

    private fun newChannel(process: FakePtyProcess = FakePtyProcess()): LocalTerminalChannel =
        LocalTerminalChannel(process)

    @Test
    fun `output is published in order and the stream ends after a clean exit`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        process.emit("ubuntu@localhost")
        process.emit(":~$ ")
        process.exit(0)

        val end = runBlocking { channel.awaitClosed() }
        assertThat(end).isEqualTo(SessionEnd.ShellEnded(status = 0, signal = null))
        assertThat(channel.hasEnded).isTrue()
        assertThat(channel.endedDeliberately).isFalse()

        // Replay means the full conversation is still collectible after the end - a terminal tab
        // opened a moment late must not render a blank screen.
        val received = runBlocking { channel.output.take(3).toList() }
        assertThat(String(received[0])).isEqualTo("ubuntu@localhost")
        assertThat(String(received[1])).isEqualTo(":~$ ")
        assertThat(received[2]).isEmpty() // END_OF_OUTPUT is a zero-length chunk, by contract
    }

    @Test
    fun `a death by signal is reported with the signal's name`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        // 128+SIGINT: the pty bridge's convention for "killed by a signal".
        process.exit(130)

        val end = runBlocking { channel.awaitClosed() }
        assertThat(end).isEqualTo(SessionEnd.ShellEnded(status = null, signal = "INT"))
    }

    @Test
    fun `an app-side close is deliberate, drains nothing pending, and ends the stream`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        process.emit("still running")
        channel.close()

        runBlocking { channel.awaitClosed() }
        // Deliberate is the flag the reconnect ladder reads: a deliberate close must not look like
        // a dropped session that deserves an automatic reconnect attempt.
        assertThat(channel.endedDeliberately).isTrue()
        assertThat(channel.hasEnded).isTrue()
        val received = runBlocking { channel.output.take(2).toList() }
        assertThat(String(received[0])).isEqualTo("still running")
        assertThat(received[1]).isEmpty()
    }

    @Test
    fun `keystrokes are queued to the pty without blocking the caller`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        channel.write("apt list --upgradable\r")

        val written = process.writes.poll(5, TimeUnit.SECONDS)
        assertThat(written).isNotNull()
        assertThat(String(written!!)).isEqualTo("apt list --upgradable\r")

        channel.close()
    }

    @Test
    fun `resize clamps to the display's limits and forwards the pty geometry`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        assertThat(channel.ptyLabel).isEqualTo("120x40")

        channel.resize(newColumns = 10000, newRows = 1)
        assertThat(channel.ptyColumns).isEqualTo(400)
        assertThat(channel.ptyRows).isEqualTo(5)
        // The pty bridge's argument order is (rows, columns) - the one place the two conventions
        // meet, and a swap here would give every full-screen program a transposed terminal.
        assertThat(process.lastResize).isEqualTo(5 to 400)
        assertThat(channel.ptyLabel).isEqualTo("400x5")

        channel.close()
    }

    @Test
    fun `a duplicate resize is not forwarded`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        channel.resize(120, 40)
        assertThat(process.resizes).isEqualTo(0)

        channel.close()
    }

    @Test
    fun `a second teardown is a no-op, not a second close of the fd`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        // Both ending paths are reachable for one channel — Stop's closeAll closes it, the
        // session store's forget closes it again — and each used to close the master fd, so the
        // second one landed on whatever the process had since reused the descriptor for.
        channel.close()
        channel.discard(null)
        channel.close()

        val end = runBlocking { channel.awaitClosed() }
        assertThat(end).isEqualTo(SessionEnd.Released)
        assertThat(process.closeCount.get()).isEqualTo(1)
    }

    @Test
    fun `a resize after release never reaches the pty`() {
        val process = FakePtyProcess()
        val channel = newChannel(process)

        channel.close()
        channel.resize(newColumns = 100, newRows = 30)

        assertThat(process.resizes).isEqualTo(0)
    }

    @Test
    fun `a close is not held hostage by a writer parked on a shell that stopped reading`() {
        val process = BlockingWritePtyProcess()
        val channel = newChannel(process)

        channel.write("in flight\r")
        // Park the writer inside process.write; a shell that stopped reading its input does
        // exactly this, forever.
        assertThat(process.writeEntered.await(5, TimeUnit.SECONDS)).isTrue()

        val finished = CountDownLatch(1)
        Thread {
            channel.close()
            finished.countDown()
        }.start()

        // The bounded writer join gives up, the fd is closed and the channel ends — Release()
        // must return even though the writer never will.
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue()
        val end = runBlocking { channel.awaitClosed() }
        assertThat(end).isEqualTo(SessionEnd.Released)
        assertThat(process.closeCount.get()).isEqualTo(1)

        // And the wedged writer was abandoned, not lost: once the shell (the test) resumes it,
        // the queued input still lands — ordered after the close, exactly as the queue holds it.
        process.writeGate.countDown()
        val written = process.writes.poll(5, TimeUnit.SECONDS)
        assertThat(written).isNotNull()
        assertThat(String(written!!)).isEqualTo("in flight\r")
    }
}

/**
 * A [PtyProcess] whose slave side is scripted by the test: [emit] queues output, [exit] ends the
 * stream and fixes the reaped status, and every write/resize is recorded for assertions.
 *
 * Blocking semantics mirror the real bridge: `read` blocks until output or the end of the stream,
 * so the channel's reader thread parks exactly as it would on a real pty.
 */
internal class FakePtyProcess : PtyProcess {
    private val pending = LinkedBlockingQueue<ByteArray>()
    private val exited = CountDownLatch(1)

    val writes = LinkedBlockingQueue<ByteArray>()
    val closeCount = AtomicInteger()
    var resizes = 0
        private set
    var lastResize: Pair<Int, Int>? = null
        private set

    @Volatile private var exitStatus = 0

    fun emit(text: String) {
        pending.put(text.toByteArray(Charsets.UTF_8))
    }

    /** Ends the stream and reports [status] to the next [awaitExit]. */
    fun exit(status: Int) {
        exitStatus = status
        pending.put(ByteArray(0))
        exited.countDown()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val chunk = pending.take()
        if (chunk.isEmpty()) return -1
        val n = minOf(length, chunk.size)
        System.arraycopy(chunk, 0, buffer, offset, n)
        return n
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        writes.put(buffer.copyOfRange(offset, offset + length))
        return length
    }

    override fun resize(rows: Int, columns: Int) {
        resizes++
        lastResize = rows to columns
    }

    override fun awaitExit(): Int {
        exited.await()
        return exitStatus
    }

    override fun close() {
        closeCount.incrementAndGet()
        // Wake the reader with end-of-stream and unblock awaitExit: this is what SIGHUP-ing the
        // session does on a real pty.
        pending.put(ByteArray(0))
        exited.countDown()
    }
}

/**
 * A [FakePtyProcess] whose [write] parks until the test opens [writeGate] — the shape of a shell
 * that stopped reading its input, which parks the channel's writer thread inside process.write
 * forever. [writeEntered] tells the test the writer has actually arrived there.
 */
private class BlockingWritePtyProcess : FakePtyProcess() {
    val writeEntered = CountDownLatch(1)
    val writeGate = CountDownLatch(1)

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        writeEntered.countDown()
        writeGate.await()
        return super.write(buffer, offset, length)
    }
}
