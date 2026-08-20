package dev.eclipse.ssh.background

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The reconnect loop retries forever while a host stays unreachable, so the wait it computes
 * has to grow, stay bounded, and never overflow — a negative or zero delay would turn the
 * loop into a busy spin that drains the battery and hammers the server.
 */
class ReconnectBackoffTest {

    @Test
    fun `first attempt waits the configured base delay`() {
        assertThat(backoffWindowMs(baseSeconds = 5, attempt = 1)).isEqualTo(5_000L)
    }

    @Test
    fun `each attempt doubles the previous wait`() {
        val waits = (1..4).map { backoffWindowMs(baseSeconds = 2, attempt = it) }

        assertThat(waits).containsExactly(2_000L, 4_000L, 8_000L, 16_000L).inOrder()
    }

    @Test
    fun `growth stops at the ceiling`() {
        val capped = backoffWindowMs(baseSeconds = 60, attempt = 6)

        assertThat(capped).isEqualTo(MAX_BACKOFF_MS)
    }

    @Test
    fun `an outage lasting thousands of attempts never overflows`() {
        // The exponent is clamped, so `shl` cannot wrap into a negative delay.
        listOf(7, 32, 64, 1_000, Int.MAX_VALUE).forEach { attempt ->
            val wait = backoffWindowMs(baseSeconds = 60, attempt = attempt)
            assertThat(wait).isAtLeast(1_000L)
            assertThat(wait).isAtMost(MAX_BACKOFF_MS)
        }
    }

    @Test
    fun `attempt zero or negative still yields a usable delay`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { attempt ->
            assertThat(backoffWindowMs(baseSeconds = 5, attempt = attempt)).isEqualTo(5_000L)
        }
    }

    @Test
    fun `a corrupted base delay is clamped into the supported range`() {
        assertThat(backoffWindowMs(baseSeconds = 0, attempt = 1))
            .isEqualTo(SettingsRepository.MIN_RECONNECT_BASE_SECONDS * 1_000L)
        assertThat(backoffWindowMs(baseSeconds = -900, attempt = 1))
            .isEqualTo(SettingsRepository.MIN_RECONNECT_BASE_SECONDS * 1_000L)
        assertThat(backoffWindowMs(baseSeconds = Int.MAX_VALUE, attempt = 1))
            .isEqualTo(SettingsRepository.MAX_RECONNECT_BASE_SECONDS * 1_000L)
    }

    @Test
    fun `the jittered wait the service derives stays within one and a half windows`() {
        // The service adds Random(0, base/2) on top; assert the arithmetic it relies on.
        val base = backoffWindowMs(baseSeconds = 5, attempt = 3)

        assertThat(base + base / 2).isAtMost(MAX_BACKOFF_MS + MAX_BACKOFF_MS / 2)
        assertThat(base / 2 + 1).isGreaterThan(0L)
    }

    /**
     * The waiting half. All of these run on virtual time, so they assert the schedule the service
     * actually keeps rather than how fast the machine happens to be.
     */
    @Test
    fun `a window nobody interrupts is waited out in full`() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)

        val woken = awaitReconnectWindow(MAX_BACKOFF_MS, wake)

        assertThat(woken).isFalse()
        assertThat(currentTime).isEqualTo(MAX_BACKOFF_MS)
    }

    @Test
    fun `a network that returns mid-window releases the wait at that moment`() = runTest {
        // The case that used to cost up to seven and a half minutes: ConnectivityManager announces
        // a usable network one second in, and the reconnect has to happen then, not at the ceiling.
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch {
            delay(1_000)
            wake.send(Unit)
        }

        val woken = awaitReconnectWindow(MAX_BACKOFF_MS, wake)

        assertThat(woken).isTrue()
        assertThat(currentTime).isEqualTo(1_000)
    }

    @Test
    fun `a signal raised before the wait starts is not lost`() = runTest {
        // Conflated, so onAvailable firing while a connection attempt was still in flight — with
        // nobody receiving — still shortens the backoff that follows it.
        val wake = Channel<Unit>(Channel.CONFLATED)
        wake.trySend(Unit)

        val woken = awaitReconnectWindow(MAX_BACKOFF_MS, wake)

        assertThat(woken).isTrue()
        assertThat(currentTime).isEqualTo(0)
    }

    @Test
    fun `a closed channel still waits the whole window`() = runTest {
        // The anti-spin guard. A closed channel resolves immediately and permanently, so reporting
        // that as "woken" would turn the reconnect loop into an unbroken retry storm.
        val wake = Channel<Unit>(Channel.CONFLATED).apply { close() }

        val woken = awaitReconnectWindow(30_000, wake)

        assertThat(woken).isFalse()
        assertThat(currentTime).isEqualTo(30_000)
    }

    @Test
    fun `a window of zero or less does not wait at all`() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)

        listOf(0L, -1L, Long.MIN_VALUE).forEach { window ->
            assertThat(awaitReconnectWindow(window, wake)).isFalse()
        }
        assertThat(currentTime).isEqualTo(0)
    }
}
