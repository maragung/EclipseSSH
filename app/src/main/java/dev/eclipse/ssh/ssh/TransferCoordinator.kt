package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.TransferRetry
import dev.eclipse.ssh.data.afterFailedAttempt
import dev.eclipse.ssh.data.persistCancelledTransfer
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.background.TransferNotifier
import dev.eclipse.ssh.background.TransferScheduler
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.sshd.sftp.client.SftpClient

@Singleton
class TransferCoordinator @Inject constructor(
    private val repository: TransferRepository,
    private val transfers: SftpTransferManager,
    private val scheduler: TransferScheduler,
    private val notifier: TransferNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun schedule(item: TransferItem, scheduledAt: Long) {
        scope.launch {
            val runAt = scheduledAt.coerceAtLeast(System.currentTimeMillis() + 1_000L)
            repository.save(item.copy(status = TransferStatus.QUEUED, scheduledAt = runAt))
            scheduler.enqueue(item.id, delaySeconds = ((runAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000L))
        }
    }

    /**
     * Starts a download in the background.
     *
     * [ownsSftp] (the default) hands the SFTP client's lifetime to the coordinator: it is
     * closed once the transfer finishes, is paused, or fails. Callers must therefore not
     * wrap the client in `use { }` — the block would close the channel before the launched
     * job ever touched it, which silently failed every transfer started that way. Pass
     * `false` only when the caller keeps the client alive for the whole transfer (the
     * directory-sync paths, which await each item in turn).
     */
    fun download(
        item: TransferItem,
        sftp: SftpClient,
        remotePath: String,
        destination: OutputStream,
        ownsSftp: Boolean = true,
    ) {
        launchTransfer(item, if (ownsSftp) sftp else null, destination) {
            runDownload(item, sftp, remotePath, destination)
        }
    }

    fun upload(
        item: TransferItem,
        sftp: SftpClient,
        source: InputStream,
        remotePath: String,
        totalBytes: Long? = null,
        ownsSftp: Boolean = true,
    ) {
        launchTransfer(item, if (ownsSftp) sftp else null, source) {
            runUpload(item, sftp, source, remotePath, totalBytes)
        }
    }

    /**
     * Continues an interrupted download. [existingBytes] must be the destination's real length —
     * [TransferItem.transferredBytes] is throttled and lags behind, so appending from it would
     * duplicate bytes into the file.
     *
     * Required rather than defaulted for exactly that reason. It used to default to
     * `item.transferredBytes`, which is the one value the sentence above forbids: the contract was
     * stated in prose and then undermined by the signature, so a caller that trusted the default got
     * a corrupted file and no warning from anywhere. Every caller measures the partial file itself
     * (`localDocumentLength`), and now the compiler is what keeps it that way.
     */
    fun resumeDownload(
        item: TransferItem,
        sftp: SftpClient,
        destination: OutputStream,
        existingBytes: Long,
        ownsSftp: Boolean = true,
    ) {
        launchTransfer(item, if (ownsSftp) sftp else null, destination) {
            val remotePath = item.remotePath ?: error("Missing remote path")
            transfers.resumeDownload(sftp, remotePath, destination, existingBytes) { bytes, total -> persistProgress(item, bytes, total) }
        }
    }

    fun resumeUpload(item: TransferItem, sftp: SftpClient, source: InputStream, ownsSftp: Boolean = true) {
        launchTransfer(item, if (ownsSftp) sftp else null, source) {
            val remotePath = item.remotePath ?: error("Missing remote path")
            transfers.resumeUpload(sftp, source, remotePath, item.transferredBytes, item.totalBytes) { bytes, total -> persistProgress(item, bytes, total) }
        }
    }

    /** Runs a download to completion on the caller's coroutine, reusing a shared [sftp]. */
    suspend fun downloadAwait(item: TransferItem, sftp: SftpClient, remotePath: String, destination: OutputStream) {
        guarded(item) { runDownload(item, sftp, remotePath, destination) }
    }

    /** Runs an upload to completion on the caller's coroutine, reusing a shared [sftp]. */
    suspend fun uploadAwait(item: TransferItem, sftp: SftpClient, source: InputStream, remotePath: String, totalBytes: Long? = null) {
        guarded(item) { runUpload(item, sftp, source, remotePath, totalBytes) }
    }

    fun pause(id: String) {
        scheduler.cancel(id)
        jobs.remove(id)?.cancel()
    }

    /** Cancels every in-flight transfer (process teardown / "disconnect all"). */
    fun cancelAll() {
        jobs.keys.toList().forEach { id -> jobs.remove(id)?.cancel() }
    }

    private suspend fun runDownload(item: TransferItem, sftp: SftpClient, remotePath: String, destination: OutputStream) {
        transfers.download(sftp, remotePath, destination) { bytes, total -> persistProgress(item, bytes, total) }
    }

    private suspend fun runUpload(item: TransferItem, sftp: SftpClient, source: InputStream, remotePath: String, totalBytes: Long?) {
        transfers.upload(sftp, source, remotePath, totalBytes) { bytes, total -> persistProgress(item, bytes, total) }
    }

    private fun launchTransfer(
        item: TransferItem,
        ownedSftp: SftpClient?,
        stream: Closeable,
        body: suspend () -> Unit,
    ) {
        jobs.remove(item.id)?.cancel()
        jobs[item.id] = scope.launch {
            try {
                guarded(item, body)
            } finally {
                // The stream and (when owned) the SFTP channel are released on every exit
                // path, including cancellation, so a paused or failed transfer cannot leak
                // a ContentResolver file descriptor or an SFTP channel.
                runCatching { stream.close() }
                ownedSftp?.let { client -> runCatching { client.close() } }
                // Conditional, because cancellation completes asynchronously: restarting a
                // transfer cancels the previous job, whose `finally` can then run *after* the
                // replacement has been registered. An unconditional remove would evict the
                // replacement, leaving pause() with nothing to cancel — the row would flip to
                // PAUSED while the job kept transferring and persistProgress pushed it back
                // to RUNNING.
                coroutineContext[Job]?.let { self -> jobs.remove(item.id, self) }
            }
        }
    }

    /**
     * Runs one attempt and records how it ended.
     *
     * Every terminal write goes through [withObservedProgress] rather than using [item] directly.
     * [item] is the snapshot the caller handed over before the transfer started — usually zero bytes
     * of an unknown total — and `TransferDao.upsert` is a whole-row REPLACE, so writing that snapshot
     * back *undid* everything [persistProgress] had recorded during the attempt. A download paused at
     * 80% was written back as 0%, and every completed transfer was stored with `progress = 1f` beside
     * a byte count of zero. The bytes themselves were never re-sent — a resumed download takes its
     * offset from the destination's real length and a resumed upload from the remote file's size — so
     * this was the list lying about state rather than lost data, on every completion and every pause.
     */
    private suspend fun guarded(item: TransferItem, body: suspend () -> Unit) {
        // The reason a previous attempt failed does not survive the attempt that replaces it: a row
        // that kept its error after succeeding would go on accusing a healthy transfer.
        repository.save(item.copy(status = TransferStatus.RUNNING, errorMessage = null))
        try {
            body()
            val finished = withObservedProgress(item).copy(progress = 1f, status = TransferStatus.COMPLETE, errorMessage = null)
            repository.save(finished)
            notifier.notifyComplete(finished)
            scheduleNextIfRecurring(item)
        } catch (cancelled: CancellationException) {
            // Progress is already persisted, so a resume picks up at the right offset.
            persistCancelledTransfer(withObservedProgress(item), repository::save)
            throw cancelled
        } catch (error: Throwable) {
            // The reason is persisted rather than only reported, because the row is the one place the
            // user looks: a FAILED transfer on the list used to say nothing about why, and the reason
            // was discarded here exactly where it was still in hand.
            scheduleRetryOrFail(withObservedProgress(item), failureReason(error))
        } finally {
            lastPersistedAt.remove(item.id)
            lastObserved.remove(item.id)
        }
    }

    /**
     * [item] carrying the most recent counts this attempt reported, or unchanged if it reported none
     * (a transfer that failed while opening the remote file never reaches a progress callback).
     */
    private fun withObservedProgress(item: TransferItem): TransferItem {
        val observed = lastObserved[item.id] ?: return item
        val total = observed.total ?: item.totalBytes
        val fraction = total?.takeIf { it > 0 }?.let { (observed.bytes.toFloat() / it).coerceIn(0f, 1f) }
            ?: item.progress
        return item.copy(progress = fraction, transferredBytes = observed.bytes, totalBytes = total)
    }

    /**
     * Persists transfer progress at most every [PROGRESS_INTERVAL_MS] so a large
     * transfer does not hammer Room with a write per 64 KiB chunk. The final state
     * is always written by the callers (COMPLETE / PAUSED / FAILED).
     */
    private suspend fun persistProgress(item: TransferItem, bytes: Long, total: Long?) {
        // Recorded on every callback, not just the ones that reach the database: this is what the
        // terminal write in `guarded` reads, and throttling it would put the last 200 ms of a
        // transfer beyond the reach of the row that closes it.
        lastObserved[item.id] = ObservedProgress(bytes, total)
        val now = System.currentTimeMillis()
        val last = lastPersistedAt[item.id] ?: 0L
        if (now - last < PROGRESS_INTERVAL_MS) return
        lastPersistedAt[item.id] = now
        val fraction = total?.takeIf { it > 0 }?.let { bytes.toFloat() / it } ?: item.progress
        repository.save(item.copy(progress = fraction.coerceIn(0f, 1f), transferredBytes = bytes, totalBytes = total ?: item.totalBytes, status = TransferStatus.RUNNING))
    }

    private suspend fun scheduleRetryOrFail(item: TransferItem, reason: String? = null) {
        when (val next = item.afterFailedAttempt()) {
            is TransferRetry.Again -> {
                repository.save(next.item.copy(errorMessage = reason))
                scheduler.enqueue(next.item.id, delaySeconds = next.delaySeconds)
            }
            is TransferRetry.GiveUp -> {
                repository.save(next.item.copy(errorMessage = reason))
                notifier.notifyFailed(next.item)
            }
        }
    }

    /**
     * One line a card can show. `Throwable.message` is often the server's own sentence ("No such
     * file"), but some throwables carry null or an empty message and the class name alone ("SshException")
     * is still more than the nothing the list used to say.
     */
    private fun failureReason(error: Throwable): String? =
        error.message?.trim()?.takeIf(String::isNotEmpty) ?: error.javaClass.simpleName.takeIf(String::isNotEmpty)


    private suspend fun scheduleNextIfRecurring(item: TransferItem) {
        val repeatMinutes = item.repeatMinutes ?: return
        if (repeatMinutes <= 0) return
        val next = item.copy(
            id = java.util.UUID.randomUUID().toString(),
            progress = 0f,
            transferredBytes = 0L,
            retryCount = 0,
            status = TransferStatus.QUEUED,
            scheduledAt = System.currentTimeMillis() + repeatMinutes * 60_000L,
        )
        repository.save(next)
        scheduler.enqueue(next.id, delaySeconds = repeatMinutes * 60L)
    }

    private val lastPersistedAt = ConcurrentHashMap<String, Long>()

    /** The newest counts a running transfer has reported, whether or not they were written yet. */
    private val lastObserved = ConcurrentHashMap<String, ObservedProgress>()

    private data class ObservedProgress(val bytes: Long, val total: Long?)

    private companion object {
        // The retry ladder used to live here too; it is shared with the restorer now, in
        // dev.eclipse.ssh.data.TransferRetryPolicy.
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
