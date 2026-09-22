package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Backing up and restoring the installed userspace: the two operations that move a whole rootfs
 * across the app's own boundary, as a gzipped tar.
 *
 * It exists because both of them are the *state machine's* business rather than the file layer's.
 * An export has to be one thing at a time with an install — a repair rewriting the tree while it is
 * being read would produce an archive of nothing anyone ever had — and an import has to be an
 * unspoken promise kept: the userspace that is installed now is replaced only once a complete,
 * verified copy of the new one is on disk, so a failed or cancelled import leaves the user exactly
 * where they were. [RootfsArchive] does the bytes; this class does the ordering, the lock, and the
 * handoff back into the pipeline that turns a tree into a working userspace.
 *
 * ## Why it does its own staging and its own swap
 * [RootfsInstaller] has both already, and neither is reachable from here: its staging directory is
 * `rootfs.staging` and its swap is private. That is not an accident of visibility to be worked
 * around — `RootfsInstaller.isExtracted()` is `!stagingDir.exists() && rootfs has a bin`, so an
 * import that unpacked into *that* directory would, for the length of the import, make an installed
 * userspace report itself as not extracted, and a concurrent install would delete the staging tree
 * out from under it. So an import gets its own directory (`rootfs.import`) and its own swap, and
 * the two can never be confused for one another. The install lock below is the belt to that
 * suspenders: no install or repair can be running while either happens.
 *
 * ## The handoff
 * A swapped-in tree is not an installed userspace. The archive carries the *exporting* device's app
 * uid in `/etc/passwd`, its resolvers and its apt mirror, and none of those belong on this device;
 * the setup pipeline is what fixes all three, and it is the same pipeline a repair runs. So the
 * last thing an import does is hand the tree to [LinuxUserspaceManager]: `install()` when nothing
 * was installed (which resolves to setup only, because the rootfs is now extracted — no download,
 * no re-extraction), `repair()` when something was. Both end in the health probe, so an imported
 * userspace is "installed" only when it has been proven to work, exactly like a downloaded one.
 *
 * The lock is released *before* that handoff, because the manager takes it itself — the one
 * sequence that would otherwise deadlock on its own bookkeeping.
 *
 * @param rootDir the userspace root (`filesDir/linux`), the same one every other member of the
 *   graph was built over
 */
