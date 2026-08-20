package dev.eclipse.ssh.ssh

import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.PropertyResolver
import org.apache.sshd.common.io.DefaultIoConnectFuture
import org.apache.sshd.common.io.IoConnectFuture
import org.apache.sshd.common.io.IoConnector
import org.apache.sshd.common.io.IoHandler
import org.apache.sshd.common.io.IoSession
import org.apache.sshd.common.io.nio2.Nio2Connector
import org.apache.sshd.common.io.nio2.Nio2ServiceFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Per-connection SOCKS5 proxy configuration, carried through Apache MINA SSHD's
 * [AttributeRepository] connection context so each session may use a different proxy.
 */
data class SocksProxyConfig(
    val host: String,
    val port: Int,
    val connectTimeoutMs: Long = 15_000L,
    val username: String? = null,
    val password: String? = null,
) {
    val hasCredentials: Boolean get() = !username.isNullOrEmpty() || !password.isNullOrEmpty()

    /**
     * Redacts [password], because the generated one would print it.
     *
     * This object is handed to Apache MINA SSHD as a connection attribute, so where it ends up next
     * is that library's decision, not this app's: attribute maps are stringified in diagnostics and
     * in `IoSession` dumps. Every other field is kept — the point is a log line that still says which
     * proxy was in use.
     *
     * Enumerated by hand, so a new field has to be added here too. The check is
     * `ProxyConfigTest.neither proxy config can print its password`.
     */
    override fun toString(): String = "SocksProxyConfig(host=$host, port=$port, " +
        "connectTimeoutMs=$connectTimeoutMs, username=$username, " +
        "password=${if (password.isNullOrEmpty()) "none" else REDACTED})"

    companion object {
        /** Deliberately says nothing about the value, not even its length. */
        internal const val REDACTED = "<redacted>"

        val KEY = AttributeRepository.AttributeKey<SocksProxyConfig>()

        fun from(context: AttributeRepository?): SocksProxyConfig? = context?.getAttribute(KEY)
    }
}

/**
 * A [SshClient] whose [IoConnector] transparently tunnels connections through a
 * SOCKS5 proxy when a [SocksProxyConfig] is present in the connection context.
 *
 * MINA SSHD 2.14 does not ship a client-side SOCKS connector, so we subclass the
 * default client and swap in [SocksProxyConnector].
 */
class ProxyAwareClient : SshClient() {
    override fun createConnector(): IoConnector {
        val factory = getIoServiceFactory() as? Nio2ServiceFactory ?: return super.createConnector()
        return try {
            // Nio2ServiceFactory keeps its channel group and resume executor private,
            // so we extract them once to build the connector ourselves.
            val group = Nio2ServiceFactory::class.java
                .getDeclaredField("group").apply { isAccessible = true }
                .get(factory) as AsynchronousChannelGroup
            val resuming = Nio2ServiceFactory::class.java
                .getDeclaredField("resuming").apply { isAccessible = true }
                .get(factory) as ExecutorService
            ProxyAwareConnector(factory, this, getSessionFactory(), group, resuming)
        } catch (t: Throwable) {
            // Fall back to a plain connector if reflection is ever restricted.
            super.createConnector()
        }
    }

    companion object {
        /** Builds a default-configured client backed by [ProxyAwareClient]. */
        fun setUpDefault(): SshClient = ClientBuilder.builder()
            .factory(org.apache.sshd.common.Factory { ProxyAwareClient() })
            .build()
    }
}

/**
 * Connector that dispatches to the SOCKS5 or HTTP CONNECT handshake based on which
 * proxy configuration is attached to the connection context, otherwise connects direct.
 */
