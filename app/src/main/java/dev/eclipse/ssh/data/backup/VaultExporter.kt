package dev.eclipse.ssh.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ssh.SshConnectionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Writes the encrypted vault backup to a document the user picked.
 *
 * This was five lines inside `MainViewModel.exportVault`, and it moved here for the reason the
 * Settings row that calls it moved out of the Settings screen: the row opens a window of its own,
 * and a second window cannot reach a `@HiltViewModel` that is scoped to `MainActivity`'s
 * `ViewModelStore`. Every input it needs is a singleton - the hosts, the settings, the known-host
 * fingerprints - so the operation belongs on one too, and both callers now share it instead of the
 * newer one reimplementing the encryption.
 *
 * Failing is a *throw*, not a returned status, and that is the contract the two callers want. The
 * realistic failures are all reported the same way to the user - a revoked URI permission, a
 * document provider that refuses the write, a full disk - and each caller already owns the wording
 * it wants on top (`guardBackup("Export failed")` in the view model, the settings report channel in
 * the new window). Returning a `Result` here would only make both of them unwrap it to say the same
 * sentence.
 */
@Singleton
class VaultExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostRepository: HostRepository,
    private val settingsRepository: SettingsRepository,
    private val sshConnectionManager: SshConnectionManager,
) {

    /**
     * Encrypt the vault and write it to [uri]. Returns the number of hosts that went into it.
     *
     * The count is returned rather than reported because "Exported 3 host(s)" needs a number the
     * caller does not otherwise have, and it is the one fact worth confirming back: it is what tells
     * a user their backup is not silently empty.
     */
    suspend fun export(passphrase: String, uri: Uri): Int {
        val hosts = hostRepository.hosts.first()
        val settings = settingsRepository.settings.first()
        val payload = VaultBackup.encrypt(
            VaultBackup.toJson(hosts, settings, sshConnectionManager.knownHosts()),
            passphrase,
        )
        write(uri, payload)
        return hosts.size
    }

    /**
     * `"wt"` and not the default: a document provider that already holds a longer file would
     * otherwise leave its tail behind, and a truncated backup that still parses is worse than one
     * that does not. Same mode the view model used.
     */
    private fun write(uri: Uri, payload: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupFormatException("That location could not be written")
        stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }
}
