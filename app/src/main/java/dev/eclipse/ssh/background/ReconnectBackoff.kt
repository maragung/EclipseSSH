package dev.eclipse.ssh.background

import dev.eclipse.ssh.data.settings.SettingsRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/** Hard ceiling for a single reconnect wait, so a long outage cannot push retries hours apart. */
const val MAX_BACKOFF_MS = 5 * 60_000L

/**
 * Deterministic part of the reconnect wait for [attempt] (1-based), in milliseconds.
 *
 * Doubles per attempt from [baseSeconds], with the exponent capped at 5 so the shift cannot
 * overflow however long an outage lasts, and the result capped at [maxBackoffMs]. The caller
 * adds jitter on top; this half stays pure so the growth and the clamps are testable.
 */
fun backoffWindowMs(
    baseSeconds: Int,
    attempt: Int,
    maxBackoffMs: Long = MAX_BACKOFF_MS,
): Long {
    val clampedBase = baseSeconds.coerceIn(
        SettingsRepository.MIN_RECONNECT_BASE_SECONDS,
        SettingsRepository.MAX_RECONNECT_BASE_SECONDS,
    )
    // Clamp the attempt before subtracting: `Int.MIN_VALUE - 1` wraps to Int.MAX_VALUE,
    // which would survive coerceIn and jump straight to the maximum exponent.
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(MAX_EXPONENT)
    return (clampedBase * 1_000L shl exponent).coerceAtMost(maxBackoffMs)
}

private const val MAX_EXPONENT = 5


/**
 * Waits out one reconnect window, or gives up waiting the moment [wake] signals.
 *
 * The window exists so a host that stays unreachable is not hammered, and it grows to
 * [MAX_BACKOFF_MS] — five minutes, plus the caller's jitter. That is the right wait for a server
 * that is down and the wrong one for the far more common case: the phone had no network at all, and
 * now it does. A plain `delay` could not tell those apart, so re-joining Wi-Fi left the sessions
 * dark for up to seven and a half minutes even though `ConnectivityManager` had announced the
 * network immediately, and the user's only recourse was the notification's Reconnect action.
 *
 * Returns true when it was woken early, so the caller can say so instead of counting down a wait
 * that no longer applies.
 *
 * @param windowMs the full wait; zero or less means do not wait at all.
 * @param wake conflated, so a signal raised while a connection attempt was in flight is not lost.
 */
internal suspend fun awaitReconnectWindow(windowMs: Long, wake: ReceiveChannel<Unit>): Boolean {
    if (windowMs <= 0) return false
    var woken = false
    withTimeoutOrNull(windowMs) {
        woken = wake.receiveCatching().isSuccess
        // A closed channel resolves instantly and forever. Falling through to the timeout keeps the
        // full window instead of turning the reconnect loop into a spin that never sleeps again.
        if (!woken) awaitCancellation()
    }
    return woken
}
