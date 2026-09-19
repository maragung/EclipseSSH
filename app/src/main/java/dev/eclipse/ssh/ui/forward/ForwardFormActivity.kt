package dev.eclipse.ssh.ui.forward

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * The Add-port-forward form, in its own window.
 *
 * It used to be an `AlertDialog` over the workspace, and the reason it is not one any more is the one
 * every other Settings entry was promoted for: a dialog's body is capped at about 70% of the screen
 * height, and a form whose fields change with the type it is set to - three of them for Local, two
 * for Remote, one for Dynamic - spends that budget on chrome. As a window it gets its own back stack
 * entry and the keyboard resizes a screen instead of a letterbox.
 *
 * What stays behind is the *list*. The running forwards and their Stop buttons remain the live status
 * row they were, because a list of what is running right now is a status, not a screen - and because
 * the form and that list answer different questions: one asks what to open, the other says what is
 * open. Only the asking moved.
 *
 * The host is named at launch rather than resolved on return, so the forward lands on the session the
 * user was looking at when they pressed Add - see [ForwardRequest]. Resolution back to a host happens
 * on the workspace's side, where a host deleted in the meantime is one `firstOrNull` away from being
 * reported instead of silently forwarding to somebody else's server.
 */
@AndroidEntryPoint
class ForwardFormActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID)
        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME)
        if (hostId == null) {
            // No host to forward through - a re-delivery from the recents screen, or a caller that
            // forgot the extra. Nothing here can be started, and an empty form would be a lie.
            finish()
            return
        }
        setContent {
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = "Add port forward",
                onClose = { finish() },
            ) {
                ForwardForm(
                    hostName = hostName,
                    onStart = { request ->
                        ForwardRequests.confirm(request)
                        finish()
                    },
                    hostId = hostId,
                )
            }
        }
    }

    companion object {
        /** The host this forward will ride, by id, and its name for the form to show. */
        const val EXTRA_HOST_ID = "dev.eclipse.ssh.forward.HOST_ID"
        const val EXTRA_HOST_NAME = "dev.eclipse.ssh.forward.HOST_NAME"

        /** The intent that opens the form on [hostId], named as the user sees it. */
        fun intent(context: Context, hostId: String, hostName: String): Intent =
            Intent(context, ForwardFormActivity::class.java)
                .putExtra(EXTRA_HOST_ID, hostId)
                .putExtra(EXTRA_HOST_NAME, hostName)
    }
}

/**
 * The form itself: the three chips, the fields they decide, and Start.
 *
 * The fields are the ones the dialog had, with two changes that are both about the type rather than
 * about the widget. Remote no longer offers a destination host, because a remote forward's destination
 * is this device - the field existed and was ignored, which is worse than absent. And every field now
 * reports what is wrong with it, in place, instead of leaving Start greyed out for a reason the user
 * has to guess: the button is disabled for the same conditions, but the field in question says so.
 *
 * Start hands back a [ForwardRequest] rather than starting anything. Opening the forward is the
 * workspace's business - it owns the sessions and the forwarding state - and this window's job ends
 * at "this is what the user asked for", which is also what makes the form testable without a session.
 */
@Composable
private fun ForwardForm(
    hostId: String,
    hostName: String?,
    onStart: (ForwardRequest) -> Unit,
) {
    var state by remember { mutableStateOf(ForwardFormState()) }
    val request = state.requestFor(hostId)
    // A mistake is only worth naming once the user has started telling us something. Every field of a
    // form the user has not touched yet is empty, and a window that opens with three red sentences
    // under three empty boxes is a form that greets people by complaining about them.
    val touched = state.localPort.isNotBlank() || state.remoteHost.isNotBlank() || state.remotePort.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (hostName.isNullOrBlank()) {
                "Opens a tunnel through the session that is connected"
            } else {
                "Through $hostName"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForwardType.entries.forEach { type ->
                FilterChip(
                    selected = state.type == type,
                    // Switching type keeps what was typed: the two ports mean different things per
                    // type but the values are the user's own, and clearing a field they just filled
                    // in is the form arguing with them.
                    onClick = { state = state.copy(type = type) },
                    label = { Text(type.label) },
                    modifier = Modifier.semantics { contentDescription = "${type.label} forward" },
                )
            }
        }
        FormField(
            value = state.localPort,
            onValueChange = { state = state.copy(localPort = it) },
            label = localPortLabel(state.type),
            error = state.localPortError.takeIf { touched },
            description = "Local port",
        )
        when (state.type) {
            ForwardType.LOCAL -> {
                FormField(
                    value = state.remoteHost,
                    onValueChange = { state = state.copy(remoteHost = it) },
                    label = "Remote host",
                    error = state.remoteHostError.takeIf { touched },
                    description = "Remote host",
                )
                FormField(
                    value = state.remotePort,
                    onValueChange = { state = state.copy(remotePort = it) },
                    label = "Remote port",
                    error = state.remotePortError.takeIf { touched },
                    description = "Remote port",
                )
            }
            ForwardType.REMOTE -> FormField(
                value = state.remotePort,
                onValueChange = { state = state.copy(remotePort = it) },
                label = "Remote bind port",
                error = state.remotePortError.takeIf { touched },
                description = "Remote bind port",
            )
            ForwardType.DYNAMIC -> Text(
                "Creates a SOCKS5 proxy on the local port for on-demand tunnelling.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            describe(state.type),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { request?.let(onStart) },
            enabled = request != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics { contentDescription = "Start forward" },
        ) { Text("Start") }
    }
}

/**
 * One field, and the sentence under it when it is wrong.
 *
 * [error] is drawn as its own line rather than handed to the field's `supportingText` slot for a
 * reason worth writing down: Material reserves that slot's height whenever it is non-null, so a form
 * with nothing wrong with it would still carry three blank lines of it. Here the line exists only when
 * there is something to say, and the field turns red in the same breath.
 */
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .semantics { contentDescription = "$description error" },
            )
        }
    }
}

/**
 * What the port field is asking for, which is the one thing that changes with the type.
 *
 * A Remote forward's two ports are the pair most easily swapped - the phone-side port is the one the
 * *server* is connected to, and the server's bind port is the one that is listened on over there - so
 * the labels say which is which rather than leaving "Local port" to be read as "the port on the
 * server's side", which is exactly the sentence that starts the confusion.
 */
private fun localPortLabel(type: ForwardType): String = when (type) {
    ForwardType.LOCAL -> "Local port"
    ForwardType.REMOTE -> "Local destination port"
    ForwardType.DYNAMIC -> "Local port"
}

/** One sentence on what pressing Start will open, in the type's own terms. */
private fun describe(type: ForwardType): String = when (type) {
    ForwardType.LOCAL -> "Listens on this device and forwards every connection to the remote host."
    ForwardType.REMOTE -> "Asks the server to listen on the bind port and forward to this device."
    ForwardType.DYNAMIC -> "A SOCKS5 proxy: the application chooses where each connection goes."
}
