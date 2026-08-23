package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.data.model.isForcedTerminalSize
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
import java.util.EnumSet
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
import org.apache.sshd.common.SshException
import org.apache.sshd.common.PropertyResolver
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.OptionalFeature
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.compression.Compression
import org.apache.sshd.common.io.IoSession
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.common.session.helpers.CurrentService
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent
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
        val globalLegacy = applyLegacyAlgorithmsIfEnabled()
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
        val keepAlive = resolveKeepAliveSeconds(profile, globalKeepAlive)
        /*
         * Everything the session has to be told before it finishes being built.
         *
         * Resolved here, in one object, for the same reason the keep-alive interval is: by the time
         * `connect` holds a [ClientSession] the heartbeat, the authentication method list and the
         * algorithm proposal are already fixed. See [LivenessClientSession].
         */
        val tuning = SessionTuning(
            keepAliveSeconds = keepAlive,
            serverAliveCountMax = profile.serverAliveCountMax
                .coerceIn(SERVER_ALIVE_COUNT_RANGE.first, SERVER_ALIVE_COUNT_RANGE.last),
            compression = profile.compression,
            preferredAuths = preferredAuths(profile),
            hostKeyPolicy = profile.hostKeyPolicy,
            algorithms = algorithmsOverrideFor(profile.legacyAlgorithms, globalLegacy),
        )
        // Separate from the connect budget on purpose: a server can answer its socket in a millisecond
        // and then spend a minute in a PAM stack or waiting for a hardware token. See
        // [HostProfile.authTimeoutSeconds].
        val authTimeout = profile.authTimeoutSeconds
            .coerceIn(AUTH_TIMEOUT_RANGE.first, AUTH_TIMEOUT_RANGE.last)
            .toLong()
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
                put(TUNING_KEY, tuning)
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
        try {
            awaitHandshake(session, timeout)
            // The transport is up and the key exchange is done; everything from here is the server
            // deciding whether to let this user in.
            onPhase(SshConnectPhase.AUTHENTICATE)
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
            configureIdleTimeout(session, keepAlive, tuning.serverAliveCountMax)
            // MINA's own authentication clock, which it checks against `authStart` on every idle sweep
            // and which is otherwise a flat 30 seconds however long the user allowed. Set alongside the
            // `verify` budget rather than instead of it: this one ends the *session* from MINA's side,
            // the other ends the *wait* on this side, and a wait that outlives the session reports a
            // closed transport instead of a timeout.
            CoreModuleProperties.AUTH_TIMEOUT.set(session, Duration.ofSeconds(authTimeout))
            session.auth().verify(authTimeout, TimeUnit.SECONDS)
            session
        } catch (error: Throwable) {
            // The handshake, auth or pre-auth setup failed: close the socket so retries (the app
            // tries up to 3 times) do not accumulate half-open sessions.
            runCatching { session.close(false) }
            throw error
        }
    }

    /**
     * Waits up to [timeoutSeconds] for [session] to finish its SSH handshake.
     *
     * MINA's connect future resolves as soon as the *socket* is up, not when the handshake is done. Without
     * this wait, a server that accepts the connection and then says nothing - a port held open by a
     * firewall, a load balancer in front of a dead backend, a box being rebooted - is charged to the
     * authentication budget, which exists for something else entirely: the server thinking about
     * credentials it has already been sent, in a PAM stack or against a hardware token. The user would be
     * told the connection timed out after a number they configured for a later phase, and the phase
     * reported would be `AUTHENTICATE` on a server that never asked for a password.
     *
     * [ClientSessionEvent.WAIT_AUTH] is MINA's own name for the boundary between the two budgets: its
     * session state adds it once the key exchange is `DONE` and no authentication has been attempted yet.
     * [ClientSessionEvent.AUTHED] is accepted for the same reason - it can only mean the handshake is
     * further along than this frame needs.
     *
     * [ClientSessionEvent.CLOSED] is waited for but deliberately not thrown on. A handshake that fails for
     * a real reason - no key exchange in common, no cipher in common, a host key the verifier refused -
     * closes the session carrying that reason, and the `auth()` call that follows reports it verbatim. A
     * message invented here would replace a precise diagnostic with a vague one.
     */
    private fun awaitHandshake(session: ClientSession, timeoutSeconds: Long) {
        val settled = EnumSet.of(
            ClientSessionEvent.WAIT_AUTH,
            ClientSessionEvent.AUTHED,
            ClientSessionEvent.CLOSED,
        )
        // Returns as soon as the session's state has any of these in common with `settled`, and on expiry
        // returns whatever the state is plus TIMEOUT - so "none of the three" is exactly "it expired",
        // stated in terms of what was waited for rather than in terms of MINA's expiry marker.
        val state = session.waitFor(settled, Duration.ofSeconds(timeoutSeconds))
        // Phrased the way MINA phrases its own expiries, because this is the same kind of answer and the
        // milliseconds are what a user compares against the number they typed.
        if (state.none { it in settled }) {
            throw SshException(
                "Failed to complete the SSH handshake within specified timeout: ${timeoutSeconds * 1_000} msec",
            )
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
        profile: HostProfile? = null,
    ): TerminalChannel = withContext(Dispatchers.IO) {
        val pty = ptyRequestFor(profile, columns, rows)
        val channel = TerminalChannel(session.createShellChannel())
        channel.open(
            columns = pty.columns ?: channel.ptyColumns,
            rows = pty.rows ?: channel.ptyRows,
            usePty = pty.enabled,
            terminalType = pty.terminalType,
        )
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
    private suspend fun applyLegacyAlgorithmsIfEnabled(): Boolean {
        val legacy = runCatching { settingsRepository.settings.first().legacyAlgorithms }.getOrDefault(false)
        if (legacyApplied == legacy) return legacy
        synchronized(this) {
            if (legacyApplied == legacy) return legacy
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
        return legacy
    }

    /**
     * The four algorithm lists to install on one session, or null to leave it on the client's.
     *
     * A host only gets its own lists when it disagrees with the global switch, which keeps the common
     * case - every host following one setting - on exactly the path it was on before, lists included.
     * A disagreeing host gets *session*-level lists rather than a second write to the client's,
     * because the client is a singleton shared by every connection: two hosts dialled at once with
     * different answers would otherwise each get whichever set was installed last, and neither would
     * be reproducible.
     */
    private fun algorithmsOverrideFor(hostPreference: Boolean?, global: Boolean): SessionAlgorithms? {
        if (hostPreference == null || hostPreference == global) return null
        return if (hostPreference) {
            SessionAlgorithms(
                ciphers = modernCipherFactories + legacyCiphers.supported(),
                macs = modernMacFactories + legacyMacs.supported(),
                signatures = modernSignatureFactories + legacySignatures.supported(),
                keyExchange = modernKeyExchangeFactories + legacyKeyExchangeFactories,
            )
        } else {
            SessionAlgorithms(
                ciphers = modernCipherFactories.toList(),
                macs = modernMacFactories.toList(),
                signatures = modernSignatureFactories.toList(),
                keyExchange = modernKeyExchangeFactories.toList(),
            )
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
     *
     * With the keep-alive switched off it is switched off with it - [idleTimeoutSeconds] returns zero,
     * which MINA's `checkIdleTimeout` treats as "no idle timeout" because it only acts on a positive
     * duration. That coupling is the whole point: a backstop for a heartbeat that is not running is not
     * a backstop, it is a timer that ends healthy idle sessions a couple of minutes after login, and it
     * would fire on exactly the host whose owner asked for less traffic, not more.
     */
    private fun configureIdleTimeout(session: ClientSession, keepAliveSeconds: Int, serverAliveCountMax: Int) {
        CoreModuleProperties.IDLE_TIMEOUT.set(
            session,
            Duration.ofSeconds(idleTimeoutSeconds(keepAliveSeconds, serverAliveCountMax)),
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
    }
}

/** Slack between the last heartbeat that could arrive and the idle timeout firing. */
private const val IDLE_TIMEOUT_MARGIN_SECONDS = 60L

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

/** What to do with a host key the store has not already accepted. */
internal enum class HostKeyDecision { PIN, ASK, REFUSE }

/**
 * The rule applied to a host key that does not match what is pinned.
 *
 * Only a *first* sighting is a question, and only then does the policy get a say. A key that changed
 * falls through to the challenge under [HostKeyPolicy.ACCEPT_NEW] as well, because trust-on-first-use
 * means exactly that: the first use. Silently accepting the second, different key would turn pinning
 * into decoration and hide the one event it exists to catch.
 */
internal fun hostKeyDecision(policy: HostKeyPolicy, keyChanged: Boolean): HostKeyDecision = when {
    // Refuses either way: a strict host connects to a pinned key or it does not connect.
    policy == HostKeyPolicy.STRICT -> HostKeyDecision.REFUSE
    keyChanged -> HostKeyDecision.ASK
    policy == HostKeyPolicy.ACCEPT_NEW -> HostKeyDecision.PIN
    else -> HostKeyDecision.ASK
}

/** A host and port the verifier can name, and whether that host is this device. */
internal class SocketIdentity(val host: String, val port: Int, val isLoopback: Boolean)

internal class KnownHostsVerifier(
    private val store: KnownHostsStore,
    private val tunnelledTarget: (String?) -> TunnelledTarget?,
    /**
     * The policy of the host being dialled, read off the session because this verifier is a single
     * object shared by every dial: a field would be whatever the last connect wrote.
     */
    private val hostKeyPolicy: (ClientSession?) -> HostKeyPolicy = { session ->
        sessionTuning(session)?.hostKeyPolicy ?: HostKeyPolicy.ASK
    },
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
        // The key is not the pinned one; [hostKeyDecision] holds the rule for what that means here.
        when (hostKeyDecision(hostKeyPolicy(clientSession), keyChanged = expected != null)) {
            HostKeyDecision.REFUSE -> return false
            HostKeyDecision.PIN -> {
                store.save(address.host, address.port, actual)
                return true
            }
            HostKeyDecision.ASK -> Unit
        }
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
 * Everything about a host that has to be applied while its session is still being built.
 *
 * MINA copies the [AttributeRepository] passed to `connect` onto the [IoSession] before the session
 * factory ever sees it, which makes it the one channel that reaches session construction - and
 * construction is the only moment several of these values can still be read. See
 * [LivenessClientSession] for why, and [applySessionTuning] for what is done with them.
 *
 * [hostKeyPolicy] rides along for a different reason: it is not needed early, but the host-key
 * verifier is a single object shared by every dial, so the *session* has to carry the answer to "which
 * host's rules apply to this key" rather than the manager holding it in a field two concurrent
 * connects would fight over.
 */
internal class SessionTuning(
    val keepAliveSeconds: Int,
    val serverAliveCountMax: Int,
    val compression: Boolean,
    val preferredAuths: String,
    val hostKeyPolicy: HostKeyPolicy,
    val algorithms: SessionAlgorithms?,
)

/**
 * A complete algorithm proposal for one session.
 *
 * Per-session rather than per-client because [SshClient] is a singleton here: writing a host's
 * preference onto the client would silently re-proposal every *other* dial in flight, and two hosts
 * with opposite settings connecting at once would each get whichever list the other wrote last. MINA
 * resolves these lists per session with the client as fallback, so setting them on the session is both
 * correct and invisible to everyone else.
 */
internal class SessionAlgorithms(
    val ciphers: List<NamedFactory<Cipher>>,
    val macs: List<NamedFactory<Mac>>,
    val signatures: List<NamedFactory<Signature>>,
    val keyExchange: List<KeyExchangeFactory>,
)

/** [SessionTuning] as carried on the connection context. */
private val TUNING_KEY = AttributeRepository.AttributeKey<SessionTuning>()

/**
 * The keep-alive interval to use for [profile], in seconds, or `0` for "no keep-alive at all".
 *
 * Three settings collapse into one number here. [HostProfile.keepAliveEnabled] is the switch,
 * [HostProfile.keepAliveSeconds] is the host's own interval and a null there means "inherit", so
 * [globalSeconds] - the value from Settings - is the fallback rather than a constant.
 */
internal fun resolveKeepAliveSeconds(profile: HostProfile, globalSeconds: Int): Int {
    if (!profile.keepAliveEnabled) return 0
    val requested = profile.keepAliveSeconds ?: globalSeconds
    return requested.coerceIn(KEEP_ALIVE_RANGE.first, KEEP_ALIVE_RANGE.last)
}

/**
 * How long a session may sit without traffic before MINA closes it, in seconds, or `0` for never.
 *
 * The margin matters. This clock is a *backstop for the heartbeat*, so it has to outlive the full run
 * of missed replies the heartbeat is allowed - interval times count - or it fires first and reports an
 * idle timeout for a session the heartbeat was still perfectly happy about. The extra minute covers the
 * scheduling slop of a phone that was asleep for most of the interval.
 *
 * Returns `0` when the keep-alive is off, and that coupling is the point rather than an edge case: with
 * no heartbeat running there are no missed replies to back up, so an idle timeout stops being a
 * backstop and becomes the only thing in the system that ends healthy sessions - roughly two minutes
 * after login, on exactly the host whose owner asked for *less* traffic. MINA reads a non-positive
 * duration as "no idle timeout", so zero switches the timer off rather than setting it to instant.
 */
internal fun idleTimeoutSeconds(keepAliveSeconds: Int, serverAliveCountMax: Int): Long {
    if (keepAliveSeconds <= 0) return 0L
    val misses = serverAliveCountMax.coerceAtLeast(1).toLong()
    return keepAliveSeconds.toLong() * misses + IDLE_TIMEOUT_MARGIN_SECONDS
}

/**
 * The `PreferredAuthentications` list to offer for [profile].
 *
 * `publickey` leads whatever else is in the list because it is the one method that cannot leak a
 * password to a server that turns out not to be the intended one, and a client that offers a password
 * first has already sent it by the time it finds out.
 *
 * `keyboard-interactive` is what carries a one-time code, a PAM prompt or a 2FA challenge, so dropping
 * it locks out any account that needs one - but leaving it in means a server that offers it gets asked
 * for a password twice when the first answer was wrong, which is why it is a per-host switch rather
 * than a constant. A profile that authenticates *by* keyboard-interactive keeps it regardless: the
 * alternative is a profile that cannot log in with the method it was configured to use.
 */
internal fun preferredAuths(profile: HostProfile): String =
    if (profile.keyboardInteractiveAuth || profile.authMethod == AuthMethod.KEYBOARD_INTERACTIVE) {
        "publickey,keyboard-interactive,password"
    } else {
        "publickey,password"
    }

/**
 * What to ask the server for when opening a shell: pty or no pty, terminal type, and geometry.
 *
 * A null [columns]/[rows] means "the caller has not measured the viewport yet", which is a different
 * thing from a host that has *chosen* a size, and both differ again from a host that wants whatever the
 * screen happens to be. That last case is why the stored size uses zero as its sentinel rather than a
 * nullable column: a forced size has to survive a rotation, and "match the screen" has to lose to the
 * measurement every time.
 */
internal class PtyRequest(
    val enabled: Boolean,
    val terminalType: String,
    val columns: Int?,
    val rows: Int?,
)

internal fun ptyRequestFor(profile: HostProfile?, measuredColumns: Int?, measuredRows: Int?): PtyRequest {
    val forcedColumns = profile?.terminalColumns?.takeIf { it.isForcedTerminalSize() }
        ?.coerceIn(TERMINAL_COLUMNS_RANGE.first, TERMINAL_COLUMNS_RANGE.last)
    val forcedRows = profile?.terminalRows?.takeIf { it.isForcedTerminalSize() }
        ?.coerceIn(TERMINAL_ROWS_RANGE.first, TERMINAL_ROWS_RANGE.last)
    return PtyRequest(
        enabled = profile?.usePty ?: true,
        terminalType = profile?.terminalType?.takeIf(String::isNotBlank) ?: DEFAULT_TERMINAL_TYPE,
        columns = forcedColumns ?: measuredColumns,
        rows = forcedRows ?: measuredRows,
    )
}

/**
 * The compression proposal for a host, ordered best-first.
 *
 * `delayedZlib` (`zlib@openssh.com`) before plain `zlib` because it starts compressing only after
 * authentication, which is what OpenSSH itself prefers and what keeps a password out of a compressed
 * stream. `none` stays on the end of the enabled list as well: it is a *proposal*, and a server with no
 * compression support would have nothing to agree to otherwise.
 *
 * Filtered by [BuiltinCompressions.isSupported] because the zlib factories need a JCE/JZlib provider
 * that is not guaranteed on every Android image, and proposing an algorithm this client cannot actually
 * instantiate fails the key exchange rather than the compression.
 */
internal fun compressionFactories(enabled: Boolean): List<NamedFactory<Compression>> {
    val none = listOf(BuiltinCompressions.none)
    if (!enabled) return none
    val preferred = listOf(BuiltinCompressions.delayedZlib, BuiltinCompressions.zlib)
        .filter(BuiltinCompressions::isSupported)
    return preferred + none
}

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
 * [noReplyMax] missed replies before giving up, mirroring `ServerAliveCountMax`, so a single dropped
 * packet or one long garbage-collection pause on the server cannot end a working session.
 *
 * `HEARTBEAT_REPLY_WAIT` is deliberately not set: it is deprecated, and `configureMaxNoReply` ignores
 * it entirely whenever `HEARTBEAT_NO_REPLY_MAX` is set explicitly, as it is here.
 *
 * A non-positive [keepAliveSeconds] switches the heartbeat off instead of scheduling one, for the host
 * whose owner turned it off - a metered link, or a server that logs every global request. MINA reads a
 * non-positive `HEARTBEAT_INTERVAL` as "do not schedule", and the request name and miss limit are left
 * unwritten so nothing downstream can mistake a disabled heartbeat for a configured one.
 */
private fun armHeartbeat(
    resolver: PropertyResolver,
    keepAliveSeconds: Int,
    noReplyMax: Int = HEARTBEAT_NO_REPLY_MAX,
) {
    if (keepAliveSeconds <= 0) {
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(resolver, Duration.ZERO)
        return
    }
    CoreModuleProperties.HEARTBEAT_INTERVAL.set(resolver, Duration.ofSeconds(keepAliveSeconds.toLong()))
    CoreModuleProperties.HEARTBEAT_REQUEST.set(resolver, KEEPALIVE_REQUEST)
    CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(resolver, noReplyMax.coerceAtLeast(1))
}

/**
 * Applies the part of [tuning] that has to be in place before the session finishes constructing.
 *
 * Called from [LivenessClientSession.initializeCurrentService], which runs inside `AbstractSession`'s
 * constructor - before `ClientUserAuthService` reads [CoreModuleProperties.PREFERRED_AUTHS] into a
 * field, and before `ClientSessionImpl` sends its `SSH_MSG_KEXINIT`. That is what makes the algorithm
 * and compression lists take effect: written any later they read back exactly as set and change
 * nothing, because the proposal has already gone out.
 */
private fun applySessionTuning(session: ClientSession, tuning: SessionTuning) {
    armHeartbeat(session, tuning.keepAliveSeconds, tuning.serverAliveCountMax)
    CoreModuleProperties.PREFERRED_AUTHS.set(session, tuning.preferredAuths)
    session.compressionFactories = compressionFactories(tuning.compression)
    tuning.algorithms?.let { algorithms ->
        session.cipherFactories = algorithms.ciphers
        session.macFactories = algorithms.macs
        session.signatureFactories = algorithms.signatures
        session.keyExchangeFactories = algorithms.keyExchange
    }
}

/**
 * The [SessionTuning] a session was dialled with, if it was dialled by this app.
 *
 * Read off the [IoSession] rather than through `getConnectionContext()`: MINA attaches the repository
 * to the socket before the session is constructed, whereas the session's own accessor is populated by
 * the connector afterwards - and the host-key verifier, the one caller that needs this, runs during the
 * key exchange, which can start in between.
 */
private fun sessionTuning(session: ClientSession?): SessionTuning? {
    val context = session?.ioSession?.getAttribute(AttributeRepository::class.java) as? AttributeRepository
    return context?.getAttribute(TUNING_KEY)
}

/**
 * A client session that applies its host's configuration while it is still being constructed.
 *
 * This exists because of *when* `ClientConnectionService` reads its configuration. MINA builds both
 * services - userauth and connection - inside `AbstractSession`'s constructor, by way of
 * `initializeCurrentService`, and the connection service copies the three heartbeat properties into
 * `final` fields there and then. By the time `connect` holds a `ClientSession` the values are already
 * fixed, so setting them on the session at that point configures nothing at all: the session silently
 * keeps whatever the client-level resolver happened to hold. That is not a detail worth working around
 * in the caller, because it is invisible - every property reads back exactly as it was written.
 *
 * Overriding [initializeCurrentService] puts the per-host configuration on the session's own resolver
 * one step before the service is built, which is the last moment it can matter. The heartbeat is the
 * clearest case but not the only one: the authentication-method list and the algorithm proposal are
 * fixed in the same window. See [applySessionTuning].
 */
private class LivenessClientSession(
    manager: ClientFactoryManager,
    ioSession: IoSession,
) : ClientSessionImpl(manager, ioSession) {

    override fun initializeCurrentService(): CurrentService {
        // Runs from the superclass constructor, so nothing declared by this class is initialised yet -
        // hence the attribute lookup rather than a constructor parameter.
        val context = getIoSession().getAttribute(AttributeRepository::class.java) as? AttributeRepository
        context?.getAttribute(TUNING_KEY)?.let { applySessionTuning(this, it) }
        return super.initializeCurrentService()
    }
}

/** Makes [LivenessClientSession] the session every connection gets. */
private class LivenessSessionFactory(client: ClientFactoryManager) : SessionFactory(client) {
    override fun doCreateSession(ioSession: IoSession): ClientSessionImpl =
        LivenessClientSession(getClient(), ioSession)
}
