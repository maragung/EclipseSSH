package dev.eclipse.ssh.ssh

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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Per-connection HTTP CONNECT proxy configuration, carried through MINA SSHD's
 * [AttributeRepository] connection context so each session may use a different proxy.
 */
data class HttpProxyConfig(
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
    override fun toString(): String = "HttpProxyConfig(host=$host, port=$port, " +
        "connectTimeoutMs=$connectTimeoutMs, username=$username, " +
        "password=${if (password.isNullOrEmpty()) "none" else REDACTED})"

    companion object {
        /** Deliberately says nothing about the value, not even its length. */
        internal const val REDACTED = "<redacted>"

        val KEY = AttributeRepository.AttributeKey<HttpProxyConfig>()

        fun from(context: AttributeRepository?): HttpProxyConfig? = context?.getAttribute(KEY)
    }
}

/**
 * A [SshClient] whose [IoConnector] tunnels connections through an HTTP CONNECT
 * proxy when an [HttpProxyConfig] is present in the connection context.
 */
class HttpProxyClient : SshClient() {
    override fun createConnector(): IoConnector {
        val factory = getIoServiceFactory() as? Nio2ServiceFactory ?: return super.createConnector()
        return try {
            val group = Nio2ServiceFactory::class.java
                .getDeclaredField("group").apply { isAccessible = true }
                .get(factory) as AsynchronousChannelGroup
            val resuming = Nio2ServiceFactory::class.java
                .getDeclaredField("resuming").apply { isAccessible = true }
                .get(factory) as ExecutorService
            HttpProxyConnector(factory, this, getSessionFactory(), group, resuming)
        } catch (t: Throwable) {
            super.createConnector()
        }
    }

    companion object {
        fun setUpDefault(): SshClient = org.apache.sshd.client.ClientBuilder.builder()
            .factory(org.apache.sshd.common.Factory { HttpProxyClient() })
            .build()
    }
}

/** HTTP/1.1 CONNECT handshake (RFC 7231 §4.3.6) over an asynchronous socket channel. */
object HttpConnectHandshake {
    fun perform(channel: AsynchronousSocketChannel, targetHost: String, targetPort: Int, config: HttpProxyConfig) {
        val timeoutMs = config.connectTimeoutMs
        val authHeader = if (config.hasCredentials) {
            val token = Base64.getEncoder()
                .encodeToString("${config.username.orEmpty()}:${config.password.orEmpty()}".toByteArray(Charsets.UTF_8))
            "Proxy-Authorization: Basic $token\r\n"
        } else {
            ""
        }
        val authority = authority(targetHost, targetPort)
        val request = "CONNECT $authority HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            authHeader +
            "Proxy-Connection: Keep-Alive\r\n\r\n"
        writeFully(channel, request.toByteArray(Charsets.US_ASCII), timeoutMs)
        val response = readLine(channel, timeoutMs)
        if (!response.startsWith("HTTP/")) throw IOException("Invalid HTTP proxy response: $response")
        val statusLine = response.split(" ")
        if (statusLine.size < 2 || statusLine[1] != "200") {
            throw IOException("HTTP CONNECT failed: $response")
        }
        // Drain headers until the blank line that terminates the header block. The cap is what
        // stops a proxy that never sends that blank line from holding the connect attempt open
        // indefinitely: every individual read has a timeout, but an endless drip of header
        // lines keeps resetting it, so the loop itself needs a bound.
        var headerLines = 0
        while (true) {
            if (readLine(channel, timeoutMs).isEmpty()) break
            if (++headerLines > MAX_HEADER_LINES) {
                throw IOException("HTTP proxy sent more than $MAX_HEADER_LINES response headers")
            }
        }
    }

    private const val MAX_HEADER_LINES = 100

    /**
     * The authority to put in the request line and the `Host` header.
     *
     * Two separate things the text form of an HTTP request is unforgiving about, and this used to get
     * both wrong.
     *
     * An IPv6 literal has to be bracketed (RFC 3986 §3.2.2). `InetSocketAddress.getHostString`
     * returns it bare, so `CONNECT 2001:db8::1:22 HTTP/1.1` went out instead of
     * `CONNECT [2001:db8::1]:22`, which is not a request line any proxy parses — the SOCKS connector
     * next door handles v6 explicitly and this path simply could not reach a v6 host at all.
     *
     * And the host is interpolated straight into a header block, so anything in it that the proxy
     * reads as a line break appends headers of the caller's choosing — CRLF injection, with the
     * proxy's own request as the vehicle. A host name arrives here from the exported `ssh://` deep
     * link (any app on the device can send one), from an imported vault, and from an imported OpenSSH
     * config, so this is not the place to assume something upstream already checked it. Rejected
     * rather than stripped: a target that is not the one that was asked for should fail, not quietly
     * become a different connection. The bound is deliberately wider than the grammar — every
     * control character, space and DEL — because the point is to be obviously sufficient rather than
     * exactly minimal. Bytes above DEL go too: the request is encoded as US-ASCII a few lines below,
     * which would silently turn an IDN host into question marks, so it has to be punycode by now.
     */
    private fun authority(host: String, port: Int): String {
        if (host.isEmpty()) throw IOException("HTTP CONNECT requires a target host name")
        host.forEach { character ->
            if (character.code <= 0x20 || character.code >= 0x7F) {
                throw IOException(
                    "Illegal character in target host name: 0x%02x".format(character.code),
                )
            }
        }
        // Already bracketed if it came from somewhere that formats v6 addresses properly.
        val bracketed = host.startsWith('[') && host.endsWith(']')
        return if (!bracketed && host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    private fun writeFully(channel: AsynchronousSocketChannel, bytes: ByteArray, timeoutMs: Long) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer).get(timeoutMs, TimeUnit.MILLISECONDS)
            if (written <= 0) throw IOException("HTTP CONNECT handshake write failed")
        }
    }

    private fun readLine(channel: AsynchronousSocketChannel, timeoutMs: Long): String {
        val line = StringBuilder()
        while (true) {
            val byte = ByteArray(1)
            val buffer = ByteBuffer.wrap(byte)
            val read = channel.read(buffer).get(timeoutMs, TimeUnit.MILLISECONDS)
            if (read < 0) throw IOException("HTTP CONNECT handshake connection closed")
            val value = byte[0].toInt() and 0xFF
            if (value == '\n'.code) return line.toString().trimEnd('\r')
            if (value != '\r'.code) line.append(value.toChar())
            if (line.length > 16 * 1024) throw IOException("HTTP proxy response too long")
        }
    }
}

/**
 * An [IoConnector] that routes through an HTTP CONNECT proxy before handing the socket
 * to SSHD, replicating [Nio2Connector]'s session bootstrap like the SOCKS connector.
 */
class HttpProxyConnector(
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
        val proxy = HttpProxyConfig.from(context) ?: return super.connect(address, context, localAddress)

        val future = DefaultIoConnectFuture(address, null)
        var channel: AsynchronousSocketChannel? = null
        try {
            val target = address as? InetSocketAddress
                ?: throw IOException("HTTP CONNECT requires an InetSocketAddress target")
            val socket = setSocketOptions(AsynchronousSocketChannel.open(getChannelGroup()))
            channel = socket
            if (localAddress != null) socket.bind(localAddress)

            socket.connect(InetSocketAddress(proxy.host, proxy.port))
                .get(proxy.connectTimeoutMs, TimeUnit.MILLISECONDS)
            HttpConnectHandshake.perform(socket, target.hostString, target.port, proxy)

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
