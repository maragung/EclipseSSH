package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every way a connection can end, driven through the app the way a tap on Connect drives it.
 *
 * The success path was already covered by `TerminalSessionLifecycleRobolectricTest`; what was not
 * covered anywhere is the shape of the *failures*, and those are where a session manager goes wrong.
 * A refused socket, a server that accepts TCP and never speaks SSH, a rejected password, a host edited
 * out from under a live session, a host deleted while its shell is open, and the same host connected
 * and disconnected over and over — each of those has to end with a tab in a settled state, a sentence
 * a user can act on, no secret in that sentence, and nothing left running.
 *
 * Three local endpoints, all bound to loopback inside this JVM and all gone with the process:
 * [server] is a working SSH server; [silentPort] accepts TCP and sends nothing, which is what a
 * firewalled or overloaded host looks like; [deadPort] is bound and immediately closed, so nothing is
 * listening on it. There is no external account anywhere in this suite — the credentials below exist
 * only here.
 *
 * `authAttempts` is the assertion that could not be made any other way: a rejected password must be
 * offered *once*. The retry loop used to be unconditional, so a typo went to the server three times,
 * which is how a real account earns a lockout or a `fail2ban` ban for a slip of the finger.
 *
 * Everything below runs against the real Hilt graph, the real Room file and the real credential store,
 * with one piece of the platform stood in for: `AndroidKeyStore` does not exist on the JVM, so
 * [StandInAndroidKeyStore] gives the vault's key request somewhere to land. See that class for what is
 * real about it and what is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ConnectionMatrixRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetServerCounters() {
        authAttempts.set(0)
        shellsStarted.set(0)
    }

    /**
     * Ends every session this test opened, before the next one starts.
     *
     * A fresh application per test does not close a socket: the session, its channels and the shell the
     * server started for it all stay resident, and a live thread is a GC root, so a suite that opens one
     * per test finishes holding all of them at once on a JVM confined to two cores. Handshakes and
     * database writes that take milliseconds in isolation start missing their deadlines under that. See
     * `TerminalSessionLifecycleRobolectricTest.endEverySession`.
     */
    @After
    fun endEverySession() {
        runCatching { compose.runOnUiThread { viewModel().disconnectAll() } }
    }

    // ---------------------------------------------------------------- the happy path

    /** Connect with the password typed into the prompt: the baseline the failures are measured against. */
    @Test
    fun aCorrectPasswordConnectsAndReportsNoError() {
        val hostId = connect(port = serverPort, password = PASSWORD)

        val tab = checkNotNull(tabFor(hostId))
        assertThat(tab.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tab.lastError).isNull()
        awaitCount(authAttempts, 1, "passwords offered to the server")
        awaitCount(shellsStarted, 1, "shells the server started")
    }

    /**
     * Connect with nothing typed, on a host whose password is in the vault — the one-tap connect.
     *
     * Also the proof that saving a credential works end to end: it is written through
     * [MainViewModel.saveHost], read back by the connect path, and accepted by a real server. The
     * assertion that it is *stored* is made on metadata only, because metadata is the widest view of a
     * credential the UI state carries.
     */
    @Test
    fun aSavedPasswordConnectsWithoutBeingAskedForAgain() {
        val host = saveHostWithPassword()

        connectSaved(host.id, password = null)

        assertThat(tabFor(host.id)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        awaitCount(authAttempts, 1, "passwords offered to the server")
    }

    /**
     * The credential the UI is allowed to see, and what it is allowed to see about it.
     *
     * `hasPassword` is the whole of it: the form renders "Saved" from this and nothing else, and a
     * `StoredCredentials` that carried the secret would be a secret in a UI state object — snapshotted
     * into a saved instance state, printed by any `toString`, and visible to anything that can read the
     * view model. The assertion below is that the metadata is true *and* that the value itself is not
     * anywhere in it.
     */
    @Test
    fun aSavedPasswordIsVisibleToTheFormAsMetadataAndNeverAsItsValue() {
        val host = saveHostWithPassword()

        val stored = checkNotNull(viewModel().uiState.value.savedCredentials[host.id])
        assertThat(stored.hasPassword).isTrue()
        assertThat(stored.hasKey).isFalse()
        assertThat(stored.isEmpty).isFalse()
        assertWithMessage("the password reached a UI state object")
            .that(stored.toString()).doesNotContain(PASSWORD)
    }

    // ---------------------------------------------------------------- the failures

    /**
     * A rejected password: settled, explained, offered exactly once, and with the password itself
     * nowhere in what the user is shown.
     */
    @Test
    fun aWrongPasswordFailsOnceWithAReadableErrorAndNoLeak() {
        val hostId = connectExpectingFailure(port = serverPort, password = "not-the-password")

        val tab = checkNotNull(tabFor(hostId))
        val error = checkNotNull(tab.lastError) { "DISCONNECTED with nothing to show the user" }
        assertThat(error).isNotEmpty()
        assertThat(error).doesNotContain("not-the-password")
        assertThat(error).doesNotContain(PASSWORD)
        // The account must not be hammered for a typo: the server saw the credential once.
        awaitCount(authAttempts, 1, "passwords offered to the server")
        // Nothing was left half-open by the failure.
        assertWithMessage("a shell was started for a session that never authenticated")
            .that(shellsStarted.get()).isEqualTo(0)
    }

    /** Nothing listening: reported as a connection problem, not as a crash and not as a hang. */
    @Test
    fun anUnreachableHostSettlesOnDisconnectedWithAReadableError() {
        val hostId = connectExpectingFailure(port = deadPort, password = PASSWORD)

        val error = checkNotNull(tabFor(hostId)?.lastError)
        assertThat(error).isNotEmpty()
        assertThat(error).doesNotContain(PASSWORD)
        // A real sentence rather than a class name with no message behind it.
        assertThat(error).isNotEqualTo("Connection failed")
    }

    /**
     * A host that accepts the TCP connection and then says nothing: the profile's timeout has to end
     * it, or the tab spins forever on a session that will never exist.
     *
     * The timeout is set to the smallest the form accepts so the test costs the least real time it can
     * — three attempts of it, which is also the proof that the *per-host* value is what the engine
     * uses rather than a hard-coded default.
     */
    @Test
    fun aServerThatNeverSpeaksSshTimesOutInsteadOfHangingForever() {
        val hostId = connectExpectingFailure(
            port = silentPort,
            password = PASSWORD,
            timeoutSeconds = CONNECT_TIMEOUT_RANGE.first,
            timeoutMs = 120_000,
        )

        val error = checkNotNull(tabFor(hostId)?.lastError)
        assertThat(error).isNotEmpty()
        assertThat(error).doesNotContain(PASSWORD)
    }

    /**
     * The first connection to an unknown server: asked about before the password is anywhere near the
     * wire, and connected once the answer is yes.
     *
     * This is the one test in the class that does not pin the fingerprint in advance, which makes it
     * the trust-on-first-use path as a user meets it. The assertion that matters is the *order*: at the
     * moment the challenge is on screen the server has been offered nothing, because host key
     * verification happens during key exchange and a credential sent before it would have gone to
     * whoever answered the socket. Accepting retries the connection itself, so one accept is the whole
     * of what the user does.
     */
    @Test
    fun anUnknownHostKeyIsAskedAboutBeforeAnyPasswordIsSent() {
        val host = saveHost(port = serverPort, pinFingerprint = false)
        val viewModel = viewModel()
        val saved = viewModel.uiState.value.hosts.first { it.id == host.id }

        compose.runOnUiThread { viewModel.connect(saved, password = PASSWORD) }
        pumpUntil(describe = { "the unknown host key was never challenged: " + diagnose(host.id) }) {
            viewModel.uiState.value.hostKeyChallenge != null
        }

        val challenge = checkNotNull(viewModel.uiState.value.hostKeyChallenge)
        assertThat(challenge.host).isEqualTo(LOOPBACK)
        assertThat(challenge.port).isEqualTo(serverPort)
        assertThat(challenge.fingerprint).isEqualTo(serverFingerprint)
        // Not a key that changed under us — this profile has never seen one.
        assertThat(challenge.changed).isFalse()
        assertWithMessage("the password was offered before the key was trusted")
            .that(authAttempts.get()).isEqualTo(0)

        compose.runOnUiThread { viewModel.acceptHostKey() }
        pumpUntil(describe = { "accepting the key did not connect: " + diagnose(host.id) }) {
            tabFor(host.id)?.state == SessionConnectionState.CONNECTED
        }
        assertThat(viewModel.uiState.value.hostKeyChallenge).isNull()
        // Trusted now, and recorded as such, so the next connection is not asked again.
        assertThat(viewModel.uiState.value.knownHosts).containsEntry("$LOOPBACK:$serverPort", serverFingerprint)
        awaitCount(authAttempts, 1, "passwords offered to the server")
    }

    // ---------------------------------------------------------------- lifecycle

    /** Closing a session takes the tab, the transcript and the frame with it. */
    @Test
    fun closingASessionClearsEverythingItOwned() {
        val hostId = connect(port = serverPort, password = PASSWORD)
        val viewModel = viewModel()

        compose.runOnUiThread { viewModel.closeTab(checkNotNull(tabFor(hostId))) }

        pumpUntil(describe = { "the tab never closed" }) { tabFor(hostId) == null }
        assertThat(viewModel.uiState.value.tabs).isEmpty()
        assertThat(viewModel.uiState.value.terminalOutput).doesNotContainKey(hostId)
        assertThat(viewModel.frames.value).doesNotContainKey(hostId)
    }

    /**
     * Closing a tab from a copy that is one update out of date still closes the session.
     *
     * This is the regression test for a bug that made a tap on a tab's close button do nothing. The
     * list was filtered with `tabs.value - tab`, which compares every field, and the only copy any
     * caller can have came from [MainViewModel.uiState] — a conflating `StateFlow` that is allowed to
     * be one update behind. The window was real and small, which is the worst kind: a session settles
     * its SFTP state a moment after it connects, and a close that landed in between tore down the
     * socket, the pty and the collector while leaving the tab on screen, pointing at nothing.
     *
     * The stale copy is constructed rather than raced for, because a race reproduces the bug only
     * sometimes and the property under test is not timing — it is that a tab is identified by its
     * session, not by a snapshot of its contents. [SftpSessionState] is the field the real case
     * differed in.
     */
    @Test
    fun closingATabFromAStaleCopyStillClosesTheSession() {
        val hostId = connect(port = serverPort, password = PASSWORD)
        val viewModel = viewModel()
        val live = checkNotNull(tabFor(hostId))
        val stale = live.copy(sftpState = SftpSessionState.CONNECTING, sftpError = null)
        assertWithMessage("the stale copy has to differ, or this proves nothing").that(stale).isNotEqualTo(live)

        compose.runOnUiThread { viewModel.closeTab(stale) }

        pumpUntil(describe = { "closing from a stale copy did nothing: " + diagnose(hostId) }) {
            tabFor(hostId) == null
        }
        assertThat(viewModel.uiState.value.tabs).isEmpty()
        assertThat(viewModel.uiState.value.terminalOutput).doesNotContainKey(hostId)
        assertThat(viewModel.frames.value).doesNotContainKey(hostId)
    }

    /**
     * Connect, disconnect, repeat — the cycle a user on a flaky train does dozens of times.
     *
     * What this is looking for is accumulation. Each pass opens a real session, a real pty and a real
     * collector, and every one of them has to be gone before the next begins: a leaked collector pins
     * its terminal buffer for the lifetime of the view model, and a session that is not closed leaves
     * a socket and two MINA threads behind. The server-side shell count is the honest measure — one
     * per cycle, no more — because it counts what actually reached the far end rather than what the
     * app believes it closed.
     */
    @Test
    fun repeatedConnectAndDisconnectCyclesLeaveNothingBehind() {
        val host = saveHostWithPassword()
        val viewModel = viewModel()

        repeat(CYCLES) { cycle ->
            connectSaved(host.id, password = null)
            assertWithMessage("cycle $cycle").that(viewModel.uiState.value.tabs).hasSize(1)
            compose.runOnUiThread { viewModel.closeTab(checkNotNull(tabFor(host.id))) }
            pumpUntil(describe = { "cycle $cycle never closed its tab" }) { tabFor(host.id) == null }
            assertWithMessage("cycle $cycle left state behind")
                .that(viewModel.uiState.value.terminalOutput).doesNotContainKey(host.id)
        }

        assertThat(viewModel.uiState.value.tabs).isEmpty()
        assertThat(viewModel.frames.value).doesNotContainKey(host.id)
        // One shell per cycle: no attempt was silently retried and no session was left open.
        awaitCount(shellsStarted, CYCLES, "shells the server started")
        awaitCount(authAttempts, CYCLES, "passwords offered to the server")
    }

    /**
     * Editing a host decides where its *next* connection goes.
     *
     * Both directions on purpose. Pointing a working profile at a dead port has to start failing, and
     * pointing it back has to start working again — a view model that cached the profile from the
     * first connect would pass either half alone.
     */
    @Test
    fun editingAHostChangesWhereTheNextConnectionGoes() {
        val hostId = connect(port = serverPort, password = PASSWORD)
        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.closeTab(checkNotNull(tabFor(hostId))) }
        pumpUntil(describe = { "the first session never closed" }) { tabFor(hostId) == null }

        // Edited to a port nothing is listening on, the way the Edit dialog saves.
        editHost(hostId) { it.copy(port = deadPort) }
        connectSaved(hostId, password = PASSWORD, expect = SessionConnectionState.DISCONNECTED, timeoutMs = SSH_TIMEOUT_MS)
        assertThat(tabFor(hostId)?.lastError).isNotNull()

        // And back again: the same profile, edited once more, connects.
        compose.runOnUiThread { viewModel.closeTab(checkNotNull(tabFor(hostId))) }
        pumpUntil(describe = { "the failed tab never closed" }) { tabFor(hostId) == null }
        editHost(hostId) { it.copy(port = serverPort) }
        connectSaved(hostId, password = PASSWORD)
        assertThat(tabFor(hostId)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(tabFor(hostId)?.lastError).isNull()
    }

    /**
     * Deleting a host closes its live session and takes its saved credentials with it.
     *
     * The credential half is the one that matters and the one that is easy to get wrong: once the row
     * is gone there is nothing left to tie a stored password to, so a secret left behind would belong
     * to a host id no screen in the app can name — undeletable from the UI and invisible in it.
     */
    @Test
    fun deletingAHostClosesItsSessionAndForgetsItsCredentials() {
        val host = saveHostWithPassword()
        val viewModel = viewModel()
        connectSaved(host.id, password = null)

        val live = viewModel.uiState.value.hosts.first { it.id == host.id }
        compose.runOnUiThread { viewModel.deleteHost(live) }

        pumpUntil(describe = { "the host was never deleted: " + diagnose(host.id) }) {
            viewModel.uiState.value.hosts.none { it.id == host.id }
        }
        // The session went with it rather than being left pointing at a profile that no longer exists.
        pumpUntil(describe = { "the session outlived its host" }) { tabFor(host.id) == null }
        pumpUntil(describe = { "the saved credential outlived its host" }) {
            viewModel.uiState.value.savedCredentials[host.id]?.hasPassword != true
        }
        assertThat(viewModel.frames.value).doesNotContainKey(host.id)
    }

    /**
     * A profile written by one launch is there for the next one, with every field intact.
     *
     * Restart is simulated the only way a single-process test can: the activity is recreated, which
     * drops the view model and everything it held in memory, and the host list is read again from
     * Room. A field that only lived in the form or in a `remember` would not survive that.
     */
    @Test
    fun aSavedHostAndItsSettingsSurviveTheActivityBeingRecreated() {
        val host = saveHost(port = serverPort, autoLoginSftp = false, group = "Restart", tags = listOf("qa", "local"))

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        val viewModel = viewModel()
        pumpUntil(describe = { "the host list never came back after recreation" }) {
            viewModel.uiState.value.hosts.any { it.id == host.id }
        }
        val restored = viewModel.uiState.value.hosts.first { it.id == host.id }
        assertThat(restored.host).isEqualTo(LOOPBACK)
        assertThat(restored.username).isEqualTo(USER)
        assertThat(restored.port).isEqualTo(serverPort)
        assertThat(restored.group).isEqualTo("Restart")
        assertThat(restored.tags).containsExactly("qa", "local")
        assertThat(restored.connectTimeoutSeconds).isEqualTo(60)
        // The new column included: a per-host switch that reset on restart would be worse than none.
        assertThat(restored.autoLoginSftp).isFalse()
    }

    /**
     * Two sessions to the same server at once, each with its own tab and its own shell.
     *
     * Multi-account is the feature, and the failure it used to have was in the bookkeeping: state was
     * keyed by host id, so two profiles pointing at the same machine had to stay separate all the way
     * down. Closing one must not touch the other.
     */
    @Test
    fun twoHostsConnectSideBySideAndClosingOneLeavesTheOther() {
        val first = connect(port = serverPort, password = PASSWORD)
        val second = connect(port = serverPort, password = PASSWORD)
        val viewModel = viewModel()

        assertThat(viewModel.uiState.value.tabs.map(SessionTab::hostId)).containsExactly(first, second).inOrder()
        awaitCount(shellsStarted, 2, "shells the server started")

        compose.runOnUiThread { viewModel.closeTab(checkNotNull(tabFor(first))) }
        pumpUntil(describe = { "the first tab never closed" }) { tabFor(first) == null }

        assertThat(tabFor(second)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        assertThat(viewModel.uiState.value.tabs.map(SessionTab::hostId)).containsExactly(second)
    }

    /** Disconnect all, from the sessions list: every tab goes, nothing is left connected. */
    @Test
    fun disconnectAllClosesEverySession() {
        connect(port = serverPort, password = PASSWORD)
        connect(port = serverPort, password = PASSWORD)
        val viewModel = viewModel()

        compose.runOnUiThread { viewModel.disconnectAll() }

        pumpUntil(describe = { "sessions survived disconnectAll: " + viewModel.uiState.value.tabs }) {
            viewModel.uiState.value.tabs.isEmpty()
        }
        assertThat(viewModel.frames.value).isEmpty()
    }

    /**
     * A double tap on Connect opens one session, not two.
     *
     * The guard is in the view model — a second attempt cancels the first — and without it the loser
     * of the race left a fully authenticated session with no tab pointing at it: a socket, a pty and
     * two MINA threads that nothing in the app could ever close.
     */
    @Test
    fun connectingTwiceInARowLeavesOneSession() {
        val host = saveHost(port = serverPort)
        val viewModel = viewModel()
        val saved = viewModel.uiState.value.hosts.first { it.id == host.id }

        compose.runOnUiThread {
            viewModel.connect(saved, password = PASSWORD)
            viewModel.connect(saved, password = PASSWORD)
        }
        acceptAnyHostKey(host.id)

        assertThat(viewModel.uiState.value.tabs.filter { it.hostId == host.id }).hasSize(1)
        // Only one shell reached the server, so the cancelled attempt really was cancelled.
        awaitCount(shellsStarted, 1, "shells the server started")
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun tabFor(hostId: String): SessionTab? =
        viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    /** Writes a profile through the view model and waits for Room to hand it back. */
    /**
     * Writes a profile through the view model and waits for Room to hand it back.
     *
     * [pinFingerprint] is on by default and is what makes the rest of the class deterministic. The
     * server generates a fresh host key per run, so every profile would otherwise meet a
     * trust-on-first-use challenge whose *timing* depends on which test happened to run first — and a
     * test measuring a failure would settle on DISCONNECTED for the wrong reason, having never reached
     * authentication at all. Pinning is the app's own feature for exactly this: a fingerprint saved on
     * the profile is written to known-hosts by [MainViewModel.saveHost]. The path through the challenge
     * is not lost by pinning here; it is what
     * [anUnknownHostKeyIsAskedAboutBeforeAnyPasswordIsSent] tests on its own.
     */
    private fun saveHost(
        port: Int,
        timeoutSeconds: Int = 60,
        autoLoginSftp: Boolean = false,
        group: String = "Personal",
        tags: List<String> = emptyList(),
        credentials: HostCredentialUpdate = HostCredentialUpdate(),
        pinFingerprint: Boolean = true,
    ): HostProfile {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "matrix-" + nextHostId++,
            name = HOST_NAME,
            host = LOOPBACK,
            username = USER,
            port = port,
            authMethod = AuthMethod.PASSWORD,
            group = group,
            tags = tags,
            connectTimeoutSeconds = timeoutSeconds,
            // Off unless a test says otherwise: this class is about the SSH half, and an SFTP login on
            // every connect would put a second channel in the way of what is being measured.
            autoLoginSftp = autoLoginSftp,
            fingerprint = serverFingerprint.takeIf { pinFingerprint && port == serverPort },
        )
        compose.runOnUiThread { viewModel.saveHost(profile, credentials) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        return profile
    }

    /**
     * Saves a profile with its password in the vault and waits for the store to say so.
     *
     * The wait is on [MainUiState.savedCredentials] rather than on the call returning, because the
     * write is a suspending round trip through `DataStore` and the connect path reads it back from
     * there; connecting before it lands would test nothing but a race. The metadata arriving is also
     * the only observable proof that the encryption succeeded — [dev.eclipse.ssh.security.SecureVault]
     * reports a failed write by leaving the flag false and putting a sentence on the status line.
     */
    private fun saveHostWithPassword(): HostProfile {
        val host = saveHost(
            port = serverPort,
            credentials = HostCredentialUpdate(password = SecretEdit.Replace(PASSWORD)),
        )
        pumpUntil(describe = { "the credential was never recorded as saved: " + diagnose(host.id) }) {
            viewModel().uiState.value.savedCredentials[host.id]?.hasPassword == true
        }
        return host
    }

    private fun editHost(hostId: String, edit: (HostProfile) -> HostProfile) {
        val viewModel = viewModel()
        val edited = edit(viewModel.uiState.value.hosts.first { it.id == hostId })
        compose.runOnUiThread { viewModel.saveHost(edited) }
        pumpUntil(describe = { "the edit never reached the host list" }) {
            viewModel.uiState.value.hosts.first { it.id == hostId } == edited
        }
    }

    /** Saves a profile and connects it, expecting to arrive CONNECTED. */
    private fun connect(port: Int, password: String?, timeoutSeconds: Int = 60): String {
        val host = saveHost(port = port, timeoutSeconds = timeoutSeconds)
        connectSaved(host.id, password)
        return host.id
    }

    private fun connectExpectingFailure(
        port: Int,
        password: String?,
        timeoutSeconds: Int = 60,
        timeoutMs: Long = SSH_TIMEOUT_MS,
    ): String {
        val host = saveHost(port = port, timeoutSeconds = timeoutSeconds)
        connectSaved(host.id, password, expect = SessionConnectionState.DISCONNECTED, timeoutMs = timeoutMs)
        return host.id
    }

    /**
     * Connects an already-saved host and waits for [expect], accepting the host key on the way.
     *
     * The first connection to this server is genuinely a trust-on-first-use prompt — the key is
     * generated fresh per run — so accepting it inside the wait is the real first-connection path
     * rather than a shortcut around it. `acceptHostKey` retries the connection itself.
     */
    private fun connectSaved(
        hostId: String,
        password: String?,
        expect: SessionConnectionState = SessionConnectionState.CONNECTED,
        timeoutMs: Long = SSH_TIMEOUT_MS,
    ) {
        val viewModel = viewModel()
        val saved = viewModel.uiState.value.hosts.first { it.id == hostId }
        compose.runOnUiThread { viewModel.connect(saved, password = password) }
        var trusted = false
        pumpUntil(timeoutMs, describe = { "the session never reached $expect: " + diagnose(hostId) }) {
            if (!trusted && viewModel.uiState.value.hostKeyChallenge != null) {
                trusted = true
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(hostId)?.state == expect
        }
    }

    /** Waits for CONNECTED on an already-started attempt, trusting the key if it is asked about. */
    private fun acceptAnyHostKey(hostId: String) {
        val viewModel = viewModel()
        var trusted = false
        pumpUntil(describe = { "the session never connected: " + diagnose(hostId) }) {
            if (!trusted && viewModel.uiState.value.hostKeyChallenge != null) {
                trusted = true
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(hostId)?.state == SessionConnectionState.CONNECTED
        }
    }

    /**
     * Waits for a server-side counter to reach [expected], then holds still to prove it stops there.
     *
     * Reading these the instant the app says CONNECTED was wrong in both directions. A client considers
     * a shell channel open when the server confirms the *channel*; the `shell` request that makes
     * [CountingShell.start] run is processed after that, on another thread, so a count read too early is
     * low for a session that is perfectly fine. And a count that is merely high enough says nothing
     * about a duplicate arriving a moment later — which is the failure
     * [connectingTwiceInARowLeavesOneSession] exists to catch. So: wait for it to arrive, then wait a
     * little longer and insist it is still exactly that.
     *
     * [settle] sleeps for real rather than advancing the looper's clock, because what it is waiting for
     * is not on the looper: it is Apache MINA's threads and a socket on loopback.
     */
    private fun awaitCount(counter: AtomicInteger, expected: Int, what: String) {
        pumpUntil(describe = { "only ${counter.get()} of $expected $what: " + diagnose("") }) {
            counter.get() >= expected
        }
        settle()
        assertWithMessage(what).that(counter.get()).isEqualTo(expected)
    }

    private fun settle(rounds: Int = SETTLE_ROUNDS) {
        repeat(rounds) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(SETTLE_PAUSE_MS)
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`: when composition throws, that reports only "Condition still not
     * satisfied" and loses the real exception. `sendApplyNotifications` publishes state written
     * outside a frame — `connect` writes its tab from a plain function call — which is what invalidates
     * the recomposer. `idleFor` rather than `idle` because the retry backoff in `connect` is a real
     * `delay`, and `idle()` leaves the looper's virtual clock where it was so it never comes due.
     */
    private fun pumpUntil(timeoutMs: Long = SSH_TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    private fun diagnose(hostId: String): String {
        val viewModel = viewModel()
        return buildString {
            append("tabs=").append(viewModel.uiState.value.tabs)
            append(" status=").append(viewModel.statusMessage.value)
            append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
            // Metadata only, and that is all it can be: StoredCredentials carries no secret.
            append(" saved=").append(viewModel.uiState.value.savedCredentials)
            append(" authAttempts=").append(authAttempts.get())
            append(" shellsStarted=").append(shellsStarted.get())
            append(" ports=").append(listOf(serverPort, silentPort, deadPort))
            append(" hostId=").append(hostId)
        }
    }

    private companion object {
        /**
         * How long a wait behind the wire is given.
         *
         * Longer than a state wait needs, because this suite shares two cores with a real SSH server, a
         * real Room database and a real DataStore. A harness deadline shorter than the app's own connect
         * budget can only report the harness running out of patience, never what the app did - and
         * nothing asserted here is relaxed by the extra time, since every wait ends the moment its
         * condition holds.
         */
        const val SSH_TIMEOUT_MS = 90_000L

        const val LOOPBACK = "127.0.0.1"

        /** Local test credentials only: this server lives and dies inside this JVM. */
        const val USER = "testuser"
        const val PASSWORD = "testpass123"
        const val HOST_NAME = "matrix"

        /** How many connect/disconnect rounds the accumulation test does. */
        const val CYCLES = 4

        /** Frames of quiet after a counter arrives, to catch a duplicate that lands just behind it. */
        const val SETTLE_ROUNDS = 20
        const val SETTLE_PAUSE_MS = 10L

        var serverPort = 0

        /**
         * The server's host key as the app fingerprints it, for pinning and for the challenge test.
         *
         * Computed the same way [dev.eclipse.ssh.ssh.SshConnectionManager] does — SHA-256 over the
         * encoded public key, base64 without padding — so the challenge test can assert on the exact
         * string the user would be shown rather than merely that some string appeared.
         */
        var serverFingerprint = ""

        /** Accepts the TCP connection and never sends an SSH version banner. */
        var silentPort = 0

        /** Bound then closed, so nothing is listening: a refused connection. */
        var deadPort = 0

        /** How many times the server was offered a password, across every session. */
        val authAttempts = AtomicInteger(0)

        /** How many shells the server actually started, which is what "connected" means on the wire. */
        val shellsStarted = AtomicInteger(0)

        private var nextHostId = 0
        private lateinit var server: SshServer
        private lateinit var silent: ServerSocket
        private var silentAcceptor: Thread? = null

        @JvmStatic
        @BeforeClass
        fun startEndpoints() {
            // Before the first activity: the vault caches its key on first use, and the credential
            // store's first write is what asks for it.
            StandInAndroidKeyStore.install()

            val root = Files.createTempDirectory("eclipse-connection-matrix")
            server = SshServer.setUpDefaultServer().apply {
                // Port 0 because Gradle runs the debug and release unit-test tasks in separate JVMs
                // and they overlap; the loser of a fixed bind runs its whole suite against a server
                // that never started.
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("matrix-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    authAttempts.incrementAndGet()
                    user == USER && password == PASSWORD
                }
                // Password only, which is what makes `authAttempts` an exact count of "how many times
                // was the credential offered". With the default factory list the server also advertises
                // keyboard-interactive, whose default authenticator delegates to this same callback, so
                // a client carrying a password tried it there first and again as `password` — two
                // increments for one authentication, and no way to tell that apart from a retry. Plenty
                // of real servers are configured exactly this way (`KbdInteractiveAuthentication no`).
                userAuthFactories = listOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
                subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                shellFactory = ShellFactory { CountingShell() }
                start()
            }
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
            serverFingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(server.keyPairProvider.loadKeys(null).first().public.encoded),
            )

            // Accepts and holds the socket open without writing: the client waits for a version
            // string that never comes, which is what the profile's timeout is for.
            silent = ServerSocket(0, 8, java.net.InetAddress.getByName(LOOPBACK))
            silentPort = silent.localPort
            silentAcceptor = Thread {
                val held = mutableListOf<java.net.Socket>()
                runCatching { while (true) held += silent.accept() }
                held.forEach { runCatching { it.close() } }
            }.apply { isDaemon = true; start() }

            // Bound only to learn a port nothing else will take, then closed.
            deadPort = ServerSocket(0, 1, java.net.InetAddress.getByName(LOOPBACK)).use { it.localPort }
        }

        @JvmStatic
        @AfterClass
        fun stopEndpoints() {
            runCatching { server.stop(true) }
            runCatching { silent.close() }
            silentAcceptor?.interrupt()
            // Puts the JVM back as it was: Gradle runs many test classes in one, and a provider left
            // registered would quietly change how the next one behaves.
            StandInAndroidKeyStore.uninstall()
        }
    }

    /**
     * A shell that counts itself, prints a prompt and echoes.
     *
     * The count is the point: "the tab says CONNECTED" is the app's opinion, and the number of shells
     * the server actually started is the fact. They disagree exactly when a session leaks.
     */
    private class CountingShell : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null
        private var worker: Thread? = null

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { this.exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            shellsStarted.incrementAndGet()
            val source = input ?: return
            val sink = output ?: return
            worker = Thread {
                runCatching {
                    sink.write("eclipse-matrix-shell\r\n$ ".toByteArray())
                    sink.flush()
                    val line = StringBuilder()
                    while (true) {
                        val byte = source.read()
                        if (byte < 0) break
                        if (byte == '\n'.code || byte == '\r'.code) {
                            sink.write("echo: $line\r\n$ ".toByteArray())
                            sink.flush()
                            line.setLength(0)
                        } else {
                            line.append(byte.toChar())
                        }
                    }
                }
                exit?.onExit(0)
            }.apply { isDaemon = true; start() }
        }

        override fun destroy(channel: ChannelSession) {
            worker?.interrupt()
            worker = null
        }
    }
}
