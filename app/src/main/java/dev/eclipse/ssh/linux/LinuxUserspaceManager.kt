package dev.eclipse.ssh.linux

import dev.eclipse.ssh.ssh.SessionEnd
import java.io.File
import java.io.IOException
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

    /**
     * The repair ladder's window into the install log — the ring the user reads, shared with every
     * other phase of the userspace (see `LinuxUserspaceGraphProvider`, which hands one instance to
     * the installer and the distribution). A repair that escalates through four rungs is exactly the
     * run whose log has to say which rung did the work, and it says it here.
     */
    private val diagnostics: UserspaceDiagnostics get() = distribution.diagnostics

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
     * Re-runs whatever is broken, in the order of what each answer costs the user: the local state
     * nothing else can restore, the package database, the setup pipeline again, then the base files
     * the archive says are missing, then the base system rewritten wholesale, and only then a full
     * reinstall from the pin.
     *
     * Until this ladder existed, Repair had exactly one rung — re-extract if there is no rootfs,
     * otherwise set up again — and the failure it was reported against ("Installing base packages
     * failed (exit 100): dpkg: error: 1 expected program not found in PATH or not executable") was
     * one it could not climb: the rootfs was present, complete enough to pass an existence check and
     * missing the programs dpkg runs, so every rung of the apt pipeline failed the same way and the
     * app answered the only failure it exists to clear. A rootfs that is *there* and wrong is the
     * case the deeper rungs are for, and the deepest of them cannot fail for want of a file: it
     * writes the pinned archive's own base system over whatever is on disk.
     *
     * The boundary the ladder refuses to cross is a failure a rebuild cannot fix — no network, no
     * DNS, a disk with nothing left to free, a mirror that refuses or serves an unsigned index, a
     * step that timed out, an archive that no longer matches its pin, a process the system killed, an
     * app runtime that is not on the device. Those stop it with a typed message ([aRebuildCouldFix])
     * rather than rewriting a working base system: on a metered connection, a rebuild is the one
     * repair that makes the state worse, and it would not have worked anyway. The disk is the
     * exception the ladder acts on before it starts: the space a userspace's own package caches hold
     * is regenerable, so it is given up rather than reported (see the space step in [repairLadder])
     * and a full disk stops the ladder only when there is nothing left that can be freed without
     * costing the user something.
     *
     * What no rung touches is the user's own data — with two narrow, deliberate exceptions, both of
     * them state the package manager itself owns and neither of them anything the user wrote. `/home`
     * (the workspace inside it) is preserved everywhere and the one rung that replaces the rootfs —
     * the reinstall — parks the workspace first and puts it back when it is done. `/var/lib/dpkg` and
     * the state trees beside it are preserved too, but one *named* file inside `/var/lib/dpkg` is the
     * exception that makes the rest of the ladder reachable at all: a package database that cannot be
     * read is a state no rung can work in, so rung D restores that one file and parks what was there
     * as `status.broken` (see [RootfsInstaller.restorePackageDatabase], which is also where the price
     * of the archive's copy is stated). Apt's caches and index lists are regenerable by definition and
     * rung L clears them; a workspace the user deleted is recreated empty, and told about. A repair
     * therefore never costs the user a package they installed or a file they wrote; the price of a
     * success it cannot achieve more cheaply is the base system's own bytes, re-fetched from the same
     * SHA256-verified pin the install used.
     *
     * @throws UserspaceFailure when the failure is one a rebuild cannot fix, unchanged, so the
     *   controller renders its own sentence rather than this layer's prose
     * @throws IOException when every rung ran and none of them worked, naming the last failure
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
            try {
                val warnings = mutableListOf<String>()
                val report = repairLadder(warnings)
                writeInstalledState()
                warnings += report.warnings
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
     * The rungs, cheapest first, each collected into [warnings] as it goes. Returns the report of
     * the first rung that ended with a healthy userspace, and throws when the ladder runs out.
     *
     * @param warnings the caller's list: warnings from a rung that failed are kept, because the
     *   extraction that skipped a hardlink is still a fact about the rootfs even when the rung built
     *   on top of it was superseded by a deeper one
     */
    private suspend fun repairLadder(warnings: MutableList<String>): SetupReport {
        var lastFailure: Throwable? = null
        val attempted = mutableListOf<String>()

        /**
         * One rung. A rung that succeeds returns its report; one that has nothing to do returns
         * null, as does one that fails for a reason a deeper rung could still fix — the difference
         * the user sees is only that the ladder keeps going. A failure no rebuild could fix is
         * thrown straight out, which is what stops the ladder before it does harm.
         */
        suspend fun rung(label: String, action: suspend () -> SetupReport?): SetupReport? {
            attempted += label
            return try {
                action()
            } catch (t: Throwable) {
                lastFailure = t
                if (!aRebuildCouldFix(t)) throw t
                diagnostics.record(
                    UserspaceDiagnosticCategory.ROOTFS,
                    "repair step left the userspace broken: $label",
                    detail = t.message ?: t.javaClass.simpleName,
                )
                null
            }
        }

        // Rung 0 — a crashed extraction: a whole rootfs with a staging tree left beside it.
        // isExtracted() is false only because of the leftover, and every rung below checks it, so
        // the leftover is cleared before the ladder starts rather than mistaken for a missing
        // install. Nothing is deleted but the staging tree a failed run already abandoned.
        if (!installer.isExtracted() && installer.rootfsInPlace()) {
            installer.reclaimFailedExtraction()
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "reclaimed an interrupted extraction",
                detail = "the rootfs was in place; the staging tree beside it was not",
            )
        }

        // Rung S — the disk, before any rung that could need it. The space the deeper rungs work in
        // is the one thing about the environment a repair *can* change: the userspace's own apt
        // caches run to hundreds of megabytes after a package or two, and every byte of them is
        // rebuilt by the next `apt-get update`. So they are given up whenever the space is short —
        // and whenever the platform will not say how much there is, because a repair that cannot
        // prove there is room makes room rather than finding out partway through an extraction.
        // Nothing here can fail the ladder: it takes only what is regenerable, and takes it
        // best-effort (see [RootfsInstaller.reclaimSpace]).
        val free = storage.freeBytes()
        if (free <= 0L || free < installer.requiredFreeBytes) {
            val reclaimed = installer.reclaimSpace()
            if (reclaimed > 0L) {
                warnings += "freed ${reclaimed / (1024 * 1024)} MB of package caches and temporary " +
                    "files to make room for the repair"
            }
        }

        // Rung A — no rootfs at all. There is nothing to repair, only something to build, and the
        // ladder's later rungs all read the archive: going straight to the install is both the
        // cheapest answer here and the only one that can work.
        if (!installer.rootfsInPlace()) {
            return rung("reinstall", { reinstall(warnings) })
                ?: throw repairFailed(attempted, lastFailure)
        }

        // Rungs L and D — the two repairs that are entirely local. They sit above the setup rung
        // because the failures they answer make every rung below fail identically and instantly: a
        // package lock nothing holds makes every dpkg and apt call refuse in a second ("Could not get
        // lock ... is another process using it?"), and an unreadable package database makes them
        // refuse before they read anything else — so the old ladder spent all four of its rungs on
        // the same refusal, and the reinstall it ended with did not touch either one. Neither rung
        // downloads anything it does not have to, which matters here more than anywhere else on the
        // ladder: a repair that reads the network before it has looked at the rootfs spends a metered
        // connection on a run that cannot get past its own first command.
        return rung("restore local state", { restoreLocalState(warnings) })
            ?: rung("restore the package database", { restorePackageDatabase(warnings) })
            ?: rung("setup", { setUpAgain(warnings) })
            ?: rung("restore missing files", { restoreDamaged(warnings) })
            ?: rung("rewrite the base system", { overlayBase(warnings) })
            ?: rung("reinstall", { reinstall(warnings) })
            ?: throw repairFailed(attempted, lastFailure)
    }

    /**
     * Rung L — the repairs that need nothing but the rootfs: the package locks an interrupted run
     * left behind, the apt-owned state the next update rebuilds, and the guest directories that
     * nothing else puts back.
     *
     * Each part answers a failure the ladder could otherwise not see. The locks are a *marker* a
     * killed dpkg leaves on disk while the kernel has already dropped the lock itself, so every
     * package step of every rung refuses in a second and the user is told the same thing four times
     * (see [RootfsInstaller.clearStalePackageLocks], which proves nothing holds a lock before
     * removing it rather than deleting a file that happens to be named `lock`). The apt state is
     * what `Hash Sum mismatch` and an unparseable index live in, and it is *only* ever cleared by
     * rung S — which runs on a disk gate, so on a device with space to spare a corrupt index was
     * unfixable by anything short of the rebuild (see [RootfsInstaller.clearRegenerableState]). The
     * guest's `tmp` and `run` are preserved members that no rung writes and no archive overlay
     * replaces, and the setup rung's own attempt to create them — `prepareWorkspace`, which runs on
     * every rung below this one — throws its result away, so a `tmp` that is a *file* and a
     * workspace that is not a real directory both survived every repair there was, silently.
     *
     * Unconditional rather than gated on the failure that brought the user here, which is a
     * deliberate trade and not an oversight: the ladder has no structured record of that failure
     * (the state carries a sentence, and a sentence is not something to branch on), and every part
     * here is cheap, local, and costs the user nothing — apt's caches are its own bytes, locks that
     * something holds are left alone, and the directories are recreated only when they are missing
     * or wrong. The one real cost is a re-download of the package index: a few megabytes against
     * the base system's thirty, on a repair the user asked for, and only when the index was not
     * already cleared by rung S in this same pass.
     *
     * Returns null when it found nothing to do, which is the honest answer for a userspace whose
     * damage is elsewhere — the setup rung below is the one that says so, and running it twice would
     * only double the network's share of a repair.
     */
    private suspend fun restoreLocalState(warnings: MutableList<String>): SetupReport? {
        var changed = false

        val sweep = installer.clearStalePackageLocks()
        if (sweep.removed.isNotEmpty()) {
            changed = true
            warnings += "cleared ${sweep.removed.size} package-manager lock(s) an interrupted run " +
                "left behind: ${sweep.removed.joinToString(", ")}"
        }
        if (sweep.held.isNotEmpty()) {
            // Not this rung's failure and not hidden either: something really is holding these, and
            // the package steps below will refuse in dpkg's own words rather than this one's.
            diagnostics.record(
                UserspaceDiagnosticCategory.APT,
                "package locks are still held; the package steps below will refuse",
                detail = sweep.held.joinToString(", "),
            )
        }

        val freed = installer.clearRegenerableState()
        if (freed > 0L) {
            changed = true
            warnings += "cleared ${freed / (1024 * 1024)} MB of apt's own caches and package lists, " +
                "which its next update rebuilds"
        }

        val temp = installer.ensureGuestTemp()
        if (temp.isNotEmpty()) {
            changed = true
            warnings += "recreated the guest's temporary directories: " +
                temp.joinToString(", ") { "/$it" }
        }

        if (workspace.ensureExists()) {
            changed = true
            // Two sentences, because these are two different pieces of news and only one of them is
            // about the user's files. A workspace that is simply gone took their projects with it —
            // the snapshot that survives an uninstall is not involved, since nothing here deletes a
            // rootfs — while one that was a link or a file was never a workspace at all.
            if (workspace.exists()) {
                warnings += "the workspace in your home was missing or was not a real directory; an " +
                    "empty one was created in its place"
                diagnostics.record(
                    UserspaceDiagnosticCategory.ROOTFS,
                    "the workspace was missing from /home/ubuntu and was recreated empty",
                    detail = "projects that were in it are not recoverable from the rootfs",
                )
            } else {
                warnings += "the workspace in your home is missing and could not be created"
            }
        }

        if (!changed) return null
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "local state restored; setting the userspace up again",
            detail = "the rootfs itself was not written",
        )
        return setUpAgain(warnings)
    }

    /**
     * Rung D — the package database, the one file no other rung may touch and every rung needs.
     *
     * `var/lib/dpkg` is a preserved member, so nothing writes it: not the setup rung, not the
     * file-level restore, not the overlay — and not the reinstall either, because a rebuild replaces
     * the base system but the preserved trees are the user's. That is the deadlock this rung exists
     * for: every repair of anything else begins with `dpkg --configure -a`, which reads
     * `/var/lib/dpkg/status` first, so a database that is truncated or unparseable fails every rung
     * in the same second and the ladder ends by rebuilding a rootfs whose only broken part was one
     * file. [RootfsInstaller.restorePackageDatabase] is where the three sources and their prices are
     * described; what belongs here is what the rung does with the answer.
     *
     * Returns null when the database read as a database, which is the common case and means this
     * rung has no opinion about the failure being repaired.
     */
    private suspend fun restorePackageDatabase(warnings: MutableList<String>): SetupReport? {
        val source = installer.restorePackageDatabase(onProgress = ::emitArchiveProgress) ?: return null
        warnings += when (source) {
            // dpkg's own previous generation: the same database one write ago, so every package the
            // user installed is still recorded and nothing has to be reinstalled.
            PackageDatabaseSource.PREVIOUS ->
                "the package database could not be read and was restored from dpkg's own previous " +
                    "copy; the unreadable file was kept as /var/lib/dpkg/status.broken"
            // The archive's copy: honest about the one thing it costs, because the user is the only
            // one who can put it back.
            PackageDatabaseSource.ARCHIVE ->
                "the package database could not be read and was rebuilt from the Ubuntu archive, so " +
                    "packages installed on top of the base system are no longer tracked as " +
                    "installed; their files are still on disk and reinstalling them with apt puts " +
                    "the record back"
        }
        return setUpAgain(warnings)
    }

    /**
     * The rung that costs nothing but time: the setup pipeline again over a rootfs that is there.
     * Most repairs end here — a mirror that was down, a dpkg run that was interrupted, a package
     * half-configured — and it is the only rung the ladder used to have.
     */
    private suspend fun setUpAgain(warnings: MutableList<String>): SetupReport {
        storage.updateInstallLockPhase("setup")
        return setUpAndVerify(InstallPlan.SETUP_ONLY, HEALTH_FAILURE).also { warnings += it.warnings }
    }

    /**
     * The rung for a rootfs that is missing base files: the archive is compared with what is on
     * disk, the absent members are written back from it, and the setup pipeline runs over the
     * result.
     *
     * This is the rung the reported dpkg failure needs — `rm`, `tar` and `ldconfig` missing from the
     * rootfs are exactly members of the archive that are not on disk — and it is the cheapest way to
     * put a handful of binaries back, because it writes only those. Nothing else on disk is touched:
     * not the user's packages, not their files, and not a base file that happens to have been
     * upgraded rather than lost.
     *
     * Returns null when the scan found nothing missing, which is the honest answer for a rootfs
     * whose damage is not an absent file — the scan asks presence and kind, never bytes, so a
     * replaced library reads as whole and the rung has nothing to say about it. The overlay below
     * does not care what the scan thinks and rewrites the base system regardless.
     */
    private suspend fun restoreDamaged(warnings: MutableList<String>): SetupReport? {
        val integrity = installer.inspectAgainstPinnedArchive(onProgress = ::emitArchiveProgress)
        if (integrity.whole) return null
        val restored = installer.restoreFromPinnedTarball(integrity.damaged, onProgress = ::emitArchiveProgress)
        if (restored.isEmpty()) {
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "nothing could be restored from the archive",
                detail = integrity.describe(),
            )
            return null
        }
        warnings += "restored ${restored.size} missing file(s) from the Ubuntu archive: " +
            restored.take(4).joinToString(", ") +
            (if (restored.size > 4) " and ${restored.size - 4} more" else "")
        return setUpAgain(warnings)
    }

    /**
     * The rung for a base system that is present but wrong — a library replaced by something that
     * does not load, a binary that is there and not executable, damage no file-level restore can
     * name. Every member the archive carries is written over what the rootfs holds at that name,
     * with the preserved trees left alone (see [RootfsInstaller.isPreservedMember]), so packages the
     * user installed stay installed and tracked while the base system becomes the base system again.
     *
     * Returns null only when the archive named nothing to write, which no real archive does: a
     * non-null result of this rung means the rewrite happened, whether or not the setup after it
     * worked.
     */
    private suspend fun overlayBase(warnings: MutableList<String>): SetupReport? {
        val members = installer.overlayFromPinnedArchive(
            onProgress = ::emitArchiveProgress,
            onWarning = { warnings += it },
        )
        if (members == 0) return null
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "base system rewritten, then set up again",
            detail = "$members member(s)",
        )
        return setUpAgain(warnings)
    }

    /**
     * The last rung: the rootfs is thrown away and built again from the pinned archive, which is the
     * one repair that cannot fail for want of a file in the rootfs — whatever was wrong with the old
     * one, the new one is not it.
     *
     * It is last because it is the only rung that costs the user something: their installed packages
     * are gone with the base system they were installed into. Their *files* are not — the workspace
     * is parked before the rootfs is replaced and restored by [repair] when the run finishes, and a
     * snapshot that fails aborts the rung rather than proceeding without it.
     */
    private suspend fun reinstall(warnings: MutableList<String>): SetupReport {
        parkWorkspaceForRebuild()
        storage.updateInstallLockPhase("download")
        installer.install(
            onProgress = { progress ->
                _state.value = LinuxUserspaceState.Installing(progress.toInstallStep(), InstallPlan.FULL)
            },
            onExtractionWarnings = { warnings += it },
        )
        // The same belt as install(): an extraction that produced nothing must fail here, as a
        // repair the UI can name, rather than deep inside setup as a missing-file error.
        check(installer.isExtracted()) { "the extracted rootfs is incomplete - there is nothing to set up" }
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "rootfs rebuilt from the pinned archive",
            detail = "packages installed on top of the old base system are gone with it",
        )
        return setUpAgain(warnings)
    }

    /** Reports the archive scans to the install screen as if they were an extraction, which they are. */
    private fun emitArchiveProgress(progress: RootfsInstaller.Progress) {
        _state.value = LinuxUserspaceState.Installing(progress.toInstallStep(), InstallPlan.SETUP_ONLY)
    }

    /**
     * Parks the workspace where a rebuild's restore picks it back up. A snapshot failure propagates:
     * the rung that calls this is about to delete the rootfs the workspace lives in, and losing the
     * user's projects to an IO hiccup is the one outcome worth failing a repair over.
     */
    private suspend fun parkWorkspaceForRebuild() {
        if (!workspace.exists() || workspace.fileCount() <= 0) return
        workspace.snapshotTo(backupFile)
    }

    /**
     * Whether a rebuild could plausibly fix [failure] — the gate between "this rootfs is broken" and
     * "this rootfs cannot be repaired right now".
     *
     * The failures this refuses are the ones the *environment* owns: no network, no DNS, a full disk,
     * a mirror that refuses or serves an index whose signature does not verify, a step that ran out
     * of time, a clock that disagrees with the archive's timestamps, an archive that no longer matches
     * its pin, a process the system killed, an app runtime that is not there. A deeper rung cannot
     * touch any of them — they all read the same network, the same disk and the same device — and the
     * deepest one would rewrite a working base system to no purpose, which on a metered connection or
     * a nearly-full device leaves the user worse off than the failure did. Everything else, including
     * every failure whose evidence is inside the rootfs (a missing program, a broken package database,
     * proot's own exit 127, a lock an interrupted run left behind), passes: those are what the ladder
     * is for.
     *
     * The message prefixes are the same contracts the taxonomy reads ([RUNTIME_STORAGE_PREFIX],
     * [DISK_FULL_PREFIX]), checked along the cause chain as well as at the top, because a failure
     * that has been wrapped is still the failure.
     *
     * Internal rather than private so the gate can be tested as the table it is — one test per
     * failure type against its verdict — instead of only through a ladder that has to reach the type
     * by making something fail for real.
     */
    internal fun aRebuildCouldFix(failure: Throwable): Boolean {
        var seen: Throwable? = failure
        var depth = 0
        while (seen != null && depth++ < MAX_CAUSE_DEPTH) {
            if (seen is PinnedArchiveUnavailable) return false
            val message = seen.message ?: ""
            if (message.startsWith(RUNTIME_STORAGE_PREFIX) || message.startsWith(DISK_FULL_PREFIX)) return false
            seen = seen.cause
        }
        if (failure !is UserspaceFailure) return true
        return when (failure) {
            is UserspaceFailure.Offline,
            is UserspaceFailure.DnsUnresolved,
            is UserspaceFailure.MirrorUnreachable,
            is UserspaceFailure.RepositoryUnsigned,
            is UserspaceFailure.DiskFull,
            is UserspaceFailure.StepTimedOut,
            // The device's clock, which nothing inside the rootfs can move: apt's index reads as
            // expired (or as not valid yet) because the phone's date is wrong, and the deepest rung
            // would spend thirty megabytes and a base-system rewrite to reach the same sentence.
            is UserspaceFailure.ClockSkew,
            // The device, not the userspace: a kill by the system's low-memory killer or a crash is
            // an answer about the phone's memory or about a binary that segfaulted, and the deepest
            // rung is 30 MB of download and extraction that ends the same way. 137 in particular is
            // the one failure where a rebuild leaves the user worse off — the memory it needs is the
            // memory that was already gone.
            is UserspaceFailure.KilledBySignal,
            // The app's own runtime is what is missing (a wrong-ABI split, a half-updated app), so no
            // amount of writing the rootfs supplies it. See [ProotRuntime]'s loader check.
            is UserspaceFailure.NativeRuntimeMissing,
            // A device that cannot fork another pty: the rebuild forks them too.
            is UserspaceFailure.TooManyTerminals,
            -> false
            // ProotLaunchFailed and PackageDbBroken are inside the rootfs' side of the line:
            // exit 127 is a program that is not there, and a broken package database is what
            // `dpkg --configure -a` exists for. Both are what the deeper rungs repair. So are the
            // rungs' own new types — a held package lock (rung L clears it), a hash-mismatched
            // index (rung L clears the state it lives in), a database that could not be restored in
            // place (rung D's own last resort is the rebuild, and the archive's copy of the database
            // is no worse than the archive's copy of everything else), a dpkg subprocess that
            // failed, and a guest temp directory: the rebuild re-extracts the whole tree, and that
            // is what puts all five back.
            else -> true
        }
    }

    /**
     * What the user is told when every rung ran and the userspace is still broken. The last failure
     * is the one worth naming — the rungs above it were superseded by a deeper one, and the deepest
     * is the one that had the whole archive to work from.
     *
     * A typed failure stays typed: the controller renders its own sentence for those, and rewording
     * it here would fork one explanation into two.
     */
    private fun repairFailed(attempted: List<String>, failure: Throwable?): Throwable {
        failure?.let { UserspaceFailure.fromMessage(it.message ?: "", it) }?.let { return it }
        return IOException(
            "Ubuntu could not be repaired: ${failure?.message ?: "every repair step failed"}" +
                " (tried: ${attempted.joinToString(", ")})",
            failure,
        )
    }

    private companion object {
        /**
         * The sentence a rung that ends with an unhealthy userspace is reported with. The probe's own
         * [HealthReport.describe] completes it, and it names the repair rather than the rung, because
         * which rung ran is the log's business and the user's business is only that it did not work.
         */
        const val HEALTH_FAILURE = "Ubuntu was repaired but still fails its health check"

        /**
         * How far down a cause chain [aRebuildCouldFix] looks before giving up. Three is deeper than
         * this code wraps — a typed failure wraps the IOException it classified, and nothing wraps
         * that — and a bound is what keeps a self-referential cause from spinning the check.
         */
        const val MAX_CAUSE_DEPTH = 4
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

    /**
     * A local terminal session ended with the guest's own shell missing, which the manager has to be
     * told because nothing else would conclude it: a shell that exits is not a fault as far as the tab
     * is concerned ([SessionEnd.isFault]), so the ending lands as DISCONNECTED, the userspace stays
     * Running, and the host card goes on offering a terminal that cannot open while the Repair that
     * would fix it is never offered at all. That is the failure this closes: exit 127 from a pty the
     * app forked means proot ran and the program it was asked for was not in the rootfs, and no
     * ending an SSH session can produce means that about *this* userspace.
     *
     * Three conditions, and they are the same three the class's own reasoning about a Running
     * userspace implies (see [refreshHealth], which deliberately leaves a running environment alone):
     * the ending has to blame the userspace ([sessionEndBlamesUserspace]), no other local session may
     * still be live — a sibling terminal that is still typing is proof the environment works, whatever
     * this one exit says — and the health probe has to disagree too. Only when all three hold does the
     * state become NeedsRepair, and the probe is what keeps a user who typed a command that does not
     * exist, or `exit 127` by hand, from being told their environment is broken: it passes, and the
     * userspace stays exactly as it was.
     *
     * The probe runs here rather than being left to Repair's first rung, because by then the user has
     * pressed a button and the answer is already needed: a card that offers Repair for a healthy
     * userspace is its own kind of lie.
     */
    suspend fun noteSessionEnded(sessionKey: String, end: SessionEnd) = transition.withLock {
        if (!sessionEndBlamesUserspace(end)) return@withLock
        val current = _state.value
        if (current !is LinuxUserspaceState.Running && current !is LinuxUserspaceState.Starting) {
            return@withLock
        }
        val live = processes.liveCountExcept(sessionKey)
        if (live > 0) {
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "a terminal session ended with exit 127, but $live other session(s) are still open",
                detail = "the environment is in use, so the ending was not treated as its verdict",
            )
            return@withLock
        }
        val health = distribution.healthProbe()
        _lastHealth.value = health
        if (health.healthy) {
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "a terminal session ended with exit 127; the environment still passes its health check",
                detail = health.describe(),
            )
            return@withLock
        }
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "a terminal session ended with exit 127 and the health check now fails",
            detail = health.describe(),
        )
        _state.value = LinuxUserspaceState.NeedsRepair(health.describe())
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
 * Whether a session ending is evidence about the *userspace* rather than about the session.
 *
 * Exactly one ending is, and it is not the one an eye would pick: not a fault ([SessionEnd.isFault]),
 * which is a tab's distinction and puts a killed shell in the same bucket as a broken one, but exit
 * 127 with no signal — the status a proot child leaves when the program it was asked to run is not in
 * the rootfs. A missing `bash`, a missing `sh`, a startup program `dpkg` needs that is not there: that
 * is exactly the damage the repair ladder's deeper rungs exist for, and it is invisible to every other
 * part of the app — the shell "ran and exited", so the tab says DISCONNECTED, and nothing reports the
 * environment as broken while every new terminal fails the same way.
 *
 * The endings deliberately *not* included, and why the line is drawn here rather than at "any fault":
 *
 *  - a signal death — 137 (the low-memory killer) above all — is the device's answer, not the
 *    rootfs's. Treating it as evidence would send the user to Repair over a userspace that is
 *    intact, and the probe that adjudicates forks another proot into the same shortage of memory.
 *  - an ordinary exit (`exit`, `exit 1`, a shell script that finished) is a session doing what it was
 *    asked, and `exit 127` typed by hand is indistinguishable from the damage by the status alone:
 *    the health probe is what tells them apart, and this predicate is only the first of the three
 *    conditions [LinuxUserspaceManager.noteSessionEnded] requires before it concludes anything.
 *  - every other [SessionEnd] is about a transport, and a local pty has none.
 *
 * A top-level function rather than a method because both sides need it and neither owns the other:
 * MainViewModel decides whether a session ending is worth reporting, and the manager decides what to
 * do about it, and a rule that lived in one of them would be re-derived — or drift — in the other.
 */
internal fun sessionEndBlamesUserspace(end: SessionEnd): Boolean = when (end) {
    is SessionEnd.ShellEnded -> end.signal == null && end.status == GUEST_SHELL_MISSING
    else -> false
}

/**
 * Exit 127, the status of a child whose program could not be executed.
 *
 * Named rather than repeated, because it means two different things in this feature and only one of
 * them is this: `ProotLaunchFailed` carries it as "the launcher could not start" for a non-interactive
 * command, and here it is the interactive ending that says the same thing about a pty — proot ran,
 * and the program it was told to run was not there.
 */
internal const val GUEST_SHELL_MISSING = 127

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
