package dev.eclipse.ssh.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R

class QuickConnectWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildRemoteViews(context)) }
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.quick_connect_widget)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_open, pendingIntent)
        return views
    }
}
