package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.archive.SftpArchiveByteSource
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_AUTH_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalKeys
import dev.eclipse.ssh.terminal.Utf8StreamDecoder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.helpers.AbstractConnectionService
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end test of the app's real SSH stack (SshConnectionManager, terminal,
 * SFTP) against an in-process Apache MINA SSHD server. Exercises password auth,
 * the known-hosts challenge flow, command execution, terminal I/O and SFTP
 * upload/download/chmod without needing an emulator.
 */
@RunWith(RobolectricTestRunner::class)
class SshIntegrationTest {

    companion object {
        /**
         * Whatever the kernel hands out, read back after the bind. A fixed port only looked safe: the
         * debug and release unit-test tasks are separate JVMs, they can overlap, and the loser of a
         * bind ran its whole suite against a server that never started.
         */
        private var serverPort = 0

        /**
         * Spelled out rather than taken from [InetAddress.getLoopbackAddress], whose answer depends
         * on `java.net.preferIPv6Addresses` — the profile host and the bound socket have to agree.
         */
        private const val LOOPBACK = "127.0.0.1"
        private const val USER = "testuser"
        private const val PASSWORD = "testpass123"

        /**
         * How long a terminal assertion waits. Generous because the echo server answers on its own
         * thread and the suite shares one JVM: a failure here should mean the pipeline is broken, not
         * that the machine was busy.
         */
        private const val TERMINAL_WAIT_MS = 20_000L
        private lateinit var root: Path
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            root = Files.createTempDirectory("eclipse-ssh-it")
            Files.createDirectories(root.resolve("sftp_test/subdir"))
            Files.write(root.resolve("sftp_test/hello.txt"), "hello from integration test\n".toByteArray())
            Files.write(root.resolve("sftp_test/large.bin"), ByteArray(256 * 1024))
            Files.write(root.resolve("sftp_test/subdir/nested.txt"), "nested\n".toByteArray())

            server = SshServer.setUpDefaultServer()
            server.port = 0
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("it-hostkey", ".ser"))
            server.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, _ ->
                username == USER && password == PASSWORD
            }
            server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { username, _, _ -> username == USER }
            server.subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
            server.fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
            // Provide an interactive echo shell + exec commands so the terminal and
            // runCommand paths have a real server side to talk to.
            server.shellFactory = ShellFactory { EchoCommand() }
            server.commandFactory = CommandFactory { _, command -> EchoCommand(initialReply = command) }
            server.start()
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop() }
        }
    }

    /**
     * Compares two file bodies and reports *where* they differ.
     *
     * Not `assertThat(actual).isEqualTo(expected)`: Truth renders a byte array by printing every
     * element, so a mismatch on a 200 KiB file produced a 2.8 MB failure message that had to be
     * searched to learn anything. The first differing offset is the whole diagnosis for the bugs
     * these tests cover — a hole starts at the resume offset, a duplicated block starts one buffer
     * later — so that is what this prints.
     */
    private fun assertSameBytes(expected: ByteArray, actual: ByteArray) {
        val firstDifference = (0 until minOf(expected.size, actual.size))
            .firstOrNull { expected[it] != actual[it] }
        if (firstDifference == null && expected.size == actual.size) return
        val detail = when {
            firstDifference != null -> "first differs at byte $firstDifference " +
                "(expected 0x%02X, got 0x%02X)".format(expected[firstDifference], actual[firstDifference])
            else -> "identical up to the shorter length, then truncated or padded"
        }
        throw AssertionError(
            "remote file does not match the source: expected ${expected.size} bytes, " +
                "got ${actual.size}; $detail",
        )
    }

    private fun hostProfile() = HostProfile(
        name = "integration",
        host = "127.0.0.1",
        username = USER,
        port = serverPort,
        authMethod = AuthMethod.PASSWORD,
        // Stated, and roomier than the app default, on purpose. These tests assert SSH behaviour,
        // not handshake latency: the suite shares one JVM with the Robolectric classes, and a
        // loopback handshake that takes under a second in isolation has been seen to blow past the
        // 15-second default on a loaded machine. A failure here should mean the SSH stack is wrong,
        // never that the box was busy.
        //
        // Not larger than this, though. The same number also caps `session.auth().verify(...)`, so
        // an over-generous value would let a genuinely wedged handshake sit until JUnit's 120-second
        // timeout killed the test — a hang reported as a timeout instead of as the failure it is.
        // Nothing here should ever actually wait this long; if something does, that is the finding.
        //
        // The production default is covered by the unit tests, and
        // [aPerHostTimeoutIsHonouredInsteadOfTheOldHardCodedFifteenSeconds] proves the field is what
        // the engine reads rather than a number the UI collects and MINA ignores.
        connectTimeoutSeconds = 60,
    )

    /**
     * Connects, and the first time the server key is unknown it captures the
     * emitted challenge, trusts the fingerprint, then reconnects — mirroring the
     * app's acceptHostKey() flow. Subsequent calls connect directly.
     *
     * The challenge flow uses a SharedFlow with no replay and tryEmit, which
     * resumes the collector synchronously on the emitting (SSHD) thread, so a
     * long-lived collector must be active *before* the connect that triggers the
     * verifier; an AtomicReference gives cross-thread visibility.
     */
    private suspend fun trustedConnect(
        manager: SshConnectionManager,
        profile: HostProfile,
        password: String? = null,
        keyPair: KeyPair? = null,
    ): org.apache.sshd.client.session.ClientSession = coroutineScope {
        val challengeRef = AtomicReference<HostKeyChallenge?>()
        val collector = launch { manager.hostKeyChallenges.collect { challengeRef.set(it) } }
        val attempt = runCatching { manager.connect(profile, password, keyPair) }
        delay(300)
        collector.cancel()
        val challenge = challengeRef.get()
        if (attempt.isSuccess) return@coroutineScope attempt.getOrThrow()
        if (challenge != null) {
            manager.trustHost(challenge)
            return@coroutineScope manager.connect(profile, password, keyPair)
        }
        throw attempt.exceptionOrNull() ?: IllegalStateException("connect failed without a host-key challenge")
    }

    /**
     * Authenticates against [server] with [keyPair] and nothing else, and proves the key is what got
     * in.
     *
     * No password is offered, so a session here cannot have been reached by any other means — the
     * server's password authenticator never sees a credential to accept. The command afterwards is
     * the second half of the proof: `isAuthenticated` is true from the moment `auth().verify`
     * returns, and a channel that opens and answers is what shows the session is actually usable
     * rather than merely past the handshake.
     */
    private suspend fun assertKeyAuthenticates(keyPair: KeyPair) {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        try {
            val profile = hostProfile().copy(authMethod = AuthMethod.SSH_KEY)
            trustedConnect(manager, profile, password = null, keyPair = keyPair).use { session ->
                assertThat(session.isAuthenticated).isTrue()
                assertThat(manager.runCommand(session, "echo key-auth-ok")).contains("key-auth-ok")
            }
        } finally {
            manager.close()
        }
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("keys/$name")) { "Missing fixture keys/$name" }
            .use { it.readBytes() }

    /**
     * A second server, cut down by [restrict] to algorithms the app should offer only when legacy
     * compatibility is on. Whether the client offers them is then observable as connect success or
     * failure — measured on the wire, rather than by reading a field the client happens to expose.
     *
     * Its own port and its own host key, so trusting it cannot disturb the main server's known-hosts
     * entry. The key file is a fresh name inside a new temp directory rather than a
     * `createTempFile` path, because SSHD logs a "is not a host key" warning when asked to load the
     * empty file that would leave behind.
     */
    private fun startRestrictedServer(restrict: SshServer.() -> Unit): SshServer =
        SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                Files.createTempDirectory("legacy-host").resolve("hostkey.ser"),
            )
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == USER && password == PASSWORD
            }
            restrict()
            start()
        }

    /**
     * Connects to [server] once with legacy compatibility off and once with it on, asserting the first
     * is refused during the handshake and the second reaches an authenticated session.
     *
     * The refusal is checked for *where* it happened rather than just for being a failure: a connect that
     * failed because the port was busy or the server had not finished starting would otherwise satisfy
     * the same assertion and the test would pass while proving nothing. [failedDuringSshHandshake] is
     * that check, and it is deliberately not a string match on MINA's negotiation text, because a server
     * with no algorithm in common has two ways to say so - a disconnect packet naming the negotiation,
     * and simply closing the socket, which on a loaded machine can beat the packet and surfaces as a
     * connect future with no session attached. Both are the server refusing this proposal at the
     * handshake; neither is a port that was busy, and asserting only the first made this test fail once
     * in a while for a reason that had nothing to do with what it is about.
     */
    private suspend fun assertNeedsLegacyCompatibility(
        settings: SettingsRepository,
        manager: SshConnectionManager,
        server: SshServer,
    ) {
        val profile = hostProfile().copy(port = server.port)

        settings.setLegacyAlgorithms(false)
        val refused = runCatching { trustedConnect(manager, profile, PASSWORD) }
        assertThat(refused.isFailure).isTrue()
        assertThat(failedDuringSshHandshake(refused.exceptionOrNull())).isTrue()

        settings.setLegacyAlgorithms(true)
        val session = trustedConnect(manager, profile, PASSWORD)
        assertThat(session.isAuthenticated).isTrue()
        session.close(false)
    }

    /**
     * Runs [body] with a restricted server and a manager of its own, then puts the shared setting back.
     *
     * The restore is not optional: `preferencesDataStore` caches one store per delegate for the whole
     * classloader and Robolectric hands each test method a fresh `filesDir`, so a stray `true` left
     * here becomes a failure in some other class that never touched the setting.
     */
    private fun withLegacyServer(restrict: SshServer.() -> Unit, body: suspend (SettingsRepository, SshConnectionManager, SshServer) -> Unit) {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val settings = SettingsRepository(context)
            val server = startRestrictedServer(restrict)
            val manager = SshConnectionManager(context, settings)
            try {
                body(settings, manager, server)
            } finally {
                manager.close()
                runCatching { server.stop() }
                runCatching { settings.setLegacyAlgorithms(false) }
            }
        }
    }

    /**
     * Turning legacy compatibility back off has to actually take the old algorithms away again.
     *
     * The bug this pins was a way to weaken the app permanently by accident: the legacy set was applied
     * the first time the setting was seen enabled and then latched for the life of the process, so
     * switching it on to reach one elderly server and switching it straight back off left every later
     * connection still offering the weakened set — with the switch in Settings showing "off". The third
     * step is the one that used to pass when it should not.
     *
     * One manager across all three steps on purpose: the latch was per instance, so a fresh manager
     * between steps would hide exactly the defect this is about.
     */
    @Test(timeout = 180_000)
    fun `turning legacy algorithms back off stops offering them again`() {
        withLegacyServer({ cipherFactories = listOf(BuiltinCiphers.aes128cbc) }) { settings, manager, server ->
            assertNeedsLegacyCompatibility(settings, manager, server)

            settings.setLegacyAlgorithms(false)
            val afterDisabling = runCatching { trustedConnect(manager, hostProfile().copy(port = server.port), PASSWORD) }
            assertThat(afterDisabling.isFailure).isTrue()
            assertThat(failedDuringSshHandshake(afterDisabling.exceptionOrNull())).isTrue()
        }
    }

    /**
     * CBC ciphers are behind the switch, not on by default.
     *
     * Apache MINA SSHD still lists `aes128/192/256-cbc` among its default client ciphers; OpenSSH
     * stopped offering CBC by default in 6.7. Taking MINA's list verbatim meant the app negotiated CBC
     * with every server no matter what the setting said — and made the switch's own cipher additions
     * very nearly a no-op. Same assertion as the round trip above, kept separately because this one is
     * about the default and that one is about the latch.
     */
    @Test(timeout = 180_000)
    fun `a cbc only server cannot be reached without legacy compatibility`() {
        withLegacyServer({ cipherFactories = listOf(BuiltinCiphers.aes256cbc) }, ::assertNeedsLegacyCompatibility)
    }

    /**
     * SHA-1 `ssh-rsa` host keys are behind the switch too.
     *
     * MINA's default signature list still contains it; OpenSSH disabled it in 8.8. An RSA host key and
     * nothing but the `ssh-rsa` signature is what a pre-7.2 server looks like, and `rsa-sha2-256` and
     * `rsa-sha2-512` — which the app does offer by default — are not enough for it.
     */
    @Test(timeout = 180_000)
    fun `an ssh-rsa only host key cannot be verified without legacy compatibility`() {
        withLegacyServer(
            {
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("legacy-rsa-host").resolve("hostkey.ser"),
                ).apply {
                    algorithm = "RSA"
                    keySize = 2048
                }
                signatureFactories = listOf(BuiltinSignatures.rsa)
            },
            ::assertNeedsLegacyCompatibility,
        )
    }

    /**
     * The truncated and MD5 HMACs are behind the switch.
     *
     * The cipher is pinned to `aes128-ctr` as well, and that is load-bearing: MAC negotiation only
     * happens for ciphers that do not authenticate their own output, so a server left free to choose
     * ChaCha20-Poly1305 or AES-GCM would agree a session without ever looking at the MAC lists and this
     * would pass whatever the client offered.
     */
    @Test(timeout = 180_000)
    fun `a truncated hmac server cannot be reached without legacy compatibility`() {
        withLegacyServer(
            {
                cipherFactories = listOf(BuiltinCiphers.aes128ctr)
                macFactories = listOf(BuiltinMacs.hmacsha196)
            },
            ::assertNeedsLegacyCompatibility,
        )
    }

    /**
     * The switch has to deliver the key exchange its own description promises.
     *
     * Settings offers it as "Compatibility with older SSH servers (CBC, dh-group1)" and it used to
     * change no key exchange algorithm at all, so the one server generation it named could not be
     * reached with it on. MINA's defaults stop at group14-*sha256*, matching OpenSSH, so the SHA-1
     * groups have to come from somewhere — and this is the switch that says they do.
     *
     * Asserted with group14-sha1 rather than group1-sha1: both are in the legacy set, and this one
     * needs no 1024-bit Diffie-Hellman, which a hardened JCE provider may refuse outright.
     */
    @Test(timeout = 180_000)
    fun `a sha1 key exchange server cannot be reached without legacy compatibility`() {
        assumeTrue(
            "this JVM's providers cannot do diffie-hellman-group14-sha1",
            BuiltinDHFactories.dhg14.isSupported,
        )
        withLegacyServer(
            { keyExchangeFactories = listOf(ServerBuilder.DH2KEX.apply(BuiltinDHFactories.dhg14)) },
            ::assertNeedsLegacyCompatibility,
        )
    }

    @Test(timeout = 120_000)
    fun `password connect runs command and opens terminal`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        try {
            val session = trustedConnect(manager, hostProfile(), PASSWORD)
            assertThat(session.isAuthenticated).isTrue()

            // Command execution through the real channel.
            val output = manager.runCommand(session, "echo integration-ok")
            assertThat(output).contains("integration-ok")

            // Terminal shell: read the banner until a prompt appears, then send input
            // and wait for the echo to come back through the channel. The collector is
            // resumed on the SSHD thread (tryEmit), so the banner is an AtomicReference.
            val terminal = manager.openTerminal(session)
            val banner = AtomicReference("")
            val job = launch {
                terminal.output.collect { chunk ->
                    // Bytes now, decoded here as the ViewModel's collector does.
                    banner.updateAndGet { it + String(chunk, Charsets.UTF_8) }
                    if (banner.get().contains("$")) terminal.write("echo tty-works\n")
                }
            }
            try {
                // Wait for our echo to come back through the PTY. delay() (not
                // Thread.sleep) lets the runBlocking event loop schedule the
                // collector coroutine so it can subscribe to the terminal flow.
                val deadline = System.currentTimeMillis() + 15_000
                while (!banner.get().contains("tty-works") && System.currentTimeMillis() < deadline) {
                    delay(100)
                }
                assertThat(banner.get()).contains("tty-works")
            } finally {
                job.cancel()
                terminal.close()
            }
        } finally {
            manager.close()
        }
        }
    }

    /**
     * The whole terminal pipeline over a real SSH connection: authenticate, attach a shell, decode
     * what comes back, draw it, type into it, resize it, and shut it down.
     *
     * The unit tests cover each stage against synthetic input; this is the only test where the bytes
     * come off a socket. That distinction has caught real bugs - a chunk boundary falls wherever the
     * network puts it, so a decoder that works on whole strings and an emulator that assumes each feed
     * is a complete escape sequence both pass their own tests and fail here.
     */
    @Test(timeout = 120_000)
    fun `a shell session is attached decoded drawn and typed into`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = SshConnectionManager(context, SettingsRepository(context))
            try {
                val session = trustedConnect(manager, hostProfile(), PASSWORD)
                val terminal = manager.openTerminal(session)
                val buffer = AnsiTerminalBuffer()
                val decoder = Utf8StreamDecoder()
                buffer.resize(80, 24)
                // Exactly the ViewModel's collector: bytes to the decoder, text to the buffer.
                val pump = launch(Dispatchers.IO) {
                    terminal.output.collect { chunk -> buffer.feed(decoder.decode(chunk)) }
                }
                try {
                    awaitTerminal(buffer) { it.contains("$") }
                    assertThat(buffer.plainText()).contains("$")

                    // Typed a character at a time, as the software keyboard does, and ended with the
                    // Enter key's own encoding rather than a newline in a string.
                    "echo lifecycle".forEach { char ->
                        terminal.writeBytes(TerminalKeys.encode(char))
                    }
                    terminal.writeBytes(TerminalKeys.encode(TerminalKey.ENTER))
                    awaitTerminal(buffer) { it.contains("echo: echo lifecycle") }

                    // A frame is what the renderer draws: the viewport, addressed absolutely.
                    val frame = buffer.frame()
                    assertThat(frame.rows).isEqualTo(24)
                    assertThat(frame.columns).isEqualTo(80)
                    assertThat(frame.lines.size).isAtMost(24)
                    assertThat(frame.firstLine + frame.lines.size).isEqualTo(frame.totalLines)

                    // Rotation. Both halves have to move together, and the server has to accept the
                    // window change on a live channel.
                    terminal.resize(100, 30)
                    buffer.resize(100, 30)
                    assertThat(buffer.frame().columns).isEqualTo(100)
                    assertThat(terminal.isOpen).isTrue()

                    // A UTF-8 codepoint split across two writes must survive the boundary, which is
                    // the case the old String-per-read channel got wrong.
                    val snowman = "\u2603".toByteArray(Charsets.UTF_8)
                    terminal.writeBytes(snowman.copyOfRange(0, 1))
                    terminal.writeBytes(snowman.copyOfRange(1, snowman.size))
                    terminal.writeBytes(TerminalKeys.encode(TerminalKey.ENTER))
                    awaitTerminal(buffer) { it.contains("echo: \u2603") }
                } finally {
                    pump.cancel()
                    terminal.close()
                }
                // Closing the channel must not take the session with it: the app keeps SFTP and port
                // forwards on the same session after a tab is closed.
                assertThat(terminal.isOpen).isFalse()
                assertThat(session.isOpen).isTrue()
                assertThat(manager.runCommand(session, "echo still-alive")).contains("still-alive")
            } finally {
                manager.close()
            }
        }
    }

    /** Waits for the terminal's rendered text to satisfy [predicate], or fails saying what it held. */
    private suspend fun awaitTerminal(buffer: AnsiTerminalBuffer, predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + TERMINAL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (predicate(buffer.plainText())) return
            delay(50)
        }
        throw AssertionError("terminal never matched; it held: " + buffer.plainText().takeLast(400))
    }

    /**
     * Every algorithm the app can generate produces a key that authenticates a real session.
     *
     * This is the test the ECDSA export bug walked straight past. `SshKeyAlgorithm.ECDSA_P256` used
     * to write its private key as PKCS#8, which is what `PrivateKey.getEncoded()` hands back — and
     * SunEC omits the optional public key from that encoding, so sshd's own EC parser refused the
     * file with "No public key data bytes". Nothing caught it, because BouncyCastle is on the
     * unit-test classpath (Robolectric brings it) and recomputes the point from the private scalar.
     * On a device, where no BouncyCastle exists, every generated ECDSA key was unusable: the Add Host
     * form reported "a key format this app cannot read" for a key the app had written seconds
     * earlier. Loading through [SshKeyLoader] and then *authenticating* with the result is what makes
     * that visible — the round-trip is the part the app depends on, and a bad export is only
     * detectable by attempting it.
     *
     * Driven off [SshKeyAlgorithm.entries] so a new algorithm is covered the moment it is added
     * rather than whenever someone remembers to write the case for it.
     */
    @Test(timeout = 120_000)
    fun `every generated key authenticates a real session`() {
        runBlocking {
            SshKeyAlgorithm.entries.forEach { algorithm ->
                val generated = algorithm.generate()
                val loaded = SshKeyLoader.load(
                    generated.privatePem.toByteArray(),
                    generated.defaultPrivateName,
                )
                assertKeyAuthenticates(loaded)
            }
        }
    }

    /**
     * An OpenSSH Ed25519 key file authenticates end to end.
     *
     * Ed25519 is the case that depends on sshd's optional EdDSA provider, so this is the end-to-end
     * proof for [dev.eclipse.ssh.EclipseApp.SSHD_PROVIDER_SCOPING]: the app stops sshd publishing
     * that provider into the process-wide `java.security.Security` list under the bare name `EdDSA`,
     * and holding it as an instance instead must not cost the app the ability to authenticate with
     * one of these keys. Reading a file was already covered by `SshKeyProbeTest`; getting a server to
     * accept the signature it produces was not.
     */
    @Test(timeout = 120_000)
    fun `an ed25519 key file authenticates a real session`() {
        runBlocking {
            assertKeyAuthenticates(SshKeyLoader.load(fixture("plain_ed25519"), "id_ed25519"))
        }
    }

    /**
     * A passphrase-protected key authenticates once the passphrase is supplied.
     *
     * The pair that matters for the form's flow: the same file, decrypted, has to reach a session.
     * `SshKeyProbeTest` proves the wrong passphrase is rejected and the right one parses; this proves
     * the key that comes out of that decryption is the real one and not merely a well-formed object.
     */
    @Test(timeout = 120_000)
    fun `an encrypted ed25519 key file authenticates once its passphrase is supplied`() {
        runBlocking {
            val loaded = SshKeyLoader.load(fixture("encrypted_ed25519"), "id_ed25519", "correct horse")
            assertKeyAuthenticates(loaded)
        }
    }

    /**
     * A key the server has never seen is refused, and the refusal does not blame the host key.
     *
     * The negative half of the pair above. Without it every test here would still pass if the client
     * silently fell back to some other method, or if the server's authenticator accepted anyone: this
     * is the case that fails in both of those worlds. The server accepts [USER] with any key, so the
     * rejection has to come from the username — which is the only thing this changes.
     */
    @Test(timeout = 120_000)
    fun `a key offered for the wrong user is refused`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = SshConnectionManager(context, SettingsRepository(context))
            try {
                // Trust the host key first, exactly as `wrong password fails with a clear error`
                // does: otherwise KnownHostsVerifier refuses before authentication is reached and
                // this would pass without testing anything about keys.
                val challengeRef = AtomicReference<HostKeyChallenge?>()
                val collector = launch { manager.hostKeyChallenges.collect { challengeRef.set(it) } }
                val keyPair = SshKeyAlgorithm.ECDSA_P256.generate().let {
                    SshKeyLoader.load(it.privatePem.toByteArray(), it.defaultPrivateName)
                }
                val profile = hostProfile().copy(username = "nobody", authMethod = AuthMethod.SSH_KEY)
                runCatching { manager.connect(profile, null, keyPair) }
                delay(300)
                collector.cancel()
                manager.trustHost(requireNotNull(challengeRef.get()) { "no host key challenge was raised" })

                val error = runCatching { manager.connect(profile, null, keyPair) }.exceptionOrNull()

                assertThat(error).isNotNull()
                assertThat(error!!.toString()).doesNotContain("Server key")

                // The control: the same key, the same manager, the same trusted host key, and only
                // the username put back. If this gets in, the refusal above was the user and not
                // some unrelated breakage in key authentication.
                trustedConnect(
                    manager,
                    hostProfile().copy(authMethod = AuthMethod.SSH_KEY),
                    password = null,
                    keyPair = keyPair,
                ).use { session -> assertThat(session.isAuthenticated).isTrue() }
            } finally {
                manager.close()
            }
        }
    }

    @Test(timeout = 120_000)
    fun `unknown host key raises a challenge that can be trusted and reconnects`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        try {
            // First connect: the server key is unknown -> challenge is emitted and connect fails.
            var challenge: HostKeyChallenge? = null
            val collector = launch { manager.hostKeyChallenges.collect { challenge = it } }
            runCatching { manager.connect(hostProfile(), PASSWORD) }
            Thread.sleep(500)
            collector.cancel()

            assertThat(challenge).isNotNull()
            assertThat(challenge!!.fingerprint).startsWith("SHA256:")

            // Trust the fingerprint, then a second connect succeeds (verifier accepts).
            manager.trustHost(challenge!!)
            val session = manager.connect(hostProfile(), PASSWORD)
            assertThat(session.isAuthenticated).isTrue()
            session.close(false)
        } finally {
            manager.close()
        }
        }
    }

    @Test(timeout = 120_000)
    fun `sftp lists uploads downloads renames and chmods`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        val directory = SftpDirectoryService()
        try {
            val session = trustedConnect(manager, hostProfile(), PASSWORD)
            manager.withSftp(session) { sftp ->
                // List
                val listing = directory.list(sftp, "sftp_test")
                val names = listing.map { it.name }
                assertThat(names).containsAtLeast("hello.txt", "large.bin", "subdir")

                // Stat / properties
                val hello = listing.first { it.name == "hello.txt" }
                assertThat(hello.size).isEqualTo("hello from integration test\n".length.toLong())

                // Download and verify contents
                val downloaded = java.io.ByteArrayOutputStream()
                sftp.read("sftp_test/hello.txt").use { input -> input.copyTo(downloaded) }
                assertThat(downloaded.toString(Charsets.UTF_8)).contains("hello from integration test")

                // Upload and verify size/rename
                val uploadBytes = ByteArray(32 * 1024) { it.toByte() }
                sftp.write("sftp_test/upload.bin").use { out -> out.write(uploadBytes) }
                assertThat(sftp.stat("sftp_test/upload.bin").size).isEqualTo(uploadBytes.size.toLong())
                directory.rename(sftp, "sftp_test/upload.bin", "sftp_test/renamed.bin")
                assertThat(directory.exists(sftp, "sftp_test/renamed.bin")).isTrue()

                // chmod
                directory.chmod(sftp, "sftp_test/hello.txt", 0b110_100_100)
                assertThat(directory.list(sftp, "sftp_test").first { it.name == "hello.txt" }.permissions).isEqualTo("644")

                // Recursive tree listing
                val tree = directory.listTree(sftp, "sftp_test")
                assertThat(tree.keys).contains("subdir/nested.txt")

                // Recursive copy of a directory
                directory.copy(sftp, "sftp_test/subdir", "sftp_test/subdir_copy")
                assertThat(directory.exists(sftp, "sftp_test/subdir_copy/nested.txt")).isTrue()

                // Delete
                directory.delete(sftp, "sftp_test/renamed.bin")
                assertThat(directory.exists(sftp, "sftp_test/renamed.bin")).isFalse()
            }
        } finally {
            manager.close()
        }
        }
    }

    /**
     * Where an SFTP lifetime runs, which on a real device decided whether a session survived it.
     *
     * The bug this pins ended three sessions in a row on a phone, 1.6 to 2.0 seconds after each login,
     * and reported itself as `Connection lost: NetworkOnMainThreadException` with a five-rung reconnect
     * ladder behind it. The link was fine. The code was `openSftp(session).use { ... }` called from a
     * `viewModelScope` body: `openSftp` does its work in `withContext(Dispatchers.IO)`, and a
     * `withContext` **resumes its caller on the caller's dispatcher** - so the client came back on
     * `Dispatchers.Main.immediate`, and `use`'s `finally` closed it there. Closing an SFTP client tears
     * down a channel, which is a socket write, which on Android's UI thread is a `BlockGuard` throw
     * raised *inside* MINA's write path - so the transport was already broken before any `catch` could
     * see it, and an app-side threading mistake arrived at the state machine as a transport fault.
     *
     * Two assertions, and the first is the mechanism the second exists to defeat:
     *
     *  - [SshConnectionManager.openSftp] hands its client back on the *caller's* dispatcher. That is not
     *    a defect - it is a hand-off for a caller that owns the client's lifetime, which is what the
     *    transfer coordinator does - and it is exactly why closing one in a `use` is the caller's
     *    problem rather than `openSftp`'s.
     *  - [SshConnectionManager.withSftp] runs the whole lifetime somewhere else. The block's dispatcher
     *    is asserted rather than only its thread, because `use`'s `finally` - the close - is the same
     *    coroutine frame as the block, so its dispatcher cannot be anything other than the one asserted
     *    here. That is the whole guarantee, and it is not observable from outside the frame.
     *
     * A named single-thread dispatcher stands in for the UI thread. Robolectric's `Dispatchers.Main` is
     * backed by the main looper, and the main looper is this thread, blocked in `runBlocking` - so a
     * coroutine that hopped to IO could never be resumed back onto it. The rule under test is
     * dispatcher-independent, and the production path through `viewModelScope` is covered end to end by
     * `SftpAutoLoginRobolectricTest`.
     */
    @Test(timeout = 120_000)
    fun `an sftp lifetime opened for a caller stays off the caller's dispatcher`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        val uiThreads = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "stand-in-ui") }
        val ui = uiThreads.asCoroutineDispatcher()
        try {
            val session = trustedConnect(manager, hostProfile(), PASSWORD)
            withContext(ui) {
                val caller = Thread.currentThread()
                // `startsWith`, not equality: with assertions enabled - which Gradle's test tasks do
                // by default - kotlinx.coroutines renames the running thread to `<name> @coroutine#N`
                // while a coroutine is on it. The prefix is the part that says which dispatcher this is.
                assertThat(caller.name).startsWith("stand-in-ui")

                // The hand-off: opened on IO, handed back here. This is the line that used to be
                // followed by `.use { }`, and `use` closes where it stands.
                val handedBack = manager.openSftp(session)
                assertThat(Thread.currentThread()).isEqualTo(caller)
                // Closed the way a hand-off's owner has to: somewhere that may block on a socket.
                withContext(Dispatchers.IO) { handedBack.close() }

                // The owned lifetime: open, work and close, none of them here.
                val where = manager.withSftp(session) { sftp ->
                    // A real round trip on the wire from inside the block, so this is not merely a
                    // statement about a dispatcher - the channel is open and answering from there.
                    assertThat(sftp.canonicalPath(".")).isNotEmpty()
                    Thread.currentThread() to currentCoroutineContext()[ContinuationInterceptor]
                }
                assertThat(where.first).isNotEqualTo(caller)
                assertThat(where.second).isEqualTo(Dispatchers.IO)
            }
            session.close(false)
        } finally {
            ui.close()
            uiThreads.shutdownNow()
            manager.close()
        }
        }
    }

    /**
     * A command that never answers fails the caller, rather than parking it forever.
     *
     * The bug this pins was an unbounded `executeRemoteCommand`: the stat producer asked `uptime`
     * and a server that had accepted the channel but gone quiet - wedged under load, or a
     * transport that died without the error reaching this side - held the producer for as long as
     * the process lived. The bound is what [SshConnectionManager.REMOTE_COMMAND_TIMEOUT_MS] ships
     * with; the override exists so this test can make it one second instead of thirty.
     *
     * The failure must also arrive as an [IOException], not as the [kotlinx.coroutines.TimeoutCancellationException]
     * it starts as: that class is a [kotlinx.coroutines.CancellationException], and the producer's
     * cancellation branch rethrows those on sight - a timeout surfacing as cancellation would
     * have silenced the stat card instead of marking it "Unavailable", which is the exact bug the
     * caller's own catch exists to avoid.
     */
    @Test(timeout = 120_000)
    fun `a command that never answers fails the caller instead of hanging it`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        // A server of its own: the shared one answers commands, and the whole point here is one
        // that takes the channel and never speaks again.
        val wedged = startRestrictedServer {
            commandFactory = CommandFactory { _, _ -> WedgedCommand() }
        }
        try {
            val profile = hostProfile().copy(port = wedged.port)
            trustedConnect(manager, profile, PASSWORD).use { session ->
                val startedAt = System.currentTimeMillis()
                val error = runCatching { manager.runCommand(session, "forever", timeoutMs = 1_000) }
                    .exceptionOrNull()

                assertThat(error).isInstanceOf(IOException::class.java)
                assertThat(error!!.message).contains("did not answer")
                // Bounded means bounded: generous over the one-second budget for a loaded CI box,
                // and far under what JUnit's own 120-second method timeout would have needed to
                // notice an unbounded wait. Closing the session (use) tears the wedged channel
                // down with it, releasing the IO thread the abandoned read still occupies.
                assertThat(System.currentTimeMillis() - startedAt).isLessThan(60_000)
            }
        } finally {
            manager.close()
            runCatching { wedged.stop() }
        }
        }
    }

    /**
     * An archive whose open fails must release the SFTP channel that open created.
     *
     * The race this pins is real on any host whose files move: something deletes or replaces the
     * archive between the stat that produced its size and the open that wants its bytes, and
     * `client.open` throws with the freshly created SFTP channel assigned to nothing - `close()`
     * only closes what the fields hold. Before the fix, one channel leaked per failed attempt,
     * and a server has a finite number of channel slots to give.
     *
     * Measured on the wire, as the connection service's live channel count, because that is what
     * the server side experiences - not a field the code under test happens to expose. Three
     * attempts so a single lucky release cannot pass a leak that only sometimes happens.
     */
    @Test(timeout = 120_000)
    fun `a failed archive open releases the sftp channel it opened`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        val store = SshSessionStore()
        try {
            val session = trustedConnect(manager, hostProfile(), PASSWORD)
            store.install(sessionKey = "it-archive-host", session = session, hostId = "it-archive-host")
            fun openChannelCount(): Int =
                // getChannels() lives on AbstractConnectionService, not on the ConnectionService
                // interface; the concrete service behind a client session is always the
                // implementation (ClientConnectionService), so the cast cannot fail here.
                (session.getService(ConnectionService::class.java) as AbstractConnectionService)
                    .channels.size
            val baseline = openChannelCount()

            repeat(3) {
                val source = SftpArchiveByteSource(
                    connectionManager = manager,
                    sessionStore = store,
                    hostId = "it-archive-host",
                    hostName = "integration",
                    remotePath = "sftp_test/deleted-between-stat-and-open.zip",
                    size = 64 * 1024,
                )
                val error = runCatching { source.readAt(0, 32) }.exceptionOrNull()
                assertThat(error).isNotNull()

                // The close runs in the throw path, but the channel leaving the service's list is
                // MINA's close event, which lands on its own thread: poll for it with a deadline
                // rather than assert it synchronously, so a leak fails instead of timing out.
                val released = withTimeoutOrNull(10_000) {
                    while (openChannelCount() > baseline) delay(100)
                    true
                } == true
                assertThat(released).isTrue()
            }

            session.close(false)
        } finally {
            manager.close()
        }
        }
    }

    @Test(timeout = 120_000)
    fun `wrong password fails with a clear error`() {
        runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = SshConnectionManager(context, SettingsRepository(context))
        try {
            // Trust the server key first. Every test method gets fresh SharedPreferences, so
            // without this the connection is refused by KnownHostsVerifier before a password is
            // ever offered: the assertion below would hold for a host-key rejection and this test
            // would pass without exercising authentication at all.
            val challengeRef = AtomicReference<HostKeyChallenge?>()
            val collector = launch { manager.hostKeyChallenges.collect { challengeRef.set(it) } }
            runCatching { manager.connect(hostProfile(), "wrong-password") }
            delay(300)
            collector.cancel()
            val challenge = requireNotNull(challengeRef.get()) { "no host key challenge was raised" }
            manager.trustHost(challenge)

            val error = runCatching { manager.connect(hostProfile(), "wrong-password") }.exceptionOrNull()

            assertThat(error).isNotNull()
            // The key is trusted now, so a rejection can only come from the password — and the
            // message must not still be blaming the host key, which is what the user would be shown.
            assertThat(error!!.toString()).doesNotContain("Server key")

            // The control. Same manager, same trusted key, same profile: if the correct password
            // gets in, the failure above was the credential and nothing else about this setup.
            manager.connect(hostProfile(), PASSWORD).use { session ->
                assertThat(session.isAuthenticated).isTrue()
            }
        } finally {
            manager.close()
        }
        }
    }

    /**
     * A runaway tree walk fails with a message instead of running the process out of memory.
     *
     * [SftpDirectoryService.listTree] holds the whole listing in memory and every entry in it is the
     * server's word. Depth is the bound this can prove against a real server — a genuine filesystem
     * cannot contain itself, but nesting past the ceiling produces the same unbounded descent that a
     * `readdir` loop would, and it is the same guard that stops it.
     *
     * What must not happen is the old behaviour: walk until the heap is gone and the app is killed,
     * with nothing shown to the user. The sync callers turn this exception into a snackbar.
     */
    @Test(timeout = 120_000)
    fun `listing a tree deeper than the ceiling fails with an explanation rather than exhausting memory`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = SshConnectionManager(context, SettingsRepository(context))
            val directory = SftpDirectoryService()
            try {
                val session = trustedConnect(manager, hostProfile(), PASSWORD)
                // Comfortably past MAX_TREE_DEPTH (64), and shallow enough to create quickly.
                val deep = (1..70).fold(root.resolve("deep")) { path, level -> path.resolve("l$level") }
                Files.createDirectories(deep)

                manager.withSftp(session) { sftp ->
                    val error = runCatching { directory.listTree(sftp, "deep") }.exceptionOrNull()

                    assertThat(error).isInstanceOf(java.io.IOException::class.java)
                    // The message is what the user is shown, so it has to say what happened.
                    assertThat(error!!.message).contains("levels")
                }

                // And a tree inside the ceiling still lists normally — the guard bounds the walk, it
                // does not disable it.
                manager.withSftp(session) { sftp ->
                    assertThat(directory.listTree(sftp, "sftp_test").keys).contains("subdir/nested.txt")
                }
            } finally {
                manager.close()
            }
        }
    }

    /**
     * A resumed upload writes the *right* bytes when the source stream will not seek.
     *
     * [SftpTransferManager.resumeUpload] positions the source with `InputStream.skip`, and `skip` is
     * allowed to return fewer bytes than asked for — "possibly 0" — without being at EOF. Every
     * resumed upload reads its source from `ContentResolver.openInputStream`, and a document that
     * lives behind a cloud provider rather than on local storage arrives as a pipe, which is exactly
     * the stream that cannot seek. Resuming a large file over a flaky network from cloud storage is
     * not an exotic case; it is the main reason a resume happens at all.
     *
     * The old loop gave up silently on a short skip and then wrote whatever the stream handed it at
     * the resumed offset, so the file on the server ended up with a hole and a block of duplicated
     * data — and the transfer was reported COMPLETE. This asserts the remote bytes, not the byte
     * count, because a length check passes on the corrupted result.
     */
    @Test(timeout = 120_000)
    fun `a resumed upload from a stream that will not skip still writes the original file`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = SshConnectionManager(context, SettingsRepository(context))
            val transfers = SftpTransferManager()
            try {
                val session = trustedConnect(manager, hostProfile(), PASSWORD)
                // Distinct per byte position, so a hole or a duplicated block is visible in the
                // comparison rather than hidden by repeated filler.
                val content = ByteArray(200 * 1024) { (it * 31 + 7).toByte() }
                val alreadySent = 150 * 1024
                val remote = "sftp_test/resumed-upload.bin"

                manager.withSftp(session) { sftp ->
                    // The state an interrupted upload leaves behind: a partial remote file.
                    sftp.write(remote).use { it.write(content, 0, alreadySent) }
                    assertThat(sftp.stat(remote).size).isEqualTo(alreadySent.toLong())

                    transfers.resumeUpload(
                        sftp = sftp,
                        source = UnskippableInputStream(content.inputStream()),
                        remotePath = remote,
                        existingBytes = alreadySent.toLong(),
                        totalBytes = content.size.toLong(),
                    )
                }

                assertSameBytes(content, root.resolve(remote).toFile().readBytes())
            } finally {
                manager.close()
            }
        }
    }

    /**
     * A resume whose source cannot reach the offset fails instead of reporting success.
     *
     * If the remote file is already longer than the source — a different file at that path, or a
     * truncated local document — there is no honest way to continue: the bytes the server is missing
     * do not exist locally. The old loop skipped to EOF, read -1 immediately, returned normally, and
     * the caller marked the transfer COMPLETE, leaving whatever was on the server as the "uploaded"
     * file. Failing puts it through the retry ladder and, eventually, in front of the user.
     */
    @Test(timeout = 120_000)
    fun `a resumed upload whose source is shorter than the remote file fails rather than claiming success`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = SshConnectionManager(context, SettingsRepository(context))
            val transfers = SftpTransferManager()
            try {
                val session = trustedConnect(manager, hostProfile(), PASSWORD)
                val remote = "sftp_test/short-source.bin"
                manager.withSftp(session) { sftp ->
                    sftp.write(remote).use { it.write(ByteArray(64 * 1024)) }

                    val error = runCatching {
                        transfers.resumeUpload(
                            sftp = sftp,
                            source = ByteArray(1_024).inputStream(),
                            remotePath = remote,
                            existingBytes = 64L * 1024,
                        )
                    }.exceptionOrNull()

                    assertThat(error).isNotNull()
                    assertThat(error).isInstanceOf(java.io.IOException::class.java)
                }
            } finally {
                manager.close()
            }
        }
    }

    /**
     * A resumed download appends from the offset it is given, leaving the bytes already on disk.
     *
     * The counterpart of the upload case, and the arithmetic that matters is the same: the remote
     * read starts at [existingBytes] while the local write continues where the file ends, so an
     * off-by-one in either direction shows up as a duplicated or missing block rather than as a
     * length mismatch.
     */
    @Test(timeout = 120_000)
    fun `a resumed download appends the remainder without duplicating what is already there`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = SshConnectionManager(context, SettingsRepository(context))
            val transfers = SftpTransferManager()
            try {
                val session = trustedConnect(manager, hostProfile(), PASSWORD)
                val content = ByteArray(200 * 1024) { (it * 17 + 3).toByte() }
                val remote = "sftp_test/resumed-download.bin"
                Files.write(root.resolve(remote), content)
                val alreadyHave = 120 * 1024

                val local = Files.createTempFile("resume-download", ".bin")
                Files.write(local, content.copyOfRange(0, alreadyHave))

                manager.withSftp(session) { sftp ->
                    java.io.FileOutputStream(local.toFile(), /* append = */ true).use { out ->
                        transfers.resumeDownload(
                            sftp = sftp,
                            remotePath = remote,
                            destination = out,
                            existingBytes = alreadyHave.toLong(),
                        )
                    }
                }

                assertSameBytes(content, local.toFile().readBytes())
            } finally {
                manager.close()
            }
        }
    }

    /**
     * `HostProfile.connectTimeoutSeconds` reaches Apache MINA, rather than being a field the UI
     * collects and the engine ignores.
     *
     * The engine used to `verify(15, SECONDS)` twice with the number written into the source, so the
     * only way to know the per-host setting is wired is to watch a connection give up on the host's
     * schedule instead of on that one. The server here is a bare `ServerSocket`: it completes the TCP
     * handshake and then says nothing at all, which is the shape of a wedged bastion or a port
     * forwarded to something that is not sshd. TCP connect succeeds, the version exchange never
     * does, and the attempt can only end on the timeout — the one condition that distinguishes a
     * five-second budget from a fifteen-second one.
     *
     * It also pins which budget covers that silence. MINA's connect future resolves when the *socket*
     * is up, so the wait for the version and key exchange belongs to nobody unless something claims
     * it. The connect budget claims it, not the authentication one: this server never asks for a
     * credential, and charging its silence to the time allowed for answering a password prompt would
     * report a number the user chose for a phase the attempt never reached. See `awaitHandshake`.
     */
    @Test(timeout = 120_000)
    fun aPerHostTimeoutIsHonouredInsteadOfTheOldHardCodedFifteenSeconds() {
        // The shortest interval the form and the importer are allowed to store.
        val shortTimeout = CONNECT_TIMEOUT_RANGE.first
        val silent = java.net.ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        // Accepted and then held open, deliberately unanswered. Letting the socket close instead
        // would produce an immediate EOF and the attempt would fail without reaching any timeout.
        val held = Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val acceptor = Thread {
            runCatching { while (!silent.isClosed) held += silent.accept() }
        }.apply { isDaemon = true; start() }
        try {
            runBlocking {
                val context = RuntimeEnvironment.getApplication()
                val manager = SshConnectionManager(context, SettingsRepository(context))
                try {
                    val profile = HostProfile(
                        name = "silent",
                        host = LOOPBACK,
                        username = USER,
                        port = silent.localPort,
                        authMethod = AuthMethod.PASSWORD,
                        connectTimeoutSeconds = shortTimeout,
                    )

                    val startedAt = System.nanoTime()
                    val error = runCatching { manager.connect(profile, PASSWORD) }.exceptionOrNull()
                    val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0

                    assertThat(error).isNotNull()
                    // The load-bearing assertion, and it is deliberately not a stopwatch reading:
                    // the failure names the budget it was given, so this reads the number the engine
                    // actually used rather than inferring it from wall clock. On a busy machine a
                    // 5-second wait can measure as 20; the message cannot drift.
                    assertThat(error!!.toString())
                        .contains("timeout: ${shortTimeout * 1_000} msec")
                    // And the two numbers it must not be: the budget that was once hard-coded, and
                    // the default authentication budget, which is what an unclaimed handshake falls
                    // through to and would report for a server that never asked for anything.
                    assertThat(error.toString()).doesNotContain("15000 msec")
                    assertThat(error.toString())
                        .doesNotContain("${DEFAULT_AUTH_TIMEOUT_SECONDS * 1_000} msec")
                    // Only a lower bound, because load can lengthen the wait but never shorten it.
                    // It rules out the one thing that would fake a pass: an instant refusal, which
                    // is what an unbound port would give and would satisfy the message check never.
                    assertThat(elapsedSeconds).isAtLeast(shortTimeout - 1.0)
                } finally {
                    manager.close()
                }
            }
        } finally {
            held.forEach { runCatching { it.close() } }
            runCatching { silent.close() }
            acceptor.interrupt()
        }
    }
}

