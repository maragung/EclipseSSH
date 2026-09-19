package dev.eclipse.ssh

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Chaos journeys for the release APK: orientation changes, rapid navigation, and
 * dialog-rotation interplay — the interactions a real device user produces and a
 * plain happy-path suite never does.
 *
 * The release-test pipeline installs the actual distribution artifact (R8-minified,
 * release-signed) and runs this suite against it via `am instrument`, so these tests
 * are the on-device signal for the class of release-only defect this app has really
 * had: v1.1.12–v1.1.16 crashed on open because a library clinit path resolved
 * differently through R8 than it did in the debug build every prior test ran on.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseChaosJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /**
     * A destination's first frame is async (see [AppNavigationTest.awaitSeededRow]).
     *
     * `onAllNodesWithText` is an extension on SemanticsNodeInteractionsProvider, so the
     * receiver form needs its androidx.compose.ui.test import — the call compiles only
     * once that import is present, as this file's first CI run demonstrated.
     */
    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    /**
     * A tap on the navigation bar, and the screen it owns, arrived.
     *
     * `performClick` injects a touch and returns; nothing in the harness waits for the destination
     * to change. Each tap in the journey below is followed by a rotation, so a tap that had not been
     * acted on yet carried the test into the next orientation and left it asserting against the
     * screen it had just left — the whole of a red API 35 leg that was green on API 30 from the same
     * code. Waiting here fails at the tap that did not land, and names the destination it wanted.
     */
    private fun open(label: String, marker: String) {
        tab(label).performClick()
        awaitText(marker)
    }

    /**
     * The activity the user is actually looking at.
     *
     * The rule's `scenario` is only ever `MainActivity`, and the display orientation follows the
     * activity *on top*: asking this one to rotate while the host form covers it changes nothing at
     * all, and a stopped activity's `resources.configuration` is never updated either, so there is
     * nothing to wait for. `compose.activityRule.scenario.onActivity` is what the first version of
     * [rotate] used, and it did not have a signal problem — it had no effect.
     *
     * Resumed rather than merely visible, and `lastOrNull` rather than `first` because both
     * activities are briefly resumed while the form animates in and the one that just came up is
     * the one that owns the display. `ActivityLifecycleMonitorRegistry` is what `ActivityScenario`
     * itself is built on, and it is on this source set's compile classpath already:
     * `espresso-core` declares `androidx.test:runner` at compile scope.
     */
    private fun resumedActivity(): Activity? {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .lastOrNull()
        }
        return activity
    }

    private fun foregroundActivity(): Activity =
        requireNotNull(resumedActivity()) { "no resumed activity to rotate" }

    /**
     * Waits for [window] to be the window in front again — the counterpart of [rotate]'s problem, one
     * window over.
     *
     * `waitForIdle` answers for Compose, and the activity that comes back when the one on top
     * finishes is a different window whose arrival is asynchronous: on API 30 the form was still
     * mid-finish when this test's last assertion ran, so the workspace behind it was reported as
     * present but not displayed. That is not a product failure and it is not a slow emulator either —
     * it is an assertion made against whichever window happened to be in front. Waiting here fails at
     * the window that did not come back, ten seconds in, and names it.
     */
    private fun awaitForeground(window: Class<out Activity>) {
        compose.waitUntil(timeoutMillis = 10_000) { resumedActivity()?.javaClass == window }
        compose.waitForIdle()
    }

    /**
     * A rotation the caller can rely on having happened.
     *
     * `waitForIdle` is not that signal: it answers for Compose, and Compose is idle for the whole
     * time the system spends rotating the display — 0.7-1.1 s per turn on the API 35 emulator, whose
     * logcat fills with "Slow dispatch" while it tears down and rebuilds the taskbar. Returning early
     * left the taps and assertions below running against the window in the *previous* orientation.
     * `MainActivity` declares `configChanges` for orientation, so there is no recreation to wait for
     * either, and the window's own configuration is the only thing that reports the change.
     */
    private fun rotate(orientation: Int) {
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = foregroundActivity()
        instrumentation.runOnMainSync { activity.requestedOrientation = orientation }
        compose.waitUntil(timeoutMillis = 10_000) {
            var current = Configuration.ORIENTATION_UNDEFINED
            instrumentation.runOnMainSync { current = activity.resources.configuration.orientation }
            current == expected
        }
        compose.waitForIdle()
    }

    /**
     * Every destination must still be usable after a real orientation change —
     * `requestedOrientation` drives a genuine configuration change on the device,
     * which is a heavier path than `scenario.recreate()`: the window relayouts,
     * every `remember` block that keys on the configuration recomposes.
     */
    @Test
    fun rotatingThroughEveryDestinationKeepsTheScreenUsable() {
        compose.waitForIdle()
        awaitText("Production edge")

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()

        open("Terminal", "No active sessions")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.onNodeWithText("No active sessions").assertIsDisplayed()

        open("Transfers", "release-bundle.tar.gz")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        awaitText("release-bundle.tar.gz")
        compose.onNodeWithText("release-bundle.tar.gz").assertIsDisplayed()

        open("Settings", "Terminal theme")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.onNodeWithText("Terminal theme").assertExists()

        // And the round trip leaves a working workspace behind.
        open("Hosts", "Search hosts, tags, or usernames")
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
    }

    /**
     * Rapid repeated taps on the navigation bar are the cheapest way to find a
     * state machine that assumes serialized transitions: mid-flight destination
     * changes overlap collectors and recompositions. The screen must land on a
     * coherent destination afterwards, not a blank or stale one.
     */
    @Test
    fun rapidTapsOnTheNavigationBarsNeverCrashOrLoseTheScreen() {
        compose.waitForIdle()

        // Deliberately without waiting for idle between taps - the race is the point.
        repeat(3) {
            tab("Terminal").performClick()
            tab("Files").performClick()
            tab("Transfers").performClick()
            tab("Hosts").performClick()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()

        // And the workspace still navigates normally afterwards.
        tab("Settings").performClick()
        compose.onNodeWithText("Terminal theme").assertExists()
    }

    /**
     * A form open during a configuration change keeps everything in it, and Cancel still works.
     *
     * This used to rotate `MainActivity` with an `AlertDialog` layered over it, where the risk was a
     * dialog window outliving the activity that owned it. The form is an activity of its own now, so
     * the risk moved: the window is the one holding the state, and the failure this catches is a
     * rotation that wipes it.
     *
     * That is not hypothetical here, and the assertion below is what pins the fix.
     * [HostFormActivity] declares the configChanges list for exactly this reason — the form is a
     * page of unsaved input, and recreating the window would discard it. Every other window in the
     * Settings block deliberately does *not* declare one, because each of them holds a value that
     * already lives in a store. So a reader who aligns this entry with its sixteen neighbours, or who
     * reaches for `rememberSaveable` on the fields instead, gets a form that comes back empty; the
     * typed value asserted after the rotation is what makes that visible rather than silent.
     *
     * The rotation below is real as of the same change that made [rotate] target the foreground
     * activity. It was not before: the request went to `MainActivity`, which this form covers, so the
     * display never turned and the assertion after it held by standing still. The window is a
     * separate activity, which is exactly why the one helper that assumed otherwise had to be told
     * which activity it is rotating.
     *
     * The same is true of the last step: Cancel finishes this window, and the workspace behind it is
     * a different window arriving asynchronously, so [awaitForeground] waits for it rather than
     * asserting against whatever is in front when the click returns.
     */
    @Test
    fun theAddHostFormSurvivesARotation() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add host").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Hostname or IP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }

        // Something typed, so the rotation has state to lose rather than only a layout to redo.
        // The profile name is the one field an empty form can hold without becoming saveable - the
        // Save button stays switched off, so nothing here can reach the host store.
        compose.onNodeWithText("Profile name").performScrollTo().performTextInput("Rotated")
        compose.onNodeWithText("Rotated").assertIsDisplayed()

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Hostname or IP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText("Hostname or IP").assertIsDisplayed()
        // The half that fails if the window was recreated behind the user's back.
        compose.onNodeWithText("Rotated").assertIsDisplayed()

        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
        // Cancel finishes this window. The workspace behind it comes back asynchronously, and an
        // assertion that does not wait for it is an assertion about whichever window is in front.
        awaitForeground(MainActivity::class.java)
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
    }
}
