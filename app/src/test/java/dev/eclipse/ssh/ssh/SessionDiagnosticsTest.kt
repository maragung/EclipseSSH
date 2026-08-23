package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.SessionConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one place in this app that writes a log line, held to the promise its KDoc makes: a trace
 * complete enough to explain a dropped session, carrying nothing the user would mind pasting into a
 * public issue.
 *
 * Both halves need a test, and the second one needs it more. A diagnostic log is exactly the artefact
 * users copy into bug reports, screenshots and support chats, so a secret that reaches it has been
 * published rather than merely stored - there is no revoking a paste. The inputs are not hypothetical
 * either: [SessionDiagnostics.record] is called with exception messages from an SSH library, and the
 * things that throw during authentication are the things holding the passphrase, the key body and the
 * token.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDiagnosticsTest {

    private fun diagnostics() = SessionDiagnostics()

    private val hostId = "b7c1f0e2-8a44-4d1b-9f3e-1c2d3e4f5a6b"

    /**
     * A key that reached an exception message does not reach the log.
     *
     * `SshKeyLoader` parses what the user imported, and a malformed key is reported by the library with
     * the material it choked on. That is the single most valuable secret this app holds.
     */
    @Test
    fun `a private key in an exception message is replaced whole`() {
        val key = buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            repeat(6) { append("b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt\n") }
            append("-----END OPENSSH PRIVATE KEY-----")
        }
        val diagnostics = diagnostics()

        diagnostics.record(hostId, SessionEvent.CONNECT_FAILED, detail = "invalid key: $key")

        val trace = diagnostics.export()
        assertThat(trace).doesNotContain("BEGIN OPENSSH PRIVATE KEY")
        assertThat(trace).doesNotContain("b3BlbnNzaC1rZXktdjEA")
        assertThat(trace).contains("«key»")
        // The diagnosis survives the redaction: which event it was, and that a key was the subject.
        assertThat(trace).contains("CONNECT_FAILED")
        assertThat(trace).contains("invalid key")
    }

    @Test
    fun `a password or passphrase named in a message is redacted and the message still reads`() {
        val diagnostics = diagnostics()

        diagnostics.record(hostId, SessionEvent.ATTEMPT_FAILED, detail = "auth failed (password=hunter2-secret)")
        diagnostics.record(hostId, SessionEvent.ATTEMPT_FAILED, detail = "passphrase: correct-horse-battery")
        diagnostics.record(hostId, SessionEvent.ATTEMPT_FAILED, detail = "token => ghp_notarealtokenvalue")

        val trace = diagnostics.export()
        assertThat(trace).doesNotContain("hunter2-secret")
        assertThat(trace).doesNotContain("correct-horse-battery")
        assertThat(trace).doesNotContain("ghp_notarealtokenvalue")
        assertThat(trace).contains("«redacted»")
        // Still a usable trace: three failed attempts, and what each was about.
        assertThat(trace.lines()).hasSize(3)
        assertThat(trace).contains("auth failed")
        assertThat(trace).contains("passphrase:")
    }

    /**
     * A long unbroken token is redacted even when nothing named it.
     *
     * The catch-all for the case the other two rules miss: a base64 blob with no `BEGIN` armour and no
     * `password=` label in front of it. Nothing a network library says about a socket looks like that,
     * so the false-positive cost is low and the false-negative cost is a published credential.
     */
    @Test
    fun `an unlabelled long token is redacted while ordinary messages are left alone`() {
        val diagnostics = diagnostics()
        val token = "AAAAB3NzaC1yc2EAAAADAQABAAABgQDZ9mKp7vLxTqRnH2sWcFbYeJgXuNdA4tPmVzQ"

        diagnostics.record(hostId, SessionEvent.ENDED, detail = "offered $token")
        diagnostics.record(hostId, SessionEvent.ENDED, detail = "Connection reset by peer")
        diagnostics.record(hostId, SessionEvent.ENDED, detail = "Auth fail: no more authentication methods available")

        val trace = diagnostics.export()
        assertThat(trace).doesNotContain(token)
        // The messages that make the log worth keeping pass through untouched - a scrubber that ate
        // them would leave the app with no diagnosis at all, which is where it started.
        assertThat(trace).contains("Connection reset by peer")
        assertThat(trace).contains("Auth fail: no more authentication methods available")
    }

    @Test
    fun `the scrubber leaves a message that only mentions a secret by name`() {
        // "password" as a word, with no value after it, is diagnosis rather than disclosure.
        assertThat(scrub("password authentication is not permitted")).isEqualTo("password authentication is not permitted")
        assertThat(scrub("publickey authentication failed")).isEqualTo("publickey authentication failed")
        assertThat(scrub("timeout after 30s")).isEqualTo("timeout after 30s")
    }

    /**
     * The trace names sessions, not infrastructure.
     *
     * A host id is a UUID rather than a hostname, so it is not a secret - but it ties a pasted log to a
     * row in the user's own database, and it buys the reader nothing that an ordinal does not. What must
     * never appear is the identity: a log that enumerated `user@host:port` for every session would turn
     * a bug report into an inventory of the user's servers and accounts.
     */
    @Test
    fun `no host id reaches the trace and each host keeps one opaque label`() {
        val diagnostics = diagnostics()
        val otherHost = "2f9e8d7c-6b5a-4938-8271-0a1b2c3d4e5f"

        diagnostics.record(hostId, SessionEvent.CONNECT_REQUESTED)
        diagnostics.record(otherHost, SessionEvent.CONNECT_REQUESTED)
        diagnostics.record(hostId, SessionEvent.SHELL_OPEN)

        val trace = diagnostics.export()
        assertThat(trace).doesNotContain(hostId)
        assertThat(trace).doesNotContain(otherHost)

        val events = diagnostics.events.value
        assertThat(events).hasSize(3)
        // Stable per host, so a trace can still be followed session by session...
        assertThat(events[0].session).isEqualTo(events[2].session)
        // ...and distinct between hosts, so two sessions are never read as one.
        assertThat(events[1].session).isNotEqualTo(events[0].session)
    }

    /**
     * The ring is bounded and keeps the newest events.
     *
     * It is held for the life of the process and written from the SSH I/O threads, so an unbounded one
     * is a slow leak in the component whose whole job is to explain long-running sessions. Dropping the
     * *oldest* is the only useful direction: the interesting moment is always the drop that just
     * happened, not the connect twenty minutes before it.
     */
    @Test
    fun `the ring keeps the newest events and drops the oldest`() {
        val diagnostics = diagnostics()
        val total = 600

        repeat(total) { index -> diagnostics.record(hostId, SessionEvent.HANDSHAKE, detail = "attempt-$index") }

        val events = diagnostics.events.value
        assertThat(events).hasSize(500)
        assertThat(events.first().detail).isEqualTo("attempt-${total - 500}")
        assertThat(events.last().detail).isEqualTo("attempt-${total - 1}")
        assertThat(diagnostics.export().lines()).hasSize(500)
    }

    /**
     * A ring of 500 entries stays a ring: one enormous message cannot make the trace unbounded.
     *
     * The detail is prose here on purpose. A 4 000-character run of base64-ish characters is redacted
     * whole by [scrub] before the length ever matters, which is the next test - this one has to reach
     * the truncation to test it.
     */
    @Test
    fun `an oversized detail is truncated instead of being stored whole`() {
        val diagnostics = diagnostics()
        val long = "the server closed the channel while the shell was still draining output, "
            .repeat(60)

        diagnostics.record(hostId, SessionEvent.ENDED, detail = long)

        val detail = diagnostics.events.value.single().detail
        assertThat(detail).hasLength(SessionDiagnostics.MAX_DETAIL)
        // Truncated from the end, so the part a reader needs - what happened - is the part kept.
        assertThat(long).startsWith(detail)
    }

    /**
     * Length is not a way past the redaction.
     *
     * Scrubbing runs before truncation, so a key long enough to overflow the field is replaced whole
     * rather than sliced into a 200-character prefix that is still 200 characters of key.
     */
    @Test
    fun `an oversized token shaped detail is redacted rather than merely shortened`() {
        val diagnostics = diagnostics()

        diagnostics.record(hostId, SessionEvent.ENDED, detail = "AAAAB3NzaC1yc2E".repeat(300))

        val detail = diagnostics.events.value.single().detail
        assertThat(detail).isEqualTo("«redacted»")
        assertThat(detail).doesNotContain("AAAAB3NzaC1yc2E")
    }

    /**
     * A line names the fields that are present and says nothing about the ones that are not.
     *
     * The format is read by eye far more often than parsed, and a trace where two thirds of every line
     * is `null` is one nobody reads. It is also `grep`-able on purpose: `net=`, `attempt=` and
     * `keepalive=` are how a reader finds the network change or the ladder in a 500-line export.
     */
    @Test
    fun `a line carries every field it was given and omits the rest`() {
        val diagnostics = diagnostics()

        diagnostics.record(
            hostId,
            SessionEvent.RECONNECT_ATTEMPT,
            state = SessionConnectionState.RECONNECTING,
            detail = "after network change",
            network = "WIFI",
            keepAliveSeconds = 30,
            pty = "80x24",
            attempt = 2,
            upForMs = 125_000,
        )
        val full = diagnostics.events.value.single().line()

        assertThat(full).contains("RECONNECT_ATTEMPT")
        assertThat(full).contains("state=RECONNECTING")
        assertThat(full).contains("attempt=2")
        assertThat(full).contains("net=WIFI")
        assertThat(full).contains("keepalive=30s")
        assertThat(full).contains("pty=80x24")
        // Seconds, not milliseconds: an uptime is read, not measured.
        assertThat(full).contains("up=125s")
        assertThat(full).contains("detail=\"after network change\"")

        diagnostics.clear()
        diagnostics.record(hostId, SessionEvent.CLOSED_BY_USER)
        val bare = diagnostics.events.value.single().line()

        assertThat(bare).contains("CLOSED_BY_USER")
        assertWithMessage("absent fields must not be printed as null: %s", bare)
            .that(bare.lowercase()).doesNotContain("null")
        assertThat(bare).doesNotContain("attempt=")
        assertThat(bare).doesNotContain("detail=")
    }

    /**
     * A quote inside a detail cannot break the line's own shape.
     *
     * Exception messages quote paths and commands, and a `detail="..."` field that let one through
     * would produce a line that cannot be read back field by field.
     */
    @Test
    fun `a quoted detail cannot break the field the trace puts it in`() {
        val diagnostics = diagnostics()

        diagnostics.record(hostId, SessionEvent.ENDED, detail = """no such file: "/etc/ssh/ssh_host_key"""")

        val line = diagnostics.events.value.single().line()
        assertThat(line.count { it == '"' }).isEqualTo(2)
        assertThat(line).endsWith("\"")
    }

    @Test
    fun `clearing empties both the export and the flow`() {
        val diagnostics = diagnostics()
        diagnostics.record(hostId, SessionEvent.SHELL_OPEN)

        diagnostics.clear()

        assertThat(diagnostics.export()).isEmpty()
        assertThat(diagnostics.events.value).isEmpty()
    }

    /**
     * Recording from several threads at once neither loses the ring's bound nor duplicates a sequence.
     *
     * [SessionDiagnostics.record] is documented as safe to call from a MINA I/O thread, and it is: the
     * heartbeat, the liveness sweep, the service's restore pass and the view model all record from
     * different threads, sometimes about the same session in the same instant. The `synchronized` block
     * and the atomic sequence are the whole implementation of that claim, so this is the test that would
     * fail if either were removed.
     */
    @Test
    fun `records arriving from several threads at once keep the ring consistent`() {
        val diagnostics = diagnostics()
        val threads = 4
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { thread ->
            Thread {
                start.await()
                repeat(perThread) { index ->
                    diagnostics.record("host-$thread", SessionEvent.HANDSHAKE, detail = "$thread-$index")
                }
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        start.countDown()

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()

        val events = diagnostics.events.value
        assertThat(events).hasSize(500)
        // No two entries share a sequence number, which is what makes the export's order and the
        // screen's `key` stable.
        assertThat(events.map { it.sequence }.toSet()).hasSize(events.size)
        assertThat(events.map { it.sequence }.max()).isEqualTo((threads * perThread).toLong())
        // Every line is whole - a ring corrupted by a concurrent write shows up as a null field or a
        // missing event name long before it shows up as a wrong count.
        events.forEach { event ->
            assertThat(event.line()).contains(event.event.name)
            assertThat(event.session).isNotEmpty()
        }
        // Every one of these was a handshake, so every one opened a transport of its own: no two lines
        // may claim the same one. A get-then-increment would hand two threads the same number here.
        assertThat(events.map { it.session to it.connection }.toSet()).hasSize(events.size)
    }

    /**
     * Each of a host's connections is numbered, so a trace with several sockets in it can be read.
     *
     * The question a reconnect trace exists to answer is *which* connection a line belongs to, and a
     * per-host label alone cannot answer it: forty lines saying `s1` across six sockets make "did the
     * pty open on the session that then died, or on the one before it?" unanswerable - which is exactly
     * the shape of the connect-disconnect-reconnect reports this trace was added for.
     */
    @Test
    fun `each connection of a host is numbered so a trace can be read one socket at a time`() {
        val diagnostics = diagnostics()

        // One dial, one drop, one redial - the sequence a flapping host produces.
        diagnostics.record(hostId, SessionEvent.CONNECT_REQUESTED)
        diagnostics.record(hostId, SessionEvent.HANDSHAKE)
        diagnostics.record(hostId, SessionEvent.AUTHENTICATE)
        diagnostics.record(hostId, SessionEvent.SHELL_OPEN)
        diagnostics.record(hostId, SessionEvent.ENDED)
        diagnostics.record(hostId, SessionEvent.RECONNECT_SCHEDULED)
        diagnostics.record(hostId, SessionEvent.RECONNECT_ATTEMPT)
        diagnostics.record(hostId, SessionEvent.HANDSHAKE)
        diagnostics.record(hostId, SessionEvent.SHELL_OPEN)

        val events = diagnostics.events.value
        // A request arrives before any transport exists and says so rather than borrowing the next
        // one's number; everything from the handshake to the ending belongs to the first transport;
        // the reconnect being armed and attempted still belongs to the connection that dropped, since
        // that is what those lines are about; the second handshake starts the second.
        assertThat(events.map { it.connection })
            .containsExactly(0L, 1L, 1L, 1L, 1L, 1L, 1L, 2L, 2L)
            .inOrder()
        // One token in the text, so a whole connection is one `grep`.
        assertThat(events[3].line()).contains("${events[3].session}.1 SHELL_OPEN")
        assertThat(events.last().line()).contains("${events.last().session}.2 SHELL_OPEN")
    }

    @Test
    fun `two hosts number their own connections`() {
        val diagnostics = diagnostics()
        val otherHost = "2f9e8d7c-6b5a-4938-8271-0a1b2c3d4e5f"

        diagnostics.record(hostId, SessionEvent.HANDSHAKE)
        diagnostics.record(otherHost, SessionEvent.HANDSHAKE)
        diagnostics.record(hostId, SessionEvent.HANDSHAKE)

        val events = diagnostics.events.value
        assertThat(events[0].connection).isEqualTo(1L)
        assertThat(events[1].connection).isEqualTo(1L)
        assertThat(events[2].connection).isEqualTo(2L)
        assertThat(events[2].session).isEqualTo(events[0].session)
    }

    /**
     * The reconnect tally keeps growing where the ladder's own rung is reset.
     *
     * These are two different numbers and the trace needs both. `attempt` is the rung being climbed and
     * it is deliberately reset - by a user asking to connect, and by a session that stayed up long
     * enough to count as stable - which is right for deciding when to give up and useless for judging a
     * host: a link that drops every four minutes all afternoon reports `attempt=1` every single time.
     */
    @Test
    fun `the reconnect tally grows where the ladder rung resets`() {
        val diagnostics = diagnostics()

        repeat(3) {
            // Every one of these is the first rung of a fresh ladder, as a stable session's drop is.
            diagnostics.record(hostId, SessionEvent.RECONNECT_ATTEMPT, attempt = 1)
            diagnostics.record(hostId, SessionEvent.HANDSHAKE)
            diagnostics.record(hostId, SessionEvent.SHELL_OPEN)
        }

        val events = diagnostics.events.value
        assertThat(events.map { it.reconnects }).containsExactly(1L, 1L, 1L, 2L, 2L, 2L, 3L, 3L, 3L).inOrder()
        assertThat(events.last().line()).contains("reconnects=3")
        // And the rung it is not.
        assertThat(events.filter { it.attempt != null }.map { it.attempt }).containsExactly(1, 1, 1)
    }

    @Test
    fun `a host that has never been redialled prints no tally`() {
        val diagnostics = diagnostics()

        diagnostics.record(hostId, SessionEvent.HANDSHAKE)

        assertThat(diagnostics.events.value.single().reconnects).isEqualTo(0L)
        assertThat(diagnostics.events.value.single().line()).doesNotContain("reconnects=")
    }

    /**
     * The channel's state and the age of the last output reach the line as fields of their own.
     *
     * Both used to exist only inside prose details on one of the probe's messages, which is where a
     * `grep` cannot reach them and where three quarters of the events that need them never had them.
     * Together they separate the two endings that look identical from the outside: a transport that
     * died under a session which was streaming output a moment earlier, and one whose far end had gone
     * quiet minutes before.
     */
    @Test
    fun `a line carries the channel state and the age of the last output`() {
        val diagnostics = diagnostics()

        diagnostics.record(
            hostId,
            SessionEvent.ENDED,
            state = SessionConnectionState.RECONNECTING,
            channel = "closed",
            idleForMs = 7_400,
            pty = "100x30",
        )
        val line = diagnostics.events.value.single().line()

        assertThat(line).contains("chan=closed")
        // Seconds, like every other duration on the line.
        assertThat(line).contains("idle=7s")
        assertThat(line).contains("pty=100x30")

        diagnostics.clear()
        // Sub-second rounds down to zero, which is the answer that matters: output was arriving as
        // this happened, so whatever ended the session, it was not the far end going quiet.
        diagnostics.record(hostId, SessionEvent.ENDED, channel = "open", idleForMs = 120)
        assertThat(diagnostics.events.value.single().line()).contains("idle=0s")

        diagnostics.clear()
        // And a recorder with no channel to look at prints neither field rather than guessing.
        diagnostics.record(hostId, SessionEvent.ENDED)
        val bare = diagnostics.events.value.single().line()
        assertThat(bare).doesNotContain("chan=")
        assertThat(bare).doesNotContain("idle=")
    }
}
