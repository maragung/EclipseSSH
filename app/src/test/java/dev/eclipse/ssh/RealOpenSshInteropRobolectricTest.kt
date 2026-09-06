package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalLayout
import dev.eclipse.ssh.terminal.TerminalVisualRow
import dev.eclipse.ssh.terminal.terminalLayout
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
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
     * Closes every session this test opened, before the next test starts counting.
     *
     * Sessions deliberately outlive the activity - that is the whole point of adoption, and what stops a
     * rotation from dialling again - so nothing in the app tears one down between two test methods
     * sharing a JVM. The consequence showed up in the one place it could not be argued away: a session
     * left over from an earlier test keeps sending its own keep-alives, the server logs them, and the
     * next test counts them as its own. That is what made
     * [aSessionWithKeepAliveOffSurvivesPastTheDeadlineItWouldHaveHad] see thirty heartbeats from a
     * session configured to send none - three leftover sessions at the shipped 30-second interval across
     * a five-minute hold. The app was doing exactly what it says; the harness was measuring the wrong
     * sessions.
     *
     * Closing the tabs is only half of that, though, and the second leak came through the half it did
     * not cover: a session the app never installed in its store has no tab to close, so
     * `disconnectAll` said "nothing to do" and the socket stayed open. Tabs are the app's *opinion*
     * about what is connected; the kernel's socket table is the fact. So after the tabs are gone the
     * socket table is asked directly - see [leakedSandboxSockets] - and a descriptor this JVM is still
     * holding to a sandbox port fails the test that leaked it, here and now, rather than surfacing as
     * doubled heartbeat counts in an idle test forty minutes later where it reads like a keep-alive bug.
     *
     * A session that will not close is a failure worth reporting, so this asserts rather than hoping.
     * JUnit reports an `@After` failure alongside the test's own, so nothing is masked by it.
     */
    @After
    fun closeEverySession() {
        if (viewModel().uiState.value.tabs.isNotEmpty()) {
            compose.runOnUiThread { viewModel().disconnectAll() }
            pumpUntil(
                timeoutMs = 10_000,
                describe = { "a session was still open after disconnectAll" },
            ) { viewModel().uiState.value.tabs.isEmpty() }
        }
        // The tab going away is the app's decision; the socket closing is MINA finishing it. Give the
        // close futures a moment on the looper so the next test's log offset lands after the server has
        // logged the disconnect rather than in the middle of it.
        pumpFor(CLOSE_SETTLE_MS)
        // Retried rather than asked once: a close that MINA has accepted is finished by its I/O
        // thread, not by the looper, so the descriptor can leave the table a moment after the tab
        // does. What is still here after [LEAK_SETTLE_MS] is not a close in flight - nothing is
        // coming to close it.
        var leaked = leakedSandboxSockets()
        val settleDeadline = System.nanoTime() + LEAK_SETTLE_MS * 1_000_000
        while (leaked.isNotEmpty() && System.nanoTime() < settleDeadline) {
            Thread.sleep(100)
            leaked = leakedSandboxSockets()
        }
        check(leaked.isEmpty()) {
            "this test left ${leaked.values.sum()} socket(s) open to the sandbox " +
                "(by port: $leaked) after every tab was closed - a session the app authenticated and " +
                "then lost track of, with no tab to close and a keep-alive still running. The leak is " +
                "in this test's connect path, not in whichever idle test counts the heartbeats later."
        }
    }

    /**
     * The sockets this JVM still holds open to the sandbox, counted by the port on the far end.
     *
     * `/proc/net/tcp` is the kernel's own socket table for this process, so it sees a connection
     * nothing in the app remembers: a session that authenticated, never reached the session store,
     * and went out of scope with its transport still up. Robolectric does not redirect it - the file
     * reads are plain JVM I/O against the real process, the same as the sandbox files above.
     *
     * Only sockets whose *remote* port is a sandbox listening port are counted, which excludes
     * sshd's own side of each connection (its remote port is the client's ephemeral one) and
     * everything else the JVM talks to. `ESTABLISHED` and `CLOSE_WAIT` are the two states that mean
     * a file descriptor is still open on this side - a close that is still in flight is gone in
     * milliseconds, so the caller waits before asking, and what is left after that is a leak.
     */
    private fun leakedSandboxSockets(): Map<Int, Int> {
        val sandbox = sandbox() ?: return emptyMap()
        val ports = sequenceOf("port", "port-silent", "port-no-shell")
            .mapNotNull { name -> File(sandbox, name).takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull() }
            .toSet()
        if (ports.isEmpty()) return emptyMap()
        return runCatching {
            val counts = mutableMapOf<Int, Int>()
            // The client side of every sandbox connection is IPv4: the tests dial LOOPBACK, which is
            // 127.0.0.1, so /proc/net/tcp alone is the whole story.
            File("/proc/net/tcp").readLines().drop(1).forEach { line ->
                val columns = line.trim().split(Regex("\\s+"))
                val state = columns.getOrNull(3) ?: return@forEach
                if (state == "01" || state == "08") {
                    val remotePort = columns.getOrNull(2)?.substringAfterLast(':')?.toIntOrNull(16) ?: return@forEach
                    if (remotePort in ports) counts[remotePort] = (counts[remotePort] ?: 0) + 1
                }
            }
            counts
        }.getOrDefault(emptyMap())
    }

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
                restartStateTrace(saved.id)
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

        // The reported fault, asserted against a real OpenSSH server: the banner is the first thing the
        // server sends unasked, and arriving output must never be read as the session going away.
        assertNothingLookedLikeADrop(saved.id)

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
        // One connection from the first frame to the last, through the motd, a resize, two commands,
        // an SFTP login and several heartbeats.
        assertNothingLookedLikeADrop(saved.id)
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
                restartStateTrace(saved.id)
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
        assertNothingLookedLikeADrop(saved.id)
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
                restartStateTrace(saved.id)
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
        assertNothingLookedLikeADrop(saved.id)
    }

    /**
     * The programs the brief names, as the binaries they are rather than as the bytes they send.
     *
     * Section 17 of the audit tested `top`, `htop`, `vim`, `nano` and `less` by feeding the emulator the
     * sequences those programs use, and said so plainly, because at the time nothing here could run a
     * login shell: the scripted server is a test double and this host cannot boot an Android image. The
     * sandbox removed that limit without anybody noticing it had. A real `sshd` runs a real login shell
     * on a real pty on the loopback, so the programs can simply be started, and what arrives is then
     * whatever they actually send at this terminal type and this window - including anything the
     * hand-written sequences got subtly wrong, which is the other half of the reason to do it this way
     * round as well.
     *
     * One session for all five, because handing the screen back is the part worth checking: each program
     * takes the window, paints it, and returns it, and the shell underneath has to still be there
     * afterwards. `top` is in the list precisely because it is the one that does *not* use the alternate
     * screen - procps clears and repaints in place - so it is the case the wrapping rule cannot key off
     * that flag to protect. What is asserted for it here is only what is true of it: it paints, the
     * shell comes back, and the session never flickers.
     *
     * Skipped rather than failed for a program the image does not have: which binaries exist is the
     * runner's business, not this app's.
     */
    @Test
    fun realFullScreenProgramsPaintThroughThisEmulatorAndGiveTheScreenBack() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val viewModel = viewModel()
        val saved = logInWithAShell("real-openssh-fullscreen", sandbox!!, port)
        // The pty the app asks for on a phone: 80 columns whatever the screen fits, which is
        // `AppSettings.terminalMinColumns` and the reason a full-screen program's rows are wider than
        // the view they are drawn into.
        compose.runOnUiThread { viewModel.resizeTerminal(saved.id, PHONE_COLUMNS, PHONE_ROWS) }

        // Text of this test's own, wide enough that a phone-width view has to do something about it,
        // rather than whatever this machine happens to keep in /etc.
        //
        // A fresh name per JVM, not a fixed one, because two of the programs below are editors and CI
        // runs the debug and release suites at the same time - `org.gradle.parallel` is on and the two
        // task's timestamps overlap. Two `vi`s on one path meet `E325: ATTENTION` and a swap-file prompt
        // instead of the file, which is vim being right and a harness sharing mutable state being wrong.
        val paged = File.createTempFile("eclipse-pager", ".txt", sandbox)
        paged.deleteOnExit()
        paged.writeText((1..PAGER_LINES).joinToString("\n") { "$PAGER_MARKER line $it " + "=".repeat(50) } + "\n")

        // Opened by name from its own directory rather than by absolute path, because one of the markers
        // below is a program's title bar and a title bar is only as wide as the screen. `nano` centres its
        // version string next to the file name on one row and drops the version when the name crowds it
        // out: measured on a real 80x12 pty, `GNU nano 8.4` is there at a 48-character path and gone by 60,
        // and the runner's workspace makes that path 70. The assertion was passing here on the length of
        // this machine's directory names, which is no kind of test.
        val fromItsDirectory = "cd ${sandbox.absolutePath} &&"

        val ran = mutableListOf<String>()

        // A pager: alternate screen up, the file painted into it, the shell's screen back on `q`.
        onPath("less")?.let { less ->
            runFullScreenProgram(
                hostId = saved.id,
                command = "$fromItsDirectory $less ${paged.name}",
                paints = PAGER_MARKER,
                alternateScreen = true,
                quit = { compose.runOnUiThread { viewModel.sendText(saved.id, "q") } },
            )
            ran += "less"
        }

        // An editor. `vi` here is Vim, which is what the brief's `vim` is on a Debian-family image -
        // and `vim` by that name too, for an image that installs the binary without the alternatives
        // symlink, since the requirement below is an editor rather than a particular spelling of one.
        (onPath("vi") ?: onPath("vim"))?.let { vi ->
            runFullScreenProgram(
                hostId = saved.id,
                command = "$fromItsDirectory $vi ${paged.name}",
                paints = PAGER_MARKER,
                alternateScreen = true,
                quit = {
                    compose.runOnUiThread { viewModel.sendText(saved.id, ":q!") }
                    compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
                },
            )
            ran += "vi"
        }

        // The other editor, and the only one of the five quit by a control chord rather than a letter -
        // so this is also the shortcut bar's Ctrl row reaching a real program through a real pty.
        onPath("nano")?.let { nano ->
            runFullScreenProgram(
                hostId = saved.id,
                command = "$fromItsDirectory $nano ${paged.name}",
                paints = "GNU nano",
                alternateScreen = true,
                quit = { compose.runOnUiThread { viewModel.sendChar(saved.id, 'x', ctrl = true) } },
            )
            ran += "nano"
        }

        // procps `top`: no alternate screen, a full repaint every interval instead. `-d 9` so it paints
        // once and then waits, because this host shares two cores with other tenants.
        onPath("top")?.let { top ->
            runFullScreenProgram(
                hostId = saved.id,
                command = "$top -d 9",
                paints = "Tasks:",
                alternateScreen = false,
                quit = { compose.runOnUiThread { viewModel.sendText(saved.id, "q") } },
            )
            ran += "top"
        }

        // And ncurses' own: alternate screen, meters, and a function-key bar.
        onPath("htop")?.let { htop ->
            runFullScreenProgram(
                hostId = saved.id,
                command = "$htop -d 100",
                paints = "Tasks:",
                alternateScreen = true,
                quit = { compose.runOnUiThread { viewModel.sendText(saved.id, "q") } },
            )
            ran += "htop"
        }

        // A pager and an editor at the least, or this test passed without testing anything.
        check(ran.containsAll(listOf("less", "vi"))) {
            "this image has neither a pager nor an editor to run: found $ran"
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
        // Five programs, five alternate-screen switches, and one connection underneath all of it.
        assertNothingLookedLikeADrop(saved.id)
    }

    /**
     * Real output from real programs, wrapped by the display and never through a token.
     *
     * The wrapping tests under `terminal/` are pure functions over frames this suite builds, which is
     * the right way to test the rule and no way at all to test the assumption underneath it: that what a
     * server actually sends looks like what those tests assume. Here the URL is echoed by the sandbox's
     * own shell, the hash is produced by the sandbox's own `sha256sum`, and both travel the whole path -
     * pty, transport, decoder, emulator, layout - before anything is asserted about where the line was
     * broken.
     *
     * The width is the interesting part: the pty is 80 columns because that is what
     * `AppSettings.terminalMinColumns` gives it, and the view is [NARROW_COLUMNS], which is roughly what
     * a phone fits. Every one of these tokens is longer than the view and shorter than the pty, so the
     * server sends it whole and the display is the only thing that could break it.
     */
    @Test
    fun realServerOutputWrapsWithoutSplittingATokenApart() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val viewModel = viewModel()
        val saved = logInWithAShell("real-openssh-wrapping", sandbox!!, port)
        compose.runOnUiThread { viewModel.resizeTerminal(saved.id, PHONE_COLUMNS, PHONE_ROWS) }

        // A URL and a path, which is what the complaint that started this was about: reachable only by
        // dragging the text sideways, and unreadable if broken at column 46.
        echoAndAssertWhole(saved.id, "https://mirror.example.invalid/pool/e/eclipsessh_1.1.0_arm64.deb")
        echoAndAssertWhole(saved.id, "/usr/lib/jvm/temurin-17-jdk-amd64/lib/security/cacerts.2026-08-24")
        // Quoted, because an unquoted brace is the shell's to expand and this is meant to arrive as JSON.
        //
        // A blob is not a token, and asking for it whole was this test's own mistake before it was
        // anything else. `{`, `}`, `,` and `"` are deliberately not word characters - long-pressing a
        // value in a line like this gives the value, not the line - so a 58-column blob in a 46-column
        // view is broken, after `22022`, at the comma, which is where a reader would break it too. What
        // must survive is every key and every value: a hostname, a port or a boolean cut in half reads as
        // a different value, and a reader cannot tell that cut from one the server sent.
        echoAndAssertWhole(
            hostId = saved.id,
            token = """{"host":"eclipse.example.invalid","port":22022,"pty":true}""",
            typed = """echo '{"host":"eclipse.example.invalid","port":22022,"pty":true}'""",
            whole = listOf(""""host"""", """"eclipse.example.invalid"""", "22022", """"pty"""", "true"),
        )

        // Not a token this test invented: a real digest from a real program, which is the case where a
        // break is worst - two halves of a hash are indistinguishable from a different hash.
        compose.runOnUiThread { viewModel.sendText(saved.id, "sha256sum ${File(sandbox, "port").absolutePath}") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "sha256sum printed no digest. " + diagnose(saved.id) }) {
            SHA256.containsMatchIn(drawn(saved.id))
        }
        assertWholeOnOneVisualRow(saved.id, SHA256.find(drawn(saved.id))!!.value)

        assertThat(tabFor(saved.id)?.lastError).isNull()
        assertNothingLookedLikeADrop(saved.id)
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
     * How the hold is watched is in [holdASilentSession], which this and the rest of the idle matrix
     * share.
     *
     * Off by default and skipped, not failed, because ten minutes does not belong in a gate that runs on
     * every push: set `ECLIPSE_STRESS=1` to run it. CI has a `stress` job that does, triggered by hand
     * from the Actions tab, and it fails rather than passes if any of these skip.
     */
    @Test
    fun aDefaultSessionSurvivesTenMinutesOfSilence() {
        // Deliberately no keep-alive interval: the shipped default is what the idle deadline is derived
        // from and what a user who never opened Settings is running.
        holdASilentSession(id = "real-openssh-idle-10m", holdMs = MINUTE_MS * 10)
    }

    /**
     * The rest of the idle matrix, which is one rule at five durations and three configurations.
     *
     * Thirty seconds and a minute are here because the bug reports are not all about long idles - "it
     * drops while I read the output of one command" is the same complaint at a shorter scale, and a
     * session that dies at thirty seconds and one that dies at thirty minutes have different causes. The
     * middle of the range is where the app's own timers live, so it is where an off-by-one in the idle
     * deadline shows up.
     */
    @Test
    fun aSessionSurvivesThirtySecondsOfSilence() {
        holdASilentSession(id = "real-openssh-idle-30s", holdMs = 30_000L)
    }

    @Test
    fun aSessionSurvivesOneMinuteOfSilence() {
        holdASilentSession(id = "real-openssh-idle-1m", holdMs = MINUTE_MS)
    }

    @Test
    fun aSessionSurvivesFiveMinutesOfSilence() {
        holdASilentSession(id = "real-openssh-idle-5m", holdMs = MINUTE_MS * 5)
    }

    /**
     * Half an hour, which is the top of the range the reports describe and twelve crossings of the
     * 150-second deadline the shipped keep-alive implies.
     *
     * Nothing here differs from the ten-minute case except patience, and that is the point: if the app
     * has any timer that fires once and is never rearmed, this is the hold that finds it.
     */
    @Test
    fun aSessionSurvivesThirtyMinutesOfSilence() {
        holdASilentSession(id = "real-openssh-idle-30m", holdMs = MINUTE_MS * 30)
    }

    /**
     * Compression on, because it changes who is holding the bytes.
     *
     * zlib puts a deflate stream between the channel and the transport, and a deflater holds output in
     * its own buffer until it has enough to emit. That is worth a test of its own: if the app ever
     * concluded anything from "no bytes arrived", compression is the setting that would make a healthy
     * session look silent, and the heartbeat travels the same compressed link as everything else.
     */
    @Test
    fun aCompressedSessionSurvivesFiveMinutesOfSilence() {
        holdASilentSession(id = "real-openssh-idle-zlib", holdMs = MINUTE_MS * 5, compression = true)
    }

    /**
     * Keep-alive off, held well past the deadline it would have implied - the W4 coupling, tested.
     *
     * This is the case that used to be a bug and is easy to reintroduce. `configureIdleTimeout` asks MINA
     * for `keepAlive * 3 + 60` seconds of patience, so at the shipped thirty-second interval a silent
     * transport is closed after 150. Turning the heartbeat off removes the traffic that resets that timer
     * without removing the timer, and the result is a session that dies two and a half minutes after
     * login *because* the user asked for less network chatter. Five minutes of silence is twice the
     * deadline, so a session that reaches the end of this hold proves the timeout went off with the
     * heartbeat.
     *
     * The zero-probe assertion is the other half: "off" has to mean nothing was sent, or this test would
     * pass on a session that was quietly still beating.
     */
    @Test
    fun aSessionWithKeepAliveOffSurvivesPastTheDeadlineItWouldHaveHad() {
        holdASilentSession(
            id = "real-openssh-idle-nokeepalive",
            holdMs = MINUTE_MS * 5,
            keepAliveEnabled = false,
        )
    }

    /**
     * A server that takes the key and then refuses the shell: the reported login fault, as a config.
     *
     * This is the one failure the rest of this suite structurally cannot produce and the one the reports
     * describe. On the sandbox's ordinary port every failure happens before the login or not at all, and
     * no unit test can reach it either, because the whole premise is that authentication *succeeded* first - three times
     * over - and that what then failed was the pty. `MaxSessions 0` on the sandbox's third port is
     * OpenSSH's own switch for exactly this (see `tools/local-sshd.sh`): the transport comes up, the key
     * is accepted, and the session channel is refused, without needing a host that is genuinely out of
     * ptys or a `ForceCommand` that exits.
     *
     * What it asserts is the whole of the login fix, measured on a real socket:
     *
     *  - the trace reaches CHANNEL_PTY_INITIALIZING, so the login really did work before this failed -
     *    without which the rest of the test would be about a connection that never got that far;
     *  - RECONNECTING appears nowhere in it, on a host with auto-reconnect deliberately left **on**. A
     *    session that has never once been up cannot be reconnecting, and that word - on this exact
     *    sequence - is what three releases of "it connects and then just keeps reconnecting" were
     *    actually reporting;
     *  - each wait between attempts says which half of the login failed and which attempt is next,
     *    rather than borrowing the ladder's sentence about the connection;
     *  - the server logged one accepted key per attempt, which is the only witness that the credential
     *    was taken every single time rather than this being an authentication retry in disguise;
     *  - the tab ends at ERROR carrying a reason, and that reason survives the *compact* status row -
     *    the row the user is looking at while this happens, and the one place the reason used to be
     *    deleted on its way to the screen;
     *  - and the keyboard still on that screen reaches nothing, which is the honest form of "no typing
     *    into a dead session" for a design that keeps its IME host composed on purpose.
     */
    @Test
    fun aServerThatRefusesTheShellNeverCallsTheFailureAReconnect() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val noShellPort = File(sandbox, "port-no-shell")
        assumeTrue(
            "this sandbox predates the no-shell port: run tools/local-sshd.sh clean && tools/local-sshd.sh start",
            noShellPort.isFile,
        )
        val port = noShellPort.readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val log = File(sandbox, "sshd.log")
        val logOffset = if (log.isFile) log.length() else 0L

        val viewModel = viewModel()
        val profile = HostProfile(
            id = "real-openssh-no-shell",
            name = "real-openssh-no-shell",
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            // Left on deliberately. A host with the ladder switched off would pass this test by having
            // no ladder to mislabel; what has to be true is that a connect-time failure is not answered
            // with the word for an outage even when the user has asked for outages to be answered.
            autoReconnect = true,
            autoLoginSftp = false,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        val saved = viewModel.uiState.value.hosts.first { it.id == profile.id }
        val key = File(sandbox, "client_ed25519").readBytes()
        compose.runOnUiThread { viewModel.connect(saved, keyBytes = key) }

        // Waited for on its own rather than inside the wait below, because known hosts are keyed by host
        // *and* port: this port has never been connected to whatever ran before, so the first attempt
        // always stops at the trust question - and that attempt's own ERROR would otherwise satisfy the
        // wait for the failure actually under test.
        pumpUntil(describe = { "the sandbox key was never offered for trust. " + diagnose(saved.id) }) {
            viewModel.uiState.value.hostKeyChallenge != null
        }
        compose.runOnUiThread { viewModel.acceptHostKey() }
        restartStateTrace(saved.id)

        // Collected inside the wait for the same reason [statesSeen] is: the notices are what the user
        // reads *during* the ladder, and by the time it has given up the tab is showing the final
        // failure instead. Deduplicated against the previous one so a sentence that stayed on screen for
        // two hundred frames appears once.
        val notices = mutableListOf<String>()
        val collectNotice: () -> Unit = {
            tabFor(saved.id)?.lastError?.let { if (notices.lastOrNull() != it) notices += it }
        }

        // Two waits, and which comes first is the whole reliability of this test. The trust question is
        // raised from inside the dial and the failure that raised it is written to the tab afterwards, so
        // the answer can be given - and the trace restarted - while that ERROR is still on its way. A
        // single wait for ERROR would then be satisfied by the failure the user has just dismissed,
        // before the sandbox had been asked for a shell at all, and this test would pass without
        // exercising one line of what it is about. Waiting for the login to reach the shell phase first
        // makes the ERROR that follows necessarily the shell's.
        pumpUntil(describe = { "the trusted login never reached the shell phase. " + diagnose(saved.id) }) {
            collectNotice()
            statesSeen[saved.id].orEmpty().contains(SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        }
        pumpUntil(describe = { "the shell-less login never gave up. " + diagnose(saved.id) }) {
            collectNotice()
            tabFor(saved.id)?.state == SessionConnectionState.ERROR
        }

        val seen = statesSeen[saved.id].orEmpty()
        val evidence = "states=$seen notices=$notices"
        assertWithMessage("the login never reached the shell phase, so this proves nothing: $evidence")
            .that(seen)
            .contains(SessionConnectionState.CHANNEL_PTY_INITIALIZING)
        assertWithMessage("a first login that failed was reported as a reconnect: $evidence")
            .that(seen)
            .doesNotContain(SessionConnectionState.RECONNECTING)
        assertWithMessage("the tab did not end at ERROR: $evidence").that(seen.last())
            .isEqualTo(SessionConnectionState.ERROR)

        assertWithMessage("no retry said what had failed: $evidence")
            .that(notices.filter { it.contains("the shell did not open") })
            .isNotEmpty()
        assertWithMessage("a retry blamed the connection that had just worked: $evidence")
            .that(notices.filter { it.contains("Retrying connection") })
            .isEmpty()
        assertWithMessage("the retries did not count themselves: $evidence")
            .that(notices.filter { it.endsWith("attempt 2 of ${MainViewModel.MAX_CONNECT_ATTEMPTS}") })
            .isNotEmpty()

        // The server's own account of the ladder. Every rung authenticated, so every rung failed after
        // the credential was accepted - which is the premise the wording fix rests on, and the one thing
        // the app could be wrong about without any of its own state showing it.
        // Every port's count in the message, not just this one's. The sandbox log is shared, so the
        // interesting failure is "three here and three somewhere else" - and a bare count cannot tell
        // that from "six here", which is the difference between a shared log and a ladder gone twice
        // round. That distinction cost two CI round trips to make.
        assertWithMessage(
            "the ladder did not spend its attempts on the server that kept letting it in " +
                "(port $port; logins by port ${loginsByPort(log, logOffset)}): $evidence " +
                diagnose(saved.id),
        )
            .that(logins(log, logOffset, port))
            .isEqualTo(MainViewModel.MAX_CONNECT_ATTEMPTS)

        val reason = tabFor(saved.id)?.lastError
        assertWithMessage("a failed login left the tab with nothing to show for it").that(reason)
            .isNotEmpty()
        assertWithMessage("the failure called itself a reconnect: $reason")
            .that(reason?.lowercase())
            .doesNotContain("reconnect")
        // B1 on a real failure: the compact row is the terminal screen's, and it is the row that used to
        // drop everything the app knew about why.
        assertThat(statusLine(SessionConnectionState.ERROR, startedAt = null, lastError = reason, compact = true))
            .isEqualTo(reason)
        // And the keyboard. The IME host is deliberately in the tree for as long as the terminal screen
        // is - an `InputConnection` cannot be established for a view that is not composed, so a field
        // created only when the keyboard was wanted would arrive after the request to open it - so what
        // has to be true here is not that it is absent but that it is *inert*. Asserted in that order,
        // because the first half is the app's documented design and a test that quietly required the
        // opposite would be an argument for breaking it.
        assertWithMessage("the IME host went missing, and it cannot be created on demand")
            .that(compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes())
            .isNotEmpty()
        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $MARKER") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpFor(DEAD_TYPING_MS)
        assertWithMessage("a session that never had a pty echoed something back")
            .that(drawn(saved.id))
            .doesNotContain(MARKER)
        assertWithMessage(
            "typing at a failed tab dialled the server again " +
                "(port $port; logins by port ${loginsByPort(log, logOffset)})",
        )
            .that(logins(log, logOffset, port))
            .isEqualTo(MainViewModel.MAX_CONNECT_ATTEMPTS)
        assertWithMessage("typing at a failed tab changed what it says").that(tabFor(saved.id)?.lastError)
            .isEqualTo(reason)
        assertWithMessage("typing at a failed tab moved it out of ERROR").that(tabFor(saved.id)?.state)
            .isEqualTo(SessionConnectionState.ERROR)
    }

    /**
     * Compression on, with the server's own key exchange log as the witness that it is on.
     *
     * A per-host switch that silently does nothing is worse than no switch, and "the session came up" is
     * not evidence either way - `none` is in the app's own preference list, so a client that failed to
     * offer zlib at all would connect exactly like this one. The only honest witness is the far end, and
     * sshd names the compression it negotiated in each direction at debug level, which is why the
     * sandbox runs at `LogLevel DEBUG`.
     */
    @Test
    fun aCompressedLoginReachesAShellAndTheServerAgreesItIsCompressed() {
        aLoginNegotiatesCompression(id = "real-openssh-zlib-on", compression = true)
    }

    /** And off, which is the shipped default and has to reach the wire just as literally. */
    @Test
    fun anUncompressedLoginReachesAShellAndTheServerAgreesItIsNot() {
        aLoginNegotiatesCompression(id = "real-openssh-zlib-off", compression = false)
    }

    /**
     * A host's saved tunnel comes up with its session, and carries real bytes.
     *
     * `forwardsOpen == forwardsTotal` is the app's own claim about itself, and a tracker that bound a
     * socket and forwarded nothing would satisfy it. So the rule points back at the sandbox's own ssh
     * port and the test reads what comes out: an OpenSSH identification string arriving through a local
     * socket, having crossed the session as a channel, is proof the tunnel carried traffic that only the
     * far end could have produced.
     */
    @Test
    fun aSavedLocalForwardComesUpWithTheSessionAndCarriesTheServersOwnTraffic() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val local = freePort()
        val saved = logInWithAShell(
            id = "real-openssh-forward",
            sandbox = sandbox!!,
            port = port,
            // Straight back to the server that is carrying it, so the only thing that can answer on the
            // local port is the far end of the tunnel.
            savedForwards = "L:$local:$LOOPBACK:$port",
        )
        pumpUntil(describe = { "the saved forward never came up. " + diagnose(saved.id) }) {
            tabFor(saved.id)?.forwardsOpen == 1
        }
        val tab = tabFor(saved.id)
        assertThat(tab?.forwardsTotal).isEqualTo(1)
        assertThat(tab?.forwardError).isNull()
        assertThat(tab?.state).isEqualTo(SessionConnectionState.CONNECTED)

        // On its own thread, because the read has to happen while this test keeps pumping: the socket is
        // answered by MINA's own I/O threads, but a blocking read on this one would stop the looper that
        // the session's state - and any failure this test would rather report than hang on - travels on.
        val throughTheTunnel = AtomicReference<String?>(null)
        val tunnelFailure = AtomicReference<Throwable?>(null)
        val reader = Thread {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(LOOPBACK, local), TUNNEL_TIMEOUT_MS.toInt())
                    socket.soTimeout = TUNNEL_TIMEOUT_MS.toInt()
                    throughTheTunnel.set(socket.getInputStream().bufferedReader().readLine())
                }
            }.onFailure(tunnelFailure::set)
        }
        reader.start()
        pumpUntil(describe = { "nothing came back through the tunnel. " + diagnose(saved.id) }) {
            throughTheTunnel.get() != null || tunnelFailure.get() != null
        }
        reader.join(TUNNEL_TIMEOUT_MS)
        assertWithMessage("the tunnel failed instead of carrying bytes").that(tunnelFailure.get()).isNull()
        assertWithMessage("what came through the tunnel was not the server on the other end of it")
            .that(throughTheTunnel.get())
            .startsWith("SSH-2.0-")
        assertNothingLookedLikeADrop(saved.id)
    }

    /**
     * A tunnel that cannot bind costs the user the tunnel and nothing else.
     *
     * The rule the whole feature turns on. A port already in use is the ordinary case - a SOCKS proxy
     * left over from another app, two hosts saving the same 8080 - and answering it by ending the
     * session, or by writing it onto the status line, would make a working shell look broken and hand
     * the reconnect ladder a failure it cannot fix by redialling. The port is taken by this test rather
     * than assumed to be, so the failure is the one under test and not a coincidence.
     */
    @Test
    fun aSavedForwardThatCannotBindCostsTheTunnelAndNotTheShell() {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress(LOOPBACK, 0))
            val saved = logInWithAShell(
                id = "real-openssh-forward-busy",
                sandbox = sandbox!!,
                port = port,
                savedForwards = "L:${taken.localPort}:$LOOPBACK:$port",
            )
            pumpUntil(describe = { "a forward that could not bind never said so. " + diagnose(saved.id) }) {
                tabFor(saved.id)?.forwardError != null
            }
            val tab = tabFor(saved.id)
            assertWithMessage("a tunnel that could not bind took the session down with it")
                .that(tab?.state)
                .isEqualTo(SessionConnectionState.CONNECTED)
            assertThat(tab?.forwardsOpen).isEqualTo(0)
            assertThat(tab?.forwardsTotal).isEqualTo(1)
            assertWithMessage("a bind failure was written onto the session's own status line")
                .that(tab?.lastError)
                .isNull()
            assertWithMessage("the report did not say which rule failed").that(tab?.forwardError)
                .contains("localhost:${taken.localPort}")

            // And the shell is not merely labelled connected.
            val marker = "$FORWARD_MARKER-busy"
            compose.runOnUiThread { viewModel().sendText(saved.id, "echo $marker") }
            compose.runOnUiThread { viewModel().sendKey(saved.id, TerminalKey.ENTER) }
            pumpUntil(describe = { "the shell stopped answering after a forward failed. " + diagnose(saved.id) }) {
                drawn(saved.id).contains(marker)
            }
            assertNothingLookedLikeADrop(saved.id)
        }
    }

    /**
     * Logs in with [compression] set, echoes through the shell, and reads the server's kex log back.
     *
     * The two directions are asserted separately from each other only in the failure message: what
     * matters is that no direction disagrees with the setting, and naming the offenders is what makes a
     * failure here diagnosable rather than a bare `false`.
     */
    private fun aLoginNegotiatesCompression(id: String, compression: Boolean) {
        val sandbox = sandbox()
        assumeTrue("no local sshd sandbox: run tools/local-sshd.sh start", sandbox != null)
        val port = File(sandbox, "port").readText().trim().toInt()
        assumeTrue("local sshd sandbox is not listening on $port", listening(port))

        val log = File(sandbox, "sshd.log")
        val logOffset = if (log.isFile) log.length() else 0L

        val saved = logInWithAShell(id = id, sandbox = sandbox!!, port = port, compression = compression)
        val marker = "$COMPRESSION_MARKER-$id"
        compose.runOnUiThread { viewModel().sendText(saved.id, "echo $marker") }
        compose.runOnUiThread { viewModel().sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "a compression=$compression shell never answered. " + diagnose(saved.id) }) {
            drawn(saved.id).contains(marker)
        }

        // `debug1: kex: client->server cipher: … MAC: <implicit> compression: none [preauth]` - one line
        // per direction, and the trailing `[preauth]` is why this takes the first token rather than the
        // rest of the line.
        //
        // The window is a shared log, not this session's: a connection any other test's session opens
        // while this one is logging in lands its own kex lines in the same bytes. So the verdict below
        // carries the raw lines and the window's login count - with those two, the next failure names
        // its own cause (one login that negotiated none, against a foreign login's lines interleaved)
        // instead of a bare list that cannot distinguish them.
        val kexLines = appendedLog(log, logOffset).lineSequence()
            .filter { it.contains("kex:") && it.contains("compression: ") }
            .toList()
        val negotiated = kexLines
            .map { it.substringAfter("compression: ").trim().substringBefore(' ') }
            .toList()
        val windowLogins = loginsByPort(log, logOffset)
        val window = "logins in this window: $windowLogins; kex lines:\n${kexLines.joinToString("\n")}"
        assertWithMessage("the server logged no negotiated compression for this session. $window")
            .that(negotiated.size)
            .isAtLeast(2)
        if (compression) {
            assertWithMessage("compression was on for this host and the server compressed nothing. $window")
                .that(negotiated.filterNot { it.startsWith("zlib") })
                .isEmpty()
        } else {
            assertWithMessage("compression was off for this host and the server compressed anyway. $window")
                .that(negotiated.filterNot { it == "none" })
                .isEmpty()
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
        assertNothingLookedLikeADrop(saved.id)
    }

    /**
     * Logs in, says nothing for [holdMs], and then checks the shell still answers.
     *
     * Sampling every [IDLE_STEP_MS] rather than only at the end, because the three ways this fails are
     * not all visible afterwards:
     *
     *  - the tab leaving CONNECTED, which is the drop itself;
     *  - the server logging a second `Accepted publickey`, which is a redial - a session that died and
     *    came back inside a sampling gap looks connected at every sample but has authenticated twice,
     *    and the server is the only honest witness to that;
     *  - the shell not answering at the end, which is a session that is up in name only.
     *
     * Held on the silent port, so the server contributes nothing (`ClientAliveInterval 0`) and every
     * packet on the link is the app's.
     */
    private fun holdASilentSession(
        id: String,
        holdMs: Long,
        keepAliveEnabled: Boolean = true,
        keepAliveSeconds: Int? = null,
        compression: Boolean = false,
    ) {
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
            id = id,
            name = id,
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            keepAliveSeconds = keepAliveSeconds,
            keepAliveEnabled = keepAliveEnabled,
            compression = compression,
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
                restartStateTrace(saved.id)
            }
            tabFor(saved.id)?.state == SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "the login shell printed nothing. " + diagnose(saved.id) }) {
            drawn(saved.id).isNotBlank()
        }

        val deadline = System.nanoTime() + holdMs * 1_000_000
        while (System.nanoTime() < deadline) {
            Thread.sleep(IDLE_STEP_MS)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_STEP_MS))
            sampleStates()
            val heldMs = holdMs - (deadline - System.nanoTime()) / 1_000_000
            val tab = tabFor(saved.id)
            check(tab?.state == SessionConnectionState.CONNECTED) {
                "an idle session dropped after ${heldMs}ms of ${holdMs}ms " +
                    "(keepAlive=$keepAliveEnabled@${keepAliveSeconds ?: "default"}s compression=$compression): " +
                    "state=${tab?.state} error=${tab?.lastError}. " +
                    "Heartbeats the server logged: ${clientProbes(log, logOffset)}, " +
                    "logins: ${logins(log, logOffset, port)}. " + diagnose(saved.id)
            }
            check(logins(log, logOffset, port) <= 1) {
                "the session was redialled after ${heldMs}ms of idling - the server has authenticated " +
                    "this host ${logins(log, logOffset, port)} times for one connect, so a session " +
                    "died and came back between two samples. " +
                    "Logins by port: ${loginsByPort(log, logOffset)}. " + diagnose(saved.id)
            }
        }

        val probes = clientProbes(log, logOffset)
        if (keepAliveEnabled) {
            // What "roughly the configured rate" means has to come from the interval and the hold, and
            // cannot be a constant: this matrix runs a thirty-second hold and a half-hour one at the
            // same thirty-second interval, and the short one has room for exactly one heartbeat. A fixed
            // floor of two therefore asked the app to beat twice as fast as it had been told to, and it
            // passed anyway - because a session an earlier test had left open was beating into this
            // window and being counted here. [closeEverySession] stopped that, and the assertion it had
            // been propping up failed on the next run, which is the honest order of events.
            val interval = viewModel.uiState.value.settings.keepAliveSeconds
            val allowed = (holdMs / 1000) / (keepAliveSeconds ?: interval)
            // Half of what the window allows, because MINA's timer restarts on traffic and a shared
            // runner may lose a beat, and only when there is room for two: below that the count says
            // nothing about the rate in either direction.
            if (allowed >= 2) assertThat(probes).isAtLeast((allowed / 2).toInt())
            // Bounded above as well, because an interval is a promise in both directions. A client
            // beating ten times as often as it was asked to would hold every session up and satisfy
            // every assertion above this one, while costing a metered connection real money and waking
            // the radio on a phone that was trying to sleep.
            assertThat(probes).isAtMost((allowed * 2 + 1).toInt())
        } else {
            // "Off" means nothing was sent. Without this the test would also pass on a session that was
            // quietly still beating, which is the opposite of what the setting promises.
            assertThat(probes).isEqualTo(0)
        }
        assertThat(logins(log, logOffset, port)).isEqualTo(1)

        val marker = "$IDLE_MARKER_PREFIX-$id"
        compose.runOnUiThread { viewModel.sendText(saved.id, "echo $marker") }
        compose.runOnUiThread { viewModel.sendKey(saved.id, TerminalKey.ENTER) }
        pumpUntil(describe = { "the shell stopped answering after ${holdMs}ms idle. " + diagnose(saved.id) }) {
            drawn(saved.id).contains(marker)
        }
        assertThat(tabFor(saved.id)?.lastError).isNull()
        assertNothingLookedLikeADrop(saved.id)
    }

    // ---------------------------------------------------------------- harness

    /**
     * How many times the server has accepted a login since [offset].
     *
     * The one witness to a redial that the app cannot fake: a session that dropped and was replaced
     * inside a sampling gap presents as CONNECTED at every sample the test takes, but the server has
     * authenticated twice and says so.
     */
    /**
     * How many times the server on [port] accepted a key from this client since [offset].
     *
     * Filtered by the port the connection arrived on, and that filter is the whole reliability of every
     * assertion below that counts logins. `tools/local-sshd.sh` runs *one* sshd serving all three
     * sandbox ports and writing *one* log, so an unfiltered count is the three servers' logins added
     * together - and this class shares a JVM, a session store and a database with every other test in
     * the suite, so it is also every login that any host left behind by an earlier test contributes
     * while this one is running. "The ladder spent its attempts on *this* server" cannot be read off
     * that total. It was read off it, and CI found the difference: `expected 3 but was 6` on a run whose
     * own timings were unchanged to within 70ms, so the three extra logins were never this test's to
     * count and no ladder here had climbed twice.
     *
     * The listening port is recoverable because sshd names both ends when the connection arrives
     * (`Connection from <ip> port <src> on <ip> port <dst>`, at the `LogLevel DEBUG` this sandbox
     * already runs for the compression assertions) and names the same client port again on its
     * `Accepted publickey ... from <ip> port <src>` line. The client port is the join key, and it is
     * unique for as long as the connection exists - which is longer than the two lines are apart.
     */
    private fun logins(log: File, offset: Long, port: Int): Int = loginsByPort(log, offset)[port] ?: 0

    /**
     * Every login since [offset], counted per listening port, as the evidence for [logins].
     *
     * Key 0 collects the logins whose own arrival predates [offset] - a connection this window did not
     * see opened, so a port cannot be put to it. Counted rather than dropped, because a number nobody
     * can account for is exactly what sent the last such failure round CI twice.
     */
    private fun loginsByPort(log: File, offset: Long): Map<Int, Int> {
        val arrivedOn = mutableMapOf<String, Int>()
        val counts = mutableMapOf<Int, Int>()
        appendedLog(log, offset).lineSequence().forEach { line ->
            ARRIVED.find(line)?.let { match ->
                arrivedOn[match.groupValues[1]] = match.groupValues[2].toInt()
                return@forEach
            }
            ACCEPTED.find(line)?.let { match ->
                val listening = arrivedOn[match.groupValues[1]] ?: 0
                counts[listening] = (counts[listening] ?: 0) + 1
            }
        }
        return counts
    }

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
        append("\ntrace:\n").append(trace(hostId))
        append("\nframe:\n").append(drawn(hostId))
    }

    /**
     * This host's own lines from the app's diagnostic ring.
     *
     * The trace is the only witness to what a failed attempt actually was - which of the endings, whose
     * dial, how many attempts in - and a harness that reports a state sequence without it leaves the
     * reader guessing at exactly the point the app has already written the answer down. Filtered to one
     * host by its session label, because these tests run several sessions at once and a sandbox port
     * appears in nobody's line: [SessionDiagnostics] records an opaque ordinal per host on purpose.
     */
    private fun trace(hostId: String): String {
        val viewModel = viewModel()
        val label = viewModel.uiState.value.diagnosticsLabels[hostId] ?: return "(no session label yet)"
        return viewModel.exportDiagnostics()
            .lineSequence()
            .filter { line -> " $label." in line }
            .joinToString("\n")
            .ifEmpty { "(nothing recorded for $label)" }
    }

    /**
     * Saves a host, trusts the sandbox's key, connects, and waits for a shell with output in it.
     *
     * The terminal, compression and forwarding tests need the same four things and none of them is
     * about how they happen - the tests above cover that in detail, including what the first, untrusted
     * attempt reports. Returns the saved profile because the id the app stored is what addresses the
     * session.
     *
     * [compression] and [savedForwards] are the two per-host settings whose effects are only observable
     * against a real server: one is a negotiated algorithm the far end has to agree to, the other binds
     * real sockets and moves real bytes. Both default to what a host has before anybody opens Advanced.
     */
    private fun logInWithAShell(
        id: String,
        sandbox: File,
        port: Int,
        compression: Boolean = false,
        savedForwards: String = "",
    ): HostProfile {
        val viewModel = viewModel()
        val profile = HostProfile(
            id = id,
            name = id,
            host = LOOPBACK,
            username = File(sandbox, "user").readText().trim(),
            port = port,
            authMethod = AuthMethod.SSH_KEY,
            connectTimeoutSeconds = 60,
            keepAliveSeconds = KEEP_ALIVE_SECONDS,
            compression = compression,
            savedForwards = savedForwards,
            // Off, so the only channel on this session is the one the terminal is on: an SFTP channel
            // opening underneath a full-screen program is a second thing to explain in a failure.
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
                restartStateTrace(saved.id)
            }
            tabFor(saved.id)?.state == SessionConnectionState.CONNECTED
        }
        pumpUntil(describe = { "the shell never took the window. " + diagnose(saved.id) }) {
            compose.onAllNodesWithContentDescription("Terminal input").fetchSemanticsNodes().isNotEmpty()
        }
        pumpUntil(describe = { "the login shell printed nothing. " + diagnose(saved.id) }) {
            drawn(saved.id).isNotBlank()
        }
        return saved
    }

    /**
     * Runs [command], waits for it to paint [paints], checks the screen it painted, and quits it.
     *
     * Two things are asserted about the painting itself. The screen mode has to be the one the program
     * actually uses - `true` for anything built on the alternate screen, `false` for `top`, and getting
     * that wrong in either direction is a bug in the emulator's handling of `1049` rather than a detail.
     * And while a program owns the alternate screen, the layout has to hand back one visual row per grid
     * row: those rows are positional, the program drew its own borders and columns to the width it was
     * told it had, and reflowing row 3 into two rows moves everything below it. The rows here are wider
     * than [NARROW_COLUMNS] - checked, not assumed, because an assertion that nothing was wrapped proves
     * nothing about a screen with nothing wide enough to wrap.
     *
     * Then the program is quit and the shell has to answer for itself, which is the other half of the
     * claim: a full-screen program is something a session comes back from.
     */
    private fun runFullScreenProgram(
        hostId: String,
        command: String,
        paints: String,
        alternateScreen: Boolean,
        quit: () -> Unit,
    ) {
        val viewModel = viewModel()
        val program = command.substringAfterLast('/').substringBefore(' ')
        compose.runOnUiThread { viewModel.sendText(hostId, command) }
        compose.runOnUiThread { viewModel.sendKey(hostId, TerminalKey.ENTER) }
        pumpUntil(describe = { "`$command` never painted \"$paints\". " + diagnose(hostId) }) {
            val frame = frame(hostId)
            drawn(hostId).contains(paints) &&
                frame.alternateScreen == alternateScreen &&
                (frame.alternateScreen || frame.positionalScreen)
        }

        val painted = frame(hostId)
        assertThat(painted.alternateScreen).isEqualTo(alternateScreen)
        val widest = visualRows(painted, painted.columns).maxOf { it.trimEnd().length }
        check(widest > NARROW_COLUMNS) {
            "$program painted nothing wider than the $NARROW_COLUMNS-column view, so this proves " +
                "nothing about wrapping: widest row is $widest columns. " + diagnose(hostId)
        }
        // Whichever way the program took the screen, the display leaves it alone: one visual row per grid
        // row, every one of them starting at column zero, in a view narrower than the pty. Not gated on
        // the alternate screen, because `top` never asks for one - it homes the cursor and repaints the
        // primary screen, and until the emulator marked that, this narrow layout reflowed every row of
        // it in the configuration a phone actually ships with.
        assertThat(painted.alternateScreen || painted.positionalScreen).isTrue()
        val layout = layoutOf(painted, NARROW_COLUMNS)
        assertThat(layout.rows.map { it.line }).isEqualTo(painted.lines.indices.toList())
        assertThat(layout.rows.filter { it.from != 0 }).isEmpty()

        quit()
        val marker = "$AFTER_MARKER_PREFIX-$program"
        // The shell answering and *both* marks coming off, in one wait. Both, because a mark that stuck
        // would leave word wrapping off for the rest of the session, and `top` is the program that would
        // do it - it is on the primary screen the whole time, so nothing switches back on its behalf and
        // only flowing output releases it. Asked for after the echo rather than before it precisely
        // because that output is the release: a restored screen is a full one, so the answer scrolls it.
        //
        // Typed again on every retry rather than once, because a program that is exiting can eat what is
        // typed at it - and one of these does. Probed on this machine through a real pty, with `q` and
        // the command written as one chunk: `less` runs the command, `top` never sees it. procps restores
        // the terminal it borrowed with a flush, so input already in the buffer is discarded, and how
        // much of it is there depends on how long procps takes to get out - which on a host sharing two
        // cores is long enough. Retrying is what a person does when a keystroke vanishes into a
        // program's exit, and it weakens nothing: the shell still has to run the command, and both marks
        // still have to come off before this returns.
        pumpUntilTyping(
            describe = { "$program did not hand the screen back to a shell. " + diagnose(hostId) },
            type = {
                compose.runOnUiThread { viewModel.sendText(hostId, "echo $marker") }
                compose.runOnUiThread { viewModel.sendKey(hostId, TerminalKey.ENTER) }
            },
        ) {
            drawn(hostId).lineSequence().any { it.trimEnd() == marker } &&
                frame(hostId).let { !it.alternateScreen && !it.positionalScreen }
        }
    }

    /**
     * Types `echo [typed]`, waits for [token] to be printed, and checks [whole] survived the display.
     *
     * [whole] defaults to [token] itself, which is the case for anything that is one token - a URL, a
     * path, a digest. A line made of several tokens passes its own pieces instead, because the display is
     * allowed to break such a line and only promises not to break through what it is made of.
     */
    private fun echoAndAssertWhole(
        hostId: String,
        token: String,
        typed: String = "echo $token",
        whole: List<String> = listOf(token),
    ) {
        val viewModel = viewModel()
        compose.runOnUiThread { viewModel.sendText(hostId, typed) }
        compose.runOnUiThread { viewModel.sendKey(hostId, TerminalKey.ENTER) }
        // Waits for the line the shell *printed* - a row that is nothing but the token - and not for two
        // copies of the token, which is what this asked for first and is not something a terminal owes
        // it. The echoed copy of what was typed is preceded by the prompt, and prompt plus command is
        // longer than the pty is wide, so the pty hard-wraps the echo and the token arrives split across
        // two grid rows. That split belongs to the terminal, at the pty's width, and is not the wrap
        // layer's to undo; the row the shell printed starts at column zero and is what this is about.
        pumpUntil(describe = { "the shell never printed $token on a line of its own. " + diagnose(hostId) }) {
            drawn(hostId).lineSequence().any { it.trimEnd() == token }
        }
        assertWholeOnOneVisualRow(hostId, token, whole)
    }

    /**
     * Asserts every piece of [whole] survives on one visual row of the line the server printed [token] on,
     * in a [NARROW_COLUMNS]-wide view that is demonstrably wrapping.
     *
     * Anchored on that one line rather than on the screen, because the screen holds a second, broken copy
     * of every token here: the command was echoed by the pty at its own eighty columns, prompt included,
     * and that copy is not the display's doing and not what is being asked about. The last assertion is
     * what stops the rest from being vacuous - a layout that wrapped nothing at all would keep every token
     * whole and would also be the bug this feature exists to fix.
     */
    private fun assertWholeOnOneVisualRow(hostId: String, token: String, whole: List<String> = listOf(token)) {
        val painted = frame(hostId)
        val printed = painted.lines.indices.firstOrNull { gridText(painted, it).contains(token) }
        checkNotNull(printed) { "the server's own copy of $token left the screen. " + diagnose(hostId) }
        val layout = layoutOf(painted, NARROW_COLUMNS)
        val rows = layout.rows.filter { it.line == printed }.map { textOf(painted, it) }
        for (piece in whole) {
            assertWithMessage(
                "the $NARROW_COLUMNS-column display broke $piece apart; the line it printed lays out as\n" +
                    rows.joinToString(separator = "\n") { "|$it|" },
            ).that(rows.filter { it.contains(piece) }).isNotEmpty()
        }
        assertThat(layout.rows.groupBy { it.line }.values.filter { it.size > 1 }).isNotEmpty()
    }

    /**
     * Every row [frame] lays out at [width], however many that is.
     *
     * Deliberately not the view's row budget. Wrapping turns twelve grid rows into about twenty at this
     * width and the layout is bottom-anchored, exactly as a terminal should be - so asking for twelve
     * back drops the earliest of them, and which ones survive depends on where the shell happened to be
     * when the frame was sampled. That made a flake out of an assertion that is not about scrolling: the
     * question here is whether the display *breaks* a token, not whether that token is on screen this
     * frame.
     */
    private fun layoutOf(frame: TerminalFrame, width: Int): TerminalLayout =
        terminalLayout(frame, width = width, maxRows = Int.MAX_VALUE)

    /** The frame as the view would lay it out at [width] columns, one string per drawn row. */
    private fun visualRows(frame: TerminalFrame, width: Int): List<String> =
        layoutOf(frame, width).rows.map { textOf(frame, it) }

    /** The characters [row] paints, which is the substring of its line the view would draw. */
    private fun textOf(frame: TerminalFrame, row: TerminalVisualRow): String {
        val line = frame.lines[row.line]
        val to = row.to.coerceAtMost(line.size)
        return if (row.from >= to) "" else line.subList(row.from, to).joinToString("") { it.value.toString() }
    }

    /** Line [index] of [frame] as one string, trailing blanks and all. */
    private fun gridText(frame: TerminalFrame, index: Int): String =
        frame.lines[index].joinToString(separator = "") { it.value.toString() }

    private fun frame(hostId: String): TerminalFrame =
        viewModel().frames.value[hostId] ?: TerminalFrame.EMPTY

    /**
     * The absolute path of [program] on this machine, or null when the image does not have it.
     *
     * The sandbox is this machine, so the JVM's own PATH is a fair place to look - and the command is
     * built from the absolute path it finds, so what the login shell has in *its* PATH cannot change
     * which binary runs or make the test fail for a reason that has nothing to do with the app.
     */
    private fun onPath(program: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, program) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    /** Idles the looper for [ms] without waiting for anything in particular. */
    private fun pumpFor(ms: Long) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    private fun pumpUntil(timeoutMs: Long = SSH_TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        sampleStates()
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            sampleStates()
        }
        sampleStates()
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * [pumpUntil], for a wait whose input can be lost rather than merely late.
     *
     * [type] runs immediately and then again every [retryMs] until [condition] holds. That is for the
     * one case in this suite where sending once is not enough: a program that is exiting can discard
     * what has already been typed at it, so the first send can land in a buffer nobody will read.
     * Only the sending repeats - the condition is whatever the caller asked for, and still has to be
     * satisfied by the server.
     */
    private fun pumpUntilTyping(
        timeoutMs: Long = SSH_TIMEOUT_MS,
        retryMs: Long = RETYPE_MS,
        describe: () -> String,
        type: () -> Unit,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var nextType = System.nanoTime()
        sampleStates()
        while (System.nanoTime() < deadline && !condition()) {
            if (System.nanoTime() >= nextType) {
                type()
                nextType = System.nanoTime() + retryMs * 1_000_000
            }
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            sampleStates()
        }
        sampleStates()
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * Every state each tab has been seen in, in order, with repeats collapsed.
     *
     * The audit this suite belongs to is about a state the app passes *through*: connected, banner,
     * "Reconnecting…", back. Sampling only at the end of a wait cannot see that - by then the app is
     * connected again and the tab looks the way it did before. Every pump iteration adds to this trace,
     * so a flip that lasted a frame is still on the record when the assertion runs.
     */
    private val statesSeen = mutableMapOf<String, MutableList<SessionConnectionState>>()

    /**
     * Drops what the trace holds for a host, called the moment its key is trusted.
     *
     * The first attempt against this sandbox always fails: the server's key has never been seen, so the
     * app stops and asks. That attempt correctly reports ERROR - and correctly does *not* report
     * RECONNECTING, which is the fault this suite is about - but it is a failed attempt all the same,
     * and it belongs to a question the user has now answered rather than to the session under audit.
     * Every test here meets it: known hosts live under the app's data directory, and Robolectric hands
     * each test method its own, so the trust one test establishes is not there for the next.
     */
    private fun restartStateTrace(hostId: String) {
        statesSeen.remove(hostId)
        // `acceptHostKey` dials again from the calling thread but the dial itself is a coroutine, so the
        // tab still reports the failed attempt's state for now. Remember it, and ignore it until it
        // changes, or the next sample would put back exactly what this call just removed.
        //
        // That covers the answer arriving after the failure was written. The other order - the question
        // published, answered, and the failure written afterwards, onto the dial the answer started - is
        // not a harness problem and is not papered over here: the app refuses the write, because an
        // attempt the user has replaced does not get to report. See `MainViewModel.dialGenerations`.
        tabFor(hostId)?.let { traceHoldover[it.hostId] = it.state }
    }

    private val traceHoldover = mutableMapOf<String, SessionConnectionState>()

    private fun sampleStates() {
        viewModel().uiState.value.tabs.forEach { tab ->
            if (traceHoldover[tab.hostId] == tab.state) return@forEach
            traceHoldover -= tab.hostId
            val seen = statesSeen.getOrPut(tab.hostId) { mutableListOf() }
            if (seen.lastOrNull() != tab.state) seen += tab.state
        }
    }

    /**
     * Asserts the tab only ever moved forwards through the state machine and is connected now.
     *
     * This is the audit's central claim, written as three assertions: the session is up, it was never
     * reported as dropping, and - because the states are declared in lifecycle order - it never went
     * backwards. The third is what catches the reported fault specifically: connected, then
     * RECONNECTING, then connected again is a *decrease* in that order, and it stays in the trace even
     * though the end state is the same as the start.
     */
    private fun assertNothingLookedLikeADrop(hostId: String) {
        val seen = statesSeen[hostId].orEmpty()
        // Every one of these carries the trace, because the sequence alone says a step was taken
        // backwards and nothing about which attempt took it. That cost a CI round trip once.
        val why = { "states=$seen " + diagnose(hostId) }
        assertWithMessage(why()).that(seen).isNotEmpty()
        assertWithMessage(why()).that(seen).containsNoneOf(
            SessionConnectionState.RECONNECTING,
            SessionConnectionState.DISCONNECTED,
            SessionConnectionState.ERROR,
        )
        assertWithMessage(why()).that(seen.map { it.ordinal }).isInStrictOrder()
        assertWithMessage(why()).that(seen.last()).isEqualTo(SessionConnectionState.CONNECTED)
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

        /** Wide enough that a phone-width view has to wrap it, narrow enough for an 80-column pty. */
        const val PAGER_MARKER = "eclipse-pager"
        const val PAGER_LINES = 40

        /** Roughly what a phone fits of legible monospace, and what the view is when the pty is 80. */
        const val NARROW_COLUMNS = 46

        const val AFTER_MARKER_PREFIX = "eclipse-after"

        /**
         * How long [pumpUntilTyping] waits before typing again. Long enough that a shell which did get
         * the command is answering, not being interrupted; short enough that several attempts fit in
         * [SSH_TIMEOUT_MS] on a host where a program can take seconds to finish exiting.
         */
        const val RETYPE_MS = 3_000L

        /** A digest as `sha256sum` prints one. */
        val SHA256 = Regex("[0-9a-f]{64}")

        /** How long the close futures get after the last tab has gone. */
        const val CLOSE_SETTLE_MS = 500L

        /**
         * How long a socket this test should have closed may take to leave the kernel's table before
         * that counts as a leak. Far longer than any real close (the I/O thread finishes one in
         * milliseconds) and far shorter than the heartbeat interval a leaked session would be
         * discovered by, which is the alternative this replaces.
         */
        const val LEAK_SETTLE_MS = 5_000L

        /**
         * Long enough for a keystroke aimed at a dead session to have reached a server if it could.
         *
         * A round trip on loopback is under a millisecond, so this is generous by three orders of
         * magnitude - which is the point: the assertion is that nothing arrives, and a wait too short to
         * have carried anything would prove nothing at all.
         */
        const val DEAD_TYPING_MS = 1_000L

        /** Each compression case echoes its own marker, so a stale frame cannot satisfy it. */
        const val COMPRESSION_MARKER = "eclipse-compressed"

        /** Likewise for the forwarding cases. */
        const val FORWARD_MARKER = "eclipse-forwarded"

        /**
         * How long a read through the tunnel may take before the tunnel is the failure.
         *
         * Generous for a loopback socket on purpose: it crosses the app's acceptor, a channel on a real
         * session, and the server's own connector, on a runner sharing two cores with the build.
         */
        const val TUNNEL_TIMEOUT_MS = 15_000L

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

        /** sshd's own record of which of the sandbox's three servers a connection arrived at. */
        val ARRIVED = Regex("""Connection from \S+ port (\d+) on \S+ port (\d+)""")

        /** The accepted login, naming the client port [ARRIVED] attributed to a server. */
        val ACCEPTED = Regex("""Accepted publickey for \S+ from \S+ port (\d+)""")

        const val SILENT_SERVER_MARKER = "eclipse-outlived-the-silence"

        /**
         * The unit the idle matrix is written in, so a hold reads as the duration a report describes.
         *
         * The durations themselves are at the call sites: 30 s, 1, 5, 10 and 30 minutes. The shipped
         * thirty-second keep-alive puts the app's own idle deadline at `30 * 3 + 60` = 150 seconds, so
         * every hold from five minutes up crosses it repeatedly - which is the point.
         */
        const val MINUTE_MS = 60_000L

        /** Each hold echoes its own marker, so a stale frame from another test cannot satisfy it. */
        const val IDLE_MARKER_PREFIX = "eclipse-outlived"

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

        /**
         * A loopback port nothing is listening on, for a saved forward to claim.
         *
         * Asked of the kernel and then released, rather than picked out of the air: a hard-coded port
         * is a test that fails on whichever machine already runs something there, and the window
         * between releasing this one and the app binding it is a fraction of a second on a loopback
         * interface nothing else in this sandbox is competing for.
         */
        fun freePort(): Int = ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(LOOPBACK, 0))
            socket.localPort
        }
    }
}
