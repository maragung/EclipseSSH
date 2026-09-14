package dev.eclipse.ssh.linux

import dev.eclipse.ssh.ssh.SessionEnd
import dev.eclipse.ssh.ssh.TerminalChannel
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The interactive-session half of the userspace: a [TerminalChannel] whose far side is a locally
 * forked proot process, not an SSH server.
 *
 * It implements the same contract as the SSH channel — bytes both ways, the stream ending in band
 * with [TerminalChannel.END_OF_OUTPUT], a [SessionEnd] carried by [awaitClosed], deliberate-close
 * bookkeeping — so the terminal tab, the collector and the terminal buffer cannot tell the
 * difference between a shell on a server in another country and a shell in the phone's own
 * sandbox. What is genuinely different is where the bytes come from and go: a pty master fd, not a
 * socket, and one of our own threads, not MINA's pump.
 *
 * Threading, deliberately asymmetric:
 *
 *  - **One private reader thread** pumps the pty into [output]. It owns nothing else, blocks only
 *    on `read()`, and publishes each chunk as a private copy (the buffer is reused on the next
 *    iteration, so a collector must never see the original).
 *  - **One private writer thread** drains a queue, so a caller blocked on a full pty — a program
 *    that stopped reading its input — never blocks the thread a keystroke arrived on. Same shape,
 *    and for the same reason, as the SSH channel's queue-backed stdin.
 *
 * Lifecycles: the channel ends either when the reader hits end-of-stream (the shell exited: the
 * reader reaps it and reports [SessionEnd.ShellEnded]) or when the app closes it (the writer is
 * drained, the pty closed — SIGHUP to the session — and [SessionEnd.Released] is reported if
 * nothing raced us to it). First completion wins, exactly like the SSH channel: both endings can
 * be in flight at once, and whichever the user caused is the one that matters.
 *
 * @param process the forked proot from [PtySpawner]; this channel takes sole ownership of it
 */
