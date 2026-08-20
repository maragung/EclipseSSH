package dev.eclipse.ssh

import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "Block screenshots" setting reaching the actual window.
 *
 * `FLAG_SECURE` is the only thing that keeps the terminal out of screenshots, out of screen
 * recordings and — the case that costs nothing to hit — out of the snapshot the system takes for the
 * recents overview when the app goes to the background. A switch in Settings that silently failed to
 * reach the window would be worse than not offering one, so this drives the real activity and reads
 * the flag off `window.attributes`.
 *
 * Its own class rather than another method on [MainActivityLaunchTest], because it writes a
 * preference: `preferencesDataStore` caches one store per delegate for the whole classloader, so a
 * write here would otherwise outlive the method and the launch tests next door are specifically
 * about a fresh install. [restoringTheSetting] puts the value back even when an assertion fails,
 * which is what the first version of this test got wrong — a failed assertion skipped the restore
 * and the leaked `true` then failed [dev.eclipse.ssh.data.settings.SettingsRepositoryTest]'s
 * pristine-defaults check two classes later, turning one broken test into two.
 *
 * Driven through [createAndroidComposeRule] rather than `Robolectric.buildActivity`, and that is not
 * a stylistic choice — the bare-activity version of this class passed alone and failed in a full
 * suite, reporting a setting the repository had definitely stored and a window that never changed.
 * A recomposer takes its frames from the `Choreographer` reachable through
 * `AndroidUiDispatcher.CurrentThread`, which is a *thread-local* that the first Compose test in the
 * JVM populates and every later one inherits; by the time this class ran, that cached dispatcher
 * belonged to an earlier test's looper, so the frames this activity's recomposer was waiting for went
 * somewhere nothing was draining and no amount of hand-idling produced a recomposition. The rule owns
 * the clock instead of borrowing it, which is why the other Compose classes here were never affected.
 * It still launches the real [MainActivity], so the assertions below are still made against a real
 * window.
 *
 * Toggling covers the launched-with-it-already-on case as well, rather than leaving it untested: the
 * flag is applied from a `DisposableEffect` keyed on the setting, and the setting arrives from
 * DataStore asynchronously, so a launch with it enabled composes once with the default and *then*
 * sees `true` — the same two states, in the same order, as the toggle below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MainActivitySecureWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val repository get() = SettingsRepository(RuntimeEnvironment.getApplication())

    private fun secureFlagSet(): Boolean =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /**
     * Runs [body] and writes the setting back to its default afterwards, however [body] ends.
     *
     * The store is shared with every other Robolectric class in this JVM, so leaving it dirty is
     * somebody else's failing test.
     */
    private fun restoringTheSetting(body: () -> Unit) {
        try {
            body()
        } finally {
            runCatching { runBlocking { repository.setBlockScreenshots(false) } }
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, giving up after 20s of real time.
     *
     * [Snapshot.sendApplyNotifications] on every turn is the part that matters. The setting arrives
     * through DataStore, which reads on `Dispatchers.IO`; `collectAsStateWithLifecycle` then writes a
     * snapshot state object from that background thread, and the recomposer is not invalidated until
     * the write is *published*. Bounded by real time rather than by a number of turns, because
     * Robolectric runs every class in one JVM and how much pumping a frame needs depends on what has
     * already run — any fixed count is a test that passes alone and fails in a suite.
     */
    private fun pumpUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 20_000L * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return condition()
    }

    private fun awaitSecureFlag(expected: Boolean) {
        if (pumpUntil { secureFlagSet() == expected }) return
        // Reports what the setting actually is, so a failure says whether the value never reached
        // the store or reached it and never reached the window.
        val stored = runCatching { runBlocking { repository.settings.first().blockScreenshots } }
        assertWithMessage(
            "FLAG_SECURE never became $expected; repository reports blockScreenshots=$stored",
        ).that(secureFlagSet()).isEqualTo(expected)
    }

    @Test
    fun `the window is not secured unless the user asks for it`() = restoringTheSetting {
        // The default has to be off: FLAG_SECURE blocks the user's own screenshots as bluntly as
        // anyone else's, and pasting terminal output into a ticket is ordinary work for this app.
        //
        // Waits for real UI first, so this is an assertion about a composition that has run and read
        // the settings rather than about a window nothing ever touched — which is what the previous
        // version of this test could not tell apart, and admitted as much.
        assertWithMessage("the workspace never rendered, so the settings were never read")
            .that(pumpUntil { compose.onAllNodesWithText("Hosts").fetchSemanticsNodes().isNotEmpty() })
            .isTrue()

        assertThat(secureFlagSet()).isFalse()
    }

    @Test
    fun `enabling the setting secures the window and disabling it releases it again`() = restoringTheSetting {
        runBlocking { repository.setBlockScreenshots(true) }
        awaitSecureFlag(expected = true)

        // And back. The flag is applied from a keyed effect rather than once in onCreate precisely so
        // the switch works while the app is open; if only `addFlags` were wired, turning the setting
        // off would leave the user unable to screenshot their own terminal until the next launch.
        runBlocking { repository.setBlockScreenshots(false) }
        awaitSecureFlag(expected = false)
    }
}
