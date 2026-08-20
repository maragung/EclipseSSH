package dev.eclipse.ssh.data.saf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A folder the app can no longer read has to say so, not come back empty.
 *
 * A persisted SAF grant is not permanent: it can be revoked from the app's storage settings, the folder
 * can be deleted, and a removable volume can be unmounted with the grant still on record. `listFiles()`
 * returns an empty array for all of those - the same answer an empty folder gives - so the browser used
 * to show a blank pane with no way to tell "nothing here" from "no longer allowed to look", and no hint
 * that picking the folder again would fix it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFileBrowserTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Shaped like the real thing: what the system file picker hands back for a chosen folder. */
    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")

    @Test
    fun `a folder with no grant behind it is reported as unavailable`() {
        val failure = runCatching { LocalFileBrowser.list(context, treeUri) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalAccessUnavailableException::class.java)
    }

    @Test
    fun `the failure names the folder and nothing else`() {
        val message = runCatching { LocalFileBrowser.list(context, treeUri) }
            .exceptionOrNull()
            ?.message
            .orEmpty()

        assertThat(message).contains(treeUri.toString())
    }

    @Test
    fun `a uri that is not a folder tree is reported the same way rather than thrown raw`() {
        // A value left over from an older build, or a single document arriving through a share:
        // DocumentFile rejects it with IllegalArgumentException, which the caller cannot tell apart
        // from any other bug. To the user it is the same situation - this folder cannot be opened.
        val notATree = Uri.parse("content://com.example.provider/document/42")

        val failure = runCatching { LocalFileBrowser.list(context, notATree) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalAccessUnavailableException::class.java)
    }

    @Test
    fun `asking for the parent of an unreadable folder answers null instead of crashing`() {
        assertThat(LocalFileBrowser.parent(context, treeUri)).isNull()
        assertThat(LocalFileBrowser.parent(context, Uri.parse("content://com.example.provider/document/42")))
            .isNull()
    }
}
