package dev.eclipse.ssh.data

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The retry ladder and the pause write, both of which used to be duplicated inline in
 * `TransferCoordinator` and `TransferRestorer` where nothing could reach them.
 */
class TransferRetryPolicyTest {

    private fun item(retryCount: Int = 0, status: TransferStatus = TransferStatus.RUNNING) = TransferItem(
        id = "t1",
        name = "release.tar.gz",
        direction = TransferDirection.DOWNLOAD,
        hostName = "edge",
        progress = 0.4f,
        status = status,
        sizeLabel = "1.2 GB",
        hostId = "h1",
        remotePath = "/srv/release.tar.gz",
        localUri = "content://docs/1",
        transferredBytes = 500_000L,
        totalBytes = 1_250_000L,
        retryCount = retryCount,
    )

    @Test
    fun `every attempt short of the ceiling is retried on a lengthening delay`() {
        // 0 retries so far means this was attempt 1, and the wait before attempt 2 is one step.
        (0 until MAX_TRANSFER_RETRIES).forEach { alreadyTried ->
            val next = item(retryCount = alreadyTried).afterFailedAttempt()

            assertThat(next).isInstanceOf(TransferRetry.Again::class.java)
            next as TransferRetry.Again
            assertThat(next.item.retryCount).isEqualTo(alreadyTried + 1)
            assertThat(next.delaySeconds)
                .isEqualTo(TRANSFER_RETRY_DELAY_SECONDS * (alreadyTried + 1))
        }
    }

    /**
     * A retryable failure is recorded as PAUSED, never FAILED.
     *
     * `TransferRestorer` only picks up transfers whose status is RUNNING, PAUSED or QUEUED, so a row
     * marked FAILED is never looked at again. Marking a transfer that still has attempts left as
     * FAILED would stop it dead while the scheduler went on believing it had queued a retry.
     */
    @Test
    fun `a transfer with attempts left stays in a state the restorer will pick up`() {
        val next = item().afterFailedAttempt() as TransferRetry.Again

        assertThat(next.item.status).isEqualTo(TransferStatus.PAUSED)
        assertThat(next.item.status).isIn(RESUMABLE_FOR_TEST)
    }

    @Test
    fun `the attempt after the last one gives up and says so`() {
        val next = item(retryCount = MAX_TRANSFER_RETRIES).afterFailedAttempt()

        assertThat(next).isInstanceOf(TransferRetry.GiveUp::class.java)
        assertThat(next.item.status).isEqualTo(TransferStatus.FAILED)
        assertThat(next.item.retryCount).isEqualTo(MAX_TRANSFER_RETRIES + 1)
    }

    /**
     * A retryCount already past the ceiling — a row written by an older build, or one that raced —
     * must not wrap back around into being retried forever.
     */
    @Test
    fun `a count already past the ceiling still gives up`() {
        listOf(MAX_TRANSFER_RETRIES + 1, 99, Int.MAX_VALUE - 1).forEach { absurd ->
            assertThat(item(retryCount = absurd).afterFailedAttempt())
                .isInstanceOf(TransferRetry.GiveUp::class.java)
        }
    }

    @Test
    fun `nothing but the status and the count is touched`() {
        val original = item()

        val next = original.afterFailedAttempt().item

        // Progress and the byte offset in particular: a resume reads them to know where to start.
        assertThat(next).isEqualTo(original.copy(status = next.status, retryCount = next.retryCount))
    }

    /**
     * The regression test for a broken Pause button.
     *
     * `persistCancelledTransfer` is called from inside `catch (CancellationException)`, so the
     * coroutine's context is already cancelled. The two call sites used to write the row with a bare
     * `repository.save(...)` wrapped in `runCatching`, and a suspend call on a cancelled context
     * throws at its first suspension point — so the write never happened and `runCatching` ate the
     * evidence. The job really did stop, but Room went on recording the transfer as RUNNING and the
     * list went on showing it in flight.
     *
     * The save here suspends, which is what makes the test meaningful: a non-suspending lambda would
     * pass even without [kotlinx.coroutines.NonCancellable].
     */
    @Test
    fun `a cancelled transfer is written as paused even though the caller is already cancelled`() = runTest {
        val saved = mutableListOf<TransferItem>()
        val gate = CompletableDeferred<Unit>()

        val job = launch {
            try {
                gate.await()
            } catch (cancelled: CancellationException) {
                persistCancelledTransfer(item()) { updated ->
                    // Suspends, so a cancelled context would abandon the write right here.
                    yield()
                    saved += updated
                }
                throw cancelled
            }
        }
        yield()
        job.cancel()
        job.join()

        assertThat(saved.map { it.status }).containsExactly(TransferStatus.PAUSED)
        // And it is a pause, not an attempt: no retry is counted and nothing is rescheduled.
        assertThat(saved.single().retryCount).isEqualTo(0)
    }

    /**
     * A database failure during teardown must not replace the cancellation the caller is about to
     * rethrow — that would turn a paused transfer into an unhandled exception in a service shutdown.
     */
    @Test
    fun `a save that throws does not escape`() = runTest {
        persistCancelledTransfer(item()) { error("Room is closed") }
    }

    private companion object {
        /** Mirrors `TransferRestorer.RESUMABLE_STATUSES`, which is private to it. */
        val RESUMABLE_FOR_TEST =
            setOf(TransferStatus.RUNNING, TransferStatus.PAUSED, TransferStatus.QUEUED)
    }
}
