package dev.eclipse.ssh.ui.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.rememberDialogBodyMaxHeight
import dev.eclipse.ssh.ui.terminal.TerminalMonoFontFamily
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Type-aware preview for one file: an image with zoom, text, pretty JSON, rendered Markdown, the
 * first pages of a PDF, an honest hand-off for media — and for everything else, the file's
 * information rather than a pretended preview.
 *
 * The one rule the sheet never bends: **no unsafe automatic execution**. A preview only ever *reads*
 * bytes and draws pixels; "Open with another app" is the user's explicit tap, and it is offered only
 * for local files whose URI Android can hand to another process.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(
    entry: FsEntry,
    provider: FileSystemProvider,
    onDismiss: () -> Unit,
    onEdit: (FsEntry) -> Unit,
) {
    val kind = remember(entry) { previewKind(entry) }
    var state by remember(entry.path, provider.providerId) { mutableStateOf<PreviewState>(PreviewState.Loading) }

    LaunchedEffect(entry.path, provider.providerId) {
        // Everything below reads whole files into memory, so each kind refuses what it cannot hold.
        state = when (kind) {
            PreviewKind.IMAGE -> loadBitmapPreview(entry, provider)
            PreviewKind.PDF -> loadPdfPreview(entry, provider)
            PreviewKind.TEXT, PreviewKind.JSON, PreviewKind.MARKDOWN -> loadTextPreview(entry, provider, kind)
            PreviewKind.MEDIA, PreviewKind.OTHER -> PreviewState.Info
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // 0.85 of the screen: a bottom sheet is the one surface where "as tall as the
                // screen allows" is the point, and the column scrolls. Replaces a fixed 620dp
                // that assumed one phone. Must stay above the monospace body's 0.55 below.
                .heightIn(max = rememberDialogBodyMaxHeight(0.85f))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            PreviewHeader(entry)
            Spacer(Modifier.height(12.dp))

            when (val current = state) {
                is PreviewState.Loading -> Box(
                    Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is PreviewState.Failed -> Text(
                    current.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is PreviewState.TooLarge -> Text(
                    "This file is ${"%.1f".format(current.bytes / (1024.0 * 1024.0))} MB — too large " +
                        "to preview on the device. It can still be downloaded, renamed or deleted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is PreviewState.Info -> FileInformation(entry)

                is PreviewState.Image -> ZoomableImage(current.bitmap)

                is PreviewState.PdfPages -> PdfPages(current.pages, current.totalPages)

                is PreviewState.Textual -> when (current.rendered) {
                    is RenderedText.Text -> SelectionContainerMonospace(current.rendered.content)
                    is RenderedText.Json -> SelectionContainerMonospace(current.rendered.content)
                    is RenderedText.Markdown -> MarkdownDocument(current.rendered.blocks)
                }
            }

            Spacer(Modifier.height(16.dp))

            // The actions, one row: what this kind can do, never a list of things it cannot.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind in setOf(PreviewKind.TEXT, PreviewKind.JSON, PreviewKind.MARKDOWN) &&
                    (state is PreviewState.Textual || state is PreviewState.Failed)
                ) {
                    FilledTonalButton(onClick = { onEdit(entry) }) { Text("Edit as text") }
                }
                if (provider.providerId == "local" &&
                    kind in setOf(PreviewKind.IMAGE, PreviewKind.PDF, PreviewKind.MEDIA)
                ) {
                    val context = LocalContext.current
                    FilledTonalButton(onClick = { openWithAnotherApp(context, entry) }) {
                        Text("Open with another app")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/** What the sheet is showing. [Info] is a real answer, not a fallback of last resort. */
private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Info : PreviewState
    data class Failed(val message: String) : PreviewState
    data class TooLarge(val bytes: Long) : PreviewState
    data class Image(val bitmap: Bitmap) : PreviewState
    data class PdfPages(val pages: List<Bitmap>, val totalPages: Int) : PreviewState
    data class Textual(val rendered: RenderedText) : PreviewState
}

/** The kinds of preview the sheet knows how to draw. */
private enum class PreviewKind { IMAGE, TEXT, JSON, MARKDOWN, PDF, MEDIA, OTHER }

private fun previewKind(entry: FsEntry): PreviewKind {
    // The MIME type, when the backend resolved one, outranks the extension — it is the backend's
    // considered answer. Only when it is silent does the name get a say.
    val mime = entry.mimeType
    if (mime != null) {
        return when {
            mime.startsWith("image/") -> PreviewKind.IMAGE
            mime.startsWith("video/") || mime.startsWith("audio/") -> PreviewKind.MEDIA
            mime == "application/pdf" -> PreviewKind.PDF
            mime.startsWith("text/") || mime == "application/json" -> textKindOf(entry.name)
            else -> PreviewKind.OTHER
        }
    }
    return textKindOf(entry.name)
}

private fun textKindOf(name: String): PreviewKind = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp" -> PreviewKind.IMAGE
    "mp4", "mkv", "webm", "mp3", "ogg", "wav", "flac", "m4a" -> PreviewKind.MEDIA
    "pdf" -> PreviewKind.PDF
    "json" -> PreviewKind.JSON
    "md", "markdown" -> PreviewKind.MARKDOWN
    "txt", "log", "xml", "yml", "yaml", "conf", "ini", "sh", "kt", "java", "py", "js", "ts",
    "css", "html", "csv", "properties", "gradle", "kts", "toml", "env",
        -> PreviewKind.TEXT
    else -> PreviewKind.OTHER
}

// ---------------------------------------------------------------------------------------------
// Loaders — one per kind, each with its own ceiling
// ---------------------------------------------------------------------------------------------

private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
private const val MAX_TEXT_BYTES = 512L * 1024
private const val MAX_PDF_BYTES = 24L * 1024 * 1024
private const val MAX_PDF_PAGES_SHOWN = 5

private suspend fun loadBitmapPreview(entry: FsEntry, provider: FileSystemProvider): PreviewState {
    val size = entry.size
    if (size != null && size > MAX_IMAGE_BYTES) return PreviewState.TooLarge(size)
    return try {
        val bytes = provider.read(entry.path)
        if (bytes.size > MAX_IMAGE_BYTES) return PreviewState.TooLarge(bytes.size.toLong())
        val bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(bytes) }
            ?: return PreviewState.Failed("This image could not be decoded.")
        PreviewState.Image(bitmap)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        PreviewState.Failed(error.message ?: "The image could not be read")
    }
}

private suspend fun loadTextPreview(
    entry: FsEntry,
    provider: FileSystemProvider,
    kind: PreviewKind,
): PreviewState {
    val size = entry.size
    if (size != null && size > MAX_TEXT_BYTES) return PreviewState.TooLarge(size)
    return try {
        val bytes = provider.read(entry.path)
        if (bytes.size > MAX_TEXT_BYTES) return PreviewState.TooLarge(bytes.size.toLong())
        val text = withContext(Dispatchers.IO) { bytes.toString(Charsets.UTF_8) }
        val rendered = when (kind) {
            PreviewKind.JSON -> prettyJson(text)
                ?.let { RenderedText.Json(it) }
                ?: RenderedText.Text(text)
            PreviewKind.MARKDOWN -> RenderedText.Markdown(parseMarkdown(text))
            else -> RenderedText.Text(text)
        }
        PreviewState.Textual(rendered)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        PreviewState.Failed(error.message ?: "The file could not be read")
    }
}

private suspend fun loadPdfPreview(entry: FsEntry, provider: FileSystemProvider): PreviewState {
    val size = entry.size
    if (size != null && size > MAX_PDF_BYTES) return PreviewState.TooLarge(size)
    return try {
        val bytes = provider.read(entry.path)
        if (bytes.size > MAX_PDF_BYTES) return PreviewState.TooLarge(bytes.size.toLong())
        withContext(Dispatchers.IO) {
            // PdfRenderer wants a seekable file descriptor; a remote PDF arrives as bytes, so it is
            // staged in the cache directory first and removed as soon as the renderer is done.
            renderPdf(bytes)?.let { PreviewState.PdfPages(it.first, it.second) }
                ?: PreviewState.Failed("This PDF could not be opened.")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        PreviewState.Failed(error.message ?: "The PDF could not be read")
    }
}

/**
 * Renders up to [MAX_PDF_PAGES_SHOWN] pages of [pdfBytes], returning the bitmaps and the page
 * count the document itself reports.
 */
private fun renderPdf(pdfBytes: ByteArray): Pair<List<Bitmap>, Int>? {
    val staged = File.createTempFile("preview", ".pdf", null)
    try {
        staged.outputStream().use { it.write(pdfBytes) }
        ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pages = ArrayList<Bitmap>(min(renderer.pageCount, MAX_PDF_PAGES_SHOWN))
                for (index in 0 until min(renderer.pageCount, MAX_PDF_PAGES_SHOWN)) {
                    renderer.openPage(index).use { page ->
                        // 2x: crisp enough to read on a phone screen without a bitmap per page
                        // costing more memory than the preview is worth.
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, Matrix().apply { setScale(2f, 2f) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(bitmap)
                    }
                }
                return pages to renderer.pageCount
            }
        }
    } finally {
        staged.delete()
    }
}

/** Pretty-prints JSON when the text really is JSON; null when it only looks like it. */
private fun prettyJson(text: String): String? = try {
    text.trim().let { trimmed ->
        if (trimmed.startsWith("{")) JSONObject(trimmed).toString(2)
        else if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2)
        else null
    }
} catch (error: Throwable) {
    null
}

/**
 * Decodes [bytes] as a bitmap sized to something a sheet can show, rather than whatever the file
 * happens to be — a 48-megapixel photo is not more useful at 3264 samples, and is 20× the memory.
 */
private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

// ---------------------------------------------------------------------------------------------
// Pieces of the sheet
// ---------------------------------------------------------------------------------------------

@Composable
private fun PreviewHeader(entry: FsEntry) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = entry.size
            if (size != null) {
                Text(
                    describeSize(size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileInformation(entry: FsEntry) {
    // What the backend knows, and no more: every row here is only drawn when the value exists,
    // because an invented "0 B" or "1970" is worse than an absent row.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "No preview for this file type.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        InfoRow("Type", entry.mimeType ?: kindLabel(entry))
        InfoRow("Path", entry.path)
        entry.size?.let { InfoRow("Size", describeSize(it)) }
        entry.modifiedEpochMillis?.let {
            InfoRow("Modified", DateFormat.getDateTimeInstance().format(Date(it)))
        }
        entry.permissions?.let { InfoRow("Permissions", it) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Text(
            label,
            Modifier.width(96.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** An image that fits the sheet until the user pinches, and springs back to fitting on a double-tap. */
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
                    // Panning only means something while zoomed in; back at 1x the image is where
                    // it belongs and the offset is reset rather than nudged.
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

@Composable
private fun PdfPages(pages: List<Bitmap>, totalPages: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (totalPages > pages.size) {
            Text(
                "Showing the first ${pages.size} of $totalPages pages.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pages.forEach { page ->
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun SelectionContainerMonospace(content: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        SelectionContainer {
            Text(
                content,
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    // 0.55 of the screen: the sheet this sits in gets 0.85, and the monospace
                    // body must stay under it so the header and this scroller both fit.
                    .heightIn(max = rememberDialogBodyMaxHeight(0.55f))
                    .verticalScroll(rememberScrollState()),
                fontFamily = TerminalMonoFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Markdown: a small renderer for the subset a terminal user's README actually uses
// ---------------------------------------------------------------------------------------------

/** What the text loaders produce for the textual kinds. */
private sealed interface RenderedText {
    data class Text(val content: String) : RenderedText
    data class Json(val content: String) : RenderedText
    data class Markdown(val blocks: List<MarkdownBlock>) : RenderedText
}

/** One block of a Markdown document, at the granularity the renderer draws it. */
private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : MarkdownBlock
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock
    data class Bullet(val text: AnnotatedString) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val paragraph = StringBuilder()
    var inCode = false
    val code = StringBuilder()

    fun flushParagraph() {
        val joined = paragraph.toString().trim()
        if (joined.isNotEmpty()) blocks.add(MarkdownBlock.Paragraph(markdownInline(joined)))
        paragraph.clear()
    }

    for (rawLine in text.lines()) {
        when {
            rawLine.trimStart().startsWith("```") -> {
                if (inCode) {
                    blocks.add(MarkdownBlock.CodeBlock(code.toString()))
                    code.clear()
                } else {
                    flushParagraph()
                }
                inCode = !inCode
            }

            inCode -> code.appendLine(rawLine)

            rawLine.trim() == "" -> flushParagraph()

            rawLine.trim().let { it == "---" || it == "***" } -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Rule)
            }

            else -> {
                val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(rawLine)
                val bullet = Regex("^\\s*[-*+]\\s+(.*)$").matchEntire(rawLine)
                when {
                    heading != null -> {
                        flushParagraph()
                        blocks.add(
                            MarkdownBlock.Heading(
                                heading.groupValues[1].length,
                                markdownInline(heading.groupValues[2]),
                            ),
                        )
                    }

                    bullet != null -> {
                        flushParagraph()
                        blocks.add(MarkdownBlock.Bullet(markdownInline(bullet.groupValues[1])))
                    }

                    else -> paragraph.appendLine(rawLine)
                }
            }
        }
    }
    if (inCode) blocks.add(MarkdownBlock.CodeBlock(code.toString()))
    flushParagraph()
    return blocks
}

/**
 * The inline marks worth honoring — **bold**, *italic*, `code` — parsed into spans.
 *
 * Deliberately small: a preview that honored every inline construct of the Markdown spec would be
 * a Markdown engine, and pretending to be one is how a README renders *wrong* with confidence.
 */
private fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append(text.substring(i)); break }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append(text.substring(i)); break }
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.Gray.copy(alpha = 0.25f),
                    ),
                ) { append(text.substring(i + 1, end)) }
                i = end + 1
            }

            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end < 0) { append(text.substring(i)); break }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }

            else -> {
                val next = text.indexOfAny(charArrayOf('*', '`'), i + 1)
                if (next < 0) { append(text.substring(i)); break }
                append(text.substring(i, next))
                i = next
            }
        }
    }
}

@Composable
private fun MarkdownDocument(blocks: List<MarkdownBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    block.text,
                    style = when (block.level.coerceIn(1, 6)) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )

                is MarkdownBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)

                is MarkdownBlock.Bullet -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }

                is MarkdownBlock.CodeBlock -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        block.code,
                        Modifier.fillMaxWidth().padding(10.dp),
                        fontFamily = TerminalMonoFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }

                MarkdownBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hand-offs and small helpers
// ---------------------------------------------------------------------------------------------

/** Offers the file to another app — the user's explicit tap, local files only. */
private fun openWithAnotherApp(context: Context, entry: FsEntry) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(entry.path), entry.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
}

private fun describeSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun kindLabel(entry: FsEntry): String = when (previewKind(entry)) {
    PreviewKind.IMAGE -> "Image"
    PreviewKind.TEXT -> "Text"
    PreviewKind.JSON -> "JSON"
    PreviewKind.MARKDOWN -> "Markdown"
    PreviewKind.PDF -> "PDF document"
    PreviewKind.MEDIA -> "Media"
    PreviewKind.OTHER -> "Unknown type"
}
