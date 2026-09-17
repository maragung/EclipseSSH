package dev.eclipse.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.ui.settings.HostFormActivity
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The Add / Edit host form, in its own window.
 *
 * The second half of the pair every destination in this batch has: the wiring that opens it is
 * asserted in `NavigationRobolectricTest` and `HostAndThemeUiRobolectricTest`, and what the window
 * actually shows is asserted here. Launching it for real is the point — an intent that names the
 * right class proves nothing about whether the screen behind it composes.
 *
 * **Why an empty compose rule rather than `createAndroidComposeRule`.** Every other window in this
 * batch takes no input, so `createAndroidComposeRule<X>()` is enough for them. This one is opened two
 * ways, and the two ways are different screens: no extra is Add, an extra is Edit. The intent has to
 * be built by the test to express that, and `createAndroidComposeRule` has no intent variant — it
 * always launches with a bare intent. `createEmptyComposeRule` is the documented pairing for a
 * scenario the test launches itself.
 *
 * **What this class can and cannot reach.** The three states the window has before anything is typed
 * are all reachable, and they are the ones worth pinning. Add and Edit are both exercised against
 * real stores, and the store this form reads — [dev.eclipse.ssh.data.HostRepository] — is Room, which
 * works here. That is not true of every store in the app: the *credential* half of this form reads a
 * vault whose key lives in AndroidKeyStore, which the host JVM does not have, so no saved password or
 * key can be put in front of this screen. `HostCredentialStore.stored` therefore always answers
 * "nothing saved" here, and the saved-credential rows ("A password is saved for this host", the
 * per-field Forget, "Replace saved password") are out of reach. What is asserted below is deliberately
 * the set that does not depend on them.
 *
 * The Save button's *enabled state* is asserted rather than only its label, and it is the assertion
 * that does the most work here. It is computed by `HostFormDraft` from the same values the fields
 * were seeded with, so a prefill that reached the text fields but not the draft — or a draft built
 * from a hardcoded default — would leave the button in the wrong state. A form that merely *looked*
 * filled in would pass a text-only check and fail this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HostFormActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * The paragraph at the top of the form, copied from the screen's own string.
     *
     * It is the only statement anywhere that credentials are optional and where they go, so a window
     * that dropped it would leave four secret fields with nothing explaining why they are there.
     */
    private val explanation =
        "Connection details are stored in the encrypted vault. Credentials are optional — save " +
            "them for one-tap connects, or leave them blank to be asked each time."

    private companion object {
        /**
         * The value put on the clipboard by [thePasteButtonReadsTheClipboardAndStripsItsNewline].
         *
         * Deliberately not a password-shaped string: it is asserted by exact text, and a value that
         * could appear anywhere else on the form would make the assertion find the wrong node.
         */
        const val PASTED = "clipboard-secret"
    }

    private fun launchIntent(hostId: String?): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, HostFormActivity::class.java).also { intent ->
            hostId?.let { intent.putExtra(HostFormActivity.EXTRA_HOST_ID, it) }
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor` rather
     * than `idle` so the real work behind the store read comes due. The form's host arrives on a Room
     * flow whose first emission is not synchronous, so asserting after a fixed number of frames would
     * be a race even when the frames are pumped.
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

    /** Waits for a string to be in the tree at all, visible or not. */
    private fun awaitText(text: String, describe: () -> String) = pumpUntil(describe = describe) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Puts a host in the store, through the repository the window itself will read.
     *
     * The repository is taken off the launched Activity rather than built here, which is the whole
     * reason this helper opens a window to save through it: [HostFormActivity]'s binds are `@Inject`
     * fields on a concrete activity, so an instance of it is a handle on the app's own singletons.
     * Building a second repository would write to a different store and the Edit under test would
     * find nothing.
     */
    private fun saveHost(
        name: String,
        hostname: String,
        username: String,
        port: Int,
    ): HostProfile {
        val profile = HostProfile(
            id = "form-test-$name",
            name = name,
            host = hostname,
            username = username,
            port = port,
        )
        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = null)).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking { activity.hostRepository.save(profile) }
            }
        }
        return profile
    }

    /**
     * Add opens the form, and it is the empty one.
     *
     * The bar title is the only thing distinguishing Add from Edit before a field is read, and it is
     * asserted together with the absence of an id in the intent, because the two failure modes look
     * opposite: a form that opened as Edit for a host that was never named, and one that opened as Add
     * when the user pressed Edit, both come out here as the wrong title.
     *
     * No dialog is asserted because there is nowhere for one to have come from — the promotion removed
     * the only dialog this flow had, and a regression that put it back would be caught by the wiring
     * tests. What is asserted instead is that the window is the whole screen it claims to be.
     */
    @Test
    fun addOpensTheEmptyFormInItsOwnWindow() {
        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = null)).use { scenario ->
            awaitText("Add host", describe = { "the Add form never composed" })

            compose.onNodeWithText("Add host").assertIsDisplayed()
            compose.onNodeWithText(explanation).assertIsDisplayed()

            // The identity fields, which is as far as the first screenful reaches.
            listOf("Profile name", "Hostname or IP", "SSH port", "Username").forEach { label ->
                compose.onNodeWithText(label).assertIsDisplayed()
            }

            assertThat(ShadowDialog.getShownDialogs()).isEmpty()
        }
    }

    /**
     * An empty form will not save, and says so by having the action switched off.
     *
     * A host needs a hostname and a username, and neither has a default the app could invent. An Add
     * form that opened with its action armed would let a blank profile be written and then fail at the
     * first connect with nothing on screen to connect the two — this is the cheapest possible check
     * that it does not.
     */
    @Test
    fun theEmptyFormCannotBeSaved() {
        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = null)).use { scenario ->
            awaitText("Add host", describe = { "the Add form never composed" })

            compose.onNodeWithText("Save securely").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save securely").assertIsNotEnabled()
            compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Every section of the form is on the screen, reachable by scrolling to it.
     *
     * This is the claim the promotion was for, and it is asserted in the form the claim can actually
     * take here. The dialog capped its body at roughly 70% of the screen height, which is not a
     * property any assertion inside a window can observe — what can be observed is that the sections
     * below the fold are *there* and that scrolling reaches them, which is what a letterboxed body
     * could not promise. The headings are the ones the form groups its fields under, and each is
     * asserted in `SettingsSection`'s own form, where it applies.
     */
    @Test
    fun everySectionOfTheFormIsReachable() {
        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = null)).use { scenario ->
            awaitText("Add host", describe = { "the Add form never composed" })

            // Headings the form draws as plain label text, in the order the body declares them.
            listOf(
                "Authentication",
                "Saved credentials (optional)",
                "Accent color",
                "Connection options",
                "Connection route",
            ).forEach { heading ->
                compose.onNodeWithText(heading).performScrollTo().assertIsDisplayed()
            }

            // And the fields that live below the fold, which the dialog put out of reach entirely.
            listOf("Timeout (s)", "Keep-alive (s)").forEach { label ->
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            }
        }
    }

    /**
     * The password field's paste button reads through the clipboard boundary and normalizes what it
     * finds.
     *
     * This is the one part of the form that had to be *replicated* rather than moved when it left the
     * dialog: the paste used to be `MainViewModel.pasteSecret`, and a window cannot reach that view
     * model. So the window owns its own copy, and this is what proves the copy is the same behaviour
     * and not a lookalike — [dev.eclipse.ssh.security.SecureClipboard]'s read, then
     * [dev.eclipse.ssh.security.normalizePastedSecret]'s newline strip, then the field.
     *
     * The newline is the assertion that carries the weight. A clip copied by a password manager
     * routinely ends in one, a `singleLine` field cannot accept it, and a paste that skipped the
     * normalization would put a password with an invisible trailing character into the vault — where
     * it would fail to authenticate and look exactly like a wrong password. `SecureClipboard.paste`
     * deliberately keeps newlines for the terminal's sake, so the strip has to happen here, and this
     * is the only place that can show it does.
     *
     * The same button is asserted on a device in `AppNavigationTest`, for the half no JVM run has:
     * that it renders and is reachable with a real keyboard on screen. This is the half that proves
     * it is wired to the right thing.
     */
    @Test
    fun thePasteButtonReadsTheClipboardAndStripsItsNewline() {
        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = null)).use { scenario ->
            awaitText("Add host", describe = { "the Add form never composed" })

            compose.onNodeWithContentDescription("Paste password from clipboard")
                .performScrollTo().assertIsDisplayed()

            // Set after the window is up, so nothing the app does on start can have already replaced
            // it - the clipboard is process-wide state and this class is not the only writer.
            val clipboard = ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "$PASTED\n"))

            compose.onNodeWithContentDescription("Paste password from clipboard").performClick()
            // Found by exact text: an `OutlinedTextField` carries its label and its value in one
            // merged node, so this is the password field and it holds the normalized value.
            awaitText(PASTED, describe = { "the paste never reached the password field" })
            compose.onNodeWithText(PASTED).assertExists()
        }
    }

    /**
     * Edit opens on the host that was named, prefilled from it.
     *
     * The prefill is the assertion, and it was moved here from `HostAndThemeUiRobolectricTest` when
     * the form stopped being a dialog. That class could only ever see the intent — `startActivity` is
     * recorded, not performed, under Robolectric — so the values it used to read out of the dialog's
     * window had to follow the window here.
     *
     * Both halves are asserted because they fail differently. The *text* half catches a form that
     * opened blank; the *armed action* half catches one that was filled in on screen but whose draft
     * was built from defaults, which is the state that would save over the user's host with empty
     * values.
     */
    @Test
    fun editOpensOnThatHostPrefilled() {
        val host = saveHost(
            name = "Editable",
            hostname = "edit.example.test",
            username = "editor",
            port = 2244,
        )

        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = host.id)).use { scenario ->
            awaitText("Edit host", describe = { "the Edit form never composed" })

            compose.onNodeWithText("Edit host").assertIsDisplayed()
            // Each value is looked up by exact text, and an `OutlinedTextField` carries its label and
            // its value in one merged node, so finding the value is finding the field that holds it.
            listOf(host.name, host.host, host.username, host.port.toString()).forEach { value ->
                awaitText(value, describe = { "the form did not carry \"$value\" over" })
            }

            // The draft agrees with the fields: this host has everything a profile needs, so the
            // action is armed. A form that only looked filled in would leave this disabled.
            compose.onNodeWithText("Save changes").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save changes").assertIsEnabled()
        }
    }

    /**
     * An Edit for a host that is gone says so, and does not become an Add form.
     *
     * This is the state the whole `HostLoad` split exists for. The host can disappear between the tap
     * on Edit and this window opening — it is removed from the list, or from another window — and the
     * tempting way to handle a missing profile is to treat it as null and draw the Add form. That
     * looks like it worked, and then saves a *second* host instead of changing the one that was
     * tapped. A dead end that says what happened is the honest outcome, and it is pinned here because
     * nothing else in the suite can produce the state.
     */
    @Test
    fun editForAVanishedHostSaysSoRatherThanBecomingAnAdd() {
        ActivityScenario.launch<HostFormActivity>(launchIntent(hostId = "no-such-host")).use { scenario ->
            awaitText(
                "That host is no longer saved, so there is nothing to edit.",
                describe = { "the missing-host state never composed" },
            )

            compose.onNodeWithText("That host is no longer saved, so there is nothing to edit.")
                .assertIsDisplayed()
            // A way out, on the screen rather than in a snackbar on a window that is closing.
            compose.onNodeWithText("Close").assertIsDisplayed()
            // And not the Add form: the title is the Edit one it was opened with, and no field of the
            // form is drawn at all.
            compose.onNodeWithText("Edit host").assertIsDisplayed()
            compose.onNodeWithText("Hostname or IP").assertDoesNotExist()
            compose.onNodeWithText("Save changes").assertDoesNotExist()
        }
    }
}
