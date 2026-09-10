package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardRuntime
import dev.eclipse.ssh.data.model.ForwardStatus
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import dev.eclipse.ssh.ssh.SshSessionStore
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.apache.sshd.common.channel.RequestHandler
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.ConnectionServiceRequestHandler
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
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
 * Port forwarding end to end: a host's saved rules, started by the engine that a connect sets running,
 * stopped and restarted by hand, edited in place, isolated per host, and carried across a reconnect -
 * every one of them measured by whether bytes actually travel, because "the row says Running" is the
 * app's opinion and a tunnel that cannot carry a payload is a list entry, not a tunnel.
 *
 * The three kinds are verified the only way each can be:
 *
 *  - **LOCAL** - the test JVM listens with a plain echo [ServerSocket], the rule points at it, and a
 *    socket dialled on the rule's bound port has to get its own bytes back through the phone, the
 *    server and back.
 *  - **DYNAMIC** - a real SOCKS client is not needed: writing the SOCKS5 greeting
 *    (`05 01 00`, "no auth please") to the bound port and reading the two-byte answer is proof a
 *    SOCKS listener answered, which is proof the proxy came up.
 *  - **REMOTE** - the *server* holds the listening end, so the test connects to the server's port
 *    (the embedded server runs in this JVM, so that is a loopback dial), the server opens the channel,
 *    the phone dials the rule's destination - the echo server again - and the round trip closes.
 *
 * The whole engine's governing rule is asserted here too, in the failure tests: **a forward that fails
 * may not cost the user their shell.** A taken port and a server that refuses `tcpip-forward` are
 * facts about one listening socket, and the session beside them stays CONNECTED with the reason on
 * `forwardError`, not on the status line, and no reconnect armed - the loop this release exists to
 * stop.
 *
 * One working embedded server, password auth only (so `authAttempts` is an exact count - see
 * `ConnectionMatrixRobolectricTest` for why the factory list matters), a counting shell, and a global
 * request handler in front of MINA's own that can be told to refuse `tcpip-forward` - the one way to
 * make a real server say "no" to a remote forward. No sandbox and no external account: everything
 * below lives and dies inside this JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PortForwardingRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetServerCounters() {
        authAttempts.set(0)
        shellsStarted.set(0)
        forwardGate.denied = false
    }

    /**
     * Ends every session this test opened, before the next one starts - for the same reason as
     * `ConnectionMatrixRobolectricTest.endEverySession`: a live session is a GC root, and a suite of
     * them on a two-core JVM starts missing its own deadlines. Also puts the forward gate back to
     * allowing, so a test that denied `tcpip-forward` cannot poison the next one.
     */
    @After
    fun endEverySession() {
        forwardGate.denied = false
        runCatching { compose.runOnUiThread { viewModel().disconnectAll() } }
    }

    // ---------------------------------------------------------------- the three kinds, carrying traffic

    /**
     * One host, one of each kind of rule, all enabled with auto-start: the connect brings every one
     * of them up, the tab reports the full ratio, and - the assertion this suite exists for - each
     * tunnel actually carries traffic rather than merely owning a row that says Running.
     *
     * The remote half deserves its why: a `-R` rule makes the *server* listen, and when the test
     * dials the server's port the *phone* dials the rule's destination. Both ends are in this JVM
     * here, but the path is still the full one - test socket, server accept, channel over the
     * transport, phone-side dial, echo server, and back.
     */
    @Test
    fun `enabled auto-start rules of all three kinds come up on connect and carry traffic`() {
        val lEcho = EchoServer()
        val rEcho = EchoServer()
        try {
            val lPort = freePort()
            val dPort = freePort()
            val rPort = freePort()
            val host = saveHostWithRules(
                """
                L:$lPort:${LOOPBACK}:${lEcho.port}
                D:$dPort
                R:$rPort:${rEcho.port}
                """.trimIndent(),
            )
            connectSaved(host.id, password = PASSWORD)

            // Size first, then state: an empty map passes "all running" vacuously, and the rows are
            // written a moment after the tab says CONNECTED. The tab ratio is waited on too, because
            // the counters are refreshed after the last row lands.
            pumpUntil(describe = { "the three rules never came up: " + diagnose(host.id) }) {
                val states = statusesOf(host.id)
                states.size == 3 && states.values.all { it.state == ForwardRuntime.RUNNING } &&
                    tabFor(host.id)?.let { it.forwardsOpen == 3 && it.forwardsTotal == 3 } == true
            }
            // 3/3 is the ratio the tab renders; anything less means a rule was left behind silently.
            val tab = checkNotNull(tabFor(host.id))
            assertThat(tab.forwardsOpen).isEqualTo(3)
            assertThat(tab.forwardsTotal).isEqualTo(3)
            assertThat(tab.forwardError).isNull()

            // Local: the phone's port tunnels to the echo server the rule names.
            assertThat(echoThrough(lPort, "through-local")).isEqualTo("through-local")
            // Dynamic: a SOCKS5 listener answers the greeting with "no auth required" (05 00).
            assertThat(socks5GreetingAnswer(dPort)).isTrue()
            // Remote: dial the *server's* port; the payload comes back through the phone's dial-out.
            assertThat(echoThrough(rPort, "through-remote")).isEqualTo("through-remote")
        } finally {
            runCatching { lEcho.close() }
            runCatching { rEcho.close() }
        }
    }

    /**
     * Several rules at once - two locals to different destinations and a SOCKS proxy - stay separate:
     * each has its own port, its own tunnel, and the payload written to one comes back from that one
     * and no other. The failure shape this guards against is a starter that binds one port for every
     * rule, or a list keyed by something coarser than the rule.
     */
    @Test
    fun `several rules on one host each get their own tunnel`() {
        val firstEcho = EchoServer()
        val secondEcho = EchoServer()
        try {
            val firstPort = freePort()
            val secondPort = freePort()
            val socksPort = freePort()
            val host = saveHostWithRules(
                """
                L:$firstPort:${LOOPBACK}:${firstEcho.port}
                L:$secondPort:${LOOPBACK}:${secondEcho.port}
                D:$socksPort
                """.trimIndent(),
            )
            connectSaved(host.id, password = PASSWORD)

            // Size first, then state - an empty map passes "all running" vacuously, and the tab's
            // counters are refreshed after the last row lands.
            pumpUntil(describe = { "the three rules never came up: " + diagnose(host.id) }) {
                val states = statusesOf(host.id)
                states.size == 3 && states.values.all { it.state == ForwardRuntime.RUNNING } &&
                    tabFor(host.id)?.let { it.forwardsOpen == 3 && it.forwardsTotal == 3 } == true
            }
            val tab = checkNotNull(tabFor(host.id))
            assertThat(tab.forwardsOpen).isEqualTo(3)
            assertThat(tab.forwardsTotal).isEqualTo(3)

            // Whose payload comes back decides whose tunnel answered: the two locals have to reach
            // their own echo servers, not each other's and not a shared socket.
            assertThat(echoThrough(firstPort, "first")).isEqualTo("first")
            assertThat(echoThrough(secondPort, "second")).isEqualTo("second")
            assertThat(socks5GreetingAnswer(socksPort)).isTrue()
        } finally {
            runCatching { firstEcho.close() }
            runCatching { secondEcho.close() }
        }
    }

    // ---------------------------------------------------------------- the flags and the hands-on paths

    /**
     * The two flags are a standing instruction about what a connect may start on its own: a disabled
     * rule is listed as Disabled and never binds, and an enabled rule without auto-start is listed as
     * Stopped and equally unbound - it is the sheet's Start button, not a connect's business.
     *
     * The bind is what is asserted, not the row alone: the port a rule *would* have taken is still
     * free while the session is up, which is the difference between "not started" and "started and
     * then quietly dropped from the list".
     */
    @Test
    fun `a disabled rule and a manual rule are listed but never bound by a connect`() {
        val echo = EchoServer()
        try {
            val disabledPort = freePort()
            val manualPort = freePort()
            val host = saveHostWithRules(
                """
                #L:$disabledPort:${LOOPBACK}:${echo.port}
                -L:$manualPort:${LOOPBACK}:${echo.port}
                """.trimIndent(),
            )
            connectSaved(host.id, password = PASSWORD)

            val rules = savedRules(host)
            // Pumped, not read on the spot: the rows are written a little after the tab says
            // CONNECTED (the connect path saves the host's lastConnectedAt on the way), so an
            // immediate read races the bookkeeping rather than the feature.
            pumpUntil(describe = { "the two rules were never listed: " + diagnose(host.id) }) {
                val states = statusesOf(host.id)
                states[rules[0].id]?.state == ForwardRuntime.DISABLED &&
                    states[rules[1].id]?.state == ForwardRuntime.STOPPED &&
                    // The counters land a dispatch behind the rows, so they are waited on too.
                    tabFor(host.id)?.forwardsTotal == 1 && tabFor(host.id)?.forwardsOpen == 0
            }
            // Neither port was taken: both are still bindable by anyone else while the shell is up.
            assertThat(portIsFree(disabledPort)).isTrue()
            assertThat(portIsFree(manualPort)).isTrue()
            // The disabled rule does not count in the total; the manual one does, because it is a
            // real tunnel this host describes that merely has not been started yet.
            val tab = checkNotNull(tabFor(host.id))
            assertThat(tab.forwardsOpen).isEqualTo(0)
            assertThat(tab.forwardsTotal).isEqualTo(1)
        } finally {
            runCatching { echo.close() }
        }
    }

    /**
     * Start and Stop, by hand, from the forwarding sheet: Start brings up a rule the connect left
     * alone (the manual one), Stop takes a running tunnel down and *releases its port* - asserted by
     * binding the port back, because a released forward that still holds its listening socket is the
     * failure that would make the next Start of the same rule report a conflict with itself.
     */
    @Test
    fun `startForwardRule starts a stopped rule and stopForwardRule releases its port`() {
        val echo = EchoServer()
        try {
            val port = freePort()
            val host = saveHostWithRules("-L:$port:${LOOPBACK}:${echo.port}")
            connectSaved(host.id, password = PASSWORD)
            val ruleId = savedRules(host).single().id
            // Pumped for the same reason as the flags test: the row arrives just behind CONNECTED.
            pumpUntil(describe = { "the manual rule was never listed: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.STOPPED
            }

            // A hand start outranks the flags: this rule is enabled-but-manual, and Start runs it.
            compose.runOnUiThread { viewModel().startForwardRule(host.id, ruleId) }
            pumpUntil(describe = { "the manual rule never started: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.RUNNING &&
                    tabFor(host.id)?.forwardsOpen == 1
            }
            assertThat(echoThrough(port, "by-hand")).isEqualTo("by-hand")

            compose.runOnUiThread { viewModel().stopForwardRule(ruleId) }
            pumpUntil(describe = { "the rule never stopped: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.STOPPED &&
                    tabFor(host.id)?.forwardsOpen == 0
            }
            // The close happens off the main thread (see releaseForwards), so the port is *expected*
            // to be free a moment later rather than this instant - hence the wait.
            pumpUntil(describe = { "the stopped rule's port was never released" }) { portIsFree(port) }
            assertThat(checkNotNull(tabFor(host.id)).forwardsOpen).isEqualTo(0)
        } finally {
            runCatching { echo.close() }
        }
    }

    // ---------------------------------------------------------------- failure shapes

    /**
     * Two saved rules wanting the same `listenHost:port`: the first comes up, the second is Failed
     * with the one fixed sentence the spec mandates - "Port N is already in use by another forwarding
     * rule." - and the reason reaches the tab as a forward error, not as a session error.
     *
     * The fixed wording matters beyond readability: the forwarding sheet matches on it to offer "edit
     * the conflicting rule", so a message that varied with the loser's position or the OS's mood
     * would break the one action the user can take from the failure.
     */
    @Test
    fun `two rules on one port leave the first running and the second failed with the fixed sentence`() {
        val echo = EchoServer()
        try {
            val port = freePort()
            val host = saveHostWithRules(
                """
                L:$port:${LOOPBACK}:${echo.port}
                L:$port:${LOOPBACK}:${echo.port}
                """.trimIndent(),
            )
            connectSaved(host.id, password = PASSWORD)

            val rules = savedRules(host)
            pumpUntil(describe = { "the conflict never settled: " + diagnose(host.id) }) {
                val states = statusesOf(host.id)
                states[rules[0].id]?.state == ForwardRuntime.RUNNING &&
                    states[rules[1].id]?.state == ForwardRuntime.FAILED &&
                    // The tab's counters and aggregate error land after the batch, so they are part
                    // of what is waited for rather than asserted cold.
                    tabFor(host.id)?.let { it.forwardsOpen == 1 && it.forwardsTotal == 2 } == true &&
                    tabFor(host.id)?.forwardError?.contains("already in use") == true
            }
            val statuses = statusesOf(host.id)
            // The exact sentence, verbatim: the sheet's conflict action keys on it.
            assertThat(statuses[rules[1].id]?.error)
                .isEqualTo("Port $port is already in use by another forwarding rule.")
            // The winner is unaffected by the loser's failure, and still carries traffic.
            assertThat(echoThrough(port, "winner")).isEqualTo("winner")
            val tab = checkNotNull(tabFor(host.id))
            assertThat(tab.forwardsOpen).isEqualTo(1)
            assertThat(tab.forwardsTotal).isEqualTo(2)
            assertThat(tab.forwardError).contains("Port $port is already in use by another forwarding rule.")
            // And above all: the session survived its rule's bad day.
            assertThat(tab.state).isEqualTo(SessionConnectionState.CONNECTED)
        } finally {
            runCatching { echo.close() }
        }
    }

    /**
     * A server that refuses `tcpip-forward` - what `AllowTcpForwarding no` looks like from the phone.
     *
     * The refusal is made by a real global request handler in front of the server's own, replying
     * failure to the actual request name, so the client's `createRemotePortForwardingTracker` gets a
     * genuine refusal from a genuine server rather than an exception faked inside the app.
     *
     * The two assertions that matter most are the negative ones: the rule is FAILED *with the
     * server's own reason surfaced*, and the session beside it stays CONNECTED, still typeable, with
     * no reconnect armed - a forward that fails is one row's bad day (see `startSavedForwards`), and
     * answering it by tearing down the shell is the loop this release exists to stop.
     */
    @Test
    fun `a server that denies tcpip-forward fails the remote rule and keeps the session alive`() {
        forwardGate.denied = true
        val echo = EchoServer()
        try {
            val rPort = freePort()
            val host = saveHostWithRules("R:$rPort:${echo.port}")
            connectSaved(host.id, password = PASSWORD)
            val ruleId = savedRules(host).single().id

            pumpUntil(describe = { "the refused rule never settled: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.FAILED &&
                    // The aggregate error reaches the tab after the batch, so it is waited on too.
                    tabFor(host.id)?.forwardError != null
            }
            // MINA's own sentence for a refused request, passed through untouched rather than
            // paraphrased into something that names neither the port nor the server.
            assertThat(statusesOf(host.id)[ruleId]?.error).contains("denied")
            val tab = checkNotNull(tabFor(host.id))
            assertThat(tab.forwardError).isNotNull()
            // The shell survived: the tab never left CONNECTED and the tunnel-less session is still
            // an interactive terminal, which is proven by an echo coming back through it.
            assertThat(tab.state).isEqualTo(SessionConnectionState.CONNECTED)
            compose.runOnUiThread { viewModel().sendInput(host.id, "still-here\n") }
            pumpUntil(describe = { "the shell stopped echoing after a forward failure" }) {
                viewModel().uiState.value.terminalOutput[host.id]?.contains("still-here") == true
            }
            assertThat(tabFor(host.id)?.state).isEqualTo(SessionConnectionState.CONNECTED)
        } finally {
            runCatching { echo.close() }
        }
    }

    // ---------------------------------------------------------------- across a reconnect

    /**
     * The transport drops mid-session, the reconnect ladder brings the host back, and every saved
     * auto-start rule is rebound - *once each*, with no duplicate handle behind any rule, because the
     * old transport's trackers must not be double-counted against the new one's.
     *
     * The hand-opened SOCKS proxy is the other half: it is the user's own, the reconnect never
     * resurrects it, and after the session comes back its row reads Stopped rather than pretending a
     * tunnel nothing rebound is still up.
     *
     * Duplicates are asserted on the engine's own books rather than the UI's: the live handle map
     * (read by reflection, the same choice `ConnectionMatrixRobolectricTest` explains) must hold
     * exactly the saved rules' ids, and the running list must contain each id exactly once.
     */
    @Test
    fun `a reconnect rebinds every saved rule exactly once and does not resurrect a hand-opened forward`() {
        val echo = EchoServer()
        try {
            val lPort = freePort()
            val socksPort = freePort()
            val host = saveHostWithRules(
                "L:$lPort:${LOOPBACK}:${echo.port}",
                credentials = HostCredentialUpdate(password = SecretEdit.Replace(PASSWORD)),
            )
            awaitCredentialSaved(host.id)
            connectSaved(host.id, password = null)
            val ruleId = savedRules(host).single().id
            pumpUntil(describe = { "the saved rule never came up: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.RUNNING
            }

            // A forward the user opened themselves, on no rule at all.
            compose.runOnUiThread { viewModel().startDynamicForward(currentHost(host.id), socksPort) }
            val handId = pumpForForwardOn(socksPort)
            val viewModel = viewModel()
            val store = injected(viewModel, "sessionStore", SshSessionStore::class.java)
            // Two tunnels up - the rule's and the hand-opened one - against one saved rule.
            pumpUntil(describe = { "the hand-opened forward never counted: " + diagnose(host.id) }) {
                tabFor(host.id)?.let { it.forwardsOpen == 2 && it.forwardsTotal == 1 } == true
            }

            // The drop, not through the store, so it reaches the app as an outage rather than as a
            // deliberate end - which is what arms the ladder.
            checkNotNull(store.liveSession(host.id)) { "the session never reached the store" }.close(true)
            pumpUntil(describe = { "the drop never reached the tab: " + diagnose(host.id) }) {
                tabFor(host.id)?.state == SessionConnectionState.RECONNECTING
            }
            // While the ladder waits, the saved rule says Reconnecting - a promise the reconnect will
            // keep - and the hand-opened one says Stopped, because nothing will bring it back. Pumped
            // rather than read on the spot: both are *derived* states, recomputed a dispatch after the
            // tab state that drives them.
            pumpUntil(describe = { "the dropped rules never showed their derived states: " + diagnose(host.id) }) {
                val states = statusesOf(host.id)
                states[ruleId]?.state == ForwardRuntime.RECONNECTING &&
                    states[handId]?.state == ForwardRuntime.STOPPED
            }

            // The ladder's own redial: the vaulted password is what it authenticates with.
            pumpUntil(timeoutMs = RECONNECT_TIMEOUT_MS, describe = { "the ladder never came back: " + diagnose(host.id) }) {
                tabFor(host.id)?.state == SessionConnectionState.CONNECTED
            }
            pumpUntil(describe = { "the saved rule was not rebound: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.RUNNING &&
                    tabFor(host.id)?.let { it.forwardsOpen == 1 && it.forwardsTotal == 1 } == true
            }

            // Exactly one handle per saved rule, the hand-opened forward absent, and the running
            // list free of duplicate ids - a reconnect that left the dead transport's tracker behind
            // would answer with two entries for one port.
            val running = viewModel.uiState.value.forwardings.filter { it.hostId == host.id }
            assertThat(running.map(ForwardEntry::id)).containsNoDuplicates()
            assertThat(running.map(ForwardEntry::id)).containsExactly(ruleId)
            val handles = injected(viewModel, "forwardHandles", ConcurrentHashMap::class.java)
            assertThat(handles).containsKey(ruleId)
            assertThat(handles).doesNotContainKey(handId)
            val tab = checkNotNull(tabFor(host.id))
            assertThat(tab.forwardsOpen).isEqualTo(1)
            assertThat(tab.forwardsTotal).isEqualTo(1)
            // And the rebound tunnel carries traffic, which is the difference between a rebind and a
            // stale row over a dead tracker.
            assertThat(echoThrough(lPort, "after-reconnect")).isEqualTo("after-reconnect")
            // The hand-opened proxy's port is dead: nothing listens on it any more.
            assertThat(socks5GreetingAnswer(socksPort)).isFalse()
        } finally {
            runCatching { echo.close() }
        }
    }

    // ---------------------------------------------------------------- isolation

    /**
     * Forwards belong to their host. Connecting a second host - one with no rules of its own - starts
     * nothing on it, lists nothing from the first host's rules on it, and ending that second host's
     * session entirely does not touch the first host's tunnels, which are still carrying traffic
     * after the other host is gone.
     */
    @Test
    fun `a second host's session neither starts nor disturps the first host's forwards`() {
        val echo = EchoServer()
        try {
            val port = freePort()
            val a = saveHostWithRules("L:$port:${LOOPBACK}:${echo.port}")
            connectSaved(a.id, password = PASSWORD)
            val ruleId = savedRules(a).single().id
            pumpUntil(describe = { "the rule never came up: " + diagnose(a.id) }) {
                statusesOf(a.id)[ruleId]?.state == ForwardRuntime.RUNNING
            }

            // B: same server, no rules of any kind.
            val b = saveHostWithRules("")
            connectSaved(b.id, password = PASSWORD)

            // B's tab claims nothing - zero of zero - and no row anywhere belongs to B.
            val bTab = checkNotNull(tabFor(b.id))
            assertThat(bTab.forwardsOpen).isEqualTo(0)
            assertThat(bTab.forwardsTotal).isEqualTo(0)
            assertThat(statusesOf(b.id)).isEmpty()
            assertThat(viewModel().uiState.value.forwardings.none { it.hostId == b.id }).isTrue()
            // And A's rule is untouched by B's arrival.
            assertThat(statusesOf(a.id)[ruleId]?.state).isEqualTo(ForwardRuntime.RUNNING)

            // The whole of B, ended: A's tunnel has to still be up and still be carrying.
            compose.runOnUiThread { viewModel().closeTab(checkNotNull(tabFor(b.id))) }
            pumpUntil(describe = { "B's tab never closed" }) { tabFor(b.id) == null }
            assertThat(statusesOf(a.id)[ruleId]?.state).isEqualTo(ForwardRuntime.RUNNING)
            assertThat(echoThrough(port, "a-survives-b")).isEqualTo("a-survives-b")
            assertThat(checkNotNull(tabFor(a.id)).forwardsOpen).isEqualTo(1)
        } finally {
            runCatching { echo.close() }
        }
    }

    // ---------------------------------------------------------------- editing the rules

    /**
     * [MainViewModel.saveForwardRules] applies an edit to the live session without a reconnect: a
     * changed rule is a new id (ids derive from the rule's text), so the old tunnel is released, the
     * new one is bound, and the port the rule used to hold is given back. A deleted rule takes its
     * row with it, and a rule switched off is shown Disabled and unbound.
     */
    @Test
    fun `editing the saved rules rebinds the changed rule, drops the deleted one and disables the toggled one`() {
        val echo = EchoServer()
        try {
            val firstPort = freePort()
            val host = saveHostWithRules("L:$firstPort:${LOOPBACK}:${echo.port}")
            connectSaved(host.id, password = PASSWORD)
            val original = savedRules(host).single()
            pumpUntil(describe = { "the rule never came up: " + diagnose(host.id) }) {
                statusesOf(host.id)[original.id]?.state == ForwardRuntime.RUNNING
            }
            assertThat(echoThrough(firstPort, "before-edit")).isEqualTo("before-edit")

            // A port change: same rule to the user, new id to the engine, old port handed back.
            val secondPort = freePort()
            compose.runOnUiThread {
                viewModel().saveForwardRules(host.id, listOf(original.copy(localPort = secondPort)))
            }
            val edited = savedRulesAfter(host.id) { it.localPort == secondPort }
            pumpUntil(describe = { "the edited rule never came up: " + diagnose(host.id) }) {
                statusesOf(host.id)[edited.id]?.state == ForwardRuntime.RUNNING
            }
            assertThat(echoThrough(secondPort, "after-edit")).isEqualTo("after-edit")
            pumpUntil(describe = { "the old port was never released by the edit" }) { portIsFree(firstPort) }
            // The changed rule's old id is gone from both the rows and the running list.
            assertThat(statusesOf(host.id)).doesNotContainKey(original.id)
            assertThat(viewModel().uiState.value.forwardings.map(ForwardEntry::id)).doesNotContain(original.id)

            // Off: still a rule, listed as Disabled, bound to nothing. Looked up from the saved
            // column rather than by the enabled rule's id, because ids derive from the rule's *text*
            // and the `#` marker is part of the text - the disabled rule is a new id, which is the
            // very mechanism that made the engine release the running tunnel above.
            compose.runOnUiThread {
                viewModel().saveForwardRules(host.id, listOf(edited.copy(enabled = false)))
            }
            val disabled = savedRulesAfter(host.id) { !it.enabled && it.localPort == secondPort }
            pumpUntil(describe = { "the disabled rule never settled: " + diagnose(host.id) }) {
                statusesOf(host.id)[disabled.id]?.state == ForwardRuntime.DISABLED &&
                    tabFor(host.id)?.let { it.forwardsOpen == 0 && it.forwardsTotal == 0 } == true
            }
            pumpUntil(describe = { "the disabled rule's port was never released" }) { portIsFree(secondPort) }

            // Deleted: no row at all, nothing running, nothing claimed.
            compose.runOnUiThread { viewModel().saveForwardRules(host.id, emptyList()) }
            pumpUntil(describe = { "the deleted rule's row never left: " + diagnose(host.id) }) {
                statusesOf(host.id).isEmpty()
            }
            assertThat(viewModel().uiState.value.forwardings.none { it.hostId == host.id }).isTrue()
        } finally {
            runCatching { echo.close() }
        }
    }

    // ---------------------------------------------------------------- teardown

    /**
     * A host's forwards ride its primary session, so what happens to them when sessions end is the
     * whole teardown story:
     *
     *  - when the session under them dies *as a fault* and a sibling terminal still holds a live
     *    session, the rules are rebound to the survivor - the collector's ending path does this -
     *    and the tunnel keeps carrying traffic;
     *  - when the host's *last* session goes, the forwards are stopped, their rows leave the list,
     *    and the port is given back.
     *
     * The kill is out-of-band and abrupt - `close(true)` on the raw session, not through the store -
     * so it reaches the app as an outage, which is the path that carries the rebind. A deliberate
     * `closeTab` of the primary would be nobody's fault, and the ending path rightly rebinds nothing.
     */
    @Test
    fun `a fault under a surviving sibling rebinds the forwards and the last session stops them`() {
        val echo = EchoServer()
        try {
            val port = freePort()
            val host = saveHostWithRules("L:$port:${LOOPBACK}:${echo.port}")
            connectSaved(host.id, password = PASSWORD)
            val ruleId = savedRules(host).single().id
            pumpUntil(describe = { "the rule never came up: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.RUNNING
            }

            // A second terminal on the same host. The forward stays on the *primary* session - the
            // first terminal's - because that is the session a host's tunnels ride.
            compose.runOnUiThread { viewModel().duplicateSession(checkNotNull(tabFor(host.id))) }
            pumpUntil(describe = { "the duplicate never connected: " + diagnose(host.id) }) {
                viewModel().uiState.value.tabs.count { it.hostId == host.id && it.state == SessionConnectionState.CONNECTED } == 2
            }
            val viewModel = viewModel()
            val store = injected(viewModel, "sessionStore", SshSessionStore::class.java)

            // The primary's transport dies the way a network fault looks: abruptly, and without
            // anything telling the app it was on purpose.
            checkNotNull(store.liveSession(host.id)) { "the primary session never reached the store" }.close(true)
            // During the rebind window the local port briefly refuses connections — the dead
            // transport's listener is gone and the sibling's is not up yet — so the probe must read
            // as "not yet" here, not throw. The state assertions outside this loop still fail
            // loudly on a connection that stays refused.
            pumpUntil(describe = { "the forwards were never rebound to the sibling: " + diagnose(host.id) }) {
                statusesOf(host.id)[ruleId]?.state == ForwardRuntime.RUNNING &&
                    runCatching { echoThrough(port, "probe") }.getOrDefault("") == "probe"
            }
            // Exactly one handle for the rule: the dead transport's tracker must not linger beside
            // the survivor's.
            assertThat(viewModel.uiState.value.forwardings.map(ForwardEntry::id)).containsExactly(ruleId)
            val handles = injected(viewModel, "forwardHandles", ConcurrentHashMap::class.java)
            assertThat(handles.keys().toList().filter { it == ruleId }).hasSize(1)

            // And the last session of the host: the forwards go with it.
            viewModel.uiState.value.tabs.filter { it.hostId == host.id }.forEach { tab ->
                compose.runOnUiThread { viewModel.closeTab(tab) }
            }
            pumpUntil(describe = { "the host's tabs never closed" }) { tabFor(host.id) == null }
            pumpUntil(describe = { "the host's rows outlived its last session" }) { statusesOf(host.id).isEmpty() }
            assertThat(viewModel.uiState.value.forwardings.none { it.hostId == host.id }).isTrue()
            pumpUntil(describe = { "the forward's port was never released at teardown" }) { portIsFree(port) }
        } finally {
            runCatching { echo.close() }
        }
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * Reads one of the view model's private forward bookkeeping maps. By reflection deliberately,
     * for the same reason `ConnectionMatrixRobolectricTest` gives: a public getter would put every
     * live session's tunnels on the production API, and the duplicate-handle assertion is the one
     * thing that cannot be made any other way - the running list and the counters can both look
     * right while a dead transport's handle is still on the books.
     */
    private fun <T> injected(viewModel: MainViewModel, name: String, type: Class<T>): T =
        type.cast(
            MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(viewModel),
        )!!

    private fun tabFor(hostId: String): SessionTab? =
        viewModel().uiState.value.tabs.firstOrNull { it.hostId == hostId }

    private fun currentHost(hostId: String): HostProfile =
        checkNotNull(viewModel().uiState.value.hosts.firstOrNull { it.id == hostId }) {
            "host $hostId is not in the list"
        }

    /** The rules this host's saved column decodes to - the same ids the engine will key handles on. */
    private fun savedRules(host: HostProfile): List<ForwardEntry> = decodeForwardRules(host.savedForwards, host.id)

    /** Waits for the saved column to carry a rule matching [matching], and answers it. */
    private fun savedRulesAfter(hostId: String, matching: (ForwardEntry) -> Boolean): ForwardEntry {
        var found: ForwardEntry? = null
        pumpUntil(describe = { "the saved rules never reflected the edit: " + diagnose(hostId) }) {
            found = decodeForwardRules(currentHost(hostId).savedForwards, hostId).firstOrNull(matching)
            found != null
        }
        return checkNotNull(found)
    }

    /** This host's forward rows, as the sheet sees them (displayed states included). */
    private fun statusesOf(hostId: String): Map<String, ForwardStatus> =
        viewModel().uiState.value.forwardStatuses.filterValues { it.entry.hostId == hostId }

    /**
     * Writes a profile with [rules] as its saved-forwards column - the syntax the forwarding sheet
     * persists, so the engine reads back exactly what a user's edit would have left there - and pins
     * the server's fingerprint, for the same determinism reason as the connection matrix.
     */
    private fun saveHostWithRules(
        rules: String,
        credentials: HostCredentialUpdate = HostCredentialUpdate(),
    ): HostProfile {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = "pf-" + nextHostId++,
            name = HOST_NAME,
            host = LOOPBACK,
            username = USER,
            port = serverPort,
            authMethod = AuthMethod.PASSWORD,
            connectTimeoutSeconds = 60,
            autoLoginSftp = false,
            fingerprint = serverFingerprint,
            savedForwards = rules,
        )
        compose.runOnUiThread { viewModel.saveHost(profile, credentials) }
        pumpUntil(describe = { "the host was never saved" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        return profile
    }

    /** Waits for a vaulted password to be recorded, so a ladder redial can authenticate with it. */
    private fun awaitCredentialSaved(hostId: String) {
        pumpUntil(describe = { "the credential was never recorded as saved: " + diagnose(hostId) }) {
            viewModel().uiState.value.savedCredentials[hostId]?.hasPassword == true
        }
    }

    /**
     * Connects an already-saved host and waits for CONNECTED, accepting a host-key challenge on the
     * way if one appears - copied from `ConnectionMatrixRobolectricTest.connectSaved`.
     */
    private fun connectSaved(hostId: String, password: String?, timeoutMs: Long = SSH_TIMEOUT_MS) {
        val viewModel = viewModel()
        val saved = currentHost(hostId)
        compose.runOnUiThread { viewModel.connect(saved, password = password) }
        var trusted = false
        pumpUntil(timeoutMs, describe = { "the session never connected: " + diagnose(hostId) }) {
            if (!trusted && viewModel.uiState.value.hostKeyChallenge != null) {
                trusted = true
                compose.runOnUiThread { viewModel.acceptHostKey() }
            }
            tabFor(hostId)?.state == SessionConnectionState.CONNECTED
        }
    }

    /**
     * Waits for a hand-opened forward on [localPort] to be up, and answers its entry id - the UUID
     * the engine gives a forward that came from no rule, which is how "not resurrected" is asserted.
     */
    private fun pumpForForwardOn(localPort: Int): String {
        var id: String? = null
        pumpUntil(describe = { "the hand-opened forward on $localPort never came up" }) {
            val entry = viewModel().uiState.value.forwardings.firstOrNull { it.localPort == localPort }
            id = entry?.id
            entry != null &&
                viewModel().uiState.value.forwardStatuses[entry.id]?.state == ForwardRuntime.RUNNING
        }
        return checkNotNull(id)
    }

    // ---------------------------------------------------------------- the wire

    /**
     * Writes [payload] to a local port and answers what came back. Any exception - a refused dial, a
     * reset, a stall past the socket timeout - fails the test with the socket's own story.
     */
    private fun echoThrough(port: Int, payload: String): String {
        Socket().use { socket ->
            socket.soTimeout = TRAFFIC_TIMEOUT_MS.toInt()
            socket.connect(InetSocketAddress(LOOPBACK, port), TRAFFIC_TIMEOUT_MS.toInt())
            socket.getOutputStream().apply { write(payload.toByteArray()); flush() }
            val expected = payload.toByteArray()
            val read = ByteArray(expected.size)
            var got = 0
            while (got < read.size) {
                val n = socket.getInputStream().read(read, got, read.size - got)
                if (n < 0) break
                got += n
            }
            return String(read, 0, got)
        }
    }

    /**
     * Speaks the first two bytes of SOCKS5 to [port] and reports whether a proxy answered.
     *
     * A real SOCKS client is not needed on purpose: the greeting `05 01 00` (" SOCKS5, one method,
     * no auth") and its two-byte answer `05 00` are a complete request-response pair with the
     * listener, so the exchange proves a SOCKS5 proxy is on the port without caring what it would
     * connect to. A refused or stalling port answers false, which is exactly the assertion the
     * not-resurrected half of the reconnect test needs.
     */
    private fun socks5GreetingAnswer(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = TRAFFIC_TIMEOUT_MS.toInt()
            socket.connect(InetSocketAddress(LOOPBACK, port), TRAFFIC_TIMEOUT_MS.toInt())
            socket.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x00)); flush() }
            val answer = ByteArray(2)
            var got = 0
            while (got < answer.size) {
                val n = socket.getInputStream().read(answer, got, answer.size - got)
                if (n < 0) return@use false
                got += n
            }
            answer[0] == 0x05.toByte() && answer[1] == 0x00.toByte()
        }
    }.getOrDefault(false)

    /**
     * Whether [port] can be bound right now - the honest measure of "released", because a forward
     * that is off the list but still holding its listening socket is a leak with a tidy UI.
     */
    private fun portIsFree(port: Int): Boolean =
        runCatching {
            ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).use { Unit }
        }.isSuccess

    /** A port number nothing is holding at the moment, learned by borrowing it and giving it back. */
    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    /**
     * Drives frames and main-looper work until [condition] holds - copied from
     * `ConnectionMatrixRobolectricTest.pumpUntil`, which explains each line.
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
            append(" statuses=").append(statusesOf(hostId))
            append(" running=").append(viewModel.uiState.value.forwardings)
            append(" status=").append(viewModel.statusMessage.value)
            append(" authAttempts=").append(authAttempts.get())
            append(" shellsStarted=").append(shellsStarted.get())
            append(" serverPort=").append(serverPort)
            append(" hostId=").append(hostId)
        }
    }

    private companion object {
        const val SSH_TIMEOUT_MS = 90_000L

        /** Generous, on purpose: the reconnect ladder waits out a real backoff with jitter first. */
        const val RECONNECT_TIMEOUT_MS = 150_000L

        /** One tunnel round trip may take this long on a JVM sharing two cores with an SSH server. */
        const val TRAFFIC_TIMEOUT_MS = 15_000L

        const val LOOPBACK = "127.0.0.1"

        /** Local test credentials only: this server lives and dies inside this JVM. */
        const val USER = "pf-user"
        const val PASSWORD = "pf-pass-456"
        const val HOST_NAME = "forwards"

        var serverPort = 0
        var serverFingerprint = ""

        val authAttempts = AtomicInteger(0)
        val shellsStarted = AtomicInteger(0)

        /** The switch that makes the server refuse remote binds; see [TcpipForwardGate]. */
        val forwardGate = TcpipForwardGate()

        private var nextHostId = 0
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            // Before the first activity, for the same reason as the connection matrix: the vault
            // caches its key on first use, and the credential store's first write asks for it.
            StandInAndroidKeyStore.install()

            val root = Files.createTempDirectory("eclipse-port-forwarding")
            server = SshServer.setUpDefaultServer().apply {
                // Port 0: the debug and release unit-test JVMs overlap, and a fixed port would give
                // the loser of the bind a server that never started.
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    Files.createTempDirectory("pf-hostkey").resolve("hostkey.ser"),
                )
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    authAttempts.incrementAndGet()
                    user == USER && password == PASSWORD
                }
                // Password only, so `authAttempts` is an exact count - see the connection matrix.
                userAuthFactories = listOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
                // The builder's default filter rejects every forward - direct-tcpip with
                // SSH_OPEN_ADMINISTRATIVELY_PROHIBITED and tcpip-forward with a plain refusal - and a
                // suite whose subject is tunnels would then measure only the client's bind succeeding
                // while every byte never left the phone. Accept-all here: the one refusal this suite
                // needs is produced on purpose by [forwardGate], which sits in front of MINA's own
                // handler and can deny a single request without touching anything else.
                forwardingFilter = AcceptAllForwardingFilter.INSTANCE
                subsystemFactories = Collections.singletonList(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                shellFactory = ShellFactory { CountingShell(shellsStarted) }
                // In front of MINA's own handler, so a test can make the server say no to a remote
                // forward - the one refusal this suite cannot produce any other way.
                globalRequestHandlers =
                    listOf<RequestHandler<ConnectionService>>(forwardGate) + globalRequestHandlers.orEmpty()
                start()
            }
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
            serverFingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(server.keyPairProvider.loadKeys(null).first().public.encoded),
            )
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
            StandInAndroidKeyStore.uninstall()
        }
    }
}

