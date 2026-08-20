package dev.eclipse.ssh.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.eclipse.ssh.R
import dev.eclipse.ssh.data.TransferRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@HiltWorker
class RetryTransferWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionRegistry: SessionRegistry,
    private val transferRepository: TransferRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val activeHosts = sessionRegistry.activeHostIds.first()
            val transferId = inputData.getString(TransferScheduler.KEY_TRANSFER_ID)
            val scheduledItem = transferId?.let { id -> transferRepository.transfers.first().firstOrNull { it.id == id && it.scheduledAt != null } }
            if (scheduledItem != null && scheduledItem.hostId !in activeHosts) {
                return Result.retry()
            }
            if (activeHosts.isNotEmpty() && !startSessionService()) {
                // The platform refused, and it will keep refusing for as long as this worker runs
                // in the background, so retrying is pointless — ask the user instead.
                promptToRestore()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager cancels this coroutine when the work is cancelled or its constraints stop
            // holding. That is not an attempt that failed, and turning it into Result.retry() spent
            // one of the five from runAttemptCount — enough cancellations and the retry gave up for
            // good on a job that had never actually been tried.
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    /**
     * Starts the session service, reporting whether the platform allowed it.
     *
     * From API 31 a foreground service generally cannot be started while the app is in the
     * background: the attempt throws `ForegroundServiceStartNotAllowedException`. Receivers for
     * `BOOT_COMPLETED` are exempt, but that exemption belongs to the receiver's own dispatch — it
     * does not carry over to a WorkManager job the receiver enqueued, which is how this worker is
     * reached. Android 15 additionally blocks `dataSync` services outright when the launch traces
     * back to `BOOT_COMPLETED`, so there is no arrangement of this code path that can reconnect
     * unattended after a restart.
     *
     * The refusal used to escape into the blanket catch in [doWork], which retried five times and
     * then gave up silently, so restore-after-restart simply never worked.
     */
    private fun startSessionService(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, EclipseSessionService::class.java).setAction(EclipseSessionService.ACTION_RESTORE),
        )
    }.isSuccess

    /**
     * Falls back to a notification. Tapping it opens the app, and the activity starts the service
     * once it is resumed — an app with a foreground activity is always allowed to, which is why the
     * restart is routed through the UI rather than attempted again from here.
     */
    private fun promptToRestore() {
        postAlert(
            context = appContext,
            id = NotificationChannels.ID_RESTORE,
            title = appContext.getString(R.string.notif_restore_title),
            body = appContext.getString(R.string.notif_restore_body),
            contentIntent = EclipseSessionService.restoreActivityIntent(appContext),
        )
    }
}
