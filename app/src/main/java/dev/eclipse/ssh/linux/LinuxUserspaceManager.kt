package dev.eclipse.ssh.linux

import java.io.File
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The userspace's lifecycle: Not Installed → Installing → Stopped ⇄ Starting ⇄ Running →
 * Stopping, with NeedsRepair as the honest alternative to pretending a broken install works.
 *
 * What "Running" means here needs saying, because proot has no daemon: the userspace is *not* a
 * process that starts and stops. It is a rootfs plus the ability to fork shells into it. Running
 * therefore means "held open": the manager has verified the runtime with a health probe and
 * declared the environment active, and the foreground service (wired outside this class) keeps the
 * app's process — and with it every live proot child — alive while the user is elsewhere. Closing
 * the last terminal does *not* stop it; only an explicit Stop (or the app's process dying, which
 * takes the pty masters with it) returns it to Stopped. That is the spec's "terminal close is not
 * logout" rule, and this is the only class that enforces it.
 *
 * Persistence is one properties file under the userspace root, holding nothing but facts
 * (`installed=true`, which distro, when). Derived truth is always re-derived: on construction the
 * persisted "installed" is checked against the files actually on disk, and a disagreement yields
 * NeedsRepair rather than a state the UI would render as healthy. Crash recovery is exactly this
 * path — after a crash the sessions are gone (their pty masters died with the process), the files
 * usually are not, and the manager comes back as Stopped, ready to Start again.
 *
 * Every public operation is serialized by one mutex, so there is exactly one transition in flight
 * at any moment and no state can be observed mid-flight.
 *
 * @param rootDir the userspace root (`filesDir/linux`)
 * @param distro the pinned distribution this manager installs and runs
 * @param backupFile where the keep-workspace uninstall parks its snapshot; deliberately outside
 *   [rootDir] so an uninstall (which deletes the root) cannot destroy the thing it was keeping
 */
