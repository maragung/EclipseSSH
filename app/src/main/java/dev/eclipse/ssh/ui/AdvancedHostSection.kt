package dev.eclipse.ssh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.ENVIRONMENT_MAX_LENGTH
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.MAX_SAVED_FORWARDS
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.STARTUP_COMMAND_MAX_LENGTH
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_TYPE_CHOICES
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.describe
import dev.eclipse.ssh.data.model.toPortOrNull
import dev.eclipse.ssh.presentation.AdvancedHostOptions
import dev.eclipse.ssh.ssh.AlgorithmKind
import dev.eclipse.ssh.ssh.AlgorithmListReview
import dev.eclipse.ssh.ssh.supportedAlgorithmNames

/**
 * The Advanced section of the Add / Edit Host form.
 *
 * Open by default, which is a deliberate reversal. It was collapsed so that a wall of switches would not
 * bury the four fields that decide whether a host connects at all - sound reasoning that turned out to
 * have the more expensive failure mode: a per-host setting nobody can see is a per-host setting nobody
 * knows exists, and every one of these was asked for again after it had already shipped. A section that
 * is open still keeps its heading, still collapses, and still remembers which way the user left it; a
 * section that is hidden teaches the user it is not there.
 *
 * Still a section, and still ordered, because the list is now long: Connection, Keep-alive, Reconnect,
 * Terminal, Authentication, Algorithms, Session start, Port forwarding. Every control carries one line
 * saying what it does to this connection, because a setting whose effect has to be guessed is one a user
 * changes once and then cannot un-change with any confidence.
 *
 * Holds no rules - [AdvancedHostOptions] owns validation, defaults and the mapping onto the profile, so
 * that all of it stays testable on the JVM. See its KDoc for the requested settings MINA cannot apply
 * per host, and why they are not here.
 */
