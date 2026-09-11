package dev.eclipse.ssh.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FsModificationConflictException
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.editor.highlight.SyntaxColors
import dev.eclipse.ssh.ui.editor.highlight.syntaxTransformationFor
import dev.eclipse.ssh.ui.terminal.TerminalMonoFontFamily
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A request to open the full-screen text editor on one file, local or remote.
 *
 * [provider] is the filesystem the entry came from — the editor reads and saves through it, which
 * is what makes one editor serve both backends. [isNewFile] marks a file that was just created
 * empty and has never been read from disk.
 */
data class EditorRequest(
    val entry: FsEntry,
    val provider: FileSystemProvider,
    val isNewFile: Boolean = false,
)

/** The most a file may weigh to be opened here; larger ones are refused with a message, not opened. */
private const val MAX_EDIT_BYTES = 512 * 1024

/** How close together two keystrokes must land to count as one undo step. */
private const val UNDO_COALESCE_MS = 700L

/** How many undo steps are kept before the oldest starts falling off the front. */
private const val MAX_UNDO_STEPS = 200

/**
 * The auto-save delays the options sheet offers, as (millis, label) pairs, fastest first. The
 * codec accepts any delay in its clamp range — a hand-edited blob can carry one this list has
 * never heard of — so the sheet treats the list as its *choices*, not as the truth: a value
 * outside it still works, it just displays as "Custom" and either arrow steps from the 2 s
 * default rather than guessing which side of the list it fell off.
 */
private val AUTO_SAVE_DELAY_CHOICES = listOf(
    500L to "0.5 s",
    1_000L to "1 s",
    2_000L to "2 s",
    5_000L to "5 s",
    10_000L to "10 s",
)

