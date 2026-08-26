package dev.eclipse.ssh.ssh

import android.util.Log
import dev.eclipse.ssh.data.model.SessionConnectionState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The lifecycle of every SSH session, recorded as structured events that carry no secrets.
 *
 * "It disconnects sometimes" is a bug report nobody can act on, and until this existed the app had no
 * answer to it: there was no logging of any kind, so the only evidence a session ever produced was the
 * one sentence its tab was showing when the user happened to look. What was needed to explain a drop -
 * *which* session, how long it had been up, what the network was doing, whether the heartbeat was even
 * running, which of the six endings it was, how many reconnects had already been spent - existed only
 * as transient state inside a coroutine that had since finished.
 *
 * Four of those answers are derived here rather than asked of the caller, because the callers are
 * deliberately ignorant of each other: [connections] numbers each host's transports so a trace with
 * six sockets in it can be read one socket at a time, and [reconnects] counts redials for the life of
 * the process, which is the number that says a link is flaky rather than that this attempt is the
 * first. The other two - whether the channel was open, and how long since the far end last spoke -
 * come from `TerminalChannel` at the sites that hold one.
 *
 * Two sinks, deliberately:
 *
 *  - [Log] with a single stable tag, so `adb logcat -s EclipseSSH` is a complete session trace on a
 *    developer's machine or a user's device in a bug report;
 *  - an in-memory ring the app can put on screen and export, because the overwhelming majority of
 *    users will never run `adb`, and a diagnostic that needs a computer to read is not available at
 *    the moment the session drops.
 *
 * ## What must never appear here
 *
 * Passwords, passphrases, private keys, tokens, host-key material, the contents of the terminal, and
 * anything typed into it. Nothing in this class formats a credential, and nothing accepts an arbitrary
 * object whose `toString` might contain one: every field is either an enum, a number, a duration, or a
 * short string this app produced itself. Host *identity* is recorded as an opaque per-process session
 * label rather than as `user@host:port`, so a log the user pastes into a public issue does not enumerate
 * their infrastructure or their account names. The mapping from label to host lives only in memory.
 *
 * Exception messages are the one judgement call. They are recorded, because "Connection reset by peer"
 * versus "Auth fail" versus "Timeout" is the whole diagnostic - and they are recorded through
 * [scrub], which drops anything that looks like it came from a credential rather than from a socket.
 */
@Singleton
class SessionDiagnostics @Inject constructor() {

    private val entries = ArrayDeque<SessionDiagnosticEvent>()
    private val sequence = AtomicLong()

    /** Opaque per-process labels for host ids, so nothing here names a real host or account. */
    private val labels = ConcurrentHashMap<String, String>()
    private val labelSequence = AtomicLong()

    /**
     * How many transports each host has been given, so a trace can be read one connection at a time.
     *
     * A host label alone cannot answer the question a reconnect trace exists to answer: with `s1` on
     * forty lines spanning six sockets, "did the pty open on the session that then died, or on the one
     * before it?" is unanswerable, and that is precisely the confusion a connect-disconnect loop
     * produces. Counted here rather than passed in because every caller would otherwise have to thread
     * an id through the dial, the collector and the ladder - three components that deliberately do not
     * know about each other - and one that forgot would silently mislabel a whole connection.
     *
     * Advanced by [SessionEvent.HANDSHAKE], which is exactly once per socket: it is the first thing
     * `SshConnectionManager.connect` reports, on every attempt, including each rung of the ladder.
     * Events recorded before the first handshake - a connect being requested, a reconnect being armed
     * for a session that has just died - carry the number of the transport they belong to, which for a
     * request is 0 and for the aftermath of a drop is the one that dropped.
     */
    private val connections = ConcurrentHashMap<String, AtomicLong>()

    /**
     * How many times each host has been redialled by the ladder, for the life of the process.
     *
     * Deliberately not the same number as [SessionDiagnosticEvent.attempt]. That one is the rung of
     * the ladder currently being climbed, and it is *reset* - by a user asking to connect, and by a
     * session that stayed up long enough to count as stable - which is correct for deciding when to
     * give up and useless for judging a host: a link that reconnects every four minutes all afternoon
     * shows `attempt=1` every single time. This one only ever grows, so `reconnects=37` on a line says
     * what an hour of trace would otherwise have to be counted by hand to learn.
     */
    private val reconnects = ConcurrentHashMap<String, AtomicLong>()

    private val _events = MutableStateFlow<List<SessionDiagnosticEvent>>(emptyList())

    /** The ring, newest last, for the diagnostics screen. */
    val events: StateFlow<List<SessionDiagnosticEvent>> = _events

