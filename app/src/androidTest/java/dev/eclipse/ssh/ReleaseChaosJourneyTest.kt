package dev.eclipse.ssh

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

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
     * A seeded row's first emission is async (see [AppNavigationTest.awaitSeededRow]).
     *
     * `onAllNodesWithText` is an extension on SemanticsNodeInteractionsProvider, so the
     * receiver form needs its androidx.compose.ui.test import — the call compiles only
     * once that import is present, as this file's first CI run demonstrated.
     */
    private fun awaitSeededRow(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rotate(orientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
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
        awaitSeededRow("Production edge")

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()

        tab("Terminal").performClick()
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.onNodeWithText("No active sessions").assertIsDisplayed()

        tab("Transfers").performClick()
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        awaitSeededRow("release-bundle.tar.gz")
        compose.onNodeWithText("release-bundle.tar.gz").assertIsDisplayed()

        tab("Settings").performClick()
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.onNodeWithText("Terminal theme").assertExists()

        // And the round trip leaves a working workspace behind.
        tab("Hosts").performClick()
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
     * A dialog open during a configuration change is a classic leak-and-crash
     * window: the dialog's window is torn down with the activity while its state
     * survives in the composition. Cancel must still work afterwards.
     */
    @Test
    fun theAddHostDialogSurvivesARotation() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add host").performClick()
        compose.onNodeWithText("Hostname or IP").assertIsDisplayed()

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.onNodeWithText("Hostname or IP").assertIsDisplayed()

        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search hosts, tags, or usernames").assertIsDisplayed()
    }
}
