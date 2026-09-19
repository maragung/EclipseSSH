package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
 * The transfers window, as a window: which rows one transfer's own state can serve, and what happens
 * to the answer when a row is tapped.
 *
 * This used to be `TransfersActionsRobolectricTest`, over a `ModalBottomSheet` opened by long-pressing
 * a card in the Transfers tab. The matrix it pinned has not changed — the control the status asks for,
 * the file actions only when the local file exists *in full*, Copy details always — and it is pinned
 * here now because the surface moved: a sheet lives inside the workspace's composition, a window is a
 * separate activity with its own intent, and nothing about the first proves anything about the second.
 *
 * What only this level can show is the pairing. The subject is a plain id in the intent, read back
 * from [TransferRepository], so a window opened on the right class with an id that names nothing, or
 * one that kept showing a transfer another window had already removed, would pass every test the rows
 * have and fail the user on the first tap. Both of those are asserted below, along with the transfer
 * that is still moving while its actions are on screen — which is the whole reason the id is used
 * rather than a snapshot.
 *
 * The rows themselves are deliberately *not* asserted to do anything: every one of them files an
 * [ActionAnswer] and closes, because the workspace is the only place a transfer is paused, opened or
 * copied from. What that handoff costs and what it buys is `TransfersActionsRobolectricTest`'s
 * subject now — it is the suite that drives the workspace and watches the answer land.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TransferActionsActivityRobolectricTest {

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

    /** A completed download: the file it produced exists in full, so every file action is servable. */
    private fun completedDownload(id: String) = TransferItem(
        id = id,
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

    /**
     * A running download: no local file at all, so the only rows are the control its status asks for,
     * the cancellation, and the always-there facts row.
     */
    @Test
    fun aRunningTransferIsOfferedTheControlItsStatusAsksForAndNothingItCannotServe() {
        val item = completedDownload("window-running").copy(
            name = "release-bundle.tar.gz",
            status = TransferStatus.RUNNING,
            progress = 0.1f,
            localUri = null,
            remotePath = "/srv/releases/release-bundle.tar.gz",
            transferredBytes = 0,
        )
        seed(item)

        launchWindow(item).use {
            awaitRow("Pause")
            awaitRow("Cancel transfer")
            awaitRow("Copy details")
            assertRowAbsent("Resume")
            assertRowAbsent("View file")
            assertRowAbsent("Edit")
            assertRowAbsent("Open with")
            assertRowAbsent("Remove from list")
        }
    }

    /** The finished download: the file, and every way this app can hand it over. */
    @Test
    fun aCompletedDownloadIsOfferedTheFileItProduced() {
        val item = completedDownload("window-complete")
        seed(item)

        launchWindow(item).use {
            awaitRow("View file")
            awaitRow("Edit")
            awaitRow("Open")
            awaitRow("Open with")
            awaitRow("Copy details")
            awaitRow("Remove from list")
            assertRowAbsent("Pause")
            assertRowAbsent("Resume")
            assertRowAbsent("Cancel transfer")
        }
    }

    /**
     * A paused download has a localUri but only a *prefix* of the file behind it.
     *
     * This is the row that separates "actions appear when a localUri exists" from the actual rule: a
     * download's file is whole only at COMPLETE, and previewing or editing a prefix would show content
     * the user would take for the whole file.
     */
    @Test
    fun aPausedDownloadIsOfferedResumeButNotTheHalfOfTheFileOnDisk() {
        val item = completedDownload("window-paused").copy(
            name = "nightly-dump.sql",
            status = TransferStatus.PAUSED,
            progress = 0.4f,
            remotePath = "/db/nightly-dump.sql",
            transferredBytes = 377_487_360,
            totalBytes = 943_718_400,
        )
        seed(item)

        launchWindow(item).use {
            awaitRow("Resume")
            awaitRow("Cancel transfer")
            awaitRow("Copy details")
            assertRowAbsent("Pause")
            assertRowAbsent("View file")
            assertRowAbsent("Edit")
            assertRowAbsent("Open with")
            assertRowAbsent("Remove from list")
        }
    }

    /**
     * The header counts up while the window is open, because the window reads the transfer rather than
     * a copy of it.
     *
     * This is what the id-in-the-intent buys over the token the other windows use, and it is the
     * reason the transfer window is the one that could not be given a snapshot: progress reaches the
     * header through the repository's own flow, so a download that finishes while its actions are on
     * screen stops being a running download in the row above them.
     */
    @Test
    fun theHeaderFollowsTheTransferWhileTheWindowIsOpen() {
        val item = completedDownload("window-live").copy(
            name = "nightly-dump.sql",
            status = TransferStatus.RUNNING,
            progress = 0.4f,
            remotePath = "/db/nightly-dump.sql",
            localUri = null,
            transferredBytes = 377_487_360,
            totalBytes = 943_718_400,
        )
        seed(item)

        launchWindow(item).use { scenario ->
            awaitRow("Pause")
            // The same row, written through the repository the window reads: the strings above are
            // derived from the item, so a header that stopped moving shows the old ones.
            scenario.onActivity { activity ->
                runBlocking { activity.transferRepository.save(item.copy(status = TransferStatus.COMPLETE, progress = 1f)) }
            }
            pumpUntil(describe = { "the header never caught up with the transfer's own state" }) {
                compose.onAllNodes(hasText("Download · Production edge · complete")).fetchSemanticsNodes().isNotEmpty()
            }
            // And the rows follow it, since they are derived from the same item: the control a running
            // transfer had is gone and the file it finished writing is offered.
            awaitRow("View file")
            assertRowAbsent("Pause")
        }
    }

    /**
     * A row files its answer and closes the window — the whole of what a row does.
     *
     * Pause deliberately, and not one of the file rows: this level's claim is that the *click* reaches
     * the workspace, and Pause is the one whose answer is a bare id with no provider resolution behind
     * it. What the workspace then does with it is `TransfersActionsRobolectricTest`'s.
     */
    @Test
    fun aRowFilesItsAnswerAndClosesTheWindow() {
        val item = completedDownload("window-answer").copy(
            name = "release-bundle.tar.gz",
            status = TransferStatus.RUNNING,
            localUri = null,
        )
        seed(item)

        launchWindow(item).use { scenario ->
            awaitRow("Pause")
            compose.onNode(hasText("Pause") and hasClickAction()).performClick()

            pumpUntil(describe = { "the row's answer never reached the workspace" }) {
                ActionRequests.takeAnswer() == ActionAnswer.TransferAction(item.id, TransferActionKind.PAUSE)
            }
            pumpUntil(describe = { "the window stayed up after its row was tapped" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    /**
     * A transfer that leaves the list takes its window with it.
     *
     * "Remove from list" is one of this window's own rows, so this is reachable without a race: the
     * row deletes the transfer, the window watches the id it was opened on, and an id that is no
     * longer in the repository means there is nothing left to offer. The window has to close rather
     * than draw a menu for a row that is gone.
     */
    @Test
    fun theWindowClosesWhenItsTransferLeavesTheList() {
        val item = completedDownload("window-removed")
        seed(item)

        launchWindow(item).use { scenario ->
            awaitRow("Copy details")
            scenario.onActivity { activity ->
                runBlocking { activity.transferRepository.delete(item.id) }
            }
            pumpUntil(describe = { "the window stayed open on a transfer that no longer exists" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    /**
     * An intent carrying no id opens nothing, rather than a window on an arbitrary transfer.
     *
     * The intent is the one thing about this window the system can re-deliver after the process is
     * gone, and there is no default transfer for it to fall back to: the first transfer in the list
     * would be a menu of actions for something the user never touched.
     */
    @Test
    fun anIntentWithNoIdOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(Intent(context, TransferActionsActivity::class.java)).use { scenario ->
            pumpUntil(describe = { "an intent with no transfer id opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    // ---------------------------------------------------------------- driving the app

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun launchWindow(item: TransferItem): ActivityScenario<TransferActionsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch(TransferActionsActivity.intent(context, item))
    }

    /**
     * Puts a transfer in the store the window will read, through the app's own repository.
     *
     * The repository is taken off the workspace's view model — the same reason
     * `HostFormActivityRobolectricTest` takes its host repository off the form: it is an `@Inject`
     * field on a concrete activity, so an instance of the app's own components is the only handle on
     * the store the window under test will be reading. A second repository would write somewhere else
     * and the window would find nothing.
     *
     * The workspace is launched for exactly one write and closed again. Nothing in this suite is
     * driven through it: this is a window suite, and the surface under test is the one it opens.
     */
    private fun seed(item: TransferItem) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
                val repository = MainViewModel::class.java.getDeclaredField("transferRepository")
                    .apply { isAccessible = true }
                    .get(viewModel) as TransferRepository
                runBlocking { repository.save(item) }
            }
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
        assertThat(compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes())
            .isEmpty()
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

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
