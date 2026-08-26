package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.background.SessionRegistry
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.presentation.MAX_AUTO_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.security.SecureVault
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import dev.eclipse.ssh.ssh.SessionEvent
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import java.io.InputStream
import java.net.InetSocketAddress
import java.io.OutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SshServer
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole session lifecycle, from Connect to a live pty and back out again, against a real SSH
 * server running in this JVM.
 *
 * This is the test that covers the seam nothing else could reach. `SshIntegrationTest` drives
 * `SshConnectionManager` and `TerminalChannel` directly, so it proves the SSH stack works but knows
 * nothing about the view model that owns the buffers or the screen that draws them.
 * `TerminalScreenRobolectricTest` drives the screen, but with no server behind it: its grid is
 * [TerminalFrame.EMPTY] and its keystrokes go nowhere. Everything in between — that authenticating
 * *automatically* attaches a shell, that its output reaches a frame without anything being typed,
 * that a keystroke crosses the wire and its echo comes back, that scrolled-back text stays still,
 * that a resize reaches the remote pty, that the mode a remote program sets changes what an arrow key
 * sends, and that frames stop being built when nothing is drawing them and are current again the
 * moment something is — is only observable with both halves present.
 *
 * The server side is [ScriptedShell] rather than an echo: several of these assertions need a known
 * quantity of output on demand (scrollback), a way to enter application-cursor mode from the remote
 * end (DECCKM), and a record of the exact bytes the app sent. It writes CRLF, as a pty does, so the
 * emulator's output is a rectangle rather than a staircase.
 *
 * All state is asserted on [MainViewModel] rather than on the semantics tree, because the terminal
 * draws to a canvas and its cells are not nodes. The screen's part is still load-bearing and still
 * covered: navigating to Terminal is what subscribes to `frames`, and navigating away is what
 * unsubscribes, which is the mechanism [noFrameIsBuiltWhileTheTerminalIsOffScreen] is about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TerminalSessionLifecycleRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetServerRecording() {
        received.clear()
        flapEveryShell.set(false)
        liveTransport.set(null)
        windowSize.set(null)
        ptyRequests.clear()
        shellsStarted.set(0)
        serverWrote.set(0)
        passwordsAccepted.set(0)
        passwordsRejected.set(0)
    }

    /**
     * Ends every session this test opened, before the next one starts.
     *
     * Robolectric builds a fresh application - and so a fresh Hilt graph, a fresh `SshClient` and a
     * fresh view model - for each test, but a socket is not owned by any of them: the `ClientSession`
     * and its shell channel stay open, the server keeps the `ScriptedShell` and its reader thread alive
     * for each one, and nothing left behind is ever collected because a live thread is a GC root. Ten
     * tests in this class therefore used to end with ten sessions, ten shells and ten client thread
     * pools all resident at once, on a JVM this suite deliberately confines to two cores. That is a
     * leak in the harness rather than in the app - a device gets a new process, not a new application
     * object - but it is a leak that lands on the tests that run last, as handshakes and database
     * writes that take milliseconds in isolation start missing their deadlines. Closing them here is
     * also the only cover [MainViewModel.disconnectAll] has outside the Settings screen.
     */
    @After
    fun endEverySession() {
        runCatching { compose.runOnUiThread { viewModel().disconnectAll() } }
    }

    /**
     * Puts back any preference a test in here changed, even when that test failed.
     *
     * `preferencesDataStore` caches one store per delegate for the whole classloader, so a setting
     * written by one test class is still there for the next one - and one class next door is
     * specifically about what a *fresh install* reads. This is not hypothetical: the flap test below
     * drops the reconnect base to its minimum so five doubling backoffs fit in a test, and the leaked
     * `1` failed `SettingsRepositoryTest`'s pristine-defaults check in a later class, turning one
     * deliberate change into someone else's red test. [MainActivitySecureWindowTest] documents the same
     * trap from the other side.
     *
     * In `@After` rather than a `finally` inside the test, because a failed assertion must not be able
     * to skip it: a test that fails should cost one red test, not two.
     */
    @After
    fun restoreChangedSettings() {
        if (!reconnectBaseChanged) return
        reconnectBaseChanged = false
        runCatching {
            runBlocking {
                SettingsRepository(RuntimeEnvironment.getApplication())
                    .setReconnectBaseSeconds(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
            }
        }
    }

    /** Set by the one test that writes a preference, so [restoreChangedSettings] knows to undo it. */
    private var reconnectBaseChanged = false

    // ---------------------------------------------------------------- the tests

    /**
     * Authenticating attaches the shell by itself, and its output arrives with nothing typed.
     *
     * The headline requirement, and the one the old card interface could not meet: there, a session
     * connected and then sat there until the user filled in a command box. Here the banner and prompt
     * the shell prints on startup are on screen because the pty was opened as part of connecting. The
     * assertion that nothing was typed is what makes that meaningful — an empty command history and an
     * empty recorded input stream together say the output cannot have been provoked.
     */
    @Test
    fun theRemoteShellAttachesItselfAfterAuthenticationAndItsOutputArrivesUntyped() {
        val hostId = connectAndOpenTerminal()

        waitForFrameText(hostId, BANNER)

        val frame = frameFor(hostId)
        assertThat(frame.rows).isGreaterThan(0)
        assertThat(frame.asDrawn()).contains(PROMPT)
        // The cursor is a real cursor, sitting after the prompt rather than at the origin.
        assertThat(frame.cursorRow).isAtLeast(0)
        assertThat(frame.cursorColumn).isEqualTo(PROMPT.length)
        // Nothing was sent, so nothing above can have been a reply to us.
        assertThat(sentText()).isEmpty()
        assertThat(viewModel().uiState.value.commandHistory[hostId].orEmpty()).isEmpty()
    }

    /**
     * A keystroke reaches the remote shell and its echo comes back into the frame.
     *
     * Sent through [MainViewModel.sendText] and [MainViewModel.sendKey] because those are the two the
     * IME bridge and the on-screen key row call; the bytes they produce are asserted exactly in
     * `TerminalKeysTest`, and what is checked here is that they arrive, in order, at a shell that
     * answers. Enter is the interesting half: it is sent as CR, and a server reading lines would see
     * nothing at all if it were LF.
     */
    @Test
    fun typingACommandReachesTheShellAndTheEchoComesBack() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.sendText(hostId, "whoami") }
        compose.runOnUiThread { viewModel.sendKey(hostId, TerminalKey.ENTER) }

        waitForFrameText(hostId, "echo: whoami")
        // Reconstructed from the keystrokes, which is the only way the recent-commands list can know.
        pumpUntil(describe = { "the command was never recorded" }) {
            viewModel.uiState.value.commandHistory[hostId].orEmpty().contains("whoami")
        }
        assertThat(sentText()).contains("whoami")
    }

    /**
     * The searchable transcript catches up with the frame, at its own much slower rate.
     *
     * Two publications, deliberately not one: the frame is what the renderer draws and goes out at
     * frame rate, while the plain text walks every cell of a 2 000-line buffer and would be ruinous at
     * that frequency, so it is throttled to once a second. That makes the transcript *lag*, which is
     * fine and is what this asserts is only a lag — search, Save logs and Save text all read it, and a
     * transcript that settled a second behind and stayed there would quietly lose the last line of
     * every session.
     */
    @Test
    fun theSearchableTranscriptCatchesUpWithTheFrame() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.sendInput(hostId, "transcript\n") }

        waitForFrameText(hostId, "echo: transcript")
        pumpUntil(describe = { "the transcript never caught up with the frame" }) {
            viewModel.uiState.value.terminalOutput[hostId].orEmpty().contains("echo: transcript")
        }
    }

    /**
     * No frame is built while the terminal is not the visible destination, and the first one after it
     * comes back is current.
     *
     * The saving this proves is the whole point of publishing frames on their own flow: on Hosts,
     * Files, Transfers or Settings — and while the app is backgrounded — nothing is drawing a grid, and
     * copying one thirty times a second is pure waste on the devices that can least afford it.
     *
     * What it also proves is that the saving is only of *copies*. The session is not paused with them:
     * the transcript keeps advancing while the terminal is off screen, which is only possible because
     * the collector kept draining the pty into the buffer the whole time. So the frame that appears on
     * the way back is not a replay of what was on screen before — it is built from a buffer that has
     * all the output in it, which is why the last spammed line is in it immediately.
     */
    @Test
    fun noFrameIsBuiltWhileTheTerminalIsOffScreen() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()

        // Out of the shell and back to the list of sessions, which is where the navigation bar is —
        // there is none to tap while a terminal is full screen.
        compose.onNodeWithContentDescription("Show sessions").performClick()
        // Frames are gated on there being a collector, and leaving the terminal disposes the only
        // one. Pumped until the revision stops moving so the gate is provably shut before we spam:
        // a frame already in flight when the screen left composition would otherwise be mistaken for
        // one built afterwards.
        val settled = settledRevision(hostId)

        compose.runOnUiThread { viewModel.sendInput(hostId, "spam 30\n") }
        pumpUntil(describe = { "the shell kept no output while the terminal was off screen" }) {
            viewModel.uiState.value.terminalOutput[hostId].orEmpty().contains("line 30")
        }
        assertThat(frameFor(hostId).revision).isEqualTo(settled)
        assertThat(frameFor(hostId).text()).doesNotContain("line 30")

        // Back into the session from its row in the list, and the frames resume from where the shell
        // actually is rather than from where it was when the screen went away.
        tab(HOST_NAME).performClick()
        waitForFrameText(hostId, "line 30")
        assertThat(frameFor(hostId).revision).isGreaterThan(settled)
    }

    /**
     * Scrolled-back text stays on the same lines while the shell keeps printing underneath it.
     *
     * The offset has to be measured from the bottom, because "zero" must mean "following the output"
     * however long the scrollback grows. The consequence is that every new line shifts the window that
     * offset describes, so without compensation a user reading halfway up a busy log would watch the
     * text crawl away under their eyes. [MainViewModel] adds the growth back on each render; this
     * asserts the sum is exactly zero, by pinning on the absolute line number of the top of the view
     * and on the text that is actually there.
     *
     * The scrollback is built relative to the view's own row count rather than to a fixed number of
     * lines. A terminal that fills the window has as many rows as the screen allows, and a line count
     * that was deeper than the viewport on one screen is shallower than it on a taller one - which
     * leaves nothing to scroll back into and makes the premise of this test, not the behaviour it
     * guards, the thing that fails.
     */
    @Test
    fun aScrolledBackViewStaysStillWhileOutputArrives() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()

        // A screenful plus a margin, so there is provably history above the view to hold still.
        val spammed = frameFor(hostId).rows + SCROLLBACK_MARGIN_LINES
        compose.runOnUiThread { viewModel.sendInput(hostId, "spam $spammed\n") }
        waitForFrameText(hostId, "line $spammed")

        compose.runOnUiThread { viewModel.scrollTerminal(hostId, SCROLL_BACK_LINES) }
        val scrolled = frameFor(hostId)
        assertWithMessage(
            "nothing to scroll back into: firstLine=%s totalLines=%s rows=%s spammed=%s",
            scrolled.firstLine, scrolled.totalLines, scrolled.rows, spammed,
        ).that(scrolled.firstLine).isGreaterThan(0)
        val topLine = scrolled.lines.first().text()
        val linesBefore = scrolled.totalLines

        compose.runOnUiThread { viewModel.sendInput(hostId, "spam $SCROLL_BACK_LINES\n") }
        pumpUntil(describe = { "the buffer never grew" }) { frameFor(hostId).totalLines > linesBefore }

        val after = frameFor(hostId)
        assertThat(after.firstLine).isEqualTo(scrolled.firstLine)
        assertThat(after.lines.first().text()).isEqualTo(topLine)

        // And going live again follows the output, which is the state the offset of zero means.
        compose.runOnUiThread { viewModel.scrollTerminal(hostId, 0) }
        assertThat(frameFor(hostId).asDrawn()).contains(PROMPT)
    }

    /**
     * Resizing the terminal reaches the remote pty, not only the local grid.
     *
     * Both halves matter and they fail independently. A grid that resized without sending
     * `window-change` leaves the remote side drawing to the old width, which is what makes `top` and
     * `vim` wrap into nonsense after a rotation; a `window-change` sent without resizing the buffer
     * puts the wrapping the other way round. The server records what it was told, so this asserts the
     * number that arrived rather than that a call was made.
     *
     * The size asserted first is the one nobody asked for: the screen measures itself and reports the
     * grid that fits, so a session has an honest window from the moment it opens.
     */
    @Test
    fun resizingTheTerminalReachesTheRemotePty() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        pumpUntil(describe = { "the pty was never given a size" }) { windowSize.get() != null }
        val measured = windowSize.get()!!
        assertThat(measured.first).isIn(TERMINAL_COLUMNS_ALLOWED)
        assertThat(measured.second).isIn(TERMINAL_ROWS_ALLOWED)

        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.resizeTerminal(hostId, 40, 12) }

        pumpUntil(describe = { "the remote pty was never told about 40x12, saw ${windowSize.get()}" }) {
            windowSize.get() == 40 to 12
        }
        val frame = frameFor(hostId)
        assertThat(frame.columns).isEqualTo(40)
        assertThat(frame.rows).isEqualTo(12)
        assertThat(frame.lines).hasSize(12)
    }

    /**
     * An arrow key sends what the mode the *remote program* set says it should.
     *
     * `vi`, `less` and everything using ncurses turn on DECCKM, after which an arrow is `ESC O A` and
     * not `ESC [ A`. Sending the wrong one is the classic broken-terminal symptom: the arrow inserts a
     * stray `A` into the file instead of moving the cursor. The mode is therefore read from the
     * emulator at the moment the key is pressed, and this is the test that the reading is wired to the
     * real thing — the sequence that switches it comes from the server, and what is asserted is the
     * bytes that then went back.
     */
    @Test
    fun arrowKeysFollowTheModeTheRemoteProgramSet() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()

        compose.runOnUiThread { viewModel.sendKey(hostId, TerminalKey.ARROW_UP) }
        pumpUntil(describe = { "no arrow key arrived, saw ${sentCodes()}" }) { sentCodes().size >= 3 }
        assertThat(sentCodes()).containsExactly(ESC, BRACKET, UPPER_A).inOrder()

        received.clear()
        compose.runOnUiThread { viewModel.sendInput(hostId, "appmode\n") }
        pumpUntil(describe = { "the remote never put the terminal into application cursor mode" }) {
            frameFor(hostId).applicationCursorKeys
        }

        received.clear()
        compose.runOnUiThread { viewModel.sendKey(hostId, TerminalKey.ARROW_UP) }
        pumpUntil(describe = { "no arrow key arrived, saw ${sentCodes()}" }) { sentCodes().size >= 3 }
        assertThat(sentCodes()).containsExactly(ESC, UPPER_O, UPPER_A).inOrder()
    }

    /**
     * Closing the tab ends the session and takes its frame with it.
     *
     * A frame holds a copy of the visible grid, so one left behind for a host with no session is a
     * leak that grows with every tab the user ever opened. The tab going away is also what the
     * reconnect logic keys on, so a stale entry here is not only memory.
     */
    @Test
    fun closingTheTabEndsTheSessionAndForgetsItsFrame() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()

        val tab = viewModel.uiState.value.tabs.first { it.hostId == hostId }
        compose.runOnUiThread { viewModel.closeTab(tab) }

        pumpUntil(describe = { "the tab or its frame outlived the session" }) {
            viewModel.uiState.value.tabs.none { it.hostId == hostId } &&
                viewModel.frames.value[hostId] == null
        }
        // And the empty state is what is on screen, rather than a terminal with no session behind it.
        pumpUntil(describe = { "the terminal did not fall back to its empty state" }) {
            compose.onAllNodesWithText("No active sessions").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A tab closed as the screen goes away is still taken off the registry, so nothing dials it back.
     *
     * [SessionRegistry] is how [dev.eclipse.ssh.background.EclipseSessionService] knows which hosts are
     * meant to be up: it restores every one it lists, on its own start and whenever the network comes
     * back. So the write that takes a closed host off it is not tidying - it is the only thing standing
     * between "the user closed this session" and the service dialling it again, minutes later, with the
     * tab announcing a reconnect for a session nobody asked for. It is also what forgets that host's
     * stored credential.
     *
     * Every other line of `closeTab` has finished by the time this one has anything to wait for: the
     * write is a DataStore round trip, so it outlives the frame that asked for it by design. Launched on
     * the view model's own scope it was therefore droppable precisely when it mattered - close the last
     * tab and leave, and the scope is cancelled mid-write. Clearing the store here is that race made
     * deterministic: it cancels `viewModelScope` and runs `onCleared` exactly as finishing the activity
     * does, without depending on which of the two wins.
     */
    @Test
    fun closingATabAsTheScreenGoesAwayStillTakesTheHostOffTheRegistry() {
        // A second instance, deliberately: DataStore hands out one per process, so this reads exactly
        // what the view model wrote and what the service would later read.
        val registry = SessionRegistry(RuntimeEnvironment.getApplication(), SecureVault())
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()
        // Without this the assertion below would pass on a host that was never registered at all.
        pumpUntil(describe = { "the live session was never registered as active" }) {
            hostId in runBlocking { registry.activeHostIds.first() }
        }
        val tab = viewModel.uiState.value.tabs.first { it.hostId == hostId }

        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        compose.runOnUiThread { viewModel.closeTab(tab) }

        pumpUntil(
            describe = {
                "a session the user closed is still listed as active, so the service will dial it " +
                    "again: active=${runBlocking { registry.activeHostIds.first() }}"
            },
        ) {
            hostId !in runBlocking { registry.activeHostIds.first() }
        }
        assertWithMessage("the closed session's credential was left at rest in the registry")
            .that(runBlocking { registry.credential(hostId) })
            .isNull()
    }

    /**
     * A session that ends by itself is noticed, said out loud, and does not take its output with it.
     *
     * Nothing used to notice. [dev.eclipse.ssh.ssh.TerminalChannel.output] is a `SharedFlow`, and a
     * `SharedFlow` never completes, so a shell that exited was indistinguishable from a prompt with
     * nobody typing at it: the tab kept saying CONNECTED and every keystroke after that went into a
     * dead stream in silence. Both halves are asserted here - that the shell's parting line survives
     * the teardown, and that the tab then reports the session as over - because a teardown that reports
     * itself promptly by discarding the last output would pass half of this and be worse than the bug.
     */
    @Test
    fun theRemoteShellExitingEndsTheSessionAndTheTabSaysSo() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()

        compose.runOnUiThread { viewModel.sendInput(hostId, BYE + "\n") }

        waitForFrameText(hostId, FAREWELL)
        pumpUntil(describe = { "the tab never noticed the session had ended. " + diagnose(hostId) }) {
            tabFor(hostId)?.state == SessionConnectionState.DISCONNECTED
        }
        assertThat(tabFor(hostId)?.lastError).isNotNull()
        // Still readable and still saveable: ending a session does not throw its scrollback away.
        assertThat(frameFor(hostId).asDrawn()).contains(BANNER)
        pumpUntil(describe = { "the transcript lost the end of the session" }) {
            viewModel.uiState.value.terminalOutput[hostId].orEmpty().contains(FAREWELL)
        }
        // And there is a way back that is not "close the tab and start again from Hosts", which would
        // take the scrollback with it. Reporting the state without offering the remedy is half a fix.
        pumpUntil(describe = { "no way to reconnect the session that just ended" }) {
            compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A shell that closes without reporting a status is reported as itself, and not dialled again.
     *
     * This is the shape of the bug a user hit as "the terminal opens, there is some server text, and
     * then the tab says Reconnecting". Not every remote shell ends by exiting with a status: an account
     * whose login shell is `nologin` prints its line and closes, a `ForceCommand` that finishes closes,
     * a `~/.profile` that fails closes, and an administrator taking the pty away closes. All of those
     * arrive as a channel close on a transport that is still up, with nothing said about why.
     *
     * [dev.eclipse.ssh.ssh.TerminalChannel] used to report every ending as a bare `Int?` exit status,
     * so all of them arrived as `null` - indistinguishable from the socket dying mid-session. That had
     * two consequences, and this test exists for both. The app treated it as an outage and re-dialled
     * it five times, so a host that could only ever answer this way flickered through the whole ladder
     * before settling; and whatever it settled on said "Disconnected from the remote host", which named
     * the one thing that had *not* happened. So the assertions are: the tab says the shell closed, it
     * reaches DISCONNECTED without ever passing through RECONNECTING, and the server never starts a
     * second shell however long the ladder is given to fire.
     *
     * The scrollback is asserted for the same reason [theRemoteShellExitingEndsTheSessionAndTheTabSaysSo]
     * asserts it: on this path the parting line is the *only* diagnostic the user has, and a teardown
     * that reported itself promptly by discarding it would be worse than the bug.
     */
    @Test
    fun aShellThatClosesWithoutSayingWhyIsReportedRatherThanRedialled() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()
        assertWithMessage("shells started by connecting").that(shellsStarted.get()).isEqualTo(1)

        compose.runOnUiThread { viewModel.sendInput(hostId, VANISH + "\n") }

        waitForFrameText(hostId, VANISHED)
        // Sampled per pump as well as held afterwards, because the losing order writes RECONNECTING
        // immediately after DISCONNECTED and a wait that only looked at the end state would read the
        // first of the two and pass.
        val states = linkedSetOf<SessionConnectionState>()
        pumpUntil(describe = { "the tab never noticed the shell had gone. " + diagnose(hostId) }) {
            tabFor(hostId)?.state?.let(states::add)
            tabFor(hostId)?.state == SessionConnectionState.DISCONNECTED
        }

        val reported = tabFor(hostId)?.lastError
        assertWithMessage("what the tab says about an ending it was told nothing about")
            .that(reported).contains("remote shell")
        assertWithMessage("the ending must not be reported as the transport dying")
            .that(reported).doesNotContain("Disconnected from the remote host")
        assertWithMessage("states the tab passed through: $states")
            .that(states).doesNotContain(SessionConnectionState.RECONNECTING)
        holdWhileTheLadderWouldHaveFired(
            describe = { "the ended shell was treated as an outage. " + diagnose(hostId) },
        ) {
            tabFor(hostId)?.state == SessionConnectionState.DISCONNECTED && shellsStarted.get() == 1
        }
        assertThat(frameFor(hostId).asDrawn()).contains(BANNER)
        assertThat(frameFor(hostId).asDrawn()).contains(VANISHED)
    }

    /**
     * A shell killed by a signal says which signal, and is not dialled again either.
     *
     * The realistic member of the family above, and the one most likely to be behind a session that
     * ends seconds after it started: the OOM killer, an administrator's `pkill`, a login session's
     * cgroup being torn down, or sshd's own `ClientAliveCountMax` all end the shell by signal, and a
     * real sshd reports that as `exit-signal` with no exit status at all. MINA's server side has no
     * sender for it - [ScriptedShell] writes the request itself - but the client half being exercised is
     * the real one, and `SIGHUP` here comes off the wire exactly as it would from OpenSSH.
     *
     * Naming the signal is the whole point. "Disconnected from the remote host" sends a user looking at
     * their network; "ended by SIGKILL" sends them to `dmesg`, which is where the answer is.
     *
     * The state is `ERROR` and not `DISCONNECTED` because `SessionEnd.isFault` puts a killed shell on
     * the fault side of that line: `exit 1` is an ordinary thing for a command to do, being killed is
     * not, and the two used to share one amber tab reading "Disconnected". What this test is really
     * about is unchanged either way - the signal is named, and no ladder fires.
     */
    @Test
    fun aShellKilledBySignalNamesTheSignalAndIsNotRedialled() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()

        compose.runOnUiThread { viewModel.sendInput(hostId, KILL + "\n") }

        waitForFrameText(hostId, KILLED)
        pumpUntil(describe = { "the tab never noticed the shell was killed. " + diagnose(hostId) }) {
            tabFor(hostId)?.state == SessionConnectionState.ERROR
        }
        assertWithMessage("what the tab says about a signalled shell")
            .that(tabFor(hostId)?.lastError).contains("SIG" + KILL_SIGNAL)
        holdWhileTheLadderWouldHaveFired(
            describe = { "a signalled shell was treated as an outage. " + diagnose(hostId) },
        ) {
            tabFor(hostId)?.state == SessionConnectionState.ERROR && shellsStarted.get() == 1
        }
    }

    /**
     * Reconnecting keeps the tab connected, keeps its scrollback, and lands the keyboard on the new
     * shell.
     *
     * The interesting part is what must *not* happen. A reconnect closes the outgoing channel on its
     * way in, and the close of a channel is how a session reports that it ended - so a careless version
     * of that report arrives just after the new session was marked CONNECTED and overwrites it, leaving
     * a working shell behind a tab that says it is disconnected. Two things in the ViewModel stop that:
     * the outgoing collector is cancelled before its channel is closed, and the report is conditional
     * on the channel still being the host\'s current one. This test asserts the invariant those two
     * protect rather than the interleaving - the losing order needs the cancelled coroutine to resume
     * on another core inside a window of a few instructions, which a test cannot force - so treat a
     * failure here as serious and a pass as no proof the belt and the braces are both still on.
     */
    @Test
    fun reconnectingKeepsTheTabConnectedAndItsScrollback() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.sendInput(hostId, "before-reconnect\n") }
        waitForFrameText(hostId, "echo: before-reconnect")

        val host = viewModel.uiState.value.hosts.first { it.id == hostId }
        compose.runOnUiThread { viewModel.connect(host, password = PASSWORD) }
        // Waited on through the app, not through `shellsStarted`. The server increments that counter
        // when it starts the shell, which is *before* the client has opened the channel, swapped it in
        // and pointed the keyboard at it - so a test that only waited for the count sent its next line
        // into the previous shell, which is still open at that moment and echoes just as convincingly.
        // The echo then arrived while the reconnect was still handshaking, and the assertion below read
        // the CONNECTING that `connect` had just written. A second banner in the frame can only have
        // come through the new channel's collector, which is started one statement before the tab is
        // marked CONNECTED.
        pumpUntil(describe = { "the second shell never reached the app. " + diagnose(hostId) }) {
            greetingsInFrame(hostId) >= 2 && tabFor(hostId)?.state == SessionConnectionState.CONNECTED
        }

        // Typing has to reach the shell that is there now, not the socket that just closed.
        compose.runOnUiThread { viewModel.sendInput(hostId, "after-reconnect\n") }
        waitForFrameText(hostId, "echo: after-reconnect")
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tabFor(hostId)?.lastError).isNull()
        // And the session before it is still readable: a reconnect is not a clear screen.
        assertThat(frameFor(hostId).asDrawn()).contains("echo: before-reconnect")
    }

    /**
     * The local grid is the same size as the pty the shell was given, on the very first connection.
     *
     * Both halves are required and only one of them was reachable at the moment the size was known.
     * The viewport is measured as soon as the terminal is laid out, which is while the screen still says
     * CONNECTING - before any shell, and so before the buffer that shell's output will be parsed into
     * exists. `resizeTerminal` remembered the size for the pty and had no buffer to apply it to, and the
     * composable reports only when the size it measures *changes*, so it never offered it again: the
     * grid kept its 120x40 construction default for the life of the session while the pty was correctly
     * sized to the screen.
     *
     * The symptom is not wrapped text - the shell wraps first, at the width it was told - it is every
     * program that addresses the screen directly. `vim` and `top` draw the rows the server knows about,
     * and a grid holding more rows than that keeps whatever was under them, with a cursor row that
     * agrees with neither half. Asserted against the size the *server* was asked for, because that is
     * the number the far end will actually draw for.
     */
    @Test
    fun theGridIsSizedToThePtyTheShellWasGiven() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        val opened = ptyRequests.single()
        assertWithMessage("a shell must be opened at some size, or this test proves nothing")
            .that(opened.first).isGreaterThan(0)
        assertWithMessage("the grid kept its default while the pty was sized to the screen")
            .that(frameFor(hostId).let { it.columns to it.rows })
            .isEqualTo(opened)
    }

    /**
     * A reconnect brings the pty back at the size the user was working at, not at the default.
     *
     * The size lives in the view model rather than in the channel, because the channel that knew it is
     * exactly the thing a reconnect throws away. Getting this wrong is invisible until a full-screen
     * program draws: `top` and `vim` come back wrapping at 80 columns inside a 47-column window, and
     * the only clue is that it started after a dropped connection rather than after a rotation.
     *
     * Asserted against the size the *new* pty was requested at, so a `window-change` that happened to
     * follow cannot make a broken open look fixed.
     */
    @Test
    fun aReconnectAsksForThePtySizeTheUserWasWorkingAt() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.resizeTerminal(hostId, 47, 15) }
        pumpUntil(describe = { "the remote pty was never told about 47x15, saw ${windowSize.get()}" }) {
            windowSize.get() == 47 to 15
        }

        // The drop is staged from the server end rather than by asking the app to connect again:
        // `connect` on a session that is still alive *adopts* it, which is the behaviour that keeps a
        // navigation from costing a second login - and adoption reuses the pty, so it could never
        // exercise this. A genuine outage is the only thing that makes the app open a second one.
        checkNotNull(liveTransport.get()) { "the server never recorded its session" }.close(true)

        pumpUntil(describe = { "the session never came back. " + diagnose(hostId) }) {
            tabFor(hostId)?.state == SessionConnectionState.CONNECTED && ptyRequests.size >= 2
        }

        assertWithMessage("pty sizes requested, one per shell")
            .that(ptyRequests)
            .hasSize(2)
        assertThat(ptyRequests.last()).isEqualTo(47 to 15)
        // And the local grid agrees with the remote one, which is the pair that has to match.
        val frame = frameFor(hostId)
        assertThat(frame.columns).isEqualTo(47)
        assertThat(frame.rows).isEqualTo(15)
    }

    /**
     * A session ending must not resize the terminal, because the reason it ended is drawn above it.
     *
     * The test above says the reconnected pty comes back at the user's size; this one says why that was
     * ever in doubt. The status row over the grid carries the ending's own words on a second line and a
     * Why? button that only exists while there is something to explain, so for one release it was one
     * height while a shell was healthy and two rows taller while it was not - and the terminal below it
     * takes what is left. The composable reported the smaller viewport in good faith,
     * [dev.eclipse.ssh.presentation.MainViewModel.resizeTerminal] filed it as the size the user was
     * working at, and the recovered shell opened at a geometry that had only ever existed while the
     * banner was up, then resized again the moment it cleared: two window-changes per outage, and a
     * reconnected `top` drawn for a window that had stopped existing.
     *
     * Asserted as a count of resizes rather than by reading the layout, because the count is the thing
     * with consequences - each one is a `window-change` on the wire and a redraw at the far end - and
     * because it holds the whole way through the outage rather than at one instant of it. An undisturbed
     * session reports its viewport once, when it is first laid out. Nothing after that is the user.
     */
    @Test
    fun aSessionEndingDoesNotResizeTheTerminal() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val settled = ptyResizes()
        // The size the user's own viewport produced, taken from what the server was asked for rather
        // than from the layout: it is the number with consequences, and it is the one a second shell
        // has to reproduce.
        val opened = ptyRequests.single()
        assertWithMessage("the grid and the pty disagreed before anything had gone wrong")
            .that(frameFor(hostId).let { it.columns to it.rows })
            .isEqualTo(opened)

        checkNotNull(liveTransport.get()) { "the server never recorded its session" }.close(true)
        pumpUntil(describe = { "the session never noticed the drop. " + diagnose(hostId) }) {
            tabFor(hostId)?.state != SessionConnectionState.CONNECTED
        }
        // Held across the whole first backoff window, so the frames the reason and the Why? button are
        // on screen for are all measured, not just whichever one the loop above happened to stop on.
        holdWhileTheLadderWouldHaveFired(
            describe = { "the terminal was resized by the session ending, not by the user. " + diagnose(hostId) },
            invariant = { ptyResizes() == settled },
        )

        pumpUntil(describe = { "the session never came back. " + diagnose(hostId) }) {
            tabFor(hostId)?.state == SessionConnectionState.CONNECTED && ptyRequests.size >= 2
        }
        assertWithMessage("the recovered shell was opened at a size the user never chose")
            .that(ptyRequests.last()).isEqualTo(opened)
        assertWithMessage("the grid moved back after the banner cleared, so the outage cost two resizes")
            .that(ptyResizes()).isEqualTo(settled)
    }

    /**
     * A password typed at the prompt and never saved still gets the session back after an outage.
     *
     * The ladder re-dials with no credential of its own - it cannot have one, the drop happens long
     * after the tap that supplied it - so everything it authenticates with has to come from a store.
     * Two exist: the credentials saved against the host profile, which this host deliberately has none
     * of, and the live session's own credential, held encrypted for exactly as long as its tab is open.
     * Consulting only the first meant a user who did not tick "save" got `No more authentication
     * methods available` out of a ten-second outage, after spending one refused login per rung.
     *
     * The counters are the point of the test: the recovery has to be an accepted *login*, not merely a
     * tab that reads CONNECTED, and no rung may have offered a password the server turned down. The
     * credential store is then checked again, because the fix must not have quietly promoted "typed,
     * not saved" to "written to disk" - the resume reads what the session already holds in memory, and
     * persists nothing.
     */
    @Test
    fun aTypedPasswordThatWasNeverSavedStillRecoversTheSessionAfterAnOutage() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        val viewModel = viewModel()
        assertWithMessage("the host must have no saved password, or this test proves nothing")
            .that(viewModel.uiState.value.savedCredentials[hostId]?.hasPassword ?: false)
            .isFalse()
        val loginsBefore = passwordsAccepted.get()

        checkNotNull(liveTransport.get()) { "the server never recorded its session" }.close(true)

        // Waited on through the frame rather than `shellsStarted`, for the reason spelled out in
        // `reconnectingKeepsTheTabConnectedAndItsScrollback`: the server counts a shell before the app
        // has swapped the channel in.
        pumpUntil(describe = { "the session never came back. " + diagnose(hostId) }) {
            greetingsInFrame(hostId) >= 2 && tabFor(hostId)?.state == SessionConnectionState.CONNECTED
        }
        assertWithMessage("the reconnect authenticated instead of arriving with nothing to offer")
            .that(passwordsAccepted.get())
            .isGreaterThan(loginsBefore)
        assertWithMessage("a rung offered a password the server refused")
            .that(passwordsRejected.get())
            .isEqualTo(0)
        assertThat(tabFor(hostId)?.lastError).isNull()

        // The shell that came back is a working one, not merely an open one.
        compose.runOnUiThread { viewModel.sendInput(hostId, "after-outage\n") }
        waitForFrameText(hostId, "echo: after-outage")

        // And nothing was written to the credential store on the way.
        assertThat(viewModel.uiState.value.savedCredentials[hostId]?.hasPassword ?: false).isFalse()
    }

    /**
     * The keyboard is wired to the shell the moment the session is ready, with nothing tapped.
     *
     * The headline complaint this answers is that typing after login went nowhere: the invisible field
     * that couriers keystrokes to the pty exists as soon as the terminal composes, but nothing gave it
     * focus, and a field without focus receives neither IME text nor hardware keys. The terminal drew,
     * the software keyboard was up, and every character was swallowed. Asserting focus rather than
     * "the requester was called" is deliberate - the two came apart in exactly this bug.
     *
     * Then the whole path, end to end and in one test because that is the claim: characters through
     * the IME reach the remote pty, and Enter from the on-screen row executes what they typed.
     */
    @Test
    fun theKeyboardIsWiredToTheShellAsSoonAsTheSessionIsReady() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        val bridge = compose.onNodeWithContentDescription("Terminal input")
        // No tap of any kind between connecting and this line.
        pumpUntil(describe = { "the terminal never took focus. " + diagnose(hostId) }) {
            runCatching { bridge.assertIsFocused() }.isSuccess
        }

        clearRecording()
        bridge.performTextInput("whoami")
        pumpUntil(describe = { "the typed text never reached the pty. sent=" + sentText() }) {
            sentText() == "whoami"
        }
        // And they went *only* to the shell. A terminal does not draw what was typed - the remote pty
        // echoes it, and the echo is what appears - so after everything has settled the screen still
        // shows nothing but the prompt. Asserted because the tempting shortcut, writing the keystroke
        // into the local buffer as well, doubles every character on a real host and only shows up
        // against a server that echoes. This scripted shell answers lines rather than characters, so
        // here the absence is the whole signal.
        settledRevision(hostId)
        assertWithMessage("the app echoed the keystrokes itself instead of leaving it to the pty")
            .that(frameFor(hostId).asDrawn())
            .doesNotContain("whoami")

        cap("ENTER")
        waitForFrameText(hostId, "echo: whoami")
        assertThat(sentCodes().last()).isEqualTo(CARRIAGE_RETURN)
        // And the keyboard is still connected afterwards, so the next command needs no tap either.
        bridge.assertIsFocused()

        // The other route Return takes. Some keyboards do not report it as a key event at all, they
        // commit it as text - and a clipboard suggestion or voice typing puts newlines mid-commit. That
        // has to execute the command exactly as the cap did, which means the newline has to leave as the
        // app's own CR and not as the raw LF it arrived as: `TerminalKeysTest` and
        // `TerminalCommittedTextTest` pin the encoding, and this pins that the committed newline reaches
        // the wire at all rather than being filtered out somewhere in the field.
        clearRecording()
        bridge.performTextInput("uptime\n")
        waitForFrameText(hostId, "echo: uptime")
        assertWithMessage("a committed newline reached the pty as a bare line feed, not the app's Enter")
            .that(sentCodes())
            .doesNotContain(LINE_FEED)
        assertThat(sentCodes().last()).isEqualTo(CARRIAGE_RETURN)
    }

    /**
     * Every key on the on-screen row sends its real bytes to the pty.
     *
     * `TerminalKeysTest` pins what each key encodes to; this pins that pressing the cap encodes it and
     * sends it, which is the difference between a terminal and a picture of one. Asserted as bytes at
     * the server rather than as calls into the view model, because a cap that produced the right call
     * on a channel nobody was reading would pass the second and fail the user.
     *
     * The whole row, not a sample: these are the keys a phone keyboard does not have at all, so each
     * one is the only way to send what it sends, and a cap wired to the wrong constant is invisible
     * until someone needs Page Up in `less`.
     */
    @Test
    fun everyKeyOnTheRowSendsItsBytesToThePty() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)

        // ESC and TAB are single bytes; the rest are the CSI forms, which is what normal cursor mode
        // sends - the scripted shell never asks for DECCKM, and `arrowKeysFollowTheModeTheRemoteProgramSet`
        // covers the mode that changes them.
        assertCapSends("ESC", listOf(ESC))
        assertCapSends("TAB", listOf(HORIZONTAL_TAB))
        assertCapSends("↑", csi(UPPER_A))
        assertCapSends("↓", csi(UPPER_B))
        assertCapSends("→", csi(UPPER_C))
        assertCapSends("←", csi(UPPER_D))
        assertCapSends("HOME", csi(UPPER_H))
        assertCapSends("END", csi(UPPER_F))
        assertCapSends("PGUP", tilde(5))
        assertCapSends("PGDN", tilde(6))
        assertCapSends("DEL", tilde(3))
        // DEL rather than BS: an Android backspace has to erase, and `stty erase` is ^? on every
        // terminal the app will meet.
        assertCapSends("BKSP", listOf(DELETE))
        assertCapSends("ENTER", listOf(CARRIAGE_RETURN))

        // The latch is the other half of the row: Ctrl is not a key that sends anything, it is a state
        // that changes the next one. Ctrl-C is the reason it exists, and 0x03 is the byte that stops a
        // runaway command.
        clearRecording()
        cap("CTRL off")
        compose.onNodeWithContentDescription("Terminal input").performTextInput("c")
        pumpUntil(describe = { "ctrl-c never reached the pty. sent=" + sentCodes() }) {
            sentCodes() == listOf(END_OF_TEXT)
        }
        // And it disarmed itself, so the next character is a plain one rather than another control byte.
        clearRecording()
        compose.onNodeWithContentDescription("Terminal input").performTextInput("c")
        pumpUntil(describe = { "the latch stayed armed. sent=" + sentCodes() }) {
            sentCodes() == listOf('c'.code)
        }
    }

    /**
     * A transport that dies is reported even when a liveness check reached its channel first.
     *
     * Both halves of this were racing, and the loser was always the user. The registry prunes a dead
     * session on *any* liveness check - the notification refresh, the restore pass, the next connect -
     * and pruning used to close the channel with the same call the app uses to close a tab, which
     * marks the end as deliberate; a deliberate end is not reported and not reconnected. So a genuine
     * drop that happened to be noticed by a background check first left a tab that said CONNECTED, a
     * keyboard wired to a dead pty, and nothing scheduled to bring it back. The pruner also removed the
     * channel from the registry, and the handler that reports the death treated a missing entry as
     * proof it had been replaced, so it stayed silent for that reason too.
     *
     * The drop is staged from the server end - the session is closed immediately, with no exit status
     * and no `SSH_MSG_DISCONNECT` - because that is what a phone leaving Wi-Fi looks like to the client.
     */
    @Test
    fun aDroppedTransportIsNeverSilent() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)

        val transport = checkNotNull(liveTransport.get()) { "the server never recorded its session" }
        transport.close(true)

        // Whatever it does about it - reconnect, or say so and offer the button - it is not allowed to
        // go on claiming a connection it no longer has.
        //
        // The state is kept at the moment it is seen rather than read again afterwards. Re-reading was
        // a race in both directions: between the two reads the ladder can fail an attempt and land on
        // ERROR, or - now that a session opened with a typed password can actually be resumed - finish
        // one and be CONNECTED again. Neither says anything about whether the drop was noticed, which
        // is the whole of this test.
        val noticed = AtomicReference<SessionConnectionState?>(null)
        pumpUntil(describe = { "the drop was never noticed. " + diagnose(hostId) }) {
            tabFor(hostId)?.state?.takeIf { it != SessionConnectionState.CONNECTED }?.let(noticed::set)
            noticed.get() != null
        }
        // Any of the three honest answers, because which one arrives is timing this test does not own:
        // a fault with the ladder armed says RECONNECTING straight away, a reconnect already in flight
        // says CONNECTING, and an ending nothing is going to answer says DISCONNECTED. Two states are
        // deliberately *not* accepted: CONNECTED, which is the bug, and ERROR, which would mean the
        // drop was reported as a dead end while a retry was in fact pending - the red flash this used
        // to produce before the ending picked its state with the ladder's answer in hand.
        val state = noticed.get()
        assertWithMessage("a dropped tab must be reconnecting or disconnected, not $state")
            .that(state)
            .isAnyOf(
                SessionConnectionState.RECONNECTING,
                SessionConnectionState.CONNECTING,
                SessionConnectionState.DISCONNECTED,
            )
    }

    /**
     * A session that dies seconds after every login must stop reconnecting, and say what killed it.
     *
     * This is the bug behind every report of *"it keeps reconnecting"*, and it was not the reconnecting
     * that was wrong - it was that the ladder could never finish. The allowance in
     * [MAX_AUTO_RECONNECT_ATTEMPTS] was handed back on every successful attach, so a server that hung
     * up a few seconds after each login reset the counter on each of those logins: attempt 1 of 5,
     * forever. The app dialled for as long as it was open, never reached the end of the ladder, and so
     * never ran the one piece of code that reports *why* - while the tab, whose `lastError` was
     * overwritten with "Reconnecting - attempt 1 of 5" the moment each retry was scheduled, could not
     * show the cause either. A user watching that had nothing to tell anybody except that it keeps
     * reconnecting, which is exactly what we were told.
     *
     * Both halves are asserted, because either one alone leaves the bug reportable:
     *
     *  - it **stops**, at ERROR, after a bounded number of shells - proof the allowance now survives a
     *    flap rather than being refilled by the login at the start of it;
     *  - it **says why**, naming the ending from [dev.eclipse.ssh.ssh.describeSessionEnd] and not only
     *    the app's own retry count, both while retrying and in the message it settles on.
     *
     * The base delay is dropped to its minimum so five doubling backoffs fit in one test. They come due
     * on the looper's virtual clock, which [pumpUntil] advances far faster than wall time, so what this
     * costs in real seconds is the five logins - not the 31 seconds of backoff the app thinks it waited.
     */
    @Test
    fun aFlappingSessionStopsReconnectingAndSaysWhy() {
        val viewModel = viewModel()
        compose.runOnUiThread {
            viewModel.setReconnectBaseSeconds(SettingsRepository.MIN_RECONNECT_BASE_SECONDS)
        }
        reconnectBaseChanged = true
        pumpUntil(describe = { "the reconnect base never took: ${viewModel.uiState.value.settings}" }) {
            viewModel.uiState.value.settings.reconnectBaseSeconds == SettingsRepository.MIN_RECONNECT_BASE_SECONDS
        }

        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)

        // From here every shell greets and then loses its transport, including the one that is up: a
        // flap is not one bad session followed by good ones, it is the same thing happening each time.
        flapEveryShell.set(true)
        checkNotNull(liveTransport.get()) { "the server never recorded its session" }.close(true)

        pumpInStepUntil(
            timeoutMs = FLAP_TIMEOUT_MS,
            describe = { "the ladder never stopped. " + diagnose(hostId) },
        ) {
            // Sampled on the way past, because the reason has to be visible *while* it is retrying and
            // not only at the end - the RECONNECTING line is all a user sees for the whole ladder.
            tabFor(hostId)?.let { tab ->
                if (tab.state == SessionConnectionState.RECONNECTING) tab.lastError?.let(reconnectingSaid::add)
            }
            // Fail on the spot rather than at the deadline. An unbounded ladder dials as fast as the
            // server will answer, and letting it run for the whole budget leaves hundreds of live
            // sessions in a JVM the rest of this suite has to share - so the regression this test exists
            // to catch would be reported as a timeout in some later, innocent test. The number is
            // deliberately loose: the assertions below hold the ladder to its exact allowance, this only
            // decides how much evidence is enough to stop collecting.
            check(shellsStarted.get() <= FLAP_SHELL_CEILING) {
                "the reconnect ladder is unbounded: ${shellsStarted.get()} shells and still going. " +
                    diagnose(hostId)
            }
            tabFor(hostId)?.state == SessionConnectionState.ERROR
        }

        val settled = tabFor(hostId)?.lastError.orEmpty()
        assertWithMessage("the tab must say the ladder is over, not go on counting: $settled")
            .that(settled)
            .contains("gave up after $MAX_AUTO_RECONNECT_ATTEMPTS reconnect attempts")
        assertWithMessage("the give-up message must name the ending, not only the retry count: $settled")
            .that(settled.substringBefore(" · gave up").trim())
            .isNotEmpty()
        // Only the lines that count attempts: "Waiting for a network..." is a different sentence with
        // nothing to add, and this test does not stage an outage for it.
        val counting = reconnectingSaid.filter { it.contains("attempt ") }
        assertWithMessage("the ladder never said it was retrying: $reconnectingSaid")
            .that(counting)
            .isNotEmpty()
        assertWithMessage("a Reconnecting line has to end in the reason, not in the retry count: $counting")
            .that(counting.all { line -> line.substringAfterLast(" · ").let { it.isNotBlank() && !it.startsWith("attempt ") } })
            .isTrue()

        // The count on the far side of the wire is what makes "it stopped" mean it: one shell for the
        // first login, and at most one per attempt of the allowance. Unbounded is what this test exists
        // to catch, and an unbounded ladder runs until the harness gives up rather than stopping here.
        assertWithMessage("the ladder never retried the flapping session")
            .that(shellsStarted.get())
            .isGreaterThan(1)
        assertWithMessage("the ladder outran its allowance")
            .that(shellsStarted.get())
            .isAtMost(1 + MAX_AUTO_RECONNECT_ATTEMPTS)
    }

    /** Every RECONNECTING line the tab showed during the flap, for the assertions above. */
    private val reconnectingSaid: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Closing the tab while a reconnect is waiting cancels it, and nothing dials again.
     *
     * The other half of the ladder: it has to give up when it is *told* to, not only when it runs out
     * of attempts. A user watching a session try to come back and deciding they have had enough taps
     * Disconnect, and the wait already scheduled must die with the tab - otherwise the app reappears a
     * few seconds later holding a session nobody asked for, on a network the user may have chosen to
     * stop using, with no tab on screen to close it again. That is the shape of a reconnect loop that
     * outlives its own UI, and it is worse than the bounded one because there is nothing left to press.
     *
     * The server keeps answering throughout, which is what makes the assertion mean something: if the
     * ladder were still armed it would succeed, and a successful reconnect is visible on the far side
     * of the wire as a second shell. Counting shells rather than watching the tab also rules out the
     * variant where the session comes back and quietly re-registers itself with no tab to show it.
     *
     * Pumped in step with the wall clock up to the tap, because the thing being interrupted is a
     * duration - the first backoff window is seconds, and a flat-out pump would spend all of it inside
     * the wait-for-RECONNECTING loop, leaving nothing to cancel.
     */
    @Test
    fun closingTheTabWhileAReconnectIsPendingCancelsIt() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        assertThat(shellsStarted.get()).isEqualTo(1)

        // One drop, not a flap: `flapEveryShell` stays off, so the next login would succeed.
        checkNotNull(liveTransport.get()) { "the server never recorded its session" }.close(true)
        pumpInStepUntil(
            timeoutMs = SSH_TIMEOUT_MS,
            describe = { "the drop never armed a reconnect. " + diagnose(hostId) },
        ) {
            tabFor(hostId)?.state == SessionConnectionState.RECONNECTING
        }
        // Read before the tap, not assumed to be 1: an attempt that had already begun is legitimate
        // timing, and what this test is about is that no *further* dialling happens after the close.
        val dialled = shellsStarted.get()

        compose.runOnUiThread { viewModel().closeTab(checkNotNull(tabFor(hostId))) }
        compose.waitForIdle()
        assertWithMessage("closing a tab has to remove it").that(tabFor(hostId)).isNull()

        holdWhileTheLadderWouldHaveFired(
            describe = {
                "the reconnect outlived the tab that wanted it: shells=${shellsStarted.get()} " +
                    "(was $dialled), tab=${tabFor(hostId)}"
            },
        ) {
            shellsStarted.get() == dialled && tabFor(hostId) == null
        }
    }

    /**
     * None of the things that merely *look* like a disconnection may cause one.
     *
     * Every trigger here was a way the app used to throw away a perfectly good session, and each is
     * cheap to get wrong because each one legitimately tears down and rebuilds something: a resize
     * rebuilds the grid, the software keyboard rebuilds the layout around it, leaving the screen
     * disposes the frame collector, and being backgrounded stops the whole composition. A connection
     * keyed on any of those - or a `LaunchedEffect` that dials on composition rather than on a request
     * to connect - reconnects when the user rotates the phone, and reconnects *again* when the keyboard
     * opens, which is what "Reconnecting..." a few seconds after login turned out to be.
     *
     * `shellsStarted` is the assertion because it is counted on the far side of the wire: the app
     * cannot reach a shell without a session, so one shell for the whole test means one session, no
     * matter how many times the UI was rebuilt around it. The scripted shell still answers at the end,
     * which is the other half - a session that survived on paper but stopped carrying input would pass
     * a count and fail a user.
     */
    @Test
    fun neitherResizeNorKeyboardNorBackgroundingReconnects() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        assertThat(shellsStarted.get()).isEqualTo(1)
        val viewModel = viewModel()

        // A rotation, and the pair of resizes an IME produces as it opens and closes: the terminal
        // loses height to the keyboard and gets it back.
        listOf(40 to 12, 100 to 30, 100 to 14, 100 to 30).forEach { (columns, rows) ->
            compose.runOnUiThread { viewModel.resizeTerminal(hostId, columns, rows) }
            pumpUntil(describe = { "the pty never heard about ${columns}x$rows, saw ${windowSize.get()}" }) {
                windowSize.get() == columns to rows
            }
        }

        // The keyboard being put away and brought back. Focus is what the IME follows, and losing it
        // must not be read as the session ending - the user is scrolling back through output, not
        // leaving.
        val bridge = compose.onNodeWithContentDescription("Terminal input")
        compose.runOnUiThread { compose.activity.currentFocus?.clearFocus() }
        compose.waitForIdle()
        cap("ENTER")
        pumpUntil(describe = { "the keyboard never came back. " + diagnose(hostId) }) {
            runCatching { bridge.assertIsFocused() }.isSuccess
        }

        // Off the terminal and back, which disposes and rebuilds every collector the screen owns.
        compose.onNodeWithContentDescription("Show sessions").performClick()
        settledRevision(hostId)
        tab(HOST_NAME).performClick()
        waitForFrameText(hostId, PROMPT)

        // And the app being backgrounded and resumed, the strongest of them: the composition is torn
        // down at onStop and built again at onStart.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertWithMessage("something rebuilt the session instead of keeping it")
            .that(shellsStarted.get())
            .isEqualTo(1)
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        // The session did not merely survive on paper: it still carries input and answers.
        clearRecording()
        compose.runOnUiThread { viewModel.sendInput(hostId, "still-here\n") }
        waitForFrameText(hostId, "echo: still-here")
        assertThat(shellsStarted.get()).isEqualTo(1)
    }

    /**
     * The activity being destroyed and rebuilt adopts the session it left behind rather than dialling.
     *
     * The harder half of [neitherResizeNorKeyboardNorBackgroundingReconnects]. That test covers what a
     * rotation actually does to *this* app - the manifest declares `orientation|screenSize|...` in
     * `configChanges`, so turning the phone is a resize and the activity lives - and what backgrounding
     * does, which stops the composition but keeps the activity. Neither destroys the ViewModel.
     *
     * This does. `recreate` is the platform's own teardown: `onDestroy`, a new activity, a new
     * ViewModel with empty maps and no tabs. It is what the system does under memory pressure, what
     * "Don't keep activities" does on every switch away, and what a restore after process death looks
     * like from the app's side. The session itself is in [dev.eclipse.ssh.ssh.SshSessionStore], which
     * is a singleton and outlives all of that, so `MainViewModel.adoptExistingSessions` has to find it
     * on the way up - and until this test there was nothing at this level asserting that it does. The
     * store's half is covered by `SessionStabilityTest`; what only a rebuilt activity can show is that
     * the app *uses* the answer.
     *
     * Four things are asserted, and each fails differently:
     *
     *  - `shellsStarted` stays at 1, counted on the far side of the wire. A rebuilt UI that dialled
     *    again would leave the user with two logins for one tap and one of them orphaned;
     *  - the tab is `CONNECTED`, not `RECONNECTING` - a session adopted but reported as recovering is
     *    the "Reconnecting..." the user sees for no reason;
     *  - the scrollback from before the rebuild is still in the frame. Buffers live in the store next
     *    to the sessions for exactly this, and a ViewModel-local map would pass the other three;
     *  - the shell still answers. A tab restored over a dead channel passes everything above.
     *
     * The terminal is expected back on screen by itself: `openSessionHostId` in `MainActivity` is
     * `rememberSaveable` so the shell the user was in is where they come back to, which is a promise
     * nothing else in the suite holds to account with a live session behind it.
     */
    @Test
    fun anActivityDestroyedAndRebuiltAdoptsItsSessionInsteadOfDiallingAgain() {
        val hostId = connectAndOpenTerminal()
        waitForFrameText(hostId, PROMPT)
        compose.runOnUiThread { viewModel().sendInput(hostId, "before-rebuild\n") }
        waitForFrameText(hostId, "echo: before-rebuild")
        assertThat(shellsStarted.get()).isEqualTo(1)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        pumpUntil(describe = { "the session never came back after the rebuild. " + diagnose(hostId) }) {
            tabFor(hostId)?.state == SessionConnectionState.CONNECTED
        }
        assertWithMessage("the rebuilt activity dialled a second session instead of adopting the one it had")
            .that(shellsStarted.get())
            .isEqualTo(1)
        assertThat(tabFor(hostId)?.lastError).isNull()

        // Back in the shell the user was in, with everything they had already read still there.
        pumpUntil(describe = { "the terminal did not come back on screen. " + diagnose(hostId) }) {
            compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes().isNotEmpty()
        }
        waitForFrameText(hostId, "echo: before-rebuild")

        // And it is the session, not a picture of it.
        clearRecording()
        compose.runOnUiThread { viewModel().sendInput(hostId, "after-rebuild\n") }
        waitForFrameText(hostId, "echo: after-rebuild")
        assertThat(shellsStarted.get()).isEqualTo(1)
    }

    private fun tabFor(hostId: String) = viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    // ---------------------------------------------------------------- the on-screen key row

    /** Presses a cap on the key row, scrolling it into reach the way a thumb would. */
    private fun cap(description: String) {
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * Presses [description] and requires exactly [expected] to arrive at the remote pty.
     *
     * Exactly, and starting from empty: a cap that sent its bytes *and* something else - a stray
     * newline, a repeat, the character the focus change put in the field - would be just as broken as
     * one that sent nothing.
     */
    private fun assertCapSends(description: String, expected: List<Int>) {
        clearRecording()
        cap(description)
        pumpUntil(describe = { "$description sent ${sentCodes()} rather than $expected" }) {
            sentCodes() == expected
        }
        // The row must not steal the keyboard: the next thing typed has to go to the shell, not to
        // whatever the tap focused.
        compose.onNodeWithContentDescription("Terminal input").assertIsFocused()
    }

    private fun clearRecording() = synchronized(received) { received.clear() }

    /** The CSI form of a cursor key: ESC [ <final>. */
    private fun csi(final: Int) = listOf(ESC, BRACKET, final)

    /** The VT220 form of an editing key: ESC [ <number> ~. */
    private fun tilde(number: Int) = listOf(ESC, BRACKET, '0'.code + number, TILDE)

    // ---------------------------------------------------------------- driving the app

    /** The tab, not the identically-titled top app bar; only the tab is clickable. */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun viewModel(): MainViewModel =
        ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Deliberately not `compose.waitUntil`: when composition or measure throws, that reports only
     * "Condition still not satisfied" and the real exception is lost. Pumping by hand lets it
     * propagate. `idleFor` rather than `idle` because the work here is full of real `delay` —
     * `TERMINAL_FRAME_MS` between renders, the reconnect backoff in `connect` — and `idle()` leaves
     * the looper's virtual clock where it was, so those continuations would never come due.
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

    /**
     * [pumpUntil], but with the looper's clock kept in step with the wall clock.
     *
     * Every other wait in this suite wants the opposite. `pumpUntil` advances virtual time as fast as
     * the CPU allows precisely so a `delay` does not cost real seconds - and that is right up to the
     * moment the thing under test is itself a *duration*. The reconnect ladder is one: it hands its
     * allowance back after [dev.eclipse.ssh.presentation.STABLE_SESSION_MS] of uptime, measured with
     * `SystemClock.elapsedRealtime`, which Robolectric drives from this same clock. Pumped flat out, a
     * session that lived 400ms of real time looked to the app as though it had been up for the better
     * part of an hour, so every flap refilled the ladder and the test watched 258 logins go by with the
     * tab still saying CONNECTED - a perfect reproduction of the bug, produced entirely by the harness.
     *
     * A frame of virtual time per frame of real time keeps the two readings of "how long was it up"
     * within a small factor of each other, which is all the ladder's thresholds need. It costs this test
     * the ~31s of backoff the app really waits, and [FLAP_TIMEOUT_MS] is sized for that.
     */
    private fun pumpInStepUntil(timeoutMs: Long, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            Thread.sleep(FRAME_MS)
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * Pumps until the first backoff window could not still be pending, failing if [invariant] breaks.
     *
     * Two clocks have to run out, because the two things being ruled out are on different ones. The
     * ladder's wait is a `delay` on the main dispatcher, so it comes due on Compose's *virtual* clock,
     * and only advancing that past the longest first window the jitter can choose proves the wait was
     * never armed. Dialling is then real work on real sockets, so a second shell would appear on the
     * *wall* clock - and a window measured only in virtual time ends microseconds later, before a
     * connect thread could have got anywhere. Waiting for both is what makes "no second shell" mean it.
     */
    private fun holdWhileTheLadderWouldHaveFired(describe: () -> String, invariant: () -> Boolean) {
        val virtualDeadline = compose.mainClock.currentTime + FIRST_BACKOFF_CEILING_MS
        val wallDeadline = System.nanoTime() + REAL_DIAL_GRACE_NANOS
        while (compose.mainClock.currentTime < virtualDeadline || System.nanoTime() < wallDeadline) {
            assertWithMessage(describe()).that(invariant()).isTrue()
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        assertWithMessage(describe()).that(invariant()).isTrue()
    }

    /**
     * Connects to the in-process server the way the Connect button does and opens the Terminal tab.
     *
     * The host profile is saved through the view model rather than inserted into Room, so the id it
     * connects with is the one the app would use, and the profile carries a stated timeout for the same
     * reason `SshIntegrationTest` does: this suite shares a JVM, and a loopback handshake that takes
     * well under a second in isolation should not be able to fail on a loaded machine.
     *
     * The first connection to this server always raises a host-key challenge, because the server
     * generates a fresh key per run and nothing has ever trusted it. Accepting it is exactly what the
     * dialog's button does, and `acceptHostKey` retries the connection itself — so this is the real
     * first-connection path, not a shortcut around it.
     */
    private fun connectAndOpenTerminal(): String {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "lifecycle-" + nextHostId++,
            name = HOST_NAME,
            host = LOOPBACK,
            username = USER,
            port = serverPort,
            authMethod = AuthMethod.PASSWORD,
            connectTimeoutSeconds = 60,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(
            describe = {
                "the host was never saved: status=${viewModel.statusMessage.value}" +
                    " hosts=${viewModel.uiState.value.hosts.map { it.id }}"
            },
        ) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        compose.runOnUiThread { viewModel.connect(saved, password = PASSWORD) }

        // Every challenge is answered, not only the first. One tap per question is what the dialog
        // does, and pinning the latch to the first one made this helper depend on there being exactly
        // one: a challenge raised again - because the key was never recorded as trusted, or because a
        // retry got there before it was - left the attempt stuck on a question nobody was answering,
        // reported thirty seconds later as a connection that failed with the key it had just accepted.
        pumpUntil(describe = { "the session never connected. " + diagnose(saved.id) }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            viewModel.uiState.value.tabs.any {
                it.hostId == saved.id && it.state == SessionConnectionState.CONNECTED
            }
        }
        // No navigation tap: a session that has just connected opens its own shell full screen, which
        // is what the app does after a login. Waited on by the IME host, the one thing only the
        // terminal composes.
        pumpUntil(describe = { "the shell never took the window. " + diagnose(saved.id) }) {
            compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes().isNotEmpty()
        }
        return saved.id
    }

    /** How many shell greetings the frame holds - one per shell that has attached to this tab. */
    private fun greetingsInFrame(hostId: String): Int =
        viewModel().frames.value[hostId]?.asDrawn()?.split(BANNER)?.let { it.size - 1 } ?: 0

    private fun frameFor(hostId: String): TerminalFrame =
        checkNotNull(viewModel().frames.value[hostId]) { "no frame has been published for $hostId" }

    private fun waitForFrameText(hostId: String, text: String) = pumpUntil(
        describe = { "\"$text\" never reached the frame. " + diagnose(hostId) },
        condition = { viewModel().frames.value[hostId]?.asDrawn()?.contains(text) == true },
    )

    /** Everything worth knowing when the terminal is not showing what it should. */
    /**
     * How many times the app has told the engine the terminal changed size, read from its own trace.
     *
     * The trace rather than a spy, because [dev.eclipse.ssh.ssh.SessionEvent.PTY_RESIZED] is recorded
     * only when the size actually differs from the one already remembered - which is the definition the
     * assertion wants, and one a counter wrapped around the call would get wrong.
     */
    private fun ptyResizes(): Int =
        viewModel().uiState.value.diagnostics.count { it.event == SessionEvent.PTY_RESIZED }

    private fun diagnose(hostId: String): String {
        val viewModel = viewModel()
        val frame = viewModel.frames.value[hostId]
        return buildString {
            append("tabs=").append(viewModel.uiState.value.tabs)
            append(" status=").append(viewModel.statusMessage.value)
            append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
            append(" knownHosts=").append(viewModel.uiState.value.knownHosts)
            append(" shellsStarted=").append(shellsStarted.get())
            append(" serverWrote=").append(serverWrote.get())
            append(" appSent=").append(sentBytes().size)
            // Whether a login was even attempted, and whether the server turned one down. A resume
            // that arrives with nothing to authenticate with produces neither, which is a different
            // fault from offering the wrong password and is otherwise indistinguishable from it in a
            // timeout message - see [passwordsAccepted].
            append(" logins=").append(passwordsAccepted.get()).append("+/").append(passwordsRejected.get()).append("-")
            append(" window=").append(windowSize.get())
            append(" transcript=").append(viewModel.uiState.value.terminalOutput[hostId]?.length)
            append(" frameRows=").append(frame?.lines?.size)
            append(" frameRevision=").append(frame?.revision)
            // Whether anything is *collecting* frames, which decides whether any get built at all:
            // `publishTerminalFrame` skips the whole render while nothing is drawing, and the terminal
            // screen's subscription is lifecycle-bound. A stale revision-0 frame next to a transcript
            // that kept growing means this went to zero and never came back, which is a different
            // fault from output that never arrived - and telling them apart from the message is the
            // point of printing it.
            // Read through the mutable type the view model exposes read-only, because the count
            // lives on `MutableSharedFlow` and this is the same object.
            append(" frameCollectors=").append((viewModel.frames as? MutableStateFlow<*>)?.subscriptionCount?.value)
            append("\nframe:\n").append(frame?.text() ?: "no frame")
            // The app's own account of what it did, which is the only thing that can tell a ladder that
            // never ran from one that ran with no credential: CREDENTIAL_NOT_STORED says the vault
            // refused the live session's password at install, and the RECONNECT_* lines say which rung
            // reached the wire. Printed only on the way to a failure, and safe to print - every field
            // is scrubbed of secrets, which [SessionDiagnosticsTest] asserts.
            append("\ntrace:\n").append(viewModel.exportDiagnostics())
        }
    }

    /**
     * The frame revision once it has stopped moving, for asserting that nothing built another one.
     *
     * Needed because leaving the destination does not stop a render that is already in flight: the
     * collector may be between `frame()` and `update`, and that frame is legitimately from before the
     * gate shut. Waiting for two quiet passes distinguishes it from one built afterwards.
     */
    private fun settledRevision(hostId: String): Long {
        var last = viewModel().frames.value[hostId]?.revision ?: -1L
        var stableSince = System.nanoTime()
        pumpUntil(describe = { "frames never stopped being published" }) {
            val current = viewModel().frames.value[hostId]?.revision ?: -1L
            if (current != last) {
                last = current
                stableSince = System.nanoTime()
            }
            System.nanoTime() - stableSince >= QUIET_NANOS
        }
        return last
    }

    /** Every byte the app has sent since the last reset, as a printable string. */
    private fun sentText(): String = String(sentBytes(), Charsets.UTF_8)

    private fun sentCodes(): List<Int> = sentBytes().map { it.toInt() and 0xFF }

    private fun sentBytes(): ByteArray = synchronized(received) { received.toByteArray() }

    private fun TerminalFrame.text(): String = lines.joinToString(separator = "\n") { it.text() }

    /**
     * The frame with its trailing blanks left on, for matching text that ends in a space.
     *
     * [text] trims each row, which is what makes a diagnostic dump readable and what makes it useless
     * for looking for a shell prompt: `"$ "` is a dollar and a space, and the space is the last thing
     * on the row. Searching the trimmed rendering for it can never succeed no matter how correct the
     * terminal is.
     */
    private fun TerminalFrame.asDrawn(): String =
        lines.joinToString(separator = "\n") { line -> line.joinToString(separator = "") { it.value.toString() } }

    private fun List<dev.eclipse.ssh.terminal.TerminalCell>.text(): String =
        joinToString(separator = "") { it.value.toString() }.trimEnd()

    private companion object {
        /**
         * Whatever the kernel hands out, read back after the bind.
         *
         * It used to be a fixed 2324, chosen so it would not collide with `SshIntegrationTest`'s 2323
         * inside the JVM the two suites share. That reasoning only ever covered one JVM: Gradle runs
         * the debug and release unit-test tasks as separate processes, and on a clean build they
         * overlapped - so the second process to start found the port taken, its server never came up,
         * and both suites failed on connections that could not have succeeded. Nothing about either
         * test needs a known port, and a port nobody else can be holding is the only kind that is
         * safe to assume.
         */
        var serverPort = 0
        const val LOOPBACK = "127.0.0.1"
        const val USER = "testuser"
        const val PASSWORD = "testpass123"
        const val HOST_NAME = "lifecycle"

        /** What the shell prints on startup, before anything could have been typed at it. */
        const val BANNER = "eclipse-scripted-shell"
        const val PROMPT = "$ "

        /** The directive that makes the scripted shell leave, and the line it leaves on. */
        const val BYE = "bye"
        const val FAREWELL = "logout-now"

        /**
         * The directive that makes the shell close its channel without reporting an exit status, and
         * the line it leaves on. What a login shell replaced by `nologin`, a `ForceCommand` that ends,
         * or a pty torn down by an administrator looks like on the wire: data, then a channel close,
         * with the transport still perfectly alive and nothing said about why.
         */
        const val VANISH = "vanish"
        const val VANISHED = "closing-without-a-status"

        /** The directive that makes the shell die from a signal, the signal it dies from, and its line. */
        const val KILL = "kill"
        const val KILLED = "about-to-be-signalled"
        const val KILL_SIGNAL = "HUP"

        const val ESC = 0x1B
        const val BRACKET = 0x5B
        const val UPPER_A = 0x41
        const val UPPER_O = 0x4F
        const val UPPER_B = 0x42
        const val UPPER_C = 0x43
        const val UPPER_D = 0x44
        const val UPPER_F = 0x46
        const val UPPER_H = 0x48
        const val TILDE = 0x7E
        const val HORIZONTAL_TAB = 0x09
        const val CARRIAGE_RETURN = 0x0D
        const val LINE_FEED = 0x0A
        const val DELETE = 0x7F

        /** Ctrl-C, the byte that interrupts a running command. */
        const val END_OF_TEXT = 0x03

        /**
         * How long the revision has to hold still to count as settled.
         *
         * Measured in wall-clock time rather than in pump iterations, because the pump advances a
         * *virtual* clock and spins through iterations in microseconds, while the collector that
         * publishes frames runs on `Dispatchers.Default` against real `delay`. Counting iterations
         * would declare the gate shut before a render in flight had landed. Fifteen render periods.
         */
        const val QUIET_NANOS = 500_000_000L

        /**
         * How long a wait on the SSH stack is given, as opposed to one on the app's own state.
         *
         * Longer than the 30s default because this suite shares two cores with a real SSH server, a
         * real Room database and a real DataStore, and every profile it saves states a 60s connect
         * budget of its own for the same reason. Under load a loopback handshake that takes
         * milliseconds in isolation can take seconds, and a harness deadline shorter than the app's own
         * timeout can only ever report the harness running out of patience - never what the app did.
         * Nothing asserted here is relaxed by it: a broken connection still fails, it just fails saying
         * something true.
         */
        const val SSH_TIMEOUT_MS = 90_000L

        /**
         * The longest first reconnect wait the ladder can choose: the 5s base doubled no times, plus
         * the half-window of jitter that is added on top of it.
         */
        const val FIRST_BACKOFF_CEILING_MS = 7_500L

        /** Long enough for a real re-dial to have shown up on the server, if one were coming. */
        const val REAL_DIAL_GRACE_NANOS = 1_000_000_000L

        /**
         * Budget for the whole five-attempt flap ladder.
         *
         * Longer than [SSH_TIMEOUT_MS] because this one waits on five real logins rather than one, and
         * the box it runs on is shared. The backoffs themselves are virtual and nearly free.
         */
        const val FLAP_TIMEOUT_MS = 240_000L

        /** One frame, as both clocks count it, for [pumpInStepUntil]. */
        const val FRAME_MS = 16L

        /**
         * Shells after which a flapping ladder is unbounded beyond argument, and this test stops.
         *
         * Twice the allowance plus its first login: comfortably above anything a correct ladder can do,
         * far below the hundreds an unbounded one reaches, and low enough that a regression leaves the
         * shared JVM with a dozen dead sessions rather than a few hundred live ones.
         */
        const val FLAP_SHELL_CEILING = 2 * (1 + MAX_AUTO_RECONNECT_ATTEMPTS)

        /** Lines of history above the view, so scrolling back provably has somewhere to go. */
        const val SCROLLBACK_MARGIN_LINES = 40

        /** How far back the view is scrolled while the shell keeps printing underneath it. */
        const val SCROLL_BACK_LINES = 20

        val TERMINAL_COLUMNS_ALLOWED = dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
        val TERMINAL_ROWS_ALLOWED = dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE

        /** Every byte the app has written to the shell, in order. Guarded by its own monitor. */
        val received: MutableList<Byte> = Collections.synchronizedList(mutableListOf())

        /** The server's side of the newest session, for the test that takes the network away. */
        val liveTransport = AtomicReference<org.apache.sshd.common.session.Session?>(null)

        /**
         * When set, every shell greets its client and then loses its transport a moment later.
         *
         * This is the server half of a *flap*, and it is the shape of the bug users kept reporting as
         * "it keeps reconnecting": the login works, the banner arrives, and the session is gone again
         * seconds later - a `ClientAliveInterval` the client is failing to satisfy, a middlebox
         * dropping the flow, an `sshd` being restarted in a loop. One drop is
         * [aDroppedTransportIsNeverSilent]; this is the same drop happening to every session the
         * reconnect ladder manages to establish. See [aFlappingSessionStopsReconnectingAndSaysWhy].
         */
        val flapEveryShell = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * How long a flapping shell stays up before its transport goes.
         *
         * Long enough that the client has finished attaching - a transport that dies *during* the
         * handshake is a connect failure and travels a different, already-covered path - and short
         * enough that five of them plus their backoffs fit inside one test.
         */
        const val FLAP_UPTIME_MS = 400L

        /** The last window size the remote pty was told about, columns to rows. */
        val windowSize = AtomicReference<Pair<Int, Int>?>(null)

        /**
         * The size each pty was *created* at, one entry per shell, oldest first.
         *
         * Separate from [windowSize] on purpose: a `window-change` arriving after the shell started
         * would overwrite that, so it cannot answer what size a reconnect asked its new pty for.
         */
        val ptyRequests: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

        /** Server-side counters, so a silent terminal can be blamed on the right side of the wire. */
        val shellsStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val serverWrote = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Password logins the server accepted, and ones it turned down.
         *
         * A reconnect that reaches `CONNECTED` says nothing about *how*; these two say whether the app
         * had a credential to offer at all. A ladder with none produces no accepted login and no
         * rejected one either - it never gets as far as an attempt - which is a different failure from
         * offering the wrong password, and the two are worth telling apart.
         */
        val passwordsAccepted = java.util.concurrent.atomic.AtomicInteger(0)
        val passwordsRejected = java.util.concurrent.atomic.AtomicInteger(0)

        private var nextHostId = 0
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            // `AndroidKeyStore` does not exist on the JVM, and the vault behind `SessionRegistry` asks
            // the platform for its key on first use. Without somewhere for that request to land, every
            // write of a live session's credential fails - silently, because a session that is up is not
            // failed over a vault error - and the reconnect ladder in this suite then has nothing to
            // authenticate with, which is a property of the harness rather than of the app. See
            // [StandInAndroidKeyStore] for what is real about it and what is not.
            StandInAndroidKeyStore.install()

            val root = Files.createTempDirectory("eclipse-terminal-lifecycle")
            server = SshServer.setUpDefaultServer()
            server.port = 0
            server.keyPairProvider =
                SimpleGeneratorHostKeyProvider(Files.createTempFile("lifecycle-hostkey", ".ser"))
            server.passwordAuthenticator =
                org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
                    val ok = user == USER && password == PASSWORD
                    (if (ok) passwordsAccepted else passwordsRejected).incrementAndGet()
                    ok
                }
            server.subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
            server.fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
            server.shellFactory = ShellFactory { ScriptedShell() }
            server.start()
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
            // Gradle runs many test classes in one JVM; a provider left registered would quietly change
            // how the next one behaves.
            StandInAndroidKeyStore.uninstall()
        }
    }

    /**
     * A shell that does only what these tests need to observe, and records what it was sent.
     *
     * Line-oriented but read a byte at a time, because the recording has to be of raw bytes: an arrow
     * key is three of them and none is part of any line. Writes CRLF, as a pty does — bare LF would
     * leave the cursor's column where it was and render 60 lines of output as a staircase, which is
     * correct emulation of an incorrect server and would make every text assertion here about
     * whitespace.
     *
     * Directives:
     *  - `spam N` writes N numbered lines, for building a scrollback of a known size;
     *  - `appmode` turns on DECCKM from the remote end, the way `less` does;
     *  - anything else is echoed.
     */
    private class ScriptedShell : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null

        /** This shell's channel, for the endings that have to be written onto the wire by hand. */
        @Volatile private var channel: ChannelSession? = null

        /**
         * Set once this shell has closed its own channel, so the reader thread does not then report an
         * exit status the ending is defined by *not* having.
         */
        @Volatile private var handRolledEnding = false

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { this.exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            val out = output ?: return
            val inn = input ?: return
            shellsStarted.incrementAndGet()
            this.channel = channel
            // Kept so a test can drop the transport from the far end, which is the only honest way to
            // stage an outage: no exit status, no goodbye, the socket simply stops being a session.
            liveTransport.set(channel.session)
            sizeOf(env)?.let { ptyRequests += it }
            recordWindow(env)
            // The pty's size arrives as a signal, exactly as it does on a real host, so what is
            // recorded is what the remote side would actually act on.
            env.addSignalListener({ _, _ -> recordWindow(env) }, Signal.WINCH)
            val greeting = (BANNER + CRLF + PROMPT).toByteArray()
            out.write(greeting)
            out.flush()
            serverWrote.addAndGet(greeting.size)
            if (flapEveryShell.get()) {
                // The greeting first, then the drop, because "some server text and then Reconnecting"
                // is the report this reproduces. `close(true)` on the *session* rather than the
                // channel: no exit status and no SSH_MSG_DISCONNECT, which is what a link that stops
                // forwarding looks like from the client - and the one ending the ladder answers.
                Thread {
                    runCatching {
                        Thread.sleep(FLAP_UPTIME_MS)
                        channel.session.close(true)
                    }
                }.also { it.isDaemon = true }.start()
                return
            }
            Thread {
                runCatching {
                    val line = StringBuilder()
                    var state = TEXT
                    var running = true
                    while (running) {
                        val byte = inn.read()
                        if (byte < 0) break
                        received += byte.toByte()
                        // Escape sequences are consumed rather than filed as typed text, because that
                        // is what a line editor does with them: pressing Up sends ESC [ A and readline
                        // acts on it, it does not leave three characters in front of the next command.
                        // Without this the arrow key asserted earlier in a test was still sitting in
                        // the line buffer when the next command was typed, and the shell saw both.
                        state = when {
                            state == CSI -> if (byte in FINAL_BYTES) TEXT else CSI
                            state == SS3 -> TEXT
                            state == ESCAPED -> when (byte) {
                                BRACKET -> CSI
                                SS3_INTRODUCER -> SS3
                                else -> TEXT
                            }
                            byte == ESCAPE -> ESCAPED
                            byte == CARRIAGE_RETURN || byte == LINE_FEED -> {
                                running = respond(out, line.toString())
                                line.setLength(0)
                                TEXT
                            }
                            else -> {
                                line.append(byte.toChar())
                                TEXT
                            }
                        }
                    }
                }
                if (!handRolledEnding) runCatching { exit?.onExit(0) }
            }.start()
        }

        private fun recordWindow(env: Environment) {
            sizeOf(env)?.let { windowSize.set(it) }
        }

        private fun sizeOf(env: Environment): Pair<Int, Int>? {
            val columns = env.env[Environment.ENV_COLUMNS]?.toIntOrNull() ?: return null
            val rows = env.env[Environment.ENV_LINES]?.toIntOrNull() ?: return null
            return columns to rows
        }

        /** Answers [command], reporting whether the shell should stay up afterwards. */
        private fun respond(out: OutputStream, command: String): Boolean {
            when {
                // What `exit` does: a parting line and then the channel goes away. The farewell is
                // written deliberately, because output that arrives in the same breath as the close is
                // the output a teardown is most likely to drop.
                command == BYE -> {
                    out.write((FAREWELL + CRLF).toByteArray())
                    out.flush()
                    return false
                }
                // The two endings MINA's server side cannot produce on its own. `ChannelSession` only
                // ever sends `exit-status`, and only from `onExit` - so an ending that has no status,
                // or that has a signal instead, has to be written as the protocol messages a real sshd
                // would send. Both are ordinary channel messages on the same session, queued behind the
                // parting line, so the far end sees the text and then the ending, in that order.
                command == VANISH -> {
                    out.write((VANISHED + CRLF).toByteArray())
                    out.flush()
                    handRolledEnding = true
                    closeChannelByHand()
                    return false
                }
                command == KILL -> {
                    out.write((KILLED + CRLF).toByteArray())
                    out.flush()
                    handRolledEnding = true
                    // RFC 4254 6.10: the signal name carries no SIG prefix, and a dying process never
                    // asks for a reply.
                    sendRaw(SshConstants.SSH_MSG_CHANNEL_REQUEST) { buffer ->
                        buffer.putString("exit-signal")
                        buffer.putBoolean(false)
                        buffer.putString(KILL_SIGNAL)
                        buffer.putBoolean(false)
                        buffer.putString("Killed by signal $KILL_SIGNAL.")
                        buffer.putString("")
                    }
                    closeChannelByHand()
                    return false
                }
                command.startsWith("spam ") -> {
                    val count = command.removePrefix("spam ").trim().toIntOrNull() ?: 0
                    repeat(count) { index -> out.write(("line ${index + 1}" + CRLF).toByteArray()) }
                }
                command == "appmode" -> out.write(APPLICATION_CURSOR_KEYS.toByteArray())
                else -> out.write(("echo: $command" + CRLF).toByteArray())
            }
            out.write(PROMPT.toByteArray())
            out.flush()
            return true
        }

        /** EOF then CLOSE, which is how a server says a channel is over and says nothing else. */
        private fun closeChannelByHand() {
            sendRaw(SshConstants.SSH_MSG_CHANNEL_EOF)
            sendRaw(SshConstants.SSH_MSG_CHANNEL_CLOSE)
        }

        /** Writes one channel message for this channel's far end, as the server's own code would. */
        private fun sendRaw(command: Byte, fill: (Buffer) -> Unit = {}) {
            val channel = this.channel ?: return
            runCatching {
                val session = channel.session
                val buffer = session.createBuffer(command, RAW_BUFFER_ESTIMATE)
                buffer.putUInt(channel.recipient)
                fill(buffer)
                session.writePacket(buffer)
            }
        }

        override fun destroy(channel: ChannelSession) = Unit

        private companion object {
            /** Room for a recipient id and a short request; the buffer grows if a caller needs more. */
            const val RAW_BUFFER_ESTIMATE = 64

            const val CARRIAGE_RETURN = 0x0D
            const val LINE_FEED = 0x0A
            const val ESCAPE = 0x1B
            const val BRACKET = 0x5B

            /** `O`, which introduces the SS3 form of an arrow key. */
            const val SS3_INTRODUCER = 0x4F

            /** What ends a CSI sequence, per ECMA-48. */
            val FINAL_BYTES = 0x40..0x7E

            /** States of the little input parser in [start]: plain text, ESC seen, CSI, SS3. */
            const val TEXT = 0
            const val ESCAPED = 1
            const val CSI = 2
            const val SS3 = 3
            val CRLF = "" + Char(CARRIAGE_RETURN) + Char(LINE_FEED)

            /** `ESC [ ? 1 h` — DECCKM, written by code point rather than as a literal escape. */
            val APPLICATION_CURSOR_KEYS = Char(0x1B) + "[?1h"
        }
    }
}
