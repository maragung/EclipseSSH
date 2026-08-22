package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.settings.SettingsRepository
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.time.Duration
import java.util.concurrent.TimeUnit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.ClientFactoryManager
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntry
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.PropertyResolver
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.OptionalFeature
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.io.IoSession
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.session.helpers.CurrentService
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.ClientSessionImpl
import org.apache.sshd.client.session.SessionFactory
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory

@Singleton
class SshConnectionManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
) : Closeable {
    private val knownHosts = KnownHostsStore(context)
    /**
     * The host keys nobody has trusted yet, for whoever is in a position to ask the user about them.
     *
     * `replay = 1` is load-bearing, and its absence was a way to make a host permanently
     * unconnectable. The emission below happens on Apache MINA's I/O thread, from inside the verifier,
     * and the verifier's answer - `false` - is what fails the handshake; the app then reports "Server
     * key did not validate". With no replay, a `tryEmit` is discarded outright if nothing is collecting
     * at that instant and returns false once a collector is even slightly behind, and the result is
     * thrown away here because an I/O thread has nowhere to report it. Every one of those paths ends
     * the same way: the connection fails saying the key is untrusted, *no dialog is ever shown*, and
     * because trust is the only thing that would change the outcome, every later attempt fails
     * identically for as long as the process lives. Keeping the last challenge means a collector that
     * subscribes late, or was busy, still learns which key it was being asked about.
     *
     * The replay cache is cleared through [challengeHandled] once the user has answered, so a stale
     * question cannot be put to a collector that arrives afterwards.
     */
    private val _hostKeyChallenges = MutableSharedFlow<HostKeyChallenge>(replay = 1, extraBufferCapacity = 4)
    val hostKeyChallenges: SharedFlow<HostKeyChallenge> = _hostKeyChallenges
    private val client: SshClient = ProxyAwareClient.setUpDefault().apply {
        // Unknown keys and changed keys are rejected until the user explicitly
        // trusts the displayed SHA-256 fingerprint.
        serverKeyVerifier = KnownHostsVerifier(
            store = knownHosts,
            tunnelledTarget = ::tunnelledTargetFor,
        ) { challenge -> _hostKeyChallenges.tryEmit(challenge) }
        // Every session this client creates is a [LivenessClientSession], which is the only way the
        // host's own keep-alive interval can reach the heartbeat at all: see [armHeartbeat].
        sessionFactory = LivenessSessionFactory(this)
        // The fallback for a session that reaches construction without a liveness attribute, and the
        // value the client-level resolver hands to any session that asks. [connect] keeps it in step
        // with the global setting.
        armHeartbeat(this, DEFAULT_KEEP_ALIVE_SECONDS)
        // Nagle off. This is the difference between a terminal that feels connected and one that does
        // not, and Apache MINA leaves it on: `CoreModuleProperties.TCP_NODELAY` defaults to false, so
        // every keystroke this app sent was a small segment held by the kernel until the previous
        // one was acknowledged. Paired with the peer's delayed ACK that is the classic ~40 ms of
        // interactive lag, worst exactly where it is most visible - single characters typed into a
        // shell, and the cursor-key traffic of `vim` and `htop`. Every SSH client sets this; MINA
        // simply does not do it for you.
        CoreModuleProperties.TCP_NODELAY.set(this, true)
        // TCP-level keepalive as a backstop under the SSH heartbeat below. The heartbeat notices a
        // peer that is still answering TCP but has stopped answering SSH; this notices a socket whose
        // other end has gone away entirely, including the case where no SSH heartbeat is due for
        // minutes. MINA defaults this to false as well.
        CoreModuleProperties.SOCKET_KEEPALIVE.set(this, true)
    }

    @Volatile private var started = false

    /**
     * The algorithms offered when legacy compatibility is off, and the ones the switch adds.
     *
     * Both sets are computed once, here, rather than recomputed later: once legacy mode has been
     * applied the live lists on [client] are no longer the ones it started with, so there would be
     * nothing left to derive "off" from.
     *
     * Apache MINA SSHD's own defaults are more permissive than OpenSSH's, and taking them verbatim
     * meant this app negotiated deprecated cryptography with *every* server whatever the setting said:
     * `MINA 2.14`'s default cipher list still contains `aes128/192/256-cbc` (OpenSSH stopped offering
     * CBC by default in 6.7) and its default signature list still contains SHA-1 `ssh-rsa` (OpenSSH
     * disabled that in 8.8). Those are exactly the two things the legacy switch existed to add, so
     * with the switch off the app was no safer, and with it on the switch barely did anything.
     *
     * So the modern sets are MINA's minus those, and the legacy sets put them back — appended, never
     * prepended, because in SSH the client's order is the preference order and a server that supports
     * something better must still get it.
     *
     * `hmac-sha1` deliberately stays in the modern set: OpenSSH 9.x still enables it by default, HMAC
     * does not inherit SHA-1's collision problem, and dropping it would strand aes-ctr-only appliances
     * for no real gain. Its genuinely broken relatives — truncated SHA-1 and both MD5 forms — are in
     * the legacy set instead.
     *
     * The key exchange half is not a hardening but a missing feature: the switch is described in
     * Settings as "Compatibility with older SSH servers (CBC, dh-group1)" and never added a single key
     * exchange algorithm, so the one server generation it named by name — `diffie-hellman-group1-sha1`
     * — could not be reached with it on. MINA's defaults stop at group14-*sha256*, matching OpenSSH,
     * so the SHA-1 groups belong here.
     */
    private val legacyCiphers = listOf(
        BuiltinCiphers.aes256cbc,
        BuiltinCiphers.aes192cbc,
        BuiltinCiphers.aes128cbc,
        BuiltinCiphers.tripledescbc,
        BuiltinCiphers.blowfishcbc,
    )
    private val legacyMacs = listOf(BuiltinMacs.hmacsha196, BuiltinMacs.hmacmd5, BuiltinMacs.hmacmd596)
    private val legacySignatures = listOf(BuiltinSignatures.rsa)
    private val legacyKeyExchanges = listOf(
        BuiltinDHFactories.dhgex,
        BuiltinDHFactories.dhg14,
        BuiltinDHFactories.dhg1,
    )

    private val modernCipherFactories = client.cipherFactories.without(legacyCiphers)
    private val modernMacFactories = client.macFactories.without(legacyMacs)
    private val modernSignatureFactories = client.signatureFactories.without(legacySignatures)
    private val modernKeyExchangeFactories = client.keyExchangeFactories.without(legacyKeyExchanges)

    /**
     * The legacy key exchanges as SSHD factories, filtered by [OptionalFeature.isSupported] because
     * whether a JCE provider will do 1024-bit Diffie-Hellman at all is a property of the device.
     * Silently dropping an unsupported one is right: the alternative is a switch that throws.
     */
    private val legacyKeyExchangeFactories: List<KeyExchangeFactory> =
        legacyKeyExchanges.filter { it.isSupported }.map { ClientBuilder.DH2KEX.apply(it) }

    /** Null until the legacy setting has been applied at least once, then the value that was applied. */
    @Volatile private var legacyApplied: Boolean? = null

    /**
     * The jump-host attempts currently in flight, so a host key that arrives down a tunnel can be
     * pinned against the host it belongs to.
     *
     * Needed because of what Apache MINA does for `ProxyJump`, which is visible in
     * `SshClient.doConnect`: it connects to the jump host, opens a *local* port forward to the real
     * target, and then dials the target through `127.0.0.1:<ephemeral port>`. The session that reaches
     * the target therefore reports a loopback connect address with a port the operating system picked
     * moments earlier, and that is all the verifier can see. Keying known-hosts on it had two
     * consequences, both bad: trust never stuck, because the port is different on every connection, so
     * the user was asked to confirm the fingerprint of a jump-host target every single time and each
     * answer left another meaningless `127.0.0.1:41xxx` row behind; and an entry written for one
     * ephemeral port could later be *matched* by an unrelated host whose forward happened to reuse it,
     * which is host-key confusion - the app would accept the wrong server's key without a word, and
     * the "this key has changed" warning would not fire, which is the one thing pinning exists to do.
     *
     * Keyed by username and required to be unambiguous: two concurrent jump attempts by the same user
     * to different hosts cannot be told apart from here, and in that case the verifier keeps its old
     * address-based behaviour rather than guessing. Entries live only for the duration of the connect,
     * and direct and proxied connections never add one, so for them nothing about verification changes.
     */
    private val tunnelledTargets = CopyOnWriteArrayList<TunnelledTarget>()

    private fun tunnelledTargetFor(username: String?): TunnelledTarget? {
        if (username == null) return null
        return tunnelledTargets
            .filter { it.username == username }
            .distinctBy { it.host to it.port }
            .singleOrNull()
    }

    /**
     * Dials [profile] and returns an authenticated session, reporting each phase as it is entered.
     *
     * [onPhase] exists because the two halves of connecting fail for different reasons, take different
     * lengths of time, and call for different things to be said to the user - and from outside this
     * function they were indistinguishable. A host that answers its socket immediately and then spends
     * fifteen seconds in a PAM stack, an LDAP lookup or a hardware token prompt spent all of it under
     * one word, "Connecting…", which is the shape of a hang. It is called from this coroutine, at most
     * once per phase, in order; the caller is expected to do nothing slow in it.
     */
    suspend fun connect(
        profile: HostProfile,
        password: String? = null,
        keyPair: KeyPair? = null,
        onPhase: (SshConnectPhase) -> Unit = {},
    ): ClientSession = withContext(Dispatchers.IO) {
        onPhase(SshConnectPhase.HANDSHAKE)
        ensureStarted()
        applyLegacyAlgorithmsIfEnabled()
        // Clamped rather than trusted: the profile can also arrive from an imported vault backup or a
        // parsed ssh_config, neither of which the Add Host validation ever saw. A zero would make
        // `verify` time out instantly and look like an unreachable server.
        val timeout = profile.connectTimeoutSeconds
            .coerceIn(CONNECT_TIMEOUT_RANGE.first, CONNECT_TIMEOUT_RANGE.last)
            .toLong()
        /*
         * The jump host this connection will actually use, or null.
         *
         * Resolved once and used both to register the tunnelled target and to choose the branch below,
         * so the two cannot disagree. A profile carrying a proxy type *and* a `proxyJump` takes the
         * proxy: `when` picks the first matching branch, and the jump string is ignored - so the
         * registry must ignore it too, or it would hold an entry for a tunnel that was never opened.
         */
        val jumpHost = profile.proxyJump
            ?.takeIf(String::isNotBlank)
            ?.takeIf { profile.proxyType != ProxyType.SOCKS5 && profile.proxyType != ProxyType.HTTP_CONNECT }
        // Registered before the dial and dropped the moment the handshake is over, because the only
        // code that reads it is the verifier and the verifier only runs in between: see
        // [tunnelledTargets].
        val tunnelled = jumpHost
            ?.let { TunnelledTarget(profile.username, profile.host, profile.port) }
            ?.also(tunnelledTargets::add)
        /*
         * Resolved before the dial, because the heartbeat cannot be configured after it.
         *
         * A host's own interval wins over the global one when it sets one: a host behind an aggressive
         * NAT needs a shorter heartbeat than the default, and one on a metered link a longer one.
         */
        val globalKeepAlive = runCatching { settingsRepository.settings.first().keepAliveSeconds }
            .getOrDefault(DEFAULT_KEEP_ALIVE_SECONDS)
            .coerceIn(KEEP_ALIVE_RANGE.first, KEEP_ALIVE_RANGE.last)
        val keepAlive = (profile.keepAliveSeconds ?: globalKeepAlive)
            .coerceIn(KEEP_ALIVE_RANGE.first, KEEP_ALIVE_RANGE.last)
        // Keeps the client-level fallback tracking the user's global preference rather than a
        // constant, for the one session that is built without a socket to carry the attribute: the
        // far end of a jump-host tunnel. Two connects can interleave here, and it does not matter -
        // every value written is a valid interval and it is only ever a fallback.
        armHeartbeat(client, globalKeepAlive)
        /*
         * The connection context, which is how per-host configuration reaches session construction.
         *
         * MINA attaches this repository to the [IoSession] before handing it to the session factory,
         * so it is readable from [LivenessClientSession] at the one moment the heartbeat can still be
         * armed. The proxy configurations travel the same way and are read by [ProxyAwareConnector].
         */
        val context = AttributeRepository.ofAttributesMap(
            buildMap<AttributeRepository.AttributeKey<*>, Any> {
                put(LIVENESS_KEY, keepAlive)
                when (profile.proxyType) {
                    ProxyType.SOCKS5 -> put(SocksProxyConfig.KEY, socksProxyConfig(profile, timeout))
                    ProxyType.HTTP_CONNECT -> put(HttpProxyConfig.KEY, httpProxyConfig(profile, timeout))
                    else -> Unit
                }
            },
        )
        val session = try {
            when {
                jumpHost != null -> {
                    val entry = HostConfigEntry(profile.host, profile.host, profile.port, profile.username, jumpHost)
                    client.connect(entry, context, null)
                }
                else -> client.connect(profile.username, profile.host, profile.port, context, null)
            }.verify(timeout, TimeUnit.SECONDS)
                .session
        } finally {
            // The underlying client owns the socket; this is the only thing this frame owns.
            tunnelled?.let(tunnelledTargets::remove)
        }
        // The transport is up and the key exchange is done; everything from here is the server
        // deciding whether to let this user in.
        onPhase(SshConnectPhase.AUTHENTICATE)
        try {
            password?.takeIf(String::isNotEmpty)?.let { secret ->
                session.addPasswordIdentity(secret)
                session.setUserInteraction(object : UserInteraction {
                    override fun isInteractionAllowed(session: ClientSession): Boolean = true
                    override fun welcome(session: ClientSession, banner: String, lang: String) = Unit
                    override fun interactive(session: ClientSession, name: String, instruction: String, lang: String, prompt: Array<String>, echo: BooleanArray): Array<String> = Array(prompt.size) { secret }
                    override fun getUpdatedPassword(session: ClientSession, prompt: String, lang: String): String = secret
                })
            }
            keyPair?.let(session::addPublicKeyIdentity)
            // The heartbeat itself was armed at construction (see [armHeartbeat]); this is only its
            // backstop, and it is the one liveness property MINA will still read after the fact.
            configureIdleTimeout(session, keepAlive)
            session.auth().verify(timeout, TimeUnit.SECONDS)
            session
        } catch (error: Throwable) {
            // Auth or pre-auth setup failed: close the socket so retries (the app
            // tries up to 3 times) do not accumulate half-open sessions.
            runCatching { session.close(false) }
            throw error
        }
    }

    /**
     * Opens an interactive shell on [session], sized [columns]x[rows] when the caller knows the size.
     *
     * The caller normally does know it: a session being reconnected is replacing a pty whose geometry
     * the user's screen already decided. Passing it here rather than resizing afterwards is what makes
     * a reconnected `htop` come back at the size it left.
     */
    suspend fun openTerminal(
        session: ClientSession,
        columns: Int? = null,
        rows: Int? = null,
    ): TerminalChannel = withContext(Dispatchers.IO) {
        val channel = TerminalChannel(session.createShellChannel())
        if (columns != null && rows != null) channel.open(columns, rows) else channel.open()
        channel
    }

    /**
     * Asks [session] whether it is still there, and waits [timeoutSeconds] for the answer.
     *
     * This is what a network migration needs and what a heartbeat cannot give it. Switching from Wi-Fi
     * to mobile data invalidates the socket's source address, so the TCP connection is dead - but
     * nothing says so: no FIN arrives, no error is raised, `isOpen` stays true, and the app's own
     * heartbeat only concludes anything after [CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX]
     * consecutive unanswered keepalives, which at the default interval is a minute and a half. For that
     * minute and a half the terminal looks connected and silently swallows every keystroke, which is
     * the single most-reported symptom of "SSH keeps disconnecting" in this app.
     *
     * The probe is OpenSSH's own `keepalive@openssh.com` global request, and the reply that matters is
     * *any* reply:
     *
     *  - a `SSH_MSG_REQUEST_SUCCESS` returns the payload buffer;
     *  - a `SSH_MSG_REQUEST_FAILURE` - what OpenSSH actually sends, since it implements no such global
     *    request - makes MINA return `null`, which is still proof the far end is listening;
     *  - a timeout or an I/O error is the only negative answer.
     *
     * So a server that says "no" counts as alive, and only silence counts as dead. That asymmetry is
     * deliberate: the cost of a false positive is a session the app kills while it was working, which
     * is the bug this exists to fix rather than a fix for it.
     */
    suspend fun probeLiveness(
        session: ClientSession,
        timeoutSeconds: Long = LIVENESS_PROBE_SECONDS,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!session.isOpen) return@withContext false
        try {
            val request = KEEPALIVE_REQUEST
            // The shape AbstractSession.request expects: the command byte, the request name, and a
            // want-reply flag it re-reads out of the buffer to decide whether to wait for anything.
            // Four bytes for the string's length prefix and one for the boolean; the hint only sizes
            // the initial allocation, so being a byte out costs a grow, not correctness.
            val buffer = session.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST, request.length + Integer.BYTES + 1)
            buffer.putString(request)
            buffer.putBoolean(true)
            session.request(request, buffer, Duration.ofSeconds(timeoutSeconds))
            true
        } catch (error: IOException) {
            // SocketTimeoutException (no reply in time) and InterruptedIOException are both IOExceptions,
            // and all of them mean the same thing here: nothing came back.
            false
        }
    }

    suspend fun openSftp(session: ClientSession): SftpClient = withContext(Dispatchers.IO) {
        SftpClientFactory.instance().createSftpClient(session)
    }

    suspend fun runCommand(session: ClientSession, command: String): String = withContext(Dispatchers.IO) {
        session.executeRemoteCommand(command).trim()
    }

    /** Trusts this fingerprint for the profile's host. False when it could not be persisted. */
    fun trustHost(profile: HostProfile, fingerprint: String): Boolean =
        knownHosts.save(profile.host, profile.port, fingerprint)

    /** Trusts the key the user has just accepted. False when it could not be persisted. */
    fun trustHost(challenge: HostKeyChallenge): Boolean =
        knownHosts.save(challenge.host, challenge.port, challenge.fingerprint)

    /**
     * Forgets the challenge held for a late collector, once the user has accepted or rejected it.
     *
     * The counterpart to the replay cache described on [_hostKeyChallenges]: without this, the answered
     * question would be re-asked of the next collector to subscribe - a fresh view model after the last
     * one was cleared, for instance - and a host-key dialog would appear for a key that had already
     * been dealt with.
     */
    fun challengeHandled() {
        _hostKeyChallenges.resetReplayCache()
    }

    fun knownHosts(): Map<String, String> = knownHosts.all()

    fun removeKnownHost(host: String, port: Int) = knownHosts.remove(host, port)

    fun clearKnownHosts() = knownHosts.clear()

    fun importKnownHosts(map: Map<String, String>) = knownHosts.putAll(map)

    fun fingerprint(publicKey: PublicKey): String = KnownHostsVerifier.fingerprint(publicKey)

    /**
     * Brings the client's algorithm set in line with the legacy-compatibility setting, in both
     * directions.
     *
     * The one-way version of this was a way to weaken the app permanently by accident. It applied the
     * extra algorithms the first time the setting was seen enabled and then latched, so a user who
     * switched legacy mode on to reach one elderly server and switched it straight back off carried the
     * weakened set into *every* later connection until the process was killed — and nothing in the UI
     * suggested that, because the switch showed the setting as off. Assigning the modern lists back is
     * what makes turning it off mean something before the next launch.
     *
     * `client` is a `@Singleton`, so this is process-wide state and the window matters: the check is
     * recorded in [legacyApplied] so an unchanged setting costs one volatile read per connect rather
     * than rebuilding four lists, and it runs immediately before the connect rather than from a
     * settings observer, so no session can be opened with the previous set still installed.
     */
    private suspend fun applyLegacyAlgorithmsIfEnabled() {
        val legacy = runCatching { settingsRepository.settings.first().legacyAlgorithms }.getOrDefault(false)
        if (legacyApplied == legacy) return
        synchronized(this) {
            if (legacyApplied == legacy) return
            if (legacy) {
                client.cipherFactories = modernCipherFactories + legacyCiphers.supported()
                client.macFactories = modernMacFactories + legacyMacs.supported()
                client.signatureFactories = modernSignatureFactories + legacySignatures.supported()
                client.keyExchangeFactories = modernKeyExchangeFactories + legacyKeyExchangeFactories
            } else {
                client.cipherFactories = modernCipherFactories.toList()
                client.macFactories = modernMacFactories.toList()
                client.signatureFactories = modernSignatureFactories.toList()
                client.keyExchangeFactories = modernKeyExchangeFactories.toList()
            }
            legacyApplied = legacy
        }
    }

    /**
     * The same list without anything named in [unwanted], compared by algorithm name because the
     * default lists hold SSHD's own factory objects and the legacy lists hold `Builtin*` enum entries.
     */
    private fun <T : NamedResource> List<T>.without(unwanted: List<NamedResource>): List<T> {
        val names = unwanted.mapTo(mutableSetOf()) { it.name }
        return filterNot { it.name in names }
    }

    /**
     * Drops anything this device's JCE providers cannot actually do — Blowfish and 3DES are the usual
     * absentees. An unsupported factory left in the list would be advertised in the KEX proposal and
     * then fail to instantiate if the server picked it, which is a worse outcome than not offering it.
     */
    private fun <T> List<T>.supported(): List<T> where T : OptionalFeature = filter { it.isSupported }

    /**
     * Sets the idle timeout that backs up the heartbeat armed in [armHeartbeat].
     *
     * MINA's default idle timeout is exactly ten minutes and [KEEP_ALIVE_RANGE] allows a keep-alive of
     * exactly ten minutes. Those two race, and the user who lost is the one who deliberately chose the
     * longest interval to save battery on a metered link: their idle session was dropped by the
     * client's own timer at almost the moment the heartbeat that would have kept it alive was due.
     * Anchoring it to three heartbeat intervals plus a minute keeps a real backstop - the session
     * still goes away if the heartbeat itself stops running - while making it impossible for the
     * backstop to fire before the heartbeat does.
     *
     * Unlike the heartbeat properties this one can be set on a live session, because MINA re-reads it
     * on every idle check rather than caching it in a field at construction.
     */
    private fun configureIdleTimeout(session: ClientSession, keepAliveSeconds: Int) {
        CoreModuleProperties.IDLE_TIMEOUT.set(
            session,
            Duration.ofSeconds(keepAliveSeconds.toLong() * HEARTBEAT_NO_REPLY_MAX + IDLE_TIMEOUT_MARGIN_SECONDS),
        )
    }

    private fun ensureStarted() {
        if (!started) synchronized(this) {
            if (!started) {
                client.start()
                started = true
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (started) {
                client.stop()
                started = false
            }
        }
    }

    private companion object {

        /** Keep-alive interval used when neither the host nor the settings have an opinion. */
        const val DEFAULT_KEEP_ALIVE_SECONDS = 30

        /** Slack between the last heartbeat that could arrive and the idle timeout firing. */
        const val IDLE_TIMEOUT_MARGIN_SECONDS = 60L
    }
}