class RootfsTransfer(
    private val rootDir: File,
    private val manager: LinuxUserspaceManager,
    private val distro: LinuxDistro,
    private val diagnostics: UserspaceDiagnostics = UserspaceDiagnostics(),
    private val storage: RuntimeStorageManager = RuntimeStorageManager(rootDir),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Where an import unpacks, deliberately not the installer's `rootfs.staging`; see the class doc.
     */
    val stagingDir: File get() = File(rootDir, IMPORT_STAGING)

    private val _state = MutableStateFlow<RootfsTransferState>(RootfsTransferState.Idle)

    /** What a transfer is doing right now, for the screen that shows its progress. */
    val state: StateFlow<RootfsTransferState> = _state

    /**
     * Whether the last import got as far as replacing the installed rootfs.
     *
     * A cancelled import is delivered to its caller as a `CancellationException` from either side of
     * that line, and the two are not the same news: before it, the userspace that was installed is
     * the one still installed; after it, the archive's is, and the setup pipeline that makes it work
     * on this device has already run. The caller is the one that has to say which, and this is the
     * only place that knows. Volatile because it is written on the transfer's thread and read from
     * the screen's; reset at the start of every import, so a refusal cannot inherit the last one's.
     */
    @Volatile
    var importSwapped: Boolean = false
        private set

    /**
     * Writes the installed rootfs to [output] — the document the user picked — as a gzipped tar.
     *
     * [output] is the caller's stream, opened and closed by whoever owns the document, because this
     * class must not know what a `content://` URI is: it writes to an [OutputStream] and anything
     * Android-shaped about the destination stays above it.
     *
     * Refused while nothing is installed (there is no tree), and while an install or a stop is
     * running (the state machine is mid-sentence about what is on disk). Called while the userspace
     * is *running*, which is allowed and is the ordinary case: a session's files are on the same
     * disk the export reads, and the archive is a snapshot of the tree as it stands.
     *
     * @throws IllegalStateException when there is nothing to export or an operation is in flight
     * @throws IOException when the document cannot be written or the rootfs cannot be read
     */
    suspend fun exportTo(output: OutputStream): Unit = withContext(Dispatchers.IO) {
        val current = manager.state.value
        check(current !is LinuxUserspaceState.NotInstalled) {
            "there is no userspace installed to export"
        }
        check(current !is LinuxUserspaceState.Installing && current !is LinuxUserspaceState.Stopping) {
            "a userspace is $current - wait for it to finish before exporting"
        }
        storage.requireReady()
        // The install lock, not a second mechanism of our own: an export racing a repair would read
        // a tree that is being rewritten under it, and the lock is what the whole feature already
        // agrees means "one userspace operation at a time".
        storage.acquireInstallLock(distro.id, "export")
        val startedAt = clock()
        try {
            _state.value = RootfsTransferState.Exporting(0, 0, 0)
            val report = RootfsArchive(storage.rootfsDir).export(output) { progress ->
                _state.value =
                    RootfsTransferState.Exporting(progress.bytes, progress.total, progress.entries)
            }
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "rootfs exported",
                durationMs = clock() - startedAt,
                detail = "${report.entries} entries, ${report.bytes / MIB}MB" + warningsDetail(report.warnings),
            )
        } catch (t: Throwable) {
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "rootfs export failed",
                durationMs = clock() - startedAt,
                detail = t.message ?: t.javaClass.simpleName,
            )
            throw t
        } finally {
            storage.releaseInstallLock()
            _state.value = RootfsTransferState.Idle
        }
    }

    /**
     * Replaces whatever is installed with the userspace in [source] — the archive the user picked —
     * and then has it set up for this device.
     *
     * The order is the whole guarantee, so it is worth reading as one:
     *
     *  1. anything running is stopped, because the tree about to be replaced is the one it is
     *     running on, and the manager's stop is the one path that closes those sessions;
     *  2. the archive is unpacked into a staging directory nothing else points at, through the same
     *     guards and budget a pinned install uses;
     *  3. the staging tree is checked for actually being a userspace this device can run;
     *  4. only then is the installed rootfs renamed aside and the staging tree renamed into its
     *     place — the installer's own park-and-rename ordering, mirrored here because a
     *     delete-then-rename would have a window with no rootfs at all in it;
     *  5. the setup pipeline runs over the result.
     *
     * Every failure before step 4 leaves the installed userspace byte-for-byte as it was, and step 4
     * itself either completes or puts the parked tree back. A failure *after* it is a userspace that
     * is installed but not yet working, which is what NeedsRepair exists to name and Repair exists
     * to fix.
     *
     * A cancellation is answered at the same boundary rather than wherever it happens to land: it is
     * taken before the swap — leaving the installed userspace alone — and refused after it, where the
     * tree on disk is already the archive's and the only honest ending is the setup pipeline running
     * to completion. [importSwapped] is which of the two it was.
     *
     * @param archiveBytes the archive's size as the picker reported it, or 0/-1 when the provider
     *   would not say; it is both the progress denominator and the basis of the expansion budget
     * @throws IllegalStateException while an install or a stop is in flight, or while another
     *   process holds the install lock
     * @throws IOException when the archive is unreadable, escapes its directory, expands beyond the
     *   budget, or is not a userspace this device can adopt
     */
    suspend fun importFrom(source: InputStream, archiveBytes: Long): Unit = withContext(Dispatchers.IO) {
        val before = manager.state.value
        check(before !is LinuxUserspaceState.Installing && before !is LinuxUserspaceState.Stopping) {
            "a userspace is $before - wait for it to finish before importing over it"
        }
        storage.requireReady()
        // Taken here rather than inside the try: acquiring a lock someone else holds must not be
        // followed by releasing *their* lock in a finally.
        storage.acquireInstallLock(distro.id, "import")
        val startedAt = clock()
        try {
            importSwapped = false
            if (before !is LinuxUserspaceState.NotInstalled) {
                // From Running this is what closes the live sessions; from Stopped or NeedsRepair it
                // is a no-op that lands on Stopped — which is also what makes the handoff's repair()
                // legal, since repair() accepts nothing else.
                manager.stop()
            }
            deleteTreeNoFollow(stagingDir)
            stagingDir.mkdirs()
            _state.value = RootfsTransferState.Importing(0, archiveBytes, 0)
            val archive = RootfsArchive(stagingDir)
            val report = archive.extract(source, importBudgetBytes(archiveBytes), archiveBytes) { progress ->
                _state.value =
                    RootfsTransferState.Importing(progress.bytes, progress.total, progress.entries)
            }
            val findings = archive.verify(distro)
            if (findings.isNotEmpty()) {
                throw IOException(
                    "this archive cannot be used as a ${distro.displayName} userspace: " +
                        findings.joinToString("; "),
                )
            }
            _state.value = RootfsTransferState.Swapping
            // The point of no return, and the one cancellation check that has to be written out: a
            // cancel that arrived while the last entry was unpacking must be answered *here*, where
            // the installed userspace is still untouched and [importSwapped] is still false, rather
            // than after the swap — where the only honest thing left to do is finish.
            coroutineContext.ensureActive()
            // A snapshot parked by an earlier keep-workspace uninstall is restored by the setup at
            // step 5 — over the workspace this archive has just brought back. The archive's copy is
            // what the user asked for by importing it; the parked one is what they left behind when
            // they uninstalled the userspace that held it, and the newer intent wins.
            val discardedBackup = storage.workspaceBackupFile.delete()
            swapIntoPlace()
            importSwapped = true
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "rootfs imported",
                durationMs = clock() - startedAt,
                detail = "${report.entries} entries, ${report.bytes / MIB}MB" +
                    warningsDetail(report.warnings) +
                    if (discardedBackup) "; discarded the workspace saved by an earlier uninstall" else "",
            )
        } catch (t: Throwable) {
            // A half-unpacked tree is only ever garbage: nothing installed was touched, and the next
            // attempt starts from the first byte. Best-effort, like every cleanup here — the failure
            // that got us here may be the one refusing deletes.
            deleteTreeNoFollow(stagingDir)
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "rootfs import failed",
                durationMs = clock() - startedAt,
                detail = t.message ?: t.javaClass.simpleName,
            )
            throw t
        } finally {
            storage.releaseInstallLock()
            _state.value = RootfsTransferState.Idle
        }
        // Outside the lock, and outside the try: install() and repair() take the install lock
        // themselves, so holding it across this call would refuse our own handoff. NonCancellable for
        // the mirror-image reason: past the swap there is nothing left to abandon, and a setup
        // pipeline interrupted half way would leave a userspace no state names — an imported tree
        // that is not configured for this device. Cancellation therefore lands on one side of the
        // swap or the other, never in the middle of either.
        withContext(NonCancellable) {
            diagnostics.record(
                UserspaceDiagnosticCategory.ROOTFS,
                "imported rootfs adopted",
                detail = "running ${if (before is LinuxUserspaceState.NotInstalled) "install" else "repair"} over it",
            )
            if (before is LinuxUserspaceState.NotInstalled) manager.install() else manager.repair()
        }
    }

    /**
     * What an archive is allowed to unpack to: the same multiple of its own size the pinned install
     * applies to its tarball, against the only quantity this path has. A provider that will not
     * report the size — a pipe, a cloud backend — leaves the fallback standing, which is a ceiling
     * no phone's storage could reach and therefore no protection against a bomb: the honest failure
     * there is the write that runs out of space, with the staging tree reclaimable.
     */
    internal fun importBudgetBytes(archiveBytes: Long): Long =
        if (archiveBytes > 0) archiveBytes * BUDGET_MULTIPLE else FALLBACK_BUDGET_BYTES

    /**
     * Replaces the installed rootfs with the staging tree by rename, parking the old one beside it
     * first.
     *
     * Mirrors [RootfsInstaller]'s own move-into-place, including its ordering and its rollback, and
     * it cannot call it: that method is private, and the reason it is private is that its staging
     * directory is not this one. The parked directory carries the installer's own name for the same
     * reason — "the previous rootfs, still on disk until the new one is in place" is one idea, and a
     * second name for it would be a second thing each path has to remember to reclaim.
     */
    private fun swapIntoPlace() {
        val parked = File(rootDir, PARKED_ROOTFS)
        deleteTreeNoFollow(parked)
        var parkedPrevious = false
        if (storage.rootfsDir.exists()) {
            parkedPrevious = storage.rootfsDir.renameTo(parked)
            if (!parkedPrevious) {
                // Renaming the old root away failed for reasons nothing here can fix; deleting it
                // reopens the window where no rootfs exists, but keeps the import able to proceed.
                deleteTreeNoFollow(storage.rootfsDir)
            }
        }
        if (!stagingDir.renameTo(storage.rootfsDir)) {
            if (parkedPrevious) parked.renameTo(storage.rootfsDir)
            throw IOException("could not move the imported rootfs into place at ${storage.rootfsDir}")
        }
        deleteTreeNoFollow(parked)
    }

    /** A report's warnings as one detail string; empty when there were none. */
    private fun warningsDetail(warnings: List<String>): String =
        if (warnings.isEmpty()) "" else "; ${warnings.size} warning(s): ${warnings.first()}"

    private companion object {
        const val MIB = 1024L * 1024

        /** The staging directory an import unpacks into; see the class doc for why it is not the installer's. */
        const val IMPORT_STAGING = "rootfs.import"

        /** Where the installer parks the rootfs it is replacing, reused so both swaps reclaim alike. */
        const val PARKED_ROOTFS = "rootfs.old"

        /** The installer's own multiple of the pinned tarball's size. */
        const val BUDGET_MULTIPLE = 10L

        /** The ceiling when the archive's own size is unknown; see [importBudgetBytes]. */
        const val FALLBACK_BUDGET_BYTES = 8L * 1024 * 1024 * 1024
    }
}

