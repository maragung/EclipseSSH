package dev.eclipse.ssh.vnc

import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.ssh.PortForwardingManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.apache.sshd.client.SshClient
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.client.session.ClientSession
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.common.truth.Truth.assertThat

/**
 * The VNC engine against real software on both sides of itself.
 *
 * The subject is [VncTunnel] - the SSH forward, the socket, the vernacular handshake - and
 * everything around it is real, per the house rule that a tunnel suite measures bytes, not
 * bookkeeping: an embedded MINA SSH server in this JVM, a hand-written RFB 3.8 server on
 * loopback answering the protocol's every message, and a real [SshClient] between them. The
 * one thing this suite does not use is the app's `MainViewModel`, because the tunnel is not
 * a view-model feature: it takes a `ClientSession` and a target, and a bare authenticated
 * session is a smaller, faster thing to build than a whole activity.
 *
 * The RFB server is the interesting prop. It performs the handshake exactly (version
 * exchange, security-type list, the 3.8 security *result* that must follow even a None
 * choice, ServerInit), then honours the pixel format the *client* picks - which is the
 * truth of RFB: the client dictates the format and the server paints in it - and answers
 * every framebuffer request with a full raw frame of known pixels. It records every key and
 * pointer event that reaches it, which is how "input is sent" is asserted from the far end
 * of the wire rather than from the tunnel's own memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VncTunnelTest {

    private lateinit var sshClient: SshClient
    private lateinit var session: ClientSession
    private var tunnel: VncTunnel? = null

    @Before
    fun connectSession() {
        sshClient = SshClient.setUpDefaultClient().apply {
            // A stand-in verifier is all the test needs: the server's key is generated in this
            // JVM a moment before, and the tunnel's subject is not host-key trust.
            serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
        session = sshClient.connect(USER, LOOPBACK, serverPort).verify(15, TimeUnit.SECONDS).session
        session.addPasswordIdentity(PASSWORD)
        session.auth().verify(15, TimeUnit.SECONDS)
    }

    @After
    fun endEverySession() {
        runCatching { tunnel?.stop() }
        runCatching { session.close() }
        runCatching { sshClient.stop() }
    }

    // ---------------------------------------------------------------------------------------------
    // The session itself
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a tunnel connects through the forward and delivers a frame`() {
        val rfb = FakeRfbServer()
        try {
            val t = VncTunnel(PortForwardingManager())
            tunnel = t
            t.start(session, RemoteDesktopTarget(port = rfb.port))

            pumpUntil("the tunnel never connected: " + t.state.value) {
                t.state.value is VncTunnelState.Connected
            }
            val connected = t.state.value as VncTunnelState.Connected
            assertThat(connected.width).isEqualTo(FRAME_WIDTH)
            assertThat(connected.height).isEqualTo(FRAME_HEIGHT)

            // The frame is the whole point: a bitmap of the fake server's pixels, in the size
            // ServerInit announced, with the colour the server painted.
            pumpUntil("no frame ever arrived: " + t.state.value) { t.frames.value != null }
            val frame = checkNotNull(t.frames.value)
            assertThat(frame.width).isEqualTo(FRAME_WIDTH)
            assertThat(frame.height).isEqualTo(FRAME_HEIGHT)
            assertThat(frame.sequence).isAtLeast(1L)
            val center = frame.bitmap.getPixel(FRAME_WIDTH / 2, FRAME_HEIGHT / 2)
            assertThat(center).isEqualTo(0xFFFF0000.toInt())
        } finally {
            runCatching { rfb.close() }
        }
    }

    @Test
    fun `input reaches the far end of the tunnel`() {
        val rfb = FakeRfbServer()
        try {
            val t = VncTunnel(PortForwardingManager())
            tunnel = t
            t.start(session, RemoteDesktopTarget(port = rfb.port))
            pumpUntil("the tunnel never connected: " + t.state.value) {
                t.state.value is VncTunnelState.Connected
            }

            t.moveMouse(3, 4)
            t.mouseButton(1, pressed = true)
            t.mouseButton(1, pressed = false)
            t.key(0x61, pressed = true) // 'a'
            t.key(0x61, pressed = false)
            t.type("b")

            // Three pointer events (move, press, release) and two key *presses* ('a' and 'b');
            // releases carry a flag, not a separate event, which is the protocol's own accounting.
            pumpUntil("the RFB server never saw the input: seen=${rfb.pointerEvents}/${rfb.keyEvents}") {
                rfb.pointerEvents.get() >= 3 && rfb.keyEvents.get() >= 2
            }
            // Coordinates, masks and keysyms, from the server's side of the socket: the move and
            // the release carry no button, the press carries button 1.
            assertThat(rfb.recordedPointer).containsAtLeast("3,4,0", "3,4,1", "3,4,0")
            assertThat(rfb.recordedKeys).containsExactly(0x61, 0x62).inOrder()
        } finally {
            runCatching { rfb.close() }
        }
    }

    @Test
    fun `a stop closes the session and releases the forward`() {
        val rfb = FakeRfbServer()
        try {
            val t = VncTunnel(PortForwardingManager())
            tunnel = t
            t.start(session, RemoteDesktopTarget(port = rfb.port))
            pumpUntil("the tunnel never connected: " + t.state.value) {
                t.state.value is VncTunnelState.Connected
            }

            t.stop()
            pumpUntil("the tunnel never closed: " + t.state.value) { t.state.value == VncTunnelState.Closed }

            // The RFB server saw the socket go away - the viewer's stop is not a local illusion.
            pumpUntil("the RFB server never saw a disconnect") { rfb.disconnected.await(1, TimeUnit.MILLISECONDS) }
            // A second stop is a no-op, not a second teardown or a state overwrite.
            t.stop()
            assertThat(t.state.value).isEqualTo(VncTunnelState.Closed)
        } finally {
            runCatching { rfb.close() }
        }
    }

    @Test
    fun `a target nothing answers fails with the socket's own story`() {
        // A port nothing is listening on - borrowed from the OS and given straight back - which is
        // the exact shape a user hits when the VNC server is not running: the *forward* binds fine,
        // the server-side dial is refused, and the tunnel must end Failed, not park in Connecting
        // forever on a socket that closed the moment it opened.
        val deadPort = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
        val t = VncTunnel(PortForwardingManager())
        tunnel = t
        t.start(session, RemoteDesktopTarget(port = deadPort))

        pumpUntil("the tunnel never failed: " + t.state.value) {
            t.state.value is VncTunnelState.Failed
        }
        val failed = t.state.value as VncTunnelState.Failed
        // One line for a screen, not a stack trace - and not empty, which was the bug class
        // this assertion exists for in the hand-forward paths.
        assertThat(failed.reason).isNotEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // The wire, and waiting on it
    // ---------------------------------------------------------------------------------------------

    /**
     * Waits until [condition] holds, on a plain thread - the tunnel's flows are written from
     * vernacular's threads and read here, so there is no main looper to pump and no compose
     * clock to advance; the honest instrument is a deadline.
     */
    private fun pumpUntil(describe: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Thread.sleep(20)
        }
        check(condition()) { "timed out after ${TEST_TIMEOUT_MS}ms: $describe" }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
        const val LOOPBACK = "127.0.0.1"

        /** Local test credentials only: this server lives and dies inside this JVM. */
        const val USER = "vnc-user"
        const val PASSWORD = "vnc-pass-456"

        const val FRAME_WIDTH = 16
        const val FRAME_HEIGHT = 8

        var serverPort = 0

        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            server = SshServer.setUpDefaultServer().apply {
                // Port 0: the debug and release unit-test JVMs overlap, and a fixed port would give
                // the loser of the bind a server that never started.
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("vnc-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
                }
                userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
                // The learned lesson from the forwarding suite, one layer down: a local forward's
                // destination is dialed by the *server* through a direct-tcpip channel, and the
                // builder's default filter refuses every one of those. Without an accepting filter
                // here, the tunnel under test binds its port and then waits forever for a frame.
                forwardingFilter = AcceptAllForwardingFilter.INSTANCE
                start()
            }
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
        }
    }
}

