package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.UbuntuActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ubuntu on this device, in its own window.
 *
 * A separate class from the Settings suite because the subject is what the window shows rather than
 * the row that opens it.
 *
 * **This class asserts exactly one of the screen's branches, and it is the unsupported one.** That is
 * not a convenience: `LinuxUserspaceUiState.supported` starts `false` and is only raised once the
 * device's ABI is matched against the rootfs catalog, and a JVM host running these tests maps to no
 * Ubuntu architecture - which is the same condition the controller's own documentation names for an
 * x86 emulator. So the branch that renders here is the one-row, no-button one, and the assertable
 * surface is small and precise.
 *
 * That branch is worth its own class anyway, because the rule it encodes is the interesting one: on a
 * device that cannot run a userspace the screen is *absent of controls*, not full of disabled ones. An
 * unsupported device cannot be offered an install that cannot finish, but it also must not look like a
 * feature that went missing - so the row is there, saying why, and nothing else is. Every other branch
 * - installing, stopped, running, stopping, needing repair, each with its own action set and its own
 * confirmation dialogs - needs an installed userspace to reach and is therefore not asserted here
 * rather than asserted hollowly.
 *
 * The title appears twice in the same case on this screen: once on the bar, and once on the row the
 * body keeps. Hence a collection of at least two, with the section header - the same words uppercased -
 * asserted separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class UbuntuActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<UbuntuActivity>()

    /**
     * The sentence the unsupported branch shows, copied from the screen's own branch.
     *
     * It is the whole of what this state tells the user, so it carries the message on its own: not
     * "unsupported" as an error, but which device property is the reason.
     */
    private val unsupported = "Not supported on this device's processor"

    /**
     * Asserts the window names itself on the bar *and* on the row the body keeps.
     *
     * A collection rather than `onNodeWithText`, which demands exactly one match and would fail on the
     * pair. Both copies are asserted displayed, so neither being off screen passes.
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

        compose.assertTitled("Ubuntu on this device")
    }

    @Test
    fun theCardHeaderNamesTheSection() {
        compose.waitForIdle()

        compose.assertSectionHeader("Ubuntu on this device")
    }

    /**
     * On a device that cannot run it, the screen says so rather than looking broken.
     *
     * This is the branch's entire message, and it is doing the work the section's original comment
     * describes: a feature that vanished would read as a bug, so the row stays and explains itself.
     */
    @Test
    fun anUnsupportedDeviceIsToldSoRatherThanShownNothing() {
        compose.waitForIdle()

        compose.onNodeWithText(unsupported).assertIsDisplayed()
    }

    /**
     * No action is offered on a device that cannot run the userspace - not even a disabled one.
     *
     * Read from the source rather than guessed. The unsupported branch is a single `SettingRow` with an
     * empty trailing slot, and it returns out of the section before any action row is built, so these
     * controls are not composed at all in this state. Asserting their absence is therefore asserting
     * the branch, and it is the assertion that fails if a later change moves the action row above the
     * supported check and leaves an Install button on a device with no rootfs to install.
     *
     * "Verify" and "View" are on the list with the rest on purpose: they live in the installed states,
     * and a device with nothing installed has nothing to verify and no log to read. "OK" is not, because
     * it belongs to the error and warning rows, which also require an installed userspace.
     */
    @Test
    fun noActionIsOfferedOnADeviceThatCannotRunIt() {
        compose.waitForIdle()

        listOf("Install", "Uninstall", "Start", "Stop", "Restart", "Repair", "Verify", "View")
            .forEach { label ->
                assertWithMessage("the window offers \"$label\" on a device that cannot run it")
                    .that(compose.onAllNodesWithText(label).fetchSemanticsNodes()).isEmpty()
            }
    }

    /**
     * The body is laid out in a window that carries the destination's own chrome.
     *
     * What a regression to a dialog would take away: the shell's own bar with its navigation icon, and
     * body content laid out inside the window rather than clipped beneath a fold. This screen is the
     * one the promotion mattered most for on that count - the section it came from was up to six rows
     * plus a progress bar, and the install log alone was a capped dialog body.
     */
    @Test
    fun theBodyIsLaidOutInAWindowWithItsOwnChrome() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(unsupported).assertIsDisplayed()
    }
}
