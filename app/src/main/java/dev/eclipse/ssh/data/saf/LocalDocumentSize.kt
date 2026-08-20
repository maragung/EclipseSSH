package dev.eclipse.ssh.data.saf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Actual byte length of a SAF/content document, or null when the provider will not report
 * one.
 *
 * Resuming a download must append from the file's real length, not from the last progress
 * count persisted in Room: progress is throttled (one write per 200 ms), so the file on disk
 * is almost always longer than the stored counter. Appending remote bytes from the stale
 * offset duplicated a region and silently corrupted every resumed download.
 */
fun localDocumentLength(context: Context, uri: Uri): Long? {
    // openFileDescriptor reports the true size for file-backed documents.
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize.takeIf { it >= 0 }
        }
    }.getOrNull()?.let { return it }

    // Providers that cannot supply a descriptor may still expose SIZE.
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index).takeIf { it >= 0 }
        }
    }.getOrNull()
}
