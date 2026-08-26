package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.ssh.SessionDiagnosticEvent
import dev.eclipse.ssh.ssh.SessionEvent
import dev.eclipse.ssh.statusLine
import org.junit.Test

/**
 * How a failed session explains itself on its own tab.
 *
 * Two halves, and the app has historically got both wrong in the same direction - the evidence existed
 * and did not reach the screen. [statusLine] computed the reason and then discarded it for the one
 * caller that had a user looking at it, and the trace that would have named the cause was filed under
 * an opaque label with no way to ask which lines belonged to which session. Both are pure functions
 * now, so both are pinned here rather than through a screen.
 */
class SessionTraceOnTheTabTest {

    @Test
    fun `a reconnecting tab keeps its reason in a compact row`() {
        // The regression test for the bug behind three releases of reports that could only say "it keeps
        // reconnecting". `lastError?.takeIf { !compact }` sat on this branch, and the terminal screen -
        // the only caller that asks for compact, and the screen the user is on while a session drops -
        // was therefore the one place in the app that showed the bare word. The reason must survive.
        val reason = "The server disconnected: Timeout, your session not responding"
        val compact = statusLine(
            SessionConnectionState.RECONNECTING,
            startedAt = null,
            lastError = reason,
            compact = true,
        )

        assertThat(compact).contains(reason)
        // And it still says which state it is in, because the row it goes in has no other label.
        assertThat(compact).startsWith("Reconnecting")
    }

    @Test
    fun `the roomy row says the reason without repeating the state`() {
        // The wide caller draws its own state chip beside this text, so prefixing it there would print
        // the word twice. Same information, different surroundings - which is the whole reason `compact`
        // exists, and the reason it must not be a licence to drop the payload.
        val reason = "Connection lost: Connection reset"
        assertThat(statusLine(SessionConnectionState.RECONNECTING, null, reason, compact = false))
            .isEqualTo(reason)
    }

    @Test
    fun `every ended or recovering state keeps its reason when compact`() {
        // RECONNECTING was the only one of the three that dropped it, and that asymmetry is exactly what
        // hid the bug: two of the three worked, so the code read as if all of them did.
        val reason = "Session ended (exit 127)"
        listOf(
            SessionConnectionState.RECONNECTING,
            SessionConnectionState.DISCONNECTED,
            SessionConnectionState.ERROR,
        ).forEach { state ->
            assertThat(statusLine(state, null, reason, compact = true)).contains(reason)
        }
    }

    @Test
    fun `a state with nothing recorded still names itself in both rows`() {
        // The fallbacks are what an ordinary tab shows, and a null reason must not produce an empty row.
        listOf(true, false).forEach { compact ->
            assertThat(statusLine(SessionConnectionState.RECONNECTING, null, null, compact))
                .isEqualTo("Reconnecting…")
            assertThat(statusLine(SessionConnectionState.DISCONNECTED, null, null, compact))
                .isEqualTo("Disconnected")
            assertThat(statusLine(SessionConnectionState.ERROR, null, null, compact))
                .isEqualTo("Connection failed")
            assertThat(statusLine(SessionConnectionState.IDLE, null, null, compact))
                .isEqualTo("Not connected")
        }
    }

    @Test
    fun `a connected session says it is encrypted and when it started`() {
        // Not part of the bug, but the same function, and the compact caller is the terminal's one-line
        // header: there is no room for a timestamp there and no question the timestamp answers while a
        // session is working.
        assertThat(statusLine(SessionConnectionState.CONNECTED, "09:41", null, compact = true))
            .isEqualTo("Connected · encrypted")
        assertThat(statusLine(SessionConnectionState.CONNECTED, "09:41", null, compact = false))
            .isEqualTo("Connected · since 09:41")
        // A session with no start time recorded falls back rather than printing "since null".
        assertThat(statusLine(SessionConnectionState.CONNECTED, null, null, compact = false))
            .isEqualTo("Connected · encrypted")
    }

