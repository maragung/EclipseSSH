package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules that decide whether the liveness probe is allowed to believe its own silence.
 *
 * [SessionStabilityTest] proves the probe finds a black-holed socket in seconds against a real server
 * and a freezable relay; what it cannot show cheaply is the boundary, because arranging for a byte to
 * land in the middle of two six-second deadlines is a race, not a test. The boundary is where the
 * expensive mistake lives - killing a session that was working - so the comparison itself is a pure
 * function and is pinned here.
 */
class LivenessEvidenceTest {

    private val probeStartedAt = 1_700_000_000_000L

    @Test
    fun `bytes that arrive while the probes are timing out keep the session`() {
        // The case this rule exists for: a shell streaming a build log, whose answer to a global request
        // is queued behind the output proving it does not need to answer.
        assertThat(probeContradicted(probeStartedAt + 3_000L, probeStartedAt)).isTrue()
    }

    @Test
    fun `bytes that arrived before the probe began settle nothing`() {
        // The reason this is not a "recent activity" window. A socket that has just been black-holed by
        // a handover *always* has recent output - it was delivering normally until a moment ago - so any
        // rule that spares a session for having spoken recently spares precisely the dead ones. A sweep
        // fires on a network replacement and is never retried, so sparing it here loses it for good.
        assertThat(probeContradicted(probeStartedAt - 500L, probeStartedAt)).isFalse()
        assertThat(probeContradicted(probeStartedAt - 90_000L, probeStartedAt)).isFalse()
    }

    @Test
    fun `a tie counts as during the probe`() {
        // The stamp is written by MINA's pump thread and the probe start by the sweep; inside one
        // millisecond their order is not knowable. An unknowable order resolves towards keeping a
        // session, because the two outcomes are not equally bad.
        assertThat(probeContradicted(probeStartedAt, probeStartedAt)).isTrue()
    }

    @Test
    fun `a session that has never produced output contradicts nothing`() {
        // No evidence is not evidence of life: a channel that opened and said nothing is judged on the
        // probe alone, as it must be, and a young session is not exempt from being asked. It answers if
        // it is alive - any reply counts - so passing is on its own merits.
        assertThat(probeContradicted(0L, probeStartedAt)).isFalse()
    }

    @Test
    fun `the drop trace says how stale the last output was`() {
        assertThat(lastOutputAgeText(probeStartedAt - 47_000L, probeStartedAt - 600_000L, probeStartedAt))
            .isEqualTo("last output 47s ago")
    }

    @Test
    fun `a channel that never spoke is timed from when it opened`() {
        // "no output yet" on a channel open for ten minutes reads as a young session; the age is what
        // distinguishes a link that went away from this app getting it wrong.
        assertThat(lastOutputAgeText(0L, probeStartedAt - 600_000L, probeStartedAt))
            .isEqualTo("no output in 600s")
        assertThat(lastOutputAgeText(0L, 0L, probeStartedAt)).isEqualTo("no output yet")
    }

    @Test
    fun `a clock that moved backwards reads as now rather than as the future`() {
        // System.currentTimeMillis is wall clock: NTP, a timezone database update or a user setting the
        // date can move it under a running session. A negative age must not reach a trace.
        assertThat(lastOutputAgeText(probeStartedAt + 5_000L, 0L, probeStartedAt)).isEqualTo("last output 0s ago")
    }
}
