package dev.eclipse.ssh.ssh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.eclipse.ssh.data.model.HostKeyChallenge
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.KeyPairGenerator
import java.security.PublicKey
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a host key gets pinned against.
 *
 * The interesting cases are the ones where the socket a key arrives on is not the host it belongs to.
 * Apache MINA reaches a `ProxyJump` target through a *local* port forward, so the session that carries
 * the target's key reports `127.0.0.1:<ephemeral port>` and nothing else; pinning that is worse than
 * useless, because the port changes on every connection (so trust never sticks and the user is asked
 * every time) and a stored entry can later be matched by an unrelated host that reuses the port (so the
 * wrong server's key is accepted silently, and "this key has changed" never fires).
 *
 * These tests drive [KnownHostsVerifier] directly. The end-to-end suites cover the ordinary path
 * through real servers, but every server they can start is on loopback, which is precisely the address
 * a forward is indistinguishable from - so the tunnelled cases can only be stated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnownHostsVerifierTest {
    private lateinit var store: KnownHostsStore
    private val challenges = mutableListOf<HostKeyChallenge>()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = KnownHostsStore(context)
        store.clear()
        challenges.clear()
    }

    @Test
    fun `a direct connection is pinned against the host that was asked for`() {
        val verifier = verifier()

        assertThat(verifier.verifyServerKey(session(address = unresolved("edge.example", 2222)), null, KEY)).isFalse()

        assertThat(challenges.single().host).isEqualTo("edge.example")
        assertThat(challenges.single().port).isEqualTo(2222)
        assertThat(challenges.single().changed).isFalse()
    }

    @Test
    fun `trusting a fingerprint is what lets the next connection through`() {
        val verifier = verifier()
        verifier.verifyServerKey(session(address = unresolved("edge.example", 2222)), null, KEY)

        store.save("edge.example", 2222, challenges.single().fingerprint)

        assertThat(verifier.verifyServerKey(session(address = unresolved("edge.example", 2222)), null, KEY)).isTrue()
    }

    @Test
    fun `a key that arrives down a jump host tunnel is pinned against the target`() {
        val verifier = verifier(TunnelledTarget(USER, "10.0.0.5", 22))

        // MINA dials the target through a forward it opened moments earlier; this is all it reports.
        verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 41_235)), null, KEY)

        assertThat(challenges.single().host).isEqualTo("10.0.0.5")
        assertThat(challenges.single().port).isEqualTo(22)
    }

    @Test
    fun `trust of a jump host target survives the forward moving to another port`() {
        val verifier = verifier(TunnelledTarget(USER, "10.0.0.5", 22))
        verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 41_235)), null, KEY)
        store.save("10.0.0.5", 22, challenges.single().fingerprint)

        // A second connection: same host, same key, a port the operating system picked afresh.
        val accepted = verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 52_907)), null, KEY)

        assertThat(accepted).isTrue()
        assertThat(store.all().keys).containsExactly("10.0.0.5:22")
    }

    @Test
    fun `a changed key on a jump host target is reported as changed`() {
        val verifier = verifier(TunnelledTarget(USER, "10.0.0.5", 22))
        store.save("10.0.0.5", 22, "SHA256:something-else-entirely")

        assertThat(verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 41_235)), null, KEY)).isFalse()

        assertThat(challenges.single().changed).isTrue()
        assertThat(challenges.single().host).isEqualTo("10.0.0.5")
    }

    @Test
    fun `two jump attempts by one user to different hosts are not guessed between`() {
        val verifier = verifier(
            TunnelledTarget(USER, "10.0.0.5", 22),
            TunnelledTarget(USER, "10.0.0.6", 22),
        )

        verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 41_235)), null, KEY)

        // Naming the wrong one would file a key under a host it does not belong to.
        assertThat(challenges.single().host).isEqualTo("127.0.0.1")
        assertThat(challenges.single().port).isEqualTo(41_235)
    }

    @Test
    fun `a jump host on this device is left to its own address`() {
        val verifier = verifier(TunnelledTarget(USER, "127.0.0.1", 2222))

        verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 41_235)), null, KEY)

        assertThat(challenges.single().port).isEqualTo(41_235)
    }

    @Test
    fun `the target of a tunnel is only substituted for the user who asked for it`() {
        val verifier = verifier(TunnelledTarget("someone-else", "10.0.0.5", 22))

        verifier.verifyServerKey(session(address = InetSocketAddress("127.0.0.1", 41_235)), null, KEY)

        assertThat(challenges.single().host).isEqualTo("127.0.0.1")
    }

    @Test
    fun `an sshd socket address is understood rather than silently refused`() {
        val verifier = verifier()

        // Not an InetSocketAddress: a cast used to miss this and return false without asking anybody,
        // which is a connection that cannot be explained or retried into working.
        verifier.verifyServerKey(session(address = SshdSocketAddress("edge.example", 2222)), null, KEY)

        assertThat(challenges.single().host).isEqualTo("edge.example")
        assertThat(challenges.single().port).isEqualTo(2222)
    }

    @Test
    fun `the socket is used when the client kept no record of what was asked for`() {
        val verifier = verifier()

        verifier.verifyServerKey(session(address = null), unresolved("10.1.2.3", 22), KEY)

        assertThat(challenges.single().host).isEqualTo("10.1.2.3")
    }

    @Test
    fun `a key that cannot be attributed to any host is refused`() {
        val verifier = verifier()

        // Nothing to compare a stored fingerprint against later, so trusting it would be meaningless.
        assertThat(verifier.verifyServerKey(session(address = null), null, KEY)).isFalse()
        assertThat(challenges).isEmpty()
    }

    private fun verifier(vararg tunnelled: TunnelledTarget) = KnownHostsVerifier(
        store = store,
        tunnelledTarget = { username ->
            tunnelled.filter { it.username == username }
                .distinctBy { it.host to it.port }
                .singleOrNull()
        },
        onChallenge = challenges::add,
    )

    private fun unresolved(host: String, port: Int): InetSocketAddress =
        InetSocketAddress.createUnresolved(host, port)

    /** A [ClientSession] that answers the two questions the verifier asks and nothing else. */
    private fun session(address: SocketAddress?, username: String = USER): ClientSession =
        Proxy.newProxyInstance(
            ClientSession::class.java.classLoader,
            arrayOf(ClientSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getConnectAddress" -> address
                "getUsername" -> username
                "hashCode" -> System.identityHashCode(address)
                "equals" -> false
                "toString" -> "stub session"
                else -> null
            }
        } as ClientSession

    private companion object {
        const val USER = "deploy"

        /** One key, reused: what is being tested is which host it gets filed under. */
        val KEY: PublicKey = KeyPairGenerator.getInstance("EC")
            .apply { initialize(256) }
            .generateKeyPair()
            .public
    }
}
