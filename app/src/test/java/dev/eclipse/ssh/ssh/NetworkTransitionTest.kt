package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the app is allowed to conclude from a network coming, going, or being swapped.
 *
 * These three rules decide whether a user who walked through a tunnel comes back to their shell or to a
 * reconnect, so they are pure functions with the cases written down rather than behaviour to be worked
 * out from a phone in a lift. The costly mistakes are asymmetric and both are pinned here: concluding
 * DROP from weak evidence loses a working session, and concluding RESUME from weak evidence leaves a
 * terminal that looks connected and silently swallows every keystroke.
 */
class NetworkTransitionTest {

    @Test
    fun `an address that survived the outage resumes the session`() {
        // The common case, and the reason the grace period is worth having: Wi-Fi dropped for a few
        // seconds and came back with the same lease, so the socket was never disturbed and there is
        // nothing to reconnect.
        val before = setOf("192.168.1.24", "fe80::1c2b")
        val after = setOf("192.168.1.24", "fe80::1c2b")
        assertThat(graceOutcome(before, after)).isEqualTo(GraceOutcome.RESUME)
    }

    @Test
    fun `one surviving address out of several is enough to be worth resuming`() {
        // Only one address can be the one a session is bound to, and it is not knowable from here which.
        // An overlap means the answer *might* be yes, and the cost of being wrong is a probe.
        assertThat(graceOutcome(setOf("10.0.0.5", "fe80::9"), setOf("10.0.0.5"))).isEqualTo(GraceOutcome.RESUME)
    }

    @Test
    fun `an entirely new address set drops the session`() {
        // Wi-Fi to mobile data. Every socket was bound to an address that has been released; nothing can
        // be sent on them and nothing will arrive.
        val before = setOf("192.168.1.24")
        val after = setOf("10.121.44.9")
        assertThat(graceOutcome(before, after)).isEqualTo(GraceOutcome.DROP)
    }

    @Test
    fun `nothing known from before the outage asks instead of guessing`() {
        // The snapshot was never captured - the process started with no network, or the platform would
        // not answer. Silence about the past is not evidence about the present, and the app has a way to
        // ask, so it asks.
        assertThat(graceOutcome(emptySet(), setOf("10.0.0.5"))).isEqualTo(GraceOutcome.UNKNOWN)
    }

    @Test
    fun `no address yet on the network that just arrived asks instead of guessing`() {
        // An interface can be up and default before DHCP has finished with it. Reading that moment as
        // "every address changed" would drop healthy sessions on every brief Wi-Fi blip.
        assertThat(graceOutcome(setOf("10.0.0.5"), emptySet())).isEqualTo(GraceOutcome.UNKNOWN)
    }

    @Test
    fun `an outage with no addresses on either side is still not a verdict`() {
        assertThat(graceOutcome(emptySet(), emptySet())).isEqualTo(GraceOutcome.UNKNOWN)
    }

    @Test
    fun `a replacement that shares no address needs no probe`() {
        // The fast path. Twelve seconds of deadlines cannot make a released address less released, and
        // those twelve seconds are a terminal that looks fine and does nothing.
        assertThat(migrationProvesLoss(setOf("192.168.1.24"), setOf("10.121.44.9"))).isTrue()
    }

    @Test
    fun `a replacement that keeps an address still has to ask`() {
        // Re-joining the same access point, or a network that hands back the same lease, produces an
        // identical address set with sockets that may nonetheless be dead. Proof runs one way only.
        assertThat(migrationProvesLoss(setOf("192.168.1.24"), setOf("192.168.1.24"))).isFalse()
    }

    @Test
    fun `a replacement the app could not measure has to ask as well`() {
        assertThat(migrationProvesLoss(emptySet(), setOf("10.0.0.5"))).isFalse()
        assertThat(migrationProvesLoss(setOf("10.0.0.5"), emptySet())).isFalse()
    }

    @Test
    fun `the hold is long enough for a tunnel and short enough to notice`() {
        // Pinned deliberately. A shorter hold turns ordinary pocket life into reconnects; a much longer
        // one leaves a user who has genuinely walked away staring at a terminal that has quietly stopped
        // working, with no reconnect running.
        assertThat(NETWORK_GRACE_MS).isEqualTo(60_000L)
    }
}
