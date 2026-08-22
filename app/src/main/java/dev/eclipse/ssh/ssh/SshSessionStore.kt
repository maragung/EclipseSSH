package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.sshd.client.session.ClientSession

/**
 * The one place a live SSH session lives, for as long as the app process does.
 *
 * There used to be two. `MainViewModel` kept its sessions in its own maps and
 * [dev.eclipse.ssh.background.EclipseSessionService] kept a private `restoredSessions` map, and
 * neither could see the other's. The consequence was visible on the most ordinary path in the app:
 * tapping Connect starts the foreground service, the service asks the registry which hosts are
 * active — the host the UI just authenticated is one of them, because [connect][MainViewModel.connect]
 * registers it on success — finds nothing in *its* map, and dials a **second** session to the same
 * account. Two TCP connections, two authentications, two heartbeats, two entries in the server's
 * `MaxSessions` budget and two lines in its auth log, for one tap. Servers with a per-user session
 * limit answered the second dial with a refusal the user then saw as an error on a session that was
 * working, and the UI never adopted the extra session, so it stayed open until the process died.
 *
 * Ownership rules, so that "one session per host" is a property of the app rather than a coincidence:
 *
 *  - whoever is about to dial holds that host's [dialing] gate for the whole attempt, so there is
 *    never a second dial in flight to the same account;
 *  - whoever dials [install]s the result, which keeps a live incumbent and closes the newcomer
 *    instead of the other way round;
 *  - whoever is about to dial asks [isLive] first and adopts what it finds instead;
 *  - a session is closed when the user closes the tab or stops sessions from the notification, not
 *    when a component that happened to open it goes away. An `Activity` being finished is not a
 *    reason to drop a shell the user asked to keep, and the service being recycled is not either.
 *
 * The terminal channels and their scrollback buffers live here for the same reason and with the same
 * lifetime: adopting a session without its pty would mean reconnecting the shell, and adopting a pty
 * without its buffer would mean throwing away the scrollback the user is looking at. Keeping all
 * three together is what lets a rotated, backgrounded or process-restarted UI pick a session back up
 * exactly where it was.
 *
 * Every map is a [ConcurrentHashMap] because the readers and writers are genuinely concurrent: the
 * UI's connect coroutine, the service's restore pass, and Apache MINA's own close listeners all touch
 * these from different threads. The maps are exposed directly — the alternative was a wrapper method
 * per operation for the twenty-odd call sites in the view model, which buys no extra safety over the
 * atomic operations the map already offers.
 */
@Singleton
class SshSessionStore @Inject constructor() {

    /** Authenticated sessions, keyed by [dev.eclipse.ssh.data.model.HostProfile.id]. */
    val sessions = ConcurrentHashMap<String, ClientSession>()

    /** The interactive shell open on each session, where one has been opened. */
    val channels = ConcurrentHashMap<String, TerminalChannel>()

    /** Terminal state per host, kept across a reconnect so scrollback survives it. */
    val buffers = ConcurrentHashMap<String, AnsiTerminalBuffer>()

    /**
     * One dial at a time per host. See [dialing].
     *
     * Never removed. A [Mutex] with nothing waiting on it is two fields and no thread, hosts are
     * counted in tens, and pruning one would need the very lock it is pruning to stay correct.
     */
    private val dialGates = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs [attempt] as the only dial in flight for [hostId].
     *
     * This is the fix for the app's most visible connection bug: a tab that said *Reconnecting…* a
     * few seconds after a successful login, with two authentications in the server's log for one tap.
     *
     * Checking [isLive] before dialling is not enough on its own, because the check and the dial are
     * not atomic and the window between them is a whole SSH handshake. Tapping Connect starts the
     * foreground service, whose restore pass asks which hosts are active and finds one the UI is
     * still authenticating - `isLive` is false, because the UI has not put its session in the store
     * yet - so it dials a second session to the same account. Both then complete, and whichever
     * finishes last used to close the other's *live* session. The survivor's shell saw its transport
     * close, reported it as a drop, and the reconnect ladder started; the reconnect dialled again,
     * and the loop sustained itself.
     *
     * Serialising per host closes the window: the second dialler waits, and by the time it holds the
     * gate the first has installed its session, so its own [isLive] check inside the gate answers
     * truthfully and it adopts instead of dialling. Per host rather than global so connecting to one
     * server is never delayed by a handshake with another. Cancellation-safe - a cancelled attempt
     * releases the gate - and re-entrancy is not required: no dial path takes this gate twice.
     */
    suspend fun <T> dialing(hostId: String, attempt: suspend () -> T): T =
        dialGates.computeIfAbsent(hostId) { Mutex() }.withLock { attempt() }

    /**
     * Like [dialing], but reports [DialAttempt.Busy] instead of waiting when another dialler already
     * holds [hostId]'s gate.
     *
     * For callers that walk a list of hosts, where waiting is both pointless and harmful. Pointless
     * because whatever the current dialler installs is found by the next pass anyway; harmful because
     * the gate is deliberately held for a whole connect ladder - [dev.eclipse.ssh.presentation.MAX_CONNECT_ATTEMPTS]
     * attempts with a per-attempt timeout the user may set as high as five minutes - so one unreachable
     * host would stall every host queued behind it for a quarter of an hour.
     *
     * The distinction has to be in the return type rather than in a null: the thing being dialled is
     * itself nullable, and "nobody could connect" and "somebody else is connecting" call for opposite
     * responses - a backoff in the first case, patience in the second.
     */
    suspend fun <T> tryDialing(hostId: String, attempt: suspend () -> T): DialAttempt<T> {
        val gate = dialGates.computeIfAbsent(hostId) { Mutex() }
        if (!gate.tryLock()) return DialAttempt.Busy
        return try {
            DialAttempt.Ran(attempt())
        } finally {
            gate.unlock()
        }
    }