class ProxyAwareConnector(
    factory: Nio2ServiceFactory,
    propertyResolver: PropertyResolver,
    handler: IoHandler,
    group: AsynchronousChannelGroup,
    resumeTasks: ExecutorService,
) : Nio2Connector(factory, propertyResolver, handler, group, resumeTasks) {

    override fun connect(
        address: SocketAddress,
        context: AttributeRepository?,
        localAddress: SocketAddress?,
    ): IoConnectFuture {
        val socks = SocksProxyConfig.from(context)
        val http = HttpProxyConfig.from(context)
        if (socks == null && http == null) return super.connect(address, context, localAddress)

        val future = DefaultIoConnectFuture(address, null)
        var channel: AsynchronousSocketChannel? = null
        try {
            val target = address as? InetSocketAddress
                ?: throw IOException("Proxy requires an InetSocketAddress target")
            val socket = setSocketOptions(AsynchronousSocketChannel.open(getChannelGroup()))
            channel = socket
            if (localAddress != null) socket.bind(localAddress)

            when {
                socks != null -> {
                    socket.connect(InetSocketAddress(socks.host, socks.port))
                        .get(socks.connectTimeoutMs, TimeUnit.MILLISECONDS)
                    Socks5Handshake.perform(socket, target.hostString, target.port, socks)
                }
                http != null -> {
                    socket.connect(InetSocketAddress(http.host, http.port))
                        .get(http.connectTimeoutMs, TimeUnit.MILLISECONDS)
                    HttpConnectHandshake.perform(socket, target.hostString, target.port, http)
                }
            }

            val session = createSession(propertyResolver, getIoHandler(), socket)
            if (context != null) session.setAttribute(AttributeRepository::class.java, context)
            getIoHandler().sessionCreated(session)
            (sessions as MutableMap<Long, IoSession>)[session.id] = session
            future.setSession(session)
            session.startReading()
        } catch (t: Throwable) {
            try {
                channel?.close()
            } catch (_: Exception) {
                // Ignore secondary close failures.
            }
            future.setException(t)
        }
        return future
    }
}

/**
 * RFC 1928 SOCKS5 (no-authentication) CONNECT handshake over an asynchronous socket
 * channel. After [perform] returns, [channel] is a transparent tunnel to the target.
 */
object Socks5Handshake {
    fun perform(
        channel: AsynchronousSocketChannel,
        targetHost: String,
        targetPort: Int,
        config: SocksProxyConfig,
    ) {
        val timeoutMs = config.connectTimeoutMs
        // Method negotiation: offer no-auth and, when credentials are present, username/password.
        writeFully(channel, negotiationBytes(config.hasCredentials), timeoutMs)
        val method = readFully(channel, 2, timeoutMs)
        if (method[0] != 0x05.toByte()) throw IOException("Invalid SOCKS5 version in method selection")
        when (method[1].toInt() and 0xFF) {
            0x00 -> Unit // no authentication required
            0x02 -> {
                if (!config.hasCredentials) throw IOException("SOCKS5 proxy demands username/password auth but none was configured")
                performUsernamePasswordAuth(channel, config.username.orEmpty(), config.password.orEmpty(), timeoutMs)
            }
            0xFF -> throw IOException("SOCKS5 proxy rejected all offered authentication methods")
            else -> throw IOException("SOCKS5 proxy selected unsupported auth method ${method[1].toInt() and 0xFF}")
        }

        // CONNECT request.
        writeFully(channel, connectRequestBytes(targetHost, targetPort), timeoutMs)

        // Reply: VER, REP, RSV, ATYP + bound address.
        val reply = readFully(channel, 4, timeoutMs)
        if (reply[0] != 0x05.toByte()) throw IOException("Invalid SOCKS5 version in reply")
        val rep = reply[1].toInt() and 0xFF
        if (rep != 0) throw IOException("SOCKS5 CONNECT failed: ${repName(rep)}")
        when (reply[3].toInt() and 0xFF) {
            0x01 -> readFully(channel, 4 + 2, timeoutMs) // IPv4 + port
            0x03 -> {
                val len = readFully(channel, 1, timeoutMs)[0].toInt() and 0xFF
                readFully(channel, len + 2, timeoutMs) // domain + port
            }
            0x04 -> readFully(channel, 16 + 2, timeoutMs) // IPv6 + port
            else -> throw IOException("SOCKS5 reply has unknown address type")
        }
    }

    /** Builds the method-negotiation greeting: VER, NMETHODS, and the offered methods. */
    internal fun negotiationBytes(hasCredentials: Boolean): ByteArray =
        if (hasCredentials) byteArrayOf(0x05, 0x02, 0x00, 0x02) // no-auth + username/password
        else byteArrayOf(0x05, 0x01, 0x00) // no-auth only

    /** Builds the RFC 1929 username/password sub-negotiation request. */
    internal fun authRequestBytes(username: String, password: String): ByteArray {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        if (user.size > 255 || pass.size > 255) throw IOException("SOCKS5 username/password too long")
        val request = ByteArrayOutputStream()
        request.write(0x01) // VER
        request.write(user.size)
        request.write(user)
        request.write(pass.size)
        request.write(pass)
        return request.toByteArray()
    }

