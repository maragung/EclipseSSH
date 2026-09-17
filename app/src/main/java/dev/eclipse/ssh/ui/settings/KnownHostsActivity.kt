package dev.eclipse.ssh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ssh.SshConnectionManager
import javax.inject.Inject

/**
 * Known hosts, in its own window.
 *
 * It was an `AlertDialog` over Settings for the one reason the promotion exists: the trusted
 * fingerprints are a list whose length is the user's, and a dialog body is capped at 60% of the
 * screen height, so a user with a dozen hosts read them through a letterbox. A window spends the
 * whole height on them.
 *
 * What the promotion is not allowed to change is the content. The wording, the empty state, the
 * per-entry card with its monospace fingerprint, and both forget actions are the dialog's, since the
 * Settings row this replaces promised exactly this screen and nothing else.
 */
@AndroidEntryPoint
class KnownHostsActivity : SettingsDestinationActivity() {

    /**
     * The host key store itself, injected here rather than reached through the Settings view model.
     *
     * It is a `@Singleton` over the same map, and the same SharedPreferences file, that every
     * connection consults, so a forget performed here is one the next connection honours. Nothing is
     * handed back through a result code - but the store is not a Flow either, so the count in the
     * Settings row is a snapshot the Settings side can only refresh by re-reading it itself.
     */
    @Inject lateinit var sshConnectionManager: SshConnectionManager

    override val title = "Known hosts"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        // Pulled, not subscribed. The store is an in-memory map plus a SharedPreferences file and
        // neither announces a change, so there is no Flow here to collect: the snapshot is read once
        // when the window opens and read *again* after every mutation below. Because the read and the
        // re-read go through the same singleton the connection path uses, the list on screen is the
        // list that will decide whether a host is verified - which is the only thing that makes a
        // revocation here believable.
        var hosts by remember { mutableStateOf(sshConnectionManager.knownHosts()) }
        var confirmingClear by remember { mutableStateOf(false) }
        val entries = hosts.toList().sortedBy { it.first }
        val report = LocalSettingsReport.current

        // The port is whatever follows the *last* colon, not the second field of a `split(":")`: a key
        // can be an IPv6 literal, which is made of colons, and splitting there would hand the store a
        // host it has never heard of and quietly forget nothing. A key that still will not parse is
        // left in the list rather than forgotten under a guessed host - the same guard the Settings
        // view model applies.
        fun forget(key: String) {
            val (host, port) = runCatching {
                val separator = key.lastIndexOf(':')
                key.substring(0, separator) to key.substring(separator + 1).toInt()
            }.getOrNull() ?: return
            // The store's own answer, not an assumption. It drops the key from its in-memory map
            // whatever happens and reports separately whether the write reached disk, so a `false`
            // here means the fingerprint is forgotten only until the app restarts - and it is a
            // fingerprint, so a revocation the user believes happened and did not is the one failure
            // on this screen that must never be silent.
            if (!sshConnectionManager.removeKnownHost(host, port)) {
                report("That fingerprint could not be removed")
            }
            hosts = sshConnectionManager.knownHosts()
        }

        SettingsSection(title) {
            if (entries.isEmpty()) {
                Text(
                    "No trusted hosts yet. You'll be asked to verify a host's fingerprint the first time you connect.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(vertical = 14.dp),
                )
            } else {
                Text(
                    "Fingerprints you've trusted. Removing one will prompt you to verify again on next connect.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp),
                )
                // A plain `forEach` in a `Column`, not the dialog's `LazyColumn`: this body already
                // sits inside `SettingsBody`'s `verticalScroll`, and a second, lazy vertical scroller
                // nested in it is measured against an infinite maximum height and throws. The lazy
                // list was there to survive the dialog's height cap, and the window is what removed
                // the cap.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEach { (key, fingerprint) ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(key, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(fingerprint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { forget(key) }) { Text("Forget") }
                            }
                        }
                    }
                    // Last, where the dialog's dismiss button was, and on the left rather than under
                    // the right-aligned "Forget" buttons of the cards above it: it applies to every
                    // entry, and a destructive action that lines up with a row's own button is a
                    // mis-tap waiting to happen. It asks in the dialog below before it does anything.
                    TextButton(onClick = { confirmingClear = true }) {
                        Text("Forget all", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (confirmingClear) {
            // A dialog inside the window that exists to stop being a dialog, deliberately. What this
            // change removed was a *destination* kept inside an `AlertDialog` - content to read and a
            // length to scroll, in a box the system sizes for you. This is the other kind: a question
            // put before something that cannot be undone, with no content and nothing to scroll, which
            // is the case a dialog is actually for.
            AlertDialog(
                onDismissRequest = { confirmingClear = false },
                title = { Text("Forget all known hosts?") },
                text = {
                    Text("Every trusted fingerprint is removed, so the next connection to each of these hosts will ask you to verify its key again. There is no undo.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Same reasoning as the per-row forget: `clear()` empties the map before it
                        // tries to write, so its false is the only signal that the file still holds
                        // every fingerprint this screen just claimed to remove.
                        if (!sshConnectionManager.clearKnownHosts()) {
                            report("The trusted fingerprints could not be removed")
                        }
                        hosts = sshConnectionManager.knownHosts()
                        confirmingClear = false
                    }) { Text("Forget all", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
            )
        }
    }
}
