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
import dev.eclipse.ssh.ui.settings.KnownHostsActivity
import dev.eclipse.ssh.ui.settings.PinLockActivity
import dev.eclipse.ssh.ui.settings.SavedCredentialsActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The three Security rows that open a window of their own: PIN lock, Known hosts and Saved
 * credentials.
 *
 * All three were `AlertDialog`s over Settings and are now Activities in `dev.eclipse.ssh.ui.settings`.
 * Under Robolectric a `startActivity` is *recorded*, not performed, so the window that opens never
 * composes here — which is what fixes what this class can honestly claim. It asserts the *wiring*: each
 * row starts its activity, and does it without a dialog appearing over Settings. What each screen
 * actually shows is asserted by [PinLockActivityRobolectricTest], [KnownHostsActivityRobolectricTest]
 * and [SavedCredentialsActivityRobolectricTest], which launch the activity for real and therefore have
 * a composition to read. This is the same split [SettingsAboutRobolectricTest] makes for the About row,
 * applied to the other three.
 *
 * `qualifiers` pins the same phone width as [NavigationRobolectricTest]: the workspace shell branches at
 * `maxWidth >= 700.dp`, and the settings list this reads is the narrow layout's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsSecurityRowsRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    // ------------------------------------------------------------------- the three rows

    /**
     * PIN lock opens [PinLockActivity] rather than a dialog over Settings.
     *
     * The button's visible label varies with the stored flag — "Set" until a PIN exists, "Change"
     * afterwards — so a finder keyed to the label would work in one of the two states and not the other,
     * and would have to know which state the store is in. The row's content description does not vary,
     * which is what makes the row findable whichever label it currently carries; see [tapRow] for the
     * other reason it is the description and not the label that is searched for.
     */
    @Test
    fun tappingPinLockOpensPinLockInItsOwnWindow() {
        openSettings()
        assertRowOpens(PinLockActivity::class.java, "the PIN lock row") { tapRow("PIN lock") }
    }

    /**
     * Known hosts opens [KnownHostsActivity] rather than a dialog over Settings.
     *
     * The button reads "Manage", and so does the Saved credentials row's — but the label is not what
     * either test searches for, so the two cannot be confused with each other or with the "Manage" text
     * elsewhere; see [tapRow].
     */
    @Test
    fun tappingKnownHostsOpensKnownHostsInItsOwnWindow() {
        openSettings()
        assertRowOpens(KnownHostsActivity::class.java, "the Known hosts row") { tapRow("Known hosts") }
    }

    /**
     * Saved credentials opens [SavedCredentialsActivity] rather than a dialog over Settings.
     *
     * Its button reads "Manage" where it used to read "Forget all", and that rename is the refactor
     * rather than a rewording. The row's one action used to be its most destructive one, because there
     * was nowhere to look before deciding; the screen behind it is the list the count in the row's
     * subtitle always implied, so what the row offers now is the going-and-looking, and it forgets
     * nothing itself. What is pinned here is where it goes instead.
     */
    @Test
    fun tappingSavedCredentialsOpensSavedCredentialsInItsOwnWindow() {
        openSettings()
        assertRowOpens(SavedCredentialsActivity::class.java, "the Saved credentials row") { tapRow("Saved credentials") }
    }

    // ------------------------------------------------------------------------- the wiring

    /**
     * Taps a row and asserts both halves of what this class exists to pin.
     *
     * Both halves are asserted separately and both matter. The intent names the activity, which is the
     * wiring; and `ShadowDialog.getShownDialogs()` must not have grown, because this whole change *is*
     * the removal of the dialog that used to sit over Settings. A regression back to an `AlertDialog`
     * would keep the first assertion true-looking enough to be missed while restoring the letterboxed,
     * unscrollable body the Activities replaced — so the dialog count is what says the row really went
     * to a window.
     */
    private fun assertRowOpens(activity: Class<out Activity>, row: String, tap: () -> Unit) {
        // Drain whatever startup queued, so the peek below only ever reports this click's doing —
        // peeking does not consume, so a stale intent would mask the row's.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit
        val dialogsBefore = ShadowDialog.getShownDialogs().size

        tap()

        pumpUntil(describe = { "$row never started ${activity.simpleName}" }) {
            runCatching { shadowOf(compose.activity.application).peekNextStartedActivity() }.getOrNull() != null
        }
        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertWithMessage("$row did not open ${activity.simpleName} in a window of its own")
            .that(intent?.component?.className).isEqualTo(activity.name)
        assertWithMessage("$row still opened as a dialog over Settings")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(dialogsBefore)
    }

    /**
     * Scrolls the row whose control is described as [description] into view and taps it.
     *
     * The description is the row's own name, and it sits on the control because the tree gives a row no
     * other handle: a `SettingRow` is a title `Text`, a subtitle `Text` and a control in a bare `Row`
     * with no semantics of its own, so the control is a *sibling* of its own row's title rather than a
     * descendant of it and nothing records which row it belongs to. That is also why the button cannot
     * be found by the label it shows — "Manage" belongs to two of these rows — nor by the row's name as
     * text, since the name is the title's own text and a search for it lands on the title, not on the
     * button. The description is the one thing that is the row's alone.
     *
     * It is there for the screen reader first, as it is on the About row's button, where two "View"
     * controls had to say which row each belonged to; a control announcing only "Manage" says nothing
     * about what it manages, and one announcing "Change" says nothing about what changes. This test
     * rides on that description rather than being the reason for it.
     */
    private fun tapRow(description: String) {
        // The row first, then the scroll: on a loaded runner the screen can still be a frame away when
        // the tab click returns, and `performScrollTo` needs the node to exist already.
        pumpUntil(describe = { "the \"$description\" row's control never composed" }) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        pump()
    }

    // ---------------------------------------------------------------------------- plumbing

    /** Opens the Settings destination, which is where all three rows live. */
    private fun openSettings() {
        compose.waitForIdle()
        // Only the tab is clickable; a destination's name renders nowhere else in the shell.
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        pump()
    }

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
     * `idle` so real `delay`s in the DataStore flows come due. Nothing here may go through
     * `waitForIdle` while a dialog could be up, because a Compose `AlertDialog` never settles it.
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
}
