package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads, verifies and extracts a pinned rootfs tarball — the "RootfsManager" of the userspace.
 *
 * Security posture: the tarball's SHA256 is checked *before* extraction begins, so a tampered or
 * truncated download never reaches the filesystem. The extractor then refuses any entry that
 * tries to escape the target directory — `../` components or absolute paths — which is the
 * classic tar-parsing attack surface, and while the source here is hash-pinned rather than
 * hostile, the guard means the pin and the parser agree on what they guarantee.
 *
 * Extraction lands in a staging directory and is moved into place with a rename, so a rootfs that
 * exists is always a complete one: a failed or interrupted install leaves nothing half-extracted
 * under the real name.
 *
 * Permissions: Android forbids `chown`, so every extracted file is owned by the app uid no matter
 * what the tarball claims — which is also why the rootfs needs no privilege model of its own (see
 * [ProotRuntime]). What the extractor *does* preserve is the mode bits that matter to userspace:
 * the executable bit on binaries, and the read/write bits the tools inside check.
 */
class RootfsInstaller(
    private val rootDir: File,
    private val distro: LinuxDistro,
    private val downloader: HttpDownloader = UrlConnectionDownloader(),
    private val storage: RuntimeStorageManager = RuntimeStorageManager(rootDir),
    private val validator: RootfsValidator = RootfsValidator(expectedArch = distro.ubuntuArch),
    /**
     * Where this phase's events are filed. The same ring the apt phase writes to — the DI graph
     * hands one instance to both — so the "Install log" holds the whole install rather than its
     * second half. Nothing here recorded anything before this parameter existed: a download that
     * failed at 90%, a tarball that failed its checksum, or an extraction that skipped hard links
     * left no trace in logcat or in the ring, and those are exactly the events the storage and
     * rootfs failure taxonomy is built to name.
     */
    private val diagnostics: UserspaceDiagnostics = UserspaceDiagnostics(),
) {
    /** Where the tarball is downloaded to before verification. */
    val tarballFile: File get() = File(storage.downloadsDir, "rootfs-${distro.ubuntuArch}.tar.gz")

    /** Where the rootfs is unpacked before being moved into place. */
    private val stagingDir: File get() = storage.stagingDir

    /** The completed, in-place rootfs. */
    val rootfsDir: File get() = storage.rootfsDir

    /**
     * Progress through the install, for the UI's install screen. Byte-precise for the download
     * (which has a known total), step-precise for extraction.
     */
    sealed interface Progress {
        /** Downloading; [received] of [total] bytes. */
        data class Downloading(val received: Long, val total: Long) : Progress

        /** Verifying the tarball's SHA256. */
        data object Verifying : Progress

        /**
         * Extracting; [entries] unpacked so far. [warnings] carries extraction events the rootfs
         * can live without but the setup report should name (a forward hardlink is the classic
         * one: a link whose target entry arrives later in the same tarball, skipped by design).
         *
         * [fraction] is how much of the tarball has been read, 0 to 1, or null when the file's
         * length is unknown — the measure an extraction can actually take, since the size of the
         * tree it unpacks to is exactly what the decompression-bomb budget refuses to assume.
         */
        data class Extracting(
            val entries: Int,
            val warnings: List<String> = emptyList(),
            val fraction: Float? = null,
        ) : Progress
    }

    /**
     * Whether the rootfs is present, complete and alone: the staging directory is gone and the
     * rootfs holds a `/bin` directory. Deliberately cheap — the real health check is the
     * distribution manager's probe, which actually runs a shell.
     *
     * The staging half is a *promise* about a completed extraction rather than a check of one (see
     * the class doc: extraction lands in staging and is renamed into place), which is why a leftover
     * staging tree makes this false for a rootfs that is perfectly whole — [rootfsInPlace] is the
     * question to ask when the difference matters, and [reclaimFailedExtraction] is the way to
     * restore this one's answer.
     */
    fun isExtracted(): Boolean = !stagingDir.exists() && rootfsInPlace()

    /**
     * Whether a rootfs is on disk at all, whatever a crashed run left beside it in staging. A repair
     * that finds this true and [isExtracted] false has a leftover to reclaim, not an install to
     * redo.
     */
    fun rootfsInPlace(): Boolean = File(rootfsDir, "bin").isDirectory

    /**
     * Deletes the download and every extracted tree — the uninstall path, which owns the "none of
     * it is kept" decision.
     */
    fun deleteRootfs() {
        tarballFile.delete()
        deleteTreeNoFollow(rootfsDir)
        deleteTreeNoFollow(stagingDir)
    }

    /**
     * Reclaims a staging tree a failed install left behind, keeping the verified tarball so the
     * retry resumes from it instead of re-downloading. The manager's failure path calls this; it
     * never touches a rootfs that made it into place.
     */
    fun reclaimFailedExtraction() {
        deleteTreeNoFollow(stagingDir)
    }

    /**
     * The full rootfs install: download → verify → extract → move into place.
     *
     * @param onProgress invoked on [Dispatchers.IO] with each progress update; must be cheap
     * @param onExtractionWarnings invoked once, after extraction, with events the rootfs can live
     *   without but the setup report should name (forward hardlinks skipped, and whatever else
     *   [extract] records from here on)
     * @throws IOException on any network, verification or extraction failure
     */
    suspend fun install(
        onProgress: (Progress) -> Unit = {},
        onExtractionWarnings: (List<String>) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        // Storage first: the download lands under downloads/ and the probe inside ensureReady()
        // fails here — with a name — rather than as a mid-download EACCES. The sweep then clears
        // fragments a crashed attempt left pinning the space this one needs.
        storage.requireReady()
        storage.sweepOrphanPartFiles()
        checkFreeSpace()
        download(onProgress)
        onProgress(Progress.Verifying)
        verify()
        onExtractionWarnings(extract(onProgress))
        validateStaging()
        moveIntoPlace()
        // One event for the phase, at its end: the four below it that matter (the gate, the
        // download, the extraction, the validation) each recorded their own outcome, so this is
        // the line that says the whole thing finished and how long it took.
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "rootfs ready",
            durationMs = System.currentTimeMillis() - startedAt,
            detail = "${distro.displayName} ${distro.ubuntuArch}",
        )
        rootfsDir
    }

    /**
     * The staging tree's examination before it is allowed to become the rootfs: the manifest and
     * the size floor from [RootfsValidator]. A tree that fails is deleted here — it is
     * regenerable from the (verified, kept) tarball — and the install fails naming what was
     * wrong, instead of moving a broken rootfs into place and letting the first shell be the
     * thing that discovers it.
     */
    private fun validateStaging() {
        val findings = validator.validate(stagingDir)
        if (findings.isEmpty()) return
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "rootfs validation failed",
            detail = findings.joinToString("; ") { "${it.path} ${it.problem}" },
        )
        deleteTreeNoFollow(stagingDir)
        throw IOException(
            "the extracted rootfs failed validation: " +
                findings.joinToString("; ") { "${it.path} ${it.problem}" },
        )
    }

    /**
     * What an install of this distribution needs free, by the same arithmetic [checkFreeSpace]
     * gates on. Public because the repair ladder asks the same question before it decides whether
     * freeing space is worth doing, and a second copy of the formula would be a second answer.
     */
    val requiredFreeBytes: Long
        get() = distro.rootfsSizeBytes * NEEDED_TARBALL_MULTIPLE + FREE_SPACE_HEADROOM_BYTES

    /**
     * Refuses to start a download the disk cannot hold. The budget is deliberately coarse — the
     * tarball, several multiples of it unpacked (the extracted rootfs plus apt's working space),
     * and a headroom for everything else the app stores — because the alternative failure is
     * discovering ENOSPC half way through a 30 MB download on a metered connection.
     *
     * A free-space answer of 0 means "unknown" (the JVM's unmocked StatFs, or a probe failure),
     * never "full", so it never blocks an install.
     */
    private fun checkFreeSpace() {
        val free = storage.freeBytes()
        if (free <= 0L) return
        val needed = requiredFreeBytes
        // Recorded either way, like the apt phase's gate: the number that proves an ENOSPC twenty
        // minutes later is the one taken before the write started.
        diagnostics.record(
            UserspaceDiagnosticCategory.STORAGE,
            "rootfs disk gate",
            detail = "free=${free / MIB}MB, needed=${needed / MIB}MB",
        )
        if (free < needed) {
            // The prefix is a contract: the error taxonomy reads "Ubuntu needs" as DiskFull.
            throw IOException(
                "Ubuntu needs about ${needed / MIB} MB of free storage to install " +
                    "(the download plus the unpacked system), but only about ${free / MIB} MB is free. " +
                    "Free up storage and try again.",
            )
        }
    }

    /**
     * Frees the bytes a userspace can regenerate, and answers how many were freed.
     *
     * Written for the one environmental failure a repair can do something about. "Ubuntu needs about
     * N MB of free storage" and the guest's own `No space left on device` are facts about the disk
     * rather than about the rootfs, and the app's answer to both used to be to tell the user to go
     * and free some — advice rather than a repair, on a device where the largest expendable trees
     * are inside the very userspace they are being asked to fix. A userspace that has installed and
     * upgraded packages holds hundreds of megabytes of apt's downloaded `.deb` files and unpacked
     * index lists, every byte of which the next `apt-get update` rebuilds.
     *
     * What it takes is what is *generated* rather than owned:
     *
     * - apt's package cache and package lists — the two directories are kept and only emptied, the
     *   way `apt-get clean` leaves them, because they are part of the base system's structure and
     *   apt expects them to exist. The next setup run's `apt-get update` refills the lists.
     * - apt's own binary index caches (`pkgcache.bin`, `srcpkgcache.bin`), which it rewrites on the
     *   next run by definition.
     * - the contents of the guest's `/tmp` and `/var/tmp` — emptied, never deleted: the directories
     *   are the archive's, and a repair that removed them would leave a rootfs no restore puts back
     *   (both are preserved members — see [isPreservedMember]).
     * - rotated log files (`*.gz`, `*.1`), which are the large ones; a live log is left alone,
     *   because it is often the evidence of the failure being repaired.
     * - a download fragment a failed fetch left behind — never a resume point.
     * - a previous rootfs parked by a swap that crashed between parking it and deleting it. Only
     *   when a rootfs is in place: otherwise that parked tree is the user's only copy of everything
     *   they have, and deleting it would be the worst thing this class could do.
     *
     * It takes nothing the user owns: no file under `/home`, no installed package, no package
     * database, no configuration. Every step is best-effort and the answer counts only what is
     * actually gone — a file that will not delete is a file that did not free anything, and a repair
     * must not fail over one.
     *
     * Nothing here descends through a symbolic link, and that is the load-bearing rule of the whole
     * function: a rootfs' links are absolute and guest-rooted, so `<rootfs>/var/log` as the link
     * `-> /run` names the *host's* `/run` to every call this function could make — a listing would
     * enumerate the device's own files and the delete that followed would take them. Directories are
     * entered only when they are directories [LinkOption.NOFOLLOW_LINKS] says they are, and the
     * deletions themselves go through [deleteTreeNoFollow], which treats a link as the entry it is.
     */
    suspend fun reclaimSpace(): Long = withContext(Dispatchers.IO) {
        // How much each kind of thing gave back, for the log line: which tree was holding the bytes
        // is the question a report asks, and the total alone cannot answer it.
        val freed = linkedMapOf<String, Long>()

        /** Deletes [target] whole — for a cache apt owns as a unit, and for single files. */
        fun reclaim(label: String, target: File) {
            if (!target.exists() && !java.nio.file.Files.isSymbolicLink(target.toPath())) return
            val bytes = sizeNoFollow(target)
            deleteTreeNoFollow(target)
            if (target.exists()) return
            freed[label] = (freed[label] ?: 0L) + bytes
        }

        /** Empties [dir] of its contents, keeping the directory itself. */
        fun empty(label: String, dir: File) {
            if (!isRealDirectory(dir)) return
            dir.listFiles()?.forEach { reclaim(label, it) }
        }

        /**
         * Deletes the rotated logs directly under [dir] and under its immediate subdirectories —
         * `/var/log/apt` is where a long install's transcripts live. Two levels, not a walk, for the
         * reason the function's own doc gives.
         */
        fun reclaimRotatedLogs(dir: File) {
            if (!isRealDirectory(dir)) return
            val entries = dir.listFiles().orEmpty().toList()
            val rotated = { file: File -> isRealFile(file) && isRotatedLog(file.name) }
            entries.filter(rotated).forEach { reclaim("rotated logs", it) }
            entries.filter { isRealDirectory(it) }
                .flatMap { it.listFiles().orEmpty().filter(rotated).toList() }
                .forEach { reclaim("rotated logs", it) }
        }

        val startedAt = System.currentTimeMillis()
        empty("apt package cache", File(rootfsDir, "var/cache/apt/archives"))
        empty("apt package lists", File(rootfsDir, "var/lib/apt/lists"))
        reclaim("apt index caches", File(rootfsDir, "var/cache/apt/pkgcache.bin"))
        reclaim("apt index caches", File(rootfsDir, "var/cache/apt/srcpkgcache.bin"))
        empty("temporary files", File(rootfsDir, "tmp"))
        empty("temporary files", File(rootfsDir, "var/tmp"))
        reclaimRotatedLogs(File(rootfsDir, "var/log"))
        reclaim("download fragments", File(storage.downloadsDir, "${tarballFile.name}.part"))
        if (rootfsInPlace()) {
            // The condition is the whole safety of this line: a parked rootfs with nothing in place
            // is the user's system, mid-swap, and not this function's to delete.
            reclaim("a previous rootfs", File(storage.rootDir, "rootfs.old"))
        }

        val total = freed.values.sum()
        if (total > 0L) {
            diagnostics.record(
                UserspaceDiagnosticCategory.STORAGE,
                "reclaimed space in the userspace",
                durationMs = System.currentTimeMillis() - startedAt,
                detail = "${total / MIB}MB: " +
                    freed.entries.joinToString(", ") { "${it.key} ${it.value / MIB}MB" },
            )
        }
        total
    }

    /**
     * The bytes [target] occupies without following links — a link counts as its own entry, never
     * as what it points at — or 0 when the tree cannot be measured. The count is for a log line and
     * a sentence to the user, never for a decision, which is why a failure here is answered with
     * zero rather than propagated.
     */
    private fun sizeNoFollow(target: File): Long {
        val path = target.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) return 0L
        return runCatching {
            var total = 0L
            java.nio.file.Files.walkFileTree(
                path,
                emptySet<java.nio.file.FileVisitOption>(),
                Int.MAX_VALUE,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        total += attrs.size()
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
            total
        }.getOrDefault(0L)
    }

    /** Whether [file] is a directory itself, rather than a link to one. */
    private fun isRealDirectory(file: File): Boolean =
        java.nio.file.Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    /** Whether [file] is a regular file itself, rather than a link to one. */
    private fun isRealFile(file: File): Boolean =
        java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    /** Whether [name] is a log a rotation has already closed: the large ones, and the expendable ones. */
    private fun isRotatedLog(name: String): Boolean =
        name.endsWith(".gz") || name.endsWith(".1") || name.endsWith(".old")

    private suspend fun download(onProgress: (Progress) -> Unit) {
        migrateLegacyTarball()
        if (tarballFile.isFile && verifyFileSha256(tarballFile, distro.rootfsSha256)) {
            // A previously verified tarball is reused, not re-downloaded: an interrupted install
            // resumes rather than starting its 30 MB download again.
            diagnostics.record(
                UserspaceDiagnosticCategory.DOWNLOAD,
                "reused verified tarball",
                detail = "${tarballFile.length()} bytes already on disk",
            )
            return
        }
        val startedAt = System.currentTimeMillis()
        // Captured so the downloader's non-suspending chunk callback can still honour
        // cancellation — a cancelled install must stop mid-download, not after 30 MB more.
        val job = coroutineContext[Job]
        val temp = File(storage.downloadsDir, tarballFile.name + ".part")
        var receivedFinal = 0L
        var reportedTotal = 0L
        try {
            try {
                downloader.download(
                    distro.rootfsTarballUrl,
                    temp,
                    onChunk = { received, total ->
                        job?.ensureActive()
                        receivedFinal = received
                        if (total > 0) reportedTotal = total
                        // The server's content length versus the pin: wildly off in either direction
                        // means the URL is no longer serving the file this build was verified against
                        // (an error page with a 200, a redirect to something else) — fail now, not
                        // after 30 MB of the wrong bytes.
                        if (total > 0 && distro.rootfsSizeBytes > 0) {
                            val expected = distro.rootfsSizeBytes
                            if (total > expected * 2 || total < expected / 2) {
                                throw PinnedArchiveUnavailable(
                                    "the rootfs download is $total bytes, but the pinned ${distro.displayName} " +
                                        "tarball is about $expected bytes - the URL is serving something else",
                                )
                            }
                        }
                        onProgress(Progress.Downloading(received, total))
                    },
                )
            } catch (e: IOException) {
                // Typed here rather than at each throw site, because the fetch has three: the
                // downloader's own HTTP failure, the size check above, and the truncation check
                // below. They are one fact — the archive could not be obtained — and the repair
                // ladder reads it that way (see [PinnedArchiveUnavailable]).
                throw PinnedArchiveUnavailable(e.message ?: "the rootfs download failed", e)
            }
            if (reportedTotal > 0 && receivedFinal != reportedTotal) {
                throw PinnedArchiveUnavailable(
                    "the rootfs download was truncated: $receivedFinal of $reportedTotal bytes arrived",
                )
            }
            // Fail closed: an unverifiable temp file is deleted by the finally below, never
            // renamed into place as if it were the pinned tarball.
            if (!verifyFileSha256(temp, distro.rootfsSha256)) {
                diagnostics.record(
                    UserspaceDiagnosticCategory.DOWNLOAD,
                    "tarball checksum mismatch",
                    detail = "expected ${distro.rootfsSha256.take(16)}…, ${receivedFinal} bytes arrived",
                )
                throw PinnedArchiveUnavailable("Rootfs checksum mismatch for ${distro.displayName}")
            }
            // Bytes and seconds, not a progress transcript: the rate is what says whether a slow
            // install was the network or the unpack, which is the first question a report raises.
            diagnostics.record(
                UserspaceDiagnosticCategory.DOWNLOAD,
                "tarball downloaded and verified",
                durationMs = System.currentTimeMillis() - startedAt,
                detail = "${receivedFinal} bytes from ${distro.rootfsTarballUrl}",
            )
            if (!temp.renameTo(tarballFile)) {
                temp.copyTo(tarballFile, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * Tarballs used to be downloaded straight into the userspace root, before the downloads
     * directory existed. A verified leftover moves over (the retry resumes instead of
     * re-downloading); anything else is swept — it is garbage pinning the space the next attempt
     * needs.
     */
    private fun migrateLegacyTarball() {
        val legacy = File(storage.rootDir, tarballFile.name)
        if (!legacy.isFile) return
        if (verifyFileSha256(legacy, distro.rootfsSha256)) {
            if (!legacy.renameTo(tarballFile)) {
                legacy.copyTo(tarballFile, overwrite = true)
                legacy.delete()
            }
        } else {
            legacy.delete()
        }
    }

    private fun verify() {
        if (!verifyFileSha256(tarballFile, distro.rootfsSha256)) {
            throw PinnedArchiveUnavailable("Rootfs checksum mismatch for ${distro.displayName}")
        }
    }

    /**
     * The verified pinned archive on disk, fetched if it is not there.
     *
     * A successful install deletes the tarball — it is spare bytes once the rootfs is in place — so
     * every repair that needs the archive's bytes has to be ready to fetch it again: 34 MB once,
     * after which the repairs that worked from it leave it where the next one finds it.
     */
    private suspend fun ensurePinnedArchive(onProgress: (Progress) -> Unit) {
        if (tarballFile.isFile && verifyFileSha256(tarballFile, distro.rootfsSha256)) return
        download(onProgress)
        verify()
    }

    /**
     * Extracts the tarball into staging, then swaps staging into place. A rootfs that exists is
     * therefore always complete — see the class doc.
     */
    private suspend fun extract(onProgress: (Progress) -> Unit): List<String> {
        onProgress(Progress.Extracting(0))
        deleteTreeNoFollow(stagingDir)
        stagingDir.mkdirs()
        var entries = 0
        var written = 0L
        // The tarball's own size is the denominator; a file whose length the platform will not
        // report leaves every reading null, and the ladder above falls back to its schedule.
        val tarballBytes = tarballFile.length()
        var consumed = 0L
        val extractionWarnings = mutableListOf<String>()
        val budget = extractionBudgetBytes()
        val extractionStartedAt = System.currentTimeMillis()
        fun fractionRead(): Float? =
            if (tarballBytes > 0L) (consumed.toFloat() / tarballBytes).coerceIn(0f, 1f) else null
        openTarStream(tarballFile, DOWNLOAD_BUFFER) { consumed = it }.use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = resolveInside(stagingDir, entry.name)
                // 0 is LF_OLDNORM - NUL is the pre-POSIX encoding of "regular file" and real
                // tarballs (Ubuntu Base included) still emit it for early entries.
                when (entry.linkFlag) {
                    TarArchiveEntry.LF_DIR -> {
                        target.mkdirs()
                    }
                    TarArchiveEntry.LF_SYMLINK -> {
                        target.parentFile?.mkdirs()
                        // Where the link points must resolve inside the staging root too: a
                        // symlink pointing out is an escape hatch none of the entry-name checks
                        // would catch, because nothing is written through it here.
                        resolveLinkInsideRoot(stagingDir, entry.name, entry.linkName)
                        // Created and deleted: if a previous extraction (or the rootfs itself)
                        // already put something there, the symlink must replace it.
                        target.delete()
                        deleteTreeNoFollow(target)
                        java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                    }
                    TarArchiveEntry.LF_LINK -> {
                        target.parentFile?.mkdirs()
                        val source = resolveInside(stagingDir, entry.linkName)
                        target.delete()
                        deleteTreeNoFollow(target)
                        // A hard link is what the tarball says, and what costs nothing on disk.
                        // The platform refuses link(2) inside app data (EACCES — the same refusal
                        // linux/proot-patches/0003 emulates for the guest), so a symlink stands in
                        // for it: same bytes, one file, followed transparently by everything that
                        // opens it. Copying instead is what broke Ubuntu 26.04 — its coreutils is
                        // one 11 MB binary under 113 names, so a copy per name writes 1.28 GB and
                        // trips the budget below, aborting the install partway through the tarball.
                        // A copy is kept only as a last resort, and only then does it count
                        // against the budget.
                        if (!linkFile(target, source)) {
                            if (source.isFile) {
                                source.copyTo(target, overwrite = true)
                                written += target.length()
                                extractionWarnings += "hardlink ${entry.name} copied: neither a hard link nor a symlink to ${entry.linkName} could be made"
                            } else {
                                extractionWarnings += "hardlink ${entry.name} skipped: its target ${entry.linkName} was not extracted"
                            }
                        }
                    }
                    TarArchiveEntry.LF_NORMAL, 0.toByte() -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> tar.copyTo(output) }
                        applyMode(target, entry.mode)
                        written += target.length()
                    }
                    else -> {
                        // Device nodes, fifos and the like: the rootfs does not need them — proot
                        // binds the host's /dev — and creating them would require privileges the
                        // app does not have anyway.
                    }
                }
                // The decompression-bomb budget: the pin says what the tarball weighs, so the
                // tree it unpacks to is bounded at a multiple of that. A gzip bomb that would
                // fill the disk is refused mid-extraction, with the staging tree reclaimable.
                if (written > budget) {
                    throw IOException(
                        "the rootfs archive expands beyond the expected size " +
                            "(${written / (1024 * 1024)} MB unpacked from a ${tarballFile.length() / (1024 * 1024)} MB tarball) - refusing to continue",
                    )
                }
                entries++
                if (entries % PROGRESS_EVERY_ENTRIES == 0) {
                    coroutineContext.ensureActive()
                    onProgress(Progress.Extracting(entries, extractionWarnings.toList(), fractionRead()))
                }
            }
        }
        // The last reading is 1 by definition, not by the byte counter: the extraction is over, and
        // a tar's own end-of-archive padding — which nothing reads — would otherwise leave the
        // reading a little short of the end, so the bar would sit just below 100% through a phase
        // that is finished.
        onProgress(Progress.Extracting(entries, extractionWarnings.toList(), 1f))
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "rootfs extracted",
            durationMs = System.currentTimeMillis() - extractionStartedAt,
            detail = "$entries entries, ${written / (1024 * 1024)}MB unpacked, " +
                "${extractionWarnings.size} warning(s)" +
                // The warnings themselves, because a skipped forward hardlink is a file that is
                // genuinely absent from the rootfs: it is the warning most likely to explain a
                // later apt or dpkg failure, and 200 chars of it is worth more than the count.
                (extractionWarnings.takeIf { it.isNotEmpty() }?.let { ": " + it.first() } ?: ""),
        )
        return extractionWarnings
    }

    /**
     * Point [target] at [source] the way the archive's hard link means it: a hard link where the
     * platform allows one, a relative symlink where it does not. Returns false when neither could be
     * made, leaving the caller to copy the bytes instead — or to name the gap, when there is nothing
     * to copy either.
     *
     * The symlink is relative and computed between the two paths inside the root, so it keeps
     * pointing at the right file after the staging tree is swapped into place — and it needs neither
     * the permission a hard link needs nor a source that exists. A source that does *not* exist is
     * refused before either is attempted: a forward hardlink is a gap in the tree, and a link to
     * nothing standing where a program belongs is worse than the gap, because opening it fails later
     * and elsewhere. The extractor names it instead (see the forward-hardlink warning there).
     */
    private fun linkFile(target: File, source: File): Boolean {
        if (!source.exists()) return false
        return try {
            java.nio.file.Files.createLink(target.toPath(), source.toPath())
            true
        } catch (e: IOException) {
            val parent = target.parentFile ?: return false
            try {
                java.nio.file.Files.createSymbolicLink(
                    target.toPath(),
                    parent.toPath().relativize(source.toPath()),
                )
                true
            } catch (e: IOException) {
                false
            } catch (e: UnsupportedOperationException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }

    /**
     * What the tarball is allowed to unpack to: ~10x the pinned compressed size, which a real
     * Ubuntu Base image (66 MB from a 28 MB tarball) fits with room to spare and a gzip bomb does
     * not. Without a pin (a hand-built test distro), a fixed ceiling stands in.
     */
    private fun extractionBudgetBytes(): Long =
        if (distro.rootfsSizeBytes > 0) {
            distro.rootfsSizeBytes * 10
        } else {
            DEFAULT_EXTRACTION_BUDGET_BYTES
        }

    private fun moveIntoPlace() {
        // The previous rootfs is parked beside the new one rather than deleted first: the old
        // delete-then-rename ordering had a window where a crash left *neither* root, and the
        // parked copy is also the rollback if the swap itself fails.
        val parked = File(storage.rootDir, "rootfs.old")
        deleteTreeNoFollow(parked)
        var parkedPrevious = false
        if (rootfsDir.exists()) {
            parkedPrevious = rootfsDir.renameTo(parked)
            if (!parkedPrevious) {
                // Renaming the old root away failed for reasons we cannot fix here; deleting it
                // reopens the no-root window but keeps the install able to proceed.
                deleteTreeNoFollow(rootfsDir)
            }
        }
        if (!stagingDir.renameTo(rootfsDir)) {
            if (parkedPrevious) {
                parked.renameTo(rootfsDir)
            }
            // Same-filesystem rename only fails for reasons we cannot fix here; report rather than
            // half-move.
            throw IOException("Could not move the extracted rootfs into place at $rootfsDir")
        }
        deleteTreeNoFollow(parked)
        // The tarball has served its purpose; keeping it would pin 30 MB for nothing.
        tarballFile.delete()
    }

    /**
     * Resolves [name] inside [root], refusing anything that escapes it — the shared guard in
     * [resolveInsideRoot], whose doc explains why extraction and checking live apart.
     */
    private fun resolveInside(root: File, name: String): File = resolveInsideRoot(root, name)

    /**
     * Applies the mode bits that matter under Android: the executable bit from the tar entry, read
     * and write for the owner. Group/other bits are dropped — they are meaningless when every
     * file has the same owner, and `setExecutable(false, false)` on a directory would break
     * traversal.
     */
    private fun applyMode(target: File, mode: Int) {
        if (target.isDirectory) return
        val executable = (mode and 0b001_001_001) != 0
        target.setExecutable(executable, true)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    /**
     * Puts named members of the pinned archive back into the installed rootfs, and answers which
     * ones it restored.
     *
     * The one repair that cannot go through the package manager, written for the failure where the
     * package manager is the thing that broke. `dpkg` needs a handful of programs to exist before it
     * will run at all — `sh` to execute its maintainer scripts, `rm` and `tar` to unpack and clear
     * away — and when one of them is gone from the rootfs, every `dpkg --configure -a` and every
     * `apt-get -f install` fails with the same `1 expected program not found in PATH or not
     * executable`, so the app's own Repair could never clear the state it exists to clear. There is
     * no way back through apt by definition, and no way at all through a shell that has no `rm`.
     *
     * So the bytes come from where the install got them: the same tarball, the same pin, the same
     * SHA256. The archive is downloaded again when a successful install has deleted it (the install
     * path removes it deliberately — see [moveIntoPlace]) and *kept* afterwards, because unlike on
     * the install path it is not spare bytes here: it is the only copy of the files a repair can
     * reach for, and re-downloading 30 MB on every pass of the repair ladder is worse than pinning it.
     *
     * Members are restored into the live rootfs rather than into staging, because this runs on a
     * rootfs that is otherwise complete and in use: a staging swap would replace a user's whole
     * installed system to put back four binaries. Only the named members are touched — no directory
     * is cleared, nothing is overwritten that the archive does not name — so a file the user has
     * edited outside those names survives untouched.
     *
     * @param memberNames archive-relative names, exactly as the tarball spells them (`usr/bin/rm`,
     *   not `/usr/bin/rm`). A name the archive does not carry is reported by its absence from the
     *   result rather than as a failure: a rootfs that is missing four things is not made worse by
     *   restoring three.
     */
    suspend fun restoreFromPinnedTarball(
        memberNames: Set<String>,
        onProgress: (Progress) -> Unit = {},
    ): List<String> {
        if (memberNames.isEmpty()) return emptyList()
        ensurePinnedArchive(onProgress)
        val restored = sortedSetOf<String>()
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        openTarStream(tarballFile, DOWNLOAD_BUFFER) {}.use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./")
                if (name !in memberNames) continue
                // Refused for the same reason the overlay refuses it, and not left to the caller:
                // no repair writes a preserved member — `/home`, the package database, the state
                // trees (see [isPreservedMember]) — however the member set was built. Today's callers
                // never name one, and a caller that did would be writing the archive's `/home` over
                // the user's.
                if (isPreservedMember(name)) continue
                val target = resolveInside(rootfsDir, name)
                if (writeMember(name, target, entry, tar, warnings)) restored += name
            }
        }
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "restored from the pinned archive",
            durationMs = System.currentTimeMillis() - startedAt,
            detail = "${restored.size} of ${memberNames.size} member(s) put back: " +
                (restored.take(6).joinToString(", ").ifEmpty { "none" }),
        )
        return restored.toList()
    }

    /**
     * Every archive member the installed rootfs is missing, or has as something other than what the
     * archive says it is — [RootfsIntegrity] carries what that means and what it deliberately does
     * not.
     *
     * The archive is fetched if a successful install has deleted it, which is the one slow part of
     * this and the reason the repair ladder calls it only after a cheaper repair has already failed.
     * One pass over the tarball and one filesystem lookup per member: on the phone this is a second
     * or two for a whole base system, and it is the only check in the app that can say *which* of
     * the archive's two thousand members went missing.
     *
     * @throws PinnedArchiveUnavailable when the archive cannot be fetched or does not match its pin
     */
    suspend fun inspectAgainstPinnedArchive(
        onProgress: (Progress) -> Unit = {},
    ): RootfsIntegrity = withContext(Dispatchers.IO) {
        ensurePinnedArchive(onProgress)
        val paths = RootfsPaths(rootfsDir)
        val damaged = sortedSetOf<String>()
        var examined = 0
        var consumed = 0L
        val tarballBytes = tarballFile.length()
        val startedAt = System.currentTimeMillis()
        openTarStream(tarballFile, DOWNLOAD_BUFFER) { consumed = it }.use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./")
                if (name.isEmpty() || isPreservedMember(name)) continue
                examined++
                if (!memberIsPresent(paths, name, entry)) damaged += name
                if (examined % PROGRESS_EVERY_ENTRIES == 0) {
                    coroutineContext.ensureActive()
                    onProgress(Progress.Extracting(examined, fraction = tarballFraction(consumed, tarballBytes)))
                }
            }
        }
        val integrity = RootfsIntegrity(examined = examined, damaged = damaged)
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "rootfs compared with the pinned archive",
            durationMs = System.currentTimeMillis() - startedAt,
            detail = integrity.describe(),
        )
        integrity
    }

    /**
     * Rewrites the base system from the pinned archive, in place: every member the archive carries is
     * written over whatever the rootfs holds at that name, and nothing the archive does not name is
     * touched — no directory is cleared, no tree is replaced.
     *
     * This is the repair for a base system that is *present but wrong*: a library replaced by
     * something else, a binary whose mode or contents no longer match what the image shipped,
     * damage the file-level restore cannot describe because nothing is missing. It is not an install,
     * and the difference is [isPreservedMember]: the user's `/home`, the package database under
     * `/var/lib/dpkg` and the log, cache and temporary directories are never written, so a system
     * with packages installed comes out of this with those packages' files still on disk and still
     * tracked by dpkg, and with everything the base image owns replaced by the base image's own
     * bytes.
     *
     * A directory entry means "this directory exists" and is created, never cleared — clearing one
     * would be deleting the user's files to satisfy a mkdir — and a *directory* standing where the
     * archive names a file is removed, because that is the one case where the archive's own bytes
     * cannot be written at all.
     *
     * No decompression-bomb budget guards this the way [extract] is guarded: the bytes come from the
     * same SHA256-verified pin the install trusts, and what is written replaces files that are
     * already on disk rather than adding to them.
     *
     * @return how many members the archive's own entries put in place — a device node or fifo is
     *   never written, and is not counted (see [writeMember])
     * @throws PinnedArchiveUnavailable when the archive cannot be fetched or does not match its pin
     */
    suspend fun overlayFromPinnedArchive(
        onProgress: (Progress) -> Unit = {},
        onWarning: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        ensurePinnedArchive(onProgress)
        val startedAt = System.currentTimeMillis()
        var members = 0
        var replacedDirectories = 0
        var consumed = 0L
        val tarballBytes = tarballFile.length()
        val warnings = mutableListOf<String>()
        openTarStream(tarballFile, DOWNLOAD_BUFFER) { consumed = it }.use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./")
                if (name.isEmpty() || isPreservedMember(name)) continue
                val target = resolveInside(rootfsDir, name)
                if (!entry.isDirectory && target.isDirectory) {
                    deleteTreeNoFollow(target)
                    replacedDirectories++
                }
                if (writeMember(name, target, entry, tar, warnings)) members++
                if (members % PROGRESS_EVERY_ENTRIES == 0) {
                    coroutineContext.ensureActive()
                    onProgress(Progress.Extracting(members, warnings.toList(), tarballFraction(consumed, tarballBytes)))
                }
            }
        }
        onProgress(Progress.Extracting(members, warnings.toList(), 1f))
        warnings.forEach(onWarning)
        diagnostics.record(
            UserspaceDiagnosticCategory.ROOTFS,
            "base system rewritten from the pinned archive",
            durationMs = System.currentTimeMillis() - startedAt,
            detail = "$members member(s) written" +
                (replacedDirectories.takeIf { it > 0 }?.let { ", $it directory/directories replaced" } ?: "") +
                (warnings.firstOrNull()?.let { "; $it" } ?: ""),
        )
        members
    }

    /**
     * Writes one archive member onto [target], replacing whatever is there — the shared body of the
     * two repairs that put the archive's own bytes back into a live rootfs.
     *
     * The link rules are [extract]'s, because they are the same decision made over the same archive:
     * a symlink's target is checked by the guard extraction uses (a tarball whose link points out of
     * the root is not made acceptable by arriving through a repair instead of an install), and a hard
     * link becomes one where the platform allows it and a relative symlink where it does not — see
     * [linkFile]. A device node or fifo is skipped for the reason the extractor skips it: proot binds
     * the device's own `/dev`, and creating them needs privileges the app does not have.
     *
     * @return whether the member is in place afterwards, which both callers report and count: a
     *   device node is never written, and a hard link whose source is not in the rootfs cannot be
     *   made — and neither of those is a member that came back, however the entry was visited.
     */
    private fun writeMember(
        name: String,
        target: File,
        entry: TarArchiveEntry,
        tar: TarArchiveInputStream,
        warnings: MutableList<String>,
    ): Boolean = when (entry.linkFlag) {
        // `mkdirs()` answers false for a directory that already exists, which is the common case in
        // a repair and not a failure: the member's meaning is "this directory exists", and it does.
        TarArchiveEntry.LF_DIR -> target.mkdirs() || target.isDirectory
        TarArchiveEntry.LF_SYMLINK -> {
            target.parentFile?.mkdirs()
            resolveLinkInsideRoot(rootfsDir, name, entry.linkName)
            target.delete()
            deleteTreeNoFollow(target)
            java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
            true
        }
        TarArchiveEntry.LF_LINK -> {
            target.parentFile?.mkdirs()
            val source = resolveInside(rootfsDir, entry.linkName)
            target.delete()
            deleteTreeNoFollow(target)
            if (linkFile(target, source)) {
                true
            } else if (source.isFile) {
                // The platform refused the link (see [linkFile]): the source's bytes, as one copy.
                source.copyTo(target, overwrite = true)
                true
            } else {
                warnings += "hard link $name could not be made: ${entry.linkName} is not in the rootfs"
                false
            }
        }
        TarArchiveEntry.LF_NORMAL, 0.toByte() -> {
            target.parentFile?.mkdirs()
            // A *directory* standing where the archive names a file is the one obstruction
            // `File.delete()` cannot clear — and a repair that stopped there would leave the
            // member exactly as damaged as it found it. The tree goes, because the archive's own
            // bytes cannot be written over it any other way; deleteTreeNoFollow treats a link as
            // the entry it is, so nothing outside [rootfsDir] is ever reached through one.
            target.delete()
            deleteTreeNoFollow(target)
            target.outputStream().use { output -> tar.copyTo(output) }
            applyMode(target, entry.mode)
            true
        }
        // A device node, a fifo, or a type this archive has no business carrying: nothing is created,
        // so nothing came back.
        else -> false
    }

    /**
     * Whether the installed rootfs holds the thing [name] names, as the kind of thing the archive
     * says it is.
     *
     * Three questions, and deliberately not a fourth. Presence, kind, and — for a member the archive
     * marks executable — the executable bit: those are the three states a file can be in that make
     * dpkg's `'<program>' not found in PATH or not executable` true, and every one of them is one
     * lookup. What is *not* asked is whether the bytes match, and that is the deliberate part: a
     * rootfs the user has run `apt-get upgrade` in carries files that legitimately differ from the
     * pin, so a size or hash comparison would report a healthy, updated system as damaged — and the
     * repair it triggered would rewrite that system's base for no fault. Bytes that are wrong in a
     * way that matters are the health probe's and the package manager's business; this answers for
     * files.
     *
     * A path that cannot even be resolved inside the root (a link that loops, one that climbs out)
     * counts as damage rather than as an error: it is a name no program in the guest can reach
     * either, which is the same verdict for the same reason [UbuntuDistributionManager]'s
     * essential-programs check reaches it.
     */
    private fun memberIsPresent(paths: RootfsPaths, name: String, entry: TarArchiveEntry): Boolean =
        runCatching {
            when {
                entry.isDirectory -> paths.hostPath("/$name").isDirectory
                entry.isSymbolicLink -> {
                    // The link itself, not what it points at: usrmerge's `/bin -> usr/bin` re-pointed
                    // elsewhere would break every path through it while still "existing", and the
                    // target has its own entry to answer for.
                    val link = leafInside(paths, name)
                    java.nio.file.Files.isSymbolicLink(link.toPath()) &&
                        runCatching { java.nio.file.Files.readSymbolicLink(link.toPath()).toString() }
                            .getOrNull() == entry.linkName
                }
                // A hard link is present when its source is: the extraction may have spelled it as a
                // symlink (see linkFile), which hostPath follows, and a dangling one is damage here
                // exactly as it is to the program that opens it.
                entry.isLink || entry.isFile -> {
                    val file = paths.hostPath("/$name")
                    file.isFile && (entry.mode and EXECUTABLE_BITS == 0 || file.canExecute())
                }
                // Device nodes, fifos: never created by any of this app's extractors, so their
                // absence is not damage.
                else -> true
            }
        }.getOrDefault(false)

    /**
     * The host file [name] names, with a link at the last component left unresolved — see
     * [memberIsPresent], which is asking about the link itself. The components *above* it resolve as
     * everywhere else, so `sbin/ldconfig` is asked about as the file behind the `/sbin` link, which
     * is the file the guest reaches.
     */
    private fun leafInside(paths: RootfsPaths, name: String): File {
        val cut = name.lastIndexOf('/')
        if (cut < 0) return File(paths.hostPath("/"), name)
        return File(paths.hostPath("/" + name.substring(0, cut)), name.substring(cut + 1))
    }

    /**
     * Whether [name] is a member no repair ever writes, whoever asks and however deep the repair
     * goes.
     *
     * Two reasons, two rules. `/home` and `/root` are the user's — their projects, their shell
     * history, their keys — and replacing them with a base image's version would be a repair that
     * loses data. `/var/lib/dpkg` is the *record* of what is installed: the archive's copy of it
     * describes a system with nothing beyond the base packages, so writing it back would untrack
     * every package the user ever installed while leaving those packages' files on disk, which is a
     * worse state than the one being repaired. `/var/lib/apt`, `/var/cache`, `/var/log` and `/tmp`
     * are the same fact in smaller ways: state, not system, and state the guest is writing to while
     * this runs. `/etc/ssh` stays because a host key regenerated by a repair is a repair that breaks
     * every `known_hosts` entry pointing at this userspace.
     *
     * The preserved trees are invisible to [inspectAgainstPinnedArchive] as well as to the overlay,
     * so what a repair reports and what it fixes are the same set of names.
     */
    private fun isPreservedMember(name: String): Boolean =
        PRESERVED_MEMBERS.any { name == it || name.startsWith("$it/") }

    /** The fraction of the tarball read, or null when the platform will not report its length. */
    private fun tarballFraction(consumed: Long, total: Long): Float? =
        if (total > 0L) (consumed.toFloat() / total).coerceIn(0f, 1f) else null

    private fun verifyFileSha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        return actual == expected
    }

    companion object {
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val PROGRESS_EVERY_ENTRIES = 200

        /** The mode bits the archive's own entries carry for "this is a program" (0111). */
        private const val EXECUTABLE_BITS = 0b001_001_001

        /**
         * The trees no repair writes, however deep it goes — see [isPreservedMember], which is where
         * the reasoning lives. Names are archive-relative and without a leading `/`, the way the
         * tarball spells them.
         */
        private val PRESERVED_MEMBERS = listOf(
            // The user's own: their projects, their keys, their history.
            "home",
            "root",
            // The record of what is installed, and the state the package tools keep beside it.
            "var/lib/dpkg",
            "var/lib/apt",
            "var/cache",
            // State the guest is writing to while a repair runs.
            "var/log",
            "var/tmp",
            "tmp",
            "run",
            // A regenerated host key would invalidate every known_hosts entry pointing at this
            // userspace, which is data loss outside the rootfs.
            "etc/ssh",
        )

        /** The tarball plus this many multiples of it: the extracted rootfs and apt's working space. */
        private const val NEEDED_TARBALL_MULTIPLE = 5L

        /** Room for everything else the app stores, on top of the userspace's own needs. */
        private const val FREE_SPACE_HEADROOM_BYTES = 600L * 1024 * 1024

        /**
         * The extraction ceiling when the pin carries no size (a hand-built distro): enough for a
         * real rootfs, small enough that a gzip bomb dies long before the disk does.
         */
        private const val DEFAULT_EXTRACTION_BUDGET_BYTES = 500L * 1024 * 1024

        private const val MIB = 1024L * 1024
    }
}

