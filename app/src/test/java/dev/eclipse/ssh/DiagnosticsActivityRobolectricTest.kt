package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.DiagnosticsActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The session trace, in its own window.
 *
 * A separate class from the Settings suite for the reason every promotion test is: the subject here
 * is what the window shows once it is open, not the row that opens it. `startActivity` is *recorded*
 * under Robolectric rather than performed, so a wiring test would prove the right class was named and
 * nothing about whether the screen behind it composes - and this screen exists precisely because its
 * content used to be read through a capped `AlertDialog` body.
 *
 * What this class can honestly claim is bounded by what a fresh application holds, which for this
 * screen is nothing: [dev.eclipse.ssh.ssh.SessionDiagnostics] is the ring the connection path records
 * into, and no connection has been made here. So the empty state is the only state reachable, and the
 * three actions that live in the other branch are asserted *absent* rather than hollowly asserted
 * present. Seeding the ring is not attempted: it is written from MINA's I/O threads and the reconnect
 * ladder, so a test that wrote to it would be asserting its own setup rather than the screen.
 *
 * `waitForIdle` is used freely, which is the promotion's own dividend - a dialog never settles it,
 * and that is why the older suites hand-pump frames. Nothing asserted below waits on a stored setting
 * either: the shell reads the store through `produceState` and the ring through
 * `collectAsStateWithLifecycle`, and both have answered by the time the first frame is drawn.
 *
 * The exact title is matched case-sensitively and expecting a *single* node. [SettingsSection] prints
 * the same words uppercased in its header, and it prints them once - this screen has no row repeating
 * the title the way the shortcut-bar screen does - so a case-insensitive match would find two nodes
 * and `onNodeWithText` would fail with "expected exactly one" rather than pin anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DiagnosticsActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<DiagnosticsActivity>()

    /**
     * The sentence the body shows while the ring is empty, copied from the screen's empty branch.
     *
     * A named value rather than a literal in each test because two of them read it for different
     * claims - what it says, and where it was laid out - and two copies of a sentence this long are
     * two things to keep in step.
     */
    private val emptyState =
        "Nothing recorded yet. Connect a host and this becomes a timestamped trace of every " +
            "connect, disconnect, reconnect and network change."

    /**
     * The bar names the screen, with the string the Activity itself carries.
     *
     * One node, not a collection: the bar is the only place this exact spelling appears. The section
     * header below it is the same words uppercased, which is a different string and is asserted
     * separately by [theCardHeaderNamesTheSection].
     */
    @Test
    fun theBarNamesTheScreen() {
        compose.waitForIdle()

        compose.onNodeWithText("Connection diagnostics").assertIsDisplayed()
    }

    /**
     * The card under the bar carries the section header.
     *
     * `SettingsSection` renders `title.uppercase()`, so this asks for the uppercased form
     * deliberately: asking for the title as written would match the bar and fail as ambiguous.
     */
    @Test
    fun theCardHeaderNamesTheSection() {
        compose.waitForIdle()

        compose.onNode(hasText("CONNECTION DIAGNOSTICS")).assertIsDisplayed()
    }

    /**
     * With nothing recorded, the window explains itself instead of showing an empty trace.
     *
     * This is the state the window actually opens in - a fresh application has recorded no events -
     * and the sentence is doing real work in it: it is what tells the user that an empty screen is
     * the expected one rather than a broken log, and what a connect would put here.
     */
    @Test
    fun nothingRecordedYetSaysSoRatherThanShowingAnEmptyTrace() {
        compose.waitForIdle()

        compose.onNodeWithText(emptyState).assertIsDisplayed()
    }

    /**
     * Nothing on screen offers to copy, save or clear while there is no trace.
     *
     * Read from the source rather than guessed: the three actions live inside the `else` of the same
     * emptiness check that picks the sentence above, so in this state they are not composed at all
     * rather than composed and disabled. Asserting their absence is therefore asserting the branch,
     * and it is the assertion that fails if a later change lifts the buttons out of the branch and
     * leaves a Copy that would put an empty string on the clipboard.
     *
     * "Clear" is the one worth naming: it empties a ring that is already empty, and a control offering
     * to do that is the shape this test exists to catch.
     */
    @Test
    fun nothingOffersToCopySaveOrClearWhileNothingIsRecorded() {
        compose.waitForIdle()

        listOf("Copy", "Save", "Clear").forEach { label ->
            assertWithMessage("the window offers \"$label\" over a trace that holds nothing")
                .that(compose.onAllNodesWithText(label).fetchSemanticsNodes()).isEmpty()
        }
    }

    /**
     * The body is laid out in a window that carries the destination's own chrome.
     *
     * The height cap the promotion removed is not in the semantics tree and is not measured here, and
     * with one short paragraph this state could not show the difference a cap made - the assertion that
     * would, scrolling a five-hundred-row trace to its last entry, needs the non-empty branch this
     * class cannot enter (see the class comment). What is checkable is the rest of it, and it is what a
     * regression to a dialog would take away: the shell's own bar with its navigation icon, and body
     * content that `assertIsDisplayed` finds laid out inside the window rather than clipped away
     * beneath a fold.
     *
     * The paragraph asserted here is the one
     * [nothingRecordedYetSaysSoRatherThanShowingAnEmptyTrace] reads the wording of; the claim here is
     * where it was laid out, not what it says.
     */
    @Test
    fun theBodyIsLaidOutInAWindowWithItsOwnChrome() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(emptyState).assertIsDisplayed()
    }
}
