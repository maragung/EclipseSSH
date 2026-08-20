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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Reading a picked private key.
 *
 * The picker hands back a URI for *any* document the user taps, so this code is the boundary between
 * "the user meant to choose a key" and "the user hit the wrong row". Everything past the boundary
 * assumes a few kilobytes of key material, so the size ceiling and the failure messages are the
 * contract being pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickedKeyFileTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a small key file is read whole with its display name`() = runTest {
        val provider = provider()
        provider.backing = file("id_ed25519", KEY_TEXT.toByteArray())
        provider.displayName = "id_ed25519"

        val picked = readPickedKeyFile(context, uri("id_ed25519")).getOrThrow()

        assertThat(picked.bytes.toString(Charsets.UTF_8)).isEqualTo(KEY_TEXT)
        assertThat(picked.name).isEqualTo("id_ed25519")
    }

    @Test
    fun `a file exactly at the ceiling is accepted`() = runTest {
        val provider = provider()
        provider.backing = file("exact.bin", ByteArray(MAX_PRIVATE_KEY_BYTES) { 'k'.code.toByte() })

        val picked = readPickedKeyFile(context, uri("exact.bin")).getOrThrow()

        assertThat(picked.bytes.size).isEqualTo(MAX_PRIVATE_KEY_BYTES)
    }

    @Test
    fun `an oversized file is refused by its reported size without being read`() = runTest {
        val provider = provider()
        // No backing file at all: if the size check did not run first, the open would be the failure
        // and the message would be the wrong one.
        provider.reportedSize = 40L * 1024 * 1024

        val failure = readPickedKeyFile(context, uri("movie.mp4")).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("too large to be a private key")
    }

    @Test
    fun `an oversized file is refused even when the provider reports no size at all`() = runTest {
        val provider = provider()
        // A provider is not obliged to report a size, and this one reports none by either route: no
        // descriptor to stat, no SIZE column. Without the bounded read below, this is the
        // OutOfMemoryError path — the whole document pulled into the heap on the strength of one tap.
        provider.refuseDescriptor = true
        provider.hideSize = true
        val document = uri("liar.bin")
        shadowOf(context.contentResolver)
            .registerInputStream(document, ByteArrayInputStream(ByteArray(MAX_PRIVATE_KEY_BYTES + 64)))

        val failure = readPickedKeyFile(context, document).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("too large to be a private key")
    }

    @Test
    fun `the bounded read stops at its limit on a stream that never ends`() {
        // /dev/zero is a document too, as far as the picker is concerned.
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }

        assertThat(endless.readAtMost(1_024).size).isEqualTo(1_024)
    }

    @Test
    fun `the bounded read returns everything from a shorter stream`() {
        val payload = ByteArray(3_000) { (it % 97).toByte() }

        assertThat(ByteArrayInputStream(payload).readAtMost(MAX_PRIVATE_KEY_BYTES)).isEqualTo(payload)
    }

    @Test
    fun `the bounded read tolerates a stream that dribbles`() {
        // A content provider streaming over a socket returns short reads. A loop that treated a short
        // read as the end would silently truncate the key.
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val dribbling = object : InputStream() {
            private val source = ByteArrayInputStream(payload)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 7))
        }

        assertThat(dribbling.readAtMost(MAX_PRIVATE_KEY_BYTES)).isEqualTo(payload)
    }

    @Test
    fun `an empty file is refused`() = runTest {
        provider().backing = file("empty.bin", ByteArray(0))

        val failure = readPickedKeyFile(context, uri("empty.bin")).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("empty")
    }

    @Test
    fun `a revoked document fails with our own wording rather than a provider string`() = runTest {
        provider().failEverything = true

        val failure = readPickedKeyFile(context, uri("gone.bin")).exceptionOrNull()

        // The provider's own message is written for a stack trace, and it is text from another app.
        assertThat(failure).isInstanceOf(KeyFileException::class.java)
        assertThat(failure).hasMessageThat().doesNotContain("permission revoked")
        assertThat(failure).hasMessageThat().contains("Pick the private key again")
    }

    @Test
    fun `a provider that supplies no name falls back to the last path segment`() = runTest {
        val provider = provider()
        provider.backing = file("named.bin", KEY_TEXT.toByteArray())
        provider.displayName = null

        val picked = readPickedKeyFile(context, uri("weird name.bin")).getOrThrow()

        assertThat(picked.name).isEqualTo("weird name.bin")
    }

    @Test
    fun `PickedKeyFile does not print its key material`() {
        val picked = PickedKeyFile(KEY_TEXT.toByteArray(), "id_rsa")

        assertThat(picked.toString()).doesNotContain("PRIVATE KEY")
        assertThat(picked.toString()).contains("id_rsa")
    }

    @Test
    fun `a name is reduced to a file name`() {
        assertThat(sanitizedKeyName("/storage/emulated/0/keys/id_rsa")).isEqualTo("id_rsa")
        assertThat(sanitizedKeyName("C:\\Users\\me\\id_rsa")).isEqualTo("id_rsa")
    }

    @Test
    fun `control characters are dropped from a name`() {
        // A name is provider-supplied text that ends up in a label and, if anything ever logs it, in a
        // log line. A newline or a carriage return there rewrites the line around it.
        assertThat(sanitizedKeyName("id_rsa\n\rroot@host's key")).isEqualTo("id_rsaroot@host's key")
        assertThat(sanitizedKeyName("id\u0000_rsa")).isEqualTo("id_rsa")
        assertThat(sanitizedKeyName("id\u001b[31m_rsa")).isEqualTo("id[31m_rsa")
    }

    @Test
    fun `accented and non-latin names survive`() {
        assertThat(sanitizedKeyName("clé_privée")).isEqualTo("clé_privée")
        assertThat(sanitizedKeyName("私の鍵")).isEqualTo("私の鍵")
    }

    @Test
    fun `a long name is capped`() {
        val capped = sanitizedKeyName("k".repeat(500))

        assertThat(capped).hasLength(64)
    }

    @Test
    fun `a missing or unusable name falls back to a generic one`() {
        assertThat(sanitizedKeyName(null)).isEqualTo("private-key")
        assertThat(sanitizedKeyName("")).isEqualTo("private-key")
        assertThat(sanitizedKeyName("   ")).isEqualTo("private-key")
        assertThat(sanitizedKeyName("\u0001\u0002")).isEqualTo("private-key")
        // A path with nothing after the final separator has no file name in it.
        assertThat(sanitizedKeyName("/storage/keys/")).isEqualTo("private-key")
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }

    private fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(name)}")

    private fun provider(): FakeKeyProvider =
        Robolectric.buildContentProvider(FakeKeyProvider::class.java)
            .create(ProviderInfo().apply { authority = AUTHORITY; grantUriPermissions = true })
            .get()

    /** A SAF provider with a configurable name, size and payload. */
    class FakeKeyProvider : ContentProvider() {
        var backing: File? = null
        var reportedSize: Long? = null
        var displayName: String? = null

        /** Report no size through the SIZE column, the way a streaming provider does. */
        var hideSize = false

        /** Refuse to hand out a descriptor, so [localDocumentLength] has nothing to stat. */
        var refuseDescriptor = false
        var failEverything = false

        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failEverything) throw FileNotFoundException("permission revoked")
            if (refuseDescriptor) throw FileNotFoundException("stream only")
            val file = backing ?: throw FileNotFoundException("no document")
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
            val size = reportedSize ?: backing?.length()?.takeUnless { hideSize }
            // The projection is honoured column by column, because callers read by index: DocumentFile
            // asks for DISPLAY_NAME alone and takes column 0, so a provider that answered with its own
            // column order would hand it the size and the test would pass for the wrong reason.
            val columns = projection?.toList() ?: listOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)
            val row: List<Any?> = columns.map { column ->
                when (column) {
                    OpenableColumns.SIZE -> size
                    OpenableColumns.DISPLAY_NAME -> displayName
                    else -> null
                }
            }
            if (row.all { it == null }) return null
            return MatrixCursor(columns.toTypedArray()).apply { addRow(row.toTypedArray()) }
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "dev.eclipse.ssh.test.keys"
        const val KEY_TEXT = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
