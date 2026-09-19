package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.actions.ArchiveEntryActionKind
import dev.eclipse.ssh.ui.archive.ArchiveEntryActionsActivity
import java.time.Duration
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The archive entry window, as a window: what one entry inside an archive is offered, and what
 * happens when a row is tapped.
 *
 * This used to be a `ModalBottomSheet` over the archive browser. The rows are the sheet's rows, and
 * they are asserted here rather than assumed because the surface moved - a sheet is a slot inside the
 * browser's composition and a window is a separate activity reached by an intent, so nothing about
 * the first proves anything about the second.
 *
 * The window's vocabulary is the archive's, not a file manager's - no Rename, Move or Delete, because
 * the archive is read-only where it stands on the server - and the two shapes that are most easily
 * got wrong are the ones the browser alone can decide:
 *
 * - **Preview only where the range read exists.** A ZIP's entry can be fetched by seeking to its
 *   offset; a TAR inside a compressed stream cannot, so its honest row is Extract. That verdict is
 *   the caller's, travelling as a boolean extra, and both of its states are asserted below - a
 *   window that derived it from the file extension would offer Preview on a TAR.
 * - **A folder has one verb.** Extract-as-a-tree, because opening it is what tapping the row already
 *   does. A folder also has no size to show, while a file's size is drawn above the rows.
 *
 * The rows themselves act nowhere: each files an [ActionAnswer] and closes, because the entry is a row
 * of a tree the archive browser owns, and Preview opens a second window, Extract arms a picker that
 * outlives this one and Copy path writes to the clipboard - all of them the browser's own helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ArchiveEntryActionsActivityRobolectricTest {

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
    }

    /** A file inside the archive, small enough that its size line is the plain one. */
    private fun file(name: String = "readme.txt") = ArchiveEntry(
        path = "docs/$name",
        isDirectory = false,
        size = 512,
        compressedSize = 218,
        modifiedEpochMillis = 1_755_000_000_000,
        method = 8,
        dataOffset = 1_024,
        encrypted = false,
    )

    /** A directory entry, which the container reports a size for whether or not anyone means it. */
    private fun folder(name: String = "docs") = ArchiveEntry(
        path = name,
        isDirectory = true,
        size = 0,
        compressedSize = null,
        modifiedEpochMillis = 1_755_000_000_000,
        method = null,
        dataOffset = null,
        encrypted = false,
    )

    /**
     * A file whose bytes can be fetched by range - ZIP - is offered Preview *and* Download.
     *
     * The order is part of the assertion: Preview is the row that answers without moving the entry's
     * bytes to the device, so it sits above the one that writes them somewhere the user has to pick.
     * Copy path and Properties close the list, and both are unconditional - they are the two things
     * every entry has: a path inside the archive, and a header.
     */
    @Test
    fun aRangeReadableFileIsOfferedPreviewDownloadPathAndProperties() {
        launch(file(), canReadEntry = true).use {
            awaitRow("Preview")
            awaitRow("Download")
            awaitRow("Copy path")
            awaitRow("Properties")
            assertThat(labelsOnScreen())
                .containsExactly("Preview", "Download", "Copy path", "Properties").inOrder()
        }
    }

    /**
     * The same file in an archive with no ranged read loses Preview and Download, and gains Extract.
     *
     * One row, not two: pulling a single entry out of a TAR means streaming the whole thing past the
     * reader, and the same act that does that is the act that writes the one entry to a place the
     * user picks. Two names for one verb would be a promise the format cannot keep.
     */
    @Test
    fun aFileWithoutARangeReadIsOfferedOnlyExtractBesidesTheCommonRows() {
        launch(file("notes.txt"), canReadEntry = false).use {
            awaitRow("Extract")
            assertRowAbsent("Preview")
            assertRowAbsent("Download")
            // The two rows that are about the entry rather than its bytes survive either way.
            awaitRow("Copy path")
            awaitRow("Properties")
        }
    }

    /**
     * A folder has one verb, and no size line.
     *
     * "Extract folder" and not "Extract", because a subtree is a scan-and-collect where a single entry
     * is a range read - different costs the user deserves to see named. And no size: a directory entry
     * carries one in the container, but it is not a size in any sense a person means.
     */
    @Test
    fun aFolderIsOfferedOnlyExtractFolderAndShowsNoSize() {
        launch(folder(), canReadEntry = true).use {
            awaitRow("Extract folder")
            assertRowAbsent("Extract")
            assertRowAbsent("Preview")
            assertRowAbsent("Download")
            awaitRow("Copy path")
            awaitRow("Properties")
            // The file's own size line, which this window draws above the rows - a folder must not
            // have one, and the label it would carry is the one `describeSize` gives 512 bytes.
            assertThat(compose.onAllNodes(hasText("512 B")).fetchSemanticsNodes()).isEmpty()
        }
    }

    /** A file's size is drawn, in the window's own words rather than as a byte count. */
    @Test
    fun aFilesSizeIsDrawnAboveTheRows() {
        launch(file(), canReadEntry = true).use {
            awaitRow("Preview")
            compose.onNode(hasText("512 B")).assertIsDisplayed()
        }
    }

    /**
     * The window names the entry it is on, by its name inside the archive rather than by its path.
     *
     * The path is already on screen in the listing the window was opened from, and it is one of the
     * rows below for the case where it needs reading carefully - so the title carries the half that
     * identifies the entry, and nothing repeats.
     */
    @Test
    fun theWindowIsTitledWithTheEntrysOwnName() {
        launch(file("readme.txt"), canReadEntry = true).use {
            awaitRow("Preview")
            compose.onNode(hasText("readme.txt")).assertIsDisplayed()
            // The path it lives at is not repeated in the title.
            assertThat(compose.onAllNodes(hasText("docs/readme.txt")).fetchSemanticsNodes()).isEmpty()
        }
    }

    /**
     * A row files its answer and closes the window - the whole of what a row does.
     *
     * Copy path deliberately: it is one of the four the window *cannot* do itself, because the
     * clipboard in this app is the vault-backed one the workspace owns. A row that wrote to the
     * platform clipboard on its own would be a second implementation of copying, and the one the rest
     * of the app uses would not see it.
     */
    @Test
    fun aRowFilesItsAnswerAndClosesTheWindow() {
        launch(file(), canReadEntry = true).use { scenario ->
            awaitRow("Copy path")
            compose.onNode(hasText("Copy path") and hasClickAction()).performClick()

            awaitAnswer(
                ActionAnswer.ArchiveEntryAction(ArchiveEntryActionKind.COPY_PATH),
                describe = { "the row's answer never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its row was tapped" })
        }
    }

    /**
     * A spent token opens nothing, rather than a window on an arbitrary entry.
     *
     * This is the shape the system re-delivers: a rotation, the recents screen, a crash and relaunch.
     * There is no default entry for the window to fall back to, and the first row of the archive would
     * be a menu of actions for an entry the user never touched.
     */
    @Test
    fun aSpentTokenOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A token that was put and then taken, exactly as the window's own first launch would leave it.
        val spent = ActionRequests.put(ActionSubject.ArchiveEntryActions(file()))
        ActionRequests.take(spent)

        ActivityScenario.launch<ArchiveEntryActionsActivity>(
            ArchiveEntryActionsActivity.intent(context, file(), canReadEntry = true)
                .putExtra(ActionRequests.EXTRA_SUBJECT_TOKEN, spent),
        ).use { scenario ->
            pumpUntil(describe = { "a spent subject token opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    /** An intent with no token at all opens nothing, the same way. */
    @Test
    fun anIntentWithNoTokenOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<ArchiveEntryActionsActivity>(Intent(context, ArchiveEntryActionsActivity::class.java))
            .use { scenario ->
                pumpUntil(describe = { "an intent with no subject token opened a window anyway" }) {
                    scenario.state == Lifecycle.State.DESTROYED
                }
            }
    }

    // ---------------------------------------------------------------- driving the app

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun launch(entry: ArchiveEntry, canReadEntry: Boolean): ActivityScenario<ArchiveEntryActionsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<ArchiveEntryActionsActivity>(
            ArchiveEntryActionsActivity.intent(context, entry, canReadEntry),
        )
    }

    /**
     * Every row label on screen, in the order Compose found them.
     *
     * Read off the tree rather than listed from the window's own source, which is the point: the whole
     * claim of the order test is that the drawing order is the sheet's, and a list this suite wrote out
     * a second time would agree with itself whatever the window did.
     */
    private fun labelsOnScreen(): List<String> {
        val known = setOf("Preview", "Download", "Extract", "Extract folder", "Copy path", "Properties")
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                ?.firstOrNull()?.text?.takeIf(known::contains)
        }
    }

    /** A row of the window that must be there, and on screen. */
    private fun awaitRow(label: String) {
        pumpUntil(describe = { "the window's \"$label\" row never appeared" }) {
            compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
    }

    /** A row that must not be there at all. */
    private fun assertRowAbsent(label: String) {
        assertThat(compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()).isEmpty()
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
     * pass is a window that is neither finishing nor gone. The two tests that launch a window which
     * closes *before* it is ever resumed — a spent token, no token — still assert the state directly:
     * that one closes during the launch, and it is a different fact.
     */
    private fun awaitClosing(scenario: ActivityScenario<ArchiveEntryActionsActivity>, describe: () -> String) {
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
