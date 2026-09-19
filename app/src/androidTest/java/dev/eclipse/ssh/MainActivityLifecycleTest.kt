package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch, deep-link and process-lifecycle behaviour on a device. The activity is
 * `launchMode="singleTask"`, so a second ssh:// link is delivered to `onNewIntent` on the
 * existing instance — it used to be dropped there, which is what
 * [aDeepLinkDeliveredWhileAlreadyRunningIsNotDropped] pins down.
 *
 * Uses [createEmptyComposeRule] rather than createAndroidComposeRule because each test needs to
 * launch the activity with its own intent.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * An explicit VIEW intent for [link]. Explicit rather than resolved by scheme so the
     * unsupported-scheme cases below still reach the activity instead of failing to resolve.
     */
    private fun viewIntent(link: String) =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(link))

    /**
     * Waits for [text] to appear, then asserts it is displayed.
     *
     * Same shape as `ReleaseChaosJourneyTest.awaitText` and `AppNavigationTest.awaitSeededRow`,
     * which is where this idiom already lives in this suite. A bare `waitForIdle()` followed by an
     * assertion is a race for anything the app derives from its intent: the link is handed to the
     * activity by the system's activity manager and reaches the main thread afterwards, so Compose
     * can report idle before the state that opens the prompt has been posted, let alone composed.
     * A healthy emulator hides this — a recomposition is ~30ms, so the dialog usually wins — and
     * that is why this file read as green rather than as flaky. Under load it does not hide it: the
     * run on 2026-09-17 measured the guest at `app_time_stats: avg=4235.73ms` per frame, the link
     * was delivered at 15:26:01.774 (`result code=3`, START_DELIVERED_TO_TOP), the activity went
     * PAUSED/RESUMED at .823/.824, and the assertion had already failed by .84 — while the dialog
     * needed a frame the starved guest never got around to.
     *
     * The wait is bounded and the assertion still runs afterwards, so a prompt that never appears
     * still fails. This waits for the state under test; it does not excuse its absence.
     */
    private fun awaitTextDisplayed(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun aColdLaunchReachesResumedWithoutFinishing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()

            scenario.onActivity { activity -> assertThat(activity.isFinishing).isFalse() }
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
        }
    }

    @Test
    fun anSshDeepLinkOpensTheAuthenticationPromptForThatHost() {
        ActivityScenario.launch<MainActivity>(viewIntent("ssh://deploy@edge.example.com:2222")).use {
            compose.waitForIdle()

            // The link is offered as a one-off quick connect, named user@host.
            awaitTextDisplayed("Authenticate to deploy@edge.example.com")
        }
    }

    @Test
    fun anSftpDeepLinkWithoutAUserFallsBackToRoot() {
        ActivityScenario.launch<MainActivity>(viewIntent("sftp://files.example.com")).use {
            compose.waitForIdle()

            awaitTextDisplayed("Authenticate to root@files.example.com")
        }
    }

    @Test
    fun anOutOfRangePortFallsBackToTheDefault() {
        // Port 0 is not a usable SSH port, so the profile has to fall back to 22 rather than
        // carrying 0 into the connect attempt.
        ActivityScenario.launch<MainActivity>(viewIntent("ssh://ops@edge.example.com:0")).use {
            compose.waitForIdle()

            awaitTextDisplayed("Authenticate to ops@edge.example.com")
        }
    }

    @Test
    fun aDeepLinkDeliveredWhileAlreadyRunningIsNotDropped() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()

            // The intent the scenario launched the activity with, before the link replaces it.
            var launchIntent: Intent? = null
            scenario.onActivity { launchIntent = it.intent }

            // singleTask routes an explicit intent for the live instance through onNewIntent
            // rather than creating a second activity.
            context.startActivity(
                viewIntent("ssh://ci@build.example.com:2200")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            compose.waitForIdle()

            try {
                awaitTextDisplayed("Authenticate to ci@build.example.com")
                scenario.onActivity { assertThat(it.isFinishing).isFalse() }
            } finally {
                // MainActivity keeps a delivered link as its intent, and ActivityScenario only
                // accepts lifecycle callbacks for an activity whose intent still matches the one
                // it launched with — so from the link's arrival onwards it silently ignored this
                // instance, and close() waited 45s for a DESTROYED callback it had itself been
                // discarding ("last lifecycle transition = PAUSED"; on device the activity had
                // long been destroyed). Putting the launch intent back re-opens the bookkeeping
                // before the teardown close() performs. The other tests here never deliver a
                // second intent, and launching with a VIEW intent directly (the no-usable-host
                // cases) tracks that same intent, so only this test needs the restore.
                //
                // In a finally rather than after the assertions because the restore is what makes
                // the teardown cheap, and a failing assertion is exactly when it matters: the
                // 2026-09-17 failure threw before reaching it and paid the whole 45s (link
                // delivered 15:26:01.84, failure reported 15:26:46.87) on top of the failure
                // itself.
                launchIntent?.let { saved -> scenario.onActivity { it.setIntent(saved) } }
            }
        }
    }

    @Test
    fun aDeepLinkWithNoUsableHostIsIgnoredInsteadOfCrashing() {
        listOf("ssh://", "ssh:///path", "http://example.com", "ssh://user@").forEach { link ->
            ActivityScenario.launch<MainActivity>(viewIntent(link)).use { scenario ->
                compose.waitForIdle()

                scenario.onActivity { assertThat(it.isFinishing).isFalse() }
                // No host to connect to, so the workspace opens as if launched normally.
                compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
            }
        }
    }

    @Test
    fun theActivitySurvivesABackgroundAndForegroundCycle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()

            // Minimise, then come back — the relock and reconnect observers run on these edges.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()

            scenario.onActivity { assertThat(it.isFinishing).isFalse() }
            compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
        }
    }

    @Test
    fun theActivitySurvivesRecreationFromSavedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()

            scenario.recreate()
            compose.waitForIdle()

            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
        }
    }
}
