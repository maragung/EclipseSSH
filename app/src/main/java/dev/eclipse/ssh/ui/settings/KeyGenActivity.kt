package dev.eclipse.ssh.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.ssh.GeneratedKeyPair
import dev.eclipse.ssh.ssh.SshKeyAlgorithm
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Generate an SSH key pair, in its own window.
 *
 * The row this replaces opened a `KeyGenDialog` over Settings, and the dialog's content is what this
 * screen shows: the paragraph explaining what the two files are, one chip per algorithm, and the
 * "Generate & save" action. What the dialog could not be is the flow behind that action. The flow is
 * *pick an algorithm, generate a key pair, then create two documents through two other apps' pickers
 * in sequence*, and the generation in the middle of it is seconds to tens of seconds of prime
 * searching. A window owns that; a dialog owns a box that is measured and drawn by the screen behind
 * it, and the whole chain - two external pickers and the work between them - was being carried in
 * `MainActivity`'s composition on the dialog's behalf.
 *
 * The one thing that must not move back is where the generation runs. It is on `Dispatchers.Default`,
 * never in the click handler, for the reason recorded at the generation below: a 4096-bit generation
 * in a click handler is a main-thread block past the ANR threshold, and the app used to freeze and
 * offer to be killed for it.
 *
 * Both pickers are bracketed by [VaultUnlockGate]. The gate's own KDoc explains the mechanism; the
 * ordering this screen has to get right - the flag stays held across the first write and the second
 * launch, and only comes down where the chain ends - is at the two callbacks below.
 */
@AndroidEntryPoint
class KeyGenActivity : SettingsDestinationActivity() {

    /**
     * The process-wide record that a picker is in flight, injected rather than held in a `remember`.
     *
     * A `remember` would not work here at all: while a picker is on screen this window is stopped,
     * and the auto-lock countdown is decided by the observer in `MainActivity`, which cannot see any
     * state owned by a composition that is not running. The flag has to be readable from the process,
     * which is what the singleton is for.
     */
    @Inject lateinit var vaultUnlockGate: VaultUnlockGate

    override val screenTitle = "Generate SSH key pair"

    // Neither the settings snapshot nor the repository is read: nothing on this screen is a
    // preference. The algorithm is chosen per key pair and the files go where the user points the
    // pickers, so there is nothing here for the store to hold.
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        // The dialog's own default, and its own state: the chosen algorithm is held here rather than
        // read back from anywhere, because nothing writes it down.
        var selected by remember { mutableStateOf(SshKeyAlgorithm.RSA_2048) }
        // True from the tap until the chain ends - through the generation, the private-key picker,
        // the write, and the public-key picker. It disables the action so a second tap cannot start a
        // second generation and a second pair of pickers over the first pair, and it is what puts a
        // status line on the screen while the first one runs.
        var busy by remember { mutableStateOf(false) }
        // The generated pair, handed from the generation to the pickers that write it. Held here
        // rather than passed through an intent because the pickers' results come back to this
        // composition, and a PEM private key does not belong in an Intent extra.
        var pendingKeyPair by remember { mutableStateOf<GeneratedKeyPair?>(null) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val report = LocalSettingsReport.current

        // The public-key picker is declared before the private-key one because the private-key
        // callback launches it, and a lambda cannot capture a local declared below it - the same
        // ordering `MainActivity`'s two launchers have, and for the same reason.
        //
        // It is the second half of the chain and the end of it, so it is where the gate comes down
        // and where [busy] clears, whether or not the user picked a location.
        val publicKeyPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            vaultUnlockGate.release()
            busy = false
            val pair = pendingKeyPair
            pendingKeyPair = null
            if (uri != null && pair != null) {
                scope.launch {
                    writeDocument(context, uri, pair.publicLine.toByteArray(Charsets.UTF_8))
                        .onFailure { reportWriteFailure(report, "public key", it) }
                }
            }
        }