/**
 * The SOCKS5 leg of a proxied connection, built from [profile] and the connect budget the caller
 * already clamped.
 *
 * Extracted from [SshConnectionManager.connect] for two reasons. It is the only way to assert that
 * `HostProfile.connectTimeoutSeconds` reaches the *proxy* handshake — it did not, and could not,
 * while the config was built inline: the field was omitted at the call site, so every proxied
 * connection silently kept [SocksProxyConfig]'s own 15-second default no matter what the host said.
 * A host given 300 seconds because it sits behind a slow bastion still gave up on the bastion after
 * fifteen. And the SOCKS and HTTP branches were near-identical, so the port bound was written twice.
 *
 * @param timeoutSeconds the per-host connect budget, already clamped to
 *   [dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE] by the caller.
 * @throws IllegalArgumentException with the message the user is shown, when the route is incomplete.
 *   Reachable despite the Add Host validation: a profile can also arrive from an imported backup.
 */
internal fun socksProxyConfig(profile: HostProfile, timeoutSeconds: Long): SocksProxyConfig {
    val host = profile.socksHost?.trim().orEmpty()
    require(host.isNotEmpty() && profile.socksPort in PORT_RANGE) {
        "SOCKS5 proxy host and port are not configured"
    }
    return SocksProxyConfig(
        host = host,
        port = profile.socksPort,
        connectTimeoutMs = timeoutSeconds * 1_000L,
        username = profile.socksUsername?.takeIf(String::isNotBlank),
        password = profile.socksPassword,
    )
}

