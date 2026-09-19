package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.ui.forward.ForwardFormActivity
import dev.eclipse.ssh.ui.forward.ForwardRequest
import dev.eclipse.ssh.ui.forward.ForwardRequests
import java.time.Duration
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The Add-port-forward form, launched for real in its own window.
 *
 * The other half of the pair this batch of destinations always comes in: [ForwardFormWiringRobolectricTest]
 * asserts that Settings opens this window on the host the workspace is pointed at, and this class
 * asserts what the window does once it is open. Launching it is the point — an intent naming the right
 * class proves nothing about whether the form behind it composes, and the form is where the fields were
 * reworked.
 *
 * **What the dialog could not be tested for, and this can.** The dialog's validation lived inside the
 * composable's click handler, so the only way to ask it a question was to press Start and watch what
 * the workspace did — with a live session behind it. Here the form is composed in a window of its own
 * and the answer it produces is a [ForwardRequest] in a process-wide slot, so "a port of 70000 is
 * refused before the server ever sees it" is a sentence a test can assert directly. That is the whole
 * reason the validation moved out of the composable's handler into `ForwardFormState`.
 *
 * No dialog is asserted anywhere below, because this window has nowhere to put one: the promotion
 * removed the only dialog this flow had, and `AlertDialog` under Robolectric cannot be idled anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ForwardFormActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * The slot is process-wide, so an answer left in it by one test would be taken by the next.
     *
     * This is the same hazard `ForwardRequests` is built to survive in the app — a second read of an
     * answer is a second bind — and in the suite it would show up as an unrelated test failing to find
     * nothing to take. The window itself is not what clears it: the workspace is, and there is none
     * here.
     */
    @After
    fun drainTheHandoff() {
        ForwardRequests.takeConfirmed()
    }

    private fun launchIntent(hostId: String?, hostName: String?): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, ForwardFormActivity::class.java).also { intent ->
            hostId?.let { intent.putExtra(ForwardFormActivity.EXTRA_HOST_ID, it) }
            hostName?.let { intent.putExtra(ForwardFormActivity.EXTRA_HOST_NAME, it) }
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor` rather than
     * `idle` so the DataStore read behind the window's theme and its body comes due — the window renders
     * while that read is still in flight, so asserting after a fixed number of frames would be a race
     * even when the frames are pumped.
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
     * Types [value] into the field labelled [label], the way a user does.
     *
     * The field is tapped first so the value arrives in a focused field rather than depending on an
     * unfocused one accepting an insertion, and the label is the locator rather than an index into
     * `hasSetTextAction()` — the same choice `PinLockActivityRobolectricTest` makes, for the same
     * reason: a field renamed, added or reordered is then a missing node or an ambiguous one rather
     * than a silent mistarget. Material merges a text field's label into the field's own semantics, so
     * the label resolves to the control that accepts input and not to its decoration.
     *
     * Each value is exact-matched (and case-sensitive), which is what makes the label safe to use in a
     * form whose own sentences talk about the same things in lower case.
     */
    private fun typeInto(label: String, value: String) {
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).performTextInput(value)
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * The form opens on the host it was launched for, and says which one that is.
     *
     * Naming the host is the half that could not be checked from the workspace's side at all: the
     * workspace knows which host it passed, and only the window knows what it did with it. A form that
     * opened for no host in particular would take a request that could never be opened, and the two
     * failure modes — a name that did not arrive and a name that was ignored — both come out here as
     * the wrong sentence.
     */
    @Test
    fun theFormOpensOnTheHostItWasLaunchedFor() {
        ActivityScenario.launch<ForwardFormActivity>(
            launchIntent(hostId = "host-7", hostName = "bastion.example.test"),
        ).use { scenario ->
            awaitText("Through bastion.example.test", describe = { "the forward form never composed" })

            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Add port forward").assertIsDisplayed()
            compose.onNodeWithText("Through bastion.example.test").assertIsDisplayed()
            // The window is whole rather than a panel: the form's own action is on it, and the button
            // starts switched off because nothing has been typed into an empty form.
            compose.onNodeWithContentDescription("Start forward").assertIsNotEnabled()
            assertThat(ShadowDialog.getShownDialogs()).isEmpty()
        }
    }

    /**
     * With no host in the intent the window closes instead of offering a form that cannot be acted on.
     *
     * That is a re-delivery from the recents screen, or a caller that dropped the extra. The dialog this
     * replaced would have accepted the whole form and then done nothing with it, which is the failure
     * this window's `finish()` exists to make impossible — an empty form is a lie and a filled-in one
     * with no host behind it is worse.
     */
    @Test
    fun anIntentWithNoHostClosesRatherThanOpeningTheForm() {
        ActivityScenario.launch<ForwardFormActivity>(launchIntent(hostId = null, hostName = null)).use { scenario ->
            // Read through runCatching because a destroyed activity can make the scenario's own state
            // query throw; what has to hold either way is that the window never came up.
            val settled = runCatching { scenario.state }.getOrNull()
            assertThat(settled).isNotEqualTo(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A port of 70000 is named under the field, before any server is asked.
     *
     * This is the failure the old dialog produced most often, because `toIntOrNull` was all it asked of
     * the field: 70000 parsed, the form was accepted, and the refusal arrived a second later as a
     * message about a socket — a sentence about the transport rather than about the number the user
     * typed. The window says it in place now, and it can only say it because the check is a value read
     * by the form rather than a condition buried in the click handler.
     */
    @Test
    fun aPortOutsideTheRangeIsNamedUnderTheField() {
        ActivityScenario.launch<ForwardFormActivity>(launchIntent(hostId = "host-7", hostName = null)).use { _ ->
            awaitText("Local port", describe = { "the local port field never composed" })

            typeInto("Local port", "70000")
            awaitText("A port between 1 and 65535", describe = { "the out-of-range port was not named" })

            compose.onNodeWithText("A port between 1 and 65535").assertIsDisplayed()
            compose.onNodeWithContentDescription("Start forward").assertIsNotEnabled()
        }
    }

    /**
     * A mistake is named only once the user has started making it.
     *
     * A form that opens with three red sentences under three empty boxes greets people by complaining
     * about them, and the fields are empty on purpose — the user has not answered yet. So the window
     * opens quiet and tells the truth about an empty field only after something has been typed.
     */
    @Test
    fun anUntouchedFormNamesNothingItHasNotBeenAskedAbout() {
        ActivityScenario.launch<ForwardFormActivity>(launchIntent(hostId = "host-7", hostName = null)).use { _ ->
            awaitText("Local port", describe = { "the local port field never composed" })

            assertThat(compose.onAllNodesWithText("Enter a port").fetchSemanticsNodes()).isEmpty()
            assertThat(compose.onAllNodesWithText("Enter the host to forward to").fetchSemanticsNodes()).isEmpty()
            // The action is still off — an empty form is not a forward with defaults — but the reason
            // is not shouted at a user who has not typed anything yet.
            compose.onNodeWithContentDescription("Start forward").assertIsNotEnabled()
        }
    }

    /**
     * A complete Dynamic forward hands its request back and closes.
     *
     * The whole trip, at the level a window can take it: switch type, type a port, press Start, and
     * find in the slot exactly the request the workspace will act on. The type matters — a dynamic
     * forward is the one with a single field, so this also pins that the form did not quietly require a
     * destination host of a forward that has none.
     *
     * The request is read rather than the forward started, because starting one needs a live session:
     * what this window produces is intent, and intent is what is asserted.
     */
    @Test
    fun aCompleteDynamicForwardHandsBackItsRequestAndCloses() {
        ActivityScenario.launch<ForwardFormActivity>(
            launchIntent(hostId = "host-7", hostName = "bastion.example.test"),
        ).use { scenario ->
            awaitText("Through bastion.example.test", describe = { "the forward form never composed" })

            compose.onNodeWithContentDescription("${ForwardType.DYNAMIC.label} forward").performClick()
            awaitText("Creates a SOCKS5 proxy on the local port for on-demand tunnelling.", describe = { "the form never switched to a dynamic forward" })
            typeInto("Local port", "1080")
            compose.onNodeWithContentDescription("Start forward").performClick()

            assertThat(ForwardRequests.takeConfirmed()).isEqualTo(
                ForwardRequest(
                    hostId = "host-7",
                    type = ForwardType.DYNAMIC,
                    localPort = 1080,
                    remoteHost = null,
                    remotePort = null,
                ),
            )
            // The form closes itself once the answer is in the slot, so the workspace underneath is
            // what the user is left looking at rather than a window they also have to dismiss.
            val settled = runCatching { scenario.state }.getOrNull()
            assertThat(settled).isNotEqualTo(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A Local forward carries the destination it was given, and only when it was given one.
     *
     * The pair with the test above: the same form, the same single field, and a different request out
     * of it. A Local forward is the one type that needs the destination, and the two together pin that
     * what comes back depends on the type rather than on what happened to be in the boxes.
     */
    @Test
    fun aCompleteLocalForwardCarriesItsDestination() {
        ActivityScenario.launch<ForwardFormActivity>(
            launchIntent(hostId = "host-7", hostName = "bastion.example.test"),
        ).use { _ ->
            awaitText("Local port", describe = { "the local port field never composed" })

            typeInto("Local port", "8080")
            typeInto("Remote host", "db.internal")
            typeInto("Remote port", "5432")
            compose.onNodeWithContentDescription("Start forward").performClick()

            assertThat(ForwardRequests.takeConfirmed()).isEqualTo(
                ForwardRequest(
                    hostId = "host-7",
                    type = ForwardType.LOCAL,
                    localPort = 8080,
                    remoteHost = "db.internal",
                    remotePort = 5432,
                ),
            )
        }
    }
}
