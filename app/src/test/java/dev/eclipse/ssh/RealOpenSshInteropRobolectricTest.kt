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

    // ---------------------------------------------------------------- harness

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

        /** Coarse on purpose: this suite shares two cores, and a spin loop here would take one. */
        const val IDLE_STEP_MS = 100L

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