    /** Builds the RFC 1928 CONNECT request payload: VER, CMD, RSV, ATYP, address, port. */
    internal fun connectRequestBytes(targetHost: String, targetPort: Int): ByteArray {
        val request = ByteArrayOutputStream()
        request.write(0x05) // VER
        request.write(0x01) // CMD = CONNECT
        request.write(0x00) // RSV
        val ipv4 = parseIpv4(targetHost)
        // A DNS name can never contain ':', so its presence means the target is an IPv6
        // literal. Those must go out as ATYP=0x04 with the 16 raw bytes; sending the text
        // form as a domain name asks the proxy to resolve "2001:db8::1" as a hostname,
        // which fails on every conforming proxy.
        val ipv6 = if (ipv4 == null && targetHost.contains(':')) {
            parseIpv6(targetHost) ?: throw IOException("Invalid IPv6 target address: $targetHost")
        } else {
            null
        }
        when {
            ipv4 != null -> {
                request.write(0x01) // ATYP = IPv4
                request.write(ipv4)
            }
            ipv6 != null -> {
                request.write(0x04) // ATYP = IPv6
                request.write(ipv6)
            }
            else -> {
                val bytes = targetHost.toByteArray(Charsets.UTF_8)
                if (bytes.size > 255) throw IOException("SOCKS5 target hostname too long")
                // Deliberately not resolved here: letting the proxy do the lookup is what
                // keeps DNS off the local network when tunnelling.
                request.write(0x03) // ATYP = domain
                request.write(bytes.size)
                request.write(bytes)
            }
        }
        request.write((targetPort shr 8) and 0xFF)
        request.write(targetPort and 0xFF)
        return request.toByteArray()
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in parts.indices) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[i] = value.toByte()
        }
        return out
    }

    /**
     * Parses an IPv6 literal (RFC 4291 §2.2) into its 16 bytes, accepting the bracketed
     * form, "::" compression and a trailing dotted-quad, and discarding any zone index
     * since a scope only means something on the local host. Returns null when [host] is
     * not an IPv6 literal. Written out by hand rather than delegating to [java.net.InetAddress]
     * so that no name resolution can be triggered as a side effect.
     */
    internal fun parseIpv6(host: String): ByteArray? {
        if (!host.contains(':')) return null
        val text = host.removeSurrounding("[", "]").substringBefore('%')
        val compressionAt = text.indexOf("::")
        // Only one run of omitted groups may appear, or the address is ambiguous.
        if (compressionAt >= 0 && text.indexOf("::", compressionAt + 2) >= 0) return null

        if (compressionAt < 0) {
            val bytes = ipv6GroupBytes(text.split(':'), allowDottedQuad = true) ?: return null
            return bytes.takeIf { it.size == 16 }
        }

        val headText = text.substring(0, compressionAt)
        val tailText = text.substring(compressionAt + 2)
        // ":::" leaves a stray colon that would otherwise be dropped as an empty group.
        if (headText.endsWith(':') || tailText.startsWith(':')) return null
        // A dotted-quad has to occupy the last 32 bits of the address, so it may only appear in the
        // groups that follow "::" — never before it. Allowed on both sides, "1.2.3.4::" parsed as
        // 1.2.3.4 padded on the *right*, which is a different address from the one written and not a
        // legal literal at all. Silently connecting somewhere else is worse than refusing.
        val headBytes = ipv6GroupBytes(
            if (headText.isEmpty()) emptyList() else headText.split(':'),
            allowDottedQuad = false,
        ) ?: return null
        val tailBytes = ipv6GroupBytes(
            if (tailText.isEmpty()) emptyList() else tailText.split(':'),
            allowDottedQuad = true,
        ) ?: return null
        // "::" has to stand for at least one omitted 16-bit group.
        if (headBytes.size + tailBytes.size > 14) return null

        val out = ByteArray(16)
        headBytes.copyInto(out, 0)
        tailBytes.copyInto(out, 16 - tailBytes.size)
        return out
    }

    /**
     * Expands colon-separated IPv6 groups.
     *
     * [allowDottedQuad] says whether an IPv4-style final group is legal in *this* run of groups. It
     * is, for the groups that end the address; it is not for the ones before a "::", which cannot be
     * the last 32 bits by definition.
     */
    private fun ipv6GroupBytes(groups: List<String>, allowDottedQuad: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        for ((index, group) in groups.withIndex()) {
            if (group.contains('.')) {
                // A dotted-quad tail is legal only as the last group, and only where the last group
                // is also the end of the address.
                if (!allowDottedQuad || index != groups.size - 1) return null
                out.write(parseIpv4(group) ?: return null)
                continue
            }
            if (group.isEmpty() || group.length > 4) return null
            if (group.any { it !in HEX_DIGITS }) return null
            val value = group.toInt(16)
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        return out.toByteArray()
    }

    private const val HEX_DIGITS = "0123456789abcdefABCDEF"

    private fun performUsernamePasswordAuth(
        channel: AsynchronousSocketChannel,
        username: String,
        password: String,
        timeoutMs: Long,
    ) {
        writeFully(channel, authRequestBytes(username, password), timeoutMs)
        val reply = readFully(channel, 2, timeoutMs)
        if (reply[0] != 0x01.toByte()) throw IOException("Invalid SOCKS5 authentication reply")
        if (reply[1] != 0x00.toByte()) throw IOException("SOCKS5 authentication failed")
    }

    private fun repName(rep: Int): String = when (rep) {
        1 -> "general failure"
        2 -> "connection not allowed"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired"
        7 -> "command not supported"
        8 -> "address type not supported"
        else -> "unknown error ($rep)"
    }

    private fun writeFully(channel: AsynchronousSocketChannel, bytes: ByteArray, timeoutMs: Long) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer).get(timeoutMs, TimeUnit.MILLISECONDS)
            if (written <= 0) throw IOException("SOCKS5 handshake write failed")
        }
    }

    private fun readFully(channel: AsynchronousSocketChannel, count: Int, timeoutMs: Long): ByteArray {
        val out = ByteArray(count)
        val buffer = ByteBuffer.wrap(out)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer).get(timeoutMs, TimeUnit.MILLISECONDS)
            // The buffer always has room here, so a non-positive count means end-of-stream or
            // a stalled channel; either way looping again would spin without ever progressing.
            if (read <= 0) throw IOException("SOCKS5 handshake connection closed")
        }
        return out
    }
}

