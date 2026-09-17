package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ssh.SshKeyAlgorithm
import dev.eclipse.ssh.ui.settings.KeyGenActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Generate an SSH key pair, in its own window.
 *
 * A separate class from the Settings suite because the subject is what the window shows, not the row
 * that opens it - `startActivity` is recorded under Robolectric, not performed, so the wiring side
 * cannot see this screen compose at all.
 *
 * What this class asserts is the *opening* state, which is the idle one: nothing is generating, the
 * action is armed, and every algorithm is on offer. Everything past that point is either a coroutine
 * on `Dispatchers.Default` searching for primes, or a Storage Access Framework picker belonging to
 * another app, and neither is reachable from here. In particular the two write paths and the
 * "Generating the key pair…" status line are deliberately not asserted: the first needs a document
 * the test cannot choose, and the second exists only while [KeyGenActivity]'s `busy` flag is up, which
 * only a tap on the action raises. Asserting the status line's *absence* here is the honest half of
 * that, and it is what would catch a screen that opened mid-chain.
 *
 * The chips are asserted *displayed* rather than merely present, and nothing is scrolled before them.
 * That is the claim this promotion is for: the dialog's strip of chips hid the algorithms that did not
 * fit behind a gesture nobody knows is there, and the window has the height to show all of them. A
 * `performScrollTo` would restore the very limit being checked is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class KeyGenActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<KeyGenActivity>()

    /**
     * The paragraph above the chips, copied from the screen's body.
     *
     * It is the only explanation of what the two files are for, so it is worth pinning: a window that
     * dropped it would leave an unexplained pair of pickers and no statement that the private half is
     * the one to keep secret.
     */
    private val explanation =
        "A private key (PEM) and a public key (OpenSSH format) will be saved as separate files. " +
            "Keep the private key secret and add the public key to your server's authorized_keys."

    /**
     * The bar names the screen, with the string the Activity itself carries.
     *
     * One node, not a collection: this screen has no row repeating its title, so the bar is the only
     * place the exact spelling appears. The card header below it is the same words uppercased, which
     * is a different string and is asserted by [theCardHeaderNamesTheSection].
     */
    @Test
    fun theBarNamesTheScreen() {
        compose.waitForIdle()

        compose.onNodeWithText("Generate SSH key pair").assertIsDisplayed()
    }

    /** `SettingsSection` renders `title.uppercase()`, so the header is asserted in that form. */
    @Test
    fun theCardHeaderNamesTheSection() {
        compose.waitForIdle()

        compose.onNode(hasText("GENERATE SSH KEY PAIR")).assertIsDisplayed()
    }

    /** The screen says what it is about to write, before it writes it. */
    @Test
    fun theScreenExplainsWhatTheTwoFilesAre() {
        compose.waitForIdle()

        compose.onNodeWithText(explanation).assertIsDisplayed()
    }

    /**
     * Every algorithm the generator can produce is on offer, and all of them at once.
     *
     * The labels are written out rather than derived, because they are what the user reads and a test
     * that computed them the same way the screen does could not notice the screen changing its mind
     * about the wording. The count is then checked against the enum, because a hand-spelled list is
     * exactly what cannot notice a fourth algorithm being added to it - which is the one change this
     * screen's own comment claims cannot happen silently.
     */
    @Test
    fun theScreenOffersEveryAlgorithmItCanGenerate() {
        compose.waitForIdle()

        val labels = listOf("RSA 2048-bit", "RSA 4096-bit", "ECDSA P-256")
        assertWithMessage("the offered algorithms and the labels asserted here have drifted apart")
            .that(labels.size).isEqualTo(SshKeyAlgorithm.entries.size)
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    /**
     * The action is armed on open, and no generation is already running behind it.
     *
     * Both halves matter and they are different failures. A disabled action on an idle screen is a
     * screen the user cannot use; the status line on an idle screen is worse, because it claims work
     * that is not happening, and it is the shape a screen that opened mid-chain would have.
     *
     * The button is found by its label, which is unique in this window - the dialog's "Cancel" has no
     * twin here on purpose, since the window's back arrow leaves just as well.
     */
    @Test
    fun theActionIsArmedAndNothingIsGeneratingYet() {
        compose.waitForIdle()

        compose.onNodeWithText("Generate & save").assertIsDisplayed().assertIsEnabled()
        assertWithMessage("the screen opened claiming a generation was already in flight")
            .that(compose.onAllNodesWithText("Generating the key pair").fetchSemanticsNodes()).isEmpty()
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
        compose.onNodeWithText(explanation).assertIsDisplayed()
    }
}
