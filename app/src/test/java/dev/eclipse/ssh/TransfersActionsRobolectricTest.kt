package dev.eclipse.ssh

import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.TransferActionKind
import dev.eclipse.ssh.ui.transfers.TransferActionsActivity
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the Transfers tab does with the transfers window, and what the workspace does with its answer.
 *
 * This suite used to pin the rows of a `ModalBottomSheet` that a long-press opened. The rows moved to
 * `TransferActionsActivityRobolectricTest`, which drives that window directly — and what is left here
 * is the half a window suite structurally cannot see: the two moments the workspace is involved. A
 * long-press on a card opens the window, and the answer that window files is acted on when the
 * workspace comes back to the foreground.
 *
 * The second half is the one that needs explaining, because it is the part that would be silently
 * absent. Every window that used to be a sheet hands its choice back through
 * [dev.eclipse.ssh.ui.actions.ActionRequests] rather than acting on it, and the workspace picks it up
 * in `onResume` — the only moment a returning window is noticed. A window that opened perfectly and
 * filed a perfect answer, with nothing on the other end of the handoff, would pass every test the
 * window suite has. So the drain is driven here end to end: the answer is written, the workspace is
 * stopped and resumed the way the real one is when a window above it closes, and the *effect* is
 * asserted — a transfer that leaves the list, an editor that opens.
 *
 * No server is needed. The two transfers the clean install seeds cover the running and completed
 * halves of the matrix on their own, and the rest are written through the repository the view model
 * itself writes through, so the cards these tests long-press are the cards a user would have seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TransfersActionsRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The answer slot is process-wide; a leftover would be read as this test's answer. */
    @Before
    fun drainAnswers() {
        ActionRequests.takeAnswer()
    }

    /**
     * A long-press on a transfer card opens the transfer's window, on that transfer.
     *
     * The id is asserted and not just the class, because those are two different failures: a window
     * that opened blank would be a menu with no transfer behind it, and one that opened on the wrong
     * transfer is a menu of actions for something the user did not touch. Neither is visible from the
     * card that was long-pressed.
     */
    @Test
    fun longPressingATransferCardOpensThatTransfersWindow() {
        val item = TransferItem(
            id = "row-window",
            name = "site-backup.tar",
            direction = TransferDirection.DOWNLOAD,
            hostName = "Production edge",
            progress = 1f,
            status = TransferStatus.COMPLETE,
            sizeLabel = "2.1 GB",
            hostId = "eclipse-demo",
            remotePath = "/srv/backups/site-backup.tar",
            localUri = "content://downloads/site-backup.tar",
            transferredBytes = 2_254_856_192,
            totalBytes = 2_254_856_192,
        )
        inject(item)
        openTransfers(rowName = "site-backup.tar")
        drainStartedActivities()
        longPress("site-backup.tar")

        val started = awaitStartedActivity("dev.eclipse.ssh.ui.transfers.TransferActionsActivity")
        assertThat(started.getStringExtra(TransferActionsActivity.EXTRA_TRANSFER_ID)).isEqualTo(item.id)
    }

    /**
     * The answer is acted on when the workspace comes back, and the effect is the workspace's own.
     *
     * Cancel on a finished transfer is the smallest answer with a visible consequence: the row leaves
     * the list, which is something only the view model can have done. A workspace that opened the
     * window correctly and then ignored it leaves the row sitting there.
     */
    @Test
    fun theWindowsAnswerIsActedOnWhenTheWorkspaceComesBack() {
        val item = TransferItem(
            id = "answer-cancel",
            name = "site-backup.tar",
            direction = TransferDirection.DOWNLOAD,
            hostName = "Production edge",
            progress = 1f,
            status = TransferStatus.COMPLETE,
            sizeLabel = "2.1 GB",
            hostId = "eclipse-demo",
            remotePath = "/srv/backups/site-backup.tar",
            localUri = "content://downloads/site-backup.tar",
            transferredBytes = 2_254_856_192,
            totalBytes = 2_254_856_192,
        )
        inject(item)

        ActionRequests.answer(ActionAnswer.TransferAction(item.id, TransferActionKind.CANCEL))
        resumeTheWorkspace()

        pumpUntil(describe = { "the answer to remove the transfer was never acted on" }) {
            viewModel().uiState.value.transfers.none { it.id == item.id }
        }
    }

    /**
     * A file answer opens the editor, through the same helpers the sheet's Edit row used.
     *
     * This is why the answers come back to the workspace at all rather than being carried out by the
     * window: View and Edit resolve a [dev.eclipse.ssh.data.fs.FileSystemProvider] from the transfer
     * and open a window with it, and that resolution is the workspace's. A second implementation in a
     * second window would be two answers to the same question, and this is the test that would not
     * notice the difference — which is exactly why the resolution is not duplicated.
     */
    @Test
    fun aFileAnswerOpensTheEditor() {
        val item = TransferItem(
            id = "answer-edit",
            name = "release-notes.md",
            direction = TransferDirection.DOWNLOAD,
            hostName = "Production edge",
            progress = 1f,
            status = TransferStatus.COMPLETE,
            sizeLabel = "3 KB",
            hostId = "eclipse-demo",
            remotePath = "/srv/releases/release-notes.md",
            localUri = "content://downloads/release-notes.md",
            transferredBytes = 3_072,
            totalBytes = 3_072,
        )
        inject(item)
        drainStartedActivities()

        ActionRequests.answer(ActionAnswer.TransferAction(item.id, TransferActionKind.EDIT_FILE))
        resumeTheWorkspace()

        val started = awaitStartedActivity("dev.eclipse.ssh.ui.editor.TextEditorActivity")
        assertThat(started.component?.className).isEqualTo("dev.eclipse.ssh.ui.editor.TextEditorActivity")
    }

    /**
     * A second resume does not run the answer a second time.
     *
     * `takeAnswer` empties the slot as it reads it, and this is the test that says so from outside:
     * two resumes in a row — a rotation after a window closed, say — must produce one editor, not two.
     * The alternative is the one a result-code replay has, where the second delivery re-opens a window
     * the user has already used and closed.
     */
    @Test
    fun theAnswerIsActedOnOnceEvenIfTheWorkspaceResumesTwice() {
        val item = TransferItem(
            id = "answer-once",
            name = "release-notes.md",
            direction = TransferDirection.DOWNLOAD,
            hostName = "Production edge",
            progress = 1f,
            status = TransferStatus.COMPLETE,
            sizeLabel = "3 KB",
            hostId = "eclipse-demo",
            remotePath = "/srv/releases/release-notes.md",
            localUri = "content://downloads/release-notes.md",
            transferredBytes = 3_072,
            totalBytes = 3_072,
        )
        inject(item)
        drainStartedActivities()

        ActionRequests.answer(ActionAnswer.TransferAction(item.id, TransferActionKind.EDIT_FILE))
        resumeTheWorkspace()
        awaitStartedActivity("dev.eclipse.ssh.ui.editor.TextEditorActivity")
        resumeTheWorkspace()

        // Every start recorded after the first one, until there are none left. A second editor would
        // have to appear here.
        val shadow = shadowOf(compose.activity.application)
        val started = mutableListOf<String>()
        while (true) {
            val next = shadow.nextStartedActivity ?: break
            next.component?.className?.let(started::add)
        }
        assertThat(started).doesNotContain("dev.eclipse.ssh.ui.editor.TextEditorActivity")
    }

    // ---------------------------------------------------------------- driving the app

    private fun viewModel(): MainViewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /** Reads one of the view model's injected singletons. See `ConnectionMatrixRobolectricTest`. */
    private fun <T> injected(viewModel: MainViewModel, name: String, type: Class<T>): T =
        type.cast(
            MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(viewModel),
        )!!

    /**
     * Writes a transfer row through the repository the app itself writes through, then waits for it
     * to reach the UI — so the card the test long-presses is the card a user would have seen.
     */
    private fun inject(item: TransferItem) {
        val viewModel = viewModel()
        val repository = injected(viewModel, "transferRepository", TransferRepository::class.java)
        runBlocking { repository.save(item) }
        pumpUntil(describe = { "the injected transfer never reached the UI" }) {
            viewModel.uiState.value.transfers.any { it.id == item.id }
        }
    }

    /**
     * Opens the Transfers tab and waits for one row, by the name the caller knows will be in it.
     *
     * The row is named rather than hard-coded to the clean install's seeds, because the seeds are
     * conditional: `seedIfEmpty` runs in the view model's init and only writes its demo rows while
     * the queue is still empty, so a test that injects its own row through the repository first
     * races that check — the injected row suppresses the seeds, and a wait for the seeded
     * "release-bundle.tar.gz" then times out on a screen that is showing exactly what the test
     * wrote. Each test waits for the row it put there (or, on the clean-install test, the seed it
     * knows arrives because nothing was injected).
     */
    private fun openTransfers(rowName: String = "release-bundle.tar.gz") {
        compose.waitForIdle()
        compose.onNode(hasText("Transfers") and hasClickAction()).performClick()
        pumpUntil(describe = { "the \"$rowName\" row never arrived" }) {
            compose.onAllNodes(hasText(rowName) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Long-presses one transfer's card, by the name the card shows. */
    private fun longPress(name: String) {
        compose.onNode(hasText(name) and hasClickAction()).performTouchInput { longClick() }
    }

    /**
     * Stops and resumes the workspace, which is what a window closing above it does.
     *
     * [MainActivity.onResume] is where an answer is taken, and it is deliberately the only place: the
     * alternative is a result callback replayed into a fresh composition, which is the same action
     * taken twice. Nothing else in the suite can drive it, because a window opened by `startActivity`
     * under Robolectric is recorded rather than launched — the resume is simulated here at exactly the
     * point the real one would happen.
     */
    private fun resumeTheWorkspace() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    /** Throws away every activity started so far, so a later peek reports only this test's doing. */
    private fun drainStartedActivities() {
        val shadow = shadowOf(compose.activity.application)
        while (runCatching { shadow.nextStartedActivity }.getOrNull() != null) Unit
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
     * Waits for an activity of [className] to have been started, and takes its intent.
     *
     * Taken rather than peeked, and that is the whole point of the helper: a start recorded by
     * `startActivity` under Robolectric is never launched, so the queue *is* the record of what the
     * workspace asked for — and an intent left in it is indistinguishable from one a later resume
     * asked for. The test that resumes twice asks exactly that question, and with a peek it would
     * always answer "yes": the first resume's own editor would still be sitting there to be counted
     * as the second one's. Nothing is launched by this call, so taking loses no evidence — the
     * returned intent carries the id the window will open on, which is all this level can assert.
     */
    private fun awaitStartedActivity(className: String): Intent {
        var taken: Intent? = null
        pumpUntil(describe = { "$className was never started" }) {
            taken = taken ?: shadowOf(compose.activity.application).nextStartedActivity
            taken?.component?.className == className
        }
        return checkNotNull(taken)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
