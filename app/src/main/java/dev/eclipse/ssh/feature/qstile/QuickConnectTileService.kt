package dev.eclipse.ssh.feature.qstile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.feature.quickconnect.QuickConnectContract
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Quick Settings tile for "connect to the most-recently-used host".
 *
 * A long-standing Bitvise / Termux / JuiceSSH pattern: the user adds a
 * tile to the notification shade, taps it once, and a connect-to-last
 * happens in the background. The Android Quick Settings API is
 * `TileService` (API 24+); the tile's `onClick` opens the main activity
 * with a [MainActivity.EXTRA_QUICK_CONNECT_LAST] intent extra, and the
 * activity reads the last host and dials it.
 *
 * The tile shows the label "Connect to <host>" when a last host is
 * known, and "EclipseSSH" otherwise. The state is read from the host
 * database synchronously on the click thread; that is the only call
 * that has to be quick, so the repository has a blocking `first()` that
 * resolves to the most-recent row.
 */
@AndroidEntryPoint
class QuickConnectTileService : TileService() {

    @Inject lateinit var lastHostProvider: LastHostProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        // Read the last host once at tile creation; the tile label is
        // a snapshot of the most-recent value, not a live subscription.
        scope.launch {
            val last = lastHostProvider.lastConnected()
            val tile = qsTile ?: return@launch
            tile.label = if (last == null) "EclipseSSH" else "Connect: ${last.name}"
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Open the main activity with the quick-connect intent extra.
        // The activity handles the actual dial; the tile itself is
        // stateless.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(QuickConnectContract.EXTRA_QUICK_CONNECT_LAST, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: collapse the shade on unlockAndLaunch. The
            // (PendingIntent) overload is the one Android wants; the
            // (Intent) overload is deprecated. PendingIntent.FLAG_IMMUTABLE
            // is required from API 31+ and harmless below.
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivity(intent)
        }
    }
}