/** The HTTP CONNECT leg of a proxied connection. See [socksProxyConfig]. */
internal fun httpProxyConfig(profile: HostProfile, timeoutSeconds: Long): HttpProxyConfig {
    val host = profile.socksHost?.trim().orEmpty()
    require(host.isNotEmpty() && profile.socksPort in PORT_RANGE) {
        "HTTP CONNECT proxy host and port are not configured"
    }
    return HttpProxyConfig(
        host = host,
        port = profile.socksPort,
        connectTimeoutMs = timeoutSeconds * 1_000L,
        username = profile.socksUsername?.takeIf(String::isNotBlank),
        password = profile.socksPassword,
    )
}

/**
 * The fingerprints this device has decided to trust, in memory and on disk.
 *
 * Every mutator returns whether the change reached storage. All four used to go through
 * `SharedPreferences.edit { }`, which is `apply()` - a write scheduled on a background thread whose
 * result nobody can see. Both directions of a silent failure are bad, and in opposite ways: a lost
 * `save` means the user is asked to trust the same host again on every connection while the running
 * process says it is trusted, and a lost `remove` or `clear` means a key the user explicitly revoked
 * is trusted again after the next restart - a revocation the app claimed to have carried out and
 * silently did not. `commit()` reports that, and it is the caller's job to say so.
 *
 * The synchronous write is deliberate on the main thread too. It is one small file of short strings,
 * written when a person taps "trust" or "forget", and the alternative is telling them a security
 * decision has been stored before it has.
 */
