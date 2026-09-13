package dev.eclipse.ssh.background

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ssh.DialAttempt
import dev.eclipse.ssh.ssh.SessionDiagnostics
import dev.eclipse.ssh.ssh.SessionEvent
import dev.eclipse.ssh.ssh.SessionLivenessProbe
import dev.eclipse.ssh.ssh.SshConnectionManager
import dev.eclipse.ssh.ssh.SshKeyLoader
import dev.eclipse.ssh.ssh.SshSessionStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class EclipseSessionService : LifecycleService() {
    @Inject lateinit var sessionRegistry: SessionRegistry
    @Inject lateinit var hostRepository: HostRepository
    @Inject lateinit var sshConnectionManager: SshConnectionManager
    @Inject lateinit var sessionStore: SshSessionStore
    @Inject lateinit var transferRestorer: TransferRestorer
    @Inject lateinit var transferRepository: TransferRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var livenessProbe: SessionLivenessProbe
    @Inject lateinit var diagnostics: SessionDiagnostics

    /**
     * Where an exception escaping any [serviceScope] coroutine lands, instead of the process.
     *
     * A `SupervisorJob` stops one child's failure cancelling its siblings — it does not stop the
     * exception reaching the thread's default handler, which is process death. Everything this
     * service launches is recoverable-by-someone-else's-standards: a restore pass, a transfer
     * collector, the registry clear on Stop. None of them is worth the live terminal sessions the
     * same process is holding. The handler logs the class name rather than crashing; the affected
     * coroutine is dead either way, which is the honest scope of the damage.
     *
     * The message carries only the throwable, never a host or credential: the realistic throwers
     * here are DataStore and Room IO, whose messages name files and SQL, and both are safe to log.
     */
    private val uncaughtInService = CoroutineExceptionHandler { _, error ->
        // Class name and message only, no stack trace: the realistic throwers are DataStore and Room
        // IO, whose messages name files and SQL, but a stack trace would drag in whatever frame
        // values the failure crossed on its way out.
        Log.e(TAG, "Uncaught error in a session-service coroutine: ${error::class.java.simpleName}: ${error.message}")
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + uncaughtInService)
    private val restoreMutex = Mutex()

    /**
     * The stop-path state machine, so "stopping" stops being indistinguishable from "stopped".
     *
     * The defect this replaces: ACTION_STOP ran its cleanup outside [restoreMutex], so a restore
     * pass could still be mid-dial when the user disconnected - the dial finished its
     * `install` *after* the stop's `closeAll`, leaving an authenticated SSH session alive with no
     * registry entry, no tab and no notification until process death. Every stop consumer (the
     * restore loop, [dial], [onTimeout], [onDestroy]) now asks this object first; see the class
     * KDoc below for the transition rules.
     */
    private val stopState = SessionServiceStopState()

    /**
     * True while one restore pass is queued or running, whatever triggered it.
     *
     * The entry points (onCreate, every network callback, every manual refresh, the sticky
     * restart) each used to launch their own coroutine and queue on [restoreMutex]; a flapping
     * network stacked one full dial sweep per `onAvailable`, each queued pass draining the
     * conflated wake signal the running pass needed, then waiting out the whole backoff anyway.
     * One pass at a time is the whole fix: the running pass already responds to new information
     * through [reconnectWake], so a second launch had nothing to add but queue pressure.
     */
    private val restorePassActive = AtomicBoolean(false)

    /**
     * Schedules one restore pass, unless one is already queued or running (see
     * [restorePassActive]) or the service is stopping (see [stopState]) - a pass launched during
     * a stop would exit at its first state check anyway, but not launching it at all keeps the
     * stop from waiting behind a coroutine that has nothing to do.
     */
    private fun requestRestore(reason: String) {
        if (stopState.isStopping) return
        if (!restorePassActive.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                restoreSessions(reason)
            } finally {
                restorePassActive.set(false)
            }
        }
    }

    private var sessionText = ""
    private var transferCount = 0
    private var transferPercent = 0
    private var reconnectAttempts = 0
    private lateinit var connectivityManager: ConnectivityManager

    /**
     * Whether this instance ever completed a `startForeground` call. Once it has not, the
     * service exists only to drain its pending start command and leave: it holds no foreground
     * notification, and a sticky restart of it would attempt the promotion again, fail against
     * the same exhausted `dataSync` budget, and ping-pong for as long as something kept starting
     * it. [onStartCommand] reads this to answer `START_NOT_STICKY` on exactly that path.
     */
    private var promotedToForeground = false

    /**
     * Cuts a reconnect backoff short when the platform reports a usable network.
     *
     * Conflated on purpose: the signal has to survive being raised while [restoreSessions] is
     * mid-attempt (nobody is receiving then), and several interfaces coming up at once mean the same
     * one thing. See [awaitReconnectWindow].
     */
    private val reconnectWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * True until the first [ConnectivityManager.NetworkCallback.onAvailable] arrives.
     *
     * Atomic because it is read on a ConnectivityManager thread and written once, and `getAndSet`
     * makes "was this the first" a single decision even if two callbacks arrive together.
     */
    private val startupNetworkCallbackPending = AtomicBoolean(true)

    /**
     * The default network at the moment the callback was registered, if there was one.
     *
     * `registerDefaultNetworkCallback` reports the current default network straight away, so the first
     * callback usually describes the link the service was started on rather than a change to react to.
     * Compared by identity rather than trusted by position, because when the phone starts with no
     * network at all there is no replay, and then the first callback *is* news - a link arriving is
     * exactly when a dropped session should be restored.
     */
    @Volatile
    private var initialDefaultNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Both, and in this order. The signal releases a backoff that is already sleeping —
            // which is the case that used to cost minutes — while the launch covers the service
            // sitting idle with no loop running to wake.
            reconnectWake.trySend(Unit)
            // The registration replay is not a network change: it reports the link the service was
            // just started on, and [onCreate] already runs one pass for that. Acting on it made every
            // service creation run two identical restore passes over the whole registry, one of them
            // for news that had not happened. Only the network that was already default is skipped,
            // and only once - see [initialDefaultNetwork] for the case where there was none.
            if (startupNetworkCallbackPending.getAndSet(false) && network == initialDefaultNetwork) return
            requestRestore("Network available")
        }

        override fun onLost(network: Network) {
            updateNotification("Network lost · waiting to reconnect")
            // Recorded per live host rather than once, because the diagnostics stream is read
            // per session: the question being answered later is "was this host's link up at the
            // time it died", and a global entry cannot answer it.
            val describedNetwork = networkMonitor.describe()
            sessionStore.liveHostIds().forEach { hostId ->
                diagnostics.record(
                    hostId,
                    SessionEvent.NETWORK_CHANGED,
                    detail = "default network lost",
                    network = describedNetwork,
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // API 31+ rejects background foreground-service starts and API 34+ requires the
        // declared type; either failure must not take the whole process down.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Restoring active sessions…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.isSuccess
        if (!promoted) {
            // Said out loud rather than swallowed: the usual cause is the six-hour `dataSync`
            // budget being exhausted, and the silence used to cost the user every session they
            // minimised the app to keep. Same id and copy as the restore alert, so repeated
            // start attempts replace the notification instead of stacking.
            postAlert(
                context = this,
                id = NotificationChannels.ID_RESTORE,
                title = getString(R.string.notif_restore_title),
                body = getString(R.string.notif_restore_body),
                contentIntent = restoreActivityIntent(this),
            )
            stopSelf()
            return
        }
        promotedToForeground = true
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Read before registering, so the replay that arrives during registration can be recognised.
        initialDefaultNetwork = runCatching { connectivityManager.activeNetwork }.getOrNull()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        // A network that was *replaced* has already broken every live socket, and nothing on the
        // connection itself says so. Started here as well as from the view model because the service is
        // what is alive while the app is in the background, which is when a phone changes networks.
        livenessProbe.start()
        // Kept, and kept here: this is the pass that restores the hosts the registry still calls active
        // after the process was killed, and the first Connect tap is the only thing that creates the
        // service. It is no longer the second dialler it used to be - the dial gate serialises it with
        // the UI's own attempt, and [MainViewModel.adoptStoredSession] can now use whichever session it
        // finds, with or without a shell on it.
        requestRestore("Service active")
        serviceScope.launch {
            transferRepository.transfers.collect { transfers ->
                val running = transfers.filter { it.status == TransferStatus.RUNNING }
                transferCount = running.size
                transferPercent = if (running.isEmpty()) 0 else (running.map { it.progress }.average() * 100).toInt().coerceIn(0, 100)
                refreshNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // The sticky answer the platform acts on. While promoted, the sticky restart after a
        // process death is how sessions come back. Once promotion failed (the six-hour `dataSync`
        // budget is exhausted, or a background start was refused), sticky is a ping-pong: every
        // restart would attempt `startForeground` again, fail against the same wall, and be
        // re-created for nothing. An unpromoted instance drains this one start command and is not
        // restarted.
        if (!promotedToForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> if (stopState.requestUserStop()) {
                // The state flips *here*, before the coroutine, so a restore pass mid-dial at
                // this instant already sees a stop in progress - the pass exits at its next
                // check, and [dial] discards rather than installs a session that finishes
                // connecting after this point.
                serviceScope.launch {
                    try {
                        // Bounded on purpose. The clear is a NonCancellable DataStore
                        // read-modify-write serialised through the store's single actor: queued
                        // behind any other write (a transfer's progress, a settings edit), there
                        // is no inherent bound on how long it can take. The timeout abandons the
                        // *wait*, not the write - the edit itself is NonCancellable and finishes
                        // on its own - because the service stopping must never be hostage to
                        // storage being slow. This is the stop path that
                        // `ForegroundServiceDidNotStopInTimeException` is made of.
                        runCatching { withTimeoutOrNull(STOP_CLEAR_TIMEOUT_MS) { sessionRegistry.clear() } }
                        closeSessions()
                    } finally {
                        // Last, but guaranteed: cleanup may be slow or broken, and neither may
                        // delay or skip the service's destruction.
                        stopSelf()
                    }
                }
            }
            // A wake before the launch, because the manual gesture means "try now": a pass
            // already running and sleeping out a backoff picks the signal up and retries at
            // once, which is what the notification's Reconnect button promises.
            ACTION_REFRESH, ACTION_RESTORE -> {
                stopState.revive()
                reconnectWake.trySend(Unit)
                requestRestore("Manual reconnect")
            }
            // "Keep running, the UI has a session now." Deliberately does nothing else: the UI dials
            // its own session and puts it in the shared store, so a restore pass here would only be a
            // second dialler racing it. That is what tapping Connect used to launch — the intent
            // carried no action, and a null action means the process was killed and restarted (see
            // below), so every single Connect tap ran a full restore pass alongside the UI's own
            // handshake. The dial gate makes that harmless now, and this makes it not happen.
            // The revive matters when the platform had already begun stopping this service (the
            // dataSync timeout): the UI connecting a session is the one event that says the
            // service still has work.
            ACTION_TRACK -> stopState.revive()
            // A null intent means START_STICKY re-created the service after the process was
            // killed, so the sessions genuinely need restoring rather than a text refresh
            // that would overwrite the live reconnect status.
            null -> requestRestore("Service restarted")
            else -> Unit
        }
        return START_STICKY
    }

    private suspend fun restoreSessions(reason: String) = restoreMutex.withLock {
        // Serialised: onCreate, every ConnectivityManager.onAvailable callback and every
        // ACTION_REFRESH used to launch their own loop, racing to connect() the same host
        // (the ConcurrentHashMap.put loser's session leaked) and sharing one non-atomic
        // attempt counter.
        //
        // Whatever a queued wake signal was asking for, the pass starting now delivers it. Leaving
        // it in the channel would make the first backoff below return immediately instead.
        reconnectWake.tryReceive()
        var attemptReason = reason
        // Loop instead of recursing so endless reconnect attempts (e.g. a host that
        // stays unreachable for days) can never grow the coroutine call stack. The stop
        // check rides the same condition: a pass that wakes from its backoff into a stopping
        // service ends here rather than dialling anything.
        while (coroutineContext.isActive && !stopState.isStopping) {
            val activeIds = sessionRegistry.activeHostIds.first()
            val hosts = hostRepository.hosts.first()
            // [SshSessionStore.isLive] answers for the whole app, not just for this service, and that
            // is the point: the same registry entry is written the moment the *UI* authenticates a
            // host, so a service that could only see its own sessions dialled a second one to the
            // account the user had just connected — every single time Connect was tapped, because
            // tapping Connect is also what starts this service. It prunes dead entries as it goes, so
            // a session that has since dropped is still restored here.
            //
            // Ask-first mode applies here too, or it would not apply at all: the UI's ladder parks and
            // prompts, and a pass that redialled anyway would install a live session no tab is
            // attached to behind a prompt asking whether to reconnect. The one exception is a host
            // with a transfer in flight - the user scheduled that work, and it is the standing answer
            // this pass needs.
            val askFirst = runCatching { settingsRepository.settings.first().reconnectAskFirst }.getOrDefault(false)
            val transferHostIds = if (askFirst) {
                transferRepository.transfers.first()
                    .filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
                    .mapNotNull { it.hostId }
                    .toSet()
            } else emptySet()
            // Host-wide liveness, because a host's session may be filed under a terminal tab's key
            // rather than the host's own: with more than one terminal per host the host-id slot is
            // only one of several, and a pass that read it alone would redial a host whose shell is
            // alive on screen.
            val pending = hostsNeedingRestore(hosts, activeIds, { sessionStore.liveForHost(it).isNotEmpty() }, askFirst) {
                it.id in transferHostIds
            }
            var connected = 0
            // Hosts skipped because another dialler already had them. Counted rather than ignored: they
            // are neither successes (nothing was restored) nor failures (nothing went wrong), and the
            // backoff below needs to tell those apart.
            var busy = 0
            pending.forEach { host ->
                // Re-checked per host, not just per sweep: a stop can arrive while an earlier
                // host's dial ladder is still running, and the whole point of the state machine
                // is that nothing past this line installs a session the stop then misses.
                if (stopState.isStopping) return@withLock
                // Under the host's dial gate, which is the other half of the fix for a session that
                // said *Reconnecting…* seconds after login. `pending` was computed from an `isLive`
                // check that cannot see a handshake the UI has started and not yet finished, and
                // tapping Connect is what starts this service — so this pass would dial a second
                // session to the account the user had just logged into, and collapsing the pair
                // closed the live one. Holding the gate means the UI's attempt is either finished
                // (and found by the re-check below) or has not started, never half-done.
                //
                // `tryDialing` rather than `dialing`, because this pass walks hosts in order and the
                // gate is held for a whole connect ladder. Waiting on a host the UI is already dialling
                // would block every host behind it for up to three connect timeouts - a quarter of an
                // hour at the maximum the user is allowed to set - to learn something the next pass
                // finds for free.
                val outcome = sessionStore.tryDialing(host.id) {
                    // The re-check is the point of the gate: whatever appeared while this host waited
                    // its turn is a session to adopt, not one to duplicate. Asked host-wide, because
                    // what appeared may be the UI's session filed under a terminal tab's key.
                    sessionStore.primarySession(host.id) ?: dial(host)
                }
                val session = when (outcome) {
                    DialAttempt.Busy -> {
                        busy++
                        null
                    }
                    is DialAttempt.Ran -> outcome.value
                }
                if (session != null) {
                    connected++
                    val resumed = runCatching { transferRestorer.resumeForHost(host.id, session) }.getOrDefault(0)
                    if (resumed > 0) updateNotification("Resuming $resumed transfer(s) for ${host.name}")
                }
            }
            // Retry only while something is still missing. Comparing `connected == 0` alone
            // spun forever once every host was already restored, waking the CPU every
            // backoff interval for the lifetime of the service.
            val failed = pending.size - connected - busy
            if (connected == 0 && (failed > 0 || busy > 0)) {
                // Only a real failure escalates the ladder. A host another dialler is working on has not
                // failed at anything, and letting it double the interval would push a perfectly
                // reachable host's next attempt into the minutes because the UI happened to be
                // mid-handshake. The pass still has to come back for it - the other dialler gives up
                // after its own five attempts, where this one never does - so a busy-only pass waits
                // one step's worth and re-checks without spending a step.
                if (failed > 0) reconnectAttempts++ else reconnectAttempts = reconnectAttempts.coerceAtLeast(1)
                val waitMs = backoffDelay(reconnectAttempts, pending)
                updateNotification(
                    if (failed > 0) {
                        "$attemptReason · retry $reconnectAttempts in ${waitMs / 1_000}s"
                    } else {
                        // Nothing failed, so nothing is being retried: the app is simply already
                        // connecting these hosts somewhere else, and saying "retry 4" about that would
                        // be a notification describing a problem that does not exist.
                        "Connecting $busy host(s)…"
                    },
                )
                val describedNetwork = networkMonitor.describe()
                pending.forEach { host ->
                    diagnostics.record(
                        host.id,
                        SessionEvent.RECONNECT_SCHEDULED,
                        detail = "background restore: waiting ${waitMs}ms",
                        network = describedNetwork,
                        attempt = reconnectAttempts,
                    )
                }
                // The counter is deliberately not reset when the network comes back: a link that
                // flaps would otherwise retry at the base interval forever. Waking early is about
                // when the next attempt happens, not about forgiving the ones that already failed.
                attemptReason = if (awaitReconnectWindow(waitMs, reconnectWake)) {
                    "Network returned"
                } else {
                    "Retry $reconnectAttempts"
                }
            } else {
                reconnectAttempts = 0
                val live = sessionStore.liveHostIds().size
                updateNotification(if (live == 0) "$attemptReason · no sessions restored" else "$live SSH session(s) active")
                return@withLock
            }
        }
    }

    /**
     * One dial to [host] with the credentials the registry kept for it, or null if it failed.
     *
     * The session is [installed][SshSessionStore.install] rather than `put`, so a live session this
     * pass did not know about is kept and *this* one is closed — the opposite of `put`, which closed
     * whatever it replaced and so could close the shell the user was typing into. Into the shared
     * store either way, so the UI adopts the session instead of dialling its own when it comes back,
     * which is what makes a session survive process death.
     */
    private suspend fun dial(host: HostProfile) = try {
        diagnostics.record(
            host.id,
            SessionEvent.CONNECT_REQUESTED,
            detail = "background restore",
            network = networkMonitor.describe(),
        )
        val session = sshConnectionManager.connect(host, sessionRegistry.credential(host.id), keyPairFor(host))
        if (stopState.isStopping) {
            // The dial raced a stop: the handshake finished after the user disconnected (or the
            // dataSync budget expired), and the stop's closeAll has already run or is about to.
            // Installing this session is the exact orphan the state machine exists to prevent -
            // live, authenticated, no registry entry, no tab, no notification until process
            // death - so it is closed here instead, the same way [SshSessionStore.install]
            // rejects a session it cannot use. A stop reported by the diagnostics stream is the
            // honest record of why.
            diagnostics.record(
                host.id,
                SessionEvent.CONNECT_FAILED,
                detail = "background restore: stopped mid-dial",
                network = networkMonitor.describe(),
            )
            runCatching { session.close(false) }
            null
        } else {
            sessionStore.install(host.id, session, host.id)
        }
    } catch (cancelled: CancellationException) {
        // The service is going away. Without this the cancellation was swallowed into a null session
        // and the loop went on to dial every remaining host on an already dead context — pointless
        // work during shutdown, and it hid the one condition that should stop the pass immediately.
        throw cancelled
    } catch (error: Throwable) {
        // Any other failure is this host's problem alone: unreachable, refused, wrong credentials. The
        // rest of the pass still gets its turn, and the backoff decides when to come back.
        diagnostics.record(
            host.id,
            SessionEvent.CONNECT_FAILED,
            detail = "background restore: ${error.message ?: error::class.java.simpleName}",
            network = networkMonitor.describe(),
        )
        null
    }

    /** The saved private key for [host] as a usable pair, or null when there is none or it is unreadable. */
    private suspend fun keyPairFor(host: HostProfile) = sessionRegistry.keyBytes(host.id)?.let { bytes ->
        runCatching { SshKeyLoader.load(bytes, "${host.username}-key", sessionRegistry.keyPassphrase(host.id)) }.getOrNull()
    }

    /**
     * Exponential backoff seeded from the shortest delay any pending host asked for, with jitter so
     * several hosts recovering at once do not retry in lockstep.
     */
    private suspend fun backoffDelay(attempt: Int, pending: List<HostProfile>): Long {
        val globalSeconds = runCatching { settingsRepository.settings.first().reconnectBaseSeconds }
            .getOrDefault(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
        val base = backoffWindowMs(restoreBaseSeconds(pending, globalSeconds), attempt)
        return base + Random.nextLong(0, base / 2 + 1)
    }

    /** Only for the notification's Stop action, which is the user saying "close my sessions". */
    private fun closeSessions() = sessionStore.closeAll()

    override fun onDestroy() {
        // Whether this destruction was announced by anyone: a user stop (ACTION_STOP) and a
        // platform stop (onTimeout) both told the user what they were doing; a `RUNNING` phase
        // here means the system recycled the service with live sessions still in the store -
        // the orphan state that used to end in silence.
        val recycledWithSessionsOpen = stopState.destroyed()
        // Cancel the scope first: its cancellation is what ends a sleeping backoff and the
        // transfer collector, so the binder call below unregisters callbacks that have nothing
        // left to notify. The unregister used to come first, delaying that cancellation behind
        // a synchronous trip to system_server for no benefit to anything already cancelled.
        serviceScope.cancel()
        if (::connectivityManager.isInitialized) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        // After the scope, so the cancellation is what ends a sleeping backoff rather than a closed
        // channel; awaitReconnectWindow treats the latter as "wait the whole window".
        reconnectWake.close()
        // Explicit rather than left to destruction: removing the foreground notification here
        // also covers the stop paths that demoted but had not yet been destroyed, and makes the
        // "no notification, no service, sessions still live" state below impossible to reach
        // silently.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Deliberately closes nothing. The SshClient is an application-scoped singleton shared with
        // the UI, so stopping it here tore down every interactive terminal and SFTP session the moment
        // the service was recycled — and now that sessions live in [SshSessionStore] rather than in a
        // map owned by this service, closing "its own" sessions would do exactly the same damage: the
        // session the user is typing into is the same object. A session ends when the user closes its
        // tab or taps Stop, or when the process dies and the kernel closes the socket. The service
        // being recycled is none of those. What the recycled case *does* owe the user is one line
        // saying the sessions now survive only as long as the app stays open — which is what the
        // timeout alert already says for the six-hour case, and this says for every other one.
        if (recycledWithSessionsOpen && sessionStore.liveHostIds().isNotEmpty()) {
            postAlert(
                context = this,
                id = NotificationChannels.ID_TIMEOUT_SESSIONS,
                title = getString(R.string.notif_detached_title),
                body = getString(R.string.notif_detached_body),
                contentIntent = restoreActivityIntent(this),
            )
        }
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        sessionText = text
        refreshNotification()
    }

    private fun refreshNotification() {
        val transfer = if (transferCount > 0) "$transferCount transfer(s) · $transferPercent%" else ""
        val text = listOf(sessionText, transfer).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { getString(R.string.app_name) }
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text, transferCount > 0, transferPercent))
        }
    }

    private fun buildNotification(text: String, showProgress: Boolean = false, progress: Int = 0): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EclipseSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val refreshIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, EclipseSessionService::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_action_reconnect), refreshIntent)
            .addAction(0, getString(R.string.notif_action_disconnect), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (showProgress) builder.setProgress(100, progress, false)
        return builder.build()
    }

    /**
     * Android 15 (API 35) caps `dataSync` foreground services at six hours per 24-hour window,
     * shared across the whole app, and then calls this. A service that has not stopped itself a few
     * seconds later is killed with a fatal `RemoteServiceException`, so *not* overriding this was a
     * guaranteed crash for exactly this service's purpose — keeping SSH sessions alive is the case
     * that reaches six hours.
     *
     * The sessions are deliberately **left open**, and the notification says so rather than claiming
     * they were closed - which is what it used to say, and what [onDestroy] has not done since sessions
     * moved into [SshSessionStore]. Closing them here would be the worse of the two outcomes: an
     * activity on screen holds the process at foreground importance all by itself, so a user who is
     * actually using the app keeps every shell they are typing into, and only a process Android later
     * reclaims loses them. What the app cannot do is promote itself again from the background until the
     * budget resets, so the notification's job is to say that the sessions are now only as durable as
     * the app being open - and to offer the tap that makes it so.
     *
     * Deliberately does not call `super`: the base method exists only from API 35, and its
     * implementation there is a no-op anyway.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // The state first: a restore pass mid-dial at this instant must not install a session
        // behind the stop.
        stopState.platformStop()
        // Stopping is the deadline's only deliverable, so it happens before anything that talks
        // to another process. This used to post the alert first - a permission check, a
        // PendingIntent build and a notify, three binder round trips - and spent the "few
        // seconds" of grace on them; a busy main thread turned that into
        // `ForegroundServiceDidNotStopInTimeException`. The alert still runs, but on the same
        // main thread after the stop is merely bookkeeping: destruction is queued behind the
        // current message, so the posting cannot be pre-empted by the service dying.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        postAlert(
            context = this,
            id = NotificationChannels.ID_TIMEOUT_SESSIONS,
            title = getString(R.string.notif_timeout_title),
            body = getString(R.string.notif_timeout_body),
            contentIntent = restoreActivityIntent(this),
        )
    }

    private fun createNotificationChannels() = NotificationChannels.ensureCreated(this)

    companion object {
        private const val TAG = "EclipseSessionService"

        /**
         * How long ACTION_STOP waits for the registry clear before stopping anyway. The write is
         * NonCancellable, so the timeout abandons the wait, not the write. Three seconds is
         * roughly a thousand normal DataStore edits; anything slower is storage in trouble, and
         * the service stopping must not wait for it.
         */
        private const val STOP_CLEAR_TIMEOUT_MS = 3_000L

        const val ACTION_STOP = "dev.eclipse.ssh.action.STOP"

        /**
         * Sent by the UI when it has just connected a session of its own: start the service (or keep
         * it alive) without asking it to restore anything. See the `when` in [onStartCommand].
         */
        const val ACTION_TRACK = "dev.eclipse.ssh.action.TRACK"
        const val ACTION_REFRESH = "dev.eclipse.ssh.action.REFRESH"
        const val ACTION_RESTORE = "dev.eclipse.ssh.action.RESTORE"

        /**
         * Set on a [MainActivity] intent to ask it to start this service once it is resumed. Used by
         * the alert notifications: the platform forbids starting a foreground service from the
         * background, but an app with a resumed activity is always allowed to.
         */
        const val EXTRA_RESTORE_SESSIONS = "dev.eclipse.ssh.extra.RESTORE_SESSIONS"

        // Channel ids and notification ids live in NotificationChannels, which is the single place
        // they are declared — a second copy here is how the transfer channel id drifted before.
        private const val NOTIFICATION_ID = NotificationChannels.ID_SERVICE

        /**
         * Tap target for the alert notifications: opens the app and asks it to reconnect once it is
         * on screen. Routing through the activity rather than straight at the service is what makes
         * the restart legal — see [EXTRA_RESTORE_SESSIONS].
         *
         * `FLAG_UPDATE_CURRENT` matters here: extras are not part of a PendingIntent's identity, so
         * without it a previously cached intent would be reused and the extra silently dropped.
         */
        fun restoreActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_RESTORE,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(EXTRA_RESTORE_SESSIONS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Request codes 0..2 belong to the ongoing notification's open/stop/refresh intents.
        private const val REQUEST_RESTORE = 3
    }
}

