package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isBusy
import dev.eclipse.ssh.data.model.isEnded
import dev.eclipse.ssh.data.model.isLive
import dev.eclipse.ssh.data.model.isPastAuthentication
import dev.eclipse.ssh.statusLine
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
        val notice = retryNotice(SessionConnectionState.CHANNEL_PTY_INITIALIZING, nextAttempt = 2, maxAttempts = 3)
        assertThat(notice).isEqualTo("Logged in · the shell did not open · attempt 2 of 3")
        assertThat(notice).doesNotContain("connection")
    }

    @Test
    fun `a retry before the login succeeded still says connection`() {
        // Everything short of an accepted credential genuinely is the connection, and changing that
        // wording would be a regression of its own.
        assertThat(retryNotice(SessionConnectionState.CONNECTING, 2, 3)).isEqualTo("Retrying connection… · attempt 2 of 3")
        assertThat(retryNotice(SessionConnectionState.AUTHENTICATING, 2, 3)).isEqualTo("Retrying connection… · attempt 2 of 3")
        assertThat(retryNotice(SessionConnectionState.IDLE, 2, 3)).isEqualTo("Retrying connection… · attempt 2 of 3")
    }

    @Test
    fun `every retry says which attempt it is`() {
        // "Retrying" cannot tell a first retry from the last one, and those are different situations: one
        // is worth waiting through, the other is about to become a failure the user has to act on.
        assertThat(retryNotice(SessionConnectionState.CONNECTING, nextAttempt = 3, maxAttempts = 3))
            .endsWith("attempt 3 of 3")
        assertThat(retryNotice(SessionConnectionState.CHANNEL_PTY_INITIALIZING, nextAttempt = 3, maxAttempts = 3))
            .endsWith("attempt 3 of 3")
    }

    @Test
    fun `a connect retry is never labelled a reconnect`() {
        // The single line behind the app's longest-running complaint. RECONNECTING means "a session that
        // was up has dropped" everywhere else, and a first login that failed once has never had a
        // session - so a server that took the password and then refused a pty announced itself with the
        // word for an outage, seconds after Connect was tapped. Whatever phase failed, the wait between
        // two attempts of the same connection is not a reconnect.
        SessionConnectionState.entries.forEach { phase ->
            assertThat(retryPhase(phase)).isNotEqualTo(SessionConnectionState.RECONNECTING)
        }
    }

    @Test
    fun `a retry waits in the phase it is retrying`() {
        // Authenticated already: the next attempt only has to open a shell, and saying "Connecting…"
        // would point the user back at the half that worked.
        assertThat(retryPhase(SessionConnectionState.CHANNEL_PTY_INITIALIZING))
            .isEqualTo(SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        // Not yet authenticated: the next attempt starts by dialling, so that is the honest phase for
        // the wait - including when it was the authentication itself that failed.
        assertThat(retryPhase(SessionConnectionState.AUTHENTICATING)).isEqualTo(SessionConnectionState.CONNECTING)
        assertThat(retryPhase(SessionConnectionState.CONNECTING)).isEqualTo(SessionConnectionState.CONNECTING)
        assertThat(retryPhase(SessionConnectionState.IDLE)).isEqualTo(SessionConnectionState.CONNECTING)
    }

    @Test
    fun `the phase a retry waits in keeps its own notice on screen`() {
        // The two halves have to agree, or the fix is invisible: the retry writes a phase *and* a
        // sentence, and [statusLine] shows a working state's sentence only when there is one. A phase
        // whose status line ignored `lastError` would swallow the notice exactly as compact mode used
        // to swallow the reconnect reason.
        val notice = retryNotice(SessionConnectionState.CHANNEL_PTY_INITIALIZING, 2, 3)
        val phase = retryPhase(SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        assertThat(statusLine(phase, startedAt = null, lastError = notice, compact = true)).isEqualTo(notice)
        assertThat(statusLine(phase, startedAt = null, lastError = notice, compact = false)).isEqualTo(notice)
    }

    @Test
    fun `a working phase with nothing to report still names itself`() {
        // And the other direction: the phase words are what an ordinary first connection shows, so they
        // must survive having no message attached.
        assertThat(statusLine(SessionConnectionState.CONNECTING, null, null, compact = true))
            .isEqualTo("Connecting…")
        assertThat(statusLine(SessionConnectionState.AUTHENTICATING, null, null, compact = true))
            .isEqualTo("Authenticating…")
        assertThat(statusLine(SessionConnectionState.CHANNEL_PTY_INITIALIZING, null, null, compact = true))
            .isEqualTo("Opening shell…")
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
