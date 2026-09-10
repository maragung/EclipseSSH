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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides what a network change means for the sessions that were using it: hold, resume, ask, or drop.
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
 * The other half is the case where the network does not move but *goes*. A phone loses its network for
 * a few seconds constantly - a lift, a tunnel, a platform, the gap between two Wi-Fi cells - and none of
 * those is a reason to end a session. There is also nothing useful to do about them: with no route out, a
 * probe cannot reach the host and a redial cannot open a socket, so declaring the session dead does not
 * recover anything, it just loses the shell and starts a ladder that will spend its attempts on failures.
 * So a loss starts a *hold*: for [NETWORK_GRACE_MS] the session is left exactly as it is, still
 * CONNECTED, with only the status line saying the app is waiting. If the network comes back inside the
 * minute, the local addresses from before are compared with the ones after - see [graceOutcome] - and an
 * overlap resumes the session silently, without a packet having been sent about any of it. This is
 * ConnectBot's design, and the reason it feels stable in a pocket.
 *
 * Properties this deliberately has:
 *
 *  - **Only on evidence.** Nothing is probed on a timer, on idle, or because output stopped arriving.
 *    A shell sitting at a prompt for an hour is not suspicious, and treating silence as death is how
 *    an app ends up reconnecting sessions that were working. The trigger is a network *replacement* -
 *    an event the platform is certain about.
 *  - **A reply means alive, whatever the reply says.** See [SshConnectionManager.probeLiveness]. The
 *    expensive mistake is not "waited too long to notice", it is "killed a working session".
 *  - **An observation outranks an inference.** A probe asks a question; arriving bytes answer it. If
 *    the far end speaks while the probes are timing out, the probes are wrong. See [probeContradicted].
 *  - **Two chances.** A single timeout on a link that just changed underneath it is suggestive rather
 *    than conclusive - the new interface may still be bringing itself up, and a phone that has just
 *    handed off can be slow for a second or two. Two consecutive silences with a fresh deadline each
 *    are conclusive enough, and cost 12 seconds against a 90-second alternative.
 *  - **Proof skips the question.** When not one local address survived a replacement, no socket bound to
 *    the old one can work, so there is nothing to ask and the 12 seconds are spent for nothing. See
 *    [migrationProvesLoss]. The converse does not hold and is not assumed: a surviving address does not
 *    mean a surviving session, so overlap still probes.
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

    /** One hold at a time, for the same reason. Never taken by [sweep], which a hold may call. */
    private val holding = Mutex()

    private val _networkHeld = MutableStateFlow(false)

    /**
     * True while live sessions are being held through a gap in connectivity.
     *
     * Not a [dev.eclipse.ssh.data.model.SessionConnectionState]: the sessions are connected, and saying
     * otherwise would be both untrue and destructive, because the tab's own machinery treats a
     * non-connected state as something to recover from. This is a fact *about the device* that happens to
     * be worth putting on screen, so it is published separately and folded into the status line, leaving
     * the state machine to describe the session.
     */
    val networkHeld: StateFlow<Boolean> = _networkHeld

    /**
     * The local addresses as they were at the last moment the device had a network.
     *
     * Needed because both comparisons are with a *before*, and by the time either event arrives the
     * before is gone: a loss reports an interface that is already down, and a replacement reports a new
     * network that has already displaced the old one. Refreshed on every arrival, so it is never older
     * than the most recent network the device had.
     *
     * Staleness is safe in the one direction that matters. If it lags and shows addresses that are no
     * longer assigned, the comparison finds no overlap and the app concludes the sockets are gone - which
     * is what an address change means anyway, since a socket bound to a released address is dead however
     * the app came to know it.
     */
    @Volatile
    private var lastAddresses: Set<String> = emptySet()

    /**
     * Begins watching for network changes. Idempotent, and safe to call from every entry point that
     * might be the first one alive - the service's `onCreate`, the view model's `init` - because which
     * of them exists at any moment is not something either can know.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        lastAddresses = networkMonitor.localAddresses()
        scope.launch {
            networkMonitor.migrated.collect { onNetworkReplaced() }
        }
        scope.launch {
            networkMonitor.lost.collect { holdThroughOutage() }
        }
        scope.launch {
            // Kept current here rather than only where it is read: an arrival is the one event that
            // guarantees an address set worth remembering, and the next loss or replacement needs the
            // set from *before* it happened.
            networkMonitor.available.collect { lastAddresses = networkMonitor.localAddresses() }
        }
    }

    /**
     * The default network was replaced. Drop what is provably gone; ask about the rest.
     */
    private suspend fun onNetworkReplaced() {
        val before = lastAddresses
        val after = networkMonitor.localAddresses()
        lastAddresses = after
        if (!migrationProvesLoss(before, after)) {
            sweep()
            return
        }
        val live = sessionStore.liveKeysByHost()
        if (live.isEmpty()) return
        val network = networkMonitor.describe()
        // No probe: every address the sockets could be bound to has been released, and twelve seconds of
        // deadlines cannot make that less true.
        dropAll(live, "network replaced · no local address survived · dropping the session", network)
    }

    /**
     * The device has no network. Leave every session alone for [NETWORK_GRACE_MS], then decide.
     *
     * Nothing is touched during the hold - not the socket, not the pty, not the buffer - so a resume is
     * genuinely a resume: the terminal the user comes back to is the terminal they left, with its history
     * and its working directory and whatever was half-typed at the prompt.
     */
    private suspend fun holdThroughOutage() = holding.withLock {
        val live = sessionStore.liveKeysByHost()
        if (live.isEmpty()) return@withLock
        val before = lastAddresses.ifEmpty { networkMonitor.localAddresses() }
        live.forEach { (_, hostId) ->
            diagnostics.record(
                hostId,
                SessionEvent.NETWORK_CHANGED,
                detail = "network lost · holding the session for ${NETWORK_GRACE_MS / 1000}s",
                network = networkMonitor.describe(),
            )
        }
        _networkHeld.value = true
        try {
            // [NetworkMonitor.online] is a StateFlow, so this reads the current answer before it waits:
            // a network that came back in the moment between the callback and this line is not missed.
            val returned = withTimeoutOrNull(NETWORK_GRACE_MS) { networkMonitor.online.first { it } } != null
            val network = networkMonitor.describe()
            if (!returned) {
                dropAll(
                    sessionStore.liveKeysByHost(),
                    "no network for ${NETWORK_GRACE_MS / 1000}s · dropping the session",
                    network,
                )
                return@withLock
            }
            val after = networkMonitor.localAddresses()
            lastAddresses = after
            val stillLive = sessionStore.liveKeysByHost()
            when (graceOutcome(before, after)) {
                GraceOutcome.RESUME ->
                    // Silently, and that is the point: no probe, no redial, no reconnect, nothing sent.
                    // The addresses the sockets are bound to are still assigned, so as far as the session
                    // is concerned the outage did not happen.
                    stillLive.forEach { (_, hostId) ->
                        diagnostics.record(
                            hostId,
                            SessionEvent.NETWORK_CHANGED,
                            detail = "network back · address unchanged · resumed",
                            network = network,
                        )
                    }
                GraceOutcome.DROP ->
                    dropAll(stillLive, "network back on a new address · dropping the session", network)
                GraceOutcome.UNKNOWN -> {
                    // The comparison did not run - no addresses on one side or the other. Ask the
                    // sessions themselves rather than guess, which is what a sweep is for.
                    stillLive.forEach { (_, hostId) ->
                        diagnostics.record(
                            hostId,
                            SessionEvent.NETWORK_CHANGED,
                            detail = "network back · address unknown · probing",
                            network = network,
                        )
                    }
                    sweep()
                }
            }
        } finally {
            // In a finally because a cancelled hold - the process is going down, the scope was cancelled -
            // must not leave the UI claiming to be waiting for a network forever.
            _networkHeld.value = false
        }
    }

    /**
     * Ends the given sessions naming the network as the cause.
     *
     * [SessionEnd.NetworkLost] rather than letting the channel guess, because the guess is made from
     * `session.isOpen`, which is still true here - the app knows the transport is finished for a reason
     * MINA has no way to see, and a trace that says so is worth more than one that says `Released`.
     */
    private fun dropAll(live: List<Pair<String, String>>, detail: String, network: String) {
        live.forEach { (sessionKey, hostId) ->
            diagnostics.record(hostId, SessionEvent.NETWORK_CHANGED, detail = detail, network = network)
            sessionStore.discard(sessionKey, SessionEnd.NetworkLost)
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
        // Every live *session*, not every live host: with more than one terminal on a host each one is
        // its own socket and has to be asked its own question. The trace stays host-keyed - one line
        // per session is what a reader chasing a drop wants.
        val live = sessionStore.liveKeysByHost()
        if (live.isEmpty()) return@withLock
        val network = networkMonitor.describe()
        live.forEach { (_, hostId) ->
            diagnostics.record(hostId, SessionEvent.NETWORK_CHANGED, network = network)
        }
        live.map { (sessionKey, hostId) ->
            scope.async {
                val session = sessionStore.liveSession(sessionKey) ?: return@async
                val channel = sessionStore.channels[sessionKey]
                val probeStartedAt = System.currentTimeMillis()
                var alive = false
                for (attempt in 1..PROBE_ATTEMPTS) {
                    alive = connectionManager.probeLiveness(session)
                    if (alive) {
                        diagnostics.record(
                            hostId,
                            SessionEvent.LIVENESS_PROBE,
                            detail = "alive",
                            network = network,
                            channel = channel?.channelLabel,
                            idleForMs = channel?.idleForMs(),
                            attempt = attempt,
                        )
                        break
                    }
                }
                if (alive) return@async
                // Asked on the way out, and only on the way out. See [probeContradicted].
                if (channel != null && probeContradicted(channel.lastActivityAtMs, probeStartedAt)) {
                    diagnostics.record(
                        hostId,
                        SessionEvent.LIVENESS_PROBE,
                        detail = "output arrived while probing · kept",
                        network = network,
                        channel = channel.channelLabel,
                        // Measured from the probe, not from now: it is the age *at the moment the
                        // decision was taken* that the decision was taken on.
                        idleForMs = channel.idleForMs(probeStartedAt),
                        attempt = PROBE_ATTEMPTS,
                    )
                    return@async
                }
                diagnostics.record(
                    hostId,
                    SessionEvent.LIVENESS_PROBE,
                    detail = "no reply after $PROBE_ATTEMPTS probes · " +
                        lastOutputAgeText(
                            lastActivityAtMs = channel?.lastActivityAtMs ?: 0L,
                            openedAtMs = channel?.openedAtMs ?: 0L,
                            nowMs = probeStartedAt,
                        ) +
                        " · dropping the session",
                    network = network,
                    channel = channel?.channelLabel,
                    idleForMs = channel?.idleForMs(probeStartedAt),
                    attempt = PROBE_ATTEMPTS,
                )
                // Not `close`: the app did not decide to end this, it found it already ended. The
                // difference is whether the tab reconnects. See [SshSessionStore.discard]. The reason is
                // named because it is known - a sweep only ever runs after the network changed - and
                // `NetworkLost` tells the user something, where the channel's own guess would say
                // `Released` and read as though the app had chosen this.
                sessionStore.discard(sessionKey, SessionEnd.NetworkLost)
            }
        }.awaitAll()
    }

    private companion object {
        const val PROBE_ATTEMPTS = 2
    }
}

