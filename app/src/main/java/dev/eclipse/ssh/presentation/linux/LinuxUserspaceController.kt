package dev.eclipse.ssh.presentation.linux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R
import dev.eclipse.ssh.background.LinuxUserspaceService
import dev.eclipse.ssh.background.NotificationChannels
import dev.eclipse.ssh.background.postAlert
import dev.eclipse.ssh.di.LinuxUserspaceGraph
import dev.eclipse.ssh.di.LinuxUserspaceGraphProvider
import dev.eclipse.ssh.linux.HealthReport
import dev.eclipse.ssh.linux.LinuxDistro
import dev.eclipse.ssh.linux.LinuxUserspaceManager
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.LinuxUserspaceState.NotInstalled
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What Settings → Linux Userspace renders, in one snapshot — and what the host-list rule reads.
 *
 * [supported] is false exactly when the device maps to no Ubuntu architecture (an x86 emulator, an
 * ABI the catalog has no rootfs for); the UI then shows "not supported on this device" instead of
 * an Install button that cannot finish. Everything else is null rather than defaulted on such a
 * device, so a screen cannot accidentally render a fake status line.
 *
 * [distro] is the version the *next* install downloads while nothing is installed (the install
 * screen's chooser moves it), and the version that *is* installed once something is — never a
 * third thing in between, because the graph the state comes from is built around exactly one of
 * those two distros. [availableVersions] is the chooser's list, empty on an unsupported device.
 */
data class LinuxUserspaceUiState(
    val supported: Boolean = false,
    val distro: LinuxDistro? = null,
    /** Every LTS this device can run, newest first; the install dialog's version list. */
    val availableVersions: List<LinuxDistro> = emptyList(),
    val state: LinuxUserspaceState? = null,
    val health: HealthReport? = null,
    /** Total bytes under the userspace root — rootfs, tmp and workspace together. */
    val storageUsedBytes: Long = 0,
    /** Files in the persisted workspace; the "keep workspace" dialog's stakes. */
    val workspaceFileCount: Long = 0,
    /** A keep-workspace snapshot from a previous uninstall exists, awaiting the next install. */
    val hasPendingWorkspaceBackup: Boolean = false,
    /** Live terminal sessions held by the userspace right now. */
    val sessionCount: Int = 0,
    /** The last operation's failure, verbatim; cleared by [clearError] or the next operation. */
    val error: String? = null,
    /** The last install/repair's non-fatal warnings (npm tools that did not install, sudo skipped). */
    val installWarnings: List<String> = emptyList(),
)

/**
 * The Linux userspace's UI surface: one state object for the settings screen, one action per
 * lifecycle verb, and nothing else. The managers underneath ([LinuxUserspaceManager] and its
 * graph) are deliberately not exposed — the UI acts through named actions so every transition
 * funnels through the same error handling, and the only observers of the raw flows are this
 * controller and the flows it publishes. ([graph] is the one exception, and it exists because the
 * connect path and the session registry need the manager's collaborators, not its state.)
 *
 * The graph arrives via [LinuxUserspaceGraphProvider] rather than as a constructor-bound value,
 * because it is no longer a process-lifetime constant: while nothing is installed, the install
 * screen's version chooser can swap it. The UI state therefore derives from the provider's graph
 * *flow* — a swap re-subscribes every collector to the new graph's flows — and one-shot readers
 * ([graph], every action) resolve the current graph at the moment they run.
 *
 * On construction over an installed-but-stopped userspace, one background health probe runs: the
 * host card requires a passing probe, and a fresh process starts with none. Without this kick, the
 * "Local Ubuntu" card would never appear after an app relaunch until the user visited a screen
 * that happened to refresh health — the card would silently disagree with the truth.
 */
@Singleton
class LinuxUserspaceController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val graphProvider: LinuxUserspaceGraphProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The whole graph, or null on an unsupported device; the connect path and the host-list rule
     * read it directly. A property, not a val: it tracks the version the user picks while nothing
     * is installed, so a caller cannot be left holding the graph from process start forever.
     */
    val graph: LinuxUserspaceGraph? get() = graphProvider.graph

    /** The graph as a flow, for subscribers that must re-derive when a version choice swaps it. */
    val graphFlow: StateFlow<LinuxUserspaceGraph?> get() = graphProvider.graphFlow

    private val _error = MutableStateFlow<String?>(null)
    private val _installWarnings = MutableStateFlow<List<String>>(emptyList())

    /**
     * The three storage facts as one value, not three flows. A recompute used to write bytes, file
     * count and the pending-backup claim as three separate StateFlow writes, and a subscriber
     * could observe the combination in between — most misleadingly a freshly restored workspace
     * (one file, bytes on disk) shown beside a backup still claimed as parked, three writes after
     * the restore consumed it. One write per recompute makes the half-updated state unobservable;
     * a failed recompute keeps the previous whole value instead of a partial new one.
     */
    private val _storageFacts = MutableStateFlow(StorageFacts())

    /** The UI state. On an unsupported device this is a constant; nothing beneath it exists. */
    val uiState: StateFlow<LinuxUserspaceUiState>

    init {
        uiState =
            graphProvider.graphFlow
                .flatMapLatest { graph ->
                    if (graph == null) {
                        flowOf(LinuxUserspaceUiState())
                    } else {
                        combine(
                            graph.manager.state,
                            graph.manager.lastHealth,
                            _storageFacts,
                            combine(_error, _installWarnings, graph.manager.sessionCount) {
                                error, warnings, sessions ->
                                Extras(error, warnings, sessions)
                            },
                        ) { state, health, storage, extras ->
                            LinuxUserspaceUiState(
                                supported = true,
                                distro = graph.distro,
                                availableVersions = graphProvider.versions,
                                state = state,
                                health = health,
                                storageUsedBytes = storage.usedBytes,
                                workspaceFileCount = storage.workspaceFiles,
                                hasPendingWorkspaceBackup = storage.hasPendingBackup,
                                sessionCount = extras.sessionCount,
                                error = extras.error,
                                installWarnings = extras.warnings,
                            )
                        }
                    }
                }.stateIn(
                    scope,
                    // WhileSubscribed, not Eagerly: this flow's subscribers are the settings screen,
                    // which only exists while it is shown. The host-list card does not read it — it
                    // reads the manager's own flows, which never pause — so pausing this one when
                    // nobody collects saves the combine work without ever hiding the card.
                    SharingStarted.WhileSubscribed(5_000),
                    // The synchronous now, so a resubscribed screen's first frame is the current
                    // truth rather than a "not installed" flash while the combine warms up.
                    currentSnapshot(),
                )

        // The graph-long collectors. collectLatest, not collect: a version swap while nothing is
        // installed cancels the previous graph's collectors (whose flows would otherwise report a
        // NotInstalled that is no longer anyone's truth) and starts them over the new graph.
        scope.launch {
            graphFlow.collectLatest { graph ->
                val manager = graph?.manager ?: return@collectLatest
                watchSettledStates(manager)
                probeInstalledUserspace(manager)
                holdProcessWhileRunning(manager)
            }
        }
    }

    /** The snapshot [stateIn] starts from, computed over whatever graph is current right now. */
    private fun currentSnapshot(): LinuxUserspaceUiState {
        val graph = graphProvider.graph ?: return LinuxUserspaceUiState()
        return LinuxUserspaceUiState(
            supported = true,
            distro = graph.distro,
            availableVersions = graphProvider.versions,
            state = graph.manager.state.value,
            health = graph.manager.lastHealth.value,
        )
    }

    // ------------------------------------------------------------------ actions

    /**
     * Chooses the version [install] downloads: records the pick and swaps the graph while nothing
     * is installed. Refused once anything is on disk — the installed version is what Repair and
     * Start operate on, and only an Uninstall makes a choice meaningful again.
     */
    fun selectDistro(distroId: String): Boolean = graphProvider.selectDistro(distroId)

    /** Installs the userspace from scratch: download, verify, extract, set up, verify health. */
    fun install() = act("Install") { _installWarnings.value = it.install().warnings }

    /** Starts the userspace: verifies health, then holds it open for terminal sessions. */
    fun start() = act("Start") { it.start() }

    /** Stops the userspace, closing every live terminal session it holds. */
    fun stop() = act("Stop") { it.stop() }

    /** Stop then Start — the settings screen's Restart, one operation. */
    fun restart() = act("Restart") { it.restart() }

    /** Repairs a broken install without re-downloading what is still sound. */
    fun repair() = act("Repair") { _installWarnings.value = it.repair().warnings }

    /**
     * Uninstalls the userspace. [keepWorkspace] snapshots the workspace first (its failure aborts
     * the uninstall rather than destroying the projects it was keeping); otherwise the workspace
     * and any pending snapshot go too.
     */
    fun uninstall(keepWorkspace: Boolean) =
        act("Uninstall") { it.uninstall(keepWorkspace = keepWorkspace) }

    /** Re-runs the health probe on demand; the settings screen's status line is its answer. */
    fun refreshHealth() = act("Health check") { it.refreshHealth() }

    /** Clears the error line once the user has read it. */
    fun clearError() { _error.value = null }

    /** Clears the install warnings once the user has read them. */
    fun clearInstallWarnings() { _installWarnings.value = emptyList() }

    // ------------------------------------------------------------------ internals

    /**
     * The fields that change too often to deserve their own combine slot — grouped so the typed
     * overloads stay available for the state's main sources.
     */
    private data class Extras(
        val error: String?,
        val warnings: List<String>,
        val sessionCount: Int,
    )

    /** The storage facts that must move together; see [_storageFacts]. */
    private data class StorageFacts(
        val usedBytes: Long = 0L,
        val workspaceFiles: Long = 0L,
        val hasPendingBackup: Boolean = false,
    )

    /**
     * Runs one lifecycle action on the controller's scope, with the one error story every action
     * shares: the manager has already moved its state machine to the right failure state (its own
     * contract), and this layer adds the human sentence the settings screen shows beneath it.
     *
     * The manager is resolved when the action is requested, not when the coroutine runs: the graph
     * can be swapped by a version choice between the two moments, and an Install must act on the
     * distro the user saw on the button, not the one that was current when the tap was queued.
     */
    private fun act(name: String, block: suspend (LinuxUserspaceManager) -> Unit) {
        val manager = graphProvider.graph?.manager ?: return
        scope.launch {
            _error.value = null
            try {
                block(manager)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
                _error.value = "$name failed: $detail"
            }
        }
    }

    /**
     * Recomputes storage and workspace numbers whenever the state machine settles — NotInstalled,
     * Stopped or NeedsRepair — because those are the moments the bytes on disk changed (an install
     * finished, an uninstall deleted the root, a repair replaced files). While Installing or
     * Running the numbers would be a moving target the settings screen cannot render honestly.
     */
    private fun watchSettledStates(manager: LinuxUserspaceManager) {
        scope.launch {
            manager.state
                .map { state ->
                    when (state) {
                        NotInstalled,
                        is LinuxUserspaceState.Stopped,
                        is LinuxUserspaceState.NeedsRepair,
                        -> state
                        // Unchanged-but-different sentinel: distinctUntilChanged drops repeats, so
                        // an equal-but-new emission (e.g. NeedsRepair with the same detail) still
                        // reaches the collector below only when it is a real settled state.
                        else -> Unit
                    }
                }
                .distinctUntilChanged()
                .collect { settled ->
                    if (settled !== Unit) refreshStorageNumbers()
                }
        }
    }

    /** One background probe when the process starts over an already-installed userspace. */
    private fun probeInstalledUserspace(manager: LinuxUserspaceManager) {
        scope.launch {
            val state = manager.state.value
            if (state is LinuxUserspaceState.Stopped || state is LinuxUserspaceState.NeedsRepair) {
                runCatching { manager.refreshHealth() }
            }
        }
    }

    /**
     * Keeps [LinuxUserspaceService] alive exactly while the state machine is Running.
     *
     * Every Ubuntu terminal is a child of this process, so the spec's "backgrounding the app does
     * not stop Ubuntu" is a process-liveness promise, and the foreground service is how it is
     * kept. This is the one owner of the binding — every path into Running (Settings' Start, a
     * terminal opening a stopped userspace, a future Repair that ends running) promotes the
     * process, and every path out (Stop, an uninstall) demotes it — while the service itself
     * watches the same state and stops itself if it is ever alive without a Running userspace, so
     * neither side trusts the other.
     *
     * The promotion can be refused on API 31+ when the app is not visible, which today cannot
     * happen (every entry into Running is a user action), so the refusal branch is defensive: the
     * userspace keeps running and the alert is the honest "Ubuntu is now only as durable as the
     * app being open" instead of a silent loss of background protection.
     */
    private fun holdProcessWhileRunning(manager: LinuxUserspaceManager) {
        scope.launch {
            manager.state
                .map { it is LinuxUserspaceState.Running }
                .distinctUntilChanged()
                .collect { running ->
                    val intent = Intent(appContext, LinuxUserspaceService::class.java)
                    if (running) {
                        val promoted = runCatching {
                            ContextCompat.startForegroundService(appContext, intent)
                        }.isSuccess
                        if (!promoted) {
                            postAlert(
                                appContext,
                                NotificationChannels.ID_LINUX_PROMOTION,
                                appContext.getString(R.string.notif_linux_promotion_title),
                                appContext.getString(R.string.notif_linux_promotion_body),
                                PendingIntent.getActivity(
                                    appContext,
                                    0,
                                    Intent(appContext, MainActivity::class.java),
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                                ),
                            )
                        }
                    } else {
                        appContext.stopService(intent)
                    }
                }
        }
    }

    private suspend fun refreshStorageNumbers() {
        val graph = graphProvider.graph ?: return
        // Computed as a whole and published as a whole: the reads happen on the IO dispatcher,
        // and the one write lands on this controller's context, so a subscriber sees either the
        // previous facts or the next ones — never a mixture. A failed recompute keeps the
        // previous whole value; the old code could stop between writes and leave one number
        // from the new disk state beside two from the old.
        val facts = withContext(Dispatchers.IO) {
            runCatching {
                StorageFacts(
                    usedBytes = graph.manager.storageUsedBytes(),
                    workspaceFiles = graph.workspace.fileCount(),
                    hasPendingBackup = graph.manager.hasPendingWorkspaceBackup(),
                )
            }.getOrDefault(_storageFacts.value)
        }
        _storageFacts.value = facts
    }
}