@Composable
fun TextEditorScreen(
    request: EditorRequest,
    prefs: EditorPrefs,
    onPrefsChange: (EditorPrefs) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var loadState by remember { mutableStateOf<EditorLoad>(EditorLoad.Loading) }
    var savedText by remember { mutableStateOf("") }
    var loadedModified by remember { mutableStateOf<Long?>(null) }
    var textValue by remember { mutableStateOf(TextFieldValue("")) }
    val history = remember { EditorHistory() }

    // Find & replace. Owned here so both the toolbar toggle and the bar itself speak to one state.
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findReplacement by remember { mutableStateOf("") }
    var findCaseSensitive by remember { mutableStateOf(false) }
    var matchIndex by remember { mutableStateOf(0) }

    // Dialogs, save, and the one outstanding "bring this offset into view" request.
    var goToLineOpen by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var conflictOpen by remember { mutableStateOf(false) }
    var discardOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var scrollRequest by remember { mutableStateOf<Int?>(null) }

    val dirty = textValue.text != savedText

    /** The one way the text changes: through the history, so undo sees every edit. */
    fun applyEdit(new: TextFieldValue) {
        history.record(textValue, new)
        textValue = new
    }

    /** Undo and redo land here instead — they *are* the history moving, not new edits in it. */
    fun applySnapshot(snapshot: TextFieldValue) {
        textValue = snapshot
    }

    fun save(overwrite: Boolean = false) {
        val bytes = textValue.text.encodeToByteArray()
        saving = true
        saveError = null
        scope.launch {
            try {
                request.provider.write(
                    request.entry.path,
                    bytes,
                    // The guard is armed with the modification time seen at load, and disarmed only
                    // when the user has just answered "overwrite" to the conflict it raised.
                    loadedModified.takeIf { !overwrite },
                )
                savedText = textValue.text
                // Re-stat so the *next* save is guarded by the time this one produced, not the one
                // from before it — otherwise saving twice in a row would raise its own conflict.
                loadedModified = runCatching {
                    request.provider.stat(request.entry.path)?.modifiedEpochMillis
                }.getOrNull() ?: System.currentTimeMillis()
            } catch (conflict: FsModificationConflictException) {
                conflictOpen = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                saveError = error.message ?: "The file could not be saved"
            } finally {
                saving = false
            }
        }
    }

    fun reloadFromDisk() {
        scope.launch {
            try {
                val fresh = request.provider.read(request.entry.path)
                val decoded = decodeStrictUtf8(fresh) ?: return@launch
                savedText = decoded
                loadedModified = request.provider.stat(request.entry.path)?.modifiedEpochMillis
                    ?: System.currentTimeMillis()
                textValue = TextFieldValue(decoded, TextRange(0))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                saveError = error.message ?: "The file could not be reloaded"
            }
        }
    }

    fun selectMatch(range: TextRange) {
        textValue = textValue.copy(selection = range)
        scrollRequest = range.start
    }

    // ---- Load ----
    LaunchedEffect(request.entry.path, request.provider.providerId) {
        if (request.isNewFile) {
            savedText = ""
            loadedModified = request.entry.modifiedEpochMillis
            textValue = TextFieldValue("")
            loadState = EditorLoad.Ready
            return@LaunchedEffect
        }
        loadState = EditorLoad.Loading
        try {
            val entry = request.provider.stat(request.entry.path) ?: request.entry
            val size = entry.size
            if (size != null && size > MAX_EDIT_BYTES) {
                loadState = EditorLoad.TooLarge(size)
                return@LaunchedEffect
            }
            val bytes = request.provider.read(request.entry.path)
            if (bytes.size > MAX_EDIT_BYTES) {
                loadState = EditorLoad.TooLarge(bytes.size.toLong())
                return@LaunchedEffect
            }
            // Strict, because a permissive decode would open a binary file as mojibake and then
            // save that mojibake back over the original — a silent corruption dressed as a feature.
            val decoded = decodeStrictUtf8(bytes)
            if (decoded == null) {
                loadState = EditorLoad.Failed(
                    "This file is not valid UTF-8 text. The editor cannot show it without damaging " +
                        "it on save; it can still be downloaded, renamed or deleted.",
                )
                return@LaunchedEffect
            }
            savedText = decoded
            loadedModified = entry.modifiedEpochMillis
            textValue = TextFieldValue(decoded, TextRange(0))
            loadState = EditorLoad.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadState = EditorLoad.Failed(error.message ?: "The file could not be read")
        }
    }

    // ---- Auto-save ----
    // Fires only on quiet: the effect is keyed on the text itself, so every keystroke tears the
    // countdown down and starts a fresh one — a save mid-burst would be a network write per
    // sentence, and on SFTP that is real round-trips. `dirty` is a key too, so a save landing
    // (auto or manual, which moves savedText) re-arms rather than re-fires; `saving` keeps the
    // heartbeat from racing its own upload, and the two dialog flags keep it from answering, behind
    // the user's back, a question ("overwrite?") that is still on screen. Reaching the far side of
    // the delay means none of those keys moved — the text stayed put, no dialog opened, no save
    // started — which is exactly "still dirty after the configured quiet".
    LaunchedEffect(
        textValue.text,
        dirty,
        prefs.autoSaveEnabled,
        prefs.autoSaveDelayMillis,
        saving,
        conflictOpen,
        discardOpen,
    ) {
        if (!prefs.autoSaveEnabled || !dirty || saving || conflictOpen || discardOpen) {
            return@LaunchedEffect
        }
        delay(prefs.autoSaveDelayMillis)
        if (dirty) save(false)
    }

    // Back: close the find panel first, then guard unsaved work, then leave.
    BackHandler(enabled = true) {
        when {
            findOpen -> findOpen = false
            dirty -> discardOpen = true
            else -> onClose()
        }
    }

    when (val state = loadState) {
        is EditorLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is EditorLoad.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(request.entry.name, style = MaterialTheme.typography.titleMedium)
            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClose) { Text("Close") }
        }

        is EditorLoad.TooLarge -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(request.entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "This file is ${"%.1f".format(state.bytes / (1024.0 * 1024.0))} MB. The editor opens " +
                    "files up to ${MAX_EDIT_BYTES / (1024 * 1024)} MB so the device does not run out " +
                    "of memory holding it; it can still be downloaded, renamed or deleted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClose) { Text("Close") }
        }

        is EditorLoad.Ready -> EditorBody(
            request = request,
            textValue = textValue,
            onTextChange = ::applyEdit,
            onSnapshot = ::applySnapshot,
            history = history,
            dirty = dirty,
            saving = saving,
            saveError = saveError,
            onSave = { save(false) },
            onClose = { if (dirty) discardOpen = true else onClose() },
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            onToggleFind = {
                findOpen = !findOpen
                if (!findOpen) matchIndex = 0
            },
            findOpen = findOpen,
            findQuery = findQuery,
            findReplacement = findReplacement,
            findCaseSensitive = findCaseSensitive,
            matchIndex = matchIndex,
            onQuery = { findQuery = it; matchIndex = 0 },
            onReplacement = { findReplacement = it },
            onCaseToggle = { findCaseSensitive = !findCaseSensitive; matchIndex = 0 },
            onPrevious = {
                val matches = findAllMatches(textValue.text, findQuery, findCaseSensitive)
                if (matches.isNotEmpty()) {
                    val next = (((matchIndex - 1) % matches.size) + matches.size) % matches.size
                    matchIndex = next
                    selectMatch(matches[next])
                }
            },
            onNext = {
                val matches = findAllMatches(textValue.text, findQuery, findCaseSensitive)
                if (matches.isNotEmpty()) {
                    val next = (matchIndex + 1) % matches.size
                    matchIndex = next
                    selectMatch(matches[next])
                }
            },
            onReplace = {
                val matches = findAllMatches(textValue.text, findQuery, findCaseSensitive)
                if (matches.isNotEmpty()) {
                    val current = matches[matchIndex.coerceIn(0, matches.size - 1)]
                    applyEdit(
                        textValue.copy(
                            text = textValue.text.replaceRange(current.start, current.end, findReplacement),
                            selection = TextRange(current.start + findReplacement.length),
                        ),
                    )
                }
            },
            onReplaceAll = {
                if (findQuery.isNotEmpty()) {
                    applyEdit(
                        textValue.copy(
                            text = replaceAllMatches(textValue.text, findQuery, findReplacement, findCaseSensitive),
                            selection = TextRange(0),
                        ),
                    )
                }
            },
            scrollRequest = scrollRequest,
            onScrollHandled = { scrollRequest = null },
            onGoToLine = { goToLineOpen = true },
        )
    }

    if (goToLineOpen) {
        GoToLineDialog(
            lineCount = textValue.text.count { it == '\n' } + 1,
            onDismiss = { goToLineOpen = false },
            onGo = { line ->
                goToLineOpen = false
                val offset = lineStartOffset(textValue.text, line)
                textValue = textValue.copy(selection = TextRange(offset))
                scrollRequest = offset
            },
        )
    }

    if (conflictOpen) {
        AlertDialog(
            onDismissRequest = { conflictOpen = false },
            title = { Text("Changed since it was opened") },
            text = {
                Text(
                    "${request.entry.name} was modified after the editor opened it. Saving now would " +
                        "overwrite those changes; reloading would discard what you typed.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    conflictOpen = false
                    save(overwrite = true)
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = {
                    conflictOpen = false
                    reloadFromDisk()
                }) { Text("Reload") }
            },
        )
    }

    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text("Unsaved changes") },
            text = { Text("Leave the editor and discard what you typed in ${request.entry.name}?") },
            confirmButton = {
                Button(onClick = {
                    discardOpen = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        discardOpen = false
                        save(false)
                    }) { Text("Save") }
                    TextButton(onClick = { discardOpen = false }) { Text("Keep editing") }
                }
            },
        )
    }
}

