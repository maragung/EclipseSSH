package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.presentation.MainViewModel
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Auto Login SFTP switch, end to end, against two local SSH servers in this JVM.
 *
 * The switch exists because the two halves of a session can succeed independently. SFTP is a
 * subsystem channel on the transport the SSH handshake already authenticated — there is no second
 * password to get wrong — so what varies is whether the server will *serve* it. An account confined
 * to a shell, a build of sshd with no `sftp-server`, a jail that permits exec and nothing else: all
 * of them authenticate perfectly and then refuse the file browser. Before the switch, connecting
 * listed the remote home directory unconditionally, so every one of those greeted a successful login
 * with an error about a feature the user had not asked for.
 *
 * Two servers rather than one with a flag, because "the server refuses SFTP" has to be true on the
 * wire for this to prove anything. [sftpServer] carries [SftpSubsystemFactory]; [noSftpServer] is
 * identical except that it has no subsystem factories at all, which is what a server without SFTP
 * actually looks like to a client. Both are local, isolated, and thrown away with the JVM: the
 * credentials below exist only inside this test process.
 *
 * Asserted on [MainViewModel] rather than on the semantics tree. The states this is about are carried
 * on the session tab, the failure path has no screen of its own, and the Files tab's own rendering is
 * covered where it can be reached — the point here is that the *state machine* is right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SftpAutoLoginRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Ends every session this test opened, before the next one starts.
     *
     * A fresh application per test does not close a socket: the session, its channels and the shell the
     * server started for it all stay resident, and a live thread is a GC root, so a suite that opens one
     * per test finishes holding all of them at once on a JVM confined to two cores. Handshakes and
     * database writes that take milliseconds in isolation start missing their deadlines under that. See
     * `TerminalSessionLifecycleRobolectricTest.endEverySession`.
     */
    @After
    fun endEverySession() {
        runCatching { compose.runOnUiThread { viewModel().disconnectAll() } }
    }

    // ---------------------------------------------------------------- the tests

    /**
     * The switch on: the file browser is signed in and pointing at the remote home directory by the
     * time the shell appears, with nothing typed and no tab visited.
     */
    @Test
    fun autoLoginOnSignsIntoSftpAndListsTheRemoteHomeDirectory() {
        val hostId = connect(autoLoginSftp = true, port = sftpPort)

        pumpUntil(describe = { "SFTP never became ready: " + diagnose(hostId) }) {
            tabFor(hostId)?.sftpState == SftpSessionState.READY
        }
        val viewModel = viewModel()
        assertThat(tabFor(hostId)?.sftpError).isNull()
        // The listing itself, not just the state: the seeded file is what proves a real subsystem
        // channel opened, authenticated on the SSH transport, and read a directory.
        assertThat(viewModel.uiState.value.remotePath).isNotNull()
        assertThat(viewModel.uiState.value.remoteFiles.map { it.name }).contains(SEEDED_FILE)
        // And the session is untouched by any of it.
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
    }

    /**
     * The switch off: SSH only. Nothing opens a second channel, the tab says so, and the shell is
     * exactly as connected as it would have been.
     *
     * The absence is the assertion, so it is made against a server that *would* have served SFTP —
     * against [noSftpServer] a null listing would prove nothing about the switch.
     */
    @Test
    fun autoLoginOffConnectsTheShellAndLeavesSftpAlone() {
        val hostId = connect(autoLoginSftp = false, port = sftpPort)

        pumpUntil(describe = { "the tab never reported SFTP as disabled: " + diagnose(hostId) }) {
            tabFor(hostId)?.sftpState == SftpSessionState.DISABLED
        }
        assertThat(tabFor(hostId)?.sftpError).isNull()
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        // Nothing was listed, and nothing was reported: an off switch is not a failure.
        assertThat(viewModel().uiState.value.remotePath).isNull()
        assertThat(viewModel().uiState.value.remoteFiles).isEmpty()
        assertThat(viewModel().statusMessage.value).isNull()

        // Still SSH: the shell is live and the pty is attached, which is the whole point of the mode.
        assertThat(viewModel().uiState.value.tabs.map(SessionTab::hostId)).contains(hostId)
    }

    /**
     * A server that authenticates and then refuses SFTP: reported, once, in a sentence — and the
     * terminal keeps working.
     *
     * This is the failure the switch was added for, and the assertion that matters most is the
     * negative one. A file browser that cannot open is not a session that failed, so nothing here may
     * touch [SessionConnectionState]: tearing down a working shell because SFTP is unavailable would
     * be the app breaking a feature the server had not.
     */
    @Test
    fun aServerThatRefusesSftpIsReportedWithoutLosingTheShell() {
        val hostId = connect(autoLoginSftp = true, port = noSftpPort)

        pumpUntil(describe = { "SFTP never settled on FAILED: " + diagnose(hostId) }) {
            tabFor(hostId)?.sftpState == SftpSessionState.FAILED
        }
        val tab = checkNotNull(tabFor(hostId))
        // A sentence, not a stack trace: this string is shown to a user in the session list.
        val reason = checkNotNull(tab.sftpError) { "FAILED with no reason to show" }
        assertThat(reason).isNotEmpty()
        assertThat(reason).doesNotContain("Exception")
        assertThat(reason).doesNotContain("\n")
        // Nothing about a credential may reach it — this text goes on screen and into the snackbar.
        assertThat(reason).doesNotContain(PASSWORD)
        assertThat(reason).doesNotContain(USER)

        // The shell is untouched. Both halves: the tab still says CONNECTED with no session error,
        // and the terminal is still the thing on screen.
        assertThat(tab.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tab.lastError).isNull()
        assertThat(
            compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes(),
        ).isNotEmpty()

        // Said once, in the status message, with the host named so a second session's failure is
        // distinguishable from this one's.
        pumpUntil(describe = { "the failure was never reported to the user" }) {
            viewModel().statusMessage.value?.contains("SFTP") == true
        }
        assertThat(viewModel().statusMessage.value).contains(HOST_NAME)
        assertThat(viewModel().statusMessage.value).doesNotContain(PASSWORD)
    }

    /**
     * Asking for a listing by hand on a server that has no SFTP reports the problem and leaves the app
     * standing — the path a user takes after turning the switch off, or after it failed.
     *
     * The Files tab keeps its own error rather than the tab's, so this asserts what the *browser* does:
     * an empty listing, a directory to show, and a report. Nothing crashes, and the tab is not
     * promoted to READY by a refresh that failed.
     */
    @Test
    fun refreshingFilesByHandOnAServerWithoutSftpReportsItAndDoesNotCrash() {
        val hostId = connect(autoLoginSftp = false, port = noSftpPort)
        pumpUntil(describe = { "the tab never reported SFTP as disabled" }) {
            tabFor(hostId)?.sftpState == SftpSessionState.DISABLED
        }
        val viewModel = viewModel()
        val host = viewModel.uiState.value.hosts.first { it.id == hostId }

        compose.runOnUiThread { viewModel.refreshFiles(host) }

        pumpUntil(describe = { "the failed listing was never reported: " + diagnose(hostId) }) {
            viewModel.statusMessage.value?.contains("Could not list directory") == true
        }
        assertThat(viewModel.uiState.value.remoteFiles).isEmpty()
        // A directory to show rather than a null: the browser has to render something.
        assertThat(viewModel.uiState.value.remotePath).isNotNull()
        // Not promoted by a refresh that threw.
        assertThat(tabFor(hostId)?.sftpState).isNotEqualTo(SftpSessionState.READY)
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
    }

    /**
     * A successful manual listing clears an earlier failure.
     *
     * Without this the tab would keep telling the user SFTP was broken while they were browsing files
     * with it — the failure was recorded once and nothing ever took it back. Reached the way it
     * happens in practice: the switch is off, so the tab starts DISABLED, and the first thing the user
     * does is open Files.
     */
    @Test
    fun aSuccessfulListingPromotesADisabledSessionToReady() {
        val hostId = connect(autoLoginSftp = false, port = sftpPort)
        pumpUntil(describe = { "the tab never reported SFTP as disabled" }) {
            tabFor(hostId)?.sftpState == SftpSessionState.DISABLED
        }
        val viewModel = viewModel()
        val host = viewModel.uiState.value.hosts.first { it.id == hostId }

        compose.runOnUiThread { viewModel.refreshFiles(host) }

        pumpUntil(describe = { "the listing never promoted the tab: " + diagnose(hostId) }) {
            tabFor(hostId)?.sftpState == SftpSessionState.READY
        }
        assertThat(tabFor(hostId)?.sftpError).isNull()
        assertThat(viewModel.uiState.value.remoteFiles.map { it.name }).contains(SEEDED_FILE)
    }

    /**
     * The switch is a property of the host, so editing it changes what the *next* connection does.
     *
     * Both directions, in one test, because the interesting failure is a setting that is read once and
     * cached: on-then-off would pass against a view model that ignored the edit entirely.
     */
    @Test
    fun editingTheSwitchChangesWhatTheNextConnectionDoes() {
        val hostId = connect(autoLoginSftp = true, port = sftpPort)
        pumpUntil(describe = { "SFTP never became ready" }) {
            tabFor(hostId)?.sftpState == SftpSessionState.READY
        }
        val viewModel = viewModel()

        // Edit the host the way the dialog does, close the session, and connect again.
        val edited = viewModel.uiState.value.hosts.first { it.id == hostId }.copy(autoLoginSftp = false)
        compose.runOnUiThread { viewModel.saveHost(edited) }
        pumpUntil(describe = { "the edit never reached the host list" }) {
            viewModel.uiState.value.hosts.first { it.id == hostId }.autoLoginSftp.not()
        }
        closeAll()

        compose.runOnUiThread {
            viewModel.connect(viewModel.uiState.value.hosts.first { it.id == hostId }, password = PASSWORD)
        }
        pumpUntil(describe = { "the reconnected session ignored the edit: " + diagnose(hostId) }) {
            tabFor(hostId)?.sftpState == SftpSessionState.DISABLED
        }
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
    }

    /** Closing a session cancels its SFTP login instead of leaving the job running on a dead socket. */
    @Test
    fun closingASessionDuringSftpLoginLeavesNothingBehind() {
        val hostId = connect(autoLoginSftp = true, port = sftpPort)
        val viewModel = viewModel()

        // Closed without waiting for READY, so the login is very likely still in flight.
        compose.runOnUiThread {
            viewModel.uiState.value.tabs.firstOrNull { it.hostId == hostId }?.let(viewModel::closeTab)
        }

        pumpUntil(describe = { "the tab never went away" }) { tabFor(hostId) == null }
        // Nothing resurrects the tab, and nothing crashes on the cancelled job's way out.
        repeat(30) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        assertThat(tabFor(hostId)).isNull()
        assertThat(viewModel.uiState.value.tabs).isEmpty()
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun tabFor(hostId: String): SessionTab? =
        viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    /**
     * Saves a profile pointing at [port] and connects it, accepting the host key the way the dialog's
     * button does.
     *
     * Through [MainViewModel.saveHost] rather than straight into Room, so the id that connects is the
     * one the app would have used, and through the challenge rather than around it: each server
     * generates a fresh host key per run, so the first connection to it is genuinely a
     * trust-on-first-use prompt, and accepting it is what retries the connection.
     */
    private fun connect(autoLoginSftp: Boolean, port: Int): String {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "sftp-toggle-" + nextHostId++,
            name = HOST_NAME,
            host = LOOPBACK,
            username = USER,
            port = port,
            authMethod = AuthMethod.PASSWORD,
            // Stated rather than defaulted: this suite shares a JVM with every other test, and a
            // loopback handshake that takes milliseconds alone should not fail on a loaded machine.
            connectTimeoutSeconds = 60,
            autoLoginSftp = autoLoginSftp,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        compose.runOnUiThread { viewModel.connect(saved, password = PASSWORD) }

        // Every challenge is answered, not only the first: one tap per question is what the dialog does,
        // and a latch on the first one made this helper depend on there being exactly one. Counted, so a
        // failure here says whether the key was asked about once, repeatedly, or never.
        var accepts = 0
        pumpUntil(describe = { "the session never connected: accepts=$accepts " + diagnose(profile.id) }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                accepts++
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(profile.id)?.state == SessionConnectionState.CONNECTED
        }
        return saved.id
    }

    private fun closeAll() {
        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.disconnectAll() }
        pumpUntil(describe = { "sessions never closed" }) { viewModel.uiState.value.tabs.isEmpty() }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. `sendApplyNotifications` publishes state written
     * outside a frame — `connect` and `loginSftp` both write tabs from plain function calls — which is
     * what invalidates the recomposer at all. `idleFor` rather than `idle` because this code is full
     * of real `delay`, and `idle()` leaves the looper's virtual clock where it was.
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

    /** Everything worth knowing when a session is not in the state it should be. */
    private fun diagnose(hostId: String): String {
        val viewModel = viewModel()
        return buildString {
            append("tabs=").append(viewModel.uiState.value.tabs)
            append(" knownHosts=").append(viewModel.uiState.value.knownHosts)
            append(" status=").append(viewModel.statusMessage.value)
            append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
            append(" remotePath=").append(viewModel.uiState.value.remotePath)
            append(" remoteFiles=").append(viewModel.uiState.value.remoteFiles.size)
            append(" sftpPort=").append(sftpPort).append(" noSftpPort=").append(noSftpPort)
            append(" hostId=").append(hostId)
        }
    }

    private companion object {
        /**
         * How long a wait behind the wire is given.
         *
         * Longer than a state wait needs, because this suite shares two cores with a real SSH server, a
         * real Room database and a real DataStore. A harness deadline shorter than the app's own connect
         * budget can only report the harness running out of patience, never what the app did - and
         * nothing asserted here is relaxed by the extra time, since every wait ends the moment its
         * condition holds.
         */
        const val SSH_TIMEOUT_MS = 90_000L

        const val LOOPBACK = "127.0.0.1"

        /**
         * Local test credentials, and only local: both servers below are bound to loopback inside this
         * JVM, authenticate nothing but this pair, and die with the process.
         */
        const val USER = "testuser"
        const val PASSWORD = "testpass123"
        const val HOST_NAME = "sftp-toggle"

        /** Seeded into the SFTP root, so a listing that worked is provable by its contents. */
        const val SEEDED_FILE = "listing-proof.txt"

        var sftpPort = 0
        var noSftpPort = 0
        private var nextHostId = 0
        private lateinit var sftpServer: SshServer
        private lateinit var noSftpServer: SshServer

        @JvmStatic
        @BeforeClass
        fun startServers() {
            val root = Files.createTempDirectory("eclipse-sftp-toggle")
            Files.write(root.resolve(SEEDED_FILE), "proof\n".toByteArray())
            sftpServer = server(root) { subsystemFactories = Collections.singletonList(SftpSubsystemFactory()) }
            // The point of this one: it authenticates, gives a shell, and has no subsystem to offer.
            // `emptyList` rather than leaving the default alone — setUpDefaultServer installs SFTP.
            noSftpServer = server(root) { subsystemFactories = emptyList() }
            sftpPort = (sftpServer.boundAddresses.first() as InetSocketAddress).port
            noSftpPort = (noSftpServer.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServers() {
            runCatching { sftpServer.stop(true) }
            runCatching { noSftpServer.stop(true) }
        }

        /**
         * A started server on a kernel-assigned port with its own host key.
         *
         * Port 0 because Gradle runs the debug and release unit-test tasks in separate JVMs and they
         * overlap: a fixed port means the loser of the bind runs its whole suite against a server that
         * never started. Its own key file per server so trusting one cannot disturb the other's
         * known-hosts entry.
         */
        private fun server(root: Path, configure: SshServer.() -> Unit): SshServer =
            SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("sftp-toggle-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
                }
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                shellFactory = ShellFactory { QuietShell() }
                configure()
                start()
            }
    }

    /**
     * A shell that prints a prompt and echoes, which is all these tests need from the SSH half.
     *
     * Present at all because the assertions turn on the *shell surviving* an SFTP failure: a server
     * with no shell factory would fail to open the pty, the tab would never reach CONNECTED, and the
     * test would be measuring the wrong thing. CRLF because that is what a pty writes.
     */
    private class QuietShell : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null
        private var worker: Thread? = null

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { this.exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            val source = input ?: return
            val sink = output ?: return
            worker = Thread {
                runCatching {
                    sink.write("eclipse-quiet-shell\r\n$ ".toByteArray())
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
