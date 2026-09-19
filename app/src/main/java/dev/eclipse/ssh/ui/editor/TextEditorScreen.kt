package dev.eclipse.ssh.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.ui.editor.encoding.FileEncoding
import dev.eclipse.ssh.ui.editor.highlight.SyntaxColors
import dev.eclipse.ssh.ui.editor.highlight.debouncedSyntaxTransformationFor
import dev.eclipse.ssh.ui.terminal.TerminalMonoFontFamily
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

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
internal const val MAX_EDIT_BYTES = 512 * 1024

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
    requests: SnapshotStateList<EditorRequest>,
    prefs: EditorPrefs,
    onPrefsChange: (EditorPrefs) -> Unit,
    onClose: () -> Unit,
    // The editor's one route to the system clipboard, both directions, supplied by the activity so
    // it goes through the app's audited SecureClipboard rather than Compose's LocalClipboardManager
    // (deprecated here). Copy keeps the text until something replaces it — editor content is not a
    // short-lived secret to be wiped on a timer — and paste keeps newlines, which a multi-line paste
    // depends on.
    onClipboardCopy: (String) -> Unit,
    onClipboardPaste: () -> String?,
) {
    val scope = rememberCoroutineScope()

    // The tabs themselves. Remembered rather than activity-owned because they are screen-shaped
    // state — undo histories, scroll positions, load outcomes — not requests, which is all the
    // activity knows about.
    val tabs = remember { mutableStateListOf<EditorTabState>() }
    var selectedTabId by remember { mutableStateOf<Long?>(null) }
    var nextTabId by remember { mutableStateOf(0L) }
    var consumedRequests by remember { mutableStateOf(0) }

    // Find & replace. Owned here so both the toolbar toggle and the bar itself speak to one state,
    // and shared across tabs: switching files keeps the query, which is what searching "the same
    // thing" in a second file means.
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findReplacement by remember { mutableStateOf("") }
    var findCaseSensitive by remember { mutableStateOf(false) }
    var matchIndex by remember { mutableStateOf(0) }

    // The tab whose discard-changes dialog is up, if any. A single slot rather than a per-tab
    // flag: only one dialog can be on screen, and while one is, auto-save stands down everywhere.
    var pendingCloseTabId by remember { mutableStateOf<Long?>(null) }

    val active = tabs.firstOrNull { it.id == selectedTabId }

    /** Opens a file — in the tab it is already open in when it is, and in a new one when it is not. */
    fun openTab(request: EditorRequest) {
        val requestKey = "${request.provider.providerId}:${request.entry.path}"
        tabs.firstOrNull { it.key == requestKey }?.let { existing ->
            selectedTabId = existing.id
            return
        }
        // Seeded from the prefs' encoding: a sticky default for files opened from here on. The
        // tab owns its encoding from this line, so a later choice in one tab never reaches into
        // another. fromLabel's null is unreachable — the prefs codec only lets known labels
        // through — but a preference that could crash the load it seeds is not a default worth
        // trusting, so the belt stays.
        val tab = EditorTabState(
            nextTabId++,
            request,
            FileEncoding.fromLabel(prefs.encoding) ?: FileEncoding.UTF_8,
        )
        tabs += tab
        selectedTabId = tab.id
        tab.startLoad(scope)
    }

    fun closeTab(tab: EditorTabState) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        pendingCloseTabId = null
        if (tabs.isEmpty()) {
            onClose()
            return
        }
        // Closing the selected tab hands the selection to the neighbour the closed one occupied the
        // place of — the file that was visually next in line, or the new last one when it was at the end.
        if (selectedTabId == tab.id) {
            selectedTabId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        }
    }

    /** A close is a question while the tab is dirty — the discard dialog answers it — a command otherwise. */
    fun requestClose(tab: EditorTabState) {
        if (tab.dirty) pendingCloseTabId = tab.id else closeTab(tab)
    }

    // ---- Opens ----
    // The activity appends to `requests` as new intents arrive; consuming from the front opens each
    // request exactly once, in order, without reopening anything on recomposition. Keyed on size so
    // a burst of opens lands as one pass, and each append restarts it for the next.
    LaunchedEffect(requests.size) {
        while (consumedRequests < requests.size) {
            openTab(requests[consumedRequests++])
        }
    }

    // ---- Auto-save ----
    // One AutoSaveEffect per tab, keyed by the tab's id so the effect follows the tab through any
    // reshuffle of the list, and composed for *every* tab, not just the active one: a background
    // tab keeps auto-saving too, deliberately — a file the user edited and switched away from is
    // still their work, and "switch tabs" should not mean "pause saving it". The single dialog slot
    // blocks all of them at once, since a question on screen is a question the whole editor stops
    // to hear. A read-only tab blocks its own: save() would refuse the write anyway, so letting
    // the heartbeat count down towards it would only be a timer that ends in nothing. The effect's
    // own comment carries the quiet-period reasoning.
    tabs.forEach { tab ->
        key(tab.id) {
            AutoSaveEffect(tab, scope, prefs, blocked = pendingCloseTabId != null || tab.readOnly)
        }
    }

    // Back: close the find panel first, then guard the active tab's unsaved work, then close that
    // tab — and when the last tab closes, closeTab falls through to onClose, so back out of the
    // final tab leaves the editor itself, as back always did.
    BackHandler(enabled = true) {
        when {
            findOpen -> findOpen = false
            active != null && active.dirty -> pendingCloseTabId = active.id
            active != null -> closeTab(active)
        }
    }

    active?.let { tab ->
        SingleFileEditor(
            tab = tab,
            scope = scope,
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            onClipboardCopy = onClipboardCopy,
            onClipboardPaste = onClipboardPaste,
            findOpen = findOpen,
            findQuery = findQuery,
            findReplacement = findReplacement,
            findCaseSensitive = findCaseSensitive,
            matchIndex = matchIndex,
            onToggleFind = {
                findOpen = !findOpen
                if (!findOpen) matchIndex = 0
            },
            onQuery = { findQuery = it; matchIndex = 0 },
            onReplacement = { findReplacement = it },
            onCaseToggle = { findCaseSensitive = !findCaseSensitive; matchIndex = 0 },
            onMatchIndex = { matchIndex = it },
            onRequestClose = { requestClose(tab) },
            tabStrip = {
                // The strip exists only once there is a choice to make: one file needs no tabs.
                if (tabs.size > 1) {
                    EditorTabStrip(
                        tabs = tabs,
                        selectedTabId = selectedTabId,
                        onSelect = { selectedTabId = it.id },
                        onCloseTab = { requestClose(it) },
                    )
                }
            },
        )
    }

    // The discard dialog, hoisted out of the tabs: only one such question can be on screen at a
    // time, and it is the coordinator that knows which tab a "yes" is about. `blocked` above keys
    // every auto-save on this same slot, so no tab saves its way out from under the question.
    pendingCloseTabId?.let { pendingId ->
        tabs.firstOrNull { it.id == pendingId }?.let { tab ->
            AlertDialog(
                onDismissRequest = { pendingCloseTabId = null },
                title = { Text("Unsaved changes") },
                text = { Text("Leave the editor and discard what you typed in ${tab.request.entry.name}?") },
                confirmButton = {
                    Button(onClick = { closeTab(tab) }) { Text("Discard") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            // Save does not close — the same semantics the single-file editor had:
                            // it saves and leaves the user in the file, and the close has to be
                            // asked for again if they still want it.
                            pendingCloseTabId = null
                            tab.save(scope, false)
                        }) { Text("Save") }
                        TextButton(onClick = { pendingCloseTabId = null }) { Text("Keep editing") }
                    }
                },
            )
        }
    }
}

