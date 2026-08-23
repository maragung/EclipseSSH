package dev.eclipse.ssh.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/**
 * Which platform callback means which thing, which is the whole basis of the grace period.
 *
 * Three signals come out of [NetworkMonitor] and the app does three different things with them: an
 * arrival wakes a waiting reconnect, a replacement asks every session whether it survived, and a loss
 * holds every session untouched for a minute. Confusing the last two is expensive in both directions -
 * holding through a handover leaves a terminal that looks connected and swallows keystrokes for a
 * minute, and probing through a blackout drops working sessions over a tunnel - and the difference
 * between them is nothing but the order two callbacks arrive in. That ordering is what this pins.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSignalTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopCollectors() {
        scopes.forEach { it.cancel() }
    }

    private val manager: ConnectivityManager
        get() = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The callback [NetworkMonitor] registered in its constructor. */
    private fun callback(): ConnectivityManager.NetworkCallback = shadowOf(manager).networkCallbacks.last()

    private fun network(id: Int): Network = ShadowNetwork.newInstance(id)

    /**
     * Counts emissions of [signal], and does not return until the collector is actually subscribed.
     *
     * The wait is the point. These are hot [SharedFlow]s with no replay, so a callback fired before the
     * collector arrives is not late, it is gone - and a test that raced its own subscription would fail
     * for that and be read as a broken signal.
     */
    private fun watch(signal: SharedFlow<Unit>): CountDownLatch {
        val latch = CountDownLatch(1)
        val subscribed = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.IO)
        scopes += scope
        scope.launch {
            signal.onSubscription { subscribed.countDown() }.collect { latch.countDown() }
        }
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue()
        return latch
    }

    @Test
    fun `losing the network with nothing to replace it starts a hold`() {
        val monitor = NetworkMonitor(RuntimeEnvironment.getApplication())
        val lost = watch(monitor.lost)

        val wifi = network(1)
        callback().onAvailable(wifi)
        // Nothing came up to take over, so the device has no route at all: the case the grace period is
        // for, and the one where probing or redialling could not reach anything anyway.
        shadowOf(manager).setDefaultNetworkActive(false)
        callback().onLost(wifi)

        assertThat(lost.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(monitor.online.value).isFalse()
    }

    @Test
    fun `a handover says migrated and stays quiet about a loss`() {
        // The ordering that makes the distinction work: the platform announces the replacement before it
        // retires what it replaced, so by the time `onLost` arrives the old network is no longer the
        // default. Reading that as a blackout would hold every session for a minute over a Wi-Fi to
        // mobile-data switch - the one case where the sessions really may be dead already and the app
        // should be asking about them at once.
        val monitor = NetworkMonitor(RuntimeEnvironment.getApplication())
        val migrated = watch(monitor.migrated)
        val lost = watch(monitor.lost)

        val wifi = network(1)
        val cell = network(2)
        callback().onAvailable(wifi)
        callback().onAvailable(cell)
        callback().onLost(wifi)

        assertThat(migrated.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(lost.await(1, TimeUnit.SECONDS)).isFalse()
    }

    @Test
    fun `a loss while another network is already carrying the device is not a loss`() {
        // The same rule from the other side: the network that went is not the one the sessions are on.
        val monitor = NetworkMonitor(RuntimeEnvironment.getApplication())
        val lost = watch(monitor.lost)

        callback().onAvailable(network(1))
        // A second interface disappearing - a VPN, a dropped Wi-Fi while data carries on - while the
        // default is untouched.
        callback().onLost(network(9))

        assertThat(lost.await(1, TimeUnit.SECONDS)).isFalse()
        assertThat(monitor.online.value).isTrue()
    }

    @Test
    fun `the first network of the process is an arrival, not a replacement`() {
        // A reconnect ladder is already waiting on `available` for this one. Calling it a migration would
        // probe sessions that cannot exist yet, and on a real device the process often starts before the
        // radio does.
        val monitor = NetworkMonitor(RuntimeEnvironment.getApplication())
        val available = watch(monitor.available)
        val migrated = watch(monitor.migrated)

        callback().onAvailable(network(1))

        assertThat(available.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(migrated.await(1, TimeUnit.SECONDS)).isFalse()
    }

    @Test
    fun `the addresses it reports leave out loopback and carry no scope suffix`() {
        // The set exists to be compared with itself across an outage, so the two requirements are that it
        // excludes an address every machine has - loopback would overlap with itself forever and every
        // comparison would say RESUME - and that an address reads the same before and after, which a
        // link-local scope suffix does not, because the interface index it names can change.
        val addresses = NetworkMonitor(RuntimeEnvironment.getApplication()).localAddresses()

        assertThat(addresses).doesNotContain("127.0.0.1")
        assertThat(addresses).doesNotContain("::1")
        addresses.forEach { assertThat(it).doesNotContain("%") }
    }
}
