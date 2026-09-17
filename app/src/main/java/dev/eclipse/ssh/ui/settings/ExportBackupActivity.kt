package dev.eclipse.ssh.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.backup.VaultExporter
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.security.normalizePastedSecret
import dev.eclipse.ssh.ui.SecretFieldKeyboard
import dev.eclipse.ssh.ui.SecretPasteButton
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export an encrypted backup, in its own window.
 *
 * The row this replaces opened a `PassphraseDialog` over Settings, and then, once the passphrase was
 * confirmed, a create-document picker held by `MainActivity` along with the passphrase the dialog had
 * handed back (`pendingExportPassphrase`). Both halves are here now: the field, reproduced from the
 * dialog down to its masking, its IME treatment and its paste affordance, and the picker, launched
 * from the same screen that asked the question. A window is the right container for the pair for the
 * reason the promotion exists at all - the answer belongs to a screen the user is on, not to a box
 * covering the one behind it - and for the one this screen shares with key generation: the picker in
 * the middle of the flow is another app's activity, which a dialog could only survive by parking its
 * state in the composition that drew it.
 *
 * The export itself is [VaultExporter], a singleton extracted from `MainViewModel.exportVault` for
 * exactly this: a second window cannot reach a view model scoped to `MainActivity`'s
 * `ViewModelStore`, and every input the export needs is a singleton already. It *throws* on failure
 * rather than returning a status, so the `try` below is the whole of the error handling, and it
 * reports the count it wrote so this screen can say "Exported N host(s)" - the wording the view model
 * used, kept because it is the one fact that tells a user their backup is not silently empty.
 *
 * The passphrase is held in the composition for as long as it takes to hand it to the export, and
 * nowhere else: it is never logged, never written to the settings store, and the field is cleared as
 * soon as the picker returns rather than left on screen behind a finished export.
 */
@AndroidEntryPoint
class ExportBackupActivity : SettingsDestinationActivity() {

    @Inject lateinit var vaultExporter: VaultExporter

    /**
     * Bracket for the picker. Injected and not remembered for the reason [VaultUnlockGate]'s own KDoc
     * gives: the auto-lock countdown is decided by an observer in `MainActivity` that can only see
     * process-wide state, and a picker this window opens reports the whole app as backgrounded.
     */
    @Inject lateinit var vaultUnlockGate: VaultUnlockGate

    /**
     * The audited clipboard boundary, injected so the field's paste is not a second, unaudited way
     * into the clipboard. `MainViewModel.pasteSecret` read through this same singleton - the read is
     * denied to an app that is not in the foreground and never cached - and the newline a password
     * manager appends is stripped by [normalizePastedSecret] for the same reason it is there.
     */
    @Inject lateinit var secureClipboard: SecureClipboard

    override val title = "Export encrypted backup"

    // Neither the settings snapshot nor the repository is read. The only setting this screen touches
    // is the vault's contents, and those are read by [VaultExporter] when the export runs.
    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        var passphrase by remember { mutableStateOf("") }
        // The passphrase the picker was launched with, held across the picker the same way the dialog
        // held it in `MainActivity` - the field itself is cleared at launch, so this is the only copy
        // alive while another app is on screen.
        var pendingPassphrase by remember { mutableStateOf<String?>(null) }
        // True from the tap until the export has finished, covering both the picker being out and the
        // write running. It disables the action, so a second tap cannot open a second picker over the
        // first, and the field with it, so the export cannot run against a passphrase the user is
        // still editing.
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current

        val exportPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            // Released the moment the picker is back, before anything is done with the result: the
            // picker is the whole of what the flag was covering here. Key generation has to hold it
            // longer because it chains into a second picker; this one has no second picker to cover.
            vaultUnlockGate.release()
            val pass = pendingPassphrase
            pendingPassphrase = null
            // Cleared here rather than after the export for two reasons: the passphrase is no longer
            // needed once it has been captured, and a field that still held it would keep it in the
            // composition - and in any screenshot - for the whole of a slow write.
            passphrase = ""
            if (uri == null || pass == null) {
                // The user cancelled the picker, or the answer that launched it is gone. Either way
                // there is nothing to write.
                busy = false
            } else {
                scope.launch {
                    try {
                        // On IO, not the main thread: the export reads the hosts and settings through
                        // Room and DataStore, encrypts with a key derived from the passphrase, and
                        // writes the result through a content resolver - all three of which are disk
                        // or CPU work that would block the frame, and the write in particular can be a
                        // binder round trip into a cloud provider.
                        val exported = withContext(Dispatchers.IO) { vaultExporter.export(pass, uri) }
                        report("Exported $exported host(s)")
                    } catch (failure: Throwable) {
                        // The exporter throws rather than returning a status, so this is where every
                        // realistic failure arrives - a revoked URI grant, a provider that refuses the
                        // write, a full disk - and [reportWriteFailure] is the shell's wording for a
                        // write that did not land. It rethrows cancellation, which is the window going
                        // away rather than a failed export.
                        reportWriteFailure(report, "the backup", failure)
                    } finally {
                        busy = false
                    }
                }
            }
        }

        SettingsSection(title) {
            // The row's own subtitle, which is what this screen's card is about. The dialog had no
            // text above its field at all - a dialog titled "Export encrypted backup" said this much
            // by being on top of the row that offered it, and a window that outlives that context has
            // to say what is in the file and what protects it.
            Text(
                "Hosts and settings, passphrase-protected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp),
            )
            // The dialog's field, unchanged: masked, one line, on the password keyboard so the IME
            // does not treat a passphrase as prose and offer to remember it, with the same paste
            // affordance beside it.
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = SecretFieldKeyboard,
                trailingIcon = {
                    SecretPasteButton("passphrase", onPaste = {
                        // The view model's own read, and its own report for an empty clipboard: a
                        // Paste that appears to do nothing is indistinguishable from a broken one.
                        val pasted = secureClipboard.paste()?.let(::normalizePastedSecret)
                        if (pasted.isNullOrEmpty()) {
                            report("There is nothing on the clipboard to paste")
                            null
                        } else {
                            pasted
                        }
                    }, into = { passphrase = it })
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
            )
        }

        // Outside the card, where the dialog's confirm button sat. "Cancel" is the window's back
        // arrow: the dialog needed a dismiss button because it was covering Settings, and this screen
        // does not.
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                pendingPassphrase = passphrase
                passphrase = ""
                busy = true
                vaultUnlockGate.hold()
                exportPicker.launch("eclipse-backup.enc")
            },
            // Blank is not a passphrase: the dialog's confirm was disabled on the same condition, and
            // a backup encrypted with an empty string is one anyone can read.
            enabled = passphrase.isNotBlank() && !busy,
        ) { Text("Export") }
    }
}

