package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
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
 * The shape of the Files explorer: what is on it before any folder is granted, and how much of the
 * window the files themselves get.
 *
 * The layout claims are the ones a scrolled, weighted, lazy screen has to keep and an assertion
 * about *content* can never notice. Three, each of which was false of some ancestor of this screen:
 *
 *  - the device's session comes first and is reachable before anything is set up, with an honest
 *    "no folder chosen yet" rather than a lie about an empty folder;
 *  - the listing fills what the chrome leaves, measured against the window rather than asserted by
 *    eye — a pane wrapping its rows passes every "the right files are on screen" test there is;
 *  - scrolling it scrolls the listing, not the page, so the session chips, the count and the batch
 *    bar stay where they were.
 *
 * Most of this runs on a clean install, because a layout bug does not need a socket to reproduce.
 * The tests that need rows to scroll and select run against a real SSH server with a real SFTP
 * subsystem, bound to loopback inside this JVM, authenticating one pair of credentials that exists
 * nowhere else and dying with the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FilesExplorerLayoutRobolectricTest {

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

    // ---------------------------------------------------------------- first run, no folder granted

    /**
     * Before any folder is granted, the explorer's front door is the device's own session, first in
     * the chip row, and it says what is actually wrong: no folder chosen yet, not "this folder is
     * empty".
     *
     * The distinction is the whole assertion. A SAF permission is the one thing this screen cannot
     * grant itself, and an empty-folder message where a permission is missing sends the user looking
     * for files that were never listed. "Pick folder" is on screen because that is the way on from
     * here, and the seeded hosts' chips are on screen because the spec keeps every saved host
     * reachable from here, connected or not.
     */
    @Test
    fun firstRunOffersTheDeviceFirstAndAnHonestEmptyState() {
        openFiles()

        awaitDisplayed("No folder chosen yet")
        compose.onNode(hasText("Pick folder") and hasClickAction()).assertIsDisplayed()

        // Local is first, rendered: its chip sits to the left of the seeded hosts' chips, which is
        // the order a thumb learns, not merely the order a list happens to hold. The seeded chips
        // arrive with the hosts flow, so they are awaited rather than assumed present.
        awaitDisplayed("Production edge")
        compose.onNode(hasText("This device") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Production edge") and hasClickAction()).assertIsDisplayed()
        val deviceLeft = compose.onNode(hasText("This device") and hasClickAction()).getBoundsInRoot().left
        val seededLeft = compose.onNode(hasText("Production edge") and hasClickAction()).getBoundsInRoot().left
        assertThat(deviceLeft.value).isLessThan(seededLeft.value)
    }

    // ---------------------------------------------------------------- with a session open

    /**
     * The listing fills the window under the chrome, measured against the window itself.
     *
     * Measured rather than eyeballed because the bug this guards against is invisible to every
     * content assertion: `assertExists` passes on a pane one row tall. The two numbers are the two
     * halves of "fills it": tall enough that it is not merely wrapping its contents, and ending near
     * the bottom of the window rather than partway down it. Both are deliberately loose - this is a
     * claim about the layout being bounded at all, not about a particular header height, which is
     * free to change.
     */
    @Test
    fun theListingFillsTheWindowRatherThanWrappingItsContents() {
        connect()
        openFiles()
        openTheHostsListing()
        pumpUntil(describe = { "the listing never arrived: " + diagnose() }) {
            names().contains(bulkName(0))
        }

        val window = compose.onRoot().getBoundsInRoot()
        val pane = listing().getBoundsInRoot()
        val windowHeight = window.bottom - window.top
        val paneHeight = pane.bottom - pane.top

        // Wrapping a handful of rows would be well under half the window; half is a bound only a
        // pane that was given the leftover height can clear.
        assertThat(paneHeight.value).isGreaterThan(windowHeight.value / 2)
        // And it reaches the bottom: what is left is the navigation bar and this screen's own
        // padding, not a second pane stacked under it.
        assertThat((window.bottom - pane.bottom).value).isLessThan(BOTTOM_SLACK_DP)
    }

    /**
     * Scrolling a long directory scrolls the directory, and the chrome stays where it is.
     *
     * With the listing inside the page's scroll, reaching the last file meant scrolling the page,
     * which carried the session chips, the count and the crumbs off the top of the screen. Sixty
     * files so the list is several screens long, and the scroll is asked of the listing by index,
     * which only a lazy list that owns its own scrolling can answer.
     */
    @Test
    fun scrollingALongDirectoryLeavesTheChromeInPlace() {
        connect()
        openFiles()
        openTheHostsListing()
        pumpUntil(describe = { "the listing never arrived: " + diagnose() }) {
            names().contains(bulkName(0))
        }

        listing().performScrollToIndex(FILE_COUNT - 1)
        compose.waitForIdle()

        // The far end of the directory is on screen...
        awaitDisplayed(bulkName(FILE_COUNT - 1))
        // ...and so is everything that is not the directory: the chips the rows belong to, and the
        // count that says how many there are.
        compose.onNode(hasText("This device") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText(HOST_NAME) and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("$FILE_COUNT item(s)")).assertIsDisplayed()
        // The first row is above the pane's top now, which is the proof it scrolled at all rather
        // than the whole list having been composed at full length.
        assertThat(nodes(bulkName(0))).isEqualTo(0)
    }

    /**
     * A long-press opens the per-entry action sheet; its Select row starts a selection; and the batch
     * bar that follows offers the actions the session can serve.
     *
     * The bar is what a touch file manager is judged by: it appears on the gesture, counts what the
     * gesture caught, and offers Download and Schedule on a remote listing - the two a server's rows
     * can do - alongside the ones both backends share. The gesture itself changed - long-press used
     * to select directly - so this also pins the new half: the sheet is what the gesture opens, and
     * selection is what its Select row starts.
     */
    @Test
    fun aSelectionBringsTheBatchBarAndItsActions() {
        connect()
        openFiles()
        openTheHostsListing()
        pumpUntil(describe = { "the listing never arrived: " + diagnose() }) {
            names().contains(bulkName(0))
        }

        compose.onNode(hasText(bulkName(0)) and hasClickAction()).performTouchInput { longClick() }
        // The sheet, not the batch bar: the gesture's first consequence is the menu of everything
        // this entry can do.
        awaitDisplayed("Edit")
        clickSheetRow("Select")
        awaitDisplayed("1 selected")
        compose.onNode(hasText("Download") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Schedule") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Delete") and hasClickAction()).assertIsDisplayed()
    }

    /**
     * The sheet's Edit row opens the full-window editor for the file that was long-pressed.
     *
     * This is the reason the sheet exists: before it, the only way to edit a file was to open its
     * preview and find the Edit button there - and only for the preview's text-ish kinds. The
     * assertion is the editor activity starting, which is the whole of the promise; the editor's own
     * behaviour has its own suites.
     */
    @Test
    fun theActionsSheetsEditRowOpensTheEditor() {
        connect()
        openFiles()
        openTheHostsListing()
        pumpUntil(describe = { "the listing never arrived: " + diagnose() }) {
            names().contains(bulkName(0))
        }
        val app = compose.activity.application
        compose.onNode(hasText(bulkName(0)) and hasClickAction()).performTouchInput { longClick() }
        // Drain whatever starts the setup made, so the peek below only ever reports this
        // click's doing — peeking does not consume, so a stale intent would mask the editor's.
        while (runCatching { shadowOf(app).nextStartedActivity }.getOrNull() != null) Unit
        clickSheetRow("Edit")

        // Stage 1: the row's own first act is closing the sheet it lives in, so the sheet
        // leaving the tree is the observable proof that the click ran the app's code rather
        // than stalling in the harness.
        pumpUntil(describe = { "the sheet never closed after its Edit row was tapped: " + diagnose() }) {
            compose.onAllNodes(hasText("Edit") and hasClickAction()).fetchSemanticsNodes().isEmpty()
        }
        // Stage 2: the request the row filed is consumed by a LaunchedEffect keyed on it,
        // which fires on a later frame. Robolectric records every startActivity
        // unconditionally, so a timeout here means the request never reached the effect —
        // and the peeked intent is the honest witness of what did start instead.
        pumpUntil(describe = {
            "the editor activity never started (last start: " +
                runCatching { shadowOf(app).peekNextStartedActivity() }.getOrNull() + "). " + diagnose()
        }) {
            runCatching { shadowOf(app).peekNextStartedActivity() }.getOrNull()
                ?.component?.className == "dev.eclipse.ssh.ui.editor.TextEditorActivity"
        }
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * Clicks a bottom-sheet row by invoking its own OnClick semantics action.
     *
     * A Material3 ModalBottomSheet lives in a Dialog window, and `performClick` delivers a real
     * touch through the window's input dispatcher — which under Robolectric never reaches content
     * inside a dialog window: the node is found, the call returns, and the row's lambda has not run.
     * (The same gesture on the main window — a tab, a file row — arrives fine, which is why only
     * the sheet-driven tests fail.) Invoking the action the touch would have dispatched runs the
     * row's own code with nothing to deliver, so what the test asserts afterwards is about the app
     * rather than about the harness.
     */
    private fun clickSheetRow(label: String) {
        val row = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
        val click = row.config.getOrNull(SemanticsActions.OnClick)?.action
        checkNotNull(click) { "the \"$label\" sheet row has no OnClick action" }
        compose.runOnUiThread { click() }
    }

    private fun names(): List<String> = viewModel().filesExplorer.state.value.entries.map { it.name }

    private fun nodes(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    /**
     * The listing that is on screen: the scrollable holding the seeded rows.
     *
     * Named by its contents rather than by `hasScrollAction()` alone, because the chrome scrolls
     * horizontally and is scrollable too - so "the scrollable" is ambiguous and a height measured
     * from the wrong one would pass for the wrong reason.
     */
    private fun listing() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(bulkName(0))))

    /** [nodes] matches whole text, so the wait does too; the assertion is on display, not on text. */
    /**
     * Points the explorer at this suite's host — it opens on the device's own session, so a remote
     * listing is one chip tap away, never automatic.
     */
    private fun openTheHostsListing() {
        pumpUntil(describe = { "the host's chip never appeared: " + diagnose() }) {
            compose.onAllNodes(hasText(HOST_NAME) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(HOST_NAME) and hasClickAction()).performClick()
    }

    private fun awaitDisplayed(text: String) {
        pumpUntil(describe = { "\"$text\" never appeared" }) { nodes(text) > 0 }
        compose.onNode(hasText(text, substring = true)).assertIsDisplayed()
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
        compose.onNode(hasText("Files") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /**
     * Saves a profile pointing at the local server and connects it, accepting the host key.
     *
     * Auto-login is on, so the SFTP channel the explorer rides is open before the Files tab is
     * visited - the state the listing tests need before they can say anything about layout.
     */
    private fun connect(): String {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "files-layout-" + nextHostId++,
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
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        compose.runOnUiThread { viewModel.connect(saved, password = PASSWORD) }

        var accepts = 0
        pumpUntil(describe = { "$HOST_NAME never connected: accepts=$accepts " + diagnose() }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                accepts++
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            viewModel.uiState.value.tabs.firstOrNull { it.hostId == profile.id }?.state ==
                SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "SFTP never became ready: " + diagnose() }) {
            viewModel.uiState.value.tabs.firstOrNull { it.hostId == profile.id }?.sftpState ==
                SftpSessionState.READY
        }
        return profile.id
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. `sendApplyNotifications` publishes state written outside
     * a frame - `connect` and the explorer's own scope both write from plain function calls - which is
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

    private fun diagnose(): String = buildString {
        val viewModel = viewModel()
        append("explorer=").append(viewModel.filesExplorer.state.value)
        append(" tabs=").append(viewModel.uiState.value.tabs)
        append(" status=").append(viewModel.statusMessage.value)
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

        const val HOST_NAME = "files-layout-host"

        /**
         * Enough files to be several screens of them.
         *
         * The scroll test needs a list longer than the pane, or "it scrolled" is unfalsifiable.
         * Sixty is comfortably that.
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
            val root = Files.createTempDirectory("eclipse-files-layout")
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
                    Files.createTempDirectory("files-layout-hostkey").resolve("hostkey.ser"),
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
