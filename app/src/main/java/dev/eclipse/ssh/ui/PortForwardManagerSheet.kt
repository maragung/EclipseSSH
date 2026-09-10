package dev.eclipse.ssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.data.model.DEFAULT_FORWARD_LISTEN_HOST
import dev.eclipse.ssh.data.model.MAX_SAVED_FORWARDS
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardRuntime
import dev.eclipse.ssh.data.model.ForwardStatus
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.conflictsWith
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.describe
import dev.eclipse.ssh.data.model.isForwardHostName
import dev.eclipse.ssh.data.model.toPortOrNull
import java.util.UUID

/**
 * The per-host port-forwarding manager: every rule the host has saved, what each one is doing right
 * now, and the controls that change either.
 *
 * The rules come from [HostProfile.savedForwards] decoded fresh on every recomposition rather than
 * from a copy held at open time, because saving a rule *is* the edit this sheet makes - a list that
 * kept showing the pre-save state after its own Save button would be a sheet arguing with itself.
 * Runtime state comes from the UI state's statuses map, keyed by entry id, and a rule this process
 * has never touched (the host was edited elsewhere, or has not connected yet) falls back to what the
 * rule itself says it is: Disabled or Stopped. That fallback is what makes the sheet worth opening
 * on a host that is not connected - the list is the rules, and Start is how a disconnected host
 * answers (with the engine's "is not connected" report, which is that path's own message).
 *
 * Every structural change - enable, disable, edit, delete - goes through [onSaveRules] as a whole
 * new list, so the engine's own save path is the only writer of the column and its
 * stop-the-removed-keep-the-unchanged behaviour applies to the sheet exactly as it applies to a
 * connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardManagerSheet(
    host: HostProfile,
    statuses: Map<String, ForwardStatus>,
    /**
     * The forwards that are actually bound right now, hand-opened ones included, from the UI state's
     * running list. The add/edit dialog checks a candidate rule against these, because two hosts'
     * rules never meet in one list - the conflict the engine pre-checks at start time is better
     * caught in the form, before anything is saved.
     */
    runningForwards: List<ForwardEntry>,
    onDismiss: () -> Unit,
    onStartRule: (String) -> Unit,
    onStopRule: (String) -> Unit,
    onSaveRules: (List<ForwardEntry>) -> Unit,
) {
    // Re-derived whenever the saved column changes, which is whenever this sheet's own saves land -
    // see the class comment for why the list must not be a copy held at open time.
    val rules = remember(host.savedForwards, host.id) { decodeForwardRules(host.savedForwards, host.id) }
    // The dialog is open over the sheet, not instead of it, so the sheet's rows stay where they
    // were; `editingRuleId` is null for an add, and the rule's id for an edit. Both are keyed on the
    // host so a stale dialog can never write one host's rule into another host's list.
    var editorOpen by remember(host.id) { mutableStateOf(false) }
    var editingRuleId by remember(host.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 22.dp)
                // The host may legally carry 32 rules, and each row is two lines plus its actions,
                // so the body scrolls. HostDetailsSheet has no scroller only because nothing in it
                // repeats.
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Text("Port forwarding", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${host.username}@${host.host}:${host.port}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (rules.isEmpty()) {
                // One sentence, because the three kinds each get their own explanation the moment a
                // rule exists to show it on: this line only has to say what the door is for.
                Text(
                    "Port forwarding tunnels connections between this device and the server - a local " +
                        "rule reaches a service through the host, a remote rule opens something on this " +
                        "device to it, and a dynamic rule is a SOCKS5 proxy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            } else {
                rules.forEach { rule ->
                    ForwardRuleRow(
                        // A rule never started this process has no recorded state, and the honest
                        // thing to say about it is what the rule itself says: Disabled when it is
                        // switched off, Stopped when it simply has not been started.
                        status = statuses[rule.id]
                            ?: ForwardStatus(rule, if (rule.enabled) ForwardRuntime.STOPPED else ForwardRuntime.DISABLED),
                        onToggleEnabled = { on ->
                            onSaveRules(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it })
                        },
                        onStart = { onStartRule(rule.id) },
                        onStop = { onStopRule(rule.id) },
                        onEdit = { editingRuleId = rule.id; editorOpen = true },
                        onDelete = { onSaveRules(rules.filterNot { it.id == rule.id }) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            // Capped rather than left open because the column itself is capped (MAX_SAVED_FORWARDS),
            // and a 33rd rule would be a button that saves a list the codec silently truncates.
            TextButton(
                onClick = { editingRuleId = null; editorOpen = true },
                enabled = rules.size < MAX_SAVED_FORWARDS,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add rule") }
        }
    }

    if (editorOpen) {
        ForwardRuleDialog(
            hostId = host.id,
            initial = rules.firstOrNull { it.id == editingRuleId },
            // The whole current list, not the list minus the edited rule: an edited candidate keeps
            // the rule it came from, and `conflictsWith` compares ids so that rule never counts as
            // its own conflict.
            others = rules,
            runningForwards = runningForwards,
            onDismiss = { editorOpen = false },
            onSave = { entry ->
                editorOpen = false
                // Replacement is by id and in place, so an edit is not also a reorder - a reorder
                // changes every shifted rule's derived id and restarts those tunnels.
                val next = if (editingRuleId == null) {
                    rules + entry
                } else {
                    rules.map { if (it.id == editingRuleId) entry else it }
                }
                onSaveRules(next)
            },
        )
    }
}

/**
 * The colour a forward's state should be said in. Green for up, amber for on its way anywhere
 * (starting, or waiting out a reconnect), red for failed, plain for over or never started - the same
 * vocabulary [dev.eclipse.ssh.ui] already uses for sessions.
 */
@Composable
private fun forwardStateColor(state: ForwardRuntime) = when (state) {
    ForwardRuntime.RUNNING -> EclipseSuccess
    ForwardRuntime.STARTING, ForwardRuntime.RECONNECTING -> EclipseWarning
    ForwardRuntime.FAILED -> MaterialTheme.colorScheme.error
    ForwardRuntime.STOPPED, ForwardRuntime.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * One saved rule: what it does ([ForwardEntry.describe]), what it is doing right now, and the four
 * things a user can do to it. [ForwardEntry.describe] already leads with the rule's label, so the
 * name is not repeated on its own line - two lines saying "Database: localhost:5432 → …" and
 * "Database" would be one line of information wearing a hat.
 */
@Composable
private fun ForwardRuleRow(
    status: ForwardStatus,
    onToggleEnabled: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val entry = status.entry
    val stateColor = forwardStateColor(status.state)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(stateColor))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.describe(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(status.state.label, style = MaterialTheme.typography.labelMedium, color = stateColor)
                // The engine's own sentence, verbatim: a bind failure or the fixed conflict wording,
                // whatever it said, is the explanation this row owes for being red.
                if (status.state == ForwardRuntime.FAILED && !status.error.isNullOrBlank()) {
                    Text(
                        status.error,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // The enabled switch rather than a menu item, because enabled is a state the row is in
            // and not an action the user takes: it reads as a toggle everywhere else in this app.
            Switch(checked = entry.enabled, onCheckedChange = onToggleEnabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // RECONNECTING is grouped with the running states on purpose: it is a recorded
            // RUNNING/STARTING whose session died under it, so a handle may still exist and Stop is
            // the honest control - a Start offered here would race the reconnect's own rebind.
            if (status.state == ForwardRuntime.RUNNING ||
                status.state == ForwardRuntime.STARTING ||
                status.state == ForwardRuntime.RECONNECTING
            ) {
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                TextButton(onClick = onStart) { Text("Start") }
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * Add or edit one rule. The shape of the form follows [ForwardType]: a LOCAL rule listens on this
 * device and points at a host reachable from the server, a REMOTE rule listens on the server and
 * points back at this device, and a DYNAMIC rule is only a listener.
 *
 * Ports are kept as text until Save because that is what an OutlinedTextField holds; `toPortOrNull`
 * is the validation, and it covers blank, non-numeric, zero and out-of-range with one null. The
 * bind interface is validated with `isForwardHostName` - it is a name the rule will be bound to,
 * nothing more - and `0.0.0.0` is *allowed* while being warned about, because a rule that says it
 * is what the user wrote out themselves and the engine will honour it; the codec's notes on the
 * bind slot are the long version of why nothing fills it in for them.
 */
@Composable
private fun ForwardRuleDialog(
    hostId: String,
    initial: ForwardEntry?,
    others: List<ForwardEntry>,
    runningForwards: List<ForwardEntry>,
    onDismiss: () -> Unit,
    onSave: (ForwardEntry) -> Unit,
) {
    var type by remember { mutableStateOf(initial?.type ?: ForwardType.LOCAL) }
    var listenHost by remember { mutableStateOf(initial?.listenHost ?: DEFAULT_FORWARD_LISTEN_HOST) }
    // One field per question, mapped onto the entry's side-named ports at Save: `listenPort` is the
    // LOCAL/DYNAMIC listener and also the REMOTE destination, `serverPort` is the REMOTE listener,
    // `destinationHost`/`destinationPort` are where the tunnel points - the server's network for
    // LOCAL, this device for REMOTE.
    var listenPort by remember { mutableStateOf(initial?.localPort?.toString().orEmpty()) }
    var serverPort by remember {
        mutableStateOf(initial?.takeIf { it.type == ForwardType.REMOTE }?.remotePort?.toString().orEmpty())
    }
    var destinationHost by remember {
        mutableStateOf(
            when (initial?.type) {
                // The edited rule's own destination, whichever side it pointed at.
                ForwardType.LOCAL -> initial?.remoteHost.orEmpty()
                // `ssh -R`'s default destination is the phone's own loopback, and the field shows it
                // rather than an empty box the user has to know the default for.
                ForwardType.REMOTE -> initial?.localHost ?: DEFAULT_FORWARD_LISTEN_HOST
                else -> ""
            },
        )
    }
    var destinationPort by remember {
        mutableStateOf(
            when (initial?.type) {
                ForwardType.LOCAL -> initial?.remotePort?.toString().orEmpty()
                ForwardType.REMOTE -> initial?.localPort?.toString().orEmpty()
                else -> ""
            },
        )
    }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var autoStart by remember { mutableStateOf(initial?.autoStart ?: true) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    fun selectType(next: ForwardType) {
        type = next
        // Switching to REMOTE with nothing typed in the destination gives it the default the entry
        // would carry anyway, so the field never asks a question it has already answered.
        if (next == ForwardType.REMOTE && destinationHost.isBlank()) destinationHost = DEFAULT_FORWARD_LISTEN_HOST
    }

    val listenPortNum = listenPort.toPortOrNull()
    val serverPortNum = serverPort.toPortOrNull()
    val destinationPortNum = destinationPort.toPortOrNull()
    val destinationHostValid = destinationHost.trim().isForwardHostName()
    val listenHostValid = listenHost.trim().isForwardHostName()
    // Per type, because the types do not share fields: LOCAL wants a listener, a destination host
    // and a destination port, REMOTE wants the server's port and the phone-side destination, and
    // DYNAMIC wants nothing but the listener. (For REMOTE, `listenPort` - the field - is not the
    // entry's localPort: there the destination port is the phone-side port and the server port is
    // the listener.)
    val formValid = listenHostValid && when (type) {
        ForwardType.LOCAL -> listenPortNum != null && destinationHostValid && destinationPortNum != null
        ForwardType.REMOTE -> serverPortNum != null && destinationHostValid && destinationPortNum != null
        ForwardType.DYNAMIC -> listenPortNum != null
    }
    // Built only when the fields parse, so the conflict check never runs against a half-typed rule;
    // the non-null assertions are exactly what `formValid` above has already established.
    val candidate = if (!formValid) null else ForwardEntry(
        // The edited rule's id, kept rather than regenerated: `conflictsWith` excludes by id,
        // which is how the rule being edited never conflicts with itself.
        id = initial?.id ?: UUID.randomUUID().toString(),
        type = type,
        localPort = (if (type == ForwardType.REMOTE) destinationPortNum else listenPortNum)!!,
        remoteHost = if (type == ForwardType.LOCAL) destinationHost.trim() else null,
        remotePort = when (type) {
            ForwardType.LOCAL -> destinationPortNum!!
            ForwardType.REMOTE -> serverPortNum!!
            ForwardType.DYNAMIC -> null
        },
        hostId = hostId,
        listenHost = listenHost.trim(),
        localHost = if (type == ForwardType.REMOTE) destinationHost.trim() else null,
        enabled = enabled,
        autoStart = autoStart,
        name = name.trim().takeIf(String::isNotBlank),
    )
    // The engine's own conflict sentence, so the form's refusal and the row's failure message are
    // the same words for the same mistake. Remote rules never conflict here - their listener is on
    // the server, and the server is the authority on that bind.
    val conflict = candidate?.let { entry ->
        val inThisList = others.any { entry.conflictsWith(it) }
        val alreadyRunning = runningForwards.any { entry.conflictsWith(it) }
        if (inThisList || alreadyRunning) "Port ${entry.localPort} is already in use by another forwarding rule." else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add forwarding rule" else "Edit forwarding rule") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForwardType.entries.forEach { t ->
                        // The enum's own label says "Dynamic (SOCKS5)", which is a mouthful for a
                        // chip; the short word is what the three chips are for.
                        FilterChip(selected = type == t, onClick = { selectType(t) }, label = {
                            Text(
                                when (t) {
                                    ForwardType.LOCAL -> "Local"
                                    ForwardType.REMOTE -> "Remote"
                                    ForwardType.DYNAMIC -> "Dynamic"
                                },
                            )
                        })
                    }
                }
                OutlinedTextField(
                    listenHost,
                    { listenHost = it },
                    label = { Text(if (type == ForwardType.REMOTE) "Listen interface (server side)" else "Listen interface") },
                    singleLine = true,
                    isError = !listenHostValid,
                    supportingText = if (!listenHostValid) {
                        { Text("Enter a hostname or address, or leave the default loopback.") }
                    } else {
                        null
                    },
                )
                when (type) {
                    ForwardType.LOCAL -> {
                        OutlinedTextField(
                            listenPort,
                            { listenPort = it },
                            label = { Text("Listen port") },
                            singleLine = true,
                            isError = listenPortNum == null,
                            supportingText = if (listenPortNum == null) {{ Text("A port from 1 to 65535.") }} else null,
                        )
                        OutlinedTextField(
                            destinationHost,
                            { destinationHost = it },
                            label = { Text("Destination host") },
                            singleLine = true,
                            isError = !destinationHostValid,
                            supportingText = if (!destinationHostValid) {
                                { Text("The host to reach from the server.") }
                            } else {
                                null
                            },
                        )
                        OutlinedTextField(
                            destinationPort,
                            { destinationPort = it },
                            label = { Text("Destination port") },
                            singleLine = true,
                            isError = destinationPortNum == null,
                            supportingText = if (destinationPortNum == null) {{ Text("A port from 1 to 65535.") }} else null,
                        )
                    }
                    ForwardType.REMOTE -> {
                        OutlinedTextField(
                            serverPort,
                            { serverPort = it },
                            label = { Text("Server port") },
                            singleLine = true,
                            isError = serverPortNum == null,
                            supportingText = if (serverPortNum == null) {{ Text("A port from 1 to 65535.") }} else null,
                        )
                        OutlinedTextField(
                            destinationHost,
                            { destinationHost = it },
                            label = { Text("Destination host (on this device)") },
                            singleLine = true,
                            isError = !destinationHostValid,
                            supportingText = if (!destinationHostValid) {
                                { Text("The phone-side address the server connects to.") }
                            } else {
                                null
                            },
                        )
                        OutlinedTextField(
                            destinationPort,
                            { destinationPort = it },
                            label = { Text("Destination port") },
                            singleLine = true,
                            isError = destinationPortNum == null,
                            supportingText = if (destinationPortNum == null) {{ Text("A port from 1 to 65535.") }} else null,
                        )
                    }
                    ForwardType.DYNAMIC -> {
                        OutlinedTextField(
                            listenPort,
                            { listenPort = it },
                            label = { Text("Listen port") },
                            singleLine = true,
                            isError = listenPortNum == null,
                            supportingText = if (listenPortNum == null) {{ Text("A port from 1 to 65535.") }} else null,
                        )
                        Text(
                            "Creates a SOCKS5 proxy on the listen port for on-demand tunneling.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Shown for every type: a listener on 0.0.0.0 is reachable from other machines
                // whichever side of the tunnel it lands on, and a SOCKS proxy open to the network is
                // the worst case of the three. The interface is still accepted - it is the user's
                // rule to write - but it is never filled in by anything, and it is never silent.
                if (listenHost.trim() == "0.0.0.0") {
                    Text(
                        "Warning: This may expose the forwarded service to other machines.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EclipseWarning,
                    )
                }
                OutlinedTextField(
                    name,
                    // The codec drops a label longer than its own cap at decode time, so a longer
                    // one could be saved and then silently not come back; capped at the source, the
                    // field and the codec agree.
                    { name = it.take(64) },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A disabled rule is stored and listed, and never started.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start automatically", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Starts with the connection; off means by hand from the manager.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoStart, onCheckedChange = { autoStart = it })
                }
                if (conflict != null) {
                    Text(conflict, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { candidate?.let(onSave) }, enabled = candidate != null && conflict == null) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
