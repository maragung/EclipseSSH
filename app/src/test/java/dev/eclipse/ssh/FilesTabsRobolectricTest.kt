package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
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
 * The shape of the Files screen: whose files are on it, and how much of the window they get.
 *
 * The complaint this answers is that picking a session and opening Files did not show that session's
 * files - it showed the top half of them. Both listings were stacked in one `Column`, and that Column
 * was inside the app-wide `verticalScroll`, which hands its children an unbounded height. So neither
 * pane could be told to fill the window (`weight`/`fillMaxHeight` mean nothing without a height to
 * divide), both drew themselves at their full natural length, and the *page* scrolled: the server's
 * directory took the first screenful, the phone's folder began somewhere below the fold, and the path
 * row and the session switcher scrolled away with them.
 *
 * So the assertions here are about layout, which is unusual for this suite and is the point - the code
 * was correct about *which* files to show long before it was capable of showing them. Three claims,
 * each of which was false before:
 *
 *  - on a phone the two listings are separate tabs, and only the chosen one is composed;
 *  - the chosen listing fills what the header and the selection bar leave, measured rather than
 *    asserted by eye;
 *  - scrolling it scrolls the listing, not the page, so the tabs and the path stay where they are.
 *
 * The breakpoint is covered from both sides: a phone gets tabs, and a tablet wide enough for both gets
 * them side by side, since a device with the width to show a transfer's two ends at once should.
 *
 * Two tests need a session, because a session switcher needs an open session and a selection bar needs
 * rows to select - so those run against a real SSH server with a real SFTP subsystem, bound to loopback
 * inside this JVM, authenticating one pair of credentials that exists nowhere else and dying with the
 * process. The rest run on a clean install, where the browser composes disconnected, because a layout
 * bug does not need a socket to reproduce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FilesTabsRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Ends every session this test opened, and waits for it to be gone.
     *
     * A fresh application per test does not close a socket, and `closeTab` finishes on a coroutine that
     * stops being pumped the moment the test method returns - so firing the closes and walking away
     * leaves live threads, and a live thread is a GC root, on a JVM confined to two cores. Waiting keeps
     * each test's cleanup inside its own test.
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

    // ---------------------------------------------------------------- a phone: two tabs

    /**
     * On a phone the server and the phone's own files are two tabs, and only one of them is composed.
     *
     * "Only one" is the half that fixes the screen rather than decorating it. A tab row over two panes
     * that are both still built would leave the server's listing exactly as short as it was; the local
     * pane's header is asserted *absent* because that is the difference between a tab and a heading.
     */
    @Test
    fun thePhoneShowsTheTwoListingsAsTabsAndComposesOnlyTheChosenOne() {
        openFiles()

        // Server first, because that is what the user opened Files to see. Its count is left off until
        // there is a listing - an empty `remoteFiles` means "not connected" as often as it means "empty
        // directory", and on a clean install it is the former.
        awaitDisplayed("Server")
        awaitDisplayed("Local")
        assertThat(nodes("Server · 0")).isEqualTo(0)

        // The server pane is the one on screen: its parent row and its own account of the session.
        compose.onNodeWithText("Parent directory").assertIsDisplayed()
        compose.onNodeWithText("Not connected").assertIsDisplayed()

        // And the local pane is not merely below the fold - it does not exist.
        assertThat(nodes("Local files")).isEqualTo(0)
        assertThat(nodes("Choose a local folder to browse and transfer files.")).isEqualTo(0)
    }

    /**
     * Tapping Local replaces the server listing rather than scrolling to it, and Server brings it back.
     *
     * The return trip is half the test: a tab that can be left and not returned to is the stacked layout
     * again with an extra tap in front of it.
     */
    @Test
    fun tappingLocalReplacesTheServerListingAndTappingServerBringsItBack() {
        openFiles()
        awaitDisplayed("Local")

        tab("Local").performClick()
        awaitDisplayed("Local files")
        compose.onNodeWithText("Choose a local folder to browse and transfer files.").assertIsDisplayed()
        assertThat(nodes("Not connected")).isEqualTo(0)
        assertThat(nodes("Parent directory")).isEqualTo(0)

        tab("Server").performClick()
        awaitDisplayed("Not connected")
        compose.onNodeWithText("Parent directory").assertIsDisplayed()
        assertThat(nodes("Local files")).isEqualTo(0)
    }

    /**
     * The chosen listing fills the window under the header, measured against the window itself.
     *
     * Measured rather than eyeballed because the bug was invisible to every assertion the suite already
     * had: `assertExists` passes on a pane one row tall, and so does every "the right files are on
     * screen" test in [FilesSessionSwitchRobolectricTest]. The two numbers are the two halves of "fills
     * it": tall enough that it is not merely wrapping its contents, and ending near the bottom of the
     * window rather than partway down it. Both are deliberately loose - this is a claim about the layout
     * being bounded at all, not about a particular header height, which is free to change.
     */
    @Test
    fun theChosenListingFillsTheWindowRatherThanWrappingItsContents() {
        openFiles()
        awaitDisplayed("Parent directory")

        val window = compose.onRoot().getBoundsInRoot()
        val pane = listing().getBoundsInRoot()
        val windowHeight = window.bottom - window.top
        val paneHeight = pane.bottom - pane.top

        // Wrapping its two rows would be well under a fifth of the window; half is a bound only a pane
        // that was given the leftover height can clear.
        assertThat(paneHeight.value).isGreaterThan(windowHeight.value / 2)
        // And it reaches the bottom: what is left is the navigation bar and this screen's own padding,
        // not a second listing stacked under it.
        assertThat((window.bottom - pane.bottom).value).isLessThan(BOTTOM_SLACK_DP)
    }

    /**
     * A tablet shows both listings at once instead of tabs.
     *
     * The other side of the breakpoint, and a real behaviour rather than a fallback: with the width for
     * both, a transfer's two ends belong on screen together, and tabs would hide half of what the user
     * is dragging between. Asserted by the absence of the tab row rather than its presence somewhere
     * else, since "Server" and "Local" as tabs and as panes are different things.
     */
    @Test
    @Config(qualifiers = "w1024dp-h768dp-xhdpi")
    fun aTabletShowsBothListingsSideBySideInsteadOfTabs() {
        openFiles()

        awaitDisplayed("Not connected")
        awaitDisplayed("Local files")
        // Exact text, so "Local files" does not answer for the "Local" tab.
        assertThat(nodes("Local")).isEqualTo(0)
        assertThat(nodes("Server")).isEqualTo(0)
    }

    // ---------------------------------------------------------------- with a session open

    /**
     * Scrolling a long directory scrolls the directory, and the tabs and the path stay where they are.
     *
     * This is the behaviour the old layout could not have: with the listing inside the page's scroll,
     * reaching the last file meant scrolling the page, which carried the path row, the session switcher
     * and - once they existed - the tabs off the top of the screen. Sixty files so the list is several
     * screens long, and the scroll is asked of the listing by index, which only a lazy list that owns
     * its own scrolling can answer.
     */
    @Test
    fun scrollingALongDirectoryLeavesTheTabsAndThePathInPlace() {
        val host = connect()
        awaitSftp(host)
        openFiles()
        awaitDisplayed("Parent directory")
        pumpUntil(describe = { "the listing never arrived: " + diagnose() }) {
            names().contains(bulkName(FILE_COUNT - 1))
        }

        listing().performScrollToIndex(FILE_COUNT)
        compose.waitForIdle()

        // The far end of the directory is on screen...
        awaitDisplayed(bulkName(FILE_COUNT - 1))
        // ...and so is everything that is not the directory. The tabs, the path the rows belong to, and
        // the switcher that says which host they came from: all outside the pane that scrolled.
        compose.onNodeWithText("Server · $FILE_COUNT").assertIsDisplayed()
        compose.onNodeWithText("Local").assertIsDisplayed()
        assertThat(browsingChips()).isEqualTo(1)
        // The first row is above the pane's top now, which is the proof it scrolled at all rather than
        // the whole list having been composed at full length.
        assertThat(nodes("Parent directory")).isEqualTo(0)
    }

    /**
     * Switching pane keeps the session switcher and the selection bar - the two things that belong to
     * the screen rather than to either listing.
     *
     * They are also the two the tabs could most easily have swallowed. The switcher answers "whose
     * files are these", which does not change when the pane does; the selection bar counts
     * `selectedRemote + selectedLocal` together, because Download, Upload and Send to host each need one
     * side and the other, so a bar that emptied on a tab change would take a half-made transfer with it.
     */
    @Test
    fun switchingPaneKeepsTheSessionSwitcherAndTheSelectionBar() {
        val host = connect()
        awaitSftp(host)
        openFiles()
        pumpUntil(describe = { "the listing never arrived: " + diagnose() }) {
            names().contains(bulkName(0))
        }

        // Only files carry a checkbox, so the first toggleable node in the pane is a file row's.
        awaitDisplayed("Server · $FILE_COUNT")
        pumpUntil(describe = { "no selectable row appeared: " + tree() }) {
            compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(isToggleable())[0].performClick()
        awaitDisplayed("1 selected")

        tab("Local").performClick()
        awaitDisplayed("Local files")
        // Both survive the switch, on the pane that holds neither of the selected rows.
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        assertThat(browsingChips()).isEqualTo(1)

        // And back, with the selection still counted rather than quietly dropped.
        tab("Server").performClick()
        awaitDisplayed("Server · $FILE_COUNT")
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun hostFor(hostId: String): HostProfile =
        viewModel().uiState.value.hosts.first { it.id == hostId }

    private fun names(): List<String> = viewModel().uiState.value.remoteFiles.map(RemoteFile::name)

    /**
     * The listing tab whose label starts with [label].
     *
     * Matched on the selectable role and on a prefix of the label rather than the whole of it: "Server"
     * and "Server · 60" are the same tab, the count appears the moment a directory is listed, and
     * pinning the whole label here would make every assertion in this class depend on how many files the
     * fixture happens to have. The role is what keeps it unambiguous - the tabs are the only selectable
     * nodes on this screen, so no heading or button carrying the same word can answer for one.
     */
    private fun tab(label: String) = compose.onNode(isSelectable() and hasText(label, substring = true))

    private fun nodes(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /**
     * How many session chips say they are the one being browsed.
     *
     * By content description rather than by the host's name, because that description is the affordance:
     * the switcher exists so the Files screen names its server and offers the others, and a screen reader
     * gets exactly one "Browsing files on ..." however many sessions are open.
     */
    private fun browsingChips(): Int =
        compose.onAllNodesWithContentDescription("Browsing files on $HOST_NAME").fetchSemanticsNodes().size

    /**
     * The listing that is on screen: the scrollable holding the parent-directory row.
     *
     * Named by its contents rather than by `hasScrollAction()` alone, because the selection bar scrolls
     * horizontally and is a scrollable too - so once a row is selected, "the scrollable" is ambiguous
     * and a height measured from the wrong one would pass for the wrong reason.
     */
    private fun listing() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Parent directory")))

    private fun awaitDisplayed(text: String) {
        pumpUntil(describe = { "\"$text\" never appeared: " + tree() }) { nodes(text) > 0 }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /**
     * Navigates to Files by the route the app leaves open.
     *
     * Connecting takes the app straight into the shell full screen, and a full-screen shell has no
     * navigation bar at all - that is the point of it - so the Files tab does not exist until the shell
     * is left, and Back is the way out. Going through the dispatcher keeps this the route a user takes
     * rather than a state write no user can perform.
     */
    private fun openFiles() {
        compose.waitForIdle()
        if (compose.onAllNodes(hasText("Files") and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            pumpUntil(describe = { "the navigation bar never came back after leaving the shell" }) {
                compose.onAllNodes(hasText("Files") and hasClickAction()).fetchSemanticsNodes().size == 1
            }
        }
        tab("Files").performClick()
        compose.waitForIdle()
    }

    private fun awaitSftp(vararg hostIds: String) {
        pumpUntil(describe = { "SFTP never became ready: " + diagnose() }) {
            hostIds.all { id ->
                viewModel().uiState.value.tabs.firstOrNull { it.hostId == id }?.sftpState == SftpSessionState.READY
            }
        }
    }

    /**
     * Saves a profile pointing at the local server and connects it, accepting the host key.
     *
     * Auto-login is on, which is what puts a listing on the screen without anyone navigating - the state
     * these two tests need before they can say anything about tabs.
     */
    private fun connect(): String {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "files-tabs-" + nextHostId++,
            name = HOST_NAME,
            host = LOOPBACK,
            username = USER,
            port = serverPort,
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
        compose.runOnUiThread { viewModel.connect(hostFor(profile.id), password = PASSWORD) }

        var accepts = 0
        pumpUntil(describe = { "$HOST_NAME never connected: accepts=$accepts " + diagnose() }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                accepts++
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            viewModel.uiState.value.tabs.firstOrNull { it.hostId == profile.id }?.state ==
                SessionConnectionState.CONNECTED
        }
        return profile.id
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. `sendApplyNotifications` publishes state written outside
     * a frame - `connect`, `loginSftp` and `refreshFiles` all write from plain function calls - which is
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

    /** The semantics tree, for a layout assertion that fails: the only useful thing to print. */
    private fun tree(): String = runCatching { compose.onRoot().printToString(maxDepth = 100) }
        .getOrElse { "could not print the tree: $it" }

    private fun diagnose(): String = buildString {
        val viewModel = viewModel()
        append("selected=").append(viewModel.uiState.value.selectedHostId)
        append(" remotePath=").append(viewModel.uiState.value.remotePath)
        append(" remoteFiles=").append(names().size)
        append(" tabs=").append(viewModel.uiState.value.tabs)
        append(" status=").append(viewModel.statusMessage.value)
        append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
        append(" port=").append(serverPort)
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
        const val TEARDOWN_TIMEOUT_MS = 15_000L

        const val LOOPBACK = "127.0.0.1"

        /**
         * Local test credentials, and only local: the server below is bound to loopback inside this JVM,
         * authenticates nothing but this pair, and dies with the process.
         */
        const val USER = "testuser"
        const val PASSWORD = "testpass123"

        const val HOST_NAME = "files-tabs-host"

        /**
         * Enough files to be several screens of them.
         *
         * The scroll test needs a list longer than the pane, or "it scrolled" is unfalsifiable, and it
         * has to stay under `MAX_LISTED_ENTRIES` so the last row is a file rather than the truncation
         * notice. Sixty is comfortably both.
         */
        const val FILE_COUNT = 60

        /**
         * How far above the window's bottom edge the listing is allowed to end.
         *
         * The navigation bar (80dp) plus this screen's own bottom padding, with room to spare: the
         * assertion is that nothing the size of a second listing is stacked underneath, not that the
         * chrome is a particular height.
         */
        const val BOTTOM_SLACK_DP = 140f

        private fun bulkName(index: Int): String = "file-%02d.txt".format(index)

        var serverPort = 0
        private var nextHostId = 0
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val root = Files.createTempDirectory("eclipse-files-tabs")
            repeat(FILE_COUNT) { index ->
                Files.write(root.resolve(bulkName(index)), "row $index\n".toByteArray())
            }
            server = server(root)
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
        }

        /**
         * A started server on a kernel-assigned port with its own host key.
         *
         * Port 0 because Gradle runs the debug and release unit-test tasks in separate JVMs and they
         * overlap: a fixed port means the loser of the bind runs its whole suite against a server that
         * never started.
         */
        private fun server(root: Path): SshServer =
            SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("files-tabs-hostkey").resolve("hostkey.ser"),
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
     * A shell that prints a prompt and reads, which is all these tests need from the SSH half.
     *
     * Present at all because every assertion here is about the file browser while the terminal keeps
     * working: a server with no shell factory would fail to open the pty, the tab would never reach
     * CONNECTED, and the test would be measuring the wrong thing.
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