internal class KnownHostsStore(context: Context) {
    private val preferences = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)
    private val fingerprints = ConcurrentHashMap<String, String>().apply {
        preferences.all.forEach { (key, value) -> if (value is String) put(key, value) }
    }

    fun get(host: String, port: Int): String? = fingerprints["$host:$port"]

    /** Trusts [fingerprint] for this host. False when it was not written to disk. */
    fun save(host: String, port: Int, fingerprint: String): Boolean {
        val key = "$host:$port"
        fingerprints[key] = fingerprint
        return commit { putString(key, fingerprint) }
    }

    fun all(): Map<String, String> = fingerprints.toMap()

    /** Revokes this host's stored key. False when the revocation did not reach disk. */
    fun remove(host: String, port: Int): Boolean {
        val key = "$host:$port"
        fingerprints.remove(key)
        return commit { this.remove(key) }
    }

    /** Revokes every stored key. False when the revocation did not reach disk. */
    fun clear(): Boolean {
        fingerprints.clear()
        // Qualified with `this` so it cannot be misread as recursion: inside the editor block the
        // implicit receiver is the Editor, whose clear() has the same signature as this class's.
        return commit { this.clear() }
    }

    fun putAll(map: Map<String, String>): Boolean {
        fingerprints.putAll(map)
        return commit { map.forEach { (key, value) -> putString(key, value) } }
    }

    /**
     * Applies [edits] and reports whether they were stored.
     *
     * `runCatching` because a full or read-only data directory throws out of `commit()`, and the
     * caller is already being told the write failed - taking the process down as well would turn a
     * disk problem into a crash in the middle of a connection.
     */
    private fun commit(edits: android.content.SharedPreferences.Editor.() -> Unit): Boolean =
        runCatching { preferences.edit().apply(edits).commit() }.getOrDefault(false)
}

