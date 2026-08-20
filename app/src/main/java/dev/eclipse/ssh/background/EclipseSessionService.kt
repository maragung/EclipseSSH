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
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ssh.SshConnectionManager
import dev.eclipse.ssh.ssh.SshKeyLoader
import java.util.concurrent.ConcurrentHashMap
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
    @Inject lateinit var transferRestorer: TransferRestorer
    @Inject lateinit var transferRepository: TransferRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreMutex = Mutex()
    private val restoredSessions = ConcurrentHashMap<String, org.apache.sshd.client.session.ClientSession>()
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
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Both, and in this order. The signal releases a backoff that is already sleeping —
            // which is the case that used to cost minutes — while the launch covers the service
            // sitting idle with no loop running to wake.
            reconnectWake.trySend(Unit)
            serviceScope.launch { restoreSessions("Network available") }
        }

        override fun onLost(network: Network) {
            updateNotification("Network lost · waiting to reconnect")
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
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
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
            // Drop sessions the server has closed, otherwise a dropped session stays in the
            // map forever and the host is never reconnected — the service's whole purpose.
            restoredSessions.entries.removeAll { (_, session) -> !session.isOpen }
            val activeIds = sessionRegistry.activeHostIds.first()
            val hosts = hostRepository.hosts.first().filter { it.id in activeIds }
            val pending = hosts.filterNot { restoredSessions.containsKey(it.id) }
            var connected = 0
            pending.forEach { host ->
                val password = sessionRegistry.credential(host.id)
                val keyPair = sessionRegistry.keyBytes(host.id)?.let { bytes -> runCatching { SshKeyLoader.load(bytes, "${host.username}-key", sessionRegistry.keyPassphrase(host.id)) }.getOrNull() }
                val session = try {
                    sshConnectionManager.connect(host, password, keyPair)
                } catch (cancelled: CancellationException) {
                    // The service is going away. Without this the cancellation was swallowed into a
                    // null session and the loop went on to dial every remaining host on an already
                    // dead context — pointless work during shutdown, and it hid the one condition
                    // that should stop the pass immediately.
                    throw cancelled
                } catch (_: Throwable) {
                    // Any other failure is this host's problem alone: unreachable, refused, wrong
                    // credentials. The rest of the pass still gets its turn, and the backoff below
                    // decides when to come back.
                    null
                }
                if (session != null) {
                    restoredSessions[host.id] = session
                    connected++
                    val resumed = runCatching { transferRestorer.resumeForHost(host.id, session) }.getOrDefault(0)
                    if (resumed > 0) updateNotification("Resuming $resumed transfer(s) for ${host.name}")
                }
            }
            // Retry only while something is still missing. Comparing `connected == 0` alone
            // spun forever once every host was already restored, waking the CPU every
            // backoff interval for the lifetime of the service.
            if (connected == 0 && pending.isNotEmpty()) {
                reconnectAttempts++
                val waitMs = backoffDelay(reconnectAttempts)
                updateNotification("$attemptReason · retry $reconnectAttempts in ${waitMs / 1_000}s")
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
                val live = restoredSessions.size
                updateNotification(if (live == 0) "$attemptReason · no sessions restored" else "$live SSH session(s) active")
                return@withLock
            }
        }
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

    private fun closeSessions() {
        restoredSessions.values.forEach { runCatching { it.close(false) } }
        restoredSessions.clear()
    }

    override fun onDestroy() {
        if (::connectivityManager.isInitialized) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        serviceScope.coroutineContext.cancel()
        // After the scope, so the cancellation is what ends a sleeping backoff rather than a closed
        // channel; awaitReconnectWindow treats the latter as "wait the whole window".
        reconnectWake.close()
        closeSessions()
        // Deliberately does NOT call sshConnectionManager.close(): the SshClient is an
        // application-scoped singleton shared with the UI, and stopping it here tore down
        // every interactive terminal/SFTP session the moment the service was recycled.
        // Only the sessions this service opened itself are closed, above.
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
