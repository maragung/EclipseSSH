package dev.eclipse.ssh.archive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.eclipse.ssh.data.fs.FileSystemProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The View Archive browser's controller: one remote archive, from "open" to "close".
 *
 * The screen renders [state]; this class owns everything stateful underneath it: the byte source
 * and its SFTP channel, the scan job, the changed-on-server watcher, and the password the user
 * offered (held in memory for the archive's lifetime only, exactly as the requirement's
 * "never stored permanently without consent" reads - closing the archive forgets it).
 *
 * Every rule the epic states in prose becomes a structural fact here:
 *  - the listing is a scan over the byte source (never a download; the source itself refuses to
 *    be one);
 *  - a server change is a flag on [ArchiveUiState.Ready], never an action on the tree;
 *  - the watcher re-stats through the provider and compares against [ArchiveValidation] with the
 *    unknown-is-not-different rule;
 *  - cancel aborts the scan through the engine's progress callback and closes the channel.
 */
class ArchiveBrowserState(
    /** The archive's name and the format it was opened as. */
    val archiveName: String,
    val format: ArchiveReader.Format,
    /** Reads the archive's bytes where they are. Closed exactly once, by [close]. */
    private val source: ArchiveByteSource,

    /**
     * The byte source for read-throughs after the scan: the preview's range read and the extract
     * path both fetch entry data through this, on the same channel the scan opened.
     *
     * Exposed rather than wrapping a readEntry method here, because [ArchiveReader] already owns
     * the format dispatch - a second copy of it in this class would be the one place the two
     * vocabularies could drift apart.
     */
    fun sourceForReading(): ArchiveByteSource = source,
    /** The provider the archive was listed from - the watcher stats through it. */
    private val provider: FileSystemProvider,
    /** The archive's provider-opaque path, for the properties sheet and watcher stat. */
    val remotePath: String,
    /** Where the scan and watcher coroutines live; the caller's (screen's) scope. */
    private val scope: CoroutineScope,
) {
    /** The state the screen renders. Written only from the scan/watcher paths. */
    var state: ArchiveUiState by mutableStateOf(ArchiveUiState.Loading(null))
        private set

    /** The password the user offered for an encrypted archive; null until then, forgotten at close. */
    private var password: String? = null

    private var scanJob: Job? = null
    private var watchJob: Job? = null
    private var closed = false

    init {
        startScan()
    }

    /** Cancels the in-flight scan. The screen's Cancel button; the source closes with [close]. */
    fun cancelScan() {
        scanJob?.cancel()
    }

    /** Retries after a failure: a fresh scan over the same source. */
    fun retry() {
        if (closed) return
        startScan()
    }

    /** Offers the password for an encrypted archive and rescans with it. */
    fun unlock(password: String) {
        if (closed) return
        this.password = password
        startScan()
    }

    /**
     * Reloads after a server change or a refresh: the old tree stands until the new scan lands,
     * because an empty screen in place of a stale one is a worse answer than a stale one with a
     * banner - the banner is the notification, the user chose the reload.
     */
    fun reload() {
        if (closed) return
        startScan()
    }

    /** Dismisses the changed-on-server banner without reloading ("continue with what's shown"). */
    fun dismissServerChange() {
        val s = state
        if (s is ArchiveUiState.Ready && s.serverChanged) {
            state = s.copy(serverChanged = false)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scanJob?.cancel()
        watchJob?.cancel()
        // The channel is the one resource this class owns outright; closing it on a scope launch
        // means close() itself never suspends, so the screen can call it straight from
        // onDismissRequest.
        scope.launch { source.close() }
    }

    private fun startScan() {
        scanJob?.cancel()
        watchJob?.cancel()
        state = ArchiveUiState.Loading(null)
        scanJob = scope.launch {
            try {
                val stats = ArchiveValidation(
                    size = source.size,
                    modifiedEpochMillis = provider.stat(remotePath)?.modifiedEpochMillis,
                )
                val entries = ArchiveReader.list(format, source) { progress ->
                    state = ArchiveUiState.Loading(progress)
                }
                if (!isActive) return@launch
                state = ArchiveUiState.Ready(
                    tree = ArchiveTree(entries),
                    format = format,
                    stats = stats,
                )
                startWatcher(stats)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by the user or by close(): no state change. The loading screen the
                // user cancelled from is being torn down with the browser itself.
                throw e
            } catch (e: Throwable) {
                state = archiveStateFor(e)
            }
        }
    }

    /**
     * Watches the remote archive for changes while it is open.
     *
     * Every 10 s (the same cadence the editor's watcher uses - slow enough not to be traffic,
     * fast enough to catch a rewrite during a browsing session) the archive is re-statted; when
     * the stat disagrees with the baseline the banner is raised and the watcher STOPS - one
     * notification, not one per tick.
     */
    private fun startWatcher(baseline: ArchiveValidation) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (closed) return@launch
                val fresh = runCatching { provider.stat(remotePath) }.getOrNull()
                val differs = fresh == null || baseline.differsFrom(
                    size = fresh.size,
                    modifiedEpochMillis = fresh.modifiedEpochMillis,
                )
                if (differs) {
                    val s = state
                    if (s is ArchiveUiState.Ready && !s.serverChanged) {
                        state = s.copy(serverChanged = true)
                    }
                    return@launch
                }
            }
        }
    }
}

/** What MainActivity holds while one archive is being browsed: the target plus its controller. */
class ArchiveTarget(
    val entry: dev.eclipse.ssh.data.fs.FsEntry,
    val browser: ArchiveBrowserState,
)
