package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.presentation.files.ExplorerState
import dev.eclipse.ssh.presentation.files.LOCAL_SESSION_ID
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
 * The Files explorer's session model, with two servers open at once, against two local SSH servers
 * in this JVM.
 *
 * The explorer is one screen over many places files live: this device first, then every saved host.
 * The separation that has to hold is the same one the old single-listing browser lost — each session
 * keeps its own directory, and switching moves the browser rather than shuffling rows — but the
 * stakes are higher now, because the listing is also what Download, Rename, Chmod and Delete resolve
 * their paths against, and what per-session memory restores on return. A listing that belonged to
 * another session was never merely the wrong picture.
 *
 * So the assertions here are about *separation* and *return*: each session's rows come from its own
 * socket (one uniquely-named file per directory per server, so "alpha's listing is on screen" is a
 * claim about bytes that came off a particular wire), switching follows the chip that was tapped,
 * and coming back to a session lands in the folder it was left in rather than in home. Plus the
 * state the spec demands of a disconnected host: its chip stays, browsing it says why it cannot, and
 * the device's own session keeps working — files on this phone do not depend on a server being up.
 *
 * Both servers are bound to loopback inside this JVM, authenticate one pair of credentials that
 * exists nowhere else, and die with the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FilesSessionSwitchRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Ends every session this test opened, before the next one starts.
     *
     * A fresh application per test does not close a socket: the sessions, their channels and the shells
     * the servers started stay resident, and a live thread is a GC root, so a suite that opens two per
     * test finishes holding all of them at once on a JVM confined to two cores.
     *
     * It then *waits* for the tabs to go, which matters more than it looks: `closeTab` finishes on a
     * coroutine, and a Robolectric test's main looper stops being pumped the moment the method returns.
     * Firing the closes and walking away leaves that work suspended forever, which is a leak the next
     * test in the class inherits. Waiting keeps each test's cleanup inside its own test.
     */
    @After
    fun endEverySession() {
        runCatching {
            compose.runOnUiThread { viewModel().disconnectAll() }
            pumpUntil(TEARDOWN_TIMEOUT_MS, { "tabs still open: " + viewModel().uiState.value.tabs }) {
                viewModel().uiState.value.tabs.isEmpty()
            }
        }
    }

    // ---------------------------------------------------------------- the tests

    /**
     * Two connected hosts, two listings: the screen shows the one belonging to the chip that was
     * tapped, and only that one.
     *
     * Each server's home directory holds a file the other's does not, so "alpha's listing is on
     * screen" and "beta's is not" are both claims about bytes that came off a particular socket
     * rather than about which state happened to be written last. And the trip is made three times,
     * because a browser that can be pointed at another session once and then sticks there is the
     * same bug from the other side.
     */
    @Test
    fun eachSessionKeepsItsOwnListingAndSwitchingFollowsTheChips() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        val beta = connect(BETA_NAME, betaPort)
        awaitSftp(alpha, beta)
        openFilesScreen()

        browse(ALPHA_NAME, ALPHA_HOME_FILE)
        assertThat(names()).doesNotContain(BETA_HOME_FILE)
        assertThat(explorer().activeSessionId).isEqualTo("sftp:$alpha")

        browse(BETA_NAME, BETA_HOME_FILE)
        assertThat(names()).doesNotContain(ALPHA_HOME_FILE)
        assertThat(explorer().activeSessionId).isEqualTo("sftp:$beta")

        browse(ALPHA_NAME, ALPHA_HOME_FILE)
        assertThat(names()).doesNotContain(BETA_HOME_FILE)

        // Neither session was disturbed by any of the switching: the explorer rides on the SFTP
        // channel the shell's transport already authenticated, and switching its mind must not
        // touch either.
        assertThat(tabFor(alpha)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tabFor(beta)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tabFor(alpha)?.sftpState).isEqualTo(SftpSessionState.READY)
        assertThat(tabFor(beta)?.sftpState).isEqualTo(SftpSessionState.READY)
    }

    /**
     * A session that is switched away from and come back to is still in the directory it was browsing.
     *
     * The spec's "semua session harus mempertahankan lokasi terakhirnya": Local → Server A → Server B
     * → Server A lands back in A's subdirectory, not in A's home. Two directories deep enough to tell
     * apart, each holding a file the other's does not, because "came back to the right folder" is a
     * claim about rows that came off that server's wire, and the path alone could be satisfied by a
     * remembered string with someone else's listing under it.
     */
    @Test
    fun switchingBackToASessionRestoresTheDirectoryItWasBrowsing() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        val beta = connect(BETA_NAME, betaPort)
        awaitSftp(alpha, beta)
        openFilesScreen()

        browse(ALPHA_NAME, ALPHA_HOME_FILE)
        openDirectory(ALPHA_DIR, ALPHA_PROOF)
        val alphaDir = browsedPath()

        browse(BETA_NAME, BETA_HOME_FILE)
        openDirectory(BETA_DIR, BETA_PROOF)
        val betaDir = browsedPath()

        browse(ALPHA_NAME, ALPHA_PROOF)
        assertThat(browsedPath()).isEqualTo(alphaDir)
        assertThat(names()).doesNotContain(BETA_PROOF)

        browse(BETA_NAME, BETA_PROOF)
        assertThat(browsedPath()).isEqualTo(betaDir)
        assertThat(names()).doesNotContain(ALPHA_PROOF)
    }

    /**
     * Refresh re-reads the directory on screen instead of navigating home.
     *
     * The proof of the re-read is a file created on the server after the listing was taken: it can only
     * appear if the directory was read again. The proof that it stayed put is the path, which a
     * refresh that resolved back to the home directory whenever it was not spelled out would
     * teleport the user out of the folder they were in.
     */
    @Test
    fun refreshingListsTheDirectoryOnScreenRatherThanTheHomeDirectory() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        awaitSftp(alpha)
        openFilesScreen()

        browse(ALPHA_NAME, ALPHA_HOME_FILE)
        openDirectory(ALPHA_DIR, ALPHA_PROOF)
        val alphaDir = browsedPath()

        val appeared = alphaRoot.resolve(ALPHA_DIR).resolve(REFRESH_PROOF)
        Files.deleteIfExists(appeared)
        Files.write(appeared, "written after the listing\n".toByteArray())

        compose.onNodeWithContentDescription("Refresh").performClick()
        pumpUntil(describe = { "the refresh never re-read $alphaDir: " + diagnose() }) {
            names().contains(REFRESH_PROOF)
        }
        assertThat(browsedPath()).isEqualTo(alphaDir)
        assertThat(names()).contains(ALPHA_PROOF)
        assertThat(names()).doesNotContain(ALPHA_HOME_FILE)
    }

    /**
     * A host that is saved but not connected: its chip is still offered, browsing it says why it
     * cannot, and the device's own session keeps working.
     *
     * The spec is explicit on all three — a disconnected session stays in the list so the user can
     * reconnect, the failure is a sentence on the screen rather than a crash, and Local works no
     * matter what the servers are doing. Reached through the chip because that is the only way a
     * user reaches a session at all.
     */
    @Test
    fun aDisconnectedHostIsReportedOnScreenAndTheDeviceSessionKeepsWorking() {
        saveOnly(ALPHA_NAME, alphaPort)
        openFilesScreen()

        pumpUntil(describe = { "the disconnected host's chip never appeared: " + diagnose() }) {
            explorer().sessions.any { it.label == ALPHA_NAME && !it.live }
        }
        // The device's session is first and present throughout — the spec's "local selalu first".
        assertThat(explorer().sessions.first().id).isEqualTo(LOCAL_SESSION_ID)

        browse(ALPHA_NAME, proof = null)
        pumpUntil(describe = { "browsing the dead host said nothing: " + diagnose() }) {
            explorer().error == "$ALPHA_NAME is not connected"
        }
        compose.onNode(hasText("$ALPHA_NAME is not connected")).assertIsDisplayed()

        // And Local is untouched by the failure: one tap and it is its own front-door state again.
        browse("This device", proof = null)
        pumpUntil(describe = { "Local never came back: " + diagnose() }) {
            explorer().isLocal && explorer().error == null && explorer().path == null
        }
        compose.onNode(hasText("Pick folder") and hasClickAction()).assertIsDisplayed()

        // The app is still standing: the navigation bar is there and Terminal is a tap away.
        compose.onNode(hasText("Terminal") and hasClickAction()).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /** The explorer's own snapshot — what the Files tab renders, distinct from the app-wide uiState. */
    private fun explorer(): ExplorerState = viewModel().filesExplorer.state.value

    private fun tabFor(hostId: String): SessionTab? =
        viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    /** What the explorer is showing: the names of the active session's listing. */
    private fun names(): List<String> = explorer().entries.map { it.name }

    private fun browsedPath(): String? = explorer().path

    /**
     * Navigates to Files the way a user with a session open has to.
     *
     * Connecting takes the app straight into the shell full screen, and a full-screen shell has no
     * navigation bar at all - that is the point of it - so there is no "Files" tab to tap until the
     * shell is left. Back is the way out, and the only way out: `BackHandler(enabled = terminalImmersive)`
     * clears the watched session rather than closing the app. Doing it through the dispatcher rather than
     * by reaching for the state keeps this a test of the route a user actually takes.
     */
    private fun openFilesScreen() {
        compose.waitForIdle()
        // Only a session leaves the app in the full-screen shell; with none open the bar is already
        // there and Back would be the exit-the-activity gesture instead.
        if (compose.onAllNodes(hasText("Files") and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            pumpUntil(describe = { "the navigation bar never came back after leaving the shell" }) {
                compose.onAllNodes(hasText("Files") and hasClickAction()).fetchSemanticsNodes().size == 1
            }
        }
        compose.onNode(hasText("Files") and hasClickAction()).performClick()
    }

    /**
     * Taps the session chip named [label] and waits for [proof] to be listed.
     *
     * Through the chip because that is the assertion: the chip is the affordance, and a controller
     * call could not fail the way this is meant to fail — by the browser not following the tap.
     * Matched on text plus a click action rather than on a role, because the one label that could
     * be ambiguous ("This device", also the local title before any folder is granted) is only
     * clickable as the chip.
     */
    private fun browse(label: String, proof: String?) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        pumpUntil(describe = { "tapping the $label chip never listed anything: " + diagnose() }) {
            proof == null || names().contains(proof)
        }
    }

    /** Opens the subdirectory named [name] the way tapping its row does, and waits for its contents. */
    private fun openDirectory(name: String, proof: String) {
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        pumpUntil(describe = { "$name never opened: " + diagnose() }) {
            names().contains(proof)
        }
    }

    private fun awaitSftp(vararg hostIds: String) {
        pumpUntil(describe = { "SFTP never became ready: " + diagnose() }) {
            hostIds.all { tabFor(it)?.sftpState == SftpSessionState.READY }
        }
    }

    /**
     * Saves a profile pointing at [port] and connects it, accepting the host key the way the dialog's
     * button does.
     *
     * Auto-login is on for every host here: it exercises the SFTP channel the explorer rides on, and
     * proves the explorer neither needs nor disturbs it.
     */
    private fun connect(name: String, port: Int): String {
        val hostId = saveOnly(name, port)
        compose.runOnUiThread { viewModel().connect(hostFor(hostId), password = PASSWORD) }

        // Every challenge is answered, not only the first: each server generates its own host key, so
        // the first connection to either is genuinely a trust-on-first-use prompt, and accepting it is
        // what retries the connection.
        var accepts = 0
        pumpUntil(describe = { "$name never connected: accepts=$accepts " + diagnose() }) {
            if (viewModel().uiState.value.hostKeyChallenge != null) {
                accepts++
                compose.runOnUiThread { viewModel().acceptHostKey() }
            }
            tabFor(hostId)?.state == SessionConnectionState.CONNECTED
        }
        return hostId
    }

    /** Saves a profile without connecting it — the state a user is in between sessions. */
    private fun saveOnly(name: String, port: Int): String {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "files-switch-" + name + "-" + nextHostId++,
            name = name,
            host = LOOPBACK,
            username = USER,
            port = port,
            authMethod = AuthMethod.PASSWORD,
            // Stated rather than defaulted: this suite shares a JVM with every other test, and a
            // loopback handshake that takes milliseconds alone should not fail on a loaded machine.
            connectTimeoutSeconds = 60,
            autoLoginSftp = true,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        return profile.id
    }

    private fun hostFor(hostId: String): HostProfile =
        viewModel().uiState.value.hosts.first { it.id == hostId }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. `sendApplyNotifications` publishes state written outside
     * a frame — `connect` and the explorer's own scope both write from plain function calls — which is
     * what invalidates the recomposer at all. `idleFor` rather than `idle` because this code is full of
     * real `delay`, and `idle()` leaves the looper's virtual clock where it was.
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

    /** Everything worth knowing when the explorer is not showing what it should be. */
    private fun diagnose(): String {
        val viewModel = viewModel()
        return buildString {
            append("explorer=").append(explorer())
            append(" tabs=").append(viewModel.uiState.value.tabs)
            append(" status=").append(viewModel.statusMessage.value)
            append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
            append(" alphaPort=").append(alphaPort).append(" betaPort=").append(betaPort)
        }
    }

    private companion object {
        /**
         * How long a wait behind the wire is given.
         *
         * Longer than a state wait needs, because this suite shares two cores with two real SSH servers,
         * a real Room database and a real DataStore. A harness deadline shorter than the app's own
         * connect budget can only report the harness running out of patience, never what the app did -
         * and nothing asserted here is relaxed by the extra time, since every wait ends the moment its
         * condition holds.
         */
        const val SSH_TIMEOUT_MS = 90_000L

        /** Closing two sessions is local work, so it gets a fraction of a connect's budget. */
        const val TEARDOWN_TIMEOUT_MS = 15_000L

        const val LOOPBACK = "127.0.0.1"

        /**
         * Local test credentials, and only local: both servers below are bound to loopback inside
         * this JVM, authenticate nothing but this pair, and die with the process.
         */
        const val USER = "testuser"
        const val PASSWORD = "testpass123"

        const val ALPHA_NAME = "files-alpha"
        const val BETA_NAME = "files-beta"

        /**
         * One uniquely-named file per directory per server, so every assertion about which listing is on
         * screen is an assertion about which socket the bytes came from.
         */
        const val ALPHA_HOME_FILE = "alpha-home.txt"
        const val BETA_HOME_FILE = "beta-home.txt"
        const val ALPHA_DIR = "alpha-logs"
        const val BETA_DIR = "beta-logs"
        const val ALPHA_PROOF = "alpha-inside.txt"
        const val BETA_PROOF = "beta-inside.txt"

        /** Created on the server mid-test: what proves a refresh re-read the directory. */
        const val REFRESH_PROOF = "appeared-after-the-listing.txt"

        var alphaPort = 0
        var betaPort = 0
        private var nextHostId = 0
        private lateinit var alphaRoot: Path
        private lateinit var alphaServer: SshServer
        private lateinit var betaServer: SshServer

        @JvmStatic
        @BeforeClass
        fun startServers() {
            alphaRoot = tree("eclipse-files-alpha", ALPHA_HOME_FILE, ALPHA_DIR, ALPHA_PROOF)
            val betaRoot = tree("eclipse-files-beta", BETA_HOME_FILE, BETA_DIR, BETA_PROOF)
            alphaServer = server(alphaRoot)
            betaServer = server(betaRoot)
            alphaPort = (alphaServer.boundAddresses.first() as InetSocketAddress).port
            betaPort = (betaServer.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServers() {
            runCatching { alphaServer.stop(true) }
            runCatching { betaServer.stop(true) }
        }

        /** A root holding one file, and one subdirectory holding one file. */
        private fun tree(prefix: String, homeFile: String, dir: String, inside: String): Path {
            val root = Files.createTempDirectory(prefix)
            Files.write(root.resolve(homeFile), "home\n".toByteArray())
            Files.write(Files.createDirectory(root.resolve(dir)).resolve(inside), "inside\n".toByteArray())
            return root
        }

        /**
         * A started server on a kernel-assigned port with its own host key.
         *
         * Port 0 because Gradle runs the debug and release unit-test tasks in separate JVMs and they
         * overlap: a fixed port means the loser of the bind runs its whole suite against a server that
         * never started. Its own key file per server so trusting one cannot disturb the other's
         * known-hosts entry.
         */
        private fun server(root: Path): SshServer =
            SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("files-switch-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
                }
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
                shellFactory = ShellFactory { QuietShell() }
                start()
            }
    }

    /**
     * A shell that prints a prompt and echoes, which is all these tests need from the SSH half.
     *
     * Present at all because every assertion here is about the file browser while the terminal keeps
     * working: a server with no shell factory would fail to open the pty, the tab would never reach
     * CONNECTED, and the test would be measuring the wrong thing. CRLF because that is what a pty
     * writes.
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
                    while (true) {
                        val byte = source.read()
                        if (byte < 0) break
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
