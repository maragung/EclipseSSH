package dev.eclipse.ssh.ui.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.EclipseTheme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The text editor in its own window, on top of the app.
 *
 * It used to be a full-screen composable layered at the root of MainActivity, which reads as a
 * panel floating over the workspace — the terminal's glow showed through its edges, and the editor
 * never felt like a place you *were*. A dedicated activity gives it what QuickEdit and other editors
 * have: its own opaque window, its own back stack entry, and a surface the keyboard resizes rather
 * than one it shares with whatever the workspace was showing.
 *
 * The file it edits is handed over through [EditorRequests] rather than the intent because an
 * [EditorRequest] carries a live [dev.eclipse.ssh.data.fs.FileSystemProvider] — an object reference,
 * not something an intent can parcel. Both activities live in one process, so the handoff is a
 * process-wide token exchange; the token is consumed on arrival, so a stale intent (a recents
 * screen re-delivery, a crash and relaunch) finds nothing to reopen and the activity finishes.
 */
@AndroidEntryPoint
class TextEditorActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 already forces edge-to-edge on Android 15, where this window's toolbar was
        // drawing under the status bar with no opt-in at all; calling it here makes pre-35 devices
        // behave the same way rather than differently for no reason. The screen below supplies its
        // own inset padding, which either way is what keeps the toolbar and the text off the bars.
        enableEdgeToEdge()
        val request = intent?.getStringExtra(EXTRA_REQUEST_TOKEN)?.let(EditorRequests::take)
        if (request == null) {
            finish()
            return
        }
        setContent {
            // The app's own dark-theme setting rather than the system's, so the editor does not flip
            // on a user who pinned one in Settings. Read once, ahead of the first frame it can affect.
            val darkTheme by produceState(initialValue = isSystemInDarkTheme()) {
                value = runCatching { settingsRepository.settings.first().darkTheme }.getOrDefault(true)
            }
            // Same shape as the theme read above: the options blob is read once, before the first
            // frame, so no frame ever composes with placeholder prefs. The decode never throws —
            // a corrupt blob yields defaults rather than a broken editor — but the read itself can
            // fail, and that failure should not take the window down.
            val prefs by produceState(initialValue = EditorPrefs()) {
                value = runCatching {
                    EditorPrefsCodec.decode(settingsRepository.settings.first().editorPrefsJson)
                }.getOrDefault(EditorPrefs())
            }
            // The sheet persists on every toggle, not on dismiss: a user who flips word wrap and
            // force-closes the app has still told us what they want, and a preference lost to an
            // unpressed OK button is a bug wearing a dialog's clothes.
            val scope = rememberCoroutineScope()
            EclipseTheme(darkTheme = darkTheme) {
                TextEditorScreen(
                    request,
                    prefs = prefs,
                    onPrefsChange = { updated ->
                        scope.launch {
                            settingsRepository.setEditorPrefsJson(EditorPrefsCodec.encode(updated))
                        }
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        /** The intent extra carrying the [EditorRequests] token. */
        const val EXTRA_REQUEST_TOKEN = "dev.eclipse.ssh.editor.REQUEST_TOKEN"
    }
}

/**
 * One-shot handoff of an [EditorRequest] to [TextEditorActivity].
 *
 * [put] mints a token and [take] consumes it, so a request can be opened exactly once: a replayed
 * intent is a request for a file the user already closed, not a reason to reopen it behind their
 * back. Entries are never evicted otherwise, but a token is a UUID nobody can guess and the map only
 * ever holds the handful of files the user opened and did not yet reach.
 */
object EditorRequests {
    private val pending = ConcurrentHashMap<String, EditorRequest>()

    fun put(request: EditorRequest): String {
        val token = UUID.randomUUID().toString()
        pending[token] = request
        return token
    }

    fun take(token: String): EditorRequest? = pending.remove(token)
}
