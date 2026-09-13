package dev.eclipse.ssh.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.eclipse.ssh.R

/**
 * Creates the app's notification channels.
 *
 * These used to be created only in [EclipseSessionService.onCreate], which made every other
 * notification depend on the service having run at least once: posting to a channel that does not
 * exist yet is silently dropped on API 26+, so anything that needed to warn the user *because* the
 * service could not start had nowhere to go. Channel creation is idempotent and cheap, so it now
 * happens at application startup and callers can rely on the channels existing.
 *
 * No SDK_INT guard: channels landed in API 26 and minSdk is 28.
 */
internal object NotificationChannels {
    const val SESSIONS = "active_sessions"
    const val TRANSFERS = "transfer_progress"
    const val ALERTS = "alerts"
    const val LINUX = "linux_userspace"

    /** Ongoing foreground-service notification. */
    const val ID_SERVICE = 4101

    /** "Background sessions paused" — the Android 15 foreground-service time limit was hit. */
    const val ID_TIMEOUT = 4102

    /** "Reconnect your sessions" — the platform refused a background service start. */
    const val ID_RESTORE = 4103

    /** The local Ubuntu environment's ongoing "Running" notification. */
    const val ID_LINUX = 4104

    /** "Ubuntu runs only while the app is open" — the platform refused the promotion to foreground. */
    const val ID_LINUX_PROMOTION = 4105

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        // Wrapped because this runs during Application.onCreate, where an unexpected failure would
        // take the whole process down before any UI exists to report it.
        runCatching {
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        SESSIONS,
                        context.getString(R.string.channel_sessions),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_sessions_desc) },
                    NotificationChannel(
                        TRANSFERS,
                        context.getString(R.string.channel_transfers),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_transfers_desc) },
                    NotificationChannel(
                        ALERTS,
                        context.getString(R.string.channel_alerts),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = context.getString(R.string.channel_alerts_desc) },
                    NotificationChannel(
                        LINUX,
                        context.getString(R.string.channel_linux),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_linux_desc) },
                ),
            )
        }
    }
}

/**
 * Posts a one-off alert on [NotificationChannels.ALERTS].
 *
 * Shared by the session service and its workers, which both need to tell the user about something
 * the platform stopped the app from doing quietly in the background — a notification is the only
 * channel either of them has when no UI is on screen. [contentIntent] is what a tap does.
 */
internal fun postAlert(
    context: Context,
    id: Int,
    title: String,
    body: String,
    contentIntent: PendingIntent?,
) {
    // Android 13+ silently discards notifications from apps without POST_NOTIFICATIONS, so there is
    // nothing to gain by building one; the check is also what keeps lint's MissingPermission quiet.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    // Both alert bodies explain a platform restriction and are longer than one collapsed line.
    val notification = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
}
