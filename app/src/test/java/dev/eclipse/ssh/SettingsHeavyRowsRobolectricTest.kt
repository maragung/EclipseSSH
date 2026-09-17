package dev.eclipse.ssh

import android.app.Activity
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
import dev.eclipse.ssh.ui.settings.DiagnosticsActivity
import dev.eclipse.ssh.ui.settings.ExportBackupActivity
import dev.eclipse.ssh.ui.settings.KeyGenActivity
import dev.eclipse.ssh.ui.settings.ShortcutBarActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The five heavy Settings rows, and where each one leads.
 *
 * "Heavy" is the batch's own word for them and the distinction is real: the ten rows that were
 * promoted before these read one value out of a store and write it back, while every one of these
 * five drives something that outlives the window — a generated key pair and an exported backup each
 * pick a destination and write it, the shortcut bar edits a stored blob and can be left unsaved,
 * diagnostics reads a ring buffer the session keeps, and the Ubuntu row starts and stops a real
 * userspace. That is also why each of their screens holds the vault's picker gate across its SAF
 * picker, which is a fact about those screens and not about this class.
 *
 * The split this class sits on is the one [SettingsChoiceRowsRobolectricTest] documents for the six
 * before it and [SettingsAboutRobolectricTest] documents for About: under Robolectric a
 * `startActivity` is *recorded*, not performed, so an activity opened from a MainActivity-driven test
 * never composes. This class therefore asserts the wiring only — that the row starts the right
 * screen, and that no dialog was shown over Settings to do it — and the contents of each screen are
 * the business of a class driving that screen directly, where a real composition exists.
 *
 * `qualifiers` pins the same phone width as the rest of the suite: the workspace shell branches at
 * `maxWidth >= 700.dp`, and the settings list this drives is the narrow layout's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsHeavyRowsRobolectricTest {

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
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor` rather
     * than `idle` so real `delay`s in the Room and DataStore flows come due.
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

    /** Opens Settings, the destination all five rows below live in. */
    private fun openSettings() {
        compose.waitForIdle()
        tab("Settings").performClick()
        pump()
    }

    /**
     * Scrolls the Settings row titled [title] into view and taps the button on it.
     *
     * The button is found by the content description it carries, which is the row's own title, and
     * not by the label it shows. Those labels are "Generate", "Customize", "Export" and "View" —
     * and "View" is a phrase this screen already had trouble with, since the About row and the
     * diagnostics row once both read it and a screen reader heard the two as the same word. The
     * description is what tells the rows, and by extension their controls, apart.
     *
     * Note that the title *text* and the description are the same string here, so the two finders
     * are not interchangeable: `onNodeWithText` finds the row's title and
     * `onNodeWithContentDescription` finds the row's button, and only the second is the control that
     * opens the screen.
     *
     * The button, and not the row, is what is scrolled to. `performScrollTo` aligns the node it is
     * given with the viewport, and a row is taller than its title: scrolling by the title leaves the
     * button, which sits lower down the row, below the fold, and `performClick` taps a position
     * rather than a node — so a tap there can miss the row entirely.
     */
    private fun tapRowButton(title: String) {
        // The row first, then the scroll: on a loaded runner the screen can still be a frame away
        // when the tab click returns, and `performScrollTo` needs the node to exist already.
        pumpUntil(describe = { "the \"$title\" row never composed" }) {
            compose.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(title).performScrollTo().performClick()
        pump()
    }

    /**
     * Asserts that the row titled [title] opens [destination], and that no dialog was shown to do it.
     *
     * Both halves matter and they are asserted separately. The intent names the activity, which is
     * the wiring this class exists to pin; and `ShadowDialog.getShownDialogs()` must not have grown,
     * because a regression that went back to `AlertDialog` would keep the first assertion
     * true-looking enough to be missed while restoring the letterboxed body the promotion removed —
     * and for these five the body is the whole point, since a capped dialog is what a keypair
     * generator's progress, a shortcut bar's cap list and an install log all could not fit in.
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
     * Generating a key pair opens its own window, and this is the row that most needed one: the
     * dialog it used to open did the generation while its own frame held the tap, so the frame
     * froze for as long as the prime search took — seconds for 2048-bit, tens of seconds for 4096.
     * The window can say "generating" and stay responsive; nothing about that is this class's to
     * assert, but it is what makes the wiring worth pinning.
     */
    @Test
    fun theKeyGenRowOpensTheKeyGenScreen() {
        assertRowOpens("Generate SSH key pair", KeyGenActivity::class.java)
    }

    /**
     * The shortcut bar opens its own window. Its row sits below the terminal font size, past the
     * first screenful on a phone, so this is one of the five the list has to scroll for — the case
     * [tapRowButton] documents.
     */
    @Test
    fun theShortcutBarRowOpensTheShortcutBarScreen() {
        assertRowOpens("Shortcut bar", ShortcutBarActivity::class.java)
    }

    /**
     * Exporting the vault opens its own window. The row is in Backup & restore, three sections down
     * a list that opens at the top, and the window it opens is where the passphrase is typed and the
     * destination picked — one motion, which a row could not hold.
     */
    @Test
    fun theExportBackupRowOpensTheExportBackupScreen() {
        assertRowOpens("Export encrypted backup", ExportBackupActivity::class.java)
    }

    /**
     * Connection diagnostics opens its own window. Its label is "View", the same word the About row
     * used to carry, and the two were once indistinguishable to a screen reader — the case the
     * content description exists for, and the reason this row is addressed by its description and
     * not by its button text.
     */
    @Test
    fun theDiagnosticsRowOpensTheDiagnosticsScreen() {
        assertRowOpens("Connection diagnostics", DiagnosticsActivity::class.java)
    }

    /**
     * The Ubuntu row is on the list, reports the device cannot run a userspace, and offers nothing
     * to tap — and the launch therefore cannot be asserted at this level.
     *
     * This is a stated gap, not a covered case. The row's control is composed only when the
     * userspace controller reports the device supported, and under Robolectric it never does:
     * `LinuxUserspaceGraphProvider`'s real build resolves the device's `nativeLibraryDir`, which a
     * JVM host has none of, and returns a null graph for the whole feature — the provider's own
     * documentation says the build "can only produce null" here, and `LinuxUserspaceControllerTest`
     * has to inject a hand-built graph through a test seam for exactly that reason. Null graph means
     * `supported = false`, which is the branch that deliberately shows the row with no button: an
     * unsupported device must not be offered an install that cannot finish, but it also should not
     * look like a feature that went missing.
     *
     * So what is asserted is what is true here — the row exists, and it says why it has nothing to
     * offer. The absence of a control is asserted rather than left unmentioned because it *is* the
     * designed behaviour on this device, and a test that quietly skipped the row would let a
     * regression that dropped the button on supported devices pass unnoticed.
     *
     * When the row's launch does become reachable at this level, this test is the one to extend: the
     * activity is `UbuntuActivity`, and the assertion belongs beside the four above.
     */
    @Test
    fun theUbuntuRowSaysTheDeviceCannotRunItAndOffersNoControl() {
        openSettings()

        pumpUntil(describe = { "the Ubuntu row never composed" }) {
            compose.onAllNodesWithText("Ubuntu on this device").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ubuntu on this device").performScrollTo().assertIsDisplayed()
        // The subtitle is the unsupported branch's own sentence, so asserting it is asserting that
        // the branch was taken rather than merely that a row exists.
        compose.onNodeWithText("Not supported on this device's processor")
            .performScrollTo().assertIsDisplayed()

        assertWithMessage(
            "the Ubuntu row offers a control on a device that cannot run a userspace; the " +
                "unsupported branch is supposed to hide it, and this test should then be " +
                "extended to assert the launch instead",
        ).that(compose.onAllNodesWithContentDescription("Ubuntu on this device").fetchSemanticsNodes())
            .isEmpty()
    }
}