/**
 * A hand-written RFB 3.8 server: the protocol's half of the conversation, and nothing else.
 *
 * It does just what the handshake and the update loop require - version exchange, None
 * security with the 3.8 result word, ServerInit, the client's SetPixelFormat and
 * SetEncodings, a full raw frame for every framebuffer request - because the engine under
 * test is the *client*, and the point of the prop is to be a server worth connecting to, not
 * a VNC implementation. Every key and pointer event is recorded, so input is asserted at the
 * far end of the socket; `disconnected` latches when the socket goes away, which is how a
 * deliberate stop is told from a stalled one.
 *
 * Pixels are painted in whatever format the client asked for in its SetPixelFormat - the
 * truth of the protocol, where the client dictates and the server obeys - so the test's
 * `0xFFFF0000` assertion holds no matter which colour depth vernacular negotiates.
 */
private class FakeRfbServer : AutoCloseable {

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    val pointerEvents = AtomicInteger(0)
    val keyEvents = AtomicInteger(0)

    /** Pointer events as "x,y,mask" - compared by content, which `IntArray` in a list never is. */
    val recordedPointer = Collections.synchronizedList(mutableListOf<String>())
    val recordedKeys = Collections.synchronizedList(mutableListOf<Int>())

    /** Latched by the serve loop when the client's socket ends, for whoever wants to know. */
    val disconnected = CountDownLatch(1)

    /** The pixel format the client last set; painted from until it changes. */
    @Volatile private var pixelFormat = intArrayOf(32, 24, 0, 1, 255, 255, 255, 16, 8, 0)

    private val acceptOnce = thread(name = "fake-rfb-accept") {
        runCatching { server.accept().use { socket -> serve(socket) } }
        disconnected.countDown()
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())

