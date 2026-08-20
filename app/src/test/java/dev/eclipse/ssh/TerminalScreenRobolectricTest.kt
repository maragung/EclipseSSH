package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.presentation.MainViewModel
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The terminal destination with a session tab open — the state the app enters the instant Connect is
 * tapped.
 *
 * Every other UI test only ever reaches the Terminal destination with no session open, where it gets
 * `TerminalSessionsScreen` and its "No active sessions" empty state. That left the whole terminal
 * unexercised, and it is where the crash-on-Connect lived: the shell hosts every *other* destination in
 * a `Column(Modifier.verticalScroll(...))`, which measures its content with an infinite maximum height,
 * and a nested vertically scrollable child measured that way throws `IllegalStateException` out of
 * `checkScrollableContainerConstraints`. No amount of navigation testing finds that while `tabs` stays
 * empty.
 *
 * The terminal now composes *outside* that scrolling column — outside the shell's `Scaffold`
 * altogether, with the system bars hidden — which is what lets it fill the window, and which makes
 * [theTranscriptToggleKeepsTheTerminalRendering] the standing regression test for the crash, because
 * the transcript branch is a `verticalScroll` and would throw again the moment the terminal were put
 * back inside the shell's column.
 *
 * The tab is opened through [MainViewModel.connect] rather than by poking state, because that is the
 * path the Connect button takes and its synchronous half — marking the tab CONNECTING — is what puts
 * the terminal on screen. The handshake itself runs on `Dispatchers.IO` and fails on a background
 * thread; that outcome is the subject of [aRefusedConnectionSettlesTheTabOnDisconnectedWithoutCrashing]
 * and deliberately not asserted by the rendering tests, which must hold in every connection state.
 *
 * What these tests can and cannot reach is worth stating, because the gap is deliberate. There is no
 * remote host here, so no pty, no buffer and no frame: the rendered grid is [TerminalFrame.EMPTY] and
 * keystrokes go nowhere. Anything about *bytes* — the escape sequences a key sends, what the emulator
 * does with them, the decoding across chunk boundaries — is covered where it can be asserted exactly,
 * in `TerminalKeysTest`, `AnsiTerminalBufferTest`, `Utf8StreamDecoderTest` and the live-server
 * `SshIntegrationTest`. What is only observable here is the part that needs a real window: that the
 * full-screen layout measures, that every control the card interface had is still reachable, that the
 * latched modifiers behave like latches, and that input reaches the session the user selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TerminalScreenRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * A clickable row or chip carrying [label] — a session chip in the strip, a session card in the
     * list, or a navigation destination — as opposed to an identically-titled app bar or heading, which
     * is not clickable.
     */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /**
     * The activity's [MainViewModel].
     *
     * `hiltViewModel()` inside the composable resolves the nearest `ViewModelStoreOwner`, which is the
     * activity, under the default provider key — so this is the instance the UI is collecting, not a
     * second one whose `WhileSubscribed` state would never leave its initial value.
     */
    private fun viewModel(): MainViewModel =
        ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe] if it
     * never does.
     *
     * Deliberately not `compose.waitUntil`, and this is the whole reason the crash it now covers was
     * so hard to see: when composition or measure throws, `waitUntil` reports nothing but
     * "Condition still not satisfied after 10000 ms" and the real `IllegalStateException` is lost.
     * Pumping by hand lets the exception propagate out of the frame, so a layout crash arrives as a
     * layout crash. `sendApplyNotifications` publishes state written outside a frame — `connect`
     * writes the tab from a plain function call — which is what invalidates the recomposer at all.
     */
    private fun pumpUntil(timeoutMs: Long = 10_000, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            // idleFor, not idle: `idle()` runs only what is already due and leaves the looper's
            // virtual clock where it was, so anything waiting on `delay()` — the reconnect backoff in
            // MainViewModel.connect, for one — never comes due and the pump spins until it times out.
            // Advancing a frame's worth of virtual time per turn runs those continuations on the same
            // schedule a real device would.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    private fun waitForText(text: String) = pumpUntil(
        describe = { "\"$text\" never appeared" },
        condition = { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() },
    )

    private fun waitForDescription(description: String) = pumpUntil(
        describe = { "nothing described as \"$description\" appeared" },
        condition = {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        },
    )

    /**
     * Opens a session tab the way the Connect button does, and returns the host it opened it for.
     *
     * Waits for Room's seeding first: `hosts` is empty on the first frame, and connecting to a host
     * that does not exist yet would prove nothing.
     */
    private fun openSessionTab(): String {
        val viewModel = viewModel()
        pumpUntil(describe = { "no hosts were seeded" }) { viewModel.uiState.value.hosts.isNotEmpty() }
        val host = viewModel.uiState.value.hosts.first()
        compose.runOnUiThread { viewModel.connect(host) }
        pumpUntil(describe = { "connect(${host.id}) opened no tab" }) {
            viewModel.uiState.value.tabs.isNotEmpty()
        }
        return host.name
    }

    /**
     * Opens a tab and waits for its shell to take the window, which is what tapping Connect does.
     *
     * Deliberately no navigation tap any more: a new session opens its own shell full screen, and the
     * navigation bar this used to be tapped on is not on screen while one is open. Waited on by the IME
     * host rather than by the host name, because the name is also in the session list — only the
     * terminal has an input bridge.
     */
    private fun openTerminalWithSession(): String {
        compose.waitForIdle()
        val hostName = openSessionTab()
        waitForDescription("Terminal input")
        waitForText(hostName)
        return hostName
    }

    /** Opens the tab strip's overflow menu, where everything that is not typing now lives. */
    private fun openTerminalMenu() {
        compose.onNodeWithContentDescription("Terminal actions").performClick()
        waitForText("Snippets")
    }

    private fun tapMenuItem(label: String) {
        openTerminalMenu()
        compose.onNodeWithText(label).performClick()
        compose.mainClock.advanceTimeByFrame()
        Snapshot.sendApplyNotifications()
    }

    /**
     * The terminal renders full-screen with a session open — the regression test for the crash on
     * Connect, and for the screen being a terminal rather than a form.
     *
     * Asserts the three things that make it one: the session's own identity in the tab strip, the live
     * grid (which is the only thing in the column with a weight, so it is what fills the window), and
     * the modifier row, which on a touch device is the sole way to produce Ctrl, Esc or Page Up at all.
     * Not the connection status text: that depends on how far the doomed handshake has got by the time
     * the frame is drawn, whereas these hold in every connection state.
     *
     * There is deliberately no assertion for a command input. The card interface required one and this
     * screen does not have one by default — that absence *is* the feature, so asserting it stays
     * absent is what stops the form coming back.
     */
    @Test
    fun theTerminalRendersFullScreenWithASessionTabOpen() {
        val hostName = openTerminalWithSession()

        compose.onNodeWithText(hostName).assertIsDisplayed()
        // The on-screen keys, which are the terminal's own input surface.
        compose.onNodeWithContentDescription("CTRL off").assertIsDisplayed()
        compose.onNodeWithText("ESC").assertIsDisplayed()
        // The IME host: one transparent pixel that must be in the tree whenever the terminal is,
        // because an InputConnection cannot be established for a view that is not composed.
        compose.onNodeWithContentDescription("Terminal input").assertExists()
        // No command form unless it is asked for.
        assertThat(compose.onAllNodesWithText("Type a command…").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * Every action the card interface had is still reachable.
     *
     * This is the test that would fail if a future change to the terminal quietly dropped a feature
     * instead of relocating it. The card exposed these as visible buttons; the full-screen terminal
     * moved them into the overflow menu to free the window, and moving them is only acceptable while
     * all of them are still there.
     */
    @Test
    fun theOverflowMenuStillOffersEveryActionTheCardInterfaceHad() {
        openTerminalWithSession()
        openTerminalMenu()

        listOf(
            "Show command bar",
            "Transcript",
            "Snippets",
            "Paste",
            "Copy all output",
            "Jump to live output",
            "Save logs",
            "Save text",
            "Save screen",
            "Disconnect all",
        ).forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
    }

    /**
     * The transcript toggle swaps the terminal body to the plain-text renderer and back.
     *
     * Two reasons this matters more than it looks. The transcript is the branch that can be selected
     * with the platform's own text handles and read by a screen reader, so it is a real feature, not a
     * debug view. And it is a `verticalScroll`: if the full-screen terminal were ever moved back inside
     * the shell's scrolling column, measuring this branch would throw out of
     * `checkScrollableContainerConstraints` — which is exactly the crash-on-Connect this suite exists
     * for. Toggling in both directions measures both layouts.
     */
    @Test
    fun theTranscriptToggleKeepsTheTerminalRendering() {
        val hostName = openTerminalWithSession()

        tapMenuItem("Transcript")
        waitForText("Waiting for remote shell…")
        compose.onNodeWithText(hostName).assertIsDisplayed()

        tapMenuItem("Live terminal")
        waitForDescription("CTRL off")
        compose.onNodeWithText(hostName).assertIsDisplayed()
    }

    /**
     * The in-terminal search field opens and counts matches without disturbing the session.
     *
     * Search adds a text field above a terminal whose height is whatever is left, so it is the layout
     * most likely to be squeezed by a future change to those constraints.
     */
    @Test
    fun theTerminalSearchFieldOpensOverASession() {
        val hostName = openTerminalWithSession()

        // `Modifier.clickable` merges descendant semantics, so the tappable Surface carries the
        // Icon's description — one node with both the description and the click action.
        compose.onNodeWithContentDescription("Search terminal").performClick()
        waitForText("Search terminal output")

        compose.onNodeWithText("Search terminal output").assertIsDisplayed()
        compose.onNodeWithText(hostName).assertIsDisplayed()
    }

    /**
     * The whole on-screen key row is tappable, including the keys that only exist here.
     *
     * With no channel open every one of these is a no-op on the wire, and that is the point: the
     * assertion is that none of them throws. A key row is built from a table, a table is where a wrong
     * entry hides, and a `TerminalKey` the UI can name but the encoder cannot handle would crash on
     * tap rather than at build time.
     */
    @Test
    fun everyOnScreenKeyIsTappable() {
        openTerminalWithSession()

        listOf("ESC", "TAB", "←", "↓", "↑", "→", "HOME", "END", "PGUP", "PGDN", "DEL", "BKSP", "ENTER")
            .forEach { label ->
                // performScrollTo: the row scrolls horizontally and thirteen keys plus three latches do
                // not fit across a 411dp phone. Being off screen is what a scroller is for; being
                // unreachable by scrolling would be the bug.
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
            }

        // Still rendering after all of them.
        compose.onNodeWithContentDescription("CTRL off").assertExists()
    }

    /**
     * A latched modifier arms, announces itself, and disarms when the next key consumes it.
     *
     * Latching rather than holding is forced by the hardware: a phone has one finger available for the
     * terminal, so Ctrl-C cannot be two simultaneous presses. That makes the armed state a mode, and an
     * unannounced mode is unusable with a screen reader — the description is the only way to discover
     * it, so it is asserted rather than the colour.
     */
    @Test
    fun aLatchedModifierArmsAnnouncesItselfAndIsConsumedByTheNextKey() {
        openTerminalWithSession()

        compose.onNodeWithContentDescription("CTRL off").performScrollTo().performClick()
        waitForDescription("CTRL on")

        // Consumed by the key it modifies, not left armed for the rest of the session.
        compose.onNodeWithText("ESC").performScrollTo().performClick()
        waitForDescription("CTRL off")

        // Tapping it twice is a way back out for someone who armed it by accident.
        compose.onNodeWithContentDescription("CTRL off").performScrollTo().performClick()
        waitForDescription("CTRL on")
        compose.onNodeWithContentDescription("CTRL on").performScrollTo().performClick()
        waitForDescription("CTRL off")

        // Three independent latches, not one shared flag.
        compose.onNodeWithContentDescription("ALT off").performScrollTo().performClick()
        waitForDescription("ALT on")
        compose.onNodeWithContentDescription("CTRL off").assertExists()
        compose.onNodeWithContentDescription("SHIFT off").assertExists()
    }

    /**
     * Closing the only tab returns the terminal to its empty state rather than leaving `activeTabHostId`
     * pointing at a tab that no longer exists.
     */
    @Test
    fun closingTheLastTabFallsBackToTheEmptyState() {
        openTerminalWithSession()

        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.closeTab(viewModel.uiState.value.tabs.first()) }
        waitForText("No active sessions")

        compose.onNodeWithText("No active sessions").assertIsDisplayed()
        assertThat(viewModel.uiState.value.tabs).isEmpty()
    }

    /**
     * Two sessions render side by side, and the tab strip does what a tab strip must: keep its order,
     * select on tap, close only on the close button, and route input to whichever session is selected.
     *
     * Each of those was broken. `updateTab` rebuilt the list as "everything else, then the updated
     * one", so a tab moved to the end of the strip every time its state changed — with two handshakes
     * in flight the strip visibly reshuffled. And the close button was a 26dp `IconButton`, whose touch
     * target Material expands to 48dp regardless: on a chip whose title was short the expanded target
     * covered the label, so tapping a tab to switch to it closed the session instead.
     *
     * Routing is asserted through the optional command bar because it is the only input path with an
     * observable effect while no pty exists: `onSendInput` records into the app's own command history
     * for the *active* host. Keystrokes from the IME bridge go straight to a channel that is not there.
     */
    @Test
    fun twoSessionsOpenAsSeparateTabsAndBothStayOpen() {
        compose.waitForIdle()
        val viewModel = viewModel()
        pumpUntil(describe = { "fewer than two hosts were seeded" }) {
            viewModel.uiState.value.hosts.size >= 2
        }
        val hosts = viewModel.uiState.value.hosts.take(2)
        compose.runOnUiThread { hosts.forEach { viewModel.connect(it) } }
        pumpUntil(describe = { "expected two tabs, got ${viewModel.uiState.value.tabs.size}" }) {
            viewModel.uiState.value.tabs.size == 2
        }

        // The second connection took the window by itself; both chips are in its strip.
        waitForDescription("Terminal input")
        waitForText(hosts[0].name)
        waitForText(hosts[1].name)

        // inOrder, and asserted here rather than at the end: by now both doomed handshakes have moved
        // their tabs through CONNECTING and RECONNECTING, which is exactly what used to reorder them.
        assertThat(viewModel.uiState.value.tabs.map(SessionTab::hostId))
            .containsExactlyElementsIn(hosts.map { it.id }).inOrder()

        // performScrollTo, not a bare assertIsDisplayed: the tab strip scrolls horizontally and two
        // host names plus the two icon buttons do not fit across a 411dp phone.
        compose.onNodeWithText(hosts[1].name).performScrollTo().performClick()
        compose.mainClock.advanceTimeByFrame()
        assertThat(viewModel.uiState.value.tabs).hasSize(2)

        // Selecting a tab has to actually redirect the terminal, which is only observable through
        // where the input goes — `onSendInput` is called with the *active* tab's host id.
        tapMenuItem("Show command bar")
        waitForText("Type a command…")
        // Not a bare hasSetTextAction(): the IME bridge is a text field too, and it is the one that
        // must not receive this. Excluding it by its own label picks the command bar unambiguously.
        compose.onNode(hasSetTextAction() and hasContentDescription("Terminal input").not())
            .performTextInput("whoami")
        waitForText("whoami")
        // No performScrollTo: the full-screen terminal has no scrolling ancestor any more, which is
        // precisely why it can fill the window. The command bar sits at the bottom of a Column whose
        // grid takes the remaining height, so it is on screen by construction.
        compose.onNodeWithText("Send").performClick()
        pumpUntil(describe = { "input did not reach ${hosts[1].id}: ${viewModel.uiState.value.commandHistory}" }) {
            viewModel.uiState.value.commandHistory[hosts[1].id].orEmpty().contains("whoami")
        }
        assertThat(viewModel.uiState.value.commandHistory[hosts[0].id].orEmpty()).doesNotContain("whoami")

        // The close button is reachable by its own screen-reader label and closes only its own tab.
        compose.onNodeWithContentDescription("Close ${hosts[0].name} session").performScrollTo().performClick()
        pumpUntil(describe = { "closing one tab left ${viewModel.uiState.value.tabs.size}" }) {
            viewModel.uiState.value.tabs.size == 1
        }
        assertThat(viewModel.uiState.value.tabs.single().hostId).isEqualTo(hosts[1].id)
    }

    /**
     * A session that has just opened owns the whole window: no top bar, no navigation bar, and no other
     * destination on screen to compete with the grid.
     *
     * This is the shape the screen exists for — after a login the app is a terminal, the way PuTTY and
     * JuiceSSH are — and it is asserted by absence, because absence is what full screen means here: the
     * chrome is gone, not merely small. The navigation bar's own labels are the probe, since "Transfers"
     * and "Files" appear nowhere else in the app.
     */
    @Test
    fun aNewSessionTakesTheWholeWindowWithNoAppChromeAroundIt() {
        openTerminalWithSession()

        compose.onNodeWithContentDescription("Terminal input").assertExists()
        compose.onNodeWithContentDescription("CTRL off").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Transfers").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Files").fetchSemanticsNodes()).isEmpty()
        // The way out has to be visible, because nothing else is.
        compose.onNodeWithContentDescription("Show sessions").assertIsDisplayed()
    }

    /**
     * Back leaves the shell for the list of sessions and brings the app's navigation back with it.
     *
     * Both halves matter. A full-screen terminal whose navigation bar is hidden is a dead end unless
     * back means "leave the session", and a back press that left the chrome hidden would be worse than
     * not handling back at all. The session is not closed on the way out — it is still running, and the
     * list is what says so.
     */
    @Test
    fun backLeavesTheShellForTheSessionListAndRestoresTheNavigation() {
        val hostName = openTerminalWithSession()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("1 active session")

        compose.onNodeWithText("Transfers").assertExists()
        compose.onNodeWithText(hostName).assertExists()
        assertThat(viewModel().uiState.value.tabs).hasSize(1)
        // Nothing is rendering a terminal while the list is up, which is what keeps the frame gate shut.
        assertThat(compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes()).isEmpty()

        // And the row is the way back in.
        tab(hostName).performClick()
        waitForDescription("Terminal input")
    }

    /**
     * The Terminal destination lists every live session, and the row that is tapped is the shell that
     * opens.
     *
     * A phone holds several sessions at once, so the list is the app's answer to "what am I connected
     * to" — and the answer is worth nothing if the row does not lead to that session. Routing is
     * asserted the only way it is observable with no pty behind either session: `onSendInput` records
     * into the app's own command history for the *active* host, so what the command bar sends is what
     * says which shell is on screen.
     */
    @Test
    fun theSessionListShowsEverySessionAndOpensTheOneTapped() {
        compose.waitForIdle()
        val viewModel = viewModel()
        pumpUntil(describe = { "fewer than two hosts were seeded" }) {
            viewModel.uiState.value.hosts.size >= 2
        }
        val hosts = viewModel.uiState.value.hosts.take(2)
        compose.runOnUiThread { hosts.forEach { viewModel.connect(it) } }
        waitForDescription("Terminal input")

        // Out of the shell the last connection opened, and into the list.
        compose.onNodeWithContentDescription("Show sessions").performClick()
        waitForText("2 active sessions")
        hosts.forEach { compose.onNodeWithText(it.name).assertExists() }

        // The first host's row — not the session the app opened by itself.
        tab(hosts[0].name).performClick()
        waitForDescription("Terminal input")
        tapMenuItem("Show command bar")
        waitForText("Type a command…")
        compose.onNode(hasSetTextAction() and hasContentDescription("Terminal input").not())
            .performTextInput("id")
        compose.onNodeWithText("Send").performClick()
        pumpUntil(describe = { "input did not reach ${hosts[0].id}: ${viewModel.uiState.value.commandHistory}" }) {
            viewModel.uiState.value.commandHistory[hosts[0].id].orEmpty().contains("id")
        }
        assertThat(viewModel.uiState.value.commandHistory[hosts[1].id].orEmpty()).doesNotContain("id")
    }

    /**
     * A failed connection is reported in the tab rather than thrown.
     *
     * Deliberately aimed at `127.0.0.1:1` rather than at the seeded `edge.example.com`. A refused
     * connection on the loopback interface fails immediately and identically everywhere, whereas an
     * unresolvable name depends on the DNS the machine happens to have — on a sandboxed host the
     * lookup blocks for minutes, which made this test time out while the tab was still RECONNECTING
     * and told us nothing about the app.
     *
     * The tab must survive the failure: a disconnected session the user can read the error off is the
     * intended behaviour, and dropping the tab would hide the failure entirely.
     */
    @Test
    fun aRefusedConnectionSettlesTheTabOnDisconnectedWithoutCrashing() {
        compose.waitForIdle()
        val viewModel = viewModel()
        val unreachable = HostProfile(
            id = "refused-host",
            name = "Refused",
            host = "127.0.0.1",
            port = 1,
            username = "nobody",
        )
        compose.runOnUiThread {
            viewModel.saveHost(unreachable)
            viewModel.connect(unreachable)
        }

        pumpUntil(
            timeoutMs = 60_000,
            describe = { "tab never settled: ${viewModel.uiState.value.tabs.firstOrNull()?.state}" },
        ) {
            viewModel.uiState.value.tabs.firstOrNull { it.hostId == unreachable.id }?.state ==
                SessionConnectionState.DISCONNECTED
        }

        val settled = viewModel.uiState.value.tabs.single { it.hostId == unreachable.id }
        assertThat(settled.state).isEqualTo(SessionConnectionState.DISCONNECTED)
        assertThat(settled.lastError).isNotNull()

        // The failure is surfaced in the terminal, not swallowed. The status line under the tab strip
        // carries the reason, which is the only thing distinguishing a shell waiting for input from a
        // session that never opened. No navigation tap: connecting opened this shell full screen, and
        // a session that failed to connect stays open on screen precisely so the reason can be read.
        waitForText("Refused")
        compose.onNodeWithText("Refused").performScrollTo().assertIsDisplayed()
        pumpUntil(describe = { "the error was never shown in the terminal" }) {
            compose.onAllNodesWithText(settled.lastError!!, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
