package dev.eclipse.ssh.ui.remotedesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.credentials.RdpCredentials
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.security.SecureClipboard
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
 * The session the tunnel rides is handed over through [RemoteDesktopRequests] rather than the
 * intent, because a [ClientSession] is a live object an intent cannot parcel. The token is
 * consumed on arrival, so a stale intent (a recents re-delivery, a crash and relaunch) finds
 * nothing to reopen and the activity finishes.
 *
 * Every variant's `sessionProvider` is a *provider* and not a session on purpose: the viewer is
 * single-shot per connection (each tunnel owns exactly one), so its Reconnect button needs a
 * fresh answer to "what session does this host have now", not the session it happened to start
 * with. Re-dialling stays in the view model, where every other dial lives; the provider here
 * only ever reports what the host already has.
 */
@AndroidEntryPoint
class RemoteDesktopActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var secureClipboard: SecureClipboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is forced on Android 15 anyway; calling it here makes pre-35 devices behave
        // the same way. The viewer supplies its own inset handling (which is: none - the desktop
        // goes under the bars and the toolbar floats above it).
        enableEdgeToEdge()
        val request = intent?.getStringExtra(EXTRA_REQUEST_TOKEN)?.let(RemoteDesktopRequests::take)
        if (request == null) {
            finish()
            return
        }
        // What a user who never touched the clipboard setting has. Taken from the model rather than
        // written out here, so a change to that default cannot leave this window falling back to a
        // number nobody chose.
        val defaultClearAfterSeconds = AppSettings().clearClipboardAfterSeconds
        setContent {
            // The app's own dark-theme setting rather than the system's, so the viewer does not
            // flip on a user who pinned one in Settings. Read once, ahead of the first frame.
            val darkTheme by produceState(initialValue = isSystemInDarkTheme()) {
                value = runCatching { settingsRepository.settings.first().darkTheme }.getOrDefault(true)
            }
            // How long a copy this window makes may sit on the clipboard, read the same way and for
            // the same reason: the viewer lands a remote machine's clipboard on the phone's, and the
            // lifetime of that copy is the user's setting, not the viewer's. A second `first()` on
            // the same store, which DataStore answers from the snapshot the theme read already
            // loaded - cheaper than threading one settings object through two windows.
            val clearAfterSeconds by produceState(initialValue = defaultClearAfterSeconds) {
                value = runCatching { settingsRepository.settings.first().clearClipboardAfterSeconds }
                    .getOrDefault(defaultClearAfterSeconds)
            }
            EclipseTheme(darkTheme = darkTheme) {
                RemoteDesktopScreen(
                    request = request,
                    secureClipboard = secureClipboard,
                    clearClipboardAfterSeconds = clearAfterSeconds,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        /** The intent extra carrying the [RemoteDesktopRequests] token. */
        const val EXTRA_REQUEST_TOKEN = "dev.eclipse.ssh.remotedesktop.REQUEST_TOKEN"
    }
}

/**
 * One remote-desktop viewing request: the endpoint as saved on the host, and where to get the
 * SSH session the tunnel rides. One variant per protocol the viewer speaks - the shared half
 * is what the shell renders (the endpoint, the session, the target's flags); the variant is
 * what the screen drives with it.
 *
 * [hostName] travels along only for the screen to say whose desktop this is - the viewer never
 * touches the profile, and saving a changed endpoint is the menu's job, not the viewer's.
 */
sealed interface RemoteDesktopRequest {
    val hostName: String
    val target: RemoteDesktopTarget
    val sessionProvider: () -> ClientSession?

    /** A VNC desktop: the RFB viewer dials the target and asks for a password when the server does. */
    data class Vnc(
        override val hostName: String,
        override val target: RemoteDesktopTarget,
        override val sessionProvider: () -> ClientSession?,
    ) : RemoteDesktopRequest

    /**
     * An RDP desktop: the FreeRDP viewer dials the target and answers NLA with what the host has
     * saved.
     *
     * [credentials] is the saved NLA credential, or null when the host has none - carried so the
     * viewer can connect without asking and pre-fill the challenge form when it has to ask anyway.
     * [credentialsComplete] says whether that credential can answer NLA on its own; the store
     * saves the whole credential or nothing, so today the two agree, but "complete" is the
     * viewer's contract rather than a fact to re-derive from the credential's shape - a future
     * store that returns partials wants no handoff change.
     */
    data class Rdp(
        override val hostName: String,
        override val target: RemoteDesktopTarget,
        override val sessionProvider: () -> ClientSession?,
        val credentials: RdpCredentials?,
        val credentialsComplete: Boolean,
    ) : RemoteDesktopRequest
}

/**
 * One-shot handoff of a [RemoteDesktopRequest] to [RemoteDesktopActivity].
 *
 * [put] mints a token and [take] consumes it, exactly like the editor's handoff: a replayed
 * intent is a request for a desktop the user already closed, not a reason to reopen one behind
 * their back. The provider it carries closes over the app's session store, so a token left
 * behind by a crashed launch is a small closure, not a session held open.
 */
object RemoteDesktopRequests {
    private val pending = ConcurrentHashMap<String, RemoteDesktopRequest>()

    fun put(request: RemoteDesktopRequest): String {
        val token = UUID.randomUUID().toString()
        pending[token] = request
        return token
    }

    fun take(token: String): RemoteDesktopRequest? = pending.remove(token)
}
