package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.SshException
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.signature.SignatureFactory
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The phone as an ssh-agent, on its own.
 *
 * The identities come from the same places the app's own key paths use - [SshKeyAlgorithm] for the
 * generated kinds and the real `ssh-keygen` Ed25519 fixture for the one the app cannot generate -
 * so what is proved here is the agent's behaviour over genuine keys, not over a hand-built
 * substitute. What the far end does with the replies is `AgentForwardingEndToEndTest`'s half.
 */
class StoreBackedAgentTest {

    /** One identity per key kind the app can hold, with the comment the vault would give it. */
    private fun fixtureIdentities(): List<AgentIdentity> {
        val rsa = SshKeyAlgorithm.RSA_2048.generate().let {
            AgentIdentity(SshKeyLoader.load(it.privatePem.toByteArray(), it.defaultPrivateName), "id_rsa")
        }
        val ecdsa = SshKeyAlgorithm.ECDSA_P256.generate().let {
            AgentIdentity(SshKeyLoader.load(it.privatePem.toByteArray(), it.defaultPrivateName), "id_ecdsa")
        }
        val ed25519 = AgentIdentity(
            SshKeyLoader.load(fixture("plain_ed25519"), "id_ed25519"),
            "id_ed25519",
        )
        return listOf(rsa, ecdsa, ed25519)
    }

    @Test
    fun `identities are every stored key with its comment, in order`() {
        val agent = StoreBackedAgent(fixtureIdentities())

        val identities = agent.getIdentities().toList()

        assertThat(identities.map { it.value }).containsExactly("id_rsa", "id_ecdsa", "id_ed25519").inOrder()
        // The public half is the key itself, compared the way the agent itself compares keys -
        // java.security PublicKey implementations do not promise equals, and a fingerprint or
        // type-only check would pass with a re-encoded stranger.
        val held = fixtureIdentities()
        assertThat(identities).hasSize(held.size)
        identities.zip(held).forEach { (offered, stored) ->
            assertThat(KeyUtils.compareKeys(offered.key, stored.keyPair.public)).isTrue()
        }
    }

    @Test
    fun `sign produces a verifiable signature for every key kind`() {
        val data = "the bytes an onward host would ask about".toByteArray()
        // The algorithm names as the wire protocol asks for them: the RSA SHA-2 aliases are what
        // AbstractAgentClient's flags translate to, and OpenSSH servers refuse plain ssh-rsa.
        val algos = mapOf(
            "id_rsa" to listOf("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512"),
            "id_ecdsa" to listOf("ecdsa-sha2-nistp256"),
            "id_ed25519" to listOf("ssh-ed25519"),
        )

        val agent = StoreBackedAgent(fixtureIdentities())
        agent.getIdentities().forEach { (publicKey, comment) ->
            algos.getValue(comment).forEach { algo ->
                val signed = agent.sign(null, publicKey, algo, data)

                // The name is the reply's first field: a far end that asked for rsa-sha2-256 and was
                // answered ssh-rsa is a refusal, silently.
                assertThat(signed.key).isEqualTo(algo)
                assertThat(signed.value).isNotEmpty()
                // And the signature is real: verified with the public half the agent itself offered,
                // the way the receiving sshd verifies it.
                val verifier = SignatureFactory.resolveSignatureFactoryByPublicKey(publicKey, algo).create()
                verifier.initVerifier(null, publicKey)
                verifier.update(null, data)
                assertThat(verifier.verify(null, signed.value)).isTrue()
            }
        }
    }

    @Test
    fun `signing with a key the agent does not hold is refused`() {
        val agent = StoreBackedAgent(fixtureIdentities())
        val stranger = SshKeyLoader.load(
            SshKeyAlgorithm.RSA_2048.generate().privatePem.toByteArray(),
            "a different key",
        )

        val error = assertThrows(SshException::class.java) {
            agent.sign(null, stranger.public, "rsa-sha2-256", ByteArray(1))
        }

        assertThat(error.message).contains("No such identity")
    }

    /**
     * The refusal that is a security property, not a limitation. A remote `ssh-add` that succeeded
     * would be a key the administrator chose, signed and exported by this same agent - one
     * `ssh -A` hop from exfiltration. MINA's wire decoder turns the throw into the agent protocol's
     * failure reply, so the remote side sees a refusal rather than a broken channel.
     */
    @Test
    fun `the remote side cannot add, remove or clear identities`() {
        val agent = StoreBackedAgent(fixtureIdentities())
        val held = agent.getIdentities().first().key
        val loaded = SshKeyLoader.load(
            SshKeyAlgorithm.ECDSA_P256.generate().privatePem.toByteArray(),
            "attacker key",
        )

        assertThrows(SshException::class.java) { agent.addIdentity(loaded, "no") }
        assertThrows(SshException::class.java) { agent.removeIdentity(held) }
        assertThrows(SshException::class.java) { agent.removeAllIdentities() }

        // Nothing changed behind the refusals.
        assertThat(agent.getIdentities().toList()).hasSize(3)
    }

    @Test
    fun `resolveLocalIdentity finds the pair a client-side auth would need`() {
        val identities = fixtureIdentities()
        val agent = StoreBackedAgent(identities)

        val resolved = agent.resolveLocalIdentity(identities.first().keyPair.public)

        assertThat(resolved).isEqualTo(identities.first().keyPair)
        assertThat(agent.resolveLocalIdentity(null)).isNull()
    }

    /** close is a no-op: MINA asks twice, once after auth and once when the channel ends. */
    @Test
    fun `closing does not take the identities away`() {
        val agent = StoreBackedAgent(fixtureIdentities())
        agent.close()

        assertThat(agent.isOpen()).isTrue()
        assertThat(agent.getIdentities().toList()).hasSize(3)
    }

    /**
     * The decision the factory delegates to, tested as a function of the context because the null
     * half is load-bearing: MINA asks the agent factory for identities during publickey auth of
     * *every* session, so a context-less or empty answer must be "no agent" and never "here is the
     * vault anyway".
     */
    @Test
    fun `the agent is served only to a context that carries forwarding identities`() {
        val identities = fixtureIdentities()

        // No context at all - the far end of a jump-host tunnel, or a session built outside connect.
        assertThat(agentFor(null)).isNull()

        // A context that carries no agent identities: what every ordinary login dials with, and the
        // case where answering "the vault anyway" would offer the user's keys to a host that never
        // asked for forwarding.
        val plain = AttributeRepository.ofAttributesMap(
            buildMap<AttributeRepository.AttributeKey<*>, Any>(),
        )
        assertThat(agentFor(plain)).isNull()

        val forwarding = AttributeRepository.ofAttributesMap(mapOf(AGENT_IDENTITIES_KEY to identities))
        val served = agentFor(forwarding)
        assertThat(served).isNotNull()
        assertThat(served!!.getIdentities().map { it.value })
            .containsExactly("id_rsa", "id_ecdsa", "id_ed25519").inOrder()

        // An empty list is the vault-that-could-not-be-read case, and it is no agent at all rather
        // than an agent that answers every question with "no such identity".
        val unreadable = AttributeRepository.ofAttributesMap(mapOf(AGENT_IDENTITIES_KEY to emptyList<AgentIdentity>()))
        assertThat(agentFor(unreadable)).isNull()
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("keys/$name")) { "Missing fixture keys/$name" }
            .use { it.readBytes() }
}
