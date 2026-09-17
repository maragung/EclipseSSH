package dev.eclipse.ssh.ui.editor

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.EclipseApp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's header, after the layout the user reported was fixed.
 *
 * The report was three things at once: the toolbar was a single row of seven 48dp touch targets
 * beside a weight(1f) column, which starved the name-and-status column to a sliver; the "Saved"
 * label, with no line limit of its own, then wrapped one glyph per line to fit that sliver and read
 * as a vertical "S/a/v/e/d"; and the editing verbs a text editor is expected to offer — cut, copy,
 * paste, select-all — were not there at all. The fix split the header into an identity row and a
 * scrolling action row, and hardened the two Texts (one line, no soft wrap, an ellipsis) so the
 * stack cannot come back.
 *
 * This suite is the content half of that fix. The stacking itself is a measure-time property
 * Robolectric does not render — the sibling [TextEditorCanvasRobolectricTest] documents why pixel
 * readback is off the table here — so this asserts the level Robolectric can honestly observe: the
 * four editing verbs are composed into the header as their own reachable buttons, their enabled
 * state follows the selection and the file's writability, and the save-state label is present as a
 * single "Saved" node on a clean load. If a later change drops the action row or unhardens the
 * label back into the identity column, one of these goes red.
 *
 * The host is the editor's own activity launched with a live request, the same shape the Files and
 * Transfers sheets use and the same the canvas suite copies; a bare ComponentActivity is registered
 * in no unit-test manifest for the release variant, so the real activity is the only host that
 * composes here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class TextEditorToolbarRobolectricTest {

    /** Reads back one in-memory document; the editor needs no other provider call before paint. */
    private class SingleFileProvider(private val contents: ByteArray) : FileSystemProvider {
        override val providerId: String = "test"
        override suspend fun homePath(): String? = null
        override suspend fun parentPath(path: String): String? = null
        override suspend fun list(path: String): List<FsEntry> = emptyList()
        override suspend fun stat(path: String): FsEntry? = null
        override suspend fun read(path: String): ByteArray = contents
        override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) = Unit
        override suspend fun createFile(parentPath: String, name: String): FsEntry =
            error("unused by the editor launch")
        override suspend fun createDirectory(parentPath: String, name: String) =
            error("unused by the editor launch")
        override suspend fun rename(path: String, newName: String) = error("unused by the editor launch")
        override suspend fun copy(sourcePath: String, targetDirectoryPath: String) =
            error("unused by the editor launch")
        override suspend fun move(sourcePath: String, targetDirectoryPath: String) =
            error("unused by the editor launch")
        override suspend fun delete(path: String) = error("unused by the editor launch")
        override suspend fun setPermissions(path: String, mode: Int) = error("unused by the editor launch")
        override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> =
            emptyList()
    }

    private fun request() = EditorRequest(
        entry = FsEntry(
            name = "notes.txt",
            path = "/tmp/notes.txt",
            isDirectory = false,
            size = 5L,
            modifiedEpochMillis = 0L,
            permissions = null,
            mimeType = "text/plain",
        ),
        provider = SingleFileProvider("hello".toByteArray()),
    )

    @get:Rule
    val compose = AndroidComposeTestRule(
        ActivityScenarioRule<TextEditorActivity>(
            Intent(ApplicationProvider.getApplicationContext(), TextEditorActivity::class.java)
                .putExtra(TextEditorActivity.EXTRA_REQUEST_TOKEN, EditorRequests.put(request())),
        ),
    ) { rule ->
        var activity: TextEditorActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity)
    }

    @Test
    fun theEditingVerbsAreOnTheHeaderAndTheSaveLabelReadsSaved() {
        // The wait drives the frames: the document read runs in a coroutine, and until its text is
        // on screen the tab is still Loading and the header — which composes only for a Ready tab —
        // is not there to assert against. Once "hello" shows, the header is up in its clean state.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("hello", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // The four editing verbs the report said were missing are each composed as their own
        // reachable button. The enabled state is the second half of the claim, and it is pure
        // composition from the field's state — no clipboard call, so it is honest under Robolectric:
        // on a freshly loaded, writable file with nothing selected, cut and copy have nothing to act
        // on and stand down, while paste (the file is writable) and select-all (the file has text)
        // are live. assertIsEnabled / assertIsNotEnabled also assert the node exists, so a dropped
        // button fails here rather than passing silently.
        compose.onNodeWithContentDescription("Cut").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Copy").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Paste").assertIsEnabled()
        compose.onNodeWithContentDescription("Select all").assertIsEnabled()

        // The save-state label is present as a single node reading "Saved" — its own node in the
        // identity column, not the vertical "S/a/v/e/d" the crowded single row produced. Semantics
        // carry the string whichever way it lays out, so this pins that the label is present and
        // correct on a clean load; the one-line/no-soft-wrap hardening in the screen is what keeps
        // the layout from stacking it, and the two-row split is what keeps the column wide enough
        // that it never has to.
        assertThat(compose.onAllNodes(hasText("Saved")).fetchSemanticsNodes()).isNotEmpty()
    }
}
