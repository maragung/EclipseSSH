package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity
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
 * The host card's "Remote desktop" item and where it leads.
 *
 * The item is one menu tap with three outcomes, and the routing is the feature: a host with a
 * saved, enabled VNC target goes straight to the viewer window; a host with nothing saved (or a
 * target parked behind the `#` marker) gets the endpoint dialog first, because the entry point
 * exists so a first use does not have to hunt for a settings screen. The third outcome - what the
 * dialog's Connect commits - is asserted at the view-model boundary, the same split the other
 * dialog tests here make: a Compose dialog never idles under Robolectric, so its buttons cannot
 * be driven, but the save it would make can be called and watched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RemoteDesktopEntryRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** Deletes this class's hosts, so the next class sees the Room file the app ships with. */
    @After
    fun removeHosts() {
        runCatching {
            val viewModel = viewModel()
            compose.runOnUiThread {
                viewModel.uiState.value.hosts.filter { it.id.startsWith(ID_PREFIX) }.forEach(viewModel::deleteHost)
            }
        }
    }

    // ---------------------------------------------------------------- where the menu item leads

    /**
     * Nothing saved: the item configures, it does not refuse.
     *
     * The dialog is confirmed at window level through [ShadowDialog] — a Compose `AlertDialog`
     * never idles under Robolectric — which is the one honest signal that the tap reached the
     * endpoint dialog rather than doing nothing.
     */
    @Test
    fun theMenuItemOpensTheEndpointDialogWhenNothingIsSaved() {
        val host = addHost("Bare host")
        val before = ShadowDialog.getShownDialogs().size

        openRemoteDesktopMenu(host)

        assertWithMessage("the item did not open the endpoint dialog")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
        // Nothing was launched either: a host with no target must not open a viewer that has
        // nothing to dial.
        assertThat(startedRemoteDesktopViewer()).isFalse()
    }

    /**
     * A saved, enabled target: one tap, the viewer window, no questions.
     *
     * The started activity is the assertion, read from the application's shadow the same way the
     * Files tests read the editor's launch — and the token extra is checked to be present, since
     * an intent without one opens an activity that finishes before its first frame.
     */
    @Test
    fun theMenuItemGoesStraightToTheViewerWhenAVncTargetIsSaved() {
        val host = addHost("Desktop host", remoteDesktop = "V:10.0.1.5:5901")
        val before = ShadowDialog.getShownDialogs().size

        openRemoteDesktopMenu(host)

        pumpUntil(describe = { "the viewer never started" }) { startedRemoteDesktopViewer() }
        // No endpoint dialog in the way: the saved target is the answer to the dialog's question.
        assertWithMessage("a saved target still asked before opening")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(before)

        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertThat(intent?.getStringExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN)).isNotEmpty()
    }

    /**
     * A target parked behind the `#` marker is stored but not offered - and "not offered" means
     * the menu asks first here too, not that it silently dials a desktop the user switched off.
     */
    @Test
    fun aTargetParkedWithTheDisabledMarkerStillAsksFirst() {
        val host = addHost("Parked host", remoteDesktop = "#V:5900")
        val before = ShadowDialog.getShownDialogs().size

        openRemoteDesktopMenu(host)

        assertWithMessage("the parked target was opened without asking")
            .that(startedRemoteDesktopViewer()).isFalse()
        assertWithMessage("the item did not open the endpoint dialog")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
    }

    // ---------------------------------------------------------------- what a save commits

    /**
     * The save the dialog's Connect makes, at the boundary the dialog calls.
     *
     * The defaults are the interesting half: a target that says nothing about its host is the
     * server's own loopback, and the packed-text column omits it - so the saved line is `V:5901
     * view-only`, not `V:127.0.0.1:5901 view-only`. What comes back from the column is what the
     * next menu tap reads, which is why the assertion reads the hosts flow rather than trusting
     * the encoder.
     *
     * The provider half: a host with no live session answers null - that is the viewer's
     * precondition, and it is why the viewer reports "connect the host first" instead of dialing.
     */
    @Test
    fun savingATargetNormalisesItThroughTheColumnAndTheProviderReportsNoSession() {
        val host = addHost("Saving host")

        compose.runOnUiThread {
            viewModel().saveRemoteDesktopTarget(
                host.id,
                RemoteDesktopTarget(port = 5901, viewOnly = true),
            )
        }

        pumpUntil(describe = { "the target never reached the host" }) {
            viewModel().uiState.value.hosts.any { it.id == host.id && it.remoteDesktop == "V:5901 view-only" }
        }
        assertThat(viewModel().vncSessionProvider(host.id)()).isNull()
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * Saves a host through the view model and waits for its card, so the kebab below it is on
     * screen before anything clicks it.
     */
    private fun addHost(name: String, remoteDesktop: String = ""): HostProfile {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = ID_PREFIX + nextId++,
            name = name,
            host = "desktop.example.test",
            username = "tester",
            port = 22,
            remoteDesktop = remoteDesktop,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host never reached the list" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        pumpUntil(describe = { "the card never appeared for $name" }) {
            compose.onAllNodesWithContentDescription("More actions for $name").fetchSemanticsNodes().isNotEmpty()
        }
        return profile
    }

    /** Opens the kebab and taps Remote desktop, waiting for the menu the way the flake taught us to. */
    private fun openRemoteDesktopMenu(host: HostProfile) {
        // Drain whatever the setup started, so the peek below only ever reports this click's
        // doing — peeking does not consume, so a stale intent would mask the viewer's.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit

        compose.onNodeWithContentDescription("More actions for ${host.name}").performClick()
        pumpUntil(describe = { "the kebab menu never offered Remote desktop" }) {
            compose.onAllNodesWithText("Remote desktop").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Remote desktop").performClick()
        pump()
    }

    /** Whether the viewer activity was started, by peeking at what the application recorded. */
    private fun startedRemoteDesktopViewer(): Boolean =
        runCatching { shadowOf(compose.activity.application).peekNextStartedActivity() }.getOrNull()
            ?.component?.className == "dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity"

    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    /**
     * Drives frames until [condition] holds, then fails with [describe] - the house `pumpUntil`,
     * copied from `HostAndThemeUiRobolectricTest` so this class owns its own clock.
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

    private companion object {
        /** Prefix on every host this class creates, so the cleanup can find them and leave others. */
        const val ID_PREFIX = "rd-qa-"

        private var nextId = 0
    }
}
