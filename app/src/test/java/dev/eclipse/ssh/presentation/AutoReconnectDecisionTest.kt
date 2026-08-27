package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.background.MAX_BACKOFF_MS
import dev.eclipse.ssh.background.ReconnectPolicy
import dev.eclipse.ssh.background.reconnectPolicyOf
import dev.eclipse.ssh.background.backoffWindowMs
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
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
    fun `a session the app dropped because the network went comes back`() {
        // The ending the app concludes itself, rather than one MINA reported. It has to reconnect, and for
        // the strongest reason of any of them: the session was proven to have lost the address it was
        // bound to, so there is nothing to salvage and something to redial.
        assertThat(shouldAutoReconnect(SessionEnd.NetworkLost, tabIsOpen = true, endedDeliberately = false)).isTrue()
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
        SessionEnd.NetworkLost,
        SessionEnd.TransportClosed,
        SessionEnd.Released,
    )

    @Test
    fun `a connection closed before it said anything is not an outage to wait out`() {
        // The loop the user kept reporting, in its remaining form. A server that accepts the transport
        // and then closes it - MaxStartups, DenyUsers, a wrapper that hangs up, a firewall that resets
        // the moment a shell is asked for - produces an ending the ladder cannot tell from a drop, so
        // the app redialled it, got the same refusal, and spent the whole ladder announcing
        // "Reconnecting" over a session that had never once carried a byte.
        assertThat(
            endedBeforeItRan(
                SessionEnd.TransportClosed,
                upForMs = 400,
                idleForMs = null,
            ),
        ).isTrue()
        assertThat(
            endedBeforeItRan(
                SessionEnd.Disconnected(reason = SshConstants.SSH2_DISCONNECT_BY_APPLICATION, message = "bye", byPeer = true),
                upForMs = 900,
                idleForMs = null,
            ),
        ).isTrue()
    }

    @Test
    fun `output arriving even once makes an ending a real drop`() {
        // The veto, and the reason this needs no tolerance on the clock: `idleForMs` is null until the
        // far end has sent something, so a non-null value - any value, including zero - is proof the
        // session ran. A shell that printed its prompt and then lost its socket in the same second is
        // exactly what auto-reconnect is for, and must not be caught by the rule above.
        assertThat(endedBeforeItRan(SessionEnd.TransportClosed, upForMs = 400, idleForMs = 0)).isFalse()
        assertThat(endedBeforeItRan(SessionEnd.TransportClosed, upForMs = 400, idleForMs = 120)).isFalse()
    }

    @Test
    fun `a session that stayed up long enough is a real drop however it ended`() {
        // Past the floor there is nothing to distinguish this from an outage, and guessing would cost a
        // user their reconnect. A silent session is ordinary: an ssh window left open at a prompt sends
        // nothing and receives nothing for hours.
        endings().forEach { end ->
            assertThat(endedBeforeItRan(end, upForMs = NEVER_RAN_MS, idleForMs = null)).isFalse()
            assertThat(endedBeforeItRan(end, upForMs = 3_600_000, idleForMs = null)).isFalse()
        }
    }

    @Test
    fun `an ending with no uptime recorded is left to the ladder`() {
        // A connect that failed before a session existed reports no uptime, and it has its own path -
        // `connect()`'s attempt loop - which must keep it. Absent evidence is not evidence.
        endings().forEach { end ->
            assertThat(endedBeforeItRan(end, upForMs = null, idleForMs = null)).isFalse()
        }
    }

    @Test
    fun `only an ending that could be a refusal is treated as one`() {
        // Narrow on purpose. A transport that *failed* names a fault - a reset, a timeout, a parse
        // error - and a network that went away names an outage; both are worth retrying however fast
        // they arrived, and a phone that connects as it loses Wi-Fi produces the second inside a
        // second. A shell that ended reported an exit, which is already handled. So the rule covers
        // only the two endings that carry no fault at all: a bare close, and a server saying goodbye.
        assertThat(endedBeforeItRan(SessionEnd.TransportFailed(IOException("Connection reset")), 200, null)).isFalse()
        assertThat(endedBeforeItRan(SessionEnd.NetworkLost, 200, null)).isFalse()
        assertThat(endedBeforeItRan(SessionEnd.Released, 200, null)).isFalse()
        assertThat(endedBeforeItRan(SessionEnd.ShellEnded(status = null, signal = null), 200, null)).isFalse()
        assertThat(endedBeforeItRan(SessionEnd.ShellEnded(status = 0, signal = null), 200, null)).isFalse()
    }

    @Test
    fun `a refusal and a reconnectable drop are not the same answer`() {
        // The two predicates run on the same ending, and the whole point is that they disagree about
        // this one: `shouldAutoReconnect` says yes to a bare close - correctly, it cannot see the
        // uptime - and this says the ladder is pointless here. If they ever agreed, the fix would be
        // doing nothing.
        val refused = SessionEnd.TransportClosed
        assertThat(shouldAutoReconnect(refused, tabIsOpen = true, endedDeliberately = false)).isTrue()
        assertThat(endedBeforeItRan(refused, upForMs = 250, idleForMs = null)).isTrue()
    }

    @Test
    fun `a server that hangs up on a brand new session is a refusal not a drop`() {
        // The gap that left "it logs in and then goes straight to Reconnecting" unfixed: a server that
        // prints a banner and closes the channel within the first seconds did not drop - it refused.
        // `endedBeforeItRan` misses this because output arrived, so this predicate catches it, and the
        // tab must land in ERROR with the server's words rather than looping.
        val closedChannel = SessionEnd.TransportClosed
        val byServer = SessionEnd.Disconnected(
            reason = SshConstants.SSH2_DISCONNECT_BY_APPLICATION,
            message = "your session is not responding",
            byPeer = true,
        )
        assertThat(serverRefusedYoungSession(closedChannel, upForMs = 400)).isTrue()
        assertThat(serverRefusedYoungSession(byServer, upForMs = 900)).isTrue()
        // The combined decision the collector actually uses must now refuse it too.
        assertThat(endedBeforeItRan(closedChannel, upForMs = 400, idleForMs = 120) || serverRefusedYoungSession(closedChannel, upForMs = 400)).isTrue()
    }

    @Test
    fun `output does not rescue a server refusal that arrived while the session was young`() {
        // The veto [endedBeforeItRan] applies to a bare close once any output has arrived must not let
        // a genuine server refusal reconnect forever. A banner followed by a channel close in the same
        // second is a refusal; a reset in the same second is a drop. They must be answered differently.
        val bannerThenClose = SessionEnd.TransportClosed
        val bannerThenReset = SessionEnd.TransportFailed(IOException("Connection reset"))
        assertThat(serverRefusedYoungSession(bannerThenClose, upForMs = 500)).isTrue()
        assertThat(serverRefusedYoungSession(bannerThenReset, upForMs = 500)).isFalse()
    }

    @Test
    fun `a young session the server refused is still reconnectable once it has been up a while`() {
        // Past the floor the same ending is an interruption, not a refusal: an idle timeout or an admin
        // hang-up on a session that was in use is exactly what auto-reconnect is for.
        assertThat(serverRefusedYoungSession(SessionEnd.TransportClosed, upForMs = NEVER_RAN_MS)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.TransportClosed, upForMs = 3_600_000)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.Disconnected(SshConstants.SSH2_DISCONNECT_BY_APPLICATION, "bye", byPeer = true), upForMs = 3_600_000)).isFalse()
    }

    @Test
    fun `only a server side hang up counts as a young refusal`() {
        // A protocol/MAC error MINA raised itself (byPeer false) is a fault worth retrying, as are a
        // transport failure, a network loss, a shell that reported an end, and an app release.
        assertThat(serverRefusedYoungSession(SessionEnd.Disconnected(SshConstants.SSH2_DISCONNECT_MAC_ERROR, null, byPeer = false), 200)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.TransportFailed(IOException("Connection reset")), 200)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.NetworkLost, 200)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.Released, 200)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.ShellEnded(status = null, signal = null), 200)).isFalse()
        assertThat(serverRefusedYoungSession(SessionEnd.ShellEnded(status = 0, signal = null), 200)).isFalse()
    }

    @Test
    fun `a young refusal with no uptime recorded is left to the ladder`() {
        // Absent evidence is not evidence: a connect that failed before a session existed reports no
        // uptime and keeps its own path.
        assertThat(serverRefusedYoungSession(SessionEnd.TransportClosed, upForMs = null)).isFalse()
    }

    @Test
    fun `the floor is short enough to mean immediately`() {
        // It has to be too short for a person to have used the session and long enough to cover a
        // handshake, an authentication and a channel open on a slow link. Seconds, not tens of seconds:
        // a genuine drop three seconds into a working session must still get its ladder.
        assertThat(NEVER_RAN_MS).isAtLeast(1_000)
        assertThat(NEVER_RAN_MS).isAtMost(5_000)
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

    /**
     * A host with auto-reconnect switched off is not reconnected, whatever ended it.
     *
     * Checked against the endings that *are* reconnect-worthy, because the switch is only meaningful
     * where the answer would otherwise have been yes.
     */
    @Test
    fun `a host with auto reconnect off is never reconnected`() {
        val faults = listOf(
            SessionEnd.TransportFailed(IOException("reset")),
            SessionEnd.Disconnected(SshConstants.SSH2_DISCONNECT_BY_APPLICATION, "bye", byPeer = true),
            SessionEnd.NetworkLost,
            SessionEnd.TransportClosed,
            SessionEnd.Released,
        )

        for (end in faults) {
            assertThat(
                shouldAutoReconnect(end, tabIsOpen = true, endedDeliberately = false, autoReconnectEnabled = false),
            ).isFalse()
            // The same ending with the switch on is the control: without this the assertion above
            // would pass just as well if the rule had stopped reconnecting anything at all.
            assertThat(
                shouldAutoReconnect(end, tabIsOpen = true, endedDeliberately = false, autoReconnectEnabled = true),
            ).isTrue()
        }
    }

    @Test
    fun `a host policy takes its attempt ceiling and delay from the profile`() {
        val profile = hostProfile().copy(
            autoReconnect = true,
            maxReconnectAttempts = 12,
            reconnectBackoffSeconds = 9,
        )

        val policy = reconnectPolicyOf(profile)

        assertThat(policy.enabled).isTrue()
        assertThat(policy.maxAttempts).isEqualTo(12)
        // The global delay is offered and ignored, because this host stated its own.
        assertThat(policy.backoffSeconds(globalSeconds = 45)).isEqualTo(9)
    }

    /**
     * A host that never set a delay follows the app-wide one, and follows it *live*.
     *
     * The inherit sentinel is the reason this is resolved at scheduling time rather than at dial time:
     * a user who raises the global delay while a tab is open should have the next wait honour it.
     */
    @Test
    fun `a host with no delay of its own inherits the app wide one`() {
        val policy = reconnectPolicyOf(hostProfile().copy(reconnectBackoffSeconds = INHERIT_RECONNECT_BACKOFF))

        assertThat(policy.backoffSeconds(globalSeconds = 45)).isEqualTo(45)
        assertThat(policy.backoffSeconds(globalSeconds = SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS))
            .isEqualTo(SettingsRepository.DEFAULT_RECONNECT_BASE_SECONDS)
    }

    /** A profile carrying an out-of-range number - an old backup, a hand-written import - is clamped. */
    @Test
    fun `a policy clamps values a profile should not have held`() {
        val policy = reconnectPolicyOf(
            hostProfile().copy(maxReconnectAttempts = 9_999, reconnectBackoffSeconds = 9_999),
        )

        assertThat(policy.maxAttempts).isEqualTo(MAX_RECONNECT_ATTEMPTS_RANGE.last)
        assertThat(policy.backoffSeconds(globalSeconds = 5)).isEqualTo(RECONNECT_BACKOFF_RANGE.last)
    }

    /** The fallback for a session no profile was seen for behaves exactly as the app always has. */
    @Test
    fun `the default policy matches the app wide ladder`() {
        assertThat(ReconnectPolicy.DEFAULT.enabled).isTrue()
        assertThat(ReconnectPolicy.DEFAULT.maxAttempts).isEqualTo(MAX_AUTO_RECONNECT_ATTEMPTS)
        assertThat(ReconnectPolicy.DEFAULT.backoffSeconds(globalSeconds = 45)).isEqualTo(45)
    }

    private fun hostProfile() = HostProfile(
        id = "policy-host",
        name = "Policy",
        host = "policy.example.com",
        username = "root",
    )
}
