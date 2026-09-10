package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.settings.SettingsRepository
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.sshd.agent.SshAgent
import org.apache.sshd.agent.local.ProxyAgentFactory
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.common.signature.SignatureFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * SSH agent forwarding, end to end against a real Apache MINA SSHD server.
 *
 * The whole client path is the app's own: [SshConnectionManager] builds the client, the factory
 * decides which sessions get an agent, and [TerminalChannel.open] asks for forwarding. The server
 * plays "host A" with a [ProxyAgentFactory], which is exactly what the mechanism looks like from a
 * server that honours `auth-agent-req@openssh.com`: the request is accepted, the shell is handed
 * an `SSH_AUTH_SOCK`, and a server-side agent client opens `auth-agent@openssh.com` back at the
 * phone. What the test then drives through that channel - list identities, sign, and the refusal
 * of a remote `ssh-add` - is what the administrator of a real forwarded host could do.
 *
 * Lean on purpose: two identities (the RSA/SHA-2 case and the Ed25519 case) and one signature
 * round trip prove the whole path; the per-key-kind and refusal behaviour is pinned by
 * [StoreBackedAgentTest] where failures are easier to read.
 */
@RunWith(RobolectricTestRunner::class)
class AgentForwardingEndToEndTest {

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val USER = "agent-user"
        private const val PASSWORD = "agent-pass-123"
        private const val WAIT_MS = 20_000L

        private var serverPort = 0
        private lateinit var server: SshServer

