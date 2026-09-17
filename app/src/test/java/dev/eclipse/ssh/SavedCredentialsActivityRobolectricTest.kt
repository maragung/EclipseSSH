package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.SavedCredentialsActivity
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Saved credentials, in its own window.
 *
 * Every destination in this batch is asserted twice over: from Settings, for the wiring that opens
 * it, and here, for what the window actually shows. This is the second half. Holding both in one
 * class is only possible if the activity is launched for real, and launching it here rather than
 * recording a `startActivity` is the point — an intent that names the right class proves nothing
 * about whether the screen behind it composes.
 *
 * What this class can honestly reach is narrower than the screen, and that is a property of the
 * suite rather than a choice. The vault is empty under Robolectric and stays that way: this class
 * writes no credential, and the key that would encrypt one lives in AndroidKeyStore, which the host
 * JVM does not have. So the list of saved hosts, a per-host "Forget", and the clear-all
 * confirmation are all out of reach — the first because nothing can be put into it, the last two
 * because they clear real stores and because a Compose `AlertDialog` never settles `waitForIdle`.
 * The empty state is the state this suite is actually in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SavedCredentialsActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<SavedCredentialsActivity>()

    /**
     * What the card says with nothing saved, copied from the screen's own string with its line
     * breaks intact so the two fragments below concatenate to exactly the text that is rendered.
     */
    private val emptyState =
        "Nothing is saved yet. A password, private key or passphrase is stored only when you " +
            "ask for one to be saved, while connecting or from a host's own form, and this " +
            "screen is where it is forgotten again."

    /** A few frames of work, for the reads and recompositions that follow the window opening. */
    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied"; `idleFor` rather
     * than `idle` so the real work behind the DataStore read comes due. The screen's content is a
     * flow whose first emission is not synchronous, so asserting after a fixed number of frames is
     * a race even when the frames are pumped.
     */
    private fun pumpUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        check(condition()) { "timed out after ${timeoutMs}ms: ${describe()}" }
    }

    /**
     * The window names itself and states the empty vault rather than leaving the card blank.
     *
     * The empty state is asserted *after* it has composed, and that ordering is the whole shape of
     * the test. The screen collects the credential map into a deliberately nullable state so that
     * "nothing is saved yet" cannot be shown before the store has answered; catching a regression
     * that seeded that collection with an empty map — what a list screen normally does, and what
     * would put this sentence in front of a user who has three hosts saved — would mean observing a
     * frame *before* the store's first emission. This class cannot: everything it sees is a settled
     * tree. So what is asserted is the state the screen settles into, and the nullable collection is
     * not claimed to be verified here.
     *
     * The bar title and the card's header are both asserted because they are separate nodes: the
     * header is uppercased by `SettingsSection`, so the two read differently and each is reachable
     * by its own exact string — the match is case-sensitive on purpose, since an `ignoreCase` match
     * would find the pair and fail rather than pin either. The shell's navigation icon goes with
     * them: it is the other half of "a window, not a dialog", and a dialog has none.
     */
    @Test
    fun theWindowNamesItselfAndStatesTheEmptyVault() {
        compose.waitForIdle()
        pump()

        compose.onNodeWithText("Saved credentials").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText("SAVED CREDENTIALS").assertIsDisplayed()

        pumpUntil(describe = { "the empty state never composed" }) {
            compose.onAllNodesWithText(emptyState).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(emptyState).assertIsDisplayed()

        // No row, and therefore no per-host action: an entry whose secrets were all forgotten is
        // left standing with nothing in it, and the screen filters those out — but with no entries
        // at all this holds under either reading of the map, so it pins that no row is drawn rather
        // than that the filter works. The filter's own evidence needs a vault with something in it,
        // which is exactly what this suite cannot build.
        assertWithMessage("a \"Forget\" row action was drawn with nothing saved")
            .that(compose.onAllNodesWithText("Forget").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * The clear-all control is on screen with nothing saved, and off.
     *
     * The screen does not hide it when the list is empty, which is why there is a presence assertion
     * to make at all: it sits outside the card — inside it, it would read as a row for a host called
     * "Forget all" — and is disabled by `enabled = savedCount > 0` instead. The two assertions
     * together are that pair, and the disabled one is the reason a user cannot clear an empty vault.
     *
     * It is asserted rather than tapped. A tap opens the confirmation, and a Compose `AlertDialog`
     * never settles `waitForIdle`; the write behind that confirmation also clears the session
     * registry, a real store this class has no business emptying. The empty state is awaited first
     * because `savedCount` is 0 before the store answers as well, so a disabled button observed
     * there would say nothing about the settled empty vault.
     */
    @Test
    fun theClearAllControlIsShownButOffWhileNothingIsSaved() {
        compose.waitForIdle()
        pump()
        pumpUntil(describe = { "the empty state never composed" }) {
            compose.onAllNodesWithText(emptyState).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Forget all").assertIsDisplayed()
        compose.onNodeWithText("Forget all").assertIsNotEnabled()
    }
}