/**
 * An [IoConnector] that routes through a SOCKS5 proxy (RFC 1928) before handing the
 * socket to SSHD. It replicates [Nio2Connector]'s session bootstrap and inserts the
 * CONNECT handshake in between.
 */
class SocksProxyConnector(
    factory: Nio2ServiceFactory,
    propertyResolver: PropertyResolver,
    handler: IoHandler,
    group: AsynchronousChannelGroup,
    resumeTasks: ExecutorService,
) : Nio2Connector(factory, propertyResolver, handler, group, resumeTasks) {

    override fun connect(
        address: SocketAddress,
        context: AttributeRepository?,
        localAddress: SocketAddress?,
    ): IoConnectFuture {
        val proxy = SocksProxyConfig.from(context) ?: return super.connect(address, context, localAddress)

        val future = DefaultIoConnectFuture(address, null)
        var channel: AsynchronousSocketChannel? = null
        try {
            val target = address as? InetSocketAddress
                ?: throw IOException("SOCKS5 requires an InetSocketAddress target")
            val socket = setSocketOptions(AsynchronousSocketChannel.open(getChannelGroup()))
            channel = socket
            if (localAddress != null) socket.bind(localAddress)

            // 1. Connect to the proxy itself.
            socket.connect(InetSocketAddress(proxy.host, proxy.port))
                .get(proxy.connectTimeoutMs, TimeUnit.MILLISECONDS)
            // 2. SOCKS5 CONNECT to the real target.
            Socks5Handshake.perform(socket, target.hostString, target.port, proxy)
            // 3. Bootstrap the SSHD session, mirroring Nio2Connector.ConnectionCompletionHandler.
            val session = createSession(propertyResolver, getIoHandler(), socket)
            if (context != null) session.setAttribute(AttributeRepository::class.java, context)
            getIoHandler().sessionCreated(session)
            (sessions as MutableMap<Long, IoSession>)[session.id] = session
            future.setSession(session)
            session.startReading()
        } catch (t: Throwable) {
            try {
                channel?.close()
            } catch (_: Exception) {
                // Ignore secondary close failures.
            }
            future.setException(t)
        }
        return future
    }
}
