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
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.matchesQuery
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.data.model.SessionConnectionState
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
import dev.eclipse.ssh.ssh.describeConnectFailure
import dev.eclipse.ssh.ssh.describeSftpFailure
import dev.eclipse.ssh.ssh.fallbackHome
import dev.eclipse.ssh.ssh.joinRemote
import dev.eclipse.ssh.ssh.OpenSshConfigParser
import dev.eclipse.ssh.background.NetworkMonitor
import dev.eclipse.ssh.background.awaitReconnectWindow
import dev.eclipse.ssh.background.backoffWindowMs
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
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    private val remotePath = MutableStateFlow<String?>(null)
    private val localFiles = MutableStateFlow<List<LocalFile>>(emptyList())
    private val localDirUri = MutableStateFlow<String?>(null)
    private val forwardings = MutableStateFlow<List<ForwardEntry>>(emptyList())
    private val forwardHandles = ConcurrentHashMap<String, ForwardingHandle>()
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
     * In-flight SFTP logins, one per host, so a reconnect cannot leave the previous attempt writing
     * a stale listing (or a stale failure) into the tab it no longer belongs to.
     */
    private val sftpJobs = ConcurrentHashMap<String, Job>()
    /** Remote home directory resolved from the server, keyed by host id. */
    private val homePaths = ConcurrentHashMap<String, String>()

    /**
     * Automatic reconnects waiting out their backoff, one per host. See [scheduleAutoReconnect].
     */
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

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
     * Cuts a reconnect backoff short the moment the platform reports a usable network.
     *
     * Conflated, and fed from [NetworkMonitor] rather than collected inline, because the signal has to
     * survive being raised while a connect attempt is already in flight — nobody is receiving then, and
     * a lost signal means waiting out the rest of a five-minute window on a network that came back
     * immediately. Same channel shape as the foreground service uses; see [awaitReconnectWindow].
     */
    private val reconnectWake = Channel<Unit>(Channel.CONFLATED)
    private var pendingConnection: PendingConnection? = null

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

    private val terminalState = combine(terminalOutput, commandHistory) { output, history ->
        TerminalState(output = output, history = history)
    }

    private val remoteState = combine(remoteFiles, remotePath) { files, path ->
        RemoteState(files = files, path = path)
    }

    private val localState = combine(localFiles, localDirUri) { files, dir ->
        LocalState(files = files, dirUri = dir)
    }

    val uiState: StateFlow<MainUiState> = combine(baseUiState, transferRepository.transfers, terminalState, remoteState, localState) { state, activeTransfers, terminal, remote, local ->
        state.copy(
            transfers = activeTransfers,
            terminalOutput = terminal.output,
            commandHistory = terminal.history,
            remoteFiles = remote.files,
            remotePath = remote.path,
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
        viewModelScope.launch {
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
                terminalJobs.remove(hostId)?.cancelAndJoin()
                terminalJobs[hostId] = launchTerminalCollector(hostId, terminal, buffer)
                publishTerminalFrame(hostId, buffer)
            }
        }
    }

    fun setQuery(value: String) { query.value = value }
    fun selectHost(host: HostProfile) { selectedHostId.value = host.id }

    fun connect(host: HostProfile, password: String? = null, keyPair: KeyPair? = null, keyBytes: ByteArray? = null, keyPassphrase: String? = null) {
        selectedHostId.value = host.id
        pendingConnection = PendingConnection(host, password, keyPair, keyBytes, keyPassphrase)
        updateTab(host.id) { existing ->
            (existing ?: SessionTab(hostId = host.id, title = host.name)).copy(
                state = SessionConnectionState.CONNECTING,
                lastError = null,
            )
        }
        // Replace any attempt still running for this host so a double tap cannot leave an
        // orphaned session behind.
        connectJobs.remove(host.id)?.cancel()
        // A user asking to connect now outranks a backoff waiting to do it later, and leaving the
        // waiter alive would let it fire a second connect on top of this one.
        reconnectJobs.remove(host.id)?.cancel()
        val job = viewModelScope.launch {
            // Anything the caller supplied wins; the profile's saved credentials only fill the gaps.
            // That order matters: a password typed into the auth prompt has to beat the one saved on
            // the host, or a rotated server password could not be used at all without editing the
            // profile first.
            val resolved = resolveCredentials(host, password, keyPair, keyBytes, keyPassphrase)
            var lastError: Throwable? = null
            for (attempt in 0 until MAX_CONNECT_ATTEMPTS) {
                try {
                    val session = sshConnectionManager.connect(host, resolved.password, resolved.keyPair)
                    // The tab may have been closed (or the host deleted) while the handshake
                    // was in flight. Honour that instead of resurrecting the tab — and tear
                    // the new session down so it does not leak. Dereferencing the missing tab
                    // here used to throw an NPE inside the coroutine and crash the app.
                    if (tabs.value.none { it.hostId == host.id }) {
                        runCatching { session.close(false) }
                        return@launch
                    }
                    val terminal = sshConnectionManager.openTerminal(session)
                    // The outgoing collector goes first, and it is *waited for*, before anything it
                    // is watching is closed or handed to its replacement. Three reasons, and only the
                    // first was ever obvious:
                    //
                    //  - reconnecting to the same host otherwise stacked a new collector per attempt,
                    //    each pinning its buffer for the lifetime of the ViewModel;
                    //  - a collector reports the close of its channel as the end of the session, which
                    //    for the channel being replaced below is not what it means. Cancelled first, it
                    //    never sees that close at all;
                    //  - a reconnect deliberately keeps the host's buffer so its scrollback survives,
                    //    and AnsiTerminalBuffer is a plain list model with no locking. A cancel only
                    //    *asks* a coroutine to stop, so the outgoing collector could still be feeding
                    //    or reading that buffer while its replacement fed it from another thread. That
                    //    is a data race on an ArrayList: torn frames at best, an index out of bounds in
                    //    the middle of a reconnect at worst. Joining makes the handover exclusive, and
                    //    it is quick - every suspension point in the collector is cancellable and its
                    //    teardown does no I/O.
                    terminalJobs.remove(host.id)?.cancelAndJoin()
                    sessions.put(host.id, session)?.let { previous -> runCatching { previous.close(false) } }
                    channels.put(host.id, terminal)?.let { previous -> runCatching { previous.close() } }
                    val buffer = terminalBuffers.getOrPut(host.id) { AnsiTerminalBuffer() }
                    terminalJobs[host.id] = launchTerminalCollector(host.id, terminal, buffer)
                    updateTab(host.id) { it?.copy(state = SessionConnectionState.CONNECTED, lastError = null) }
                    // Credentials are no longer needed once the session is up.
                    pendingConnection = null
                    // The ladder is per outage, not per session: a host that reconnects gets its full
                    // allowance back for the next one.
                    reconnectAttempts.remove(host.id)
                    runCatching { hostRepository.save(host.copy(lastConnectedAt = System.currentTimeMillis())) }
                    runCatching { sessionRegistry.register(host.id, resolved.password, resolved.keyBytes, resolved.keyPassphrase) }
                    // Auto Login SFTP. Off means SSH only — nothing opens a second channel on this
                    // host until the user asks for a listing — which is the point of the switch: an
                    // account with a shell and no sftp-server subsystem otherwise greets every
                    // successful login with a failure about a feature the user never asked for.
                    if (host.autoLoginSftp) {
                        loginSftp(host)
                    } else {
                        updateTab(host.id) { it?.copy(sftpState = SftpSessionState.DISABLED, sftpError = null) }
                    }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastError = error
                    // A rejection the server has already made up its mind about is not retried: see
                    // `connectFailureIsFinal`. Among other things it stops a mistyped password being
                    // offered three times, which is how a typo gets an account locked.
                    if (connectFailureIsFinal(error)) break
                    if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                        updateTab(host.id) { it?.copy(state = SessionConnectionState.RECONNECTING, lastError = "Retrying connection…") }
                        delay(RECONNECT_DELAY_MS * (attempt + 1))
                    }
                }
            }
            updateTab(host.id) { it?.copy(state = SessionConnectionState.DISCONNECTED, lastError = describeConnectFailure(lastError)) }
        }
        connectJobs[host.id] = job
        job.invokeOnCompletion { connectJobs.remove(host.id, job) }
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
    ): ResolvedCredentials {
        // Skipped entirely when the caller already supplied both halves, so an ordinary
        // password-prompt connection does not touch the credential file at all.
        val stored = if (password == null || (keyPair == null && keyBytes == null)) {
            runCatching { credentialStore.stored(host.id) }.getOrDefault(StoredCredentials())
        } else {
            StoredCredentials()
        }

        val resolvedPassword = password
            ?: if (stored.hasPassword) runCatching { credentialStore.password(host.id) }.getOrNull() else null

        val material = keyBytes?.takeIf { it.isNotEmpty() }
            ?: if (stored.hasKey) runCatching { credentialStore.keyBytes(host.id) }.getOrNull() else null
        val label = if (keyBytes != null) PICKED_KEY_NAME else stored.keyLabel ?: PICKED_KEY_NAME
        val passphrase = keyPassphrase?.takeIf { it.isNotBlank() }
            ?: if (stored.hasPassphrase) runCatching { credentialStore.passphrase(host.id) }.getOrNull() else null

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
        val pump = launch { terminal.output.collect(incoming::send) }
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
            val status = terminal.awaitClosed()
            incoming.close()
            // Only if this is still the session on that host. A reconnect cancels this coroutine
            // before it closes the channel it is replacing, so ordinarily it never sees that close -
            // but a cancel is a request, not a suspension of physics: this can already have been
            // resumed on another core, and reporting a replaced channel as a disconnection would
            // overwrite the CONNECTED state the new session had just been given.
            if (channels.remove(hostId, terminal)) {
                updateTab(hostId) { it?.copy(state = SessionConnectionState.DISCONNECTED, lastError = describeSessionEnd(status)) }
                if (shouldAutoReconnect(status, tabIsOpen = tabs.value.any { it.hostId == hostId })) {
                    scheduleAutoReconnect(hostId)
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
                    report("Some terminal output was dropped - the display may be out of step with the shell")
                }
                delay(TERMINAL_FRAME_MS)
            }
        } finally {
            pump.cancel()
            closer.cancel()
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
     * What the tab says when a connected session ended by itself.
     *
     * A clean `exit` and a dropped connection are the same event to the channel, and the exit status is
     * the only thing that tells them apart: a shell that was asked to leave reports one, a socket that
     * died reports nothing. Both are worth distinguishing from the connect failures in
     * [describeConnectFailure], which are about a session that never started.
     */
    private fun describeSessionEnd(exitStatus: Int?): String = when {
        exitStatus == null -> "Disconnected from the remote host"
        exitStatus == 0 -> "Session ended"
        else -> "Session ended (exit $exitStatus)"
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
     *  - **Bounded.** [MAX_AUTO_RECONNECT_ATTEMPTS] consecutive attempts, counted per host in
     *    [reconnectAttempts] and reset by the first success. Past that the tab says so and stops; the
     *    Reconnect action in the UI is still there, and pressing it starts a fresh ladder.
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
    private fun scheduleAutoReconnect(hostId: String) {
        val attempt = (reconnectAttempts[hostId] ?: 0) + 1
        if (attempt > MAX_AUTO_RECONNECT_ATTEMPTS) {
            updateTab(hostId) {
                it?.copy(
                    state = SessionConnectionState.DISCONNECTED,
                    lastError = "Disconnected · gave up after $MAX_AUTO_RECONNECT_ATTEMPTS reconnect attempts",
                )
            }
            return
        }
        reconnectAttempts[hostId] = attempt
        val job = viewModelScope.launch {
            val baseSeconds = runCatching { settingsRepository.settings.first().reconnectBaseSeconds }
                .getOrDefault(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
            val window = backoffWindowMs(baseSeconds, attempt)
            // Jitter on top of the deterministic window, matching the service's ladder.
            val waitMs = window + Random.nextLong(0, window / 2 + 1)
            updateTab(hostId) {
                it?.copy(
                    state = SessionConnectionState.RECONNECTING,
                    lastError = "Reconnecting in ${waitMs / 1_000}s · attempt $attempt of $MAX_AUTO_RECONNECT_ATTEMPTS",
                )
            }
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
            if (sessionStore.isLive(hostId)) return@launch
            val host = runCatching { hostRepository.hosts.first() }.getOrNull()?.firstOrNull { it.id == hostId }
            if (host == null) {
                // The profile was deleted while the ladder was waiting: nothing left to reconnect to.
                reconnectAttempts.remove(hostId)
                return@launch
            }
            connect(host)
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
        terminalFrames.update { it + (hostId to buffer.frame(scrollOffsets[hostId] ?: 0)) }
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
                hostId to buffer.frame(scrollOffsets[hostId] ?: 0)
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
        terminalOutput.update { it + (hostId to buffer.plainText().takeLast(MAX_TERMINAL_CHARS)) }
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
     */
    private fun isDisplaying(hostId: String, buffer: AnsiTerminalBuffer): Boolean =
        terminalBuffers[hostId] === buffer

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
        pendingConnection?.let { connect(it.host, it.password, it.keyPair, it.keyBytes, it.keyPassphrase) }
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
        pendingConnection?.host?.id?.let { id -> updateTab(id) { it?.copy(state = SessionConnectionState.DISCONNECTED, lastError = "Host key was rejected") } }
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
        // sendWindowChange writes an SSH packet, which blocks when the transport is
        // congested; keep it off the main thread so a stalled link cannot cause an ANR.
        viewModelScope.launch(Dispatchers.IO) { runCatching { channels[hostId]?.resize(columns, rows) } }
    }

    /**
     * Lists [path] on [host]. When [path] is null the server's own home directory is used,
     * resolved once per host by canonicalising "." over SFTP and cached in [homePaths].
     * The old "/home/$username" guess pointed at a non-existent directory for root and for
     * every server that does not lay out home directories under /home.
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
        viewModelScope.launch {
            if (sessions[host.id] == null) {
                if (announce) report("${host.name} is not connected")
                return@launch
            }
            try {
                listRemote(host, path)
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
                remoteFiles.value = emptyList()
                remotePath.value = path ?: homePaths[host.id] ?: fallbackHome(host.username)
                report("Could not list directory", error)
            }
        }
    }

    /**
     * Opens an SFTP channel on [host]'s existing session and lists the remote home directory.
     *
     * The one place both the auto-login and pull-to-refresh go through, so they cannot disagree about
     * what "SFTP works" means or about which directory is showing. Throws rather than reporting: the
     * two callers want opposite things from a failure — one records it on the tab, the other leaves
     * the browser's own error on screen.
     */
    private suspend fun listRemote(host: HostProfile, path: String?) {
        val session = sessions[host.id] ?: throw IllegalStateException("${host.name} is not connected")
        sshConnectionManager.openSftp(session).use { sftp ->
            val target = path ?: homePaths[host.id] ?: sftpDirectoryService.homeDirectory(sftp, host.username)
                .also { homePaths[host.id] = it }
            remoteFiles.value = sftpDirectoryService.list(sftp, target)
            remotePath.value = target
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
        val job = viewModelScope.launch {
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

    /** Directory the file browser is currently pointing at for [host]. */
    private fun currentRemoteDir(host: HostProfile): String =
        remotePath.value ?: homePaths[host.id] ?: fallbackHome(host.username)

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
        viewModelScope.launch {
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
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@launch report("The local folder is no longer accessible")
            try {
                val localTree = LocalSyncIndex.walk(rootDoc)
                sshConnectionManager.openSftp(session).use { sftp ->
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
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@launch report("The local folder is no longer accessible")
            try {
                sshConnectionManager.openSftp(session).use { sftp ->
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
        viewModelScope.launch(Dispatchers.IO) {
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
                sshConnectionManager.openSftp(source).use { from ->
                    sshConnectionManager.openSftp(dest).use { to ->
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
        viewModelScope.launch {
            transferRepository.save(item)
            startDownloadJob(host, item, localUri, remote.path)
        }
    }

    fun downloadToLocal(host: HostProfile, remote: RemoteFile) {
        val localDir = localDirUri.value?.let(Uri::parse) ?: run {
            report("Pick a local folder first")
            return
        }
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val session = sessions[host.id]
            if (session == null) {
                // Reachable: the sheet and its dialogs stay up across a disconnect, so the button is
                // still there to press after the session is gone.
                report("$what failed", IllegalStateException("not connected to ${host.name}"))
                return@launch
            }
            runCatching { sshConnectionManager.openSftp(session).use { operation(it) } }
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
        viewModelScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            runCatching { portForwardingManager.startLocal(session, "127.0.0.1", localPort, remoteHost, remotePort) }
                .onSuccess { forwardHandles[entry.id] = it; forwardings.value = forwardings.value + entry }
                .onFailure { reportForwardFailure("Local forward on port $localPort failed", it) }
        }
    }

    fun startRemoteForward(host: HostProfile, remotePort: Int, localPort: Int) {
        val entry = ForwardEntry(type = ForwardType.REMOTE, localPort = localPort, remoteHost = null, remotePort = remotePort, hostId = host.id)
        viewModelScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            // Bound on the server's loopback, not 0.0.0.0. The dialog only asks for two port numbers,
            // so nobody using it has chosen to publish anything; requesting all interfaces meant that
            // on any server configured `GatewayPorts yes` (or `clientspecified`) the phone's local
            // port became reachable from the server's entire network. Most servers default to
            // `GatewayPorts no` and force loopback regardless of what the client asks, which is why
            // this went unnoticed — it only opened up on the servers where it mattered. Loopback is
            // also what `ssh -R` gives you unless you spell out a bind address.
            runCatching { portForwardingManager.startRemote(session, "127.0.0.1", remotePort, "127.0.0.1", localPort) }
                .onSuccess { forwardHandles[entry.id] = it; forwardings.value = forwardings.value + entry }
                .onFailure { reportForwardFailure("Remote forward of port $remotePort failed", it) }
        }
    }

    fun startDynamicForward(host: HostProfile, localPort: Int) {
        val entry = ForwardEntry(type = ForwardType.DYNAMIC, localPort = localPort, hostId = host.id)
        viewModelScope.launch {
            val session = sessions[host.id] ?: return@launch report("${host.name} is not connected")
            runCatching { portForwardingManager.startDynamic(session, "127.0.0.1", localPort) }
                .onSuccess { forwardHandles[entry.id] = it; forwardings.value = forwardings.value + entry }
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
        // Guarded because closing a tracker talks to the server to cancel the forward, so it throws
        // IOException once the session is gone — and the session dying is exactly when the user
        // reaches for this button. Unwrapped, that propagated out of a Compose click handler. The
        // entry is dropped either way: the forward is certainly not running if this failed.
        forwardHandles.remove(id)?.let { handle -> runCatching { handle.close() } }
        forwardings.value = forwardings.value.filterNot { it.id == id }
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
        viewModelScope.launch {
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
            serverStats.value = serverStats.value + (host.id to stats)
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
        viewModelScope.launch {
            val item = transferRepository.transfers.first().firstOrNull { it.id == id } ?: return@launch
            val host = hostRepository.hosts.first().firstOrNull { it.id == item.hostId }
                ?: return@launch report("The host for ${item.name} no longer exists")
            val uri = item.localUri?.let(Uri::parse) ?: return@launch report("${item.name} has no local file")
            val session = sessions[host.id] ?: run {
                val password = sessionRegistry.credential(host.id)
                val keyPair = sessionRegistry.keyBytes(host.id)?.let { bytes -> runCatching { SshKeyLoader.load(bytes, "${host.username}-key", sessionRegistry.keyPassphrase(host.id)) }.getOrNull() }
                val reconnected = try {
                    sshConnectionManager.connect(host, password, keyPair)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@launch report("Could not reconnect to ${host.name}", error)
                }
                sessions.put(host.id, reconnected)?.let { previous -> runCatching { previous.close(false) } }
                reconnected
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
        // Closing a tab is the clearest possible statement that this session is not wanted, so it also
        // ends any reconnect waiting to bring it back.
        reconnectJobs.remove(tab.hostId)?.cancel()
        reconnectAttempts.remove(tab.hostId)
        sessionStore.forget(tab.hostId)
        tabs.value = tabs.value.filterNot { it.hostId == tab.hostId }
        terminalOutput.update { it - tab.hostId }
        terminalFrames.update { it - tab.hostId }
        scrollOffsets.remove(tab.hostId)
        textPublishedAt.remove(tab.hostId)
        typedLines.remove(tab.hostId)
        commandHistory.value = commandHistory.value - tab.hostId
        serverStats.value = serverStats.value - tab.hostId
        homePaths.remove(tab.hostId)
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
            channels.remove(host.id)?.close()
            sessions.remove(host.id)?.close(false)
            tabs.value = tabs.value.filterNot { it.hostId == host.id }
            terminalOutput.update { it - host.id }
            terminalFrames.update { it - host.id }
            scrollOffsets.remove(host.id)
            textPublishedAt.remove(host.id)
            typedLines.remove(host.id)
            commandHistory.value = commandHistory.value - host.id
            serverStats.value = serverStats.value - host.id
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
        forwardHandles.values.forEach { runCatching { it.close() } }
        forwardHandles.clear()
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
        forwardings.value.filter { it.hostId == hostId }.forEach { entry ->
            // Same reason as stopForwarding: this runs *because* the host is going away, so the
            // cancel round-trip to the server is the most likely thing in the app to throw.
            forwardHandles.remove(entry.id)?.let { handle -> runCatching { handle.close() } }
        }
        forwardings.value = forwardings.value.filterNot { it.hostId == hostId }
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
        val existing = tabs.value.indexOfFirst { it.hostId == hostId }
        val updated = transform(tabs.value.getOrNull(existing)) ?: return
        tabs.value = if (existing < 0) tabs.value + updated else {
            tabs.value.toMutableList().also { it[existing] = updated }
        }
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
    )

    private data class SecurityState(
        val knownHosts: Map<String, String>,
        val credentials: Map<String, StoredCredentials>,
    )

    private data class RemoteState(
        val files: List<RemoteFile>,
        val path: String?,
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

    private companion object {
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
)

/**
 * Consecutive automatic reconnects allowed per host before the app stops and says so.
 *
 * Five, with the backoff in [dev.eclipse.ssh.background.backoffWindowMs], spans roughly ten minutes
 * of outage — long enough to ride out
 * a train tunnel, a Wi-Fi handover or a server reboot, short enough that a host which is
 * genuinely gone stops being dialled while the phone is in a pocket.
 */
internal const val MAX_AUTO_RECONNECT_ATTEMPTS = 5

/**
 * Whether a session that just ended should be brought back automatically.
 *
 * `null` is the whole distinction. Apache MINA reports the shell's exit status when the remote
 * side sent one, and reports nothing when the channel died without one — a dropped transport,
 * a killed `sshd`, a NAT that stopped forwarding, a heartbeat that ran out of replies. The
 * first is a session that finished, and reconnecting it would resurrect a shell the user
 * closed on purpose; `exit`, `logout` and `Ctrl-D` all land there, and so does a remote command
 * that failed with a non-zero status. The second is a session that was taken away, which is
 * the only case worth reconnecting.
 *
 * A closed tab is not reconnected either, whatever the status: the user is not looking at that
 * session any more, and [MainViewModel.closeTab] has already released it.
 *
 * Pure so the rule can be tested without a network, a server or a view model — the states this
 * has to get right are exactly the ones that are awkward to reproduce on demand.
 */
internal fun shouldAutoReconnect(exitStatus: Int?, tabIsOpen: Boolean): Boolean =
    tabIsOpen && exitStatus == null
