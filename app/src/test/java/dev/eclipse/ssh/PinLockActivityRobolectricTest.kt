package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.PinLockActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PIN lock, in its own window.
 *
 * A destination is asserted twice over in this batch: from Settings, for the wiring that opens it,
 * and here, for what the window actually shows. This is the second half. It is possible to hold both
 * in one class only if the activity is launched for real, and launching it here rather than recording
 * a `startActivity` is the point — an intent that names the right class proves nothing about whether
 * the screen behind it composes.
 *
 * Under Robolectric the vault has no PIN, so this screen opens on the new-PIN stage and stays there:
 * the settings snapshot the shell hands the body defaults to `pinEnabled = false`, and nothing in
 * this class writes one. What that puts out of reach is worth stating rather than leaving as a gap.
 * The verify stage needs a stored PIN to exist at all. A successful save is not asserted because it
 * would write a real credential into the test DataStore, and what came back would then be a claim
 * about the store rather than about the screen. The removal confirmation is a Compose `AlertDialog`,
 * which never settles `waitForIdle`. What is left is what the window shows with no PIN set, and the
 * two validation branches, which refuse a PIN *before* any write is attempted — the only part of the
 * write path a JVM test can drive without storing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PinLockActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<PinLockActivity>()

    /** A few frames of work, for the reads and recompositions that follow the window opening. */
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
     * than `idle` so the real work behind the settings read comes due.
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
     * Types [digits] into the field labelled [label], the way a user does.
     *
     * The field is tapped first so the digits arrive in a focused field rather than depending on an
     * unfocused one accepting an insertion. The label is the locator rather than an index into
     * `hasSetTextAction()` so that a field renamed, added or reordered is a missing node or an
     * ambiguous one — `onNodeWithText` fails on more than one match — instead of a silent mistarget.
     */
    private fun typeInto(label: String, digits: String) {
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).performTextInput(digits)
        pump()
    }

    /**
     * Taps "Save PIN", once the button has actually taken the field's new contents.
     *
     * The button is `enabled = newPin.isNotBlank() && !writing`, so it is disabled for the frames
     * between the digits arriving and the recomposition that sees them. A click on a disabled button
     * does nothing at all, and the failure that would follow is a missing error message — which
     * reads as the validation being wrong rather than as a click that never landed.
     */
    private fun tapSave() {
        pumpUntil(describe = { "the Save button never became enabled with digits in the field" }) {
            compose.onAllNodes(hasText("Save PIN") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save PIN").performClick()
        pump()
    }

    /**
     * The window names itself, and a vault with no PIN opens on the new-PIN stage.
     *
     * Every screen in this batch renders its title twice — the shell's bar and the body's row — and
     * this one is no exception, because it deliberately states the setting in the same shape the
     * Settings list did. `onNodeWithText` demands exactly one match, so the title is asked for as a
     * collection; the count is asserted at two and not merely "at least one" so that a screen which
     * lost its bar, or its row, fails here, and every match is asserted displayed so neither copy is
     * off screen. The card's header is the same words uppercased and so is not one of the two — the
     * match stays case-sensitive for that reason, and the header is asserted separately.
     *
     * The status strings after it are the not-set branch of the row. Both a default `AppSettings` and
     * a store that has answered "no PIN" produce them, so they pin the wording rather than proving
     * the read landed; what the read decides is the stage, and the fields asserted below are where
     * that shows.
     */
    @Test
    fun aVaultWithNoPinOpensOnTheNewPinFields() {
        compose.waitForIdle()
        pump()

        val named = compose.onAllNodesWithText("PIN lock")
        assertWithMessage("the bar title and the row title are the two nodes reading \"PIN lock\"")
            .that(named.fetchSemanticsNodes().size).isEqualTo(2)
        repeat(named.fetchSemanticsNodes().size) { named[it].assertIsDisplayed() }

        // The shell's chrome, which is the other half of "a window, not a dialog": a dialog has no
        // navigation icon.
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText("PIN LOCK").assertIsDisplayed()

        compose.onNodeWithText("Not set").assertIsDisplayed()
        compose.onNodeWithText("The vault has no lock screen at launch, and nothing is asked for")
            .assertIsDisplayed()

        compose.onNodeWithText("New PIN (4+ digits)").assertIsDisplayed()
        compose.onNodeWithText("Confirm PIN").assertIsDisplayed()
        compose.onNodeWithText("Save PIN").assertIsDisplayed()

        // The verify stage is what a vault with a PIN set opens on, and it has nothing to prove
        // here; the Cancel in the new-PIN stage exists only for the way back from it. Both absent is
        // the stage this window opened on, said from the other side — the fields above are the stage
        // itself, and these two are the one it is not.
        assertWithMessage("the verify stage composed with no PIN to verify")
            .that(compose.onAllNodesWithText("Current PIN").fetchSemanticsNodes()).isEmpty()
        assertWithMessage("the new-PIN stage offered a Cancel with no earlier stage to return to")
            .that(compose.onAllNodesWithText("Cancel").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * A PIN under four digits is refused in the field, and no write is attempted.
     *
     * This screen keeps two failure surfaces apart on purpose: "what you typed is not acceptable"
     * sits beside the fields as a local error, while "the write did not happen" goes to
     * `LocalSettingsReport`, the shell's channel. The second surface is not asserted here, and that
     * is a limit rather than an omission — reaching it means making `DataStore.edit` throw, and its
     * wording belongs to the shell.
     *
     * What is asserted instead is that the too-short PIN never became a write at all. A save that
     * had gone through clears both fields and leaves "PIN saved." under the row; neither happens on
     * this path, so the error beside the fields is the validation branch and the missing notice is
     * the write it stopped. Asserting the branch this way is also what keeps the test off the
     * DataStore — a PIN that really saved would be a credential stored for a screen test.
     */
    @Test
    fun aTooShortPinIsRefusedInTheFieldRatherThanWritten() {
        compose.waitForIdle()
        pump()

        typeInto("New PIN (4+ digits)", "12")
        tapSave()

        compose.onNodeWithText("PIN must be at least 4 digits").assertIsDisplayed()
        assertWithMessage("a two-digit PIN reached the store")
            .that(compose.onAllNodesWithText("PIN saved.").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * A confirmation that disagrees with the PIN is refused the same way.
     *
     * The second of the two field-level checks and the same write-free path: a mismatched pair never
     * reaches `repository.setPin`. It is asserted separately because the two branches are separate
     * `when` arms, and a reordering that let a mismatched pair through to the write would satisfy the
     * too-short test in full.
     */
    @Test
    fun aPinAndItsConfirmationThatDisagreeAreRefusedInTheField() {
        compose.waitForIdle()
        pump()

        typeInto("New PIN (4+ digits)", "1234")
        typeInto("Confirm PIN", "1235")
        tapSave()

        compose.onNodeWithText("PINs do not match").assertIsDisplayed()
        assertWithMessage("a mismatched confirmation reached the store")
            .that(compose.onAllNodesWithText("PIN saved.").fetchSemanticsNodes()).isEmpty()
    }
}
