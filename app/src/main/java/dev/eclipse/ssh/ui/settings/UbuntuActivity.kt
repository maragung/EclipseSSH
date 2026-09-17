package dev.eclipse.ssh.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.linux.LinuxInstallStep
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.SetupStep
import dev.eclipse.ssh.linux.UserspaceDiagnosticEvent
import dev.eclipse.ssh.presentation.linux.LinuxUserspaceController
import dev.eclipse.ssh.presentation.linux.LinuxUserspaceUiState
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.ui.rememberDialogBodyMaxHeight
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Linux userspace, in a window of its own: the whole feature's control panel and, for most users,
 * its front door.
 *
 * It was an inline section of the Settings list - a `LinuxUserspaceSection` composable that
 * `SettingsScreen` called with two callbacks, `onCopyInstallLog` and `onSaveInstallLog`, because the
 * clipboard and the document picker lived in the scaffold above it. That borrowing is the part the
 * promotion settles: this screen owns both halves of the export itself. The rows, the three dialogs and
 * the wording inside them are the section's, unchanged, so a user who followed the Settings row here
 * reads what the row promised. The one string that does not come across is the list's own heading over
 * the section - that labelled part of the Settings list, and this is not a part of it any more.
 *
 * The section is not a setting and does not read like one. Every row it draws is derived from
 * [LinuxUserspaceController]'s one state object - the state machine, the storage numbers, the health
 * probe, the install trace - which is what stops this screen ever disagreeing with the host-list card
 * about whether the environment is installed. That is also why there is no view model here: the
 * controller is a `@Singleton` holding flows the rest of the app reads through the same instance, and
 * a second copy of that state would be a second opinion.
 *
 * One rule shapes the layout, and it is the section's: **an operation in flight hides every button.**
 * A mid-install screen with a working Install button would restart the pipeline underneath itself, and
 * a mid-stop screen with Stop still armed would be a lie about what is already happening. The action
 * row at the bottom is therefore absent - not disabled - for Installing, Starting and Stopping; the
 * trace and the failure lines keep their controls, because reading what went wrong while it is
 * happening is the one thing a user wants mid-operation, and neither can restart anything.
 *
 * The three dialogs below stay dialogs. `confirmInstall`, `confirmUninstall` and the install-log view
 * are questions asked of the screen, not places to go: the first two name what a destructive tap is
 * about to do and are two lines of prose each, and the third is a reading surface opened over the row
 * that changes with the state underneath it.
 */
@AndroidEntryPoint
class UbuntuActivity : SettingsDestinationActivity() {

    /**
     * The userspace's state, actions and trace, injected rather than reached through the Settings view
     * model.
     *
     * It is the same `@Singleton` the terminal and the host-list card read, so an Install performed
     * here is the state every other screen sees; a screen that acted on a copy would report success to
     * a userspace that never changed.
     */
    @Inject lateinit var linuxUserspace: LinuxUserspaceController

    /** The audited clipboard boundary - the only route this app has to the system clipboard. */
    @Inject lateinit var secureClipboard: SecureClipboard

    /**
     * Held for the length of the Save picker, through no state of this window's own.
     *
     * The picker is another app's activity, so while it is on screen this process has no started
     * activity and `ProcessLifecycleOwner` reports it as backgrounded - which is also when the
     * auto-lock countdown starts. A user who studies the picker for longer than their configured delay
     * would come back to a PIN lock screen with the export they were saving gone. The gate is a
     * singleton because that countdown is decided by an observer in `MainActivity`, which cannot see a
     * `remember` owned by a window that is not running.
     */
    @Inject lateinit var vaultUnlockGate: VaultUnlockGate

    override val screenTitle = "Ubuntu on this device"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        val ui by linuxUserspace.uiState.collectAsStateWithLifecycle()
        val installLog by linuxUserspace.installLog.collectAsStateWithLifecycle(emptyList())
        var confirmInstall by remember { mutableStateOf(false) }
        var confirmUninstall by remember { mutableStateOf(false) }
        var showInstallLog by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current

