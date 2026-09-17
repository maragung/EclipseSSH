package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.ExportBackupActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Export encrypted backup, in its own window.
 *
 * A separate class from the Settings suite because the subject is what the window shows rather than
 * the row that opens it.
 *
 * The whole of what this screen does past its opening frame runs through a Storage Access Framework
 * create-document picker, which is another app's activity and is not reachable from a JVM test. So
 * nothing here touches the export: the picker is never launched, no passphrase is ever typed, and no
 * document is ever chosen. What is asserted is the armed-but-not-fired state the window opens in, and
 * that state carries the one rule worth pinning - the action refuses until there is a passphrase,
 * because a backup encrypted with an empty string is one anyone can read.
 *
 * The clipboard is not touched either. The paste affordance is asserted to *exist* and to be named for
 * a screen reader; tapping it would read the audited [dev.eclipse.ssh.security.SecureClipboard]
 * boundary, and a test that drove it would be asserting the clipboard's behaviour rather than this
 * screen's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ExportBackupActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<ExportBackupActivity>()

    /**
     * The line above the field, which is the Settings row's own subtitle.
     *
     * A dialog titled "Export encrypted backup" said this much by sitting on top of the row that
     * offered it. A window outlives that context, so it has to say what is in the file and what
     * protects it, and this is the sentence that does.
     */
    private val subtitle = "Hosts and settings, passphrase-protected"

    /**
     * The bar names the screen, with the string the Activity itself carries.
     *
     * One node: this screen has no row repeating its title, so the bar holds the only exact match. The
     * card header is the same words uppercased and is asserted separately.
     */
    @Test
    fun theBarNamesTheScreen() {
        compose.waitForIdle()

        compose.onNodeWithText("Export encrypted backup").assertIsDisplayed()
    }

    /** `SettingsSection` renders `title.uppercase()`, so the header is asserted in that form. */
    @Test
    fun theCardHeaderNamesTheSection() {
        compose.waitForIdle()

        compose.onNode(hasText("EXPORT ENCRYPTED BACKUP")).assertIsDisplayed()
    }

    /** The window states what the file holds and what protects it. */
    @Test
    fun theScreenSaysWhatTheFileHoldsAndWhatProtectsIt() {
        compose.waitForIdle()

        compose.onNodeWithText(subtitle).assertIsDisplayed()
    }

    /**
     * The passphrase field is on screen, and it is a field that takes text.
     *
     * Found by its input action rather than by its label. A `TextField`'s label is drawn inside its
     * decoration box and is not guaranteed to be an independent node in the merged semantics tree, so
     * matching the string "Passphrase" would be asserting a rendering detail of Material's field
     * rather than the field's presence - and it would start failing the day the label animation
     * changes. `hasSetTextAction` is what the field *is*, and it is the matcher this suite already uses
     * for text inputs elsewhere.
     *
     * The value is not read back: these fields are never pre-filled from storage, so what one holds is
     * either empty or a typo being corrected, and the state that matters is the assertion below.
     */
    @Test
    fun thePassphraseFieldIsOnScreen() {
        compose.waitForIdle()

        assertWithMessage("the window offers nowhere to type a passphrase")
            .that(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isNotEmpty()
    }

    /**
     * The paste affordance is offered, and named for a screen reader.
     *
     * Found by its content description rather than by an icon lookup, which is the convention this
     * codebase uses for a control whose meaning is not in its pixels. It is worth asserting separately
     * from the field: long-press paste in a password field is unreliable in exactly the situation it is
     * needed most, which is why the button exists at all.
     */
    @Test
    fun thePasteAffordanceIsOffered() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Paste passphrase from clipboard").assertIsDisplayed()
    }

    /**
     * The action refuses until there is a passphrase to encrypt with.
     *
     * This is the one rule the window carries that the dialog also had, and it is the reason the button
     * is asserted *disabled* rather than merely present: a backup encrypted with an empty string is one
     * anyone can read, so a screen that armed this action on an empty field would be offering to write
     * an unprotected copy of every host and setting the app holds.
     *
     * "Export" is unique in this window - the bar's longer title is a different string - so the label
     * needs no disambiguation.
     */
    @Test
    fun theActionRefusesUntilAPassphraseIsTyped() {
        compose.waitForIdle()

        compose.onNodeWithText("Export").assertIsDisplayed().assertIsNotEnabled()
    }

    /**
     * Nothing has been exported, and the window does not claim otherwise.
     *
     * A backup that ran would report its host count, and no picker has been opened here - so a line
     * saying so would mean the screen had exported on open, into a document nobody chose. The check is
     * on the prefix rather than a host count, because the count is whatever the vault holds.
     */
    @Test
    fun nothingHasBeenExportedYet() {
        compose.waitForIdle()

        assertWithMessage("the window reports an export that was never asked for")
            .that(compose.onAllNodesWithText("Exported", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    /**
     * The body is laid out in a window that carries the destination's own chrome.
     *
     * What a regression to a dialog would take away: the shell's own bar with its navigation icon, and
     * body content laid out inside the window rather than clipped beneath a fold.
     */
    @Test
    fun theBodyIsLaidOutInAWindowWithItsOwnChrome() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(subtitle).assertIsDisplayed()
    }
}
