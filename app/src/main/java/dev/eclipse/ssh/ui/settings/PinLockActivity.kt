package dev.eclipse.ssh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * PIN lock, in its own window.
 *
 * The row it replaces opened an `AlertDialog` holding a three-stage flow: prove you know the current
 * PIN, enter a new one, confirm it. A dialog was the wrong container for that for the reason this
 * whole promotion exists - its body is capped at a fraction of the screen height, so a flow of three
 * fields lived in a letterbox - and for one reason particular to this setting: a PIN has a *state*
 * worth stating plainly, and a dialog has nowhere to put it. Here [SettingsSection] and [SettingRow]
 * say it in the same shape the Settings list says it, so the promoted screen still reads as the row
 * it came from.
 *
 * The half of this screen the dialog did not own is the write. `MainViewModel.setPin`/`clearPin` wrap
 * the repository calls in its `writeSetting` helper, which reports a failed `DataStore.edit` to the
 * user; a destination calls the repository directly, so the report is its own to make, and it is made
 * through [LocalSettingsReport] - the channel the shell mounts, so a PIN that failed to save is
 * announced the same way a switch that failed to save is. A PIN this screen reported as saved and
 * never stored is the one lie it must not tell: it would leave a user believing the app is locked when
 * it is not.
 */
@AndroidEntryPoint
class PinLockActivity : SettingsDestinationActivity() {

    override val screenTitle = "PIN lock"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        // Whether a PIN is set is carried here rather than re-read. The snapshot in `settings` is taken
        // once, when the window opened (see [SettingsDestination]), so this screen's own write is the
        // only thing that can change the answer while it is open - and without this state the row above
        // would go on offering to set a PIN that is already set. Keyed on the snapshot for the one
        // emission that matters: the first composition runs before the store has answered and sees
        // `AppSettings()`'s defaults, and every one of those defaults is wrong about the PIN.
        var pinSet by remember(settings.pinEnabled) { mutableStateOf(settings.pinEnabled) }
        // The stages are numbered in the order the flow runs rather than in the order they are laid out,
        // so the index is a position in the flow and not a layout slot: proving the current PIN has to
        // come before changing it, and entering the new one is where the flow ends. Which stage the
        // screen opens on is decided by the store alone, exactly as the dialog decided it from the same
        // flag - with a PIN set the flow has to start by proving you know it, and with none set there is
        // nothing to prove, so it opens on the new-PIN fields.
        var stage by remember(settings.pinEnabled) { mutableIntStateOf(if (settings.pinEnabled) STAGE_VERIFY else STAGE_NEW) }
        var current by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var verifying by remember { mutableStateOf(false) }
        var writing by remember { mutableStateOf(false) }
        var confirmingRemove by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        // Where a failed write is announced. The shell mounts the host this reaches, and its default is
        // a no-op, so this is a call with somewhere to go rather than a channel of this screen's own.
        val report = LocalSettingsReport.current

        fun verifyCurrent(onOk: () -> Unit) {
            // Guarded the same way the lock screen is: verification is PBKDF2 at 60k iterations, so
            // it now suspends for a noticeable moment, and without this a double-tap - or a tap on
            // "Remove PIN" followed by "Change PIN" - runs two verifications whose callbacks both
            // fire. `verifyPin` fails closed on a store it cannot read, so a refusal arrives as an
            // ordinary false and needs no branch of its own here.
            if (verifying) return
            verifying = true
            error = null
            notice = null
            scope.launch {
                if (repository.verifyPin(current)) onOk() else error = "Incorrect PIN"
                verifying = false
            }
        }

        // The write path, and the reason this screen cannot lean on the view-model's: `writeSetting`
        // reports into the snackbar of the window that launched it, and a destination calls the
        // repository directly, so the report is its own to make. It goes to [LocalSettingsReport], which
        // is the shell's channel, rather than to a text field of this screen's own: a PIN that failed to
        // save is then reported where a failed settings write is reported, and this screen is not
        // carrying a second failure surface that could drift from the shell's.
        //
        // Nothing these calls can do wrong arrives as a value: `setPin` returns `Unit` and `clearPin`
        // returns whatever `DataStore.edit` handed back, so neither has a result that means "this did not
        // save" - a condition on one would be reading a success signal that does not exist. Failure is
        // the throw, and what the throw means is that no `edit` committed: an `edit` is one transaction,
        // so a failed `setPin` leaves none of its three keys behind. That is what makes each caller's
        // sentence true rather than merely likely, which matters because the state left behind differs -
        // a failed `setPin` on a vault with no PIN leaves no PIN at all, while the same failure on a
        // vault that had one leaves the old PIN in force.
        //
        // The stage and the fields are deliberately left as they were, so a retry after a failure does
        // not ask for the PIN to be typed again. The `writing` guard is the same double-tap guard as
        // `verifying`, for the same kind of cost: each tap here is a PBKDF2 hash and a store transaction,
        // and two of them racing would put two outcomes on screen for one tap.
        fun writePin(ifItFails: String, write: suspend () -> Unit, onSuccess: () -> Unit) {
            if (writing) return
            writing = true
            error = null
            notice = null
            scope.launch {
                try {
                    write()
                    onSuccess()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    report(ifItFails)
                }
                writing = false
            }
        }

