package dev.eclipse.ssh.data.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileNotFoundException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A resumed download appends to the partially written local file, so the offset it starts
 * from has to be the file's real length. These tests pin both ways a provider can report
 * that length and the fallback when it reports neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDocumentSizeTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `descriptor size is used when the provider can open the document`() {
        val file = File(context.cacheDir, "partial.bin").apply { writeBytes(ByteArray(4_096) { 7 }) }
        provider().backing = file

        assertThat(localDocumentLength(context, uri("partial.bin"))).isEqualTo(4_096L)
    }

    @Test
    fun `SIZE column is used when the provider cannot supply a descriptor`() {
        provider().reportedSize = 1_234L

        assertThat(localDocumentLength(context, uri("streamed.bin"))).isEqualTo(1_234L)
    }

    @Test
    fun `unknown length returns null rather than a bogus zero`() {
        provider()

        // Null lets the caller fall back to the persisted counter instead of restarting
        // the transfer from byte 0 and truncating what was already downloaded.
        assertThat(localDocumentLength(context, uri("opaque.bin"))).isNull()
    }

    @Test
    fun `an empty file reports zero, not null`() {
        val file = File(context.cacheDir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        provider().backing = file

        assertThat(localDocumentLength(context, uri("empty.bin"))).isEqualTo(0L)
    }

    @Test
    fun `a revoked or deleted document reports no length instead of throwing`() {
        provider().failEverything = true

        assertThat(localDocumentLength(context, uri("gone.bin"))).isNull()
    }

    private fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

    /** Registers the fake provider for this test and returns it for configuration. */
    private fun provider(): FakeDocumentProvider =
        Robolectric.buildContentProvider(FakeDocumentProvider::class.java)
            .create(ProviderInfo().apply { authority = AUTHORITY; grantUriPermissions = true })
            .get()

    /** Stands in for a SAF document provider with configurable capabilities. */
    class FakeDocumentProvider : ContentProvider() {
        var backing: File? = null
        var reportedSize: Long? = null
        var failEverything = false

        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failEverything) throw FileNotFoundException("revoked")
            val file = backing ?: throw FileNotFoundException("stream only")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (failEverything) throw SecurityException("permission revoked")
            val size = reportedSize ?: return null
            return MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(size)) }
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "dev.eclipse.ssh.test.documents"
    }
}
