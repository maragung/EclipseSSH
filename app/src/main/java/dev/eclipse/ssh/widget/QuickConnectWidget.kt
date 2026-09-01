package dev.eclipse.ssh.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R
import dev.eclipse.ssh.feature.qstile.LastHostProvider
import dev.eclipse.ssh.feature.quickconnect.QuickConnectContract
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget for "connect to my last host in one tap".
 *
 * Replaces the previous "open the app" widget, which used the same UI
 * surface for what should be two different actions. The widget now
 * reads the most-recently-connected host from [LastHostProvider] and
 * binds its name and a "Connect: <name>" subtitle into the existing
 * layout. A long-press still routes the user to the app's host list
 * via the launcher icon, but the widget's own click path is the
 * quick-connect.
 *
 * The widget is `@AndroidEntryPoint` because [LastHostProvider] is a
 * Hilt-injected singleton. Hilt's widget injection requires the
 * receiver to be an `@AndroidEntryPoint` so the generated
 * `Hilt_QuickConnectWidget` superclass wires the field before
 * `onUpdate` runs.
 */
@AndroidEntryPoint
class QuickConnectWidget : AppWidgetProvider() {

    @Inject lateinit var lastHostProvider: LastHostProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            scope.launch {
                val last = lastHostProvider.lastConnected()
                appWidgetManager.updateAppWidget(id, buildRemoteViews(context, last?.name))
            }
        }
    }

    private fun buildRemoteViews(context: Context, lastHostName: String?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.quick_connect_widget)
        val title = lastHostName ?: context.getString(R.string.app_name)
        val subtitle = if (lastHostName != null) {
            context.getString(R.string.widget_subtitle_with_host, lastHostName)
        } else {
            context.getString(R.string.widget_subtitle)
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)

        // Click action: the widget tap triggers a connect to the last host. It carries
        // QuickConnectContract.EXTRA_QUICK_CONNECT_LAST; MainActivity recognises that through
        // QuickConnectContract.isQuickConnect and dials the most-recently-used host.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(QuickConnectContract.EXTRA_QUICK_CONNECT_LAST, true)
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

    companion object {
        /**
         * Force a widget refresh from anywhere in the app — call after
         * a successful connect so the widget's "last host" label
         * updates without waiting for the next AppWidgetProvider tick.
         */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, QuickConnectWidget::class.java))
            val intent = Intent(context, QuickConnectWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