        // Handshake: version, then the security the server offers - None only, which is what a
        // VNC server behind an SSH tunnel is normally configured for, the tunnel being the auth.
        output.writeBytes("RFB 003.008\n")
        output.flush()
        input.readFully(ByteArray(12)) // the client's version
        output.writeByte(1); output.writeByte(1) // one security type: None
        output.flush()
        require(input.readUnsignedByte() == 1) { "client did not choose None" }
        output.writeInt(0) // RFB 3.8: the security *result* follows even a None choice
        output.flush()
        input.readUnsignedByte() // ClientInit: the shared flag

        // ServerInit: geometry, the server's *offered* pixel format (32bpp true colour, the
        // textbook shape), and a name. The client answers with a SetPixelFormat of its own.
        output.writeShort(FRAME_WIDTH)
        output.writeShort(FRAME_HEIGHT)
        writePixelFormat(output, pixelFormat)
        val name = "fake".toByteArray()
        output.writeInt(name.size)
        output.write(name)
        output.flush()

        // The message loop: everything a client may send between init and goodbye. The client
        // dictates the pixel format and the encoding list; this server paints raw in whatever
        // format it was last given, which is all a framebuffer request asks of it.
        while (true) {
            val type = input.read()
            if (type < 0) return
            when (type) {
                0 -> { // SetPixelFormat
                    input.readFully(ByteArray(3)) // padding
                    val format = IntArray(10)
                    format[0] = input.readUnsignedByte() // bitsPerPixel
                    format[1] = input.readUnsignedByte() // depth
                    format[2] = input.readUnsignedByte() // bigEndian
                    format[3] = input.readUnsignedByte() // trueColour
                    format[4] = input.readUnsignedShort() // redMax
                    format[5] = input.readUnsignedShort() // greenMax
                    format[6] = input.readUnsignedShort() // blueMax
                    format[7] = input.readUnsignedByte() // redShift
                    format[8] = input.readUnsignedByte() // greenShift
                    format[9] = input.readUnsignedByte() // blueShift
                    input.readFully(ByteArray(3)) // padding
                    pixelFormat = format
                }
                1 -> input.readFully(ByteArray(5 + 6 * input.readUnsignedShort())) // FixColourMapEntries
                2 -> { // SetEncodings
                    input.readFully(ByteArray(1)) // padding
                    input.readFully(ByteArray(4 * input.readUnsignedShort()))
                }
                3 -> { // FramebufferUpdateRequest
                    input.readFully(ByteArray(9)) // incremental + x, y, w, h
                    paint(output)
                }
                4 -> { // KeyEvent
                    val down = input.readUnsignedByte()
                    input.readFully(ByteArray(2)) // padding
                    val keySym = input.readInt()
                    if (down == 1) {
                        keyEvents.incrementAndGet()
                        recordedKeys.add(keySym)
                    }
                }
                5 -> { // PointerEvent
                    val mask = input.readUnsignedByte()
                    val x = input.readUnsignedShort()
                    val y = input.readUnsignedShort()
                    pointerEvents.incrementAndGet()
                    recordedPointer.add("$x,$y,$mask")
                }
                6 -> { // ClientCutText
                    input.readFully(ByteArray(3)) // padding
                    input.readFully(ByteArray(input.readInt()))
                }
                else -> return // a message this prop does not speak: end the session
            }
        }
    }

    /** One full raw frame in the client's own pixel format, red corner to corner. */
    private fun paint(output: DataOutputStream) {
        val format = pixelFormat
        val bytesPerPixel = format[0] / 8
        output.writeByte(0) // FramebufferUpdate
        output.writeByte(0) // padding
        output.writeShort(1) // one rectangle
        output.writeShort(0) // x
        output.writeShort(0) // y
        output.writeShort(FRAME_WIDTH)
        output.writeShort(FRAME_HEIGHT)
        output.writeInt(0) // Raw
        // Red at the client's own max and shift: value = redMax << redShift, written big-endian,
        // because the decoder on the other side reads exactly that many bytes in exactly that
        // order. Whatever depth vernacular asked for, the round trip lands on 0xFFFF0000.
        val value = format[4] shl format[7]
        for (i in 0 until FRAME_WIDTH * FRAME_HEIGHT) {
            for (b in bytesPerPixel - 1 downTo 0) {
                output.writeByte((value ushr (8 * b)) and 0xFF)
            }
        }
        output.flush()
    }

    private fun writePixelFormat(output: DataOutputStream, format: IntArray) {
        output.writeByte(format[0]) // bitsPerPixel
        output.writeByte(format[1]) // depth
        output.writeByte(format[2]) // bigEndian
        output.writeByte(format[3]) // trueColour
        output.writeShort(format[4]) // redMax
        output.writeShort(format[5]) // greenMax
        output.writeShort(format[6]) // blueMax
        output.writeByte(format[7]) // redShift
        output.writeByte(format[8]) // greenShift
        output.writeByte(format[9]) // blueShift
        output.write(ByteArray(3)) // padding
    }

    override fun close() {
        runCatching { server.close() }
    }
}
