package dev.eclipse.ssh.linux

import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * One owner for every path the Linux userspace keeps on disk, plus the storage-level invariants
 * the runtime depends on: the directories exist and are writable *before* proot is ever spawned,
 * free space is knowable *before* a 30 MB download starts, and an install another process (or a
 * previous boot) left half-done is detectable.
 *
 * The class exists because these paths used to be assembled ad hoc at each use site — `rootfs` was
 * defined in two classes, `tmp` in one, the state file in two — and the one that mattered most,
 * proot's `PROOT_TMP_DIR`, was *set* without ever being *created*. That is precisely the
 * historical "can't create temporary directory: Permission denied" failure: an environment
 * variable is not a directory. Every path now comes from here, so the directory and the variable
 * that names it can never drift apart again.
 *
 * Deliberately plain Kotlin — `java.io`, plus `java.nio.file.Files` where a create must explain
 * itself (see [ensureDirectory]) — so the JVM tests construct it against a temp directory. The one
 * Android dependency, [StatFs], sits behind an injectable probe, and under `returnDefaultValues`
 * unit tests it reports 0, which every caller treats as "unknown", not as "full".
 *
 * @param rootDir the userspace root (`filesDir/linux`); everything below lives under it
 */
class RuntimeStorageManager(
    val rootDir: File,
    private val freeBytesProbe: (File) -> Long = { dir ->
        runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    },
    private val pidProvider: () -> Int = { android.os.Process.myPid() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** How many times [ensureDirectory] tries before reporting; see its doc for why it retries. */
    private companion object {
        const val CREATE_ATTEMPTS = 3
        const val CREATE_RETRY_DELAY_MS = 250L

        /** The prefix [ensureReady] gives its probe file; see [sweepOrphanTempDirs]. */
        const val PROBE_PREFIX = ".probe-"

        /**
         * How old a probe file must be before it is certainly not a live call's. See
         * [sweepOrphanTempDirs]: the round trip that creates and deletes one takes milliseconds,
         * so an hour is a margin, not a threshold anything real approaches.
         */
        const val PROBE_LITTER_AGE_MS = 60L * 60L * 1000L

        /**
         * proot's own temp-name shape, taken from its `create_temp_name`: `"%s/%s-%d-XXXXXX"`, with
         * `mkdtemp(3)` replacing the six `X`s with `[A-Za-z0-9]`. The whole name is matched, and the
         * prefix is left free, so this covers every directory proot makes — `exec`, `care`,
         * `sysvipc_shm` — rather than the one this app has seen.
         */
        val PROOT_TEMP_NAME = Regex("""[\w-]+-(\d+)-[A-Za-z0-9]{6}""")
    }
    /** The completed, in-place rootfs. */
    val rootfsDir: File get() = File(rootDir, "rootfs")

    /** Where a rootfs is unpacked before being validated and moved into place. */
    val stagingDir: File get() = File(rootDir, "rootfs.staging")

    /** proot's working directory (`PROOT_TMP_DIR`) and the one the probe file is written to. */
    val tmpDir: File get() = File(rootDir, "tmp")

    /** Where tarballs are downloaded before verification. */
    val downloadsDir: File get() = File(rootDir, "downloads")

    /**
     * The directory the persisted state lives in. It is the userspace root itself, not a
     * subdirectory: the state file has lived at the root since the first release, and moving it
     * would orphan every existing install's `installed=true` flag into a fresh NotInstalled.
     */
    val stateDir: File get() = rootDir

    /** The persisted userspace state (`installed=true`, which distro, when). */
    val stateFile: File get() = File(stateDir, "state.properties")

    /**
     * Where a keep-workspace uninstall parks its snapshot. Deliberately *outside* [rootDir]: the
     * uninstall deletes the root, and it must not destroy the thing it was keeping.
     */
    val workspaceBackupFile: File get() = File(rootDir.parentFile, "linux-workspace-backup.tar.gz")

    /** The cross-process install lock; see [acquireInstallLock]. */
    val installLockFile: File get() = File(rootDir, "install.lock")

    /**
     * Makes the runtime storage real: every directory created, and the tmp directory proven
     * writable by a create-write-read-delete round trip — because a tmp directory the app cannot
     * write to is the failure proot reports as "Permission denied" long after this point, where
     * naming it is still actionable.
     */
    fun ensureReady(): Result<Unit> = runCatching {
        for (dir in listOf(rootDir, tmpDir, downloadsDir)) {
            ensureDirectory(dir)
        }
        val probe = File(tmpDir, ".probe-${System.nanoTime()}")
        try {
            probe.writeText("probe")
            if (probe.readText() != "probe") throw IOException("cannot read back a file written to $tmpDir")
        } finally {
            probe.delete()
        }
    }

    /**
     * Creates one runtime directory, or proves it already is one.
     *
     * A plain `mkdirs()` reports failure as a bare `false`, and every distinct cause collapses into
     * the same message: a dangling symlink parked at the path (mkdir answers EEXIST, `exists()`
     * answers false), a transient filesystem error, or a directory another thread created between
     * the `exists()` check and the `mkdir()`. The first E2E install died exactly here with no way
     * to tell those apart — `cannot create …/tmp` and nothing else — so this method does three
     * things a boolean cannot:
     *
     *  1. [Files.createDirectories] instead of `mkdirs()`: it throws with the underlying errno as
     *     the message, and treats a directory that appeared concurrently as success, so the benign
     *     race stops being a failure at all.
     *  2. A short retry, because the failure window it guards sits mid-install on CI emulators,
     *     where a one-off I/O error should not burn a 15-minute run.
     *  3. A failure message that dumps the observable state of the path — exists, is a directory,
     *     is a symlink, parent writable, free bytes, and the last errno — so the next artifact
     *     names the real cause instead of another dead end.
     */
    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) return
        var lastFailure: IOException? = null
        for (attempt in 1..CREATE_ATTEMPTS) {
            try {
                Files.createDirectories(dir.toPath())
                if (dir.isDirectory) return
                throw IOException("$dir is not a directory")
            } catch (failure: IOException) {
                lastFailure = failure
            }
            if (attempt < CREATE_ATTEMPTS) Thread.sleep(CREATE_RETRY_DELAY_MS * attempt)
        }
        val path = dir.toPath()
        throw IOException(
            "cannot create $dir (exists=${dir.exists()} isDirectory=${dir.isDirectory} " +
                "symlink=${runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)} " +
                "parentWritable=${dir.parentFile?.canWrite() == true} freeBytes=${freeBytes()}; " +
                "last reason: ${lastFailure?.message})",
            lastFailure,
        )
    }

    /**
     * [ensureReady] with the failure made fatal in the wording every caller shares. The message
     * prefix is a contract: the error taxonomy recognizes "runtime storage not ready" as the
     * storage failure, distinct from anything proot itself might report.
     */
    fun requireReady() {
        ensureReady().getOrElse { cause ->
            throw IOException("runtime storage not ready: ${cause.message}", cause)
        }
    }

    /**
     * Free bytes at the userspace root, or 0 when the answer is unknown (the JVM's unmocked
     * `StatFs`, or a probe failure). Callers treat 0 as "do not gate on this", never as "full".
     */
    fun freeBytes(): Long = runCatching { freeBytesProbe(rootDir) }.getOrDefault(0L)

    /**
     * Deletes stray `*.part` download fragments from [downloadsDir] and (for installs that
     * predate it) the userspace root. A `.part` file is never a resume point — the download
     * restarts from the first byte and verifies the whole tarball — so it is only ever garbage
     * pinning disk the next attempt needs.
     *
     * @return how many fragments were removed
     */
    fun sweepOrphanPartFiles(): Int {
        var swept = 0
        for (dir in listOf(downloadsDir, rootDir)) {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".part") }
                ?.forEach { if (it.delete()) swept++ }
        }
        return swept
    }

    /**
     * Deletes the scratch directories proot's own processes left behind in [tmpDir], and the probe
     * files of an [ensureReady] that was killed mid-call.
     *
     * proot creates one private directory per process — `$PROOT_TMP_DIR/<prefix>-<pid>-XXXXXX`,
     * from its own `create_temp_name` — and removes it at exit. Two things leave one behind: a
     * process that is SIGKILLed, whose removal runs from talloc destructors a killed process never
     * runs, and — until proot patch 0006 — an *ordinary* exit, whose teardown chmod'ed the loader
     * symlink 0005 puts in that directory, was refused by the platform, and skipped the unlink the
     * refused chmod guarded. The second was one directory per proot process, every session and
     * every health probe, on a userspace that was otherwise healthy — so what is already on disk
     * has to be clearable by something, which is this.
     *
     * Liveness is read rather than guessed: the pid is part of the name proot chose, so a directory
     * whose process is still running belongs to a live session and is never touched. That also
     * bounds the sweep's one imprecision — a pid recycled after a reboot keeps a few empty
     * directories until that process exits — and is why this runs on every start rather than once.
     *
     * `.probe-*` is [ensureReady]'s: created and deleted inside one call, so one present is one
     * whose call was killed. Its name carries no owner to ask about, so age is the test instead, and
     * an hour is longer than that round trip by orders of magnitude — which is what makes it safe
     * against a probe another live process is halfway through.
     *
     * Deletion goes through [deleteTreeNoFollow], and not incidentally: the entry being cleared
     * holds exactly the symlink that must not lead a delete anywhere.
     *
     * @return how many entries were removed
     */
    fun sweepOrphanTempDirs(): Int {
        var swept = 0
        for (entry in tmpDir.listFiles().orEmpty()) {
            if (!isOrphanTempEntry(entry)) continue
            if (deleteTreeNoFollow(entry)) swept++
        }
        return swept
    }

    /** Whether one [tmpDir] entry is litter; see [sweepOrphanTempDirs] for both rules. */
    private fun isOrphanTempEntry(entry: File): Boolean {
        if (entry.name.startsWith(PROBE_PREFIX)) {
            return System.currentTimeMillis() - entry.lastModified() > PROBE_LITTER_AGE_MS
        }
        val pid = PROOT_TEMP_NAME.matchEntire(entry.name)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return !pidIsAlive(pid)
    }

    /**
     * The state of one held install lock: who holds it and since when, written so that a *crashed*
     * holder can be told apart from a live one.
     *
     * Liveness is two facts, because either alone lies: a pid alone survives its process (pids are
     * recycled), and a timestamp alone cannot distinguish a crash from a slow install. The boot id
     * pins the pid to one boot — a lock from a previous boot is stale no matter what pid now
     * exists — and within one boot, the pid's `/proc` entry is the truth.
     */
    data class InstallLock(
        val distroId: String,
        val phase: String,
        val acquiredAtMs: Long,
        val pid: Int,
        val bootId: String,
    ) {
        fun isStale(currentBootId: String?, processAlive: (Int) -> Boolean): Boolean {
            if (bootId.isNotEmpty() && currentBootId != null && currentBootId != bootId) return true
            return !processAlive(pid)
        }
    }

    /**
     * Takes the install lock for [distroId], refusing when a *live* one is held — two install
     * entry points racing across processes (a triple-tapped button, a foreground service retrying
     * while the UI retries) must run exactly one install. A lock left by a crashed holder is
     * stale by definition and is taken over.
     */
    fun acquireInstallLock(distroId: String, phase: String): InstallLock {
        rootDir.mkdirs()
        val existing = readInstallLock()
        if (existing != null && !existing.isStale(currentBootId(), ::pidIsAlive)) {
            throw IllegalStateException(
                "an install is already in progress (${existing.phase}, pid ${existing.pid})",
            )
        }
        val lock = InstallLock(distroId, phase, clock(), pidProvider(), currentBootId() ?: "")
        installLockFile.writeText(
            "${lock.distroId}\n${lock.phase}\n${lock.acquiredAtMs}\n${lock.pid}\n${lock.bootId}",
        )
        return lock
    }

    /** Re-records the phase of the lock this process holds, without changing its identity. */
    fun updateInstallLockPhase(phase: String) {
        val existing = readInstallLock() ?: return
        if (existing.pid != pidProvider()) return
        val updated = existing.copy(phase = phase)
        installLockFile.writeText(
            "${updated.distroId}\n${updated.phase}\n${updated.acquiredAtMs}\n${updated.pid}\n${updated.bootId}",
        )
    }

    /** Releases the install lock. Releasing an unheld lock is a no-op, not an error. */
    fun releaseInstallLock() {
        installLockFile.delete()
    }

    /**
     * The install lock as it stands on disk, or null when none is held or the file is unreadable
     * (a torn write during a crash lands here — treated as no lock, the safe direction).
     */
    fun readInstallLock(): InstallLock? {
        if (!installLockFile.isFile) return null
        val lines = runCatching { installLockFile.readLines() }.getOrNull() ?: return null
        if (lines.size < 5) return null
        return InstallLock(
            distroId = lines[0],
            phase = lines[1],
            acquiredAtMs = lines[2].toLongOrNull() ?: return null,
            pid = lines[3].toIntOrNull() ?: return null,
            bootId = lines[4],
        )
    }

    /** The boot id of the running system, or null off-device / without `/proc`. */
    internal fun currentBootId(): String? =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
}

/**
 * Whether [pid] names a process that is still running: `/proc/<pid>` exists.
 *
 * The same fact [RuntimeStorageManager.InstallLock.isStale] decides on, and read the same way — a
 * `/proc` entry rather than a signal, because a process this app may not signal still owns its
 * directories. On a system with no `/proc` the answer is "not running", which is the direction that
 * clears litter rather than the one that keeps it.
 */
private fun pidIsAlive(pid: Int): Boolean =
    pid > 0 && runCatching { File("/proc/$pid").exists() }.getOrDefault(false)
