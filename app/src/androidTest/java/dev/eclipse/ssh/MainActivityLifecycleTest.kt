package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
            compose.onNodeWithText("Authenticate to deploy@edge.example.com").assertIsDisplayed()
        }
    }

    @Test
    fun anSftpDeepLinkWithoutAUserFallsBackToRoot() {
        ActivityScenario.launch<MainActivity>(viewIntent("sftp://files.example.com")).use {
            compose.waitForIdle()

            compose.onNodeWithText("Authenticate to root@files.example.com").assertIsDisplayed()
        }
    }

    @Test
    fun anOutOfRangePortFallsBackToTheDefault() {
        // Port 0 is not a usable SSH port, so the profile has to fall back to 22 rather than
        // carrying 0 into the connect attempt.
        ActivityScenario.launch<MainActivity>(viewIntent("ssh://ops@edge.example.com:0")).use {
            compose.waitForIdle()

            compose.onNodeWithText("Authenticate to ops@edge.example.com").assertIsDisplayed()
        }
    }

    @Test
    fun aDeepLinkDeliveredWhileAlreadyRunningIsNotDropped() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()

            // singleTask routes an explicit intent for the live instance through onNewIntent
            // rather than creating a second activity.
            context.startActivity(
                viewIntent("ssh://ci@build.example.com:2200")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            compose.waitForIdle()

            compose.onNodeWithText("Authenticate to ci@build.example.com").assertIsDisplayed()
            scenario.onActivity { assertThat(it.isFinishing).isFalse() }
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
