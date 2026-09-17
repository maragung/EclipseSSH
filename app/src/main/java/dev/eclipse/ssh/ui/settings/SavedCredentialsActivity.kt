package dev.eclipse.ssh.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.background.SessionRegistry
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.credentials.describe
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Saved credentials: every host that has a secret in the vault, what is saved for it, and the two
 * ways to be rid of it.
 *
 * This one is not a dialog being re-hosted. The Settings row it comes from had no screen at all: it
 * showed a count and offered one button, "Forget all". A count answers "did I leave a password on
 * something I have forgotten about" - that is the question the row was written for - but only if the
 * thing counted is visible somewhere, and nothing in the app could show it. The only way to be rid of
 * a single host's password was to find that host in the list, open its details sheet and forget it
 * from there, which is a different screen's subject on a different screen's terms. This is that
 * somewhere: the list the count implies, with the two actions the vault actually offers.
 *
 * A window rather than the dialog this could have been, for the reason every destination in this batch
 * is one: the list is as long as the user's saved hosts, and a dialog body is capped at a fraction of
 * the screen. Unlike its siblings, the flow behind it is collected live rather than read once. The
 * whole content of the screen is that map, the user is deleting from it, and a forget that did not
 * visibly remove its row would read as a forget that failed.
 */
@AndroidEntryPoint
class SavedCredentialsActivity : SettingsDestinationActivity() {

    @Inject lateinit var credentialStore: HostCredentialStore

    /**
     * Injected beside [credentialStore] because a saved secret sits in two places at once.
     * [SessionRegistry] keeps a vault-encrypted mirror of every live or recent session's password,
     * key and passphrase so the reconnect ladder can redial a host whose UI is gone, and the
     * ViewModel's own forget paths clear both stores for exactly that reason. A screen that dropped
     * only the credential store would leave a still-decryptable copy of the very secret it had just
     * told the user was deleted.
     */
    @Inject lateinit var sessionRegistry: SessionRegistry

    @Inject lateinit var hostRepository: HostRepository

    override val screenTitle = "Saved credentials"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        // Null until the store's first emission, and the card stays blank until then. Seeding this
        // with an empty map - what a list screen normally does - would put "nothing is saved yet" in
        // front of a user who has three hosts saved, for as long as the store takes to answer. A
        // screen whose whole subject is which secrets are on this device is the last place that may
        // claim there are none.
        val saved by credentialStore.credentials
            .collectAsStateWithLifecycle(initialValue = null as Map<String, StoredCredentials>?)
        val hosts by hostRepository.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current
        var confirmForgetAll by remember { mutableStateOf(false) }

        // Hosts with at least one secret saved. Everything the store reports has a password or a key
        // behind it, but an entry whose secrets were all forgotten one at a time can be left standing
        // with nothing in it, so this filters rather than reading the map as it comes - the same
        // reason the Settings row's count filters instead of taking the size.
        val savedHosts = saved?.filterValues { !it.isEmpty }
        val savedCount = savedHosts?.size ?: 0
        val profiles = hosts.associateBy { it.id }

        SettingsSection(screenTitle) {
            when {
                savedHosts == null -> Unit
                savedHosts.isEmpty() -> Text(
                    "Nothing is saved yet. A password, private key or passphrase is stored only when you " +
                        "ask for one to be saved, while connecting or from a host's own form, and this " +
                        "screen is where it is forgotten again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
                else -> savedHosts.entries
                    .sortedBy { credentialHostName(profiles, it.key) }
                    .forEach { (hostId, stored) ->
                        val name = credentialHostName(profiles, hostId)
                        SettingRow(Icons.Default.Key, name, stored.describe()) {
                            // One host, one tap, no confirmation. The row names the host and the button
                            // says what it does, the known-hosts list forgets a trusted fingerprint the
                            // same way, and what is lost is a password the user can type again. The
                            // forget-all below is the opposite case - it is broad, and no single row
                            // makes it self-evident - which is why that one keeps its dialog.
                            TextButton(onClick = { scope.launch { forgetHost(hostId, name, report) } }) { Text("Forget") }
                        }
                    }
            }
        }

        // Outside the card: it is not one host's row, and inside the section it would read as a row
        // for a host called "Forget all". Left uncoloured and disabled with nothing saved, both as
        // the Settings row had it - the red belongs on the dialog's confirm, which is where the
        // existing flow puts it.
        Spacer(Modifier.height(14.dp))
        TextButton(
            onClick = { confirmForgetAll = true },
            enabled = savedCount > 0,
        ) { Text("Forget all") }

        if (confirmForgetAll) {
            AlertDialog(
                onDismissRequest = { confirmForgetAll = false },
                title = { Text("Forget every saved credential?") },
                text = {
                    // The Settings row's confirmation, word for word. It is the promise the app makes
                    // about what forgetting means - hosts kept, the next connect asks again, open
                    // sessions untouched - and a promoted screen that reworded it would be changing
                    // that promise in passing.
                    Text(
                        "Passwords, private keys and passphrases saved for all $savedCount host(s) " +
                            "are deleted from the vault. The hosts themselves are kept, and each will ask for " +
                            "credentials again at the next connect. Sessions already open are unaffected.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmForgetAll = false
                            scope.launch { forgetEverything(report) }
                        },
                    ) { Text("Forget all", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmForgetAll = false }) { Text("Cancel") } },
            )
        }
    }

    /**
     * Forgets one host's secrets in both stores, together.
     *
     * The registry write goes first for the same reason it does in the ViewModel's forget paths: when
     * only one of the two lands, the copy that must not survive is the registry's, and nothing on this
     * screen would show that it did - the row is drawn from the credential store, and that one is
     * gone. Either failure is reported, that half case included.
     *
     * Success is not reported. The row leaving the list is the report, and a message beside a row that
     * has just vanished says the same thing twice.
     */
    private suspend fun forgetHost(hostId: String, name: String, report: (String) -> Unit) {
        val unregistered = runCatching { sessionRegistry.unregister(hostId) }
        val forgotten = runCatching { credentialStore.forget(hostId) }
        if (unregistered.isFailure || forgotten.isFailure) report("Could not forget credentials for $name")
    }

    /**
     * The same pair of writes for the whole vault, which is what the confirmation above promises.
     *
     * One message for the pair rather than one per store: the user asked for one thing and either got
     * it or did not, and the second failure line would only name which internal file was unhappy.
     */
    private suspend fun forgetEverything(report: (String) -> Unit) {
        val cleared = runCatching { sessionRegistry.clear() }
        val forgotten = runCatching { credentialStore.forgetAll() }
        if (cleared.isFailure || forgotten.isFailure) report("Could not clear saved credentials")
    }
}

/**
 * What to call a credential's host on screen.
 *
 * Falls back to the raw id rather than dropping the row, because credentials outlive the profile they
 * were saved for: a vault restored onto a device whose host database came back empty, or a host
 * removed while its secrets stayed behind, both leave a host id here with nothing to name it. A
 * secret with no row is a secret the user cannot reach to forget, and being able to reach every one
 * of them is this screen's entire purpose.
 */
private fun credentialHostName(profiles: Map<String, HostProfile>, hostId: String): String =
    profiles[hostId]?.name ?: hostId