class LocalTerminalChannel(
    private val process: PtyProcess,
) : TerminalChannel {

    private val outputEvents = MutableSharedFlow<ByteArray>(replay = REPLAY_CHUNKS, extraBufferCapacity = BUFFERED_CHUNKS)
    override val output: SharedFlow<ByteArray> = outputEvents

    private val outputEnded = AtomicBoolean(false)
    private val deliberate = AtomicBoolean(false)

    /**
     * Guards [release]: both ending paths — [close] and [discard] — funnel into it, and both are
     * reachable for the same channel (Stop's closeAll, the session store's forget, a replaced
     * registration). A second teardown would close a master fd the process may have already
     * handed to something else, so exactly one caller proceeds.
     */
    private val released = AtomicBoolean(false)
    private val droppedChunkCount = AtomicLong()
    private val lastActivityAt = AtomicLong(0)

    /**
     * When the pty was forked, as `System.currentTimeMillis` — the same "too young to judge"
     * evidence the liveness probe reads off an SSH channel, and the reason it is on the
     * interface at all.
     */
    private val openedAt = System.currentTimeMillis()

    private val closed = CompletableDeferred<SessionEnd>()

    private val writes = LinkedBlockingQueue<ByteArray>()
    @Volatile private var writesClosed = false

    @Volatile private var columns = DEFAULT_COLUMNS
    @Volatile private var rows = DEFAULT_ROWS

    private val reader = Thread(::pumpOutput, "linux-terminal-reader").apply { isDaemon = true }
    private val writer = Thread(::drainWrites, "linux-terminal-writer").apply { isDaemon = true }

    init {
        reader.start()
        writer.start()
    }

    override val hasEnded: Boolean get() = closed.isCompleted

    override val endedDeliberately: Boolean get() = deliberate.get()

    override val droppedChunks: Long get() = droppedChunkCount.get()

    override val lastActivityAtMs: Long get() = lastActivityAt.get()

    override val openedAtMs: Long get() = openedAt

    override fun idleForMs(nowMs: Long): Long? =
        lastActivityAtMs.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) }

    override val ptyColumns: Int get() = columns
    override val ptyRows: Int get() = rows

    override val ptyLabel: String get() = "${columns}x$rows"

    // A pty is not optional here — the whole channel *is* a pty — so there is no "none" case and
    // the label is always the geometry.
    override val channelLabel: String get() = if (isOpen) "open" else "closed"

    override val isOpen: Boolean get() = !hasEnded

    override suspend fun awaitClosed(): SessionEnd = closed.await()

    override fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (writesClosed) return
        // Copied: keystrokes and terminal replies are produced by the UI thread, which reuses
        // nothing, but a caller passing a buffer it mutates must not corrupt what the writer
        // thread has not reached yet.
        writes.put(bytes.copyOf())
    }

    override fun write(value: String) {
        if (value.isEmpty()) return
        writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    override fun resize(newColumns: Int, newRows: Int) {
        // A released channel has no pty to resize: the master fd is closed and possibly reused,
        // and the native bridge would refuse the ioctl anyway.
        if (released.get()) return
        // Same clamping discipline as the SSH channel: the display's limits, agreed once, so the
        // pty and the terminal buffer never disagree about which size they hold.
        val safeColumns = newColumns.coerceIn(TERMINAL_COLUMN_RANGE)
        val safeRows = newRows.coerceIn(TERMINAL_ROW_RANGE)
        if (safeColumns == columns && safeRows == rows) return
        columns = safeColumns
        rows = safeRows
        process.resize(safeRows, safeColumns)
    }

    override fun markDeliberate() {
        deliberate.set(true)
    }

    override fun discard(reason: SessionEnd?) {
        // A local pty has no separate transport, so there is no recorded transport reason to
        // prefer: whatever ended the channel is what the reader already saw.
        release()
    }

    override fun close() {
        markDeliberate()
        release()
    }

    /**
     * The teardown both ending paths share: stop accepting writes, drain them so a shell reading
     * its last input still gets it, close the pty (SIGHUP to the child's session), then report
     * [SessionEnd.Released] unless the reader already reported the shell's own ending.
     *
     * Idempotent by [released]: whichever of close/discard arrives second returns immediately, so
     * the master fd is closed exactly once however many ending paths race.
     */
    private fun release() {
        if (!released.compareAndSet(false, true)) return
        writesClosed = true
        // Unblocks the writer if it is parked waiting for something that will never come.
        writes.put(ByteArray(0))
        // The writer drains whatever the shell has not read yet before the fd goes away. Bounded:
        // a child that stopped reading its input parks the writer inside process.write forever,
        // and a close must not be held hostage by it — past the bound the writer's in-flight
        // write is refused by the native bridge's freed-slot guard instead.
        writer.join(DRAIN_WAIT_MS)
        process.close()
        // Closing the pty wakes the reader with end-of-stream; let it publish whatever it had
        // already read before the terminator goes in, or the marker lands *ahead* of the shell's
        // last lines — a terminal closed mid-banner would replay a blank screen. The wait is
        // bounded, and past it the trade is the one the SSH channel's terminator makes: a lost
        // line rather than a tab close held up by a collector that stopped draining.
        reader.join(DRAIN_WAIT_MS)
        finish(SessionEnd.Released)
    }

    /**
     * Ends the output stream, then completes [closed]. Same order and same first-wins rule as the
     * SSH channel: the terminator must sit *behind* the last chunk, not race it.
     */
    private fun finish(end: SessionEnd) {
        if (!outputEnded.compareAndSet(false, true)) {
            // Someone already ended the stream; their reason is the one that counts. The deferred
            // is completed by the same code path that won the race.
            return
        }
        outputEvents.tryEmit(TerminalChannel.END_OF_OUTPUT)
        closed.complete(end)
    }

    /**
     * The reader loop: pty bytes out, forever, until the child side closes.
     *
     * `read()` returning -1 means the child is gone (Linux reports EIO on a pty whose slave side
     * closed). At that point the child has exited, so the reap cannot block meaningfully and the
     * exit status is the honest ending: this is the local equivalent of the SSH channel reading a
     * shell's `exit-status`, and it must win over a concurrent [close] the same way — the shell
     * said something, and its account outranks the app's bookkeeping.
     */
    private fun pumpOutput() {
        val chunk = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val read = process.read(chunk, 0, chunk.size)
                if (read < 0) break
                if (read > 0) publish(chunk.copyOf(read))
            }
            // The deliberate flag is read here because release() waits for this loop to drain
            // before finishing, so on an app-side close the reader is the one that gets to
            // finish first — and a shell SIGHUP'd by our own close must not be reported as a
            // shell death when the honest account is that the app let go.
            if (deliberate.get()) {
                finish(SessionEnd.Released)
                return
            }
            val status = runCatching { process.awaitExit() }.getOrDefault(-1)
            finish(
                when {
                    status in 129..255 -> SessionEnd.ShellEnded(
                        status = null,
                        // 128+N is the pty bridge's convention for "killed by signal N"; a real
                        // name makes the tab's error line read like every other line that names a
                        // signal.
                        signal = signalName(status - 128),
                    )
                    else -> SessionEnd.ShellEnded(status = status, signal = null)
                },
            )
        } catch (_: InterruptedException) {
            // Only a release races us here, and release() completes the channel itself.
        }
    }

    /**
     * The writer loop: queued input in, pty bytes out. A zero-length chunk is the shutdown token,
     * pushed by [release] to wake the writer after the queue is closed.
     */
    private fun drainWrites() {
        try {
            while (true) {
                val chunk = writes.take()
                if (chunk.isEmpty()) return
                var offset = 0
                while (offset < chunk.size) {
                    val written = process.write(chunk, offset, chunk.size - offset)
                    if (written < 0) return
                    offset += written
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    /**
     * Hands [chunk] to the collectors, blocking briefly rather than dropping it when they are
     * behind — the same reasoning, word for word, as the SSH channel's publish: a drop lands in
     * the middle of an escape sequence and corrupts the emulator's state, while a bounded pause
     * merely slows the reader thread, and the pty's own flow control pushes back on the shell.
     *
     * One difference from SSH: the pausing thread is ours, not a transport thread shared with
     * SFTP and port forwards, so the wait can be simple.
     */
    private fun publish(chunk: ByteArray) {
        lastActivityAt.set(System.currentTimeMillis())
        if (outputEvents.tryEmit(chunk)) return
        val delivered = runBlocking {
            withTimeoutOrNull(PUBLISH_WAIT_MS) {
                outputEvents.emit(chunk)
                true
            }
        } ?: false
        if (!delivered) droppedChunkCount.incrementAndGet()
    }

    companion object {
        /**
         * The signals a local shell is realistically killed by. Anything else still gets a name —
         * `SIG<n>` — because a raw number in a sentence the user reads is a question, not an
         * answer.
         */
        private fun signalName(number: Int): String = when (number) {
            1 -> "HUP"
            2 -> "INT"
            3 -> "QUIT"
            6 -> "ABRT"
            9 -> "KILL"
            11 -> "SEGV"
            13 -> "PIPE"
            15 -> "TERM"
            else -> number.toString()
        }

        private const val DEFAULT_COLUMNS = 120
        private const val DEFAULT_ROWS = 40

        private const val READ_CHUNK = 4096

        /** A login banner for a collector that subscribes just after spawn. */
        private const val REPLAY_CHUNKS = 16

        /** Headroom for a burst, so the reader never has to block in the common case. */
        private const val BUFFERED_CHUNKS = 256

        private const val PUBLISH_WAIT_MS = 10_000L

        /**
         * How long an app-side close waits for the reader to publish its already-read chunks
         * before putting the terminator in. Normally the reader drains in well under a
         * millisecond — the pty has just been closed — so this bound only matters when the
         * collector has stopped draining altogether.
         */
        private const val DRAIN_WAIT_MS = 1_000L
    }
}
