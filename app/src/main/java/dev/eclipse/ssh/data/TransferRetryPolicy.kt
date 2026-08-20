package dev.eclipse.ssh.data

import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What becomes of a transfer whose attempt did not succeed.
 *
 * Written once because it used to be written twice. `TransferCoordinator.scheduleRetryOrFail` and
 * `TransferRestorer.resumeForHost` each carried their own copy of the ladder — the same
 * `retryCount + 1`, the same ceiling of five, the same fifteen-second step — and the copies had
 * already drifted: the coordinator told the user when a transfer was finally given up on, and the
 * restorer did not. So a transfer that exhausted its retries while the app was in the background,
 * which is the only place a restore runs and therefore the only time the user is not watching, was
 * marked FAILED in silence.
 */
sealed interface TransferRetry {
    /** The item as it should now be persisted, with this attempt counted. */
    val item: TransferItem

    /** Try again later. [delaySeconds] backs off with the attempt number. */
    data class Again(override val item: TransferItem, val delaySeconds: Long) : TransferRetry

    /** Out of attempts. The caller is expected to tell the user. */
    data class GiveUp(override val item: TransferItem) : TransferRetry
}

/**
 * Attempts allowed before a transfer is abandoned. Deliberately generous: the usual cause is a link
 * that went away, and the ladder below spans about six minutes before giving up.
 */
const val MAX_TRANSFER_RETRIES = 5

/** Multiplied by the attempt number, so the waits run 15s, 30s, 45s, 60s, 75s. */
const val TRANSFER_RETRY_DELAY_SECONDS = 15L

/**
 * Counts this attempt and decides what follows.
 *
 * The returned item is PAUSED rather than FAILED while attempts remain, because PAUSED is what the
 * restorer looks for — a transfer marked FAILED is never picked up again, so promoting a retryable
 * failure to FAILED would quietly strand it.
 */
fun TransferItem.afterFailedAttempt(): TransferRetry {
    val attempt = retryCount + 1
    return if (attempt <= MAX_TRANSFER_RETRIES) {
        TransferRetry.Again(
            item = copy(status = TransferStatus.PAUSED, retryCount = attempt),
            delaySeconds = TRANSFER_RETRY_DELAY_SECONDS * attempt,
        )
    } else {
        TransferRetry.GiveUp(copy(status = TransferStatus.FAILED, retryCount = attempt))
    }
}

/**
 * Records a cancelled transfer as paused, and does it in a way that outlives the cancellation.
 *
 * Cancellation is not failure — the user tapped Pause, the session closed, the service was
 * destroyed — so [TransferItem.retryCount] is left alone and nothing is rescheduled. What matters is
 * that the row stops claiming to be RUNNING.
 *
 * [NonCancellable] is the load-bearing part. Both call sites reach this from inside a
 * `catch (CancellationException)`, where the coroutine's own context is already cancelled: a suspend
 * call there throws at its first suspension point instead of doing any work, so the write was lost
 * every single time. Pause therefore appeared to do nothing — the job really did stop, but the row
 * stayed RUNNING in Room and the list went on showing a transfer in flight until something else
 * happened to rewrite it.
 *
 * [save] is passed in rather than the repository injected so this stays a plain function a plain test
 * can call. `runCatching` because a database error during teardown must not replace the cancellation
 * the caller is about to rethrow.
 */
suspend fun persistCancelledTransfer(item: TransferItem, save: suspend (TransferItem) -> Unit) {
    withContext(NonCancellable) {
        runCatching { save(item.copy(status = TransferStatus.PAUSED)) }
    }
}
