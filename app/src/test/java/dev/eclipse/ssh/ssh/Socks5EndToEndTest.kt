package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.After
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Exercises the real SOCKS5 client handshake end-to-end against a minimal
 * in-process SOCKS5 (no-auth) server, verifying that bytes actually tunnel to
 * the target through the proxy.
 */
class Socks5EndToEndTest {

    private val servers = mutableListOf<ServerSocket>()
    private val channels = mutableListOf<AsynchronousSocketChannel>()

    @After
    fun tearDown() {
        channels.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `payload tunnels through in-process SOCKS5 proxy`() {
        val targetPort = startEchoTarget()
        val proxyPort = startSocks5Proxy()

        val channel = AsynchronousSocketChannel.open()
        channels += channel
        channel.connect(InetSocketAddress("127.0.0.1", proxyPort)).get(5, TimeUnit.SECONDS)

        Socks5Handshake.perform(
            channel,
            targetHost = "127.0.0.1",
            targetPort = targetPort,
            config = SocksProxyConfig("127.0.0.1", proxyPort),
        )

        // Write a line through the tunnel and read the target's reply.
        val payload = "ping\n".toByteArray(Charsets.UTF_8)
        val writeBuffer = ByteBuffer.wrap(payload)
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer).get(5, TimeUnit.SECONDS)
        }

        val readBuffer = ByteBuffer.allocate(256)
        channel.read(readBuffer).get(5, TimeUnit.SECONDS)
        readBuffer.flip()
        val response = ByteArray(readBuffer.remaining()).also { readBuffer.get(it) }.toString(Charsets.UTF_8)

        assertThat(response).startsWith("HELLO-FROM-TARGET:ping")
    }

    /** Accepts one connection, reads a line, and echoes it back with a prefix. */
    private fun startEchoTarget(): Int {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().write("HELLO-FROM-TARGET:$line".toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        }
        return server.localPort
    }

    /** Accepts one connection and speaks just enough SOCKS5 (no-auth) to relay it. */
    private fun startSocks5Proxy(): Int {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            server.accept().use { client ->
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Method negotiation: VER=5, NMETHODS, methods.
                require(input.read() == 0x05) { "Expected SOCKS5 version" }
                val nmethods = input.read()
                input.readExact(nmethods)
                output.write(byteArrayOf(0x05, 0x00)) // select no-auth
                output.flush()

                // CONNECT request: VER CMD RSV ATYP addr port.
                val header = input.readExact(4)
                val atyp = header[3].toInt() and 0xFF
                val host = when (atyp) {
                    0x01 -> InetAddress.getByAddress(input.readExact(4)).hostAddress
                    0x03 -> {
                        val len = input.read()
                        input.readExact(len).toString(Charsets.UTF_8)
                    }
                    0x04 -> InetAddress.getByAddress(input.readExact(16)).hostAddress
                    else -> throw IOException("Unexpected ATYP $atyp")
                }
                val port = (input.read() shl 8) or input.read()

                val target = Socket(host, port)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // success
                output.flush()

                // Relay in both directions until either side closes.
                val clientToTarget = thread(isDaemon = true) { input.copyTo(target.getOutputStream()) }
                target.getInputStream().copyTo(output)
                clientToTarget.join(2_000)
                target.close()
            }
        }
        return server.localPort
    }
}

private fun InputStream.readExact(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw IOException("Connection closed during SOCKS5 handshake")
        offset += read
    }
    return bytes
}

private fun InputStream.copyTo(out: OutputStream) {
    val buffer = ByteArray(4096)
    while (true) {
        val read = read(buffer)
        if (read < 0) return
        out.write(buffer, 0, read)
        out.flush()
    }
}
