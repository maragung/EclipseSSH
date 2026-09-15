package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.credentials.RdpCredentialUpdate
import dev.eclipse.ssh.data.credentials.RdpCredentials
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopRequest
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopRequests
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The host card's "Remote desktop" item and where it leads, and its RDP sibling "RDP desktop".
 *
 * Each item is one menu tap with three outcomes, and the routing is the feature: a host with a
 * saved, enabled target goes straight to the viewer window; a host with nothing saved (or a
 * target parked behind the `#` marker) gets the endpoint dialog first, because the entry point
 * exists so a first use does not have to hunt for a settings screen. The third outcome - what the
 * dialog's Connect commits - is asserted at the view-model boundary, the same split the other
 * dialog tests here make: a Compose dialog never idles under Robolectric, so its buttons cannot
 * be driven, but the save it would make can be called and watched.
 *
 * The RDP item has the same three outcomes now that the viewer speaks RDP, plus a fourth thing
 * only it has: the handoff carries the host's saved NLA credential, so a saved one connects
 * without asking and the sign-in form starts pre-filled. That handoff is asserted by taking the
 * one-shot token back out of the store and reading the request it names - the activity is only
 * peeked at, never started, so the token is still there to take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RemoteDesktopEntryRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The credential round-trip needs the vault to encrypt, and the vault needs an `AndroidKeyStore`
     * to hold its key — the one platform piece Robolectric has none of. Without the stand-in every
     * write through [dev.eclipse.ssh.data.credentials.HostCredentialStore] fails silently behind the
     * ViewModel's `runCatching`, and the read asserts null against a save that never happened. The
     * other classes that save credentials through the app install the same provider for the same
     * reason; see [StandInAndroidKeyStore] for what it does and does not stand in for.
     */
    @Before
    fun installKeyStore() {
        StandInAndroidKeyStore.install()
    }

    /** Puts the JVM back as it was, so opting in does not change how the next test class behaves. */
    @After
    fun uninstallKeyStore() {
        StandInAndroidKeyStore.uninstall()
    }

    /** Deletes this class's hosts, so the next class sees the Room file the app ships with. */
    @After
    fun removeHosts() {
        runCatching {
            val viewModel = viewModel()
            compose.runOnUiThread {
                viewModel.uiState.value.hosts.filter { it.id.startsWith(ID_PREFIX) }.forEach(viewModel::deleteHost)
            }
        }
    }

    // ---------------------------------------------------------------- where the menu item leads

    /**
     * Nothing saved: the item configures, it does not refuse.
     *
     * The dialog is confirmed at window level through [ShadowDialog] — a Compose `AlertDialog`
     * never idles under Robolectric — which is the one honest signal that the tap reached the
     * endpoint dialog rather than doing nothing.
     */
    @Test
    fun theMenuItemOpensTheEndpointDialogWhenNothingIsSaved() {
        val host = addHost("Bare host")
        val before = ShadowDialog.getShownDialogs().size

        openRemoteDesktopMenu(host)

        assertWithMessage("the item did not open the endpoint dialog")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
        // Nothing was launched either: a host with no target must not open a viewer that has
        // nothing to dial.
        assertThat(startedRemoteDesktopViewer()).isFalse()
    }

    /**
     * A saved, enabled target: one tap, the viewer window, no questions.
     *
     * The started activity is the assertion, read from the application's shadow the same way the
     * Files tests read the editor's launch — and the token extra is checked to be present, since
     * an intent without one opens an activity that finishes before its first frame.
     */
    @Test
    fun theMenuItemGoesStraightToTheViewerWhenAVncTargetIsSaved() {
        val host = addHost("Desktop host", remoteDesktop = "V:10.0.1.5:5901")
        val before = ShadowDialog.getShownDialogs().size

        openRemoteDesktopMenu(host)

        pumpUntil(describe = { "the viewer never started" }) { startedRemoteDesktopViewer() }
        // No endpoint dialog in the way: the saved target is the answer to the dialog's question.
        assertWithMessage("a saved target still asked before opening")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(before)

        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertThat(intent?.getStringExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN)).isNotEmpty()
    }

    /**
     * A target parked behind the `#` marker is stored but not offered - and "not offered" means
     * the menu asks first here too, not that it silently dials a desktop the user switched off.
     */
    @Test
    fun aTargetParkedWithTheDisabledMarkerStillAsksFirst() {
        val host = addHost("Parked host", remoteDesktop = "#V:5900")
        val before = ShadowDialog.getShownDialogs().size

        openRemoteDesktopMenu(host)

        assertWithMessage("the parked target was opened without asking")
            .that(startedRemoteDesktopViewer()).isFalse()
        assertWithMessage("the item did not open the endpoint dialog")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
    }

    // ---------------------------------------------------------------- what a save commits

    /**
     * The save the dialog's Connect makes, at the boundary the dialog calls.
     *
     * The defaults are the interesting half: a target that says nothing about its host is the
     * server's own loopback, and the packed-text column omits it - so the saved line is `V:5901
     * view-only`, not `V:127.0.0.1:5901 view-only`. What comes back from the column is what the
     * next menu tap reads, which is why the assertion reads the hosts flow rather than trusting
     * the encoder.
     *
     * The provider half: a host with no live session answers null - that is the viewer's
     * precondition, and it is why the viewer reports "connect the host first" instead of dialing.
     */
    @Test
    fun savingATargetNormalisesItThroughTheColumnAndTheProviderReportsNoSession() {
        val host = addHost("Saving host")

        compose.runOnUiThread {
            viewModel().saveRemoteDesktopTarget(
                host.id,
                RemoteDesktopTarget(port = 5901, viewOnly = true),
            )
        }

        pumpUntil(describe = { "the target never reached the host" }) {
            viewModel().uiState.value.hosts.any { it.id == host.id && it.remoteDesktop == "V:5901 view-only" }
        }
        assertThat(viewModel().remoteDesktopSessionProvider(host.id)()).isNull()
    }

    // ---------------------------------------------------------------- the RDP item

    /**
     * No R line saved: the item is offered anyway, the VNC item's rule. Both entries are the
     * first-use story for their protocol now that both viewers exist - the entry point is the
     * menu item, not a settings screen the user has to find first.
     */
    @Test
    fun theRdpMenuItemIsOfferedForAHostWithNoRdpTargetAndOpensTheDialog() {
        val host = addHost("Bare RDP host")
        val before = ShadowDialog.getShownDialogs().size

        openRdpMenu(host)

        assertWithMessage("the item did not open the RDP endpoint dialog")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
        assertThat(startedRemoteDesktopViewer()).isFalse()
    }

    /**
     * A saved, enabled RDP target: one tap, the viewer window, no questions - the same rule the
     * VNC item lives by, and the routing this class exists to pin.
     */
    @Test
    fun theRdpMenuItemGoesStraightToTheViewerWhenAnRdpTargetIsSaved() {
        val host = addHost("RDP host", remoteDesktop = "R:3389")
        val before = ShadowDialog.getShownDialogs().size

        openRdpMenu(host)

        pumpUntil(describe = { "the viewer never started" }) { startedRemoteDesktopViewer() }
        assertWithMessage("a saved target still asked before opening")
            .that(ShadowDialog.getShownDialogs().size).isEqualTo(before)

        val intent = shadowOf(compose.activity.application).peekNextStartedActivity()
        assertThat(intent?.getStringExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN)).isNotEmpty()
    }

    /** A parked `#R:` target is stored but not offered - the dialog is where it gets re-enabled. */
    @Test
    fun aParkedRdpTargetStillAsksFirst() {
        val host = addHost("Parked RDP host", remoteDesktop = "#R:3389")
        val before = ShadowDialog.getShownDialogs().size

        openRdpMenu(host)

        assertWithMessage("the parked target was opened without asking")
            .that(startedRemoteDesktopViewer()).isFalse()
        assertWithMessage("the item did not open the RDP endpoint dialog")
            .that(ShadowDialog.getShownDialogs().size).isGreaterThan(before)
        assertThat(ShadowDialog.getLatestDialog()?.isShowing).isTrue()
    }

    // ---------------------------------------------------------------- the RDP handoff

    /**
     * What the RDP viewer is handed, read back out of the one-shot token store.
     *
     * The activity is peeked at rather than started, so the token it was launched with is still
     * in [RemoteDesktopRequests] - taking it back yields the request itself, which is where the
     * saved NLA credential has to survive to: the same values the credential round trip above
     * reads, carried into the window that will answer an NLA challenge with them, with the
     * completeness flag saying it can.
     */
    @Test
    fun theViewerHandoffCarriesTheSavedRdpCredentials() {
        val host = addHost("Credential RDP host", remoteDesktop = "R:3389")
        compose.runOnUiThread {
            viewModel().saveRdpCredentials(
                host.id,
                RdpCredentialUpdate(
                    username = SecretEdit.Replace("administrator"),
                    domain = SecretEdit.Replace("CORP"),
                    password = SecretEdit.Replace("rdp-secret"),
                ),
            )
        }
        assertThat(awaitRdpCredentials(host.id) { it?.username == "administrator" }).isNotNull()

        openRdpMenu(host)
        pumpUntil(describe = { "the viewer never started" }) { startedRemoteDesktopViewer() }

        val token = shadowOf(compose.activity.application).peekNextStartedActivity()
            ?.getStringExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN)
        assertWithMessage("the viewer was started without a handoff token").that(token).isNotEmpty()
        val request = RemoteDesktopRequests.take(checkNotNull(token))
        assertWithMessage("the RDP item handed the viewer a VNC request")
            .that(request).isInstanceOf(RemoteDesktopRequest.Rdp::class.java)
        request as RemoteDesktopRequest.Rdp
        assertThat(request.target).isEqualTo(RemoteDesktopTarget(port = 3389))
        assertThat(request.credentials?.username).isEqualTo("administrator")
        assertThat(request.credentials?.domain).isEqualTo("CORP")
        assertThat(request.credentials?.password).isEqualTo("rdp-secret")
        assertThat(request.credentialsComplete).isTrue()
    }

    /**
     * The same handoff for a host with nothing saved: no credential to pre-fill from and nothing
     * to answer NLA with, which is exactly what the completeness flag is for - the viewer knows
     * to expect its sign-in form rather than a silent negotiation.
     */
    @Test
    fun theViewerHandoffReportsWhenNoRdpCredentialsAreSaved() {
        val host = addHost("Bare credential RDP host", remoteDesktop = "R:3389")

        openRdpMenu(host)
        pumpUntil(describe = { "the viewer never started" }) { startedRemoteDesktopViewer() }

        val token = shadowOf(compose.activity.application).peekNextStartedActivity()
            ?.getStringExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN)
        assertWithMessage("the viewer was started without a handoff token").that(token).isNotEmpty()
        val request = RemoteDesktopRequests.take(checkNotNull(token))
        assertWithMessage("the RDP item handed the viewer a VNC request")
            .that(request).isInstanceOf(RemoteDesktopRequest.Rdp::class.java)
        request as RemoteDesktopRequest.Rdp
        assertThat(request.credentials).isNull()
        assertThat(request.credentialsComplete).isFalse()
    }

    /**
     * The save the RDP dialog's Save makes, at the boundary the dialog calls.
     *
     * The interesting half is the column around the R line: a host with a VNC target must come out
     * of an RDP save with the V line untouched and the R line beside it, because the packed-text
     * column is one field that holds both protocols. The defaults land as `R:3389 view-only`, not
     * `R:127.0.0.1:3389 view-only`, the same elision the VNC line makes of the loopback host.
     *
     * The read-back goes through [decodeRemoteDesktop] itself - the codec the stopgap R-line
     * reader stood in for until this branch - because that is the reader the menu item reads on
     * the very next tap: both targets have to come back from the one column, not just the one
     * that was just saved.
     */
    @Test
    fun savingAnRdpTargetNormalisesItAndLeavesTheVncLineAlone() {
        val host = addHost("Saving RDP host", remoteDesktop = "V:10.0.1.5:5901")

        compose.runOnUiThread {
            viewModel().saveRdpTarget(
                host.id,
                RemoteDesktopTarget(port = 3389, viewOnly = true),
            )
        }

        pumpUntil(describe = { "the RDP target never reached the host" }) {
            viewModel().uiState.value.hosts.any { it.id == host.id && it.remoteDesktop == "V:10.0.1.5:5901\nR:3389 view-only" }
        }
        val column = viewModel().uiState.value.hosts.first { it.id == host.id }.remoteDesktop
        val decoded = decodeRemoteDesktop(column)
        assertThat(decoded.vnc).isEqualTo(RemoteDesktopTarget(host = "10.0.1.5", port = 5901))
        assertThat(decoded.rdp).isEqualTo(RemoteDesktopTarget(port = 3389, viewOnly = true))
    }

    /**
     * The RDP credential's ViewModel round trip, and its lifetime: the save the NLA prompt will
     * make comes back through the callback read, and deleting the host takes the credential with
     * it - the store's forget covers all three fields, so nothing outlives the profile it belongs
     * to.
     *
     * Both ends read through [awaitRdpCredentials], not one bare read: the save and the delete's
     * forget are fire-and-forget (a click handler's shape) and land through the DataStore edit
     * path while the read goes through its state flow, so a read racing either write observes the
     * pre-write value. Waiting for the value to settle is the same honesty as the pump for the UI.
     */
    @Test
    fun rdpCredentialsRoundTripThroughTheViewModelAndDieWithTheHost() {
        val host = addHost("RDP credential host")

        compose.runOnUiThread {
            viewModel().saveRdpCredentials(
                host.id,
                RdpCredentialUpdate(
                    username = SecretEdit.Replace("administrator"),
                    domain = SecretEdit.Replace("CORP"),
                    password = SecretEdit.Replace("rdp-secret"),
                ),
            )
        }

        val first = awaitRdpCredentials(host.id) { it?.username == "administrator" }
        assertThat(first?.domain).isEqualTo("CORP")
        assertThat(first?.password).isEqualTo("rdp-secret")

        compose.runOnUiThread { viewModel().deleteHost(host) }
        pumpUntil(describe = { "the host never went away" }) {
            viewModel().uiState.value.hosts.none { it.id == host.id }
        }

        assertThat(awaitRdpCredentials(host.id) { it == null }).isNull()
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /**
     * Saves a host through the view model and waits for its card, so the kebab below it is on
     * screen before anything clicks it.
     */
    private fun addHost(name: String, remoteDesktop: String = ""): HostProfile {
        compose.waitForIdle()
        val viewModel = viewModel()
        val profile = HostProfile(
            id = ID_PREFIX + nextId++,
            name = name,
            host = "desktop.example.test",
            username = "tester",
            port = 22,
            remoteDesktop = remoteDesktop,
        )
        compose.runOnUiThread { viewModel.saveHost(profile) }
        pumpUntil(describe = { "the host never reached the list" }) {
            viewModel.uiState.value.hosts.any { it.id == profile.id }
        }
        pumpUntil(describe = { "the card never appeared for $name" }) {
            compose.onAllNodesWithContentDescription("More actions for $name").fetchSemanticsNodes().isNotEmpty()
        }
        return profile
    }

    /** Opens the kebab and taps Remote desktop, waiting for the menu the way the flake taught us to. */
    private fun openRemoteDesktopMenu(host: HostProfile) {
        // Drain whatever the setup started, so the peek below only ever reports this click's
        // doing — peeking does not consume, so a stale intent would mask the viewer's.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit

        openKebabMenu(host.name, "Remote desktop")
        compose.onNodeWithText("Remote desktop").performClick()
        pump()
    }

    /** Opens the kebab and taps RDP desktop, waiting for the menu the way the flake taught us to. */
    private fun openRdpMenu(host: HostProfile) {
        // Drain whatever the setup started, so the peek below only ever reports this click's
        // doing — peeking does not consume, so a stale intent would mask the viewer's.
        while (runCatching { shadowOf(compose.activity.application).nextStartedActivity }.getOrNull() != null) Unit

        openKebabMenu(host.name, "RDP desktop")
        compose.onNodeWithText("RDP desktop").performClick()
        pump()
    }

    /**
     * Opens a host card's kebab menu and waits for the item the caller is about to tap.
     *
     * The tap is retried because on a loaded runner it can simply miss the icon while the host
     * list settles underneath it - a click that never landed is why one CI run waited 20 seconds
     * for an RDP desktop menu that was never going to open (the same mechanism the host-and-theme
     * suite's copy of this helper retries for). Each attempt re-finds the kebab for fresh
     * coordinates; a menu that does open is still asserted to hold the item, so a missing item
     * stays a failure.
     */
    private fun openKebabMenu(hostName: String, offering: String) {
        val kebab = "More actions for $hostName"
        repeat(KEBAB_OPEN_ATTEMPTS) {
            compose.onNodeWithContentDescription(kebab).performClick()
            if (waitFor(KEBAB_OPEN_WAIT_MS) {
                    compose.onAllNodesWithText(offering).fetchSemanticsNodes().isNotEmpty()
                }
            ) {
                return
            }
        }
        check(false) {
            "the kebab menu never offered $offering after $KEBAB_OPEN_ATTEMPTS taps on $kebab"
        }
    }

    /**
     * One callback read of a host's saved RDP credential, pumped until the callback has fired -
     * the read is asynchronous on purpose (see the ViewModel's KDoc), so the pump is the wait.
     */
    private fun readRdpCredentials(hostId: String): RdpCredentials? {
        var result: RdpCredentials? = null
        var done = false
        compose.runOnUiThread {
            viewModel().rdpCredentials(hostId) {
                result = it
                done = true
            }
        }
        pumpUntil(describe = { "the RDP credential never read back" }) { done }
        return result
    }

    /**
     * Reads [hostId]'s credential until [settled] accepts what came back, re-issuing the same
     * callback read the NLA prompt will use. The store's writes are fire-and-forget from the
     * ViewModel's side and land through the DataStore edit path while this read goes through its
     * state flow, so a single read that races a save (or the delete's forget) observes the
     * pre-write value without anything being wrong; polling is the wait for "the store has caught
     * up", the way [pumpUntil] is the wait for the UI.
     */
    private fun awaitRdpCredentials(
        hostId: String,
        settled: (RdpCredentials?) -> Boolean,
    ): RdpCredentials? {
        var latest: RdpCredentials? = null
        pumpUntil(describe = { "the RDP credential never settled to the expected value" }) {
            latest = readRdpCredentials(hostId)
            settled(latest)
        }
        return latest
    }

    /** Whether the viewer activity was started, by peeking at what the application recorded. */
    private fun startedRemoteDesktopViewer(): Boolean =
        runCatching { shadowOf(compose.activity.application).peekNextStartedActivity() }.getOrNull()
            ?.component?.className == "dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity"

    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    /**
     * Drives frames until [condition] holds, then fails with [describe] - the house `pumpUntil`,
     * copied from `HostAndThemeUiRobolectricTest` so this class owns its own clock.
     */
    private fun pumpUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        check(waitFor(timeoutMs, condition)) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /** [pumpUntil]'s loop without the failure, for callers that retry rather than give up. */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        return condition()
    }

    private companion object {
        /** Prefix on every host this class creates, so the cleanup can find them and leave others. */
        const val ID_PREFIX = "rd-qa-"

        /** How many times [openKebabMenu] taps the kebab before giving up on the menu. */
        const val KEBAB_OPEN_ATTEMPTS = 3

        /** Per-tap wait for the menu's items; one tap's wait is ~180 frames at 16ms. */
        const val KEBAB_OPEN_WAIT_MS = 3_000L

        private var nextId = 0
    }
}