/**
 * A global request handler that can be told to refuse `tcpip-forward`, standing in front of the
 * server's own handler so the refusal is a real answer to the real request name.
 *
 * This is the shape of `AllowTcpForwarding no` on a real server: the request is understood, and the
 * reply is failure. `Result.ReplyFailure` makes MINA answer `SSH_MSG_REQUEST_FAILURE`, which is what
 * turns into "Tcpip forwarding request denied by server" on the client. Everything else falls through
 * as `Unsupported`, so keep-alives and any other request behave exactly as they did without the gate.
 */
private class TcpipForwardGate : ConnectionServiceRequestHandler {
    @Volatile
    var denied = false

    override fun process(
        service: ConnectionService,
        request: String,
        wantReply: Boolean,
        buffer: Buffer,
    ): RequestHandler.Result =
        if (request == "tcpip-forward" && denied) RequestHandler.Result.ReplyFailure
        else RequestHandler.Result.Unsupported
}

/**
 * An echo server on loopback, one thread per connection, so a tunnel has something real to carry
 * traffic to. The payload that comes back is the payload that went in, which is the whole assertion.
 */
private class EchoServer : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    private val acceptor = Thread {
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: return@Thread
            Thread {
                runCatching {
                    client.use { socket ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val read = socket.getInputStream().read(buffer)
                            if (read < 0) break
                            socket.getOutputStream().apply { write(buffer, 0, read); flush() }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        runCatching { server.close() }
    }
}

/**
 * A shell that counts itself, prints a prompt and echoes - the connection matrix's shell, shared by
 * copy so the two classes stay free to diverge. The count exists because "the tab says CONNECTED" is
 * the app's opinion and the number of shells the server started is the fact.
 */
// Top-level, so it cannot reach the test class's companion: the counter is handed in instead.
private class CountingShell(private val shellsStarted: AtomicInteger) : Command {
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
                sink.write("eclipse-pf-shell\r\n$ ".toByteArray())
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