        /** Every shell the server has started, so a test can inspect what its own shell received. */
        private val shells = CopyOnWriteArrayList<EnvRecordingShell>()

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val root: Path = Files.createTempDirectory("eclipse-ssh-agent-fwd")
            server = SshServer.setUpDefaultServer()
            server.port = 0
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser"))
            server.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == USER && password == PASSWORD
            }
            // The server-side half of forwarding: accept the request, and expose an agent client
            // that opens the agent channel back at whoever asked. The AgentForwardingFilter default
            // already allows the request, so nothing else is needed here.
            server.agentFactory = ProxyAgentFactory()
            server.shellFactory = ShellFactory { EnvRecordingShell().also(shells::add) }
            server.start()
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
        }
    }

    /**
     * A shell that only records its environment and the session it belongs to.
     *
     * The environment is the observable half of the feature: `SSH_AUTH_SOCK` appears in it if and
     * only if the client's `auth-agent-req` arrived and was honoured, so the negative test - a
     * session without forwarding - can assert on it rather than on some client-side state that
     * would pass even if the request were never sent.
     */
    private class EnvRecordingShell : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null

        @Volatile private var environment: Environment? = null
        @Volatile private var serverSession: org.apache.sshd.server.session.ServerSession? = null

        fun env(): Map<String, String>? =
            environment?.env?.let { runCatching { HashMap(it) }.getOrNull() }

        fun session(): org.apache.sshd.server.session.ServerSession? = serverSession

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            environment = env
            serverSession = channel.serverSession
            val inn = input ?: return
            Thread {
                val buffer = ByteArray(4096)
                try {
                    while (inn.read(buffer) >= 0) {
                        output?.write("ok\n".toByteArray())
                        output?.flush()
                    }
                } catch (_: Exception) {
                    // The channel or transport went away; nothing to report from here.
                }
                runCatching { exit?.onExit(0) }
            }.apply {
                isDaemon = true
                name = "agent-fwd-shell"
            }.start()
        }

        override fun destroy(channel: ChannelSession) = Unit
    }

    private fun hostProfile(forwarding: Boolean) = HostProfile(
        name = "agent-forwarding",
        host = LOOPBACK,
        username = USER,
        port = serverPort,
        authMethod = AuthMethod.PASSWORD,
        connectTimeoutSeconds = 60,
        agentForwarding = forwarding,
    )

    /** Two identities the vault would hold: the RSA/SHA-2 case and the Ed25519 case. */
    private fun vaultIdentities(): List<AgentIdentity> {
        val rsa = SshKeyAlgorithm.RSA_2048.generate().let {
            AgentIdentity(SshKeyLoader.load(it.privatePem.toByteArray(), it.defaultPrivateName), "id_rsa")
        }
        val ed25519 = AgentIdentity(
            SshKeyLoader.load(
                checkNotNull(javaClass.classLoader?.getResourceAsStream("keys/plain_ed25519")) {
                    "Missing fixture keys/plain_ed25519"
                }.use { it.readBytes() },
                "id_ed25519",
            ),
            "id_ed25519",
        )
        return listOf(rsa, ed25519)
    }

    private fun newManager(): SshConnectionManager {
        val context = RuntimeEnvironment.getApplication()
        return SshConnectionManager(context, SettingsRepository(context), VaultKeySource { vaultIdentities() })
    }

    /** Connects, trusting the server key the first time it is offered - the app's own flow. */
    private suspend fun trustedConnect(manager: SshConnectionManager, profile: HostProfile) =
        coroutineScope {
            val challengeRef = AtomicReference<HostKeyChallenge?>()
            val collector = launch { manager.hostKeyChallenges.collect { challengeRef.set(it) } }
            val attempt = runCatching { manager.connect(profile, PASSWORD, null) }
            delay(300)
            collector.cancel()
            if (attempt.isSuccess) return@coroutineScope attempt.getOrThrow()
            val challenge = challengeRef.get()
                ?: throw attempt.exceptionOrNull() ?: IllegalStateException("connect failed with no challenge")
            manager.trustHost(challenge)
            manager.connect(profile, PASSWORD, null)
        }

    /**
     * Waits for the shell the caller is about to talk to, and for it to have *started*: MINA adds the
     * command to the channel before `start` runs, so a shell spotted the instant it is created has
     * no environment to read yet - and the environment is what both tests here assert on.
     */
    private suspend fun awaitShell(startedBefore: Int): EnvRecordingShell {
        val deadline = System.nanoTime() + WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (shells.size > startedBefore) {
                val shell = shells[startedBefore]
                if (shell.env() != null) return shell
            }
            delay(25)
        }
        throw AssertionError("the server never started a shell for this channel")
    }

    /**
     * The positive path, whole: the shell asks, the server answers, and an agent client on the
     * *server* side - standing in for the administrator running `ssh-add -l` or an onward
     * `ssh host-b` - sees the phone's vault and gets a signature back from it.
     */
    @Test(timeout = 120_000)
    fun `a forwarded session serves the vault's identities and signs over the agent channel`() = runBlocking {
        val manager = newManager()
        try {
            val shellsBefore = shells.size
            val session = trustedConnect(manager, hostProfile(forwarding = true))
            session.use {
                manager.openTerminal(session, profile = hostProfile(forwarding = true))

                val shell = awaitShell(shellsBefore)
                val env = shell.env()
                assertThat(env).isNotNull()
                // The proof the request was honoured: this is what a real sshd hands the shell too.
                val authSocket = env!![SshAgent.SSH_AUTHSOCKET_ENV_NAME]
                assertThat(authSocket).isNotEmpty()

                // The server-side agent client, exactly as ProxyAgentFactory means it to be built:
                // pointed at the proxy id the shell was handed, it opens the agent channel back at
                // the phone over this very session.
                PropertyResolverUtils.updateProperty(server, SshAgent.SSH_AUTHSOCKET_ENV_NAME, authSocket)
                val remoteAgent = (server.agentFactory as ProxyAgentFactory)
                    .createClient(shell.session(), server)

                val identities = remoteAgent.getIdentities().toList()
                assertThat(identities.map { it.value }).containsExactly("id_rsa", "id_ed25519").inOrder()

                val data = "please sign this, said the server".toByteArray()
                val rsaKey = identities.first { it.value == "id_rsa" }.key
                val signed = remoteAgent.sign(null, rsaKey, "rsa-sha2-256", data)

                // The reply is what a real sshd would check: the named algorithm, and a signature
                // that verifies under the key the agent itself listed.
                assertThat(signed.key).isEqualTo("rsa-sha2-256")
                val verifier = SignatureFactory.resolveSignatureFactoryByPublicKey(rsaKey, signed.key).create()
                verifier.initVerifier(null, rsaKey)
                verifier.update(null, data)
                assertThat(verifier.verify(null, signed.value)).isTrue()

                // And the refusal travels the wire: a remote ssh-add gets the agent protocol's
                // failure reply, which the proxy surfaces as an exception rather than a success.
                val loaded = SshKeyLoader.load(
                    SshKeyAlgorithm.ECDSA_P256.generate().privatePem.toByteArray(),
                    "administrator key",
                )
                val refused = runCatching { remoteAgent.addIdentity(loaded, "no") }
                assertThat(refused.isFailure).isTrue()
                assertThat(remoteAgent.getIdentities().toList()).hasSize(2)
            }
        } finally {
            manager.close()
        }
    }

    /**
     * The negative half: a host that never asked for forwarding never gets the invitation.
     *
     * This is the observable the session-aware factory hangs on. The client sends
     * `auth-agent-req` only when the shell was opened with the flag, so a server that would have
     * honoured it sees nothing - no `SSH_AUTH_SOCK` in the shell's environment, no proxy to talk
     * to. A regression that forwarded by default would light this up on every host in the app.
     */
    @Test(timeout = 120_000)
    fun `a session without forwarding never asks the host for an agent channel`() = runBlocking {
        val manager = newManager()
        try {
            val shellsBefore = shells.size
            val session = trustedConnect(manager, hostProfile(forwarding = false))
            session.use {
                manager.openTerminal(session, profile = hostProfile(forwarding = false))

                val shell = awaitShell(shellsBefore)
                val env = shell.env()
                assertThat(env).isNotNull()
                assertThat(env!!).doesNotContainKey(SshAgent.SSH_AUTHSOCKET_ENV_NAME)
            }
        } finally {
            manager.close()
        }
    }
}

