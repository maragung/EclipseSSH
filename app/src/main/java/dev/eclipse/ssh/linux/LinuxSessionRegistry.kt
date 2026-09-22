package dev.eclipse.ssh.linux

import dev.eclipse.ssh.ssh.TerminalChannel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The table of live local userspace sessions — which open terminal channels belong to the Linux
 * userspace, so "Stop Ubuntu" can close exactly those and nothing else.
 *
 * The channels themselves live in the session store alongside SSH channels (that is the point of
 * the [TerminalChannel] seam); this registry holds only the *local* sessions. Closing every local
 * session is a different act from closing an SSH connection — there is no transport to tear down,
 * just processes to SIGHUP — and it must not touch a terminal talking to a server in another
 * country, which is why the store alone cannot do it.
 *
 * Self-unregistering: a shell that ends on its own (the user types `exit`) reports its ending to
 * the collector, and no app close path runs for it — so a registry that only forgot sessions on
 * the tab's close would keep counting corpses, and the settings screen and the FGS notification
 * would say "3 terminals held open" about sessions that exited an hour ago. On [register] this
 * registry launches a supervisor that awaits the channel's ending and then forgets it, keyed on
 * *identity*: a reconnect's replacement registered under the same session key is never evicted by
 * the old channel's exit.
 *
 * The count is a [StateFlow], not a getter, because the settings screen and the host card both
 * display it and should not poll: every register/unregister publishes, and Compose collects.
 *
 * All methods are safe from any thread; register/unregister happen on the ViewModel's session
 * open/close paths, closeAll on Stop.
 */
class LinuxSessionRegistry(
    /**
     * Where the per-session supervisors run. Injectable so tests can drive them deterministically;
     * production uses its own supervisor scope, because this registry lives as long as the process
     * and its supervisors only ever await a channel's ending.
     */
    private val supervision: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val sessions = ConcurrentHashMap<String, TerminalChannel>()

    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount

    /** Records an open local session under its session key. Registering twice keeps the last. */
    fun register(sessionKey: String, channel: TerminalChannel) {
        sessions[sessionKey] = channel
        recount()
        supervision.launch {
            channel.awaitClosed()
            // Identity, not the key: only this registration goes. The remove is conditional, so a
            // replacement registered under the same key survives the old channel's ending, and an
            // unregister that already ran is simply a no-op when the ending arrives late.
            if (sessions.remove(sessionKey, channel)) recount()
        }
    }

    /** Forgets a session without closing it — the close path calls this after the channel ended. */
    fun unregister(sessionKey: String) {
        sessions.remove(sessionKey)
        recount()
    }

    fun channelFor(sessionKey: String): TerminalChannel? = sessions[sessionKey]

    /**
     * How many sessions *other than* [sessionKey] are still registered.
     *
     * The caller is [LinuxUserspaceManager.noteSessionEnded], which is deciding whether one
     * session's bad ending says anything about the userspace as a whole, and a sibling terminal
     * still registered is proof that it does not. The session being judged has to be excluded by
     * key rather than by asking [sessionCount] and subtracting: this registry evicts a session from
     * its own supervisor coroutine the moment the channel's ending arrives, and that coroutine and
     * the collector reporting the ending are two threads with nothing ordering them — so at the
     * moment of the question the ending session may or may not still be counted, and "one other
     * session is live" would otherwise be read as "this one is still alive".
     */
    fun liveCountExcept(sessionKey: String): Int = sessions.keys.count { it != sessionKey }

    /**
     * Closes every live local session and returns the keys that were closed, for the caller's
     * bookkeeping (tab teardown). A channel whose close throws is still removed — Stop must not be
     * held hostage by one dead process — and the failure is swallowed here because the channel's
     * own ending path reports it to its tab.
     */
    fun closeAll(): List<String> {
        val keys = sessions.keys.toList()
        for (key in keys) {
            sessions.remove(key)?.let { channel -> runCatching { channel.close() } }
        }
        recount()
        return keys
    }

    private fun recount() {
        _sessionCount.value = sessions.size
    }
}

/**
 * The registry's earlier name, kept so code written against it — the graph provider that
 * constructs it, the manager that closes it on Stop — keeps compiling while callers migrate to
 * the name that says what it is.
 */
@Deprecated("Renamed to LinuxSessionRegistry: it is a registry of terminal sessions, not a process manager")
typealias LinuxProcessManager = LinuxSessionRegistry
