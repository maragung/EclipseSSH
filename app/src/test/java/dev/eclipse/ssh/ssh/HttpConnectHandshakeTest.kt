package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Exercises the HTTP CONNECT handshake against a minimal in-process proxy. Everything a
 * proxy can do wrong here surfaces as a failed connection with no useful message unless the
 * client checks for it, so the failure paths matter as much as the tunnel itself.
 */
class HttpConnectHandshakeTest {

    private val servers = mutableListOf<ServerSocket>()
    private val channels = mutableListOf<AsynchronousSocketChannel>()

    /** Request blocks the fake proxy has received, so tests can assert on what was sent. */
    private val requests = LinkedBlockingQueue<List<String>>()

    @After
    fun tearDown() {
        channels.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `payload tunnels through in-process HTTP CONNECT proxy`() {
        val targetPort = startEchoTarget()
        val proxyPort = startTunnellingProxy()
        val channel = connect(proxyPort)

        HttpConnectHandshake.perform(channel, "127.0.0.1", targetPort, HttpProxyConfig("127.0.0.1", proxyPort))

        val payload = ByteBuffer.wrap("ping\n".toByteArray(Charsets.UTF_8))
        while (payload.hasRemaining()) channel.write(payload).get(5, TimeUnit.SECONDS)

        val readBuffer = ByteBuffer.allocate(256)
        channel.read(readBuffer).get(5, TimeUnit.SECONDS)
        readBuffer.flip()
        val response = ByteArray(readBuffer.remaining()).also { readBuffer.get(it) }.toString(Charsets.UTF_8)

        assertThat(response).startsWith("HELLO-FROM-TARGET:ping")
    }

    @Test
    fun `request line and host header name the target, not the proxy`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        HttpConnectHandshake.perform(channel, "files.example.com", 2222, HttpProxyConfig("127.0.0.1", proxyPort))

        val request = takeRequest()
        assertThat(request.first()).isEqualTo("CONNECT files.example.com:2222 HTTP/1.1")
        assertThat(request).contains("Host: files.example.com:2222")
    }

    @Test
    fun `an IPv6 target is bracketed so the request line is well formed`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        HttpConnectHandshake.perform(channel, "2001:db8::1", 22, HttpProxyConfig("127.0.0.1", proxyPort))

