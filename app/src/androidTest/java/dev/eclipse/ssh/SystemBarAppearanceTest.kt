package dev.eclipse.ssh

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system bar icons following the app's own theme rather than the system's.
 *
 * `enableEdgeToEdge()` draws the status and navigation bars over a transparent background and
 * styles their icons from the *system* `uiMode`. This app's dark setting is deliberately
 * independent of that — a dark app on a light-mode device is the ordinary case, not an edge one —
 * so without `EclipseTheme`'s effect the framework leaves dark icons over a dark app surface and
 * the clock, battery and signal glyphs vanish into it.
 *
 * **On a device rather than under Robolectric, and that is forced, not preferred.** At the SDK
 * levels this app supports, `androidx.core` resolves the appearance through
 * `WindowInsetsControllerCompat.Impl30`/`Impl35`, which writes to `window.getInsetsController()`.
 * Robolectric 4.16.1 ships no shadow for that controller — no class in `shadows-framework` mentions
 * `InsetsController` at all — so under Robolectric the write lands on nothing, and a JVM test of
 * this would assert against a value the platform never stored. The emulator legs of
 * `ci.yml` and `android-release-test.yml` are the first place the write has somewhere to land.
 *
 * Its own class, for the reason `MainActivitySecureWindowTest` (in the JVM suite next door) gives
 * for being its own class: it writes a preference, and `preferencesDataStore` caches one store per
 * delegate for the whole process, so an unrestored write would outlive this class and reach the
 * launch tests next door. [restoringTheSetting] puts the value back even when an assertion fails.
 *
 * What this does *not* establish: that the icons are legible. It pins the mapping from the app's
 * flag to the window's appearance, and that the mapping is live. Whether white-on-dark reads well
 * is a judgement only an eye can make, and it stays with the maintainer's on-device loop.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarAppearanceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val repository get() = SettingsRepository(context)

    /**
     * The appearance the window is actually carrying, read back off the live window.
     *
     * A second `WindowInsetsControllerCompat` over the same window, not a cached reference to the
     * app's: at these SDK levels the controller is window-scoped, so this reads what the app's own
     * call wrote rather than a copy of it.
     */
    private fun lightBarIcons(scenario: ActivityScenario<MainActivity>): Boolean {
        var light = false
        scenario.onActivity { activity ->
            light = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars
        }
        return light
    }

    /**
     * Runs [body] and writes the dark setting back to what it was, however [body] ends.
     *
     * The store is shared with every other instrumented class in this process.
     */
    private fun restoringTheSetting(body: () -> Unit) {
        val original = runBlocking { repository.settings.first().darkTheme }
        try {
            body()
        } finally {
            runCatching { runBlocking { repository.setDarkTheme(original) } }
        }
    }

    /**
     * Drives frames until the window reports [expected], giving up after 10s of real time.
     *
     * The setting reaches `EclipseTheme` through DataStore and a `StateFlow`, and the icons are
     * applied from a `SideEffect` after a successful recomposition — so the window changes a
     * composition *after* the write, never with it. A bare read straight after `setDarkTheme` is a
     * race that a fast emulator would usually win, which is exactly how it would read as green
     * rather than as flaky.
     *
     * A hand-rolled loop rather than [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil],
     * because the condition has to call `scenario.onActivity` — a hop to the main thread and back —
     * and every turn here is bounded by wall-clock instead of by how the rule counts frames. Same
     * shape as `MainActivitySecureWindowTest.pumpUntil`. Returns whether it got there, so the
     * caller can report the state it settled on rather than only that it never arrived.
     */
    private fun awaitLightBarIcons(scenario: ActivityScenario<MainActivity>, expected: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (lightBarIcons(scenario) == expected) return true
            compose.waitForIdle()
            // One frame. `waitForIdle` returns the moment Compose is idle, so without this a turn
            // can outrun the recomposition it is waiting for and the loop becomes a spin against
            // the main thread it keeps hopping to.
            SystemClock.sleep(16)
        }
        return lightBarIcons(scenario) == expected
    }

    @Test
    fun theSystemBarIconsFollowTheAppsOwnDarkSettingAndNotTheSystems() =
        restoringTheSetting {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()

                // Both directions, because either one alone passes on a mapping with the polarity
                // inverted: a `= darkTheme` typo would satisfy "it changes when the setting
                // changes" and still paint dark icons on the dark theme.
                val dark = runBlocking { repository.settings.first().darkTheme }
                awaitLightBarIcons(scenario, expected = !dark)

                runBlocking { repository.setDarkTheme(!dark) }
                awaitLightBarIcons(scenario, expected = dark)

                // And back, so the effect is proven live in both directions rather than only on
                // the way out. Reports the stored value, so a failure says whether the write never
                // reached the store or reached it and never reached the window.
                runBlocking { repository.setDarkTheme(dark) }
                val arrived = awaitLightBarIcons(scenario, expected = !dark)

                val stored = runBlocking { repository.settings.first().darkTheme }
                assertWithMessage(
                    "the window's light-icon appearance never tracked the setting; " +
                        "repository reports darkTheme=$stored",
                ).that(arrived).isTrue()
            }
        }
}
