package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * Ownership rules, so that sessions are a property of the app rather than a coincidence:
 *
 *  - whoever is about to dial holds that session key's [dialing] gate for the whole attempt, so there
 *    is never a second dial in flight behind the same key;
 *  - whoever dials [install]s the result under a session key, which keeps a live incumbent *under
 *    that key* and closes the newcomer instead of the other way round. A key never closes another
 *    key's session — see [install] for why that property is the one the multi-terminal feature
 *    stands on;
 *  - a session key is one of two things: a host id, while only the background service knows about
 *    the session (its restore slot), or a terminal tab's session id, once the UI owns it. [rekey] is
 *    the only way a slot moves between them;
 *  - a session is closed when the user closes the tab or stops sessions from the notification, not
 *    when a component that happened to open it goes away. An `Activity` being finished is not a
 *    reason to drop a shell the user asked to keep, and the service being recycled is not either.
 *
 * The host-scoped questions — which hosts have anything live, which of a host's sessions a UI should
 * attach to, whether this was a host's last session — are answered through [hostOf] rather than by
 * assuming the key *is* the host id, because with more than one terminal per host it no longer is.
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

    /**
     * Authenticated sessions, keyed by session key — a host id for a session only the service knows
     * about, or a terminal tab's session id once the UI owns it. See the class doc for how a key
     * moves between the two.
     */
    val sessions = ConcurrentHashMap<String, ClientSession>()

    /** The interactive shell open on each session, where one has been opened. */
    val channels = ConcurrentHashMap<String, TerminalChannel>()

    /** Terminal state per session, kept across a reconnect so scrollback survives it. */
    val buffers = ConcurrentHashMap<String, AnsiTerminalBuffer>()

    /**
     * Which host each session key belongs to, so host-scoped questions can be answered without
     * assuming the key is the host id.
     *
     * Written by [install], moved by [rekey], dropped by [close], [discard] and [reap] alongside the
     * entry it describes. Exposed like the other maps — the callers that need it read it under the
     * same atomicity rules, and a wrapper per shape would buy nothing.
     */
    val hostOf = ConcurrentHashMap<String, String>()

    /**
     * One dial at a time per session key. See [dialing].
     *
     * Never removed. A [Mutex] with nothing waiting on it is two fields and no thread, keys are
     * counted in tens, and pruning one would need the very lock it is pruning to stay correct.
     */
    private val dialGates = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs [attempt] as the only dial in flight for [sessionKey].
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
     * Serialising closes the window: the second dialler waits, and by the time it holds the gate the
     * first has installed its session, so its own [isLive] check inside the gate answers truthfully
     * and it adopts instead of dialling. Per session key rather than global so connecting to one
     * server is never delayed by a handshake with another - and so that a second terminal to the
     * same host, which is a *feature* and not a race, is not queued behind the first one's gate.
     * Cancellation-safe - a cancelled attempt releases the gate - and re-entrancy is not required:
     * no dial path takes this gate twice.
     */
    suspend fun <T> dialing(sessionKey: String, attempt: suspend () -> T): T =
        dialGates.computeIfAbsent(sessionKey) { Mutex() }.withLock { attempt() }

    /**
     * Like [dialing], but reports [DialAttempt.Busy] instead of waiting when another dialler already
     * holds [sessionKey]'s gate.
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
    suspend fun <T> tryDialing(sessionKey: String, attempt: suspend () -> T): DialAttempt<T> {
        val gate = dialGates.computeIfAbsent(sessionKey) { Mutex() }
        if (!gate.tryLock()) return DialAttempt.Busy
        return try {
            DialAttempt.Ran(attempt())
        } finally {
            gate.unlock()
        }
    }

    /**
     * Publishes [session] as the session for [sessionKey] without ever closing a live one.
     *
     * Returns the session the app is now using, which is *not* always the one passed in: if a live
     * session is already installed under this key, the newcomer is the redundant one and it is
     * closed. `put` did the opposite - it closed whatever it replaced, which on the duplicate-dial
     * path above meant closing the session the user was typing into.
     *
     * **The incumbent test is per key, and that is load-bearing.** With more than one terminal per
     * host, two keys can hold two live sessions to the same account; a check that looked at the
     * *host's* sessions instead of this key's would close one of the user's terminals on the
     * grounds that another one exists. The race this used to have - two diallers, the survivor
     * killing the user's shell - becomes, under a fresh key, one extra live session nobody claims,
     * which is the failure mode the multi-terminal feature can tolerate and clean up in [reap].
     *
     * A dead incumbent is replaced, along with its channel: the shell on a closed transport cannot be
     * reused, and leaving it in the map would let [adoptableHostIds] offer it to the next UI.
     *
     * [hostId] is explicit rather than derived from the key because the two stop being the same
     * string the moment a terminal tab owns the session: the key is then the tab's session id and
     * the host is what [hostOf] needs filed under it.
     */
    fun install(sessionKey: String, session: ClientSession, hostId: String): ClientSession {
        val incumbent = liveSession(sessionKey)
        if (incumbent != null && incumbent !== session) {
            runCatching { session.close(false) }
            return incumbent
        }
        sessions.put(sessionKey, session)?.let { previous ->
            if (previous !== session) {
                channels.remove(sessionKey)?.let { channel ->
                    channel.markDeliberate()
                    runCatching { channel.close() }
                }
                runCatching { previous.close(false) }
            }
        }
        hostOf[sessionKey] = hostId
        return session
    }

    /**
     * The live session and open shell for [sessionKey], for a caller that would otherwise dial one.
     *
     * Both halves or nothing: a session whose pty has gone has nothing to attach a terminal to, and
     * answering with it would produce a tab that shows CONNECTED and never prints anything.
     */
    fun adoptable(sessionKey: String): Pair<ClientSession, TerminalChannel>? {
        val session = liveSession(sessionKey) ?: return null
        val channel = channels[sessionKey] ?: return null
        if (!channel.isOpen) return null
        return session to channel
    }

    /**
     * The live session for [sessionKey] that has no shell on it, if there is one.
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
     * A stale closed channel is dropped on the way out, so a session whose shell died but whose
     * transport survived is offered here rather than being stuck behind a channel nobody can use.
     */
    fun sessionAwaitingShell(sessionKey: String): ClientSession? {
        val session = liveSession(sessionKey) ?: return null
        val channel = channels[sessionKey]
        if (channel != null) {
            if (channel.isOpen) return null
            // Dead, so it is bookkeeping and not a decision: the channel's own close future has
            // already published why it ended, and [TerminalChannel.finish] is first-completion-wins, so
            // discarding here reports nothing. `close` would be a claim - that the app meant this - on
            // an ending the app had no part in.
            channels.remove(sessionKey, channel)
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
    fun liveSession(sessionKey: String): ClientSession? =
        sessions[sessionKey]?.takeIf { it.isOpen && it.isAuthenticated }

    fun isLive(sessionKey: String): Boolean = liveSession(sessionKey) != null

    /**
     * Host ids with a usable session. Reads only; the dead are removed by [reap] and [discard].
     *
     * The answer is [hostOf] rather than the keys, because a session owned by a terminal tab is filed
     * under the tab's session id and not under the host's — the key set stopped being the host id set
     * the moment one host could hold more than one terminal.
     */
    fun liveHostIds(): Set<String> =
        sessions.keys.toList().filter { isLive(it) }.mapNotNullTo(mutableSetOf()) { hostOf[it] }

    /**
     * Hosts whose session *and* shell are both still alive, so a UI arriving after a rotation or a
     * process restart can pick them up rather than dial again. A session with no channel — an
     * SFTP-only restore, or one whose pty the remote side closed — is deliberately not adoptable as a
     * terminal: there is nothing to attach a collector to.
     */
    fun adoptableHostIds(): Set<String> =
        sessions.keys.toList()
            .filter { isLive(it) && channels[it]?.isOpen == true }
            .mapNotNullTo(mutableSetOf()) { hostOf[it] }

    /**
     * The session keys [hostId] still has a live session under.
     *
     * The host-scoped half of [isLive]: the service's restore pass and the SFTP client both ask about
     * a *host*, and with more than one terminal per host the answer has to consider every key filed
     * under it, not just the one that happens to be named after it.
     */
    fun liveForHost(hostId: String): Set<String> =
        sessions.keys.toList().filterTo(mutableSetOf()) { hostOf[it] == hostId && isLive(it) }

    /**
     * Every key still filed under [hostId], live or not.
     *
     * Live-ness is the wrong question for the caller that needs this: closing a tab wants to know
     * whether it is taking the host's *last* session with it, and a sibling entry that has died but
     * not yet been reaped still means another terminal may come back for it — err towards keeping
     * the host-scoped state, because the reverse (dropping a live host's registry entry and
     * command history) is the one that loses the user something.
     */
    fun sessionKeysForHost(hostId: String): Set<String> =
        sessions.keys.toList().filterTo(mutableSetOf()) { hostOf[it] == hostId }

    /**
     * The session key a UI attaching to [hostId] should use, if the host has anything to offer.
     *
     * Prefers a session a terminal can adopt whole — live *and* with its shell still open, scrollback
     * and all — because that is the case where attaching costs nothing. Falls back to the first live
     * session, which is a transport the caller can open a shell on ([sessionAwaitingShell]); a host
     * with only dead entries answers `null`, and the caller dials.
     */
    fun primarySessionFor(hostId: String): String? {
        val keys = sessionKeysForHost(hostId)
        return keys.firstOrNull { adoptable(it) != null } ?: keys.firstOrNull { isLive(it) }
    }

    /**
     * Moves everything filed under [oldKey] to [newKey]: the session, its channel, its buffer, and
     * the host the index says it belongs to.
     *
     * This is how a session the background service restored under its host-id slot becomes a
     * terminal tab's session: the UI arrives, finds a live session with no tab, and re-keys it rather
     * than dialling a twin. The one non-negotiable rule is that [newKey] must be **fresh** — a key
     * that already holds anything is refused, because moving an established session out from under
     * its own tab is exactly the "shell died" bug the per-key rules exist to prevent.
     *
     * Synchronous and on the calling thread, deliberately: the caller is about to file the session's
     * first channel or buffer under the new key, and a re-key that completed on another thread would
     * let that write land under a key that had not moved yet.
     *
     * Returns whether anything moved. `false` covers both an empty [oldKey] and an occupied
     * [newKey]; the caller treats them the same — dial or adopt under whichever key it already has.
     */
    fun rekey(oldKey: String, newKey: String): Boolean {
        if (sessions.containsKey(newKey) || channels.containsKey(newKey) || buffers.containsKey(newKey)) return false
        val host = hostOf.remove(oldKey) ?: return false
        val session = sessions.remove(oldKey)
        val channel = channels.remove(oldKey)
        val buffer = buffers.remove(oldKey)
        if (session == null && channel == null && buffer == null) return false
        if (session != null) sessions[newKey] = session
        if (channel != null) channels[newKey] = channel
        if (buffer != null) buffers[newKey] = buffer
        hostOf[newKey] = host
        return true
    }

    /**
     * Closes the session and shell for [sessionKey], keeping its scrollback.
     *
     * Order matters: the channel first, so the shell gets its EOF and the remote side sees a closed
     * pty rather than a vanished transport, then the session. `close(false)` is a graceful close —
     * MINA sends `SSH_MSG_DISCONNECT` and lets the server tidy up.
     */
    fun close(sessionKey: String) {
        val channel = channels.remove(sessionKey)?.also {
            // Marked before either close, so the shell's own close listener - which may run on a MINA
            // thread while this call is still in flight - reports the app's decision rather than an
            // outage, and nothing schedules a reconnect to a session the user asked to end.
            it.markDeliberate()
        }
        val session = sessions.remove(sessionKey)
        hostOf.remove(sessionKey)
        release(channel, session) { runCatching { it.close(false) } }
    }

    /**
     * Drops the session for [sessionKey] because it has been *found dead*, without claiming the app
     * meant it to end.
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
    fun discard(sessionKey: String, reason: SessionEnd? = null) {
        val channel = channels.remove(sessionKey)
        val session = sessions.remove(sessionKey)
        hostOf.remove(sessionKey)
        release(channel, session, releaseChannel = { runCatching { it.discard(reason) } }) { runCatching { it.close(true) } }
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
    fun reap(sessionKey: String): Boolean {
        val session = sessions[sessionKey] ?: return false
        if (session.isOpen && session.isAuthenticated) return false
        val channel = channels[sessionKey]
        if (channel != null && !channel.hasEnded) return false
        if (channel != null && channels.remove(sessionKey, channel)) runCatching { channel.discard() }
        if (!sessions.remove(sessionKey, session)) return false
        hostOf.remove(sessionKey)
        // Immediate: a graceful close writes SSH_MSG_DISCONNECT and waits for it, and this session has
        // already gone. Ordinarily a no-op, since MINA closed it itself; kept because "not live" also
        // covers a session that is open and unauthenticated, which nothing else will ever close.
        runCatching { session.close(true) }
        return true
    }

    /** [close], and forget the terminal state too. For a tab the user has closed. */
    fun forget(sessionKey: String) {
        close(sessionKey)
        buffers.remove(sessionKey)
    }

    /** Closes every session. For "Stop sessions", and for the app being torn down deliberately. */
    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    /**
     * The scope every teardown's socket work runs on.
     *
     * Its own scope, deliberately never cancelled, because teardown is exactly the work that must not be
     * interrupted: a cancelled close leaks the socket, the file descriptors and the heartbeat it was
     * supposed to release, and the callers include a service that is stopping and a view model that has
     * already been cleared.
     */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where the socket half of a teardown runs: never on the thread that asked for it.
     *
     * Ending an SSH session writes to the network. `session.close(false)` sends `SSH_MSG_DISCONNECT`
     * and waits for the write to land, and closing a shell channel sends `SSH_MSG_CHANNEL_CLOSE` -
     * so every caller of [close] and [discard] is a caller that must not be on Android's main thread.
     * Three of them were: closing a tab, deleting a host, and the notification's Stop action all run
     * straight from the UI. What that produces is not a slow frame but
     * [android.os.NetworkOnMainThreadException], raised by BlockGuard from inside MINA's write path -
     * which marks the transport broken and reports it as a fault, so the app's own teardown could
     * arrive at the collector looking like an outage worth reconnecting.
     *
     * The bookkeeping stays on the calling thread, and only the socket work moves. That split is the
     * point: [isLive], [liveSession] and [adoptableHostIds] answer from the maps, and every caller
     * expects them to have changed by the time it returns - a tab it just closed must not be
     * adoptable, and a host being deleted must not be dialled by the restore pass a moment later.
     * The entry is gone before this is called; what is deferred is the goodbye to a server that is
     * either gone already or about to be.
     *
     * [releaseChannel] defaults to a graceful channel close and is overridden by [discard], which has
     * a dead transport to release rather than a live one to say goodbye on.
     */
    private fun release(
        channel: TerminalChannel?,
        session: ClientSession?,
        releaseChannel: (TerminalChannel) -> Unit = { runCatching { it.close() } },
        releaseSession: (ClientSession) -> Unit,
    ) {
        if (channel == null && session == null) return
        releaseScope.launch {
            // In this order, and in one coroutine rather than two: a shell that says goodbye after the
            // transport carrying it has gone is a write into a closed socket, and the server learns
            // less from it than it would from being told about the channel first.
            channel?.let(releaseChannel)
            session?.let(releaseSession)
        }
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
