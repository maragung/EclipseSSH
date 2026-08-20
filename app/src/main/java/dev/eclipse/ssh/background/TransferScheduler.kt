package dev.eclipse.ssh.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(transferId: String, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<RetryTransferWorker>()
            .setInputData(Data.Builder().putString(KEY_TRANSFER_ID, transferId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("transfer:$transferId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(transferId: String) {
        workManager.cancelUniqueWork("transfer:$transferId")
    }

    companion object { const val KEY_TRANSFER_ID = "transfer_id" }
}
