package dev.eclipse.ssh.rdp

import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.ssh.PortForwardingManager
import java.nio.file.Files
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.google.common.truth.Truth.assertThat

/**
 * The RDP engine's state machine, without its engine.
 *
 * The native half of [RdpTunnel] cannot run in a JVM: [LibFreeRDP]'s static initializer
 * loads `freerdp-android`, so the first test to call [RdpTunnel.start] would die on an
 * unsatisfied link, not on a bug. What that leaves - and what this suite pins - is every
 * guarantee the viewer leans on that does not need the wire: the single-shot teardown
 * story (an abandon is the caller's own sentence and nothing rewrites it, a stop is
 * Closed and stays Closed), the input guards that keep the viewer's controls inert
 * before a session is live, and a credentials hand-off nobody is waiting for. The wire
 * itself is exercised where the library actually exists - CI's build of the :freerdp
 * module links this wrapper against the bridge, and an instrumented run on a real
 * device is where a live RDP session will one day be proven.
 *
 * The embedded SSH server exists only so the refused-start test can hand [RdpTunnel.start]
 * a [ClientSession] it will never use: the single-shot guard fires before the session,
 * the forward or the engine is so much as touched. It is a real server per the house rule
 * that a tunnel suite builds real software around its subject, but no tunnel is ever
 * started against it here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RdpTunnelTest {

    private lateinit var sshClient: SshClient
    private lateinit var session: ClientSession

    @Before
    fun connectSession() {
        sshClient = SshClient.setUpDefaultClient().apply {
            // A stand-in verifier is all the test needs: the server's key was generated in
            // this JVM a moment before, and the tunnel's subject is not host-key trust.
            serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
        session = sshClient.connect(USER, LOOPBACK, serverPort).verify(15, TimeUnit.SECONDS).session
        session.addPasswordIdentity(PASSWORD)
        session.auth().verify(15, TimeUnit.SECONDS)
    }

    @After
    fun endEverySession() {
        runCatching { session.close() }
        runCatching { sshClient.stop() }
    }

    @Test
    fun `an abandon before any start fails with the caller's reason`() {
        // The viewer's precondition failure - the host has no session to ride - is not the
        // tunnel's own error, so the reason is the caller's sentence, not a stack trace's.
        val t = RdpTunnel(PortForwardingManager())
        t.abandon("the host is not connected")

        assertThat(t.state.value).isEqualTo(RdpTunnelState.Failed("the host is not connected"))
        // And it stays single-shot: a start on an abandoned tunnel is refused before the
        // session, the forward or the engine is touched, rather than run over the teardown
        // the abandon already did.
        val refused = runCatching {
            t.start(
                RuntimeEnvironment.getApplication(),
                session,
                RemoteDesktopTarget(port = 3389),
            )
        }
        assertThat(refused.isFailure).isTrue()
        // A stop after an abandon is a no-op, not a second teardown or a state overwrite: the
        // session ended against the user's will, and Closed would rewrite that story.
        t.stop()
        assertThat(t.state.value).isEqualTo(RdpTunnelState.Failed("the host is not connected"))
    }

    @Test
    fun `a stop before any start ends Closed and nothing rewrites it`() {
        // The viewer torn down before connecting: the honest end state is Closed, not Failed -
        // nothing went wrong - and the finished flag makes every later ending a no-op.
        val t = RdpTunnel(PortForwardingManager())
        t.stop()

        assertThat(t.state.value).isEqualTo(RdpTunnelState.Closed)
        // A second stop is a no-op, and so is an abandon that arrives after it: whichever
        // ending lands first is the story that stays.
        t.stop()
        t.abandon("too late")
        assertThat(t.state.value).isEqualTo(RdpTunnelState.Closed)
    }

    @Test
    fun `input before a session is live is a no-op, not a crash`() {
        // The viewer hides its controls until Connected, and the tunnel is the belt to that
        // suspenders: every input method guards on the engine instance and returns before
        // touching it. This is also the JVM-safety net for this suite - a guard that slipped
        // would load LibFreeRDP and fail on the missing library, not on the assertion.
        val t = RdpTunnel(PortForwardingManager())

        t.moveMouse(3, 4)
        t.mouseButton(1, pressed = true)
        t.mouseButton(1, pressed = false)
        t.scroll(up = true)
        t.scroll(up = false)
        t.type("text")
        t.requestResolution(1280, 720)

        assertThat(t.state.value).isEqualTo(RdpTunnelState.Idle)
        assertThat(t.frames.value).isNull()
    }

    @Test
    fun `credentials submitted with nobody waiting are dropped, not parked`() {
        // submitCredentials completes whatever challenge is outstanding, and there is none:
        // the hand-off must be a no-op that leaves the tunnel Connecting-or-Idle, never a
        // crash and never a phantom answer a later challenge could pick up.
        val t = RdpTunnel(PortForwardingManager())

        t.submitCredentials(username = "u", domain = "d", password = "p")

        assertThat(t.state.value).isEqualTo(RdpTunnelState.Idle)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Local test credentials only: this server lives and dies inside this JVM. */
        const val USER = "rdp-user"
        const val PASSWORD = "rdp-pass-456"

        var serverPort = 0

        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            server = SshServer.setUpDefaultServer().apply {
                // Port 0: the debug and release unit-test JVMs overlap, and a fixed port would
                // give the loser of the bind a server that never started.
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("rdp-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
                }
                userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
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
