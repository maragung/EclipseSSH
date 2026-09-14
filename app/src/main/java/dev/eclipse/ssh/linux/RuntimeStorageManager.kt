package dev.eclipse.ssh.linux

import android.os.StatFs
import java.io.File
import java.io.IOException

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
 * Deliberately plain Kotlin — `java.io` and nothing else — so the JVM tests construct it against
 * a temp directory. The one Android dependency, [StatFs], sits behind an injectable probe, and
 * under `returnDefaultValues` unit tests it reports 0, which every caller treats as "unknown",
 * not as "full".
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
            if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create $dir")
            if (!dir.isDirectory) throw IOException("$dir is not a directory")
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
        if (existing != null && !existing.isStale(currentBootId(), ::processAlive)) {
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

    private fun processAlive(pid: Int): Boolean =
        pid > 0 && runCatching { File("/proc/$pid").exists() }.getOrDefault(false)
}