class LinuxUserspaceManager(
    private val rootDir: File,
    private val distro: LinuxDistro,
    private val runtime: ProotRuntime,
    private val installer: RootfsInstaller,
    private val distribution: UbuntuDistributionManager,
    private val processes: LinuxProcessManager,
    private val workspace: LinuxWorkspaceManager,
    private val backupFile: File = File(rootDir.parentFile, "linux-workspace-backup.tar.gz"),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val transition = Mutex()

    private val _state = MutableStateFlow<LinuxUserspaceState>(initialState())
    val state: StateFlow<LinuxUserspaceState> = _state

    /** The most recent health probe, when one has run; the settings screen's evidence line. */
    private val _lastHealth = MutableStateFlow<HealthReport?>(null)
    val lastHealth: StateFlow<HealthReport?> = _lastHealth

    val sessionCount: StateFlow<Int> get() = processes.sessionCount

    /** Total bytes under the userspace root — rootfs, tmp and the workspace together. */
    fun storageUsedBytes(): Long =
        if (rootDir.isDirectory) rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** A workspace snapshot from a previous keep-workspace uninstall exists, awaiting reinstall. */
    fun hasPendingWorkspaceBackup(): Boolean = backupFile.isFile

    // ------------------------------------------------------------------ lifecycle operations

    /**
     * The full install: download → verify → extract → set up the distribution → health check →
     * Stopped. The state machine only reaches Stopped after the health probe passes, so "installed"
     * and "works" are the same fact here, which is what lets the host-list card trust the state.
     *
     * A workspace snapshot parked by a previous keep-workspace uninstall is restored as the final
     * step — the fresh rootfs's empty workspace is replaced before the user ever sees it — and a
     * failed restore is a warning, not a failed install: the user still has Ubuntu, and the backup
     * file is left in place for another attempt.
     *
     * @return the setup report's warnings (plus any restore warning)
     * @throws IllegalStateException from any state but NotInstalled/NeedsRepair
     * @throws IOException on any download, verification, extraction or health-check failure
     */
    suspend fun install(): SetupReport = transition.withLock {
        requireIdleForInstall()
        try {
            installer.install { progress ->
                _state.value = LinuxUserspaceState.Installing(progress.toInstallStep())
            }
            val report = distribution.setup { step ->
                _state.value = LinuxUserspaceState.Installing(LinuxInstallStep.SettingUp(step))
            }
            _state.value = LinuxUserspaceState.Installing(LinuxInstallStep.VerifyingHealth)
            val health = distribution.healthProbe()
            _lastHealth.value = health
            check(health.healthy) { "Ubuntu installed but failed its health check: ${health.describe()}" }

            writeInstalledState()
            val warnings = report.warnings.toMutableList()
            if (backupFile.isFile) {
                val restored = runCatching { workspace.restoreFrom(backupFile) }.isSuccess
                if (restored) {
                    backupFile.delete()
                } else {
                    // Kept, not deleted: the next install (or Repair) tries again.
                    warnings += "the saved workspace could not be restored; the backup was kept"
                }
            }
            _state.value = LinuxUserspaceState.Stopped
            SetupReport(warnings)
        } catch (t: Throwable) {
            _state.value = failureState(t)
            throw t
        }
    }

    /**
     * Marks the userspace active after verifying it still works. Starting is not ceremonial: the
     * health probe is what distinguishes "Stopped and fine" from "Stopped since the rootfs got
     * corrupted", and Running is the state the foreground service is held in.
     */
    suspend fun start() = transition.withLock {
        val current = _state.value
        check(current is LinuxUserspaceState.Stopped || current is LinuxUserspaceState.NeedsRepair) {
            "Start is only possible from Stopped or Needs Repair, not $current"
        }
        _state.value = LinuxUserspaceState.Starting
        val health = distribution.healthProbe()
        _lastHealth.value = health
        _state.value =
            if (health.healthy) {
                LinuxUserspaceState.Running(clock())
            } else {
                LinuxUserspaceState.NeedsRepair(health.describe())
            }
    }

    /**
     * The explicit stop: every live session is closed (this is the one path where closing a
     * terminal *is* logout — because the user asked for it), and the userspace returns to Stopped.
     */
    suspend fun stop() = transition.withLock {
        val current = _state.value
        check(
            current is LinuxUserspaceState.Stopped ||
                current is LinuxUserspaceState.Starting ||
                current is LinuxUserspaceState.Running ||
                current is LinuxUserspaceState.NeedsRepair,
        ) {
            "Stop is not possible from $current"
        }
        _state.value = LinuxUserspaceState.Stopping
        processes.closeAll()
        _state.value = LinuxUserspaceState.Stopped
    }

    /** Stop, then Start again — the settings screen's Restart, one operation. */
    suspend fun restart() {
        stop()
        start()
    }

    /**
     * Re-runs whatever is broken: a missing rootfs is re-downloaded and extracted, the setup
     * pipeline runs again over whatever is there, and the health check has the final word.
     */
    suspend fun repair(): SetupReport = transition.withLock {
        val current = _state.value
        check(current is LinuxUserspaceState.NeedsRepair || current is LinuxUserspaceState.Stopped) {
            "Repair is only possible from Stopped or Needs Repair, not $current"
        }
        try {
            if (!installer.isExtracted()) {
                installer.install { progress ->
                    _state.value = LinuxUserspaceState.Installing(progress.toInstallStep())
                }
            }
            val report = distribution.setup { step ->
                _state.value = LinuxUserspaceState.Installing(LinuxInstallStep.SettingUp(step))
            }
            _state.value = LinuxUserspaceState.Installing(LinuxInstallStep.VerifyingHealth)
            val health = distribution.healthProbe()
            _lastHealth.value = health
            check(health.healthy) { "Ubuntu was repaired but still fails its health check: ${health.describe()}" }
            writeInstalledState()
            _state.value = LinuxUserspaceState.Stopped
            report
        } catch (t: Throwable) {
            _state.value = failureState(t)
            throw t
        }
    }

    /**
     * Removes the userspace entirely. With [keepWorkspace] the workspace is snapshotted to
     * [backupFile] first — and a snapshot failure aborts the uninstall, because losing the user's
     * projects to an IO hiccup is exactly the failure the keep option exists to prevent. Without
     * it, the workspace and any earlier backup are deleted too.
     */
    suspend fun uninstall(keepWorkspace: Boolean) = transition.withLock {
        val current = _state.value
        check(current !is LinuxUserspaceState.Installing && current !is LinuxUserspaceState.Stopping) {
            "Uninstall is not possible while $current"
        }
        processes.closeAll()
        if (keepWorkspace && workspace.exists() && workspace.fileCount() > 0) {
            workspace.snapshotTo(backupFile)
        }
        if (!keepWorkspace) {
            backupFile.delete()
        }
        installer.deleteRootfs()
        runtime.tmpDir.deleteRecursively()
        stateFile().delete()
        _lastHealth.value = null
        _state.value = LinuxUserspaceState.NotInstalled
    }

    /**
     * Runs the health probe on demand and records it. A failing probe on a Stopped userspace moves
     * the state to NeedsRepair (the card disappears — it shows only when healthy); a failing probe
     * on a Running one does *not* stop anything, because live sessions may still be fine and Stop
     * is the user's call.
     */
    suspend fun refreshHealth(): HealthReport {
        val health = distribution.healthProbe()
        _lastHealth.value = health
        if (!health.healthy && _state.value is LinuxUserspaceState.Stopped) {
            _state.value = LinuxUserspaceState.NeedsRepair(health.describe())
        }
        return health
    }

    // ------------------------------------------------------------------ internals

    private fun requireIdleForInstall() {
        val current = _state.value
        check(
            current is LinuxUserspaceState.NotInstalled || current is LinuxUserspaceState.NeedsRepair,
        ) {
            "an install is only possible from Not Installed or Needs Repair, not $current"
        }
    }

    /**
     * Where a failed install/repair lands: a rootfs that extracted is a repairable install (setup
     * can resume from it), anything less is a clean Not Installed.
     */
    private fun failureState(t: Throwable): LinuxUserspaceState =
        if (installer.isExtracted()) {
            LinuxUserspaceState.NeedsRepair(t.message ?: t.javaClass.simpleName)
        } else {
            LinuxUserspaceState.NotInstalled
        }

    private fun stateFile(): File = File(rootDir, "state.properties")

    /**
     * The constructor's state: persisted facts checked against the filesystem, so a crash, a
     * cleared directory or a stale flag can never present as a healthy install.
     */
    private fun initialState(): LinuxUserspaceState {
        val props = readStateFile() ?: return LinuxUserspaceState.NotInstalled
        if (props.getProperty("installed") != "true" || props.getProperty("distroId") != distro.id) {
            return LinuxUserspaceState.NotInstalled
        }
        return if (installer.isExtracted() && distribution.isConfigured()) {
            LinuxUserspaceState.Stopped
        } else {
            LinuxUserspaceState.NeedsRepair("the installed files are incomplete")
        }
    }

    private fun readStateFile(): Properties? {
        val file = stateFile()
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { input ->
                Properties().apply { load(input) }
            }
        }.getOrNull()
    }

    private fun writeInstalledState() {
        rootDir.mkdirs()
        val props = Properties()
        props.setProperty("installed", "true")
        props.setProperty("distroId", distro.id)
        props.setProperty("installedAtMs", clock().toString())
        stateFile().outputStream().use { output -> props.store(output, "Eclipse SSH Linux userspace") }
    }

    private fun RootfsInstaller.Progress.toInstallStep(): LinuxInstallStep =
        when (this) {
            is RootfsInstaller.Progress.Downloading -> LinuxInstallStep.Downloading(received, total)
            is RootfsInstaller.Progress.Verifying -> LinuxInstallStep.Verifying
            is RootfsInstaller.Progress.Extracting -> LinuxInstallStep.Extracting(entries)
        }
}