/** The editor's load outcome. Only [Ready] shows the body; every other state explains itself. */
private sealed interface EditorLoad {
    data object Loading : EditorLoad
    data object Ready : EditorLoad
    data class Failed(val message: String) : EditorLoad
    data class TooLarge(val bytes: Long) : EditorLoad
}

/**
 * The editable body: toolbar, line-number gutter, the field, a status line, and the find & replace
 * strip. Everything mutates through the callbacks the parent owns, so undo, save and dirty tracking
 * live in exactly one place.
 */
@Composable
private fun EditorBody(
    request: EditorRequest,
    textValue: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSnapshot: (TextFieldValue) -> Unit,
    history: EditorHistory,
    dirty: Boolean,
    saving: Boolean,
    saveError: String?,
    onSave: () -> Unit,
    onClose: () -> Unit,
    prefs: EditorPrefs,
    onPrefsChange: (EditorPrefs) -> Unit,
    onToggleFind: () -> Unit,
    findOpen: Boolean,
    findQuery: String,
    findReplacement: String,
    findCaseSensitive: Boolean,
    matchIndex: Int,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onCaseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    scrollRequest: Int?,
    onScrollHandled: () -> Unit,
    onGoToLine: () -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val verticalScroll = rememberScrollState()
    val density = LocalDensity.current
    // The options sheet's open flag lives here, next to the gear that opens it. Owned here rather
    // than in the parent because nothing above EditorBody needs to know it is on screen.
    var optionsOpen by remember { mutableStateOf(false) }
    // Remembered unconditionally so flipping word wrap never resurrects a stale horizontal
    // position: turning wrap back on discards it, turning it off starts at the left edge.
    val horizontalScroll = rememberScrollState()

    val textStyle = TextStyle(
        fontFamily = TerminalMonoFontFamily,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val gutterStyle = TextStyle(
        fontFamily = TerminalMonoFontFamily,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
    // Read here in composition and handed to the transformation, which itself cannot ask the theme.
    val otherMatchColor = MaterialTheme.colorScheme.secondaryContainer
    val currentMatchColor = MaterialTheme.colorScheme.primaryContainer
    // Syntax coloring, resolved once per file: the palette follows the theme, the language follows
    // the name, and neither can change without a recomposition that rebuilds this anyway. The
    // field's own text color stays SyntaxColors.plain (see fromScheme) so uncolored runs and the
    // status bar keep agreeing; find-matches swap in over it because a match highlight must win
    // over grammar coloring while the bar is open.
    val syntaxTransformation = syntaxTransformationFor(
        request.entry.name,
        SyntaxColors.fromScheme(MaterialTheme.colorScheme),
    )

    // Bring the requested offset into view — from go-to-line or from stepping through matches.
    LaunchedEffect(scrollRequest) {
        val offset = scrollRequest ?: return@LaunchedEffect
        val currentLayout = layout ?: run {
            onScrollHandled()
            return@LaunchedEffect
        }
        val visualLine = currentLayout.getLineForOffset(offset.coerceIn(0, textValue.text.length))
        val targetY = currentLayout.getLineTop(visualLine)
        val lead = with(density) { 96.dp.toPx() }
        verticalScroll.animateScrollTo((targetY - lead).toInt().coerceAtLeast(0))
        onScrollHandled()
    }

    // navigationBarsPadding rather than leaving the root to imePadding alone: with edge-to-edge the
    // text area would otherwise run under the gesture bar on every device, and placing the two
    // inset paddings side by side takes the larger of them rather than summing, so an open IME
    // does not double-pad. The status bar is handled on the toolbar Row below, inside the Surface,
    // so the tonal-elevation toolbar keeps painting behind it.
    //
    // The background is painted rather than borrowed from the window: the window background is the
    // XML window_background, which follows the SYSTEM dark mode, while the text below is colored by
    // this theme, which follows the app's own dark-theme setting (default dark). On a light-mode
    // device that mismatch put near-white text on a near-white window - a 1.07:1 contrast failure -
    // so the canvas now agrees with the text by construction, the same way the workspace root does.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close editor")
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        if (dirty) "${request.entry.name} •" else request.entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            saving -> "Saving…"
                            dirty -> "Unsaved changes"
                            else -> "Saved"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { history.undo(textValue)?.let(onSnapshot) },
                    enabled = history.canUndo,
                ) {
                    Icon(Icons.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(
                    onClick = { history.redo(textValue)?.let(onSnapshot) },
                    enabled = history.canRedo,
                ) {
                    Icon(Icons.Filled.Redo, contentDescription = "Redo")
                }
                IconButton(onClick = onGoToLine) {
                    Icon(Icons.Filled.Tag, contentDescription = "Go to line")
                }
                IconButton(onClick = onToggleFind) {
                    Icon(Icons.Filled.Search, contentDescription = "Find and replace")
                }
                IconButton(onClick = { optionsOpen = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Editor options")
                }
                IconButton(onClick = onSave, enabled = dirty && !saving) {
                    Icon(Icons.Filled.Done, contentDescription = "Save")
                }
            }
        }

        saveError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    message,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // One scroll for both columns: the row scrolls, so the gutter and the field — both measuring
        // their full content height — move together by construction, not by synchronization. That
        // holds with wrap off too: an unwrapped line is simply one very tall-less visual line, so
        // the gutter's per-logical-line heights (which come from the same layout either way) still
        // match the field's, one visual line per logical line.
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(verticalScroll)) {
            if (prefs.showLineNumbers) {
                LineNumberGutter(text = textValue.text, layout = layout, style = gutterStyle)
            }
            BasicTextField(
                value = textValue,
                onValueChange = onTextChange,
                onTextLayout = { layout = it },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (findOpen && findQuery.isNotEmpty()) {
                    MatchHighlightTransformation(findQuery, findCaseSensitive, matchIndex, otherMatchColor, currentMatchColor)
                } else {
                    syntaxTransformation
                },
                modifier = Modifier
                    .weight(1f)
                    // Wrap off means the field's width must stop being the fold line. A horizontal
                    // scroll hands the text unbounded width to lay out in, so long lines extend
                    // right and the user scrolls to them instead of watching them fold; the field's
                    // own footprint stays the weighted width either way, so the gutter keeps its
                    // place at the left edge while the text slides under it.
                    .then(if (prefs.wordWrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                    .padding(start = 8.dp, top = 8.dp, bottom = 32.dp, end = 8.dp)
                    // Preview, not plain onKeyEvent: the preview phase runs outer-modifier-first,
                    // before the field's own machinery can consume the key, which is the only
                    // reliable place to intercept Tab on a focused text field. Returning true ends
                    // the dispatch — the field never sees the key, so it cannot re-route it.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                            val insertion = tabInsertion(prefs)
                            val start = textValue.selection.min
                            val end = textValue.selection.max
                            onTextChange(
                                textValue.copy(
                                    text = textValue.text.replaceRange(start, end, insertion),
                                    selection = TextRange(start + insertion.length),
                                ),
                            )
                            true
                        } else {
                            false
                        }
                    },
            )
        }

        val (line, column) = lineAndColumn(textValue.text, textValue.selection.start)
        Surface(tonalElevation = 2.dp) {
            Text(
                "Ln $line, Col $column    ${textValue.text.count { it == '\n' } + 1} lines    UTF-8",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (findOpen) {
            FindReplaceBar(
                query = findQuery,
                replacement = findReplacement,
                caseSensitive = findCaseSensitive,
                matchCount = countMatches(textValue.text, findQuery, findCaseSensitive),
                matchIndex = matchIndex,
                onQuery = onQuery,
                onReplacement = onReplacement,
                onCaseToggle = onCaseToggle,
                onPrevious = onPrevious,
                onNext = onNext,
                onReplace = onReplace,
                onReplaceAll = onReplaceAll,
                onClose = onToggleFind,
            )
        }
    }

    if (optionsOpen) {
        EditorOptionsDialog(
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            onDismiss = { optionsOpen = false },
        )
    }
}

/**
 * The line numbers, one per logical line, each as tall as that line's wrapped height so the numbers
 * stay glued to their lines however the text wraps.
 *
 * The heights come from the text field's own [TextLayoutResult], which makes the gutter exact by
 * construction rather than by estimation — the lines a long wrapped line occupies in the field are
 * the same height its number is drawn at here.
 */
@Composable
private fun LineNumberGutter(
    text: String,
    layout: TextLayoutResult?,
    style: TextStyle,
) {
    Column(Modifier.width(44.dp).padding(top = 8.dp, start = 8.dp, end = 4.dp)) {
        if (layout == null) return
        val density = LocalDensity.current
        // One visual line's height, for empty logical lines, which have no run of text to measure.
        // Taken from visual line 0, which exists as soon as there is a layout at all.
        val singleLine = (layout.getLineBottom(0) - layout.getLineTop(0)).coerceAtLeast(1f)
        var offset = 0
        for ((index, lineText) in text.split('\n').withIndex()) {
            val heightPx = if (lineText.isEmpty()) {
                singleLine
            } else {
                val first = layout.getLineForOffset(offset)
                val last = layout.getLineForOffset(offset + lineText.length - 1)
                (layout.getLineBottom(last) - layout.getLineTop(first)).coerceAtLeast(singleLine)
            }
            Text(
                "${index + 1}",
                style = style,
                modifier = Modifier.height(with(density) { heightPx.toDp() }),
            )
            offset += lineText.length + 1
        }
    }
}

/**
 * Highlights every find match, and the current one differently.
 *
 * A [VisualTransformation] rather than styled text in the field's value: the value stays the plain
 * text the user owns, so undo history, save comparison and match offsets all keep working on
 * unshifted offsets — the highlight is purely a way of *looking* at the text.
 */
private class MatchHighlightTransformation(
    private val query: String,
    private val caseSensitive: Boolean,
    private val currentIndex: Int,
    private val otherMatchColor: Color,
    private val currentMatchColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        findAllMatches(text.text, query, caseSensitive).forEachIndexed { index, range ->
            spans.add(
                AnnotatedString.Range(
                    SpanStyle(background = if (index == currentIndex) currentMatchColor else otherMatchColor),
                    start = range.start,
                    end = range.end,
                ),
            )
        }
        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }
}

/** The find & replace strip along the bottom of the editor. */
@Composable
private fun FindReplaceBar(
    query: String,
    replacement: String,
    caseSensitive: Boolean,
    matchCount: Int,
    matchIndex: Int,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onCaseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Find") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onCaseToggle) { Text(if (caseSensitive) "Aa" else "aa") }
                TextButton(onClick = onPrevious, enabled = matchCount > 0) { Text("↑") }
                TextButton(onClick = onNext, enabled = matchCount > 0) { Text("↓") }
                Text(
                    if (matchCount == 0) "0" else "${(matchIndex % matchCount) + 1}/$matchCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacement,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Replace with") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onReplace, enabled = matchCount > 0) { Text("Replace") }
                TextButton(onClick = onReplaceAll, enabled = matchCount > 0) { Text("All") }
            }
        }
    }
}

