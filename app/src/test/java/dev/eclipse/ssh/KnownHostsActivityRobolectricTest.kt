package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.KnownHostsActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Known hosts, in its own window.
 *
 * A separate class from the Settings suite because the subject is different, which is the same split
 * [AboutActivityRobolectricTest] makes: that side pins the row that opens this screen, this side pins
 * what the window shows once it is open. Launching it for real is the whole point: a recorded
 * `startActivity` that names the right class proves nothing about whether the screen behind it
 * composes, and this screen exists because its content used to be a list read through a capped
 * `AlertDialog` body.
 *
 * What this class can honestly claim is bounded by what it can put on screen. The app starts with no
 * trusted key, so the only state a window opened here can be in is the empty one. Nothing below
 * asserts a row, a fingerprint, or either forget action, because none of those composes without an
 * entry in the store. Seeding one is reachable *in principle* — `SshConnectionManager` is injected
 * into this Activity as a public field (`sshConnectionManager`) and its `importKnownHosts` is the
 * same call a vault restore makes — but it is not observable from here: the body reads the map once
 * into a `remember` with no keys and re-reads it only inside its own forget callbacks, so a
 * fingerprint written after the window is up never reaches the screen; and writing it before the
 * window is up means writing the store's private `known_hosts` preferences file from a rule ordered
 * outside the compose rule, so that a fingerprint is in the store before the Activity is injected. A
 * test built on that timing is asserting its own setup, so the non-empty branch stays unasserted here
 * rather than asserted hollowly.
 *
 * `waitForIdle` is used freely, which is the promotion's own dividend. A dialog never settles it, and
 * that is why `SettingsAboutRobolectricTest` still carries the hand-pumped `pumpUntil` those screens
 * were driven with; no test here opens the confirm dialog, so there is no frame to pump by hand.
 * Nothing asserted below depends on the settings snapshot either — the shell reads it through
 * `produceState` and the first frame shows defaults — so one `waitForIdle` per test is enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class KnownHostsActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<KnownHostsActivity>()

    /**
     * The sentence the body shows when the store holds no key, copied from the screen's empty branch.
     *
     * A named value rather than a literal in each test because two of them read it for different
     * claims — what it says, and where it was laid out — and two copies of a sentence this long are
     * two things to keep in step.
     */
    private val emptyState =
        "No trusted hosts yet. You'll be asked to verify a host's fingerprint the first time you connect."

    /**
     * The bar names the screen, with the string the Activity itself carries.
     *
     * The match is case-sensitive on purpose: `SettingsSection` prints the same title uppercased
     * under the bar, so a case-insensitive match would find two nodes and fail rather than pin
     * anything. The bar is the one that keeps the Activity's own spelling, which is the value
     * asserted.
     */
    @Test
    fun theBarNamesTheScreen() {
        compose.waitForIdle()

        compose.onNodeWithText("Known hosts").assertIsDisplayed()
    }

    /**
     * With nothing trusted, the window says so instead of showing an empty list.
     *
     * This is the state the window actually opens in under Robolectric — a fresh application starts
     * with no trusted key — and the sentence is also the screen's explanation in this state: it is
     * what tells the user that a fingerprint prompt is coming and that being asked is normal. The
     * other explanation on this screen ("Fingerprints you've trusted. Removing one will prompt you to
     * verify again on next connect.") belongs to the non-empty branch, so it is not asserted here.
     */
    @Test
    fun nothingTrustedYetSaysSoRatherThanShowingAnEmptyList() {
        compose.waitForIdle()

        compose.onNodeWithText(emptyState).assertIsDisplayed()
    }

    /**
     * Nothing on screen offers to forget anything while nothing is trusted.
     *
     * Read from the source rather than guessed: the clear-all control is not disabled in this state,
     * it is not composed at all — it lives inside the `else` of the same emptiness check that picks
     * the sentence above, as do the per-row "Forget" buttons. Asserting the absence of the label is
     * therefore asserting the branch, and it is the assertion that fails if a later change moves the
     * clear-all out of the branch and leaves a control that would clear a list that is already empty.
     *
     * The confirm dialog carries a button with the same "Forget all" label, and is deliberately never
     * opened: a dialog is exactly what `waitForIdle` cannot settle, and the label's absence above is
     * about the window's body, where the control would have to live to be reachable without asking.
     */
    @Test
    fun nothingOffersToForgetWhileNothingIsTrusted() {
        compose.waitForIdle()

        assertWithMessage("the window offers to clear a list that is already empty")
            .that(compose.onAllNodesWithText("Forget all").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * The body is laid out in a window that carries the destination's own chrome.
     *
     * The height cap the promotion removed is not in the semantics tree and is not measured here, and
     * with one short paragraph this state could not show the difference a cap made — the assertion
     * that would, scrolling a long list to its last row, needs the non-empty branch this class cannot
     * enter (see the class comment). What is checkable is the rest of it, and it is what a regression
     * to a dialog would take away: the shell's own bar with its navigation icon, and body content
     * that `assertIsDisplayed` finds laid out inside the window rather than clipped away beneath a
     * fold.
     *
     * The paragraph asserted here is the one
     * [nothingTrustedYetSaysSoRatherThanShowingAnEmptyList] reads the wording of; the claim here is
     * where it was laid out, not what it says.
     */
    @Test
    fun theBodyIsLaidOutInAWindowWithItsOwnChrome() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(emptyState).assertIsDisplayed()
    }
}
