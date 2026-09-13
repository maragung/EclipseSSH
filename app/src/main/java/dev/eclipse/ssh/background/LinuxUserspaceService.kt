package dev.eclipse.ssh.background

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.presentation.linux.LinuxUserspaceController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Holds the app process — and with it every forked proot shell — at foreground importance while
 * the local Ubuntu userspace is [LinuxUserspaceState.Running].
 *
 * The userspace has no daemon to keep alive (proot is a fresh fork per shell; see
 * `ProotRuntime`), so what backgrounding actually risks is the *process*: Android reclaims a
 * cached process freely, and every Ubuntu terminal is a child of it. The spec's "app background ≠
 * stop, only an explicit Stop" is therefore a process-liveness promise, and this service is how
 * it is kept.
 *
 * Existence is derived, never requested: [LinuxUserspaceController] owns the binding — it starts
 * this service when the state machine enters Running and stops it when the machine leaves — and
 * this service *also* watches the state itself and stops itself if it is ever alive while the
 * userspace is not Running. The second rule is the belt to the first's braces: it makes the
 * service self-healing against any future path into the state machine the controller's binding
 * does not know about, and it is the same "derived, never registered" posture as the host card.
 *
 * Started with [ACTION_STOP] only from the notification's Stop action; that is the one-stop
 * gesture the notification exists to offer, and it goes through the manager (the same code path
 * as Settings → Stop) rather than killing anything directly.
 */
@AndroidEntryPoint
class LinuxUserspaceService : LifecycleService() {

    @Inject lateinit var linuxUserspace: LinuxUserspaceController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val graph = linuxUserspace.graph
        // The controller never starts this service on an unsupported device, so a null graph here
        // means a start from somewhere else; there is nothing to hold open, so leave.
        if (graph == null) {
            stopSelf()
            return
        }
        NotificationChannels.ensureCreated(this)
        // API 31+ rejects background foreground-service starts and API 34+ requires the declared
        // type; either failure must not take the process down (EclipseSessionService's posture).
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationChannels.ID_LINUX,
                buildNotification(sessionCount = graph.processes.sessionCount.value),
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
        serviceScope.launch {
            // Every live source of the notification's text, plus the state rule that ends this
            // service. Session count is in the body because it is the number the user's Stop
            // decision turns on: "3 terminals held open" is a different act to stop than "0".
            combine(graph.manager.state, graph.processes.sessionCount) { state, count ->
                state to count
            }.collect { (state, count) ->
                if (state !is LinuxUserspaceState.Running) {
                    stopSelf()
                    return@collect
                }
                updateNotification(count)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = linuxUserspace.graph
        if (intent?.action == ACTION_STOP && graph != null) {
            // Through the manager, not a direct kill: Stop is a lifecycle verb with a state
            // transition (it closes every local session and parks the machine in Stopped), and
            // the observer above then stops this service as a consequence, exactly as it would
            // for a Stop tapped in Settings.
            serviceScope.launch {
                runCatching { graph.manager.stop() }
            }
        }
        // Not sticky: if the process was killed, every proot child died with it and the fresh
        // process's state machine is not Running, so a restarted service would observe that and
        // immediately stop itself — a promotion and a dismissal for nothing.
        return START_NOT_STICKY
    }

    /**
     * Android 15 caps dataSync foreground services at six hours per 24-hour window and then calls
     * this; a service that does not stop itself within seconds is killed with a fatal
     * `RemoteServiceException`. Stopping is safe by design: the userspace's Running state and its
     * shells are left exactly as they are — an activity on screen holds the process at foreground
     * importance on its own — and the state machine stays Running, so Settings and the host card
     * keep telling the truth. What is lost is only the *background* protection, and the alert
     * says so rather than letting the shells die silently later.
     *
     * Deliberately does not call `super`: the base method exists only from API 35, and its
     * implementation there is a no-op anyway.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Stopping is the deadline's only deliverable, so it happens before the alert - the
        // alert is three binder calls (permission check, PendingIntent build, notify) that the
        // "few seconds" of grace should not be spent on. The posting still runs: destruction is
        // queued on the main looper behind the current message, so the service cannot die
        // between the stopSelf and the notify.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        postAlert(
            this,
            // Its own id, not the session service's: both services share the one dataSync
            // budget, so when it runs out they both land here, and a shared id made this
            // alert overwrite the session service's - one notification about two services.
            NotificationChannels.ID_TIMEOUT_LINUX,
            getString(R.string.notif_timeout_title),
            getString(R.string.notif_timeout_body),
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LinuxUserspaceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.LINUX)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_linux_title))
            .setContentText(getString(R.string.notif_linux_body, sessionCount))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_linux_action_open), openIntent)
            .addAction(0, getString(R.string.notif_linux_action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(sessionCount: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NotificationChannels.ID_LINUX, buildNotification(sessionCount)) }
    }

    companion object {
        /** The notification's Stop button — the one-stop gesture this service exists to offer. */
        const val ACTION_STOP = "dev.eclipse.ssh.background.LinuxUserspaceService.STOP"
    }
}
