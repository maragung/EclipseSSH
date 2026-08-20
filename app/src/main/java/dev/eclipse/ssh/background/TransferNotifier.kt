package dev.eclipse.ssh.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R
import dev.eclipse.ssh.data.model.TransferItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        runCatching {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRANSFERS,
                    "Transfer results",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    fun notifyComplete(item: TransferItem) {
        notify(item, "Transfer complete", "${item.direction.label} finished: ${item.name}")
    }

    fun notifyFailed(item: TransferItem) {
        notify(item, "Transfer failed", "${item.direction.label} failed: ${item.name}")
    }

    private fun notify(item: TransferItem, title: String, text: String) {
        // Android 13+ drops notifications from apps without POST_NOTIFICATIONS. Checking
        // first keeps lint's MissingPermission happy and avoids building a notification
        // that the system would silently discard.
        if (!canPostNotifications()) return
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify(item.id.hashCode() and Int.MAX_VALUE, notification) }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_TRANSFERS = "transfer_results"
    }
}
