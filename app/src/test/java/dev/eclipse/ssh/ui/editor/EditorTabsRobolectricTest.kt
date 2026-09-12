package dev.eclipse.ssh.ui.editor

import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.EclipseApp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The tab strip over the live editor window: what a second open does, and what closing does.
 *
 * The window half of that contract is already pinned — [TextEditorActivityRobolectricTest] shows
 * a second token routes into the same activity without recreating it — so this suite is the
 * content half: the second tab appears and renders, switching does not cost either file its text,
 * a re-open of an open file selects rather than duplicates, and the last tab's close ends the
 * window while a dirty tab's close is intercepted by the discard question.
 *
 * The same Robolectric rules the other dialog suites learned apply: a Compose `AlertDialog` never
 * settles an idle-driven wait, so the discard question is asserted at window level through
 * [ShadowDialog] and dismissed through the dialog's own back dispatcher, and every wait after a
 * dialog could be up is hand-pumped frames (`pumpUntil`), never `waitForIdle`. Clicks happen only
 * on the main window — nothing here drives a button inside an open dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class EditorTabsRobolectricTest {

    /**
     * Every file this suite opens, in one provider: `read` serves the registered bytes, `write`
     * records and moves the clock, `stat` reports what the last write left. One provider for all
     * tabs is deliberate — re-opening a file deduplicates by providerId *and* path, so the
     * duplicate-open test must hand the editor the same provider instance the first open used.
     */
    private class MemoryFilesProvider : FileSystemProvider {
        override val providerId: String = "test"

        private class Held(var bytes: ByteArray, var modified: Long)

        private val files = linkedMapOf<String, Held>()
        val writes = mutableListOf<String>()

        fun add(path: String, contents: String) {
            files[path] = Held(contents.toByteArray(), 1_000L)
        }

        private fun name(path: String) = path.substringAfterLast('/')

        override suspend fun homePath(): String? = null
        override suspend fun parentPath(path: String): String? = null
        override suspend fun list(path: String): List<FsEntry> = emptyList()
        override suspend fun stat(path: String): FsEntry? = files[path]?.let { held ->
            FsEntry(
                name = name(path),
                path = path,
                isDirectory = false,
                size = held.bytes.size.toLong(),
                modifiedEpochMillis = held.modified,
                permissions = null,
                mimeType = "text/plain",
            )
        }

        override suspend fun read(path: String): ByteArray =
            files[path]?.bytes ?: error("no such file: $path")

        override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) {
            writes += path
            files.getOrPut(path) { Held(data, 1_000L) }.let {
                it.bytes = data
                it.modified += 1_000
            }
        }

        override suspend fun createFile(parentPath: String, name: String): FsEntry =
            error("unused by the tab suite")
        override suspend fun createDirectory(parentPath: String, name: String) =
            error("unused by the tab suite")
        override suspend fun rename(path: String, newName: String) = error("unused by the tab suite")
        override suspend fun copy(sourcePath: String, targetDirectoryPath: String) =
            error("unused by the tab suite")
        override suspend fun move(path: String, targetDirectoryPath: String) =
            error("unused by the tab suite")
        override suspend fun delete(path: String) = error("unused by the tab suite")
        override suspend fun setPermissions(path: String, mode: Int) =
            error("unused by the tab suite")
        override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> =
            emptyList()
    }

    private val provider = MemoryFilesProvider().apply { add(firstPath, firstText) }

    private companion object {
        const val firstName = "notes.txt"
        const val firstPath = "/tmp/notes.txt"
        const val firstText = "the first file"

        const val secondName = "server.js"
        const val secondPath = "/srv/server.js"
        const val secondText = "the second file"
    }

    private fun request(name: String, path: String, contents: String) = EditorRequest(
        entry = FsEntry(
            name = name,
            path = path,
            isDirectory = false,
            size = contents.length.toLong(),
            modifiedEpochMillis = 1_000L,
            permissions = null,
            mimeType = "text/plain",
        ),
        provider = provider,
    )

    /**
     * The rule around the real [TextEditorActivity], launched with a live first request the way
     * the Files sheet launches it — the same extraction `createAndroidComposeRule` itself uses for
     * an `ActivityScenarioRule`, copied rather than reused because it is private in the compose
     * test library. The scenario rule is kept beside the compose wrapper because the compose rule
     * exposes only `activity` — gone the moment the window finishes — while the scenario still
     * answers lifecycle questions about a finished window. Later opens go through `onEditorToken`
     * inside the tests, the route a `singleTop` new-intent delivery takes.
     */
    private val scenarioRule = ActivityScenarioRule<TextEditorActivity>(
        Intent(ApplicationProvider.getApplicationContext(), TextEditorActivity::class.java)
            .putExtra(TextEditorActivity.EXTRA_REQUEST_TOKEN, EditorRequests.put(request(firstName, firstPath, firstText))),
    )

    @get:Rule
    val compose = AndroidComposeTestRule(scenarioRule) { rule ->
        var activity: TextEditorActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity)
    }

    /** A few frames of work, for the recompositions that follow a click or a token. */
    private fun pump(frames: Int = 12) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    /**
     * Drives frames and main-looper work until [condition] holds — hand-pumped rather than
     * `waitForIdle`, which never settles once a dialog window is up.
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

    /** Waits until some node carries [text] (as a substring, the way a document renders inside it). */
    private fun awaitText(text: String) {
        pumpUntil(describe = { "\"$text\" never composed" }) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits until no node carries [text], for proving a switch actually swapped the body. */
    private fun awaitTextGone(text: String) {
        pumpUntil(describe = { "\"$text\" never left the composition" }) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()
        }
    }

    /** The number of nodes whose whole text is [text] — how tab labels and titles are counted. */
    private fun countNamed(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /** Delivers a live request into the open window, the way a new intent's token would arrive. */
    private fun openTab(name: String, path: String, contents: String) {
        provider.add(path, contents)
        val token = EditorRequests.put(request(name, path, contents))
        scenarioRule.scenario.onActivity { it.onEditorToken(token) }
    }

    /** The tab strip's clickable label for [name] — `hasClickAction` tells it from the toolbar title. */
    private fun tabLabel(name: String) = compose.onNode(hasText(name) and hasClickAction())

    @Test
    fun aSecondTokenOpensASecondTabAndItsTextRenders() {
        awaitText(firstText)
        openTab(secondName, secondPath, secondText)

        // The new tab arrives selected: its name is on screen and its document rendered. The name
        // count is at least two — the strip's label beyond the toolbar's title — because a strip
        // with one entry is a title change, not a second tab.
        awaitText(secondText)
        pumpUntil(describe = { "no second tab for $secondName" }) { countNamed(secondName) >= 2 }
    }

    @Test
    fun switchingTabsPreservesEachFileSText() {
        awaitText(firstText)
        openTab(secondName, secondPath, secondText)
        awaitText(secondText)

        // Back to the first: its text must still be there — a switch that loses the body is the
        // one bug a tabbed editor can quietly ship.
        tabLabel(firstName).performClick()
        awaitText(firstText)
        awaitTextGone(secondText)

        // And forth again, so the assertion is a round trip and not a one-way coincidence.
        tabLabel(secondName).performClick()
        awaitText(secondText)
        awaitTextGone(firstText)

        tabLabel(firstName).performClick()
        awaitText(firstText)
    }

    @Test
    fun reopeningAnOpenFileSelectsItRatherThanOpeningItTwice() {
        awaitText(firstText)
        // With one tab there is no strip, so exactly one node carries the name: the toolbar title.
        pumpUntil(describe = { "the toolbar never titled $firstName" }) { countNamed(firstName) == 1 }

        // Same provider, same path — the identity a duplicate open keys on.
        openTab(firstName, firstPath, firstText)
        pump()

        // Still one: a second tab would have composed a strip and put its own label beside the
        // title. And the file itself is still the one showing.
        assertThat(countNamed(firstName)).isEqualTo(1)
        awaitText(firstText)
        assertThat(scenarioRule.scenario.state).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun aStaleTokenIsIgnoredAndTheOpenEditorStaysUp() {
        awaitText(firstText)

        // A spent token (recents re-delivered an intent whose token was consumed on arrival) and
        // a null one (no extra at all): neither may finish the window nor disturb what is open.
        val spent = EditorRequests.put(request(secondName, secondPath, secondText))
        checkNotNull(EditorRequests.take(spent))
        scenarioRule.scenario.onActivity { it.onEditorToken(spent) }
        scenarioRule.scenario.onActivity { it.onEditorToken(null) }
        pump()

        assertThat(scenarioRule.scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        assertThat(countNamed(secondName)).isEqualTo(0)
        awaitText(firstText)
    }

    @Test
    fun closingTheLastTabFinishesTheWindow() {
        awaitText(firstText)

        // A clean tab's close is a command, not a question: the toolbar's close is the same
        // request the back gesture makes, and the last tab leaving is the window leaving.
        compose.onNodeWithContentDescription("Close editor").performClick()

        // Read through runCatching because a destroyed activity can make the scenario's own state
        // query throw; what has to hold either way is that the window is no longer up.
        pumpUntil(describe = { "the editor window never finished" }) {
            runCatching { scenarioRule.scenario.state }.getOrNull() != Lifecycle.State.RESUMED
        }
    }

    /**
     * A dirty tab's close is a question, and the question is a dialog window.
     *
     * The dialog is asserted at window level through [ShadowDialog] — a Compose `AlertDialog`
     * never idles under Robolectric — and dismissed through the dialog's own back dispatcher so
     * the rule's teardown does not inherit an open window. This is the suite's most delicate
     * test: it is the only one that types into the live field, so it is the first to drop if the
     * input injection ever flakes here.
     */
    @Test
    fun aDirtyCloseIsInterceptedByTheDiscardQuestion() {
        awaitText(firstText)

        // The editor's own field is the only settable text on a fresh tab (find and go-to-line
        // are closed); indexed off onAllNodes so a future second field is a visible failure
        // rather than a silent mis-target.
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(" typed")
        pumpUntil(describe = { "the edit never marked the tab dirty" }) {
            compose.onAllNodes(hasText("Unsaved changes")).fetchSemanticsNodes().isNotEmpty()
        }

        val before = ShadowDialog.getShownDialogs().size
        compose.onNodeWithContentDescription("Close editor").performClick()
        pumpUntil(describe = { "the close did not raise the discard question" }) {
            ShadowDialog.getShownDialogs().size > before &&
                ShadowDialog.getLatestDialog()?.isShowing == true
        }

        // Intercepted, not executed: the window is still up with the work still in it.
        assertThat(scenarioRule.scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        awaitText(firstText)

        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no discard dialog window" }
        @Suppress("DEPRECATION")
        dialog.onBackPressed()
        pumpUntil(describe = { "the discard dialog never closed" }) { !dialog.isShowing }
        assertThat(scenarioRule.scenario.state).isEqualTo(Lifecycle.State.RESUMED)
    }
}