/**
 * Whether the far end contradicted the probe while the probe was still running.
 *
 * A probe is an inference; received bytes are an observation. When the two disagree the observation
 * wins - a session streaming a build log can be slow to answer a global request queued behind that
 * output while being unmistakably alive, and dropping it would mean choosing the weaker evidence after
 * having seen the stronger.
 *
 * The comparison is against [probeStartedAtMs], not against the clock, and that distinction is the
 * whole rule. Bytes that arrived *before* the probe began say nothing about the transport now: on a
 * black-holed socket the last byte always arrived seconds ago, because the socket only just stopped
 * delivering. Only bytes that crossed the wire *while* two six-second deadlines were expiring prove the
 * silence was a false negative.
 *
 * The same reasoning rules out asking this before probing rather than after. A sweep is triggered by a
 * network *replacement*, not by a timer, so it is never retried: a probe skipped for looking healthy is
 * a probe never sent, and the session it spared is exactly the one that just lost its interface. There
 * is also nothing to spare it from, because a healthy session answers - [SshConnectionManager.probeLiveness]
 * counts any reply, including a refusal - so a young or busy session that is genuinely up passes the
 * probe on its own merits and needs no exemption. A minimum age would only ever delay a correct
 * conclusion, and can hide one entirely.
 *
 * [lastActivityAtMs] is 0 until the far end has sent anything, which contradicts nothing.
 */
internal fun probeContradicted(lastActivityAtMs: Long, probeStartedAtMs: Long): Boolean =
    lastActivityAtMs > 0L && lastActivityAtMs >= probeStartedAtMs

/**
 * How long ago the far end last said anything, for the trace that records a drop.
 *
 * Written down because it is the difference between a diagnosis and a guess when a user reports a
 * session that vanished: a drop with the last output minutes old is a link that went away, and a drop
 * with the last output moments old is this app getting it wrong.
 */
internal fun lastOutputAgeText(lastActivityAtMs: Long, openedAtMs: Long, nowMs: Long): String = when {
    lastActivityAtMs > 0L -> "last output ${secondsBetween(lastActivityAtMs, nowMs)}s ago"
    openedAtMs > 0L -> "no output in ${secondsBetween(openedAtMs, nowMs)}s"
    else -> "no output yet"
}

private fun secondsBetween(fromMs: Long, toMs: Long): Long = (toMs - fromMs).coerceAtLeast(0L) / 1000
