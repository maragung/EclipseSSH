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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ArrowDownward
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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.linux.LinuxInstallStep
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.OptionalPackage
import dev.eclipse.ssh.linux.OptionalPackages
import dev.eclipse.ssh.linux.RootfsTransferState
import dev.eclipse.ssh.linux.SetupStep
import dev.eclipse.ssh.linux.UserspaceDiagnosticEvent
import dev.eclipse.ssh.linux.percent
import dev.eclipse.ssh.linux.percentDone
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
 * The Import and Export rows obey the same rule for the same reason — an export started over an
 * import would read a tree the import is about to replace, and both take the install lock, so a
 * second tap could only ever be refused underneath. Their own progress rows are the one thing that
 * *does* appear while they run: a transfer that showed no sign of itself would be indistinguishable
 * from a tap that did nothing, and a 250 MB write is a long time to wonder. That row carries the
 * screen's only mid-operation control, and it is a Cancel rather than a second Start: giving up on a
 * copy is a thing a user may do, and it is the one action that cannot race anything — nothing has
 * been replaced yet.
 *
 * The three dialogs below stay dialogs. `confirmInstall`, `confirmUninstall` and the install-log view
 * are questions asked of the screen, not places to go: the first two name what a destructive tap is
 * about to do and are two lines of prose each, and the third is a reading surface opened over the row
 * that changes with the state underneath it. `confirmImport` joins them for the same reason, with the
 * heaviest question of the three: it replaces a userspace that is already working.
 *
 * The install dialog is the one that grew past two lines, and it grew in place rather than into a
 * screen of its own: the preinstall checkboxes are part of the same question ("install this, and
 * what else with it?"), they are meaningless without it, and a separate screen would have to either
 * ask the question twice or answer it with a state the dialog then has to read back. What it costs
 * is height, and that is what [rememberDialogBodyMaxHeight] and a scroll are for — see
 * [PreinstallPicker].
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

        /**
         * The preinstall ticks, held as the entries' own names rather than as the entries.
         *
         * `rememberSaveable` because the dialog outlives a rotation, and a rotation that silently
         * dropped the user's ticks would hand them an install without the packages they chose — the
         * one thing on this screen that is a decision rather than a state. Names, not the enum
         * values, because a `List<String>` is what the saved-state registry saves without a custom
         * saver; [OptionalPackage.entries] puts them back in declaration order either way.
         *
         * Never persisted beyond the dialog, and deliberately: what the user ticked describes *this*
         * install, and a `Repair` months later must not re-download a toolchain nobody re-asked for.
         */
        var extraIds by rememberSaveable { mutableStateOf(listOf<String>()) }
        val selectedExtras = remember(extraIds) {
            OptionalPackage.entries.filter { it.name in extraIds }
        }
        /**
         * The entries this device's architecture cannot install, by name — the dialog's half of the
         * same decision the pipeline makes for itself. An entry whose installer ships no build for
         * this CPU is drawn disabled with the reason under it, so the user is not offered an install
         * that cannot finish and the warning at the end of a long install is not the first they hear
         * of it.
         *
         * `ui.distro` is nullable *here* even though the section below returns on a null one: this is
         * the composable's body, and the non-null `distro` that guard binds is scoped to the
         * `SettingsSection` lambda rather than to this. Null means no state has loaded yet, and an
         * empty set is the honest answer — the Install button that opens the picker cannot exist
         * without a distro either.
         */
        val unsupportedExtraIds = remember(ui.distro) {
            ui.distro?.ubuntuArch?.let { arch ->
                OptionalPackage.entries.filterNot { OptionalPackages.runsOn(it, arch) }
                    .map { it.name }
                    .toSet()
            } ?: emptySet()
        }
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

        /**
         * The chosen archive, held until the confirmation dialog's answer.
         *
         * The picker's own result is not the decision to import: it only says *which* file. What
         * replaces the installed userspace is the dialog's question, so the URI waits here — the
         * name is read off the document while the grant is still alive, because a name resolved
         * after the dialog is a name that may no longer be readable.
         */
        var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
        var pendingImportName by remember { mutableStateOf<String?>(null) }
        var confirmImport by remember { mutableStateOf(false) }

        val exportPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gzip"),
        ) { uri: Uri? ->
            // Released first and unconditionally, like the log's picker and for the same reason: this
            // is the moment the other app's activity is gone, and a flag left held suspends the
            // auto-lock countdown for the rest of the process's life.
            vaultUnlockGate.release()
            if (uri != null) linuxUserspace.exportRootfs(uri)
        }
        val importPicker = rememberLauncherForActivityResult(
            // OpenDocument rather than GetContent: this is a file the user already has, and the
            // difference that matters here is that OpenDocument's grant is what lets the read
            // happen in the same session the archive was chosen in.
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            vaultUnlockGate.release()
            if (uri != null) {
                pendingImportUri = uri
                // Best effort, and never guessed from the URI's last segment unless the provider
                // gives nothing better: it is only ever shown back to the user as the name of the
                // file they picked, and "document:1234" would be a worse answer than null.
                pendingImportName = runCatching {
                    DocumentFile.fromSingleUri(context, uri)?.name
                }.getOrNull() ?: uri.lastPathSegment
                confirmImport = true
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
                    // The percentage trails the step rather than leading it: the line's first word
                    // is the phase ("Downloading", "Installing the base packages"), and that is the
                    // reading the E2E driver's STEP_SUBTITLES and every human scan of this row are
                    // built on. The number is what the bar underneath draws.
                    SettingRow(
                        Icons.Default.CloudDownload,
                        "Installing ${distro.displayName}",
                        "${describeInstallStep(state.step)} · ${state.percent}%",
                    ) { }
                    // The bar and the number above it are the same fact, both read off the state's
                    // one percentage: a bar drawn from a step's own fraction would sit at 90% while
                    // the line beside it said 43%, which is the disagreement a progress display
                    // exists to prevent.
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
                is LinuxUserspaceState.NotInstalled -> SettingRow(
                    Icons.Default.Computer,
                    "Ubuntu on this device",
                    if (ui.hasPendingWorkspaceBackup) {
                        "Not installed · a saved workspace will be restored"
                    } else {
                        "Not installed · real bash, apt and git, on the device"
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

            // One predicate for every control below, because a transfer writes the same tree the
            // lifecycle actions do: two rules about what may be tapped now would be two rules that
            // could disagree, and the tap that got through the gap would be the one that matters.
            // (`state == null` is the compiler's proof that a graph exists, not a state a device
            // reaches — see the `when` above.)
            val busy = state == null ||
                ui.transfer !is RootfsTransferState.Idle ||
                state is LinuxUserspaceState.Installing ||
                state is LinuxUserspaceState.Starting ||
                state is LinuxUserspaceState.Stopping

            // The action row. Kept out of SettingRow's width-capped trailing slot on purpose: Start,
            // Restart and Uninstall are three coequal controls and capping the third to fit a label
            // column would demote whichever action the layout happened to squeeze.
            //
            // Absent, not disabled, while an operation is in flight - the rule in the class KDoc, and
            // the same `busy` the transfer rows below obey. A disabled Install would still be a
            // control that names an action already running, and this row is the one place on the
            // screen where a stray tap can restart a pipeline or end one.
            if (!busy) {
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

            if (!busy) {
                // Offered only where there is a tree to read: an Export button on a fresh device
                // could only ever produce the error line that says so.
                if (state !is LinuxUserspaceState.NotInstalled) {
                    SettingRow(
                        Icons.Default.Upload,
                        "Export the root filesystem",
                        "Installed packages, settings and the workspace · one .tar.gz you choose",
                    ) {
                        TextButton(
                            onClick = {
                                // Held, then launched, in that order: the callback can only fire after
                                // the picker has been on screen, and the flag has to be up before that.
                                vaultUnlockGate.hold()
                                // Named for what it is, version included: a user who keeps two exports
                                // has nothing else to tell them apart, and the extension is what makes
                                // the file openable by the next Import.
                                exportPicker.launch("eclipse-ubuntu-${distro.release}-rootfs.tar.gz")
                            },
                        ) { Text("Export") }
                    }
                }
                // Offered even when nothing is installed, and that is the point of it: restoring a
                // backup is a way to *get* a userspace, and it is the one path that installs one
                // without downloading a byte of it.
                SettingRow(
                    Icons.Default.ArrowDownward,
                    "Import a root filesystem",
                    if (state is LinuxUserspaceState.NotInstalled) {
                        "Restore a .tar.gz exported here or from another device · nothing installed to lose"
                    } else {
                        "Restore a .tar.gz exported here or from another device · this one is replaced"
                    },
                ) {
                    TextButton(
                        onClick = {
                            vaultUnlockGate.hold()
                            // Everything, deliberately: an export is a .tar.gz but arrives labelled
                            // `application/gzip`, `application/x-tar` or `application/octet-stream`
                            // depending on who wrote it, and the filter that admits all three is no
                            // filter at all. What the file actually is gets decided by reading it —
                            // see RootfsArchive's marker check — not by the provider's guess.
                            importPicker.launch(arrayOf("*/*"))
                        },
                    ) { Text("Import") }
                }
            }

            // Where the transfer itself is shown, and the one row that appears *while* the buttons
            // above are gone: a 250 MB write is a long time to look at a screen that could equally be
            // showing a tap that never registered. The bar and the number beside it read the one
            // percentage off the one state, exactly as the install row's do.
            val transfer = ui.transfer
            if (transfer !is RootfsTransferState.Idle) {
                SettingRow(
                    transferIcon(transfer),
                    transferTitle(transfer),
                    describeTransfer(transfer),
                ) {
                    // Only while the transfer can still be abandoned. Past the swap the row is this
                    // screen's own install row again — the setup pipeline the import handed off to —
                    // and there is no Cancel there for the same reason an install has none: stopping
                    // it half way is what NeedsRepair is for, not what a tap on it means.
                    if (transfer is RootfsTransferState.Exporting || transfer is RootfsTransferState.Importing) {
                        TextButton(onClick = { linuxUserspace.cancelTransfer() }) { Text("Cancel") }
                    }
                }
                val percent = transfer.percentDone
                if (percent == null) {
                    // No denominator (a provider that will not report a size): an indeterminate bar
                    // is the honest drawing, where a bar at zero would claim to know it is at zero.
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
            }

            // The last export's outcome. A success with no line to say so is the one thing an export
            // cannot show any other way: the file is in another app's directory, and nothing on this
            // screen changes when it lands there.
            if (ui.transferNotice != null) {
                SettingRow(
                    Icons.Default.CheckCircle,
                    "Root filesystem",
                    ui.transferNotice ?: "",
                ) { TextButton(onClick = { linuxUserspace.clearTransferNotice() }) { Text("OK") } }
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
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        // Capped and scrollable for the reason the log dialog is: the body is a
                        // paragraph plus one checkbox per entry with two lines each, which is taller
                        // than a phone's dialog can show. A body that grew past the screen would put
                        // the Install button out of reach of the very control that asked the question.
                        modifier = Modifier
                            .heightIn(max = rememberDialogBodyMaxHeight(0.60f))
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "A verified ${distroTitle(ui)} root filesystem (~30 MB) is downloaded and " +
                                "its base packages — bash, apt, git, curl, wget and an SSH client — " +
                                "are installed through apt itself, which needs a few hundred MB over " +
                                "your network. Anything else you want is one apt-get install away in " +
                                "the terminal, or tick it below and this install adds it for you. " +
                                "Inside it you are root over your own files and nothing else, and the " +
                                "workspace at /home/ubuntu/workspace survives Stop and Restart." +
                                if (ui.hasPendingWorkspaceBackup) {
                                    " Your saved workspace is restored after the install."
                                } else {
                                    ""
                                },
                        )
                        PreinstallPicker(
                            selectedIds = extraIds,
                            unsupportedIds = unsupportedExtraIds,
                            onSelectionChange = { extraIds = it },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { confirmInstall = false; linuxUserspace.install(selectedExtras) },
                    ) { Text("Install") }
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
        if (confirmImport) {
            // The plain confirmation the brief asks for, and the wording is load-bearing: what the
            // tap replaces is a userspace the user may have spent an hour of apt-get inside, and the
            // only protection against that is a sentence that says so plainly. It is also honest
            // about when the risk actually starts — nothing is deleted until a complete, verified
            // copy of the archive is on disk, so the state named here is the state that ends up
            // installed, not a state a failure could leave behind.
            val name = pendingImportName
            AlertDialog(
                onDismissRequest = { confirmImport = false },
                title = { Text("Import this root filesystem?") },
                text = {
                    val opening =
                        (if (name != null) "$name is " else "The chosen archive is ") +
                            "unpacked and checked before anything is replaced, and "
                    val consequence =
                        if (ui.state is LinuxUserspaceState.NotInstalled) {
                            "nothing is installed to lose. The workspace at /home/ubuntu/workspace " +
                                "comes from the archive."
                        } else {
                            "the installed ${distroTitle(ui)} — packages, files and the workspace at " +
                                "/home/ubuntu/workspace — is replaced when it has been. Uninstalling " +
                                "first would keep nothing that is not in the archive or already exported."
                        }
                    Text(
                        opening + consequence +
                            " Anything the archive was set up for — an account, a DNS server, an apt " +
                            "mirror — is rewritten for this device afterwards, and the userspace then " +
                            "has to pass the same health check an install does.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmImport = false
                            // The URI was handed over at picker time; the read is the controller's,
                            // because the stream, the byte count and the failure report all belong to
                            // the same operation the progress row above is drawing.
                            pendingImportUri?.let { linuxUserspace.importRootfs(it) }
                            pendingImportUri = null
                            pendingImportName = null
                        },
                    ) { Text("Import", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            // Dropped, not kept: a confirmation that was declined is a decision, and
                            // holding the URI would arm the next tap of Import with a file the user
                            // has since said no to.
                            pendingImportUri = null
                            pendingImportName = null
                            confirmImport = false
                        },
                    ) { Text("Cancel") }
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

/**
 * The preinstall checkboxes on the install dialog: what the user asked for beyond the base system.
 *
 * One row per [OptionalPackage], each with its own two lines — the label the user recognises and
 * the packages that make it true — because the whole value of this list is that the user does not
 * have to know that "Python 3" means four packages and a `python` symlink. A row this device cannot
 * install gets a third line saying so, and no others do.
 *
 * The row is the control, not the box: the whole row toggles, and the `Checkbox` inside it is
 * drawn with a null callback so a screen reader meets one labelled control rather than a checkbox
 * and a label that are two targets for one decision.
 *
 * [unsupportedIds] is this dialog's half of a decision the pipeline makes for itself: an entry whose
 * installer ships no build for this device's CPU is drawn disabled, with the reason, rather than
 * offered and then skipped. The pipeline is still the authority — it decides the same question from
 * the architecture it is actually installing, and says so in a warning — and this exists only so the
 * user hears it before the install rather than after it.
 *
 * [selectedIds] crosses the dialog's boundary as strings — see the caller's own note — and comes
 * back the same way, in the order the user ticked; nothing here depends on that order, and nothing
 * downstream does either. The dependency line at the bottom is the one thing a tick can cause that
 * is invisible on the row itself: the coding agents need Node.js, so ticking one of them installs
 * it whether or not its own box is ticked, and saying so here is cheaper than explaining it after
 * the install.
 */
@Composable
private fun PreinstallPicker(
    selectedIds: List<String>,
    unsupportedIds: Set<String>,
    onSelectionChange: (List<String>) -> Unit,
) {
    val selected = OptionalPackage.entries.filter { it.name in selectedIds }
    val implied = OptionalPackages.implied(selected)
    // The rows a tick can actually act on. "All" and the all-selected test both walk this rather than
    // every entry, or the button would tick a row the pipeline is going to skip — a control whose
    // whole meaning is "everything" cannot include something it knows will not install.
    val selectable = OptionalPackage.entries.filterNot { it.name in unsupportedIds }
    val allSelected = selectable.all { it.name in selectedIds }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Preinstall (optional)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    onSelectionChange(
                        if (allSelected) emptyList() else selectable.map { it.name },
                    )
                },
                // Named for what it does to the selection rather than for its own label: "All" and
                // "None" are the same control in two states, and a screen reader hears a bare "All"
                // with no way to tell which list it applies to.
                modifier = Modifier.semantics {
                    contentDescription =
                        if (allSelected) "Clear the preinstall selection" else "Select every preinstall package"
                },
            ) { Text(if (allSelected) "None" else "All") }
        }
        Text(
            "Installed after the base system, most of them from the same Ubuntu archive. The npm " +
                "agents come from npm's registry and need Node.js; the Claude Code CLI comes from its " +
                "maker's own installer. What reaches outside the archive needs a working connection, " +
                "and one that does not install is reported as a warning rather than failing the " +
                "install.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionalPackage.entries.forEach { entry ->
            val supported = entry.name !in unsupportedIds
            val checked = supported && entry in selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        enabled = supported,
                        role = Role.Checkbox,
                        onValueChange = { ticked ->
                            onSelectionChange(
                                if (ticked) selectedIds + entry.name else selectedIds - entry.name,
                            )
                        },
                    ),
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = supported)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The row stays rather than disappearing: this list's header says it is optional,
                    // not exhaustive, and a checkbox that vanishes reads as a feature that went away.
                    // The reason is the thing the user cannot get anywhere else on this screen.
                    if (!supported) {
                        Text(
                            "Not installable here: no build for this device's architecture.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (implied.isNotEmpty()) {
            Text(
                "Also installed, because it is what they run on: " +
                    implied.joinToString(", ") { it.label } + ".",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The distro's display name, or a neutral title on a device where none is supported.
 */
private fun distroTitle(ui: LinuxUserspaceUiState): String =
    ui.distro?.displayName ?: "Ubuntu"

/**
 * One install phase as the progress line renders it: the label, and the download's fraction when
 * the phase has one (only the download does — verification, extraction and setup are steps whose
 * length the pipeline honestly cannot know, and a fake progress bar is worse than none).
 */
/**
 * One install phase as the progress line renders it. The percentage that goes beside it is the
 * state's, not the phase's: what a watcher wants is how far the install has got, and a phase-local
 * number would read 0% again at each new step.
 */
private fun describeInstallStep(step: LinuxInstallStep): String = when (step) {
    is LinuxInstallStep.Downloading ->
        "Downloading · ${formatTransferBytes(step.received)} of ${formatTransferBytes(step.total)}"
    LinuxInstallStep.Verifying -> "Verifying the download"
    is LinuxInstallStep.Extracting -> "Extracting · ${step.entries} files"
    is LinuxInstallStep.SettingUp -> describeSetupStep(step.step, step.detail)
    LinuxInstallStep.VerifyingHealth -> "Running the health check"
}

private fun describeSetupStep(step: SetupStep, detail: String?): String {
    val label = when (step) {
        SetupStep.REGISTER_USER -> "Creating the ubuntu account"
        SetupStep.PREPARE_WORKSPACE -> "Preparing the workspace"
        SetupStep.CONFIGURE_DNS -> "Configuring DNS"
        SetupStep.CONFIGURE_APT -> "Configuring package sources"
        SetupStep.UPDATE_PACKAGES -> "Updating package lists"
        SetupStep.INSTALL_BASE_PACKAGES -> "Installing the base packages"
        SetupStep.INSTALL_EXTRA_PACKAGES -> "Installing the extra packages"
        SetupStep.VERIFY -> "Verifying"
    }
    // The newest command output beside the step's label: a slow-but-alive `apt-get update` shows
    // "Get: 47 …" crawling instead of a label that could be wedged for all the user can tell.
    // Capped because an apt line is unbounded prose, and this sits in a settings row's subtitle.
    return if (detail.isNullOrBlank()) label else "$label · ${detail.trim().take(80)}"
}

/** The transfer progress row's heading: what is being moved, in the direction it is moving. */
private fun transferTitle(state: RootfsTransferState): String = when (state) {
    RootfsTransferState.Idle -> "Root filesystem"
    is RootfsTransferState.Exporting -> "Exporting the root filesystem"
    is RootfsTransferState.Importing -> "Importing the root filesystem"
    RootfsTransferState.Swapping -> "Replacing the root filesystem"
}

/**
 * The icon of the transfer row, pointing the way the bytes are going — the same two icons the rows
 * that start a transfer carry, so the progress line is recognisably the continuation of the tap.
 */
private fun transferIcon(state: RootfsTransferState): ImageVector = when (state) {
    RootfsTransferState.Idle,
    is RootfsTransferState.Exporting,
    -> Icons.Default.Upload
    is RootfsTransferState.Importing,
    RootfsTransferState.Swapping,
    -> Icons.Default.ArrowDownward
}

/**
 * A transfer's progress as its row's subtitle: the phase, then the same percentage the bar under it
 * draws.
 *
 * Shaped like [describeInstallStep] and for the same reason — the first word is what is happening and
 * the trailing number is how far in — with one difference that is the point of it: a transfer's
 * percentage is absent while its total is unknown (a provider that will not report the size of the
 * document it handed over), and the bytes moved are what it has instead.
 */
private fun describeTransfer(state: RootfsTransferState): String = when (state) {
    RootfsTransferState.Idle -> ""
    is RootfsTransferState.Exporting ->
        "Written ${transferBytes(state.bytes, state.totalBytes)} · ${state.entries} entries" +
            percentSuffix(state.percentDone)
    is RootfsTransferState.Importing ->
        "Read ${transferBytes(state.bytes, state.totalBytes)} · ${state.entries} entries" +
            percentSuffix(state.percentDone)
    // Deliberately no byte count and no percentage: this phase reads nothing and its length is a
    // rename, so a number here would be a number about the phase before it.
    RootfsTransferState.Swapping -> "Verified · swapping it in"
}

/** "12.4 MB of 118.0 MB", or only the first when what it is a fraction of is unknown. */
private fun transferBytes(bytes: Long, totalBytes: Long): String =
    if (totalBytes > 0) "${formatTransferBytes(bytes)} of ${formatTransferBytes(totalBytes)}"
    else formatTransferBytes(bytes)

private fun percentSuffix(percent: Int?): String = if (percent == null) "" else " · $percent%"

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
