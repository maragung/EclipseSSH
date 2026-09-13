package dev.eclipse.ssh.linux

import dev.eclipse.ssh.ssh.TerminalChannel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The table of live local userspace sessions — which open terminal channels belong to the Linux
 * userspace, so "Stop Ubuntu" can close exactly those and nothing else.
 *
 * The channels themselves live in the session store alongside SSH channels (that is the point of
 * the [TerminalChannel] seam); this manager holds only the *local* registry. Closing every local
 * session is a different act from closing an SSH connection — there is no transport to tear down,
 * just processes to SIGHUP — and it must not touch a terminal talking to a server in another
 * country, which is why the store alone cannot do it.
 *
 * The count is a [StateFlow], not a getter, because the settings screen and the host card both
 * display it and should not poll: every register/unregister publishes, and Compose collects.
 *
 * All methods are safe from any thread; register/unregister happen on the ViewModel's session
 * open/close paths, closeAll on Stop.
 */
class LinuxProcessManager {

    private val sessions = ConcurrentHashMap<String, TerminalChannel>()

    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount

    /** Records an open local session under its session key. Registering twice keeps the last. */
    fun register(sessionKey: String, channel: TerminalChannel) {
        sessions[sessionKey] = channel
        recount()
    }

    /** Forgets a session without closing it — the close path calls this after the channel ended. */
    fun unregister(sessionKey: String) {
        sessions.remove(sessionKey)
        recount()
    }

    fun channelFor(sessionKey: String): TerminalChannel? = sessions[sessionKey]

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
