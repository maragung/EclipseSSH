package dev.eclipse.ssh.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FsModificationConflictException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How close together two keystrokes must land to count as one undo step. */
private const val UNDO_COALESCE_MS = 700L

/** How many undo steps are kept before the oldest starts falling off the front. */
private const val MAX_UNDO_STEPS = 200

/** The editor's load outcome. Only [Ready] shows the body; every other state explains itself. */
internal sealed interface EditorLoad {
    data object Loading : EditorLoad
    data object Ready : EditorLoad
    data class Failed(val message: String) : EditorLoad
    data class TooLarge(val bytes: Long) : EditorLoad
}

/** One button on the 4-way conflict dialog. The dialog itself only wires these to clicks. */
internal enum class ConflictChoice { Overwrite, Reload, Compare, Cancel }

/**
 * The compare view's fetch of the on-server text: loading, shown, or explained away. Null (the
 * tab's [EditorTabState.compareState]) means the compare view is not on screen at all.
 */
internal sealed interface ConflictCompare {
    data object Loading : ConflictCompare
    data class Ready(val serverText: String) : ConflictCompare
    data class Failed(val message: String) : ConflictCompare
}

/**
 * One open file's worth of editor state: the load, the text and everything derived from it.
 *
 * A plain state holder rather than a `remember`-shaped anything, because a tab keeps this state
 * while it is *not* the active tab — background tabs still load, still auto-save, still hold their
 * undo history and scroll position — and `remember` is only alive for as long as a composable is
 * composed. The coroutines the methods launch are handed in by the caller, so they live in the
 * screen's scope and a save survives a mid-flight tab switch (and the tab switch itself is not
 * what cancels one).
 */
internal class EditorTabState(val id: Long, val request: EditorRequest) {
    var loadState by mutableStateOf<EditorLoad>(EditorLoad.Loading)
    var savedText by mutableStateOf("")
    var loadedModified by mutableStateOf<Long?>(null)
    var textValue by mutableStateOf(TextFieldValue(""))
    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var conflictOpen by mutableStateOf(false)
    // Null while the compare view is off screen. Lives on the tab, like conflictOpen, because the
    // text it shows belongs to the same file the question is about — and so it survives the tab
    // switch that a dialog-remembered value would not.
    var compareState by mutableStateOf<ConflictCompare?>(null)
    var scrollRequest by mutableStateOf<Int?>(null)
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var goToLineOpen by mutableStateOf(false)

    val history = EditorHistory()
    val verticalScroll = ScrollState(0)
    val horizontalScroll = ScrollState(0)

    /** The tab's identity: same provider, same path, same tab — re-opening a file selects it. */
    val key get() = "${request.provider.providerId}:${request.entry.path}"

    /**
     * Whether this file can be written at all, from the mode bits the entry carried.
     *
     * Seeded from the request's entry and re-derived from the load's own stat, which is the
     * freshest word on the mode — the listing the request came from can be seconds or minutes
     * old, and a file chmod'ed in between must open the way it is now, not the way it was listed.
     * The re-derivation is the same one-line call, so the two can never disagree about *how* the
     * answer is computed, only about which snapshot of the file they saw.
     */
    var readOnly by mutableStateOf(entryIsReadOnly(request.entry))
        private set

    val dirty get() = textValue.text != savedText

