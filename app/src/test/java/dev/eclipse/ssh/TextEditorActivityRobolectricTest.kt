package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.editor.EditorRequest
import dev.eclipse.ssh.ui.editor.EditorRequests
import dev.eclipse.ssh.ui.editor.TextEditorActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's window, as against its content.
 *
 * The editor is an activity of its own so it sits on top of the app rather than layered over it,
 * and that is the property worth pinning here: it opens for a live request, it finishes when the
 * request is not live, and a request is opened exactly once. Editing itself — load, save, undo,
 * conflict guard — lives behind `TextEditorScreen` and is exercised through the providers it uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class TextEditorActivityRobolectricTest {

    /** Reads back one in-memory document; the editor uses no other operation before first paint. */
    private class SingleFileProvider(private val contents: ByteArray) : FileSystemProvider {
        override val providerId: String = "test"
        override suspend fun homePath(): String? = null
        override suspend fun parentPath(path: String): String? = null
        override suspend fun list(path: String): List<FsEntry> = emptyList()
        override suspend fun stat(path: String): FsEntry? = null
        override suspend fun read(path: String): ByteArray = contents
        override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) = Unit
        override suspend fun createFile(parentPath: String, name: String): FsEntry = error("unused by the editor launch")
        override suspend fun createDirectory(parentPath: String, name: String) = error("unused by the editor launch")
        override suspend fun rename(path: String, newName: String) = error("unused by the editor launch")
        override suspend fun copy(sourcePath: String, targetDirectoryPath: String) = error("unused by the editor launch")
        override suspend fun move(sourcePath: String, targetDirectoryPath: String) = error("unused by the editor launch")
        override suspend fun delete(path: String) = error("unused by the editor launch")
        override suspend fun setPermissions(path: String, mode: Int) = error("unused by the editor launch")
        override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> = emptyList()
    }

    private fun request(contents: String = "hello") = EditorRequest(
        entry = FsEntry(
            name = "notes.txt",
            path = "/tmp/notes.txt",
            isDirectory = false,
            size = contents.length.toLong(),
            modifiedEpochMillis = 0L,
            permissions = null,
            mimeType = "text/plain",
        ),
        provider = SingleFileProvider(contents.toByteArray()),
    )

    private fun launchIntent(token: String?): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, TextEditorActivity::class.java)
            .also { intent -> token?.let { intent.putExtra(TextEditorActivity.EXTRA_REQUEST_TOKEN, it) } }
    }

    @Test
    fun theEditorOpensInItsOwnWindowAndItsTokenIsSpent() {
        val token = EditorRequests.put(request())
        ActivityScenario.launch<TextEditorActivity>(launchIntent(token)).use { scenario ->
            // androidx.test:core 1.6 reports the scenario's state as a Lifecycle.State — the nested
            // ActivityScenario.State of older versions no longer exists.
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
        // One-shot by design: a re-delivery of the same intent (recents, a relaunch) is a request
        // for a file the user already closed, and it must find nothing to reopen.
        assertThat(EditorRequests.take(token)).isNull()
    }

    @Test
    fun anIntentWithNoLiveTokenFinishesRatherThanEditingNothing() {
        ActivityScenario.launch<TextEditorActivity>(launchIntent(token = null)).use { scenario ->
            // Read through runCatching because a destroyed activity can make the scenario's own
            // state query throw; what has to hold either way is that the window never came up.
            val settled = runCatching { scenario.state }.getOrNull()
            assertThat(settled).isNotEqualTo(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A second file opened while the editor is already up routes into the live window as a new
     * tab, the way `singleTop` + new-intent delivery promise. What only this level can pin is the
     * window half of that contract: the activity is neither finished nor recreated — RESUMED
     * throughout — and the second request's token is spent exactly like the first's. The tab
     * strip itself and the second file's content have their own suite (EditorTabsRobolectricTest).
     */
    @Test
    fun aSecondLiveTokenRoutesIntoTheSameWindowWithoutRecreatingIt() {
        val token = EditorRequests.put(request("first"))
        val second = EditorRequests.put(request("second"))
        ActivityScenario.launch<TextEditorActivity>(launchIntent(token)).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            scenario.onActivity { activity -> activity.onEditorToken(second) }
            // The routing must not have cost the window anything: the same instance, still up.
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
        assertThat(EditorRequests.take(second)).isNull()
    }
}
