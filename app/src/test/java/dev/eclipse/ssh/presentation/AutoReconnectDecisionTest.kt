package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.background.MAX_BACKOFF_MS
import dev.eclipse.ssh.background.backoffWindowMs
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ssh.SessionEnd
import java.io.IOException
import org.apache.sshd.common.SshConstants
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
    fun `a transport that failed is reconnected`() {
        // The heartbeat giving up, a socket resetting, a packet that would not parse: the session was
        // taken away from a user who was using it, which is the case this feature exists for.
        val failed = SessionEnd.TransportFailed(IOException("Connection reset"))
        assertThat(shouldAutoReconnect(failed, tabIsOpen = true, endedDeliberately = false)).isTrue()
    }

    @Test
    fun `a transport that closed or was tidied up is reconnected`() {
        assertThat(shouldAutoReconnect(SessionEnd.TransportClosed, tabIsOpen = true, endedDeliberately = false)).isTrue()
        // Released is the app taking a dead session out of the registry. The last step was the app's,
        // but the ending was not: the pruner only reaches a channel whose session already failed its
        // liveness check, so treating it as the user's decision would leave a genuinely dropped tab
        // sitting there with no reconnect and no message.
        assertThat(shouldAutoReconnect(SessionEnd.Released, tabIsOpen = true, endedDeliberately = false)).isTrue()
    }

    @Test
    fun `a server that hung up is reconnected and so is a protocol failure`() {
        val byServer = SessionEnd.Disconnected(
            reason = SshConstants.SSH2_DISCONNECT_BY_APPLICATION,
            message = "Timeout, your session not responding.",
            byPeer = true,
        )
        val byUs = SessionEnd.Disconnected(
            reason = SshConstants.SSH2_DISCONNECT_MAC_ERROR,
            message = null,
            byPeer = false,
        )
        assertThat(shouldAutoReconnect(byServer, tabIsOpen = true, endedDeliberately = false)).isTrue()
        assertThat(shouldAutoReconnect(byUs, tabIsOpen = true, endedDeliberately = false)).isTrue()
    }

    @Test
    fun `a shell that exited cleanly is left alone`() {
        // `exit`, `logout` and Ctrl-D all arrive as status 0. Reconnecting here would resurrect a
        // shell the user closed on purpose, which is the reconnect loop this feature must not become.
        val ended = SessionEnd.ShellEnded(status = 0, signal = null)
        assertThat(shouldAutoReconnect(ended, tabIsOpen = true, endedDeliberately = false)).isFalse()
    }

    @Test
    fun `a shell that exited with a failure status is also left alone`() {
        // A non-zero status is still the remote side reporting an end. `sshd` was reachable enough to
        // send it, so nothing was dropped.
        listOf(1, 130, -1).forEach { status ->
            val ended = SessionEnd.ShellEnded(status = status, signal = null)
            assertThat(shouldAutoReconnect(ended, tabIsOpen = true, endedDeliberately = false)).isFalse()
        }
    }

    @Test
    fun `a shell that ended without reporting a status is left alone too`() {
        // The regression this whole type exists for, and what a user saw as "it connects and then goes
        // straight to Reconnecting". A shell can end three ways and only one of them reports a status:
        // killed by a signal it reports a signal, and refused outright - `nologin`, a `ForceCommand`
        // that returns, a `~/.profile` that exits - it reports nothing at all. Both used to arrive as
        // `null`, which the old rule could not tell from a dead socket, so the app dialled the account
        // five more times. Every attempt printed the same parting line and ended the same way.
        val killed = SessionEnd.ShellEnded(status = null, signal = "HUP")
        val silent = SessionEnd.ShellEnded(status = null, signal = null)
        assertThat(shouldAutoReconnect(killed, tabIsOpen = true, endedDeliberately = false)).isFalse()
        assertThat(shouldAutoReconnect(silent, tabIsOpen = true, endedDeliberately = false)).isFalse()
    }

    @Test
    fun `a closed tab is never reconnected however it ended`() {
        // Closing the tab is the user saying they are done with the host. A drop that arrives just
        // afterwards - closing the channel is itself a drop - must not dial it again.
        endings().forEach { end ->
            assertThat(shouldAutoReconnect(end, tabIsOpen = false, endedDeliberately = false)).isFalse()
        }
    }

    @Test
    fun `a close the app asked for is never reconnected, even though it looks exactly like a drop`() {
        // The case no reason code can distinguish, and why this parameter exists. Closing a channel
        // from this side ends it exactly as a dropped transport does - MINA fires the same close and no
        // shell reports anything - so a deliberate close read as an outage, and the reconnect it
        // scheduled dialled a host the app had just decided to stop talking to. That is what put
        // "Reconnecting..." on screen seconds after a successful login: a second dial displaced the
        // first session, closing it counted as a drop, and each round produced the next.
        endings().forEach { end ->
            assertThat(shouldAutoReconnect(end, tabIsOpen = true, endedDeliberately = true)).isFalse()
            assertThat(shouldAutoReconnect(end, tabIsOpen = false, endedDeliberately = true)).isFalse()
        }
    }

    /** Every way a session can end, so the two blanket rules above are tested against all of them. */
    private fun endings(): List<SessionEnd> = listOf(
        SessionEnd.ShellEnded(status = 0, signal = null),
        SessionEnd.ShellEnded(status = 1, signal = null),
        SessionEnd.ShellEnded(status = null, signal = "TERM"),
        SessionEnd.ShellEnded(status = null, signal = null),
        SessionEnd.Disconnected(reason = SshConstants.SSH2_DISCONNECT_BY_APPLICATION, message = "bye", byPeer = true),
        SessionEnd.TransportFailed(IOException("Broken pipe")),
        SessionEnd.TransportClosed,
        SessionEnd.Released,
    )

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