    /**
     * The one-shot load, run at tab creation. A tab's [request] never changes after that, so unlike
     * the single-file editor this once was, there is nothing to re-key on.
     */
    fun startLoad(scope: CoroutineScope) {
        scope.launch {
            if (request.isNewFile) {
                savedText = ""
                loadedModified = request.entry.modifiedEpochMillis
                textValue = TextFieldValue("")
                loadState = EditorLoad.Ready
                return@launch
            }
            loadState = EditorLoad.Loading
            try {
                val entry = request.provider.stat(request.entry.path) ?: request.entry
                // The mode is re-read with everything else the stat refreshes, so a file whose
                // write bit was flipped since the listing opens as what it now is.
                readOnly = entryIsReadOnly(entry)
                val size = entry.size
                if (size != null && size > MAX_EDIT_BYTES) {
                    loadState = EditorLoad.TooLarge(size)
                    return@launch
                }
                val bytes = request.provider.read(request.entry.path)
                if (bytes.size > MAX_EDIT_BYTES) {
                    loadState = EditorLoad.TooLarge(bytes.size.toLong())
                    return@launch
                }
                // Strict, because a permissive decode would open a binary file as mojibake and then
                // save that mojibake back over the original — a silent corruption dressed as a feature.
                val decoded = decodeStrictUtf8(bytes)
                if (decoded == null) {
                    loadState = EditorLoad.Failed(
                        "This file is not valid UTF-8 text. The editor cannot show it without damaging " +
                            "it on save; it can still be downloaded, renamed or deleted.",
                    )
                    return@launch
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
    }

    fun save(scope: CoroutineScope, overwrite: Boolean = false) {
        // A read-only file is refused before anything starts — no coroutine, no `saving` flicker,
        // no error the user would have to dismiss — because there is no answer to give: the mode
        // says the write cannot land, and even the overwrite path (which exists to answer a
        // conflict question) is still a write this file cannot accept.
        if (readOnly) return
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

    fun reloadFromDisk(scope: CoroutineScope) {
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

    /**
     * One button press on the 4-way conflict dialog. The semantics live here rather than in the
     * dialog's onClicks so the choices are testable as plain state moves — and so the dialog is
     * nothing but wiring, with no decision of its own to get wrong.
     *
     * [ConflictChoice.Cancel] just closes the question; today's dismiss already behaved that way,
     * but as a labelled peer of the other three it is a choice the user makes rather than a way
     * out they have to discover.
     */
    fun chooseConflictAction(scope: CoroutineScope, choice: ConflictChoice) {
        when (choice) {
            ConflictChoice.Overwrite -> {
                conflictOpen = false
                save(scope, overwrite = true)
            }
            ConflictChoice.Reload -> {
                conflictOpen = false
                reloadFromDisk(scope)
            }
            ConflictChoice.Cancel -> conflictOpen = false
            ConflictChoice.Compare -> {
                // The question leaves the screen while its evidence is shown, and comes back when
                // the compare view closes — one question on screen at a time, never two dialogs.
                conflictOpen = false
                fetchServerTextForCompare(scope)
            }
        }
    }

    /**
     * Reads the on-server text for the compare view. Read-only on purpose: the user is mid-decision
     * between "overwrite" and "reload", and the compare must not become a third way to move the
     * file. The read can still fail (the connection that reported the conflict can be gone by the
     * time the user asks why) — a failure becomes one line of text in [compareState], never an
     * exception into composition.
     */
    private fun fetchServerTextForCompare(scope: CoroutineScope) {
        compareState = ConflictCompare.Loading
        scope.launch {
            try {
                val fresh = request.provider.read(request.entry.path)
                // Strict decode, same rule as the load: showing a binary file as mojibake next to
                // the local text would be a comparison of nonsense against nonsense.
                compareState = decodeStrictUtf8(fresh)?.let { ConflictCompare.Ready(it) }
                    ?: ConflictCompare.Failed("The file on the server is not valid UTF-8 text.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                compareState = ConflictCompare.Failed(error.message ?: "The file could not be read")
            }
        }
    }

    /** Leaves the compare view. The question it served is still unanswered, so the dialog returns. */
    fun closeConflictCompare() {
        compareState = null
        conflictOpen = true
    }

    /** The one way the text changes: through the history, so undo sees every edit. */
    fun applyEdit(new: TextFieldValue) {
        history.record(textValue, new)
        textValue = new
    }

    /** Undo and redo land here instead — they *are* the history moving, not new edits in it. */
    fun applySnapshot(snapshot: TextFieldValue) {
        textValue = snapshot
    }

    fun selectMatch(range: TextRange) {
        textValue = textValue.copy(selection = range)
        scrollRequest = range.start
    }
}

/**
 * Stepwise undo over snapshots of the field, coalescing typing bursts into single steps.
 *
 * Only *text* changes are recorded: the selection moving on its own (tapping elsewhere, stepping
 * through find matches) is not an edit, and polluting the stack with it would make one undo press
 * do nothing but move the cursor back.
 */
internal class EditorHistory {
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
internal fun decodeStrictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    null
}

/**
 * Whether [entry]'s file cannot be written: the provider speaks POSIX modes and the owner-write
 * bit is off. Kotlin has no octal literal, so the bit is written in binary — 0b010_000_000 is
 * 0o200, the `w` in `rw-` — the same convention the permission presets in PosixPermissions.kt
 * use for exactly this reason.
 *
 * Null permissions means the backend has no notion of modes (local SAF, whose tree grants carry
 * nothing POSIX to read), and unknown is not unwritable: such a file opens editable and a save
 * the backend refuses surfaces as the ordinary save error, which the user can still act on. A
 * permissions string that does not parse as octal is treated the same way — read as unknown
 * rather than guessed at.
 */
internal fun entryIsReadOnly(entry: FsEntry): Boolean {
    val mode = entry.permissions?.toIntOrNull(8) ?: return false
    return (mode and 0b010_000_000) == 0
}
