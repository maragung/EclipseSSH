package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
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
 *  - whoever dials puts the session here, keyed by host id, and closes any session it replaces;
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
        channels.remove(hostId)?.let { channel -> runCatching { channel.close() } }
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
        channels.remove(hostId)?.let { channel -> runCatching { channel.close() } }
        sessions.remove(hostId)?.let { session -> runCatching { session.close(false) } }
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
