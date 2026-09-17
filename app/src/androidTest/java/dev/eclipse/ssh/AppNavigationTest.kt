package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertWithMessage
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the whole navigation surface on a device: every destination must compose and render its
 * disconnected first-run content rather than crashing when there is no SSH connection yet — which
 * is exactly the situation on a clean install.
 *
 * "First-run" is not the same as "empty": both repositories call `seedIfEmpty()`, so a clean
 * install already has two demo hosts and two demo transfers. Only Terminal is genuinely empty,
 * because seeding creates hosts, not live sessions. Hosts and Transfers therefore assert on the
 * seeded content, and Files asserts on the remote listing's own empty state — the host is
 * auto-selected, so the browser composes with nothing listed rather than showing "no host".
 *
 * Assumes a clean install (no PIN configured); a leftover PIN would gate the workspace behind
 * [LockScreen] and fail these tests, which is the correct signal.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The navigation tab. Since the top bar went actions-only, it is also the only place a destination's name renders. */
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /**
     * A Settings section header.
     *
     * `SettingsSection` renders `title.uppercase()`, so the node's text is "SECURITY", not
     * "Security", and matching case-sensitively for the source string never finds it.
     */
    private fun section(title: String) = hasText(title, ignoreCase = true)

    /**
     * A Settings section, scrolled into view first: the shell hosts every destination in a
     * `verticalScroll` Column, so all five sections compose but only the first screenful is on
     * screen. Asserting display without scrolling asserts the device's height, not the app.
     */
    private fun assertSettingsSection(title: String) {
        compose.onNode(section(title)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun everyDestinationIsReachableAndRendersItsFirstRunContent() {
        compose.waitForIdle()

        // Hosts is the start destination. Its top bar is actions-only (no title, no search icon),
        // so the screen is identified by its filter field.
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()

        tab("Terminal").performClick()
        compose.onNodeWithText("No active sessions").assertIsDisplayed()
        compose.onNodeWithText("Connect to a host to open a secure terminal session.")
            .assertIsDisplayed()

        tab("Files").performClick()
        // The explorer's first run: the device's own session, first and always, with its front door
        // on screen because a SAF folder is the one thing this screen cannot grant itself — and an
        // honest "no folder chosen yet" rather than a claim about an empty folder. The seeded demo
        // hosts' chips are here too: every saved host stays reachable from Files, connected or not.
        // [NavigationRobolectricTest] asserts the same on the JVM; this is the real-device signal.
        compose.onNode(hasText("This device") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Pick folder") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("No folder chosen yet", substring = true)).assertIsDisplayed()

        tab("Transfers").performClick()
        // The header renders unconditionally; the queue itself is not empty on a clean install,
        // so assert the seeded rows arrived from Room rather than the "No transfers" branch.
        compose.onNodeWithText("Transfer queue").assertIsDisplayed()
        // The rows come from Room, whose first emission lands on a dispatch Compose's idle
        // detection cannot observe — wait for it rather than racing it (see awaitSeededRow).
        awaitSeededRow("release-bundle.tar.gz")
        compose.onNodeWithText("release-bundle.tar.gz").assertIsDisplayed()
        compose.onNodeWithText("deploy.sh").assertExists()

        tab("Settings").performClick()
        assertSettingsSection("Security")
        assertSettingsSection("Workspace")
        assertSettingsSection("Port forwarding")
        assertSettingsSection("Backup & restore")
        assertSettingsSection("Background processing")

        // And back, without the round trip having disturbed anything.
        tab("Hosts").performClick()
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
    }

    @Test
    fun theSelectedDestinationSurvivesActivityRecreation() {
        compose.waitForIdle()
        tab("Transfers").performClick()
        compose.onNodeWithText("Transfer queue").assertIsDisplayed()

        // rememberSaveable has to carry the destination through the save/restore that a
        // configuration change or a process kill puts the activity through.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText("Transfer queue").assertIsDisplayed()
    }

    @Test
    fun theHostsScreenActionsAreLabelledForScreenReaders() {
        compose.waitForIdle()

        // Icon-only buttons are unusable with TalkBack unless they carry a description.
        compose.onNodeWithContentDescription("Add host").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import account").assertIsDisplayed()
    }

    /**
     * Add host opens the form in a window of its own, and Cancel closes it without saving.
     *
     * The form is an Activity now rather than an `AlertDialog` over this screen, so what proves it
     * opened is that the other window's content is up — there is no dialog to look for, and no
     * `ShadowDialog` to ask. The fields themselves, and everything else the form shows, are asserted
     * by `HostFormActivityRobolectricTest`; what this adds is the half only a device has, that the
     * transition between two real activities happens and that backing out of it leaves the workspace
     * exactly as it was.
     */
    @Test
    fun theAddHostFormOpensAndCancelsWithoutSavingAnything() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add host").performClick()
        // A second activity, so the wait is for a different window to compose rather than for this
        // one to settle. `waitUntil` rather than a bare `waitForIdle`, which cannot know that the
        // window it is about to find has not been created yet.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Hostname or IP").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hostname or IP").assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()

        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()

        // Closed, and the workspace underneath is intact.
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
    }

    /**
     * The password field carries a visible paste affordance.
     *
     * Long-press paste over a `TYPE_TEXT_VARIATION_PASSWORD` field varies by keyboard, and a clip
     * copied by a password manager often ends in a newline a single-line field cannot accept - so
     * the field's trailing icon is the paste that always works.
     *
     * This used to be asserted on a device *only*, because an open Compose dialog never settles
     * `waitForIdle` on the JVM and no semantics query could complete. The form is a window now, so
     * that reason is gone and the same description is asserted in
     * `HostFormActivityRobolectricTest` as well. Both are kept because they are not the same claim:
     * the JVM copy pins that the affordance is wired to the right field, and this one pins that it
     * renders and is reachable with a real IME on screen, which is the situation the button exists
     * for and the one no Robolectric run has.
     */
    @Test
    fun theAddHostFormsPasswordFieldOffersAPasteButton() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add host").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Paste password from clipboard")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Paste password from clipboard").assertIsDisplayed()

        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ on a device only

    /**
     * The theme picker, end to end, including the part Robolectric cannot see.
     *
     * `HostAndThemeUiRobolectricTest` covers the same control on the JVM and covers it further — the
     * dropdown's popup does compose there, and the narrow-screen layout assertions are cheaper to make
     * against a fixed qualifier than against whatever device this runs on. What only a device can show
     * is that the choice survives the real activity being recreated by the platform rather than by a
     * test scenario, with a real DataStore write in between.
     *
     * Restored to Dark at the end. The setting is durable by design, so a test that left it on Amber
     * would hand the next test on the device a workspace it did not ask for.
     */
    @Test
    fun theTerminalThemeDropdownSelectsAThemeAndKeepsIt() {
        compose.waitForIdle()
        tab("Settings").performClick()
        compose.onNodeWithText("Terminal theme").performScrollTo().assertIsDisplayed()

        // Addressed by the setting it belongs to rather than by its current value, which the previous
        // run of this test on this device is allowed to have changed.
        val trigger = { compose.onNode(hasContentDescription("Terminal theme,", substring = true)) }

        // An option in the open menu. While the menu is up, the trigger still shows the selected
        // option's name, so when that is the option being addressed a plain text match finds both
        // ("Dark" found twice: trigger value and menu item). The trigger is the node that also
        // announces "Terminal theme, ..."; a menu item never does, so that is the difference.
        // Negation is the SemanticsMatcher member operator `not`, invoked with `!` — there is no
        // top-level `not` function to import in androidx.compose.ui.test.
        fun option(label: String) = compose.onNode(
            hasText(label) and hasClickAction() and
                !hasContentDescription("Terminal theme,", substring = true),
        )

        trigger().performClick()
        compose.waitForIdle()

        // The options a compact picker has to be able to offer without breaking the row.
        option("Dark").assertIsDisplayed()
        option("Light").assertIsDisplayed()
        option("Amber").assertIsDisplayed()

        option("Amber").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Terminal theme, Amber").assertExists()

        // Written through to DataStore, not just to the composition.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        tab("Settings").performClick()
        compose.onNodeWithText("Terminal theme").performScrollTo()
        compose.onNodeWithContentDescription("Terminal theme, Amber").assertExists()

        trigger().performClick()
        option("Dark").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Terminal theme, Dark").assertExists()
    }

    /**
     * A host card's actions live behind one three-dot button, and nothing else on the card is a button.
     *
     * The card used to carry a full-width Connect button, which made every row tall, made a list of
     * hosts a list of buttons, and left Edit and Remove somewhere else. The assertion that there is no
     * node reading exactly "Connect" on this screen is the half that keeps it from coming back.
     */
    @Test
    fun aHostCardOffersItsActionsBehindTheOverflowMenuOnly() {
        compose.waitForIdle()
        awaitSeededRow(SEEDED_HOST)

        assertWithMessage("a Connect button is back on the host cards")
            .that(compose.onAllNodesWithText("Connect").fetchSemanticsNodes()).isEmpty()

        // The kebab is addressed in the unmerged tree: its description lives on the Icon
        // inside the button, and a hosted device has once composed the card without
        // exposing the button in the merged tree — the unmerged tree always has it.
        compose.onNodeWithContentDescription("More actions for $SEEDED_HOST", useUnmergedTree = true)
            .performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    /**
     * Remove asks first, and cancelling it leaves the host alone.
     *
     * This is the assertion that belongs on a device: the confirmation is an `AlertDialog`, which under
     * Robolectric has a window but no Compose semantics that can be idled, so the JVM test can only
     * check that *a* dialog was shown. Here the wording itself is checkable — it has to say that stored
     * credentials go with the profile, because that is the part of the action a user cannot undo.
     */
    @Test
    fun removingAHostAsksBeforeItDeletesAnything() {
        compose.waitForIdle()
        awaitSeededRow(SEEDED_HOST)

        compose.onNodeWithContentDescription("More actions for $SEEDED_HOST", useUnmergedTree = true)
            .performScrollTo().performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Remove $SEEDED_HOST?").assertIsDisplayed()
        compose.onNodeWithText(
            "This removes the saved connection profile and its stored credentials. " +
                "Active sessions are not affected until disconnected.",
        ).assertIsDisplayed()

        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitForIdle()

        // Nothing was deleted: the card is still there, with its menu still on it.
        compose.onNodeWithContentDescription("More actions for $SEEDED_HOST", useUnmergedTree = true).assertExists()
    }

    /** The per-host SFTP switch is on the form, labelled, and says what each position does. */
    @Test
    fun theAddHostFormCarriesTheAutoLoginSftpSwitch() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add host").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Auto Login SFTP").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Auto Login SFTP").performScrollTo().assertIsDisplayed()
        // The explanation under the label is switched on the current value, so one of the two is on
        // screen and the default decides which.
        val explained = compose.onAllNodesWithText(
            "Signs in to the file browser as soon as the shell connects",
        ).fetchSemanticsNodes().size +
            compose.onAllNodesWithText(
                "Connects the shell only \u2014 the Files tab opens on demand",
            ).fetchSemanticsNodes().size
        assertWithMessage("the switch has no explanation under it").that(explained).isEqualTo(1)

        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
    }

    /**
     * Seeded content arrives from Room over a background dispatch that Compose's idle
     * detection cannot observe, so a test that addresses a seeded row right after
     * [waitForIdle] races the first emission — on a loaded device the assertion can run
     * before the row composes. Waiting is the honest contract: this suite assumes a clean
     * install, so the seeds are a fact; only their arrival is async.
     */
    private fun awaitSeededRow(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** From `HostRepository.seedIfEmpty`: the first card on a clean install. */
        const val SEEDED_HOST = "Production edge"
    }
}