@Composable
private fun GoToLineDialog(lineCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    val parsed = value.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to line") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { input -> value = input.filter { it.isDigit() } },
                label = { Text("Line number (1–$lineCount)") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onGo(parsed!!.coerceIn(1, lineCount)) },
                enabled = parsed != null && parsed in 1..lineCount,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The editor's options sheet: word wrap, line numbers, tab size, spaces-vs-tabs, and auto-save.
 *
 * A window-level [AlertDialog] rather than a bottom sheet because every change applies the moment
 * it is made — [onPrefsChange] persists each toggle on its own, so there is no "OK" to press and
 * nothing to cancel: the dismiss button, the back gesture, and tapping outside all mean close.
 * That immediacy is also why [prefs] is read straight into every row: the sheet never holds a
 * draft copy that could drift from what was already saved.
 *
 * Tab size and delay step through −/+ buttons rather than free entry, which makes an out-of-range
 * value unrepresentable from here — no validation to write, no error to show.
 */
@Composable
private fun EditorOptionsDialog(
    prefs: EditorPrefs,
    onPrefsChange: (EditorPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editor options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OptionsSwitchRow(
                    label = "Word wrap",
                    checked = prefs.wordWrap,
                    onCheckedChange = { onPrefsChange(prefs.copy(wordWrap = it)) },
                )
                OptionsSwitchRow(
                    label = "Line numbers",
                    checked = prefs.showLineNumbers,
                    onCheckedChange = { onPrefsChange(prefs.copy(showLineNumbers = it)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tab size", Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onPrefsChange(
                                prefs.copy(
                                    tabSize = (prefs.tabSize - 1).coerceIn(
                                        EditorPrefsCodec.TAB_SIZE_MIN,
                                        EditorPrefsCodec.TAB_SIZE_MAX,
                                    ),
                                ),
                            )
                        },
                        enabled = prefs.tabSize > EditorPrefsCodec.TAB_SIZE_MIN,
                    ) { Text("−") }
                    Text(
                        "${prefs.tabSize}",
                        Modifier.width(28.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            onPrefsChange(
                                prefs.copy(
                                    tabSize = (prefs.tabSize + 1).coerceIn(
                                        EditorPrefsCodec.TAB_SIZE_MIN,
                                        EditorPrefsCodec.TAB_SIZE_MAX,
                                    ),
                                ),
                            )
                        },
                        enabled = prefs.tabSize < EditorPrefsCodec.TAB_SIZE_MAX,
                    ) { Text("+") }
                }
                OptionsSwitchRow(
                    label = "Spaces instead of tabs",
                    checked = prefs.spacesInsteadOfTabs,
                    onCheckedChange = { onPrefsChange(prefs.copy(spacesInsteadOfTabs = it)) },
                )
                OptionsSwitchRow(
                    label = "Auto-save",
                    checked = prefs.autoSaveEnabled,
                    onCheckedChange = { onPrefsChange(prefs.copy(autoSaveEnabled = it)) },
                )
                // Stepped through the offered delays only, so the codec's clamp range can never be
                // reached from here — but the row still shows a custom delay honestly rather than
                // rounding it to the nearest lie, and the first step lands on the 2 s default.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-save delay", Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onPrefsChange(
                                prefs.copy(autoSaveDelayMillis = stepAutoSaveDelay(prefs.autoSaveDelayMillis, -1)),
                            )
                        },
                        enabled = prefs.autoSaveDelayMillis > AUTO_SAVE_DELAY_CHOICES.first().first,
                    ) { Text("−") }
                    Text(
                        autoSaveDelayLabel(prefs.autoSaveDelayMillis),
                        Modifier.width(56.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            onPrefsChange(
                                prefs.copy(autoSaveDelayMillis = stepAutoSaveDelay(prefs.autoSaveDelayMillis, +1)),
                            )
                        },
                        enabled = prefs.autoSaveDelayMillis < AUTO_SAVE_DELAY_CHOICES.last().first,
                    ) { Text("+") }
                }
            }
        },
        // Not an "OK": nothing is pending, every row already persisted itself. The button exists
        // because a dialog only dismissible by tapping outside it is a puzzle, not a sheet.
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** One "label — switch" line of the options sheet; the sheet is little but rows of these. */
@Composable
private fun OptionsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Stepwise undo over snapshots of the field, coalescing typing bursts into single steps.
 *
 * Only *text* changes are recorded: the selection moving on its own (tapping elsewhere, stepping
 * through find matches) is not an edit, and polluting the stack with it would make one undo press
 * do nothing but move the cursor back.
 */
