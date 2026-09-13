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
import dev.eclipse.ssh.linux.HealthReport
import dev.eclipse.ssh.linux.LinuxDistro
import dev.eclipse.ssh.linux.LinuxUserspaceManager
import dev.eclipse.ssh.linux.LinuxUserspaceState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
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
 */
data class LinuxUserspaceUiState(
    val supported: Boolean = false,
    val distro: LinuxDistro? = null,
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
 * controller and the flows it publishes.
 *
 * On construction, an installed-but-stopped userspace gets one background health probe: the host
 * card requires a passing probe, and a fresh process starts with none. Without this kick, the
 * "Local Ubuntu 22.04" card would never appear after an app relaunch until the user visited a
 * screen that happened to refresh health — the card would silently disagree with the truth.
 */
@Singleton
class LinuxUserspaceController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    /** The whole graph, or null on an unsupported device; the connect path and the host-list rule read it directly. */
    val graph: LinuxUserspaceGraph?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val manager: LinuxUserspaceManager? = graph?.manager

    private val _error = MutableStateFlow<String?>(null)
    private val _installWarnings = MutableStateFlow<List<String>>(emptyList())
    private val _storageUsedBytes = MutableStateFlow(0L)
    private val _workspaceFileCount = MutableStateFlow(0L)
    private val _hasPendingBackup = MutableStateFlow(false)

    /** The UI state. On an unsupported device this is a constant; nothing beneath it exists. */
    val uiState: StateFlow<LinuxUserspaceUiState>

    init {
        val graph = graph
        val manager = graph?.manager
        uiState =
            if (graph == null || manager == null) {
                MutableStateFlow(LinuxUserspaceUiState())
            } else {
                combine(
                    manager.state,
                    manager.lastHealth,
                    _storageUsedBytes,
                    _workspaceFileCount,
                    combine(_error, _installWarnings, manager.sessionCount, _hasPendingBackup) {
                        error, warnings, sessions, backup ->
                        Extras(error, warnings, sessions, backup)
                    },
                ) { state, health, storage, files, extras ->
                    LinuxUserspaceUiState(
                        supported = true,
                        distro = graph.distro,
                        state = state,
                        health = health,
                        storageUsedBytes = storage,
                        workspaceFileCount = files,
                        hasPendingWorkspaceBackup = extras.pendingBackup,
                        sessionCount = extras.sessionCount,
                        error = extras.error,
                        installWarnings = extras.warnings,
                    )
                }.stateIn(
                    scope,
                    // WhileSubscribed, not Eagerly: this flow's subscribers are the settings screen,
                    // which only exists while it is shown. The host-list card does not read it — it
                    // reads the manager's own flows, which never pause — so pausing this one when
                    // nobody collects saves the combine work without ever hiding the card.
                    SharingStarted.WhileSubscribed(5_000),
                    // The synchronous now, so a resubscribed screen's first frame is the current
                    // truth rather than a "not installed" flash while the combine warms up.
                    LinuxUserspaceUiState(
                        supported = true,
                        distro = graph.distro,
                        state = manager.state.value,
                        health = manager.lastHealth.value,
                    ),
                )
            }

        if (manager != null) {
            watchSettledStates(manager)
            probeInstalledUserspace(manager)
            holdProcessWhileRunning(manager)
        }
    }

    // ------------------------------------------------------------------ actions

    /** Installs the userspace from scratch: download, verify, extract, set up, verify health. */
    fun install() = act("Install") { _installWarnings.value = manager!!.install().warnings }

    /** Starts the userspace: verifies health, then holds it open for terminal sessions. */
    fun start() = act("Start") { manager!!.start() }

    /** Stops the userspace, closing every live terminal session it holds. */
    fun stop() = act("Stop") { manager!!.stop() }

    /** Stop then Start — the settings screen's Restart. */
    fun restart() = act("Restart") { manager!!.restart() }

    /** Repairs a broken install without re-downloading what is still sound. */
    fun repair() = act("Repair") { _installWarnings.value = manager!!.repair().warnings }

    /**
     * Uninstalls the userspace. [keepWorkspace] snapshots the workspace first (its failure aborts
     * the uninstall rather than destroying the projects it was keeping); otherwise the workspace
     * and any pending snapshot go too.
     */
    fun uninstall(keepWorkspace: Boolean) =
        act("Uninstall") { manager!!.uninstall(keepWorkspace = keepWorkspace) }

    /** Re-runs the health probe on demand; the settings screen's status line is its answer. */
    fun refreshHealth() = act("Health check") { manager!!.refreshHealth() }

    /** Clears the error line once the user has read it. */
    fun clearError() { _error.value = null }

    /** Clears the install warnings once the user has read them. */
    fun clearInstallWarnings() { _installWarnings.value = emptyList() }

    // ------------------------------------------------------------------ internals

    /**
     * The fields that change too often to deserve their own combine slot — grouped so the five-flow
     * typed overload stays available for the state's main sources.
     */
    private data class Extras(
        val error: String?,
        val warnings: List<String>,
        val sessionCount: Int,
        val pendingBackup: Boolean,
    )

    /**
     * Runs one lifecycle action on the controller's scope, with the one error story every action
     * shares: the manager has already moved its state machine to the right failure state (its own
     * contract), and this layer adds the human sentence the settings screen shows beneath it.
     */
    private fun act(name: String, block: suspend () -> Unit) {
        val manager = this.manager ?: return
        scope.launch {
            _error.value = null
            try {
                block()
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
                        is LinuxUserspaceState.NotInstalled,
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
        val manager = manager ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                _storageUsedBytes.value = manager.storageUsedBytes()
                _workspaceFileCount.value = graph?.workspace?.fileCount() ?: 0L
                _hasPendingBackup.value = manager.hasPendingWorkspaceBackup()
            }
        }
    }
}
