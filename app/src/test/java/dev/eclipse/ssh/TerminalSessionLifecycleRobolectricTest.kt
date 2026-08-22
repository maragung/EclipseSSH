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
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import java.io.InputStream
import java.net.InetSocketAddress
import java.io.OutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
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
        liveTransport.set(null)
        windowSize.set(null)
        shellsStarted.set(0)
        serverWrote.set(0)
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
        pumpUntil(describe = { "the drop was never noticed. " + diagnose(hostId) }) {
            tabFor(hostId)?.state != SessionConnectionState.CONNECTED
        }
        // Any of the three honest answers. Which one depends on timing that is not this test's
        // subject - the drop is reported as DISCONNECTED and the scheduled retry then says
        // RECONNECTING - and pinning one of them would make the test fail for being read a
        // millisecond later. CONNECTED is the bug, and it is the only value excluded.
        val state = tabFor(hostId)?.state
        assertWithMessage("a dropped tab must be reconnecting or disconnected, not $state")
            .that(state)
            .isAnyOf(
                SessionConnectionState.RECONNECTING,
                SessionConnectionState.CONNECTING,
                SessionConnectionState.DISCONNECTED,
            )
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
            append(" window=").append(windowSize.get())
            append(" transcript=").append(viewModel.uiState.value.terminalOutput[hostId]?.length)
            append(" frameRows=").append(frame?.lines?.size)
            append(" frameRevision=").append(frame?.revision)
            append("\nframe:\n").append(frame?.text() ?: "no frame")
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

        /** The last window size the remote pty was told about, columns to rows. */
        val windowSize = AtomicReference<Pair<Int, Int>?>(null)

        /** Server-side counters, so a silent terminal can be blamed on the right side of the wire. */
        val shellsStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val serverWrote = java.util.concurrent.atomic.AtomicInteger(0)

        private var nextHostId = 0
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val root = Files.createTempDirectory("eclipse-terminal-lifecycle")
            server = SshServer.setUpDefaultServer()
            server.port = 0
            server.keyPairProvider =
                SimpleGeneratorHostKeyProvider(Files.createTempFile("lifecycle-hostkey", ".ser"))
            server.passwordAuthenticator =
                org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
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

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { this.exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            val out = output ?: return
            val inn = input ?: return
            shellsStarted.incrementAndGet()
            // Kept so a test can drop the transport from the far end, which is the only honest way to
            // stage an outage: no exit status, no goodbye, the socket simply stops being a session.
            liveTransport.set(channel.session)
            recordWindow(env)
            // The pty's size arrives as a signal, exactly as it does on a real host, so what is
            // recorded is what the remote side would actually act on.
            env.addSignalListener({ _, _ -> recordWindow(env) }, Signal.WINCH)
            val greeting = (BANNER + CRLF + PROMPT).toByteArray()
            out.write(greeting)
            out.flush()
            serverWrote.addAndGet(greeting.size)
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
                runCatching { exit?.onExit(0) }
            }.start()
        }

        private fun recordWindow(env: Environment) {
            val columns = env.env[Environment.ENV_COLUMNS]?.toIntOrNull() ?: return
            val rows = env.env[Environment.ENV_LINES]?.toIntOrNull() ?: return
            windowSize.set(columns to rows)
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

        override fun destroy(channel: ChannelSession) = Unit

        private companion object {
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
