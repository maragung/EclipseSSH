package dev.eclipse.ssh.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.LinkedBlockingQueue
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.PtyCapableChannelSession

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
        runCatching { input.close() }
        runCatching { channel.close(false) }
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
        runBlocking { withTimeoutOrNull(PUBLISH_WAIT_MS) { outputEvents.emit(chunk) } }
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

        const val PUBLISH_WAIT_MS = 2_000L
    }
}