    /**
     * Records one lifecycle event.
     *
     * Cheap enough to call from anywhere, including a MINA I/O thread: a formatted string, a lock on a
     * small deque, and one `StateFlow` write. Deliberately not suspending - a diagnostic that could be
     * dropped by cancellation would be missing exactly when a session was torn down, which is when it
     * matters most.
     */
    fun record(
        hostId: String,
        event: SessionEvent,
        state: SessionConnectionState? = null,
        detail: String? = null,
        network: String? = null,
        keepAliveSeconds: Int? = null,
        pty: String? = null,
        channel: String? = null,
        idleForMs: Long? = null,
        attempt: Int? = null,
        upForMs: Long? = null,
    ) {
        // Read after the increments so the handshake that opens a transport is the first line carrying
        // that transport's number, and the reconnect attempt that leads to it is the last line carrying
        // the previous one.
        val connection = counter(connections, hostId, advance = event == SessionEvent.HANDSHAKE)
        val reconnected = counter(reconnects, hostId, advance = event == SessionEvent.RECONNECT_ATTEMPT)
        val entry = SessionDiagnosticEvent(
            sequence = sequence.incrementAndGet(),
            atMs = System.currentTimeMillis(),
            session = label(hostId),
            connection = connection,
            event = event,
            state = state,
            detail = detail?.let(::scrub)?.take(MAX_DETAIL),
            network = network,
            keepAliveSeconds = keepAliveSeconds,
            pty = pty,
            channel = channel,
            idleForMs = idleForMs,
            attempt = attempt,
            reconnects = reconnected,
            upForMs = upForMs,
        )
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            _events.value = entries.toList()
        }
        Log.i(TAG, entry.line())
    }

    /**
     * Every host id's opaque label, as a snapshot.
     *
     * The one way a caller holding a host id can find that host's lines in [events], which is what the
     * per-session "Why?" sheet needs: the trace deliberately records [label] rather than the id, so
     * without this the entries for one session could not be picked out of the ring at all. Only the
     * mapping crosses the boundary - the ids stay on this side and the labels remain the opaque
     * ordinals they were designed to be, so nothing that is exported or pasted gains an identifier.
     *
     * A snapshot rather than the live map, because the caller is a UI reading it during composition and
     * [labels] is written from MINA's I/O threads. Grows by one entry per host per process, so copying
     * it is cheaper than the events list it is read alongside.
     */
    val sessionLabels: Map<String, String> get() = labels.toMap()

    /** The whole ring as text, for Save logs and for a bug report. */
    fun export(): String = synchronized(entries) { entries.joinToString("\n") { it.line() } }

    /**
     * Empties the ring, keeping every host's label and connection number.
     *
     * Truncating the trace is not the same as forgetting which session is which. A user clears the log
     * to capture one clean reproduction, and the connection they are about to capture is very often the
     * one already up - so restarting the numbering would print a smaller number for the same live
     * transport, which is the one thing these numbers exist to rule out.
     */
    fun clear() {
        synchronized(entries) {
            entries.clear()
            _events.value = emptyList()
        }
    }

    /**
     * The opaque label for [hostId], stable for the life of the process.
     *
     * A host id is a UUID, so it is not a secret in itself - but it is a stable identifier that ties a
     * pasted log to a row in the user's database, and there is no diagnostic value in it. A short
     * ordinal is as useful for reading a trace and says nothing.
     */
    private fun label(hostId: String): String =
        labels.computeIfAbsent(hostId) { "s${labelSequence.incrementAndGet()}" }

    /**
     * [hostId]'s value in [counters], having advanced it first when [advance].
     *
     * One helper for both counters because both are the same shape - a per-host tally read on every
     * line and advanced by one kind of event - and because the read has to be the same read that
     * follows the increment. Two `record` calls for one host can land on two threads (a MINA I/O
     * thread reporting an ending while the ladder arms a retry), and a get-then-increment would give
     * them the same number.
     */
    private fun counter(counters: ConcurrentHashMap<String, AtomicLong>, hostId: String, advance: Boolean): Long {
        val counter = counters.computeIfAbsent(hostId) { AtomicLong() }
        return if (advance) counter.incrementAndGet() else counter.get()
    }

    /** Module-visible so the trace's two bounds are asserted against the values it actually uses. */
    internal companion object {
        const val TAG = "EclipseSSH"
        const val MAX_ENTRIES = 500
        const val MAX_DETAIL = 200
    }
}

/**
 * Removes anything from [text] that could be credential material rather than diagnosis.
 *
 * The input is an exception message from a network library, so the realistic risk is not a formatted
 * password but the shapes secrets take when they end up somewhere they should not: a PEM block from a
 * key parser, a `key=value` pair naming a secret, a long unbroken token. All three are replaced whole -
 * a redacted message is still useful, a leaked one is not recoverable.
 */
internal fun scrub(text: String): String {
    var out = text
    // PEM and OpenSSH key bodies, whether or not the armour survived.
    out = PEM.replace(out, KEY_MARK)
    // `password=hunter2`, `passphrase: hunter2`, `token => …` and their kin.
    out = SECRET_ASSIGNMENT.replace(out) { match -> "${match.groupValues[1]}$REDACTED_MARK" }
    // A long run of base64-ish characters with no spaces is not a sentence about a socket.
    out = LONG_TOKEN.replace(out, REDACTED_MARK)
    return out
}

