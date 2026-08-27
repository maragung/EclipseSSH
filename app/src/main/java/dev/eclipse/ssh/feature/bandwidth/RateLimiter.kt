package dev.eclipse.ssh.feature.bandwidth

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * A token-bucket rate limiter for SFTP transfers.
 *
 * Limits a stream of bytes to a long-run average of [bytesPerSecond] bytes per
 * second, with bursts up to [bucketSize] bytes. The bucket refills at the
 * configured rate; every byte that arrives drains one token, and a request
 * that finds the bucket empty waits the time it takes to accumulate the
 * tokens it needs.
 *
 * Two ways to use it:
 *  - **[await]**: call before each read/write, with the number of bytes the
 *    next operation will move. The coroutine suspends if the budget for this
 *    moment is exhausted, which is the right shape for an SFTP transfer
 *    loop: "I want to push N bytes, give me a green light".
 *  - **[wrap]**: returns a `RateLimitedInputStream` that throttles the
 *    wrapped stream's reads. Use this when the byte source is a content
 *    provider or a piped stream the transfer loop does not control.
 *
 * `bytesPerSecond = 0` (or negative) is the "no limit" sentinel: [await]
 * returns immediately and [wrap] returns the original stream. The transfer
 * coordinator uses this so a "limit disabled" setting costs nothing.
 *
 * The bucket is not thread-safe; the only coroutine that touches a transfer
 * is the one driving the upload or download, so the lack of locking is
 * deliberate.
 */
class RateLimiter(
    private val bytesPerSecond: Long,
    private val bucketSize: Long = DEFAULT_BUCKET,
) {
    init {
        require(bucketSize > 0) { "bucketSize must be > 0" }
    }

    private var available: Double = bucketSize.toDouble()
    private var lastRefillNanos: Long = System.nanoTime()

    /**
     * Suspends until [bytes] tokens are available, then consumes them.
     *
     * Cooperative cancellation: a cancelled transfer wakes from the delay
     * the next time the bucket would refill. The poll loop's [ensureActive]
     * is the only guarantee here; the underlying [delay] is itself
     * cancellable, so the wake-up latency is bounded by the chosen
     * [POLL_INTERVAL_MS].
     */
    suspend fun await(bytes: Long) {
        if (bytesPerSecond <= 0L) return
        if (bytes <= 0) return
        while (true) {
            coroutineContext.ensureActive()
            refill()
            if (available >= bytes) {
                available -= bytes
                return
            }
            val deficit = bytes - available
            val waitMs = ((deficit * 1_000.0) / bytesPerSecond).toLong()
                .coerceAtMost(POLL_INTERVAL_MS)
            if (waitMs > 0) delay(waitMs)
        }
    }

    /**
     * Convenience for the common case of "I just transferred `bytes` bytes,
     * charge them against the bucket".
     */
    suspend fun charge(bytes: Long) = await(bytes)

    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillNanos
        if (elapsedNanos <= 0) return
        val earned = (elapsedNanos / 1_000_000_000.0) * bytesPerSecond
        available = (available + earned).coerceAtMost(bucketSize.toDouble())
        lastRefillNanos = now
    }

    companion object {
        const val DEFAULT_BUCKET: Long = 256 * 1024  // 256 KiB burst
        private const val POLL_INTERVAL_MS: Long = 100

        /**
         * No-op limiter for the "rate limiting off" case. Awaiting a charge
         * returns immediately and [wrap] returns the original stream. The
         * transfer coordinator does not special-case this object — it
         * always goes through [await] / [charge].
         */
        val UNLIMITED: RateLimiter = RateLimiter(0L)
    }
}
