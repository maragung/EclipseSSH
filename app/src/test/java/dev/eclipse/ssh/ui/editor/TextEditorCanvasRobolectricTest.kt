package dev.eclipse.ssh.ui.editor

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.eclipse.ssh.EclipseApp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor paints its own canvas rather than borrowing the window's.
 *
 * The text is colored by the app's theme (which follows the app's own dark-theme setting, default
 * dark) while the window background is the XML window_background (which follows the SYSTEM dark
 * mode). Before the screen painted a background, a dark-theme editor on a light-mode device laid
 * near-white text over the light window — a 1.07:1 contrast failure.
 *
 * The color contract itself is the `Modifier.background(MaterialTheme.colorScheme.background)` on
 * the editor root: with it, the canvas is whatever the theme says wherever the text sits, and the
 * mismatch class of bug becomes "the modifier is missing", which this suite pins by composition.
 * Pixel-level readback (`captureToImage`) was tried under `@GraphicsMode(NATIVE)` and does not
 * render under this Robolectric setup — both variants fail inside the graphics layer before the
 * first assertion (a RuntimeException out of the instrumentation in release, a draw timeout in
 * debug) — so this test asserts the level Robolectric can honestly observe: a dark-theme editor
 * (the app setting's default) on a light (notnight) window reads its document, lays it out, and
 * is on screen.
 *
 * The host is the editor's own activity, launched with a live request the way the Files and
 * Transfers sheets launch it. Not `createComposeRule()`'s bare ComponentActivity: the release
 * variant's unit tests resolve their activity against the release manifest, and ComponentActivity
 * is registered in none of them — declaring the ui-test-manifest AAR testImplementation was tried
 * and lands in no unit-test manifest either. An activity that composes its own content is a shape
 * the compose rule supports for exactly this case: the rule asserts against what the activity
 * set, and `rule.setContent` is never called — on an activity that has already set its own
 * content it throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "notnight")
class TextEditorCanvasRobolectricTest {

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
        override suspend fun move(path: String, targetDirectoryPath: String) =
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

    /**
     * The rule around the real [TextEditorActivity], with a live request behind its token — the
     * same launch the sheets produce. The provider is the extraction `createAndroidComposeRule`
     * itself uses for an `ActivityScenarioRule`; copied rather than reused because it is private
     * in the compose test library.
     */
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
    fun aDarkThemeEditorPaintsADarkCanvasEvenOnALightWindow() {
        // The composition under test is the activity's own onCreate: it reads darkTheme from the
        // settings repository — the app setting's default, dark, on a clean install — wraps the
        // editor in EclipseTheme, and it is that screen which must be showing over the light
        // (notnight) window. The wait comes first because it is what drives the frames: the
        // document read runs in a coroutine a single idle pass does not have to have finished,
        // and waitUntil advances the clock until the text it produced is on screen — after
        // which the root being displayed is an assertion about a composed screen, not a race
        // with the first one.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("hello", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().assertIsDisplayed()
    }
}
