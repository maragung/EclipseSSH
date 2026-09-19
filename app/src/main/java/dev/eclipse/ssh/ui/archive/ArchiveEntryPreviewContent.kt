package dev.eclipse.ssh.ui.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.archive.ArchiveReader
import dev.eclipse.ssh.ui.files.describeSize
import dev.eclipse.ssh.ui.rememberDialogBodyMaxHeight
import dev.eclipse.ssh.ui.terminal.TerminalMonoFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The preview for one file inside an open archive — the epic's "view file inside archive" section:
 * reading only that entry's data, never the archive around it.
 *
 * This is the body of [ArchiveEntryPreviewActivity] and nothing but a body: no surface, no
 * dismissal, no theme, because the window around it owns all three. It drew inside a
 * `ModalBottomSheet` until the preview became a window, and the content did not have to change for
 * that — which is what having kept it separate bought.
 *
 * The byte-fetch is bounded twice over: the entry's own size refuses what it cannot hold before
 * anything is read, and the fetch itself is the ranged read ([ArchiveReader.readEntry]) that
 * moves exactly the entry's bytes - the ZIP's compressed payload, or an uncompressed TAR's
 * data-offset slice. A compressed TAR cannot offer either and says so before the window opens.
 * The rendering reuses the Files preview's shapes —
 * a zoomable image, a monospace text body — because a file inside an archive is still just a
 * file, and this app already knows how to show one.
 *
 * There is no Edit button here, deliberately: the editor writes through a
 * [dev.eclipse.ssh.data.fs.FileSystemProvider], and an archive entry has no provider path to
 * write back to. Extract is the honest verb for "I want to change this file", and it lives in the
 * entry's action sheet, not here.
 */
@Composable
fun ArchiveEntryPreviewContent(
    entry: ArchiveEntry,
    /**
     * The reader callback that fetches this entry's bytes, or null when the format cannot. Null is
     * also the answer the reader itself can give for an entry it cannot decode (an unsupported
     * compression method, say) - both mean the same thing to a preview: nothing to render, and the
     * failure state says so rather than an empty body that looks like an empty file.
     */
    readEntry: (suspend () -> ByteArray?)?,
) {
    // Text is the only kind previewed in-archive beyond images: the MIME sniffing the Files
    // preview does over a provider's answer does not exist here (an archive entry has no MIME),
    // and a name's extension is the whole truth available. Media and PDF are downloads, honestly
    // labelled, because their renderers want seekable files or players, not byte arrays - the
    // extract is one tap away.
    val kind = remember(entry.path) { previewKindOf(entry) }
    var state by remember(entry.path) { mutableStateOf<EntryPreviewState>(EntryPreviewState.Loading) }

    LaunchedEffect(entry.path) {
        state = if (readEntry == null || kind == EntryPreviewKind.INFO) {
            EntryPreviewState.Info
        } else {
            loadEntryPreview(entry, kind, readEntry)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            // The Files preview's own shape, one size up: no cap of its own now that the window's
            // body is the screen under the bar, with the text body below still capped so a long
            // entry scrolls inside a scroller.
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
    ) {
        // The header is the entry's own name, not its path: the path is the breadcrumb's job, and
        // this window was opened from the folder that contains it.
        Text(entry.path.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        entry.size?.let {
            Text(describeSize(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        when (val current = state) {
            is EntryPreviewState.Loading -> Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is EntryPreviewState.Failed -> Text(
                current.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is EntryPreviewState.TooLarge -> Text(
                "This file is ${"%.1f".format(current.bytes / (1024.0 * 1024.0))} MB — too large " +
                    "to preview inside the archive. It can still be extracted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is EntryPreviewState.Info -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "No preview for this file type inside the archive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is EntryPreviewState.Image -> ZoomableImage(current.bitmap)

            is EntryPreviewState.Text -> SelectionContainer {
                Text(
                    current.content,
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        // 0.7 of the screen, the Files preview's own cap: a window has room a
                        // sheet never had, and 420dp was sized for one phone's sheet.
                        .heightIn(max = rememberDialogBodyMaxHeight(0.7f))
                        .verticalScroll(rememberScrollState()),
                    fontFamily = TerminalMonoFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/** What the in-archive preview is showing; [Info] is an answer, not a fallback of last resort. */
private sealed interface EntryPreviewState {
    data object Loading : EntryPreviewState
    data object Info : EntryPreviewState
    data class Failed(val message: String) : EntryPreviewState
    data class TooLarge(val bytes: Long) : EntryPreviewState
    data class Image(val bitmap: Bitmap) : EntryPreviewState
    data class Text(val content: String) : EntryPreviewState
}

/** The kinds an archive entry can be previewed as, judged by name alone — see the header comment. */
private enum class EntryPreviewKind { IMAGE, TEXT, INFO }

private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
private const val MAX_TEXT_BYTES = 512L * 1024

private fun previewKindOf(entry: ArchiveEntry): EntryPreviewKind =
    when (entry.path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> EntryPreviewKind.IMAGE
        "txt", "log", "json", "xml", "yml", "yaml", "conf", "ini", "sh", "kt", "kts", "java",
        "py", "js", "ts", "css", "html", "csv", "properties", "gradle", "toml", "env", "md",
        "markdown", "c", "h", "cpp", "go", "rs", "sql", "php", "rb",
            -> EntryPreviewKind.TEXT
        else -> EntryPreviewKind.INFO
    }

private suspend fun loadEntryPreview(
    entry: ArchiveEntry,
    kind: EntryPreviewKind,
    readEntry: suspend () -> ByteArray?,
): EntryPreviewState {
    val size = entry.size
    val ceiling = if (kind == EntryPreviewKind.IMAGE) MAX_IMAGE_BYTES else MAX_TEXT_BYTES
    if (size != null && size > ceiling) return EntryPreviewState.TooLarge(size)
    return try {
        val bytes = readEntry()
            // The reader's "cannot" - an entry this format or method cannot serve by range - is
            // the window's Failed state, not an empty preview that reads as an empty file.
            ?: return EntryPreviewState.Failed("This entry cannot be read from the archive as-is.")
        if (bytes.size > ceiling) return EntryPreviewState.TooLarge(bytes.size.toLong())
        when (kind) {
            EntryPreviewKind.IMAGE -> {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: return EntryPreviewState.Failed("This image could not be decoded.")
                EntryPreviewState.Image(bitmap)
            }
            // A decode failure on the way to UTF-8 is replaced, not thrown: a text-named entry
            // whose bytes are not text still deserves its (garbled) preview rather than an error,
            // exactly what a terminal `cat` would have shown.
            else -> EntryPreviewState.Text(
                withContext(Dispatchers.IO) {
                    bytes.toString(Charsets.UTF_8)
                },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        EntryPreviewState.Failed(error.message ?: "The entry could not be read")
    }
}

/** The same zoom-fit-double-tap image the Files preview shows; a file inside an archive zooms too. */
@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clipToBounds()
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    scale = next
                    offset = if (next > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
