package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.background.NetworkMonitor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finds out, the moment the network moves, which sessions the move has killed.
 *
 * An SSH session is a TCP connection bound to one address on one interface. Walking out of Wi-Fi range
 * onto mobile data invalidates that address, so every live session is already dead - and nothing tells
 * the app so. No FIN arrives, no error is raised, `isOpen` stays true, and the shell goes on accepting
 * keystrokes into a socket that will never deliver another byte. The only thing that eventually notices
 * is the keep-alive, after three unanswered requests, which at the default 30-second interval is a
 * minute and a half of a terminal that looks perfectly connected and does nothing.
 *
 * That minute and a half is the single most-reported symptom of "SSH keeps disconnecting" in this app,
 * and it is not really a disconnection problem: the session was going to drop either way. It is a
 * *latency of discovery* problem. So when [NetworkMonitor.migrated] reports that the default network
 * was replaced, every live session is asked directly whether it is still there
 * ([SshConnectionManager.probeLiveness]) and the dead ones are dropped at once, which puts the tab into
 * RECONNECTING and starts the ladder seconds after the switch instead of a minute and a half later.
 *
 * Three properties this deliberately has:
 *
 *  - **Only on evidence.** Nothing is probed on a timer, on idle, or because output stopped arriving.
 *    A shell sitting at a prompt for an hour is not suspicious, and treating silence as death is how
 *    an app ends up reconnecting sessions that were working. The trigger is a network *replacement* -
 *    an event the platform is certain about.
 *  - **A reply means alive, whatever the reply says.** See [SshConnectionManager.probeLiveness]. The
 *    expensive mistake is not "waited too long to notice", it is "killed a working session".
 *  - **Two chances.** A single timeout on a link that just changed underneath it is suggestive rather
 *    than conclusive - the new interface may still be bringing itself up, and a phone that has just
 *    handed off can be slow for a second or two. Two consecutive silences with a fresh deadline each
 *    are conclusive enough, and cost 12 seconds against a 90-second alternative.
 */
@Singleton
class SessionLivenessProbe @Inject constructor(
    private val sessionStore: SshSessionStore,
    private val connectionManager: SshConnectionManager,
    private val networkMonitor: NetworkMonitor,
    private val diagnostics: SessionDiagnostics,
) {
    /**
     * App-lifetime, because what it watches is app-lifetime.
     *
     * The sessions this protects outlive both the Activity and the ViewModel - they live in
     * [SshSessionStore] - so tying the watch to either would leave a rotated or backgrounded app
     * blind to exactly the network change it most needs to notice.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** One sweep at a time: several interfaces changing at once mean the same one thing. */
    private val sweeping = Mutex()

    /**
     * Begins watching for network changes. Idempotent, and safe to call from every entry point that
     * might be the first one alive - the service's `onCreate`, the view model's `init` - because which
     * of them exists at any moment is not something either can know.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            networkMonitor.migrated.collect { sweep() }
        }
    }

    /**
     * Probes every live session and drops the ones that do not answer.
     *
     * Concurrent across hosts, because the whole point is speed and a serial sweep of four sessions on
     * a dead link would take 48 seconds - by which time the heartbeat this exists to pre-empt would
     * have finished the job itself.
     */
    suspend fun sweep() = sweeping.withLock {
        val hostIds = sessionStore.liveHostIds()
        if (hostIds.isEmpty()) return@withLock
        val network = networkMonitor.describe()
        hostIds.forEach { hostId ->
            diagnostics.record(hostId, SessionEvent.NETWORK_CHANGED, network = network)
        }
        hostIds.map { hostId ->
            scope.async {
                val session = sessionStore.liveSession(hostId) ?: return@async
                var alive = false
                for (attempt in 1..PROBE_ATTEMPTS) {
                    alive = connectionManager.probeLiveness(session)
                    if (alive) {
                        diagnostics.record(
                            hostId,
                            SessionEvent.LIVENESS_PROBE,
                            detail = "alive",
                            network = network,
                            attempt = attempt,
                        )
                        break
                    }
                }
                if (alive) return@async
                diagnostics.record(
                    hostId,
                    SessionEvent.LIVENESS_PROBE,
                    detail = "no reply after $PROBE_ATTEMPTS probes · dropping the session",
                    network = network,
                    attempt = PROBE_ATTEMPTS,
                )
                // Not `close`: the app did not decide to end this, it found it already ended. The
                // difference is whether the tab reconnects. See [SshSessionStore.discard].
                sessionStore.discard(hostId)
            }
        }.awaitAll()
    }

    private companion object {
        const val PROBE_ATTEMPTS = 2
    }
}
