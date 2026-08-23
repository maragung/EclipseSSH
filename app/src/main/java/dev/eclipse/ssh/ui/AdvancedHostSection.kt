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
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_TYPE_CHOICES
import dev.eclipse.ssh.presentation.AdvancedHostOptions

/**
 * The Advanced section of the Add / Edit Host form.
 *
 * Collapsed by default, and that is the point of it being a section at all: fourteen switches between
 * "Username" and "Save" would bury the four fields that decide whether a host connects at all. Opened,
 * every control carries one line saying what it does to this connection, because a setting whose effect
 * has to be guessed is one a user changes once and then cannot un-change with any confidence.
 *
 * Holds no rules - [AdvancedHostOptions] owns validation, defaults and the mapping onto the profile, so
 * that all of it stays testable on the JVM. See its KDoc for the four requested settings MINA cannot
 * apply per host, and why they are not here.
 */
@Composable
internal fun AdvancedHostSection(
    options: AdvancedHostOptions,
    onChange: (AdvancedHostOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Survives rotation, so an option changed on the far side of a config change is not re-hidden with
    // the section that was open when it was typed.
    var expanded by rememberSaveable { mutableStateOf(false) }
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

        TextButton(
            onClick = { onChange(AdvancedHostOptions.DEFAULTS) },
            enabled = !options.isDefault,
        ) { Text("Reset to defaults") }
    }
}

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
) = OutlinedTextField(
    value,
    { onValueChange(it.filter(Char::isDigit).take(4)) },
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
