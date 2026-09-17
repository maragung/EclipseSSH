package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.about.AboutActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The About entry on Settings, and where it leads.
 *
 * About used to be an `AlertDialog` over Settings and this class used to drive it through
 * [ShadowDialog]. It is now a window of its own, which changes what this class can honestly claim:
 * under Robolectric a `startActivity` is *recorded*, not performed, so the activity that opens
 * never composes here. The split that follows is the same one the rest of the suite makes - this
 * class asserts the wiring (the About row starts [AboutActivity], and no dialog is shown over
 * Settings to do it), and [AboutActivityRobolectricTest] asserts the screen's own contents, in its
 * own window, where a real composition exists.
 *
 * `qualifiers` pins the same phone width as [NavigationRobolectricTest]: the workspace shell
 * branches at `maxWidth >= 700.dp`, and the settings list this reads is the narrow layout's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsAboutRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The navigation tab. Only the tab is clickable; a destination's name renders nowhere else. */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /**
     * A Settings section header. `SettingsSection` renders `title.uppercase()`, so the match is
     * case-insensitive to stay tied to the source string rather than a presentation detail.
     */
    private fun section(title: String) = hasText(title, ignoreCase = true)

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

    /**
     * Scrolls the About row into view and taps its button.
     *
     * The button is found by content description, not by its visible "View" label, because the
     * diagnostics row's button reads identically — the description is the one the row itself
     * carries for exactly this disambiguation.
     */
    private fun tapAboutRow() {
        // The row first, then the scroll: on a loaded runner the screen can still be a frame away
        // when the tab click returns, and `performScrollTo` needs the node to exist already.
        pumpUntil(describe = { "the About row never composed" }) {
            compose.onAllNodesWithContentDescription("About EclipseSSH").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("About EclipseSSH").performScrollTo().performClick()
        pump()
    }

    /**
     * Settings ends in an About section, and the section is what its row says it is.
     *
     * The row is scrolled into view first: the shell hosts every destination in a `verticalScroll`
     * Column, so the last section composes below the fold and asserting display without scrolling
     * would be asserting the viewport height.
     */
    @Test
    fun settingsEndsInAnAboutSectionWithItsRow() {
        compose.waitForIdle()
        tab("Settings").performClick()
        pump()

        pumpUntil(describe = { "the About row never composed" }) {
            compose.onAllNodesWithText("About EclipseSSH").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("About EclipseSSH").performScrollTo().assertIsDisplayed()
        // A section of its own under the existing five, not a row tacked onto "Background
        // processing": the header is what makes the distinction.
        pumpUntil(describe = { "the About section header never composed" }) {
            compose.onAllNodes(section("About")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(section("About")).performScrollTo().assertIsDisplayed()
    }

    /**
     * View opens About — in its own window, not as a dialog over Settings.
     *
     * Both halves matter and they are asserted separately. The intent names the activity, which is
     * the wiring this class exists to pin; and `ShadowDialog.getShownDialogs()` must not have
     * grown, because a regression that went back to `AlertDialog` would keep the first assertion
     * true-looking enough to be missed while restoring the letterboxed, unscrollable body this
     * change removed.
     */
    @Test
    fun tappingViewOpensAboutInItsOwnWindow() {
        compose.waitForIdle()
        tab("Settings").performClick()
        pump()

        // Drain whatever startup queued, so the peek below only ever reports this click's doing —
        // peeking does not consume, so a stale intent would mask About's.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit
        val dialogsBefore = ShadowDialog.getShownDialogs().size

        tapAboutRow()

        pumpUntil(describe = { "AboutActivity was never started" }) {
            runCatching { shadowOf(compose.activity.application).peekNextStartedActivity() }.getOrNull() != null
        }
        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertWithMessage("the About row did not open AboutActivity")
            .that(intent?.component?.className).isEqualTo(AboutActivity::class.java.name)
        assertWithMessage("About still opened as a dialog over Settings")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(dialogsBefore)
    }
}
