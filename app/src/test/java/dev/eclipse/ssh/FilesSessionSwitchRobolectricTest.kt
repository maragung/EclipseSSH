package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import dev.eclipse.ssh.ssh.RemoteFile
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
 * The file browser, with two sessions open at once, against two local SSH servers in this JVM.
 *
 * The Files screen used to hold exactly one listing for the whole app: one path and one list of rows,
 * whoever had asked for them. With a single session that is invisible, and every one of these tests
 * would have passed. With two it is the difference between a file browser and a coin toss — the last
 * listing to arrive was the one on screen, and listings arrive on their own schedule: an auto-login
 * finishing on a session that just reconnected, a refresh on the host the user has switched away from.
 * The rows are also what Download, Rename, Chmod and Delete resolve their paths from, so a listing
 * that belonged to another session was not merely the wrong picture; it was an absolute path about to
 * be sent to a server that had never listed it.
 *
 * So the assertions here are mostly about *separation*: each session keeps its own directory and its
 * own rows, switching shows the one that belongs to the host being browsed, and a listing that lands
 * for another session never reaches the screen. Two servers rather than two directories on one, each
 * with its own root and its own uniquely-named files, because "this listing came from that host" has
 * to be provable from the contents rather than argued from the path.
 *
 * Both servers are bound to loopback inside this JVM, authenticate one pair of credentials that exists
 * nowhere else, and die with the process.
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
     * Two connected hosts, two listings: the screen shows the one belonging to the host being browsed,
     * and switching moves it.
     *
     * The negative half is the one that used to fail. Each server's home directory holds a file the
     * other's does not, so "alpha's listing is on screen" and "beta's listing is not" are both claims
     * about bytes that came off a particular socket rather than about which state happened to be
     * written last.
     */
    @Test
    fun eachSessionKeepsItsOwnListingAndSwitchingFollowsTheSelectedHost() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        val beta = connect(BETA_NAME, betaPort)
        awaitSftp(alpha, beta)

        // Connecting selects, so beta - dialled second - is the host the app is pointing at.
        assertThat(viewModel().uiState.value.selectedHostId).isEqualTo(beta)
        browse(beta, BETA_HOME_FILE)
        assertThat(names()).doesNotContain(ALPHA_HOME_FILE)

        browse(alpha, ALPHA_HOME_FILE)
        assertThat(names()).doesNotContain(BETA_HOME_FILE)

        // And back, because a browser that can be pointed at another session once and then sticks
        // there is the same bug from the other side.
        browse(beta, BETA_HOME_FILE)
        assertThat(names()).doesNotContain(ALPHA_HOME_FILE)
        // Neither session was disturbed by any of the switching: SFTP is a channel on the transport
        // the shell is on, and a file browser changing its mind must not touch either.
        assertThat(tabFor(alpha)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tabFor(beta)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tabFor(alpha)?.sftpState).isEqualTo(SftpSessionState.READY)
        assertThat(tabFor(beta)?.sftpState).isEqualTo(SftpSessionState.READY)
    }

    /**
     * A session that is switched away from and come back to is still in the directory it was browsing.
     *
     * Two directories deep enough to tell apart: alpha is left in its subdirectory, beta is sent into
     * its own, and the assertion is that alpha's path and rows come back exactly as they were rather
     * than the browser landing in the home directory again — which is what a single shared listing did,
     * because the only path it had was whatever the other host had just written into it.
     */
    @Test
    fun switchingBackToASessionRestoresTheDirectoryItWasBrowsing() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        val beta = connect(BETA_NAME, betaPort)
        awaitSftp(alpha, beta)

        browse(alpha, ALPHA_HOME_FILE)
        val alphaDir = dirPath(ALPHA_DIR)
        enter(alpha, alphaDir, ALPHA_PROOF)

        browse(beta, BETA_HOME_FILE)
        val betaDir = dirPath(BETA_DIR)
        enter(beta, betaDir, BETA_PROOF)

        select(alpha)
        pumpUntil(describe = { "alpha did not come back to $alphaDir: " + diagnose() }) {
            browsedPath() == alphaDir && names().contains(ALPHA_PROOF)
        }
        assertThat(names()).doesNotContain(BETA_PROOF)

        select(beta)
        pumpUntil(describe = { "beta did not come back to $betaDir: " + diagnose() }) {
            browsedPath() == betaDir && names().contains(BETA_PROOF)
        }
        assertThat(names()).doesNotContain(ALPHA_PROOF)
    }

    /**
     * A listing that arrives for another session never appears on the screen.
     *
     * This is the race the shared listing lost, made deliberate: alpha is being browsed in its
     * subdirectory while beta is told to list its own — which is what a reconnect's SFTP auto-login,
     * or a refresh on the session just switched away from, does without anyone asking.
     *
     * Both halves are needed. The invariant is checked on every frame rather than once at the end,
     * because a listing that flashes onto the screen and is corrected a frame later is still a file
     * browser showing the wrong server's files. And the wait is on beta's *own* recorded directory, so
     * the invariant cannot pass merely because nothing happened yet: it holds while a listing for the
     * other session provably lands.
     */
    @Test
    fun aListingForAnotherSessionNeverReachesTheBrowsedOne() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        val beta = connect(BETA_NAME, betaPort)
        awaitSftp(alpha, beta)

        browse(beta, BETA_HOME_FILE)
        val betaDir = dirPath(BETA_DIR)
        browse(alpha, ALPHA_HOME_FILE)
        val alphaDir = dirPath(ALPHA_DIR)
        enter(alpha, alphaDir, ALPHA_PROOF)

        refresh(beta, betaDir)
        pumpUntil(describe = { "beta's own listing never landed: " + diagnose() }) {
            assertThat(browsedPath()).isEqualTo(alphaDir)
            assertThat(names()).doesNotContain(BETA_PROOF)
            viewModel().remoteDirectory(hostFor(beta)) == betaDir
        }

        // And the listing that was kept off the screen is exactly what beta shows when it is asked for.
        select(beta)
        pumpUntil(describe = { "beta's listing was lost rather than kept: " + diagnose() }) {
            browsedPath() == betaDir && names().contains(BETA_PROOF)
        }
    }

    /**
     * Refresh re-reads the directory on screen instead of navigating home.
     *
     * The proof of the re-read is a file created on the server after the listing was taken: it can only
     * appear if the directory was read again. The proof that it stayed put is the path, which used to
     * resolve back to the home directory whenever it was not spelled out — so Refresh, arriving on the
     * Files tab, and switching sessions all teleported the user out of the folder they were in.
     */
    @Test
    fun refreshingListsTheDirectoryOnScreenRatherThanTheHomeDirectory() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        awaitSftp(alpha)

        browse(alpha, ALPHA_HOME_FILE)
        val alphaDir = dirPath(ALPHA_DIR)
        enter(alpha, alphaDir, ALPHA_PROOF)

        val appeared = alphaRoot.resolve(ALPHA_DIR).resolve(REFRESH_PROOF)
        Files.deleteIfExists(appeared)
        Files.write(appeared, "written after the listing\n".toByteArray())

        // No path, which is what the Refresh button passes.
        refresh(alpha, path = null)
        pumpUntil(describe = { "the refresh never re-read $alphaDir: " + diagnose() }) {
            names().contains(REFRESH_PROOF)
        }
        assertThat(browsedPath()).isEqualTo(alphaDir)
        assertThat(names()).contains(ALPHA_PROOF)
        assertThat(names()).doesNotContain(ALPHA_HOME_FILE)
    }

    /**
     * The switcher on the Files screen, tapped: one chip per open session, and the browser follows the
     * chip.
     *
     * Through the screen rather than the view model because the complaint was about the screen. The
     * state was addressable all along — the Files tab simply named no host and offered no way to change
     * which one it was reading, so with two sessions open the browser was stuck on whichever host the
     * rest of the app had selected. The content descriptions are the assertion that a screen reader
     * gets the same affordance: a list of servers, one of them the one being read.
     */
    @Test
    fun theSwitcherChipMovesTheFileBrowserToTheOtherSession() {
        val alpha = connect(ALPHA_NAME, alphaPort)
        val beta = connect(BETA_NAME, betaPort)
        awaitSftp(alpha, beta)
        browse(beta, BETA_HOME_FILE)

        openFilesScreen()
        pumpUntil(describe = { "the session switcher never appeared on the Files screen" }) {
            chips("Browse files on $ALPHA_NAME") == 1 && chips("Browsing files on $BETA_NAME") == 1
        }

        compose.onNodeWithContentDescription("Browse files on $ALPHA_NAME").performClick()
        pumpUntil(describe = { "the chip did not move the browser to alpha: " + diagnose() }) {
            viewModel().uiState.value.selectedHostId == alpha && names().contains(ALPHA_HOME_FILE)
        }
        assertThat(names()).doesNotContain(BETA_HOME_FILE)
        // The chips swap roles, so the row still says which session is being read.
        pumpUntil(describe = { "the chips did not follow the switch" }) {
            chips("Browsing files on $ALPHA_NAME") == 1 && chips("Browse files on $BETA_NAME") == 1
        }
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun tabFor(hostId: String): SessionTab? =
        viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    private fun hostFor(hostId: String): HostProfile =
        viewModel().uiState.value.hosts.first { it.id == hostId }

    /** What the file browser is showing: the listing of whichever host is selected. */
    private fun names(): List<String> = viewModel().uiState.value.remoteFiles.map(RemoteFile::name)

    private fun browsedPath(): String? = viewModel().uiState.value.remotePath

    private fun chips(description: String): Int =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    private fun select(hostId: String) {
        compose.runOnUiThread { viewModel().selectHost(hostFor(hostId)) }
    }

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
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        pumpUntil(describe = { "the navigation bar never came back after leaving the shell" }) {
            compose.onAllNodes(hasText("Files") and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasText("Files") and hasClickAction()).performClick()
    }

    /** Points the browser at [hostId] and waits until the listing on screen is that host's own. */
    private fun browse(hostId: String, proof: String) {
        select(hostId)
        pumpUntil(describe = { "$proof never appeared for $hostId: " + diagnose() }) {
            names().contains(proof)
        }
    }

    /** Opens [path] on [hostId] the way tapping a directory row does, and waits for its contents. */
    private fun enter(hostId: String, path: String, proof: String) {
        compose.runOnUiThread { viewModel().navigateRemote(hostFor(hostId), path) }
        pumpUntil(describe = { "$hostId never opened $path: " + diagnose() }) {
            browsedPath() == path && names().contains(proof)
        }
    }

    private fun refresh(hostId: String, path: String?) {
        compose.runOnUiThread { viewModel().refreshFiles(hostFor(hostId), path) }
    }

    /** The path of the subdirectory named [name] in the listing on screen. */
    private fun dirPath(name: String): String =
        viewModel().uiState.value.remoteFiles.first { it.name == name && it.isDirectory }.path

    private fun awaitSftp(vararg hostIds: String) {
        pumpUntil(describe = { "SFTP never became ready: " + diagnose() }) {
            hostIds.all { tabFor(it)?.sftpState == SftpSessionState.READY }
        }
    }

    /**
     * Saves a profile pointing at [port] and connects it, accepting the host key the way the dialog's
     * button does.
     *
     * Auto-login is on for every host here: it is what puts a listing on the screen without anyone
     * navigating, and therefore what made two sessions overwrite each other.
     */
    private fun connect(name: String, port: Int): String {
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
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        compose.runOnUiThread { viewModel.connect(saved, password = PASSWORD) }

        // Every challenge is answered, not only the first: each server generates its own host key, so
        // the first connection to either is genuinely a trust-on-first-use prompt, and accepting it is
        // what retries the connection.
        var accepts = 0
        pumpUntil(describe = { "$name never connected: accepts=$accepts " + diagnose() }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                accepts++
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(profile.id)?.state == SessionConnectionState.CONNECTED
        }
        return saved.id
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. `sendApplyNotifications` publishes state written outside
     * a frame — `connect`, `loginSftp` and `refreshFiles` all write from plain function calls — which is
     * what invalidates the recomposer at all. `idleFor` rather than `idle` because this code is full of
     * real `delay`, and `idle()` leaves the looper's virtual clock where it was.
     *
     * [condition] may assert as well as answer, which is how an invariant is held across a wait rather
     * than checked once it is over.
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

    /** Everything worth knowing when the browser is not showing what it should be. */
    private fun diagnose(): String {
        val viewModel = viewModel()
        return buildString {
            append("selected=").append(viewModel.uiState.value.selectedHostId)
            append(" remotePath=").append(viewModel.uiState.value.remotePath)
            append(" remoteFiles=").append(names())
            append(" tabs=").append(viewModel.uiState.value.tabs)
            append(" status=").append(viewModel.statusMessage.value)
            append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
            append(" alphaPort=").append(alphaPort).append(" betaPort=").append(betaPort)
            append(" diagnostics=").append(viewModel.uiState.value.diagnostics.map { it.line() })
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
         * Local test credentials, and only local: both servers below are bound to loopback inside this
         * JVM, authenticate nothing but this pair, and die with the process.
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
