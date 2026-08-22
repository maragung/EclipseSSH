package dev.eclipse.ssh.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.PtyCapableChannelSession
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener

/**
 * One interactive shell channel: a pty on the remote host, its output as bytes and its input as
 * whatever the keyboard produced.
 *
 * Bytes, not text, in both directions. A terminal is a byte protocol - the escape sequences that move
 * the cursor, the C0 controls a key press sends, and the UTF-8 of the text itself all share one
 * stream, and the boundaries between network reads fall wherever the network put them. Decoding each
 * read to a `String` here, which is what this class used to do, split multi-byte codepoints in half at
 * random and turned them into replacement characters (see [dev.eclipse.ssh.terminal.Utf8StreamDecoder]
 * for what that looks like). It also made the channel the wrong place to decide the encoding: the
 * decoder has to be stateful across chunks, so it belongs with the session that owns the terminal
 * buffer, not with the transport.
 *
 * Passing bytes through is also what makes the stream cheap. There is no re-encoding, no intermediate
 * `String` per network read, and nothing is serialised into or out of a text format on the way to the
 * emulator - the chunk that arrives from the socket is the array the parser reads.
 */
class TerminalChannel(
    private val channel: ClientChannel,
    private val charset: Charset = Charsets.UTF_8,
) : Closeable {
    private val input = ChannelInputStream()

    /**
     * Output chunks in arrival order.
     *
     * [replay] keeps the first moments of the session for a collector that subscribes after [open] -
     * the login banner and the first prompt arrive within microseconds of the channel opening, which
     * is inside the window a caller needs to register its collector. Counted in chunks rather than
     * bytes, so the retained cost is bounded by the SSH packet size: a handful of chunks is ample for
     * a banner, and a larger cache would pin megabytes of scrollback that the terminal buffer is
     * already keeping.
     *
     * Each element is a private copy. Apache MINA reuses the array it hands to [OutputStream.write],
     * so a collector that saw the original would be reading a buffer that had already been refilled.
     * Collectors must not mutate what they receive.
     */
    private val outputEvents = MutableSharedFlow<ByteArray>(replay = REPLAY_CHUNKS, extraBufferCapacity = BUFFERED_CHUNKS)
    val output: SharedFlow<ByteArray> = outputEvents

    private val droppedChunkCount = AtomicLong()

    /**
     * How many output chunks were discarded because nothing drained [output] in time.
     *
     * Always zero in a healthy session. Exposed so the session that owns this channel can tell the
     * user their transcript has a hole in it rather than leaving them to wonder why a line never
     * appeared; see `MainViewModel.launchTerminalCollector`.
     */
    val droppedChunks: Long get() = droppedChunkCount.get()

    /**
     * Completes when this channel is finished, carrying the shell's exit status if it sent one.
     *
     * [output] is a `SharedFlow` and a `SharedFlow` never completes, so collecting it says nothing
     * about whether the far end is still there. Without a signal of its own, a session that dropped
     * after connecting - the shell exited, the network went away, the server was rebooted - was
     * indistinguishable from a shell sitting quietly at a prompt, and the app went on presenting a
     * dead session as a live one.
     *
     * [CompletableDeferred] rather than a flow because it is a one-shot fact that has to be readable
     * *after* it happens: a listener registered late still gets the answer, which matters because MINA
     * fires a close future immediately when the channel is already closed.
     */
    private val closed = CompletableDeferred<Int?>()

    /**
     * Finishes [closed] when the transport under this channel dies, rather than when the channel is
     * closed politely.
     *
     * The channel's own close future is not enough, and the case it misses is the one that matters
     * most. When a network drops silently - a phone leaving Wi-Fi, a NAT dropping the flow, a server
     * losing power - the keep-alive is what notices, and MINA reacts by closing the session
     * *gracefully*: it wants to send `SSH_MSG_CHANNEL_CLOSE` and wait for the peer's answer. Over a
     * socket that no longer delivers anything, that answer never comes, so the channel's close future
     * never fires. [awaitClosed] then waits forever on a session whose transport has already been
     * declared dead, the tab keeps presenting itself as connected, and the auto-reconnect that
     * `shouldAutoReconnect` would have started never gets the chance.
     *
     * [SessionListener.sessionException] is the earliest honest signal: MINA raises it the moment the
     * heartbeat gives up, before it attempts any teardown. [SessionListener.sessionClosed] covers the
     * orderly endings - a server-side disconnect, or the app closing the session under a channel that
     * is still open. Either way the exit status is whatever the shell managed to report, which for a
     * dropped transport is `null` - exactly what the reconnect decision reads as "went away on its
     * own" rather than "the user typed exit".
     */
    private val transportDeath = object : SessionListener {
        override fun sessionException(session: Session, t: Throwable) = markClosed()

        override fun sessionClosed(session: Session) = markClosed()
    }

    private fun markClosed() {
        closed.complete(channel.exitStatus)
    }

    /**
     * Set before the app closes this shell, or the session under it, on purpose.
     *
     * Without it a deliberate close is indistinguishable from a dropped link, and the difference
     * decides whether the app reconnects. Every ending arrives here the same way - MINA fires
     * [SessionListener.sessionClosed], the exit status is `null` because no shell reported one - so
     * `MainViewModel.shouldAutoReconnect` read the app's own teardown as "the network went away" and
     * started a reconnect ladder against a session the app had just closed. That is the mechanism
     * behind a tab that says *Reconnecting…* seconds after a successful login: something closed the
     * duplicate session, and the survivor's channel reported it as an outage.
     *
     * [AtomicBoolean] rather than `@Volatile var` so the flag can only be raised, and raised from any
     * thread: MINA's close listeners run on its own I/O threads while the close was requested from a
     * coroutine.
     */
    private val deliberate = AtomicBoolean(false)

    /**
     * Whether this shell ended because the app ended it, rather than because the far end went away.
     *
     * Read by the session collector to decide what to tell the user and whether to reconnect. A close
     * the app asked for needs neither.
     */
    val endedDeliberately: Boolean get() = deliberate.get()

    /**
     * Declares that what happens to this channel next was the app's decision.
     *
     * Called *before* the close itself, and before closing the session underneath it, because the
     * listener that reports the death can fire inside that call - on another thread, and before the
     * caller resumes. Raising the flag afterwards would be a race whose loser is a spurious reconnect.
     */
    fun markDeliberate() {
        deliberate.set(true)
    }

    /** Suspends until the channel closes, returning the remote exit status when one was reported. */
    suspend fun awaitClosed(): Int? = closed.await()

    @Volatile private var columns = DEFAULT_COLUMNS
    @Volatile private var rows = DEFAULT_ROWS

    suspend fun open() {
        (channel as? PtyCapableChannelSession)?.apply {
            setPtyType("xterm-256color")
            setPtyColumns(columns)
            setPtyLines(rows)
        }
        channel.setIn(input)
        channel.setOut(EmittingOutputStream())
        channel.setErr(EmittingOutputStream())
        channel.open().verify(OPEN_TIMEOUT_MS)
        // Registered after the open so a failed open reports itself as a failed open, through the
        // exception, rather than as a session that came up and immediately ended.
        channel.addCloseFutureListener { closed.complete(channel.exitStatus) }
        channel.session.addSessionListener(transportDeath)
        // Closes the gap between the open completing and the listener being in place: a session that
        // ended inside that window has already fired every event it is going to fire.
        if (!channel.session.isOpen) markClosed()
    }

    /**
     * Queues [bytes] for the remote shell verbatim. Safe to call from any thread.
     *
     * The primitive of the two writes, because most of what a terminal sends is not text: an arrow
     * key is `ESC [ A`, Ctrl-C is a single 0x03, and a function key is a sequence whose meaning
     * depends on modes the remote side negotiated. Those are produced as bytes by
     * [dev.eclipse.ssh.terminal.TerminalKeys] and must not make a round trip through a `String`.
     */
    fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        input.push(bytes)
    }

    /** Queues [value] for the remote shell, encoded with [charset]. Safe to call from any thread. */
    fun write(value: String) {
        if (value.isEmpty()) return
        writeBytes(value.toByteArray(charset))
    }

    fun resize(columns: Int, rows: Int) {
        // The display's own limits, not a second set of the channel's — see [TERMINAL_COLUMN_RANGE].
        val safeColumns = columns.coerceIn(TERMINAL_COLUMN_RANGE)
        val safeRows = rows.coerceIn(TERMINAL_ROW_RANGE)
        if (safeColumns == this.columns && safeRows == this.rows) return
        this.columns = safeColumns
        this.rows = safeRows
        runCatching { (channel as? PtyCapableChannelSession)?.sendWindowChange(safeColumns, safeRows) }
    }

    val isOpen: Boolean get() = channel.isOpen

    override fun close() {
        // First, so a listener that fires inside the close below already knows this was deliberate.
        markDeliberate()
        release()
    }

    /**
     * Releases a channel whose transport is already gone, without claiming the app meant it to end.
     *
     * [close] and this do the same work; they differ only in what they say about *why*, and the
     * difference decides whether a dropped session comes back. The registry closes a channel in two
     * quite different situations - the user closed the tab, and something noticed the session behind it
     * had died - and using [close] for the second one turned a real outage into "the app asked for
     * this": [SshSessionStore.liveSession] prunes a dead entry on any liveness check, so a heartbeat
     * failure raced the notification refresh and the tab's own close handler, and whichever got there
     * first decided whether the reconnect happened at all. Losing that race left a tab that had
     * genuinely dropped sitting there with no reconnect and no message - the exact failure the
     * deliberate flag exists to prevent, arrived at from the other side.
     *
     * There is nothing to mark here: the channel is being tidied up *after* the fact, and the report
     * of its death belongs to whatever was waiting on [awaitClosed].
     */
    fun discard() {
        release()
    }

    private fun release() {
        runCatching { input.close() }
        // Removed explicitly: one session is shared by every tab pointing at that host, so a listener
        // left behind here would outlive its channel and accumulate one entry per terminal the user
        // ever opened.
        runCatching { channel.session.removeSessionListener(transportDeath) }
        // Graceful while the transport is alive, so the shell sees EOF on stdin and the server can
        // reap the pty; immediate once it is not, because a graceful close waits for a
        // `SSH_MSG_CHANNEL_CLOSE` reply that a dead socket will never deliver, and waiting for it
        // pins this channel's buffers and window state for the life of the process.
        val transportAlive = runCatching { channel.session.isOpen }.getOrDefault(false)
        runCatching { channel.close(!transportAlive) }
        // Belt and braces for the case where the channel never opened, so nothing is left awaiting a
        // close future that was never registered.
        closed.complete(null)
    }

    private inner class EmittingOutputStream : OutputStream() {
        override fun write(value: Int) {
            publish(byteArrayOf(value.toByte()))
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            // Copied, not referenced: MINA refills this array on the next read.
            if (length > 0) publish(bytes.copyOfRange(offset, offset + length))
        }
    }

    /**
     * Hands [chunk] to the collectors, waiting briefly rather than dropping it if they are behind.
     *
     * `tryEmit` alone was a silent corruption bug. It returns false once the buffer fills, and the
     * caller here is an [OutputStream] with nowhere to report that - so under a burst the terminal
     * lost bytes *in the middle of an escape sequence*, leaving the emulator parsing the remainder as
     * text. What appeared on screen was a scattering of literal `[32m` fragments and a cursor in the
     * wrong place, with no error anywhere and no way for the user to guess why.
     *
     * Blocking is the honest response, and it is what a hardware terminal does: this runs on Apache
     * MINA's pump thread, so pausing it stops the channel window from being consumed and the remote
     * side stops sending. Nothing is lost and the shell simply slows to the speed the screen can keep
     * up with. The wait is bounded because that pump thread is shared with the session's other
     * channels - an SFTP transfer running alongside the terminal would stall with it - and
     * [PUBLISH_WAIT_MS] is far longer than any render takes while still being a length a transfer can
     * absorb. Past that the collector is not merely slow but gone, and holding a transport thread for
     * a session nobody is reading would be the worse failure.
     */
    private fun publish(chunk: ByteArray) {
        if (outputEvents.tryEmit(chunk)) return
        val delivered = runBlocking {
            withTimeoutOrNull(PUBLISH_WAIT_MS) {
                outputEvents.emit(chunk)
                true
            }
        } ?: false
        // Counted, because the one thing worse than dropping output is dropping it in silence. The
        // display cannot be made whole again from here — the bytes are gone and the emulator's state
        // depends on having seen them — so the honest thing is to be able to say so, which is what
        // [droppedChunks] is for. Reaching this at all means nothing drained [output] for
        // [PUBLISH_WAIT_MS], which is a fault in whoever owns the collector rather than a busy screen.
        if (!delivered) droppedChunkCount.incrementAndGet()
    }

    /**
     * Queue-backed stdin for the shell channel.
     *
     * [java.io.PipedInputStream] must not be used here: it records the identity of the
     * writing thread and every later `read()` throws `IOException("Write end dead")` once
     * that thread terminates. Writes arrive from whichever thread produced them - keystrokes from the
     * one driving the UI, the terminal's replies to cursor-position queries from the coroutine parsing
     * its output - and a pooled thread that is reclaimed after an idle period would break the shell a
     * minute after the user stopped typing. This implementation has no thread affinity.
     */
    private class ChannelInputStream : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var current: ByteArray? = null
        private var offset = 0
        @Volatile private var closed = false

        fun push(bytes: ByteArray) {
            if (closed) return
            queue.put(bytes)
        }

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null) {
                if (closed) return -1
                chunk = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                // Zero-length chunk is the shutdown token pushed by close().
                if (chunk.isEmpty()) {
                    closed = true
                    return -1
                }
                offset = 0
            }
            val count = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, bytes, off, count)
            offset += count
            current = if (offset >= chunk.size) null else chunk
            if (current == null) offset = 0
            return count
        }

        override fun available(): Int {
            val buffered = current?.let { it.size - offset } ?: 0
            return buffered + queue.sumOf { it.size }
        }

        override fun close() {
            if (closed) return
            closed = true
            // Unblock a reader parked in take() so the sshd pump thread can exit.
            queue.put(ByteArray(0))
        }
    }

    private companion object {
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_ROWS = 40
        const val OPEN_TIMEOUT_MS = 20_000L

        /** Enough to hold a login banner for a collector that subscribes just after [open]. */
        const val REPLAY_CHUNKS = 16

        /** Headroom for a burst, so the common case never has to block the pump thread. */
        const val BUFFERED_CHUNKS = 256

        /**
         * How long the pump thread waits for a collector before giving up on a chunk.
         *
         * Ten seconds rather than two. The collector drains into an unbounded channel on
         * `Dispatchers.Default` and does no I/O, so the only way to spend even one second here is a
         * dispatcher that is genuinely wedged — and the cost of being wrong in the short direction is
         * corrupted terminal state, while the cost of being wrong in the long direction is one pump
         * thread pausing a session nobody is reading.
         */
        const val PUBLISH_WAIT_MS = 10_000L
    }
}