@Composable
internal fun AdvancedHostSection(
    options: AdvancedHostOptions,
    onChange: (AdvancedHostOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Survives rotation, so a section closed - or left open - before a config change is in the same
    // state after it, and an option changed on the far side of one is not re-hidden with it.
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Advanced",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (!expanded && !options.isDefault) {
                Text(
                    "customised",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide advanced options" else "Show advanced options",
            )
        }
        if (!expanded) return@Column
        Text(
            "These apply to this host only, and take effect on its next connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Connection")
        OptionSwitch(
            checked = options.compression,
            onCheckedChange = { onChange(options.copy(compression = it)) },
            title = "Compression",
            detail = if (options.compression) {
                "Compresses the stream after login. Helps on a slow link, costs CPU on both ends."
            } else {
                "Off, which is right on anything but a slow or metered link."
            },
        )
        NumberField(
            value = options.authTimeoutSeconds,
            onValueChange = { onChange(options.copy(authTimeoutSeconds = it)) },
            label = "Authentication timeout (s)",
            valid = options.authTimeoutValid,
            range = AUTH_TIMEOUT_RANGE,
            help = "How long the server has to accept or refuse the login, after the socket is up. " +
                "Raise it for a host that asks for a one-time code.",
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Keep-alive")
        OptionSwitch(
            checked = options.keepAliveEnabled,
            onCheckedChange = { onChange(options.copy(keepAliveEnabled = it)) },
            title = "Send keep-alives",
            detail = if (options.keepAliveEnabled) {
                "Asks the server for a reply on the interval above, which holds the connection open " +
                    "through a router and notices a link that has died."
            } else {
                "Nothing is sent and nothing is expected back. The idle timeout is switched off with " +
                    "it, so a quiet session is never assumed dead - but neither is a broken one, " +
                    "until you type."
            },
        )
        NumberField(
            value = options.serverAliveCountMax,
            onValueChange = { onChange(options.copy(serverAliveCountMax = it)) },
            label = "Missed replies allowed",
            valid = options.serverAliveCountValid,
            range = SERVER_ALIVE_COUNT_RANGE,
            enabled = options.keepAliveEnabled,
            help = "Unanswered keep-alives before the session is treated as gone. " +
                "OpenSSH calls this ServerAliveCountMax.",
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Reconnect")
        OptionSwitch(
            checked = options.autoReconnect,
            onCheckedChange = { onChange(options.copy(autoReconnect = it)) },
            title = "Reconnect automatically",
            detail = if (options.autoReconnect) {
                "A dropped connection is retried with a growing delay. A shell you exited is not."
            } else {
                "A drop leaves the tab disconnected with the reason on it, and waits for you."
            },
        )
        NumberField(
            value = options.maxReconnectAttempts,
            onValueChange = { onChange(options.copy(maxReconnectAttempts = it)) },
            label = "Attempts",
            valid = options.maxReconnectAttemptsValid,
            range = MAX_RECONNECT_ATTEMPTS_RANGE,
            enabled = options.autoReconnect,
            help = "Consecutive tries before the app stops and says why. " +
                "The allowance comes back once a session stays up for five minutes.",
            modifier = Modifier.fillMaxWidth(),
        )
        NumberField(
            value = options.reconnectBackoffSeconds,
            onValueChange = { onChange(options.copy(reconnectBackoffSeconds = it)) },
            label = "First delay (s)",
            valid = options.backoffValid,
            range = RECONNECT_BACKOFF_RANGE,
            enabled = options.autoReconnect,
            help = "Each attempt roughly doubles it, plus jitter. Blank uses the app-wide delay " +
                "from Settings.",
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Terminal")
        OptionSwitch(
            checked = options.usePty,
            onCheckedChange = { onChange(options.copy(usePty = it)) },
            title = "Request a terminal (PTY)",
            detail = if (options.usePty) {
                "The normal choice: line editing, colours, window size, and full-screen programs work."
            } else {
                "A bare stream with no terminal. Only for a host you script rather than type at - " +
                    "there is no echo and no cursor control."
            },
        )
        Text(
            "Terminal type · what \$TERM becomes on the server",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TERMINAL_TYPE_CHOICES.forEach { type ->
                FilterChip(
                    selected = options.terminalType == type,
                    onClick = { onChange(options.copy(terminalType = type)) },
                    enabled = options.usePty,
                    label = { Text(type) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                value = options.terminalColumns,
                onValueChange = { onChange(options.copy(terminalColumns = it)) },
                label = "Columns",
                valid = options.columnsValid,
                range = TERMINAL_COLUMNS_RANGE,
                enabled = options.usePty,
                help = null,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = options.terminalRows,
                onValueChange = { onChange(options.copy(terminalRows = it)) },
                label = "Rows",
                valid = options.rowsValid,
                range = TERMINAL_ROWS_RANGE,
                enabled = options.usePty,
                help = null,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Leave both blank to match the screen, which is what makes top and vim draw correctly. " +
                "A width wider than the screen is wrapped at word boundaries and stays readable; a " +
                "height taller than the screen cannot be, so rows are capped at what fits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Security")
        OptionSwitch(
            checked = options.keyboardInteractiveAuth,
            onCheckedChange = { onChange(options.copy(keyboardInteractiveAuth = it)) },
            title = "Offer keyboard-interactive",
            detail = if (options.keyboardInteractiveAuth) {
                "Lets the server prompt: a one-time code, a PAM question, a password change."
            } else {
                "Only the key and the password are offered. An account that needs a code cannot log in."
            },
        )
        OptionSwitch(
            checked = options.agentForwarding,
            onCheckedChange = { onChange(options.copy(agentForwarding = it)) },
            title = "Allow SSH agent forwarding",
            detail = if (options.agentForwarding) {
                "Shells on this host may use the keys saved in this app to sign onward challenges. " +
                    "The server's administrator can request signatures as you while a session with " +
                    "forwarding is open. A remote ssh-add cannot load keys onto this phone."
            } else {
                "The server cannot ask this phone to sign anything on its behalf. Turn on only for " +
                    "hosts you administer yourself."
            },
        )
        Text(
            "Unknown host key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostKeyPolicy.entries.forEach { policy ->
                FilterChip(
                    selected = options.hostKeyPolicy == policy,
                    onClick = { onChange(options.copy(hostKeyPolicy = policy)) },
                    label = { Text(policy.label) },
                )
            }
        }
        Text(
            options.hostKeyPolicy.detail + " A key that *changes* is always refused and always reported, " +
                "whichever of these is chosen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Legacy algorithms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LEGACY_CHOICES.forEach { (label, value) ->
                FilterChip(
                    selected = options.legacyAlgorithms == value,
                    onClick = { onChange(options.copy(legacyAlgorithms = value)) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            when (options.legacyAlgorithms) {
                null -> "Follows the app-wide switch in Settings."
                true -> "Adds the older ciphers, MACs and key exchanges some appliances and old " +
                    "servers still require. Weaker, and only for this host."
                false -> "Modern algorithms only for this host, whatever Settings says."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Algorithms")
        Text(
            "Leave these empty unless a server needs otherwise - empty means the library negotiates, " +
                "which is right for almost every host. Order matters: the first name both ends know wins.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AlgorithmField(
            kind = AlgorithmKind.CIPHERS,
            value = options.ciphers,
            review = options.cipherReview,
            onValueChange = { onChange(options.copy(ciphers = it)) },
        )
        AlgorithmField(
            kind = AlgorithmKind.KEX,
            value = options.kexAlgorithms,
            review = options.kexReview,
            onValueChange = { onChange(options.copy(kexAlgorithms = it)) },
        )
        AlgorithmField(
            kind = AlgorithmKind.MACS,
            value = options.macs,
            review = options.macReview,
            onValueChange = { onChange(options.copy(macs = it)) },
        )
        AlgorithmField(
            kind = AlgorithmKind.HOST_KEYS,
            value = options.hostKeyAlgorithms,
            review = options.hostKeyAlgorithmReview,
            onValueChange = { onChange(options.copy(hostKeyAlgorithms = it)) },
        )

        SectionLabel("Session start")
        OutlinedTextField(
            options.startupCommand,
            { onChange(options.copy(startupCommand = it.take(STARTUP_COMMAND_MAX_LENGTH))) },
            label = { Text("Startup command") },
            isError = !options.startupCommandValid,
            minLines = 2,
            supportingText = supportText(
                "Typed into the shell once it opens, on every new connection including a reconnect - " +
                    "a `cd`, a `tmux attach`. It is echoed and its output is in the terminal, so this is " +
                    "not a place for a password.",
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            options.environment,
            { onChange(options.copy(environment = it.take(ENVIRONMENT_MAX_LENGTH))) },
            label = { Text("Environment") },
            isError = !options.environmentValid,
            minLines = 2,
            supportingText = supportText(
                if (options.environmentValid) {
                    "One NAME=VALUE per line. Most servers accept only what their AcceptEnv lists - " +
                        "usually just LANG and LC_* - and refuse the rest silently. Not a place for a secret."
                } else {
                    "Each line must be NAME=VALUE, where NAME is letters, digits and underscore."
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Port forwarding")
        Text(
            "Opened after the shell comes up, on every connection. A rule that cannot bind is reported " +
                "on the session and never costs it the shell.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forwards.forEach { rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.describe(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        rule.type.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onChange(options.copy(forwards = options.forwards - rule)) }) {
                    Text("Remove")
                }
            }
        }
        ForwardRuleEditor(
            enabled = options.forwards.size < MAX_SAVED_FORWARDS,
            onAdd = { rule -> onChange(options.copy(forwards = options.forwards + rule)) },
        )

        TextButton(
            onClick = { onChange(AdvancedHostOptions.DEFAULTS) },
            enabled = !options.isDefault,
        ) { Text("Reset to defaults") }
    }
}

/**
 * One algorithm preference list: a field to type into, and every name this device supports as a chip.
 *
 * The chips are the point. These four lists are the only settings in this form whose values are opaque
 * strings from a specification - nobody remembers whether it is `hmac-sha2-256` or `hmac-sha256`, and a
 * typo here is a host that stops connecting for a reason the error message will not name. Tapping the
 * name that the device actually offers cannot be misspelt, and the field stays editable for a paste out
 * of an `ssh_config`.
 *
 * A chip toggles rather than only appends, so a list is built and unbuilt the same way, and an appended
 * name goes on the *end* because the end is the least-preferred position - adding a name must not
 * silently promote it past the ones already chosen.
 */
@Composable
private fun AlgorithmField(
    kind: AlgorithmKind,
    value: String,
    review: AlgorithmListReview,
    onValueChange: (String) -> Unit,
) {
    // Computed from the JCE providers, so once per composition of this field rather than once per frame.
    val available = remember(kind) { supportedAlgorithmNames(kind) }
    val chosen = remember(value) { value.split(',', '\n', ' ').map(String::trim).filter(String::isNotEmpty) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value,
            onValueChange,
            label = { Text(kind.label) },
            singleLine = true,
            isError = !review.isAcceptable,
            supportingText = supportText(review.problem ?: kind.help),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { name ->
                val selected = name in chosen
                FilterChip(
                    selected = selected,
                    onClick = {
                        val next = if (selected) chosen - name else chosen + name
                        onValueChange(next.joinToString(", "))
                    },
                    label = { Text(name) },
                )
            }
        }
    }
}

/**
 * The add-a-rule row of the port forwarding editor.
 *
 * Validation is [decodeForwardRules] rather than a second set of checks: the row builds the same line
 * the column stores and asks the codec to read it back, so a rule the editor accepts is exactly a rule a
 * restored backup would accept, and the two can never drift. The fields are [rememberSaveable] because a
 * half-typed rule must survive a rotation - losing three fields to turning the phone is the kind of
 * thing that makes a user give up on a feature.
 */
@Composable
private fun ForwardRuleEditor(enabled: Boolean, onAdd: (ForwardEntry) -> Unit) {
    var type by rememberSaveable { mutableStateOf(ForwardType.LOCAL) }
    var localPort by rememberSaveable { mutableStateOf("") }
    var remoteHost by rememberSaveable { mutableStateOf("") }
    var remotePort by rememberSaveable { mutableStateOf("") }

    val rule = when (type) {
        ForwardType.LOCAL -> "L:$localPort:${remoteHost.trim()}:$remotePort"
        ForwardType.REMOTE -> "R:$remotePort:$localPort"
        ForwardType.DYNAMIC -> "D:$localPort"
    }
    val parsed = decodeForwardRules(rule).singleOrNull()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForwardType.entries.forEach { choice ->
                FilterChip(
                    selected = type == choice,
                    onClick = { type = choice },
                    label = { Text(choice.label) },
                    enabled = enabled,
                )
            }
        }
        Text(
            when (type) {
                ForwardType.LOCAL -> "A port on this device reaches a host the server can see."
                ForwardType.REMOTE -> "A port on the server reaches this device. Bound to the " +
                    "server's loopback, so it is not published to the server's network."
                ForwardType.DYNAMIC -> "A SOCKS5 proxy on this device, routed through the server."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = localPort,
                onValueChange = { localPort = it },
                label = if (type == ForwardType.REMOTE) "Port on this device" else "Local port",
                valid = localPort.isEmpty() || localPort.toPortOrNull() != null,
                range = PORT_RANGE,
                help = null,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                maxDigits = PORT_DIGITS,
            )
            if (type != ForwardType.DYNAMIC) {
                NumberField(
                    value = remotePort,
                    onValueChange = { remotePort = it },
                    label = if (type == ForwardType.REMOTE) "Port on the server" else "Remote port",
                    valid = remotePort.isEmpty() || remotePort.toPortOrNull() != null,
                    range = PORT_RANGE,
                    help = null,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    maxDigits = PORT_DIGITS,
                )
            }
        }
        if (type == ForwardType.LOCAL) {
            OutlinedTextField(
                remoteHost,
                { remoteHost = it },
                label = { Text("Remote host") },
                singleLine = true,
                enabled = enabled,
                supportingText = supportText("As the server would resolve it - a name on its network, or an address."),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(
            onClick = {
                parsed?.let(onAdd)
                localPort = ""
                remoteHost = ""
                remotePort = ""
            },
            enabled = enabled && parsed != null,
        ) { Text(if (enabled) "Add forward" else "At most $MAX_SAVED_FORWARDS forwards") }
    }
}

/** Digits in the highest TCP port, so a port field can hold 65535 and not 6553. */
private const val PORT_DIGITS = 5

/** The three states of a per-host override of a global switch: inherit, force on, force off. */
private val LEGACY_CHOICES: List<Pair<String, Boolean?>> =
    listOf("Follow Settings" to null, "Allow" to true, "Never" to false)

@Composable
private fun SectionLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
)

/**
 * A switch with its own explanation, which changes with the switch.
 *
 * The whole row toggles rather than only the switch, and the [Switch] itself is not clickable, so the
 * control is announced once with its title and read as a single target - the pattern the SFTP
 * auto-login switch in the same form already uses.
 */
@Composable
private fun OptionSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    detail: String,
) = Row(
    Modifier
        .fillMaxWidth()
        .clickable(role = Role.Switch) { onCheckedChange(!checked) }
        .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = null)
}

/**
 * A digits-only field that reports its own accepted range when what is in it is not in that range.
 *
 * [help] is the line under it while the value is fine; the range replaces it while the value is not, so
 * an error never costs the user the explanation of what the field is for.
 *
 * [maxDigits] is what the field will hold at all, and it is a parameter because a port needs five and
 * every other number here needs four. Truncating at four silently made 65535 into 6553, which is a valid
 * port - so the mistake would not have been reported by anything, it would just have bound the wrong one.
 */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    valid: Boolean,
    range: IntRange,
    help: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxDigits: Int = 4,
) = OutlinedTextField(
    value,
    { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
    label = { Text(label) },
    singleLine = true,
    enabled = enabled,
    isError = !valid,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    supportingText = supportText(if (valid) help else "${range.first}\u2013${range.last}"),
    modifier = modifier,
)

/** [text] as a supporting-text slot, or no slot at all when there is nothing to say. */
private fun supportText(text: String?): (@Composable () -> Unit)? =
    text?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } }
