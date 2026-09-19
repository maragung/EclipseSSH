package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.data.settings.SnippetRepository
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.SnippetActionKind
import dev.eclipse.ssh.ui.snippets.SnippetsActivity
import java.time.Duration
import kotlinx.coroutines.flow.first
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
 * The snippets window, as a window: what it lists, what a row files back, and the one row it performs.
 *
 * This used to be `SnippetsSheetRobolectricTest`, over a `ModalBottomSheet` opened from the terminal's
 * overflow. The list is the same list and the labels are the same labels, but three things about it are
 * only true of a window, and they are what this suite is for.
 *
 * **It carries no subject.** Every other promoted window takes a token or an id because the thing it
 * acts on is held by the workspace; this one takes nothing, because the store it lists is a singleton
 * it injects. That is why the first test below is about the *store* rather than about an extra: a
 * window that read a snapshot taken at launch would show the same list forever.
 *
 * **Delete is performed, not answered.** The window already has the repository in order to draw the
 * rows, so the write is one it can make itself - and making it is what lets the row go under the finger
 * that removed it. Answered instead, the row would sit there until the workspace came back to the front,
 * which is not until the window closes. `deletingASnippetRemovesTheRowAndKeepsTheWindowOpen` is the
 * assertion that pins that difference, and it is the one test here that could not have been written
 * against the sheet.
 *
 * **Insert and Save current are answered.** Both need the terminal: the session on screen decides which
 * shell a command is typed into, and the naming dialog names the command bar's live text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SnippetsActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * Everything this suite needs in place before a window is opened, in the order it is needed.
     *
     * One method rather than three, because JUnit does not promise an order between `@Before` methods
     * of the same class and two of these three genuinely depend on the one before: the store cannot be
     * read without the key the cipher wants, and a store with another test's rows in it is not the
     * store this test is about.
     *
     * **The keystore.** Snippets are stored through the same `AndroidKeyStore`-backed cipher host
     * passwords are, and `AndroidKeyStore` is the one platform piece Robolectric has none of - so
     * without the stand-in every write would fail behind the repository's own error handling and the
     * window would list nothing whatever the test did. See [StandInAndroidKeyStore] for what it does
     * and does not stand in for.
     *
     * **The answer slot.** Process-wide, so a leftover from another test in this JVM would be read as
     * this test's answer. Emptied before rather than after, so a test that fails mid-flight cannot
     * poison the next one.
     *
     * **The store.** A DataStore file that outlives a single test method in this JVM - and outlives
     * other classes too, which is why `SnippetRepositoryTest` numbers its tests and scopes every
     * assertion to the ids it wrote. This suite cannot do that: what it asserts is which rows a
     * *window* is showing, so "the list is empty" has to mean the store is empty rather than that no
     * other test has run yet. Emptied through the repository the window itself reads, taken off a
     * window opened for exactly this - a second repository built here would write somewhere else.
     */
    @Before
    fun prepareTheStoreAndTheSlots() {
        StandInAndroidKeyStore.install()
        ActionRequests.takeAnswer()
        launch().use { scenario ->
            scenario.onActivity { activity ->
                runBlocking {
                    activity.snippetRepository.snippets.first().forEach { activity.snippetRepository.delete(it.id) }
                }
            }
        }
    }

    @After
    fun uninstallKeyStore() {
        StandInAndroidKeyStore.uninstall()
    }

    /**
     * What the window lists is what the store holds, and it holds it *live*.
     *
     * The write happens after the window is already on screen, through the same repository the window
     * is collecting - so a row that appears proves the window is watching the store rather than a copy
     * of it taken at launch. That is the whole reason this window has no subject: the store is the
     * subject, and it already has a `Flow`.
     */
    @Test
    fun theListFollowsTheStoreWhileTheWindowIsOpen() {
        launch().use { scenario ->
            awaitText("No snippets yet. Save a command to reuse it later.")
            scenario.onActivity { activity ->
                runBlocking { activity.snippetRepository.save(snippet("one", "Production deploy", "kubectl rollout restart deploy/api")) }
            }
            awaitText("Production deploy")
            awaitText("kubectl rollout restart deploy/api")
        }
    }

    /**
     * Tapping a row answers Insert with that row's id, and closes.
     *
     * The id and not the command: the terminal reads the command out of the list it is already showing,
     * so what crosses the process boundary is a name for a row and nothing that could go stale.
     */
    @Test
    fun tappingARowFilesAnInsertForThatSnippetAndCloses() {
        val saved = seed(snippet("insert", "Tail the log", "tail -f /var/log/nginx/error.log"))
        launch().use { scenario ->
            compose.onNode(hasText("Tail the log") and hasClickAction()).performClick()

            awaitAnswer(
                ActionAnswer.SnippetAction(saved.id, SnippetActionKind.INSERT),
                describe = { "the row's answer never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its row was tapped" })
        }
    }

    /**
     * "Save current command" answers with no snippet at all, and closes.
     *
     * A null id is the honest shape for it: the row is not a verb on a snippet, it is a request to name
     * the command the user has just typed, and that command is live text in the terminal's composition.
     * A window that carried a copy of it would name the wrong line the moment the user typed anything
     * else after opening this one.
     */
    @Test
    fun saveCurrentAnswersWithNoSnippetAndCloses() {
        launch().use { scenario ->
            compose.onNode(hasText("Save current command") and hasClickAction()).performClick()

            awaitAnswer(
                ActionAnswer.SnippetAction(null, SnippetActionKind.SAVE_CURRENT),
                describe = { "the save row's answer never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after the save row was tapped" })
        }
    }

    /**
     * Delete is the one row this window performs, so the row goes and the window stays.
     *
     * Both halves matter. The row going is the write landing in the store the window is watching - with
     * no round trip through the workspace, which is stopped while this window is in front of it. The
     * window staying is the difference a window makes over the sheet it replaced: a sheet's delete
     * closed the sheet, so removing three stale snippets meant opening it three times.
     *
     * The delete button carries a content description rather than a label, so it is found by that.
     */
    @Test
    fun deletingASnippetRemovesTheRowAndKeepsTheWindowOpen() {
        val doomed = seed(snippet("delete", "Old staging host", "ssh deploy@staging-01"))
        val kept = seed(snippet("keep", "Tail the log", "tail -f /var/log/nginx/error.log"))
        launch().use { scenario ->
            awaitText("Old staging host")
            compose.onNode(hasContentDescription("Delete snippet")).performClick()

            pumpUntil(describe = { "the deleted snippet's row never went" }) {
                compose.onAllNodes(hasText("Old staging host")).fetchSemanticsNodes().isEmpty()
            }
            // Still there, still listing: the delete did not close the window and did not take the
            // other row with it.
            compose.onNode(hasText("Tail the log")).assertIsDisplayed()
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            assertThat(idsInStore()).containsExactly(kept.id)
            assertThat(idsInStore()).doesNotContain(doomed.id)
        }
    }

    /** An empty store says so, rather than showing a blank window with nothing to explain it. */
    @Test
    fun anEmptyStoreSaysSo() {
        launch().use {
            awaitText("No snippets yet. Save a command to reuse it later.")
        }
    }

    /** Nothing is carried, so an intent with no extras opens the list rather than nothing. */
    @Test
    fun anIntentWithNoExtrasOpensTheList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        seed(snippet("plain", "Tail the log", "tail -f /var/log/nginx/error.log"))
        ActivityScenario.launch<SnippetsActivity>(Intent(context, SnippetsActivity::class.java)).use {
            awaitText("Tail the log")
        }
    }

    // ---------------------------------------------------------------- driving the app

    private fun launch(): ActivityScenario<SnippetsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<SnippetsActivity>(SnippetsActivity.intent(context))
    }

    /**
     * Puts a snippet in the store the window will read, through the app's own repository.
     *
     * The repository is taken off the window itself - it is an `@Inject` field on a concrete activity,
     * so an instance of the app's own components is the only handle on the store the window under test
     * will be reading. A second repository built here would write somewhere else, and the window would
     * find nothing. The window is opened for exactly one write and closed again; nothing else in this
     * suite is driven through it.
     */
    private fun seed(snippet: Snippet): Snippet {
        launch().use { scenario ->
            scenario.onActivity { activity -> runBlocking { activity.snippetRepository.save(snippet) } }
        }
        return snippet
    }

    /** The ids the store holds, read back through the same repository the window reads. */
    private fun idsInStore(): List<String> {
        var ids = emptyList<String>()
        launch().use { scenario ->
            scenario.onActivity { activity -> ids = runBlocking { activity.snippetRepository.snippets.first() }.map { it.id } }
        }
        return ids
    }

    private fun snippet(id: String, label: String, command: String) = Snippet(id = id, label = label, command = command)

    /** A text node that must be there, and on screen. */
    private fun awaitText(text: String) {
        pumpUntil(describe = { "the window never showed \"$text\"" }) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(text)).assertIsDisplayed()
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * The shape the suites that drive live state all share — see
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
     * account of why the failure it produces is the confusing one.
     *
     * A window that reached DESTROYED before this looked counts too, because a destroyed activity
     * cannot be showing a menu: `onActivity` throws once there is nothing live to run on. What cannot
     * pass is a window that is neither finishing nor gone.
     */
    private fun awaitClosing(scenario: ActivityScenario<SnippetsActivity>, describe: () -> String) {
        var closing = false
        pumpUntil(describe = describe) {
            runCatching { scenario.onActivity { activity -> closing = activity.isFinishing } }
            closing || scenario.state == Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
