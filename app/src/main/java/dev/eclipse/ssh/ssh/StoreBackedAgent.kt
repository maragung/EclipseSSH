package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.credentials.HostCredentialStore
import java.io.IOException
import java.security.KeyPair
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Objects
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.apache.sshd.agent.SshAgent
import org.apache.sshd.agent.SshAgentKeyConstraint
import org.apache.sshd.common.SshException
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.signature.SignatureFactory

/**
 * One key this app's agent offers to the far end: the pair itself and the comment the phone shows
 * in `ssh-add -l`'s place - here, the file name the user picked when they saved the key.
 */
data class AgentIdentity(val keyPair: KeyPair, val comment: String)

/**
 * The phone as an ssh-agent.
 *
 * When a host with agent forwarding enabled asks for `auth-agent@openssh.com` back, MINA's
 * [org.apache.sshd.agent.local.ChannelAgentForwarding] decodes the wire protocol and calls this
 * class, so what it answers is what a remote `ssh-add -l`, `git push` over an onward SSH hop, or
 * `ssh -A host-b` from the shell sees.
 *
 * The identities are fixed at construction: the caller resolves them from the credential vault
 * *before* the session is dialled, because every method here runs on MINA's I/O thread, where a
 * suspend read of the credential DataStore cannot go. That is also why the class holds [KeyPair]s
 * rather than re-parsing on demand - a passphrase-protected OpenSSH key is bcrypt-wrapped, and
 * hundreds of milliseconds of KDF on the session's I/O thread is a stall the user types into.
 *
 * Serving the whole vault rather than only the session's own key is a deliberate parity decision
 * (the desktop `ssh-add` behaviour: an agent holds every key you loaded, not the one that opened
 * the door). The corollary is on the form that enables it - see the helper text there.
 *
 * NOTE on logging: MINA's `AbstractAgentClient` logs the to-be-signed data as hex at DEBUG level.
 * That is default-off in this app and must stay so - it is the exact "no secrets in logs" stance
 * the rest of the app takes, and nothing here logs key material or signed data itself.
 */
class StoreBackedAgent(identities: List<AgentIdentity>) : SshAgent {

    private val keys: List<AgentIdentity> = Objects.requireNonNull(identities, "No identities")
        .filter { it.keyPair.public != null && it.keyPair.private != null }

    /**
     * Always open. The agent is stateless - each look at it re-reads the same fixed list - so there
     * is nothing a `close` could protect or a stale handle could serve. MINA closes the agent it
     * asked for twice (once after public-key auth, once when the forwarded channel ends); honouring
     * either would kill the other, and the shared list behind this instance is the app's, not the
     * channel's.
     */
    override fun isOpen(): Boolean = true

    /** Nothing to release - see [isOpen]. */
    override fun close() = Unit

    override fun getIdentities(): Iterable<Map.Entry<PublicKey, String>> =
        keys.map { SimpleImmutableEntry<PublicKey, String>(it.keyPair.public, it.comment) }

    /**
     * Signs [data] with the stored identity matching [key], mirroring `AgentImpl.sign` from MINA's
     * own local agent: resolve the signature factory from the *public* key and the requested
     * algorithm, so `ssh-rsa` keys asked to sign as `rsa-sha2-256` / `rsa-sha2-512` (the flags the
     * wire protocol carries) get the right hash, then sign through it.
     */
    override fun sign(
        session: SessionContext?,
        key: PublicKey?,
        algo: String?,
        data: ByteArray?,
    ): Map.Entry<String, ByteArray> {
        // Null guarded here rather than trusted to KeyUtils: the caller is a wire decoder, and a
        // malformed key blob reaching it as null is a refusal, not a crash on the I/O thread.
        val identity = keys.firstOrNull { key != null && KeyUtils.compareKeys(key, it.keyPair.public) }
            ?: throw SshException("No such identity")
        try {
            val publicKey = identity.keyPair.public
            val factory = SignatureFactory.resolveSignatureFactoryByPublicKey(publicKey, algo)
                ?: throw InvalidKeySpecException(
                    "No signer found for ${publicKey.algorithm} when algorithm=$algo requested",
                )
            val signer = factory.create()
                ?: throw InvalidKeySpecException(
                    "No signer found for ${publicKey.algorithm} when algorithm=$algo requested",
                )
            signer.initSigner(session, identity.keyPair.private)
            signer.update(session, requireNotNull(data) { "No data to sign" })
            return SimpleImmutableEntry(factory.name, signer.sign(session))
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw SshException(error)
        }
    }