/**
 * One tab's face: the load outcome, the dialogs that belong to this file (go-to-line, the save
 * conflict), and — once loaded — the body. Everything file-shaped is read from [tab]; everything
 * the user shares across tabs (find & replace, the options sheet's prefs) arrives as parameters.
 */
@Composable
private fun SingleFileEditor(
    tab: EditorTabState,
    scope: CoroutineScope,
    prefs: EditorPrefs,
    onPrefsChange: (EditorPrefs) -> Unit,
    onClipboardCopy: (String) -> Unit,
    onClipboardPaste: () -> String?,
    findOpen: Boolean,
    findQuery: String,
    findReplacement: String,
    findCaseSensitive: Boolean,
    matchIndex: Int,
    onToggleFind: () -> Unit,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onCaseToggle: () -> Unit,
    onMatchIndex: (Int) -> Unit,
    onRequestClose: () -> Unit,
    tabStrip: @Composable () -> Unit,
) {
    // Hoisted above the load-outcome switch because a Failed tab needs the sheet too: the
    // encoding row is exactly how a file the load refused gets a second charset, and the failure
    // screen is where that need is born — a sheet only the Ready state could open would strand
    // every non-UTF-8 file at the moment it most needs opening.
    var optionsOpen by remember { mutableStateOf(false) }

    when (val state = tab.loadState) {
        is EditorLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is EditorLoad.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(tab.request.entry.name, style = MaterialTheme.typography.titleMedium)
            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The way out the message points at: pick another encoding and the tab re-reads its
            // bytes, and a charset the file fits opens it on the spot.
            TextButton(onClick = { optionsOpen = true }) { Text("Editor options") }
            TextButton(onClick = onRequestClose) { Text("Close") }
        }

        is EditorLoad.TooLarge -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(tab.request.entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "This file is ${"%.1f".format(state.bytes / (1024.0 * 1024.0))} MB. The editor opens " +
                    "files up to ${MAX_EDIT_BYTES / (1024 * 1024)} MB so the device does not run out " +
                    "of memory holding it; it can still be downloaded, renamed or deleted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRequestClose) { Text("Close") }
        }

        is EditorLoad.Ready -> EditorBody(
            request = tab.request,
            readOnly = tab.readOnly,
            textValue = tab.textValue,
            onTextChange = tab::applyEdit,
            onSnapshot = tab::applySnapshot,
            onClipboardCopy = onClipboardCopy,
            onClipboardPaste = onClipboardPaste,
            history = tab.history,
            dirty = tab.dirty,
            saving = tab.saving,
            saveError = tab.saveError,
            onSave = { tab.save(scope, false) },
            onClose = onRequestClose,
            prefs = prefs,
            encoding = tab.encoding,
            onOpenOptions = { optionsOpen = true },
            onToggleFind = onToggleFind,
            findOpen = findOpen,
            findQuery = findQuery,
            findReplacement = findReplacement,
            findCaseSensitive = findCaseSensitive,
            matchIndex = matchIndex,
            onQuery = onQuery,
            onReplacement = onReplacement,
            onCaseToggle = onCaseToggle,
            onPrevious = {
                val matches = findAllMatches(tab.textValue.text, findQuery, findCaseSensitive)
                if (matches.isNotEmpty()) {
                    val next = (((matchIndex - 1) % matches.size) + matches.size) % matches.size
                    onMatchIndex(next)
                    tab.selectMatch(matches[next])
                }
            },
            onNext = {
                val matches = findAllMatches(tab.textValue.text, findQuery, findCaseSensitive)
                if (matches.isNotEmpty()) {
                    val next = (matchIndex + 1) % matches.size
                    onMatchIndex(next)
                    tab.selectMatch(matches[next])
                }
            },
            onReplace = {
                val matches = findAllMatches(tab.textValue.text, findQuery, findCaseSensitive)
                if (matches.isNotEmpty()) {
                    val current = matches[matchIndex.coerceIn(0, matches.size - 1)]
                    tab.applyEdit(
                        tab.textValue.copy(
                            text = tab.textValue.text.replaceRange(current.start, current.end, findReplacement),
                            selection = TextRange(current.start + findReplacement.length),
                        ),
                    )
                }
            },
            onReplaceAll = {
                if (findQuery.isNotEmpty()) {
                    tab.applyEdit(
                        tab.textValue.copy(
                            text = replaceAllMatches(tab.textValue.text, findQuery, findReplacement, findCaseSensitive),
                            selection = TextRange(0),
                        ),
                    )
                }
            },
            scrollRequest = tab.scrollRequest,
            onScrollHandled = { tab.scrollRequest = null },
            onGoToLine = { tab.goToLineOpen = true },
            verticalScroll = tab.verticalScroll,
            horizontalScroll = tab.horizontalScroll,
            layout = tab.layout,
            onLayout = { tab.layout = it },
            tabStrip = tabStrip,
        )
    }

    if (tab.goToLineOpen) {
        GoToLineDialog(
            lineCount = tab.textValue.text.count { it == '\n' } + 1,
            onDismiss = { tab.goToLineOpen = false },
            onGo = { line ->
                tab.goToLineOpen = false
                val offset = lineStartOffset(tab.textValue.text, line)
                tab.textValue = tab.textValue.copy(selection = TextRange(offset))
                tab.scrollRequest = offset
            },
        )
    }

    // Per-tab, unlike the discard dialog: a save conflict is a fact about one file, and two files
    // could each have one waiting. Four ways out, all labelled: the two destructive ones are peers
    // of the safe ones on purpose — "dismiss means keep my edits" is a rule the user has to be told,
    // a "Cancel" button is one they can read.
    if (tab.conflictOpen) {
        AlertDialog(
            onDismissRequest = { tab.chooseConflictAction(scope, ConflictChoice.Cancel) },
            title = { Text("Changed since it was opened") },
            text = {
                Text(
                    "${tab.request.entry.name} was modified after the editor opened it. Saving now would " +
                        "overwrite those changes; reloading would discard what you typed.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    tab.chooseConflictAction(scope, ConflictChoice.Overwrite)
                }) { Text("Overwrite") }
            },
            dismissButton = {
                // A column, not a row: three full-width choices read as a list of options, not as
                // a primary with side-conditions — and they stay tappable on a narrow dialog.
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        tab.chooseConflictAction(scope, ConflictChoice.Reload)
                    }) { Text("Reload") }
                    TextButton(onClick = {
                        tab.chooseConflictAction(scope, ConflictChoice.Compare)
                    }) { Text("Compare") }
                    TextButton(onClick = {
                        tab.chooseConflictAction(scope, ConflictChoice.Cancel)
                    }) { Text("Cancel") }
                }
            },
        )
    }

    // One sheet for both load outcomes, so the Failed screen's "Editor options" and the gear in
    // the toolbar open the same rows. The encoding row reads and writes *this tab's* encoding —
    // the tab starts from the prefs value but is its own from there — while every other row is
    // the shared sheet state the prefs carry.
    if (optionsOpen) {
        EditorOptionsDialog(
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            encoding = tab.encoding,
            onEncodingChange = { chosen ->
                // The choice is persisted as the default for files opened later, and applied to
                // the file in front of the user right now; the write itself still only happens
                // through save, dirty flag and all.
                onPrefsChange(prefs.copy(encoding = chosen.label))
                tab.redecode(chosen)
            },
            onDismiss = { optionsOpen = false },
        )
    }

    // The compare half of the same question. Reachable only through the dialog above, and while it
    // is up that dialog is closed — one question on screen at a time.
    tab.compareState?.let { compare ->
        ConflictCompareDialog(
            fileName = tab.request.entry.name,
            localText = tab.textValue.text,
            compare = compare,
            onBack = { tab.closeConflictCompare() },
        )
    }
}

