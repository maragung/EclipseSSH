package dev.eclipse.ssh.background

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.TransferRetry
import dev.eclipse.ssh.data.afterFailedAttempt
import dev.eclipse.ssh.data.persistCancelledTransfer
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.saf.localDocumentLength
import dev.eclipse.ssh.ssh.SftpTransferManager
import dev.eclipse.ssh.ssh.SshConnectionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.apache.sshd.client.session.ClientSession

/**
 * Reconnects durable transfers after the app process is killed. Transfers persist
 * their [TransferItem.remotePath], [TransferItem.localUri], and
 * [TransferItem.transferredBytes] in Room so a foreground service can reopen the
 * SFTP channel and continue from the exact byte offset without re-downloading.
 */
@Singleton
class TransferRestorer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransferRepository,
    private val sshConnectionManager: SshConnectionManager,
    private val transferManager: SftpTransferManager,
    private val scheduler: TransferScheduler,
    private val notifier: TransferNotifier,
) {
    suspend fun resumeForHost(hostId: String, session: ClientSession): Int {
        val pending = repository.transfers.first().filter { item ->
            item.hostId == hostId &&
                item.status in RESUMABLE_STATUSES &&
                (item.scheduledAt == null || item.scheduledAt <= System.currentTimeMillis()) &&
                // Cross-host rows are filtered out beside the blank-localUri ones rather than left
                // to the `when` below, because the restore pass must never touch them: there is no
                // local document to reopen, and "resuming" one would at best redial a host for
                // nothing and at worst mark a healthy transfer FAILED through the retry ladder.
                item.direction != TransferDirection.CROSS_HOST &&
                !item.remotePath.isNullOrBlank() &&
                !item.localUri.isNullOrBlank()
        }
        if (pending.isEmpty()) return 0

        var resumed = 0
        sshConnectionManager.withSftp(session) { sftp ->
            pending.forEach { item ->
                val uri = runCatching { item.localUri?.toUri() }.getOrNull() ?: return@forEach
                val remotePath = item.remotePath ?: return@forEach
                // The item as of its most recent progress callback. Every terminal write below uses
                // this instead of `item`, which is the row as it looked *before* this attempt ran:
                // `TransferDao.upsert` replaces the whole row, so saving the original back discarded
                // the counters the callback had just written. A restore interrupted at 80% was
                // recorded as 80% by the callback and then immediately overwritten with its starting
                // offset, which is the opposite of what the comment on that path claimed.
                var latest = item
                try {
                    when (item.direction) {
                        TransferDirection.DOWNLOAD -> {
                            val out = context.contentResolver.openOutputStream(uri, "wa")
                                ?: error("Unable to open destination")
                            // Append from the file's real length; the persisted counter lags
                            // behind by up to one progress interval and appending from it
                            // duplicated bytes into the resumed file.
                            val offset = localDocumentLength(context, uri) ?: item.transferredBytes
                            // `use` here as well as inside resumeDownload: the transfer manager
                            // only closes the stream once it reaches its own `use` block, and the
                            // sftp.open() that precedes it throws whenever the remote file has
                            // been deleted or turned unreadable since the transfer was queued.
                            // On that path the descriptor was leaked, once per failed restore,
                            // and the catch below quietly turned it into a retry. Closing twice
                            // is a no-op.
                            out.use { target ->
                                transferManager.resumeDownload(sftp, remotePath, target, offset) { bytes, total ->
                                    latest = item.asRunning(bytes, total)
                                    repository.save(latest)
                                }
                            }
                            repository.save(latest.copy(progress = 1f, status = TransferStatus.COMPLETE, errorMessage = null))
                        }
                        TransferDirection.UPLOAD -> {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: error("Unable to open source")
                            input.use { source ->
                                transferManager.resumeUpload(sftp, source, remotePath, item.transferredBytes, item.totalBytes) { bytes, total ->
                                    latest = item.asRunning(bytes, total)
                                    repository.save(latest)
                                }
                            }
                            repository.save(latest.copy(progress = 1f, status = TransferStatus.COMPLETE, errorMessage = null))
                        }
                        // Unreachable: the filter above excludes cross-host rows, and the compiler
                        // is what keeps that true for every direction added from now on. error()
                        // rather than a quiet skip, so a filter regression is a loud failure in QA
                        // instead of a restore that silently did nothing for a transfer.
                        TransferDirection.CROSS_HOST -> error("Cross-host transfers are filtered out above and cannot be restored")
                    }
                    resumed++
                } catch (cancelled: CancellationException) {
                    // A cancelled restore is not a failed one. The service being destroyed, the
                    // session closing and the user stopping a transfer all arrive here, and the
                    // blanket catch below used to treat every one of them as a failed attempt:
                    // retryCount went up and a retry worker was enqueued, so a handful of ordinary
                    // interruptions was enough to push a perfectly healthy transfer past its ceiling
                    // and mark it FAILED. Progress is already persisted by the progress callback, so
                    // pausing resumes at the right offset.
                    persistCancelledTransfer(latest, repository::save)
                    throw cancelled
                } catch (error: Throwable) {
                    // Same as the coordinator's catch: the reason is kept on the row, not discarded,
                    // so a FAILED transfer says why even when the failure happened in the background
                    // where nobody was watching it happen.
                    val reason = error.message?.trim()?.takeIf(String::isNotEmpty) ?: error.javaClass.simpleName
                    when (val next = latest.afterFailedAttempt()) {
                        is TransferRetry.Again -> {
                            repository.save(next.item.copy(errorMessage = reason))
                            scheduler.enqueue(next.item.id, delaySeconds = next.delaySeconds)
                        }
                        is TransferRetry.GiveUp -> {
                            repository.save(next.item.copy(errorMessage = reason))
                            // Absent until now, and this is the one path where it matters most: a
                            // restore only runs from the background service, so nobody is looking at
                            // the transfer list. Giving up silently meant the user's next sight of a
                            // transfer they queued hours ago was a FAILED row with no explanation of
                            // when or why. The coordinator has always notified here.
                            notifier.notifyFailed(next.item)
                        }
                    }
                }
            }
        }
        return resumed
    }

    private fun TransferItem.asRunning(bytes: Long, total: Long?): TransferItem {
        val fraction = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) } ?: progress
        return copy(progress = fraction, transferredBytes = bytes, totalBytes = total ?: totalBytes, status = TransferStatus.RUNNING, errorMessage = null)
    }

    private companion object {
        val RESUMABLE_STATUSES = setOf(TransferStatus.RUNNING, TransferStatus.PAUSED, TransferStatus.QUEUED)
    }
}
