package dev.eclipse.ssh.presentation

import android.content.Context
import android.os.SystemClock
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.backup.BackupFormatException
import dev.eclipse.ssh.data.backup.VaultBackup
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.saf.LocalAccessUnavailableException
import dev.eclipse.ssh.data.saf.LocalFile
import dev.eclipse.ssh.data.saf.LocalFileBrowser
import dev.eclipse.ssh.data.saf.LocalSyncIndex
import dev.eclipse.ssh.data.saf.localDocumentLength
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.describe
import dev.eclipse.ssh.data.model.savedForwardIdPrefix
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.matchesQuery
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isPastAuthentication
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.data.settings.SnippetRepository
import dev.eclipse.ssh.ssh.SftpDirectoryService
import dev.eclipse.ssh.ssh.connectFailureIsFinal
import dev.eclipse.ssh.ssh.SessionDiagnostics
import dev.eclipse.ssh.ssh.SessionDiagnosticEvent
import dev.eclipse.ssh.ssh.SessionEnd
import dev.eclipse.ssh.ssh.SessionEvent
import dev.eclipse.ssh.ssh.SessionLivenessProbe
import dev.eclipse.ssh.ssh.SshConnectPhase
import dev.eclipse.ssh.ssh.isAppFault
import dev.eclipse.ssh.ssh.isFault
import dev.eclipse.ssh.ssh.describeConnectFailure
import dev.eclipse.ssh.ssh.describeSessionEnd
import dev.eclipse.ssh.ssh.describeSftpFailure
import dev.eclipse.ssh.ssh.fallbackHome
import dev.eclipse.ssh.ssh.joinRemote
import dev.eclipse.ssh.ssh.OpenSshConfigParser
import dev.eclipse.ssh.background.NetworkMonitor
import dev.eclipse.ssh.background.awaitReconnectWindow
import dev.eclipse.ssh.background.ReconnectPolicy
import dev.eclipse.ssh.background.backoffWindowMs
import dev.eclipse.ssh.background.reconnectPolicyOf
import dev.eclipse.ssh.ssh.SshConnectionManager
import dev.eclipse.ssh.ssh.SshSessionStore
import dev.eclipse.ssh.ssh.SshKeyLoader
import dev.eclipse.ssh.ssh.TerminalChannel
import dev.eclipse.ssh.ssh.RemoteFile
import dev.eclipse.ssh.ssh.TransferCoordinator
import dev.eclipse.ssh.ssh.PortForwardingManager
import dev.eclipse.ssh.ssh.ForwardingHandle
import dev.eclipse.ssh.background.SessionRegistry
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalKeys
import dev.eclipse.ssh.terminal.TerminalModifiers
import dev.eclipse.ssh.terminal.Utf8StreamDecoder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient

