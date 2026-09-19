package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.credentials.describe
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.HostDetailsKind
import dev.eclipse.ssh.ui.hosts.HostDetailsActivity
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One host's details, as a window: what is saved about it, what the vault holds for it, and what it is
 * doing.
 *
 * This suite is the counterpart of `HostAndThemeUiRobolectricTest`'s Details test, which used to open
 * the same screen where it was a `ModalBottomSheet` over the Hosts list. The rows did not change, and
 * neither did the rule the sheet was built on - the menu offers Connect, Edit, Remove and the rest one
 * tap away, so this window shows only what nothing else can: the saved configuration, the vault's
 * summary of it, and the server's own numbers.
 *
 * What only this level can show is which half is live and which half is a snapshot, because that split
 * is what the window's subject is. The host and its credentials are read from their own singletons by
 * id, so a host edited elsewhere, or a credential forgotten while this is open, is on screen here; the
 * server stats are the workspace's and travel in the subject, which is also why the two Monitoring rows
 * are answers rather than reads - the window cannot fetch them, and the workspace only hears requests
 * when it resumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HostDetailsActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * The answer slot is process-wide, so a leftover from another test in this JVM would be read as
     * this test's answer. Emptied before each test rather than after, so a test that fails mid-flight
     * cannot poison the next one.
     */
    @Before
    fun drainAnswers() {
        ActionRequests.takeAnswer()
        // Before the first window, because the two rows that answer "what does the vault hold" and
        // "forget it" go through the real credential store, and that store's first write asks
        // `SecureVault` for a hardware-backed key. Robolectric has no `AndroidKeyStore` at all, so
        // without this the write dies on `KeyStoreException: AndroidKeyStore not found` and the two
        // rows are untestable off a device. See [StandInAndroidKeyStore] for what it does and does
        // not stand in for.
        StandInAndroidKeyStore.install()
    }

    @After
    fun removeKeyStore() {
        StandInAndroidKeyStore.uninstall()
    }

    /**
     * Everything the host carries is on screen, read from the host itself rather than from an argument.
     *
     * The three lines that are neither the address nor the vault's summary - Route, Fingerprint, Group -
     * are the ones with nowhere else to be read from, and the fingerprint in particular is the one a
     * user comes here to check.
     */
    @Test
    fun theSavedConfigurationIsReadFromTheHost() {
        val host = seedHost(
            HostProfile(
                id = "b2-details-full",
                name = "Detailed",
                host = "details.example.test",
                username = "reader",
                port = 2022,
                group = "Staging",
                fingerprint = "SHA256:abcdefghijklmnop",
            ),
        )

        launchWindow(host, stats = null).use {
            awaitText("Staging")
            awaitText("SHA256:abcdefghijklmnop")
            awaitText("reader@details.example.test:2022")
            awaitText("Credentials")
        }
    }

    /**
     * A host with nothing saved is told so, and is not offered a way to forget what it does not have.
     *
     * The row is shown for every host rather than hidden when the answer is "nothing": an absent row
     * would be indistinguishable from the app not tracking credentials at all, and "Asked at every
     * connect" is the answer to the question the line exists to answer.
     */
    @Test
    fun aHostWithNothingSavedSaysSoAndOffersNoWayToForgetIt() {
        val host = seedHost(
            HostProfile(id = "b2-details-empty", name = "Bare", host = "bare.example.test", username = "nobody"),
        )

        launchWindow(host, stats = null).use {
            awaitText(StoredCredentials().describe())
            assertThat(compose.onAllNodes(hasText("Forget credentials")).fetchSemanticsNodes()).isEmpty()
        }
    }

    /** The vault's summary, and the one row that changes it: forgetting what is saved. */
    @Test
    fun whatTheVaultHoldsIsSummarisedAndCanBeForgotten() {
        val host = seedHost(
            HostProfile(id = "b2-details-saved", name = "Saved", host = "saved.example.test", username = "saver"),
        )
        savePassword(host.id, "hunter2")

        launchWindow(host, stats = null).use { scenario ->
            awaitText(StoredCredentials(hasPassword = true).describe())
            compose.onNodeWithText("Forget credentials").performClick()

            awaitAnswer(
                ActionAnswer.HostDetailsAction(host.id, HostDetailsKind.FORGET_CREDENTIALS),
                describe = { "the Forget row never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its Forget row was tapped" })
        }
    }

    /**
     * The credentials line follows the store while the window is open.
     *
     * This is what reading the store live buys, and it is not hypothetical: the row that changes it is
     * in this window, and a line still reading "Asked at every connect" after a password was saved
     * would be the window disagreeing with the vault beside it.
     */
    @Test
    fun theCredentialsLineFollowsTheStoreWhileTheWindowIsOpen() {
        val host = seedHost(
            HostProfile(id = "b2-details-live", name = "Live", host = "live.example.test", username = "liver"),
        )

        launchWindow(host, stats = null).use { scenario ->
            awaitText(StoredCredentials().describe())
            scenario.onActivity { activity ->
                runBlocking {
                    activity.credentialStore.apply(
                        host.id,
                        HostCredentialUpdate(password = SecretEdit.Replace("hunter2")),
                    )
                }
            }
            pumpUntil(describe = { "the credentials line never caught up with the vault" }) {
                compose.onAllNodes(hasText(StoredCredentials(hasPassword = true).describe()))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * With nothing heard from the server, the Monitoring block offers to go and ask, and says no
     * numbers at all.
     *
     * "Load server stats" rather than "Refresh": there is nothing to refresh, and the label has always
     * been the window's way of saying which of the two states it is in.
     */
    @Test
    fun withNoStatsTheMonitoringBlockOffersToLoadThem() {
        val host = seedHost(
            HostProfile(id = "b2-details-nostats", name = "Quiet", host = "quiet.example.test", username = "quiet"),
        )

        launchWindow(host, stats = null).use {
            awaitText("Load server stats")
            assertThat(compose.onAllNodes(hasText("Refresh stats")).fetchSemanticsNodes()).isEmpty()
            assertThat(compose.onAllNodes(hasText("Load average")).fetchSemanticsNodes()).isEmpty()
        }
    }

    /**
     * A snapshot of the server's numbers is drawn whole, and the row becomes a refresh.
     *
     * The stats are the half of this window the workspace owns, and they arrive in the subject rather
     * than through any store - so this test is the one that proves the handoff carries them and that
     * the window renders the snapshot it was handed, not a fetch of its own.
     */
    @Test
    fun theWorkspacesStatsSnapshotIsDrawnWhole() {
        val host = seedHost(
            HostProfile(id = "b2-details-stats", name = "Busy", host = "busy.example.test", username = "busy"),
        )

        launchWindow(host, stats = STATS).use {
            awaitText(STATS.hostname)
            awaitText(STATS.loadAverage)
            awaitText("${STATS.memoryUsed} / ${STATS.memoryTotal}")
            awaitText("${STATS.diskUsed} / ${STATS.diskTotal}")
            awaitText("Refresh stats")
            assertThat(compose.onAllNodes(hasText("Load server stats")).fetchSemanticsNodes()).isEmpty()
        }
    }

    /**
     * Either Monitoring row files its answer and closes the window.
     *
     * Both are workspace calls - the refresh is a connection only the workspace holds, and forgetting
     * the credentials also unregisters the host's live session - so neither is something this window
     * could have done for itself. Refresh is the one tapped here, and the close is the whole point: the
     * numbers on screen are about to be replaced, and a window cannot tell the old snapshot from the
     * new one.
     */
    @Test
    fun refreshStatsFilesTheAnswerAndClosesTheWindow() {
        val host = seedHost(
            HostProfile(id = "b2-details-refresh", name = "Stale", host = "stale.example.test", username = "stale"),
        )

        launchWindow(host, stats = STATS).use { scenario ->
            awaitText("Refresh stats")
            compose.onNodeWithText("Refresh stats").performClick()

            awaitAnswer(
                ActionAnswer.HostDetailsAction(host.id, HostDetailsKind.REFRESH_STATS),
                describe = { "the Refresh row never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its Refresh row was tapped" })
        }
    }

    /**
     * A host that leaves the list takes its window with it.
     *
     * Reachable without a race: another window can delete the host while this one is open, and a
     * details window for a profile that no longer exists has nothing left to describe.
     */
    @Test
    fun theWindowClosesWhenItsHostIsRemoved() {
        val host = seedHost(
            HostProfile(id = "b2-details-gone", name = "Doomed", host = "doomed.example.test", username = "doomed"),
        )

        launchWindow(host, stats = null).use { scenario ->
            awaitText("Credentials")
            scenario.onActivity { activity -> runBlocking { activity.hostRepository.delete(host) } }
            awaitClosing(scenario, describe = { "the window stayed open on a host that no longer exists" })
        }
    }

    /**
     * An intent carrying no token opens nothing, rather than a details window for an arbitrary host.
     *
     * The intent is the one thing about this window the system can re-deliver after the process is
     * gone, and there is no default host for it to fall back to: the first host in the list would be
     * somebody else's configuration.
     */
    @Test
    fun anIntentWithNoTokenOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<HostDetailsActivity>(
            Intent(context, HostDetailsActivity::class.java),
        ).use { scenario ->
            pumpUntil(describe = { "an intent with no subject token opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    // ---------------------------------------------------------------- driving the app

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun launchWindow(host: HostProfile, stats: ServerStats?): ActivityScenario<HostDetailsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<HostDetailsActivity>(HostDetailsActivity.intent(context, host, stats))
    }

    /**
     * Saves a host through the app's own repository and returns it.
     *
     * The repository is taken off the workspace's view model - it is an `@Inject` field there, so an
     * instance of the app's own components is the only handle on the store the window under test will be
     * reading. A second repository would write somewhere else and the window would find nothing.
     *
     * The workspace is launched for exactly one write and closed again: this is a window suite, and the
     * surface under test is the one it opens.
     */
    private fun seedHost(host: HostProfile): HostProfile {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking { workspaceRepository(activity).save(host) }
            }
        }
        return host
    }

    /**
     * Puts a password in the vault for [hostId], through the app's own store.
     *
     * Deliberately a separate launch rather than part of [seedHost]: the credential store and the host
     * repository are two writes, and a test that needs only the first should not depend on the second.
     */
    private fun savePassword(hostId: String, password: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> runBlocking { workspaceCredentials(activity).apply(hostId, HostCredentialUpdate(password = SecretEdit.Replace(password))) } }
        }
    }

    /** The host repository the workspace holds, by reflection. */
    private fun workspaceRepository(activity: MainActivity): HostRepository =
        workspaceField(activity, "hostRepository", HostRepository::class.java)

    /** The credential store the workspace holds, by reflection. */
    private fun workspaceCredentials(activity: MainActivity): HostCredentialStore =
        workspaceField(activity, "credentialStore", HostCredentialStore::class.java)

    private fun <T> workspaceField(activity: MainActivity, name: String, type: Class<T>): T {
        val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
        return MainViewModel::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(viewModel) as T
    }

    /** A string the window must be showing, wherever on the surface it is drawn. */
    private fun awaitText(text: String) {
        pumpUntil(describe = { "the window never showed \"$text\"" }) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        // Not every string asserted here is unique on the surface - "Credentials" is both the label and
        // the start of the vault's own summary - so the check is that one of them is on screen.
        compose.onAllNodes(hasText(text))[0].assertIsDisplayed()
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * The shape the suites that drive live state all share - see
     * `FilesExplorerLayoutRobolectricTest.pumpUntil` for why it is this and not `compose.waitUntil`.
     */
    private fun pumpUntil(timeoutMs: Long = TIMEOUT_MS, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * Waits for the window's answer, and returns what the one-shot slot handed over.
     *
     * Cached rather than read straight into the condition, because the slot is read-once: a
     * `takeAnswer()` inside a condition is true on the evaluation that found the answer and null on
     * the next, and [pumpUntil] asks twice — once to leave the loop and once to decide whether it
     * timed out. Read straight, an answer that did arrive is reported as one that never did.
     */
    private fun awaitAnswer(expected: ActionAnswer, describe: () -> String): ActionAnswer {
        var answer: ActionAnswer? = null
        pumpUntil(describe = describe) {
            answer = answer ?: ActionRequests.takeAnswer()
            answer == expected
        }
        return checkNotNull(answer)
    }

    /**
     * Waits for the window to have been asked to close, and asserts it on the activity.
     *
     * `activity.isFinishing` rather than `scenario.state == DESTROYED`, which is the same fact one
     * looper-hop later: `finish()` sets the flag there and then, while the state the scenario reports
     * only falls once the destroy it posts has been run — and waiting for that is a test of
     * Robolectric's looper rather than of the window. See `ForwardFormActivityRobolectricTest` for the
     * account of why the failure it produces is the confusing one. It matters most for the host that
     * is deleted under the window: that close is driven by the repository's flow rather than by a tap,
     * which is one more hop again.
     *
     * A window that reached DESTROYED before this looked counts too, because a destroyed activity
     * cannot be showing a menu: `onActivity` throws once there is nothing live to run on. What cannot
     * pass is a window that is neither finishing nor gone. The one test that launches a window which
     * closes *before* it is ever resumed — no token — still asserts the state directly: that one
     * closes during the launch, and it is a different fact.
     */
    private fun awaitClosing(scenario: ActivityScenario<HostDetailsActivity>, describe: () -> String) {
        var closing = false
        pumpUntil(describe = describe) {
            runCatching { scenario.onActivity { activity -> closing = activity.isFinishing } }
            closing || scenario.state == Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L

        /** A snapshot of a server's numbers, as the workspace would hand one over. */
        val STATS = ServerStats(
            hostname = "edge-01.example.test",
            uptime = "12 days",
            loadAverage = "0.42, 0.31, 0.28",
            memoryUsed = "3.1G",
            memoryTotal = "7.8G",
            diskUsed = "41G",
            diskTotal = "98G",
        )
    }
}
