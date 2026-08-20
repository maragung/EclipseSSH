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
    override fun close() { tracker.close() }
}
