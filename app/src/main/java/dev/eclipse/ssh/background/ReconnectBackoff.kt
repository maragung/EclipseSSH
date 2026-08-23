package dev.eclipse.ssh.background

import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
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

/**
 * The reconnect rules for one host, as they stood when it was dialled.
 *
 * A class rather than three loose parameters because all three are read together at one point and are
 * meaningless apart: an attempt ceiling with no backoff is a busy loop, and a backoff with the feature
 * switched off is nothing at all.
 */
internal class ReconnectPolicy(
    val enabled: Boolean,
    val maxAttempts: Int,
    private val hostBackoffSeconds: Int,
) {
    /**
     * The first reconnect wait in seconds, resolving [HostProfile.reconnectBackoffSeconds]'s
     * inherit sentinel against the app-wide delay from Settings.
     */
    fun backoffSeconds(globalSeconds: Int): Int =
        if (hostBackoffSeconds == INHERIT_RECONNECT_BACKOFF) globalSeconds else hostBackoffSeconds

    companion object {
        /** For a session no profile was seen for - a background restore, or an adopted session. */
        val DEFAULT = ReconnectPolicy(
            enabled = true,
            maxAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS,
            hostBackoffSeconds = INHERIT_RECONNECT_BACKOFF,
        )
    }
}

/** [profile]'s reconnect rules, clamped to the ranges the form accepts. */
internal fun reconnectPolicyOf(profile: HostProfile): ReconnectPolicy = ReconnectPolicy(
    enabled = profile.autoReconnect,
    maxAttempts = profile.maxReconnectAttempts
        .coerceIn(MAX_RECONNECT_ATTEMPTS_RANGE.first, MAX_RECONNECT_ATTEMPTS_RANGE.last),
    hostBackoffSeconds = profile.reconnectBackoffSeconds
        .takeIf { it != INHERIT_RECONNECT_BACKOFF }
        ?.coerceIn(RECONNECT_BACKOFF_RANGE.first, RECONNECT_BACKOFF_RANGE.last)
        ?: INHERIT_RECONNECT_BACKOFF,
)
