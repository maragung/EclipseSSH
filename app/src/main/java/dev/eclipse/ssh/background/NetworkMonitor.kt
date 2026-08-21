package dev.eclipse.ssh.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
            _available.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            _online.value = hasActiveNetwork()
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
}