        // Unbracketed, "CONNECT 2001:db8::1:22" has no way to say where the address ends and the port
        // begins, and no conforming proxy accepts it — so before this the HTTP path could not reach a
        // v6 host at all, while the SOCKS path could.
        val request = takeRequest()
        assertThat(request.first()).isEqualTo("CONNECT [2001:db8::1]:22 HTTP/1.1")
        assertThat(request).contains("Host: [2001:db8::1]:22")
    }

    @Test
    fun `an already bracketed IPv6 target is not bracketed twice`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        HttpConnectHandshake.perform(channel, "[2001:db8::1]", 22, HttpProxyConfig("127.0.0.1", proxyPort))

        assertThat(takeRequest().first()).isEqualTo("CONNECT [2001:db8::1]:22 HTTP/1.1")
    }

    @Test
    fun `a host name carrying a line break cannot smuggle headers into the request`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        // What this defends against: the host reaches the request line from the exported ssh:// deep
        // link, from an imported vault and from an imported OpenSSH config, so it is attacker-supplied
        // on all three paths. Interpolated unchecked, this appended a header of the sender's choosing
        // to the proxy's own request.
        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(
                channel,
                "good.example.com:22 HTTP/1.1\r\nX-Injected: yes\r\nHost: evil.example.com",
                22,
                HttpProxyConfig("127.0.0.1", proxyPort),
            )
        }

        assertThat(error).hasMessageThat().contains("Illegal character")
        // Nothing was sent, so there is nothing for a proxy to have misparsed.
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a host name with a space or a control character is rejected before anything is sent`() {
        // A proxy each: the fake accepts one connection and then stops, so reusing a port across the
        // loop would leave the second attempt blocked in connect() rather than reaching the check.
        listOf("evil host", "tab\ttarget", "null\u0000byte", "del\u007f").forEach { host ->
            val proxyPort = startScriptedProxy(ESTABLISHED)
            val error = assertThrows(IOException::class.java) {
                HttpConnectHandshake.perform(connect(proxyPort), host, 22, HttpProxyConfig("127.0.0.1", proxyPort))
            }
            assertThat(error).hasMessageThat().contains("Illegal character")
        }
    }

    @Test
    fun `a non-ascii host is rejected rather than sent as question marks`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        // The request is encoded US-ASCII, so an IDN host would have gone out as "?????" and connected
        // somewhere unintended. It has to be punycode by the time it reaches here.
        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "münchen.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }

        assertThat(error).hasMessageThat().contains("Illegal character")
    }

    @Test
    fun `an empty target host is rejected`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }

        assertThat(error).hasMessageThat().contains("requires a target host")
    }

    @Test
    fun `credentials are sent as a basic proxy authorization header`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        HttpConnectHandshake.perform(
            channel,
            "edge.example.com",
            22,
            HttpProxyConfig("127.0.0.1", proxyPort, username = "alice", password = "s3cret"),
        )

        val token = Base64.getEncoder().encodeToString("alice:s3cret".toByteArray(Charsets.UTF_8))
        assertThat(takeRequest()).contains("Proxy-Authorization: Basic $token")
    }

    @Test
    fun `no authorization header is sent without credentials`() {
        val proxyPort = startScriptedProxy(ESTABLISHED)
        val channel = connect(proxyPort)

        HttpConnectHandshake.perform(channel, "edge.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))

        assertThat(takeRequest().none { it.startsWith("Proxy-Authorization") }).isTrue()
    }

    @Test
    fun `an authentication challenge fails the handshake instead of tunnelling`() {
        val proxyPort = startScriptedProxy(
            "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n",
        )
        val channel = connect(proxyPort)

        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "edge.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }
        assertThat(error).hasMessageThat().contains("407")
    }

    @Test
    fun `a refused target is reported rather than treated as a tunnel`() {
        val proxyPort = startScriptedProxy("HTTP/1.1 502 Bad Gateway\r\n\r\n")
        val channel = connect(proxyPort)

        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "edge.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }
        assertThat(error).hasMessageThat().contains("502")
    }

    @Test
    fun `a non-HTTP response fails the handshake`() {
        // A captive portal or a plain TCP service on the proxy port answers with anything at all.
        val proxyPort = startScriptedProxy("SSH-2.0-OpenSSH_9.6\r\n")
        val channel = connect(proxyPort)

        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "edge.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }
        assertThat(error).hasMessageThat().contains("Invalid HTTP proxy response")
    }

    @Test
    fun `a header block that never ends is abandoned instead of hanging`() {
        // Every individual read succeeds inside its timeout, so only a bound on the number of
        // header lines stops this from holding the connect attempt open forever.
        val proxyPort = startScriptedProxy(
            ESTABLISHED_STATUS + (1..500).joinToString("") { "X-Filler-$it: padding\r\n" },
        )
        val channel = connect(proxyPort)

        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "edge.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }
        assertThat(error).hasMessageThat().contains("response headers")
    }

    @Test
    fun `a proxy that closes without replying fails the handshake`() {
        val proxyPort = startScriptedProxy(response = null)
        val channel = connect(proxyPort)

        val error = assertThrows(IOException::class.java) {
            HttpConnectHandshake.perform(channel, "edge.example.com", 22, HttpProxyConfig("127.0.0.1", proxyPort))
        }
        assertThat(error).hasMessageThat().contains("closed")
    }

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    private fun connect(port: Int): AsynchronousSocketChannel {
        val channel = AsynchronousSocketChannel.open()
        channels += channel
        channel.connect(InetSocketAddress("127.0.0.1", port)).get(5, TimeUnit.SECONDS)
        return channel
    }

    private fun takeRequest(): List<String> =
        requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "proxy never received a request" }

    /** Accepts one connection, records the request block, then hands off to [respond]. */
    private fun startProxy(respond: (List<String>, Socket) -> Unit): Int {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            // The client aborts on purpose in several tests, so writes here are expected to fail.
            runCatching {
                server.accept().use { client ->
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = client.getInputStream().readCrlfLine() ?: break
                        if (line.isEmpty()) break
                        lines += line
                    }
                    requests.put(lines)
                    respond(lines, client)
                }
            }
        }
        return server.localPort
    }

    /** Replies with a fixed byte-for-byte response, or closes without replying when null. */
    private fun startScriptedProxy(response: String?): Int = startProxy { _, client ->
        if (response != null) {
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()
        }
    }

    /** Honours the CONNECT request and relays bytes both ways, like a real proxy. */
    private fun startTunnellingProxy(): Int = startProxy { lines, client ->
        val authority = lines.first().split(" ")[1]
        val target = Socket(authority.substringBeforeLast(':'), authority.substringAfterLast(':').toInt())
        client.getOutputStream().write(ESTABLISHED.toByteArray(Charsets.US_ASCII))
        client.getOutputStream().flush()

        val upstream = thread(isDaemon = true) {
            runCatching { client.getInputStream().relayTo(target.getOutputStream()) }
        }
        runCatching { target.getInputStream().relayTo(client.getOutputStream()) }
        upstream.join(2_000)
        target.close()
    }

    /** Accepts one connection, reads a line, and echoes it back with a prefix. */
    private fun startEchoTarget(): Int {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write("HELLO-FROM-TARGET:$line".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            }
        }
        return server.localPort
    }

    private companion object {
        const val ESTABLISHED_STATUS = "HTTP/1.1 200 Connection established\r\n"
        const val ESTABLISHED = ESTABLISHED_STATUS + "Proxy-Agent: eclipse-test\r\n\r\n"
    }
}

/** Reads one CRLF-terminated line, or null at end of stream. */
private fun InputStream.readCrlfLine(): String? {
    val line = StringBuilder()
    while (true) {
        val value = read()
        if (value < 0) return if (line.isEmpty()) null else line.toString()
        if (value == '\n'.code) return line.toString().trimEnd('\r')
        line.append(value.toChar())
    }
}

private fun InputStream.relayTo(out: OutputStream) {
    val buffer = ByteArray(4096)
    while (true) {
        val read = read(buffer)
        if (read < 0) return
        out.write(buffer, 0, read)
        out.flush()
    }
}
