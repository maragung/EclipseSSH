package dev.eclipse.ssh

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.presentation.MainViewModel
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Transfers tab's long-press action sheet.
 *
 * The sheet's contract is that it offers exactly what the row's own state can serve: the control
 * action matching the current status, the file actions only when the local file exists in full,
 * and Copy details always. Every test here pins one column of that matrix by long-pressing a row
 * of a known shape and asserting both the rows that appear and — just as deliberately — the ones
 * that must not. A sheet that offered "View file" on a half-downloaded file would pass any
 * "the row exists" assertion and still be lying about what is on disk.
 *
 * No server is needed: a transfer is a Room row, and the two the clean install seeds (a running
 * download and a completed upload) cover the no-local-file halves of the matrix on their own. The
 * local-file halves are injected through the repository the view model itself writes through, so
 * the rows the sheet reads are the rows the app would have shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TransfersActionsRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The seeded download: running, and with no local file the sheet could point at. */
    @Test
    fun longPressOnARunningTransferOffersTheControlsItsStateCanServe() {
        openTransfers()
        longPress("release-bundle.tar.gz")

        // The control its status asks for, the removal, and the always-there facts row.
        awaitRow("Pause")
        awaitRow("Cancel transfer")
        awaitRow("Copy details")
        // Nothing else: no resume for a running transfer, no file actions without a local file,
        // and nothing to remove from a list while the transfer is still in it.
        assertRowAbsent("Resume")
        assertRowAbsent("View file")
        assertRowAbsent("Edit")
        assertRowAbsent("Open with")
        assertRowAbsent("Remove from list")
    }

    /**
     * A paused download has a localUri but only a *prefix* of the file behind it, so the sheet
     * offers resume and cancel and deliberately not the file.
     *
     * This is the row that separates "actions appear when a localUri exists" from the actual rule:
     * the file actions arrive when the local file exists *in full*, which for a download is only
     * COMPLETE.
     */
    @Test
    fun longPressOnAPausedDownloadOffersResumeButNotTheHalfOfTheFileOnDisk() {
        inject(
            TransferItem(
                id = "sheet-paused",
                name = "nightly-dump.sql",
                direction = TransferDirection.DOWNLOAD,
                hostName = "Staging cluster",
                progress = 0.4f,
                status = TransferStatus.PAUSED,
                sizeLabel = "900 MB",
                hostId = "eclipse-staging",
                remotePath = "/db/nightly-dump.sql",
                localUri = "content://downloads/nightly-dump.sql",
                transferredBytes = 377_487_360,
                totalBytes = 943_718_400,
            ),
        )
        openTransfers(rowName = "nightly-dump.sql")
        longPress("nightly-dump.sql")

        awaitRow("Resume")
        awaitRow("Cancel transfer")
        awaitRow("Copy details")
        assertRowAbsent("Pause")
        assertRowAbsent("View file")
        assertRowAbsent("Edit")
        assertRowAbsent("Open with")
        assertRowAbsent("Remove from list")
    }

    /** The finished download: the file it produced, every way the app can hand it over. */
    @Test
    fun longPressOnACompletedTransferOffersTheFileItProduced() {
        inject(
            TransferItem(
                id = "sheet-complete",
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
            ),
        )
        openTransfers(rowName = "site-backup.tar")
        longPress("site-backup.tar")

        awaitRow("View file")
        awaitRow("Edit")
        awaitRow("Open")
        awaitRow("Open with")
        awaitRow("Copy details")
        awaitRow("Remove from list")
        // A finished transfer has nothing to pause, resume or cancel; removal is the only way out.
        assertRowAbsent("Pause")
        assertRowAbsent("Resume")
        assertRowAbsent("Cancel transfer")
    }

    /**
     * The sheet's Edit row opens the full-window editor, the same way the Files sheet's does.
     *
     * This is the wiring the sheet exists to expose: the transfer's local file handed to the editor
     * as a provider-backed entry. The editor's own reading and saving have their own suites; what
     * only this level can show is that the tap reaches the editor activity at all.
     */
    @Test
    fun theSheetsEditRowOpensTheEditor() {
        inject(
            TransferItem(
                id = "sheet-edit",
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
            ),
        )
        openTransfers(rowName = "release-notes.md")
        longPress("release-notes.md")
        val app = compose.activity.application
        // Drain whatever starts the setup made, so the peek below only ever reports this
        // click's doing — peeking does not consume, so a stale intent would mask the editor's.
        while (runCatching { shadowOf(app).nextStartedActivity }.getOrNull() != null) Unit
        clickSheetRow("Edit")

        // Stage 1: the row's own first act is closing the sheet it lives in, so the sheet
        // leaving the tree is the observable proof that the click ran the app's code rather
        // than stalling in the harness.
        pumpUntil(describe = { "the sheet never closed after its Edit row was tapped" }) {
            compose.onAllNodes(hasText("Edit") and hasClickAction()).fetchSemanticsNodes().isEmpty()
        }
        // Stage 2: the request the row filed is consumed by a LaunchedEffect keyed on it,
        // which fires on a later frame. Robolectric records every startActivity
        // unconditionally, so a timeout here means the request never reached the effect —
        // and the peeked intent is the honest witness of what did start instead.
        pumpUntil(describe = {
            "the editor activity never started (last start: " +
                runCatching { shadowOf(app).peekNextStartedActivity() }.getOrNull() + ")"
        }) {
            runCatching { shadowOf(app).peekNextStartedActivity() }.getOrNull()
                ?.component?.className == "dev.eclipse.ssh.ui.editor.TextEditorActivity"
        }
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
     * Clicks a sheet row by invoking its own OnClick semantics action.
     *
     * A Material3 ModalBottomSheet lives in a Dialog window, and `performClick`'s injected touch
     * never reaches content inside a dialog window under Robolectric — the node is found, the call
     * returns, the lambda never runs. See `FilesExplorerLayoutRobolectricTest.clickSheetRow` for
     * the full reasoning; the shape is copied so both suites fail in equally readable ways.
     */
    private fun clickSheetRow(label: String) {
        val row = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
        val click = row.config.getOrNull(SemanticsActions.OnClick)?.action
        checkNotNull(click) { "the \"$label\" sheet row has no OnClick action" }
        compose.runOnUiThread { click() }
    }

    /** A row of the sheet that must be there, and on screen. */
    private fun awaitRow(label: String) {
        pumpUntil(describe = { "the sheet's \"$label\" row never appeared" }) {
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
