package dev.eclipse.ssh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isBusy
import dev.eclipse.ssh.data.model.isLive

/**
 * The colour that state should be said in: green while it is up, red when something failed, amber while
 * it is working on it, and plain body text once it is simply over.
 *
 * ERROR is worth its own colour. A shell that exited and a password that was refused both used to be
 * amber "Disconnected", and only one of those is something the user has to do something about.
 *
 * It lives here rather than in `MainActivity` because it stopped being one screen's: the session rows,
 * the tab strip and the session's own why-window all say the same state in the same colour, and the
 * why-window is a separate Activity now - a private copy of five `when` branches in each of them is
 * how one of them quietly starts disagreeing.
 */
@Composable
fun statusColor(state: SessionConnectionState, networkHeld: Boolean = false): Color = when {
    // A held session is up but unusable until the network is back, which is exactly what amber says
    // everywhere else in this app. Green would invite the user to type into it.
    networkHeld && state.isLive -> EclipseWarning
    state.isLive -> EclipseSuccess
    state == SessionConnectionState.ERROR -> MaterialTheme.colorScheme.error
    state.isBusy -> EclipseWarning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