private class EditorHistory {
    private val past = ArrayDeque<TextFieldValue>()
    private val future = ArrayDeque<TextFieldValue>()
    private var lastEditAt = 0L

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun record(old: TextFieldValue, new: TextFieldValue) {
        if (old.text == new.text) return
        val now = System.currentTimeMillis()
        // The first edit of a burst pushes the state it replaced; the rest of the burst changes
        // nothing in the past, so one undo undoes the whole burst.
        if (now - lastEditAt > UNDO_COALESCE_MS) {
            past.addLast(old)
            if (past.size > MAX_UNDO_STEPS) past.removeFirst()
        }
        future.clear()
        lastEditAt = now
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        lastEditAt = 0L
        return previous
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        lastEditAt = 0L
        return next
    }
}

/** Decodes UTF-8 strictly, returning null when the bytes are not valid UTF-8. */
private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    null
}

private fun findAllMatches(text: String, query: String, caseSensitive: Boolean): List<TextRange> {
    if (query.isEmpty()) return emptyList()
    val haystack = if (caseSensitive) text else text.lowercase(Locale.ROOT)
    val needle = if (caseSensitive) query else query.lowercase(Locale.ROOT)
    val ranges = ArrayList<TextRange>()
    var from = 0
    while (true) {
        val at = haystack.indexOf(needle, from)
        if (at < 0) break
        ranges.add(TextRange(at, at + needle.length))
        from = at + needle.length
    }
    return ranges
}

