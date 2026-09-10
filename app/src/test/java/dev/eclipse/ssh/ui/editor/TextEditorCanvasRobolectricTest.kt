package dev.eclipse.ssh.ui.editor

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.EclipseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor paints its own canvas rather than borrowing the window's.
 *
 * The text is colored by the app's theme (which follows the app's own dark-theme setting, default
 * dark) while the window background is the XML window_background (which follows the SYSTEM dark
 * mode). Before the screen painted a background, a dark-theme editor on a light-mode device laid
 * near-white text over the light window — a 1.07:1 contrast failure that this test pins as
 * impossible: composed dark-theme over a light (notnight) window, the canvas must be dark wherever
 * the text sits, because the text is light. Native graphics mode is what lets the test read the
 * pixels back; the legacy canvas returns nothing renderable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        // A horizontal strip below the toolbar, through the text area. Light text can occupy a few
        // of these pixels, so the claim is that the strip is overwhelmingly the dark canvas: with
        // the old bug the window's light #F7F7FB showed through and this row would be overwhelmingly
        // light instead.
        val y = bitmap.height * 3 / 4
        var dark = 0
        var sampled = 0
        for (x in 0 until bitmap.width step 4) {
            sampled++
            if (android.graphics.Color.valueOf(bitmap.getPixel(x, y)).luminance() < 0.1f) dark++
        }
        assertThat(dark.toFloat() / sampled).isAtLeast(0.9f)
    }
}