        // The private-key half, which runs first and is the one that can chain.
        val privateKeyPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-pem-file"),
        ) { uri: Uri? ->
            // Released and then taken again a few lines down, which is not a no-op: the release is
            // the path the user takes by cancelling this picker, where the chain ends here and the
            // countdown may resume, and the hold below is what covers the write and the second
            // picker. Ordering matters more than it looks - see the hold's own comment.
            vaultUnlockGate.release()
            val pair = pendingKeyPair
            if (uri != null && pair != null) {
                // Held *across* the write rather than taken again when the public-key picker is about
                // to open. The write is a binder round trip into whichever app owns the URI, and a
                // cloud provider can sync the whole file before the stream closes, so the gap between
                // this callback and the second picker is however long that takes - not a moment. The
                // app is in the foreground for the whole gap, and the auto-lock observer has no way to
                // tell that a chain is still running: with the flag down, a slow write followed by a
                // slow second picker looks exactly like a user who left, and the vault re-locks with
                // the fresh key pair still only in this composition.
                vaultUnlockGate.hold()
                scope.launch {
                    writeDocument(context, uri, pair.privatePem.toByteArray(Charsets.UTF_8))
                        .onSuccess { publicKeyPicker.launch("${pair.defaultPrivateName}.pub") }
                        .onFailure {
                            // The chain ends here, so the flag and the screen both come back. The pair
                            // is dropped rather than kept: its private half never reached the file the
                            // user chose, and leaving it in state would offer a retry this screen does
                            // not have.
                            vaultUnlockGate.release()
                            busy = false
                            pendingKeyPair = null
                            reportWriteFailure(report, "private key", it)
                        }
                }
            } else {
                // Cancelled, or nothing was generated to write. Same end of the chain.
                pendingKeyPair = null
                busy = false
            }
        }

        fun generateAndSave() {
            if (busy) return
            // Captured at the tap rather than read inside the coroutine: the chips are disabled while
            // the chain runs, so the two cannot disagree today, and reading the state here is what
            // stops that from becoming a silent race if the control ever changes.
            val algorithm = selected
            busy = true
            // Held before the coroutine starts, not when the private-key picker opens. The wait
            // between the two is the generation - seconds on a fast phone, tens of seconds on a slow
            // one - and the shortest auto-lock delay the app offers is a minute, so without the flag
            // the vault could re-arm while a user watched the status line, and the picker that
            // follows would open behind a PIN prompt they had not asked for.
            vaultUnlockGate.hold()
            scope.launch {
                // Off the main thread, and this is the mistake this screen exists not to repeat. RSA
                // key generation searches for primes, so its cost is unbounded rather than merely
                // large: 4096-bit generation is seconds on a fast phone and tens of seconds on a slow
                // one. In the dialog it ran inside the click handler, which is a main-thread block
                // well past the 5 s ANR threshold - the tap froze the whole UI and Android offered to
                // kill the app, with the key that was being generated lost when the user accepted.
                // `Dispatchers.Default` rather than IO because this is CPU-bound work with no I/O in
                // it, and it would be wrong to occupy the pool a disk write may be waiting on.
                val generated = withContext(Dispatchers.Default) { runCatching { algorithm.generate() } }
                generated
                    .onSuccess { pair ->
                        pendingKeyPair = pair
                        privateKeyPicker.launch(pair.defaultPrivateName)
                    }
                    .onFailure { error ->
                        // Reachable: the generator rejects a coordinate it cannot encode, and a
                        // provider can refuse an algorithm outright. Thrown from a click handler this
                        // crashed the activity; here it is a message, and the chain is over before it
                        // started, so the flag comes down with it.
                        vaultUnlockGate.release()
                        busy = false
                        // "Could not generate the key pair" rather than the shell's "Could not save
                        // ..." wording: nothing was being saved at this point, so [reportWriteFailure]
                        // would put the wrong verb on it. The detail is spelled exactly the way that
                        // helper spells it, so the two failures read as one family.
                        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
                        report("Could not generate the key pair: $detail")
                    }
            }
        }

        SettingsSection(screenTitle) {
            Text(
                "A private key (PEM) and a public key (OpenSSH format) will be saved as separate files. " +
                    "Keep the private key secret and add the public key to your server's authorized_keys.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp),
            )
            // `FlowRow`, not the dialog's horizontally scrolling row: a strip of chips hides the
            // algorithms that do not fit behind a gesture nobody knows is there, and the window has
            // the height to show all three at once. The names are the dialog's, straight off the enum,
            // so a fourth algorithm cannot be added without appearing here.
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SshKeyAlgorithm.entries.forEach { algorithm ->
                    FilterChip(
                        selected = selected == algorithm,
                        onClick = { selected = algorithm },
                        enabled = !busy,
                        label = { Text(algorithm.label) },
                    )
                }
            }
        }

        // Outside the card, where the dialog's confirm button sat relative to its content. The
        // dialog's "Cancel" has no twin here: it dismissed a box that was covering Settings, and this
        // screen's back arrow leaves it just as well - there is no state here worth asking the user to
        // discard, only a choice of algorithm.
        Spacer(Modifier.height(14.dp))
        if (busy) {
            // Not in the dialog, and it has to be here. The dialog had no such state because it froze:
            // the work was on the main thread, so the screen showed nothing at all until it finished.
            // With the work on [Dispatchers.Default] the screen stays responsive, which leaves the tap
            // with no visible effect for as long as a slow phone takes to find primes - and a user who
            // believes a tap was dropped taps again. This says it was not, and the disabled action
            // below is what makes the second tap harmless.
            Text(
                "Generating the key pair…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Button(onClick = { generateAndSave() }, enabled = !busy) { Text("Generate & save") }
    }
}

/**
 * Writes [bytes] to the SAF document at [uri], off the main thread.
 *
 * This is `MainActivity`'s helper, which is `private` there and therefore out of reach of a window
 * that is no longer part of that file. The reasoning is that file's and still applies: an
 * activity-result callback is delivered on the main thread, and a document write is a binder round
 * trip into whichever provider owns the URI - which may be a cloud backend that syncs over the
 * network before the stream closes. Inline it drops frames at best and can block long enough to ANR
 * at worst, and a revoked grant or a full volume would throw straight out of the callback. The
 * [Result] is what lets the caller report the failure rather than crash on it.
 *
 * Both keys go through it, and neither write is a settings write: a failure here is a document that
 * did not land, so the caller reports it naming the document rather than in the store's vocabulary.
 */
private suspend fun writeDocument(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("The selected location could not be opened for writing")
            stream.use { it.write(bytes) }
        }
    }