@HiltViewModel
class MainViewModel @Inject constructor(
    private val hostRepository: HostRepository,
    private val settingsRepository: SettingsRepository,
    private val snippetRepository: SnippetRepository,
    private val transferRepository: TransferRepository,
    private val sshConnectionManager: SshConnectionManager,
    private val sessionStore: SshSessionStore,
    private val networkMonitor: NetworkMonitor,
    private val sftpDirectoryService: SftpDirectoryService,
    private val sessionRegistry: SessionRegistry,
    private val transferCoordinator: TransferCoordinator,
    private val portForwardingManager: PortForwardingManager,
    private val secureClipboard: SecureClipboard,
    private val credentialStore: HostCredentialStore,
    private val diagnostics: SessionDiagnostics,
    private val livenessProbe: SessionLivenessProbe,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selectedHostId = MutableStateFlow<String?>(null)
    private val tabs = MutableStateFlow(listOf<SessionTab>())
    private val terminalOutput = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The live viewport of each connected terminal - what the renderer draws.
     *
     * On its own flow rather than inside [uiState], and that is not tidiness. [uiState] is a
     * `combine` of five flows that copies several maps on every emission, and it is collected by every
     * screen in the app; publishing a frame through it would rebuild the whole UI state thirty times a
     * second and recompose Hosts, Files, Transfers and Settings along with the terminal. A dedicated
     * flow means a frame reaches exactly the one composable that wants it.
     *
     * A [TerminalFrame] is also deliberately not a [dev.eclipse.ssh.terminal.TerminalSnapshot]. The
     * snapshot deep-copies the entire grid including scrollback - on a 2 000-line buffer that is a
     * quarter of a million cell objects - whereas a frame copies only the rows currently on screen.
     * Publishing snapshots at frame rate is what made the terminal the app's worst source of garbage.
     */
    private val terminalFrames = MutableStateFlow<Map<String, TerminalFrame>>(emptyMap())
    val frames: StateFlow<Map<String, TerminalFrame>> = terminalFrames

    /**
     * How far each terminal is scrolled back, in lines above the live bottom. Absent means "live".
     *
     * Held here rather than in the composable because it has to be *adjusted* as output arrives: the
     * offset is measured from the bottom, so every new line would otherwise slide the view onto
     * different text while the user was reading it. See [pinScrollback].
     */
    private val scrollOffsets = ConcurrentHashMap<String, Int>()

    /** When each host's searchable plain text was last rebuilt - see [publishTerminalText]. */
    private val textPublishedAt = ConcurrentHashMap<String, Long>()

    /**
     * The command line each host appears to be typing, for the recent-commands list.
     *
     * A best-effort reconstruction, and it has to be. Now that keystrokes go to the remote shell
     * one at a time there is no moment at which the app is handed a finished command - the line lives
     * in the remote `readline`, which the app cannot read. Printable text is appended, Backspace
     * removes a character, Enter files the result, and anything else leaves it alone. Editing the line
     * with the arrow keys or Ctrl-A therefore records a line that is close but not exact, which is a
     * fair trade for keeping the feature: the alternative is that connecting a real shell silently
     * empties a list the user has been building up. The shell's own history, reachable with the Up
     * arrow for the first time, is the accurate one.
     */
    private val typedLines = ConcurrentHashMap<String, StringBuilder>()

    private val commandHistory = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /**
     * What each host is showing in the file browser - its directory and that directory's contents -
     * keyed by host id.
     *
     * Per host rather than one listing for the whole app, because the browser is switched between live
     * sessions and each of them is somewhere different. One slot made the last listing to arrive the
     * one on screen no matter who had asked for it: a reconnect's own SFTP login, or a refresh on the
     * host the user had just switched away from, replaced the contents under the header while the
     * header kept naming the other server. That is not only confusing to read - the file rows are what
     * Download, Rename, Chmod and Delete resolve their paths from, and every one of those runs against
     * the *selected* host, so acting on a row that belonged to a different session sent an absolute
     * path to a server that had never listed it. Keyed by host, a listing can only reach the screen
     * that asked for it, and switching back to a session restores the directory it was left in.
     *
     * Directory and contents in one value so the two cannot disagree: written as two flows, a
     * recomposition landing between the writes drew the new directory's name over the old directory's
     * files.
     */
    private val remoteListings = MutableStateFlow<Map<String, RemoteListing>>(emptyMap())
    private val localFiles = MutableStateFlow<List<LocalFile>>(emptyList())
    private val localDirUri = MutableStateFlow<String?>(null)
    private val forwardings = MutableStateFlow<List<ForwardEntry>>(emptyList())
    private val forwardHandles = ConcurrentHashMap<String, ForwardingHandle>()

    /**
     * Where every coroutine that can reach an SSH transport runs. Not the main thread, ever.
     *
     * [viewModelScope] dispatches on `Dispatchers.Main.immediate`, and that is the wrong place for
     * every line below that dials, lists, forwards or hangs up: an SSH call is a socket call, and a
     * socket call on Android's main thread is [android.os.NetworkOnMainThreadException] - raised by
     * BlockGuard *inside* MINA's write path, so the transport is already marked broken by the time the
     * throw reaches whatever `runCatching` was meant to contain it. The app then reports its own
     * threading mistake as `TransportFailed`, the tab reads "Connection lost:
     * NetworkOnMainThreadException", and the reconnect ladder answers it - five rungs, up to half a
     * minute apart, each one dying the same way a second after the shell opens, because the auto-SFTP
     * login that triggered it runs on every shell open. That is the reconnect loop, and its cause was
     * a missing dispatcher rather than anything about the network.
     *
     * It is a *named* scope rather than `Dispatchers.IO` repeated at each `launch` for the reason the
     * defect existed at all: the rule is one decision, and fifteen copies of a decision is fourteen
     * chances to forget it. `transportScope.launch` also says what kind of coroutine it is, so a
     * `viewModelScope.launch` that starts touching the transport reads as a mistake.
     *
     * Shares [viewModelScope]'s job, so everything here is still cancelled with the view model, and
     * the UI state it writes is safe from any thread: the flows are [MutableStateFlow]s and
     * [updateTab] is atomic by design.
     */
    private val transportScope: CoroutineScope = viewModelScope + Dispatchers.IO

    /**
     * Where native resources are *released*, deliberately outliving [viewModelScope].
     *
     * Two properties teardown needs and [transportScope] cannot give it. It must be off the main
     * thread, for the reason above - closing a port-forward tracker asks the server to cancel the
     * forward, which is a write. And it must not be cancellable: a close that is cancelled halfway
     * leaks the listening socket it was supposed to release, and the callers include [onCleared],
     * where [viewModelScope] has already been cancelled and a coroutine launched in it would never
     * run at all.
     *
     * Only ever handed work that finishes on its own in milliseconds, so nothing accumulates in it.
     */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverStats = MutableStateFlow<Map<String, ServerStats>>(emptyMap())
    private val hostKeyChallenge = MutableStateFlow<HostKeyChallenge?>(null)
    private val knownHostsState = MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * The live sessions, their shells and their scrollback — owned by [SshSessionStore], not by this
     * view model.
     *
     * They used to be three plain maps here, which quietly made the *UI* the owner of the app's SSH
     * sessions. Two things followed from that and both were bugs. A session outlived nothing: an
     * activity being finished ran [onCleared], which closed every socket, so a shell the user had
     * asked to keep alive in the background died the moment the task was swiped — while the foreground
     * service was still running specifically to keep it. And the service, unable to see these maps,
     * had to keep its own, so the same host ended up connected twice: see [SshSessionStore] for what
     * that did on the Connect path.
     *
     * Read through the store rather than copied out of it, so the ~thirty call sites below keep their
     * exact atomic semantics (`put`, `remove(key, value)`, `getOrPut`) while operating on state the
     * whole process shares.
     */
    private val channels: ConcurrentHashMap<String, TerminalChannel> get() = sessionStore.channels
    private val terminalBuffers: ConcurrentHashMap<String, AnsiTerminalBuffer> get() = sessionStore.buffers
    private val sessions: ConcurrentHashMap<String, ClientSession> get() = sessionStore.sessions
    /** Output collectors, one per live channel, cancelled when the tab or host goes away. */
    private val terminalJobs = ConcurrentHashMap<String, Job>()
    /** In-flight connect attempts, so double-tapping a host cannot open two sessions. */
    private val connectJobs = ConcurrentHashMap<String, Job>()

    /**
     * Which dial each host is on, so an attempt that has been replaced cannot report over its
     * replacement.
     *
     * [connectJobs] cancels the previous attempt, and cancellation is not enough on its own: it is
     * cooperative, and the path from a caught failure to the tab write that reports it contains no
     * suspension point at all, so a `cancel()` arriving anywhere along it is only noticed after the
     * report has already been made. The window is not theoretical - it is the one every new host goes
     * through. An unknown host key fails the attempt and raises the trust question, the user answers it,
     * [acceptHostKey] dials again, and the answered attempt then finishes unwinding and puts
     * `Server key did not validate` on a tab that is, by then, connecting for real. The user sees a red
     * failure appear *after* saying yes, on a connection that is about to succeed - which is the shape
     * of the complaint this release is about, arriving from the one direction nobody was looking.
     *
     * A number rather than a job identity, because the identity is not available in time: `viewModelScope`
     * dispatches on `Main.immediate`, so a body launched from the main thread starts running before
     * `connectJobs[host.id] = job` has executed, and an attempt that failed before its own job was
     * recorded would mistake itself for the stale one and report nothing at all. This is incremented
     * synchronously by [connect] before anything else happens, so every attempt knows its own number
     * from the moment it exists.
     *
     * Read *inside* the `updateTab` transform, never before it, for the reason spelled out on
     * [isDisplaying]: a check on one side of a suspension and a write on the other is two steps with a
     * whole replacement able to fit between them.
     */
    private val dialGenerations = ConcurrentHashMap<String, AtomicLong>()
    /**
     * In-flight SFTP logins, one per host, so a reconnect cannot leave the previous attempt writing
     * a stale listing (or a stale failure) into the tab it no longer belongs to.
     */
    private val sftpJobs = ConcurrentHashMap<String, Job>()

    /**
     * In-flight starts of a host's saved forwarding rules, one per host, for the same reason as
     * [sftpJobs] and one more: binding a listening socket is the one thing here that can block on a
     * port another app is holding, so a reconnect arriving mid-bind must be able to abandon the
     * previous attempt rather than let it report against the transport that replaced it.
     */
    private val forwardJobs = ConcurrentHashMap<String, Job>()
    /** Remote home directory resolved from the server, keyed by host id. */
    private val homePaths = ConcurrentHashMap<String, String>()

    /**
     * Automatic reconnects waiting out their backoff, one per host. See [scheduleAutoReconnect].
     */
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    /**
     * The reconnect rules each host was last dialled with, keyed by host id. See [ReconnectPolicy].
     *
     * Cached rather than read from the repository at the moment a session drops, for two reasons. The
     * decision is needed *synchronously* - the tab has to go to RECONNECTING or to ERROR in the same
     * update that reports the reason, and a Room read on the far side of a suspension is what used to
     * make a genuine drop flash red before admitting it was already recovering. And a session should
     * end under the rules it began under: editing a profile while its shell is open changes the next
     * connection, not the ladder already running underneath the current one.
     */
    private val reconnectPolicies = ConcurrentHashMap<String, ReconnectPolicy>()

    /**
     * Consecutive automatic reconnects attempted per host, cleared as soon as one succeeds.
     *
     * Counted so the retry ladder has an end. An unbounded reconnect loop against a host that is
     * genuinely gone is the failure mode this feature is most likely to introduce: it burns battery,
     * holds a wakelock through the foreground service, and looks to the server like a client trying to
     * brute-force its way in.
     */
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    /**
     * The viewport size each host's terminal was last measured at, carried into the next pty it opens.
     *
     * Kept here rather than on the channel because the channel is the thing that gets replaced: the
     * size belongs to the user's screen, which a reconnect does not change. See [resizeTerminal].
     */
    private val ptySizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * When each live session's shell came up, on the monotonic clock, for the "up=" field in the
     * diagnostics. Wall-clock time would report nonsense across a suspend or a clock change, and how
     * long a session survived is precisely the number that distinguishes an idle-timeout from a flaky
     * link.
     */
    private val connectedAt = ConcurrentHashMap<String, Long>()

    /**
     * Cuts a reconnect backoff short the moment the platform reports a usable network.
     *
     * Conflated, and fed from [NetworkMonitor] rather than collected inline, because the signal has to
     * survive being raised while a connect attempt is already in flight — nobody is receiving then, and
     * a lost signal means waiting out the rest of a five-minute window on a network that came back
     * immediately. Same channel shape as the foreground service uses; see [awaitReconnectWindow].
     */
    private val reconnectWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * The credentials a host-key question is holding, or null once nothing is waiting on one.
     *
     * `@Volatile` because the clear and the reads sit on different threads. It is set and read on the
     * main thread - [connect]'s prologue, [acceptHostKey], [rejectHostKey] - but cleared in
     * [attachTerminal], which since the transport work moved off the UI thread runs on
     * [transportScope]. Without it the main thread may go on seeing the object after the session it
     * belongs to is up, which is two problems: a host-key question answered later would redial from a
     * stale record, and - the reason this is not merely tidiness - a password and a decrypted
     * passphrase would stay reachable for the life of the view model when the whole point of the clear
     * is that they are needed only until the session exists. A reference write is already atomic, so
     * visibility is the only thing missing and `@Volatile` is the whole fix.
     */
    @Volatile private var pendingConnection: PendingConnection? = null

    private val _statusMessage = MutableStateFlow<String?>(null)

    /**
     * Transient, user-facing message for operations that used to fail silently or crash
     * (wrong backup passphrase, unreachable host on resume, unreadable SAF document).
     */
    val statusMessage: StateFlow<String?> = _statusMessage

    fun consumeStatusMessage() { _statusMessage.value = null }

    /**
     * Reports a failure raised in the UI layer through the same snackbar as this view model's own
     * errors. The document writes behind the file pickers are owned by the composable that holds
     * the launcher, not by the view model, but a user still needs to be told when one fails.
     */
    fun reportUiFailure(prefix: String, error: Throwable) = report(prefix, error)

    /**
     * Same channel, for a UI-layer failure that arrives as a message with no exception behind it.
     * `BiometricPrompt` reports its errors as a localised `CharSequence` through a callback, and its
     * wording ("no biometric enrolled", "too many attempts") is more useful to the user than
     * anything this app could synthesise from it.
     */
    fun reportUiMessage(message: String) = report(message)

    private fun report(message: String) { _statusMessage.value = message }

    private fun report(prefix: String, error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        _statusMessage.value = "$prefix: $detail"
    }

    private val coreUiState = combine(
        hostRepository.hosts,
        settingsRepository.settings,
        query,
        selectedHostId,
        tabs,
    ) { hosts, settings, search, selected, openTabs ->
        MainUiState(
            hosts = hosts,
            filteredHosts = hosts.filter { it.matchesQuery(search) },
            settings = settings,
            query = search,
            selectedHostId = selected ?: hosts.firstOrNull()?.id,
            tabs = openTabs,
        )
    }

    /**
     * The two things the app remembers *about* a host rather than *of* it: the key it has agreed to
     * trust, and whether the user saved credentials on it. Paired into one flow because
     * [kotlinx.coroutines.flow.combine] only has typed overloads up to five sources and the group
     * below is already using all five — the alternative is the vararg overload, which erases every
     * element to `Any?` and turns a mis-ordered argument into a runtime cast instead of a compile
     * error.
     */
    private val securityState = combine(knownHostsState, credentialStore.credentials) { knownHosts, saved ->
        SecurityState(knownHosts = knownHosts, credentials = saved)
    }

    private val baseUiState = combine(
        combine(coreUiState, hostKeyChallenge, forwardings, snippetRepository.snippets, serverStats) { state, challenge, activeForwards, savedSnippets, stats ->
            BaseState(state, challenge, activeForwards, savedSnippets, stats)
        },
        securityState,
    ) { base, security ->
        base.state.copy(
            hostKeyChallenge = base.challenge,
            forwardings = base.forwards,
            snippets = base.snippets,
            serverStats = base.stats,
            knownHosts = security.knownHosts,
            savedCredentials = security.credentials,
        )
    }

    private val terminalState = combine(terminalOutput, commandHistory, diagnostics.events) { output, history, events ->
        // The labels are read here, off the back of an events emission, and that ordering is what makes
        // a non-flow safe to read from inside a flow: a label is created by the same `record` call that
        // pushes the event, so every session with a line in [events] already has its label in the map.
        TerminalState(
            output = output,
            history = history,
            diagnostics = events,
            diagnosticsLabels = diagnostics.sessionLabels,
        )
    }

    private val localState = combine(localFiles, localDirUri) { files, dir ->
        LocalState(files = files, dirUri = dir)
    }

    val uiState: StateFlow<MainUiState> = combine(baseUiState, transferRepository.transfers, terminalState, remoteListings, localState) { state, activeTransfers, terminal, listings, local ->
        // The browser shows the session the rest of the app is pointed at, so what reaches the screen
        // is that host's own listing and never another session's - see [remoteListings]. A host that
        // has not listed anything yet has no entry, which the Files screen draws as the directory it
        // expects rather than as an empty one.
        val browsing = state.selectedHostId?.let(listings::get)
        state.copy(
            transfers = activeTransfers,
            terminalOutput = terminal.output,
            commandHistory = terminal.history,
            diagnostics = terminal.diagnostics,
            diagnosticsLabels = terminal.diagnosticsLabels,
            remoteFiles = browsing?.files.orEmpty(),
            remotePath = browsing?.path,
            localFiles = local.files,
            localDirUri = local.dirUri,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            // Room work, so it can fail for reasons that have nothing to do with this app being
            // correct: a full disk, a database file the platform could not open, a corrupted page.
            // Unguarded in a bare `launch`, any of those reached the default handler and crashed the
            // app during construction of its first screen — the worst possible moment, because there
            // is no UI up yet to say what happened and the user sees the launcher icon bounce back.
            // The app is entirely usable without seed data; the host list is simply empty.
            runCatching {
                hostRepository.seedIfEmpty()
                transferRepository.seedIfEmpty()
            }.onFailure {
                if (it is CancellationException) throw it
                report("Could not prepare local storage", it)
            }
        }
        knownHostsState.value = sshConnectionManager.knownHosts()
        // Idempotent, and started from here as well as from the service because either can be the only
        // one alive: the service is not running before the first connection, and it is the UI that is
        // in front when the user walks out of Wi-Fi range with a shell open.
        livenessProbe.start()
        sshConnectionManager.hostKeyChallenges
            .onEach { challenge -> hostKeyChallenge.value = challenge }
            .launchIn(viewModelScope)
        // The moment anything starts drawing terminals, hand it the frames that were skipped while
        // nothing was. Edge-triggered rather than polled: `map`+`distinctUntilChanged` collapses the
        // subscription count to a boolean, so a second collector arriving does not rebuild anything.
        terminalFrames.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .onEach { watched -> if (watched) republishFrames() }
            .launchIn(viewModelScope)
        networkMonitor.available
            .onEach { reconnectWake.trySend(Unit) }
            .launchIn(viewModelScope)
        // Folded onto every tab rather than read separately by the UI, so that one function still
        // answers "what does this session say" - a status line assembled from two sources in two places
        // is how "Reconnecting…" and "Disconnected" ended up on screen at the same time. The flag is
        // device-wide because the condition is: it is the phone that has no network, not one session.
        livenessProbe.networkHeld
            .onEach { held ->
                tabs.update { current ->
                    if (current.none { it.networkHeld != held }) current
                    else current.map { if (it.networkHeld == held) it else it.copy(networkHeld = held) }
                }
            }
            .launchIn(viewModelScope)
        adoptExistingSessions()
    }

    /**
     * Puts a tab back in front of every session the app is already holding.
     *
     * This is the half of "do not connect twice" that the user actually sees. Sessions live in
     * [SshSessionStore] for the life of the process, so a view model created after the activity was
     * finished and rebuilt — the task swiped away and reopened, the app resumed after the system
     * dropped the activity, a session restored by [dev.eclipse.ssh.background.EclipseSessionService]
     * while the UI was not running at all — arrives with live shells and no tabs pointing at them.
     * Before this, that state was indistinguishable from having no sessions: the user tapped Connect,
     * got a *second* session to the same account, and the first one stayed open and orphaned.
     *
     * Only hosts whose session *and* pty are both alive are adopted, because a terminal tab with
     * nothing to attach a collector to would be a lie. A session without a shell (an SFTP-only
     * restore) stays in the store and is reused by the next connect instead of being redialled.
     */
    private fun adoptExistingSessions() {
        val adoptable = sessionStore.adoptableHostIds()
        if (adoptable.isEmpty()) return
        transportScope.launch {
            val hosts = runCatching { hostRepository.hosts.first() }.getOrDefault(emptyList())
            adoptable.forEach { hostId ->
                val terminal = channels[hostId] ?: return@forEach
                if (tabs.value.any { it.hostId == hostId }) return@forEach
                val host = hosts.firstOrNull { it.id == hostId } ?: return@forEach
                val buffer = terminalBuffers.getOrPut(hostId) { AnsiTerminalBuffer() }
                updateTab(hostId) { existing ->
                    (existing ?: SessionTab(hostId = hostId, title = host.name)).copy(
                        state = SessionConnectionState.CONNECTED,
                        lastError = null,
                    )
                }
                diagnostics.record(
                    hostId,
                    SessionEvent.ADOPTED,
                    state = SessionConnectionState.CONNECTED,
                    detail = "view model recreated",
                    pty = terminal.ptyLabel,
                    channel = terminal.channelLabel,
                    idleForMs = terminal.idleForMs(),
                )
                terminalJobs.remove(hostId)?.cancelAndJoin()
                terminalJobs[hostId] = launchTerminalCollector(hostId, terminal, buffer)
                publishTerminalFrame(hostId, buffer)
            }
        }
    }

    fun setQuery(value: String) { query.value = value }
    fun selectHost(host: HostProfile) { selectedHostId.value = host.id }

    fun connect(
        host: HostProfile,
        password: String? = null,
        keyPair: KeyPair? = null,
        keyBytes: ByteArray? = null,
        keyPassphrase: String? = null,
        resuming: Boolean = false,
    ) {
        // First, and synchronously: from here on this is the dial that speaks for this host, and every
        // attempt still unwinding somewhere behind it is one whose report the user must not be shown.
        val dial = dialGenerations.computeIfAbsent(host.id) { AtomicLong() }.incrementAndGet()
        selectedHostId.value = host.id
        pendingConnection = PendingConnection(host, password, keyPair, keyBytes, keyPassphrase, resuming)
        // Asking for this session by hand is a fresh start, whatever the last one did: the allowance
        // is only spent by the ladder's own attempts, and a user who has just tapped Connect (or
        // Reconnect on a tab that gave up) is entitled to all of it. The ladder's own attempt passes
        // `resuming` and must not reset anything - that is the whole point of counting.
        if (!resuming) reconnectAttempts.remove(host.id)
        // A reconnect stays RECONNECTING for the whole attempt, keeping the sentence the ladder wrote
        // ("attempt 2 of 5"). Overwriting it with CONNECTING each time round would tell the user the
        // app had started something new, when what is happening is that it has not given up yet.
        updateTab(host.id) { existing ->
            (existing ?: SessionTab(hostId = host.id, title = host.name)).copy(
                state = if (resuming) SessionConnectionState.RECONNECTING else SessionConnectionState.CONNECTING,
                lastError = if (resuming) existing?.lastError else null,
            )
        }
        diagnostics.record(
            host.id,
            if (resuming) SessionEvent.RECONNECT_ATTEMPT else SessionEvent.CONNECT_REQUESTED,
            state = if (resuming) SessionConnectionState.RECONNECTING else SessionConnectionState.CONNECTING,
            network = networkMonitor.describe(),
            attempt = reconnectAttempts[host.id]?.takeIf { resuming },
        )
        // Replace any attempt still running for this host so a double tap cannot leave an
        // orphaned session behind.
        connectJobs.remove(host.id)?.cancel()
        // A user asking to connect now outranks a backoff waiting to do it later, and leaving the
        // waiter alive would let it fire a second connect on top of this one. Not when this *is* the
        // ladder's own attempt: cancelling the job that is running this code would kill the attempt.
        if (!resuming) {
            reconnectJobs.remove(host.id)?.let { waiting ->
                waiting.cancel()
                diagnostics.record(host.id, SessionEvent.RECONNECT_CANCELLED, detail = "connect requested")
            }
        }
        val job = transportScope.launch {
            // Anything the caller supplied wins; the profile's saved credentials only fill the gaps.
            // That order matters: a password typed into the auth prompt has to beat the one saved on
            // the host, or a rotated server password could not be used at all without editing the
            // profile first.
            val resolved = resolveCredentials(host, password, keyPair, keyBytes, keyPassphrase, resuming)
            // Recorded before the first attempt, so even a host that never gets a shell open has its
            // rules on file for the ladder that answers the failure.
            reconnectPolicies[host.id] = reconnectPolicyOf(host)
            // Everything from here to the shell being on screen happens under this host's dial gate,
            // which is what stops two dials to the same account existing at once. The service's
            // restore pass takes the same gate, so the pass that used to run concurrently with this
            // one - tapping Connect is what starts the service - now waits, finds the session this
            // attempt installed, and adopts it. See [SshSessionStore.dialing].
            sessionStore.dialing(host.id) {
                var lastError: Throwable? = null
                for (attempt in 0 until MAX_CONNECT_ATTEMPTS) {
                    try {
                        // Asked again inside the gate, because a session that appeared while this attempt
                        // waited for it is one to use, not one to duplicate - and asked on every attempt,
                        // because a restore pass can install one between two of them. See
                        // [adoptStoredSession] for the two shapes that count.
                        if (adoptStoredSession(host, dial, resolved, resuming)) return@dialing
                        val session = sshConnectionManager.connect(host, resolved.password, resolved.keyPair) { phase ->
                            onConnectPhase(host.id, dial, phase, resuming, attempt)
                        }
                        // The tab may have been closed (or the host deleted) while the handshake
                        // was in flight. Honour that instead of resurrecting the tab — and tear
                        // the new session down so it does not leak. Dereferencing the missing tab
                        // here used to throw an NPE inside the coroutine and crash the app.
                        if (tabs.value.none { it.hostId == host.id }) {
                            runCatching { session.close(false) }
                            return@dialing
                        }
                        val size = ptySizes[host.id]
                        // Authenticated. Everything from here is the channel and the pty, and it is worth
                        // saying so: a server that accepts the password and then cannot give out a pty
                        // used to spend that whole time claiming to be connecting.
                        markOpeningShell(host.id, dial, resuming)
                        val terminal =
                            sshConnectionManager.openTerminal(session, size?.first, size?.second, host)
                        // Never replaces a live session with this one: if another dialler installed one
                        // for this host while this handshake was in flight, that session is the one the
                        // app keeps and this one is the redundant half of a duplicate - the opposite of
                        // what `put` did, which closed the session the user was already typing into.
                        val installed = sessionStore.install(host.id, session)
                        if (installed !== session) {
                            // Ours lost, so its pty goes with it. Marked first so its collector reports
                            // the app's decision rather than an outage the reconnect ladder would answer.
                            terminal.markDeliberate()
                            runCatching { terminal.close() }
                            // The incumbent may have no shell on it - a restore pass installs
                            // transport-only sessions - so adopting has to be able to open one. Going
                            // round the loop instead would only lose the same race again, forever.
                            if (adoptStoredSession(host, dial, resolved, resuming)) return@dialing
                            continue
                        }
                        attachTerminal(host, terminal, resolved)
                        return@dialing
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        lastError = error
                        // A rejection the server has already made up its mind about is not retried: see
                        // `connectFailureIsFinal`. Among other things it stops a mistyped password being
                        // offered three times, which is how a typo gets an account locked.
                        diagnostics.record(
                            host.id,
                            SessionEvent.ATTEMPT_FAILED,
                            detail = error.message ?: error::class.java.simpleName,
                            network = networkMonitor.describe(),
                            attempt = attempt + 1,
                        )
                        if (connectFailureIsFinal(error)) break
                        if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                            // Named by the phase it failed in, read off the tab before it is overwritten.
                            // See [retryNotice] for why one message for all of them was actively
                            // misleading, and [retryPhase] for why this is no longer RECONNECTING.
                            updateTab(host.id) { tab ->
                                // Not this host's dial any more: a countdown belonging to an attempt the
                                // user has already replaced would sit on top of the one they are waiting
                                // for, counting attempts that are no longer being made.
                                if (!isCurrentDial(host.id, dial)) return@updateTab tab
                                tab?.copy(
                                    state = retryPhase(tab.state),
                                    lastError = retryNotice(
                                        tab.state,
                                        // 1-based, and it is the attempt this wait leads to rather than
                                        // the one that just failed: a user reading "attempt 2 of 3" is
                                        // being told what is about to happen.
                                        nextAttempt = attempt + 2,
                                        maxAttempts = MAX_CONNECT_ATTEMPTS,
                                    ),
                                )
                            }
                            delay(RECONNECT_DELAY_MS * (attempt + 1))
                        }
                    }
                }
                // A connection that never came up is an ERROR, not a session that ended: something -
                // the credentials, the host, the network - has to change before trying again is worth
                // anything, and the tab says so in red rather than in the amber of a finished session.
                val reason = describeConnectFailure(lastError)
                updateTab(host.id) { tab ->
                    // See [dialGenerations]. This attempt did fail, and the trace below says so - but a
                    // failure is only news about the session the user is watching if it is still that
                    // session's failure.
                    if (isCurrentDial(host.id, dial)) {
                        tab?.copy(state = SessionConnectionState.ERROR, lastError = reason)
                    } else {
                        tab
                    }
                }
                diagnostics.record(
                    host.id,
                    SessionEvent.CONNECT_FAILED,
                    state = SessionConnectionState.ERROR,
                    // Marked in the trace rather than hidden from it: a CONNECT_FAILED followed by a
                    // session that came up is otherwise a contradiction a reader has to guess at.
                    detail = if (isCurrentDial(host.id, dial)) reason else "$reason (superseded)",
                    network = networkMonitor.describe(),
                )
            }
        }
        connectJobs[host.id] = job
        job.invokeOnCompletion { connectJobs.remove(host.id, job) }
    }

    /**
     * Attaches the terminal to a session that is already in the store, if there is one to use.
     *
     * Two kinds qualify, and both mean "do not dial again":
     *
     *  - a session with a live shell on it, which is handed back as it is. The scrollback, the pty and
     *    the socket stay the ones the user was looking at.
     *  - a live session with *no* shell, which gets one opened on it. The background service dials
     *    exactly this shape when it restores a tracked host or resumes a transfer, and so does an
     *    SFTP-only resume - a session worth keeping (closing it would drop a transfer in flight) that
     *    the terminal cannot present until a pty exists on it.
     *
     * The second case is what [adoptExistingSessions] already promises - *"a session without a shell
     * stays in the store and is reused by the next connect instead of being redialled"* - and what,
     * before this existed, could not happen. [SshSessionStore.adoptable] rejected the session for
     * having no channel and [SshSessionStore.install] refused to replace it with a fresh one, so every
     * attempt authenticated against the server and then threw its own session away. The tab ran out of
     * attempts and failed, the ladder retried, and each cycle put another login in the server's auth
     * log: the connect - disconnect - reconnecting loop, with no network fault anywhere near it.
     *
     * Called inside the dial gate and inside the attempt loop's `try`, so a shell that fails to open
     * on an adopted session is reported and retried like any other connect failure rather than
     * escaping the coroutine.
     */
    private suspend fun adoptStoredSession(
        host: HostProfile,
        dial: Long,
        resolved: ResolvedCredentials,
        resuming: Boolean,
    ): Boolean {
        sessionStore.adoptable(host.id)?.let { (_, existing) ->
            diagnostics.record(host.id, SessionEvent.ADOPTED, state = SessionConnectionState.CONNECTED)
            attachTerminal(host, existing, resolved)
            return true
        }
        val session = sessionStore.sessionAwaitingShell(host.id) ?: return false
        // Recorded before the shell is asked for, so a pty that never opens is attributable to the
        // session it was asked of rather than looking like a fresh handshake that stalled.
        diagnostics.record(host.id, SessionEvent.ADOPTED, state = SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        val size = ptySizes[host.id]
        markOpeningShell(host.id, dial, resuming)
        val terminal = try {
            sshConnectionManager.openTerminal(session, size?.first, size?.second, host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A transport that cannot open a channel is not one to keep offering. A link that died
            // silently is still `isOpen`, so adopting it again on the next attempt would spend the
            // whole ladder waiting on the same corpse. Dropped as found-dead - discard rather than
            // close, so nothing reports this as an ending the user asked for and the reconnect they
            // are waiting for is not suppressed.
            sessionStore.discard(host.id)
            diagnostics.record(
                host.id,
                SessionEvent.ATTEMPT_FAILED,
                detail = "no shell on the stored session: ${error.message ?: error::class.java.simpleName}",
                network = networkMonitor.describe(),
            )
            // False rather than a rethrow: adopting is an optimisation, and its failure should cost a
            // dial in this attempt rather than one of the three attempts the user gets.
            return false
        }
        attachTerminal(host, terminal, resolved)
        return true
    }

    /**
     * Puts [terminal] on screen as [host]'s shell: collector, tab state, saved credentials, SFTP.
     *
     * Shared by the two ways a session becomes the one the user is typing into - a handshake that
     * just succeeded, and a live session adopted instead of dialling a second one - so that an
     * adopted session is indistinguishable from a fresh one. It used to exist only on the fresh
     * path, which is why adopting was a partial imitation of connecting.
     *
     * The outgoing collector goes first, and it is *waited for*, before anything it is watching is
     * closed or handed to its replacement. Three reasons, and only the first was ever obvious:
     *
     *  - reconnecting to the same host otherwise stacked a new collector per attempt, each pinning
     *    its buffer for the lifetime of the ViewModel;
     *  - a collector reports the close of its channel as the end of the session, which for a channel
     *    being replaced is not what it means. Cancelled first, it never sees that close at all;
     *  - a reconnect deliberately keeps the host's buffer so its scrollback survives, and
     *    [AnsiTerminalBuffer] is a plain list model with no locking. A cancel only *asks* a coroutine
     *    to stop, so the outgoing collector could still be feeding or reading that buffer while its
     *    replacement fed it from another thread. That is a data race on an ArrayList: torn frames at
     *    best, an index out of bounds in the middle of a reconnect at worst. Joining makes the
     *    handover exclusive, and it is quick - every suspension point in the collector is cancellable
     *    and its teardown does no I/O.
     */
    private suspend fun attachTerminal(host: HostProfile, terminal: TerminalChannel, resolved: ResolvedCredentials) {
        // First of everything here, and deliberately ahead of the collector below: the credential is
        // what the reconnect ladder answers an ending with, and the collector is what reports one. Left
        // where it used to be - after the tab was already CONNECTED - the two raced on every session
        // that died young, and the write lost. See [rememberCredentials].
        rememberCredentials(host, resolved)
        // That write cannot be abandoned halfway, so a cancellation arriving during it is deferred to
        // here. Honoured now, before anything below runs: the rest of this function presents a session
        // to the user, and an attempt that has been replaced by another has none to present.
        currentCoroutineContext().ensureActive()
        terminalJobs.remove(host.id)?.cancelAndJoin()
        channels.put(host.id, terminal)?.let { previous ->
            // Never the channel just installed: adopting hands back the channel that is already in
            // the map, and closing it would end the session this call exists to present.
            if (previous !== terminal) {
                previous.markDeliberate()
                runCatching { previous.close() }
            }
        }
        val buffer = terminalBuffers.getOrPut(host.id) { AnsiTerminalBuffer() }
        /*
         * The local half of the size the pty was just opened at.
         *
         * [resizeTerminal] could not apply it when it was measured, and that is structural rather than a
         * race: the first viewport report of a connection arrives while the screen still says
         * CONNECTING, and no buffer exists to resize until this line has run. The composable reports
         * only when the size it measures *changes*, so it never mentioned that size again - which left
         * the grid on its 120x40 default for the whole session while the remote pty was correctly sized
         * to the phone, undoing the half of "both are required" that had arrived too early.
         *
         * Alternate-screen programs are what paid for it. `vim` and `top` draw for the rows the server
         * told them about, so a grid holding ten rows more than the pty kept ten rows of whatever was
         * on screen before, with a cursor row that agreed with neither. Both halves are coerced into
         * [TERMINAL_COLUMN_RANGE]/[TERMINAL_ROW_RANGE] by [AnsiTerminalBuffer.resize] and
         * [TerminalChannel.open] respectively, so they land on the same numbers even at a size no
         * terminal may actually be.
         *
         * No [SessionEvent.PTY_RESIZED] here: nothing is being asked of the far end. This is the local
         * grid catching up with what the far end was already told, and recording it as a resize would
         * put a window-change in the trace that never went out on the wire.
         */
        ptySizes[host.id]?.let { (columns, rows) -> buffer.resize(columns, rows) }
        terminalJobs[host.id] = launchTerminalCollector(host.id, terminal, buffer)
        // An adopted session may be sitting at a prompt with nothing to say, and the collector only
        // publishes when output arrives - so without this the scrollback the user already had would
        // stay off screen until they pressed a key.
        publishTerminalFrame(host.id, buffer)
        updateTab(host.id) { it?.copy(state = SessionConnectionState.CONNECTED, lastError = null) }
        connectedAt[host.id] = SystemClock.elapsedRealtime()
        diagnostics.record(
            host.id,
            SessionEvent.SHELL_OPEN,
            state = SessionConnectionState.CONNECTED,
            network = networkMonitor.describe(),
            keepAliveSeconds = host.keepAliveSeconds,
            pty = terminal.ptyLabel,
            channel = terminal.channelLabel,
            idleForMs = terminal.idleForMs(),
        )
        // Credentials are no longer needed once the session is up.
        pendingConnection = null
        // Deliberately *not* resetting the reconnect allowance here. Coming up is not the same thing
        // as staying up, and treating it as one is what made "Reconnecting..." endless: a session that
        // died a few seconds after every login reset the counter on each of those logins, so the ladder
        // never reached its limit, never reported anything, and never stopped. The allowance is handed
        // back where the evidence for it exists - in the collector, once a session has actually lasted
        // [STABLE_SESSION_MS] - and by a user asking for a connection themselves. See
        // [scheduleAutoReconnect].
        runCatching { hostRepository.save(host.copy(lastConnectedAt = System.currentTimeMillis())) }
            // A cancellation is not a failed save, and everything below it presents a session that an
            // attempt replaced by another no longer has. See [rememberCredentials].
            .onFailure { if (it is CancellationException) throw it }
        // Auto Login SFTP. Off means SSH only — nothing opens a second channel on this host until the
        // user asks for a listing — which is the point of the switch: an account with a shell and no
        // sftp-server subsystem otherwise greets every successful login with a failure about a feature
        // the user never asked for.
        if (host.autoLoginSftp) {
            loginSftp(host)
        } else {
            updateTab(host.id) { it?.copy(sftpState = SftpSessionState.DISABLED, sftpError = null) }
        }
        // Last, and after the tab is already CONNECTED: the shell is the session, and the tunnels are a
        // convenience attached to it. See [startSavedForwards] for why a tunnel that cannot bind is not
        // allowed to change what this function just published.
        startSavedForwards(host)
    }

    /**
     * Brings up [host]'s saved forwarding rules on the session that has just come up.
     *
     * Called from [attachTerminal], so it runs for a fresh login, for every rung of the reconnect
     * ladder, and for a live session adopted instead of dialled - the three ways a host acquires a
     * transport, and all three need the rules rebound, because a `PortForwardingTracker` belongs to the
     * `ClientSession` that created it.
     *
     * **A forward that fails may not cost the user their shell.** This is the whole rule the feature
     * turns on. Every ordinary reason a tunnel does not come up - the local port is already taken,
     * usually by the last run of this app or by another one; the server refuses a remote bind because
     * its `AllowTcpForwarding` says no - is a fact about one listening socket and says nothing about the
     * session. So nothing here touches [SessionTab.state], nothing arms a reconnect, and the failure is
     * reported on [SessionTab.forwardError] beside the `forwardsOpen`/`forwardsTotal` pair. Answering a
     * taken port with a reconnect is exactly the loop this release exists to stop, and it would be a
     * loop nothing could break: the port would still be taken on the next attempt.
     *
     * Launched rather than awaited, like [loginSftp], because binding a listening socket can block long
     * enough to be noticed and the shell is on screen and typeable already.
     */
    private fun startSavedForwards(host: HostProfile) {
        forwardJobs.remove(host.id)?.cancel()
        // Unconditional, including for a host with no rules: this also clears the previous transport's
        // trackers, and a host whose last rule was just deleted has to end up with nothing bound and
        // nothing claimed on its tab.
        stopSavedForwards(host.id)
        val rules = decodeForwardRules(host.savedForwards, host.id)
        updateTab(host.id) { it?.copy(forwardsOpen = 0, forwardsTotal = rules.size, forwardError = null) }
        if (rules.isEmpty()) return
        val job = transportScope.launch {
            val session = sessions[host.id]
            if (session == null) {
                // Not an error worth a message: the only way to get here is a session that ended between
                // the shell opening and this line, and whatever ended it is already on the tab.
                updateTab(host.id) { it?.copy(forwardsOpen = 0, forwardsTotal = rules.size) }
                return@launch
            }
            var open = 0
            val failures = mutableListOf<String>()
            rules.forEach { entry ->
                try {
                    forwardHandles[entry.id] = openForward(session, entry)
                    forwardings.update { it + entry }
                    open++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Recorded per rule and continued, not aborted: three rules where the first port is
                    // taken must still give the user the other two, and "8080 is busy" is a different
                    // problem from "this server does not allow forwarding at all".
                    failures += "${entry.describe()}: ${error.message ?: error::class.java.simpleName}"
                }
                // Written as each one lands rather than once at the end, so a rule that takes a while to
                // bind does not hide the ones that already worked.
                updateTab(host.id) { it?.copy(forwardsOpen = open, forwardsTotal = rules.size) }
            }
            if (failures.isEmpty()) return@launch
            val reason = failures.joinToString(" · ")
            updateTab(host.id) { it?.copy(forwardError = reason) }
            // One message for the whole set, and it names the host: this fires on a reconnect the user
            // may not have asked for, so a snackbar per failed rule on a flaky link would be a queue of
            // notifications about the same two ports.
            report("Port forwarding on ${host.name}: $reason")
        }
        forwardJobs[host.id] = job
        job.invokeOnCompletion { forwardJobs.remove(host.id, job) }
    }

    /** One saved rule, on the manager, bound to loopback on whichever side it lands. */
    private suspend fun openForward(session: ClientSession, entry: ForwardEntry): ForwardingHandle = when (entry.type) {
        ForwardType.LOCAL -> portForwardingManager.startLocal(
            session,
            "127.0.0.1",
            entry.localPort,
            entry.remoteHost.orEmpty(),
            entry.remotePort ?: 0,
        )
        // Server loopback, for the reason spelled out in [startRemoteForward]: a rule written as two
        // port numbers has not asked for the phone's port to be published to the server's network.
        ForwardType.REMOTE -> portForwardingManager.startRemote(session, "127.0.0.1", entry.remotePort ?: 0, "127.0.0.1", entry.localPort)
        ForwardType.DYNAMIC -> portForwardingManager.startDynamic(session, "127.0.0.1", entry.localPort)
    }

    /**
     * Closes the forwards that came from [hostId]'s saved column, leaving the user's own alone.
     *
     * The distinction is [savedForwardIdPrefix]: a saved rule's id is derived from its text, a forward
     * opened by hand in the sheet gets a `UUID`. Only the derived ones are rebound by
     * [startSavedForwards], so only those may be closed here - a SOCKS proxy the user started
     * themselves is not this function's to take away.
     */
    private fun stopSavedForwards(hostId: String) {
        val prefix = savedForwardIdPrefix(hostId)
        releaseForwards(forwardings.value.filter { it.id.startsWith(prefix) }.map { it.id })
    }

    /**
     * Drops the forward trackers for [ids], closing them off the main thread.
     *
     * Split in two on purpose. The bookkeeping - the entries leaving [forwardHandles] and
     * [forwardings] - happens before this returns, because the list on screen and the tab's forward
     * count must not still be claiming a forward the app has given up on. The close is a *network*
     * operation: a tracker asks the server to cancel the forward on the way out, and all four callers
     * reach this from the main thread - a click handler, a shell opening, a host being deleted, and
     * [onCleared]. On the main thread that write is [android.os.NetworkOnMainThreadException] raised
     * inside MINA, which breaks the transport the forward was riding on. Tidying up a forward must not
     * cost the user the session.
     *
     * Still `runCatching` per handle, and for the reason it always was: closing a tracker talks to the
     * server, so it throws once the session is gone - and the session dying is exactly when three of
     * these four callers run. One handle that cannot be closed must not stop the others.
     *
     * [releaseScope] rather than [transportScope] because a cancelled close leaks a listening socket,
     * and because [onCleared] runs after [viewModelScope] has been cancelled.
     */
    private fun releaseForwards(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val dropped = ids.toSet()
        val handles = dropped.mapNotNull { forwardHandles.remove(it) }
        forwardings.update { entries -> entries.filterNot { it.id in dropped } }
        if (handles.isEmpty()) return
        releaseScope.launch { handles.forEach { handle -> runCatching { handle.close() } } }
    }

    /**
     * Puts the credential that just authenticated on file for the reconnect ladder and the service.
     *
     * Still `runCatching`: a vault that cannot store the credential is not a reason to fail a session
     * that is already up. But it is said out loud in the trace, because the consequence lands much
     * later and looks like something else - the ladder then has nothing to authenticate with, and an
     * outage ends the session with a credential error instead of recovering. Only the failure class is
     * recorded; the message could name what it was given.
     *
     * A cancellation is not one of those failures and is rethrown instead of filed as one. The write
     * cannot be cancelled halfway - [SessionRegistry] holds it to that - so a cancellation arriving
     * here means the attempt this call belongs to has already been replaced by another, and everything
     * after it is about presenting that attempt's session. Swallowed, as it was, it let a cancelled
     * attempt carry on to the SFTP login and report "lifecycle is not connected" against a transport
     * that was already gone - the failure that made the real fault look like an SFTP problem.
     */
    private suspend fun rememberCredentials(host: HostProfile, resolved: ResolvedCredentials) {
        runCatching { sessionRegistry.register(host.id, resolved.password, resolved.keyBytes, resolved.keyPassphrase) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                diagnostics.record(
                    host.id,
                    SessionEvent.CREDENTIAL_NOT_STORED,
                    state = SessionConnectionState.CONNECTED,
                    detail = failure.javaClass.simpleName,
                )
            }
    }

    /**
     * Fills in whatever the caller did not supply from the credentials saved on the host profile.
     *
     * This is also the one place a private key is turned into a [KeyPair]. It used to happen in the
     * Connect button's `onClick`: an OpenSSH key is wrapped in bcrypt-pbkdf, which is deliberately
     * expensive — hundreds of milliseconds, and more on a slow device — so the tap that started a
     * connection froze the frame before it, and a high enough KDF round count would have been an ANR.
     * Doing it here puts it on [Dispatchers.Default], inside the coroutine that was going to block on
     * the network anyway.
     *
     * Every read is wrapped: a credential store that cannot be read is a reason to *prompt*, never a
     * reason to fail the connection outright, which is what an escaping exception would do from
     * inside this coroutine.
     */
    private suspend fun resolveCredentials(
        host: HostProfile,
        password: String?,
        keyPair: KeyPair?,
        keyBytes: ByteArray?,
        keyPassphrase: String?,
        resuming: Boolean = false,
    ): ResolvedCredentials {
        // Skipped entirely when the caller already supplied both halves, so an ordinary
        // password-prompt connection does not touch the credential file at all.
        val stored = if (password == null || (keyPair == null && keyBytes == null)) {
            runCatching { credentialStore.stored(host.id) }.getOrDefault(StoredCredentials())
        } else {
            StoredCredentials()
        }

        // What the *live* session authenticated with. The session registry holds it encrypted from the
        // moment a session is installed until the user closes the tab or deletes the host, so it is
        // present for exactly as long as there is a session worth resuming and gone as soon as there is
        // not - nothing new is stored here, and no lifetime is extended.
        //
        // Read only for a resume, and only after the caller and the saved credentials have both come up
        // empty. Without it the reconnect ladder re-dials with no credential at all, so a user who typed
        // a password rather than saving it got "No more authentication methods available" instead of a
        // recovery: ten seconds of no signal ended the session permanently, and the attempt spent a
        // failed authentication against the server on the way. The notification and file-transfer paths
        // already resume from this registry; the ladder was the one that did not.
        suspend fun <T> resumed(read: suspend () -> T?): T? =
            if (resuming) runCatching { read() }.getOrNull() else null

        val resolvedPassword = password
            ?: (if (stored.hasPassword) runCatching { credentialStore.password(host.id) }.getOrNull() else null)
            ?: resumed { sessionRegistry.credential(host.id) }

        val material = keyBytes?.takeIf { it.isNotEmpty() }
            ?: (if (stored.hasKey) runCatching { credentialStore.keyBytes(host.id) }.getOrNull() else null)
            ?: resumed { sessionRegistry.keyBytes(host.id) }
        val label = if (keyBytes != null) PICKED_KEY_NAME else stored.keyLabel ?: PICKED_KEY_NAME
        val passphrase = keyPassphrase?.takeIf { it.isNotBlank() }
            ?: (if (stored.hasPassphrase) runCatching { credentialStore.passphrase(host.id) }.getOrNull() else null)
            ?: resumed { sessionRegistry.keyPassphrase(host.id) }

        val pair = when {
            keyPair != null -> keyPair
            material == null -> null
            else -> withContext(Dispatchers.Default) {
                runCatching { SshKeyLoader.load(material, label, passphrase) }.getOrNull()
            }
        }
        if (pair == null && material != null) {
            // Said out loud, because the alternative is an authentication failure that looks like the
            // server rejecting the account. The parser's own message is deliberately not included:
            // some key parsers quote a prefix of their input, which would put key bytes in a snackbar
            // and in anything that captured it.
            report("The private key for ${host.name} could not be read. Check its passphrase.")
        }
        // The key's bytes and passphrase travel on to the session registry only if they actually
        // produced a usable key pair, so the foreground service cannot inherit material that has
        // already been shown not to work.
        return ResolvedCredentials(
            password = resolvedPassword,
            keyPair = pair,
            keyBytes = material.takeIf { pair != null },
            keyPassphrase = passphrase.takeIf { pair != null },
        )
    }

    /**
     * Feeds [terminal]'s output into [buffer] and publishes rendered frames at a bounded rate.
     *
     * The previous version rendered on every emission, on the main thread, and that was the app's
     * worst ANR by a wide margin. [TerminalChannel] emits once per `write` from Apache MINA's pump —
     * which for a single keystroke echo is *one chunk per byte* — and each emission ran
     * `plainText()`, which concatenates every cell of a 2 000-line scrollback (a `Char.toString()`
     * per cell, so a quarter of a million short-lived Strings), plus `snapshot()`, which deep-copies
     * the same grid, plus two whole-map copies of the `StateFlow` values. `cat` on any real file made
     * the UI thread do that tens of thousands of times and the app stopped drawing.
     *
     * What this does instead:
     *  - runs on [Dispatchers.Default], so parsing and rendering are off the main thread entirely;
     *  - drains every chunk that has already arrived into the buffer before rendering once, so a
     *    burst costs one render rather than one per byte;
     *  - renders on the leading edge — the first chunk after an idle period is published immediately,
     *    so an echoed keystroke is not held back;
     *  - then rate-limits with [TERMINAL_FRAME_MS] before looking again. Anything that arrived during
     *    that pause is drained and published on the next pass, so the tail of a burst always reaches
     *    the screen; nothing is dropped and nothing is left stale.
     *
     * No chunk is discarded: the channel is unbounded and every byte reaches [AnsiTerminalBuffer] in
     * order. Only the *rendering* is coalesced, which is invisible above about 30 frames a second.
     *
     * [buffer] is confined to this coroutine, so the frame it renders is always a whole one.
     */
    private fun launchTerminalCollector(
        hostId: String,
        terminal: TerminalChannel,
        buffer: AnsiTerminalBuffer,
    ): Job = viewModelScope.launch(Dispatchers.Default) {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val decoder = Utf8StreamDecoder()
        // Answers to the queries the remote side sends: a cursor-position report, a device-attributes
        // reply. The buffer produces them while parsing and cannot write them itself; without this a
        // program that asks where the cursor is - anything using readline for a multi-line prompt -
        // waits for a reply that never comes and appears to hang. write() only appends to a queue, so
        // this never blocks the parser.
        val responder: (String) -> Unit = { reply -> runCatching { terminal.writeBytes(reply.toByteArray()) } }
        buffer.responder = responder
        var reportedDroppedOutput = false
        // Separate coroutine so a slow render can never make the shared flow drop an emission:
        // TerminalChannel publishes with tryEmit first, and a full buffer would otherwise block
        // Apache MINA's pump thread for as long as this loop took to catch up.
        //
        // It also closes `incoming`, because it is the only thing that writes to it, and it does so on
        // reaching [TerminalChannel.END_OF_OUTPUT] - the terminator the channel puts at the end of the
        // stream itself. `closer` used to close the queue the moment `awaitClosed` returned, and those
        // are two signals delivered on two threads with nothing ordering them: under load the close won
        // and the chunk still sitting in the shared flow was thrown away. That chunk is the shell's
        // parting line - the farewell of a `logout`, the last line of a build, the single line a
        // `nologin` account prints before it goes - so the session ended with its most useful output
        // missing from the screen and from the transcript that Search and Save logs read.
        val pump = launch {
            terminal.output.takeWhile { it !== TerminalChannel.END_OF_OUTPUT }.collect(incoming::send)
            incoming.close()
        }
        // Whatever the throttle below declined to publish, published once the throttle window is over.
        // Skipping a rebuild is only ever meant to be a *delay*, but the loop that would have caught up
        // blocks on the next chunk, and a shell that has just printed its prompt sends nothing more -
        // so the last thing every burst produced was dropped from the transcript and stayed dropped.
        // Search, Save logs and Save text all read that transcript, which meant the answer to "why is
        // the last line of my build missing from the log I just saved" was this throttle. Conflated on
        // purpose: at most one catch-up is ever outstanding, and nothing wakes at all while the
        // transcript is current, so a quiet session costs nothing.
        val transcriptDue = Channel<Unit>(Channel.CONFLATED)
        val transcript = launch {
            for (unused in transcriptDue) {
                delay(TERMINAL_TEXT_MS)
                publishTerminalText(hostId, buffer, force = true)
            }
        }
        // Notices that the far end is gone, which nothing used to. [TerminalChannel.output] is a
        // SharedFlow and a SharedFlow never completes, so a shell that exited, a server that rebooted
        // and a network that went away all looked exactly like a prompt with nobody typing at it: the
        // tab went on saying CONNECTED, keystrokes went into a dead stream without a word, and the only
        // way to find out was to close the tab and try again. Closing `incoming` rather than cancelling
        // anything lets the loop drain what is already queued first, so the shell's parting output -
        // `logout`, a farewell banner, the last line of a job that ended - still reaches the screen.
        val closer = launch {
            val end = terminal.awaitClosed()
            // Waits for the terminator to travel the stream, so everything the shell said before it
            // ended is in the buffer before the tab is told the session is over - and so a reconnect,
            // which cancels this collector to hand the buffer to its replacement, cannot cancel it out
            // from under output that has arrived but not yet been parsed.
            //
            // Bounded, and closing the queue by hand if the wait runs out, because the terminator is
            // emitted with `tryEmit` and a shared flow holding hundreds of undelivered chunks cannot
            // take it. That means the collector had already stopped draining, which is the one case
            // where there is nothing left to protect and a session that never reported its end would
            // be the worse outcome.
            if (withTimeoutOrNull(TERMINAL_DRAIN_MS) { pump.join() } == null) incoming.close()
            // Only if nothing newer has taken this host over. A reconnect cancels this coroutine
            // before it closes the channel it is replacing, so ordinarily it never sees that close -
            // but a cancel is a request, not a suspension of physics: this can already have been
            // resumed on another core, and reporting a replaced channel as a disconnection would
            // overwrite the CONNECTED state the new session had just been given.
            //
            // Absent is not the same as replaced, and testing for `remove` returning true conflated
            // them. Something else can legitimately have taken this channel out of the registry first -
            // the liveness sweep discards the channel of a session it proved dead - so on a real drop
            // that sweep and this handler raced for the same entry, and when the sweep won, this
            // returned early and the tab that had just lost its connection was left saying CONNECTED,
            // with nothing scheduled to bring it back.
            val current = channels[hostId]
            if (current == null || current === terminal) {
                channels.remove(hostId, terminal)
                // A close the app asked for is not an outage, and must not be described as one: the
                // tab is already going wherever the caller is taking it - closed, replaced by a
                // reconnect, or ended by Stop sessions - and overwriting it with "Disconnected from
                // the remote host" would report the app's own decision as a fault of the network.
                if (terminal.endedDeliberately) {
                    diagnostics.record(
                        hostId,
                        SessionEvent.CLOSED_BY_USER,
                        channel = terminal.channelLabel,
                        idleForMs = terminal.idleForMs(),
                        upForMs = connectedAt.remove(hostId)?.let { SystemClock.elapsedRealtime() - it },
                    )
                    return@launch
                }
                // Which of the two endings this was decides the colour as well as the wording: a shell
                // that ran and exited is finished, a transport that died under it failed. See
                // [SessionEnd.isFault].
                val endReason = describeSessionEnd(end)
                // The corpse comes out of the registry here, at the one moment the app is entitled to
                // remove it: the ending has been published, so nothing can be reported over it, and this
                // is the only path a dead session is guaranteed to reach - a redial replaces the entry
                // itself, but a tab whose auto-reconnect is off, or whose ladder ran out, never dials
                // again. Declines to touch a session that is still live, which is the ordinary case when
                // a shell exits under a transport still carrying an SFTP transfer. See
                // [SshSessionStore.reap].
                val reaped = sessionStore.reap(hostId)
                // Read once, here, because two things now depend on it: the trace, and whether this
                // ending starts a new ladder or continues the one already running.
                val upForMs = connectedAt.remove(hostId)?.let { SystemClock.elapsedRealtime() - it }
                // A session that stood up for a while and then dropped is a *new* outage and gets the
                // full allowance back. One that died shortly after coming up is flapping, and its
                // allowance carries over so that five of those in a row reach the end of the ladder and
                // say what happened, instead of reconnecting for as long as the app is open. See
                // [STABLE_SESSION_MS] for why the threshold is minutes rather than seconds.
                if (upForMs != null && upForMs >= STABLE_SESSION_MS) reconnectAttempts.remove(hostId)
                // A shell the far end hung up on before it produced a single byte. Answering that with a
                // ladder is the loop users report, so it is answered with the server's reason instead.
                // See [endedBeforeItRan] for how narrow this is.
                val refused = endedBeforeItRan(end, upForMs = upForMs, idleForMs = terminal.idleForMs())
                val willReconnect = !refused && shouldAutoReconnect(
                    end,
                    tabIsOpen = tabs.value.any { it.hostId == hostId },
                    endedDeliberately = false,
                    autoReconnectEnabled = reconnectPolicies[hostId]?.enabled ?: true,
                )
                // A fault the ladder is about to answer is a *reconnecting* tab, not an error one.
                // Writing ERROR here and RECONNECTING a moment later - from inside the scheduling
                // coroutine, on the far side of a settings read from disk - made every genuine drop
                // flash red with "Disconnected from the remote host" before admitting it was already
                // dealing with it, and a slow read left it sitting in that state. Nothing is lost by
                // starting in the state the app is actually in: the reason still rides along as the
                // line under it, and an ending that is nobody's plan to recover from is still ERROR.
                val ended = when {
                    !end.isFault -> SessionConnectionState.DISCONNECTED
                    willReconnect -> SessionConnectionState.RECONNECTING
                    else -> SessionConnectionState.ERROR
                }
                // Why there is no recovery running gets said in the same breath as what happened,
                // because a tab that stops after one ending, with no explanation of why it did not try
                // again, is the same unanswerable report in a different costume.
                val reason = if (refused) {
                    "$endReason · closed before the shell produced any output, so it was not retried"
                } else {
                    endReason
                }
                updateTab(hostId) { it?.copy(state = ended, lastError = reason) }
                diagnostics.record(
                    hostId,
                    SessionEvent.ENDED,
                    state = ended,
                    detail = "${end::class.java.simpleName}: $endReason" +
                        (if (refused) " · refused before first output" else "") +
                        (if (reaped) " · session reaped" else ""),
                    network = networkMonitor.describe(),
                    pty = terminal.ptyLabel,
                    channel = terminal.channelLabel,
                    // The evidence that separates "the transport died under a live session" from "the
                    // far end had stopped talking long before". Recorded on the ending itself because
                    // by the time anything else reads this channel it has been reaped.
                    idleForMs = terminal.idleForMs(),
                    upForMs = upForMs,
                )
                if (willReconnect) {
                    scheduleAutoReconnect(hostId, reason)
                } else {
                    // A shell that exited is finished with its session; nothing is going to use the
                    // transport again, and leaving it open would hold a socket and a heartbeat for a
                    // tab showing a dead prompt.
                    sessions.remove(hostId)?.let { session -> runCatching { session.close(false) } }
                }
            }
        }
        try {
            while (isActive) {
                var chunk: ByteArray? = incoming.receiveCatching().getOrNull() ?: break
                val before = buffer.lineCount()
                do {
                    // Decoded here rather than in the channel because the state that matters - the
                    // two or three bytes of a codepoint that straddled a network read - lives between
                    // chunks. See [Utf8StreamDecoder].
                    buffer.feed(decoder.decode(chunk!!))
                    chunk = incoming.tryReceive().getOrNull()
                } while (chunk != null)
                pinScrollback(hostId, buffer, before)
                publishTerminalFrame(hostId, buffer)
                if (!publishTerminalText(hostId, buffer)) transcriptDue.trySend(Unit)
                // Said once per session, and only if it ever happens. A hole in the transcript is
                // something the user has to be told about: the emulator's state depends on having seen
                // every byte, so what is on screen after a drop may be wrong in ways that look like the
                // remote program misbehaving. See [TerminalChannel.droppedChunks].
                if (!reportedDroppedOutput && terminal.droppedChunks > 0) {
                    reportedDroppedOutput = true
                    diagnostics.record(hostId, SessionEvent.OUTPUT_DROPPED, detail = "${terminal.droppedChunks} chunks")
                    report("Some terminal output was dropped - the display may be out of step with the shell")
                }
                delay(TERMINAL_FRAME_MS)
            }
        } finally {
            pump.cancel()
            // `closer` is deliberately left alone. It is what reports the ending, and the loop above
            // exits *because* of that ending - so cancelling it here is a race whose loser is a tab
            // that lost its session and never said so. It always finishes on its own: it is waiting on
            // a channel that has closed, and everything after that wait is a few non-suspending state
            // updates. This coroutine's completion waits for it, as a parent waits for its children;
            // a cancelled collector still takes it down, which is what replacing a channel wants.
            transcript.cancel()
            transcriptDue.close()
            // Only when it is still ours. Nothing should be able to overlap a collector with its
            // replacement - connect joins this one before starting the next - but a shared buffer left
            // with no responder is a session where anything that asks for the cursor position, which is
            // anything drawing a multi-line prompt, waits for a reply that never comes. Cheap insurance
            // against that ever becoming reachable again.
            if (buffer.responder === responder) buffer.responder = null
            // A truncated codepoint at the end of the stream becomes one replacement character
            // instead of vanishing, so a session cut mid-character still ends with what arrived.
            val tail = decoder.flush()
            if (tail.isNotEmpty()) buffer.feed(tail)
            // Unconditionally, and past the throttle. Cancelling `transcript` above cancels whatever
            // catch-up it still owed, and at the end of a session there is no next chunk to trigger
            // another one - so a shell whose last second was throttled ended with its farewell on
            // screen but missing from the transcript that Search, Save logs and Save text all read.
            publishTerminalFrame(hostId, buffer)
            publishTerminalText(hostId, buffer, force = true)
        }
    }

    /**
     * Reconnects [hostId] after the transport died, waiting out a growing backoff first.
     *
     * The shape of this is deliberate, because "reconnect automatically" is easy to get wrong in the
     * direction that hurts:
     *
     *  - **Only after a real drop.** The caller decides with [shouldAutoReconnect], which reconnects
     *    only when the shell never sent an exit status. A user typing `exit` gets a closed tab, not a
     *    session that springs back to life.
     *  - **Bounded, and bounded across a flap.** [MAX_AUTO_RECONNECT_ATTEMPTS] consecutive attempts,
     *    counted per host in [reconnectAttempts]. The allowance comes back when a session proves it can
     *    *stay* up — [STABLE_SESSION_MS] of uptime, measured in the collector — and when the user asks
     *    for a connection by hand. It deliberately does not come back merely because a login succeeded:
     *    resetting on every attach is what made *Reconnecting…* endless, because a server that hangs up
     *    a few seconds after each login handed the ladder a clean slate every time round, so it never
     *    reached its limit, never reported the reason, and never stopped. Past the limit the tab shows
     *    what actually ended the session and stops; the Reconnect action in the UI is still there, and
     *    pressing it starts a fresh ladder.
     *  - **Backed off, not hammered.** [backoffWindowMs] doubles the wait per attempt with the same
     *    ceiling the service uses, plus jitter so several hosts recovering together do not retry in
     *    lockstep — and the wait ends early when a network appears.
     *  - **Free while offline.** An attempt made with no network cannot succeed, so waiting for one
     *    does not consume the allowance. Without this, flight mode ate the whole ladder in a few
     *    seconds and the session stayed dead after the network came back — the exact case reconnecting
     *    exists for.
     *  - **One at a time.** A pending waiter is replaced, and a manual connect cancels it outright, so
     *    there is never more than one attempt in flight per host.
     *
     * The attempt itself is [connect], not a private dial: it already resolves saved credentials,
     * handles a host key that has to be trusted, keeps the scrollback buffer, re-registers the session
     * and honours Auto Login SFTP. Reconnecting through it means a recovered session is identical to a
     * fresh one rather than a subtly different second implementation of the same flow. A host with no
     * saved credentials fails its first attempt with an authentication error, which
     * [connectFailureIsFinal] treats as final, so this cannot turn a missing password into a retry
     * loop.
     */
    private fun scheduleAutoReconnect(hostId: String, endReason: String) {
        // The rules this host was dialled with, or the app-wide ones for a session nothing dialled -
        // one the service restored, or one adopted from a previous process. See [reconnectPolicies].
        val policy = reconnectPolicies[hostId] ?: ReconnectPolicy.DEFAULT
        val maxAttempts = policy.maxAttempts
        val attempt = (reconnectAttempts[hostId] ?: 0) + 1
        if (attempt > maxAttempts) {
            updateTab(hostId) {
                it?.copy(
                    state = SessionConnectionState.ERROR,
                    // The reason first, because it is the only part of this sentence anybody can act
                    // on. "Disconnected - gave up after 5 reconnect attempts" described the app's own
                    // behaviour and threw away what the *server* or the transport had said about why,
                    // which is the one thing a user chasing a session that will not stay up needs. The
                    // string comes from [describeSessionEnd] and interpolates no credential.
                    lastError = "$endReason · gave up after $maxAttempts reconnect attempts",
                )
            }
            diagnostics.record(
                hostId,
                SessionEvent.RECONNECT_EXHAUSTED,
                state = SessionConnectionState.ERROR,
                detail = endReason,
                network = networkMonitor.describe(),
                attempt = attempt - 1,
            )
            return
        }
        reconnectAttempts[hostId] = attempt
        val job = viewModelScope.launch {
            val globalSeconds = runCatching { settingsRepository.settings.first().reconnectBaseSeconds }
                .getOrDefault(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
            val window = backoffWindowMs(policy.backoffSeconds(globalSeconds), attempt)
            // Jitter on top of the deterministic window, matching the service's ladder.
            val waitMs = window + Random.nextLong(0, window / 2 + 1)
            updateTab(hostId) {
                it?.copy(
                    state = SessionConnectionState.RECONNECTING,
                    // The reason stays on screen for the whole ladder. It used to be overwritten the
                    // moment a reconnect was scheduled, so the *only* thing a user watching a session
                    // that would not stay up ever saw was "Reconnecting - attempt 2 of 5": the app knew
                    // the server had said "Timeout, your session not responding", or that the socket had
                    // been reset, and hid it behind its own progress report. Reporting the cause while
                    // the recovery is still running is the difference between a bug a user can describe
                    // and one they can only call "it keeps reconnecting".
                    lastError = "Reconnecting in ${waitMs / 1_000}s · attempt $attempt of " +
                        "$maxAttempts · $endReason",
                )
            }
            diagnostics.record(
                hostId,
                SessionEvent.RECONNECT_SCHEDULED,
                state = SessionConnectionState.RECONNECTING,
                detail = "waiting ${waitMs}ms",
                network = networkMonitor.describe(),
                attempt = attempt,
            )
            awaitReconnectWindow(waitMs, reconnectWake)
            // Waiting for a network is not an attempt. The counter was already spent above, so it is
            // handed back before parking — otherwise a long outage would arrive with an exhausted
            // ladder the moment it ended.
            if (!networkMonitor.online.value) {
                reconnectAttempts[hostId] = attempt - 1
                updateTab(hostId) {
                    it?.copy(state = SessionConnectionState.RECONNECTING, lastError = "Waiting for a network…")
                }
                networkMonitor.online.first { it }
            }
            if (tabs.value.none { it.hostId == hostId }) return@launch
            // Somebody else brought this host back while the ladder waited - a manual Reconnect, a UI
            // that adopted the session, the service's restore pass - and there is nothing left to do.
            //
            // The test is the *shell*, not the session. `isLive` was true for a live transport with no
            // pty on it, which is exactly what a background restore installs, so a restore pass landing
            // during the backoff ended the ladder and left the tab saying "Reconnecting" over a session
            // that only needed a shell opened on it - permanently, because nothing else was scheduled.
            // Falling through hands it to [connect], which adopts it under the dial gate and opens that
            // shell without a second login. See [adoptStoredSession].
            if (sessionStore.adoptable(hostId) != null) return@launch
            val host = runCatching { hostRepository.hosts.first() }.getOrNull()?.firstOrNull { it.id == hostId }
            if (host == null) {
                // The profile was deleted while the ladder was waiting: nothing left to reconnect to.
                reconnectAttempts.remove(hostId)
                return@launch
            }
            updateTab(hostId) {
                it?.copy(
                    state = SessionConnectionState.RECONNECTING,
                    lastError = "Reconnecting · attempt $attempt of $maxAttempts · $endReason",
                )
            }
            connect(host, resuming = true)
        }
        reconnectJobs.put(hostId, job)?.cancel()
        job.invokeOnCompletion { reconnectJobs.remove(hostId, job) }
    }

    /**
     * Keeps scrolled-back text still while new output arrives underneath it.
     *
     * The offset is measured from the bottom of the buffer, because that is the coordinate the live
     * view needs - "zero" has to mean "following the output" no matter how long the scrollback is. The
     * cost is that every line the shell prints moves the window the offset describes, so a user reading
     * output halfway up a busy log would watch the text slide away under their eyes while they read.
     * Adding the growth back cancels that exactly. Clamped by [AnsiTerminalBuffer.maxScrollOffset]
     * because the buffer also shrinks from the top once the scrollback limit starts evicting lines, and
     * an offset past the top would otherwise stick to a line that no longer exists.
     *
     * A view that is already live is left alone - it is *supposed* to move.
     */
    private fun pinScrollback(hostId: String, buffer: AnsiTerminalBuffer, linesBefore: Int) {
        val offset = scrollOffsets[hostId] ?: return
        if (offset <= 0) return
        val growth = buffer.lineCount() - linesBefore
        if (growth <= 0) return
        val ceiling = buffer.maxScrollOffset(buffer.viewportRows)
        scrollOffsets[hostId] = (offset + growth).coerceIn(0, ceiling)
    }

    /**
     * The viewport the renderer draws: only the rows on screen, at the offset the user scrolled to.
     *
     * Skipped entirely while nothing is collecting [frames], which is the state the app is in whenever
     * the terminal is not the visible destination — backgrounded, on the Files tab, or behind a dialog.
     * The session is deliberately *not* paused with it: [launchTerminalCollector] keeps draining the
     * pty, so the remote shell never blocks on a full window and the scrollback is complete the moment
     * the user comes back. Only the per-frame copy is skipped, and that is the expensive part —
     * [AnsiTerminalBuffer.frame] allocates a fresh list per row plus a list per row's cells, up to 30
     * times a second, and on a low-end device that is a measurable amount of a session spent producing
     * frames for a screen that is not on. [republishFrames] makes good on it as soon as anyone looks.
     *
     * `update`, not `value = value + …`: there is one collector coroutine per session and they publish
     * concurrently, so the read-modify-write on the map has to be a compare-and-set or two hosts
     * printing at once can lose one of the entries until the next frame.
     */
    private fun publishTerminalFrame(hostId: String, buffer: AnsiTerminalBuffer) {
        if (!isDisplaying(hostId, buffer)) return
        if (terminalFrames.subscriptionCount.value == 0) return
        val frame = buffer.frame(scrollOffsets[hostId] ?: 0)
        // Asked a second time, from inside the update: the check above only decides whether the frame
        // is worth building. See [isDisplaying] for why the write is where the question has to be
        // settled. [newerTerminalFrame] settles the other question the write has to answer: whether
        // this frame is still the newest one anybody built.
        terminalFrames.update { current ->
            if (!isDisplaying(hostId, buffer)) current
            else current + (hostId to newerTerminalFrame(current[hostId], frame))
        }
    }


    /**
     * Rebuilds every live terminal's frame, for the moment something starts drawing them again.
     *
     * Every buffer rather than only the ones known to have changed, and that is not laziness. Tracking
     * which hosts were skipped invites a race the tracking cannot win: a collector that has already
     * checked [MutableStateFlow.subscriptionCount] and found it zero will mark its host stale *after*
     * this has run, and if its output then stops - which is exactly what a session sitting at a prompt
     * does - nothing would rebuild that frame until the next byte arrived. The user would be looking at
     * output from before the app was backgrounded with no way to know it. Rebuilding all of them costs
     * one viewport copy per open session, once per foreground transition, and cannot get that wrong.
     */
    private fun republishFrames() {
        if (terminalBuffers.isEmpty()) return
        terminalFrames.update { current ->
            current + terminalBuffers.entries.associate { (hostId, buffer) ->
                // Same guard as the collector's publish, for the same reason: this builds one snapshot
                // per open session and a session that is printing can publish a newer one in between.
                hostId to newerTerminalFrame(current[hostId], buffer.frame(scrollOffsets[hostId] ?: 0))
            }
        }
    }

    /**
     * The whole scrollback as plain text, for the features that need words rather than cells:
     * in-terminal search, the global search, and Save logs / Save text / Save screen.
     *
     * Throttled far harder than the frame, and separately from it, because it is the expensive half.
     * [AnsiTerminalBuffer.plainText] walks every cell of the entire buffer - up to 2 000 lines - so at
     * frame rate it would allocate hundreds of thousands of characters a second to serve a search field
     * that is usually closed. Once a second is imperceptible for a text search and costs about
     * three per cent of what the frame path does.
     *
     * [force] is for the end of a session, where "the last second of output" is not something the user
     * can wait for.
     */
    private fun publishTerminalText(hostId: String, buffer: AnsiTerminalBuffer, force: Boolean = false): Boolean {
        // Reported as published rather than as throttled: there is nothing to catch up to.
        if (!isDisplaying(hostId, buffer)) return true
        val now = SystemClock.elapsedRealtime()
        val last = textPublishedAt[hostId]
        if (!force && last != null && now - last < TERMINAL_TEXT_MS) return false
        textPublishedAt[hostId] = now
        val text = buffer.plainText().takeLast(MAX_TERMINAL_CHARS)
        // Inside the update, for the reason [isDisplaying] gives: a whole scrollback is the largest
        // thing a closed session can leave behind.
        terminalOutput.update { if (isDisplaying(hostId, buffer)) it + (hostId to text) else it }
        // And the throttle stamp with it, which is written above before the answer is known.
        if (!isDisplaying(hostId, buffer)) textPublishedAt.remove(hostId)
        return true
    }

    /**
     * Whether [buffer] is still the terminal this host displays, and so whether publishing is wanted.
     *
     * Publishing is asynchronous with tearing a session down. [closeTab] and [deleteHost] cancel a
     * collector and then forget the host's frame and transcript, but a cancelled collector does not
     * stop where it stands: it finishes the iteration it was in and then runs its teardown, either of
     * which can land after the cleanup and put a whole scrollback back into a map nothing will ever
     * read again - retained for the life of the ViewModel. Both callers therefore drop the buffer
     * *before* they cancel anything, which makes this the reliable answer to "is anyone still going to
     * draw this?".
     *
     * Identity rather than presence, because a reconnect deliberately keeps the same buffer so the
     * scrollback survives it; the outgoing collector and the incoming one share it, and a last
     * publication from the outgoing one is the same content the new one would publish anyway.
     *
     * Reliable only if it is asked *as part of* the write, which is why both publishers ask it inside
     * their `update` lambda rather than only on the way in. Collectors run on [Dispatchers.Default]
     * and [closeTab] runs on the main thread, so a check at the top of a publisher and the write at
     * the bottom are two steps with a whole teardown able to fit between them: the publisher passed
     * the check, `closeTab` dropped the buffer and then removed the host's frame and transcript, and
     * the publisher put them straight back - permanently, since nothing runs after a close. Asking
     * inside the lambda makes the answer part of the compare-and-set: a publisher that wins the race
     * has its entry removed by the removal that follows, and one that loses it reads the map
     * `closeTab` has already emptied and writes nothing. That leak was a viewport of cells and up to
     * [MAX_TERMINAL_CHARS] of scrollback per closed tab, held for the life of the ViewModel, and it is
     * what `repeatedConnectAndDisconnectCyclesLeaveNothingBehind` caught.
     */
    private fun isDisplaying(hostId: String, buffer: AnsiTerminalBuffer): Boolean =
        terminalBuffers[hostId] === buffer

    /**
     * Whether [generation] is still the dial [hostId] is on. See [dialGenerations].
     *
     * Absent means yes, deliberately: nothing removes a host's counter, so the only way to read null
     * here is for the counter to have gone with the whole map, and refusing to report at all would be
     * the worse of the two failures.
     */
    private fun isCurrentDial(hostId: String, generation: Long): Boolean =
        (dialGenerations[hostId]?.get() ?: generation) == generation

    fun acceptHostKey() {
        val challenge = hostKeyChallenge.value ?: return
        // The connection goes ahead either way - the user has just said they trust this key - but if
        // the decision did not reach disk they have to be told, because the only symptom otherwise is
        // being asked the same question again after every restart, which looks like the app ignoring
        // the answer rather than a storage problem.
        if (!sshConnectionManager.trustHost(challenge)) {
            report("Connected, but this host key could not be saved - you will be asked again")
        }
        hostKeyChallenge.value = null
        // The question has been answered, so it must not be put again to whatever collects next:
        // see `SshConnectionManager.challengeHandled`.
        sshConnectionManager.challengeHandled()
        knownHostsState.value = sshConnectionManager.knownHosts()
        pendingConnection?.let {
            connect(it.host, it.password, it.keyPair, it.keyBytes, it.keyPassphrase, resuming = it.resuming)
        }
    }

    fun refreshKnownHosts() {
        knownHostsState.value = sshConnectionManager.knownHosts()
    }

    fun forgetKnownHost(key: String) {
        val (host, port) = runCatching {
            val idx = key.lastIndexOf(':')
            key.substring(0, idx) to key.substring(idx + 1).toInt()
        }.getOrNull() ?: return
        // A revocation that only happened in memory comes back on the next launch, so a failure here
        // is reported rather than swallowed: the user believes they have removed the key.
        if (!sshConnectionManager.removeKnownHost(host, port)) {
            report("Removed for now, but the change could not be saved - it may return after a restart")
        }
        refreshKnownHosts()
    }

    fun clearKnownHosts() {
        if (!sshConnectionManager.clearKnownHosts()) {
            report("Cleared for now, but the change could not be saved - they may return after a restart")
        }
        refreshKnownHosts()
    }

    fun rejectHostKey() {
        hostKeyChallenge.value = null
        sshConnectionManager.challengeHandled()
        pendingConnection?.host?.id?.let { id ->
            updateTab(id) { it?.copy(state = SessionConnectionState.ERROR, lastError = "Host key was rejected") }
            diagnostics.record(id, SessionEvent.CONNECT_FAILED, state = SessionConnectionState.ERROR, detail = "host key rejected")
        }
        pendingConnection = null
    }

    /**
     * Sends a whole line, as the command bar and the snippet list do.
     *
     * The newline is rewritten to a carriage return by [TerminalKeys.normalizeNewlines]: a pty runs
     * with ICRNL, so CR is what the Enter key actually transmits and what the remote line discipline
     * translates. Sending LF instead worked with `bash` and silently did nothing useful in a few
     * full-screen programs that read raw input.
     */
    fun sendInput(hostId: String, value: String) {
        if (value.endsWith("\n") || value.endsWith("\r")) {
            recordCommand(hostId, value.trimEnd('\n', '\r'))
        }
        writeToTerminal(hostId, TerminalKeys.encode(TerminalKeys.normalizeNewlines(value)))
    }

    /**
     * Sends text the user typed, one keystroke at a time in the normal case.
     *
     * This is the path the software keyboard uses, so it also has to feed the recent-commands list -
     * see [typedLines] for why that has to be reconstructed rather than observed.
     */
    fun sendText(hostId: String, text: String) {
        if (text.isEmpty()) return
        accumulateTyped(hostId, text)
        writeToTerminal(hostId, TerminalKeys.encode(text))
    }

    /**
     * Sends a named key, encoded for the mode the remote side has the terminal in.
     *
     * The mode matters: with DECCKM set - which `vi`, `less` and anything using ncurses do - an arrow
     * key is `ESC O A`, and outside it `ESC [ A`. Sending the wrong one puts a stray `A` in the file
     * instead of moving the cursor, which is why this reads [TerminalFrame.applicationCursorKeys] from
     * the buffer rather than assuming.
     */
    fun sendKey(
        hostId: String,
        key: TerminalKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        val buffer = terminalBuffers[hostId]
        val applicationCursorKeys = buffer?.applicationCursorKeysEnabled() ?: false
        val modifiers = TerminalModifiers(ctrl = ctrl, alt = alt, shift = shift)
        when (key) {
            TerminalKey.ENTER -> recordCommand(hostId, typedLines.remove(hostId)?.toString().orEmpty())
            TerminalKey.BACKSPACE -> typedLines[hostId]?.let { line -> if (line.isNotEmpty()) line.setLength(line.length - 1) }
            else -> Unit
        }
        writeToTerminal(hostId, TerminalKeys.encode(key, modifiers, applicationCursorKeys))
    }

    /** Sends a single character with its modifiers - the Ctrl row, and the IME's own key events. */
    fun sendChar(hostId: String, char: Char, ctrl: Boolean = false, alt: Boolean = false) {
        if (!ctrl && !alt) {
            accumulateTyped(hostId, char.toString())
        } else if (ctrl) {
            // Ctrl-C, Ctrl-U and friends all abandon the line one way or another. Keeping a partial
            // command after them would file text the user explicitly discarded.
            typedLines.remove(hostId)
        }
        writeToTerminal(hostId, TerminalKeys.encode(char, TerminalModifiers(ctrl = ctrl, alt = alt)))
    }

    /**
     * Pastes [text] into the remote shell, bracketed when the remote side asked for it.
     *
     * Whether to bracket is the remote program's decision, not a preference: an editor that enabled
     * bracketed paste is waiting to be told where a paste begins and ends so it can insert the text
     * literally, and one that did not would show the markers as garbage. [AnsiTerminalBuffer] tracks
     * the mode as the program sets it, so the answer is always the current one.
     */
    /**
     * Pastes whatever is on the system clipboard into [hostId]'s shell.
     *
     * The read lives here rather than in the composable so there is one audited clipboard boundary in
     * the app - Compose's own `LocalClipboardManager` is deprecated, and going through
     * [SecureClipboard] also means a paste is subject to the same sensitivity handling as a copy. An
     * empty clipboard is reported, because a Paste that appears to do nothing is indistinguishable
     * from a broken one.
     */
    fun pasteFromClipboard(hostId: String) {
        val text = secureClipboard.paste()
        if (text.isNullOrEmpty()) {
            report("There is nothing on the clipboard to paste")
            return
        }
        pasteIntoTerminal(hostId, text)
    }

    fun pasteIntoTerminal(hostId: String, text: String) {
        if (text.isEmpty()) return
        val bracketed = terminalBuffers[hostId]?.bracketedPasteEnabled() ?: false
        writeToTerminal(hostId, TerminalKeys.paste(text, bracketed))
    }

    /** Scrolls [hostId] to [offset] lines above the live bottom; 0 follows the output again. */
    fun scrollTerminal(hostId: String, offset: Int) {
        val buffer = terminalBuffers[hostId] ?: return
        val ceiling = buffer.maxScrollOffset(buffer.viewportRows)
        val clamped = offset.coerceIn(0, ceiling)
        if (clamped == 0) scrollOffsets.remove(hostId) else scrollOffsets[hostId] = clamped
        publishTerminalFrame(hostId, buffer)
    }

    /** Scrolls by [delta] lines - positive is back into the history. */
    fun scrollTerminalBy(hostId: String, delta: Int) {
        scrollTerminal(hostId, (scrollOffsets[hostId] ?: 0) + delta)
    }

    /** The text of a selection, in absolute buffer coordinates, for copy. */
    fun terminalSelectionText(hostId: String, fromLine: Int, fromColumn: Int, toLine: Int, toColumn: Int): String =
        terminalBuffers[hostId]?.textIn(fromLine, fromColumn, toLine, toColumn).orEmpty()

    /** One whole line of the buffer, for double-tap word selection and select-line. */
    fun terminalLineText(hostId: String, line: Int): String =
        terminalBuffers[hostId]?.textIn(line, 0, line, Int.MAX_VALUE).orEmpty()

    /**
     * Hands [bytes] to the session's outbound queue, in the order the caller produced them.
     *
     * Inline, on the calling thread, and that ordering is the whole point. This used to wrap the write
     * in `viewModelScope.launch(Dispatchers.IO)` - the ordinary way to keep I/O off the main thread,
     * and wrong here twice over. `launch` on a multi-threaded dispatcher promises nothing about the
     * order two launches run in, and every keystroke went through here as its own launch, racing the
     * one before it. Typing `whoami` and pressing Enter sent the carriage return ahead of the word
     * often enough to be the normal case: the shell ran an empty line and printed a fresh prompt while
     * `whoami` sat unread in its input buffer, waiting for an Enter that had already been spent. On a
     * real session that shows up as characters landing in the wrong order and commands running
     * half-typed, which no amount of correctness further down can undo.
     *
     * There is also no I/O here to move off the thread. [TerminalChannel.writeBytes] appends to an
     * unbounded `LinkedBlockingQueue` that Apache MINA's own pump drains, so it holds a lock for the
     * length of one enqueue and cannot block on the network. Calling it directly is both ordered and
     * cheaper than dispatching.
     *
     * A keystroke for a host that is no longer connected is dropped silently: the tab's own state
     * already says it is disconnected, and a report per keystroke would bury it.
     */
    private fun writeToTerminal(hostId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching { channels[hostId]?.writeBytes(bytes) }
    }

    /** Appends printable text to the reconstructed command line. See [typedLines]. */
    private fun accumulateTyped(hostId: String, text: String) {
        val line = typedLines.getOrPut(hostId) { StringBuilder() }
        for (char in text) {
            when {
                char == '\n' || char == '\r' -> {
                    recordCommand(hostId, line.toString())
                    line.setLength(0)
                }
                // Control characters are commands to the shell, not part of what was typed.
                char.code >= 0x20 && char.code != 0x7F -> line.append(char)
            }
        }
        if (line.length > MAX_TYPED_LINE) line.delete(0, line.length - MAX_TYPED_LINE)
    }

    private fun recordCommand(hostId: String, command: String) {
        val trimmed = command.trim()
        typedLines.remove(hostId)
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + commandHistory.value[hostId].orEmpty()).distinct().take(MAX_HISTORY)
        commandHistory.value = commandHistory.value + (hostId to updated)
    }
    /**
     * Applies a new viewport size to both halves of the terminal: the local grid and the remote pty.
     *
     * Both are required. Resizing only the buffer would leave the remote side drawing for the old
     * width, so a full-screen program would wrap its own status line; telling only the remote side
     * would leave the parser wrapping text the shell had already fitted. This runs on rotation, on a
     * multi-window drag and when the software keyboard opens.
     */
    fun resizeTerminal(hostId: String, columns: Int, rows: Int) {
        val buffer = terminalBuffers[hostId]
        buffer?.resize(columns, rows)
        if (buffer != null) {
            // A taller viewport can leave the stored offset past the top of a short buffer.
            scrollOffsets[hostId]?.let { offset ->
                // The buffer's own height, which is the requested one clamped to what a terminal may
                // be; asking for 4 000 rows and then measuring the ceiling against 4 000 would clear
                // an offset that is still perfectly valid.
                val ceiling = buffer.maxScrollOffset(buffer.viewportRows)
                if (offset > ceiling) {
                    if (ceiling <= 0) scrollOffsets.remove(hostId) else scrollOffsets[hostId] = ceiling
                }
            }
            publishTerminalFrame(hostId, buffer)
        }
        // Remembered for the *next* pty this host opens. The composable only reports a size when the
        // size it measures changes, and a reconnect does not change the screen - so without this a new
        // channel kept the 120x40 default while the UI, having already reported the real size once,
        // never mentioned it again. Every full-screen program on a reconnected session was drawn for a
        // terminal twice the width of the phone.
        val previous = ptySizes.put(hostId, columns to rows)
        // Only when the geometry really moved. The composable reports on every measurement pass, and
        // an entry per pass would push the interesting history out of the ring within seconds of
        // scrolling.
        if (previous != columns to rows) {
            diagnostics.record(
                hostId,
                SessionEvent.PTY_RESIZED,
                pty = "${columns}x$rows",
                detail = previous?.let { "was ${it.first}x${it.second}" },
            )
        }
        // sendWindowChange writes an SSH packet, which blocks when the transport is
        // congested; keep it off the main thread so a stalled link cannot cause an ANR.
        transportScope.launch { runCatching { channels[hostId]?.resize(columns, rows) } }
    }

    /**
     * Lists [path] on [host]. When [path] is null the directory this host is already showing is listed
     * again, and a host that is showing nothing yet gets the server's own home directory - resolved by
     * canonicalising "." over SFTP and cached per host in [homePaths]. The old "/home/$username" guess
     * pointed at a non-existent directory for root and for every server that does not lay out home
     * directories under /home.
     *
     * A host that is not connected is reported when the user asked for this listing — pull-to-refresh,
     * tapping a directory, or returning from a file operation — because otherwise the button does
     * nothing and the stale listing stays on screen with no explanation.
     *
     * [announce] is false for the one caller that is not a request: arriving on the Files tab refreshes
     * as a side effect of navigating there. On a clean install nothing is connected, so announcing that
     * would put an error in front of every user the first time they open the tab, about something they
     * never asked for.
     */
    fun refreshFiles(host: HostProfile, path: String? = null, announce: Boolean = true) {
        transportScope.launch {
            if (sessions[host.id] == null) {
                if (announce) report("${host.name} is not connected")
                return@launch
            }
            try {
                // path ?: where this host already is. Null means "list again", which is what the
                // Refresh button, arriving on the Files tab and switching sessions all ask for - and
                // every one of them used to land in the home directory instead, because null was
                // resolved to the home path further down. Refresh navigated away from the directory
                // it was refreshing, and a session the user came back to had forgotten where it was.
                listRemote(host, path ?: remoteListings.value[host.id]?.path)
                // A listing that worked is the same proof the auto-login looks for, so a host whose
                // SFTP failed earlier — or one that never tried, because the switch is off — stops
                // claiming to be broken the moment the user gets a directory out of it.
                updateTab(host.id) { tab ->
                    if (tab?.sftpState == SftpSessionState.READY) tab
                    else tab?.copy(sftpState = SftpSessionState.READY, sftpError = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Emptied for this host only, and left standing where it was: a listing that failed
                // says nothing about any other session's directory, and nothing about this one either
                // beyond "the contents could not be read". Keeping the path means the header still
                // names the directory the error is about instead of teleporting the user home.
                val stranded = path ?: remoteListings.value[host.id]?.path ?: homePaths[host.id]
                    ?: fallbackHome(host.username)
                remoteListings.update { it + (host.id to RemoteListing(stranded, emptyList())) }
                report("Could not list directory", error)
            }
        }
    }

    /**
     * Opens an SFTP channel on [host]'s existing session and lists [path], or the remote home directory
     * when [path] is null.
     *
     * Null means home here rather than "wherever this host was", which is the resolution [refreshFiles]
     * applies before it calls: a login starts at home even when a previous session of the same host had
     * been browsing somewhere else, because that directory can be gone by the time the host comes back,
     * and a listing that fails is recorded as SFTP being broken.
     *
     * The one place both the auto-login and pull-to-refresh go through, so they cannot disagree about
     * what "SFTP works" means or about which directory is showing. Throws rather than reporting: the
     * two callers want opposite things from a failure — one records it on the tab, the other leaves
     * the browser's own error on screen.
     */
    private suspend fun listRemote(host: HostProfile, path: String?) {
        val session = sessions[host.id] ?: throw IllegalStateException("${host.name} is not connected")
        sshConnectionManager.withSftp(session) { sftp ->
            val target = path ?: homePaths[host.id] ?: sftpDirectoryService.homeDirectory(sftp, host.username)
                .also { homePaths[host.id] = it }
            val listing = sftpDirectoryService.list(sftp, target)
            remoteListings.update { it + (host.id to RemoteListing(target, listing)) }
        }
    }

    /**
     * Logs in to SFTP on [host]'s live session and lists its home directory, recording the outcome on
     * the session tab.
     *
     * There is no second set of credentials to supply, and that is worth being explicit about because
     * the setting's name suggests otherwise: SFTP is a subsystem channel on the transport the SSH
     * handshake already authenticated, so it is logged in with exactly the password or key the profile
     * connected with. What can still fail is the server's willingness to serve it — no `sftp-server`
     * subsystem, an account confined to a shell, a session that dropped in between — and every one of
     * those leaves the terminal working.
     *
     * Which is why nothing here touches the SSH session or the tab's [SessionConnectionState]. A
     * failure is recorded, said once in the status message, and left; tearing down a working shell
     * because the file browser is unavailable would be the app breaking a feature the server had not.
     */
    fun loginSftp(host: HostProfile) {
        sftpJobs.remove(host.id)?.cancel()
        updateTab(host.id) { it?.copy(sftpState = SftpSessionState.CONNECTING, sftpError = null) }
        val job = transportScope.launch {
            try {
                listRemote(host, null)
                updateTab(host.id) { it?.copy(sftpState = SftpSessionState.READY, sftpError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val reason = describeSftpFailure(error)
                updateTab(host.id) { it?.copy(sftpState = SftpSessionState.FAILED, sftpError = reason) }
                report("SFTP on ${host.name}: $reason")
            }
        }
        sftpJobs[host.id] = job
        job.invokeOnCompletion { sftpJobs.remove(host.id, job) }
    }

    /**
     * Directory the file browser is currently pointing at for [host].
     *
     * [host]'s own directory. Read from the single global path, this returned whichever host had
     * listed last, so an upload started on one session could be addressed to a directory that only
     * existed on another.
     */
    private fun currentRemoteDir(host: HostProfile): String =
        remoteListings.value[host.id]?.path ?: homePaths[host.id] ?: fallbackHome(host.username)

    fun navigateRemote(host: HostProfile, path: String) = refreshFiles(host, path)

    fun setLocalRoot(uri: Uri) {
        localDirUri.value = uri.toString()
        listLocal(uri)
    }

    fun navigateLocal(uri: Uri) {
        localDirUri.value = uri.toString()
        listLocal(uri)
    }

    fun navigateLocalUp() {
        val current = localDirUri.value?.let(Uri::parse) ?: return
        val parent = LocalFileBrowser.parent(context, current) ?: return
        localDirUri.value = parent.toString()
        listLocal(parent)
    }

    fun uploadLocalFiles(host: HostProfile, files: List<LocalFile>) {
        files.forEach { uploadLocal(host, it) }
    }

    fun downloadRemoteFiles(host: HostProfile, files: List<RemoteFile>) {
        files.forEach { downloadToLocal(host, it) }
    }

    fun uploadLocal(host: HostProfile, file: LocalFile) {
        val directory = currentRemoteDir(host)
        val target = if (directory.endsWith('/')) directory + file.name else "$directory/${file.name}"
        val item = TransferItem(
            name = file.name,
            direction = TransferDirection.UPLOAD,
            hostName = host.name,
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = formatBytes(file.size),
            hostId = host.id,
            remotePath = target,
            localUri = file.uri.toString(),
            totalBytes = file.size.takeIf { it > 0 },
        )
        transportScope.launch {
            transferRepository.save(item)
            startUploadJob(host, item, file.uri, target, file.size.takeIf { it > 0 })
        }
    }

    /**
     * The directory remote operations should target: the browsed path, else the home
     * directory the server reported, else a conservative guess. Public so the UI can build
     * upload targets without re-deriving "/home/$username", which is wrong for root and for
     * any server that does not lay out homes under /home.
     */
    fun remoteDirectory(host: HostProfile): String = currentRemoteDir(host)

    fun cancelTransfer(id: String) {
        transferCoordinator.pause(id)
        launchGuarded("Could not remove the transfer") { transferRepository.delete(id) }
    }

    /**
     * Mirrors the picked local folder onto the server.
     *
     * Each file is awaited in turn on one shared SFTP channel ([TransferCoordinator.uploadAwait])
     * instead of being handed to the fire-and-forget `upload`. The previous version launched
     * background jobs from inside `openSftp(...).use { }`, so the channel was closed the moment
     * the loop ended and every queued transfer failed without ever moving a byte.
     */
    fun syncToRemote(host: HostProfile) {
        val rootUri = localDirUri.value?.let(Uri::parse) ?: run {
            report("Pick a local folder first")
            return
        }
        val remoteRoot = currentRemoteDir(host)
        transportScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@launch report("The local folder is no longer accessible")
            try {
                val localTree = LocalSyncIndex.walk(rootDoc)
                sshConnectionManager.withSftp(session) { sftp ->
                    val remoteTree = sftpDirectoryService.listTree(sftp, remoteRoot)
                    val remoteDirs = remoteTree.values.filter { it.isDirectory }.map { it.path }.toMutableSet()
                    val remoteFiles = remoteTree.values.filterNot { it.isDirectory }
                    for ((rel, doc, size) in localTree) {
                        if (doc.isDirectory) continue
                        val remoteDir = parentOf(rel)
                        if (remoteDir.isNotEmpty()) {
                            val dirPath = joinRemote(remoteRoot, remoteDir)
                            if (remoteDirs.add(dirPath)) {
                                runCatching { sftpDirectoryService.createDirectory(sftp, dirPath) }
                            }
                        }
                        val remotePath = joinRemote(remoteRoot, rel)
                        val existing = remoteFiles.firstOrNull { it.path == remotePath }
                        val needsUpload = existing == null || existing.size != size ||
                            (doc.lastModified() / 1_000L - existing.modifiedEpochSeconds).let { it < -2 || it > 2 }
                        if (!needsUpload) continue
                        val input = runCatching { context.contentResolver.openInputStream(doc.uri) }.getOrNull() ?: continue
                        val item = TransferItem(
                            name = doc.name ?: rel,
                            direction = TransferDirection.UPLOAD,
                            hostName = host.name,
                            progress = 0f,
                            status = TransferStatus.QUEUED,
                            sizeLabel = formatBytes(size),
                            hostId = host.id,
                            remotePath = remotePath,
                            localUri = doc.uri.toString(),
                            totalBytes = size,
                        )
                        transferRepository.save(item)
                        input.use { transferCoordinator.uploadAwait(item, sftp, it, remotePath, size) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report("Sync to ${host.name} failed", error)
            }
            refreshFiles(host, remoteRoot)
        }
    }

    fun syncFromRemote(host: HostProfile) {
        val rootUri = localDirUri.value?.let(Uri::parse) ?: run {
            report("Pick a local folder first")
            return
        }
        val remoteRoot = currentRemoteDir(host)
        transportScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@launch report("The local folder is no longer accessible")
            try {
                sshConnectionManager.withSftp(session) { sftp ->
                    val remoteTree = sftpDirectoryService.listTree(sftp, remoteRoot)
                    for (remote in remoteTree.values) {
                        if (remote.isDirectory) continue
                        val rel = remote.path.removePrefix(remoteRoot.trimEnd('/') + "/")
                        // A `return@forEach` inside the segment loop used to label that inner
                        // loop, so an undeletable/uncreatable directory silently downloaded the
                        // file into the wrong folder. mkdirs() reports failure to this loop.
                        val dir = mkdirs(rootDoc, parentOf(rel)) ?: continue
                        val localDoc = dir.findFile(remote.name)
                        if (localDoc != null && localDoc.length() == remote.size) continue
                        val target = localDoc
                            ?: runCatching { dir.createFile("application/octet-stream", remote.name) }.getOrNull()
                            ?: continue
                        val out = runCatching { context.contentResolver.openOutputStream(target.uri, "w") }.getOrNull() ?: continue
                        val item = TransferItem(
                            name = remote.name,
                            direction = TransferDirection.DOWNLOAD,
                            hostName = host.name,
                            progress = 0f,
                            status = TransferStatus.QUEUED,
                            sizeLabel = formatBytes(remote.size),
                            hostId = host.id,
                            remotePath = remote.path,
                            localUri = target.uri.toString(),
                            totalBytes = remote.size,
                        )
                        transferRepository.save(item)
                        out.use { transferCoordinator.downloadAwait(item, sftp, remote.path, it) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report("Sync from ${host.name} failed", error)
            }
            listLocal(rootUri)
        }
    }

    /** Walks/creates [relativePath] under [root], returning null if any segment cannot be created. */
    private fun mkdirs(root: DocumentFile, relativePath: String): DocumentFile? {
        var dir = root
        relativePath.split('/').filter(String::isNotBlank).forEach { segment ->
            dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                ?: runCatching { dir.createDirectory(segment) }.getOrNull()
                ?: return null
        }
        return dir
    }

    /**
     * Streams [file] from one server straight to another, without landing it on the device.
     *
     * Reported at every step, and it is the last operation in this class that was not. The body was
     * unguarded, and an uncaught throw in a `viewModelScope` coroutine reaches the thread's default
     * handler — see [writeSetting] — so the ordinary ways this fails killed the app rather than
     * explaining themselves: a destination directory that does not exist or cannot be written, a
     * source file removed since the listing was drawn, or a *directory* chosen for an operation that
     * copies a single file, which the actions sheet offers.
     *
     * A destination that is not already connected is dialled here and hung up in the `finally`. That
     * session used to be left open for the life of the process: it was never put in [sessions], so
     * nothing could find it to close and nothing on screen showed that it existed.
     */
    fun sendRemoteTo(sourceHost: HostProfile, file: RemoteFile, destHost: HostProfile, destPath: String) {
        transportScope.launch {
            val source = sessions[sourceHost.id]
                ?: return@launch report("${sourceHost.name} is not connected")
            // Only a session opened *here* may be closed here. An already-open one belongs to its tab.
            var dialled: ClientSession? = null
            try {
                val dest = sessions[destHost.id] ?: run {
                    val password = sessionRegistry.credential(destHost.id)
                    val keyPair = sessionRegistry.keyBytes(destHost.id)?.let { bytes ->
                        runCatching { SshKeyLoader.load(bytes, "${destHost.username}-key", sessionRegistry.keyPassphrase(destHost.id)) }.getOrNull()
                    }
                    sshConnectionManager.connect(destHost, password, keyPair).also { dialled = it }
                }
                val target = if (destPath.endsWith('/')) destPath + file.name else "$destPath/${file.name}"
                sshConnectionManager.withSftp(source) { from ->
                    sshConnectionManager.withSftp(dest) { to ->
                        sftpDirectoryService.copyAcross(from, to, file.path, target)
                    }
                }
                // Worth saying: the destination is not the directory on screen, so a successful
                // server-to-server copy is otherwise indistinguishable from one that never ran.
                report("Sent ${file.name} to ${destHost.name}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report("Could not send ${file.name} to ${destHost.name}", error)
            } finally {
                dialled?.let { session -> runCatching { session.close(false) } }
            }
        }
    }

    private fun parentOf(rel: String): String = rel.trimEnd('/').substringBeforeLast('/', "")

    fun scheduleUpload(host: HostProfile, file: LocalFile, scheduledAt: Long, repeatMinutes: Long? = null) {
        val directory = currentRemoteDir(host)
        val target = if (directory.endsWith('/')) directory + file.name else "$directory/${file.name}"
        val item = TransferItem(
            name = file.name,
            direction = TransferDirection.UPLOAD,
            hostName = host.name,
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = formatBytes(file.size),
            hostId = host.id,
            remotePath = target,
            localUri = file.uri.toString(),
            totalBytes = file.size.takeIf { it > 0 },
            scheduledAt = scheduledAt,
            repeatMinutes = repeatMinutes,
        )
        transferCoordinator.schedule(item, scheduledAt)
    }

    fun scheduleDownload(host: HostProfile, file: RemoteFile, scheduledAt: Long, repeatMinutes: Long? = null) {
        // Both of these returned silently, which made "Schedule" a button that did nothing and said
        // nothing — the reason is the same as it is in downloadToLocal, whose deferred twin this is.
        val localDir = localDirUri.value?.let(Uri::parse) ?: return report("Pick a local folder first")
        viewModelScope.launch {
            val target = runCatching {
                DocumentFile.fromTreeUri(context, localDir)?.createFile("application/octet-stream", file.name)
            }.getOrNull()
                ?: return@launch report("Cannot create ${file.name} in the selected folder")
            val item = TransferItem(
                name = file.name,
                direction = TransferDirection.DOWNLOAD,
                hostName = host.name,
                progress = 0f,
                status = TransferStatus.QUEUED,
                sizeLabel = formatBytes(file.size),
                hostId = host.id,
                remotePath = file.path,
                localUri = target.uri.toString(),
                totalBytes = file.size,
                scheduledAt = scheduledAt,
                repeatMinutes = repeatMinutes,
            )
            transferCoordinator.schedule(item, scheduledAt)
        }
    }

    /**
     * Lists the picked local folder, and says so when it cannot be read.
     *
     * The failure used to become `emptyList()`, which is indistinguishable on screen from a folder
     * with nothing in it. That matters because the most common cause is not a transient error: a
     * persisted SAF grant is revoked when the user clears the app's access in Settings, when the
     * volume is unmounted, or when the folder is deleted, and the honest response is to ask for the
     * folder again rather than to show an empty pane that no amount of refreshing will fill.
     */
    private fun listLocal(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { LocalFileBrowser.list(context, uri) }
                .onSuccess { localFiles.value = it }
                .onFailure { error ->
                    localFiles.value = emptyList()
                    report(
                        if (error is LocalAccessUnavailableException) {
                            "Cannot read that folder any more - pick it again to restore access"
                        } else {
                            "Cannot read that folder"
                        },
                    )
                }
        }
    }

    fun startDownload(host: HostProfile, remote: RemoteFile, localUri: Uri) {
        val item = TransferItem(
            name = remote.name,
            direction = TransferDirection.DOWNLOAD,
            hostName = host.name,
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = formatBytes(remote.size),
            hostId = host.id,
            remotePath = remote.path,
            localUri = localUri.toString(),
            totalBytes = remote.size,
        )
        transportScope.launch {
            transferRepository.save(item)
            startDownloadJob(host, item, localUri, remote.path)
        }
    }

    fun downloadToLocal(host: HostProfile, remote: RemoteFile) {
        val localDir = localDirUri.value?.let(Uri::parse) ?: run {
            report("Pick a local folder first")
            return
        }
        transportScope.launch {
            val target = runCatching {
                DocumentFile.fromTreeUri(context, localDir)?.createFile("application/octet-stream", remote.name)
            }.getOrNull() ?: run {
                report("Cannot create ${remote.name} in the selected folder")
                return@launch
            }
            val item = TransferItem(
                name = remote.name,
                direction = TransferDirection.DOWNLOAD,
                hostName = host.name,
                progress = 0f,
                status = TransferStatus.QUEUED,
                sizeLabel = formatBytes(remote.size),
                hostId = host.id,
                remotePath = remote.path,
                localUri = target.uri.toString(),
                totalBytes = remote.size,
            )
            transferRepository.save(item)
            startDownloadJob(host, item, target.uri, remote.path)
        }
    }

    fun startUpload(host: HostProfile, localUri: Uri, remoteDirectory: String) {
        val name = displayName(localUri)
        val remotePath = if (remoteDirectory.endsWith('/')) remoteDirectory + name else "$remoteDirectory/$name"
        val size = runCatching { DocumentFile.fromSingleUri(context, localUri)?.length() }.getOrNull()?.takeIf { it > 0 }
        val item = TransferItem(
            name = name,
            direction = TransferDirection.UPLOAD,
            hostName = host.name,
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = size?.let(::formatBytes) ?: "—",
            hostId = host.id,
            remotePath = remotePath,
            localUri = localUri.toString(),
            totalBytes = size,
        )
        transportScope.launch {
            transferRepository.save(item)
            startUploadJob(host, item, localUri, remotePath, size)
        }
    }

    /**
     * Opens the local stream and the SFTP channel in order, handing both to the
     * coordinator, which closes them on every completion path. Each early exit releases
     * whatever it already opened and marks the transfer FAILED with a visible reason —
     * previously these paths returned silently and leaked a ContentResolver file
     * descriptor plus an SFTP channel per attempt.
     */
    private suspend fun startUploadJob(host: HostProfile, item: TransferItem, source: Uri, remotePath: String, totalBytes: Long?) {
        val session = sessions[host.id] ?: return failTransfer(item, "${host.name} is not connected")
        val stream = runCatching { context.contentResolver.openInputStream(source) }.getOrNull()
            ?: return failTransfer(item, "Cannot read ${item.name}")
        val sftp = runCatching { sshConnectionManager.openSftp(session) }.getOrNull() ?: run {
            runCatching { stream.close() }
            return failTransfer(item, "Cannot open an SFTP channel on ${host.name}")
        }
        transferCoordinator.upload(item, sftp, stream, remotePath, totalBytes)
    }

    private suspend fun startDownloadJob(host: HostProfile, item: TransferItem, destination: Uri, remotePath: String) {
        val session = sessions[host.id] ?: return failTransfer(item, "${host.name} is not connected")
        val stream = runCatching { context.contentResolver.openOutputStream(destination, "w") }.getOrNull()
            ?: return failTransfer(item, "Cannot write ${item.name}")
        val sftp = runCatching { sshConnectionManager.openSftp(session) }.getOrNull() ?: run {
            runCatching { stream.close() }
            return failTransfer(item, "Cannot open an SFTP channel on ${host.name}")
        }
        transferCoordinator.download(item, sftp, remotePath, stream)
    }

    private suspend fun failTransfer(item: TransferItem, message: String) {
        runCatching { transferRepository.save(item.copy(status = TransferStatus.FAILED)) }
        report(message)
    }

    /** Resolves a human-readable file name for a SAF/content URI. */
    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        val fromPath = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return (fromProvider ?: fromPath ?: "upload.bin").replace('/', '_')
    }

    /**
     * Runs one remote file operation on its own SFTP client, refreshes the listing, and tells the
     * user when it failed.
     *
     * Every operation below used to be a bare `runCatching { }` with no `onFailure`, which meant a
     * server that said no produced a listing that redrew unchanged and no message anywhere. The ways
     * to hit that are not exotic — they are most of what a file manager does wrong on a real server:
     * deleting a directory that is not empty (`rmdir` refuses, which is exactly what the delete
     * confirmation promises), renaming onto a name that already exists, creating a folder in a
     * directory the account cannot write, chmod on a read-only mount, any of them on a session that
     * has since dropped. A button that appears to do nothing is a worse bug than one that reports the
     * server's reason, and it is the kind users retry until they damage something.
     *
     * The refresh runs on both paths on purpose: a partly-applied batch has to be shown as it now is,
     * and a failure that was really a dropped session should not leave a stale listing on screen.
     */
    private fun fileOperation(host: HostProfile, what: String, path: String? = null, operation: suspend (SftpClient) -> Unit) {
        transportScope.launch {
            val session = sessions[host.id]
            if (session == null) {
                // Reachable: the sheet and its dialogs stay up across a disconnect, so the button is
                // still there to press after the session is gone.
                report("$what failed", IllegalStateException("not connected to ${host.name}"))
                return@launch
            }
            runCatching { sshConnectionManager.withSftp(session) { operation(it) } }
                .onFailure { if (it is CancellationException) throw it else report("$what failed", it) }
            refreshFiles(host, path ?: currentRemoteDir(host))
        }
    }

    /**
     * Applies [action] to every file, then throws once if any of them failed.
     *
     * Deliberately continues past a failure: deleting nine of ten selected files and abandoning the
     * rest because the tenth was busy is not what was asked for. But the failures still have to reach
     * the user, and only one message can be shown at a time, so they are summarised into a single
     * exception for [fileOperation] to report.
     */
    private suspend fun eachFile(files: List<RemoteFile>, action: suspend (RemoteFile) -> Unit) {
        val failed = mutableListOf<Pair<String, Throwable>>()
        files.forEach { file ->
            currentCoroutineContext().ensureActive()
            try {
                action(file)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failed += file.name to error
            }
        }
        if (failed.isEmpty()) return
        val (name, error) = failed.first()
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        throw IOException(
            if (failed.size == 1) "$name: $detail"
            else "${failed.size} of ${files.size} failed, starting with $name: $detail",
        )
    }

    fun createDirectory(host: HostProfile, name: String) {
        val base = currentRemoteDir(host)
        val path = if (base.endsWith('/')) base + name else "$base/$name"
        fileOperation(host, "Create folder", base) { sftpDirectoryService.createDirectory(it, path) }
    }

    fun deleteRemote(host: HostProfile, file: RemoteFile) {
        deleteRemoteFiles(host, listOf(file))
    }

    fun deleteRemoteFiles(host: HostProfile, files: List<RemoteFile>) {
        fileOperation(host, "Delete") { sftp ->
            eachFile(files) { sftpDirectoryService.delete(sftp, it.path) }
        }
    }

    fun renameRemote(host: HostProfile, file: RemoteFile, newName: String) {
        val base = file.path.substringBeforeLast('/', "")
        val target = if (base.isBlank()) newName else "$base/$newName"
        fileOperation(host, "Rename") { sftpDirectoryService.rename(it, file.path, target) }
    }

    fun chmodRemote(host: HostProfile, file: RemoteFile, mode: Int) {
        fileOperation(host, "Change permissions") { sftpDirectoryService.chmod(it, file.path, mode) }
    }

    fun copyRemote(host: HostProfile, file: RemoteFile, destinationPath: String) {
        copyRemoteFiles(host, listOf(file), destinationPath)
    }

    fun copyRemoteFiles(host: HostProfile, files: List<RemoteFile>, destinationPath: String) {
        fileOperation(host, "Copy") { sftp ->
            eachFile(files) { file ->
                sftpDirectoryService.copy(sftp, file.path, joinRemote(destinationPath, file.name))
            }
        }
    }

    fun moveRemote(host: HostProfile, file: RemoteFile, destinationPath: String) {
        moveRemoteFiles(host, listOf(file), destinationPath)
    }

    fun moveRemoteFiles(host: HostProfile, files: List<RemoteFile>, destinationPath: String) {
        fileOperation(host, "Move") { sftp ->
            eachFile(files) { file ->
                sftpDirectoryService.rename(sftp, file.path, joinRemote(destinationPath, file.name))
            }
        }
    }

    /**
     * Opens a local forward: a port on this device tunnelling to [remoteHost]:[remotePort] via [host].
     *
     * The disconnected case is reported here and in the two forwards below. The forwarding sheet is
     * reachable from any open tab, including one whose session has dropped and is mid-reconnect — which
     * is precisely when someone reaches for a tunnel — and the button previously did nothing at all and
     * gave no reason. [reportForwardFailure] covers the other half of the same problem.
     */
    fun startLocalForward(host: HostProfile, localPort: Int, remoteHost: String, remotePort: Int) {
        val entry = ForwardEntry(type = ForwardType.LOCAL, localPort = localPort, remoteHost = remoteHost, remotePort = remotePort, hostId = host.id)
        transportScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            runCatching { portForwardingManager.startLocal(session, "127.0.0.1", localPort, remoteHost, remotePort) }
                .onSuccess { handle -> forwardHandles[entry.id] = handle; forwardings.update { it + entry } }
                .onFailure { reportForwardFailure("Local forward on port $localPort failed", it) }
        }
    }

    fun startRemoteForward(host: HostProfile, remotePort: Int, localPort: Int) {
        val entry = ForwardEntry(type = ForwardType.REMOTE, localPort = localPort, remoteHost = null, remotePort = remotePort, hostId = host.id)
        transportScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            // Bound on the server's loopback, not 0.0.0.0. The dialog only asks for two port numbers,
            // so nobody using it has chosen to publish anything; requesting all interfaces meant that
            // on any server configured `GatewayPorts yes` (or `clientspecified`) the phone's local
            // port became reachable from the server's entire network. Most servers default to
            // `GatewayPorts no` and force loopback regardless of what the client asks, which is why
            // this went unnoticed — it only opened up on the servers where it mattered. Loopback is
            // also what `ssh -R` gives you unless you spell out a bind address.
            runCatching { portForwardingManager.startRemote(session, "127.0.0.1", remotePort, "127.0.0.1", localPort) }
                .onSuccess { handle -> forwardHandles[entry.id] = handle; forwardings.update { it + entry } }
                .onFailure { reportForwardFailure("Remote forward of port $remotePort failed", it) }
        }
    }

    fun startDynamicForward(host: HostProfile, localPort: Int) {
        val entry = ForwardEntry(type = ForwardType.DYNAMIC, localPort = localPort, hostId = host.id)
        transportScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            runCatching { portForwardingManager.startDynamic(session, "127.0.0.1", localPort) }
                .onSuccess { handle -> forwardHandles[entry.id] = handle; forwardings.update { it + entry } }
                .onFailure { reportForwardFailure("SOCKS proxy on port $localPort failed", it) }
        }
    }

    /**
     * Says why a forward did not start.
     *
     * All three start paths used `runCatching { … }.onSuccess { … }` with nothing on the other branch,
     * so the most ordinary failure there is — the local port is already taken, which is what happens
     * the second time a user taps 8080, or when any other app on the device holds it — produced no
     * forward and no message at all. The button simply did nothing, indefinitely.
     *
     * Rethrowing cancellation matters as much as reporting: `runCatching` catches it too, and
     * swallowing it detaches the failure from the scope that was being cancelled.
     */
    private fun reportForwardFailure(what: String, error: Throwable) {
        if (error is CancellationException) throw error
        report(what, error)
    }

    fun stopForwarding(id: String) {
        // Called straight from a Compose click handler, so the close cannot happen here: see
        // [releaseForwards], which drops the entry now and says goodbye to the server off the main
        // thread. The entry goes either way - the forward is certainly not running if the close failed.
        releaseForwards(listOf(id))
    }

    /**
     * Reads uptime, load, memory and disk usage from [host] for the server card.
     *
     * A disconnected host is reported rather than ignored: the card keeps showing its "refresh" prompt
     * until some stats arrive, so a silent return made the button appear broken. That is a different
     * answer from the "Unavailable" placeholder below, which means the commands did run and the server
     * did not give anything usable back.
     */
    fun refreshStats(host: HostProfile) {
        transportScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            val stats = try {
                val hostname = sshConnectionManager.runCommand(session, "hostname")
                val uptime = sshConnectionManager.runCommand(session, "uptime")
                val load = uptime.substringAfter("load average:", "").trim()
                val mem = sshConnectionManager.runCommand(session, "free -m | awk 'NR==2{print $3, $2}'").split(Regex("\\s+"))
                val disk = sshConnectionManager.runCommand(session, "df -h / | awk 'NR==2{print $3, $2}'").split(Regex("\\s+"))
                ServerStats(
                    hostname = hostname.ifBlank { host.name },
                    uptime = uptime,
                    loadAverage = load.ifBlank { "—" },
                    memoryUsed = mem.getOrNull(0)?.let { "$it MB" } ?: "—",
                    memoryTotal = mem.getOrNull(1)?.let { "$it MB" } ?: "—",
                    diskUsed = disk.getOrNull(0) ?: "—",
                    diskTotal = disk.getOrNull(1) ?: "—",
                )
            } catch (cancelled: CancellationException) {
                // Leaving via the branch below would publish "Unavailable" for a host whose stats
                // were merely never asked for — the sheet then shows a server as unreachable when
                // nothing about it is known either way. Every other suspend body in this class
                // rethrows here; this one did not.
                throw cancelled
            } catch (_: Throwable) {
                ServerStats(hostname = host.name, uptime = "Unavailable", loadAverage = "—", memoryUsed = "—", memoryTotal = "—", diskUsed = "—", diskTotal = "—")
            }
            // `update`, not a read-modify-write: this body runs on [transportScope] now, so two hosts'
            // cards refreshing at once - or one refreshing while a tab closes and removes its entry
            // below - would lose whichever write finished second. Same reason as [updateTab].
            serverStats.update { it + (host.id to stats) }
        }
    }

    fun pauseTransfer(id: String) = transferCoordinator.pause(id)

    /**
     * Resumes a paused transfer, reconnecting the host first when the session is gone.
     *
     * Every step can fail (the host may be unreachable, the SAF document revoked), and an
     * uncaught throw here used to take the process down because it happened inside a
     * viewModelScope launch with no handler.
     */
    fun resumeTransfer(id: String) {
        transportScope.launch {
            val item = transferRepository.transfers.first().firstOrNull { it.id == id } ?: return@launch
            val host = hostRepository.hosts.first().firstOrNull { it.id == item.hostId }
                ?: return@launch report("The host for ${item.name} no longer exists")
            val uri = item.localUri?.let(Uri::parse) ?: return@launch report("${item.name} has no local file")
            var dialFailure: Throwable? = null
            // The same gate and the same install as [connect], for the same reason: this runs from a
            // notification action, so it can land in the middle of the UI's own dial to the host it
            // wants. `sessions[hostId]` also did not prune - a session that had died was handed
            // straight to `openSftp` - which is what [SshSessionStore.liveSession] is for.
            val session = sessionStore.liveSession(host.id) ?: sessionStore.dialing(host.id) {
                sessionStore.liveSession(host.id) ?: run {
                    val password = sessionRegistry.credential(host.id)
                    val keyPair = sessionRegistry.keyBytes(host.id)?.let { bytes -> runCatching { SshKeyLoader.load(bytes, "${host.username}-key", sessionRegistry.keyPassphrase(host.id)) }.getOrNull() }
                    val reconnected = try {
                        sshConnectionManager.connect(host, password, keyPair)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        dialFailure = error
                        null
                    }
                    reconnected?.let { sessionStore.install(host.id, it) }
                }
            }
            if (session == null) {
                // The dial can also come back null with nothing thrown - another caller holding the
                // gate installed a session that had already died - so the cause is genuinely optional.
                val failure = dialFailure
                return@launch if (failure == null) {
                    report("Could not reconnect to ${host.name}")
                } else {
                    report("Could not reconnect to ${host.name}", failure)
                }
            }
            // Measure the partial file before reopening it: the persisted byte counter is
            // throttled and lags behind, and appending from it would duplicate bytes.
            val existingBytes = localDocumentLength(context, uri) ?: item.transferredBytes
            // Append mode for a resumed download so the already-received prefix survives.
            val stream = runCatching {
                when (item.direction) {
                    TransferDirection.DOWNLOAD -> context.contentResolver.openOutputStream(uri, "wa")
                    TransferDirection.UPLOAD -> context.contentResolver.openInputStream(uri)
                }
            }.getOrNull() ?: return@launch report("Cannot reopen ${item.name}")
            val sftp = runCatching { sshConnectionManager.openSftp(session) }.getOrNull() ?: run {
                runCatching { stream.close() }
                return@launch report("Cannot open an SFTP channel on ${host.name}")
            }
            when (item.direction) {
                TransferDirection.DOWNLOAD -> transferCoordinator.resumeDownload(item, sftp, stream as OutputStream, existingBytes)
                TransferDirection.UPLOAD -> transferCoordinator.resumeUpload(item, sftp, stream as InputStream)
            }
        }
    }

    /**
     * Ends the session [tab] belongs to and drops everything it owned.
     *
     * Identified by its host, not by the object handed in. The list used to be filtered with
     * `tabs.value - tab`, which removes by *value*: every field of the caller's copy had to match the
     * live one, and the copy a caller has is the one it last read from [uiState] - a `StateFlow` that
     * conflates, so it can be one update behind [tabs] by design. A session that had just settled its
     * SFTP state a moment after connecting therefore had a tab in the list that no longer equalled the
     * one on screen, and closing it removed nothing at all: the tap did visibly nothing while the
     * socket, the pty and the collector were all torn down underneath it, leaving a tab pointing at a
     * session that no longer existed. Keyed on `hostId` because that is the app's own identity for a
     * session - every map below is keyed the same way, and [deleteHost] already filtered this way.
     */
    fun closeTab(tab: SessionTab) {
        // Before the cancel below, not after: see [isDisplaying]. A collector that is still
        // finishing an iteration must not be able to publish a frame for a session being closed.
        terminalBuffers.remove(tab.hostId)
        terminalJobs.remove(tab.hostId)?.cancel()
        connectJobs.remove(tab.hostId)?.cancel()
        sftpJobs.remove(tab.hostId)?.cancel()
        forwardJobs.remove(tab.hostId)?.cancel()
        // Closing a tab is the clearest possible statement that this session is not wanted, so it also
        // ends any reconnect waiting to bring it back.
        reconnectJobs.remove(tab.hostId)?.let { waiting ->
            waiting.cancel()
            diagnostics.record(tab.hostId, SessionEvent.RECONNECT_CANCELLED, detail = "tab closed")
        }
        reconnectAttempts.remove(tab.hostId)
        reconnectPolicies.remove(tab.hostId)
        sessionStore.forget(tab.hostId)
        tabs.value = tabs.value.filterNot { it.hostId == tab.hostId }
        terminalOutput.update { it - tab.hostId }
        terminalFrames.update { it - tab.hostId }
        scrollOffsets.remove(tab.hostId)
        textPublishedAt.remove(tab.hostId)
        typedLines.remove(tab.hostId)
        commandHistory.value = commandHistory.value - tab.hostId
        serverStats.update { it - tab.hostId }
        remoteListings.update { it - tab.hostId }
        homePaths.remove(tab.hostId)
        ptySizes.remove(tab.hostId)
        connectedAt.remove(tab.hostId)
        stopForwardingsFor(tab.hostId)
        viewModelScope.launch { runCatching { sessionRegistry.unregister(tab.hostId) } }
    }

    fun disconnectAll() {
        tabs.value.toList().forEach(::closeTab)
        pendingConnection = null
    }

    /**
     * Saves a host profile, the secrets to connect with if [credentials] asks for it, and the pinned
     * host key if the profile carries one.
     *
     * Seeding the known-hosts store from the profile is what keeps a pin from being decoration:
     * [dev.eclipse.ssh.ssh.SshConnectionManager]'s verifier compares against that store, not against
     * the profile. It turns a pasted fingerprint into a verified first connection instead of a
     * trust-on-first-use prompt, and it is only ever a *tightening* — an unpinned host still has to
     * be accepted by hand.
     *
     * The credential write comes *after* the profile write and is not allowed to take the profile
     * down with it. A store that cannot be written — a full disk, a corrupted preference file being
     * replaced — must not mean the user loses the hostname and port they just typed; they can retry
     * saving the password, but a discarded form is gone. The failure is reported rather than
     * swallowed, so a host that silently has no password on it is not what the user is left guessing
     * about.
     */
    fun saveHost(host: HostProfile, credentials: HostCredentialUpdate = HostCredentialUpdate()) {
        launchGuarded("Could not save ${host.name}") {
            hostRepository.save(host)
            if (!credentials.isNoop) {
                runCatching { credentialStore.apply(host.id, credentials) }
                    .onFailure { error -> report("Saved ${host.name}, but its credentials could not be stored", error) }
            }
            host.fingerprint?.trim()?.takeIf(String::isNotBlank)?.let { pinned ->
                runCatching { sshConnectionManager.trustHost(host, pinned) }
                knownHostsState.value = sshConnectionManager.knownHosts()
            }
        }
    }

    /** Drops the saved password, key and passphrase for a host without touching the profile. */
    fun forgetCredentials(host: HostProfile) {
        viewModelScope.launch {
            runCatching { credentialStore.forget(host.id) }
                .onSuccess { report("Forgot saved credentials for ${host.name}") }
                .onFailure { error -> report("Could not forget credentials for ${host.name}", error) }
        }
    }

    /** Drops every saved credential in the app, for the Settings screen's panic action. */
    fun forgetAllCredentials() {
        viewModelScope.launch {
            runCatching { credentialStore.forgetAll() }
                .onSuccess { report("Forgot every saved credential") }
                .onFailure { error -> report("Could not clear saved credentials", error) }
        }
    }
    fun clearCompletedTransfers() = launchGuarded("Could not clear the completed transfers") { transferRepository.clearCompleted() }

    fun deleteHost(host: HostProfile) {
        launchGuarded("Could not delete ${host.name}") {
            // Before the cancel below, not after: see [isDisplaying]. A collector that is still
            // finishing an iteration must not be able to publish a frame for a session being closed.
            terminalBuffers.remove(host.id)
            terminalJobs.remove(host.id)?.cancel()
            connectJobs.remove(host.id)?.cancel()
            sftpJobs.remove(host.id)?.cancel()
            forwardJobs.remove(host.id)?.cancel()
            // Through the store, which marks the channel deliberate so its ending is not read as a
            // fault, and which does the socket half off the main thread. Deleting a host used to close
            // both here, on the caller's dispatcher — and `launchGuarded` is a `viewModelScope` body, so
            // that was `SSH_MSG_DISCONNECT` written from the UI thread. The buffer is already gone,
            // removed above for the reason recorded there, so this deliberately is not `forget`.
            sessionStore.close(host.id)
            tabs.value = tabs.value.filterNot { it.hostId == host.id }
            terminalOutput.update { it - host.id }
            terminalFrames.update { it - host.id }
            scrollOffsets.remove(host.id)
            textPublishedAt.remove(host.id)
            typedLines.remove(host.id)
            commandHistory.value = commandHistory.value - host.id
            serverStats.update { it - host.id }
            remoteListings.update { it - host.id }
            homePaths.remove(host.id)
            stopForwardingsFor(host.id)
            runCatching { sessionRegistry.unregister(host.id) }
            // Before the profile, and unconditionally: once the row is gone there is nothing left to
            // tie these secrets to, so a failure here would leave a password and a private key on
            // disk for a host id that no screen in the app can name — undeletable from the UI and
            // invisible in it. Deleting a host is exactly when a user expects its secrets to go.
            runCatching { credentialStore.forget(host.id) }
                .onFailure { error -> report("Deleted ${host.name}, but its saved credentials remain", error) }
            hostRepository.delete(host)
            if (selectedHostId.value == host.id) selectedHostId.value = null
        }
    }

    fun saveSnippet(label: String, command: String) = launchGuarded("Could not save the snippet") {
        if (label.isNotBlank() && command.isNotBlank()) snippetRepository.save(Snippet(label = label.trim(), command = command.trimEnd()))
    }
    fun deleteSnippet(id: String) = launchGuarded("Could not delete the snippet") { snippetRepository.delete(id) }
    fun copyToClipboard(text: String) = launchGuarded("Could not copy to the clipboard") {
        val seconds = settingsRepository.settings.first().clearClipboardAfterSeconds
        secureClipboard.copy(text, seconds)
    }

    /**
     * The whole SSH lifecycle trace as text, for the clipboard or a saved file.
     *
     * Read straight from the ring rather than from [MainUiState], so what the user exports is what the
     * app knows right now - including anything recorded while the dialog was open.
     */
    fun exportDiagnostics(): String = diagnostics.export()

    /**
     * Empties the trace.
     *
     * Offered because the useful way to use a 500-entry ring is to clear it, reproduce the problem, and
     * export what is left; a trace that starts at the moment of the bug is far easier to read than one
     * that starts at app launch.
     */
    fun clearDiagnostics() {
        diagnostics.clear()
        report("Diagnostics cleared")
    }

    /**
     * Backup/restore entry points.
     *
     * Every one of these touches a user-picked SAF document and a passphrase, so all the
     * realistic failures — revoked URI permission, a file that is not a backup, the wrong
     * passphrase, a truncated payload — are reported through [statusMessage]. Previously an
     * uncaught [BackupFormatException] (or the raw `AEADBadTagException` behind it) crashed
     * the process from inside the launched coroutine.
     */
    fun exportVault(passphrase: String, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        guardBackup("Export failed") {
            val hosts = hostRepository.hosts.first()
            val settings = settingsRepository.settings.first()
            val payload = VaultBackup.encrypt(VaultBackup.toJson(hosts, settings, sshConnectionManager.knownHosts()), passphrase)
            writeDocument(uri, payload)
            report("Exported ${hosts.size} host(s)")
        }
    }

    fun exportAccount(host: HostProfile, passphrase: String, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        guardBackup("Export failed") {
            writeDocument(uri, VaultBackup.encrypt(VaultBackup.toAccountJson(host), passphrase))
            report("Exported ${host.name}")
        }
    }

    fun importAccount(passphrase: String, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        guardBackup("Import failed") {
            val host = VaultBackup.fromAccountJson(VaultBackup.decrypt(readDocument(uri), passphrase))
            hostRepository.save(host)
            report("Imported ${host.name}")
        }
    }

    fun importVault(passphrase: String, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        guardBackup("Import failed") {
            val (hosts, settings, knownHosts) = VaultBackup.fromJson(VaultBackup.decrypt(readDocument(uri), passphrase))
            hosts.forEach { hostRepository.save(it) }
            sshConnectionManager.importKnownHosts(knownHosts)
            // All persisted settings are restored; the previous version silently dropped
            // legacyAlgorithms, terminalTheme and reconnectBaseSeconds.
            settingsRepository.setDarkTheme(settings.darkTheme)
            settingsRepository.setBiometricUnlock(settings.biometricUnlock)
            settingsRepository.setClipboardSeconds(settings.clearClipboardAfterSeconds)
            settingsRepository.setKeepAliveSeconds(settings.keepAliveSeconds)
            settingsRepository.setReconnectBaseSeconds(settings.reconnectBaseSeconds)
            settingsRepository.setTerminalFontSize(settings.terminalFontSize)
            settingsRepository.setTerminalMinColumns(settings.terminalMinColumns)
            settingsRepository.setLegacyAlgorithms(settings.legacyAlgorithms)
            settingsRepository.setTerminalTheme(settings.terminalTheme)
            settingsRepository.setBlockScreenshots(settings.blockScreenshots)
            report("Imported ${hosts.size} host(s)")
        }
    }

    fun importOpenSshConfig(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        guardBackup("Import failed") {
            val parsed = OpenSshConfigParser.parse(readDocument(uri))
            val existing = hostRepository.hosts.first()
            var added = 0
            parsed.forEach { host ->
                val duplicate = existing.any { it.host == host.host && it.port == host.port && it.username == host.username }
                if (!duplicate) {
                    hostRepository.save(host)
                    added++
                }
            }
            report(
                when {
                    parsed.isEmpty() -> "No hosts found in that config"
                    added == 0 -> "All ${parsed.size} host(s) already exist"
                    else -> "Imported $added of ${parsed.size} host(s)"
                },
            )
        }
    }

    private suspend fun guardBackup(prefix: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(prefix, error)
        }
    }

    private fun readDocument(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: throw BackupFormatException("That file could not be opened")

    private fun writeDocument(uri: Uri, payload: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupFormatException("That location could not be written")
        stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Runs a database write on the view-model scope, reporting a failure instead of crashing.
     *
     * The Room half of what [writeSetting] does for DataStore, and for the same reason: these bodies
     * were bare `viewModelScope.launch { … }`, and an uncaught exception in a `viewModelScope`
     * coroutine reaches the thread's default handler and kills the process. `SQLiteFullException` and
     * `SQLiteDiskIOException` are the realistic ways to get one — a device with no free space, or a
     * database file the platform could not read back — and losing the app while saving a host is a
     * worse outcome than being told the host could not be saved. Every one of these writes happens
     * because the user pressed something, so there is always somewhere to show the reason.
     */
    private fun launchGuarded(what: String, body: suspend () -> Unit) = viewModelScope.launch {
        guardBackup(what, body)
    }

    /**
     * Runs a settings write, reporting a failure instead of crashing.
     *
     * `DataStore.edit` throws [java.io.IOException] when it cannot write — a full disk, storage that
     * is unavailable because the device is still in direct boot, a file another process corrupted.
     * These setters were bare `viewModelScope.launch { … }` bodies, and an uncaught exception in a
     * `viewModelScope` coroutine reaches the thread's default handler, so flipping a switch in
     * Settings on a device with no free space killed the process. The switch simply staying where it
     * was, with the reason in the snackbar, is what the user needs to see instead.
     */
    private fun writeSetting(what: String, write: suspend () -> Unit) = viewModelScope.launch {
        guardBackup("Could not save $what", write)
    }

    fun setBiometricUnlock(enabled: Boolean) = writeSetting("the unlock setting") { settingsRepository.setBiometricUnlock(enabled) }
    fun setBlockScreenshots(enabled: Boolean) = writeSetting("the screenshot setting") { settingsRepository.setBlockScreenshots(enabled) }
    fun setDarkTheme(enabled: Boolean) = writeSetting("the theme setting") { settingsRepository.setDarkTheme(enabled) }
    fun setClipboardSeconds(seconds: Int) = writeSetting("the clipboard timeout") { settingsRepository.setClipboardSeconds(seconds) }
    fun setKeepAliveSeconds(seconds: Int) = writeSetting("the keep-alive interval") { settingsRepository.setKeepAliveSeconds(seconds) }

    /**
     * How long the session service waits before its *first* reconnect attempt; each further attempt
     * doubles it, up to [dev.eclipse.ssh.background.MAX_BACKOFF_MS].
     *
     * The repository clamps to [SettingsRepository.MIN_RECONNECT_BASE_SECONDS]..[SettingsRepository.MAX_RECONNECT_BASE_SECONDS],
     * so an out-of-range value from a caller cannot store a delay the backoff would have to defend
     * against later.
     */
    fun setReconnectBaseSeconds(seconds: Int) = writeSetting("the reconnect delay") { settingsRepository.setReconnectBaseSeconds(seconds) }
    fun setTerminalFontSize(size: Int) = writeSetting("the terminal font size") { settingsRepository.setTerminalFontSize(size) }
    fun setTerminalMinColumns(columns: Int) = writeSetting("the terminal width") { settingsRepository.setTerminalMinColumns(columns) }
    fun setTerminalKeyRowVisible(visible: Boolean) = writeSetting("the shortcut bar") { settingsRepository.setTerminalKeyRowVisible(visible) }
    fun setLegacyAlgorithms(enabled: Boolean) = writeSetting("the legacy algorithm setting") { settingsRepository.setLegacyAlgorithms(enabled) }
    fun setTerminalTheme(name: String) = writeSetting("the terminal theme") { settingsRepository.setTerminalTheme(name) }
    fun setPin(pin: String) = writeSetting("the app PIN") { settingsRepository.setPin(pin) }
    fun clearPin() = writeSetting("the app PIN") { settingsRepository.clearPin() }
    suspend fun verifyPin(pin: String): Boolean = settingsRepository.verifyPin(pin)

    /**
     * Releases every native resource the ViewModel owns. Port-forward handles and terminal
     * collectors were previously left running, so a process that kept the ViewModel's
     * dependencies alive (a foreground service) held listening sockets open after the UI
     * was gone.
     */
    override fun onCleared() {
        terminalJobs.values.forEach { it.cancel() }
        terminalJobs.clear()
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        forwardJobs.values.forEach { it.cancel() }
        forwardJobs.clear()
        // Every tracker, by key rather than through [forwardings]: a forward whose entry never made it
        // onto the list - it failed after binding, or the list write lost a race - still holds a
        // listening socket, and this is the last chance anything has to close it.
        releaseForwards(forwardHandles.keys.toList())
        reconnectWake.close()
        // Sessions, shells and scrollback deliberately survive: they belong to [SshSessionStore] and
        // the foreground service is running to keep them. Closing them here is what used to kill every
        // background session the instant the activity was finished — swiping the task away hung up on
        // a running job, and the service that existed to prevent exactly that reconnected afterwards
        // as if the session had dropped. What is dropped here is this view model's *view* of them:
        // the collectors, and the per-screen state that is rebuilt on the next
        // [adoptExistingSessions]. Ending a session is [closeTab], [disconnectAll] or the
        // notification's Stop action — all of them user actions.
        scrollOffsets.clear()
        textPublishedAt.clear()
        typedLines.clear()
        homePaths.clear()
        super.onCleared()
    }

    private fun stopForwardingsFor(hostId: String) {
        releaseForwards(forwardings.value.filter { it.hostId == hostId }.map { it.id })
    }

    /**
     * Applies [transform] to the tab for [hostId], **in place**.
     *
     * The index matters: this used to be `filterNot { it.hostId == hostId } + updated`, which moved a
     * tab to the end of the strip on every state change. Connecting two hosts at once was enough to
     * see it — the first tab jumped past the second the moment its handshake went CONNECTING →
     * RECONNECTING, so the strip reshuffled under the user's finger while they were reaching for it.
     * A tab keeps the position it was opened in for as long as it is open.
     */
    private fun updateTab(hostId: String, transform: (SessionTab?) -> SessionTab?) {
        // Atomic, because not every caller is on the main thread: the connect phase callback is
        // invoked from MINA's dial on Dispatchers.IO while the collector and the reconnect ladder are
        // writing the same list from the main dispatcher. Read-modify-write on `tabs.value` lost
        // whichever update finished second - the state a tab was left showing depended on a race.
        // `update` retries its transform on conflict, which is safe here: every transform is a pure
        // `copy` of the tab it was handed.
        tabs.update { current ->
            val index = current.indexOfFirst { it.hostId == hostId }
            val updated = transform(current.getOrNull(index)) ?: return@update current
            if (index < 0) current + updated else current.toMutableList().also { it[index] = updated }
        }
    }

    /**
     * Says that the login is done and the shell is being opened.
     *
     * Silent during a reconnect, for the same reason [onConnectPhase] is: a tab that is counting
     * attempts should keep counting them rather than narrate the phases of each one. Silent, too, on a
     * tab that is already live, which is what an adopted session's tab already is - there is no shell to
     * wait for when the shell is the one already on screen. And silent on behalf of a [dial] that has
     * been replaced, for the reason [onConnectPhase] gives.
     */
    private fun markOpeningShell(hostId: String, dial: Long, resuming: Boolean) {
        if (resuming) return
        val current = isCurrentDial(hostId, dial)
        updateTab(hostId) { tab ->
            if (tab != null && phaseReportIsWritable(tab.state, current)) {
                tab.copy(state = SessionConnectionState.CHANNEL_PTY_INITIALIZING)
            } else {
                tab
            }
        }
    }

    /**
     * Moves a tab through the two phases of one dial.
     *
     * The point of separating them is that they fail for entirely different reasons and take entirely
     * different amounts of time. "Connecting…" covering both meant a PAM stack thinking for fifteen
     * seconds, a two-factor prompt waiting on a push notification, and a TCP connection to an
     * unreachable address all looked identical - a stuck app - and the user's only move was to guess.
     *
     * A reconnect keeps saying RECONNECTING throughout: which phase attempt three is in matters much
     * less than the fact that the app is still trying, and flickering CONNECTING/AUTHENTICATING under
     * "attempt 3 of 5" reads as instability rather than as progress.
     *
     * Never demotes a tab that is already past authentication, because the callback crosses threads: an
     * authentication that finishes instantly can have its AUTHENTICATING land behind the
     * CHANNEL_PTY_INITIALIZING or CONNECTED that followed it, and a session with a pty must not be told
     * it is still logging in. See [isPastAuthentication].
     *
     * Nor does it let a superseded [dial] narrate. Every other write in `connect` already takes this
     * gate - the retry countdown and the final failure both check it - and the phase callback was the
     * one that did not, so an attempt the user had already replaced could still walk the tab they *are*
     * waiting on backwards from AUTHENTICATING to CONNECTING as its own handshake came up. The
     * [isPastAuthentication] guard cannot catch that: it is there to order two reports of one dial, and
     * this is a report belonging to a dial that no longer speaks for the host. Recorded in the trace
     * either way, marked as superseded - a diagnostic that hid the attempts actually being made would
     * be the harder bug to read. See [dialGenerations].
     */
    private fun onConnectPhase(hostId: String, dial: Long, phase: SshConnectPhase, resuming: Boolean, attempt: Int) {
        val state = when {
            resuming -> SessionConnectionState.RECONNECTING
            phase == SshConnectPhase.HANDSHAKE -> SessionConnectionState.CONNECTING
            else -> SessionConnectionState.AUTHENTICATING
        }
        val current = isCurrentDial(hostId, dial)
        updateTab(hostId) { tab ->
            if (tab != null && phaseReportIsWritable(tab.state, current)) tab.copy(state = state) else tab
        }
        diagnostics.record(
            hostId,
            when (phase) {
                SshConnectPhase.HANDSHAKE -> SessionEvent.HANDSHAKE
                SshConnectPhase.AUTHENTICATE -> SessionEvent.AUTHENTICATE
            },
            state = state,
            detail = if (current) null else "superseded",
            network = networkMonitor.describe(),
            attempt = attempt + 1,
        )
    }

    private data class BaseState(
        val state: MainUiState,
        val challenge: HostKeyChallenge?,
        val forwards: List<ForwardEntry>,
        val snippets: List<Snippet>,
        val stats: Map<String, ServerStats>,
    )

    /**
     * The credentials of a connection attempt still waiting on a host-key decision.
     *
     * Not a `data class`, and neither is [ResolvedCredentials]. The generated `toString` prints every
     * property, so a plain-text password would be one string interpolation away from a log line, a
     * crash report or a snackbar — and `equals` on a `ByteArray` property compares identity, which
     * silently makes any equality check here wrong anyway. Neither is generated, so neither can leak.
     */
    private class PendingConnection(
        val host: HostProfile,
        val password: String?,
        val keyPair: KeyPair?,
        val keyBytes: ByteArray?,
        val keyPassphrase: String?,
        /**
         * Whether the attempt that raised the host-key question was the reconnect ladder's.
         *
         * Carried so that trusting a key does not silently turn a reconnect into a fresh connection.
         * Without it the retry lost the flag: the tab jumped from "Reconnecting - attempt 3 of 5" to
         * "Connecting...", dropped the reason it was showing, and handed the ladder a clean allowance -
         * a reset that belongs to a user asking for a *connection*, not to one answering a question the
         * recovery asked them.
         */
        val resuming: Boolean,
    )

    /** What [resolveCredentials] settled on: the caller's values, with the saved ones filling gaps. */
    private class ResolvedCredentials(
        val password: String?,
        val keyPair: KeyPair?,
        val keyBytes: ByteArray?,
        val keyPassphrase: String?,
    )

    private data class TerminalState(
        val output: Map<String, String>,
        val history: Map<String, List<String>>,
        val diagnostics: List<SessionDiagnosticEvent>,
        val diagnosticsLabels: Map<String, String>,
    )

    private data class SecurityState(
        val knownHosts: Map<String, String>,
        val credentials: Map<String, StoredCredentials>,
    )

    /** One host's file-browser position: the directory it is showing, and what is in it. */
    private data class RemoteListing(
        val path: String,
        val files: List<RemoteFile>,
    )

    private data class LocalState(
        val files: List<LocalFile>,
        val dirUri: String?,
    )

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    /**
     * Module-visible for one reason: [MAX_CONNECT_ATTEMPTS] is a number a test has to agree with.
     *
     * `RealOpenSshInteropRobolectricTest` counts the logins a real `sshd` records while the connect
     * ladder climbs, and "three" written out in the test would turn a deliberate change to the ladder
     * into a failing interop test about OpenSSH. Nothing else here is public API; `internal` is the
     * whole module and no wider, the same seam [dev.eclipse.ssh.ssh.SessionDiagnostics] uses for its
     * bounds.
     */
    internal companion object {
        const val MAX_TERMINAL_CHARS = 100_000
        /**
         * Ceiling on how often the terminal repaints, in milliseconds — about 30 frames a second.
         *
         * Not a debounce: output is never delayed waiting for more of it. See
         * [launchTerminalCollector].
         */
        const val TERMINAL_FRAME_MS = 33L
        const val MAX_CONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 1_500L

        const val MAX_HISTORY = 50

        /**
         * How often the searchable plain text is rebuilt. Two orders of magnitude slower than the
         * frame on purpose - see [publishTerminalText].
         */
        const val TERMINAL_TEXT_MS = 1_000L

        /**
         * How long the ending of a session waits for output still in flight to reach the buffer.
         *
         * Generous because it is not a timeout in the normal case - the terminator is already queued
         * behind the last chunk when this starts, so the wait is one dispatch - and reaching the bound
         * means the shared flow was overrun, which the code that waits explains.
         */
        const val TERMINAL_DRAIN_MS = 2_000L

        /** A sane bound on the reconstructed command line, so a `cat` of binary cannot grow it. */
        const val MAX_TYPED_LINE = 4_096

        /**
         * Resource name given to a key picked from the file picker, for the parser's error messages.
         *
         * The picked document's own name is deliberately not used: it comes from another app, is
         * attacker-influenced in the general case, and would end up inside an exception message. A
         * key saved on a profile has a label the user has already seen in the form, so that one is
         * used instead.
         */
        const val PICKED_KEY_NAME = "private-key"
    }
}

data class MainUiState(
    val hosts: List<HostProfile> = emptyList(),
    val filteredHosts: List<HostProfile> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val query: String = "",
    val selectedHostId: String? = null,
    val tabs: List<SessionTab> = emptyList(),
    val transfers: List<TransferItem> = emptyList(),
    val terminalOutput: Map<String, String> = emptyMap(),
    val commandHistory: Map<String, List<String>> = emptyMap(),
    val hostKeyChallenge: HostKeyChallenge? = null,
    val knownHosts: Map<String, String> = emptyMap(),
    /**
     * Saved-credential metadata per host id — which hosts can connect without a prompt, and what key
     * is on them. Metadata only: no secret ever reaches the UI state. See [StoredCredentials].
     */
    val savedCredentials: Map<String, StoredCredentials> = emptyMap(),
    val remoteFiles: List<RemoteFile> = emptyList(),
    val remotePath: String? = null,
    val localFiles: List<LocalFile> = emptyList(),
    val localDirUri: String? = null,
    val forwardings: List<ForwardEntry> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val serverStats: Map<String, ServerStats> = emptyMap(),
    /**
     * The SSH lifecycle trace, oldest first. Carries no secrets and no host names - see
     * [dev.eclipse.ssh.ssh.SessionDiagnostics].
     */
    val diagnostics: List<SessionDiagnosticEvent> = emptyList(),
    /**
     * Host id to the opaque label its [diagnostics] lines are filed under, so one session's trace can
     * be shown on that session's own tab. See [dev.eclipse.ssh.ssh.SessionDiagnostics.sessionLabels]
     * and [sessionDiagnostics].
     */
    val diagnosticsLabels: Map<String, String> = emptyMap(),
)

/**
 * One host's slice of the trace, newest first, at most [limit] lines.
 *
 * Newest first because the question this answers - *why did this session end?* - is answered by the
 * last few lines, and a user who has opened it from a failed tab should not have to scroll a 500-entry
 * ring to reach them.
 *
 * A host with no label yet has never had a line recorded, and returning nothing for it is the honest
 * answer: the alternative, falling back to the unfiltered ring, would show one session another
 * session's trace, which is the one thing this must not do.
 *
 * Pure, so both of those properties are asserted directly rather than through a screen.
 */
internal fun sessionDiagnostics(
    events: List<SessionDiagnosticEvent>,
    label: String?,
    limit: Int = MAX_SESSION_DIAGNOSTIC_LINES,
): List<SessionDiagnosticEvent> {
    if (label == null) return emptyList()
    return events.asReversed().asSequence().filter { it.session == label }.take(limit).toList()
}

/**
 * How many of a session's trace lines the "Why?" sheet shows.
 *
 * Enough to hold a whole failed recovery - a connect, an ending, and a five-rung ladder with its
 * attempts and its exhaustion - so the shape of the failure is visible without scrolling to find the
 * start of it. The full ring is still one tap away under Settings.
 */
internal const val MAX_SESSION_DIAGNOSTIC_LINES = 24

/**
 * Consecutive automatic reconnects allowed per host before the app stops and says so.
 *
 * Five, with the backoff in [dev.eclipse.ssh.background.backoffWindowMs], spans roughly ten minutes
 * of outage — long enough to ride out
 * a train tunnel, a Wi-Fi handover or a server reboot, short enough that a host which is
 * genuinely gone stops being dialled while the phone is in a pocket.
 *
 * The same number as [DEFAULT_MAX_RECONNECT_ATTEMPTS] and deliberately defined as it: this is the
 * allowance a host that has never been configured gets, and a host that raises its own ceiling in the
 * Advanced section climbs its own ladder instead. Two independent fives would drift the day one moved.
 */
internal const val MAX_AUTO_RECONNECT_ATTEMPTS = DEFAULT_MAX_RECONNECT_ATTEMPTS

/**
 * How long a session must last before it counts as having *stayed* up, handing back the reconnect
 * allowance in [MAX_AUTO_RECONNECT_ATTEMPTS].
 *
 * Five minutes, and the floor under that number is the keepalive arithmetic rather than taste. With the
 * default 30 s keepalive a heartbeat death is declared after three unanswered probes — `keepAlive * 3`
 * = 90 s, see `HEARTBEAT_NO_REPLY_MAX` in [dev.eclipse.ssh.ssh.SshConnectionManager] — and the idle
 * backstop it configures alongside is `keepAlive * 3 + 60` = 150 s. A threshold shorter than
 * those would let a session that never carried a byte, and was only ever waiting to be declared dead,
 * count as stable and refill the ladder; the loop would then be unbounded again for exactly the case
 * this guards. Five minutes clears both with room to spare while still being short enough that an
 * ordinary working session — one that has been usable for minutes and then hits a tunnel — gets the
 * full allowance for that outage, which is what the allowance is for.
 */
internal const val STABLE_SESSION_MS = 300_000L

/**
 * Whether a session that just ended should be brought back automatically.
 *
 * One question decides it: *was the shell taken away, or was it finished?* A transport that died —
 * a dropped link, a killed `sshd`, a NAT that stopped forwarding, a heartbeat that ran out of replies
 * — took a working shell away from a user who was using it, and that is the case reconnecting exists
 * for. A shell that ended is finished, and dialling another one would only end the same way.
 *
 * This used to be decided by `exitStatus == null`, which got the second half wrong in a way users
 * reported as *"it connects and then goes straight to Reconnecting"*. A shell only reports a status
 * when it exits normally: killed by a signal it reports a signal, and refused outright it reports
 * nothing at all. Both arrived here as `null` — indistinguishable from a dead socket — so an account
 * whose login shell printed a line and exited (`nologin`, a failing `~/.profile`, a `ForceCommand`)
 * was dialled five more times, each attempt printing the same line and dying the same way, before the
 * ladder gave up. [SessionEnd] exists so the two are no longer the same value.
 *
 * A closed tab is not reconnected either, whatever ended it: the user is not looking at that session
 * any more, and [MainViewModel.closeTab] has already released it.
 *
 * [endedDeliberately] is the third state, and leaving it out was a bug with a very visible symptom.
 * The app closes sessions itself - a tab closed, Stop sessions, a duplicate session collapsed into the
 * one the app keeps - and from here every one of those looks like an ending that happened *to* the
 * session. A tab that said *Reconnecting…* a few seconds after a successful login was this: something
 * closed a session on purpose, and the rule below read it as an outage and started a ladder. See
 * [dev.eclipse.ssh.ssh.TerminalChannel.endedDeliberately].
 *
 * Pure so the rule can be tested without a network, a server or a view model — the states this
 * has to get right are exactly the ones that are awkward to reproduce on demand.
 */
/**
 * What a tab should say while a *connect* attempt that failed is being tried again.
 *
 * One message used to cover every attempt: "Retrying connection…", under the RECONNECTING state. On a
 * host that refused the TCP connection that is exactly right. On a host that accepted the password and
 * then failed to give out a pty it is false in the way that costs a user an evening - the login worked,
 * nothing about the connection needs retrying, and the app is reporting the one thing the user can see
 * is not the problem. It is also, word for word, what the reconnect ladder says after a genuine drop,
 * so the single most-reported symptom of this app - *log in, then "Reconnecting…"* - had two completely
 * different causes wearing one label, and no way to tell which one anybody was looking at.
 *
 * [phase] is the state the tab was in when the attempt failed, which is where that information already
 * lives. See [SessionConnectionState.CHANNEL_PTY_INITIALIZING].
 *
 * [nextAttempt] and [maxAttempts] are said out loud because "retrying" on its own cannot distinguish a
 * first retry from the last one, and those are different situations for the person watching: the first
 * is worth waiting through, the last is about to become a failure they will have to act on.
 */
internal fun retryNotice(phase: SessionConnectionState, nextAttempt: Int, maxAttempts: Int): String {
    val what = when (phase) {
        // Authenticated, so the credentials and the route are proven and neither is what to look at. A
        // server at its MaxSessions limit, out of ptys, or running a ForceCommand that refuses one lands
        // here, and all three are fixed on the server rather than in this app.
        SessionConnectionState.CHANNEL_PTY_INITIALIZING -> "Logged in · the shell did not open"
        else -> "Retrying connection…"
    }
    return "$what · attempt $nextAttempt of $maxAttempts"
}

/**
 * Whether a phase report may be written onto a tab that is currently showing [phase].
 *
 * Two independent reasons to refuse, and they answer different questions. [isCurrentDial] asks *whose*
 * report this is: `connect` gates its retry countdown and its final failure on the dial generation
 * already, and until this existed the phase callback was the one write that did not, so an attempt the
 * user had replaced - a double tap, a host key answered while the rejected attempt was still unwinding -
 * could walk the tab they were actually waiting on backwards from AUTHENTICATING to CONNECTING as its
 * own handshake came up. [SessionConnectionState.isPastAuthentication] asks *when*: within one dial the
 * callback crosses threads, so an instant authentication can have its AUTHENTICATING land behind the
 * shell phase that followed it, and a session with a pty must never be told it is still logging in.
 *
 * Extracted rather than left as a two-clause `if` for the same reason [retryPhase] and [retryNotice]
 * were: the interesting part of this app's connect reporting is a handful of decisions, and a decision
 * inside a coroutine inside a view model can only be tested by standing up a server.
 */
internal fun phaseReportIsWritable(phase: SessionConnectionState, isCurrentDial: Boolean): Boolean =
    isCurrentDial && !phase.isPastAuthentication

/**
 * The state a tab should wait in between two attempts of the *same* connection.
 *
 * It used to be RECONNECTING, and that is the single line of code behind the app's longest-running
 * complaint. RECONNECTING has one meaning everywhere else in this app - *a session that was up has
 * dropped and is being recovered* - and a first login that failed once has no session, has never had
 * one, and is not being recovered. So a server that accepted the password and then refused a pty
 * announced itself with the word for an outage, on a connection whose transport had just proved it
 * worked, seconds after the user tapped Connect. "Log in, then straight to Reconnecting" is that
 * sentence, and it was the app describing its own retry.
 *
 * Staying in the phase that failed keeps the report honest and keeps one word meaning one thing: after
 * this, RECONNECTING on screen always means a session existed and was lost. [retryNotice] carries the
 * detail, and [dev.eclipse.ssh.data.model.isPastAuthentication] is what stops the next attempt's
 * handshake callback walking the shell phase back to CONNECTING.
 */
internal fun retryPhase(phase: SessionConnectionState): SessionConnectionState =
    if (phase.isPastAuthentication) {
        SessionConnectionState.CHANNEL_PTY_INITIALIZING
    } else {
        // Not AUTHENTICATING even when that is where it failed: the next attempt starts by dialling, so
        // the honest phase for the wait is the one it is about to be in.
        SessionConnectionState.CONNECTING
    }

/**
 * [autoReconnectEnabled] is the host's own switch, and it is checked here rather than at the top of
 * the ladder so that a tab whose owner turned the feature off goes straight to the state that says what
 * happened, instead of showing one frame of "Reconnecting" for a recovery that is not going to be
 * attempted.
 */
internal fun shouldAutoReconnect(
    end: SessionEnd,
    tabIsOpen: Boolean,
    endedDeliberately: Boolean,
    autoReconnectEnabled: Boolean = true,
): Boolean {
    if (!tabIsOpen || endedDeliberately || !autoReconnectEnabled) return false
    // A bug in this app is not an outage, and the ladder cannot fix one: the next session runs the same
    // code and dies the same way, so the only thing five rungs buy is a minute of a user's evening and a
    // message blaming their server. Ends at ERROR instead, saying whose fault it is - see [isAppFault],
    // and the threading fix it is a net behind.
    if (end.isAppFault) return false
    return when (end) {
        // The far end is finished with this shell, however it phrased that: a status, a signal, or a
        // channel closed with neither. A fresh shell would meet the same end, so the tab says what
        // happened and stops; the manual Reconnect action is still right there for a user who
        // disagrees.
        is SessionEnd.ShellEnded -> false
        // Everything else is the transport, and a transport is the one thing worth waiting out.
        is SessionEnd.TransportFailed, is SessionEnd.Disconnected -> true
        // The one ending the app itself concluded. It is reconnect-worthy for the same reason as the
        // rest, and more certainly than most: the session was proven to have lost its interface.
        SessionEnd.NetworkLost -> true
        SessionEnd.TransportClosed, SessionEnd.Released -> true
    }
}

/**
 * Whether a session was hung up on before it ever ran, in which case dialling it again immediately is
 * the loop rather than the cure.
 *
 * The shape this catches is specific, and it is the one shape a backoff ladder cannot help with: the
 * shell opened, the far end never sent a single byte through it - no banner, no prompt, nothing - and
 * the connection was over in under [NEVER_RAN_MS]. That is a server declining the session, not a link
 * that faltered: `MaxStartups` shedding load, `MaxSessions` reached, a `ForceCommand` that exits, a
 * `DenyUsers`/`AllowUsers` rule applied after the login, a firewall or middlebox killing the flow, a
 * container whose entrypoint is already gone. Every one of those answers a redial the same way, five
 * times, one backoff apart - which is the connect / disconnect / reconnecting cycle in the reports, and
 * during it the tab shows the *app's* recovery instead of the server's reason for refusing.
 *
 * Narrow on purpose, in three independent ways, because getting this wrong in the other direction would
 * cost a user the recovery they actually need:
 *
 *  - **[idleForMs] must be null**, which [dev.eclipse.ssh.ssh.TerminalChannel.idleForMs] returns only
 *    when the far end has sent nothing at all. A session that printed even a prompt and then dropped was
 *    a working session, and working sessions that drop are exactly what auto-reconnect is for.
 *  - **[upForMs] must be under the floor.** A session that lasted longer than a couple of seconds was
 *    not refused; it was interrupted.
 *  - **the ending must be a hang-up**, not an I/O failure. `Disconnected` is the server saying so in
 *    words and `TransportClosed` is a bare FIN, both decisions taken at the far end.
 *    [SessionEnd.TransportFailed] (a reset, a timeout) and [SessionEnd.NetworkLost] stay reconnect-worthy
 *    however young the session was, because a phone that changes network one second after a login is the
 *    ordinary case and it must ride that out. [SessionEnd.Released] cannot honestly reach here anyway -
 *    the liveness probe will not conclude anything about a session this young - and is left out for the
 *    same reason.
 *
 * The user is not left worse off: the tab lands in ERROR carrying the server's own words, and the
 * manual Reconnect button is offered on every ended state. What they lose is four automatic redials
 * that were always going to fail, and what they gain is the reason.
 *
 * Pure, so the whole matrix is testable without a server that can be talked into refusing a shell.
 */
internal fun endedBeforeItRan(end: SessionEnd, upForMs: Long?, idleForMs: Long?): Boolean {
    if (idleForMs != null) return false
    if (upForMs == null || upForMs >= NEVER_RAN_MS) return false
    return when (end) {
        is SessionEnd.Disconnected -> true
        SessionEnd.TransportClosed -> true
        is SessionEnd.ShellEnded,
        is SessionEnd.TransportFailed,
        SessionEnd.NetworkLost,
        SessionEnd.Released,
        -> false
    }
}

/**
 * How long a session has to last before a hang-up counts as an interruption rather than a refusal.
 *
 * Three seconds, and it is a generous reading of "immediately": a shell that opens sends its banner or
 * its prompt in one round trip, so on any link this app can be used over, output has either arrived by
 * now or is never going to. Long enough that a slow server which is genuinely getting there is not
 * accused of refusing; short enough that it cannot be confused with a session that was in use.
 *
 * See [endedBeforeItRan].
 */
internal const val NEVER_RAN_MS = 3_000L

/**
 * Whichever of the two frames was built from the later state of the buffer.
 *
 * Publishing is two steps - build a snapshot, then write it - and the writers run on different
 * threads: the output collector on [Dispatchers.Default], `attachTerminal`,
 * `adoptExistingSessions` and every scroll and resize on the main thread. A writer preempted
 * between its two steps writes a snapshot that has since gone stale, and a plain map overwrite
 * lets that stale snapshot *replace* a newer frame.
 *
 * That is not a cosmetic ordering nicety, because nothing is scheduled to correct it. The case
 * that exposed it: `attachTerminal` publishes the frame of a session that has just come up - an
 * empty buffer, revision 0 - so the scrollback of a reconnect or an adopted session is on screen
 * before any new output arrives. Let the collector feed the login banner and publish it while
 * that build is in flight, and the empty frame lands last. A shell sitting at its prompt then
 * sends nothing more, so there is no next frame: the terminal stays blank, the transcript behind
 * it holds the banner, and the only way out is to type something. `frameRevision=0` beside a
 * transcript that has content is the signature, and it is what
 * `theRemoteShellAttachesItselfAfterAuthenticationAndItsOutputArrivesUntyped` caught once the box
 * was loaded enough to lose that race.
 *
 * [AnsiTerminalBuffer.revision] is the ordering, and it is a sound one here: it never goes
 * backwards, and every frame a host publishes is built from the one buffer it keeps for as long as
 * it has a published frame at all - [MainViewModel.closeTab] and [MainViewModel.deleteHost] drop
 * the buffer and the frame together, so a later session's revision 0 is never compared against an
 * earlier session's revisions. Equal revisions keep the incoming frame, because a rebuild of an unchanged buffer is
 * a new *viewport* of it - a scroll, a resize, a foreground republish - and those are built on the
 * main thread in the order they were asked for.
 */
internal fun newerTerminalFrame(published: TerminalFrame?, built: TerminalFrame): TerminalFrame =
    if (published != null && published.revision > built.revision) published else built
