package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import dev.eclipse.ssh.ssh.SshSessionStore
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Two shells on one host: the duplicate-terminal feature, end to end against a real server.
 *
 * Everything in this class is a fact the wire can check, because the app's own idea of "connected"
 * is exactly what these tests exist to doubt. The server counts every shell it starts, and that
 * count is what separates "a second terminal the user can type into" from "a second tab showing
 * the first session's output twice" — the failure a session-key re-key that quietly resolved both
 * dials to the same key would produce.
 *
 * The other half of the feature is what happens when one of the two ends. A host-scoped teardown
 * that ran on the first tab's close — the registry, the SFTP state, everything the survivor still
 * needs — is the bug the two-scope [MainViewModel.closeTab] split exists to prevent, and the
 * closing tests assert the survivor is not just *listed* as connected but still echoing.
 *
 * One working server, password auth only (so `authAttempts` is an exact count of credentials
 * offered — see `ConnectionMatrixRobolectricTest` for why the factory list matters), and a shell
 * that echoes. The counting shell is shared with the connection matrix by copy rather than
 * reference so the two classes stay free to diverge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DuplicateTerminalRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetServerCounters() {
        authAttempts.set(0)
        shellsStarted.set(0)
    }

    /**
     * Ends every session this test opened, before the next one starts. See
     * `ConnectionMatrixRobolectricTest.endEverySession` for why a suite on two cores cannot leave
     * shells resident between tests.
     */
    @After
    fun endEverySession() {
        runCatching { compose.runOnUiThread { viewModel().disconnectAll() } }
    }

    // ---------------------------------------------------------------- the feature

    /**
     * The baseline: duplicate a connected host and get a *second* shell, not a tab pointed at the
     * first one's session.
     *
     * Two logins and two shells is the whole assertion. A duplicate that adopted the existing
     * session (one login, one shell) or that silently dialled a tab nobody can reach (two logins,
     * one shell) both fail here, in the direction the server can see.
     */
    @Test
    fun duplicatingAConnectedHostOpensASecondShell() {
        val host = connectOneShell()

        compose.runOnUiThread { viewModel().duplicateSession(checkNotNull(tabFor(host.id))) }

        val tabs = pumpUntilTabs(host.id, count = 2)
        assertThat(tabs.map(SessionTab::id).toSet()).hasSize(2)
        pumpUntil(describe = { "the duplicate never connected: " + diagnose(host.id) }) {
            tabsFor(host.id).all { it.state == SessionConnectionState.CONNECTED }
        }
        awaitCount(authAttempts, 2, "passwords offered to the server")
        awaitCount(shellsStarted, 2, "shells the server started")
        // Both terminals have a frame of their own, keyed by their own session key: the duplicate
        // is wired to the UI, not merely listed as CONNECTED.
        val frames = viewModel().frames.value
        assertThat(frames.keys).containsAtLeastElementsIn(tabsFor(host.id).map(SessionTab::id))
    }

    /**
     * The two shells are independent: text typed into one appears in that terminal's transcript and
     * not in the other's.
     *
     * This is the test a re-key that resolved both dials to the same key would fail *without* the
     * server counters ever noticing — one login behind, two shells started, both tabs fed from one
     * buffer. The transcripts are the only place that divergence is observable.
     */
    @Test
    fun duplicatedShellsKeepIndependentTranscripts() {
        val host = connectOneShell()
        compose.runOnUiThread { viewModel().duplicateSession(checkNotNull(tabFor(host.id))) }
        val tabs = pumpUntilTabs(host.id, count = 2)
        val first = tabs[0].id
        val second = tabs[1].id

        compose.runOnUiThread {
            viewModel().sendText(first, "first-shell\n")
            viewModel().sendText(second, "second-shell\n")
        }

        val viewModel = viewModel()
        pumpUntil(describe = { "neither shell echoed: " + diagnose(host.id) }) {
            viewModel.uiState.value.terminalOutput[first].orEmpty().contains("echo: first-shell") &&
                viewModel.uiState.value.terminalOutput[second].orEmpty().contains("echo: second-shell")
        }
        // And only its own: cross-talk here would mean both tabs are reading one buffer.
        assertWithMessage("the first shell's transcript carries the second shell's line")
            .that(viewModel.uiState.value.terminalOutput[first].orEmpty())
            .doesNotContain("echo: second-shell")
        assertWithMessage("the second shell's transcript carries the first shell's line")
            .that(viewModel.uiState.value.terminalOutput[second].orEmpty())
            .doesNotContain("echo: first-shell")
    }

    /**
     * Closing one of the two terminals leaves the other working, and closing the survivor is what
     * finally clears the host.
     *
     * The survivor is proven alive the only way that means anything: by typing into it after the
     * first tab is gone and hearing the echo back. And the last close is asserted on the store
     * rather than the tab list, because the registry and session teardown that must run there are
     * invisible to the UI either way.
     */
    @Test
    fun closingOneDuplicatedTerminalLeavesTheOtherAliveAndTheLastCloseClearsTheHost() {
        val host = connectOneShell()
        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.duplicateSession(checkNotNull(tabFor(host.id))) }
        val tabs = pumpUntilTabs(host.id, count = 2)
        val survivor = tabs[1]
        val store = injected(viewModel, "sessionStore", SshSessionStore::class.java)

        compose.runOnUiThread { viewModel.closeTab(tabs[0]) }

        pumpUntil(describe = { "the survivor reacted to its sibling closing: " + diagnose(host.id) }) {
            tabFor(host.id)?.id == survivor.id
        }
        assertThat(tabFor(host.id)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        // Still its own live session, not a tab describing a transport that closed with its sibling.
        assertThat(store.isLive(survivor.id)).isTrue()
        compose.runOnUiThread { viewModel.sendText(survivor.id, "still-here\n") }
        pumpUntil(describe = { "the survivor stopped echoing after its sibling closed: " + diagnose(host.id) }) {
            viewModel.uiState.value.terminalOutput[survivor.id].orEmpty().contains("echo: still-here")
        }

        // The last close is the one that may clear the host - and must.
        compose.runOnUiThread { viewModel.closeTab(survivor) }
        pumpUntil(describe = { "the last tab never closed: " + diagnose(host.id) }) {
            tabsFor(host.id).isEmpty()
        }
        assertThat(store.liveSession(survivor.id)).isNull()
        assertThat(store.liveSession(host.id)).isNull()
    }

    // ---------------------------------------------------------------- driving the app

    /** Connects the shared host with a saved password and waits for the first shell. */
    private fun connectOneShell(): HostProfile {
        val profile = HostProfile(
            id = "dup-" + nextHostId++,
            name = HOST_NAME,
            host = LOOPBACK,
            username = USER,
            port = serverPort,
            authMethod = AuthMethod.PASSWORD,
            // Off: this class is about shells, and an SFTP login would put a second channel in the
            // way of what is being counted.
            autoLoginSftp = false,
            fingerprint = serverFingerprint,
        )
        val viewModel = viewModel()
        compose.runOnUiThread {
            viewModel.saveHost(profile, HostCredentialUpdate(password = SecretEdit.Replace(PASSWORD)))
        }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        compose.runOnUiThread { viewModel.connect(saved, password = null) }
        var trusted = false
        pumpUntil(describe = { "the first session never connected: " + diagnose(profile.id) }) {
            if (!trusted && viewModel.uiState.value.hostKeyChallenge != null) {
                trusted = true
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(profile.id)?.state == SessionConnectionState.CONNECTED
        }
        awaitCount(authAttempts, 1, "passwords offered to the server")
        awaitCount(shellsStarted, 1, "shells the server started")
        return saved
    }

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /** Reads one of the view model's injected singletons. See `ConnectionMatrixRobolectricTest`. */
    private fun <T> injected(viewModel: MainViewModel, name: String, type: Class<T>): T =
        type.cast(
            MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(viewModel),
        )!!

    /** The host's first tab - the single-session identity every duplicate starts from. */
    private fun tabFor(hostId: String): SessionTab? =
        viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    private fun tabsFor(hostId: String): List<SessionTab> =
        viewModel().uiState.value.tabs.filter { it.hostId == hostId }

    /** Waits until the host has [count] tabs, and returns them in strip order. */
    private fun pumpUntilTabs(hostId: String, count: Int): List<SessionTab> {
        pumpUntil(describe = { "the host never had $count tabs: " + diagnose(hostId) }) {
            tabsFor(hostId).size >= count
        }
        return tabsFor(hostId)
    }

    /** Waits for a server-side counter to reach [expected], then holds still to prove it stops there. */
    private fun awaitCount(counter: AtomicInteger, expected: Int, what: String) {
        pumpUntil(describe = { "only ${counter.get()} of $expected $what: " + diagnose("") }) {
            counter.get() >= expected
        }
        settle()
        assertWithMessage(what).that(counter.get()).isEqualTo(expected)
    }

    private fun settle(rounds: Int = SETTLE_ROUNDS) {
        repeat(rounds) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(SETTLE_PAUSE_MS)
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. See `ConnectionMatrixRobolectricTest.pumpUntil` for
     * the full reasoning; the shape is copied so the two suites fail in equally readable ways.
     */
    private fun pumpUntil(timeoutMs: Long = SSH_TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    private fun diagnose(hostId: String): String {
        val viewModel = viewModel()
        return buildString {
            append("tabs=").append(viewModel.uiState.value.tabs)
            append(" status=").append(viewModel.statusMessage.value)
            append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
            append(" authAttempts=").append(authAttempts.get())
            append(" shellsStarted=").append(shellsStarted.get())
            append(" hostId=").append(hostId)
        }
    }

    private companion object {
        const val SSH_TIMEOUT_MS = 90_000L
        const val LOOPBACK = "127.0.0.1"
        const val USER = "testuser"
        const val PASSWORD = "testpass123"
        const val HOST_NAME = "duphost"
        const val SETTLE_ROUNDS = 20
        const val SETTLE_PAUSE_MS = 10L

        var serverPort = 0
        var serverFingerprint = ""
        val authAttempts = AtomicInteger(0)
        val shellsStarted = AtomicInteger(0)

        private var nextHostId = 0
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            StandInAndroidKeyStore.install()
            val root = Files.createTempDirectory("eclipse-duplicate")
            server = SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("dup-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    authAttempts.incrementAndGet()
                    user == USER && password == PASSWORD
                }
                // Password only, so `authAttempts` counts credentials offered. See
                // `ConnectionMatrixRobolectricTest.startEndpoints` for the keyboard-interactive
                // double-count this list shape avoids.
                userAuthFactories = listOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
                subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                shellFactory = ShellFactory { EchoShell() }
                start()
            }
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
            serverFingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(server.keyPairProvider.loadKeys(null).first().public.encoded),
            )
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
            StandInAndroidKeyStore.uninstall()
        }
    }

    /**
     * A shell that echoes, counting itself as it starts.
     *
     * The count is the point: "the tab says CONNECTED" is the app's opinion, and the number of
     * shells the server actually started is the fact. They disagree exactly when a session leaks or
     * a dial is resolved to a session that was already open.
     */
    private class EchoShell : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null
        private var worker: Thread? = null

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { this.exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            shellsStarted.incrementAndGet()
            val source = input ?: return
            val sink = output ?: return
            worker = Thread {
                runCatching {
                    sink.write("eclipse-dup-shell\r\n$ ".toByteArray())
                    sink.flush()
                    val line = StringBuilder()
                    while (true) {
                        val byte = source.read()
                        if (byte < 0) break
                        if (byte == '\n'.code || byte == '\r'.code) {
                            sink.write("echo: $line\r\n$ ".toByteArray())
                            sink.flush()
                            line.setLength(0)
                        } else {
                            line.append(byte.toChar())
                        }
                    }
                }
                exit?.onExit(0)
            }.apply { isDaemon = true; start() }
        }

        override fun destroy(channel: ChannelSession) {
            worker?.interrupt()
            worker = null
        }
    }
}
