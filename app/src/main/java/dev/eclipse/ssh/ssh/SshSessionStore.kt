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
     * The live session for [hostId] that has no shell on it, if there is one.
     *
     * Not every session in the store carries a pty. The background service dials transport-only
     * sessions when it restores a host (a transfer to resume, a tracked host after process death),
     * and so does a resumed SFTP transfer; both install a session and open no channel. Such a
     * session is the one the app should keep — closing it would drop a transfer in flight — but the
     * terminal needs a shell on it, so [adoptable] deliberately rejects it.
     *
     * That rejection used to be a dead end: the terminal could neither adopt the session nor replace
     * it (see [install], which keeps the incumbent), so every attempt authenticated again and the tab
     * ended in an error the user saw as connect → disconnect → reconnecting. This is the missing half
     * of the answer — the caller opens a shell on the session that is already there instead of
     * dialling a second one.
     *
     * A stale closed channel is dropped on the way out, so a host whose shell died but whose
     * transport survived is offered here rather than being stuck behind a channel nobody can use.
     */
    fun sessionAwaitingShell(hostId: String): ClientSession? {
        val session = liveSession(hostId) ?: return null
        val channel = channels[hostId]
        if (channel != null) {
            if (channel.isOpen) return null
            // Dead, so it is bookkeeping and not a decision: the channel's own close future has
            // already published why it ended, and [TerminalChannel.finish] is first-completion-wins, so
            // discarding here reports nothing. `close` would be a claim - that the app meant this - on
            // an ending the app had no part in.
            channels.remove(hostId, channel)
            runCatching { channel.discard() }
        }
        return session
    }

    /**
     * The session for [hostId] if it is still usable. **A pure read: it changes nothing.**
     *
     * Both conditions are checked because both have been wrong in practice. A session whose peer went
     * away is `isOpen == false` but stays in the map until something looks, and a session that failed
     * authentication is open without being usable.
     *
     * This used to prune as it read - removing the session and calling `discard()` on its channel the
     * first time either flag looked wrong - and that was the mechanism behind the oldest complaint
     * about this app: **connect, see the banner, watch the tab flip to *Reconnecting…***. The chain was
     * short and entirely internal. `discard()` deliberately does not mark a channel deliberate, so the
     * collector reported [SessionEnd.Released]; `Released` is reconnect-worthy; and this function is
     * reached from `isLive`, `liveHostIds`, `adoptableHostIds`, `adoptable`, `sessionAwaitingShell`,
     * `install` and the liveness sweep - which is to say from the foreground service's restore pass and
     * from every network event, on their own threads, concurrently with a login that had just
     * succeeded. One transient `false` from a session MINA was still settling was therefore enough to
     * kill a working shell **with no transport event anywhere behind it**, and the ladder would
     * dutifully reconnect what had never actually broken.
     *
     * So: readers read. The authority on whether a transport died is the transport - MINA reports it
     * through [TerminalChannel]'s session listener, which names a reason - and the authority on
     * removing a corpse is whoever proved it was one, through [discard]. A dead entry left in the map
     * costs nothing: it reads as not-live, so the restore pass dials, and [install] replaces it.
     */
    fun liveSession(hostId: String): ClientSession? =
        sessions[hostId]?.takeIf { it.isOpen && it.isAuthenticated }

    fun isLive(hostId: String): Boolean = liveSession(hostId) != null

    /** Host ids with a usable session. Reads only; the dead are removed by [reap] and [discard]. */
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
     *
     * [reason] names *how* it was found dead, for callers that know more than "it stopped answering" -
     * a lost network, say. Passed to [TerminalChannel.discard], which records it only if the transport
     * has not already reported something first-hand.
     */
    fun discard(hostId: String, reason: SessionEnd? = null) {
        channels.remove(hostId)?.let { channel -> runCatching { channel.discard(reason) } }
        sessions.remove(hostId)?.let { session -> runCatching { session.close(true) } }
    }

    /**
     * Removes an entry whose session is finished **and** whose channel has already said so.
     *
     * The counterpart to [liveSession] becoming a pure read. Pruning used to happen wherever anyone
     * asked a question, which is what killed working sessions; taking it out left the opposite, smaller
     * problem - a session that dies with no redial behind it (auto-reconnect off, or the ladder
     * exhausted) stays in the map until the process ends. Nothing keeps a socket or a thread alive by
     * then, because MINA released those when the transport failed, but a map that disagrees with reality
     * is how the last round of bugs started and it is not worth keeping for the sake of one field.
     *
     * Three conditions, all of them required, and each ruling out one way this could repeat the bug it
     * replaces:
     *
     *  - **The session is not live.** A shell can end while its transport is perfectly healthy - `exit`,
     *    or a pty the server closed - and that transport may still be carrying an SFTP transfer. An
     *    ending on the channel is not evidence about the session underneath it.
     *  - **The channel has already reported its ending** ([TerminalChannel.hasEnded]). This is what
     *    makes the removal unobservable: [TerminalChannel.finish] is first-completion-wins, so a release
     *    after the fact cannot publish [SessionEnd.Released] over the real reason. Asking `isOpen`
     *    instead would leave the window between a channel shutting and its close future naming why.
     *  - **Nothing has replaced either entry.** Both removals compare-and-remove, so a dial that
     *    installed a live session while this was deciding keeps it.
     *
     * Returns whether anything was removed, which the caller uses only for the trace: reaping nothing is
     * the ordinary outcome and not a failure.
     */
    fun reap(hostId: String): Boolean {
        val session = sessions[hostId] ?: return false
        if (session.isOpen && session.isAuthenticated) return false
        val channel = channels[hostId]
        if (channel != null && !channel.hasEnded) return false
        if (channel != null && channels.remove(hostId, channel)) runCatching { channel.discard() }
        if (!sessions.remove(hostId, session)) return false
        // Immediate: a graceful close writes SSH_MSG_DISCONNECT and waits for it, and this session has
        // already gone. Ordinarily a no-op, since MINA closed it itself; kept because "not live" also
        // covers a session that is open and unauthenticated, which nothing else will ever close.
        runCatching { session.close(true) }
        return true
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
