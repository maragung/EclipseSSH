package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.ui.settings.ShortcutBarActivity
import dev.eclipse.ssh.ui.terminal.KeyBarPrefsCodec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shortcut bar editor, in its own window.
 *
 * A separate class from the Settings suite because the subject is what the window shows rather than
 * the row that opens it.
 *
 * This is the one promoted screen whose title appears *twice in the same case*: the bar carries it, and
 * so does the [dev.eclipse.ssh.ui.settings.SettingRow] the window keeps as a summary of the row it came
 * from. So the title assertion here is a collection of at least two rather than a single node, and the
 * section header - the same words uppercased, a third string - is asserted on its own.
 *
 * The editor body itself is the one part not asserted, and the reason is scope rather than difficulty:
 * it is `ShortcutBarEditorBody`, the same composable the dialog draws, so the controls it renders are
 * the editor's subject and not this window's. What this class pins is that the window composed it at
 * all - the count on the row above is read from the editor's live working copy, so a count on screen is
 * a statement that the editor state exists and is rendering - plus the two actions the window owns and
 * the discard guard's *absence* until there is something to discard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShortcutBarActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<ShortcutBarActivity>()

    /**
     * The row's own subtitle, kept by the window so the screen reads as the row that opened it.
     *
     * Belt and braces with [theTitleIsOnTheBarAndRepeatedOnTheRow]: that test proves the string is
     * present twice, this one proves the sentence under it is the Settings list's own.
     */
    private val subtitle = "Choose the keys on the bar, add custom buttons, set the rows"

    /**
     * Asserts the window names itself on the bar *and* on the row it keeps.
     *
     * Two nodes at minimum, and a collection rather than `onNodeWithText` for exactly that reason -
     * the single-node form demands one match and would fail on the pair. Both are asserted displayed,
     * so neither copy being off screen passes.
     */
    private fun AndroidComposeTestRule<*, *>.assertTitled(title: String) {
        val named = onAllNodesWithText(title)
        assertWithMessage("the window does not name itself twice: \"$title\"")
            .that(named.fetchSemanticsNodes().size).isAtLeast(2)
        repeat(named.fetchSemanticsNodes().size) { named[it].assertIsDisplayed() }
    }

    /** `SettingsSection` renders `title.uppercase()`, which is a different string from both above. */
    private fun AndroidComposeTestRule<*, *>.assertSectionHeader(title: String) {
        onNode(hasText(title.uppercase())).assertIsDisplayed()
    }

    @Test
    fun theTitleIsOnTheBarAndRepeatedOnTheRow() {
        compose.waitForIdle()

        compose.assertTitled("Shortcut bar")
    }

    @Test
    fun theCardHeaderNamesTheSection() {
        compose.waitForIdle()

        compose.assertSectionHeader("Shortcut bar")
    }

    @Test
    fun theRowKeepsTheSettingsListsOwnSubtitle() {
        compose.waitForIdle()

        compose.onNodeWithText(subtitle).assertIsDisplayed()
    }

    /**
     * The row's summary counts the arrangement the editor is actually holding.
     *
     * The expected number is not written down here, because writing it down would mean re-deriving the
     * app's default arrangement in a test file and then asserting the screen agreed with the copy. It
     * is read from the same two places the screen reads it from - the store's default blob, through the
     * codec the screen decodes it with, and the catalog the editor merges in - so what this asserts is
     * that the window reports *the arrangement it loaded* rather than a stale or invented number, and
     * that it spells the singular and the plural the way it always has.
     *
     * It is also the evidence that the editor body composed: the count lives inside the row the window
     * draws above that body, and a window that failed to build its editor state has no count to show.
     */
    @Test
    fun theRowCountsTheArrangementTheEditorLoaded() {
        compose.waitForIdle()

        val loaded = KeyBarPrefsCodec.decode(AppSettings().terminalKeyBarJson).withMissingStandardCaps()
        val buttons = loaded.caps.count { it.visible }
        val readout = if (buttons == 1) "1 button" else "$buttons buttons"

        compose.onNodeWithText(readout).assertIsDisplayed()
    }

    /**
     * The two actions this window owns are offered, and they are the dialog's.
     *
     * `Reset` queues a preset replacement - the confirmation it takes is the shared one, not a
     * reworded copy - and `Save` writes the whole blob. Neither is asserted *enabled*, because unlike
     * the key-generation screen this one has no in-flight flag at all: the same blob written twice is
     * the same blob, so a double tap costs a redundant write and nothing else.
     *
     * The dialog's "Cancel" has no twin here on purpose: leaving *is* Cancel on this screen, and the
     * back-out guard below is what asks before it drops anything.
     */
    @Test
    fun theWindowOffersResetAndSave() {
        compose.waitForIdle()

        compose.onNodeWithText("Reset").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    /**
     * Nothing is offered to discard on open, because nothing has been changed yet.
     *
     * The guard is `enabled = editor.isDirty && !editor.hasSubDialog`, and a freshly opened editor is
     * not dirty - `isDirty` compares against the arrangement it opened on, *after* the standard caps
     * were merged in, so merely opening the screen is not a change. The dialog this replaces had no
     * such guard at all: its Cancel, its back and an outside tap all dropped the working copy
     * silently, which is the behaviour this screen deliberately does not copy.
     *
     * The confirmation is deliberately never opened here for the reason the known-hosts suite gives:
     * a Compose dialog is exactly what `waitForIdle` cannot settle, and the absence claim is about the
     * body anyway.
     */
    @Test
    fun nothingOffersToDiscardBeforeAnythingIsChanged() {
        compose.waitForIdle()

        assertWithMessage("the window asks about discarding edits that have not been made")
            .that(compose.onAllNodesWithText("Discard").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * The body is laid out in a window that carries the destination's own chrome.
     *
     * Worth more here than on the sibling screens: the editor's body is what the dialog capped at 75%
     * of the screen height, and the cap is what a regression would restore. The header and the row are
     * asserted displayed without any scrolling first, which is the closest this level can come to
     * saying the page is the page.
     */
    @Test
    fun theBodyIsLaidOutInAWindowWithItsOwnChrome() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(subtitle).assertIsDisplayed()
    }
}