private fun countMatches(text: String, query: String, caseSensitive: Boolean): Int =
    findAllMatches(text, query, caseSensitive).size

private fun replaceAllMatches(
    text: String,
    query: String,
    replacement: String,
    caseSensitive: Boolean,
): String = findAllMatches(text, query, caseSensitive).asReversed()
    .fold(text) { acc, range -> acc.replaceRange(range.start, range.end, replacement) }

private fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var column = 1
    for (i in 0 until offset.coerceAtMost(text.length)) {
        if (text[i] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }
    return line to column
}

private fun lineStartOffset(text: String, line: Int): Int {
    var current = 1
    var offset = 0
    while (current < line && offset < text.length) {
        if (text[offset] == '\n') current++
        offset++
    }
    return offset.coerceAtMost(text.length)
}

/**
 * What the Tab key inserts at the cursor for [prefs]: one tab character, or [EditorPrefs.tabSize]
 * spaces when the user asked for spaces. Extracted because it is the one piece of the Tab-key path
 * worth pinning in a plain unit test — the key interception itself is Compose machinery that only
 * a device (or a fragile instrumented test) can exercise.
 */
internal fun tabInsertion(prefs: EditorPrefs): String =
    if (prefs.spacesInsteadOfTabs) " ".repeat(prefs.tabSize) else "\t"

