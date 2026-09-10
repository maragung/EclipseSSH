package dev.eclipse.ssh.ssh

import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
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
    ): ForwardingHandle = withContext(Dispatchers.IO) {
        val tracker = session.createLocalPortForwardingTracker(
            SshdSocketAddress(localHost, localPort),
            SshdSocketAddress(remoteHost, remotePort),
        )
        ForwardingHandle(tracker)
    }

    suspend fun startDynamic(session: ClientSession, localHost: String, localPort: Int): ForwardingHandle = withContext(Dispatchers.IO) {
        ForwardingHandle(session.createDynamicPortForwardingTracker(SshdSocketAddress(localHost, localPort)))
    }

    suspend fun startRemote(
        session: ClientSession,
        remoteBindHost: String,
        remoteBindPort: Int,
        localHost: String,
        localPort: Int,
    ): ForwardingHandle = withContext(Dispatchers.IO) {
        val tracker = session.createRemotePortForwardingTracker(
            SshdSocketAddress(remoteBindHost, remoteBindPort),
            SshdSocketAddress(localHost, localPort),
        )
        ForwardingHandle(tracker)
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
