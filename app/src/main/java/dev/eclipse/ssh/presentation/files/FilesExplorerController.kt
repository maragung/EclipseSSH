package dev.eclipse.ssh.presentation.files

import dev.eclipse.ssh.archive.SftpArchiveByteSource
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.data.fs.LocalFileSystemProvider
import dev.eclipse.ssh.data.fs.SftpProviderFactory
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.ssh.SshSessionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The id of the one session that is always present: the device's own storage. */
const val LOCAL_SESSION_ID = "local"

/** How a directory is sorted in the explorer. The UI offers each of these both ways. */
enum class ExplorerSort(val label: String) {
    NAME_ASC("Name A→Z"),
    NAME_DESC("Name Z→A"),
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    LARGEST_FIRST("Largest first"),
}

/** List or grid. The grid earns its place for images and media; everything else reads better as rows. */
enum class ExplorerViewMode { LIST, GRID }

/** One place the explorer can browse: the device's own storage, or one host's SFTP tree. */
data class ExplorerSession(
    val id: String,
    val label: String,
    val isLocal: Boolean,
    /** Whether the host currently holds a live session — drives the dot and the "reconnect first" hint. */
    val live: Boolean,
)

/** What the Files tab is looking at, in one snapshot. */
data class ExplorerState(
    /** Local is always first, and always present — the spec's "local-always-first" rule. */
    val sessions: List<ExplorerSession> = emptyList(),
    val activeSessionId: String = LOCAL_SESSION_ID,
    /** The entries of the current directory (or the search results when [searchQuery] is set). */
    val entries: List<FsEntry> = emptyList(),
    /** The current directory's provider path, or null before the first listing. */
    val path: String? = null,
    /** Whether [path] has a parent the explorer can go up to. */
    val canGoUp: Boolean = false,
    /** The current directory's name, for the bar between the crumbs and the list. */
    val title: String = "This device",
    /**
     * The path bar's steps, root first. Built by the controller rather than the UI because only it
     * knows where a local trail came from: a remote path is segmented where it was listed, a local
     * one is the navigation history itself. The UI may collapse an over-long trail — see
     * [ellipsizeCrumbs] — but never invents a crumb.
     */
    val crumbs: List<Crumb> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Set while a search is running or showing; null during ordinary browsing. */
    val searchQuery: String? = null,
    val sort: ExplorerSort = ExplorerSort.NAME_ASC,
    val viewMode: ExplorerViewMode = ExplorerViewMode.LIST,
    /** Paths of the selected entries, always belonging to the listing they were made in. */
    val selection: Set<String> = emptySet(),
    /** Whether the active provider can set POSIX permissions; hides the action when it cannot. */
    val supportsPermissions: Boolean = false,
    /** Whether the active session is the device's own storage. */
    val isLocal: Boolean = true,
)

/**
 * The Files Explorer's state and its every action, over [FileSystemProvider]s.
 *
 * One controller rather than a second ViewModel, because the explorer is not a screen with a life of
 * its own — it is the Files tab of the main one, and it needs what only that screen's context knows:
 * which hosts are live, what the terminal is doing. A second ViewModel beside [MainViewModel] could
 * see neither without a bridge of shared flows, and the bridge would be this class with worse names.
 *
 * Every operation runs through the active session's provider, so the UI never learns whether a path
 * is a `content://` URI or a POSIX string. Errors surface as [ExplorerState.error] strings a person
 * can read, never as a crash and never as a silent nothing.
 */