/**
 * The stop-path state machine for [EclipseSessionService].
 *
 * A `Service` has no built-in notion of "stopping": `stopSelf()` is a request, destruction is
 * asynchronous, and between the two the object still receives start commands and its coroutines
 * still run. This class is the one atomic answer to "is this service on its way out", which the
 * service used to lack entirely - ACTION_STOP's cleanup ran outside the restore mutex, so a
 * restore pass could install a live session *after* the stop's closeAll, and the start-vs-stop
 * race had no arbiter at all.
 *
 * The transitions:
 *
 * - `requestUserStop()` - ACTION_STOP, the notification's Disconnect. Accepted once; a second
 *   tap while a stop is in flight changes nothing. A user stop is final: nothing revives it,
 *   because the user just said "close my sessions" and a session connected moments later is
 *   still under that instruction.
 * - `platformStop()` - the dataSync timeout ([Service.onTimeout]). Also final, but differently:
 *   the *service* is going, and whether its work should continue is not the platform's call.
 * - `revive()` - a start intent (TRACK/REFRESH/RESTORE) that arrived while a platform stop was
 *   pending. The UI connecting a session is the one event that says this service still has
 *   work, so the restore machinery is allowed to continue until destruction actually lands.
 *   It does not resurrect the foreground promotion - that is the budget's call.
 * - `destroyed()` - [Service.onDestroy]. Returns whether the destruction was *unannounced*
 *   (phase was still RUNNING): the system recycled the service with no stop path involved,
 *   which is the state that owes the user a "your sessions lost their background protection"
 *   alert.
 *
 * A free class rather than service internals for the same reason [hostsNeedingRestore] is a
 * free function: the transition rules are the part with decisions in them, and a `Service` is
 * close to untestable in a JVM suite - the install-after-stop race this exists for was
 * invisible precisely because it lived inside one.
 */
