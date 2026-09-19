package dev.eclipse.ssh.ui.editor

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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
 * The gutter draws the numbers of the lines that are on screen, not one for every line in the file.
 *
 * [EditorGutterWindowTest] pins the arithmetic that decides which lines those are, which is where a
 * mistake is cheapest to find. This pins the other half, the one arithmetic cannot state: that the
 * screen is actually built on it. Before, the gutter emitted a `Text` per line of the document, so a
 * two-hundred-line file composed two hundred text nodes on every keystroke — a cost the user paid for
 * numbers they could not see, and one that no pure function's test would notice, because the function
 * was never the thing making the nodes.
 *
 * So the document here is long enough that the difference is a fact rather than a measurement: line
 * 100 and line 200 exist in the file and must not exist as gutter nodes, while line 1 — the line the
 * viewport opens on — must. That pair is what fails if the windowing is ever bypassed, and it says
 * nothing about how tall the window happens to be, which is what would make it a test of the font
 * size instead of a test of the decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "notnight")
class EditorGutterWindowingRobolectricTest {

    /** Reads back one in-memory document; the editor needs no other provider call before paint. */
    private class LinesProvider(private val contents: ByteArray) : FileSystemProvider {
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

    private val document = (1..LINES).joinToString("\n") { "line $it" }

    private fun request() = EditorRequest(
        entry = FsEntry(
            name = "long.txt",
            path = "/tmp/long.txt",
            isDirectory = false,
            size = document.length.toLong(),
            modifiedEpochMillis = 0L,
            permissions = null,
            mimeType = "text/plain",
        ),
        provider = LinesProvider(document.toByteArray()),
    )

    /** The editor's own activity, launched with a live request behind its token. */
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
    fun theGutterComposesTheLinesOnScreenRatherThanEveryLineInTheDocument() {
        // Line 1 is the line the viewport opens on, so its number is also the wait: it is composed
        // only once the document has been read *and* the field has laid it out, which is what hands
        // the gutter the heights it windows by. Asserting on a gutter the layout has not reached yet
        // would be an assertion that no numbers are drawn, which is true and says nothing.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("1")).fetchSemanticsNodes().isNotEmpty()
        }

        // The lines below the fold are in the document and are not on the screen, so nothing may
        // have drawn their numbers: a hundred is twice any window this screen can fit, which keeps
        // the assertion about the decision rather than about the device it is running on.
        assertThat(compose.onAllNodes(hasText("100")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("200")).fetchSemanticsNodes()).isEmpty()
    }

    private companion object {
        /** Long enough that composing every line would be unmistakable. */
        const val LINES = 200
    }
}