/** The host a jump-host connection is for, for as long as that connection is being made. */
internal class TunnelledTarget(val username: String, val host: String, val port: Int)

/** A host and port the verifier can name, and whether that host is this device. */
internal class SocketIdentity(val host: String, val port: Int, val isLoopback: Boolean)

internal class KnownHostsVerifier(
    private val store: KnownHostsStore,
    private val tunnelledTarget: (String?) -> TunnelledTarget?,
    private val onChallenge: (HostKeyChallenge) -> Unit,
) : ServerKeyVerifier {
    override fun verifyServerKey(
        clientSession: ClientSession?,
        remoteAddress: java.net.SocketAddress?,
        serverKey: PublicKey,
    ): Boolean {
        // Prefer the real target host (set by SshClient) over the raw socket address, so known-hosts
        // stay correct when connecting through a SOCKS5 or HTTP CONNECT proxy: for those, MINA dials
        // the proxy but records the address the caller asked for, while the socket's peer is the proxy
        // and would key every host behind one proxy to the same entry.
        val observed = identify(clientSession?.connectAddress)
            ?: identify(remoteAddress)
            // An address type that cannot be named cannot be pinned to anything, and accepting a key
            // that nothing can be compared against later is the one answer that must not be given.
            ?: return false
        val address = intendedIdentity(clientSession, observed)
        val actual = fingerprint(serverKey)
        val expected = store.get(address.host, address.port)
        if (expected == actual) return true
        onChallenge(
            HostKeyChallenge(
                host = address.host,
                port = address.port,
                fingerprint = actual,
                changed = expected != null,
            ),
        )
        return false
    }

    /**
     * The host this key belongs to, which for a tunnelled connection is not the socket it arrived on.
     *
     * Only a loopback address can be a local port forward, and only an in-flight jump-host attempt can
     * have created one, so every other connection - direct, SOCKS5, HTTP CONNECT, and the leg that
     * reaches the jump host itself - is identified exactly as before. See
     * [SshConnectionManager.tunnelledTargets] for what MINA does and why this is necessary.
     *
     * A jump host that is itself on this device is left to the address: from here it is
     * indistinguishable from the forward, and pinning it as the target would file the wrong key under
     * the target's name.
     */
    private fun intendedIdentity(session: ClientSession?, observed: SocketIdentity): SocketIdentity {
        if (!observed.isLoopback) return observed
        val target = tunnelledTarget(session?.username) ?: return observed
        if (target.host == observed.host && target.port == observed.port) return observed
        if (isLoopbackName(target.host)) return observed
        return SocketIdentity(target.host, target.port, isLoopback = false)
    }

    companion object {
        /**
         * [address] as a host and port, for the address types an SSH connection can present.
         *
         * `SshdSocketAddress` is not an `InetSocketAddress` - it extends `SocketAddress` directly - so
         * a cast to the latter silently misses it, and missing it here used to mean returning false
         * without asking anybody: a connection that fails saying the key is untrusted while no dialog
         * is ever shown, which is unexplainable from the outside.
         */
        private fun identify(address: java.net.SocketAddress?): SocketIdentity? = when (address) {
            is InetSocketAddress -> SocketIdentity(
                host = address.hostString,
                port = address.port,
                isLoopback = address.address?.isLoopbackAddress == true || isLoopbackName(address.hostString),
            )
            is SshdSocketAddress -> SocketIdentity(
                host = address.hostName,
                port = address.port,
                isLoopback = isLoopbackName(address.hostName),
            )
            else -> null
        }

        private fun isLoopbackName(host: String): Boolean =
            host == "localhost" || host == "::1" || host.startsWith("127.")

        fun fingerprint(publicKey: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

/**
 * The global-request name used for the SSH-level keep-alive.
 *
 * `keepalive@openssh.com` rather than Apache MINA's own `keepalive@sshd.apache.org`: both are
 * answered — an unknown request with `want-reply` gets `SSH_MSG_REQUEST_FAILURE`, which is the
 * proof of life this needs — but the OpenSSH name is the one servers, bastions and audit logs
 * recognise, and a name a middlebox has seen before is one less thing to be filtered.
 */
private const val KEEPALIVE_REQUEST = "keepalive@openssh.com"

/** Missed keep-alive replies tolerated before the session is closed, as `ServerAliveCountMax`. */
private const val HEARTBEAT_NO_REPLY_MAX = 3

/**
 * How long [SshConnectionManager.probeLiveness] waits for a reply.
 *
 * Long enough that a congested mobile link is not mistaken for a dead one - a keepalive round trip on
 * a bad 3G connection can take seconds - and short enough that the alternative, waiting out three
 * missed heartbeats at the configured interval, is not what decides how fast a session recovers.
 */
private const val LIVENESS_PROBE_SECONDS = 6L

/**
 * The host's keep-alive interval, in seconds, as carried on the connection context.
 *
 * MINA copies the [AttributeRepository] passed to `connect` onto the [IoSession] before the session
 * factory ever sees it, which makes it the one channel that reaches session construction.
 */
private val LIVENESS_KEY = AttributeRepository.AttributeKey<Int>()

/**
 * Arms the SSH keep-alive that both holds a NAT mapping open and detects a peer that has stopped
 * answering.
 *
 * The heartbeat used to be `HeartbeatType.IGNORE`, which sends `SSH_MSG_IGNORE` and expects nothing
 * back. That keeps a NAT mapping warm, which is what it was added for, but it cannot detect a dead
 * peer: nothing about it fails when the packets stop arriving anywhere. The failure that produced was
 * the ugliest kind - a session that stays CONNECTED in the UI forever after the network moved
 * underneath it (a NAT rebind, a Wi-Fi to mobile handover, a sleeping upstream router), with the user
 * typing into a shell that is not there and no error ever appearing. Nothing reconnected, because as
 * far as the app was concerned nothing had gone wrong.
 *
 * Switching it to `HeartbeatType.RESERVED` through `setSessionHeartbeat` looked like the fix and was
 * not one. That call writes `CommonModuleProperties.SESSION_HEARTBEAT_TYPE` and
 * `SESSION_HEARTBEAT_INTERVAL`, which only `AbstractConnectionService` reads - and that generic
 * implementation supports `IGNORE` and throws `NullPointerException: No customized heartbeat handler
 * registered` for anything else. `ClientConnectionService`, the service a client session actually
 * runs, reads [CoreModuleProperties.HEARTBEAT_INTERVAL], [CoreModuleProperties.HEARTBEAT_REQUEST] and
 * [CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX] instead, and only falls back to the generic heartbeat
 * when that interval is unset. So the app had a heartbeat that scheduled nothing.
 *
 * What is set here is a `keepalive@openssh.com` global request with `want-reply`, which is what
 * OpenSSH's own `ServerAliveInterval` sends. MINA counts the requests that have gone unanswered and
 * closes the session once the count passes the limit, and a close is an event the app can act on: the
 * terminal collector sees it and starts a bounded, backed-off reconnect. RFC 4254 requires a server to
 * answer an unknown global request with `SSH_MSG_REQUEST_FAILURE` when a reply was asked for, and a
 * failure reply is still a reply - it proves the peer is alive and resets the counter - so this works
 * against servers that have never heard of the request name.
 *
 * Three missed replies before giving up, mirroring `ServerAliveCountMax`, so a single dropped packet
 * or one long garbage-collection pause on the server cannot end a working session.
 *
 * `HEARTBEAT_REPLY_WAIT` is deliberately not set: it is deprecated, and `configureMaxNoReply` ignores
 * it entirely whenever `HEARTBEAT_NO_REPLY_MAX` is set explicitly, as it is here.
 */
private fun armHeartbeat(resolver: PropertyResolver, keepAliveSeconds: Int) {
    CoreModuleProperties.HEARTBEAT_INTERVAL.set(resolver, Duration.ofSeconds(keepAliveSeconds.toLong()))
    CoreModuleProperties.HEARTBEAT_REQUEST.set(resolver, KEEPALIVE_REQUEST)
    CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(resolver, HEARTBEAT_NO_REPLY_MAX)
}

/**
 * A client session that arms its heartbeat while it is still being constructed.
 *
 * This exists because of *when* `ClientConnectionService` reads its configuration. MINA builds both
 * services - userauth and connection - inside `AbstractSession`'s constructor, by way of
 * `initializeCurrentService`, and the connection service copies the three heartbeat properties into
 * `final` fields there and then. By the time `connect` holds a `ClientSession` the values are already
 * fixed, so setting them on the session at that point configures nothing at all: the session silently
 * keeps whatever the client-level resolver happened to hold. That is not a detail worth working around
 * in the caller, because it is invisible - every property reads back exactly as it was written.
 *
 * Overriding [initializeCurrentService] puts the per-host interval on the session's own resolver one
 * step before the service is built, which is the last moment it can matter.
 */
private class LivenessClientSession(
    manager: ClientFactoryManager,
    ioSession: IoSession,
) : ClientSessionImpl(manager, ioSession) {

    override fun initializeCurrentService(): CurrentService {
        // Runs from the superclass constructor, so nothing declared by this class is initialised yet -
        // hence the attribute lookup rather than a constructor parameter.
        val context = getIoSession().getAttribute(AttributeRepository::class.java) as? AttributeRepository
        context?.getAttribute(LIVENESS_KEY)?.let { armHeartbeat(this, it) }
        return super.initializeCurrentService()
    }
}

/** Makes [LivenessClientSession] the session every connection gets. */
private class LivenessSessionFactory(client: ClientFactoryManager) : SessionFactory(client) {
    override fun doCreateSession(ioSession: IoSession): ClientSessionImpl =
        LivenessClientSession(getClient(), ioSession)
}