internal class SessionServiceStopState {
    enum class Phase { RUNNING, STOPPING_USER, STOPPING_PLATFORM, DESTROYED }

    private val phase = AtomicReference(Phase.RUNNING)

    val isStopping: Boolean get() = phase.get() != Phase.RUNNING

    /** ACTION_STOP. False when a stop is already in flight, so cleanup runs exactly once. */
    fun requestUserStop(): Boolean = phase.compareAndSet(Phase.RUNNING, Phase.STOPPING_USER)

    /** The platform called [android.app.Service.onTimeout]: the service is going, now. */
    fun platformStop() {
        phase.set(Phase.STOPPING_PLATFORM)
    }

    /**
     * A start intent arrived. Only a pending *platform* stop can be cancelled: a user stop is
     * the user's instruction and stands. False means nothing changed.
     */
    fun revive(): Boolean = phase.compareAndSet(Phase.STOPPING_PLATFORM, Phase.RUNNING)

    /**
     * [onDestroy]: flips to the terminal [Phase.DESTROYED] and answers whether this destruction
     * was unannounced - no stop path had run, so the system recycled a live service.
     *
     * DESTROYED is its own phase rather than reusing STOPPING_PLATFORM precisely so [revive]
     * cannot resurrect it: the machine outlives the service only long enough for onDestroy to
     * read the unannounced flag, and a start intent racing destruction must not flip a dead
     * service back to RUNNING - which is what a shared phase value would allow, because
     * revive's compareAndSet cannot tell "stopping" from "stopped".
     */
    fun destroyed(): Boolean = phase.getAndSet(Phase.DESTROYED) == Phase.RUNNING
}

