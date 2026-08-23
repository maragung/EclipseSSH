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
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreMutex = Mutex()
    private var sessionText = ""
    private var transferCount = 0
    private var transferPercent = 0
    private var reconnectAttempts = 0
    private lateinit var connectivityManager: ConnectivityManager

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
            serviceScope.launch { restoreSessions("Network available") }
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
            stopSelf()
            return
        }
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
        serviceScope.launch { restoreSessions("Service active") }
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
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch {
                sessionRegistry.clear()
                closeSessions()
                stopSelf()
            }
            ACTION_REFRESH, ACTION_RESTORE -> serviceScope.launch { restoreSessions("Manual reconnect") }
            // "Keep running, the UI has a session now." Deliberately does nothing else: the UI dials
            // its own session and puts it in the shared store, so a restore pass here would only be a
            // second dialler racing it. That is what tapping Connect used to launch — the intent
            // carried no action, and a null action means the process was killed and restarted (see
            // below), so every single Connect tap ran a full restore pass alongside the UI's own
            // handshake. The dial gate makes that harmless now, and this makes it not happen.
            ACTION_TRACK -> Unit
            // A null intent means START_STICKY re-created the service after the process was
            // killed, so the sessions genuinely need restoring rather than a text refresh
            // that would overwrite the live reconnect status.
            null -> serviceScope.launch { restoreSessions("Service restarted") }
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
        // stays unreachable for days) can never grow the coroutine call stack.
        while (coroutineContext.isActive) {
            val activeIds = sessionRegistry.activeHostIds.first()
            val hosts = hostRepository.hosts.first()
            // [SshSessionStore.isLive] answers for the whole app, not just for this service, and that
            // is the point: the same registry entry is written the moment the *UI* authenticates a
            // host, so a service that could only see its own sessions dialled a second one to the
            // account the user had just connected — every single time Connect was tapped, because
            // tapping Connect is also what starts this service. It prunes dead entries as it goes, so
            // a session that has since dropped is still restored here.
            val pending = hostsNeedingRestore(hosts, activeIds) { sessionStore.isLive(it) }
            var connected = 0
            // Hosts skipped because another dialler already had them. Counted rather than ignored: they
            // are neither successes (nothing was restored) nor failures (nothing went wrong), and the
            // backoff below needs to tell those apart.
            var busy = 0
            pending.forEach { host ->
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
                    // its turn is a session to adopt, not one to duplicate.
                    sessionStore.liveSession(host.id) ?: dial(host)
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
                val waitMs = backoffDelay(reconnectAttempts)
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
        sessionStore.install(host.id, sshConnectionManager.connect(host, sessionRegistry.credential(host.id), keyPairFor(host)))
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
     * Exponential backoff seeded from the user's reconnect base delay, with jitter so
     * several hosts recovering at once do not retry in lockstep.
     */
    private suspend fun backoffDelay(attempt: Int): Long {
        val baseSeconds = runCatching { settingsRepository.settings.first().reconnectBaseSeconds }
            .getOrDefault(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
        val base = backoffWindowMs(baseSeconds, attempt)
        return base + Random.nextLong(0, base / 2 + 1)
    }

    /** Only for the notification's Stop action, which is the user saying "close my sessions". */
    private fun closeSessions() = sessionStore.closeAll()

    override fun onDestroy() {
        if (::connectivityManager.isInitialized) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        serviceScope.coroutineContext.cancel()
        // After the scope, so the cancellation is what ends a sleeping backoff rather than a closed
        // channel; awaitReconnectWindow treats the latter as "wait the whole window".
        reconnectWake.close()
        // Deliberately closes nothing. The SshClient is an application-scoped singleton shared with
        // the UI, so stopping it here tore down every interactive terminal and SFTP session the moment
        // the service was recycled — and now that sessions live in [SshSessionStore] rather than in a
        // map owned by this service, closing "its own" sessions would do exactly the same damage: the
        // session the user is typing into is the same object. A session ends when the user closes its
        // tab or taps Stop, or when the process dies and the kernel closes the socket. The service
        // being recycled is none of those.
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }
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
     * The sessions are closed cleanly by [onDestroy] and the user is told, because the app cannot
     * restart the service from the background afterwards; bringing the app to the foreground resets
     * the budget. Deliberately does not call `super`: the base method exists only from API 35, and
     * its implementation there is a no-op anyway.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        postAlert(
            context = this,
            id = NotificationChannels.ID_TIMEOUT,
            title = getString(R.string.notif_timeout_title),
            body = getString(R.string.notif_timeout_body),
            contentIntent = restoreActivityIntent(this),
        )
        stopSelf()
    }

    private fun createNotificationChannels() = NotificationChannels.ensureCreated(this)

    companion object {
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
 */
internal fun hostsNeedingRestore(
    hosts: List<HostProfile>,
    activeIds: Set<String>,
    isLive: (String) -> Boolean,
): List<HostProfile> = hosts.filter { it.id in activeIds && !isLive(it.id) }
