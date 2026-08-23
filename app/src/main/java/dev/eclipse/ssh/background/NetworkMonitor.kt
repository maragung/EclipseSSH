package dev.eclipse.ssh.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device has a network, and a signal each time a usable one appears.
 *
 * Reconnect logic needs both halves and for different reasons. The signal is what turns "wait five
 * minutes, then try" into "try the moment Wi-Fi comes back" — see [awaitReconnectWindow]. The state
 * is what stops a retry budget being spent on attempts that could not have succeeded: five attempts
 * fired into a flight-mode gap would exhaust the allowance in seconds and leave the session dead for
 * good once the network returned, which is the opposite of what a retry limit is for. A reconnect
 * loop that is waiting for a network is not retrying; it is waiting, and it costs nothing.
 *
 * [online] fails *open*. If `ConnectivityManager` is unavailable or the callback cannot be
 * registered, the answer is "online", so the worst case is one connect attempt that fails with a real
 * network error — as against a permanent "offline" that would silently disable reconnecting
 * altogether. The registration is wrapped for the same reason: this class must never be the thing
 * that takes a process down.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    /**
     * Emits once per network that becomes available.
     *
     * `DROP_OLDEST` with a small buffer: several interfaces coming up at once mean the same one
     * thing, and a collector that is mid-reconnect only needs to know that *something* changed after
     * it looked. Nothing here is allowed to suspend the platform's callback thread.
     */
    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val available: SharedFlow<Unit> = _available

    /**
     * Emits when the default network is *replaced* rather than merely appearing: Wi-Fi to mobile data,
     * data back to Wi-Fi, one Wi-Fi network to another.
     *
     * A different signal from [available] because it means something different. An SSH connection is a
     * TCP connection bound to an address on the old interface, so a migration has already broken every
     * live session - the socket is not going to deliver another byte - but nothing about it is visible
     * from inside the connection. Nobody sends a FIN, no error arrives, and the app's heartbeat only
     * discovers it when three consecutive keepalives go unanswered, which at the default interval is a
     * minute and a half of a terminal that looks fine and swallows every keystroke.
     *
     * With this, a migration is a cue to *ask* each session whether it is still there. See
     * `SshConnectionManager.probeLiveness`.
     */
    private val _migrated = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val migrated: SharedFlow<Unit> = _migrated

    /**
     * Emits when the network live sessions were bound to goes away with nothing to replace it.
     *
     * The counterpart to [migrated], and the difference is what the app should *do*. A migration hands
     * over to a working network, so the question is whether each session survived it, and that question
     * can be asked immediately. A loss leaves nothing to ask over: probing has no route out, a redial
     * cannot open a socket, and a session declared dead here would be declared dead on the strength of
     * a lift going into a tunnel. So this signal starts a hold instead - see `NETWORK_GRACE_MS` - and
     * the session stays connected and untouched until the network returns or the hold runs out.
     *
     * Emitted only for the *default* network, and only when it has not already been replaced. During a
     * seamless handover the platform announces the new network before retiring the old one, so
     * [defaultNetwork] no longer matches by the time `onLost` arrives and this stays quiet: that case is
     * a migration, and saying both would start a hold over a network the device still has.
     */
    private val _lost = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lost: SharedFlow<Unit> = _lost

    /**
     * The default network the callback last reported, used only to tell a replacement from an arrival.
     *
     * `@Volatile` because it is written on the platform's callback thread and read there too, but
     * published to whatever thread asks [describe].
     */
    @Volatile
    private var defaultNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            _online.value = true
            _available.tryEmit(Unit)
            // Only a *replacement*. The first network of the process, and one that comes back after
            // flight mode, are arrivals: there is no live session bound to a dead interface to probe,
            // and the reconnect ladder is already waiting on [available] for those.
            if (previous != null && previous != network) _migrated.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            val wasDefault = defaultNetwork == network
            if (wasDefault) defaultNetwork = null
            _online.value = hasActiveNetwork()
            // After the state, so a collector that reads [online] on this signal sees the new answer;
            // and only when nothing has taken over, because a device that still has a route has not
            // lost its network, it has changed it.
            if (wasDefault && !_online.value) _lost.tryEmit(Unit)
        }

        override fun onUnavailable() {
            _online.value = hasActiveNetwork()
        }
    }

    init {
        _online.value = hasActiveNetwork()
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
    }

    /**
     * True when the platform has a default network at all.
     *
     * Deliberately not `NET_CAPABILITY_VALIDATED`: a captive portal, a LAN-only network and a link
     * whose validation probe has not finished yet all report unvalidated, and all three can reach an
     * SSH host on the local network. The question here is only "is it worth trying".
     */
    private fun hasActiveNetwork(): Boolean =
        runCatching { connectivityManager?.activeNetwork != null }.getOrDefault(true)

    /**
     * Every address currently assigned to a non-loopback interface that is up, as text.
     *
     * Used for one comparison and nothing else: the set is recorded when the network goes away and read
     * again when it returns, and an overlap between the two means at least one of the addresses a live
     * session could be bound to is still assigned - so the socket may well have survived the gap and is
     * worth speaking to before anything concludes it is dead. See `graceOutcome`.
     *
     * **These strings are never logged, exported or shown.** An IP address is the one thing this class
     * handles that could identify a network or a person, in contrast to the transport name [describe]
     * returns; only the *result* of comparing two sets ever leaves this process's memory. Callers that
     * want to record what happened record `resumed` or `dropped`, not an address.
     *
     * An empty set is a legitimate answer that means "could not tell" as much as it means "nothing
     * assigned" - during flight mode there is genuinely nothing, and `NetworkInterface` can also throw
     * on some devices. Both are handled the same way by the caller, which treats an unknown comparison
     * as a reason to ask the session rather than to kill it, matching how [online] fails open.
     */
    fun localAddresses(): Set<String> = runCatching {
        val addresses = mutableSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptySet()
        for (candidate in interfaces) {
            if (runCatching { candidate.isLoopback || !candidate.isUp }.getOrDefault(true)) continue
            for (address in candidate.inetAddresses) {
                val text = address?.hostAddress ?: continue
                // Link-local IPv6 carries a scope suffix that changes with the interface index, so the
                // same address can read as two different strings across a handover. The address itself
                // is what is being compared.
                addresses += text.substringBefore('%')
            }
        }
        addresses
    }.getOrDefault(emptySet())

    /**
     * The current network as one word, for diagnostics: `wifi`, `cell`, `ethernet`, `bluetooth`,
     * `vpn`, `other`, `none`, or `unknown` when the platform would not say.
     *
     * A transport name is not personal information - it is not the SSID, the carrier or an address -
     * and it is the single most useful field in a report about a session that dropped, because "cell"
     * on the line before a disconnect and "wifi" on the line after it *is* the diagnosis.
     */
    fun describe(): String = runCatching {
        val manager = connectivityManager ?: return@runCatching "unknown"
        val active = manager.activeNetwork ?: return@runCatching "none"
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching "unknown"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }.getOrDefault("unknown")
}