    /**
     * Publishes [session] as the session for [hostId] without ever closing a live one.
     *
     * Returns the session the app is now using, which is *not* always the one passed in: if a live
     * session is already installed, the newcomer is the redundant one and it is closed. `put` did the
     * opposite - it closed whatever it replaced, which on the duplicate-dial path above meant closing
     * the session the user was typing into.
     *
     * A dead incumbent is replaced, along with its channel: the shell on a closed transport cannot be
     * reused, and leaving it in the map would let [adoptableHostIds] offer it to the next UI.
     */
    fun install(hostId: String, session: ClientSession): ClientSession {
        val incumbent = liveSession(hostId)
        if (incumbent != null && incumbent !== session) {
            runCatching { session.close(false) }
            return incumbent
        }
        sessions.put(hostId, session)?.let { previous ->
            if (previous !== session) {
                channels.remove(hostId)?.let { channel ->
                    channel.markDeliberate()
                    runCatching { channel.close() }
                }
                runCatching { previous.close(false) }
            }
        }
        return session
    }

    /**
     * The live session and open shell for [hostId], for a caller that would otherwise dial one.
     *
     * Both halves or nothing: a session whose pty has gone has nothing to attach a terminal to, and
     * answering with it would produce a tab that shows CONNECTED and never prints anything.
     */
    fun adoptable(hostId: String): Pair<ClientSession, TerminalChannel>? {
        val session = liveSession(hostId) ?: return null
        val channel = channels[hostId] ?: return null
        if (!channel.isOpen) return null
        return session to channel
    }

    /**
     * The session for [hostId] if it is still usable, dropping it if it is not.
     *
     * Both conditions are checked because both have been wrong in practice. A session whose peer went
     * away is `isOpen == false` but stays in the map until something looks, and a session that failed
     * authentication is open without being usable. Pruning on read is what keeps a dead entry from
     * making a host permanently un-reconnectable — the service's restore pass skips hosts it thinks
     * are live, so a stale entry there is indistinguishable from a working session.
     */
    fun liveSession(hostId: String): ClientSession? {
        val session = sessions[hostId] ?: return null
        if (session.isOpen && session.isAuthenticated) return session
        sessions.remove(hostId, session)
        // discard, not close: this is bookkeeping about a session that has already died, and saying the
        // app meant it to end would suppress the reconnect the user is waiting for. See
        // [TerminalChannel.discard].
        channels.remove(hostId)?.let { channel -> runCatching { channel.discard() } }
        return null
    }

    fun isLive(hostId: String): Boolean = liveSession(hostId) != null

    /** Host ids with a usable session, pruning any that have died. */
    fun liveHostIds(): Set<String> = sessions.keys.toList().filterTo(mutableSetOf()) { isLive(it) }

    /**
     * Hosts whose session *and* shell are both still alive, so a UI arriving after a rotation or a
     * process restart can pick them up rather than dial again. A session with no channel — an
     * SFTP-only restore, or one whose pty the remote side closed — is deliberately not adoptable as a
     * terminal: there is nothing to attach a collector to.
     */
    fun adoptableHostIds(): Set<String> =
        liveHostIds().filterTo(mutableSetOf()) { channels[it]?.isOpen == true }

    /**
     * Closes the session and shell for [hostId], keeping its scrollback.
     *
     * Order matters: the channel first, so the shell gets its EOF and the remote side sees a closed
     * pty rather than a vanished transport, then the session. `close(false)` is a graceful close —
     * MINA sends `SSH_MSG_DISCONNECT` and lets the server tidy up.
     */
    fun close(hostId: String) {
        channels.remove(hostId)?.let { channel ->
            // Marked before either close, so the shell's own close listener - which may run on a MINA
            // thread while this call is still in flight - reports the app's decision rather than an
            // outage, and nothing schedules a reconnect to a session the user asked to end.
            channel.markDeliberate()
            runCatching { channel.close() }
        }
        sessions.remove(hostId)?.let { session -> runCatching { session.close(false) } }
    }

    /**
     * Drops the session for [hostId] because it has been *found dead*, without claiming the app meant
     * it to end.
     *
     * The difference from [close] is one line of consequence: [close] marks the channel deliberate,
     * which tells the collector "the app did this" and suppresses the reconnect. Here the opposite is
     * required - a liveness probe has just proved the transport is gone, and what has to happen next is
     * exactly what happens on any other drop: the tab says so and the ladder brings it back.
     *
     * `close(true)` rather than `close(false)`, because there is nobody left to be graceful to. A
     * graceful close writes `SSH_MSG_DISCONNECT` and waits for the write to land, which on a socket
     * bound to an interface that no longer exists means blocking until the kernel gives up.
     */
    fun discard(hostId: String) {
        channels.remove(hostId)?.let { channel -> runCatching { channel.discard() } }
        sessions.remove(hostId)?.let { session -> runCatching { session.close(true) } }
    }

    /** [close], and forget the terminal state too. For a tab the user has closed. */
    fun forget(hostId: String) {
        close(hostId)
        buffers.remove(hostId)
    }

    /** Closes every session. For "Stop sessions", and for the app being torn down deliberately. */
    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }
}

/**
 * The outcome of [SshSessionStore.tryDialing]: either it ran, or someone else was already dialling.
 */
sealed interface DialAttempt<out T> {

    /** [attempt] ran to completion and produced [value] - which may itself be null. */
    data class Ran<out T>(val value: T) : DialAttempt<T>

    /** Another dialler holds the host's gate, so nothing was attempted. */
    data object Busy : DialAttempt<Nothing>
}