    @Test
    fun `a live session waiting for the network is never called reconnecting`() {
        // The word means "your session is gone", and a held session is not gone: if the network returns
        // to the same address the shell carries on mid-command.
        listOf(true, false).forEach { compact ->
            val held = statusLine(
                SessionConnectionState.CONNECTED,
                startedAt = "09:41",
                lastError = null,
                compact = compact,
                networkHeld = true,
            )
            assertThat(held).startsWith("Connected")
            assertThat(held).doesNotContain("Reconnecting")
        }
        // And a session that really has dropped is not disguised as a held one just because the network
        // is also down - the flag only speaks for states that are still live.
        assertThat(
            statusLine(SessionConnectionState.RECONNECTING, null, "Connection lost", true, networkHeld = true),
        ).contains("Connection lost")
    }

    @Test
    fun `a session's trace is the lines filed under its own label`() {
        // Two hosts failing at once is the normal case for this app - a tab per host, all reconnecting
        // over the same dead Wi-Fi - and a sheet that mixed them would be worse than no sheet: the user
        // would read another host's ending as this host's cause.
        val events = listOf(
            event(1, "s1", SessionEvent.CONNECT_REQUESTED),
            event(2, "s2", SessionEvent.CONNECT_REQUESTED),
            event(3, "s1", SessionEvent.HANDSHAKE),
            event(4, "s2", SessionEvent.ENDED, detail = "TransportClosed"),
            event(5, "s1", SessionEvent.SHELL_OPEN),
        )

        assertThat(sessionDiagnostics(events, "s1").map { it.sequence }).containsExactly(5L, 3L, 1L)
        assertThat(sessionDiagnostics(events, "s2").map { it.sequence }).containsExactly(4L, 2L)
    }

    @Test
    fun `the newest lines come first`() {
        // The question is "why did this just fail", and its answer is the last few lines. A user opening
        // this from a failed tab must not have to scroll a 500-entry ring to reach them.
        val events = (1..5).map { event(it.toLong(), "s1", SessionEvent.HANDSHAKE) }
        assertThat(sessionDiagnostics(events, "s1").map { it.sequence })
            .containsExactly(5L, 4L, 3L, 2L, 1L)
            .inOrder()
    }

    @Test
    fun `only the most recent lines are kept`() {
        // The ring holds 500 entries and a sheet is not a log viewer. The cap counts from the newest end,
        // which is the only end worth capping from.
        val events = (1..200L).map { event(it, "s1", SessionEvent.HANDSHAKE) }
        val slice = sessionDiagnostics(events, "s1")

        assertThat(slice).hasSize(MAX_SESSION_DIAGNOSTIC_LINES)
        assertThat(slice.first().sequence).isEqualTo(200L)
        assertThat(slice.last().sequence).isEqualTo(200L - MAX_SESSION_DIAGNOSTIC_LINES + 1)
        // Explicit limits are honoured too, so a caller with less room can ask for less.
        assertThat(sessionDiagnostics(events, "s1", limit = 3).map { it.sequence })
            .containsExactly(200L, 199L, 198L)
    }

    @Test
    fun `a host that has never been dialled shows nothing rather than someone else's trace`() {
        // A host with no label has recorded no lines, and the tempting fallback - show the unfiltered
        // ring - is the one behaviour this must never have.
        val events = listOf(event(1, "s1", SessionEvent.ENDED, detail = "TransportFailed"))

        assertThat(sessionDiagnostics(events, label = null)).isEmpty()
        assertThat(sessionDiagnostics(events, label = "s9")).isEmpty()
        assertThat(sessionDiagnostics(emptyList(), label = "s1")).isEmpty()
    }

    @Test
    fun `the cap is large enough to hold a whole failed recovery`() {
        // A connect, an ending, and a five-rung ladder with an attempt and a schedule per rung, plus the
        // exhaustion: the shape of the failure has to be visible without scrolling to find its start.
        assertThat(MAX_SESSION_DIAGNOSTIC_LINES).isAtLeast(13)
    }

    private fun event(
        sequence: Long,
        session: String,
        event: SessionEvent,
        detail: String? = null,
    ) = SessionDiagnosticEvent(
        sequence = sequence,
        atMs = 1_700_000_000_000 + sequence,
        session = session,
        connection = 1,
        event = event,
        state = null,
        detail = detail,
        network = null,
        keepAliveSeconds = null,
        pty = null,
        channel = null,
        idleForMs = null,
        attempt = null,
        reconnects = 0,
        upForMs = null,
    )
}