    /**
     * The client-side authentication shortcut: a key already held here needs no second lookup to
     * authenticate with. MINA's [org.apache.sshd.client.auth.pubkey.KeyAgentIdentity] signs through
     * [sign] either way, so this only saves the round trip it was designed for.
     */
    override fun resolveLocalIdentity(key: PublicKey?): KeyPair? =
        keys.firstOrNull { key != null && KeyUtils.compareKeys(key, it.keyPair.public) }?.keyPair

    /**
     * Refused on purpose, on both sides of the wire.
     *
     * Design decision: "addIdentity/removeIdentity/removeAllIdentities from the remote side must
     * fail - a remote `ssh-add` must never load keys into the phone." The vault is the user's, and
     * the channel asking is the server's administrator acting as the user; anything it could load
     * would then be signed and exported by this same agent, which is one `ssh -A` hop away from
     * being a key-exfiltration primitive. Throwing is the idiom the interface's other methods use;
     * MINA's `AbstractAgentClient` catches it and answers the wire protocol's failure reply, so a
     * remote `ssh-add` sees a refusal rather than a hang or a broken channel.
     */
    override fun addIdentity(key: KeyPair?, comment: String?, vararg constraints: SshAgentKeyConstraint?) {
        throw SshException("This agent does not accept identities from the remote side")
    }

    /** Refused for the same reason as [addIdentity] - see the note there. */
    override fun removeIdentity(key: PublicKey?) {
        throw SshException("This agent does not remove identities from the remote side")
    }

    /** Refused for the same reason as [addIdentity] - see the note there. */
    override fun removeAllIdentities() {
        throw SshException("This agent does not remove identities from the remote side")
    }
}

/**
 * Where the agent's identities come from.
 *
 * An interface so the connect path can resolve the vault off its own dispatcher (the reads are
 * suspend and the key parses are expensive) and the tests can serve fixtures without a credential
 * store. [NONE] is the default of [SshConnectionManager]'s constructor, which is what every
 * non-forwarding caller and every test that never enables forwarding gets.
 */
fun interface VaultKeySource {
    /** Every identity the agent should offer, in offer order. */
    suspend fun loadIdentities(): List<AgentIdentity>

    companion object {
        val NONE: VaultKeySource = VaultKeySource { emptyList() }
    }
}

/**
 * The real source: every private key saved on any host in the app's credential vault.
 *
 * "All of them" is the desktop `ssh-add` parity decision - an agent serves the keys you hold, and
 * the host that wants forwarding is rarely the only host a key opens. A key that cannot be read or
 * parsed is skipped rather than failing the connect: an unreadable credential already falls back to
 * prompting elsewhere in the app, and a forwarding session that comes up with fewer agent keys is a
 * degraded feature, not a failed login. Duplicate public keys are offered once, so three hosts
 * sharing one key do not turn into three `ssh-add -l` lines and three signature attempts per hop.
 */
@Singleton
class StoredKeyVaultSource @Inject constructor(
    private val credentials: HostCredentialStore,
) : VaultKeySource {
    override suspend fun loadIdentities(): List<AgentIdentity> {
        val stored = runCatching { credentials.credentials.first() }.getOrDefault(emptyMap())
        val identities = stored.entries
            .filter { it.value.hasKey }
            .mapNotNull { (hostId, meta) -> load(hostId, meta.keyLabel ?: DEFAULT_KEY_COMMENT) }
        val seen = HashSet<PublicKey>()
        // Offered once per distinct public key: three hosts saved with the same key are one
        // identity to a far end counting its MaxAuthTries, not three.
        return identities.filter { identity -> seen.add(identity.keyPair.public) }
    }

    private suspend fun load(hostId: String, label: String): AgentIdentity? {
        val bytes = runCatching { credentials.keyBytes(hostId) }.getOrNull() ?: return null
        val passphrase = runCatching { credentials.passphrase(hostId) }.getOrNull()
        val pair = runCatching { SshKeyLoader.load(bytes, label, passphrase) }.getOrNull() ?: return null
        return AgentIdentity(pair, label)
    }

    private companion object {
        const val DEFAULT_KEY_COMMENT = "saved key"
    }
}