/**
 * What a rootfs looks like measured against the archive it was installed from: how many members were
 * examined, and which of them are not there in the shape the archive describes.
 *
 * [damaged] holds archive-relative names — `usr/bin/rm`, not `/usr/bin/rm` — because that is what
 * restores them ([RootfsInstaller.restoreFromPinnedTarball]) and what the diagnostic ring records;
 * translating in either direction at each call site is how a name and the thing it names drift apart.
 *
 * The set is a *lower* bound on what is wrong, by construction: [RootfsInstaller.inspectAgainstPinnedArchive]
 * asks whether each member is present and of the right kind, never whether its bytes are the
 * archive's, so a base system whose `/lib/x86_64-linux-gnu/libc.so.6` has been replaced by something
 * else scans as whole. That is deliberate — see the scan's own doc for why a byte comparison would
 * condemn a rootfs that has legitimately been upgraded — and it is why the repair ladder has a rung
 * that rewrites the base system whether or not this found anything.
 */
data class RootfsIntegrity(
    /** How many archive members were looked at — the preserved trees are not among them. */
    val examined: Int,
    /** The members that are missing, of the wrong kind, or not executable where the archive says so. */
    val damaged: Set<String>,
) {
    /** Whether every member examined is where the archive says it is, as what it says it is. */
    val whole: Boolean get() = damaged.isEmpty()

    /** One line for the log and the failure message; [limit] names members, then counts the rest. */
    fun describe(limit: Int = 6): String {
        if (damaged.isEmpty()) return "$examined member(s) examined, all present"
        val named = damaged.take(limit.coerceAtLeast(0)).joinToString(", ")
        val rest = damaged.size - limit.coerceAtLeast(0)
        return "$examined member(s) examined, ${damaged.size} missing or wrong: $named" +
            (if (rest > 0) " and $rest more" else "")
    }
}

/**
 * The pinned rootfs archive cannot be obtained, or is not the archive the pin describes.
 *
 * Typed rather than a bare [IOException] because it is the one failure a repair must *not* answer by
 * rebuilding: the bytes came from the network, and every rung that could still run needs them. An
 * offline phone, a mirror that answers 503, a truncated transfer or a checksum that does not match
 * the pin all land here — and the honest response to all four is to stop and say so, not to wipe a
 * user's base system to no purpose. The repair ladder keys on exactly this type to tell the
 * difference between "this rootfs is broken" and "this rootfs cannot be repaired *right now*".
 */
class PinnedArchiveUnavailable(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** A byte-range-progressing HTTP(S) GET; an interface so tests never touch the network. */
fun interface HttpDownloader {
    /**
     * Downloads [url] into [target], reporting each [onChunk] with the bytes received so far and
     * the total when the server names one (0 when it does not).
     */
    fun download(url: String, target: File, onChunk: (received: Long, total: Long) -> Unit)
}

/** The production downloader, on `HttpsURLConnection`. */
class UrlConnectionDownloader : HttpDownloader {
    override fun download(url: String, target: File, onChunk: (received: Long, total: Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("downloading $url failed: HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onChunk(received, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
