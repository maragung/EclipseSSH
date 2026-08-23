package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.terminal.TerminalKey
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app against a real OpenSSH server, held open long enough for the keep-alive to matter.
 *
 * Every other test in this suite talks to Apache MINA's own server, which is the same library the
 * client half is built from: both ends agree about extension negotiation, about what a global request
 * means, and about which messages may arrive unsolicited, because both ends are the same code. A real
 * `sshd` agrees about none of that for free — it sends `ext-info` and `hostkeys-00@openssh.com`
 * unasked, answers an unknown global request with `SSH_MSG_REQUEST_FAILURE` rather than success, runs
 * a login shell that prints `/etc/motd` through a pty, and offers `sftp-server` as a subsystem
 * process. A session that comes up against MINA and dies against OpenSSH is a bug the rest of this
 * suite cannot see, and "connects, prints the motd, then says Reconnecting" is exactly what that class
 * of bug looks like from the outside.
 *
 * The wait is the point of the test rather than an accident of it. A transport that fails on the
 * *third unanswered heartbeat* is alive for three whole intervals first, so a session sampled the
 * moment it connects passes while a session anybody actually uses drops a minute and a half later.
 * [KEEP_ALIVE_SECONDS] compresses that window without weakening it: the same three-strikes rule, the
 * same `keepalive@openssh.com` request, the same reply handling, ten times sooner. What is asserted is
 * that the tab is still CONNECTED after several heartbeats have had to be sent, answered and counted.
 *
 * Skipped, not failed, when the sandbox is not running: it needs a server this JVM cannot start on its
 * own. `tools/local-sshd.sh start` builds one — an isolated `sshd` on the loopback with its own host
 * key, its own `authorized_keys` and a throwaway key pair under `.tmp-build/sshd-test`, sharing
 * nothing with the machine's real SSH configuration and reachable from nowhere else. CI runs it before
 * the unit tests; a developer without it loses this one test and no other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RealOpenSshInteropRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * A real login, a real pty, real SFTP, and the session still up several heartbeats later.
     *
     * One test rather than four because each stage is a precondition of the next and the setup is a
     * live server: splitting them would dial four sessions to assert what one session can show, and
     * the assertion that matters — that none of the earlier stages *ends* the session — is only
     * observable by holding one open.
     */
    @Test
    fun aRealOpenSshSessionSurvivesTheMotdTheKeyboardAndItsHeartbeats() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val viewModel = viewModel()
        val profile = HostProfile(
            id = "real-openssh",
            name = "real-openssh",
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            keepAliveSeconds = KEEP_ALIVE_SECONDS,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        val key = File(sandbox, "client_ed25519").readBytes()
        compose.runOnUiThread { viewModel.connect(saved, keyBytes = key) }

        // The server's key has never been seen before, so the first attempt raises the challenge the
        // dialog raises; accepting it is what the dialog's button does.
        pumpUntil(describe = { "the session never connected. " + diagnose(saved.id) }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(saved.id)?.state == SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "the shell never took the window. " + diagnose(saved.id) }) {
            compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes().isNotEmpty()
        }

        // The login shell's own output, through a real pty: nothing has been typed at this point.
        pumpUntil(describe = { "the login shell printed nothing. " + diagnose(saved.id) }) {
            drawn(saved.id).isNotBlank()
        }

        // What the on-screen keyboard does to a session a second after it connects.
        compose.runOnUiThread { viewModel.resizeTerminal(saved.id, PHONE_COLUMNS, PHONE_ROWS) }
        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $MARKER") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "the shell never answered the command. " + diagnose(saved.id) }) {
            drawn(saved.id).split(MARKER).size > 2 // the echo of the command, then its output
        }

        // Auto Login SFTP is on by default and the sandbox offers the real `sftp-server` subsystem.
        pumpUntil(describe = { "sftp never settled. " + diagnose(saved.id) }) {
            tabFor(saved.id)?.sftpState in setOf(SftpSessionState.READY, SftpSessionState.FAILED)
        }
        assertThat(tabFor(saved.id)?.sftpState).isEqualTo(SftpSessionState.READY)

        // And now the part nothing else covers: several keep-alive intervals of an idle session.
        val deadline = System.nanoTime() + HOLD_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            Thread.sleep(IDLE_STEP_MS)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_STEP_MS))
            val tab = tabFor(saved.id)
            check(tab?.state == SessionConnectionState.CONNECTED) {
                "the session dropped while idle after " +
                    "${(HOLD_MS - (deadline - System.nanoTime()) / 1_000_000)}ms with a " +
                    "${KEEP_ALIVE_SECONDS}s keep-alive: state=${tab?.state} error=${tab?.lastError}. " +
                    diagnose(saved.id)
            }
        }

        // Still a working shell, not merely a tab that says so.
        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $LATE_MARKER") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "the shell stopped answering after the idle hold. " + diagnose(saved.id) }) {
            drawn(saved.id).contains(LATE_MARKER)
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
    }

    /**
     * A server that probes *the client* does not get to kill this session.
     *
     * `ClientAliveInterval` is the half of keep-alive the app does not control. sshd sends
     * `keepalive@openssh.com` **to the client** with `want-reply` set, and disconnects a client that
     * fails to answer `ClientAliveCountMax` of them - "Timeout, your session not responding". Every
     * hardened VPS image sets it, 60 to 300 seconds being the usual advice, so a client that answered
     * nothing would be dropped by a real server a few minutes after login with nothing wrong at either
     * end, come straight back, and be dropped again: a reconnect loop with no fault anywhere in it.
     * Apache MINA's own server never sends the request unasked, so no other test in this suite can see
     * whether the client answers it.
     *
     * The host asks for the *longest* keep-alive the app offers ([SILENT_KEEP_ALIVE]) so that the
     * client itself stays quiet and the server's probe is the only traffic on the link - a client
     * heartbeat would keep resetting sshd's idle timer and the probe under test would never be sent.
     * The sandbox runs `ClientAliveInterval 5` with `ClientAliveCountMax 2`, so a probe the client
     * ignores closes the session inside about fifteen seconds, well within [SERVER_PROBE_HOLD_MS].
     */
    @Test
    fun aRealOpenSshServerCannotTimeOutASessionThisClientIsAnswering() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val viewModel = viewModel()
        val profile = HostProfile(
            id = "real-openssh-server-keepalive",
            name = "real-openssh-server-keepalive",
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            keepAliveSeconds = SILENT_KEEP_ALIVE,
            autoLoginSftp = false,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        val key = File(sandbox, "client_ed25519").readBytes()
        compose.runOnUiThread { viewModel.connect(saved, keyBytes = key) }
        pumpUntil(describe = { "the session never connected. " + diagnose(saved.id) }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(saved.id)?.state == SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "the login shell printed nothing. " + diagnose(saved.id) }) {
            drawn(saved.id).isNotBlank()
        }

        // Silent from this side for longer than ClientAliveInterval x ClientAliveCountMax: every probe
        // the server sends in here has to be answered by the client or the session is gone.
        val deadline = System.nanoTime() + SERVER_PROBE_HOLD_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            Thread.sleep(IDLE_STEP_MS)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_STEP_MS))
            val tab = tabFor(saved.id)
            check(tab?.state == SessionConnectionState.CONNECTED) {
                "the server timed out this session after " +
                    "${(SERVER_PROBE_HOLD_MS - (deadline - System.nanoTime()) / 1_000_000)}ms of " +
                    "ClientAliveInterval probes: state=${tab?.state} error=${tab?.lastError}. " +
                    "The client is not answering the server's keepalive@openssh.com requests. " +
                    diagnose(saved.id)
            }
        }

        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $SERVER_PROBE_MARKER") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "the shell stopped answering after the probe hold. " + diagnose(saved.id) }) {
            drawn(saved.id).contains(SERVER_PROBE_MARKER)
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
    }

    /**
     * A server that never probes cannot outwait this client's *own* heartbeat.
     *
     * The other two tests both run against a port with `ClientAliveInterval 5`, and that setting hides
     * the thing this one measures. While the server is probing, its requests and the client's replies
     * are traffic, and traffic is what an idle timer watches - so both ends stay up whether the app's
     * heartbeat fires or not. A heartbeat that never left the client would pass both of them.
     *
     * `ClientAliveInterval 0` is OpenSSH's default, so a stock VPS is exactly this: a server that sends
     * nothing and waits forever. There the app's heartbeat is the only traffic on an idle link, and the
     * clock it has to beat is the app's own - `SshConnectionManager` sets MINA's `IDLE_TIMEOUT` to
     * `keepAlive * 3 + 60` seconds and MINA closes the transport when it expires. So a broken heartbeat
     * does not hang: it drops a healthy session about two and a half minutes after login at the default
     * interval, the tab reports an outage nothing caused, the ladder dials again, and the next session
     * dies the same way. Connects, prints the motd, keeps reconnecting - with a successful login every
     * time, which is why it cannot be diagnosed from the fact that connecting works.
     *
     * Two assertions, because either one alone could lie. The tab still being CONNECTED past
     * [SILENT_SERVER_HOLD_MS] is the behaviour and cannot be faked by another test's leftovers, since it
     * is this tab that is asked. The server's log naming the requests is the mechanism, and it is what
     * distinguishes "the heartbeat fired" from "the idle timeout happened not to be reached": sshd
     * reports every global request it receives, and `keepalive@openssh.com` from this side can only have
     * come from the heartbeat, because nothing else in the app sends one unasked.
     */
    @Test
    fun aServerThatNeverProbesCannotOutwaitThisClientsOwnHeartbeat() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val silentPort = File(sandbox, "port-silent")
        assumeTrue(
            "this sandbox predates the silent port: run tools/local-sshd.sh clean && tools/local-sshd.sh start",
            silentPort.isFile,
        )
        val port = silentPort.readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        // Only what *this* session makes the server say. The log is append-only and shared with every
        // other test that has talked to this sandbox.
        val log = File(sandbox, "sshd.log")
        val logOffset = if (log.isFile) log.length() else 0L

        val viewModel = viewModel()
        val profile = HostProfile(
            id = "real-openssh-silent-server",
            name = "real-openssh-silent-server",
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            keepAliveSeconds = KEEP_ALIVE_SECONDS,
            // Off so the link is genuinely idle: an SFTP session opened at login is a second channel
            // whose own traffic would reset the idle timer this test is measuring.
            autoLoginSftp = false,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        val key = File(sandbox, "client_ed25519").readBytes()
        compose.runOnUiThread { viewModel.connect(saved, keyBytes = key) }
        pumpUntil(describe = { "the session never connected. " + diagnose(saved.id) }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(saved.id)?.state == SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "the login shell printed nothing. " + diagnose(saved.id) }) {
            drawn(saved.id).isNotBlank()
        }

        // Nothing typed, nothing sent, and a server that will not break the silence either. The only
        // reason this session can still be here at the end is that the app spoke.
        val deadline = System.nanoTime() + SILENT_SERVER_HOLD_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            Thread.sleep(IDLE_STEP_MS)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_STEP_MS))
            val tab = tabFor(saved.id)
            check(tab?.state == SessionConnectionState.CONNECTED) {
                "an idle session dropped after " +
                    "${(SILENT_SERVER_HOLD_MS - (deadline - System.nanoTime()) / 1_000_000)}ms against a " +
                    "server that never probes, with a ${KEEP_ALIVE_SECONDS}s keep-alive: " +
                    "state=${tab?.state} error=${tab?.lastError}. The app's own heartbeat is not " +
                    "reaching the wire, so MINA's idle timeout " +
                    "(${KEEP_ALIVE_SECONDS} x 3 + 60 = ${KEEP_ALIVE_SECONDS * 3 + 60}s) closed it. " +
                    "Requests the server logged from this side: ${clientProbes(log, logOffset)}. " +
                    diagnose(saved.id)
            }
        }

        // And the mechanism, not just the outcome.
        val probes = clientProbes(log, logOffset)
        assertThat(probes).isAtLeast(MIN_CLIENT_PROBES)

        // Still a working shell, not merely a tab that says so.
        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $SILENT_SERVER_MARKER") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "the shell stopped answering after the silent hold. " + diagnose(saved.id) }) {
            drawn(saved.id).contains(SILENT_SERVER_MARKER)
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
    }

    /**
     * Ten minutes of a genuinely idle session, at the settings a real install actually uses.
     *
     * The other tests compress the keep-alive to five seconds so that three strikes fit inside a test's
     * patience. That is the right trade for the *rule*, and the wrong one for the *number*: what ships
     * is a thirty-second default, which puts the app's own idle deadline at `30 * 3 + 60` = 150 seconds
     * - a figure no other test in this repository is open long enough to reach. A user who logs in and
     * reads for a few minutes crosses it four times over, and "connects, works for a couple of minutes,
     * then keeps reconnecting" is the report this exists to reproduce or refute.
     *
     * Held on the silent port, so the server contributes nothing and every packet on the link is the
     * app's. Nothing is typed. The three things asserted are the three ways this can fail:
     *
     *  - the tab leaving CONNECTED, which is the drop itself;
     *  - the server logging a second `Accepted publickey`, which is a redial - a session that died and
     *    came back inside a sampling gap would look connected at every sample but has authenticated
     *    twice, and the server is the only honest witness to that;
     *  - the shell not answering at the end, which is a session that is up in name only.
     *
     * Off by default and skipped, not failed, because ten minutes does not belong in a gate that runs on
     * every push: set `ECLIPSE_STRESS=1` to run it. CI has a `stress` job that does, triggered by hand
     * from the Actions tab, and it fails rather than passes if the test skips.
     */
    @Test
    fun aDefaultSessionSurvivesTenMinutesOfSilence() {
        assumeTrue(
            "long idle stress test is off: set ECLIPSE_STRESS=1 to run it",
            System.getenv("ECLIPSE_STRESS") == "1",
        )
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val silentPort = File(sandbox, "port-silent")
        assumeTrue(
            "this sandbox predates the silent port: run tools/local-sshd.sh clean && tools/local-sshd.sh start",
            silentPort.isFile,
        )
        val port = silentPort.readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val log = File(sandbox, "sshd.log")
        val logOffset = if (log.isFile) log.length() else 0L

        val viewModel = viewModel()
        val profile = HostProfile(
            id = "real-openssh-long-idle",
            name = "real-openssh-long-idle",
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            // Deliberately *not* set: the point of this test is the shipped default, which is what the
            // idle deadline is derived from and what a user who never opened Settings is running.
            keepAliveSeconds = null,
            autoLoginSftp = false,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        val key = File(sandbox, "client_ed25519").readBytes()
        compose.runOnUiThread { viewModel.connect(saved, keyBytes = key) }
        pumpUntil(describe = { "the session never connected. " + diagnose(saved.id) }) {
            if (viewModel.uiState.value.hostKeyChallenge != null) {
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(saved.id)?.state == SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "the login shell printed nothing. " + diagnose(saved.id) }) {
            drawn(saved.id).isNotBlank()
        }

        val deadline = System.nanoTime() + LONG_IDLE_HOLD_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            Thread.sleep(IDLE_STEP_MS)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_STEP_MS))
            val heldMs = LONG_IDLE_HOLD_MS - (deadline - System.nanoTime()) / 1_000_000
            val tab = tabFor(saved.id)
            check(tab?.state == SessionConnectionState.CONNECTED) {
                "an idle session dropped after ${heldMs}ms at the default keep-alive: " +
                    "state=${tab?.state} error=${tab?.lastError}. " +
                    "Heartbeats the server logged: ${clientProbes(log, logOffset)}, " +
                    "logins: ${logins(log, logOffset)}. " + diagnose(saved.id)
            }
            check(logins(log, logOffset) <= 1) {
                "the session was redialled after ${heldMs}ms of idling - the server has authenticated " +
                    "this host ${logins(log, logOffset)} times for one connect, so a session died and " +
                    "came back between two samples. " + diagnose(saved.id)
            }
        }

        // The heartbeat must have been the traffic keeping it up, at roughly the configured rate.
        assertThat(clientProbes(log, logOffset)).isAtLeast(MIN_CLIENT_PROBES)
        assertThat(logins(log, logOffset)).isEqualTo(1)

        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $LONG_IDLE_MARKER") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "the shell stopped answering after ten minutes idle. " + diagnose(saved.id) }) {
            drawn(saved.id).contains(LONG_IDLE_MARKER)
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
    }

    // ---------------------------------------------------------------- harness

    /**
     * How many times the server has accepted a login since [offset].
     *
     * The one witness to a redial that the app cannot fake: a session that dropped and was replaced
     * inside a sampling gap presents as CONNECTED at every sample the test takes, but the server has
     * authenticated twice and says so.
     */
    private fun logins(log: File, offset: Long): Int =
        appendedLog(log, offset).lineSequence().count { it.contains("Accepted publickey") }

    /**
     * How many `keepalive@openssh.com` requests the server has logged receiving since [offset].
     *
     * sshd names every inbound global request at `debug1`, which is why the sandbox runs `LogLevel
     * DEBUG`. Counted from a byte offset taken before the session opened, because the file is shared
     * with every other test that has used this sandbox - a count from the whole file would include
     * their heartbeats and could only ever be too generous.
     */
    private fun clientProbes(log: File, offset: Long): Int =
        appendedLog(log, offset).lineSequence().count {
            it.contains(KEEPALIVE_REQUEST_NAME) && it.contains("global_request")
        }

    /** Whatever the server has written since [offset], as text. Empty when there is no log at all. */
    private fun appendedLog(log: File, offset: Long): String {
        if (!log.isFile) return ""
        return log.inputStream().use { stream ->
            stream.skipNBytes(offset)
            stream.readBytes().decodeToString()
        }
    }

    private fun viewModel(): MainViewModel =
        ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun tabFor(hostId: String) = viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    private fun drawn(hostId: String): String =
        viewModel().frames.value[hostId]?.lines
            ?.joinToString(separator = "\n") { line -> line.joinToString(separator = "") { it.value.toString() } }
            .orEmpty()

    private fun diagnose(hostId: String): String = buildString {
        val viewModel = viewModel()
        append("tabs=").append(viewModel.uiState.value.tabs)
        append(" status=").append(viewModel.statusMessage.value)
        append(" challenge=").append(viewModel.uiState.value.hostKeyChallenge)
        append("\nframe:\n").append(drawn(hostId))
    }

    private fun pumpUntil(timeoutMs: Long = SSH_TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MARKER = "eclipse-real-marker"
        const val LATE_MARKER = "eclipse-still-here"
        const val SSH_TIMEOUT_MS = 90_000L

        /** A phone-sized pty, which is what the app asks for once the keyboard has taken half the screen. */
        const val PHONE_COLUMNS = 80
        const val PHONE_ROWS = 12

        /**
         * Short enough that the three-strikes rule is exercised inside the hold, long enough to be a
         * legal interval the app itself offers: [dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE] starts
         * at five seconds, and a user on a NAT that drops idle flows sets exactly this.
         */
        const val KEEP_ALIVE_SECONDS = 5

        /** Long enough for [KEEP_ALIVE_SECONDS] to come round several times over. */
        const val HOLD_MS = 32_000L

        /**
         * The longest interval [dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE] allows, chosen so the
         * client sends nothing at all during a hold: what is under test is the reply to the *server's*
         * probe, and a client heartbeat would keep sshd's idle timer from ever expiring.
         */
        const val SILENT_KEEP_ALIVE = 600

        /** Six `ClientAliveInterval` probes with `ClientAliveCountMax 2`: three chances to be killed. */
        const val SERVER_PROBE_HOLD_MS = 30_000L

        const val SERVER_PROBE_MARKER = "eclipse-answered-the-server"

        /** Coarse on purpose: this suite shares two cores, and a spin loop here would take one. */
        const val IDLE_STEP_MS = 100L

        /**
         * Longer than the idle timeout the app gives itself at [KEEP_ALIVE_SECONDS].
         *
         * `SshConnectionManager.configureIdleTimeout` asks MINA for `keepAlive * 3 + 60` seconds, so at
         * a five-second interval the transport closes after 75 of them with no traffic. Holding for 85
         * puts the deadline inside the test with ten seconds to spare, and the margin is the app's own
         * constant rather than a guess about the network.
         */
        const val SILENT_SERVER_HOLD_MS = 85_000L

        /**
         * Heartbeats the server must have logged over [SILENT_SERVER_HOLD_MS].
         *
         * Seventeen are expected at [KEEP_ALIVE_SECONDS]; two is what makes the difference between a
         * heartbeat that is running and one that is not, and asking for near the expected number would
         * turn a slow shared CI box into a failure about keep-alive.
         */
        const val MIN_CLIENT_PROBES = 2

        /** The request MINA sends for a heartbeat, as sshd names it in its log. */
        const val KEEPALIVE_REQUEST_NAME = "keepalive@openssh.com"

        const val SILENT_SERVER_MARKER = "eclipse-outlived-the-silence"

        /**
         * Ten minutes: the low end of the idle the bug reports describe, and four crossings of the
         * 150-second idle deadline the shipped thirty-second keep-alive implies.
         */
        const val LONG_IDLE_HOLD_MS = 600_000L

        const val LONG_IDLE_MARKER = "eclipse-outlived-ten-minutes"

        /** The sandbox `tools/local-sshd.sh` builds, found from wherever Gradle set the working directory. */
        fun sandbox(): File? {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, ".tmp-build/sshd-test")
                if (File(candidate, "port").isFile && File(candidate, "client_ed25519").isFile) return candidate
                dir = dir.parentFile
            }
            return null
        }

        fun listening(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 2_000) }
        }.isSuccess
    }
}
