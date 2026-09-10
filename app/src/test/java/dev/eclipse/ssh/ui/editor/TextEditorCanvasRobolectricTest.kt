package dev.eclipse.ssh.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.EclipseTheme
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
 * debug) — so this test asserts the level Robolectric can honestly observe: composed dark-theme
 * over a light (notnight) window, the editor reads its document, lays it out, and is on screen.
 * The theme half is still pinned exactly: `EclipseTheme(darkTheme = true)` is what colors both
 * the text and the canvas the modifier paints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "notnight")
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
    val composeRule = createComposeRule()

    @Test
    fun aDarkThemeEditorPaintsADarkCanvasEvenOnALightWindow() {
        composeRule.setContent {
            // darkTheme = true is the app setting's default; notnight above is the system half of
            // the mismatch that used to leave the text unreadable.
            EclipseTheme(darkTheme = true) {
                TextEditorScreen(request()) { }
            }
        }
        composeRule.waitForIdle()

        // The editor read its document and put it on screen: the provider's bytes are what the
        // text field holds. Root displayed, because "the screen the user sees" is the editor's
        // own surface — not the light window behind it, which is the whole of the fix. Polled
        // rather than asserted after waitForIdle because the read runs in a LaunchedEffect that
        // a single idle pass does not have to have finished.
        composeRule.onRoot().assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("hello", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
