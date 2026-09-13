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
import dev.eclipse.ssh.data.backup.AccountCredentials
import dev.eclipse.ssh.data.backup.BackupFormatException
import dev.eclipse.ssh.data.backup.VaultBackup
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.KeyEdit
import dev.eclipse.ssh.data.credentials.RdpCredentialUpdate
import dev.eclipse.ssh.data.credentials.RdpCredentials
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.saf.LocalAccessUnavailableException
import dev.eclipse.ssh.data.saf.LocalFile
import dev.eclipse.ssh.data.saf.LocalFileBrowser
import dev.eclipse.ssh.data.saf.LocalSyncIndex
import dev.eclipse.ssh.data.saf.localDocumentLength
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.DEFAULT_FORWARD_LISTEN_HOST
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardRuntime
import dev.eclipse.ssh.data.model.ForwardStatus
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.RemoteDesktopConfig
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.data.model.encodeRemoteDesktop
import dev.eclipse.ssh.data.model.describe
import dev.eclipse.ssh.data.model.deviceListenAddress
import dev.eclipse.ssh.data.model.encodeForwardRules
import dev.eclipse.ssh.data.model.savedForwardIdPrefix
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.matchesQuery
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isPastAuthentication
import dev.eclipse.ssh.data.model.isLive
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.data.settings.SnippetRepository
import dev.eclipse.ssh.feature.wakeonlan.WakeOnLan
import dev.eclipse.ssh.feature.wakeonlan.parseMac
import dev.eclipse.ssh.linux.LocalLinuxHost
import dev.eclipse.ssh.linux.LocalTerminalChannel
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.presentation.files.FilesExplorerController
import dev.eclipse.ssh.presentation.linux.LinuxUserspaceController
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
import dev.eclipse.ssh.ssh.isCredentialRejection
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
import dev.eclipse.ssh.background.TransferNotifier
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.security.normalizePastedSecret
import dev.eclipse.ssh.feature.terminallog.SessionLog
import dev.eclipse.ssh.feature.terminallog.SilenceDetector
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val wakeOnLan: WakeOnLan,
    private val transferNotifier: TransferNotifier,
    val filesExplorer: FilesExplorerController,
    val linuxUserspace: LinuxUserspaceController,
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
     * The host a Quick Settings tile or home-screen widget tap has asked to dial, or null when no
     * such tap is pending.
     *
     * [MainActivity] recognises the intent extra and calls [requestQuickConnectLastHost]; this flow
     * then carries the resolved profile to the workspace, which routes it through the very auth
     * prompt a deep link uses. Modelled on that deep-link flow on purpose: a value the UI consumes
     * exactly once, and one the PIN gate leaves untouched because the gate composes a different
     * branch rather than clearing state — so a tap made while the vault is locked dials the moment
     * it is unlocked, instead of being swallowed by the lock screen.
     */
    private val quickConnectHost = MutableStateFlow<HostProfile?>(null)
    val pendingQuickConnect: StateFlow<HostProfile?> = quickConnectHost

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
     * The raw transcript each session keeps for "Save session log", keyed by session key.
     *
     * Beside [scrollOffsets] and [textPublishedAt] rather than inside the UI state because it is
     * written from the collector at output rate and read only when the user exports it - putting a
     * quarter megabyte of text through [uiState] would rebuild every screen in the app per chunk.
     * Created by the collector alongside the session's buffer, so a reconnect (which keeps the
     * same key and the same scrollback) keeps its log too; dropped in the same teardown that
     * drops [terminalOutput] - [closeTab] and [deleteHost].
     */
    private val sessionLogs = ConcurrentHashMap<String, SessionLog>()

    /**
     * The armed "Notify when done" detector of each session that asked for one, keyed by session
     * key and held only while an arming is outstanding - a session that has not asked costs
     * nothing, and one whose detector fired or whose session ended is dropped from here. The
     * [silenceWatchdog] is what turns their verdicts into notifications.
     */
    private val sessionDetectors = ConcurrentHashMap<String, SilenceDetector>()

    /**
     * The one coroutine that polls every armed [sessionDetectors] entry, started on the first
     * arming and ending itself when none are left. One watchdog for all sessions rather than one
     * per session, because the work is a map walk twice a second - a timer per terminal would be
     * the only part of a quiet session that wakes up.
     */
    @Volatile private var silenceWatchdog: Job? = null

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
     * What each forward is doing right now, keyed by entry id — saved rules and hand-opened forwards
     * alike, because a running forward is only a rule with a handle behind it and the forwarding sheet
     * has to show both.
     *
     * A *recorded* state, not necessarily the one the UI is shown: [displayedForwardState] overlays the
     * two states a rule cannot record for itself. A rule has no way to notice that the transport under
     * its tracker died — MINA closes the listening socket from the session's own teardown and nothing
     * calls back into this class — so RECONNECTING and this-session-gone STOPPED are derived where the
     * statuses are produced rather than tracked live, from the ride in [forwardRides].
     */
    private val forwardStates = MutableStateFlow<Map<String, ForwardStatus>>(emptyMap())

    /** The transport one forward rides: the session key it was opened under, and the session itself. */
    private data class ForwardRide(val sessionKey: String, val session: ClientSession)

    /**
     * The transport each forward rides, by entry id.
     *
     * Forwards always ride the host's primary session, but *which* key that is changes: a second
     * terminal can hold the primary while the first one's transport dies, and "the host still has a
     * live session" is then true while this particular tracker is dead. The instance is carried beside
     * the key because a key is not a transport: the reconnect ladder dials a *new* session under the
     * old tab's key, so liveness of the key alone would call the new session "the forward's session"
     * and resurrect a tunnel that died with the old one the moment the reconnect lands. Without this
     * map a hand-opened forward whose session died under a surviving sibling would show RUNNING for as
     * long as the app stayed open — the one display state that is not merely wrong but unrecoverable,
     * because nothing ever rewrites it. Cleared beside its handle in [releaseForwards].
     */
    private val forwardRides = ConcurrentHashMap<String, ForwardRide>()

    /**
     * Handle closes that are still running, by forward id.
     *
     * MINA releases a listening port synchronously *inside* `close()`, but the close is itself a
     * coroutine on [releaseScope] - so a bind issued right after a release (a reconnect rebinding its
     * rules, an edited rule re-claiming the port of the rule it replaced, or Stop followed by Start on
     * the same one) can reach the port first and lose it to the close that is still on its way:
     * "Address already in use", reported against no one. [openForward] joins whatever it finds here
     * before claiming anything, which is every bind there is; entries leave with the close they name.
     */
    private val pendingReleases = ConcurrentHashMap<String, Job>()

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
    /**
     * The refused login a dialog should answer, or null.
     *
     * A rejected credential is the one failure whose fix is a dialog rather than another attempt:
     * the password or the key has to change, and the user is holding both. Only a manual dial
     * raises it — the reconnect ladder never reaches a second attempt with a wrong password
     * (a rejection is final, so the ladder stops after the first), and a background restore dials
     * through the service, which has no dialog to show.
     */
    private val authFailure = MutableStateFlow<AuthFailurePrompt?>(null)
    /**
     * The dropped session a dialog should answer, or null.
     *
     * The whole of ask-first mode: instead of the reconnect ladder scheduling its own backoff after
     * a fault, the tab parks at Disconnected with the reason and this prompt carries the question to
     * the UI. Answering "reconnect" calls the same [connect] the ladder would have, so a recovered
     * session is identical to an automatically recovered one - the property the ladder's KDoc
     * promises. Raised only by the UI-side ladder because a dialog can only exist here; the
     * background service honours ask-first by declining to redial unattended instead.
     */
    private val reconnectPrompt = MutableStateFlow<ReconnectPrompt?>(null)
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

    /**
     * One lock per host, serialising every pass that stops, releases or binds that host's forwards.
     *
     * The reason this exists is the rebind's shape: a new pass (a connect's attach, a reconnect's
     * ladder, an edit) cancels the previous [forwardJobs] entry and immediately wants the same
     * ports back - but a bind that is already running cannot be interrupted, only abandoned once it
     * lands (see [PortForwardingManager] and [abandonForward]). Without a lock, the new pass's stop
     * could run before the dying pass's handle reached [forwardHandles] - so nothing would release
     * it - and its bind could race the dying pass's for the same port, the loser reporting "Address
     * already in use" against nobody. A mutex rather than a job join because joins chain only two
     * deep: a pass cancelled while waiting on its own predecessor completes early, and the pass
     * behind it would sail past a predecessor still in flight. The lock is held for the whole pass,
     * so a cancelled holder unwinds - closing whatever it had just bound - before the next waiter
     * enters.
     */
    private val forwardPasses = ConcurrentHashMap<String, Mutex>()

    /** The serialisation lock for [hostId]'s forwards; see [forwardPasses]. */
    private fun forwardPassesFor(hostId: String): Mutex =
        forwardPasses.computeIfAbsent(hostId) { Mutex() }
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

    /**
     * The two halves of the forwarding picture - which forwards are up, and what every rule is doing -
     * as one source, because the five-slot typed combine below is full and the two only make sense
     * together: a list of running tunnels beside a list of rule states that cannot mention them is how
     * "the sheet says stopped, the port is listening" happens.
     *
     * [tabs] is a source, not read off to the side, because the derived states turn on it: a session
     * ending rewrites the tab in the same update that reports the ending, so the statuses are
     * recomputed at exactly the moment their inputs changed rather than one emission later.
     */
    private val forwardingUi = combine(forwardings, forwardStates, tabs) { active, states, openTabs ->
        ForwardingUiState(
            active = active,
            statuses = if (states.isEmpty()) states else states.mapValues { (_, status) ->
                status.copy(state = displayedForwardState(status, openTabs))
            },
        )
    }

    private val baseUiState = combine(
        combine(coreUiState, hostKeyChallenge, forwardingUi, snippetRepository.snippets, serverStats) { state, challenge, forwarding, savedSnippets, stats ->
            BaseState(state, challenge, forwarding, savedSnippets, stats)
        },
        securityState,
        authFailure,
        reconnectPrompt,
    ) { base, security, refusedLogin, droppedSession ->
        base.state.copy(
            hostKeyChallenge = base.challenge,
            forwardings = base.forwarding.active,
            forwardStatuses = base.forwarding.statuses,
            snippets = base.snippets,
            serverStats = base.stats,
            knownHosts = security.knownHosts,
            savedCredentials = security.credentials,
            authFailure = refusedLogin,
            reconnectPrompt = droppedSession,
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

    /**
     * The host list's "Local Ubuntu 22.04" card, or null when it must not be shown.
     *
     * Passed as a flow rather than folded into [MainUiState] for the same reason [filesExplorer]'s
     * state is not: the card is derived from the userspace's own two flows, exists only on a device
     * that has installed it, and changes far more rarely than any of the five things [MainUiState]
     * already combines — a dedicated flow lets the host screen collect it alone.
     *
     * Null exactly when [LocalLinuxHost.shouldShowCard] says so: not installed, mid-install, being
     * torn down, broken, or never probed healthy. The card is a promise that tapping it opens a
     * terminal, so every state that cannot keep that promise hides it — see [LocalLinuxHost].
     */
    val localLinuxCard: StateFlow<HostProfile?> =
        linuxUserspace.graph?.let { graph ->
            val profile = LocalLinuxHost.hostProfile(graph.distro)
            combine(graph.manager.state, graph.manager.lastHealth) { state, health ->
                if (LocalLinuxHost.shouldShowCard(state, health)) profile else null
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        } ?: MutableStateFlow(null)

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
            // The folder the Files explorer last picked as this device's root, remembered across
            // restarts by the SAF grant that outlives the process. Restored here so a download
            // works on a cold start instead of telling the user to pick a folder they already
            // picked — the in-memory [localDirUri] starts null and nothing else seeds it.
            runCatching { settingsRepository.settings.first().localRootUri }
                .onSuccess { persisted -> if (persisted != null && localDirUri.value == null) localDirUri.value = persisted }
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
                if (tabs.value.any { it.hostId == hostId }) return@forEach
                val host = hosts.firstOrNull { it.id == hostId } ?: return@forEach
                // The session's key travels with it: the tab takes the id the session is already filed
                // under (the host's own id for a service restore, the previous tab's id for a process
                // restart), so everything keyed by session - buffers, channels, the pty size the next
                // reconnect will want - is already where the new tab will look for it. A fresh id would
                // orphan all of it.
                val sessionKey = sessionStore.primarySessionFor(hostId) ?: return@forEach
                val terminal = channels[sessionKey] ?: return@forEach
                val buffer = terminalBuffers.getOrPut(sessionKey) { AnsiTerminalBuffer() }
                updateTab(sessionKey) { existing ->
                    (existing ?: SessionTab(id = sessionKey, hostId = hostId, title = host.name)).copy(
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
                terminalJobs.remove(sessionKey)?.cancelAndJoin()
                terminalJobs[sessionKey] = launchTerminalCollector(sessionKey, hostId, terminal, buffer)
                publishTerminalFrame(sessionKey, buffer)
            }
        }
    }

    fun setQuery(value: String) { query.value = value }
    fun selectHost(host: HostProfile) { selectedHostId.value = host.id }

    /**
     * Resolves the most-recently-connected saved host and offers it to the workspace as a pending
     * quick-connect — the action behind the Quick Settings tile and the home-screen widget.
     *
     * Fire-and-forget rather than `suspend` because its callers are [MainActivity.onCreate] and
     * [MainActivity.onNewIntent], which are not coroutines; the single repository read runs on
     * [viewModelScope]. When no saved host has ever connected the request resolves to null and
     * nothing is shown, so a tap on a fresh install opens the app normally rather than flashing an
     * empty prompt. The choice itself is [mostRecentlyConnectedHost], kept in step with the
     * tile/widget's own resolver so every entry point dials the same host.
     */
    fun requestQuickConnectLastHost() {
        viewModelScope.launch {
            val hosts = runCatching { hostRepository.hosts.first() }.getOrDefault(emptyList())
            quickConnectHost.value = mostRecentlyConnectedHost(hosts)
        }
    }

    /** Clears the pending quick-connect once the workspace has opened its auth prompt for that host. */
    fun consumeQuickConnect() {
        quickConnectHost.value = null
    }

    /** Clears the refused-login prompt once the UI has answered it. */
    fun consumeAuthFailure() {
        authFailure.value = null
    }

    /**
     * The Reconnect action on a terminal tab: re-dials [sessionKey]'s own session.
     *
     * Asked for by session key, not host, because a host can hold several shells and the tab the
     * user tapped is the one that must come back. This used to arrive as a host id and be resolved
     * by [connect] to the host's *first* tab, so reconnecting the second terminal of a host
     * re-dialled the first one and left the tab on screen parked at Disconnected.
     *
     * The dial is the ladder's, not a fresh connection's: `resuming` with the session key said
     * outright, so the attempt accounting and the "Reconnecting · attempt N of M" sentence behave
     * exactly as they do for the prompt answer, and the credential comes from the store or the
     * session registry rather than an interrogation — a refusal still raises the retry prompt.
     */
    fun reconnectSession(sessionKey: String) {
        val tab = tabs.value.firstOrNull { it.id == sessionKey } ?: return
        val host = uiState.value.hosts.firstOrNull { it.id == tab.hostId } ?: return
        connect(host, resuming = true, sessionKey = sessionKey)
    }

    /**
     * Answers the reconnect prompt: reconnects on the same path the ladder would have used, or just
     * clears the question.
     *
     * Consume-first on both branches — the dial writes its own tab state immediately, and a prompt
     * left behind would sit over a session that is already coming back.
     */
    fun answerReconnectPrompt(reconnect: Boolean) {
        val prompt = reconnectPrompt.value ?: return
        reconnectPrompt.value = null
        if (!reconnect) return
        viewModelScope.launch {
            val host = runCatching { hostRepository.hosts.first() }.getOrNull()
                ?.firstOrNull { it.id == prompt.hostId } ?: return@launch
            connect(host, resuming = true, sessionKey = prompt.sessionId)
        }
    }

    fun connect(
        host: HostProfile,
        password: String? = null,
        keyPair: KeyPair? = null,
        keyBytes: ByteArray? = null,
        keyPassphrase: String? = null,
        resuming: Boolean = false,
        sessionKey: String? = null,
        adopt: Boolean = true,
    ) {
        // The local Ubuntu card is a host card like any other, but its "connect" forks a pty in
        // this process instead of dialing a server — no credentials, no transport, no retries. The
        // intercept is the one line that makes the whole feature ride the existing terminal stack:
        // every caller of connect() (the card, the auth path's callers, a future reconnect) reaches
        // [connectLocal] without knowing the difference.
        if (LocalLinuxHost.isLocalHost(host.id)) {
            connectLocal(host, resuming = resuming, sessionKey = sessionKey)
            return
        }
        // Which session this dial speaks for. A session is owned by its *key* — the terminal tab's id —
        // and this is the one place that decides it, before the tab exists:
        //
        //  - a caller that already knows (the reconnect ladder, the prompt answer and a tab's own
        //    Reconnect action, for a specific tab) says so outright;
        //  - a host with a tab open resolves to that tab, so tapping Connect on the host's card
        //    reconnects the session the user is already watching rather than opening a twin;
        //  - otherwise the host's own id, which is what the first tab of a host claims as its key — and
        //    also the key the background service restores under, so a session a restore pass left there
        //    is found by the adoption below instead of being dialled past.
        //
        // `adopt` is false only for a deliberate second terminal to the same host, which must not claim
        // anything the host already has: that is the feature, not a race to be resolved.
        val key = sessionKey
            ?: tabs.value.firstOrNull { it.hostId == host.id }?.id
            ?: host.id
        // A session sitting in the service's host-id slot with no tab of its own — restored while the
        // UI's own session was down — is this dial's to claim. Moving it under the key above (usually a
        // no-op, because they are already the same string) is what lets [adoptStoredSession] find it
        // and open a shell on it rather than logging in a second time. Refused when the key already
        // holds anything, which is the tab's own live session saying so.
        if (adopt) sessionStore.rekey(host.id, key)
        // First, and synchronously: from here on this is the dial that speaks for this session, and
        // every attempt still unwinding somewhere behind it is one whose report the user must not be shown.
        val dial = dialGenerations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        selectedHostId.value = host.id
        pendingConnection = PendingConnection(host, password, keyPair, keyBytes, keyPassphrase, resuming)
        // Asking for this session by hand is a fresh start, whatever the last one did: the allowance
        // is only spent by the ladder's own attempts, and a user who has just tapped Connect (or
        // answered the refused-login prompt with new credentials) is entitled to all of it. The
        // ladder's own attempt - and a tab's Reconnect action, which is the same dial - passes
        // `resuming` and must not reset anything - that is the whole point of counting.
        if (!resuming) reconnectAttempts.remove(key)
        // A fresh manual dial retires any prompt a previous one raised: the user has already acted,
        // and a dialog about an attempt they replaced would sit on top of the one they are watching.
        if (!resuming) authFailure.value = null
        // A reconnect stays RECONNECTING for the whole attempt, keeping the sentence the ladder wrote
        // ("attempt 2 of 5"). Overwriting it with CONNECTING each time round would tell the user the
        // app had started something new, when what is happening is that it has not given up yet.
        updateTab(key) { existing ->
            (existing ?: SessionTab(id = key, hostId = host.id, title = host.name)).copy(
                state = if (resuming) SessionConnectionState.RECONNECTING else SessionConnectionState.CONNECTING,
                lastError = if (resuming) existing?.lastError else null,
            )
        }
        diagnostics.record(
            host.id,
            if (resuming) SessionEvent.RECONNECT_ATTEMPT else SessionEvent.CONNECT_REQUESTED,
            state = if (resuming) SessionConnectionState.RECONNECTING else SessionConnectionState.CONNECTING,
            network = networkMonitor.describe(),
            attempt = reconnectAttempts[key]?.takeIf { resuming },
        )
        // Replace any attempt still running for this session so a double tap cannot leave an
        // orphaned session behind.
        connectJobs.remove(key)?.cancel()
        // A user asking to connect now outranks a backoff waiting to do it later, and leaving the
        // waiter alive would let it fire a second connect on top of this one. Not when this *is* the
        // ladder's own attempt: cancelling the job that is running this code would kill the attempt.
        if (!resuming) {
            reconnectJobs.remove(key)?.let { waiting ->
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
            reconnectPolicies[key] = reconnectPolicyOf(host)
            // Everything from here to the shell being on screen happens under this session's dial gate,
            // which is what stops two dials behind the same key existing at once. The gate is per key
            // rather than per host on purpose: a second terminal to the same host is a feature, and
            // serialising it behind the first one's handshake would make it wait for no reason. The
            // service's restore pass takes the host-id key's gate, so a pass that runs concurrently
            // with a *duplicate* dial can still produce one extra live session — the accepted cost of
            // never closing a session another key is using. See [SshSessionStore.dialing].
            sessionStore.dialing(key) {
                var lastError: Throwable? = null
                for (attempt in 0 until MAX_CONNECT_ATTEMPTS) {
                    try {
                        // Asked again inside the gate, because a session that appeared while this attempt
                        // waited for it is one to use, not one to duplicate - and asked on every attempt,
                        // because a restore pass can install one between two of them. See
                        // [adoptStoredSession] for the two shapes that count.
                        if (adoptStoredSession(host, key, dial, resolved, resuming)) return@dialing
                        val session = sshConnectionManager.connect(host, resolved.password, resolved.keyPair) { phase ->
                            onConnectPhase(key, host.id, dial, phase, resuming, attempt)
                        }
                        // The tab may have been closed (or the host deleted) while the handshake
                        // was in flight. Honour that instead of resurrecting the tab — and tear
                        // the new session down so it does not leak. Dereferencing the missing tab
                        // here used to throw an NPE inside the coroutine and crash the app.
                        if (tabs.value.none { it.id == key }) {
                            runCatching { session.close(false) }
                            return@dialing
                        }
                        val size = ptySizes[key]
                        // Authenticated. Everything from here is the channel and the pty, and it is worth
                        // saying so: a server that accepts the password and then cannot give out a pty
                        // used to spend that whole time claiming to be connecting.
                        markOpeningShell(key, dial, resuming)
                        // The session is authenticated but still unowned: [SshSessionStore.install] is
                        // what hands it to the app, and it runs only after the shell is open. A server
                        // that logs the user in and then refuses the channel - `MaxSessions 0`,
                        // `ForceCommand internal-sftp`, a chroot with no shell - throws out of
                        // `openTerminal` while the transport is held by this local variable, and nothing
                        // else can close it: not `closeTab`, which forgets by host id and was never
                        // given this session, and not `connect`'s own catch, which has already
                        // returned. Every retry of the loop below leaked one such session -
                        // authenticated, heartbeating at its configured interval, invisible to the UI
                        // and to `disconnectAll` for the rest of the process. Closing it here is what
                        // makes the catch block's promise ("retries do not accumulate half-open
                        // sessions") true for the one phase it did not cover: after auth, before shell.
                        val terminal = try {
                            sshConnectionManager.openTerminal(session, size?.first, size?.second, host)
                        } catch (error: Throwable) {
                            runCatching { session.close(false) }
                            throw error
                        }
                        // Never replaces a live session with this one: if another dialler installed one
                        // under this key while the handshake was in flight, that session is the one the
                        // app keeps and this one is the redundant half of a duplicate - the opposite of
                        // what `put` did, which closed the session the user was already typing into.
                        val installed = sessionStore.install(key, session, host.id)
                        if (installed !== session) {
                            // Ours lost, so its pty goes with it. Marked first so its collector reports
                            // the app's decision rather than an outage the reconnect ladder would answer.
                            terminal.markDeliberate()
                            runCatching { terminal.close() }
                            // The incumbent may have no shell on it - a restore pass installs
                            // transport-only sessions - so adopting has to be able to open one. Going
                            // round the loop instead would only lose the same race again, forever.
                            if (adoptStoredSession(host, key, dial, resolved, resuming)) return@dialing
                            continue
                        }
                        attachTerminal(host, key, terminal, resolved)
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
                            updateTab(key) { tab ->
                                // Not this session's dial any more: a countdown belonging to an attempt the
                                // user has already replaced would sit on top of the one they are waiting
                                // for, counting attempts that are no longer being made.
                                if (!isCurrentDial(key, dial)) return@updateTab tab
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
                updateTab(key) { tab ->
                    // See [dialGenerations]. This attempt did fail, and the trace below says so - but a
                    // failure is only news about the session the user is watching if it is still that
                    // session's failure.
                    if (isCurrentDial(key, dial)) {
                        tab?.copy(state = SessionConnectionState.ERROR, lastError = reason)
                    } else {
                        tab
                    }
                }
                // A refused login asks the user for the one thing that can change the outcome: the
                // credential. Everything else stays a status line, because for those the answer is
                // "wait" or "check the network", not a field to type into.
                if (isCurrentDial(key, dial) && !resuming && isCredentialRejection(lastError)) {
                    authFailure.value = AuthFailurePrompt(hostId = host.id, hostName = host.name, reason = reason)
                }
                diagnostics.record(
                    host.id,
                    SessionEvent.CONNECT_FAILED,
                    state = SessionConnectionState.ERROR,
                    // Marked in the trace rather than hidden from it: a CONNECT_FAILED followed by a
                    // session that came up is otherwise a contradiction a reader has to guess at.
                    detail = if (isCurrentDial(key, dial)) reason else "$reason (superseded)",
                    network = networkMonitor.describe(),
                )
            }
        }
        connectJobs[key] = job
        job.invokeOnCompletion { connectJobs.remove(key, job) }
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
        sessionKey: String,
        dial: Long,
        resolved: ResolvedCredentials,
        resuming: Boolean,
    ): Boolean {
        sessionStore.adoptable(sessionKey)?.let { (_, existing) ->
            diagnostics.record(host.id, SessionEvent.ADOPTED, state = SessionConnectionState.CONNECTED)
            attachTerminal(host, sessionKey, existing, resolved)
            return true
        }
        val session = sessionStore.sessionAwaitingShell(sessionKey) ?: return false
        // Recorded before the shell is asked for, so a pty that never opens is attributable to the
        // session it was asked of rather than looking like a fresh handshake that stalled.
        diagnostics.record(host.id, SessionEvent.ADOPTED, state = SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        val size = ptySizes[sessionKey]
        markOpeningShell(sessionKey, dial, resuming)
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
            sessionStore.discard(sessionKey)
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
        attachTerminal(host, sessionKey, terminal, resolved)
        return true
    }

    /**
     * The local Ubuntu session's connect: the same tab, buffer and collector an SSH shell gets,
     * over a forked proot pty instead of a dial.
     *
     * Everything the SSH path needs and this one does not is absent on purpose. No credentials to
     * resolve — the session is entered by process identity, the app's uid *is* the `ubuntu`
     * account. No retry loop — a fork either works or it does not, and retrying instantly would
     * fork again into whatever just failed. No dial gate — there is no transport to serialize over.
     * What it keeps is the session-key contract: the key is the tab's id, the first terminal
     * claims [LocalLinuxHost.HOST_ID], and the collector, the channels map and the buffer are the
     * shared ones — which is what makes a local shell render, scroll, resize and report its ending
     * through the exact path a remote one does.
     *
     * Opening a terminal also starts the userspace when it is stopped. The card is a promise that
     * tapping it opens a shell, and "go to Settings and press Start first" would be the promise
     * withdrawn one tap later; [LinuxUserspaceManager.start]'s probe is what catches a broken
     * environment before a shell is forked into it, and its refusal lands on the tab as the reason.
     */
    private fun connectLocal(host: HostProfile, resuming: Boolean, sessionKey: String?) {
        val graph = linuxUserspace.graph ?: return
        // The same key resolution as the SSH dial above: a caller that knows says so, a tab that
        // is already open is the one reconnected, and the first local terminal claims the
        // synthesized host's own id.
        val key = sessionKey
            ?: tabs.value.firstOrNull { it.hostId == host.id }?.id
            ?: host.id
        selectedHostId.value = host.id
        if (!resuming) reconnectAttempts.remove(key)
        updateTab(key) { existing ->
            (existing ?: SessionTab(id = key, hostId = host.id, title = host.name)).copy(
                state = if (resuming) SessionConnectionState.RECONNECTING else SessionConnectionState.CONNECTING,
                lastError = if (resuming) existing?.lastError else null,
            )
        }
        diagnostics.record(
            host.id,
            if (resuming) SessionEvent.RECONNECT_ATTEMPT else SessionEvent.CONNECT_REQUESTED,
            state = if (resuming) SessionConnectionState.RECONNECTING else SessionConnectionState.CONNECTING,
        )
        connectJobs.remove(key)?.cancel()
        if (!resuming) {
            reconnectJobs.remove(key)?.let { waiting ->
                waiting.cancel()
                diagnostics.record(host.id, SessionEvent.RECONNECT_CANCELLED, detail = "connect requested")
            }
        }
        connectJobs[key] = transportScope.launch {
            try {
                val before = graph.manager.state.value
                if (before is LinuxUserspaceState.NotInstalled || before is LinuxUserspaceState.Installing) {
                    // Cannot happen through the card — it is hidden in exactly these states — but a
                    // stale tap or a race with an uninstall must not fork a shell into a rootfs that
                    // is not there.
                    throw IOException("the Linux environment is not installed")
                }
                if (before is LinuxUserspaceState.Stopped || before is LinuxUserspaceState.NeedsRepair) {
                    // A refusal is fatal only if the userspace did not come up anyway — two
                    // connects racing to start it serialize on the manager's own mutex, and the
                    // loser's start() finds Running and refuses. That refusal is the race resolving
                    // itself, not a failure to show the user.
                    val startFailure = runCatching { graph.manager.start() }.exceptionOrNull()
                    val after = graph.manager.state.value
                    if (after !is LinuxUserspaceState.Running && after !is LinuxUserspaceState.Starting) {
                        val detail = (after as? LinuxUserspaceState.NeedsRepair)?.detail
                            ?: startFailure?.message
                            ?: "the Linux environment is not running"
                        throw IOException(detail)
                    }
                }
                val size = ptySizes[key]
                val process = withContext(Dispatchers.IO) {
                    graph.runtime.spawnSession(rows = size?.second ?: LOCAL_DEFAULT_ROWS, columns = size?.first ?: LOCAL_DEFAULT_COLUMNS)
                }
                val terminal = LocalTerminalChannel(process)
                graph.processes.register(key, terminal)
                // The hand-over rules are [attachTerminal]'s, repeated rather than shared because
                // the shared function also does credential and SFTP work an SSH session needs and
                // this one has no equivalent for: cancel the outgoing collector first so it never
                // reports the hand-over as an outage, and never close a channel another dial
                // installed under this key in the meantime.
                terminalJobs.remove(key)?.cancelAndJoin()
                channels.put(key, terminal)?.let { previous ->
                    if (previous !== terminal) {
                        previous.markDeliberate()
                        runCatching { previous.close() }
                    }
                }
                val buffer = terminalBuffers.getOrPut(key) { AnsiTerminalBuffer() }
                ptySizes[key]?.let { (columns, rows) -> buffer.resize(columns, rows) }
                terminalJobs[key] = launchTerminalCollector(key, host.id, terminal, buffer)
                publishTerminalFrame(key, buffer)
                updateTab(key) { it?.copy(state = SessionConnectionState.CONNECTED, lastError = null) }
                connectedAt[key] = SystemClock.elapsedRealtime()
                diagnostics.record(
                    host.id,
                    SessionEvent.SHELL_OPEN,
                    state = SessionConnectionState.CONNECTED,
                    pty = terminal.ptyLabel,
                    channel = terminal.channelLabel,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
                updateTab(key) { it?.copy(state = SessionConnectionState.ERROR, lastError = reason) }
                diagnostics.record(host.id, SessionEvent.ENDED, state = SessionConnectionState.ERROR, detail = reason)
            }
        }
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
    private suspend fun attachTerminal(
        host: HostProfile,
        sessionKey: String,
        terminal: TerminalChannel,
        resolved: ResolvedCredentials,
    ) {
        // First of everything here, and deliberately ahead of the collector below: the credential is
        // what the reconnect ladder answers an ending with, and the collector is what reports one. Left
        // where it used to be - after the tab was already CONNECTED - the two raced on every session
        // that died young, and the write lost. See [rememberCredentials].
        rememberCredentials(host, resolved)
        // That write cannot be abandoned halfway, so a cancellation arriving during it is deferred to
        // here. Honoured now, before anything below runs: the rest of this function presents a session
        // to the user, and an attempt that has been replaced by another has none to present.
        currentCoroutineContext().ensureActive()
        terminalJobs.remove(sessionKey)?.cancelAndJoin()
        channels.put(sessionKey, terminal)?.let { previous ->
            // Never the channel just installed: adopting hands back the channel that is already in
            // the map, and closing it would end the session this call exists to present.
            if (previous !== terminal) {
                previous.markDeliberate()
                runCatching { previous.close() }
            }
        }
        val buffer = terminalBuffers.getOrPut(sessionKey) { AnsiTerminalBuffer() }
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
        ptySizes[sessionKey]?.let { (columns, rows) -> buffer.resize(columns, rows) }
        terminalJobs[sessionKey] = launchTerminalCollector(sessionKey, host.id, terminal, buffer)
        // An adopted session may be sitting at a prompt with nothing to say, and the collector only
        // publishes when output arrives - so without this the scrollback the user already had would
        // stay off screen until they pressed a key.
        publishTerminalFrame(sessionKey, buffer)
        updateTab(sessionKey) { it?.copy(state = SessionConnectionState.CONNECTED, lastError = null) }
        connectedAt[sessionKey] = SystemClock.elapsedRealtime()
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
        //
        // A host that already has a working SFTP channel — a duplicate terminal attaching under a
        // sibling's session, or a reconnect while the channel survived — keeps it: SFTP is host-scoped
        // (one channel on the host's primary session, see [loginSftp]), so a second login would close
        // and reopen a channel the host is already using. The READY write below mirrors the state onto
        // the new tab, whose copy still says it never tried.
        val sftpReady = tabs.value.any { it.hostId == host.id && it.sftpState == SftpSessionState.READY }
        when {
            sftpReady -> updateHostTabs(host.id) { it?.copy(sftpState = SftpSessionState.READY, sftpError = null) }
            host.autoLoginSftp -> loginSftp(host)
            else -> updateHostTabs(host.id) { it?.copy(sftpState = SftpSessionState.DISABLED, sftpError = null) }
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
     * Only the enabled rules with autoStart are started. An enabled rule without autoStart is listed
     * as STOPPED - it is the forwarding sheet's Start button, not a connect's business - and a
     * disabled rule as DISABLED, and neither is ever bound here, because the flags are a standing
     * instruction about what the app may start on its own. [startForwardRule] is the one deliberate
     * exception: a user pressing Start outranks both flags for as long as the forward runs.
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
        val rules = decodeForwardRules(host.savedForwards, host.id)
        val toStart = rules.filter { it.enabled && it.autoStart }
        val job = transportScope.launch {
            // One host's forwards change hands one pass at a time - see [forwardPasses]. The
            // cancelled predecessor may still be inside a bind, and a bind cannot be interrupted:
            // without the lock this pass's stop would run before the predecessor's handle landed
            // in [forwardHandles] - so nothing would release it - and this pass's bind would race
            // the predecessor's for the same port, the loser reporting "Address already in use"
            // against nobody.
            forwardPassesFor(host.id).withLock {
                // Unconditional, including for a host with no rules: this also clears the previous
                // transport's trackers, and a host whose last rule was just deleted has to end up
                // with nothing bound and nothing claimed on its tab. [releaseDeadRides] beside it
                // takes the hand-opened tunnels the dead transport took with it - forwards no rule
                // will ever rebind, so no later pass would.
                stopSavedForwards(host.id)
                releaseDeadRides(host.id)
                // Every rule gets a row before anything is started, so the sheet can say "Disabled"
                // about a rule that will never bind and "Stopped" about one that only a hand can
                // start - a rule that is not running is only invisible until the user looks for it.
                // The started ones below overwrite their row with STARTING the moment their attempt
                // exists. Written after the stop above, because a release takes the released ids'
                // rows with it.
                forwardStates.update { current ->
                    current + rules.associate { entry ->
                        entry.id to ForwardStatus(
                            entry,
                            when {
                                !entry.enabled -> ForwardRuntime.DISABLED
                                // Enabled but not auto-start: a connect brings up the automatic
                                // rules and deliberately leaves this one for the sheet's Start.
                                else -> ForwardRuntime.STOPPED
                            },
                        )
                    }
                }
                refreshForwardCounters(host.id)
                updateHostTabs(host.id) { it?.copy(forwardError = null) }
                // Forwards belong to the host, not to whichever terminal came up last, so the
                // session they ride is the host's primary one - with a single terminal that is the
                // session that just attached; with several, the first live session the host has.
                val session = sessionStore.primarySession(host.id)
                if (session == null) {
                    // Not an error worth a message: the only way to get here is a session that
                    // ended between the shell opening and this line, and whatever ended it is
                    // already on the tab. The rows above already say STOPPED, which is the truth
                    // about rules that were never attempted.
                    return@launch
                }
                val failures = startForwardBatch(host, session, toStart)
                if (failures.isEmpty()) return@launch
                val reason = failures.joinToString(" · ")
                updateHostTabs(host.id) { it?.copy(forwardError = reason) }
                // One message for the whole set, and it names the host: this fires on a reconnect
                // the user may not have asked for, so a snackbar per failed rule on a flaky link
                // would be a queue of notifications about the same two ports.
                report("Port forwarding on ${host.name}: $reason")
            }
        }
        forwardJobs[host.id] = job
        job.invokeOnCompletion { forwardJobs.remove(host.id, job) }
    }

    /**
     * One rule, on the manager, binding whatever the rule says rather than a constant.
     *
     * [entry.listenHost] is the interface on whichever side the listener lands - this device for LOCAL
     * and DYNAMIC, the server for REMOTE - and it defaults to loopback, so a rule that says nothing
     * still binds exactly what it always did. The one place an explicit bind matters is a remote rule:
     * `listenHost = 0.0.0.0` is the user asking, in writing, for the phone's port to be published on
     * the server's network (on a `GatewayPorts yes` server), which is why nothing fills it in for them
     * - see the codec's own notes on the bind slot.
     *
     * The REMOTE destination is [ForwardEntry.localHost] - the address the *phone* dials when the
     * server's side is connected to - and null means this device's own loopback, `ssh -R`'s default.
     */
    private suspend fun openForward(session: ClientSession, entry: ForwardEntry): ForwardingHandle {
        // The one bind path, so the one place a port is claimed: any release still in flight may be
        // holding it - see [pendingReleases]. Joined as a set because one close serves several ids, and
        // joining another host's moment-long close is cheaper than being wrong about whose port it was.
        pendingReleases.values.toSet().forEach { it.join() }
        // A bind this caller is cancelled out of is closed and registered rather than dropped, so
        // the next bind of the same rule waits for the port it is giving back instead of racing it.
        val abandon: (ForwardingHandle) -> Unit = { handle -> abandonForward(entry.id, handle) }
        return when (entry.type) {
            ForwardType.LOCAL -> portForwardingManager.startLocal(
                session,
                entry.listenHost,
                entry.localPort,
                entry.remoteHost.orEmpty(),
                entry.remotePort ?: 0,
                abandon,
            )
            ForwardType.REMOTE -> portForwardingManager.startRemote(
                session,
                entry.listenHost,
                entry.remotePort ?: 0,
                entry.localHost ?: DEFAULT_FORWARD_LISTEN_HOST,
                entry.localPort,
                abandon,
            )
            ForwardType.DYNAMIC -> portForwardingManager.startDynamic(session, entry.listenHost, entry.localPort, abandon)
        }
    }

    /**
     * Closes a forward whose handle never reached [forwardHandles], and registers the close so the
     * next bind of the same rule waits for it.
     *
     * Two callers, one shape: a bind that finished after its caller's reason for wanting it was
     * taken away. The batch path's is a Stop (or a rewrite) that arrived while the bind was in
     * flight and left STOPPED standing over the STARTING row; the manager's is a cancelled rebind
     * whose `withContext` would otherwise discard a bound tracker on the way out. In both cases the
     * port was genuinely claimed, so the close is registered in [pendingReleases] exactly like
     * [releaseForwards] registers its own - a Start on the same rule must not race the give-back.
     */
    private fun abandonForward(id: String, handle: ForwardingHandle) {
        val close = releaseScope.launch { runCatching { handle.close() } }
        pendingReleases[id] = close
        close.invokeOnCompletion { pendingReleases.remove(id, close) }
    }

    /**
     * Starts [entries] against [session], one state machine per rule, and answers with the failure
     * sentences for the caller's aggregate report.
     *
     * Before any LOCAL or DYNAMIC rule is attempted, the device ports this batch is about to claim are
     * pre-checked - against the forwards already running under [forwardHandles], and against the rules
     * earlier in this same batch. Two rules wanting the same `listenHost:port` is a mistake in the
     * rules, not a race for the socket: without this check the loser's fate depended on scheduling,
     * its message was whatever the bind syscall said, and on a phone the port can also be held by
     * another app, which the OS message names nothing about. The first rule in the batch wins and the
     * loser is [ForwardRuntime.FAILED] with one fixed sentence, spec-mandated so the sheet can match
     * it. A REMOTE rule claims nothing on this device, so it never conflicts here - what it would
     * collide on is a server-side port, and the server is both the authority on that and the one that
     * says so when the bind is refused.
     *
     * Recorded per rule and continued, never aborted: three rules where the first port is taken must
     * still give the user the other two, and "8080 is busy" is a different problem from "this server
     * does not allow forwarding at all".
     */
    private suspend fun startForwardBatch(host: HostProfile, session: ClientSession, entries: List<ForwardEntry>): List<String> {
        val sessionKey = sessionStore.primarySessionFor(host.id)
        val failures = mutableListOf<String>()
        // Seeded with what is running, then grown by this batch: one `add` answers both halves of the
        // pre-check, because a port claimed by a running forward and a port claimed by an earlier rule
        // of this batch are the same refusal to whoever asks next. Only handles whose session is still
        // live seed it - a tracker left over from a dead transport holds no port (the socket went with
        // the session), and counting it would tell the user their own rule was in its own way.
        val claimed = forwardings.value
            .filter { candidate ->
                forwardHandles.containsKey(candidate.id) &&
                    forwardRideIsLive(candidate.id)
            }
            .mapNotNull { it.deviceListenAddress() }
            .toMutableSet()
        entries.forEach { entry ->
            val address = entry.deviceListenAddress()
            if (address != null && !claimed.add(address)) {
                // Fixed wording, on purpose: the sentence is the sheet's way of recognising "two rules
                // want one port", and a message that varies with the loser's position in the batch
                // would be a different sentence every time the same mistake was made.
                val reason = "Port ${entry.localPort} is already in use by another forwarding rule."
                setForwardState(entry, ForwardRuntime.FAILED, reason)
                failures += "${entry.describe()}: $reason"
                refreshForwardCounters(host.id)
                return@forEach
            }
            setForwardState(entry, ForwardRuntime.STARTING)
            try {
                val handle = openForward(session, entry)
                // A Stop that arrived while the bind was in flight found no handle to close and left
                // STOPPED standing over the STARTING row. Honouring it here - closing what just opened
                // and leaving the row stopped - is what keeps Stop a promise against its own race, and
                // the same test tidies a rebind this batch replaced mid-bind, whose rules' rows were
                // rewritten underneath it. Closed by hand because it never reached [forwardHandles],
                // so no other path knows it exists.
                if (forwardStates.value[entry.id]?.state == ForwardRuntime.STOPPED) {
                    // Registered like every other close, because the port this handle just claimed
                    // is being given back and a Start on the same rule would race it exactly like a
                    // rebind races a release.
                    abandonForward(entry.id, handle)
                    refreshForwardCounters(host.id)
                    return@forEach
                }
                forwardHandles[entry.id] = handle
                if (sessionKey != null) forwardRides[entry.id] = ForwardRide(sessionKey, session)
                forwardings.update { it + entry }
                setForwardState(entry, ForwardRuntime.RUNNING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
                setForwardState(entry, ForwardRuntime.FAILED, reason)
                failures += "${entry.describe()}: $reason"
            }
            // Written as each one lands rather than once at the end, so a rule that takes a while to
            // bind does not hide the ones that already worked.
            refreshForwardCounters(host.id)
        }
        return failures
    }

    /** Records one rule's state, replacing whatever it was doing a moment ago. */
    private fun setForwardState(entry: ForwardEntry, state: ForwardRuntime, error: String? = null) {
        forwardStates.update { it + (entry.id to ForwardStatus(entry, state, error)) }
    }

    /**
     * The state the UI should be shown for [status], which is not always the state last recorded.
     *
     * A tracker gives this app no callback when the transport under it dies, so two states cannot be
     * recorded at all and are derived here instead, from the session key the forward rode:
     *
     *  - **RECONNECTING** - the rule was running, its session is gone, and the host's ladder is
     *    bringing that session back. Restricted to saved rules with enabled *and* autoStart, because
     *    those are the only ones the reconnect's own [startSavedForwards] will rebind: a hand-opened
     *    forward or a manual rule on a reconnecting host is gone with the old transport, and showing
     *    it "Reconnecting" would promise a tunnel the reconnect never starts. The host's tab state,
     *    not [reconnectJobs], is the signal: the ladder parks the tab at RECONNECTING in the same
     *    update that reports the ending, and the tab is a flow this map is already derived alongside.
     *  - **STOPPED** - the rule was running (or starting) and its session is gone with no reconnect
     *    coming for it. A stale RUNNING here would be the one display state nothing ever corrects.
     *
     * Anything else is passed through as recorded: FAILED keeps its reason across a reconnect until
     * the new attempt overwrites it, which is honest - the rule *did* fail, and may again.
     */
    private fun displayedForwardState(status: ForwardStatus, openTabs: List<SessionTab>): ForwardRuntime {
        val recorded = status.state
        if (recorded != ForwardRuntime.RUNNING && recorded != ForwardRuntime.STARTING) return recorded
        if (!forwardRideIsLive(status.entry.id)) return deriveDeadRideState(status, openTabs)
        return recorded
    }

    /**
     * Whether the transport [id]'s forward rides is still the live session under its key.
     *
     * The comparison is on the session *instance*, not the key's liveness, because a reconnect dials a
     * fresh session under the old tab's key: the key comes back alive while the tunnel that rode the
     * old transport stays dead.
     */
    private fun forwardRideIsLive(id: String): Boolean =
        forwardRides[id]?.let { ride -> sessionStore.liveSession(ride.sessionKey) === ride.session } == true

    /** What a row whose transport is gone should show while it waits to be rebound or released. */
    private fun deriveDeadRideState(status: ForwardStatus, openTabs: List<SessionTab>): ForwardRuntime {
        val hostId = status.entry.hostId
        val comesBackWithTheLadder = hostId != null &&
            status.entry.enabled &&
            status.entry.autoStart &&
            status.entry.id.startsWith(savedForwardIdPrefix(hostId)) &&
            openTabs.any { it.hostId == hostId && it.state == SessionConnectionState.RECONNECTING }
        return if (comesBackWithTheLadder) ForwardRuntime.RECONNECTING else ForwardRuntime.STOPPED
    }

    /**
     * Rewrites the host's forward counters on the tab: how many of its tunnels are up, against how
     * many enabled rules it has.
     *
     * Counted from [forwardStates] - which holds every rule [startSavedForwards] ever listed for the
     * host, running or not - rather than from a number the start paths increment, because forwards
     * also stop outside those paths (a user's Stop, an edit in [saveForwardRules]) and a counter that
     * only the starter maintains is wrong the first time anything else stops one.
     *
     * `forwardsOpen` counts every RUNNING forward of the host, hand-opened ones included: a tunnel
     * that is carrying traffic is up whether or not a rule asked for it. `forwardsTotal` counts only
     * the host's *saved* enabled rules - that is the ratio the tab has always shown, and a hand-opened
     * tunnel has no denominator. Open can therefore exceed total while a manual tunnel is up, which is
     * the honest sentence: this host has one rule and two tunnels.
     */
    private fun refreshForwardCounters(hostId: String) {
        val prefix = savedForwardIdPrefix(hostId)
        val statuses = forwardStates.value.values.filter { it.entry.hostId == hostId }
        val open = statuses.count { displayedForwardState(it, tabs.value) == ForwardRuntime.RUNNING }
        val total = statuses.count { it.entry.id.startsWith(prefix) && it.entry.enabled }
        updateHostTabs(hostId) { it?.copy(forwardsOpen = open, forwardsTotal = total) }
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
     * Releases the host's forwards whose transport is gone - the hand-opened tunnels a dead session
     * took with it, which nothing else tears down: [stopSavedForwards] handles the rule-derived ones,
     * and the reconnect's own [startSavedForwards] call is the one moment the app knows for sure that
     * the old transport's trackers will never work again and a new session has come to replace them.
     *
     * A dead ride left standing is not inert, either: the moment a new session lands under the old
     * tab's key, key-liveness would flip the row back to RUNNING - a tunnel that is not there,
     * counted open on the tab forever.
     */
    private fun releaseDeadRides(hostId: String) {
        // Only entries with a *dead* ride, not every entry without a live one: a forward still mid-bind
        // has no ride yet, and releasing it here would close a tunnel this very batch is about to own.
        val dead = forwardings.value
            .filter { it.hostId == hostId }
            .filter { entry ->
                forwardRides[entry.id]?.let { ride ->
                    sessionStore.liveSession(ride.sessionKey) !== ride.session
                } == true
            }
            .map { it.id }
        releaseForwards(dead)
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
     *
     * The close is registered in [pendingReleases] for as long as it runs: a bind that happens to
     * want a port this close is still releasing would lose the race to it - MINA's unbind is
     * synchronous *inside* `close()`, but the close itself is a coroutine launched here, so without
     * this handoff the rebind's bind lands first, the port is still held, and the rule reports
     * "Address already in use" against nobody. [openForward] joins whatever it finds there.
     */
    private fun releaseForwards(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val dropped = ids.toSet()
        val handles = dropped.mapNotNull { forwardHandles.remove(it) }
        dropped.forEach { forwardRides.remove(it) }
        forwardings.update { entries -> entries.filterNot { it.id in dropped } }
        // The status rows go with the handles: a released forward is not "stopped", it is gone - the
        // rebind path that calls this re-lists every rule a moment later, and the stop paths that want
        // a STOPPED row to remain on screen ([stopForwardRule]) write it back after this returns. A
        // row left behind here would claim a tunnel nothing is holding.
        forwardStates.update { states -> states.filterNot { it.key in dropped } }
        if (handles.isEmpty()) return
        val close = releaseScope.launch { handles.forEach { handle -> runCatching { handle.close() } } }
        dropped.forEach { pendingReleases[it] = close }
        close.invokeOnCompletion { dropped.forEach { pendingReleases.remove(it, close) } }
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
        sessionKey: String,
        hostId: String,
        terminal: TerminalChannel,
        buffer: AnsiTerminalBuffer,
    ): Job = viewModelScope.launch(Dispatchers.Default) {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val decoder = Utf8StreamDecoder()
        // Created here rather than beside the buffer in `connect` so every path that starts a
        // collector - a fresh connect, an adopted session, a reconnect - gets one, and so a
        // reconnect (same session key, same scrollback) keeps the log it already had.
        val sessionLog = sessionLogs.getOrPut(sessionKey) { SessionLog() }
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
                publishTerminalText(sessionKey, buffer, force = true)
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
            // Only if nothing newer has taken this session over. A reconnect cancels this coroutine
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
            val current = channels[sessionKey]
            if (current == null || current === terminal) {
                channels.remove(sessionKey, terminal)
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
                        upForMs = connectedAt.remove(sessionKey)?.let { SystemClock.elapsedRealtime() - it },
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
                val reaped = sessionStore.reap(sessionKey)
                // Read once, here, because two things now depend on it: the trace, and whether this
                // ending starts a new ladder or continues the one already running.
                val upForMs = connectedAt.remove(sessionKey)?.let { SystemClock.elapsedRealtime() - it }
                // A session that stood up for a while and then dropped is a *new* outage and gets the
                // full allowance back. One that died shortly after coming up is flapping, and its
                // allowance carries over so that five of those in a row reach the end of the ladder and
                // say what happened, instead of reconnecting for as long as the app is open. See
                // [STABLE_SESSION_MS] for why the threshold is minutes rather than seconds.
                if (upForMs != null && upForMs >= STABLE_SESSION_MS) reconnectAttempts.remove(sessionKey)
                // A shell the far end hung up on before it produced a single byte. Answering that with a
                // ladder is the loop users report, so it is answered with the server's reason instead.
                // See [endedBeforeItRan] for how narrow this is.
                val refusedBeforeOutput = endedBeforeItRan(end, upForMs = upForMs, idleForMs = terminal.idleForMs())
                // A server that sends SSH_MSG_DISCONNECT (byPeer) within the first moments of a login did
                // not suffer a transient drop - it refused the session. Reconnecting answers a deliberate
                // server decision and loops on every login, so it is answered with the server's reason
                // instead. See [serverRefusedYoungSession].
                val refusedByServer = serverRefusedYoungSession(end, upForMs = upForMs)
                val refused = refusedBeforeOutput || refusedByServer
                val willReconnect = !refused && shouldAutoReconnect(
                    end,
                    tabIsOpen = tabs.value.any { it.id == sessionKey },
                    endedDeliberately = false,
                    autoReconnectEnabled = reconnectPolicies[sessionKey]?.enabled ?: true,
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
                val reason = when {
                    refusedByServer -> "$endReason · the server disconnected right after login, so it was not retried"
                    refusedBeforeOutput -> "$endReason · closed before the shell produced any output, so it was not retried"
                    else -> endReason
                }
                updateTab(sessionKey) { it?.copy(state = ended, lastError = reason) }
                diagnostics.record(
                    hostId,
                    SessionEvent.ENDED,
                    state = ended,
                    detail = "${end::class.java.simpleName}: $endReason" +
                        (if (refusedByServer) " · refused by server right after login" else "") +
                        (if (refusedBeforeOutput) " · refused before first output" else "") +
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
                    scheduleAutoReconnect(sessionKey, hostId, reason)
                } else {
                    // A shell that exited is finished with its session; nothing is going to use the
                    // transport again, and leaving it open would hold a socket and a heartbeat for a
                    // tab showing a dead prompt. Keyed by session, so this can only ever be closing
                    // the transport under *this* terminal - a sibling terminal to the same host holds
                    // its own session under its own key and is not touched. Through the store rather
                    // than the raw map, so the host index entry goes with it.
                    sessionStore.close(sessionKey)
                }
                // The host's saved tunnels ride its primary session, and this ending may have just
                // taken that transport with it. A sibling terminal still holds a live session, so the
                // forwards can be rebound to the survivor now; on a host whose last session this was,
                // nothing runs - the ladder's own attach brings the tunnels back with the session it
                // dials. [startSavedForwards] stops the old handles first, so a host with no saved
                // rules is a no-op.
                if (sessionStore.sessionKeysForHost(hostId).any { it != sessionKey && sessionStore.isLive(it) }) {
                    runCatching { hostRepository.hosts.first() }.getOrNull()
                        ?.firstOrNull { it.id == hostId }
                        ?.let { host -> startSavedForwards(host) }
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
                    val decoded = decoder.decode(chunk!!)
                    buffer.feed(decoded)
                    // The single tee point for everything a session showed: every decoded chunk
                    // passes through here on its way to the buffer, so the session log and the
                    // notify-when-done detector see exactly what the terminal saw. Typed input
                    // needs no second plumbing - there is no local echo; a keystroke is written to
                    // the channel and comes back through `terminal.output` like any other output,
                    // so it reaches this tee when the shell echoes it.
                    sessionLog.append(decoded)
                    sessionDetectors[sessionKey]?.onOutput()
                    chunk = incoming.tryReceive().getOrNull()
                } while (chunk != null)
                pinScrollback(sessionKey, buffer, before)
                publishTerminalFrame(sessionKey, buffer)
                if (!publishTerminalText(sessionKey, buffer)) transcriptDue.trySend(Unit)
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
            // The same tail goes into the session log - it is real output, the last thing the
            // session ever said, and a log that stops one character short of the farewell it was
            // built to keep would be a strange one. It also feeds the detector, for symmetry with
            // the tee above; a session ending here disarms it via the watchdog's tab check.
            if (tail.isNotEmpty()) {
                buffer.feed(tail)
                sessionLog.append(tail)
                sessionDetectors[sessionKey]?.onOutput()
            }
            // Unconditionally, and past the throttle. Cancelling `transcript` above cancels whatever
            // catch-up it still owed, and at the end of a session there is no next chunk to trigger
            // another one - so a shell whose last second was throttled ended with its farewell on
            // screen but missing from the transcript that Search, Save logs and Save text all read.
            publishTerminalFrame(sessionKey, buffer)
            publishTerminalText(sessionKey, buffer, force = true)
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
    private fun scheduleAutoReconnect(sessionKey: String, hostId: String, endReason: String) {
        // The rules this session was dialled with, or the app-wide ones for a session nothing dialled -
        // one the service restored, or one adopted from a previous process. See [reconnectPolicies].
        val policy = reconnectPolicies[sessionKey] ?: ReconnectPolicy.DEFAULT
        val action = reconnectActionOnDrop(
            // Read from the UI state's cached settings rather than a fresh DataStore read, because
            // this is not a coroutine - the collector calls it directly - and a suspend read here
            // would have to launch, which puts the tab's park state a frame behind the prompt. The
            // cache is at most one emission stale, and the setting changes only from the Settings
            // screen.
            askFirst = uiState.value.settings.reconnectAskFirst,
            attemptsSpent = reconnectAttempts[sessionKey] ?: 0,
            maxAttempts = policy.maxAttempts,
        )
        // Ask-first mode: no ladder runs at all. The order is [reconnectActionOnDrop]'s - the mode
        // spends no attempts, so an unspent allowance is part of the promise. The tab is rewritten
        // here rather than in the collector because the ending-path cannot know the mode; the
        // RECONNECTING it wrote first is replaced before any frame shows it.
        if (action == ReconnectAction.PROMPT) {
            updateTab(sessionKey) {
                it?.copy(state = SessionConnectionState.DISCONNECTED, lastError = "$endReason · waiting for your answer")
            }
            diagnostics.record(
                hostId,
                SessionEvent.RECONNECT_PROMPTED,
                state = SessionConnectionState.DISCONNECTED,
                detail = endReason,
                network = networkMonitor.describe(),
            )
            val hostName = tabs.value.firstOrNull { it.id == sessionKey }?.title ?: hostId
            reconnectPrompt.value = ReconnectPrompt(
                sessionId = sessionKey,
                hostId = hostId,
                hostName = hostName,
                reason = endReason,
            )
            return
        }
        val maxAttempts = policy.maxAttempts
        if (action == ReconnectAction.GIVE_UP) {
            val attempt = (reconnectAttempts[sessionKey] ?: 0) + 1
            updateTab(sessionKey) {
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
        val attempt = (reconnectAttempts[sessionKey] ?: 0) + 1
        reconnectAttempts[sessionKey] = attempt
        val job = viewModelScope.launch {
            val globalSeconds = runCatching { settingsRepository.settings.first().reconnectBaseSeconds }
                .getOrDefault(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
            val window = backoffWindowMs(policy.backoffSeconds(globalSeconds), attempt)
            // Jitter on top of the deterministic window, matching the service's ladder.
            val waitMs = window + Random.nextLong(0, window / 2 + 1)
            updateTab(sessionKey) {
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
                reconnectAttempts[sessionKey] = attempt - 1
                updateTab(sessionKey) {
                    it?.copy(state = SessionConnectionState.RECONNECTING, lastError = "Waiting for a network…")
                }
                networkMonitor.online.first { it }
            }
            if (tabs.value.none { it.id == sessionKey }) return@launch
            // Somebody else brought this session back while the ladder waited - a manual Reconnect, a UI
            // that adopted the session, the service's restore pass - and there is nothing left to do.
            //
            // The test is the *shell*, not the session. `isLive` was true for a live transport with no
            // pty on it, which is exactly what a background restore installs, so a restore pass landing
            // during the backoff ended the ladder and left the tab saying "Reconnecting" over a session
            // that only needed a shell opened on it - permanently, because nothing else was scheduled.
            // Falling through hands it to [connect], which adopts it under the dial gate and opens that
            // shell without a second login. See [adoptStoredSession].
            if (sessionStore.adoptable(sessionKey) != null) return@launch
            val host = runCatching { hostRepository.hosts.first() }.getOrNull()?.firstOrNull { it.id == hostId }
            if (host == null) {
                // The profile was deleted while the ladder was waiting: nothing left to reconnect to.
                reconnectAttempts.remove(sessionKey)
                return@launch
            }
            updateTab(sessionKey) {
                it?.copy(
                    state = SessionConnectionState.RECONNECTING,
                    lastError = "Reconnecting · attempt $attempt of $maxAttempts · $endReason",
                )
            }
            connect(host, resuming = true, sessionKey = sessionKey)
        }
        reconnectJobs.put(sessionKey, job)?.cancel()
        job.invokeOnCompletion { reconnectJobs.remove(sessionKey, job) }
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
    private fun pinScrollback(sessionKey: String, buffer: AnsiTerminalBuffer, linesBefore: Int) {
        val offset = scrollOffsets[sessionKey] ?: return
        if (offset <= 0) return
        val growth = buffer.lineCount() - linesBefore
        if (growth <= 0) return
        val ceiling = buffer.maxScrollOffset(buffer.viewportRows)
        scrollOffsets[sessionKey] = (offset + growth).coerceIn(0, ceiling)
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
    private fun publishTerminalFrame(sessionKey: String, buffer: AnsiTerminalBuffer) {
        if (!isDisplaying(sessionKey, buffer)) return
        if (terminalFrames.subscriptionCount.value == 0) return
        val frame = buffer.frame(scrollOffsets[sessionKey] ?: 0)
        // Asked a second time, from inside the update: the check above only decides whether the frame
        // is worth building. See [isDisplaying] for why the write is where the question has to be
        // settled. [newerTerminalFrame] settles the other question the write has to answer: whether
        // this frame is still the newest one anybody built.
        terminalFrames.update { current ->
            if (!isDisplaying(sessionKey, buffer)) current
            else current + (sessionKey to newerTerminalFrame(current[sessionKey], frame))
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
            current + terminalBuffers.entries.associate { (sessionKey, buffer) ->
                // Same guard as the collector's publish, for the same reason: this builds one snapshot
                // per open session and a session that is printing can publish a newer one in between.
                sessionKey to newerTerminalFrame(current[sessionKey], buffer.frame(scrollOffsets[sessionKey] ?: 0))
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
    private fun publishTerminalText(sessionKey: String, buffer: AnsiTerminalBuffer, force: Boolean = false): Boolean {
        // Reported as published rather than as throttled: there is nothing to catch up to.
        if (!isDisplaying(sessionKey, buffer)) return true
        val now = SystemClock.elapsedRealtime()
        val last = textPublishedAt[sessionKey]
        if (!force && last != null && now - last < TERMINAL_TEXT_MS) return false
        textPublishedAt[sessionKey] = now
        val text = buffer.plainText().takeLast(MAX_TERMINAL_CHARS)
        // Inside the update, for the reason [isDisplaying] gives: a whole scrollback is the largest
        // thing a closed session can leave behind.
        terminalOutput.update { if (isDisplaying(sessionKey, buffer)) it + (sessionKey to text) else it }
        // And the throttle stamp with it, which is written above before the answer is known.
        if (!isDisplaying(sessionKey, buffer)) textPublishedAt.remove(sessionKey)
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
    private fun isDisplaying(sessionKey: String, buffer: AnsiTerminalBuffer): Boolean =
        terminalBuffers[sessionKey] === buffer

    /**
     * Whether [generation] is still the dial [sessionKey] is on. See [dialGenerations].
     *
     * Absent means yes, deliberately: nothing removes a session's counter, so the only way to read null
     * here is for the counter to have gone with the whole map, and refusing to report at all would be
     * the worse of the two failures.
     */
    private fun isCurrentDial(sessionKey: String, generation: Long): Boolean =
        (dialGenerations[sessionKey]?.get() ?: generation) == generation

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
            // The tab this dial was speaking for - the host's existing one, or the fresh one it
            // created - rather than the host id itself, which is only that tab's key by coincidence.
            val sessionKey = tabs.value.firstOrNull { it.hostId == id }?.id ?: id
            updateTab(sessionKey) { it?.copy(state = SessionConnectionState.ERROR, lastError = "Host key was rejected") }
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
    fun sendInput(sessionKey: String, value: String) {
        if (value.endsWith("\n") || value.endsWith("\r")) {
            recordCommand(sessionKey, value.trimEnd('\n', '\r'))
        }
        writeToTerminal(sessionKey, TerminalKeys.encode(TerminalKeys.normalizeNewlines(value)))
    }

    /**
     * Sends text the user typed, one keystroke at a time in the normal case.
     *
     * This is the path the software keyboard uses, so it also has to feed the recent-commands list -
     * see [typedLines] for why that has to be reconstructed rather than observed.
     */
    fun sendText(sessionKey: String, text: String) {
        if (text.isEmpty()) return
        accumulateTyped(sessionKey, text)
        writeToTerminal(sessionKey, TerminalKeys.encode(text))
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
        sessionKey: String,
        key: TerminalKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        val buffer = terminalBuffers[sessionKey]
        val applicationCursorKeys = buffer?.applicationCursorKeysEnabled() ?: false
        val modifiers = TerminalModifiers(ctrl = ctrl, alt = alt, shift = shift)
        when (key) {
            TerminalKey.ENTER -> recordCommand(sessionKey, typedLines.remove(sessionKey)?.toString().orEmpty())
            TerminalKey.BACKSPACE -> typedLines[sessionKey]?.let { line -> if (line.isNotEmpty()) line.setLength(line.length - 1) }
            else -> Unit
        }
        writeToTerminal(sessionKey, TerminalKeys.encode(key, modifiers, applicationCursorKeys))
    }

    /** Sends a single character with its modifiers - the Ctrl row, and the IME's own key events. */
    fun sendChar(sessionKey: String, char: Char, ctrl: Boolean = false, alt: Boolean = false) {
        if (!ctrl && !alt) {
            accumulateTyped(sessionKey, char.toString())
        } else if (ctrl) {
            // Ctrl-C, Ctrl-U and friends all abandon the line one way or another. Keeping a partial
            // command after them would file text the user explicitly discarded.
            typedLines.remove(sessionKey)
        }
        writeToTerminal(sessionKey, TerminalKeys.encode(char, TerminalModifiers(ctrl = ctrl, alt = alt)))
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
    fun pasteFromClipboard(sessionKey: String) {
        val text = secureClipboard.paste()
        if (text.isNullOrEmpty()) {
            report("There is nothing on the clipboard to paste")
            return
        }
        pasteIntoTerminal(sessionKey, text)
    }

    fun pasteIntoTerminal(sessionKey: String, text: String) {
        if (text.isEmpty()) return
        val bracketed = terminalBuffers[sessionKey]?.bracketedPasteEnabled() ?: false
        writeToTerminal(sessionKey, TerminalKeys.paste(text, bracketed))
    }

    /**
     * Whatever is on the system clipboard, ready for a credential field, or null when the clipboard
     * holds nothing usable.
     *
     * The secret-field twin of [pasteFromClipboard]: the read lives here for the same reason (one
     * audited clipboard boundary, not `LocalClipboardManager`), and an empty clipboard is reported
     * for the same reason - a Paste that appears to do nothing is indistinguishable from a broken
     * one. The difference is the normalization: [normalizePastedSecret] strips the newline password
     * managers append, which a `singleLine` field would otherwise have to refuse. The caller owns
     * what happens with the value; a field is never pre-filled from storage, so replacing its
     * contents is the right semantics for a paste here.
     *
     * The report surfaces as a snackbar behind the open dialog's scrim, visible once the dialog
     * closes - the same trade [pasteFromClipboard] already makes.
     */
    fun pasteSecret(): String? {
        val text = secureClipboard.paste()?.let(::normalizePastedSecret)
        if (text.isNullOrEmpty()) {
            report("There is nothing on the clipboard to paste")
            return null
        }
        return text
    }

    /** Scrolls [sessionKey]'s terminal to [offset] lines above the live bottom; 0 follows the output again. */
    fun scrollTerminal(sessionKey: String, offset: Int) {
        val buffer = terminalBuffers[sessionKey] ?: return
        val ceiling = buffer.maxScrollOffset(buffer.viewportRows)
        val clamped = offset.coerceIn(0, ceiling)
        if (clamped == 0) scrollOffsets.remove(sessionKey) else scrollOffsets[sessionKey] = clamped
        publishTerminalFrame(sessionKey, buffer)
    }

    /** Scrolls by [delta] lines - positive is back into the history. */
    fun scrollTerminalBy(sessionKey: String, delta: Int) {
        scrollTerminal(sessionKey, (scrollOffsets[sessionKey] ?: 0) + delta)
    }

    /** The text of a selection, in absolute buffer coordinates, for copy. */
    fun terminalSelectionText(sessionKey: String, fromLine: Int, fromColumn: Int, toLine: Int, toColumn: Int): String =
        terminalBuffers[sessionKey]?.textIn(fromLine, fromColumn, toLine, toColumn).orEmpty()

    /** One whole line of the buffer, for double-tap word selection and select-line. */
    fun terminalLineText(sessionKey: String, line: Int): String =
        terminalBuffers[sessionKey]?.textIn(line, 0, line, Int.MAX_VALUE).orEmpty()

    /**
     * The raw transcript of one session, for "Save session log".
     *
     * Read on demand rather than pushed through [uiState] because it is only ever wanted at the
     * moment the user taps export - see [sessionLogs]. Null when the session has no log (its tab
     * was closed or it never connected), empty when it has simply not produced output yet; the
     * caller treats both as "nothing to save".
     */
    fun sessionLogText(sessionKey: String): String? = sessionLogs[sessionKey]?.snapshot()

    /**
     * Arms "Notify when done" for one session: a notification when the session's output - which
     * must arrive *after* this call, so an idle shell is not mistaken for a finished command - has
     * gone quiet. See [SilenceDetector] for why quiet is the signal.
     *
     * One notification per arming: the detector disarms itself when it fires, and re-arming (this
     * call again) is how the user asks about the next command. The arming is dropped without a
     * notification when the session ends, which the watchdog notices from the tab's state - a
     * session that died under a command did not "finish" it.
     */
    fun notifyWhenDone(sessionKey: String) {
        sessionDetectors.getOrPut(sessionKey) { SilenceDetector() }.arm()
        // Restarting rather than relying on a session-lifetime loop: the watchdog ends itself when
        // the last detector is dropped, so a session that never asks never wakes anything, and this
        // check is what brings polling back.
        if (silenceWatchdog?.isActive == true) return
        silenceWatchdog = viewModelScope.launch {
            while (true) {
                delay(NOTIFY_WHEN_DONE_POLL_MS)
                sessionDetectors.entries.removeIf { (key, detector) ->
                    val tab = tabs.value.firstOrNull { it.id == key }
                    // Gone or no longer live: the session ended, so the wait is over either way
                    // and there is nothing to report - the ending itself is what the tab shows.
                    if (tab == null || !tab.state.isLive) return@removeIf true
                    if (!detector.poll()) return@removeIf false
                    transferNotifier.notifyTerminalDone(key, tab.title)
                    true
                }
                if (sessionDetectors.isEmpty()) break
            }
        }
    }

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
    private fun writeToTerminal(sessionKey: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching { channels[sessionKey]?.writeBytes(bytes) }
    }

    /** Appends printable text to the reconstructed command line. See [typedLines]. */
    private fun accumulateTyped(sessionKey: String, text: String) {
        val line = typedLines.getOrPut(sessionKey) { StringBuilder() }
        for (char in text) {
            when {
                char == '\n' || char == '\r' -> {
                    recordCommand(sessionKey, line.toString())
                    line.setLength(0)
                }
                // Control characters are commands to the shell, not part of what was typed.
                char.code >= 0x20 && char.code != 0x7F -> line.append(char)
            }
        }
        if (line.length > MAX_TYPED_LINE) line.delete(0, line.length - MAX_TYPED_LINE)
    }

    /**
     * Files a finished command under the recent-commands list.
     *
     * The line being filed is session-scoped (each terminal reconstructs its own), but the history is
     * host-scoped on purpose - it is the same shell account, and bash would share one history across
     * two windows on the desktop too. The host is resolved from the tab rather than passed by the
     * caller so the two keyings cannot drift apart at a call site.
     */
    private fun recordCommand(sessionKey: String, command: String) {
        val trimmed = command.trim()
        typedLines.remove(sessionKey)
        if (trimmed.isEmpty()) return
        val hostId = tabs.value.firstOrNull { it.id == sessionKey }?.hostId ?: return
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
    fun resizeTerminal(sessionKey: String, columns: Int, rows: Int) {
        val buffer = terminalBuffers[sessionKey]
        buffer?.resize(columns, rows)
        if (buffer != null) {
            // A taller viewport can leave the stored offset past the top of a short buffer.
            scrollOffsets[sessionKey]?.let { offset ->
                // The buffer's own height, which is the requested one clamped to what a terminal may
                // be; asking for 4 000 rows and then measuring the ceiling against 4 000 would clear
                // an offset that is still perfectly valid.
                val ceiling = buffer.maxScrollOffset(buffer.viewportRows)
                if (offset > ceiling) {
                    if (ceiling <= 0) scrollOffsets.remove(sessionKey) else scrollOffsets[sessionKey] = ceiling
                }
            }
            publishTerminalFrame(sessionKey, buffer)
        }
        // Remembered for the *next* pty this session opens. The composable only reports a size when the
        // size it measures changes, and a reconnect does not change the screen - so without this a new
        // channel kept the 120x40 default while the UI, having already reported the real size once,
        // never mentioned it again. Every full-screen program on a reconnected session was drawn for a
        // terminal twice the width of the phone.
        val previous = ptySizes.put(sessionKey, columns to rows)
        // Only when the geometry really moved. The composable reports on every measurement pass, and
        // an entry per pass would push the interesting history out of the ring within seconds of
        // scrolling. The trace is per *server*, so the host is resolved from the tab rather than
        // assumed to be the key.
        if (previous != columns to rows) {
            tabs.value.firstOrNull { it.id == sessionKey }?.hostId?.let { hostId ->
                diagnostics.record(
                    hostId,
                    SessionEvent.PTY_RESIZED,
                    pty = "${columns}x$rows",
                    detail = previous?.let { "was ${it.first}x${it.second}" },
                )
            }
        }
        // sendWindowChange writes an SSH packet, which blocks when the transport is
        // congested; keep it off the main thread so a stalled link cannot cause an ANR.
        transportScope.launch { runCatching { channels[sessionKey]?.resize(columns, rows) } }
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
            if (sessionStore.primarySession(host.id) == null) {
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
                updateHostTabs(host.id) { tab ->
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
        val session = sessionStore.primarySession(host.id) ?: throw IllegalStateException("${host.name} is not connected")
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
        updateHostTabs(host.id) { it?.copy(sftpState = SftpSessionState.CONNECTING, sftpError = null) }
        val job = transportScope.launch {
            try {
                listRemote(host, null)
                updateHostTabs(host.id) { it?.copy(sftpState = SftpSessionState.READY, sftpError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val reason = describeSftpFailure(error)
                updateHostTabs(host.id) { it?.copy(sftpState = SftpSessionState.FAILED, sftpError = reason) }
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
        launchTransportGuarded("Could not start the upload") {
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
            val session = sessionStore.primarySession(host.id) ?: return@launch report("${host.name} is not connected")
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
            val session = sessionStore.primarySession(host.id) ?: return@launch report("${host.name} is not connected")
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
            val source = sessionStore.primarySession(sourceHost.id)
                ?: return@launch report("${sourceHost.name} is not connected")
            // Only a session opened *here* may be closed here. An already-open one belongs to its tab.
            var dialled: ClientSession? = null
            try {
                val dest = sessionStore.primarySession(destHost.id) ?: run {
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
        launchTransportGuarded("Could not start the download") {
            transferRepository.save(item)
            startDownloadJob(host, item, localUri, remote.path)
        }
    }

    fun downloadToLocal(host: HostProfile, remote: RemoteFile) {
        val localDir = localDirUri.value?.let(Uri::parse) ?: run {
            report("Pick a local folder first")
            return
        }
        launchTransportGuarded("Could not start the download") {
            val target = runCatching {
                DocumentFile.fromTreeUri(context, localDir)?.createFile("application/octet-stream", remote.name)
            }.getOrNull() ?: run {
                report("Cannot create ${remote.name} in the selected folder")
                return@launchTransportGuarded
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
        launchTransportGuarded("Could not start the upload") {
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
        val session = sessionStore.primarySession(host.id) ?: return failTransfer(item, "${host.name} is not connected")
        val stream = runCatching { context.contentResolver.openInputStream(source) }.getOrNull()
            ?: return failTransfer(item, "Cannot read ${item.name}")
        val sftp = runCatching { sshConnectionManager.openSftp(session) }.getOrNull() ?: run {
            runCatching { stream.close() }
            return failTransfer(item, "Cannot open an SFTP channel on ${host.name}")
        }
        transferCoordinator.upload(item, sftp, stream, remotePath, totalBytes)
    }

    private suspend fun startDownloadJob(host: HostProfile, item: TransferItem, destination: Uri, remotePath: String) {
        val session = sessionStore.primarySession(host.id) ?: return failTransfer(item, "${host.name} is not connected")
        val stream = runCatching { context.contentResolver.openOutputStream(destination, "w") }.getOrNull()
            ?: return failTransfer(item, "Cannot write ${item.name}")
        val sftp = runCatching { sshConnectionManager.openSftp(session) }.getOrNull() ?: run {
            runCatching { stream.close() }
            return failTransfer(item, "Cannot open an SFTP channel on ${host.name}")
        }
        transferCoordinator.download(item, sftp, remotePath, stream)
    }

    private suspend fun failTransfer(item: TransferItem, message: String) {
        runCatching { transferRepository.save(item.copy(status = TransferStatus.FAILED, errorMessage = message)) }
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
            val session = sessionStore.primarySession(host.id)
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
            startHandForward(host, entry, "Local forward on port $localPort failed")
        }
    }

    fun startRemoteForward(host: HostProfile, remotePort: Int, localPort: Int) {
        // Bind stays loopback on the server for a hand-opened remote forward, for the reason the
        // comment this dialog's start path has always carried: nobody typing two port numbers into a
        // form has chosen to publish anything, and on a server configured `GatewayPorts yes` an
        // all-interfaces bind would put the phone's port on the server's whole network. A rule that
        // *wants* that says so in its bind field; see [openForward].
        val entry = ForwardEntry(type = ForwardType.REMOTE, localPort = localPort, remoteHost = null, remotePort = remotePort, hostId = host.id)
        transportScope.launch {
            startHandForward(host, entry, "Remote forward of port $remotePort failed")
        }
    }

    fun startDynamicForward(host: HostProfile, localPort: Int) {
        val entry = ForwardEntry(type = ForwardType.DYNAMIC, localPort = localPort, hostId = host.id)
        transportScope.launch {
            startHandForward(host, entry, "SOCKS proxy on port $localPort failed")
        }
    }

    /**
     * Opens one forward the user asked for by hand - the three dialogs' shared body.
     *
     * The state rows are written here for the same reason [startForwardBatch] writes them: the sheet
     * that started a forward by hand is also the sheet that lists what everything is doing, and a
     * hand-opened forward that only ever appeared in the running list could never be told from a saved
     * rule's tunnel, stopped by id, or shown as failed with its reason. The one difference from the
     * batch path is deliberate: no conflict pre-check, because the batch checks a set of rules the app
     * is applying together, while a hand-opened port that is taken fails against whatever took it and
     * the OS's own message is the more useful one - it names the condition, not a sibling rule that
     * may not exist.
     */
    private suspend fun startHandForward(host: HostProfile, entry: ForwardEntry, failureTitle: String) {
        // Same wait as [startForwardRule]: a rebind in flight for this host has binds that have not
        // reached [forwardHandles] yet, and a hand-opened port colliding with one of them would
        // fail against a tunnel nothing can name.
        forwardPassesFor(host.id).withLock {
            val sessionKey = sessionStore.primarySessionFor(host.id)
            val session = sessionStore.primarySession(host.id) ?: return report("${host.name} is not connected")
            setForwardState(entry, ForwardRuntime.STARTING)
            try {
                val handle = openForward(session, entry)
                forwardHandles[entry.id] = handle
                if (sessionKey != null) forwardRides[entry.id] = ForwardRide(sessionKey, session)
                forwardings.update { it + entry }
                setForwardState(entry, ForwardRuntime.RUNNING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportForwardFailure(failureTitle, error)
                setForwardState(
                    entry,
                    ForwardRuntime.FAILED,
                    error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName,
                )
            }
            refreshForwardCounters(host.id)
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

    fun stopForwarding(id: String) = stopForwardRule(id)

    /**
     * Stops one forward - a saved rule or one opened by hand - closing its handle and marking the rule
     * STOPPED rather than removing it from the picture, because a rule the user just stopped is still a
     * rule: the sheet keeps its row, with its Start button armed again.
     *
     * No-op for an id nothing is tracking, which is not an error: the forwarding sheet reads the same
     * map this writes, so a Stop arriving for an already-stopped rule means the list was one frame
     * stale, not that something went wrong.
     */
    fun stopForwardRule(ruleId: String) {
        // Called straight from a Compose click handler, so the close cannot happen here: see
        // [releaseForwards], which drops the entry now and says goodbye to the server off the main
        // thread. The entry goes either way - the forward is certainly not running if the close failed.
        val entry = forwardings.value.firstOrNull { it.id == ruleId } ?: forwardStates.value[ruleId]?.entry
        val hostId = entry?.hostId
        releaseForwards(listOf(ruleId))
        if (entry != null) {
            forwardStates.update { it + (ruleId to ForwardStatus(entry, ForwardRuntime.STOPPED)) }
            // The stopped rule still counts in the total, so only the open count moves.
            if (hostId != null) refreshForwardCounters(hostId)
        }
    }

    /**
     * Starts one rule of [hostId] against the host's live session: the forwarding sheet's Start
     * button, for a rule that is stopped or failed.
     *
     * Deliberately the only start path that ignores [ForwardEntry.enabled] and [ForwardEntry.autoStart]:
     * those flags gate what a *connect* brings up on its own, and a user pressing Start on a disabled
     * or manual rule is an instruction that outranks both - the rule runs until it is stopped, and the
     * next reconnect will not resurrect it, because the reconnect's own path honours the flags.
     *
     * A host with no live session gets the rule marked STOPPED and one sentence saying why, rather
     * than a failure row: "not connected" is a fact about the session, not about the rule, and a
     * FAILED row would accuse a rule that was never attempted. An already-running rule is left alone -
     * its port is claimed, and re-attempting it would report a conflict with itself.
     */
    fun startForwardRule(hostId: String, ruleId: String) {
        transportScope.launch {
            val host = runCatching { hostRepository.hosts.first() }.getOrNull()
                ?.firstOrNull { it.id == hostId } ?: return@launch
            // The rule comes from the recorded rows when the host has any, falling back to the saved
            // column for a host whose rows are empty (never attached this process, or edited elsewhere).
            val entry = forwardStates.value[ruleId]?.entry
                ?: decodeForwardRules(host.savedForwards, host.id).firstOrNull { it.id == ruleId }
                ?: return@launch
            // One host's forwards change hands one pass at a time - see [forwardPasses]. A rebind in
            // flight for this host has binds that have not reached [forwardHandles] yet, so the
            // guard below could not see them; waiting costs nothing and the guard then decides on
            // settled books.
            forwardPassesFor(hostId).withLock {
                // Already up, or on its way up: re-attempting a running rule would find its own port
                // claimed and report a conflict with itself. The *displayed* state is what decides,
                // because a recorded RUNNING whose session has died is a tunnel that no longer exists.
                val recorded = forwardStates.value[ruleId]
                if (forwardHandles.containsKey(ruleId) && recorded != null &&
                    displayedForwardState(recorded, tabs.value) == ForwardRuntime.RUNNING
                ) return@launch
                val session = sessionStore.primarySession(hostId) ?: run {
                    setForwardState(entry, ForwardRuntime.STOPPED)
                    refreshForwardCounters(hostId)
                    report("${host.name} is not connected")
                    return@launch
                }
                val failures = startForwardBatch(host, session, listOf(entry))
                if (failures.isEmpty()) return@launch
                val reason = failures.joinToString(" · ")
                updateHostTabs(hostId) { it?.copy(forwardError = reason) }
                report("Port forwarding on ${host.name}: $reason")
            }
        }
    }

    /**
     * Persists [hostId]'s forwarding rules and applies them to the host's live session without a
     * reconnect, so an edit in the forwarding sheet is a change the tunnels feel immediately.
     *
     * The rules are re-derived from the encoded column after saving rather than trusted from the
     * caller's objects, and that round trip is the whole mechanism: a rule's id is a function of its
     * text, so "the same rule as before" and "a changed rule" are both answered by comparing ids -
     * unchanged rules keep their running tunnels, removed and changed ones are stopped. Re-deriving
     * also means the ids used here are exactly the ids the next connect will produce, whatever the
     * sheet had in hand.
     *
     * The start half only touches rules that are not already up: a user who is editing the list while
     * a tunnel of theirs carries traffic keeps that tunnel, which is what "stop the removed/changed
     * rules" promises and a full stop-and-rebind would break. The cost of id-from-text is that
     * reordering a list changes every shifted rule's id, so a reorder reads as changed rules and
     * restarts them - a moment's blip on a socket the user has to be editing anyway.
     */
    fun saveForwardRules(hostId: String, rules: List<ForwardEntry>) {
        launchGuarded("Could not save the forwarding rules") {
            val host = hostRepository.hosts.first().firstOrNull { it.id == hostId }
                ?: return@launchGuarded report("The host for these rules no longer exists")
            val text = encodeForwardRules(rules)
            // The encode/decode round trip also enforces MAX_SAVED_FORWARDS and drops anything that
            // does not parse, so what runs is exactly what the next connect will read back.
            val saved = decodeForwardRules(text, hostId)
            hostRepository.save(host.copy(savedForwards = text))

            val prefix = savedForwardIdPrefix(hostId)
            val oldIds = buildSet {
                forwardings.value.forEach { if (it.id.startsWith(prefix)) add(it.id) }
                // Keys, not entries: a state row's key is already the entry id.
                forwardStates.value.keys.forEach { if (it.startsWith(prefix)) add(it) }
            }
            val newIds = saved.map { it.id }.toSet()
            // A rebind still running for the previous column is working from stale rules; abandoning it
            // costs at most a STARTING row, which the rewrite below replaces.
            forwardJobs.remove(hostId)?.cancel()
            val job = transportScope.launch {
                // One host's forwards change hands one pass at a time - see [forwardPasses]. The
                // cancelled predecessor may still be inside a bind for one of the old ids, and a
                // bind cannot be interrupted: without the lock the release below could run before
                // the predecessor's handle landed in [forwardHandles] - so nothing would release
                // it - and this pass's bind would race it for the same port.
                forwardPassesFor(hostId).withLock {
                    // Removed and changed rules only - unchanged ids keep their tunnels.
                    releaseForwards(oldIds - newIds)
                    forwardStates.update { current ->
                        val next = current.toMutableMap()
                        saved.forEach { entry ->
                            // A handle on a live session is a tunnel that is up right now, whatever
                            // the row said a moment ago - including a row a just-cancelled rebind
                            // never finished writing. An id that survived the edit with its tunnel
                            // intact keeps both.
                            val running = forwardHandles.containsKey(entry.id) && forwardRideIsLive(entry.id)
                            next[entry.id] = when {
                                !entry.enabled -> ForwardStatus(entry, ForwardRuntime.DISABLED)
                                running -> ForwardStatus(entry, ForwardRuntime.RUNNING)
                                else -> ForwardStatus(entry, ForwardRuntime.STOPPED)
                            }
                        }
                        next
                    }
                    refreshForwardCounters(hostId)
                    updateHostTabs(hostId) { it?.copy(forwardError = null) }
                    val toStart = saved.filter { it.enabled && it.autoStart && !forwardHandles.containsKey(it.id) }
                    if (toStart.isEmpty()) return@launch
                    // Asked for here rather than carried from before the launch, so a session that
                    // died while the save was in flight is not handed to the bind as though it
                    // were alive.
                    val session = sessionStore.primarySession(hostId) ?: return@launch
                    val failures = startForwardBatch(host, session, toStart)
                    if (failures.isEmpty()) return@launch
                    val reason = failures.joinToString(" · ")
                    updateHostTabs(hostId) { it?.copy(forwardError = reason) }
                    report("Port forwarding on ${host.name}: $reason")
                }
            }
            forwardJobs[hostId] = job
            job.invokeOnCompletion { forwardJobs.remove(hostId, job) }
        }
    }

    /**
     * Persists [hostId]'s VNC endpoint. The encode/decode round trip is the validation - the
     * dialog checks its own fields, but this is a public entry point, so the save re-derives what
     * the packed-text column will actually read back and refuses an endpoint that does not
     * survive the trip rather than writing a line the next open cannot parse.
     *
     * Nothing is started here: the viewer owns its tunnel and dials it when opened, which keeps
     * "save an endpoint" and "look at a desktop" as separate actions with separate failures.
     */
    fun saveRemoteDesktopTarget(hostId: String, target: RemoteDesktopTarget) {
        launchGuarded("Could not save the remote desktop target") {
            val host = hostRepository.hosts.first().firstOrNull { it.id == hostId }
                ?: return@launchGuarded report("The host for this target no longer exists")
            val text = encodeRemoteDesktop(RemoteDesktopConfig(vnc = target))
            if (decodeRemoteDesktop(text).vnc == null) {
                return@launchGuarded report("That VNC endpoint could not be saved")
            }
            hostRepository.save(host.copy(remoteDesktop = text))
        }
    }

    /**
     * Persists [hostId]'s RDP endpoint. The same validation rule as [saveRemoteDesktopTarget] -
     * re-read what the packed-text column will say and refuse an endpoint that does not survive the
     * trip - plus the one rule an RDP save has and a VNC save does not: the R line is written into a
     * column that may already carry a V line, and that line must come through the save untouched.
     */
    fun saveRdpTarget(hostId: String, target: RemoteDesktopTarget) {
        launchGuarded("Could not save the RDP target") {
            val host = hostRepository.hosts.first().firstOrNull { it.id == hostId }
                ?: return@launchGuarded report("The host for this target no longer exists")
            val text = encodeRemoteDesktop(
                decodeRemoteDesktop(host.remoteDesktop).copy(rdp = target),
            )
            if (decodeRemoteDesktop(text).rdp == null ||
                decodeRemoteDesktop(text).vnc != decodeRemoteDesktop(host.remoteDesktop).vnc
            ) {
                return@launchGuarded report("That RDP endpoint could not be saved")
            }
            hostRepository.save(host.copy(remoteDesktop = text))
        }
    }

    /**
     * Where the viewer's tunnel gets its SSH session, asked fresh on every (re)connect. A provider
     * and not a session because the viewer outlives the session it started with: SSH's own
     * reconnect ladder may have replaced the transport in between, and the viewer's Reconnect
     * wants whatever the host has *then*, not the object it was handed at open.
     */
    fun remoteDesktopSessionProvider(hostId: String): () -> ClientSession? =
        { sessionStore.primarySession(hostId) }

    /**
     * Saves [hostId]'s RDP credential — the NLA username, domain and password — independently of the
     * host profile, the same split [saveHost] makes: a credential write that cannot complete is
     * reported, never allowed to take an endpoint edit down with it.
     *
     * Nothing consumes the credential yet; the RDP tunnel that will answer NLA challenges with it
     * lands with the viewer. The store is the durable half of that feature, so it arrives first.
     */
    fun saveRdpCredentials(hostId: String, update: RdpCredentialUpdate) {
        launchGuarded("Could not save the RDP credentials") {
            credentialStore.applyRdp(hostId, update)
        }
    }

    /**
     * Reads [hostId]'s saved RDP credential and hands it to [onReady], or null when none is stored.
     *
     * A callback rather than a return because the read decrypts on [Dispatchers.IO] — the viewer's
     * NLA prompt will call this when a challenge arrives, and a suspend call from a click handler is
     * exactly the shape that would otherwise end up blocking a frame. Failures read as null, the
     * same direction every read in this store falls back to: asking the user again is recoverable.
     */
    fun rdpCredentials(hostId: String, onReady: (RdpCredentials?) -> Unit) {
        viewModelScope.launch {
            onReady(runCatching { credentialStore.rdpCredentials(hostId) }.getOrNull())
        }
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
            val session = sessionStore.primarySession(host.id) ?: return@launch report("${host.name} is not connected")
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
     * Pauses every running transfer at once.
     *
     * The coordinator's pause already persists PAUSED through its own cancellation handler
     * (see [persistCancelledTransfer]), so there is nothing to write here - only the loop. It runs
     * over a snapshot of the ids, because each pause rewrites the row it acts on and the flow this
     * list came from would otherwise be a list that changes while it is being iterated.
     */
    fun pauseAllTransfers() {
        launchTransportGuarded("Could not pause the transfers") {
            val running = transferRepository.transfers.first().filter { it.status == TransferStatus.RUNNING }
            running.forEach { transferCoordinator.pause(it.id) }
        }
    }

    /**
     * Resumes everything unfinished that is not waiting on a schedule of its own.
     *
     * A scheduled transfer is excluded because it already has a start time the user chose; resuming
     * it here would be "run now", which is a separate per-item action ([runTransferNow]) that also
     * has to cancel the pending schedule so the two cannot double-start it. Each resume dials its
     * own host through [resumeTransfer], so a list spread over several servers does not serialize
     * behind one connection attempt.
     */
    fun resumeAllTransfers() {
        launchTransportGuarded("Could not resume the transfers") {
            transferRepository.transfers.first()
                .filter { it.status != TransferStatus.COMPLETE && it.scheduledAt == null }
                .forEach { resumeTransfer(it.id) }
        }
    }

    /** Cancels every unfinished transfer: stops the job and drops its row, like [cancelTransfer]. */
    fun cancelAllTransfers() {
        launchGuarded("Could not cancel the transfers") {
            transferRepository.transfers.first()
                .filter { it.status != TransferStatus.COMPLETE }
                .forEach { item ->
                    transferCoordinator.pause(item.id)
                    transferRepository.delete(item.id)
                }
        }
    }

    /**
     * Starts a scheduled transfer now, instead of at the time it was queued for.
     *
     * The pending WorkManager request has to go first: it is what fires the schedule, and leaving it
     * armed beside a transfer this method started by hand would run the same item twice. Clearing
     * [TransferItem.scheduledAt] is what stops the card from still describing itself as scheduled
     * while it is already moving.
     */
    fun runTransferNow(id: String) {
        launchTransportGuarded("Could not start the transfer") {
            val item = transferRepository.transfers.first().firstOrNull { it.id == id } ?: return@launchTransportGuarded
            transferCoordinator.pause(id)
            transferRepository.save(item.copy(scheduledAt = null, errorMessage = null))
            resumeTransfer(id)
        }
    }

    /**
     * Resumes a paused transfer, reconnecting the host first when the session is gone.
     *
     * Every step can fail (the host may be unreachable, the SAF document revoked), and an
     * uncaught throw here used to take the process down because it happened inside a
     * viewModelScope launch with no handler.
     */
    fun resumeTransfer(id: String) {
        launchTransportGuarded("Could not resume the transfer") {
            val item = transferRepository.transfers.first().firstOrNull { it.id == id } ?: return@launchTransportGuarded
            // A cross-host transfer has no local file to reopen and no local stream to append into,
            // so the resume ladder below - which is entirely about reopening a SAF document and an
            // SFTP channel - has nothing to say to it. Refused here with a reason, rather than in
            // the `when` arms as a typed stream that cannot exist: restart semantics for these
            // rows arrive with the transfer UI, and until then a sentence is what the card can show.
            if (item.direction == TransferDirection.CROSS_HOST) {
                return@launchTransportGuarded report("${item.name} cannot be resumed: it is a server-to-server transfer with no local file")
            }
            val host = hostRepository.hosts.first().firstOrNull { it.id == item.hostId }
                ?: return@launchTransportGuarded report("The host for ${item.name} no longer exists")
            val uri = item.localUri?.let(Uri::parse) ?: return@launchTransportGuarded report("${item.name} has no local file")
            var dialFailure: Throwable? = null
            // The same gate and the same install as [connect], for the same reason: this runs from a
            // notification action, so it can land in the middle of the UI's own dial to the host it
            // wants. A read straight out of the map also did not prune - a session that had died was
            // handed straight to `openSftp` - which is what [SshSessionStore.primarySession] is for:
            // any live session the host has, under whichever key it is filed, and nothing else.
            val session = sessionStore.primarySession(host.id) ?: sessionStore.dialing(host.id) {
                // Asked again inside the gate, and host-wide: whatever installed a session for this
                // host while this coroutine waited for the gate is one to reuse, whatever key it is
                // filed under.
                sessionStore.primarySession(host.id) ?: run {
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
                    reconnected?.let { sessionStore.install(host.id, it, host.id) }
                }
            }
            if (session == null) {
                // The dial can also come back null with nothing thrown - another caller holding the
                // gate installed a session that had already died - so the cause is genuinely optional.
                val failure = dialFailure
                return@launchTransportGuarded if (failure == null) {
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
                    // Unreachable rather than reachable-but-wrong: CROSS_HOST is refused at the top
                    // of this function, so a branch that opened anything would mean that guard had
                    // regressed. The null lands in the "Cannot reopen" report below either way.
                    TransferDirection.CROSS_HOST -> null
                }
            }.getOrNull() ?: return@launchTransportGuarded report("Cannot reopen ${item.name}")
            val sftp = runCatching { sshConnectionManager.openSftp(session) }.getOrNull() ?: run {
                runCatching { stream.close() }
                return@launchTransportGuarded report("Cannot open an SFTP channel on ${host.name}")
            }
            when (item.direction) {
                TransferDirection.DOWNLOAD -> transferCoordinator.resumeDownload(item, sftp, stream as OutputStream, existingBytes)
                TransferDirection.UPLOAD -> transferCoordinator.resumeUpload(item, sftp, stream as InputStream)
                // Unreachable for the same reason as the stream open above: CROSS_HOST rows are
                // refused at the top of this function. error() rather than a silent skip, so a
                // regression in that guard surfaces as a loud line instead of a transfer the user
                // pressed Resume on and nothing happened.
                TransferDirection.CROSS_HOST -> error("Cross-host transfers are refused above and cannot be resumed")
            }
        }
    }

    /**
     * Opens a second shell on [tab]'s host: a new tab, a new session, nothing shared with the shell
     * that is already running except the account.
     *
     * The dial goes out under a *fresh* key with `adopt = false`, which is the whole of the feature:
     * [connect] resolves an ordinary dial to the host's existing tab so that Connect reconnects what
     * the user is watching, and a duplicate must not be resolved - it claims nothing, adopts nothing,
     * and cannot land on the session the first terminal is typing into. The cost of that is the one
     * race the design accepts: a restore pass installing a session under the host-id slot at the same
     * moment leaves one extra live session nobody claims, which [SshSessionStore.reap] tidies.
     *
     * `resuming = true` not because anything is being resumed but because it is the dial that may
     * take its credential from the session registry - the shell the user duplicated is authenticated
     * right now, so its credential is there, and a duplicate that prompted for a password the host
     * already knows would be an interrogation, not a feature.
     *
     * The tab is created here rather than by [connect] so it can carry its own title: the host's name
     * plus its ordinal, so two shells on one host are told apart by something a person can read.
     */
    fun duplicateSession(tab: SessionTab) {
        val host = uiState.value.hosts.firstOrNull { it.id == tab.hostId } ?: return
        val key = java.util.UUID.randomUUID().toString()
        val ordinal = tabs.value.count { it.hostId == host.id } + 1
        updateTab(key) { existing ->
            existing ?: SessionTab(id = key, hostId = host.id, title = "${host.name} $ordinal")
        }
        connect(host, resuming = true, sessionKey = key, adopt = false)
    }

    /**
     * Ends the session [tab] belongs to and drops everything it owned.
     *
     * Identified by the tab, not by the object handed in. The list used to be filtered with
     * `tabs.value - tab`, which removes by *value*: every field of the caller's copy had to match the
     * live one, and the copy a caller has is the one it last read from [uiState] - a `StateFlow` that
     * conflates, so it can be one update behind [tabs] by design. A session that had just settled its
     * SFTP state a moment after connecting therefore had a tab in the list that no longer equalled the
     * one on screen, and closing it removed nothing at all: the tap did visibly nothing while the
     * socket, the pty and the collector were all torn down underneath it, leaving a tab pointing at a
     * session that no longer existed. Keyed on `tab.id` because that is the session key - the one
     * identity that stays this terminal's alone when a host has more than one of them.
     *
     * Teardown comes in two scopes, and the split is what keeps a second terminal to the same host
     * alive. The session-scoped half (job, collector, output, store entry) is dropped by the tab's own
     * key and can only touch this terminal. The host-scoped half - SFTP, listings, history, forwards,
     * the registry - belongs to the host as a whole and is only dropped when this was its *last*
     * session: unregistering a host whose other terminal is still open would discard the resume
     * credential and mark the host inactive while its shell is alive on screen.
     */
    fun closeTab(tab: SessionTab) {
        // Before the cancel below, not after: see [isDisplaying]. A collector that is still
        // finishing an iteration must not be able to publish a frame for a session being closed.
        terminalBuffers.remove(tab.id)
        terminalJobs.remove(tab.id)?.cancel()
        connectJobs.remove(tab.id)?.cancel()
        // Closing a tab is the clearest possible statement that this session is not wanted, so it also
        // ends any reconnect waiting to bring it back.
        reconnectJobs.remove(tab.id)?.let { waiting ->
            waiting.cancel()
            diagnostics.record(tab.hostId, SessionEvent.RECONNECT_CANCELLED, detail = "tab closed")
        }
        reconnectAttempts.remove(tab.id)
        reconnectPolicies.remove(tab.id)
        sessionStore.forget(tab.id)
        // The store's forget above closed the channel and dropped it from the shared channels map;
        // the userspace's own session table is the one remaining place this local session is
        // recorded, and leaving it there would count a closed tab in the "N terminal sessions held
        // open" line and in Stop's close-all sweep.
        linuxUserspace.graph?.processes?.unregister(tab.id)
        tabs.value = tabs.value.filterNot { it.id == tab.id }
        terminalOutput.update { it - tab.id }
        terminalFrames.update { it - tab.id }
        sessionLogs.remove(tab.id)
        // Silently: a tab the user closed is not a command that finished, and the notification
        // would arrive for a session that no longer exists.
        sessionDetectors.remove(tab.id)
        scrollOffsets.remove(tab.id)
        textPublishedAt.remove(tab.id)
        typedLines.remove(tab.id)
        ptySizes.remove(tab.id)
        connectedAt.remove(tab.id)
        // Asked *after* the store entry is gone, so the answer already reflects this session leaving.
        // Keys that have died but not been reaped still count - a sibling terminal may come back for
        // one - and the failure mode of getting this wrong in the eager direction is losing a live
        // host's history and registry entry, which is the worse side to be wrong on.
        val siblings = sessionStore.sessionKeysForHost(tab.hostId)
        if (siblings.isNotEmpty()) {
            // Another terminal of this host is still alive: its session keeps the host's SFTP
            // channel, directory listing, command history and forwards, and the host stays
            // registered. [loginSftp] and [startSavedForwards] are what rebind the host-scoped
            // features to the surviving session when they next run.
            return
        }
        sftpJobs.remove(tab.hostId)?.cancel()
        forwardJobs.remove(tab.hostId)?.cancel()
        commandHistory.value = commandHistory.value - tab.hostId
        serverStats.update { it - tab.hostId }
        remoteListings.update { it - tab.hostId }
        homePaths.remove(tab.hostId)
        stopForwardingsFor(tab.hostId)
        // [releaseScope], not [viewModelScope], and for the reason [release] gives: this is teardown, and
        // teardown launched on a scope that dies with the screen is dropped exactly when it matters. The
        // write is a DataStore round trip, so it always outlives the frame that asked for it; every other
        // line of this function has already run synchronously by then. Close the last tab and leave - task
        // swiped, activity finished, `Stop` from the notification - and [viewModelScope] is cancelled
        // while that round trip is in flight, with two consequences that both survive the process. The
        // host is still listed active, so [EclipseSessionService]'s restore pass dials it again on its
        // next start or the next time the network returns: a session the user explicitly closed comes
        // back, reconnecting, which is the complaint this app has spent four releases chasing. And
        // [SessionRegistry.unregister] is also what forgets that host's stored credential, so the
        // password of a session the user has finished with stays at rest instead of being dropped.
        releaseScope.launch { runCatching { sessionRegistry.unregister(tab.hostId) } }
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
            // Both stores, together. The credential store is what the host form reads, but the session
            // registry keeps its own encrypted mirror of the same password/key/passphrase so the
            // reconnect ladder can redial a host whose UI is gone. Forgetting only the first leaves a
            // still-decryptable copy behind for any host with a live or recent session — exactly the
            // secret the user just asked to be rid of. Silent like [deleteHost]'s unregister: the
            // credential-store result below is the one worth a sentence.
            runCatching { sessionRegistry.unregister(host.id) }
            // The one forget covers the RDP credential with the SSH fields: the store's own rule is
            // that "forget this host" means everything durably stored for it, so an NLA username
            // and password cannot survive the action the user asked to be rid of every secret.
            runCatching { credentialStore.forget(host.id) }
                .onSuccess { report("Forgot saved credentials for ${host.name}") }
                .onFailure { error -> report("Could not forget credentials for ${host.name}", error) }
        }
    }

    /**
     * Saves a copy of [host] under a new id, with its saved credentials carried over.
     *
     * The credentials are copied rather than left behind because duplicating is usually "the same
     * box, different purpose" - the second connection wants the same password and key the first one
     * has, and a duplicate that asked for a password on first connect would be a lesser copy of the
     * host it came from. They are re-encrypted under the new id rather than the preference key
     * being pointed at twice, so forgetting the original's credentials later leaves the copy's
     * alone, the way two real hosts behave.
     *
     * The name carries "(copy)" so the new card is distinguishable from the one beside it, with a
     * number when the obvious name is already taken - twice-duplicated means twice-named, or the
     * third card is as anonymous as the second.
     *
     * [copyForwards] answers the question the UI asks when the host has rules: duplicating a host
     * silently duplicated its tunnels too, which is a surprise on a copy made for a different
     * purpose. When the user declines, the saved column is left empty on the copy. When they accept,
     * the rules are copied as text and need no re-keying of their own: a saved rule's id is derived
     * from its host's id at decode time, so the copy's rules get fresh ids for free and never share
     * live handles with the original's.
     */
    fun duplicateHost(host: HostProfile, copyForwards: Boolean = true) {
        launchGuarded("Could not duplicate ${host.name}") {
            val existing = hostRepository.hosts.first().map { it.name }.toSet()
            val base = "${host.name} (copy)"
            val name = if (base !in existing) base else (2..100).firstOrNull { n -> "$base $n" !in existing }?.let { "$base $it" } ?: base
            val copy = host.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                savedForwards = if (copyForwards) host.savedForwards else "",
            )
            hostRepository.save(copy)
            // The fingerprint travels with the profile row; the trust store entry is per-host, so it
            // has to be granted to the copy too or its first connect asks to trust a key the
            // original already accepted.
            host.fingerprint?.trim()?.takeIf(String::isNotBlank)?.let { pinned ->
                runCatching { sshConnectionManager.trustHost(copy, pinned) }
            }
            val stored = runCatching { credentialStore.stored(host.id) }.getOrDefault(StoredCredentials())
            val update = HostCredentialUpdate(
                password = credentialStore.password(host.id)?.let { SecretEdit.Replace(it) } ?: SecretEdit.Keep,
                key = credentialStore.keyBytes(host.id)?.let { bytes ->
                    KeyEdit.Replace(bytes, stored.keyLabel ?: "Private key", stored.keyType)
                } ?: KeyEdit.Keep,
                passphrase = credentialStore.passphrase(host.id)?.let { SecretEdit.Replace(it) } ?: SecretEdit.Keep,
            )
            if (!update.isNoop) {
                runCatching { credentialStore.apply(copy.id, update) }
                    .onFailure { error -> report("Duplicated ${host.name}, but its credentials could not be copied", error) }
            }
            // The RDP credential rides along for the same reason the SSH one does: the copy dials
            // the same box, so its NLA answer is the same account. Re-encrypted under the new id,
            // never shared, so forgetting either host's leaves the other's alone.
            val rdp = runCatching { credentialStore.rdpCredentials(host.id) }.getOrNull()
            if (rdp != null) {
                runCatching {
                    credentialStore.applyRdp(
                        copy.id,
                        RdpCredentialUpdate(
                            username = SecretEdit.Replace(rdp.username),
                            domain = rdp.domain?.let(SecretEdit::Replace) ?: SecretEdit.Keep,
                            password = SecretEdit.Replace(rdp.password),
                        ),
                    )
                }.onFailure { error -> report("Duplicated ${host.name}, but its RDP credentials could not be copied", error) }
            }
            report("Duplicated ${host.name} as $name")
        }
    }

    /**
     * Sends one host's Wake-on-LAN magic packet.
     *
     * Deliberately needs no session, no credentials and no reachability: the whole premise of the
     * feature is that the machine is off and nothing on it can answer, so nothing here touches
     * [SshConnectionManager], the session store or the reconnect ladder - a wake is not a connect and
     * must never look like one on a tab.
     *
     * A host with no address saved (or one that no longer parses, which can only happen through a
     * hand-edited backup, since the form refuses to save one) is answered with a sentence pointing
     * at Edit rather than silence, because an item in the menu that does nothing is indistinguishable
     * from a broken one.
     *
     * Reports "packet sent", never "host is awake": there is no acknowledgement in Wake-on-LAN, and
     * the machine may take a minute to boot even when the wake worked. Whether it is up is answered
     * by the user tapping Connect.
     */
    fun wakeHost(host: HostProfile) {
        val mac = parseMac(host.wakeOnLanMac)
        if (mac == null) {
            report("No Wake-on-LAN address saved for ${host.name} — add one in Edit")
            return
        }
        viewModelScope.launch {
            try {
                // A socket call: Dispatchers.IO, never the main thread, exactly like every other
                // network operation in this class. viewModelScope dispatches on Main.immediate.
                withContext(Dispatchers.IO) { wakeOnLan.wake(mac) }
                report("Wake-on-LAN packet sent to ${host.name}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // The socket's own story ("network unreachable", "no route to host") is the useful
                // half here: it is the difference between the phone being off the LAN and the
                // radio being off entirely.
                report("Wake-on-LAN for ${host.name} failed", error)
            }
        }
    }

    /** Drops every saved credential in the app, for the Settings screen's panic action. */
    fun forgetAllCredentials() {
        viewModelScope.launch {
            // Clears the session registry alongside the credential store. The registry keeps an
            // encrypted mirror of every active/recent host's secrets for the reconnect ladder, so a
            // "forget everything" that skipped it would leave a decryptable copy of exactly the
            // secrets this panic action exists to destroy.
            runCatching { sessionRegistry.clear() }
            runCatching { credentialStore.forgetAll() }
                .onSuccess { report("Forgot every saved credential") }
                .onFailure { error -> report("Could not clear saved credentials", error) }
        }
    }
    fun clearCompletedTransfers() = launchGuarded("Could not clear the completed transfers") { transferRepository.clearCompleted() }

    fun deleteHost(host: HostProfile) {
        launchGuarded("Could not delete ${host.name}") {
            // Every session the host has, under every key - deleting the profile ends them all, not
            // only the first terminal's. The keys come from the store's index rather than from the tab
            // list so a session with no tab (a service restore) is closed too.
            val keys = sessionStore.sessionKeysForHost(host.id)
            // Before the cancel below, not after: see [isDisplaying]. A collector that is still
            // finishing an iteration must not be able to publish a frame for a session being closed.
            keys.forEach { key -> terminalBuffers.remove(key) }
            keys.forEach { key -> terminalJobs.remove(key)?.cancel() }
            keys.forEach { key -> connectJobs.remove(key)?.cancel() }
            keys.forEach { key ->
                reconnectJobs.remove(key)?.cancel()
                reconnectAttempts.remove(key)
                reconnectPolicies.remove(key)
                scrollOffsets.remove(key)
                textPublishedAt.remove(key)
                typedLines.remove(key)
            }
            sftpJobs.remove(host.id)?.cancel()
            forwardJobs.remove(host.id)?.cancel()
            // Through the store, which marks the channel deliberate so its ending is not read as a
            // fault, and which does the socket half off the main thread. Deleting a host used to close
            // both here, on the caller's dispatcher — and `launchGuarded` is a `viewModelScope` body, so
            // that was `SSH_MSG_DISCONNECT` written from the UI thread. The buffer is already gone,
            // removed above for the reason recorded there, so this deliberately is not `forget`.
            keys.forEach { key -> sessionStore.close(key) }
            tabs.value = tabs.value.filterNot { it.hostId == host.id }
            terminalOutput.update { current -> current - keys.toSet() }
            terminalFrames.update { current -> current - keys.toSet() }
            keys.forEach { key -> sessionLogs.remove(key) }
            keys.forEach { key -> sessionDetectors.remove(key) }
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
            // The saved password and key passphrase ride along so the account is complete on the
            // other device - the point of an account export. Read via the same plaintext accessors
            // duplicateHost uses, guarded on what stored() says exists so an account with nothing
            // saved exports without the credentials block at all. The private key is deliberately
            // not included: it has its own export path, and the key is the credential whose
            // compromise is catastrophic.
            val stored = runCatching { credentialStore.stored(host.id) }.getOrDefault(StoredCredentials())
            val credentials = AccountCredentials(
                password = if (stored.hasPassword) credentialStore.password(host.id) else null,
                passphrase = if (stored.hasPassphrase) credentialStore.passphrase(host.id) else null,
            ).takeIf { it.password != null || it.passphrase != null }
            writeDocument(uri, VaultBackup.encrypt(VaultBackup.toAccountJson(host, credentials), passphrase))
            // Generic on purpose: the snackbar is readable over a shoulder and in a screenshot,
            // and whether this file carries a password is not its business.
            report("Exported ${host.name}")
        }
    }

    fun importAccount(passphrase: String, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        guardBackup("Import failed") {
            val imported = VaultBackup.fromAccountJson(VaultBackup.decrypt(readDocument(uri), passphrase))
            hostRepository.save(imported.host)
            // The same decrypt-then-apply dance duplicateHost does. A password is applied as-is;
            // a passphrase only when the host already has a key on this device, because the
            // credential store drops a passphrase with no key to attach it to - the key itself
            // never travels in the account file, so the passphrase waits for it here.
            val stored = runCatching { credentialStore.stored(imported.host.id) }.getOrDefault(StoredCredentials())
            val update = imported.credentials?.let { credentials ->
                HostCredentialUpdate(
                    password = credentials.password?.let { SecretEdit.Replace(it) } ?: SecretEdit.Keep,
                    passphrase = if (stored.hasKey) {
                        credentials.passphrase?.let { SecretEdit.Replace(it) } ?: SecretEdit.Keep
                    } else SecretEdit.Keep,
                )
            } ?: HostCredentialUpdate()
            if (!update.isNoop) {
                runCatching { credentialStore.apply(imported.host.id, update) }
                    .onFailure { error -> report("Imported ${imported.host.name}, but its credentials could not be saved", error) }
            }
            // One message, so the qualification is not spoken over by the plain confirmation.
            val keyNote = if (imported.credentials?.passphrase != null && !stored.hasKey) {
                " — import its private key to use the saved passphrase"
            } else ""
            report("Imported ${imported.host.name}$keyNote")
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
            settingsRepository.setReconnectAskFirst(settings.reconnectAskFirst)
            settingsRepository.setVaultAutoLockMinutes(settings.vaultAutoLockMinutes)
            // The blob is one key, so one call restores all six editor options at once. Its
            // codec decodes tolerantly, so a mangled import degrades to defaults, never a lockout.
            settingsRepository.setEditorPrefsJson(settings.editorPrefsJson)
            // The shortcut bar blob, same contract: one call, tolerant decode, defaults on damage.
            settingsRepository.setTerminalKeyBarJson(settings.terminalKeyBarJson)
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
     * [launchGuarded] on the transport dispatcher, for the bodies that must not run on the main
     * thread (see [transportScope]) and could still throw on their way through storage.
     *
     * The transfer path was the one part of the view model the guarded-launch fix did not reach:
     * `transferRepository.save` and the `.first()` reads feeding it are exactly the `SQLiteFullException`
     * / `SQLiteDiskIOException` calls that fix exists for, and a drag-and-drop onto a full disk took
     * the process down with every live session in it. Same contract as [launchGuarded] — the reason
     * is reported, cancellation is not swallowed.
     */
    private fun launchTransportGuarded(what: String, body: suspend () -> Unit) = transportScope.launch {
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
    fun setReconnectAskFirst(enabled: Boolean) = writeSetting("the reconnect prompt setting") { settingsRepository.setReconnectAskFirst(enabled) }
    fun setTerminalKeepSystemBars(enabled: Boolean) = writeSetting("the terminal system bars setting") { settingsRepository.setTerminalKeepSystemBars(enabled) }
    fun setVaultAutoLockMinutes(minutes: Int) = writeSetting("the vault auto-lock delay") { settingsRepository.setVaultAutoLockMinutes(minutes) }
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
    fun setEditorPrefsJson(json: String) = writeSetting("the editor preferences") { settingsRepository.setEditorPrefsJson(json) }
    fun setTerminalKeyBarJson(json: String) = writeSetting("the shortcut bar layout") { settingsRepository.setTerminalKeyBarJson(json) }
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
        // And the rows that had no handle to close go with them: the view model is gone, and a status
        // map that outlived its handles would be a claim about tunnels nobody can start or stop.
        forwardStates.value = emptyMap()
        forwardRides.clear()
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
        // The rows with nothing to release - a disabled rule, a stopped one, a failure nobody is
        // retrying - belong to a host whose last session just went, and leaving them would let the
        // forwarding sheet list rules for a host it can no longer start them against.
        forwardStates.update { states -> states.filterNot { it.value.entry.hostId == hostId } }
    }

    /**
     * Applies [transform] to the tab whose id is [sessionKey], **in place**.
     *
     * Keyed by the tab's id because that is the session key — with more than one terminal per host,
     * a host id matches several tabs and "which one" would be decided by accident. The transform is
     * handed `null` when no such tab exists, so a creator can put one there; a transform that answers
     * `null` leaves the list alone.
     *
     * The index matters: this used to be `filterNot { it.hostId == hostId } + updated`, which moved a
     * tab to the end of the strip on every state change. Connecting two hosts at once was enough to
     * see it — the first tab jumped past the second the moment its handshake went CONNECTING →
     * RECONNECTING, so the strip reshuffled under the user's finger while they were reaching for it.
     * A tab keeps the position it was opened in for as long as it is open.
     */
    private fun updateTab(sessionKey: String, transform: (SessionTab?) -> SessionTab?) {
        // Atomic, because not every caller is on the main thread: the connect phase callback is
        // invoked from MINA's dial on Dispatchers.IO while the collector and the reconnect ladder are
        // writing the same list from the main dispatcher. Read-modify-write on `tabs.value` lost
        // whichever update finished second - the state a tab was left showing depended on a race.
        // `update` retries its transform on conflict, which is safe here: every transform is a pure
        // `copy` of the tab it was handed.
        tabs.update { current ->
            val index = current.indexOfFirst { it.id == sessionKey }
            val updated = transform(current.getOrNull(index)) ?: return@update current
            if (index < 0) current + updated else current.toMutableList().also { it[index] = updated }
        }
    }

    /**
     * Applies [transform] to every tab of [hostId] — the writes that are about the *host* rather than
     * about one of its terminals.
     *
     * SFTP state, saved-forward counts and their failures live on the tab because that is where the
     * user reads them, but they describe a channel the whole host shares: one SFTP login, one set of
     * tunnels. With a single terminal the two keyings coincide; with several, a state that only one
     * tab carried would tell the user the file browser was broken in one terminal and working in
     * another on the same host. Creates nothing — there is no session to speak of until [updateTab]
     * has made its tab.
     */
    private fun updateHostTabs(hostId: String, transform: (SessionTab?) -> SessionTab?) {
        tabs.update { current ->
            if (current.none { it.hostId == hostId }) current
            else current.map { if (it.hostId == hostId) transform(it) ?: it else it }
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
    private fun markOpeningShell(sessionKey: String, dial: Long, resuming: Boolean) {
        if (resuming) return
        val current = isCurrentDial(sessionKey, dial)
        updateTab(sessionKey) { tab ->
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
    private fun onConnectPhase(
        sessionKey: String,
        hostId: String,
        dial: Long,
        phase: SshConnectPhase,
        resuming: Boolean,
        attempt: Int,
    ) {
        val state = when {
            resuming -> SessionConnectionState.RECONNECTING
            phase == SshConnectPhase.HANDSHAKE -> SessionConnectionState.CONNECTING
            else -> SessionConnectionState.AUTHENTICATING
        }
        val current = isCurrentDial(sessionKey, dial)
        updateTab(sessionKey) { tab ->
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

    /** The forwarding half of [BaseState]: the running forwards, and every rule's current status. */
    private data class ForwardingUiState(
        val active: List<ForwardEntry>,
        val statuses: Map<String, ForwardStatus>,
    )

    private data class BaseState(
        val state: MainUiState,
        val challenge: HostKeyChallenge?,
        val forwarding: ForwardingUiState,
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
         * The pty size a local Ubuntu session is forked at before the terminal composable has
         * reported its real one. The same 120x40 both channel implementations start at, repeated
         * here because the constants are private to them and the fork needs concrete numbers.
         */
        const val LOCAL_DEFAULT_COLUMNS = 120
        const val LOCAL_DEFAULT_ROWS = 40
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
         * How often the armed notify-when-done detectors are asked whether their session has gone
         * quiet. A quarter of [SilenceDetector.SILENCE_MS], so the notification lands within a
         * beat of the silence being real rather than up to a whole window late.
         */
        const val NOTIFY_WHEN_DONE_POLL_MS = 500L

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

/**
 * A login the server refused, for the dialog that asks the user to answer it.
 *
 * Carries no credential and no reason beyond the sentence the status line already shows — the
 * dialog's job is to collect a *new* password, passphrase or key file, not to repeat the failure.
 * [hostId] rather than the profile itself, so a host deleted while the dialog was open cannot be
 * resurrected by its own Retry button: the UI looks the host up and drops the prompt if it is gone.
 */
data class AuthFailurePrompt(
    val hostId: String,
    val hostName: String,
    /** What the terminal status line says, safe to show — see `describeConnectFailure`. */
    val reason: String,
)

/**
 * A dropped session waiting for the user's answer — ask-first reconnect mode, the UI half.
 * Shaped after [AuthFailurePrompt] because it is the same kind of thing: a fault whose next step
 * belongs to the user rather than to the app.
 */
data class ReconnectPrompt(
    /** The session key (the tab's id) whose drop this question is about. */
    val sessionId: String,
    val hostId: String,
    val hostName: String,
    /** Why the session ended, as the terminal status line already says it. */
    val reason: String,
)

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
    /**
     * The login a server just refused, for the dialog that asks the user to type the credential
     * again. See [AuthFailurePrompt]. Null whenever no refusal is waiting to be answered.
     */
    val authFailure: AuthFailurePrompt? = null,
    /** A dropped session asking whether to reconnect — non-null only in ask-first mode. */
    val reconnectPrompt: ReconnectPrompt? = null,
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
    /**
     * Every forwarding rule this process has touched, keyed by entry id, paired with what it is doing
     * right now - saved rules and hand-opened forwards alike, so the forwarding sheet can show a
     * disabled rule as Disabled and a failed one with its reason instead of only listing the tunnels
     * that are up ([forwardings] above).
     *
     * The states here are the *displayed* ones: RECONNECTING, and the STOPPED of a rule whose session
     * died, are derived at the source (see `MainViewModel.displayedForwardState`) rather than recorded,
     * because a tracker gives this app no callback when the transport under it goes. No error string
     * in here carries anything but hostnames, ports and a failure reason - forwarding failures come
     * from bind and channel errors, which never see a credential.
     */
    val forwardStatuses: Map<String, ForwardStatus> = emptyMap(),
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

/** What the ladder does with a drop: dial on its own, ask first, or stop. */
internal enum class ReconnectAction { SCHEDULE, PROMPT, GIVE_UP }

/**
 * Which of the three things a drop leads to, as one decision with a stated order.
 *
 * The order is the behaviour: ask-first wins over an exhausted allowance, because the mode spends no
 * attempts — the ladder never ran, so there is nothing to be out of. A user who answers *reconnect*
 * gets a fresh ladder with every attempt it would have had, and a user who switches the mode on
 * halfway through one host's ladder is prompted on its next drop rather than told the app has given
 * up on a session it never tried to recover.
 *
 * Pure for the same reason [shouldAutoReconnect] is: the precedence is the part worth pinning down,
 * and the states it has to get right — mode on with attempts spent, mode off at the top of the
 * ladder — are exactly the ones a running app cannot be made to produce on demand.
 */
internal fun reconnectActionOnDrop(askFirst: Boolean, attemptsSpent: Int, maxAttempts: Int): ReconnectAction =
    when {
        askFirst -> ReconnectAction.PROMPT
        attemptsSpent >= maxAttempts -> ReconnectAction.GIVE_UP
        else -> ReconnectAction.SCHEDULE
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

/**
 * Whether the far end explicitly disconnected a session that had only just come up, in which case the
 * ladder is the loop rather than the cure - the server's decision, not a link that faltered.
 *
 * This is the companion to [endedBeforeItRan] for the one shape it cannot see. [endedBeforeItRan] only
 * fires when the server sent nothing at all, so a server that accepts the login and then sends
 * `SSH_MSG_DISCONNECT` - an idle timeout it applies the instant the pty opens, an administrator, a
 * policy that refuses the session after authentication - slips past it, because [shouldAutoReconnect]
 * answers every [SessionEnd.Disconnected] with a redial. That redial reaches the same refusal, and
 * because it is deterministic the tab shows *Reconnecting…* on every login instead of the server's
 * reason.
 *
 * The ending this catches is the narrowest possible, and on purpose:
 *
 *  - **the far end must have sent `SSH_MSG_DISCONNECT` itself** - [SessionEnd.Disconnected.byPeer] true.
 *    That is the only ending that is unambiguously the server's decision rather than a transport event:
 *    a bare channel close ([SessionEnd.TransportClosed]) is what a phone leaving Wi-Fi looks like to
 *    the client and is left reconnect-worthy on purpose (see `aDroppedTransportIsNeverSilent` and
 *    `aFlappingSessionStopsReconnectingAndSaysWhy`), and a [SessionEnd.Disconnected] MINA raised itself
 *    ([SessionEnd.Disconnected.byPeer] false - a protocol or MAC error) is a fault worth retrying.
 *  - **[upForMs] must be under the floor.** A session that lasted longer than a couple of seconds was
 *    interrupted, not refused; an idle timeout or an admin hang-up on a session that was in use is
 *    exactly what auto-reconnect is for.
 *
 * The user is not left worse off: the tab lands in ERROR carrying the server's own words, and the
 * manual Reconnect button is still offered on every ended state. What they lose is an automatic redial
 * that was always going to fail, and what they gain is the reason.
 *
 * Pure, so the whole matrix is testable without a server that can be talked into refusing a shell.
 */
internal fun serverRefusedYoungSession(end: SessionEnd, upForMs: Long?): Boolean {
    if (upForMs == null || upForMs >= NEVER_RAN_MS) return false
    return end is SessionEnd.Disconnected && end.byPeer
}

/**
 * Picks the saved host with the greatest [HostProfile.lastConnectedAt], or null when no host has ever
 * connected.
 *
 * Top-level and `internal` for the same reason as the other resolvers above: the choice is testable
 * without standing up the whole view model. Kept deliberately in step with the Quick Settings tile
 * and home-screen widget's own resolver (`LastHostProvider`) so all three entry points dial the
 * identical host — the newest of the hosts that have actually connected, and nothing when none have.
 */
internal fun mostRecentlyConnectedHost(hosts: List<HostProfile>): HostProfile? =
    hosts.filter { it.lastConnectedAt != null }.maxByOrNull { it.lastConnectedAt ?: 0L }