        /**
         * The text Save is about to write, held until the picker answers.
         *
         * Nullable, and null is the initial value, because the two moments are a document picker apart
         * and the composition in between is not guaranteed to survive: a rotation while the picker is
         * up rebuilds this window with a fresh composition, and the result is still delivered to
         * whichever instance registered for it. There is a defensible alternative for that case -
         * re-read the ring now, since it can only have grown - and it is deliberately not taken here:
         * what the user asked to save is the trace they were reading, and a report is the honest
         * answer when that exact text is no longer available rather than a file that quietly holds
         * something else.
         */
        var pendingExport by remember { mutableStateOf<String?>(null) }
        val logSavePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            // Released first and unconditionally, including for a cancelled picker: the callback is
            // the moment the other app's activity is gone, and leaving the flag held would suspend the
            // auto-lock countdown for the rest of the process's life. Nothing here chains into a
            // second picker, so this is the only release point.
            vaultUnlockGate.release()
            val text = pendingExport
            pendingExport = null
            when {
                // Cancelled. Nothing was chosen, so there is nothing to report.
                uri == null -> Unit
                text == null -> report("Could not save the install log")
                else -> scope.launch {
                    // Off the main thread: this is a binder round trip into whichever provider owns the
                    // URI, and it can be a cloud backend that syncs before the stream closes.
                    writeDocument(context, uri, text.toByteArray(Charsets.UTF_8))
                        .onFailure { reportWriteFailure(report, "the install log", it) }
                }
            }
        }

        // The card header is the window's title rather than the list's "Linux userspace" heading. That
        // heading labelled a section *of the Settings list*, and this window is not a section of
        // anything: it is the screen the row opened, and the bar above already says its name. It is the
        // shape every promoted screen in this package has, so the one card here reads like theirs.
        SettingsSection(screenTitle) {
            if (!ui.supported) {
                // One row, no button: an unsupported device cannot be offered an install that cannot
                // finish, but it also should not look like a feature that went missing.
                SettingRow(
                    Icons.Default.Computer,
                    "Ubuntu on this device",
                    "Not supported on this device's processor",
                ) { }
                return@SettingsSection
            }
            val distro = ui.distro ?: return@SettingsSection
            val state = ui.state

            when (state) {
                // supported implies a graph, and a graph always has a state; this branch is the
                // compiler's proof of that invariant rather than a state any device can reach.
                null -> Unit
                is LinuxUserspaceState.Installing -> {
                    val (label, fraction) = describeInstallStep(state.step)
                    SettingRow(Icons.Default.CloudDownload, "Installing ${distro.displayName}", label) { }
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                is LinuxUserspaceState.NotInstalled -> SettingRow(
                    Icons.Default.Computer,
                    "Ubuntu on this device",
                    if (ui.hasPendingWorkspaceBackup) {
                        "Not installed · a saved workspace will be restored"
                    } else {
                        "Not installed · real bash, apt, Node.js and Python, on the device"
                    },
                ) {
                    // The version chooser lives here and only here: once anything is on disk the
                    // installed version is the only relevant one (Repair and Start operate on it), so
                    // the dropdown disappears with the Not Installed state that gave it a choice to
                    // make. Selecting swaps the graph, and the Install dialog below names whatever
                    // the dropdown left selected.
                    SettingDropdown(
                        label = "Ubuntu version",
                        options = ui.availableVersions,
                        selected = distro,
                        optionLabel = { it.displayName },
                        onSelect = { linuxUserspace.selectDistro(it.id) },
                    )
                }
                is LinuxUserspaceState.Stopped -> SettingRow(
                    Icons.Default.Terminal,
                    "Ubuntu on this device",
                    "Installed and verified · stopped",
                ) { }
                is LinuxUserspaceState.Starting -> SettingRow(Icons.Default.Terminal, "Ubuntu on this device", "Starting…") { }
                is LinuxUserspaceState.Running -> SettingRow(
                    Icons.Default.Terminal,
                    "Ubuntu on this device",
                    // Held open, not merely alive: closing the last terminal does not end this, and
                    // the count is what tells the user there are shells to come back to.
                    "Running · ${ui.sessionCount} terminal session(s) held open",
                ) { }
                is LinuxUserspaceState.Stopping -> SettingRow(Icons.Default.Terminal, "Ubuntu on this device", "Stopping…") { }
                is LinuxUserspaceState.NeedsRepair -> SettingRow(
                    Icons.Default.Warning,
                    "Ubuntu on this device",
                    "Needs repair · ${state.detail}",
                ) { }
            }

            // The facts that only an installed userspace can answer, absent while it is not — a
            // "0 B used" line on a fresh install screen would be a status pretending to exist.
            if (state is LinuxUserspaceState.Stopped ||
                state is LinuxUserspaceState.Starting ||
                state is LinuxUserspaceState.Running ||
                state is LinuxUserspaceState.Stopping ||
                state is LinuxUserspaceState.NeedsRepair
            ) {
                SettingRow(
                    Icons.Default.Storage,
                    "Storage used",
                    formatTransferBytes(ui.storageUsedBytes),
                ) { }
                SettingRow(
                    Icons.Default.Folder,
                    "Workspace",
                    "/home/ubuntu/workspace · ${ui.workspaceFileCount} file(s) · kept by Stop and Restart",
                ) { }
                SettingRow(
                    Icons.Default.CheckCircle,
                    "Health check",
                    ui.health?.describe() ?: "Not checked yet",
                ) {
                    TextButton(onClick = { linuxUserspace.refreshHealth() }) { Text("Verify") }
                }
            }

            // The action row. Kept out of SettingRow's width-capped trailing slot on purpose: Start,
            // Restart and Uninstall are three coequal controls and capping the third to fit a label
            // column would demote whichever action the layout happened to squeeze.
            //
            // Absent, not disabled, while an operation is in flight - the rule in the class KDoc. A
            // disabled Install would still be a control that names an action already running, and this
            // row is the one place on the screen where a stray tap can restart a pipeline or end one.
            if (state != null &&
                state !is LinuxUserspaceState.Installing &&
                state !is LinuxUserspaceState.Starting &&
                state !is LinuxUserspaceState.Stopping
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state) {
                        is LinuxUserspaceState.NotInstalled ->
                            TextButton(onClick = { confirmInstall = true }) {
                                Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Install")
                            }
                        is LinuxUserspaceState.Stopped -> {
                            TextButton(onClick = { linuxUserspace.start() }) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Start")
                            }
                            TextButton(onClick = { linuxUserspace.restart() }) { Text("Restart") }
                        }
                        is LinuxUserspaceState.Running -> {
                            TextButton(onClick = { linuxUserspace.stop() }) {
                                Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stop")
                            }
                            TextButton(onClick = { linuxUserspace.restart() }) { Text("Restart") }
                        }
                        is LinuxUserspaceState.NeedsRepair ->
                            TextButton(onClick = { linuxUserspace.repair() }) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Repair")
                            }
                        else -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    if (state !is LinuxUserspaceState.NotInstalled) {
                        TextButton(onClick = { confirmUninstall = true }) {
                            Text("Uninstall", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // The install and repair trace, reachable at last. It sits outside the installed-states
            // guard above for the reason it exists: a failed install ends at Not installed, so the
            // evidence of what went wrong is wanted in exactly the state that had no rows to show it.
            SettingRow(
                Icons.Default.Article,
                "Install log",
                if (installLog.isEmpty()) {
                    "Records every install and repair step · no secrets"
                } else {
                    "${installLog.size} event(s) recorded · no secrets"
                },
            ) {
                TextButton(
                    onClick = { showInstallLog = true },
                    // Three "View" buttons now sit on this screen, and a screen reader hears all of
                    // them as just "View" — so the button carries the row it belongs to, the same
                    // "setting, action" shape the Connection diagnostics and About rows use.
                    modifier = Modifier.semantics { contentDescription = "Install log" },
                ) { Text("View") }
            }

            // The last operation's failure, verbatim, with a way to clear it — an error line that
            // could not be dismissed would outlive the fix it described.
            if (ui.error != null) {
                SettingRow(
                    Icons.Default.Warning,
                    "Last operation",
                    ui.error ?: "",
                ) { TextButton(onClick = { linuxUserspace.clearError() }) { Text("OK") } }
            }
            if (ui.installWarnings.isNotEmpty()) {
                SettingRow(
                    Icons.Default.Warning,
                    "Installed with warnings",
                    ui.installWarnings.joinToString("; "),
                ) { TextButton(onClick = { linuxUserspace.clearInstallWarnings() }) { Text("OK") } }
            }
        }

        if (confirmInstall) {
            AlertDialog(
                onDismissRequest = { confirmInstall = false },
                title = { Text("Install ${distroTitle(ui)}?") },
                text = {
                    Text(
                        "A verified ${distroTitle(ui)} root filesystem (~30 MB) is downloaded and the " +
                            "toolchain — bash, git, Python, Node.js — is installed through apt itself, " +
                            "which needs a few hundred MB over your network. Nothing runs as root, and the " +
                            "workspace at /home/ubuntu/workspace survives Stop and Restart." +
                            if (ui.hasPendingWorkspaceBackup) {
                                " Your saved workspace is restored after the install."
                            } else {
                                ""
                            },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmInstall = false; linuxUserspace.install() }) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } },
            )
        }
        if (confirmUninstall) {
            AlertDialog(
                onDismissRequest = { confirmUninstall = false },
                title = { Text("Uninstall ${distroTitle(ui)}?") },
                text = {
                    Text(
                        "The root filesystem and every installed package are deleted" +
                            if (ui.workspaceFileCount > 0) {
                                ". The workspace holds ${ui.workspaceFileCount} file(s): keep them (restored " +
                                    "by the next install) or delete them with the rest."
                            } else {
                                ", together with the empty workspace."
                            },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { confirmUninstall = false; linuxUserspace.uninstall(keepWorkspace = false) },
                    ) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    Row {
                        if (ui.workspaceFileCount > 0) {
                            TextButton(onClick = { confirmUninstall = false; linuxUserspace.uninstall(keepWorkspace = true) }) {
                                Text("Keep workspace")
                            }
                        }
                        TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") }
                    }
                },
            )
        }
        if (showInstallLog) {
            InstallLogDialog(
                events = installLog,
                onDismiss = { showInstallLog = false },
                // Read from the ring at the moment of the tap, not from the list the dialog was composed
                // with: an export must carry what the app knows right now, including anything recorded
                // while the dialog was open — which for an install still running is most of it.
                onCopy = {
                    // `SecureClipboard`, never Compose's `LocalClipboardManager`: this is the app's one
                    // clipboard route, and it is what puts the auto-clear deadline on the copy and marks
                    // the clip sensitive on Android 13+. The delay is the one the shell read from the
                    // store when this window opened; this screen edits no setting, so there is nothing
                    // fresher for it to consult.
                    val trace = linuxUserspace.exportInstallLog()
                    // Both outcomes are reported, and the success one is not a guess. `copy` swallows a
                    // refused write itself, and the only refusal it can meet is the platform's "no
                    // window focus" on Android 10+ — which cannot be the case here, because this ran
                    // from a tap on a control in the window that holds focus. A copy that returns
                    // without throwing is a copy. Confirming it matters more now that the action is an
                    // icon: with no label, there is nothing else on screen to show the tap registered.
                    runCatching { secureClipboard.copy(trace, settings.clearClipboardAfterSeconds) }
                        .onSuccess { report("Install log copied to the clipboard") }
                        .onFailure { report("Could not copy to the clipboard") }
                },
                onSave = {
                    // Stashed, then held, then launched, in that order: the callback can only fire
                    // after the picker has been on screen, and the flag has to be up before that.
                    pendingExport = linuxUserspace.exportInstallLog()
                    vaultUnlockGate.hold()
                    logSavePicker.launch("eclipse-ubuntu-install.log")
                },
                onClear = { linuxUserspace.clearInstallLog() },
            )
        }
    }
}