        SettingsSection(screenTitle) {
            SettingRow(
                Icons.Default.Lock,
                screenTitle,
                if (pinSet) {
                    "Asked for when the app starts, and before this screen will change it"
                } else {
                    "The vault has no lock screen at launch, and nothing is asked for"
                },
            ) {
                Text(
                    if (pinSet) "Enabled" else "Not set",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // The window stays open on its own write, and the row above reads exactly the same before
            // and after one - so without a line here, saving a PIN and changing a PIN would both look
            // like nothing happened. The dialog had no need of it because it closed itself.
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp),
                )
            }
            // No `rememberDialogBodyMaxHeight` and no scroller of this screen's own, deliberately. The
            // cap existed because a dialog's own height is a fraction of the screen and a body wanting
            // more than that had to scroll inside the letterbox that was left; it is a property of the
            // dialog container, not a decision about this content. In a window the shell's column
            // already scrolls and already reaches the bottom, so keeping the cap would rebuild the very
            // letterbox this promotion removes - and with that parent offering an unbounded height, the
            // cap would be a number with nothing left to justify it. A second scroller here would be
            // worse than redundant: a vertically scrolling child measured by a vertically scrolling
            // parent is the arrangement Compose throws on.
            if (stage == STAGE_VERIFY) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        current,
                        { current = it.filter(Char::isDigit).take(6) },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { verifyCurrent { confirmingRemove = true } },
                            enabled = current.isNotBlank() && !verifying,
                        ) { Text("Remove PIN") }
                        Button(
                            onClick = {
                                verifyCurrent {
                                    stage = STAGE_NEW
                                    newPin = ""
                                    confirm = ""
                                    error = null
                                    notice = null
                                }
                            },
                            enabled = current.isNotBlank() && !verifying,
                        ) { Text(if (verifying) "Verifying…" else "Change PIN") }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        newPin,
                        { newPin = it.filter(Char::isDigit).take(6) },
                        label = { Text("New PIN (4+ digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    OutlinedTextField(
                        confirm,
                        { confirm = it.filter(Char::isDigit).take(6) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Only when a PIN is already set: with none set this stage is where the screen
                        // opens, and there is no earlier stage for a Cancel to return to.
                        if (pinSet) {
                            OutlinedButton(
                                onClick = {
                                    // The field is emptied on the way back rather than left filled.
                                    // The PIN it held has already been proved once, and every action
                                    // under it verifies again before it acts, so keeping it would only
                                    // save a retype at the cost of leaving a secret on screen.
                                    current = ""
                                    newPin = ""
                                    confirm = ""
                                    error = null
                                    notice = null
                                    stage = STAGE_VERIFY
                                },
                            ) { Text("Cancel") }
                        }
                        Button(
                            onClick = {
                                when {
                                    newPin.length < 4 -> error = "PIN must be at least 4 digits"
                                    newPin != confirm -> error = "PINs do not match"
                                    else -> {
                                        // Captured before the coroutine starts, so the write cannot pick
                                        // up an edit the user makes in the frame between the tap and the
                                        // first suspension of `setPin`.
                                        val pin = newPin
                                        writePin(
                                            ifItFails = if (pinSet) {
                                                "Could not save the new PIN, so the PIN already set is still in force."
                                            } else {
                                                "Could not save the PIN, so no PIN is set."
                                            },
                                            write = { repository.setPin(pin) },
                                            onSuccess = {
                                                pinSet = true
                                                stage = STAGE_VERIFY
                                                current = ""
                                                newPin = ""
                                                confirm = ""
                                                notice = "PIN saved."
                                            },
                                        )
                                    }
                                }
                            },
                            enabled = newPin.isNotBlank() && !writing,
                        ) { Text(if (writing) "Saving…" else "Save PIN") }
                    }
                }
            }
        }

        if (confirmingRemove) {
            // A confirmation stays a dialog even in a screen that exists because a dialog was the wrong
            // container for a setting. The promotion is about *destinations* - places you go and come
            // back from - and this is the other thing a dialog is genuinely for: a question that wants
            // an answer before anything else happens. The Settings list's own "Forget every saved
            // credential?" is the same shape for the same reason.
            AlertDialog(
                onDismissRequest = { confirmingRemove = false },
                title = { Text("Remove the PIN?") },
                text = {
                    Text(
                        "The PIN is deleted and the app stops asking for one at launch: the vault loses " +
                            "its lock screen entirely, so Auto-lock vault has nothing left to re-arm. The " +
                            "auto-lock delay itself is kept, and applies again if a PIN is set later.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // Closed before the write rather than after it: a failed removal is
                            // announced by the shell's snackbar, and that snackbar renders in the
                            // window's own layer - under a modal confirmation that says nothing about
                            // the failure, which is the wrong place to read it.
                            confirmingRemove = false
                            writePin(
                                ifItFails = "Could not remove the PIN, so it is still asked for at launch.",
                                write = { repository.clearPin() },
                                onSuccess = {
                                    pinSet = false
                                    stage = STAGE_NEW
                                    current = ""
                                    notice = "PIN removed."
                                },
                            )
                        },
                    ) { Text("Remove PIN", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") } },
            )
        }
    }
}

/**
 * The first stage of the flow: prove the current PIN.
 *
 * The index is the flow's own order, so the stage a screen opens on is decided by what the store says
 * rather than by a constant - a vault that already has a PIN passes through this one, and it cannot
 * change or remove that PIN without passing through it.
 */
private const val STAGE_VERIFY = 0

/**
 * The last stage: enter the new PIN and confirm it.
 *
 * A vault with no PIN opens here, because there is no current PIN to prove and the action on offer is
 * the set itself rather than a change.
 */
private const val STAGE_NEW = 1