/**
 * What a rootfs transfer is doing right now, or [Idle] between transfers.
 *
 * Deliberately *not* a new [LinuxUserspaceState]: that interface is the manager's account of what is
 * installed, and a transfer does not change what is installed until the moment it swaps. A second
 * sealed hierarchy that the settings screen draws beside the manager's own keeps the two honest —
 * and it is why [Importing] stops where it does: once the swap has happened the setup pipeline is
 * the manager's Installing state, with the manager's percentage, and drawing a second bar over it
 * would be two accounts of one operation.
 */
sealed interface RootfsTransferState {
    /** Nothing is being transferred. */
    data object Idle : RootfsTransferState

    /** Writing the installed rootfs into the document the user chose; nothing on disk changes. */
    data class Exporting(val bytes: Long, val totalBytes: Long, val entries: Int) : RootfsTransferState

    /**
     * Unpacking the chosen archive into the staging tree. The installed userspace is untouched for
     * the whole of this phase, which is the promise the confirmation dialog makes.
     */
    data class Importing(val bytes: Long, val totalBytes: Long, val entries: Int) : RootfsTransferState

    /** The staging tree is verified; the installed rootfs is being replaced by it. */
    data object Swapping : RootfsTransferState
}

/**
 * How far the transfer has got, 0 to 100, or null while its total is unknown — a document provider
 * that will not report a size, which is the indeterminate bar rather than one at zero. Whole percent
 * for the same reason the install's is: the number beside the bar and the bar itself must be the
 * same fact.
 *
 * Named apart from [LinuxUserspaceState]'s own `percent` deliberately, and not for want of a synonym:
 * both are top-level extension properties in this package, and a screen that shows an install row
 * and a transfer row is exactly the file that would have to import both names and could not.
 */
val RootfsTransferState.percentDone: Int?
    get() = when (this) {
        is RootfsTransferState.Exporting -> percentOf(bytes, totalBytes)
        is RootfsTransferState.Importing -> percentOf(bytes, totalBytes)
        RootfsTransferState.Swapping,
        RootfsTransferState.Idle,
        -> null
    }

private fun percentOf(bytes: Long, totalBytes: Long): Int? =
    if (totalBytes > 0) ((bytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100) else null
