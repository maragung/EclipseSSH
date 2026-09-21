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
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.actions.FileActionKind
import dev.eclipse.ssh.ui.files.FileActionsActivity
import java.time.Duration
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The explorer's per-entry window, as a window: which rows one entry's session can serve, and what
 * happens to the answer when a row is tapped.
 *
 * This used to be part of `FilesExplorerLayoutRobolectricTest`, over a `ModalBottomSheet` opened by
 * long-pressing a row. The matrix has not changed - Select above Preview, View Archive only where the
 * name can be read, Edit as text only for a file, the transfer row named for the direction the session
 * implies, Permissions only where the backend has them, Delete last and red - and it is pinned here
 * now because the surface moved: a sheet lives inside the workspace's composition, a window is a
 * separate activity with its own intent, and nothing about the first proves anything about the second.
 *
 * What only this level can show is the split the window documents. The entry travels as a token and
 * the four session facts travel as plain extras, so a window opened with the wrong extras on the right
 * entry - a local session's permissions offered for a remote one, a View Archive row for a name no
 * reader can open - would pass every test the entry alone has and fail the user on the first tap. Each
 * of those is asserted below, along with the spent token, which is the shape a re-delivered intent
 * arrives in.
 *
 * The rows themselves are deliberately *not* asserted to do anything: each files an [ActionAnswer] and
 * closes, because the entry it acts on is a row of the explorer's listing and only the explorer holds
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FileActionsActivityRobolectricTest {

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

    /** A remote file: permissions exist, and a name the archive reader can be asked about. */
    private fun remoteFile(name: String = "site-backup.tar") = FsEntry(
        name = name,
        path = "/srv/backups/$name",
        isDirectory = false,
        size = 2_254_856_192,
        modifiedEpochMillis = 1_755_000_000_000,
        permissions = "644",
        mimeType = "application/x-tar",
    )

    /**
     * The remote file with everything servable: every row the window can draw is drawn.
     *
     * This is the assertion that pins the *order* as well as the presence - the sheet's order was
     * argued for row by row (Select first because it is where long-press lands, View Archive above
     * Edit because for a 50 GB archive it is the only row that answers without moving the archive),
     * and a window that redrew them alphabetically would still pass a set comparison.
     */
    @Test
    fun aRemoteReadableFileIsOfferedEveryRowTheSessionCanServe() {
        launch(remoteFile(), isLocal = false, supportsPermissions = true, canOpenArchive = true).use {
            val order = listOf("Select", "Preview", "View Archive", "Edit as text", "Rename", "Copy to…", "Move to…", "Download to device", "Send to another server", "Permissions", "Properties", "Delete")
            // Presence, not visibility: the window scrolls, and what this test is about is which rows
            // exist and in what order - a row the user has to scroll to is still the row the sheet drew.
            order.forEach(::awaitRowPresent)
            assertThat(labelsOnScreen()).containsExactlyElementsIn(order).inOrder()
        }
    }

    /**
     * A local session is a different window, and the differences are all subtractions plus one rename.
     *
     * A SAF document tree has no mode bits and no ranged reads to browse an archive with, so Permissions
     * and View Archive are absent; and there is no second server to send a local file *from*, so Send to
     * another server goes with them. What replaces the transfer row is the same verb named for the
     * direction this session implies.
     */
    @Test
    fun aLocalFileIsOfferedUploadAndNoneOfTheRemoteOnlyRows() {
        launch(remoteFile("report.pdf"), isLocal = true, supportsPermissions = false, canOpenArchive = false).use {
            awaitRow("Upload to server")
            awaitRow("Preview")
            assertRowAbsent("Download to device")
            assertRowAbsent("Permissions")
            assertRowAbsent("View Archive")
            assertRowAbsent("Send to another server")
        }
    }

    /**
     * A folder is offered everything except the one row that edits a *file*.
     *
     * "Edit as text" is the preview window's own label, and a directory has no text to edit - the
     * explorer opens it rather than previewing it. Every other row still applies, which is the point:
     * a folder can be renamed, moved, deleted and sent exactly as a file can. Preview stays too, and
     * deliberately: it is unconditional in the sheet, so making it conditional here would be this
     * window quietly changing a rule it was only supposed to move.
     */
    @Test
    fun aFolderIsOfferedEverythingButTheTextEditor() {
        val folder = remoteFile("backups").copy(isDirectory = true, size = null, mimeType = null)
        launch(folder, isLocal = false, supportsPermissions = true, canOpenArchive = true).use {
            awaitRow("Rename")
            awaitRow("Preview")
            awaitRow("Download to device")
            awaitRow("Properties")
            assertRowAbsent("Edit as text")
        }
    }

    /**
     * A name the archive reader cannot open gets no View Archive row, whatever the session can do.
     *
     * The row is the caller's verdict rather than a fact this window could derive - it is the session's
     * provider and the file's extension together - so the window is only as honest as the boolean it was
     * handed. Hiding the verb beats offering it and failing.
     */
    @Test
    fun aNameNoReaderCanOpenIsOfferedNoViewArchiveRow() {
        launch(remoteFile("notes.txt"), isLocal = false, supportsPermissions = true, canOpenArchive = false).use {
            awaitRow("Preview")
            assertRowAbsent("View Archive")
        }
    }

    /**
     * A row files its answer and closes the window - the whole of what a row does.
     *
     * Rename deliberately, because it is one of the six the window *cannot* do itself: it opens a
     * dialog that belongs to `FilesScreen`, and the answer is what carries the request there. A row
     * that acted on its own would leave the dialog with nothing to show.
     */
    @Test
    fun aRowFilesItsAnswerAndClosesTheWindow() {
        launch(remoteFile(), isLocal = false, supportsPermissions = true, canOpenArchive = true).use { scenario ->
            awaitRow("Rename")
            compose.onNode(hasText("Rename") and hasClickAction()).performClick()

            awaitAnswer(
                ActionAnswer.FileAction(FileActionKind.RENAME),
                describe = { "the row's answer never reached the workspace" },
            )
            awaitClosing(scenario, describe = { "the window stayed up after its row was tapped" })
        }
    }

    /**
     * A spent token opens nothing, rather than a window on an arbitrary entry.
     *
     * This is the shape the system re-delivers: a rotation, the recents screen, a crash and relaunch.
     * There is no default entry for the window to fall back to, and the first row of the listing would
     * be a menu of actions for a file the user never touched.
     */
    @Test
    fun aSpentTokenOpensNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A token that was put and then taken, exactly as the window's own first launch would leave it.
        val spent = ActionRequests.put(ActionSubject.FileActions(remoteFile()))
        ActionRequests.take(spent)

        ActivityScenario.launch<FileActionsActivity>(
            FileActionsActivity.intent(
                context,
                remoteFile(),
                isLocal = false,
                isUbuntu = false,
                supportsPermissions = true,
                canOpenArchive = true,
            )
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
        ActivityScenario.launch<FileActionsActivity>(Intent(context, FileActionsActivity::class.java)).use { scenario ->
            pumpUntil(describe = { "an intent with no subject token opened a window anyway" }) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    // ---------------------------------------------------------------- driving the app

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun launch(
        entry: FsEntry,
        isLocal: Boolean,
        supportsPermissions: Boolean,
        canOpenArchive: Boolean,
        // The default is the SFTP shape every caller above wants. Named rather than positional at the
        // call below, because the two booleans that follow it are both about the *backend* and a
        // positional call would put `supportsPermissions` in the Ubuntu slot the moment one is added.
        isUbuntu: Boolean = false,
    ): ActivityScenario<FileActionsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<FileActionsActivity>(
            FileActionsActivity.intent(
                context,
                entry,
                isLocal = isLocal,
                isUbuntu = isUbuntu,
                supportsPermissions = supportsPermissions,
                canOpenArchive = canOpenArchive,
            ),
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
        val known = setOf(
            "Select", "Preview", "View Archive", "Edit as text", "Rename", "Copy to…", "Move to…",
            "Upload to server", "Download to device", "Send to another server", "Permissions",
            "Properties", "Delete",
        )
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                ?.firstOrNull()?.text?.takeIf(known::contains)
        }
    }

    /** A row of the window that must exist, wherever it sits in the scroll. */
    private fun awaitRowPresent(label: String) {
        pumpUntil(describe = { "the window's \"$label\" row never appeared" }) {
            compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
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
    private fun awaitClosing(scenario: ActivityScenario<FileActionsActivity>, describe: () -> String) {
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