/**
 * The install and repair trace, newest first — the userspace counterpart of the session diagnostics,
 * and the answer to a question that trace cannot answer at all.
 *
 * The two traces are separate rings and stay separate: this one is written by the Linux userspace's
 * own subsystems (storage, rootfs, download, proot, dns, apt) and describes one install's life, not
 * one SSH session's. Until this dialog existed the ring was written and never read — every event a
 * failed install produced went to logcat, which a user without `adb` does not have, so the one
 * person who needed the evidence was the only one who could not get it.
 *
 * Newest first on screen, oldest first in the copy: on screen the answer wanted is the last thing
 * that happened, and in a pasted report the reader has to follow the install forwards.
 *
 * This is `MainActivity`'s dialog, which is `private` there and therefore out of reach of a screen
 * that is no longer part of that file. It is a `LazyColumn` here and safe as one: a dialog body is its
 * own window, so unlike the card this is opened from, it is not measured against
 * `SettingsBody`'s infinite scrolling height.
 */
@Composable
private fun InstallLogDialog(
    events: List<UserspaceDiagnosticEvent>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val ordered = remember(events) { events.asReversed() }
    val clock = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install log") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = rememberDialogBodyMaxHeight(0.60f)),
            ) {
                if (events.isEmpty()) {
                    Text(
                        "Nothing recorded yet. Install or repair Ubuntu on this device and this " +
                            "becomes a timestamped trace of every download, extraction and apt step.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${events.size} event(s) · every line is a subsystem step or an error's own " +
                            "text, scrubbed before it was recorded, so this is safe to attach to a " +
                            "bug report.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // The line() form starts with the epoch millis, which the exported text
                        // needs and a reader does not; the row shows a clock and drops the number.
                        items(ordered, key = { it.sequence }) { entry ->
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        clock.format(Instant.ofEpochMilli(entry.atMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        entry.line().substringAfter(' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                    // Centred rather than the Row default of Top: an icon button's box is taller than a
                    // text button's, so top alignment would leave the icon riding above Save and Clear.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The label is gone, so the content description carries what it said — and it
                        // names the thing being copied rather than only the verb, because the body it
                        // sits under is a wall of monospace lines that "Copy" alone would not identify.
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy install log")
                        }
                        TextButton(onClick = onSave) { Text("Save") }
                        TextButton(onClick = onClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The distro's display name, or a neutral title on a device where none is supported. */
private fun distroTitle(ui: LinuxUserspaceUiState): String =
    ui.distro?.displayName ?: "Ubuntu"

/**
 * One install phase as the progress line renders it: the label, and the download's fraction when
 * the phase has one (only the download does — verification, extraction and setup are steps whose
 * length the pipeline honestly cannot know, and a fake progress bar is worse than none).
 */
private fun describeInstallStep(step: LinuxInstallStep): Pair<String, Float?> = when (step) {
    is LinuxInstallStep.Downloading -> {
        "Downloading · ${formatTransferBytes(step.received)} of ${formatTransferBytes(step.total)}" to
            (step.received.toFloat() / step.total.toFloat().coerceAtLeast(1f))
    }
    LinuxInstallStep.Verifying -> "Verifying the download" to null
    is LinuxInstallStep.Extracting -> "Extracting · ${step.entries} files" to null
    is LinuxInstallStep.SettingUp -> describeSetupStep(step.step, step.detail) to null
    LinuxInstallStep.VerifyingHealth -> "Running the health check" to null
}

private fun describeSetupStep(step: SetupStep, detail: String?): String {
    val label = when (step) {
        SetupStep.REGISTER_USER -> "Creating the ubuntu account"
        SetupStep.PREPARE_WORKSPACE -> "Preparing the workspace"
        SetupStep.CONFIGURE_DNS -> "Configuring DNS"
        SetupStep.CONFIGURE_APT -> "Configuring package sources"
        SetupStep.UPDATE_PACKAGES -> "Updating package lists"
        SetupStep.INSTALL_BASE_PACKAGES -> "Installing the base packages"
        SetupStep.INSTALL_NODEJS -> "Installing Node.js"
        SetupStep.INSTALL_GLOBAL_TOOLS -> "Installing pnpm and the OpenCode CLI"
        SetupStep.VERIFY -> "Verifying"
    }
    // The newest command output beside the step's label: a slow-but-alive `apt-get update` shows
    // "Get: 47 …" crawling instead of a label that could be wedged for all the user can tell.
    // Capped because an apt line is unbounded prose, and this sits in a settings row's subtitle.
    return if (detail.isNullOrBlank()) label else "$label · ${detail.trim().take(80)}"
}

/**
 * Bytes in the units installs actually reach, for the "4.2 GB / 1.8 GB" progress lines.
 *
 * `MainActivity`'s helper, which is `private` there and therefore out of reach of a screen that is no
 * longer part of that file. The transfers it was written for keep their own copy next to the transfer
 * rows; this one is the userspace screen's, and the two are the same function rather than two ideas
 * about the same number.
 */
private fun formatTransferBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = "B"
    for (next in listOf("KB", "MB", "GB", "TB")) {
        if (value < 1024) break
        value /= 1024
        unit = next
    }
    return if (unit == "B") "$bytes B" else "${"%.1f".format(value)} $unit"
}

/**
 * Writes [bytes] to the SAF document at [uri], off the main thread.
 *
 * This is `MainActivity`'s helper, which is `private` there and therefore out of reach of a window
 * that is no longer part of that file. The reasoning is that file's and still applies: an
 * activity-result callback is delivered on the main thread, and a document write is a binder round
 * trip into whichever provider owns the URI - which may be a cloud backend that syncs over the network
 * before the stream closes. Inline it drops frames at best and blocks long enough to ANR at worst, and
 * a revoked grant or a full volume would throw straight out of the callback and take the process down.
 * The [Result] is what lets the callback report the failure rather than crash on it.
 *
 * Opened with `"w"`: the picker has already asked the user about replacing an existing document, so
 * this is the write that answer referred to.
 */
private suspend fun writeDocument(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("The selected location could not be opened for writing")
            stream.use { it.write(bytes) }
        }
    }
