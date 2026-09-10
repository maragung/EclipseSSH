package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * The About entry on Settings, exercised on the JVM.
 *
 * What can be asserted where follows the rules the other dialog suites learned the hard way. A
 * Compose `AlertDialog` never settles `waitForIdle` under Robolectric, so the dialog is *opened*
 * and *closed* at window level through [ShadowDialog], never by an idle-driving assertion. Its
 * contents are still reachable by text the way `HostAndThemeUiRobolectricTest`'s edit-form test
 * reads prefilled values: `fetchSemanticsNodes` after hand-pumped frames, which completes because
 * this dialog holds static text only — no focused text field blinking the clock busy, and no
 * `assertIsDisplayed` inside it, which would idle and hang.
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
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor`
     * rather than `idle` so real `delay`s in the Room and DataStore flows come due.
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
     * Scrolls the About row into view, taps its button, and returns the dialog window the tap
     * opened.
     *
     * The button is found by content description, not by its visible "View" label, because the
     * diagnostics row's button reads identically — the description is the one the row itself
     * carries for exactly this disambiguation. The click only flips a `remember`ed flag; the
     * dialog window arrives over the next hand-pumped frames.
     */
    private fun openAboutDialog(): android.app.Dialog {
        // The row first, then the scroll: on a loaded runner the screen can still be a frame away
        // when the tab click returns, and `performScrollTo` needs the node to exist already.
        pumpUntil(describe = { "the About row never composed" }) {
            compose.onAllNodesWithContentDescription("About EclipseSSH").fetchSemanticsNodes().isNotEmpty()
        }
        val before = ShadowDialog.getShownDialogs().size
        compose.onNodeWithContentDescription("About EclipseSSH").performScrollTo().performClick()
        pumpUntil(describe = { "the About dialog never opened" }) {
            ShadowDialog.getShownDialogs().size > before && ShadowDialog.getLatestDialog()?.isShowing == true
        }
        return requireNotNull(ShadowDialog.getLatestDialog()) { "the About dialog opened no window" }
    }

    /**
     * Dismisses the open dialog with the back gesture, through the *dialog's* own dispatcher.
     *
     * Back is delivered to the focused window, and while a dialog is up that is the dialog's, not
     * the activity's — the same distinction [NavigationRobolectricTest]'s dismissal makes.
     */
    @Suppress("DEPRECATION")
    private fun dismissWithBack(dialog: android.app.Dialog) {
        dialog.onBackPressed()
        pumpUntil(describe = { "the About dialog never closed" }) { !dialog.isShowing }
    }

    /**
     * Asserts the text exists somewhere in the composed trees — the main window's or a dialog's.
     *
     * Waited for rather than read once: the dialog *window* being up (what [openAboutDialog]
     * returns on) is a frame or two ahead of its contents composing. `fetchSemanticsNodes`, not
     * `assertIsDisplayed`: inside a dialog window the display assertion would idle and never
     * return, while a plain fetch reads the tree as pumped.
     */
    private fun assertComposed(text: String) {
        pumpUntil(describe = { "the About dialog did not show \"$text\"" }) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
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
     * View opens the dialog, and the dialog answers the three questions an About screen exists
     * for: what version is this, who made it, and what is inside it.
     *
     * The version line is read from the PackageManager in the test, the same source the dialog
     * reads — so the assertion is that the dialog reports what Android reports, not that it spells
     * a version the test also hardcoded.
     */
    @Test
    fun tappingViewOpensTheAboutDialogWithVersionAuthorAndLibraries() {
        compose.waitForIdle()
        tab("Settings").performClick()
        pump()
        val dialog = openAboutDialog()

        val info = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0)
        assertComposed("Version ${info.versionName} (${info.longVersionCode})")
        assertComposed("Created by Maragung")
        assertComposed("Libraries")
        assertComposed("Source code · github.com/maragung/EclipseSSH")

        dismissWithBack(dialog)
        assertThat(dialog.isShowing).isFalse()
    }

    /**
     * The library list names the two engines the app could not exist without — the SSH stack and
     * the RDP client that lands with it — read as composed text, not as a display assertion.
     *
     * Only the first rows are asserted: the list is a `LazyColumn` inside a height-capped dialog,
     * so rows below the fold are simply not composed yet, and "scroll the dialog's list" is not
     * something the JVM harness can do without idling the dialog it would scroll inside.
     */
    @Test
    fun theAboutDialogListsTheBundledLibraries() {
        compose.waitForIdle()
        tab("Settings").performClick()
        pump()
        openAboutDialog()

        assertComposed("Apache MINA SSHD 2.14.0")
        assertComposed("vernacular-vnc f39cbe2 (JitPack)")
        assertComposed("FreeRDP 3.31.1")
    }
}