/**
 * The hosts a restore pass should dial: registered as active, and not already connected.
 *
 * A free function because it is the one part of the pass with a decision in it, and because a
 * `Service` is close to untestable in a JVM suite — the bug this replaced (the service dialling a
 * second session to a host the UI had just connected) was invisible precisely because it lived inside
 * one. `isLive` is passed in rather than read from a field so a test can state the app's session state
 * exactly, including the case that matters: an id that is *registered* and *already connected*.
 *
 * @param hosts every saved profile.
 * @param activeIds the ids [dev.eclipse.ssh.background.SessionRegistry] has credentials registered
 *   for, which is the app's definition of "the user wants this session up".
 * @param isLive whether the app already holds a usable session for an id.
 * @param askFirst the app-wide ask-first setting: when it is on, this pass - which runs from a
 *   connectivity callback while the phone is in a pocket - must not bring a shell back on its own.
 *   The dialog that asks lives in the activity; the service's half of the mode is declining.
 * @param transferPending whether [askFirst] should still dial for this host: a transfer the user
 *   scheduled is a standing instruction, and letting it die mid-copy because nobody was there to
 *   answer a prompt is not what the user asked for.
 */
internal fun hostsNeedingRestore(
    hosts: List<HostProfile>,
    activeIds: Set<String>,
    isLive: (String) -> Boolean,
    askFirst: Boolean = false,
    transferPending: (HostProfile) -> Boolean = { false },
): List<HostProfile> = hosts.filter {
    // A host that switched auto-reconnect off is never dialled by this pass. That switch exists for a
    // host which "must never be dialled unattended" - a bastion behind a one-time code, an audited
    // account, a metered link - and this pass is the most unattended dialler in the app: it runs from a
    // connectivity callback while the phone is in a pocket. Its session is still *kept* if one exists,
    // and Reconnect still works; nothing here brings it back on its own.
    it.autoReconnect && it.id in activeIds && !isLive(it.id) && (!askFirst || transferPending(it))
}

/**
 * The base reconnect delay for a pass that is retrying [hosts], in seconds.
 *
 * The pass has one wait for every host in it, so the shortest delay any of them asked for is the one
 * that has to be honoured: a host configured to retry after two seconds must not be held behind another
 * host's minute. The overshoot is harmless in the other direction - the slower host is simply retried
 * sooner than it asked, which is what it would have got before this setting existed.
 *
 * [globalSeconds] is the app-wide delay from Settings, used for every host that inherits it and for a
 * pass with nothing pending.
 */
internal fun restoreBaseSeconds(hosts: List<HostProfile>, globalSeconds: Int): Int =
    hosts.minOfOrNull { reconnectPolicyOf(it).backoffSeconds(globalSeconds) } ?: globalSeconds
