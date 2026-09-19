package dev.eclipse.ssh

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.preview.FilePreviewActivity
import dev.eclipse.ssh.ui.preview.FilePreviewRequest
import dev.eclipse.ssh.ui.preview.PreviewRequests
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The file preview's window, as against its content.
 *
 * The preview is an activity of its own now so that reading a file gets the screen rather than 55% of
 * a sheet — and that is what is pinned here: it opens on a live request with that file actually on
 * screen, it closes when the request is not live, and a second file arriving while it is up replaces
 * the first instead of stacking a window behind it.
 *
 * What only this level can answer is the *pairing*: the request is a live [FileSystemProvider] that
 * cannot be parcelled, so the intent carries a token and the file waits in [PreviewRequests]. A window
 * that opened on the right class with the wrong file, or on a spent token, would pass every test the
 * body has and fail the user on the first tap.
 *
 * The rendering itself is [dev.eclipse.ssh.ui.preview.FilePreviewContent]'s and unchanged by the
 * promotion; the name asserted below is the header it draws, and is here as the proof that the body
 * composed at all rather than as a claim about the body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FilePreviewActivityRobolectricTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /** Reads back one in-memory document; the preview uses no other operation before first paint. */
    private class SingleFileProvider(private val contents: ByteArray) : FileSystemProvider {
        override val providerId: String = "test"
        override suspend fun homePath(): String? = null
        override suspend fun parentPath(path: String): String? = null
        override suspend fun list(path: String): List<FsEntry> = emptyList()
        override suspend fun stat(path: String): FsEntry? = null
        override suspend fun read(path: String): ByteArray = contents
        override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) = Unit
        override suspend fun createFile(parentPath: String, name: String): FsEntry = error("unused by the preview launch")
        override suspend fun createDirectory(parentPath: String, name: String) = error("unused by the preview launch")
        override suspend fun rename(path: String, newName: String) = error("unused by the preview launch")
        override suspend fun copy(sourcePath: String, targetDirectoryPath: String) = error("unused by the preview launch")
        override suspend fun move(sourcePath: String, targetDirectoryPath: String) = error("unused by the preview launch")
        override suspend fun delete(path: String) = error("unused by the preview launch")
        override suspend fun setPermissions(path: String, mode: Int) = error("unused by the preview launch")
        override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> = emptyList()
    }

    private fun entry(name: String, contents: String) = FsEntry(
        name = name,
        path = "/tmp/$name",
        isDirectory = false,
        size = contents.length.toLong(),
        modifiedEpochMillis = 0L,
        permissions = null,
        mimeType = "text/plain",
    )

    /** The intent the app itself builds, so the handoff's two halves are the shipped ones. */
    private fun intentFor(name: String, contents: String): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return FilePreviewActivity.intent(context, entry(name, contents), SingleFileProvider(contents.toByteArray()))
    }

    private fun intentCarrying(token: String?): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, FilePreviewActivity::class.java)
            .also { intent -> token?.let { intent.putExtra(FilePreviewActivity.EXTRA_REQUEST_TOKEN, it) } }
    }

    /**
     * Drives frames and main-looper work until [condition] holds, then fails with [describe].
     *
     * Not `compose.waitUntil`, which reports only "Condition still not satisfied". `idleFor` rather
     * than `idle` because the body reads the file on a coroutine that comes due on the main looper,
     * so a fixed number of frames would be a race even when the frames are pumped.
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

    /**
     * The window opens on the file the intent names, and the token is spent doing it.
     *
     * Both halves are the claim. That the file's name is on screen is what a token can get wrong —
     * the request is read by the window, not carried by the intent, so "the right file appeared" is
     * the only proof that the two halves met. That the token is then gone is what makes a re-delivery
     * safe: a rotation, the recents screen or a relaunch hands the same intent back, and a preview of
     * a file the user has closed must find nothing to reopen — for a remote one, reopening would open
     * a channel to do it.
     */
    @Test
    fun thePreviewOpensOnTheFileItsIntentNamesAndSpendsItsToken() {
        val token = PreviewRequests.putFile(
            FilePreviewRequest(entry("notes.txt", "hello"), SingleFileProvider("hello".toByteArray())),
        )
        ActivityScenario.launch<FilePreviewActivity>(intentCarrying(token)).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            pumpUntil(describe = { "the previewed file's name never reached the window" }) {
                compose.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertThat(PreviewRequests.takeFile(token)).isNull()
    }

    /**
     * An intent nobody stored a request for closes the window rather than opening an empty one.
     *
     * This is the intent a hand-written `startActivity` would build, and the one the system hands back
     * for a window it restored after the process died. An empty preview would be worse than none: it
     * reads as an empty file, which is a lie about a file that may be very much not empty.
     */
    @Test
    fun anIntentWithNoLiveTokenFinishesRatherThanShowingNothing() {
        ActivityScenario.launch<FilePreviewActivity>(intentCarrying(token = null)).use { scenario ->
            // Read through runCatching because a destroyed activity can make the scenario's own state
            // query throw; what has to hold either way is that the window never came up.
            val settled = runCatching { scenario.state }.getOrNull()
            assertThat(settled).isNotEqualTo(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A second file delivered to the live window replaces the one on screen, and does not recreate it.
     *
     * `launchMode="singleTop"` is the manifest half of that promise; [FilePreviewActivity.onPreviewToken]
     * is the window's half, and it is called here the way the new-intent listener calls it. A preview is
     * a look at a file rather than a place, so the back stack stays one entry deep however many files
     * the user walks through — and the first file leaving the screen is what proves the subject was
     * replaced rather than a second preview stacked behind the first.
     */
    @Test
    fun aSecondFileReplacesTheSubjectWithoutRecreatingTheWindow() {
        var secondToken: String? = null
        ActivityScenario.launch<FilePreviewActivity>(intentFor("first.txt", "one")).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            pumpUntil(describe = { "the first file never reached the window" }) {
                compose.onAllNodesWithText("first.txt").fetchSemanticsNodes().isNotEmpty()
            }

            val second = PreviewRequests.putFile(
                FilePreviewRequest(entry("second.txt", "two"), SingleFileProvider("two".toByteArray())),
            )
            secondToken = second
            scenario.onActivity { activity -> activity.onPreviewToken(second) }

            pumpUntil(describe = { "the second file never replaced the first" }) {
                compose.onAllNodesWithText("second.txt").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("first.txt").fetchSemanticsNodes().isEmpty()
            }
            // The same window throughout: a replacement that recreated the activity would be a
            // second window over the first, which is exactly what singleTop exists to prevent.
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
        // Spent on the way in, like the first one: a window that showed the second file without
        // consuming its token would show it again the next time that intent came back.
        assertThat(PreviewRequests.takeFile(secondToken)).isNull()
    }
}