/**
 * The options sheet's label for a delay: one of the offered choices' labels, or "Custom" for a
 * value the codec accepted but the sheet has never offered — shown as what it is rather than
 * rounded to the nearest offered lie, which would silently change the delay on the next save.
 */
internal fun autoSaveDelayLabel(millis: Long): String =
    AUTO_SAVE_DELAY_CHOICES.firstOrNull { it.first == millis }?.second ?: "Custom"

/**
 * Steps [current] by [direction] (−1 or +1) through the offered delays, clamped to the list's ends.
 *
 * A delay from outside the list has no neighbour in it, so either arrow starts from the 2 s
 * default — the same value a fresh install gets — rather than guessing which side of the list the
 * value fell off. The clamping makes the buttons' enabled/disabled state and the values they can
 * produce agree by construction.
 */
internal fun stepAutoSaveDelay(current: Long, direction: Int): Long {
    val currentIndex = AUTO_SAVE_DELAY_CHOICES.indexOfFirst { it.first == current }
    val from = if (currentIndex >= 0) currentIndex else DEFAULT_DELAY_CHOICE_INDEX
    return AUTO_SAVE_DELAY_CHOICES[(from + direction).coerceIn(0, AUTO_SAVE_DELAY_CHOICES.lastIndex)].first
}

/** The index of the 2 s choice in [AUTO_SAVE_DELAY_CHOICES] — the list's default, and custom's anchor. */
private val DEFAULT_DELAY_CHOICE_INDEX = AUTO_SAVE_DELAY_CHOICES.indexOfFirst { it.first == 2_000L }
