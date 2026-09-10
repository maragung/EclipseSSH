package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity
import dev.eclipse.ssh.ui.remotedesktop.VncRequest
import dev.eclipse.ssh.ui.remotedesktop.VncRequests
import org.apache.sshd.client.session.ClientSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The viewer's window, as against its content - the same split the editor's activity test makes.
 *
 * The viewer is an activity of its own so it owns the whole screen (and the orientation lock)
 * rather than sharing a sheet with the workspace, and that is the property pinned here: it opens
 * for a live request, it finishes when the request is not live, and a request is opened exactly
 * once. The desktop itself - frames, zoom, input mapping - lives in [RemoteDesktopScreen] and in
 * the tunnel, each of which has its own suite.
 *
 * Every request here carries a provider that answers null: there is no SSH session in this JVM,
 * and a viewer handed nothing to ride reports that and stops - which is exactly the path a user
 * hits when the terminal has dropped, and it exercises the whole screen (the failure panel) with
 * no server anywhere in sight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class RemoteDesktopActivityRobolectricTest {

    /** No session in this JVM: the viewer's own no-transport path, reported not thrown. */
    private val noSession: () -> ClientSession? = { null }

    private fun request() = VncRequest(
        hostName = "desktop.example.test",
        target = RemoteDesktopTarget(port = 5900),
        sessionProvider = noSession,
    )

    private fun launchIntent(token: String?): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, RemoteDesktopActivity::class.java)
            .also { intent -> token?.let { intent.putExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN, it) } }
    }

    @Test
    fun theViewerOpensInItsOwnWindowAndItsTokenIsSpent() {
        val token = VncRequests.put(request())
        ActivityScenario.launch<RemoteDesktopActivity>(launchIntent(token)).use { scenario ->
            // androidx.test:core 1.6 reports the scenario's state as a Lifecycle.State — the nested
            // ActivityScenario.State of older versions no longer exists.
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
        // One-shot by design, exactly like the editor's handoff: a re-delivery of the same intent
        // (recents, a relaunch) is a request for a desktop the user already closed, and it must
        // find nothing to reopen.
        assertThat(VncRequests.take(token)).isNull()
    }

    @Test
    fun anIntentWithNoLiveTokenFinishesRatherThanViewingNothing() {
        ActivityScenario.launch<RemoteDesktopActivity>(launchIntent(token = null)).use { scenario ->
            // Read through runCatching because a destroyed activity can make the scenario's own
            // state query throw; what has to hold either way is that the window never came up.
            val settled = runCatching { scenario.state }.getOrNull()
            assertThat(settled).isNotEqualTo(Lifecycle.State.RESUMED)
        }
    }
}
