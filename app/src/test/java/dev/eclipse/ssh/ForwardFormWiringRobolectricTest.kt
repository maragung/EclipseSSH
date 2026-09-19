package dev.eclipse.ssh

import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.forward.ForwardFormActivity
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
 * The Settings row that opens the Add-forward form, and what it does when there is no host to open it
 * for.
 *
 * The pair to [ForwardFormActivityRobolectricTest], split the way this batch has been split
 * throughout: the wiring is asserted here, where a `startActivity` is *recorded* rather than performed
 * and the target therefore never composes, and the window's own contents are asserted there, launched
 * directly so that a real composition exists. A class cannot load both — a recorded intent and a live
 * window want different rules — which is why these are two files.
 *
 * **What changed, and what this pins.** The Add button used to open an `AlertDialog` over Settings, and
 * the dialog was shown whenever the section was drawn — with or without a connected host. Pressing its
 * Start button with no active session did nothing at all: no message, no forward, no change on screen,
 * after the user had filled in three fields. The row now asks the platform for a window, and when the
 * workspace has no host at all the button is switched off and the row says why. Both halves are
 * asserted below, because "it opens the form" and "it refuses to open a form that could never work" are
 * the same change seen from either side.
 *
 * The host is seeded through the view model rather than through the Add Host form, which is what
 * `HostAndThemeUiRobolectricTest` does for the same reason: the form's own Save is asserted in its own
 * class, and driving it here would make this test fail for a reason that has nothing to do with
 * forwarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ForwardFormWiringRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The shared Room file is the app's real one, so a host left behind here is a phantom card in
     * whatever runs next — and a leftover host is also the one thing that could make the no-host test
     * below pass for the wrong reason.
     *
     * The deletes are coroutines on the view model's scope, so they need frames to run: without the
     * [pump] this method asks for the cleanup and leaves before it happens. What runs next is not
     * relying on it either way — [seedHost] clears the list itself — but a class that tidies up after
     * itself should mean it.
     */
    @After
    fun removeSeededHosts() {
        val viewModel = viewModel()
        compose.runOnUiThread {
            viewModel.uiState.value.hosts
                .filter { it.id.startsWith(ID_PREFIX) }
                .forEach(viewModel::deleteHost)
        }
        pump()
        ForwardRequests.takeConfirmed()
    }

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

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
     * Puts the workspace in the state the row branches on: a host exists, or none does.
     *
     * Leftovers from earlier classes are cleared first, not only this class's own, because the row's
     * state is read from `MainUiState.hosts` — any host at all makes the workspace "pointed at" one —
     * so a stray profile from another class would arm a button this test is asserting is switched off.
     * Every class that seeds hosts deletes its own, so clearing here costs nobody anything.
     *
     * The sentinel is what makes that clearing real. `uiState` is a `StateFlow`, and its value before
     * Room's first emission is the empty initial state: a delete that runs that early iterates an empty
     * list, deletes nothing, and then waits twenty seconds for a list that only grows — which is how
     * this test failed the first time it ran, with every host still in place and nothing having been
     * asked to leave. Writing a host and waiting for it to come back proves the flow has been observed,
     * so everything after it is a statement about the database rather than about how far the app got
     * through starting up.
     *
     * The sentinel is necessary but not sufficient, because the database is not the only writer:
     * `HostRepository.seedIfEmpty()` runs from the view model's `init` and refills an emptied table
     * with its three demo hosts. So the clearing below re-deletes until the list has *stayed* empty
     * for a settle window rather than deleting once — the same race, one writer later, and the window
     * is what covers the three upserts still in flight behind this delete's own query.
     */
    private fun seedHost(name: String?): HostProfile? {
        compose.waitForIdle()
        val viewModel = viewModel()

        val sentinel = HostProfile(
            id = ID_PREFIX + "sentinel",
            name = "Sentinel",
            host = "sentinel.example.test",
            username = "tester",
            port = 22,
        )
        compose.runOnUiThread { viewModel.saveHost(sentinel) }
        pumpUntil(describe = { "the workspace never observed the host list" }) {
            viewModel.uiState.value.hosts.any { it.id == sentinel.id }
        }

        compose.runOnUiThread {
            viewModel.uiState.value.hosts.forEach(viewModel::deleteHost)
        }
        var emptySince = 0L
        pumpUntil(
            describe = {
                "the host list never emptied; left holding ${viewModel.uiState.value.hosts.map { it.id }}"
            },
        ) {
            // Emptied by repetition, and then only believed once it has *stayed* empty for a settle
            // window. `HostRepository.seedIfEmpty()` writes its three demo hosts whenever it finds the
            // table empty, and `MainViewModel` calls it from `init` on the view model's own scope — so
            // its three upserts can be sitting on the database's executor behind this delete's own
            // query, committing an instant after a single reading reported the list empty. That is the
            // shape CI reported: the sentinel gone, all three demo hosts back. Re-deleting handles the
            // rows that have landed; the window is what makes "empty once" into "empty".
            val hosts = viewModel.uiState.value.hosts
            if (hosts.isNotEmpty()) {
                compose.runOnUiThread { hosts.forEach(viewModel::deleteHost) }
                emptySince = 0L
            } else if (emptySince == 0L) {
                emptySince = System.nanoTime()
            }
            hosts.isEmpty() && System.nanoTime() - emptySince >= EMPTY_SETTLE_NANOS
        }
        if (name == null) return null

        val profile = HostProfile(id = ID_PREFIX + "1", name = name, host = "forward.example.test", username = "tester", port = 22)
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the seeded host never reached the workspace" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        return profile
    }

    /** Opens Settings, the destination the Port forwarding section lives in. */
    private fun openSettings() {
        compose.waitForIdle()
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        pump()
    }

    /**
     * Scrolls the section's Add button into view and taps it.
     *
     * The row sits in Workspace, past the first screenful on a phone, so the scroll is part of
     * reaching it — and the button, not the row, is what is scrolled to, because `performScrollTo`
     * aligns the node it is given and a row is taller than the control on it.
     */
    private fun tapAddForward() {
        pumpUntil(describe = { "the Add port forward button never composed" }) {
            compose.onAllNodesWithContentDescription("Add port forward").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Add port forward").performScrollTo().performClick()
        pump()
    }

    /**
     * Empties the started-activity queue and answers what was in it.
     *
     * Every assertion about what a tap *did* has to drain first, so that a stale intent from startup
     * cannot stand in for the one under test — peeking does not consume, and this test's click is not
     * the only thing that has ever called `startActivity` in this window.
     */
    private fun drainStartedIntents(): List<Intent> {
        val started = mutableListOf<Intent>()
        while (true) {
            val next = runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull()
            if (next == null) return started
            started += next
        }
    }

    /**
     * The Add button opens the forward form on the host the workspace is pointed at.
     *
     * This is the claim the promotion was for, and it is asserted in three parts that fail separately:
     * the intent names [ForwardFormActivity], it carries the host's id, and it carries the host's name
     * for the form to show. The name is not decoration — it is the only statement on the form about
     * which server the tunnel will ride, and a form that showed nothing there would be asking the user
     * to fill in a destination without telling them where the connection is going.
     *
     * The dialogs check is the other half of "the dialog is gone": a regression that went back to
     * `AlertDialog` would leave the intent assertion looking satisfied while restoring a letterboxed
     * body over the list.
     */
    @Test
    fun theAddButtonOpensTheForwardFormOnTheWorkspacesHost() {
        val host = requireNotNull(seedHost("Forward Through Me"))
        openSettings()

        val dialogsBefore = ShadowDialog.getShownDialogs().size
        drainStartedIntents()
        tapAddForward()

        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertWithMessage("the Add button did not open ${ForwardFormActivity::class.simpleName}")
            .that(intent?.component?.className).isEqualTo(ForwardFormActivity::class.java.name)
        assertWithMessage("the form was not told which host to forward through")
            .that(intent?.getStringExtra(ForwardFormActivity.EXTRA_HOST_ID)).isEqualTo(host.id)
        assertWithMessage("the form was not given the host's name to show")
            .that(intent?.getStringExtra(ForwardFormActivity.EXTRA_HOST_NAME)).isEqualTo(host.name)
        assertWithMessage("Add port forward still opened as a dialog over Settings")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(dialogsBefore)
    }

    /**
     * With no host at all the button is switched off, and the row says what to do about it.
     *
     * This is the failure the old dialog had and the reason the row changed: the dialog opened without a
     * session, accepted a full form, and then did nothing with it. A button that cannot be pressed, next
     * to a sentence naming the reason, is the honest version of the same state.
     *
     * The strongest assertion here is the last one: nothing was started at all while Settings was open,
     * which is a claim about the whole screen rather than about the tap — the Port forwarding section is
     * not the only thing on it that can open a window.
     */
    @Test
    fun withNoHostTheAddButtonIsOffAndTheRowExplainsWhy() {
        seedHost(name = null)
        openSettings()

        val startedBySettings = drainStartedIntents()
        pumpUntil(describe = { "the Add port forward button never composed" }) {
            compose.onAllNodesWithContentDescription("Add port forward").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Add port forward").performScrollTo().assertIsNotEnabled()
        // The suffix is the part that changed, and the part that is a claim: the row used to describe
        // the three forward types and nothing else, which is an invitation to a form that could not be
        // acted on.
        assertThat(compose.onAllNodesWithText("connect to a host to add one", substring = true).fetchSemanticsNodes())
            .hasSize(1)
        assertThat(startedBySettings.mapNotNull { it.component?.className })
            .doesNotContain(ForwardFormActivity::class.java.name)
    }

    private companion object {
        /** Marks the hosts this class seeds, so its cleanup cannot touch anybody else's. */
        const val ID_PREFIX = "forward-wiring-"

        /**
         * How long the host list has to *stay* empty before [seedHost] believes it, in nanoseconds of
         * wall clock across pumped frames. Long enough for three queued upserts to commit and be
         * delivered, short enough that it costs a passing run nothing.
         */
        const val EMPTY_SETTLE_NANOS = 2_000_000_000L
    }
}