/**
 * The userspace's lifecycle states. Every state is observable by the UI; the card shows only for
 * Stopped/Starting/Running with a healthy probe, and the settings screen renders all of them.
 */
sealed interface LinuxUserspaceState {
    /** Nothing installed; the settings screen offers Install (or shows a pending backup hint). */
    data object NotInstalled : LinuxUserspaceState

    /** An install or repair is in progress; [step] is the current phase for the progress display. */
    data class Installing(val step: LinuxInstallStep) : LinuxUserspaceState

    /** Installed and verified but not held open; Start opens it. */
    data object Stopped : LinuxUserspaceState

    /** Verifying before declaring the runtime active. */
    data object Starting : LinuxUserspaceState

    /** Held open; sessions may exist, and closing them does not end this state. */
    data class Running(val sinceMs: Long) : LinuxUserspaceState

    /** Tearing down every session before returning to Stopped. */
    data object Stopping : LinuxUserspaceState

    /** Installed but not working; [detail] names the failure. Repair is the way out. */
    data class NeedsRepair(val detail: String) : LinuxUserspaceState
}

/** The phases of [LinuxUserspaceState.Installing], mapped from the installer's and setup's own progress. */
sealed interface LinuxInstallStep {
    data class Downloading(val received: Long, val total: Long) : LinuxInstallStep

    data object Verifying : LinuxInstallStep

    data class Extracting(val entries: Int) : LinuxInstallStep

    data class SettingUp(val step: SetupStep) : LinuxInstallStep

    data object VerifyingHealth : LinuxInstallStep
}
