package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.background.MAX_BACKOFF_MS
import dev.eclipse.ssh.background.backoffWindowMs
import dev.eclipse.ssh.data.settings.SettingsRepository
import org.junit.Test

/**
 * The rule that decides whether a session comes back by itself, and the shape of the ladder it
 * climbs.
 *
 * Both halves are awkward to observe in a running app - a genuine transport drop needs a network to
 * take away, and the full ladder takes ten minutes of wall clock - so the decision and the wait are
 * pure functions and are tested here directly.
 */
class AutoReconnectDecisionTest {

    @Test
    fun `a transport that died without a status is reconnected`() {
        assertThat(shouldAutoReconnect(exitStatus = null, tabIsOpen = true, endedDeliberately = false)).isTrue()
    }

    @Test
    fun `a shell that exited cleanly is left alone`() {
        // `exit`, `logout` and Ctrl-D all arrive as status 0. Reconnecting here would resurrect a
        // shell the user closed on purpose, which is the reconnect loop this feature must not become.
        assertThat(shouldAutoReconnect(exitStatus = 0, tabIsOpen = true, endedDeliberately = false)).isFalse()
    }

    @Test
    fun `a shell that exited with a failure status is also left alone`() {
        // A non-zero status is still the remote side reporting an end. `sshd` was reachable enough to
        // send it, so nothing was dropped.
        assertThat(shouldAutoReconnect(exitStatus = 1, tabIsOpen = true, endedDeliberately = false)).isFalse()
        assertThat(shouldAutoReconnect(exitStatus = 130, tabIsOpen = true, endedDeliberately = false)).isFalse()
        assertThat(shouldAutoReconnect(exitStatus = -1, tabIsOpen = true, endedDeliberately = false)).isFalse()
    }

    @Test
    fun `a closed tab is never reconnected whatever the status`() {
        // Closing the tab is the user saying they are done with the host. A drop that arrives just
        // afterwards - closing the channel is itself a drop - must not dial it again.
        assertThat(shouldAutoReconnect(exitStatus = null, tabIsOpen = false, endedDeliberately = false)).isFalse()
        assertThat(shouldAutoReconnect(exitStatus = 0, tabIsOpen = false, endedDeliberately = false)).isFalse()
    }

    @Test
    fun `a close the app asked for is never reconnected, even though it looks exactly like a drop`() {
        // The one case the status cannot distinguish, and the reason this parameter exists. Closing a
        // channel from this side ends it with no exit status at all - byte for byte what a dropped
        // transport looks like - so a deliberate close read as an outage, and the reconnect it
        // scheduled dialled a host the app had just decided to stop talking to. That is what put
        // "Reconnecting..." on screen seconds after a successful login: a second dial displaced the
        // first session, closing it counted as a drop, and each round produced the next.
        assertThat(shouldAutoReconnect(exitStatus = null, tabIsOpen = true, endedDeliberately = true)).isFalse()
        assertThat(shouldAutoReconnect(exitStatus = 0, tabIsOpen = true, endedDeliberately = true)).isFalse()
        assertThat(shouldAutoReconnect(exitStatus = null, tabIsOpen = false, endedDeliberately = true)).isFalse()
    }

    @Test
    fun `the ladder grows and stays inside the ceiling for every allowed attempt`() {
        val base = SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS
        val windows = (1..MAX_AUTO_RECONNECT_ATTEMPTS).map { backoffWindowMs(base, it) }

        assertThat(windows.first()).isEqualTo(base * 1_000L)
        windows.zipWithNext().forEach { (earlier, later) ->
            assertThat(later).isGreaterThan(earlier)
        }
        windows.forEach { assertThat(it).isAtMost(MAX_BACKOFF_MS) }
    }

    @Test
    fun `the whole allowed ladder is minutes not hours`() {
        // The bound that matters to a phone in a pocket: five attempts must not add up to an
        // afternoon of radio wake-ups, and must still be long enough to outlast a server reboot.
        // Jitter adds at most half a window on top of each, so the worst case is 1.5x the sum.
        val base = SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS
        val worstCaseMs = (1..MAX_AUTO_RECONNECT_ATTEMPTS).sumOf { backoffWindowMs(base, it) } * 3 / 2

        assertThat(worstCaseMs).isAtLeast(2 * 60_000L)
        assertThat(worstCaseMs).isAtMost(20 * 60_000L)
    }

    @Test
    fun `the ceiling holds even if a host is configured with the longest allowed base`() {
        val window = backoffWindowMs(SettingsRepository.MAX_RECONNECT_BASE_SECONDS, MAX_AUTO_RECONNECT_ATTEMPTS)
        assertThat(window).isAtMost(MAX_BACKOFF_MS)
    }
}
