package dev.eclipse.ssh.data.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A private key file the user picked, held in memory with the name to show for it. */
class PickedKeyFile(val bytes: ByteArray, val name: String) {
    /** Redacted: the whole point of this class is to carry key material. */
    override fun toString(): String = "PickedKeyFile(name=$name, bytes=${bytes.size})"
}

/**
 * Ceiling on what the app will read as a private key.
 *
 * The largest key anyone uses — RSA-16384, PEM-armoured, with a certificate stapled on — is a few
 * tens of kilobytes, so half a megabyte is generous by more than an order of magnitude. A ceiling is
 * needed at all because the picker returns a `content://` URI for *any* document: the previous code
 * called `readBytes()` on it, so choosing a video by mistake read the whole file into the heap and
 * took the process down with an `OutOfMemoryError` — a crash triggered by a mis-tap, on a screen
 * whose entire job is picking a file.
 */
const val MAX_PRIVATE_KEY_BYTES = 512 * 1024

/**
 * A failure whose message this app wrote, for the user to read.
 *
 * The distinction matters because the other kind exists: a document provider that has had its grant
 * revoked throws its own `FileNotFoundException`, and its message is written for a developer reading
 * a stack trace, not for somebody who just tapped a file. Worse, a provider's message is text from
 * another app, so putting it on screen means rendering a string this app did not write.
 */
class KeyFileException internal constructor(message: String) : Exception(message)

/**
 * Reads a picked document as a private key, or explains why it cannot be one.
 *
 * Always fails with a [KeyFileException], so every message reaching the user is one of ours: no
 * provider strings, no file contents, no class names. On IO because it opens a document belonging to
 * another app.
 */
suspend fun readPickedKeyFile(context: Context, uri: Uri): Result<PickedKeyFile> = withContext(Dispatchers.IO) {
    try {
        Result.success(readKeyFile(context, uri))
    } catch (cancelled: CancellationException) {
        // Never a diagnosis of the file; the caller's scope is going away.
        throw cancelled
    } catch (ours: KeyFileException) {
        Result.failure(ours)
    } catch (_: Throwable) {
        // A revoked grant, a provider that crashed, a document that vanished between the picker
        // closing and this read. All indistinguishable from here and all the same instruction.
        Result.failure(KeyFileException("That file could not be read. Pick the private key again."))
    }
}

private fun readKeyFile(context: Context, uri: Uri): PickedKeyFile {
    // Checked before opening, so an obviously oversized file costs nothing to reject. Providers
    // are not obliged to report a size, and some lie, which is why the read below is bounded too.
    localDocumentLength(context, uri)?.let { size ->
        if (size > MAX_PRIVATE_KEY_BYTES) throw KeyFileException(TOO_LARGE)
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        // One byte past the limit, so "exactly at the limit" and "over it" are distinguishable.
        stream.readAtMost(MAX_PRIVATE_KEY_BYTES + 1)
    } ?: throw KeyFileException("That file could not be opened")
    if (bytes.size > MAX_PRIVATE_KEY_BYTES) throw KeyFileException(TOO_LARGE)
    if (bytes.isEmpty()) throw KeyFileException("That file is empty")
    return PickedKeyFile(bytes, pickedDocumentName(context, uri))
}

private const val TOO_LARGE = "That file is too large to be a private key"

/**
 * Reads at most [limit] bytes, so a provider that misreports its size cannot exhaust the heap.
 *
 * `internal` so the bound is pinned by a test. It is the last line of defence — the size check above
 * it depends on the provider telling the truth, and a provider is another app.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_CHUNK)
    var total = 0
    while (total < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - total))
        if (read < 0) break
        collected.write(chunk, 0, read)
        total += read
    }
    return collected.toByteArray()
}

private fun pickedDocumentName(context: Context, uri: Uri): String {
    val raw = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        ?: uri.lastPathSegment
    return sanitizedKeyName(raw)
}

/**
 * A display name for a picked document, safe to store and to show.
 *
 * The name comes from another app, so it is treated as input rather than as a fact. Path separators
 * are dropped so it cannot read as a location, control characters are dropped so it cannot rearrange
 * a line of text on screen or in a log, and the length is capped so it cannot push the rest of a
 * label out of view. `internal` so its behaviour is pinned by a test rather than by inspection.
 */
internal fun sanitizedKeyName(raw: String?): String {
    val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.filter { it.code in PRINTABLE_ASCII || it.code > LATIN_SUPPLEMENT_START }
        ?.trim()
        .orEmpty()
    return name.take(MAX_NAME_CHARS).ifBlank { FALLBACK_NAME }
}

private const val DEFAULT_CHUNK = 8 * 1024
private const val MAX_NAME_CHARS = 64
private const val FALLBACK_NAME = "private-key"
private val PRINTABLE_ASCII = 0x20..0x7E

/** Above the C1 control block and the ambiguous NBSP at 0xA0, so accented file names survive. */
private const val LATIN_SUPPLEMENT_START = 0xA0
