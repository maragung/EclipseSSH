package dev.eclipse.ssh.ui.remotedesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.EclipseTheme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.apache.sshd.client.session.ClientSession

/**
 * The remote-desktop viewer in its own window, on top of the app - the same shape and the same
 * reasons as [dev.eclipse.ssh.ui.editor.TextEditorActivity]: an opaque window over the workspace,
 * its own back-stack entry, and a surface that does not share its insets with whatever the
 * workspace was showing. The viewer also wants things a workspace screen cannot have: an
 * immersive fullscreen with no chrome to tap by accident, and an orientation the user can lock
 * from the toolbar without rotating the terminal behind it.
 *
 * The session the tunnel rides is handed over through [VncRequests] rather than the intent,
 * because a [ClientSession] is a live object an intent cannot parcel. The token is consumed on
 * arrival, so a stale intent (a recents re-delivery, a crash and relaunch) finds nothing to
 * reopen and the activity finishes.
 *
 * The [VncRequest.sessionProvider] is a *provider* and not a session on purpose: the viewer is
 * single-shot per connection ([dev.eclipse.ssh.vnc.VncTunnel] owns exactly one), so its
 * Reconnect button needs a fresh answer to "what session does this host have now", not the
 * session it happened to start with. Re-dialling stays in the view model, where every other
 * dial lives; the provider here only ever reports what the host already has.
 */
@AndroidEntryPoint
class RemoteDesktopActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is forced on Android 15 anyway; calling it here makes pre-35 devices behave
        // the same way. The viewer supplies its own inset handling (which is: none - the desktop
        // goes under the bars and the toolbar floats above it).
        enableEdgeToEdge()
        val request = intent?.getStringExtra(EXTRA_REQUEST_TOKEN)?.let(VncRequests::take)
        if (request == null) {
            finish()
            return
        }
        setContent {
            // The app's own dark-theme setting rather than the system's, so the viewer does not
            // flip on a user who pinned one in Settings. Read once, ahead of the first frame.
            val darkTheme by produceState(initialValue = isSystemInDarkTheme()) {
                value = runCatching { settingsRepository.settings.first().darkTheme }.getOrDefault(true)
            }
            EclipseTheme(darkTheme = darkTheme) {
                RemoteDesktopScreen(request) { finish() }
            }
        }
    }

    companion object {
        /** The intent extra carrying the [VncRequests] token. */
        const val EXTRA_REQUEST_TOKEN = "dev.eclipse.ssh.remotedesktop.REQUEST_TOKEN"
    }
}

/**
 * One remote-desktop viewing request: the endpoint as saved on the host, and where to get the
 * SSH session the tunnel rides.
 *
 * [hostName] travels along only for the screen to say whose desktop this is - the viewer never
 * touches the profile, and saving a changed endpoint is the menu's job, not the viewer's.
 */
class VncRequest(
    val hostName: String,
    val target: RemoteDesktopTarget,
    val sessionProvider: () -> ClientSession?,
)

/**
 * One-shot handoff of a [VncRequest] to [RemoteDesktopActivity].
 *
 * [put] mints a token and [take] consumes it, exactly like the editor's handoff: a replayed
 * intent is a request for a desktop the user already closed, not a reason to reopen one behind
 * their back. The provider it carries closes over the app's session store, so a token left
 * behind by a crashed launch is a small closure, not a session held open.
 */
object VncRequests {
    private val pending = ConcurrentHashMap<String, VncRequest>()

    fun put(request: VncRequest): String {
        val token = UUID.randomUUID().toString()
        pending[token] = request
        return token
    }

    fun take(token: String): VncRequest? = pending.remove(token)
}
