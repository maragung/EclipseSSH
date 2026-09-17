package dev.eclipse.ssh

import android.app.Activity
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.ClipboardClearActivity
import dev.eclipse.ssh.ui.settings.KeepAliveActivity
import dev.eclipse.ssh.ui.settings.ReconnectDelayActivity
import dev.eclipse.ssh.ui.settings.TerminalFontSizeActivity
import dev.eclipse.ssh.ui.settings.TerminalWidthActivity
import dev.eclipse.ssh.ui.settings.VaultAutoLockActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The six Settings rows that open a window of their own, and where each one leads.
 *
 * These six settings were `AlertDialog`s over Settings and are screens now, and the promotion changes
 * what a test at this level can honestly claim: under Robolectric a `startActivity` is *recorded*,
 * not performed, so an activity opened from a MainActivity-driven test never composes. The split that
 * follows is the one [SettingsAboutRobolectricTest] makes for About — this class asserts the wiring
 * (each row starts its own screen, and no dialog is shown over Settings to do it), and
 * [ChoiceActivitiesRobolectricTest] asserts each screen's own contents, in its own window, where a
 * real composition exists.
 *
 * `qualifiers` pins the same phone width as the rest of the suite: the workspace shell branches at
 * `maxWidth >= 700.dp`, and the Settings list this drives is the narrow layout's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsChoiceRowsRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The navigation tab. Only the tab is clickable; a destination's name renders nowhere else. */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** A few frames of work, for the recompositions that follow a click. */
    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor` rather than
     * `idle` so real `delay`s in the Room and DataStore flows come due.
     */
    private fun pumpUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /** Opens Settings, the destination all six rows below live in. */
    private fun openSettings() {
        compose.waitForIdle()
        tab("Settings").performClick()
        pump()
    }

    /**
     * Scrolls the Settings row titled [title] into view and taps the button on it.
     *
     * The button is found by the content description it carries, which is the row's own title, and not
     * by the label it shows. Its label is "Change" on every row in this class — and a seventh button on
     * the screen says "Change" too whenever a PIN is set, so the count is not even fixed — which makes
     * the label a phrase a screen reader user hears six times over with nothing to tell the rows apart.
     * That is what the description is for, and it is there for the screen reader first: the codebase
     * pairs a control with the row it belongs to wherever a bare label would be ambiguous, the way the
     * theme picker's description carries the value it sets and the About row's carries its subject.
     * This test rides on that description rather than inventing a handle of its own; a finder built out
     * of geometry or of a position in the list would pin how the section happens to be laid out, where
     * the description names the control the row offers.
     *
     * Note that the title *text* and the description are the same string here, so the two finders are
     * not interchangeable: `onNodeWithText` finds the row's title and `onNodeWithContentDescription`
     * finds the row's button, and only the second is the control that opens the screen.
     *
     * The button, and not the row, is what is scrolled to. `performScrollTo` aligns the node it is given
     * with the viewport, and a row is taller than its title: scrolling by the title leaves the button,
     * which sits lower down the row, below the fold, and `performClick` taps a position rather than a
     * node — so a tap there can miss the row entirely.
     */
    private fun tapRowButton(title: String) {
        // The row first, then the scroll: on a loaded runner the screen can still be a frame away when
        // the tab click returns, and `performScrollTo` needs the node to exist already.
        pumpUntil(describe = { "the \"$title\" row never composed" }) {
            compose.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(title).performScrollTo().performClick()
        pump()
    }

    /**
     * Asserts that the row titled [title] opens [destination], and that no dialog was shown to do it.
     *
     * Both halves matter and they are asserted separately. The intent names the activity, which is the
     * wiring this class exists to pin; and `ShadowDialog.getShownDialogs()` must not have grown,
     * because a regression that went back to `AlertDialog` would keep the first assertion true-looking
     * enough to be missed while restoring the letterboxed, unscrollable body the promotion removed.
     */
    private fun assertRowOpens(title: String, destination: Class<out Activity>) {
        openSettings()

        // Drain whatever startup queued, so the peek below only ever reports this click's doing —
        // peeking does not consume, so a stale intent would mask the row's.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit
        val dialogsBefore = ShadowDialog.getShownDialogs().size

        tapRowButton(title)

        pumpUntil(describe = { "the \"$title\" row started no activity" }) {
            runCatching { shadowOf(compose.activity.application).peekNextStartedActivity() }.getOrNull() != null
        }
        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertWithMessage("the \"$title\" row did not open ${destination.simpleName}")
            .that(intent?.component?.className).isEqualTo(destination.name)
        assertWithMessage("\"$title\" still opened as a dialog over Settings")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(dialogsBefore)
    }

    /**
     * Keep-alive opens its own window. The row is in Workspace and carries a button reading "Change"
     * like four of its neighbours, so it is one of the rows a finder on that label cannot tell apart —
     * the case its content description exists for, and the one [tapRowButton] documents.
     */
    @Test
    fun theKeepAliveRowOpensTheKeepAliveScreen() {
        assertRowOpens("Keep-alive interval", KeepAliveActivity::class.java)
    }

    /**
     * Clipboard auto-clear opens its own window. Its stored value may be one the screen no longer
     * offers, which is a property of the screen's contents and not of this wiring — the row starts the
     * same activity either way, and what it says about the value is the subtitle's business.
     */
    @Test
    fun theClipboardRowOpensTheClipboardClearScreen() {
        assertRowOpens("Clipboard auto-clear", ClipboardClearActivity::class.java)
    }

    /**
     * Terminal font size opens its own window, from a row whose button reads "Change" like the rest.
     * This is the one of the six whose screen is a slider rather than a list of choices, so the row is
     * reached by its own description and by nothing the screen behind it offers.
     */
    @Test
    fun theTerminalFontSizeRowOpensTheFontSizeScreen() {
        assertRowOpens("Terminal font size", TerminalFontSizeActivity::class.java)
    }

    /**
     * Terminal width opens its own window. The row sits below the shortcut bar in Workspace, past the
     * first screenful on a phone, so this is one of the six the list has to scroll for — the case
     * [tapRowButton] documents.
     */
    @Test
    fun theTerminalWidthRowOpensTheWidthScreen() {
        assertRowOpens("Terminal width", TerminalWidthActivity::class.java)
    }

    /**
     * Reconnect delay opens its own window. It lives in Background processing, four sections down a
     * list that opens at the top, so this is the furthest of the six from where Settings starts — the
     * case [tapRowButton] scrolls for.
     */
    @Test
    fun theReconnectDelayRowOpensTheDelayScreen() {
        assertRowOpens("Reconnect delay", ReconnectDelayActivity::class.java)
    }

    /**
     * Auto-lock vault opens its own window. Its subtitle here is the one row text on this screen that
     * depends on two other settings (whether a PIN is set, and the delay itself), so addressing the row
     * by name — its title, which is also the description on its button — rather than by what it happens
     * to say is what keeps this assertion about the wiring.
     */
    @Test
    fun theAutoLockRowOpensTheAutoLockScreen() {
        assertRowOpens("Auto-lock vault", VaultAutoLockActivity::class.java)
    }
}
