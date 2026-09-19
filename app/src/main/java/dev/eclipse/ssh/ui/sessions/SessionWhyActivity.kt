package dev.eclipse.ssh.ui.sessions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.isBusy
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.presentation.sessionDiagnostics
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.ssh.SessionDiagnostics
import dev.eclipse.ssh.ssh.scrub
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.rememberDialogBodyMaxHeight
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import dev.eclipse.ssh.ui.statusColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Why *this* session is in the state it is in, in a window of its own.
 *
 * It used to be a `ModalBottomSheet` at the root of MainActivity, and the reason it is not one any
 * more is the reason this app has been promoting sheets all along: a sheet covers the thing it
 * describes and then hides most of itself. This one describes a session that is failing *right now*,
 * with a trace that is still being written, and it was showing that trace through a slot capped at
 * 45% of a panel that was itself capped at 85% of the display.
 *
 * The subject (the tab) travels through [dev.eclipse.ssh.ui.actions.ActionRequests] rather than the
 * intent, because a [SessionTab] is assembled by the workspace's own view model and a screenshot of
 * it is not a thing an intent carries. The trace is *not* in that subject, and that is the point of
 * promoting this surface at all: the ring is a singleton this window observes directly, so the lines
 * keep arriving while the user reads them. A reconnect ladder climbing is exactly when somebody opens
 * this, and a frozen copy of a ladder would be a window that lies about the thing it exists to show.
 *
 * A token that is already spent finishes the window. It means the system re-delivered an intent from
 * before, and a stale explanation of a session the user has since closed is not something to reopen
 * on their behalf.
 *
 * The manifest gives this the editor's own `configChanges` list, which is load-bearing rather than a
 * habit: a rotation that recreated this window would find its token spent and close itself.
 */
@AndroidEntryPoint
class SessionWhyActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var diagnostics: SessionDiagnostics

    @Inject lateinit var secureClipboard: SecureClipboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val subject = ActionRequests.take(intent?.getStringExtra(ActionRequests.EXTRA_SUBJECT_TOKEN))
        val tab = (subject as? ActionSubject.SessionWhy)?.tab
        if (tab == null) {
            finish()
            return
        }
        setContent {
            val scope = rememberCoroutineScope()
            // Collected rather than read once: see the class doc for why the trace is the live half.
            val events by diagnostics.events.collectAsStateWithLifecycle()
            val trace = remember(events, tab.hostId) {
                sessionDiagnostics(events, diagnostics.sessionLabels[tab.hostId])
            }
            val clock = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
            val reason = remember(tab.lastError) { tab.lastError?.let(::scrub) }

            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                // The session's own name, not "Why?": the window has to say *which* session it is
                // explaining, and it is opened from a tab strip where several of them are listed.
                title = tab.title,
                onClose = { finish() },
                // The trace is a LazyColumn and scrolls itself; a second scroller over it throws on
                // the first measure. See SettingsWindow.
                scrollable = false,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        when {
                            tab.state == SessionConnectionState.RECONNECTING -> "Why it is reconnecting"
                            tab.state.isBusy -> "What it is waiting for"
                            else -> "Why it ended"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        reason ?: "No reason was recorded for this session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor(tab.state, tab.networkHeld),
                    )
                    if (trace.isEmpty()) {
                        Text(
                            "Nothing has been recorded for this session yet.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "This session's last ${trace.size} event(s), newest first. No password, key or " +
                                "host name is recorded, so this is safe to attach to a bug report.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(
                            // Fills whatever the screen has under the two paragraphs above, up to
                            // 60% of it: the sheet could only ever offer 45% of a panel, and the
                            // whole reason this is a window is that a trace is worth reading.
                            Modifier.weight(1f).heightIn(max = rememberDialogBodyMaxHeight(0.6f)),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(trace, key = { it.sequence }) { entry ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text(
                                            clock.format(Instant.ofEpochMilli(entry.atMs)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            // The epoch millis at the front of `line()` is for the
                                            // exported file; the clock above says the same thing to a
                                            // reader.
                                            entry.line().substringAfter(' '),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                // Oldest first in the copy, the opposite of the display: on screen the
                                // answer wanted is the last thing that happened, and in a pasted
                                // report the reader needs to follow the session forwards.
                                val text = buildString {
                                    append(tab.state.name)
                                    reason?.let { append(" · ").append(it) }
                                    trace.asReversed().forEach { append('\n').append(it.line()) }
                                }
                                // The window's own copy rather than a handoff back to the workspace:
                                // the clipboard is a singleton and the clear-after delay is a
                                // setting, so both halves are already here. A `Close` button is
                                // deliberately absent - a window has a back arrow, and the sheet
                                // needed one only because a sheet does not.
                                scope.launch {
                                    val seconds = settingsRepository.settings.first().clearClipboardAfterSeconds
                                    secureClipboard.copy(text, seconds)
                                }
                            },
                        ) { Text("Copy") }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * The intent that opens this window on [tab].
         *
         * The tab is snapshotted into the request rather than parcelled, so the token is the whole
         * of what the intent carries.
         */
        fun intent(context: Context, tab: SessionTab): Intent = Intent(context, SessionWhyActivity::class.java)
            .putExtra(
                ActionRequests.EXTRA_SUBJECT_TOKEN,
                ActionRequests.put(ActionSubject.SessionWhy(tab)),
            )
    }
}
