package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.ssh.SessionEvent
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.sessions.SessionWhyActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The session "why?" window, as a window.
 *
 * It used to be a `ModalBottomSheet` at the root of MainActivity, opened from a "Why?" button in the
 * tab strip or on a session row. What has to hold is unchanged — the session's own name in the bar,
 * the heading worded from its state, the reason it recorded — and it is asserted here because the
 * surface moved: the sheet read the trace out of a list the workspace had already filtered and handed
 * down, and the window reads the ring itself.
 *
 * That difference is the point of promoting this surface, and it is the one thing only this level can
 * prove: the trace is *not* carried in the subject. It is collected from
 * [dev.eclipse.ssh.ssh.SessionDiagnostics] while the window is open, so a reconnect ladder that climbs
 * a rung while somebody is reading the window has to show up in it. A snapshot would pass every other
 * test here.
 *
 * The subject is the other half: a [SessionTab] that cannot be parcelled travels through the same
 * one-shot token handoff the editor and the previews use, so a spent token has to close the window
 * rather than open it on whatever the ring happens to hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SessionWhyActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun tab(
        hostId: String = "host-why",
        title: String = "Production edge",
        state: SessionConnectionState = SessionConnectionState.ERROR,
        lastError: String? = "Connection refused by the server",
    ) = SessionTab(
        id = "tab-why",
        hostId = hostId,
        title = title,
        state = state,
        lastError = lastError,
    )

    /**
     * The window opens on the session its subject names, in that session's own name.
     *
     * The bar's title is the whole of what a token can get wrong in a way nothing else here would
     * catch: the window is opened from a tab strip where several sessions are listed, so "Why?" on
     * its own would leave the user guessing which of them is being explained.
     */
    @Test
    fun theWindowOpensOnTheSessionItsSubjectNames() {
        val tab = tab(title = "Staging cluster")
        launchWindow(tab).use {
            awaitText("Staging cluster", describe = { "the window never opened on its session" })
            compose.onNodeWithText("Staging cluster").assertIsDisplayed()
            compose.onNodeWithText("Why it ended").assertIsDisplayed()
        }
    }

    /**
     * The heading is worded from the state rather than fixed, because three different questions arrive
     * at this one window: a session that gave up, one still climbing its reconnect ladder, and one
     * waiting on something.
     */
    @Test
    fun theHeadingIsWordedFromTheSessionsOwnState() {
        launchWindow(tab(state = SessionConnectionState.RECONNECTING)).use {
            awaitText("Why it is reconnecting", describe = { "a reconnecting session was explained as something else" })
        }
        launchWindow(tab(hostId = "host-busy", title = "Busy edge", state = SessionConnectionState.AUTHENTICATING)).use {
            awaitText("What it is waiting for", describe = { "a session mid-authentication was explained as something else" })
        }
    }

    /** A session that recorded no reason says so, rather than showing an empty space. */
    @Test
    fun aSessionWithNoRecordedReasonSaysSo() {
        launchWindow(tab(lastError = null)).use {
            awaitText(
                "No reason was recorded for this session.",
                describe = { "a session with no recorded reason explained nothing at all" },
            )
        }
    }

    /**
     * The trace keeps arriving while the window is open — the reason this surface is a window at all.
     *
     * An empty ring is asserted first, so what the second half proves is that the *same* window
     * picked up an event recorded after it was already on screen. A window that read the ring once
     * would leave the first sentence standing forever, and the moment somebody opens this is exactly
     * when the ladder is still climbing.
     */
    @Test
    fun anEventRecordedWhileTheWindowIsOpenReachesIt() {
        val tab = tab(hostId = "host-live", title = "Live edge")
        launchWindow(tab).use { scenario ->
            awaitText(
                "Nothing has been recorded for this session yet.",
                describe = { "an empty trace was not reported as empty" },
            )

            scenario.onActivity { activity ->
                activity.diagnostics.record(
                    tab.hostId,
                    SessionEvent.CONNECT_FAILED,
                    state = SessionConnectionState.ERROR,
                    detail = "Connection refused",
                )
            }

            awaitText(
                "This session's last 1 event(s)",
                describe = { "an event recorded after the window opened never reached it" },
            )
        }
    }

    /**
     * A token that is already spent closes the window instead of opening it.
     *
     * The system re-delivers the intent on a rotation, from the recents screen, and after a crash and
     * relaunch — and the subject is a snapshot of a session the user may have closed since. A window
     * that reopened on it would be an explanation of something that is no longer happening, and for a
     * session that had been closed, of something that is no longer there.
     */
    @Test
    fun aSpentTokenClosesTheWindowInsteadOfReopeningIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tab = tab()
        // The token is minted and then thrown away, which is exactly the state a re-delivered intent
        // finds: the first window's launch spent it.
        val spent = ActionRequests.put(ActionSubject.SessionWhy(tab))
        ActionRequests.take(spent)

        ActivityScenario.launch<SessionWhyActivity>(spentTokenIntent(context, spent)).use { scenario ->
            pumpUntil(describe = { "a spent token opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    /** And an intent carrying no token at all opens nothing, rather than the newest trace in the ring. */
    @Test
    fun anIntentWithNoTokenOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<SessionWhyActivity>(Intent(context, SessionWhyActivity::class.java)).use { scenario ->
            pumpUntil(describe = { "an intent with no subject token opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    /**
     * The copy row is on screen, and it is the window's own rather than a handoff.
     *
     * What it writes is the clipboard's business and the clipboard has its own suite; what is pinned
     * here is that the window offers it at all — the sheet's copy row is what a user reaches for when
     * they are about to file a bug report, and losing it in the promotion would be silent.
     */
    @Test
    fun theCopyRowIsOffered() {
        launchWindow(tab()).use {
            awaitText("Copy", describe = { "the copy row never appeared on the window" })
            assertThat(compose.onAllNodes(hasText("Copy") and hasClickAction()).fetchSemanticsNodes()).isNotEmpty()
        }
    }

    // ---------------------------------------------------------------- driving the app

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun launchWindow(tab: SessionTab): ActivityScenario<SessionWhyActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch(SessionWhyActivity.intent(context, tab))
    }

    /** The same intent with the token written in by hand, which is all the window reads. */
    private fun spentTokenIntent(context: Context, token: String): Intent =
        Intent(context, SessionWhyActivity::class.java).putExtra(ActionRequests.EXTRA_SUBJECT_TOKEN, token)

    private fun awaitText(text: String, describe: () -> String) {
        pumpUntil(describe = describe) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * The shape the suites that drive live state all share — see
     * `FilesExplorerLayoutRobolectricTest.pumpUntil` for why it is this and not `compose.waitUntil`.
     */
    private fun pumpUntil(timeoutMs: Long = TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
