package dev.eclipse.ssh.linux

import java.io.File
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * @param backupFile where the keep-workspace uninstall parks its snapshot, when a caller (a test)
 *   needs its own; by default the storage manager's path, which sits deliberately outside
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
    backupFile: File? = null,
    private val storage: RuntimeStorageManager = RuntimeStorageManager(rootDir),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val transition = Mutex()

    /**
     * The effective backup location: the storage manager's single definition, overridable for
     * tests that isolate each harness's backup from its neighbours in a shared temp directory.
     */
    private val backupFile: File = backupFile ?: storage.workspaceBackupFile

    private val _state = MutableStateFlow<LinuxUserspaceState>(initialState())
    val state: StateFlow<LinuxUserspaceState> = _state

    /** The most recent health probe, when one has run; the settings screen's evidence line. */
    private val _lastHealth = MutableStateFlow<HealthReport?>(null)
    val lastHealth: StateFlow<HealthReport?> = _lastHealth

    val sessionCount: StateFlow<Int> get() = processes.sessionCount

    /**
     * Total bytes under the userspace root — rootfs, tmp and the workspace together.
     *
     * walkTreeNoFollow, not walkTopDown: the rootfs is merged-/usr, so its own `bin -> usr/bin`,
     * `lib -> usr/lib` and `sbin -> usr/sbin` links made every file beneath them count twice, and
     * the settings screen's storage line read roughly double what the userspace actually occupies.
     * A link is not content, so it counts nothing here — the files it names are already counted,
     * once apiece.
     */
    fun storageUsedBytes(): Long =
        if (rootDir.isDirectory) {
            walkTreeNoFollow(rootDir).filter { it.isRegularFileNoFollow() }.sumOf { it.length() }
        } else {
            0L
        }

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
        // Storage before anything: every proot spawn this install leads to needs PROOT_TMP_DIR to
        // exist and be writable, and failing that here — by name — beats failing it five minutes
        // in as proot's "Permission denied".
        storage.requireReady()
        requireIdleForInstall()
        // One install at a time, process-wide: the lock is what makes a triple-tapped Install
        // button or a service-retry racing the UI's retry run exactly one install.
        storage.acquireInstallLock(distro.id, "install")
        try {
            writeInstallingMarker("install")
            // A repair-shaped install (from NeedsRepair) re-extracts, and re-extraction deletes
            // the old rootfs — with the workspace inside it. The snapshot parks it where the
            // restore at the end of this install picks it back up; a snapshot failure aborts,
            // because proceeding would delete the user's projects with nothing in their place.
            if (_state.value is LinuxUserspaceState.NeedsRepair && !installer.isExtracted() &&
                workspace.exists() && workspace.fileCount() > 0
            ) {
                workspace.snapshotTo(backupFile)
            }
            try {
                // Whether this run has a rootfs half is decided before it can be consumed by the
                // half itself: a repair that finds an extracted rootfs skips the download, and the
                // percentage the user watches must start at 0 for the work that is actually left.
                val plan = installPlan()
                var extractionWarnings: List<String> = emptyList()
                if (!installer.isExtracted()) {
                    storage.updateInstallLockPhase("download")
                    installer.install(
                        onProgress = { progress ->
                            _state.value = LinuxUserspaceState.Installing(progress.toInstallStep(), plan)
                        },
                        onExtractionWarnings = { extractionWarnings = it },
                    )
                }
                // The belt under the installer's own promise: an extraction that produced nothing
                // must fail here — an install failure the UI can name — rather than deep inside
                // setup, where it surfaces as a missing-file error that reads like corruption.
                check(installer.isExtracted()) { "the extracted rootfs is incomplete - there is nothing to set up" }
                storage.updateInstallLockPhase("setup")
                val report = setUpAndVerify(plan, "Ubuntu installed but failed its health check")

                writeInstalledState()
                val warnings = (extractionWarnings + report.warnings).toMutableList()
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
                onFailedInstallRun()
                _state.value = failureState(t)
                throw t
            }
        } finally {
            storage.releaseInstallLock()
        }
    }

    /**
     * Marks the userspace active after verifying it still works. Starting is not ceremonial: the
     * health probe is what distinguishes "Stopped and fine" from "Stopped since the rootfs got
     * corrupted", and Running is the state the foreground service is held in.
     */
    suspend fun start() = transition.withLock {
        // Same storage-before-proot rule as install(): the health probe spawns proot, and a tmp
        // directory that cannot be written to is the failure this names early.
        storage.requireReady()
        val current = _state.value
        check(current is LinuxUserspaceState.Stopped || current is LinuxUserspaceState.NeedsRepair) {
            "Start is only possible from Stopped or Needs Repair, not $current"
        }
        _state.value = LinuxUserspaceState.Starting
        // Before the probe, and on every start rather than only at install time: the Android group
        // IDs a session shows are a property of the running app, so an install made before the
        // rootfs named them is corrected here — the first terminal it opens after this — instead of
        // only by a reinstall. Recorded and not fatal: the userspace is fine either way, what
        // changes is whether its `groups` output prints names or numbers, and the probe below is
        // the real verdict on it.
        runCatching { distribution.nameSupplementaryGroups() }.onFailure { failure ->
            distribution.diagnostics.record(
                UserspaceDiagnosticCategory.PROOT,
                "supplementary group names",
                detail = failure.message ?: failure.javaClass.simpleName,
            )
        }
        val health = try {
            distribution.healthProbe()
        } catch (t: Throwable) {
            // A probe that cannot even run — proot failed to spawn — is a repair state, not a
            // wedged Starting: the settings screen must be able to offer Repair.
            _state.value = LinuxUserspaceState.NeedsRepair(t.message ?: t.javaClass.simpleName)
            throw t
        }
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
        storage.requireReady()
        val current = _state.value
        check(current is LinuxUserspaceState.NeedsRepair || current is LinuxUserspaceState.Stopped) {
            "Repair is only possible from Stopped or Needs Repair, not $current"
        }
        storage.acquireInstallLock(distro.id, "repair")
        try {
            writeInstallingMarker("repair")
            // Same rule as install(): a re-extraction deletes the old rootfs — and the workspace
            // inside it — so the workspace is parked where the restore below picks it back up.
            if (!installer.isExtracted() && workspace.exists() && workspace.fileCount() > 0) {
                workspace.snapshotTo(backupFile)
            }
            try {
                val plan = installPlan()
                var extractionWarnings: List<String> = emptyList()
                if (!installer.isExtracted()) {
                    storage.updateInstallLockPhase("download")
                    installer.install(
                        onProgress = { progress ->
                            _state.value = LinuxUserspaceState.Installing(progress.toInstallStep(), plan)
                        },
                        onExtractionWarnings = { extractionWarnings = it },
                    )
                }
                // Same belt as install(): a repair whose re-extraction still left nothing must fail
                // as a repair, not as setup's missing-file error.
                check(installer.isExtracted()) { "the extracted rootfs is incomplete - there is nothing to set up" }
                storage.updateInstallLockPhase("setup")
                val report = setUpAndVerify(plan, "Ubuntu was repaired but still fails its health check")
                writeInstalledState()
                val warnings = (extractionWarnings + report.warnings).toMutableList()
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
                onFailedInstallRun()
                _state.value = failureState(t)
                throw t
            }
        } finally {
            storage.releaseInstallLock()
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
        withContext(Dispatchers.IO) {
            processes.closeAll()
            if (keepWorkspace && workspace.exists() && workspace.fileCount() > 0) {
                workspace.snapshotTo(backupFile)
            }
            if (!keepWorkspace) {
                backupFile.delete()
            }
            installer.deleteRootfs()
            // proot's temp dir holds its glue symlinks; NOFOLLOW so the sweep cannot chase one out.
            deleteTreeNoFollow(runtime.tmpDir)
            stateFile().delete()
            // The distribution's mirror memo lives beside the rootfs, not inside it, so it
            // survives deleteRootfs — but it describes an install that no longer exists, and a
            // leftover here is why a "removed entirely" root still shows bytes in use.
            distribution.clearLastGoodMirror()
            // Belt under the not-while-Installing check: a lock left by any path is gone with
            // everything else, so the next install starts from a clean slate.
            storage.releaseInstallLock()
            _lastHealth.value = null
            _state.value = LinuxUserspaceState.NotInstalled
        }
    }

    /**
     * Runs the health probe on demand and records it. A failing probe on a Stopped userspace moves
     * the state to NeedsRepair (the card disappears — it shows only when healthy); a failing probe
     * on a Running one does *not* stop anything, because live sessions may still be fine and Stop
     * is the user's call.
     */
    suspend fun refreshHealth(): HealthReport = transition.withLock {
        // Under the same mutex as every transition: a refresh racing a Start would otherwise
        // record a verdict about a userspace whose state changed underneath it.
        val health = distribution.healthProbe()
        _lastHealth.value = health
        if (!health.healthy && _state.value is LinuxUserspaceState.Stopped) {
            _state.value = LinuxUserspaceState.NeedsRepair(health.describe())
        }
        health
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

    private fun stateFile(): File = storage.stateFile

    /**
     * The constructor's state: persisted facts checked against the filesystem, so a crash, a
     * cleared directory or a stale flag can never present as a healthy install.
     */
    private fun initialState(): LinuxUserspaceState {
        val props = readStateFile() ?: return LinuxUserspaceState.NotInstalled
        if (props.getProperty("installing") == "true") {
            // The last run never reached its finally: whatever is on disk — a half-extracted
            // staging tree, a rootfs that setup never finished with — is not a system to present
            // as anything but repairable.
            return LinuxUserspaceState.NeedsRepair("a previous install was interrupted")
        }
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

    /**
     * Records that an install/repair run is in flight — the fact [initialState] reads back after
     * a crash to refuse presenting the leftovers as anything but NeedsRepair. Merged into
     * whatever state is already on disk, so a repair of an installed system does not erase the
     * installed fact it is trying to restore.
     */
    private fun writeInstallingMarker(phase: String) {
        rootDir.mkdirs()
        val props = readStateFile() ?: Properties()
        props.setProperty("installing", "true")
        props.setProperty("distroId", distro.id)
        props.setProperty("phase", phase)
        props.setProperty("startedAtMs", clock().toString())
        stateFile().outputStream().use { output -> props.store(output, "Eclipse SSH Linux userspace") }
    }

    /**
     * What a failed install/repair run leaves behind. A rootfs that extracted stays — the next
     * attempt's extraction guard resumes from it. A run that produced nothing reclaims its
     * staging tree while keeping the verified tarball (the retry resumes instead of
     * re-downloading) and drops the state file. Both clear the installing marker, so the next
     * construction reads the failure as it was, not as an interruption.
     */
    private fun onFailedInstallRun() {
        if (installer.isExtracted()) {
            clearInstallingMarker()
        } else {
            installer.reclaimFailedExtraction()
            stateFile().delete()
        }
    }

    /** Removes the in-flight marker, preserving any installed fact recorded before the run. */
    private fun clearInstallingMarker() {
        val props = readStateFile() ?: return
        props.remove("installing")
        props.remove("phase")
        props.remove("startedAtMs")
        if (props.getProperty("installed") == "true") {
            stateFile().outputStream().use { output -> props.store(output, "Eclipse SSH Linux userspace") }
        } else {
            stateFile().delete()
        }
    }

    private fun RootfsInstaller.Progress.toInstallStep(): LinuxInstallStep =
        when (this) {
            is RootfsInstaller.Progress.Downloading -> LinuxInstallStep.Downloading(received, total)
            is RootfsInstaller.Progress.Verifying -> LinuxInstallStep.Verifying
            is RootfsInstaller.Progress.Extracting -> LinuxInstallStep.Extracting(entries, fraction = fraction)
        }

    /**
     * Which halves this run will traverse, decided before either half can consume the answer:
     * a rootfs already on disk and complete means the download, the verification and the extraction
     * are not work this run has to do, and [LinuxInstallProgress] renormalizes its ladder so the
     * percentage starts where the work does.
     */
    private fun installPlan(): InstallPlan =
        if (installer.isExtracted()) InstallPlan.SETUP_ONLY else InstallPlan.FULL

    /**
     * The second half of both [install] and [repair]: the setup pipeline for real, then the health
     * check, emitting the install state — and its percentage — as each step lands.
     *
     * Shared rather than duplicated, because the progress a user watches must not depend on which
     * verb brought them here; the two callers disagree about one thing, the sentence a failed health
     * check is reported with, and that is the parameter.
     *
     * @param healthFailure the start of the message a failing health check is reported with; the
     *   probe's own [HealthReport.describe] completes it
     * @throws IllegalStateException when the health check fails — the install is not done until the
     *   environment has been proven usable, and a report that says otherwise would be the exact
     *   "it installed" / "it works" conflation the pipeline exists to refuse
     */
    private suspend fun setUpAndVerify(plan: InstallPlan, healthFailure: String): SetupReport {
        // The step the progress line belongs to: onStep and onProgress arrive as separate callbacks,
        // and the emitted state must carry both. The within-step fraction is the *highest* apt has
        // reported for the current step, never the latest: apt redraws its bar many times a second
        // for its own reasons, and a bar that walked backwards would read as a step being undone.
        var setupStep = SetupStep.REGISTER_USER
        var setupDetail: String? = null
        var setupWithin: Float? = null
        val report = distribution.setup(
            onStep = { step ->
                setupStep = step
                // Cleared, not carried: the previous step's line and its 90% both describe work
                // that is over, and leaving either standing would attribute it to this step.
                setupDetail = null
                setupWithin = null
                _state.value = LinuxUserspaceState.Installing(LinuxInstallStep.SettingUp(step), plan)
            },
            onProgress = { line ->
                // Conflated by the StateFlow: a burst of apt lines collapses to the newest, which is
                // exactly the line a watcher wants to see.
                setupWithin = maxOf(setupWithin ?: 0f, aptProgressFraction(line) ?: 0f).takeIf { it > 0f }
                setupDetail = progressDetail(line) ?: setupDetail
                _state.value = LinuxUserspaceState.Installing(
                    LinuxInstallStep.SettingUp(setupStep, setupDetail, setupWithin),
                    plan,
                )
            },
        )
        storage.updateInstallLockPhase("health")
        _state.value = LinuxUserspaceState.Installing(LinuxInstallStep.VerifyingHealth, plan)
        val health = distribution.healthProbe()
        _lastHealth.value = health
        check(health.healthy) { "$healthFailure: ${health.describe()}" }
        return report
    }
}

/**
 * The userspace's lifecycle states. Every state is observable by the UI; the card shows only for
 * Stopped/Starting/Running with a healthy probe, and the settings screen renders all of them.
 */
sealed interface LinuxUserspaceState {
    /** Nothing installed; the settings screen offers Install (or shows a pending backup hint). */
    data object NotInstalled : LinuxUserspaceState

    /**
     * An install or repair is in progress; [step] is the current phase for the progress display,
     * and [plan] says which halves this run traverses so its percentage starts where its work
     * does. Both default to the whole pipeline, which is what a caller that has nothing to say
     * about either means.
     */
    data class Installing(
        val step: LinuxInstallStep,
        val plan: InstallPlan = InstallPlan.FULL,
    ) : LinuxUserspaceState

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

    /** [fraction] of the tarball read, 0 to 1, when the file would report its length. */
    data class Extracting(val entries: Int, val fraction: Float? = null) : LinuxInstallStep

    /**
     * A setup step is running. [detail], when present, is the newest output line of the command
     * behind the step — a slow-but-alive `apt-get update` shows "Get: 47 …" moving instead of a
     * label that could be wedged for all the user can tell. [fraction] is how far the step says it
     * has got, 0 to 1, for the steps that can measure themselves.
     */
    data class SettingUp(
        val step: SetupStep,
        val detail: String? = null,
        val fraction: Float? = null,
    ) : LinuxInstallStep

    data object VerifyingHealth : LinuxInstallStep
}

/**
 * The whole install's percentage for a state, 0 to 100 — what the Ubuntu window, the host list and
 * the foreground notification all show.
 *
 * An extension on the state rather than a second call to [LinuxInstallProgress]: the step and the
 * plan that qualify each other travel together, and every renderer that took the step alone would
 * have to be handed the plan as well and could quietly be given the wrong one.
 */
val LinuxUserspaceState.Installing.percent: Int
    get() = LinuxInstallProgress.percent(step, plan)