/**
 * The "Compare" choice's answer: the local text beside the on-server text, nothing more.
 *
 * A compare VIEW, not a merge tool, on purpose. The question it serves is binary — keep your
 * version or take the server's — and what the user needs to answer it is to *see* both versions;
 * per-hunk picks and conflict markers are a different feature with different risks, and half a
 * merge tool is worse than none (a user who half-merges and then taps "Overwrite" believes their
 * picks were applied when nothing of the sort happened). So: two columns, labelled, scrollable.
 */
@Composable
private fun ConflictCompareDialog(
    fileName: String,
    localText: String,
    compare: ConflictCompare,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("$fileName: yours vs on server") },
        text = {
            when (compare) {
                ConflictCompare.Loading -> Box(
                    Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is ConflictCompare.Failed ->
                    // One line, not a stack trace: the fetch failed, the answer to the real
                    // question ("overwrite or reload?") is still waiting behind the Back button.
                    Text(compare.message)

                is ConflictCompare.Ready -> {
                    // One vertical scroll shared by both columns: the user reads the versions
                    // *against each other*, so line 40 of one must stay put beside line 40 of the
                    // other. Fresh ScrollStates rather than the tab's own because the editor field
                    // behind the dialog is still composed with those — two scrollables sharing one
                    // ScrollState would fight over its position.
                    val sharedScroll = rememberScrollState()
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Yours", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text(
                                "On server",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(sharedScroll),
                        ) {
                            // No soft wrap: a wrapped line in one column would shift every line
                            // after it out of row-alignment with the other column. Long lines get
                            // their own horizontal scroll per column instead.
                            Text(
                                localText,
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                softWrap = false,
                                style = TextStyle(
                                    fontFamily = TerminalMonoFontFamily,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                ),
                            )
                            Text(
                                compare.serverText,
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                softWrap = false,
                                style = TextStyle(
                                    fontFamily = TerminalMonoFontFamily,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Back, not OK or Done: the view changed nothing, and the label should say so — the
            // user is returning to a question, not confirming an action.
            TextButton(onClick = onBack) { Text("Back") }
        },
    )
}

/**
 * One tab's auto-save heartbeat.
 *
 * Fires only on quiet: the effect is keyed on the text itself, so every keystroke tears the
 * countdown down and starts a fresh one — a save mid-burst would be a network write per
 * sentence, and on SFTP that is real round-trips. `dirty` is a key too, so a save landing
 * (auto or manual, which moves savedText) re-arms rather than re-fires; `saving` keeps the
 * heartbeat from racing its own upload, and the conflict dialog and the coordinator's close
 * question keep it from answering, behind the user's back, a question ("overwrite?", "discard
 * what you typed?") that is still on screen. The compare view joins the conflict dialog in that
 * guard: it is the same question wearing different clothes, and an auto-save firing behind it
 * could re-raise the conflict and stack a second dialog on a decision already being looked at.
 * Reaching the far side of the delay means none of
 * those keys moved — the text stayed put, no dialog opened, no save started — which is exactly
 * "still dirty after the configured quiet". The save itself launches into [scope], the screen's
 * own, so a key moving mid-save (a keystroke, a tab switch) cancels only the countdown, never
 * the write it already started.
 */
@Composable
private fun AutoSaveEffect(
    tab: EditorTabState,
    scope: CoroutineScope,
    prefs: EditorPrefs,
    blocked: Boolean,
) {
    LaunchedEffect(
        tab.textValue.text,
        tab.dirty,
        prefs.autoSaveEnabled,
        prefs.autoSaveDelayMillis,
        tab.saving,
        tab.conflictOpen,
        tab.compareState,
        blocked,
    ) {
        if (!prefs.autoSaveEnabled || !tab.dirty || tab.saving || tab.conflictOpen ||
            tab.compareState != null || blocked
        ) {
            return@LaunchedEffect
        }
        delay(prefs.autoSaveDelayMillis)
        if (tab.dirty) tab.save(scope, false)
    }
}

/**
 * The strip of open tabs. Scrolled rather than squeezed: file names are the one label the editor
 * cannot abbreviate for the user, so the strip keeps them whole and gives up width instead.
 */
@Composable
private fun EditorTabStrip(
    tabs: List<EditorTabState>,
    selectedTabId: Long?,
    onSelect: (EditorTabState) -> Unit,
    onCloseTab: (EditorTabState) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        tabs.forEach { tab ->
            Surface(
                modifier = Modifier
                    .padding(top = 4.dp, start = 4.dp)
                    .clickable(onClick = { onSelect(tab) }),
                shape = MaterialTheme.shapes.small,
                color = if (tab.id == selectedTabId) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tab.request.entry.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            // The label reserves a width of its own so the close button cannot eat
                            // the chip's tap. Material expands any interactive component's *touch
                            // target* to 48dp whatever its visual size, so the close button's 40dp
                            // state layer reaches 4dp past its own edges on each side — far enough
                            // left, on a chip whose name is short, to cover the middle of the chip.
                            // Measured on this strip: with a label of L dp the close target starts
                            // at L+6 and the chip's centre — the point a tap on the tab lands on —
                            // sits at (L+50)/2, so any name narrower than 38dp sent a switch tap
                            // into the close button and closed the tab instead. "notes.txt" is
                            // short enough to have done exactly that. The terminal strip learned
                            // this first (TerminalTabStrip in MainActivity); a 64dp minimum label
                            // puts the centre 13dp clear of the close target, and a minimum is a
                            // layout constraint, so no name and no font can shrink it back under.
                            // The max and the ellipsis are the other end of the same problem: an
                            // unbounded name made a single chip wider than the strip.
                            .widthIn(min = 64.dp, max = 140.dp),
                    )
                    if (tab.dirty) {
                        // The same dot-language the toolbar subtitle already speaks: a dot means
                        // "this file has words in it that are not on disk yet".
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                        )
                    }
                    IconButton(onClick = { onCloseTab(tab) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close tab",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The editable body: toolbar, the caller's tab strip, line-number gutter, the field, a status
 * line, and the find & replace strip. Everything mutates through the callbacks the parent owns,
 * so undo, save and dirty tracking live in exactly one place. The scroll states, the text
 * layout, and the tab strip arrive as parameters — they are the tab's, not the body's, so
 * switching tabs swaps them in and out with the rest of the file instead of being reset.
 */
@Composable
private fun EditorBody(
    request: EditorRequest,
    readOnly: Boolean,
    textValue: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSnapshot: (TextFieldValue) -> Unit,
    onClipboardCopy: (String) -> Unit,
    onClipboardPaste: () -> String?,
    history: EditorHistory,
    dirty: Boolean,
    saving: Boolean,
    saveError: String?,
    onSave: () -> Unit,
    onClose: () -> Unit,
    prefs: EditorPrefs,
    encoding: FileEncoding,
    onOpenOptions: () -> Unit,
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
    verticalScroll: ScrollState,
    horizontalScroll: ScrollState,
    layout: TextLayoutResult?,
    onLayout: (TextLayoutResult) -> Unit,
    tabStrip: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // The options sheet's open flag lives in SingleFileEditor, hoisted there so a Failed tab can
    // open the sheet too; the gear here only reports that it was pressed.

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
    //
    // Debounced and lexed off this thread, because it is the one piece of work here proportional to
    // the whole document: see [debouncedSyntaxTransformationFor]. Passing the document in is what
    // makes it per-keystroke work otherwise.
    val syntaxTransformation = debouncedSyntaxTransformationFor(
        request.entry.name,
        SyntaxColors.fromScheme(MaterialTheme.colorScheme),
        textValue.text,
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

    // The editor's clipboard verbs, each routed through the audited SecureClipboard the parent
    // handed down rather than Compose's LocalClipboardManager. They are shared by the toolbar
    // buttons and the Ctrl-key handler below, so a copy is one copy however it was asked for.
    // Each reports whether it actually acted: the key handler returns that verdict from the
    // preview so a no-op (Ctrl+C with nothing selected, Ctrl+V of an empty clipboard) falls
    // through to the field instead of being swallowed. Copy and select-all only read, so they
    // work on a read-only file; cut and paste change the text and stand down when it cannot be
    // written — the same guard the field's own `readOnly` enforces.
    fun copySelection(): Boolean {
        val selection = textValue.selection
        if (selection.collapsed) return false
        onClipboardCopy(textValue.text.substring(selection.min, selection.max))
        return true
    }
    fun cutSelection(): Boolean {
        if (readOnly) return false
        val selection = textValue.selection
        if (selection.collapsed) return false
        onClipboardCopy(textValue.text.substring(selection.min, selection.max))
        onTextChange(
            textValue.copy(
                text = textValue.text.replaceRange(selection.min, selection.max, ""),
                selection = TextRange(selection.min),
            ),
        )
        return true
    }
    fun pasteClipboard(): Boolean {
        if (readOnly) return false
        val pasted = onClipboardPaste() ?: return false
        val selection = textValue.selection
        onTextChange(
            textValue.copy(
                text = textValue.text.replaceRange(selection.min, selection.max, pasted),
                selection = TextRange(selection.min + pasted.length),
            ),
        )
        return true
    }
    // Select-all changes no text, only the selection, so it goes through onSnapshot: no undo entry
    // to record (EditorHistory ignores a no-text-change edit anyway) and no dirty flag to raise.
    fun selectAllText(): Boolean {
        if (textValue.text.isEmpty()) return false
        onSnapshot(textValue.copy(selection = TextRange(0, textValue.text.length)))
        return true
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
        // A two-row header. The identity row up top carries the file name, the save-state line, and
        // only the actions that are always about the file as a whole — undo, redo, save; the action
        // row below carries the editing verbs. They were one row once, and that row was the bug the
        // user reported: seven 48dp touch targets beside a weight(1f) column starve it to a sliver on
        // a phone, and the "Saved" label — with no line limit of its own — wrapped one glyph per line
        // to fit the sliver it was left, reading as a vertical "S/a/v/e/d". Splitting the actions off
        // gives the name and status the width they need; the hardening on the two Texts (one line, no
        // soft wrap, an ellipsis) makes the vertical stack unrepresentable even if a later change
        // crowds them again.
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                            overflow = TextOverflow.Ellipsis,
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
                            // One line, never wrapped: this is the label that stacked into a vertical
                            // "S/a/v/e/d" when the toolbar squeezed its column, and these three
                            // together make that impossible rather than merely unlikely.
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
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
                    // Greyed out, not hidden: the icon's absence would read as "nothing to save"
                    // rather than "cannot save", and the status line's READ chip is what explains
                    // which of the two it is.
                    IconButton(onClick = onSave, enabled = dirty && !saving && !readOnly) {
                        Icon(Icons.Filled.Done, contentDescription = "Save")
                    }
                }
                // The editing verbs, scrolled rather than squeezed — the same choice the tab strip
                // makes — so a narrow screen slides them under the finger instead of dropping any.
                // Cut and copy act on a selection, so they follow it (disabled when nothing is
                // selected); cut and paste write, so they stand down on a read-only file, while copy
                // and select-all only read and stay live there.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { cutSelection() },
                        enabled = !readOnly && !textValue.selection.collapsed,
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = "Cut")
                    }
                    IconButton(
                        onClick = { copySelection() },
                        enabled = !textValue.selection.collapsed,
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = { pasteClipboard() }, enabled = !readOnly) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                    IconButton(
                        onClick = { selectAllText() },
                        enabled = textValue.text.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                    }
                    IconButton(onClick = onGoToLine) {
                        Icon(Icons.Filled.Tag, contentDescription = "Go to line")
                    }
                    IconButton(onClick = onToggleFind) {
                        Icon(Icons.Filled.Search, contentDescription = "Find and replace")
                    }
                    IconButton(onClick = onOpenOptions) {
                        Icon(Icons.Filled.Settings, contentDescription = "Editor options")
                    }
                }
            }
        }

        // The caller's slot: the tab strip when there is more than one file, nothing when there
        // is not. Directly below the toolbar Surface, in the same tonal band, so the strip reads
        // as part of the header rather than as content that drifted upward.
        tabStrip()

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
        //
        // The viewport is measured here rather than inside the gutter because this is the node the
        // scroll clips: it is the only place that knows how much of the document is on screen, which
        // is what lets the gutter compose the numbers for the lines the user can see and stand in for
        // the rest with a single spacer of their exact combined height.
        var viewportHeightPx by remember { mutableIntStateOf(0) }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportHeightPx = it.height }
                .verticalScroll(verticalScroll),
        ) {
            if (prefs.showLineNumbers) {
                LineNumberGutter(
                    text = textValue.text,
                    layout = layout,
                    style = gutterStyle,
                    scrollOffsetPx = verticalScroll.value,
                    viewportHeightPx = viewportHeightPx,
                )
            }
            BasicTextField(
                value = textValue,
                onValueChange = onTextChange,
                onTextLayout = onLayout,
                textStyle = textStyle,
                // The field itself refuses edits, which is what makes read-only a *mode* rather
                // than a save that always fails: the user can still read, select, copy and search
                // the file, everything that does not change it. (Replace in the find bar can still
                // mutate the working copy through applyEdit — the mode's guard is at save, which
                // then refuses to write; the dirty dot that follows is the honest report of that.)
                readOnly = readOnly,
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
                    // place at the left edge while the text slides under it. The scroll state is
                    // the tab's and exists either way, so flipping word wrap never resurrects a
                    // stale horizontal position: wrap back on discards it, wrap off starts at the
                    // left edge.
                    .then(if (prefs.wordWrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                    .padding(start = 8.dp, top = 8.dp, bottom = 32.dp, end = 8.dp)
                    // Preview, not plain onKeyEvent: the preview phase runs outer-modifier-first,
                    // before the field's own machinery can consume the key, which is the only
                    // reliable place to intercept keys on a focused text field — Tab below, and
                    // the desktop-style Ctrl shortcuts this is an SSH client for (hardware and
                    // Bluetooth keyboards are a real input method here, not a desktopism).
                    // Returning true ends the dispatch — the field never sees the key, so it
                    // cannot re-route it.
                    //
                    // Shortcuts fire on KeyDown only: KeyUp would run every action twice, and
                    // holding a key re-fires KeyDown as a repeat, which is wanted for undo but
                    // must not re-open anything — so the dialog-openers are idempotent calls and
                    // save is gated on the same dirty/!saving guard as the toolbar button.
                    // Everything else returns false so the field (and its own Ctrl handling, if
                    // any) still works; modifiers are matched exactly — Ctrl+S with Shift held
                    // is not "save" — and Tab keeps its unconditional slot below so plain
                    // insertion does not regress.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // Exact Ctrl: no Shift/Alt/Meta riding along, or it is a different
                        // shortcut than the one these branches implement.
                        val ctrl = event.isCtrlPressed && !event.isShiftPressed &&
                            !event.isAltPressed && !event.isMetaPressed
                        when {
                            ctrl && event.key == Key.S && dirty && !saving -> {
                                onSave()
                                true
                            }
                            // Open only: toggling would close a bar the user is reading, and the
                            // desktop gesture they are repeating is "focus find", which the bar
                            // being open already approximates.
                            ctrl && event.key == Key.F && !findOpen -> {
                                onToggleFind()
                                true
                            }
                            ctrl && event.key == Key.G -> {
                                onGoToLine()
                                true
                            }
                            // Same guards as the toolbar buttons: a history that cannot move
                            // falls through (false) rather than swallowing the key.
                            ctrl && event.key == Key.Z && history.canUndo -> {
                                history.undo(textValue)?.let(onSnapshot)
                                true
                            }
                            ctrl && event.key == Key.Y && history.canRedo -> {
                                history.redo(textValue)?.let(onSnapshot)
                                true
                            }
                            // The other redo spelling; Ctrl is checked, Shift required — the
                            // exact mirror of the plain-Ctrl branches above.
                            event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed &&
                                !event.isMetaPressed && event.key == Key.Z && history.canRedo -> {
                                history.redo(textValue)?.let(onSnapshot)
                                true
                            }
                            // Clipboard shortcuts for the hardware and Bluetooth keyboards this is an
                            // SSH client for, routed through the same SecureClipboard-backed handlers
                            // as the toolbar buttons so a copy is one copy either way. Each branch's
                            // value is the handler's verdict: a copy or cut with nothing selected, a
                            // paste of an empty clipboard, a select-all of an empty file — and, for the
                            // two that write, a read-only tab — all return false, so the key falls
                            // through to the field's own handling instead of being swallowed to no
                            // effect.
                            ctrl && event.key == Key.C -> copySelection()
                            ctrl && event.key == Key.X -> cutSelection()
                            ctrl && event.key == Key.V -> pasteClipboard()
                            ctrl && event.key == Key.A -> selectAllText()
                            event.key == Key.Tab -> {
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
                            }
                            else -> false
                        }
                    },
            )
        }

        // One walk of the document answers all three of the status line's questions, and it is keyed
        // on the text and the caret so a recomposition that changed neither - a theme flip, a sheet
        // opening, the highlight landing - does not walk the document again.
        val position = remember(textValue.text, textValue.selection.start) {
            documentPosition(textValue.text, textValue.selection.start)
        }
        Surface(tonalElevation = 2.dp) {
            // The encoding label, not a hard-coded "UTF-8": the status line is where the eye
            // already sits, so a non-UTF-8 tab has to say so here — otherwise the only hint a
            // save will write Windows-1252 is a refusal dialog after the fact.
            Text(
                "Ln ${position.line}, Col ${position.column}    ${position.lines} lines    ${encoding.label}" +
                    // The mode rides along in the status line — the one place the eye already
                    // rests for facts about the file — rather than a toolbar icon, because
                    // read-only is a fact, not an action.
                    if (readOnly) "    READ" else "",
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
}

/**
 * The line numbers, one per logical line, each as tall as that line's wrapped height so the numbers
 * stay glued to their lines however the text wraps.
 *
 * The heights come from the text field's own [TextLayoutResult], which makes the gutter exact by
 * construction rather than by estimation — the lines a long wrapped line occupies in the field are
 * the same height its number is drawn at here.
 *
 * Only the lines inside the viewport are composed. A gutter that emits a text node per line is a
 * thousand composables rebuilt on every keystroke in a thousand-line file, which is a cost the user
 * pays for numbers they cannot see; the lines above and below stand in as one spacer each, of
 * exactly the height they occupy, so what is on screen is identical and what is off it is arithmetic.
 */
@Composable
private fun LineNumberGutter(
    text: String,
    layout: TextLayoutResult?,
    style: TextStyle,
    scrollOffsetPx: Int,
    viewportHeightPx: Int,
) {
    if (layout == null) return
    val density = LocalDensity.current
    // Every logical line's top edge in the field's own coordinates, cumulative, so a line's height is
    // its top minus the next one's and the height of a run of them is a subtraction rather than a sum.
    val tops = remember(text, layout) { lineTops(text, layout) }
    val window = remember(tops, scrollOffsetPx, viewportHeightPx) {
        gutterWindow(tops, scrollOffsetPx.toFloat(), viewportHeightPx.toFloat())
    }
    Column(Modifier.width(44.dp).padding(top = 8.dp, start = 8.dp, end = 4.dp)) {
        // The gutter's own top padding is inside the scrolled content, so it belongs to the first
        // line's offset and not to the spacer: a line skipped above has to carry its full height.
        if (window.first > 0) {
            Spacer(Modifier.height(with(density) { tops[window.first].toDp() }))
        }
        for (index in window) {
            val heightPx = (tops[index + 1] - tops[index]).coerceAtLeast(1f)
            Text(
                "${index + 1}",
                style = style,
                modifier = Modifier.height(with(density) { heightPx.toDp() }),
            )
        }
        val below = tops.size - 1 - window.last
        if (below > 0) {
            val rest = layout.size.height - tops[window.last + 1]
            Spacer(Modifier.height(with(density) { rest.coerceAtLeast(0f).toDp() }))
        }
    }
}

/**
 * The top edge of every logical line, in the field's own pixel coordinates, ascending, with one
 * entry past the end holding the bottom of the whole layout.
 *
 * The extra entry is what makes a line's height its top minus the next one's, and a run of lines a
 * single subtraction rather than a sum. It is the layout's own bottom rather than the top of a line
 * that does not exist, which is what gives the *last* line its height.
 *
 * The walk is bounded by the text the layout was built from, not by [text]: `onTextLayout` fires
 * after layout, so a keystroke between the two leaves the caller holding a document one character
 * ahead of the layout. Asking that layout for a line past its end is an offset it cannot address, so
 * the extra line waits a frame for its number instead of taking the frame down with it.
 *
 * No line is ever materialised as a string — only its start offset is recorded — which is what keeps
 * this off the allocator for a large file.
 */
private fun lineTops(text: String, layout: TextLayoutResult): FloatArray {
    val known = layout.layoutInput.text.length
    val starts = ArrayList<Int>()
    starts.add(0)
    for (index in 0 until minOf(text.length, known)) {
        if (text[index] == '\n') starts.add(index + 1)
    }
    val tops = FloatArray(starts.size + 1)
    for (position in starts.indices) {
        val offset = starts[position].coerceIn(0, known)
        tops[position] = layout.getLineTop(layout.getLineForOffset(offset))
    }
    tops[starts.size] = layout.size.height
    return tops
}

/**
 * Which logical lines to compose, given where they start and how much is on screen.
 *
 * Pure, and separate, because it is the whole of the windowing decision and a mistake in it is a
 * gutter that drops lines or scrolls out of step with the text. [overscan] lines are composed past
 * each edge so that a drag does not outrun the numbers, and the range is clamped to the lines that
 * exist: an empty document still has one line, and a scroll past the end must not ask for one that
 * is not there.
 *
 * [tops] is in the text field's coordinates while [scrollOffsetPx] is in the scrolled content's, and
 * the two differ by the gutter's own top padding — a few pixels, less than a line. That is the other
 * thing [overscan] buys: the window is generous enough that being a padding's worth of pixels out
 * cannot put a line the user can see outside it.
 */
internal fun gutterWindow(
    tops: FloatArray,
    scrollOffsetPx: Float,
    viewportHeightPx: Float,
    overscan: Int = 6,
): IntRange {
    if (tops.size < 2) return 0..0
    val lastLine = tops.size - 2
    if (viewportHeightPx <= 0f) return 0..lastLine.coerceAtMost(overscan)
    // The first line whose bottom edge is past the top of the viewport. Linear rather than a binary
    // search on purpose: the walk is short (it starts near the scan line in every case that matters,
    // because `tops` is ascending) and it is one comparison per line against the array.
    var first = 0
    while (first < lastLine && tops[first + 1] <= scrollOffsetPx) first++
    var last = first
    val bottom = scrollOffsetPx + viewportHeightPx
    while (last < lastLine && tops[last] < bottom) last++
    return (first - overscan).coerceAtLeast(0)..(last + overscan).coerceAtMost(lastLine)
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
 * The editor's options sheet: word wrap, line numbers, tab size, spaces-vs-tabs, auto-save, and
 * the open file's encoding.
 *
 * A window-level [AlertDialog] rather than a bottom sheet because every change applies the moment
 * it is made — [onPrefsChange] persists each toggle on its own, so there is no "OK" to press and
 * nothing to cancel: the dismiss button, the back gesture, and tapping outside all mean close.
 * That immediacy is also why [prefs] is read straight into every row: the sheet never holds a
 * draft copy that could drift from what was already saved.
 *
 * The encoding row is the exception to "the sheet is prefs": it shows and changes the *tab's*
 * encoding, which started from the prefs value but lives in [encoding] here. Every other row is
 * shared state the prefs carry; the encoding is per-file, because files disagree about what they
 * were written as.
 *
 * Tab size and delay step through −/+ buttons rather than free entry, which makes an out-of-range
 * value unrepresentable from here — no validation to write, no error to show. The encoding opens
 * a picker instead of stepping, because its choices are names, not magnitudes: "UTF-16 LE" is not
 * two steps from "UTF-8" in any sense a −/+ pair could honour.
 */
@Composable
private fun EditorOptionsDialog(
    prefs: EditorPrefs,
    onPrefsChange: (EditorPrefs) -> Unit,
    encoding: FileEncoding,
    onEncodingChange: (FileEncoding) -> Unit,
    onDismiss: () -> Unit,
) {
    var encodingPickerOpen by remember { mutableStateOf(false) }
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
                // The one row about the file rather than the editor: everything above is view
                // state, while this decides what the next save writes. The label doubles as the
                // button — a row that already shows the answer should not hide it behind an ellipsis.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Encoding", Modifier.weight(1f))
                    TextButton(onClick = { encodingPickerOpen = true }) { Text(encoding.label) }
                }
            }
        },
        // Not an "OK": nothing is pending, every row already persisted itself. The button exists
        // because a dialog only dismissible by tapping outside it is a puzzle, not a sheet.
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (encodingPickerOpen) {
        EncodingPickerDialog(
            selected = encoding,
            onSelect = {
                encodingPickerOpen = false
                onEncodingChange(it)
            },
            onDismiss = { encodingPickerOpen = false },
        )
    }
}

/**
 * The encoding picker: one row per charset the file codec supports, the current one checked.
 *
 * Every entry of [FileEncoding] is offered — the set the codec can encode is exactly the set the
 * picker may promise, so an entry the list omitted would be a selector that cannot select. A
 * pick applies immediately, like every other row of the sheet beneath it.
 */
@Composable
private fun EncodingPickerDialog(
    selected: FileEncoding,
    onSelect: (FileEncoding) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File encoding") },
        text = {
            Column {
                FileEncoding.entries.forEach { candidate ->
                    // The whole row answers, not just the dot: a radio row that only listened to
                    // its own button would make the bigger target the one that does nothing.
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(candidate) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = candidate == selected, onClick = { onSelect(candidate) })
                        Text(candidate.label, Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        // Cancel, not Done: picking already applied, so the only thing to dismiss *is* the picker.
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

/**
 * Where the caret is, and how many lines the file has, from a single walk of [text].
 *
 * The status line wants all three numbers on every recomposition, and counting them separately meant
 * two passes — one stopping at the caret for the line and column, one to the end for the line count.
 * One pass answers both: a document is a list of lines either way, and the caret's line is simply the
 * last newline before it.
 *
 * [offset] past the end is clamped rather than refused, because a selection can briefly outlive the
 * text it indexed into — a replace that shortens the document is one keystroke and two states.
 */
internal data class DocumentPosition(val line: Int, val column: Int, val lines: Int)

internal fun documentPosition(text: String, offset: Int): DocumentPosition {
    val caret = offset.coerceAtMost(text.length)
    var line = 1
    var column = 1
    var lines = 1
    for (index in text.indices) {
        if (text[index] == '\n') {
            lines++
            if (index < caret) {
                line++
                column = 1
            }
        } else if (index < caret) {
            column++
        }
    }
    return DocumentPosition(line, column, lines)
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