/**
 * What a removed key is replaced with, and what the later rules refuse to touch.
 *
 * The rules run over each other's output, so without this a message like `invalid key: <PEM>` would
 * have its own [KEY_MARK] taken for the value of `key:` and replaced again - still redacted, but no
 * longer saying which kind of secret was dropped, which is the only part a reader can use.
 */
private const val KEY_MARK = "«key»"
private const val REDACTED_MARK = "«redacted»"

private val PEM = Regex("-{3,}\\s*BEGIN[\\s\\S]*?END[^-]*-{3,}", RegexOption.IGNORE_CASE)
private val SECRET_ASSIGNMENT = Regex(
    "((?:pass(?:word|phrase)?|secret|token|credential|key)\\s*[:=>]+\\s*)(?!«(?:key|redacted)»)\\S+",
    RegexOption.IGNORE_CASE,
)
private val LONG_TOKEN = Regex("[A-Za-z0-9+/=_-]{40,}")

/** One recorded moment in a session's life. */
data class SessionDiagnosticEvent(
    val sequence: Long,
    val atMs: Long,
    /** The opaque per-process session label - never a hostname, a username or a host id. */
    val session: String,
    /** Which of [session]'s transports this line belongs to; 0 before the first was dialled. */
    val connection: Long,
    val event: SessionEvent,
    val state: SessionConnectionState?,
    val detail: String?,
    val network: String?,
    val keepAliveSeconds: Int?,
    val pty: String?,
    /** Whether the shell channel was open, where the recorder had one to look at. */
    val channel: String?,
    /** Milliseconds since the far end last sent anything, where that was known. */
    val idleForMs: Long?,
    val attempt: Int?,
    /** How many times this host had been redialled by the ladder when this was recorded. */
    val reconnects: Long,
    val upForMs: Long?,
) {
    /**
     * The event as one line of `key=value` fields.
     *
     * A shape a human can scan and `grep` can filter, with absent fields left out entirely rather than
     * printed as `null` - a trace of a hundred sessions is read by eye far more often than parsed.
     */
    fun line(): String = buildString {
        append(atMs)
        append(' ')
        // One token, so a whole connection is one `grep`: the host label with the transport it names.
        append(session)
        append('.')
        append(connection)
        append(' ')
        append(event.name)
        state?.let { append(" state=").append(it.name) }
        attempt?.let { append(" attempt=").append(it) }
        // Only once there is one, so an ordinary first connection is not padded with a zero.
        if (reconnects > 0) append(" reconnects=").append(reconnects)
        network?.let { append(" net=").append(it) }
        keepAliveSeconds?.let { append(" keepalive=").append(it).append('s') }
        pty?.let { append(" pty=").append(it) }
        channel?.let { append(" chan=").append(it) }
        // Seconds, like every other duration on the line. Sub-second is rounded down to 0, which is
        // the answer that matters: output was arriving as this happened.
        idleForMs?.let { append(" idle=").append(it / 1_000).append('s') }
        upForMs?.let { append(" up=").append(it / 1_000).append('s') }
        detail?.let { append(" detail=\"").append(it.replace('"', '\'')).append('"') }
    }
}

/** What happened, as a closed set so a trace can be filtered rather than read. */
enum class SessionEvent {
    /** The user, or a restore pass, asked for a session. */
    CONNECT_REQUESTED,

    /** Dialling: socket, proxy, key exchange. */
    HANDSHAKE,

    /** Transport up, credentials being offered. */
    AUTHENTICATE,

    /** A shell is open and the session is on screen. */
    SHELL_OPEN,

    /** A live session was found and reused instead of dialling a second one. */
    ADOPTED,

    /** One dial attempt failed; the ladder may try again. */
    ATTEMPT_FAILED,

    /** Connecting gave up. */
    CONNECT_FAILED,

    /** The session ended - [SessionDiagnosticEvent.detail] says which of the endings it was. */
    ENDED,

    /** A reconnect was armed, with the delay in [SessionDiagnosticEvent.detail]. */
    RECONNECT_SCHEDULED,

    /** A reconnect attempt is starting now. */
    RECONNECT_ATTEMPT,

    /** The reconnect ladder ran out. */
    RECONNECT_EXHAUSTED,

    /** A reconnect was cancelled because the user took over. */
    RECONNECT_CANCELLED,

    /** The default network changed under a live session. */
    NETWORK_CHANGED,

    /** A liveness probe was sent after a network change, and what it found. */
    LIVENESS_PROBE,

    /** The pty was resized, or its size was restored after a reconnect. */
    PTY_RESIZED,

    /** The user closed the session, or the app did on their behalf. */
    CLOSED_BY_USER,

    /** Output was dropped because nothing drained it - a hole in the transcript. */
    OUTPUT_DROPPED,

    /**
     * The live session's credential could not be kept for a resume, so an outage will need the user.
     *
     * Recorded rather than swallowed: the write goes through the keystore-backed vault, and a key that
     * has become unusable - a device the user re-enrolled a fingerprint on, a wiped keystore - makes
     * every reconnect after a drop fail authentication with nothing in the trace to say why.
     */
    CREDENTIAL_NOT_STORED,
}
