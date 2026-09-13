package dev.eclipse.ssh.background

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The stop-path state machine of [EclipseSessionService].
 *
 * The transition rules are the arbiter for the start-vs-stop race that had none: ACTION_STOP's
 * cleanup used to run outside the restore mutex, so a restore pass could install a live
 * session *after* the stop's closeAll - authenticated, untracked, invisible until process
 * death. The machine is a class rather than service internals for exactly the reason
 * [SessionRestoreDecisionTest] states: the rules are the part with decisions in them, and a
 * `Service` is close to untestable in a JVM suite.
 */
class SessionServiceStopStateTest {

    @Test
    fun `a fresh service is running and not stopping`() {
        val state = SessionServiceStopState()

        assertThat(state.isStopping).isFalse()
    }

    @Test
    fun `a user stop is accepted exactly once`() {
        val state = SessionServiceStopState()

        assertThat(state.requestUserStop()).isTrue()
        assertThat(state.requestUserStop()).isFalse()
        assertThat(state.isStopping).isTrue()
    }

    @Test
    fun `a user stop cannot be revived by a start intent`() {
        // The user just said "close my sessions"; a session connected moments later is still
        // under that instruction, so the stop stands.
        val state = SessionServiceStopState()
        state.requestUserStop()

        assertThat(state.revive()).isFalse()
        assertThat(state.isStopping).isTrue()
    }

    @Test
    fun `a platform stop is reported as stopping`() {
        val state = SessionServiceStopState()
        state.platformStop()

        assertThat(state.isStopping).isTrue()
    }

    @Test
    fun `a start intent revives a pending platform stop`() {
        // The dataSync timeout set the service going, but the UI connected a session while
        // destruction was still being scheduled - the one event that says the work continues.
        val state = SessionServiceStopState()
        state.platformStop()

        assertThat(state.revive()).isTrue()
        assertThat(state.isStopping).isFalse()
    }

    @Test
    fun `revive does nothing to a running service`() {
        val state = SessionServiceStopState()

        assertThat(state.revive()).isFalse()
        assertThat(state.isStopping).isFalse()
    }

    @Test
    fun `a second revive after the first has no effect`() {
        val state = SessionServiceStopState()
        state.platformStop()
        state.revive()

        assertThat(state.revive()).isFalse()
    }

    @Test
    fun `destroy reports an unannounced destruction of a running service`() {
        // The recycle case: the system took the service with sessions still in the store and
        // no stop path ever ran. This is the state that owes the user an alert.
        val state = SessionServiceStopState()

        assertThat(state.destroyed()).isTrue()
        assertThat(state.isStopping).isTrue()
    }

    @Test
    fun `destroy after a user stop is not an unannounced recycle`() {
        val state = SessionServiceStopState()
        state.requestUserStop()

        assertThat(state.destroyed()).isFalse()
    }

    @Test
    fun `destroy after a platform stop is not an unannounced recycle`() {
        // onTimeout told the user what was happening; onDestroy must not say it again.
        val state = SessionServiceStopState()
        state.platformStop()

        assertThat(state.destroyed()).isFalse()
    }

    @Test
    fun `destroy after a revived platform stop is unannounced again`() {
        // A revive returned the machine to RUNNING, so the destruction that eventually lands
        // is the system's decision, not a stop anyone narrated.
        val state = SessionServiceStopState()
        state.platformStop()
        state.revive()

        assertThat(state.destroyed()).isTrue()
    }

    @Test
    fun `destroy is terminal`() {
        val state = SessionServiceStopState()
        state.destroyed()

        assertThat(state.revive()).isFalse()
        assertThat(state.requestUserStop()).isFalse()
        assertThat(state.isStopping).isTrue()
    }

    @Test
    fun `a destroy after a platform stop is terminal too - revive cannot resurrect it`() {
        // The regression this pins: destroyed() used to mark terminal by reusing the
        // STOPPING_PLATFORM phase, so a start intent racing destruction compared-and-swapped
        // a dead service straight back to RUNNING. Terminal must be a phase of its own.
        val state = SessionServiceStopState()
        state.platformStop()
        state.destroyed()

        assertThat(state.revive()).isFalse()
        assertThat(state.isStopping).isTrue()
    }
}