@Singleton
class FilesExplorerController @Inject constructor(
    private val localProvider: LocalFileSystemProvider,
    private val sftpFactory: SftpProviderFactory,
    private val sessionStore: SshSessionStore,
    private val hostRepository: HostRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ExplorerState())
    val state: StateFlow<ExplorerState> = _state.asStateFlow()

    /**
     * The local session's breadcrumb trail. SAF document URIs cannot be split into crumbs the way a
     * POSIX path can, so the trail is recorded as the user descends: each navigation appends, "up"
     * drops the last step, and a freshly picked root starts it over. Paths stay opaque throughout.
     */
    private val localTrail = ArrayDeque<Pair<String, String>>() // path to display name

    /**
     * What each session was doing the last time it was open: its directory, and with it the sort and
     * view mode it was using. The spec's "every session remembers where it was" — Local → Server A →
     * Server B → Local must land each one back in its own folder, not back in home. Recorded after
     * every listing, so it is always a directory that provably listed.
     */
    private data class SessionMemory(
        val path: String,
        val sort: ExplorerSort,
        val viewMode: ExplorerViewMode,
        /** The local session's trail, without which its remembered path could not be shown as crumbs. */
        val trail: List<Pair<String, String>>,
    )

    private val memory = mutableMapOf<String, SessionMemory>()

    /** The provider for a session id, or null when the session is not one this controller made. */
    fun providerFor(sessionId: String): FileSystemProvider? = when {
        sessionId == LOCAL_SESSION_ID -> localProvider
        sessionId.startsWith("sftp:") -> {
            val hostId = sessionId.removePrefix("sftp:")
            lastHosts.firstOrNull { it.id == hostId }?.let(sftpFactory::forHost)
        }

        else -> null
    }

    /** The hosts whose sessions were last built from, so [providerFor] can find usernames. */
    private var lastHosts: List<HostProfile> = emptyList()

    /**
     * The ranged-read view of one remote archive on the session the explorer is browsing.
     *
     * The sheet's View Archive row asks for this when it is clicked, not when the sheet opens,
     * because the factory makes a byte source, not a channel: the channel pair opens on the first
     * read and is owned until [SftpArchiveByteSource.close] — a cancelled open (sheet closed before
     * the scan starts, another archive chosen) that never opened the file leaves nothing behind.
     *
     * Null when the session is the local one or one this controller never made, for exactly the
     * reasons [providerFor] gives — View Archive is a remote browsing verb, and pretending a local
     * URI is an SFTP path would be the one dishonest answer here.
     */
    fun archiveSourceFor(sessionId: String, remotePath: String, size: Long): SftpArchiveByteSource? {
        if (!sessionId.startsWith("sftp:")) return null
        val hostId = sessionId.removePrefix("sftp:")
        val host = lastHosts.firstOrNull { it.id == hostId } ?: return null
        return sftpFactory.archiveSource(host, remotePath, size)
    }

    /** Rebuilds the session list — local first, then every saved host, live or not. */
    fun refreshSessions() {
        scope.launch {
            val hosts = runCatching { hostRepository.hosts.first() }.getOrDefault(emptyList())
            lastHosts = hosts
            val live = withContext(Dispatchers.IO) { sessionStore.liveHostIds() }
            val local = ExplorerSession(LOCAL_SESSION_ID, "This device", isLocal = true, live = true)
            val remote = hosts
                .filter { it.id != LOCAL_SESSION_ID }
                .map { host ->
                    ExplorerSession(
                        id = "sftp:${host.id}",
                        label = host.name,
                        isLocal = false,
                        live = host.id in live,
                    )
                }
            _state.update { it.copy(sessions = listOf(local) + remote) }
        }
    }

    /**
     * Opens a session: in the folder it was last browsing, or its home the first time — the device's
     * own storage in the last-chosen (or freshly picked) root, a host in its home directory.
     */
    fun openSession(sessionId: String) {
        scope.launch {
            val provider = providerFor(sessionId) ?: return@launch
            val remembered = memory[sessionId]
            _state.update {
                it.copy(
                    activeSessionId = sessionId,
                    isLocal = sessionId == LOCAL_SESSION_ID,
                    selection = emptySet(),
                    searchQuery = null,
                    error = null,
                    crumbs = emptyList(),
                    supportsPermissions = provider.supportsPermissions,
                    // Preferences belong to the session being opened, not to whichever one set them last.
                    sort = remembered?.sort ?: ExplorerSort.NAME_ASC,
                    viewMode = remembered?.viewMode ?: ExplorerViewMode.LIST,
                )
            }
            val start = try {
                when {
                    sessionId == LOCAL_SESSION_ID -> localProvider.homePath()
                    else -> provider.homePath()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A host that is saved but not connected — its chip is deliberately still offered, so
                // this is a state to report, never an uncaught exception on the main scope.
                updateIfCurrent(sessionId) {
                    it.copy(loading = false, error = error.message ?: "Could not open the session")
                }
                return@launch
            }
            if (start == null) {
                // Local with no folder granted yet: an honest empty state, not an error — the bar's
                // "Pick folder" action is the way on from here, and the picker is the user's to tap.
                updateIfCurrent(sessionId) {
                    it.copy(entries = emptyList(), path = null, canGoUp = false, crumbs = emptyList(), loading = false)
                }
                return@launch
            }
            if (sessionId == LOCAL_SESSION_ID) {
                localTrail.clear()
                localTrail.addLast(start to "This device")
            }
            val resume = remembered?.path
            if (resume != null && resume != start) {
                if (sessionId == LOCAL_SESSION_ID) {
                    localTrail.clear()
                    remembered.trail.forEach { localTrail.addLast(it) }
                }
                list(resume)
                if (_state.value.error != null) {
                    // The remembered folder is gone or unreadable — home is the honest place to land,
                    // and the failure that explains the detour stays on screen as the error.
                    if (sessionId == LOCAL_SESSION_ID) {
                        localTrail.clear()
                        localTrail.addLast(start to "This device")
                    }
                    list(start)
                }
            } else {
                list(start)
            }
        }
    }

    fun navigate(path: String, title: String) {
        if (_state.value.isLocal) {
            val index = localTrail.indexOfFirst { it.first == path }
            if (index >= 0) {
                // An ancestor the user jumped back to: the trail ends there, rather than growing a
                // second copy of a folder already on it.
                while (localTrail.size > index + 1) localTrail.removeLast()
            } else {
                localTrail.addLast(path to title)
            }
        }
        scope.launch { list(path) }
    }

    fun goUp() {
        val current = _state.value
        if (current.searchQuery != null) {
            clearSearch()
            return
        }
        if (current.isLocal) {
            // The trail is the only honest parent a document URI has.
            if (localTrail.size > 1) localTrail.removeLast()
            val parent = localTrail.lastOrNull()?.first ?: return
            scope.launch { list(parent) }
        } else {
            val path = current.path ?: return
            val provider = providerFor(current.activeSessionId) ?: return
            scope.launch {
                val parent = provider.parentPath(path)
                if (parent != null) list(parent) else list(path)
            }
        }
    }

    fun refresh() {
        val current = _state.value
        val path = current.path
        if (current.searchQuery != null) {
            search(current.searchQuery)
        } else if (path != null) {
            scope.launch { list(path) }
        }
    }

    fun setSort(sort: ExplorerSort) {
        _state.update { it.copy(sort = sort) }
        _state.value.path?.let { rememberSession(it) }
        applySort()
    }

    fun setViewMode(mode: ExplorerViewMode) {
        _state.update { it.copy(viewMode = mode) }
        _state.value.path?.let { rememberSession(it) }
    }

    fun toggleSelected(path: String) {
        _state.update {
            val next = if (path in it.selection) it.selection - path else it.selection + path
            it.copy(selection = next)
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun search(query: String) {
        if (query.isBlank()) {
            clearSearch()
            return
        }
        scope.launch {
            val current = _state.value
            val sessionId = current.activeSessionId
            val root = current.path ?: return@launch
            val provider = providerFor(sessionId) ?: return@launch
            updateIfCurrent(sessionId) { it.copy(loading = true, searchQuery = query, error = null) }
            try {
                val results = provider.search(root, query.trim(), MAX_SEARCH_RESULTS)
                updateIfCurrent(sessionId) { it.copy(entries = results, loading = false, selection = emptySet()) }
                applySort()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(sessionId) { it.copy(loading = false, error = error.message ?: "The search failed") }
            }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = null) }
        val path = _state.value.path
        if (path != null) scope.launch { list(path) }
    }

    /**
     * Writes state only when [sessionId] is still the active session.
     *
     * Every operation captures its session when it starts and reports when its channel returns, and a
     * user can switch sessions in between — an in-flight listing that landed anyway would put one
     * server's rows on another server's screen, and worse, its `path`, which every row action
     * resolves against. Dropped rather than applied: the session it belonged to will be listed again
     * the moment the user returns to it.
     */
    private fun updateIfCurrent(sessionId: String, transform: (ExplorerState) -> ExplorerState) {
        _state.update { if (it.activeSessionId == sessionId) transform(it) else it }
    }

    /** The one place a listing happens, so loading, error and empty states cannot disagree. */
    private suspend fun list(path: String) {
        val sessionId = _state.value.activeSessionId
        val provider = providerFor(sessionId) ?: return
        updateIfCurrent(sessionId) { it.copy(loading = true, error = null, path = path, selection = emptySet()) }
        try {
            val entries = provider.list(path)
            val title = if (sessionId == LOCAL_SESSION_ID) {
                localTrail.lastOrNull()?.second ?: "This device"
            } else {
                path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
            }
            val crumbs = if (sessionId == LOCAL_SESSION_ID) {
                localTrail.map { (trailPath, name) -> Crumb(name, trailPath) }
            } else {
                segmentPosixPath(path)
            }
            // Resolved before the state write because parentPath is suspend and updateIfCurrent's
            // transform is not — and because a "can I go up" answer that arrived after the listing
            // would be stale by the time it rendered anyway.
            val canGoUp = provider.parentPath(path) != null ||
                (sessionId == LOCAL_SESSION_ID && localTrail.size > 1)
            updateIfCurrent(sessionId) {
                it.copy(
                    entries = entries,
                    loading = false,
                    canGoUp = canGoUp,
                    title = title,
                    crumbs = crumbs,
                )
            }
            applySort()
            rememberSession(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateIfCurrent(sessionId) { it.copy(loading = false, error = error.message ?: "Could not list the directory") }
        }
    }

    /** Writes the active session's whereabouts into [memory]; called after every listing. */
    private fun rememberSession(path: String) {
        val state = _state.value
        memory[state.activeSessionId] = SessionMemory(
            path = path,
            sort = state.sort,
            viewMode = state.viewMode,
            trail = if (state.activeSessionId == LOCAL_SESSION_ID) localTrail.toList() else emptyList(),
        )
    }

    private fun applySort() {
        val sort = _state.value.sort
        val sorted = when (sort) {
            ExplorerSort.NAME_ASC -> _state.value.entries.sortedWith(
                compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
            )

            ExplorerSort.NAME_DESC -> _state.value.entries.sortedWith(
                compareByDescending<FsEntry> { it.isDirectory }.thenByDescending { it.name.lowercase() },
            )

            ExplorerSort.NEWEST_FIRST -> _state.value.entries.sortedWith(
                compareByDescending<FsEntry> { it.modifiedEpochMillis ?: 0L },
            )

            ExplorerSort.OLDEST_FIRST -> _state.value.entries.sortedWith(
                compareBy<FsEntry> { it.modifiedEpochMillis ?: 0L },
            )

            ExplorerSort.LARGEST_FIRST -> _state.value.entries.sortedWith(
                compareByDescending<FsEntry> { it.size ?: 0L },
            )
        }
        _state.update { it.copy(entries = sorted) }
    }

    /**
     * Runs one mutating operation and refreshes the listing it changed.
     *
     * One shape for every action, because the interesting failure modes are shared: the operation can
     * fail on the backend (reported, listing left standing), and the refresh can fail after a
     * *successful* operation (also reported — the file did change, the pane just could not say so).
     */
    fun run(operation: String, block: suspend (FileSystemProvider) -> Unit) {
        scope.launch {
            val current = _state.value
            val sessionId = current.activeSessionId
            val provider = providerFor(sessionId)
            if (provider == null) {
                _state.update { it.copy(error = "This session is no longer available") }
                return@launch
            }
            try {
                block(provider)
                // The refresh is skipped when the user has moved on to another session: listing
                // `current.path` there would send one server's path to another server's provider,
                // and the session it belongs to re-lists its remembered directory on return.
                if (_state.value.activeSessionId == sessionId) {
                    val path = current.path
                    if (path != null && current.searchQuery == null) list(path) else refresh()
                    report("$operation: done")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(sessionId) { it.copy(error = "$operation failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    /**
     * Creates an empty file in the current directory and returns it, so the caller can drop it
     * straight into the editor. Null when it failed — the reason is already on [ExplorerState.error].
     *
     * Not another [run] because the caller needs the created entry, and `run` deliberately tells
     * callers nothing: its operations end with "done", while this one's result *is* the point.
     */
    suspend fun createFileHere(name: String): FsEntry? {
        val current = _state.value
        val sessionId = current.activeSessionId
        val parent = current.path ?: return null
        val provider = providerFor(sessionId) ?: return null
        return try {
            val entry = provider.createFile(parent, name)
            if (_state.value.activeSessionId == sessionId) list(parent)
            entry
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateIfCurrent(sessionId) { it.copy(error = "New file failed: ${error.message ?: "unknown error"}") }
            null
        }
    }

    /** The folder the user just picked in the SAF picker becomes Local's root, and is remembered. */
    fun setLocalRoot(uri: android.net.Uri) {
        scope.launch {
            localProvider.setRoot(uri)
            localTrail.clear()
            localTrail.addLast(uri.toString() to "This device")
            // The old root's remembered directory must not pull the session back into a folder the
            // user has just replaced — picking a folder is a start-over, not a resume.
            memory.remove(LOCAL_SESSION_ID)
            openSession(LOCAL_SESSION_ID)
        }
    }

    private fun report(message: String) {
        // Kept separate from [ExplorerState.error], which is for failures; this is the status line.
        _state.update { it.copy(error = null) }
        lastStatus = message
    }

    /** The most recent operation's outcome, shown once; a one-slot status rather than a log. */
    var lastStatus: String? = null
        private set

    private companion object {
        /**
         * Bounded because a search walks a tree the provider does not control; results past this are
         * cut with the count still shown, which the user can narrow by typing more.
         */
        const val MAX_SEARCH_RESULTS = 500
    }
}
