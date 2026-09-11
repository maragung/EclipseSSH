package dev.eclipse.ssh.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.channel.ChannelSession
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
     *
     * The stream ends *in band*, with [END_OF_OUTPUT] as its last element. That is the only way a
     * collector can know it has seen everything: a `SharedFlow` delivers asynchronously, so a chunk
     * that has been emitted has not necessarily been received, and [awaitClosed] completing is a
     * different signal arriving on a different thread. A collector that treated the two as ordered -
     * closing its own queue the moment the channel reported it had ended - threw away whatever was
     * still in flight, which is exactly the last thing the shell said: the `logout` on the way out, the
     * final line of a build, the one line a `nologin` account ever prints. Compared by identity, not
     * by contents, so an empty write from the remote side cannot be mistaken for it.
     */
    private val outputEvents = MutableSharedFlow<ByteArray>(replay = REPLAY_CHUNKS, extraBufferCapacity = BUFFERED_CHUNKS)
    val output: SharedFlow<ByteArray> = outputEvents

    /** Guards [END_OF_OUTPUT] being emitted once, from whichever of the ending paths gets there first. */
    private val outputEnded = AtomicBoolean(false)

    private val droppedChunkCount = AtomicLong()

    /**
     * When the remote side last sent anything, as [System.currentTimeMillis], or 0 before it has.
     *
     * The strongest evidence a session is alive, and until this existed the app was throwing it away.
     * Every other liveness signal is inferential - a flag MINA may be mid-way through updating, a
     * global request that may be queued behind a burst of output, an interface that changed underneath
     * a socket that is still fine. Bytes arriving from the far end are none of those things: they are
     * the peer, answering, now.
     *
     * Only *server* bytes count. A keystroke proves the user is present, not that anyone is listening,
     * and counting writes here would let a user typing into a dead session keep it looking healthy -
     * which is the failure this is meant to prevent, inverted.
     *
     * Read by [SessionLivenessProbe] to veto a death conclusion. [AtomicLong] because MINA's pump
     * threads write it while a coroutine reads it.
     */
    private val lastActivityAt = AtomicLong(0)

    /** @see lastActivityAt */
    val lastActivityAtMs: Long get() = lastActivityAt.get()

    /**
     * When [open] completed, as [System.currentTimeMillis], or 0 while it has not.
     *
     * A session too young to have been asked anything cannot be judged for not answering. Without
     * this, a probe triggered by an interface change that happened to land moments after login would
     * put two six-second deadlines on a session that had not yet finished saying hello.
     */
    private val openedAt = AtomicLong(0)

    /** @see openedAt */
    val openedAtMs: Long get() = openedAt.get()

    /**
     * How many output chunks were discarded because nothing drained [output] in time.
     *
     * Always zero in a healthy session. Exposed so the session that owns this channel can tell the
     * user their transcript has a hole in it rather than leaving them to wonder why a line never
     * appeared; see `MainViewModel.launchTerminalCollector`.
     */
    val droppedChunks: Long get() = droppedChunkCount.get()

    /**
     * Completes when this channel is finished, carrying [SessionEnd]: *why* it finished.
     *
     * [output] is a `SharedFlow` and a `SharedFlow` never completes, so collecting it says nothing
     * about whether the far end is still there. Without a signal of its own, a session that dropped
     * after connecting - the shell exited, the network went away, the server was rebooted - was
     * indistinguishable from a shell sitting quietly at a prompt, and the app went on presenting a
     * dead session as a live one.
     *
     * It used to carry `Int?`, the shell's exit status, which made the six ways a session can end into
     * two: "the shell reported a status" and `null` for everything else. [SessionEnd] is why that was
     * not enough.
     *
     * [CompletableDeferred] rather than a flow because it is a one-shot fact that has to be readable
     * *after* it happens: a listener registered late still gets the answer, which matters because MINA
     * fires a close future immediately when the channel is already closed. First completion wins, so
     * every path below may report without checking whether another already has.
     */
    private val closed = CompletableDeferred<SessionEnd>()

    /**
     * What the transport said about its own death, recorded the moment MINA reports it.
     *
     * The channel's close future carries no reason at all, and by the time it fires the session may
     * already have been torn down - so the throwable from [SessionListener.sessionException] and the
     * reason from [SessionListener.sessionDisconnect] have to be kept when they arrive rather than
     * looked up afterwards. `@Volatile` because MINA's I/O threads write it and a coroutine reads it.
     */
    @Volatile private var transportReason: SessionEnd? = null

    /**
     * Set while [release] is closing this channel, so a close future firing inside that call knows the
     * app performed the close.
     *
     * Without it, pruning a channel whose transport had already died looked exactly like a shell that
     * exited without a status - and those two now mean opposite things to the reconnect decision.
     */
    @Volatile private var releasing = false

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
     * heartbeat gives up, before it attempts any teardown, and it carries the throwable that says what
     * went wrong. [SessionListener.sessionDisconnect] carries the server's own reason when the server
     * is the one hanging up. [SessionListener.sessionClosed] covers the rest - a bare socket close, or
     * the app closing the session under a channel that is still open. All three report a
     * [SessionEnd] that names the transport, which is what tells the reconnect decision this was an
     * outage rather than a shell that finished.
     */
    private val transportDeath = object : SessionListener {
        override fun sessionException(session: Session, t: Throwable) {
            val failure = SessionEnd.TransportFailed(t)
            transportReason = failure
            markClosed(failure)
        }

        /**
         * Recorded rather than reported, because `sessionClosed` always follows within microseconds
         * and completing here would race the shell's own exit status onto the floor. The reason is
         * what this listener exists for: an `SSH_MSG_DISCONNECT` is the one ending where the *server*
         * says why, and that sentence is worth more to the user than anything the app can infer.
         */
        override fun sessionDisconnect(session: Session, reason: Int, msg: String?, language: String?, initiator: Boolean) {
            if (transportReason == null) {
                transportReason = SessionEnd.Disconnected(reason = reason, message = msg, byPeer = !initiator)
            }
        }

        override fun sessionClosed(session: Session) = markClosed(transportReason ?: SessionEnd.TransportClosed)
    }

    /**
     * Reports [reason], unless the shell got its own word in first.
     *
     * A shell that exits takes its transport with it - OpenSSH closes the session behind the last
     * channel - so both endings arrive, microseconds apart and on threads MINA does not order for us.
     * When the shell reported a status or a signal, that is why the session ended and the transport
     * closing is a consequence; reporting the consequence instead is what made an ordinary `exit` look
     * like a dropped link.
     */
    private fun markClosed(reason: SessionEnd) {
        finish(shellReport() ?: reason)
    }

    /**
     * The shell's own account of its ending, or `null` if it never gave one.
     *
     * Wrapped because both getters read channel state that a concurrent teardown is mutating, and a
     * failure to read it must degrade to "the shell said nothing" rather than take down the thread
     * MINA is closing the session on.
     */
    private fun shellReport(): SessionEnd.ShellEnded? {
        val status = runCatching { channel.exitStatus }.getOrNull()
        val signal = runCatching { channel.exitSignal }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (status != null || signal != null) SessionEnd.ShellEnded(status, signal) else null
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

    /** Suspends until the channel closes, returning why it did. */
    suspend fun awaitClosed(): SessionEnd = closed.await()

    /**
     * Whether this channel's ending has already been reported to [awaitClosed].
     *
     * The question a caller asks before tidying a channel away: [finish] is first-completion-wins, so
     * once this is true no later release can invent an ending, and removing the channel is bookkeeping
     * that nobody can observe. Asked instead of [isOpen] because they are not the same instant - the
     * close future that publishes the reason runs after the channel reports itself shut - and it is the
     * *reason* being published that a reaper must not be allowed to race. See [SshSessionStore.reap].
     */
    val hasEnded: Boolean get() = closed.isCompleted

    /**
     * Ends the output stream, then reports [end] to whoever is waiting on [awaitClosed].
     *
     * Every ending goes through here, and in this order, so that a collector reaches the terminator
     * behind the last chunk rather than racing it. First completion wins; later calls do nothing.
     */
    private fun finish(end: SessionEnd) {
        endOutput()
        closed.complete(end)
    }

    /**
     * Puts [END_OF_OUTPUT] at the end of the stream, exactly once.
     *
     * `tryEmit` only, never the blocking [publish]: this runs on Apache MINA's I/O thread and on
     * whatever thread closed the channel - the main thread, when the user closes a tab - and pausing
     * either of those for a collector that is not draining would trade a lost line for a frozen UI.
     * The one case where the terminator cannot be queued is a shared flow already holding
     * [BUFFERED_CHUNKS] undelivered chunks, which means the collector has stopped draining
     * altogether; `MainViewModel.launchTerminalCollector` bounds its wait for the terminator for
     * exactly that reason.
     */
    private fun endOutput() {
        if (!outputEnded.compareAndSet(false, true)) return
        outputEvents.tryEmit(END_OF_OUTPUT)
    }

    @Volatile private var columns = DEFAULT_COLUMNS
    @Volatile private var rows = DEFAULT_ROWS

    /**
     * Whether this channel actually asked for a pty.
     *
     * Recorded because a window-change on a channel with no pty is not a no-op: RFC 4254 defines
     * `window-change` as a request against a pty that exists, and a server that is strict about it
     * answers with a channel failure. Every resize on such a host would then look like a fault.
     */
    @Volatile private var hasPty = true

    /**
     * The pty's current size, as the remote side understands it.
     *
     * Readable because the app has to be able to *carry the geometry across a reconnect*. A new
     * channel starts at [DEFAULT_COLUMNS]x[DEFAULT_ROWS], while the composable that measures the real
     * viewport only reports a size when the size it measures *changes* - so after a reconnect nothing
     * on the UI side had changed, nothing was reported, and a phone-sized terminal spent the rest of
     * its life pretending to be 120 columns wide. Every full-screen program was wrapped wrongly.
     */
    val ptyColumns: Int get() = columns
    val ptyRows: Int get() = rows

    /**
     * The pty as the trace records it: its geometry, or `none` on a channel that was given no pty.
     *
     * A trace that printed a geometry for a channel with no pty would be actively misleading - the
     * numbers exist either way, because a resize keeps recording them so a reconnect can carry the
     * size forward, so `80x24` on a pty-less channel is a size nobody applied. And "no pty" is the
     * explanation for a whole class of reports: no colours, `top` refusing to start, a shell with no
     * prompt. See [hasPty].
     */
    val ptyLabel: String get() = if (hasPty) "${columns}x$rows" else "none"

    /** Whether the channel itself is still open, as one word for the trace. See [isOpen]. */
    val channelLabel: String get() = if (isOpen) "open" else "closed"

    /**
     * How long since the far end last sent anything, or null while it has not sent anything yet.
     *
     * The single most useful number in a trace of a dropped session, because it separates the two
     * shapes that look identical from the outside: a session that was streaming output until the
     * moment it ended - which points at the transport or at this app - and one that had been silent
     * for minutes, which points at the far end or at whatever sits between. See [lastActivityAtMs].
     */
    fun idleForMs(nowMs: Long = System.currentTimeMillis()): Long? =
        lastActivityAtMs.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) }

    /**
     * Opens the shell, optionally at a known size.
     *
     * [columns] and [rows] exist for the reconnect path: sizing the pty *at creation* is not the same
     * as creating it at 120x40 and sending a window-change immediately afterwards. A shell whose
     * `$COLUMNS` is read by a login script, and a full-screen program started by one, see only the
     * first value - the resize arrives after they have already drawn themselves at the wrong width.
     *
     * [usePty] `false` asks for a shell with no terminal at all, which is what a scripted or
     * automation-only host wants: no echo, no line editing, no window size, and `$TERM` unset, so a
     * remote command cannot decide to draw a progress bar or a colour code at something that is reading
     * the bytes rather than watching them. It is off the normal path - an interactive terminal without a
     * pty is barely usable - so it stays a per-host choice.
     *
     * [terminalType] is what `$TERM` becomes on the far side, and it is a promise: claiming
     * `xterm-256color` tells the server this app understands everything an xterm does, and a host whose
     * emulator or curses build predates that is better served by being told `vt100` than by being sent
     * sequences it will print as text.
     *
     * [environment] is sent as one `env` channel request per entry, and has to be sent *here* because
     * the protocol allows it only between creating a channel and opening it - after the open there is no
     * request the server will honour, and MINA's own `setEnv` documents the same window. Best-effort by
     * design: OpenSSH refuses anything its `AcceptEnv` does not list, and refuses it silently, so a
     * variable that does not arrive is not something this side can detect or report.
     *
     * [agentForwarding] asks the host, with `auth-agent-req@openssh.com`, to open an agent channel
     * back at the phone while this shell is up - which is why it is a parameter of *the shell's*
     * open and of nothing else. The request rides the block MINA sends at open, and a host without
     * `AllowAgentForwarding` simply ignores it. What it grants is spelled out on the form's switch:
     * the server's administrator can ask the phone to sign as the user while the session is open.
     */
    suspend fun open(
        columns: Int = this.columns,
        rows: Int = this.rows,
        usePty: Boolean = true,
        terminalType: String = DEFAULT_TERMINAL_TYPE,
        environment: Map<String, String> = emptyMap(),
        agentForwarding: Boolean = false,
    ) {
        // Clamped once and then used everywhere. Sending the *parameter* to the pty while storing the
        // clamped value in the field put the two sides permanently out of step: the remote came up at
        // whatever was asked for, the app believed the clamped number, and because `resize` compares
        // against the field, the correcting window-change looked like a no-op and was never sent.
        val safeColumns = columns.coerceIn(TERMINAL_COLUMN_RANGE)
        val safeRows = rows.coerceIn(TERMINAL_ROW_RANGE)
        this.columns = safeColumns
        this.rows = safeRows
        this.hasPty = usePty
        (channel as? PtyCapableChannelSession)?.apply {
            setUsePty(usePty)
            // Sent from the same block, because MINA writes the agent-forwarding request in
            // doOpenPty() - which runs for a pty-less channel too, so the guard here is not
            // `usePty` but the flag itself.
            setAgentForwarding(agentForwarding)
            setPtyType(terminalType)
            setPtyColumns(safeColumns)
            setPtyLines(safeRows)
        }
        // Before the open, for the reason on the parameter. Guarded by a cast rather than assumed,
        // because `channel` is typed as the general channel interface and only a session channel carries
        // an environment - a host configured with variables but somehow opened on another channel kind
        // should lose the variables, not the shell.
        (channel as? ChannelSession)?.let { session ->
            environment.forEach { (name, value) -> session.setEnv(name, value) }
        }
        channel.setIn(input)
        channel.setOut(EmittingOutputStream())
        channel.setErr(EmittingOutputStream())
        channel.open().verify(OPEN_TIMEOUT_MS)
        // Stamped before either listener, so a session judged for its age is judged from the moment it
        // became judgeable rather than from whenever the last registration returned.
        openedAt.set(System.currentTimeMillis())
        // Both registered after the open so a failed open reports itself as a failed open, through the
        // exception, rather than as a session that came up and immediately ended.
        //
        // The *session* listener goes on first, and the order is the whole point: it is the only thing
        // that records a named [transportReason], and [closeReason] consults that reason before it
        // falls back to guessing from `channel.session.isOpen`. Registered the other way round, a
        // transport that died in the window between the two registrations fired its close future
        // against an empty `transportReason` - so the guess ran instead, and the guess produces
        // `ShellEnded(null, null)` or a bare `TransportClosed` for what was in fact a named failure the
        // server or the socket had already explained. First reason wins only if the thing that knows
        // the reason is listening first.
        channel.session.addSessionListener(transportDeath)
        channel.addCloseFutureListener { finish(closeReason()) }
        // Closes the gap between the open completing and the listeners being in place: a session that
        // ended inside that window has already fired every event it is going to fire.
        if (!channel.session.isOpen) markClosed(transportReason ?: SessionEnd.TransportClosed)
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
        // Still recorded above even with no pty, so the geometry a reconnect carries forward stays
        // right - only the request that needs a pty is skipped. See [hasPty].
        if (!hasPty) return
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
     * this": a dead entry used to be pruned by any liveness check, so a heartbeat
     * failure raced the notification refresh and the tab's own close handler, and whichever got there
     * first decided whether the reconnect happened at all. Losing that race left a tab that had
     * genuinely dropped sitting there with no reconnect and no message - the exact failure the
     * deliberate flag exists to prevent, arrived at from the other side.
     *
     * There is nothing to mark here: the channel is being tidied up *after* the fact, and the report
     * of its death belongs to whatever was waiting on [awaitClosed] - which reports whatever the
     * transport already said, and [SessionEnd.Released] only when nothing did.
     *
     * [reason] is for the one case where the app knows something the transport does not: the platform
     * has said the network this session was bound to is gone, so `isOpen` is still true, MINA has
     * nothing to report, and the honest answer would take three unanswered keep-alives to arrive at.
     * It is recorded only when the transport has said nothing yet, because a first-hand report always
     * outranks the app's conclusion - see [closeReason], which reads them in that order.
     */
    fun discard(reason: SessionEnd? = null) {
        if (reason != null && transportReason == null) transportReason = reason
        release()
    }

    /**
     * Why this channel closed, decided in order of how much each source actually knows.
     *
     * The shell's own report first: a status or a signal is the far end saying what happened. Then
     * whatever the transport reported, because a channel that closed underneath a failed transport
     * closed *because* of it. Then the app's own hand, if [release] is running. Only then is the guess
     * made, and the guess turns on the one question that decides whether reconnecting could help: was
     * the transport still up when the channel went? If it was, the shell is gone and a new one would
     * meet the same end; if it was not, the link died and waiting it out is exactly right.
     */
    private fun closeReason(): SessionEnd =
        shellReport()
            ?: transportReason
            ?: if (releasing) {
                SessionEnd.Released
            } else if (runCatching { channel.session.isOpen }.getOrDefault(false)) {
                SessionEnd.ShellEnded(status = null, signal = null)
            } else {
                SessionEnd.TransportClosed
            }

    private fun release() {
        // Before the close below, so a close future that fires inside it reports the app's own hand
        // rather than inventing a shell that exited silently.
        releasing = true
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
        finish(closeReason())
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
        // First, and unconditionally: this is the proof the peer is alive, and it is true whether or
        // not anyone is currently collecting the output it arrived in.
        lastActivityAt.set(System.currentTimeMillis())
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

    companion object {
        /**
         * The last element of [output]: the stream is over and nothing follows it.
         *
         * Empty, and compared by identity rather than by contents - [EmittingOutputStream] never
         * publishes a zero-length chunk, but identity means a remote side that somehow produced one
         * still could not impersonate the end of the session.
         */
        val END_OF_OUTPUT: ByteArray = ByteArray(0)

        private const val DEFAULT_COLUMNS = 120
        private const val DEFAULT_ROWS = 40
        private const val OPEN_TIMEOUT_MS = 20_000L

        /** Enough to hold a login banner for a collector that subscribes just after [open]. */
        private const val REPLAY_CHUNKS = 16

        /** Headroom for a burst, so the common case never has to block the pump thread. */
        private const val BUFFERED_CHUNKS = 256

        /**
         * How long the pump thread waits for a collector before giving up on a chunk.
         *
         * Ten seconds rather than two. The collector drains into an unbounded channel on
         * `Dispatchers.Default` and does no I/O, so the only way to spend even one second here is a
         * dispatcher that is genuinely wedged — and the cost of being wrong in the short direction is
         * corrupted terminal state, while the cost of being wrong in the long direction is one pump
         * thread pausing a session nobody is reading.
         */
        private const val PUBLISH_WAIT_MS = 10_000L
    }
}