/**
 * A source that refuses to seek, the way a pipe-backed `ContentResolver` stream does.
 *
 * `skip` returning 0 without being at EOF is permitted by [InputStream] and is what a document
 * served from a cloud provider actually does, since there is no file descriptor to `lseek`. Reads
 * still work normally — the stream is usable, just not positionable.
 */
private class UnskippableInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun skip(n: Long): Long = 0
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

/**
 * A server-side command that accepts its channel and then never speaks and never exits: the
 * client's `executeRemoteCommand` blocks reading a reply that is never coming. This is the shape
 * of a server wedged under load - and of a transport that died without the error reaching this
 * side - which is exactly what [SshConnectionManager.runCommand]'s bound exists for.
 */
private class WedgedCommand : Command {
    override fun setInputStream(input: InputStream) = Unit
    override fun setOutputStream(output: OutputStream) = Unit
    override fun setErrorStream(error: OutputStream) = Unit
    override fun setExitCallback(callback: ExitCallback) = Unit
    override fun start(channel: ChannelSession, env: Environment) = Unit
    override fun destroy(channel: ChannelSession) = Unit
}

/**
 * Minimal server-side command: for an exec request it replies with the command
 * text and exits; for an interactive shell it prints a "$ " prompt and echoes
 * each received line ("echo: <line>"), which is enough to exercise the client's
 * terminal channel end to end without needing a real PTY on the test host.
 */
private class EchoCommand(private val initialReply: String? = null) : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var exit: ExitCallback? = null

    override fun setInputStream(input: InputStream) {
        this.input = input
    }

    override fun setOutputStream(output: OutputStream) {
        this.output = output
    }

    override fun setErrorStream(error: OutputStream) = Unit

    override fun setExitCallback(callback: ExitCallback) {
        this.exit = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        val out = output ?: return
        val inn = input ?: return
        if (initialReply != null) {
            out.write((initialReply + "\n").toByteArray())
            out.flush()
            exit?.onExit(0)
            return
        }
        out.write("$ ".toByteArray())
        out.flush()
        Thread {
            try {
                inn.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        out.write(("echo: $line\n$ ").toByteArray())
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Channel closed: nothing to do.
            }
        }.start()
    }

    override fun destroy(channel: ChannelSession) = Unit
}
