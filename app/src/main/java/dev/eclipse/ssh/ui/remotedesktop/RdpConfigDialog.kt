package dev.eclipse.ssh.ui.remotedesktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.data.model.DEFAULT_RDP_PORT
import dev.eclipse.ssh.data.model.DEFAULT_REMOTE_DESKTOP_HOST
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.data.model.isForwardHostName
import dev.eclipse.ssh.data.model.toPortOrNull

/**
 * The RDP endpoint dialog behind the host card's "RDP Viewer" item - the RDP counterpart of
 * [RemoteDesktopConfigDialog]: same fields, same "where the SSH server dials" question, different
 * default port.
 *
 * Connect is Save plus a jump into the viewer window, exactly the VNC dialog's shape: it commits
 * what the form says through [onSave] and hands the same target to [onOpen], which routes it to
 * the viewer every RDP desktop opens in.
 *
 * The saved target is read through the remote-desktop codec's `rdp` slot; the `R:` line and the
 * `V:` line beside it are one column, so an RDP save reaching the host leaves the VNC target
 * alone.
 */
@Composable
fun RdpConfigDialog(
    host: HostProfile,
    onSave: (RemoteDesktopTarget) -> Unit,
    onOpen: (RemoteDesktopTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    // The saved target, or the defaults; the dialog edits a copy and only Save commits it.
    val saved = decodeRemoteDesktop(host.remoteDesktop).rdp
    var targetHost by remember {
        mutableStateOf(saved?.host ?: DEFAULT_REMOTE_DESKTOP_HOST)
    }
    var portText by remember { mutableStateOf((saved?.port ?: DEFAULT_RDP_PORT).toString()) }
    var viewOnly by remember { mutableStateOf(saved?.viewOnly ?: false) }
    var enabled by remember { mutableStateOf(saved?.enabled ?: true) }

    val port = portText.toPortOrNull()
    val hostValid = targetHost.isForwardHostName()
    val canSave = port != null && hostValid

    fun buildTarget() = RemoteDesktopTarget(
        host = targetHost.trim(),
        port = port ?: DEFAULT_RDP_PORT,
        enabled = enabled,
        viewOnly = viewOnly,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DesktopWindows, null) },
        title = { Text("RDP on ${host.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    singleLine = true,
                    label = { Text("Host the server reaches") },
                    isError = !hostValid,
                    supportingText = {
                        if (!hostValid) Text("A hostname or address, no spaces")
                        else Text("Where the SSH server dials - 127.0.0.1 is the server itself")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    singleLine = true,
                    label = { Text("RDP port") },
                    isError = port == null,
                    supportingText = {
                        if (port == null) Text("A TCP port, 1-65535")
                        else Text("3389 is the usual RDP port")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = viewOnly, onCheckedChange = { viewOnly = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("View only")
                        Text(
                            "Watch the desktop without sending anything to it",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Offer in the menu")
                        Text(
                            "Off keeps the target saved but out of the menu",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val target = buildTarget()
                    onSave(target)
                    onOpen(target)
                },
            ) { Text("Connect") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = canSave, onClick = { onSave(buildTarget()) }) { Text("Save") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
