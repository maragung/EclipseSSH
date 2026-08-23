package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isBusy
import dev.eclipse.ssh.data.model.isEnded
import dev.eclipse.ssh.data.model.isLive
import dev.eclipse.ssh.data.model.isPastAuthentication
import org.junit.Test

/**
 * What the app says about a session between "the password was accepted" and "here is your shell".
 *
 * That gap had no name, and the two consequences of it not having one are the two things this pins: a
 * failure in it borrowed the reconnect ladder's words, and a phase callback arriving late could walk the
 * state machine backwards over it.
 */
class ConnectPhaseReportingTest {

    @Test
    fun `a retry after the login succeeded does not blame the connection`() {
        // The reported bug, in one assertion: authentication worked, the pty did not, and the app used to
        // answer that with "Retrying connection…" - the same sentence the ladder uses after a genuine
        // drop, pointing the user at the one part of the system that had just proved it worked.
        val notice = retryNotice(SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        assertThat(notice).isEqualTo("Logged in · the shell did not open · retrying")
        assertThat(notice).doesNotContain("connection")
    }

    @Test
    fun `a retry before the login succeeded still says connection`() {
        // Everything short of an accepted credential genuinely is the connection, and changing that
        // wording would be a regression of its own.
        assertThat(retryNotice(SessionConnectionState.CONNECTING)).isEqualTo("Retrying connection…")
        assertThat(retryNotice(SessionConnectionState.AUTHENTICATING)).isEqualTo("Retrying connection…")
        assertThat(retryNotice(SessionConnectionState.IDLE)).isEqualTo("Retrying connection…")
    }

    @Test
    fun `the shell phase is one the app is working through, not resting in`() {
        // It drives the amber status colour and the spinner. A busy state that reported itself as idle
        // would leave a tab looking finished while a pty was still being negotiated.
        assertThat(SessionConnectionState.CHANNEL_PTY_INITIALIZING.isBusy).isTrue()
        // And it is emphatically not typeable: there is no pty yet, so nothing may offer a keyboard or
        // accept a keystroke on the strength of this state.
        assertThat(SessionConnectionState.CHANNEL_PTY_INITIALIZING.isLive).isFalse()
        assertThat(SessionConnectionState.CHANNEL_PTY_INITIALIZING.isEnded).isFalse()
    }

    @Test
    fun `a late phase callback cannot walk the machine backwards`() {
        // The guard `onConnectPhase` uses. A login that finishes in milliseconds can have its
        // AUTHENTICATING delivered after the shell phase it led to - the callback crosses threads - and
        // the guard has to cover both states on the far side of authentication, not just the last one.
        assertThat(SessionConnectionState.CHANNEL_PTY_INITIALIZING.isPastAuthentication).isTrue()
        assertThat(SessionConnectionState.CONNECTED.isPastAuthentication).isTrue()

        // Everything before it must stay writable, or the phases stop being reported at all.
        assertThat(SessionConnectionState.CONNECTING.isPastAuthentication).isFalse()
        assertThat(SessionConnectionState.AUTHENTICATING.isPastAuthentication).isFalse()
        assertThat(SessionConnectionState.IDLE.isPastAuthentication).isFalse()
        // And so must the ended states, or a tab that failed could never be dialled again.
        assertThat(SessionConnectionState.RECONNECTING.isPastAuthentication).isFalse()
        assertThat(SessionConnectionState.DISCONNECTED.isPastAuthentication).isFalse()
        assertThat(SessionConnectionState.ERROR.isPastAuthentication).isFalse()
    }

    @Test
    fun `every state is accounted for by exactly one resting or working category`() {
        // A new member of the enum that nobody classified would be silently invisible: not live, not
        // busy, not ended means no colour, no spinner and no Reconnect button. This is the test that
        // fails when the ninth state arrives.
        SessionConnectionState.entries.forEach { state ->
            val categories = listOf(state.isLive, state.isBusy, state.isEnded).count { it }
            assertThat(categories).isAtMost(1)
            if (state != SessionConnectionState.IDLE) {
                assertThat(categories).isEqualTo(1)
            }
        }
    }
}
