package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.presentation.shouldAutoReconnect
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import dev.eclipse.ssh.terminal.Utf8StreamDecoder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardSocketOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.RequestHandler
import org.apache.sshd.common.io.nio2.Nio2Session
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.ConnectionServiceRequestHandler
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The properties that decide whether a session *feels* like a terminal and whether it stays up:
 * socket options, a heartbeat that can be answered, character-at-a-time typing, window changes, and
 * what happens when the transport is taken away.
 *
 * Every assertion here is measured against a real Apache MINA SSHD server over a real loopback
 * socket, because none of these are things the client can be trusted to report about itself. The
 * previous configuration *looked* right in code and was wrong on the wire in three ways at once:
 * Nagle was on (MINA's `tcp-nodelay` default is false, so a keystroke waited for the previous
 * segment's ACK), `SO_KEEPALIVE` was off, and the heartbeat was [HeartbeatType.IGNORE] - a message
 * the peer is required *not* to answer, which means an unanswered one proves nothing and a dead peer
 * was never noticed.
 *
 * The dead-peer test runs through a relay that can be frozen mid-session, which is the only honest
 * way to reproduce what a phone leaving Wi-Fi does to an SSH connection: the socket stays open, the
 * bytes stop arriving, and nothing is reported to either end.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStabilityTest {

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val USER = "stability"
        private const val PASSWORD = "stability-pass-123"

        /** How long a terminal round trip may take before it counts as broken rather than slow. */
        private const val WAIT_MS = 20_000L

        /**
         * Ceiling for the dead-peer test. Detection is expected at roughly
         * [HEARTBEAT_SECONDS] x (no-reply-max + 1) = 20s; the rest is headroom for a loaded shared
         * machine, and the test still fails - rather than hanging - if the mechanism is gone.
         */
        private const val DEAD_PEER_WAIT_MS = 120_000L

        /** The shortest keep-alive the app allows, so the dead-peer test is 20s and not 2 minutes. */
        private const val HEARTBEAT_SECONDS = 5

        /**
         * Probe deadline for the tests that expect no answer.
         *
         * Shorter than [SshConnectionManager]'s own default, because the tests that use it are asserting
         * that a *dead* link is reported dead - there is nothing to wait for - and the sweep's real
         * deadline is already covered by the bound in the black-hole test.
         */
        private const val PROBE_TIMEOUT_SECONDS = 3L

        /**
         * Lines in the flood test, chosen so the payload is over a megabyte.
         *
         * That is comfortably more than the channel's own buffer of 256 chunks, which is what puts the
         * blocking publish path - the one that exists only for floods - under test rather than the
         * `tryEmit` fast path every other test here exercises.
         */
        private const val FLOOD_LINES = 120_000
        private const val FLOOD_MIN_BYTES = 1_048_576
        private const val FLOOD_WAIT_MS = 120_000L

        /** [dev.eclipse.ssh.terminal.AnsiTerminalBuffer]'s own scrollback ceiling, which is private to it. */
        private const val MAX_SCROLLBACK_LINES = 2_000

        private const val CTRL_C = 0x03.toByte()
        private const val CTRL_D = 0x04.toByte()

        private var serverPort = 0
        private lateinit var root: Path
        private lateinit var server: SshServer

        /** Every shell the server has started, so a test can inspect what its own shell received. */
        private val shells = CopyOnWriteArrayList<RecordingShell>()

        /** Counts `keepalive@openssh.com` arriving at the server, and answers them as any server does. */
        private val keepalives = CountingKeepaliveHandler()

        /**
         * Successful password authentications the server has performed.
         *
         * The duplicate-dial bug was only ever provable here. The client end of it looks like a
         * working session - two sessions to one account are both open and both authenticated - and
         * what gave it away in the field was two lines in the server's auth log for one tap on
         * Connect.
         */
        private val logins = AtomicInteger()

        @JvmStatic
        @BeforeClass
        fun startServer() {
            root = Files.createTempDirectory("eclipse-ssh-stability")
            server = SshServer.setUpDefaultServer()
            server.port = 0
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser"))
            server.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                val ok = username == USER && password == PASSWORD
                if (ok) logins.incrementAndGet()
                ok
            }
            server.shellFactory = ShellFactory { RecordingShell().also(shells::add) }
            server.globalRequestHandlers =
                listOf<RequestHandler<ConnectionService>>(keepalives) + server.globalRequestHandlers.orEmpty()
            server.start()
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
        }
    }

    private fun hostProfile(port: Int = serverPort, keepAlive: Int? = null) = HostProfile(
        name = "stability",
        host = LOOPBACK,
        username = USER,
        port = port,
        authMethod = AuthMethod.PASSWORD,
        // Same reasoning as SshIntegrationTest: these tests assert SSH behaviour, not handshake
        // latency, and the suite shares one JVM with Robolectric.
        connectTimeoutSeconds = 60,
        keepAliveSeconds = keepAlive,
    )

    private fun newManager(): SshConnectionManager {
        val context = RuntimeEnvironment.getApplication()
        return SshConnectionManager(context, SettingsRepository(context))
    }

    /** Connects, trusting the server key the first time it is offered - the app's own flow. */
    private suspend fun trustedConnect(manager: SshConnectionManager, profile: HostProfile): ClientSession =
        coroutineScope {
            val challengeRef = AtomicReference<HostKeyChallenge?>()
            val collector = launch { manager.hostKeyChallenges.collect { challengeRef.set(it) } }
            val attempt = runCatching { manager.connect(profile, PASSWORD, null) }
            delay(300)
            collector.cancel()
            if (attempt.isSuccess) return@coroutineScope attempt.getOrThrow()
            val challenge = challengeRef.get()
                ?: throw attempt.exceptionOrNull() ?: IllegalStateException("connect failed with no challenge")
            manager.trustHost(challenge)
            manager.connect(profile, PASSWORD, null)
        }

    /** Waits for the shell the caller is about to talk to, identified by position rather than time. */
    private suspend fun awaitShell(startedBefore: Int): RecordingShell {
        val deadline = System.nanoTime() + WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (shells.size > startedBefore) return shells[startedBefore]
            delay(25)
        }
        throw AssertionError("the server never started a shell for this channel")
    }

    /** Decodes the channel's output exactly as the ViewModel's collector does. */
    private fun CoroutineScope.collectText(terminal: TerminalChannel): Pair<StringBuilder, Job> {
        val sink = StringBuilder()
        val decoder = Utf8StreamDecoder()
        val job = launch(Dispatchers.IO) {
            terminal.output.collect { chunk -> synchronized(sink) { sink.append(decoder.decode(chunk)) } }
        }
        return sink to job
    }

    private suspend fun awaitText(sink: StringBuilder, timeoutMs: Long = WAIT_MS, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val text = synchronized(sink) { sink.toString() }
            if (predicate(text)) return text
            delay(25)
        }
        return synchronized(sink) { sink.toString() }
    }

    private suspend fun awaitTrue(timeoutMs: Long = WAIT_MS, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }

    @Test
    fun `the socket the session runs on has nagle off and keepalive on`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                // The kernel's answer, not the client's intention. TCP_NODELAY is the difference
                // between a keystroke leaving immediately and waiting for the delayed ACK of the
                // previous one - about 40ms per character on a normal link, which is exactly the
                // "typing feels laggy" report this fixes.
                val io = session.ioSession
                assertThat(io).isInstanceOf(Nio2Session::class.java)
                val socket = (io as Nio2Session).socket
                assertThat(socket.getOption(StandardSocketOptions.TCP_NODELAY)).isTrue()
                assertThat(socket.getOption(StandardSocketOptions.SO_KEEPALIVE)).isTrue()

                // And the properties the session resolves, so a future MINA upgrade that stops
                // honouring them fails here rather than silently restoring Nagle.
                assertThat(CoreModuleProperties.TCP_NODELAY.getRequired(session)).isTrue()
                assertThat(CoreModuleProperties.SOCKET_KEEPALIVE.getRequired(session)).isTrue()
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the heartbeat is one the peer must answer and silence has room to be noticed`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile(keepAlive = 20))
            session.use {
                // HEARTBEAT_INTERVAL is the knob ClientConnectionService reads to schedule anything at
                // all; the SESSION_HEARTBEAT_* pair behind setSessionHeartbeat() drives only the
                // generic SSH_MSG_IGNORE heartbeat, which is unanswerable and therefore cannot detect
                // a dead peer. Configuring the wrong one of those armed no heartbeat whatsoever, and
                // every property still read back exactly as intended - which is why this asserts the
                // one MINA acts on.
                assertThat(CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(session).seconds)
                    .isEqualTo(20L)
                assertThat(CoreModuleProperties.HEARTBEAT_REQUEST.getRequired(session))
                    .isEqualTo("keepalive@openssh.com")
                assertThat(CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(session)).isGreaterThan(0)

                // The idle timeout has to outlast the heartbeats, or MINA's own 10-minute default
                // closes a perfectly healthy session before the dead-peer counter can ever reach its
                // limit - which is what a 600-second keep-alive used to do.
                val noReplyMax = CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(session)
                val idle = CoreModuleProperties.IDLE_TIMEOUT.getRequired(session)
                assertThat(idle.seconds).isGreaterThan(20L * noReplyMax)

                // HEARTBEAT_REPLY_WAIT is deliberately not asserted here, and must not be added back.
                // `ClientConnectionService.configureMaxNoReply()` returns HEARTBEAT_NO_REPLY_MAX
                // outright whenever it is set explicitly, which it is above, so the reply-wait is dead
                // configuration on this session. The assertion that used to be here read it back with
                // `getRequired` and compared it against 15 seconds - which MINA's own default of five
                // minutes satisfies whether or not the app ever sets it, so it passed while proving
                // nothing. What the app actually does on the wire is asserted by the next test, at the
                // server.
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the client really sends answerable keepalives at the configured interval`() = runBlocking {
        val manager = newManager()
        try {
            val before = keepalives.count()
            val session = trustedConnect(manager, hostProfile(keepAlive = HEARTBEAT_SECONDS))
            session.use {
                // Measured at the server, which is the only place that can prove a keep-alive was
                // sent, was addressed to a request name the peer can answer, and was answered. Two of
                // them, so this cannot pass on a single request sent at connect time.
                val deadline = HEARTBEAT_SECONDS * 1_000L * 3 + WAIT_MS
                assertThat(awaitTrue(deadline) { keepalives.count() - before >= 2 }).isTrue()
                // Answered keep-alives are not allowed to end the session, which is the other half of
                // the mechanism: the unanswered counter must be resetting.
                assertThat(session.isOpen).isTrue()
                assertThat(session.isAuthenticated).isTrue()
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a keystroke reaches the shell on its own without waiting for a newline`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val started = shells.size
                val terminal = manager.openTerminal(session)
                val shell = awaitShell(started)
                coroutineScope {
                    val (sink, pump) = collectText(terminal)
                    try {
                        // One character, no Enter. This is what an interactive shell needs and what a
                        // line-buffered client cannot do: readline redraws on every keystroke, and
                        // vim, top and htop never see a newline at all.
                        terminal.write("a")
                        val echoed = awaitText(sink) { it.contains("a") }
                        assertThat(echoed).contains("a")

                        // Control bytes must survive the channel verbatim - Ctrl-C is a single 0x03
                        // that the remote pty turns into SIGINT, and anything that re-encoded it
                        // would break interrupting a runaway command.
                        terminal.writeBytes(byteArrayOf(CTRL_C))
                        assertThat(awaitTrue { shell.received().contains(CTRL_C) }).isTrue()

                        // Nothing was dropped on the way back, so the display cannot be out of step
                        // with the shell.
                        assertThat(terminal.droppedChunks).isEqualTo(0L)
                    } finally {
                        pump.cancel()
                        terminal.close()
                    }
                }
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a pasted command longer than the buffer arrives whole and in order`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val started = shells.size
                val terminal = manager.openTerminal(session)
                val shell = awaitShell(started)
                try {
                    // Longer than the channel's own read buffer, so a paste that is chunked anywhere
                    // in the path shows up as reordered or truncated bytes rather than as this
                    // assertion passing.
                    val line = (1..600).joinToString(" ") { "arg$it" }
                    terminal.write(line + "\r")
                    assertThat(awaitTrue { shell.received().size >= line.length }).isTrue()
                    val delivered = String(shell.received(), Charsets.UTF_8)
                    assertThat(delivered).contains(line)
                } finally {
                    terminal.close()
                }
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the pty is a colour terminal and a resize reaches the shell as a window change`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val started = shells.size
                val terminal = manager.openTerminal(session)
                val shell = awaitShell(started)
                try {
                    // What the remote side reads to decide whether it may use colour and cursor
                    // addressing at all. Without a pty request there is no TERM, and vim, nano, top
                    // and htop all refuse to draw.
                    assertThat(awaitTrue { shell.env()?.get(Environment.ENV_TERM) != null }).isTrue()
                    assertThat(shell.env()?.get(Environment.ENV_TERM)).isEqualTo("xterm-256color")

                    terminal.resize(100, 30)
                    assertThat(awaitTrue { shell.env()?.get(Environment.ENV_COLUMNS) == "100" }).isTrue()
                    assertThat(shell.env()?.get(Environment.ENV_LINES)).isEqualTo("30")
                    // A full-screen program only redraws when it is told; the size alone is not enough.
                    assertThat(shell.winchCount()).isAtLeast(1)

                    // Absurd sizes are clamped to what the display can render rather than sent on,
                    // because a 1-column pty makes any remote program unusable.
                    terminal.resize(1, 1)
                    assertThat(awaitTrue { shell.env()?.get(Environment.ENV_COLUMNS) == TERMINAL_COLUMN_RANGE.first.toString() }).isTrue()
                    assertThat(shell.env()?.get(Environment.ENV_LINES)).isEqualTo(TERMINAL_ROW_RANGE.first.toString())
                } finally {
                    terminal.close()
                }
            }
        } finally {
            manager.close()
        }
    }

    /**
     * A pty asked for at a size no display can render is opened at one that can, and both sides agree.
     *
     * The size handed to `open` comes from measuring the window, so it is only as sane as the
     * measurement: a very small font in a very wide window, or a viewport measured before the layout
     * settled, produces a number far outside what the buffer will hold. What made this worth a test is
     * the failure it caused rather than the number itself - the clamped value was stored in the channel
     * while the *raw* value was sent to the pty, so the remote came up wider than the buffer and every
     * later `resize` to the clamp was discarded as "already that size". The two sides could not
     * converge again for the life of the session, and every full-screen program wrapped.
     */
    @Test
    fun `a pty asked for at an impossible size is opened at one the display can match`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val started = shells.size
                val terminal = manager.openTerminal(session, columns = 4_000, rows = 2)
                val shell = awaitShell(started)
                try {
                    val columns = TERMINAL_COLUMN_RANGE.last
                    val rows = TERMINAL_ROW_RANGE.first
                    assertThat(awaitTrue { shell.env()?.get(Environment.ENV_COLUMNS) == "$columns" }).isTrue()
                    assertThat(shell.env()?.get(Environment.ENV_LINES)).isEqualTo("$rows")
                    // What the app believes, which is the geometry a reconnect carries across.
                    assertThat(terminal.ptyColumns).isEqualTo(columns)
                    assertThat(terminal.ptyRows).isEqualTo(rows)

                    // And a size inside the range still reaches the pty afterwards.
                    terminal.resize(90, 25)
                    assertThat(awaitTrue { shell.env()?.get(Environment.ENV_COLUMNS) == "90" }).isTrue()
                    assertThat(shell.env()?.get(Environment.ENV_LINES)).isEqualTo("25")
                } finally {
                    terminal.close()
                }
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a shell the user ends reports a status so nothing reconnects it`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val started = shells.size
                val terminal = manager.openTerminal(session)
                awaitShell(started)
                // Ctrl-D at a prompt is how a shell is closed on purpose. It must come back with a
                // status, because the status is what stops the reconnect logic resurrecting it.
                terminal.writeBytes(byteArrayOf(CTRL_D))
                val end = withTimeoutOrNull(WAIT_MS) { terminal.awaitClosed() }
                assertThat(end).isEqualTo(SessionEnd.ShellEnded(status = 0, signal = null))
                assertThat(shouldAutoReconnect(end!!, tabIsOpen = true, endedDeliberately = false)).isFalse()
                // And it reads as an ending rather than as a fault of the network.
                assertThat(describeSessionEnd(end)).isEqualTo("Session ended")
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a transport that silently stops delivering is noticed closed and offered for reconnect`() = runBlocking {
        val relay = FreezableRelay(serverPort)
        val manager = newManager()
        val store = SshSessionStore()
        try {
            val profile = hostProfile(port = relay.port, keepAlive = HEARTBEAT_SECONDS)
            assertThat(HEARTBEAT_SECONDS).isAtLeast(KEEP_ALIVE_RANGE.first)
            val session = trustedConnect(manager, profile)
            val started = shells.size
            val terminal = manager.openTerminal(session)
            awaitShell(started)
            store.sessions[profile.id] = session
            store.channels[profile.id] = terminal
            assertThat(store.isLive(profile.id)).isTrue()

            // The phone walks out of Wi-Fi range: the socket is still open at both ends, and not one
            // byte crosses it again. Nothing is reported - which is why this needs a heartbeat that
            // is answered rather than a read that fails.
            val frozenAt = System.nanoTime()
            relay.freeze()

            // Wrapped in a list so a timeout (null) cannot be mistaken for a channel that closed
            // without an exit status (listOf(null)) - the distinction this whole test turns on, and
            // one an earlier version of it got wrong: the assertion passed while nothing had been
            // detected at all.
            val closed = withTimeoutOrNull(DEAD_PEER_WAIT_MS) { listOf(terminal.awaitClosed()) }
            val elapsedSeconds = (System.nanoTime() - frozenAt) / 1_000_000_000L
            assertThat(closed).isNotNull()
            val end = closed!!.single()
            // The transport, named as such. Not "the shell ended with no status", which is what this
            // used to be indistinguishable from and is the one ending that must not be reconnected.
            assertThat(end).isInstanceOf(SessionEnd.TransportFailed::class.java)
            // And the reason reaches the user instead of being replaced by a generic sentence.
            assertThat(describeSessionEnd(end)).startsWith("Connection lost: ")
            assertThat(awaitTrue { !session.isOpen }).isTrue()

            // And it was the heartbeat that noticed, not the idle-timeout backstop: detection is
            // expected after HEARTBEAT_NO_REPLY_MAX unanswered requests, which is far sooner than the
            // idle timeout configured alongside it. Without this bound the test passes even when no
            // keep-alive is ever sent.
            val idleTimeout = CoreModuleProperties.IDLE_TIMEOUT.getRequired(session).seconds
            assertThat(elapsedSeconds).isLessThan(idleTimeout)
            // And the two consequences: the shared store stops offering it, so the service dials a
            // fresh one instead of adopting a corpse, and the tab asks for a reconnect.
            assertThat(store.isLive(profile.id)).isFalse()
            assertThat(store.liveHostIds()).isEmpty()
            assertThat(shouldAutoReconnect(end, tabIsOpen = true, endedDeliberately = terminal.endedDeliberately)).isTrue()
            // Nothing on this side asked for it, so the flag that suppresses a reconnect is clear -
            // the transport really did die, and this is the case that must still come back.
            assertThat(terminal.endedDeliberately).isFalse()
        } finally {
            runCatching { store.closeAll() }
            manager.close()
            relay.close()
        }
    }

    @Test
    fun `the store offers a live session for adoption instead of a second login`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        try {
            val profile = hostProfile()
            val session = trustedConnect(manager, profile)
            val started = shells.size
            val terminal = manager.openTerminal(session)
            awaitShell(started)
            store.sessions[profile.id] = session
            store.channels[profile.id] = terminal
            store.buffers[profile.id] = AnsiTerminalBuffer()

            // What a rebuilt activity asks: is there a session and a channel I can attach a tab to?
            assertThat(store.liveSession(profile.id)).isSameInstanceAs(session)
            assertThat(store.adoptableHostIds()).containsExactly(profile.id)

            // A session whose channel has gone is still live - it can open another - but it is not
            // adoptable, because there is no stream left to attach to.
            terminal.close()
            assertThat(awaitTrue { !terminal.isOpen }).isTrue()
            assertThat(store.isLive(profile.id)).isTrue()
            assertThat(store.adoptableHostIds()).isEmpty()

            store.close(profile.id)
            assertThat(store.isLive(profile.id)).isFalse()
            assertThat(awaitTrue { !session.isOpen }).isTrue()
            // close() keeps the scrollback so a reconnect resumes the same tab; forget() is what
            // closing the tab does.
            assertThat(store.buffers).containsKey(profile.id)
            store.forget(profile.id)
            assertThat(store.buffers).doesNotContainKey(profile.id)
        } finally {
            store.closeAll()
            manager.close()
        }
    }

    /**
     * Two components dialling the same host at once cost one login and leave one session.
     *
     * This is the regression test for the app's most visible connection bug: a tab that said
     * *Reconnecting…* a few seconds after a successful login. Tapping Connect authenticates in the
     * view model and starts the foreground service, whose restore pass asks the registry which hosts
     * are active, finds one the UI has not finished installing, and dials a second session to the
     * same account. Both completed, the later one's `put` closed the earlier one's *live* session,
     * and the surviving shell saw its transport close and reported a drop - which started the
     * reconnect ladder, which dialled again.
     *
     * Both halves are asserted because either alone would still pass with the bug present: one login
     * (the gate serialised the attempts) *and* one session that is still the one with the open shell
     * (the loser adopted rather than replacing it).
     */
    @Test
    fun `two components dialling one host at the same time cost one login and one session`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        val profile = hostProfile()
        // First contact is what the app's own connect path does: trust the key once, then dial.
        trustedConnect(manager, profile).close(false)
        try {
            val loginsBefore = logins.get()
            val shellsBefore = shells.size

            // Exactly the app's dial path, as MainViewModel and EclipseSessionService both run it.
            suspend fun dialOrAdopt(): ClientSession = store.dialing(profile.id) {
                store.adoptable(profile.id)?.first ?: run {
                    val dialled = manager.connect(profile, PASSWORD, null)
                    val installed = store.install(profile.id, dialled)
                    if (installed === dialled) store.channels[profile.id] = manager.openTerminal(dialled)
                    installed
                }
            }

            val (fromUi, fromService) = coroutineScope {
                val ui = async(Dispatchers.IO) { dialOrAdopt() }
                val service = async(Dispatchers.IO) { dialOrAdopt() }
                ui.await() to service.await()
            }

            assertThat(fromUi).isSameInstanceAs(fromService)
            assertThat(logins.get() - loginsBefore).isEqualTo(1)
            // Waited for rather than sampled. `openTerminal` returns when the channel is open, and the
            // server runs its ShellFactory a moment later when the `shell` request arrives - so reading
            // the count immediately is a race that reports zero shells on a loaded machine. The exact
            // count is still asserted, and it is still meaningful: one login was already proved above,
            // and one session cannot produce two shells here.
            assertThat(awaitTrue { shells.size - shellsBefore >= 1 }).isTrue()
            assertThat(shells.size - shellsBefore).isEqualTo(1)
            // And what the second caller was handed is the working session, not a corpse.
            assertThat(fromService.isOpen).isTrue()
            assertThat(fromService.isAuthenticated).isTrue()
            assertThat(store.adoptableHostIds()).containsExactly(profile.id)
            val channel = store.channels.getValue(profile.id)
            assertThat(channel.isOpen).isTrue()
            // The shell still answers, which is the user-visible claim: nothing was reconnected.
            val shell = shells.last()
            channel.write("echo alive\r")
            assertThat(awaitTrue { shell.received().isNotEmpty() }).isTrue()
        } finally {
            store.closeAll()
            manager.close()
        }
    }

    /**
     * A redundant session loses to the live one, and takes nothing of the live one's with it.
     *
     * `sessions.put` closed whatever it displaced, so a late-arriving duplicate dial closed the
     * session the user was typing into and the tab went to *Reconnecting…*. The channel matters as
     * much as the session: dropping it from the registry would leave the host un-adoptable and the
     * next UI would dial all over again.
     */
    @Test
    fun `installing a second session keeps the one that already has a shell`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        val profile = hostProfile()
        try {
            val live = trustedConnect(manager, profile)
            val started = shells.size
            val terminal = manager.openTerminal(live)
            val shell = awaitShell(started)
            assertThat(store.install(profile.id, live)).isSameInstanceAs(live)
            store.channels[profile.id] = terminal

            val redundant = trustedConnect(manager, profile)
            assertThat(store.install(profile.id, redundant)).isSameInstanceAs(live)

            // The newcomer is the one that closes.
            assertThat(awaitTrue { !redundant.isOpen }).isTrue()
            // The incumbent, its shell and its registry entry are all untouched.
            assertThat(live.isOpen).isTrue()
            assertThat(live.isAuthenticated).isTrue()
            assertThat(terminal.isOpen).isTrue()
            assertThat(terminal.endedDeliberately).isFalse()
            assertThat(store.channels[profile.id]).isSameInstanceAs(terminal)
            terminal.write("still here\r")
            assertThat(awaitTrue { shell.received().isNotEmpty() }).isTrue()

            // Installing the same instance twice is what a restore pass does; it must be a no-op and
            // must not close the session it was asked to keep.
            assertThat(store.install(profile.id, live)).isSameInstanceAs(live)
            assertThat(live.isOpen).isTrue()
            assertThat(terminal.isOpen).isTrue()
        } finally {
            store.closeAll()
            manager.close()
        }
    }

    /**
     * A session nobody types into stays Connected across several heartbeat periods.
     *
     * The complaint this answers is "*Reconnecting…* appears seconds after login on an idle tab".
     * Silence is the normal state of a shell at a prompt, so no part of the app is allowed to read it
     * as a fault: the transport stays open, the shell stays open, no exit status is produced, and the
     * reconnect predicate says no. [`the client really sends answerable keepalives`] proves the
     * heartbeat exists; this proves the heartbeat is the *only* thing that happens while idle.
     */
    @Test
    fun `an idle session is not a broken one`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        val profile = hostProfile(keepAlive = HEARTBEAT_SECONDS)
        try {
            val keepalivesBefore = keepalives.count()
            val session = trustedConnect(manager, profile)
            val started = shells.size
            val terminal = manager.openTerminal(session)
            awaitShell(started)
            store.install(profile.id, session)
            store.channels[profile.id] = terminal
            val (sink, collector) = collectText(terminal)
            try {
                // Not one byte typed for several heartbeat periods - the app's shortest keep-alive, so
                // this covers the same ground as a ten-minute idle at the default of 60s.
                val idleFor = HEARTBEAT_SECONDS * 1_000L * 4
                assertThat(awaitTrue(idleFor + WAIT_MS) { keepalives.count() - keepalivesBefore >= 3 }).isTrue()

                assertThat(session.isOpen).isTrue()
                assertThat(session.isAuthenticated).isTrue()
                assertThat(terminal.isOpen).isTrue()
                assertThat(store.isLive(profile.id)).isTrue()
                assertThat(store.adoptableHostIds()).containsExactly(profile.id)
                // Nothing ended, so there is nothing to report: awaitClosed is still waiting.
                assertThat(withTimeoutOrNull(200) { terminal.awaitClosed() }).isNull()
                assertThat(terminal.droppedChunks).isEqualTo(0L)
                // And the shell is still there to prove it, after all that silence.
                synchronized(sink) { sink.setLength(0) }
                terminal.write("awake\r")
                assertThat(awaitText(sink) { it.contains("awake") }).contains("awake")
            } finally {
                collector.cancel()
            }
        } finally {
            store.closeAll()
            manager.close()
        }
    }

    @Test
    fun `two hosts stay up at once and closing one leaves the other alone`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        try {
            val first = hostProfile()
            val second = hostProfile()
            store.sessions[first.id] = trustedConnect(manager, first)
            store.sessions[second.id] = trustedConnect(manager, second)

            assertThat(store.liveHostIds()).containsExactly(first.id, second.id)

            store.close(first.id)
            assertThat(store.isLive(first.id)).isFalse()
            assertThat(store.isLive(second.id)).isTrue()
        } finally {
            store.closeAll()
            manager.close()
        }
    }

    @Test
    fun `moving between screens reuses one session however often the channel is reopened`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                // Leaving the terminal screen and coming back closes and reopens the channel. It must
                // never cost another authentication: the session object is the connection.
                repeat(5) {
                    val started = shells.size
                    val terminal = manager.openTerminal(session)
                    val shell = awaitShell(started)
                    terminal.write("hello\r")
                    assertThat(awaitTrue { shell.received().isNotEmpty() }).isTrue()
                    assertThat(terminal.droppedChunks).isEqualTo(0L)
                    terminal.close()
                    assertThat(session.isOpen).isTrue()
                    assertThat(session.isAuthenticated).isTrue()
                }
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `repeated connect and disconnect leaves nothing behind`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        try {
            repeat(4) {
                val profile = hostProfile()
                val session = trustedConnect(manager, profile)
                store.sessions[profile.id] = session
                assertThat(store.isLive(profile.id)).isTrue()
                store.close(profile.id)
                assertThat(store.isLive(profile.id)).isFalse()
            }
            // No half-closed sessions accumulate across cycles, which is the leak a reconnect loop
            // would turn into an outage of its own.
            assertThat(store.liveHostIds()).isEmpty()
            assertThat(store.sessions).isEmpty()
            assertThat(store.channels).isEmpty()
        } finally {
            store.closeAll()
            manager.close()
        }
    }

    /**
     * A live session answers the probe, and a server that has never heard of the request still counts.
     *
     * [SshConnectionManager.probeLiveness] is what turns a network migration into a two-second
     * discovery instead of a ninety-second one, and its correctness rests entirely on an asymmetry that
     * is easy to get backwards: *any* reply proves the peer is there, including the
     * `SSH_MSG_REQUEST_FAILURE` that every real OpenSSH server sends for `keepalive@openssh.com`,
     * because no server implements that global request. MINA surfaces that failure as a `null` return
     * rather than as an exception, so a probe written to require a success reply would report every
     * healthy session dead - and the sweep would then close all of them. That is a worse bug than the
     * one being fixed, which is why the counted keep-alive is asserted too: it proves the probe left
     * the client and was refused, rather than passing on a probe that was never sent.
     */
    @Test
    fun `a live session answers the liveness probe even when the server refuses the request`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val before = keepalives.count()

                assertThat(manager.probeLiveness(session)).isTrue()

                // It really went on the wire, under the name OpenSSH uses.
                assertThat(keepalives.count()).isGreaterThan(before)
                // And it cost the session nothing: a probe that disturbed the shell would be worse
                // than the delay it removes.
                assertThat(session.isOpen).isTrue()
                assertThat(session.isAuthenticated).isTrue()
            }
        } finally {
            manager.close()
        }
    }

    /**
     * The probe finds a black-holed transport that `isOpen` cannot, and finds it in seconds.
     *
     * This is the Wi-Fi-to-mobile-data handover, reproduced honestly: the sockets stay established at
     * both ends and not one byte crosses again. Everything the app can cheaply ask is still true -
     * `isOpen`, `isAuthenticated`, the channel is open - which is precisely why the keep-alive's three
     * unanswered requests used to be the only detector, and why the terminal spent that time
     * swallowing keystrokes.
     *
     * The bound is the point of the test. A probe that eventually returns false is not worth having if
     * it takes as long as the heartbeat it exists to pre-empt, so detection is required to land well
     * inside [HEARTBEAT_SECONDS] x (no-reply-max + 1). Paired with the test above - which proves a live
     * session answers - this cannot be satisfied by a probe that simply always fails.
     */
    @Test
    fun `a session behind a black holed transport fails the probe long before the heartbeat would`() = runBlocking {
        val relay = FreezableRelay(serverPort)
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile(port = relay.port, keepAlive = HEARTBEAT_SECONDS))
            session.use {
                relay.freeze()
                // Nothing here is false, which is the whole problem.
                assertThat(session.isOpen).isTrue()
                assertThat(session.isAuthenticated).isTrue()

                val startedAt = System.nanoTime()
                assertThat(manager.probeLiveness(session, timeoutSeconds = PROBE_TIMEOUT_SECONDS)).isFalse()
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L

                // Sooner than the heartbeat would have concluded anything - the reason the probe exists.
                assertThat(elapsedSeconds).isLessThan(HEARTBEAT_SECONDS.toLong() * 4)
            }
        } finally {
            manager.close()
            relay.close()
        }
    }

    /**
     * A session that has already been closed answers "dead" instead of throwing.
     *
     * [SessionLivenessProbe.sweep] probes every live session concurrently, and a user can disconnect
     * one while the sweep is in flight. An exception escaping a single probe there would abort the
     * whole `awaitAll` and leave the other sessions - the ones that really did die in the handover -
     * unexamined until the heartbeat noticed, which is the delay this machinery removes.
     */
    @Test
    fun `a closed session fails the probe without throwing`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.close(true)
            assertThat(awaitTrue { !session.isOpen }).isTrue()

            assertThat(manager.probeLiveness(session, timeoutSeconds = PROBE_TIMEOUT_SECONDS)).isFalse()
        } finally {
            manager.close()
        }
    }

    /**
     * The non-blocking dial gate: busy is reported, not waited for, and it is per host.
     *
     * [SshSessionStore.dialing] is deliberately held for a whole connect ladder -
     * [dev.eclipse.ssh.presentation.MAX_CONNECT_ATTEMPTS] attempts, each with a timeout the user may
     * set as high as five minutes - because that is what makes two components dialling one host cost
     * one login. But [dev.eclipse.ssh.background.EclipseSessionService]'s restore pass walks its hosts
     * *sequentially*, so blocking on that gate made one slow host stall every host queued behind it for
     * up to a quarter of an hour: a head-of-line block that looked exactly like the app refusing to
     * reconnect anything.
     *
     * Both halves matter. Reporting busy is what lets the pass move on; keeping the gates per host is
     * what stops the report from being "busy" for hosts nobody is dialling at all.
     */
    @Test
    fun `the dial gate reports busy rather than queueing behind another component`() = runBlocking {
        val store = SshSessionStore()
        val slowHost = "host-being-dialled"
        val otherHost = "host-nobody-is-dialling"
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withTimeout(WAIT_MS) {
            val holder = launch(Dispatchers.IO) {
                store.dialing(slowHost) {
                    inside.complete(Unit)
                    release.await()
                }
            }
            inside.await()

            var ran = false
            val outcome = store.tryDialing(slowHost) { ran = true }

            assertThat(outcome).isEqualTo(DialAttempt.Busy)
            // Not merely reported busy: the attempt itself must not run, or the login the gate exists
            // to deduplicate happens anyway.
            assertThat(ran).isFalse()

            // A different host is dialled immediately, which is the head-of-line block being absent.
            assertThat(store.tryDialing(otherHost) { "dialled" }).isEqualTo(DialAttempt.Ran("dialled"))

            release.complete(Unit)
            holder.join()

            // And the gate is free again the moment the holder is done.
            assertThat(store.tryDialing(slowHost) { "free" }).isEqualTo(DialAttempt.Ran("free"))
        }
    }

    /**
     * A dial that fails, throws or is cancelled leaves the gate open.
     *
     * A gate held by a dead attempt is not an error the user can see and retry past - it is a host that
     * can never be dialled again for the life of the process, because every later attempt either waits
     * forever or is told somebody else is already on it. Every exit path is covered here, including
     * cancellation, which is the ordinary case: it is what leaving the terminal screen mid-connect
     * produces.
     */
    @Test
    fun `a dial that fails or is cancelled releases the gate instead of wedging the host`() = runBlocking {
        val store = SshSessionStore()
        val hostId = "host-that-fails"

        assertThat(runCatching { store.dialing<Unit>(hostId) { throw IOException("refused") } }.isFailure).isTrue()
        assertThat(store.tryDialing(hostId) { "after failure" }).isEqualTo(DialAttempt.Ran("after failure"))

        assertThat(runCatching { store.tryDialing<Unit>(hostId) { throw IOException("refused") } }.isFailure).isTrue()
        assertThat(store.tryDialing(hostId) { "after try failure" }).isEqualTo(DialAttempt.Ran("after try failure"))

        withTimeout(WAIT_MS) {
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                store.dialing(hostId) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            job.cancelAndJoin()

            assertThat(store.tryDialing(hostId) { "after cancel" }).isEqualTo(DialAttempt.Ran("after cancel"))
        }
    }

    /**
     * A flood of output arrives whole, in order, and does not grow the buffer without bound.
     *
     * The two failure modes this covers are the ones a `cat` of a large file, a verbose build, or
     * `journalctl -f` on a busy server actually produce, and they fail in opposite directions. Dropping
     * chunks corrupts the *emulator*, not just the display: bytes lost in the middle of an escape
     * sequence leave the parser treating the remainder as text, which is why the channel blocks its
     * pump thread rather than dropping - and why [TerminalChannel.droppedChunks] must be zero here.
     * Keeping everything is the other failure: an unbounded scrollback is an out-of-memory kill after a
     * long-running tail, so the emulator trims to its scrollback limit and this asserts the trim.
     *
     * The payload is deliberately larger than the channel's whole [MutableSharedFlow] buffer, so the
     * blocking publish path - the one that only exists for floods - is the path under test. Line
     * numbers are extracted and compared as a sequence rather than as a substring match: that catches
     * reordering and duplication, which a `contains` assertion cannot.
     */
    @Test
    fun `a flood of output arrives whole and in order and the scrollback stays bounded`() = runBlocking {
        val manager = newManager()
        try {
            val session = trustedConnect(manager, hostProfile())
            session.use {
                val started = shells.size
                val terminal = manager.openTerminal(session)
                awaitShell(started)
                val (sink, collector) = collectText(terminal)
                try {
                    val payload = buildString(FLOOD_LINES * 12) {
                        repeat(FLOOD_LINES) { append("line ").append(it).append('\n') }
                    }
                    assertThat(payload.length).isGreaterThan(FLOOD_MIN_BYTES)

                    terminal.write(payload)

                    // Length rather than content while waiting: this sink holds more than a megabyte,
                    // and copying it out every 25ms to test a predicate would cost more than the
                    // transfer being measured.
                    val arrived = awaitTrue(FLOOD_WAIT_MS) {
                        synchronized(sink) { sink.length } >= payload.length
                    }
                    val text = synchronized(sink) { sink.toString() }
                    assertWithMessage("only %s of %s bytes arrived", text.length, payload.length)
                        .that(arrived).isTrue()

                    val numbers = Regex("line (\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
                    assertThat(numbers).hasSize(FLOOD_LINES)
                    // Every line, exactly once, in the order it was written.
                    assertThat(numbers).isEqualTo(List(FLOOD_LINES) { it })
                    assertThat(terminal.droppedChunks).isEqualTo(0L)

                    // And the emulator that renders it holds a bounded amount of it.
                    val buffer = AnsiTerminalBuffer()
                    buffer.feed(text)
                    assertThat(buffer.lineCount()).isAtMost(buffer.viewportRows + MAX_SCROLLBACK_LINES)
                } finally {
                    collector.cancel()
                    terminal.close()
                }
            }
        } finally {
            manager.close()
        }
    }

    /**
     * Three sessions run at once, each seeing only its own output, and closing one leaves the rest.
     *
     * Everything about a session in this app is keyed by host id - the session, the channel, the
     * emulator buffer, the pty size, the scroll offset - and a single one of those keyed by anything
     * else is a cross-talk bug: one host's output painted into another's scrollback, or one tab's
     * resize applied to the wrong pty. Three is the smallest number that catches a mistake that sends
     * everything to the *first* or the *last* host rather than to the right one.
     */
    @Test
    fun `three sessions run at once without mixing their output`() = runBlocking {
        val manager = newManager()
        val store = SshSessionStore()
        try {
            val opened = (1..3).map { index ->
                val profile = hostProfile()
                // Sequential, so each shell can be identified by position rather than by a race.
                val session = trustedConnect(manager, profile)
                val startedShells = shells.size
                val terminal = manager.openTerminal(session)
                awaitShell(startedShells)
                store.install(profile.id, session)
                store.channels[profile.id] = terminal
                store.buffers[profile.id] = AnsiTerminalBuffer()
                Triple(profile, terminal, "host$index")
            }

            assertThat(store.liveHostIds()).containsExactlyElementsIn(opened.map { it.first.id })
            assertThat(store.adoptableHostIds()).containsExactlyElementsIn(opened.map { it.first.id })

            coroutineScope {
                val sinks = opened.map { (_, terminal, _) -> collectText(terminal) }
                try {
                    // Written concurrently, because that is how three open tabs behave.
                    opened.forEachIndexed { index, (_, terminal, marker) ->
                        launch(Dispatchers.IO) {
                            repeat(40) { line -> terminal.write("$marker-$line\r") }
                        }
                        // Kept distinct so a mix-up cannot be hidden by identical payloads.
                        assertThat(marker).isEqualTo("host${index + 1}")
                    }

                    opened.forEachIndexed { index, (profile, _, marker) ->
                        val (sink, _) = sinks[index]
                        val ownText = awaitText(sink, FLOOD_WAIT_MS) { it.contains("$marker-39") }
                        assertWithMessage("host %s never saw its own last line", marker)
                            .that(ownText).contains("$marker-39")
                        // And nothing that belongs to a sibling.
                        opened.map { it.third }.filter { it != marker }.forEach { other ->
                            assertWithMessage("%s's output appeared in %s's terminal", other, marker)
                                .that(ownText).doesNotContain(other)
                        }
                        store.buffers.getValue(profile.id).feed(ownText)
                    }
                } finally {
                    sinks.forEach { (_, job) -> job.cancel() }
                }
            }

            // One tab closed leaves the other two exactly as they were, which is the property a shared
            // store makes easy to break: a close that reached the wrong entry, or all of them.
            val (closed, _, _) = opened.first()
            store.close(closed.id)
            assertThat(store.isLive(closed.id)).isFalse()
            opened.drop(1).forEach { (profile, terminal, _) ->
                assertThat(store.isLive(profile.id)).isTrue()
                assertThat(terminal.isOpen).isTrue()
                assertThat(terminal.endedDeliberately).isFalse()
            }
        } finally {
            store.closeAll()
            manager.close()
        }
    }
}

/**
 * A shell that records what it was given and echoes every byte the moment it arrives.
 *
 * Byte-at-a-time on purpose: a line-buffered stand-in would pass even if the client only sent input
 * on Enter, which is the bug being guarded against.
 */
private class RecordingShell : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var exit: ExitCallback? = null
    private val receivedBytes = ByteArrayOutputStream()
    private val winch = AtomicInteger()

    @Volatile private var environment: Environment? = null

    fun received(): ByteArray = synchronized(receivedBytes) { receivedBytes.toByteArray() }

    fun env(): Map<String, String>? =
        environment?.env?.let { runCatching { HashMap(it) }.getOrNull() }

    fun winchCount(): Int = winch.get()

    override fun setInputStream(input: InputStream) {
        this.input = input
    }

    override fun setOutputStream(output: OutputStream) {
        this.output = output
    }

    override fun setErrorStream(error: OutputStream) = Unit

    override fun setExitCallback(callback: ExitCallback) {
        this.exit = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        environment = env
        env.addSignalListener(SignalListener { _, _ -> winch.incrementAndGet() }, Signal.WINCH)
        val inn = input ?: return
        val out = output ?: return
        Thread {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val read = inn.read(buffer)
                    if (read < 0) break
                    synchronized(receivedBytes) { receivedBytes.write(buffer, 0, read) }
                    val chunk = buffer.copyOfRange(0, read)
                    if (chunk.contains(0x04.toByte())) {
                        out.write("logout\n".toByteArray())
                        out.flush()
                        exit?.onExit(0)
                        return@Thread
                    }
                    out.write(chunk)
                    out.flush()
                }
            } catch (_: Exception) {
                // The channel or the transport went away; nothing to report from here.
            }
            runCatching { exit?.onExit(0) }
        }.apply {
            isDaemon = true
            name = "recording-shell"
        }.start()
    }

    override fun destroy(channel: ChannelSession) = Unit
}

/**
 * A loopback TCP relay that can stop forwarding without closing anything.
 *
 * This is the only faithful way to test what actually happens to a phone's SSH session when the
 * network goes away: the sockets stay established, the peer stops answering, and neither side is
 * told. Closing a socket instead would test a completely different path - a clean FIN, which the app
 * has always handled - and would prove nothing about dead-peer detection.
 */
private class FreezableRelay(private val targetPort: Int) : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val closeables = Collections.synchronizedList(mutableListOf<Closeable>())

    @Volatile private var frozen = false
    @Volatile private var running = true

    val port: Int get() = listener.localPort

    init {
        closeables.add(listener)
        thread("relay-accept") {
            while (running) {
                val downstream = runCatching { listener.accept() }.getOrNull() ?: break
                val upstream = runCatching { Socket("127.0.0.1", targetPort) }.getOrNull()
                if (upstream == null) {
                    runCatching { downstream.close() }
                    continue
                }
                downstream.tcpNoDelay = true
                upstream.tcpNoDelay = true
                closeables.add(downstream)
                closeables.add(upstream)
                pump("relay-up", downstream, upstream)
                pump("relay-down", upstream, downstream)
            }
        }
    }

    /** Black-holes the connection in both directions, leaving every socket open. */
    fun freeze() {
        frozen = true
    }

    private fun pump(name: String, from: Socket, to: Socket) = thread(name) {
        val buffer = ByteArray(8192)
        val input = runCatching { from.getInputStream() }.getOrNull() ?: return@thread
        val output = runCatching { to.getOutputStream() }.getOrNull() ?: return@thread
        try {
            while (running) {
                // Checked on both sides of the read so bytes already in flight when the freeze starts
                // are held rather than delivered.
                while (frozen && running) Thread.sleep(50)
                val read = input.read(buffer)
                if (read < 0) break
                while (frozen && running) Thread.sleep(50)
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // Either end closed, or the relay is shutting down.
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body).apply {
            isDaemon = true
            this.name = name
        }.start()
    }

    override fun close() {
        running = false
        frozen = false
        synchronized(closeables) { closeables.toList() }.forEach { runCatching { it.close() } }
    }
}

/**
 * Answers global requests the way any SSH server does, and counts the keep-alives.
 *
 * `Result.Unsupported` makes MINA reply `SSH_MSG_REQUEST_FAILURE`, which RFC 4254 requires for an
 * unknown request that asked for a reply. A failure reply is still a reply: it proves the peer is
 * alive and resets the client's unanswered counter, which is exactly why a client can use this as a
 * liveness probe against a server that has never heard of the request name.
 */
private class CountingKeepaliveHandler : ConnectionServiceRequestHandler {
    private val seen = AtomicInteger()

    fun count(): Int = seen.get()

    override fun process(service: ConnectionService, request: String, wantReply: Boolean, buffer: Buffer): RequestHandler.Result {
        if (request == "keepalive@openssh.com") seen.incrementAndGet()
        return RequestHandler.Result.Unsupported
    }
}
