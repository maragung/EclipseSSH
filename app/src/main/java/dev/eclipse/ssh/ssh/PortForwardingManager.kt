package dev.eclipse.ssh.ssh

import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.PortForwardingTracker
import org.apache.sshd.common.util.net.SshdSocketAddress

@Singleton
class PortForwardingManager @Inject constructor() {
    suspend fun startLocal(
        session: ClientSession,
        localHost: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
        onAbandoned: (ForwardingHandle) -> Unit = { handle -> runCatching { handle.close() } },
    ): ForwardingHandle = bindOnIo(onAbandoned) {
        session.createLocalPortForwardingTracker(
            SshdSocketAddress(localHost, localPort),
            SshdSocketAddress(remoteHost, remotePort),
        )
    }

    suspend fun startDynamic(
        session: ClientSession,
        localHost: String,
        localPort: Int,
        onAbandoned: (ForwardingHandle) -> Unit = { handle -> runCatching { handle.close() } },
    ): ForwardingHandle = bindOnIo(onAbandoned) {
        session.createDynamicPortForwardingTracker(SshdSocketAddress(localHost, localPort))
    }

    suspend fun startRemote(
        session: ClientSession,
        remoteBindHost: String,
        remoteBindPort: Int,
        localHost: String,
        localPort: Int,
        onAbandoned: (ForwardingHandle) -> Unit = { handle -> runCatching { handle.close() } },
    ): ForwardingHandle = bindOnIo(onAbandoned) {
        session.createRemotePortForwardingTracker(
            SshdSocketAddress(remoteBindHost, remoteBindPort),
            SshdSocketAddress(localHost, localPort),
        )
    }

    /**
     * Runs [bind] on the IO dispatcher and answers its tracker, with one guarantee a plain
     * `withContext` cannot give: **a bind that completed is never dropped.**
     *
     * The bind itself cannot be interrupted - by the time MINA returns a tracker, its listener is
     * bound - but the caller can be cancelled while the bind runs, and `withContext` answers a
     * cancelled caller by discarding the block's result and throwing [CancellationException]. A
     * discarded tracker is a listening socket nobody owns: it is in no map, no teardown will ever
     * close it, and the next bind of the same rule finds the port "already in use" against nobody.
     * The var is written inside the block, before any cancellation can land on the result, so the
     * catch can hand the just-claimed port to [onAbandoned] instead of leaking it.
     *
     * The default [onAbandoned] simply closes the handle. The forwarding engine passes its own, so
     * an abandoned bind is closed *and registered* where the next bind of the same rule will wait
     * for it - see `MainViewModel.abandonForward`.
     */
    private suspend fun bindOnIo(
        onAbandoned: (ForwardingHandle) -> Unit,
        bind: () -> PortForwardingTracker,
    ): ForwardingHandle {
        var tracker: PortForwardingTracker? = null
        try {
            return withContext(Dispatchers.IO) { ForwardingHandle(bind().also { tracker = it }) }
        } catch (cancelled: CancellationException) {
            tracker?.let { onAbandoned(ForwardingHandle(it)) }
            throw cancelled
        }
    }
}

class ForwardingHandle(private val tracker: PortForwardingTracker) : Closeable {
    /**
     * The port this forward's listener actually landed on.
     *
     * A bind asked for port 0 gets an OS-assigned ephemeral port here, which is how an ad-hoc
     * forward (the VNC tunnel) claims a port without predicting one - a predicted port is a race
     * another bind can win, and the saved rules already carry the scars of that family.
     */
    val boundPort: Int get() = tracker.boundAddress.port
    override fun close() { tracker.close() }
}
