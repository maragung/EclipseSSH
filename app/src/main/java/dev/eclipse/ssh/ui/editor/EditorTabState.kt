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
import dev.eclipse.ssh.ui.editor.encoding.FileEncoding
import dev.eclipse.ssh.ui.editor.encoding.FileEncodingCodec
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
internal class EditorTabState(
    val id: Long,
    val request: EditorRequest,
    initialEncoding: FileEncoding = FileEncoding.UTF_8,
) {
    var loadState by mutableStateOf<EditorLoad>(EditorLoad.Loading)
    var savedText by mutableStateOf("")
    var loadedModified by mutableStateOf<Long?>(null)
    var textValue by mutableStateOf(TextFieldValue(""))
    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var conflictOpen by mutableStateOf(false)
    var scrollRequest by mutableStateOf<Int?>(null)
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var goToLineOpen by mutableStateOf(false)

    /**
     * The charset this tab reads and writes its file as. Starts from the prefs' encoding (a
     * sticky default, not a per-file memory) and is then the tab's own: the options sheet's
     * encoding row changes it here, never in a sibling tab.
     */
    var encoding by mutableStateOf(initialEncoding)

    /**
     * The file's bytes as last read from disk. Held so the encoding row can re-decode without a
     * fresh read — the bytes are the truth the text is only a view of. A plain field, not
     * snapshot state: nothing composes off it, and a ByteArray in a snapshot would be compared
     * by identity on every write.
     */
    private var fileBytes: ByteArray? = null

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
                fileBytes = bytes
                // Armed before the decode, not after it: a tab whose load fails can still be
                // re-read under another encoding from the options sheet, and that re-read's
                // first save must be guarded by the same clock every other load's is.
                loadedModified = entry.modifiedEpochMillis
                // Strict, because a permissive decode would open a binary file as mojibake and
                // then save that mojibake back over the original — a silent corruption dressed
                // as a feature. The charset is the tab's own, so the failure names it.
                val decoded = FileEncodingCodec.decodeStrict(bytes, encoding)
                if (decoded == null) {
                    loadState = EditorLoad.Failed(notValidTextMessage(encoding))
                    return@launch
                }
                savedText = decoded
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
        saving = true
        saveError = null
        scope.launch {
            try {
                // Encoded inside the guard — and from the text as it stood when the save began —
                // so a single-byte encoding's refusal (a character it cannot hold) lands in
                // saveError with its message instead of crashing the button press that started
                // this, and so the baseline below cannot claim a save of text it never wrote.
                val text = textValue.text
                val bytes = FileEncodingCodec.encode(text, encoding)
                request.provider.write(
                    request.entry.path,
                    bytes,
                    // The guard is armed with the modification time seen at load, and disarmed only
                    // when the user has just answered "overwrite" to the conflict it raised.
                    loadedModified.takeIf { !overwrite },
                )
                savedText = text
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
                fileBytes = fresh
                // A reload under an encoding the fresh bytes do not fit keeps the text on
                // screen: throwing the user's view away on a charset mismatch is the load's
                // job (a Failed state), not the reload's.
                val decoded = FileEncodingCodec.decodeStrict(fresh, encoding) ?: return@launch
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
     * Re-reads the bytes this tab was loaded from as [newEncoding] — the options sheet's encoding
     * row.
     *
     * The re-decode works from the bytes as loaded, never from the text on screen: text is what
     * the *old* encoding made of the file, and decoding that again would be a transcode, not a
     * re-read. In the Ready state the swap goes through [applyEdit] like any edit, so undo gets
     * the previous text back, and the saved baseline is left alone — the tab goes dirty exactly
     * when the re-decode changed what is on screen, and a save writes the file under the new
     * encoding only because the user asked for it.
     *
     * A tab still sitting in [EditorLoad.Failed] is a fresh open in disguise: the load refused
     * under the old encoding, and this is the second chance the failure screen's options sheet
     * offers. There the decoded text becomes both the working copy and the baseline, clean, the
     * same deal a first successful load gives.
     *
     * A strict failure refuses the switch outright — the encoding stays what it was and the
     * refusal names the charset — rather than half-applying a new encoding over text the old
     * one produced.
     */
    fun redecode(newEncoding: FileEncoding) {
        val bytes = fileBytes
        if (bytes == null) {
            // A brand-new file has never been read from disk, so there is nothing to re-decode;
            // the choice only changes what the first save writes.
            encoding = newEncoding
            return
        }
        val decoded = FileEncodingCodec.decodeStrict(bytes, newEncoding)
        if (decoded == null) {
            if (loadState is EditorLoad.Failed) {
                // Still on the failure screen, so the refusal replaces the message the user is
                // already reading rather than a save-error bar they cannot see.
                loadState = EditorLoad.Failed(notValidTextMessage(newEncoding))
            } else {
                saveError =
                    "This file is not valid ${newEncoding.label} text; it was left as ${encoding.label}."
            }
            return
        }
        encoding = newEncoding
        if (loadState is EditorLoad.Failed) {
            savedText = decoded
            textValue = TextFieldValue(decoded, TextRange(0))
            loadState = EditorLoad.Ready
        } else {
            // A burst of typing ends here: the swap is not part of it, and undo must be able to
            // take the old text back without taking the typing with it.
            history.endBurst()
            applyEdit(TextFieldValue(decoded, TextRange(0)))
        }
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

    /**
     * Ends any typing burst still open, so the next [record] pushes its own undo step. For the
     * one caller that needs it — a re-decode replacing the whole text — folding the swap into a
     * burst of typing would let one undo press skip over it and the typing both.
     */
    fun endBurst() {
        lastEditAt = 0L
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

/**
 * The Failed message for bytes [encoding] cannot read. Spelled once because both roads to it —
 * the load and the encoding row's refusal — must say the same thing: which charset refused, that
 * showing the file anyway would damage it on save, and that the file is still a file (download,
 * rename, delete all still work — or pick another encoding and try again).
 */
internal fun notValidTextMessage(encoding: FileEncoding): String =
    "This file is not valid ${encoding.label} text. The editor cannot show it without damaging " +
        "it on save; it can still be downloaded, renamed or deleted."

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
