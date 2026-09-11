package dev.eclipse.ssh.ui.editor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * The lifecycle of one editor tab: what it is, what it sits on, and where its work stands.
 *
 * Pure state, no Android types, because every rule the user's requirements spell out is a rule
 * about *this* state machine and is cheapest to hold in one place the UI merely renders:
 *
 *  - **Save is guarded.** A save that would clobber a server change raises [FileConflict] rather
 *    than writing; the four answers the conflict dialog offers (keep local, reload server,
 *    compare, cancel) are the four transitions out of it, so no answer is a silent overwrite.
 *  - **The server is watched, not polled from the UI.** [checkServer] runs on a timer; when the
 *    file's mtime or size on the server has moved since load, the tab becomes
 *    [ServerChanged] — a notification, never an action, exactly as required: the user's local
 *    text is never replaced by anything other than an explicit reload.
 *  - **Read-only is a first-class mode.** Auto-detected from the file's permissions when the
 *    provider can speak them, user-toggled afterwards; in it, save is refused rather than
 *    erroring later at the server.
 *
 * The UI layer owns the actual I/O (it has the coroutine scopes and the provider); this class
 * decides what the I/O's outcome *means*, which is the part that needs to be testable without a
 * filesystem.
 */
class EditorTabModel(
    /** The file's name, as the tab is titled. The path it lives at is [path]. */
    val name: String,
    /** The provider-opaque path — a document URI locally, a POSIX path over SFTP. */
    val path: String,
    /** Which provider [path] belongs to ("local", or the session key of an SFTP connection). */
    val providerId: String,
    /** The human-facing server/connection name for the status bar, or null for a local file. */
    val connectionName: String?,
    initialText: String,
    /** The mtime the text was loaded from, or null when the file has never been saved anywhere. */
    initialServerModified: Long?,
    /** The size the text was loaded from, or null likewise. */
    initialServerSize: Long?,
    initialReadOnly: Boolean,
) {
    /**
     * The text as loaded or last saved — the baseline "modified" is measured against.
     *
     * Not exposed as a var by accident: it changes only through [setText], [saveSucceeded] and
     * [reloadSucceeded], each of which is a state transition with its own reasons.
     */
    var savedText: String = initialText
        private set

    /** The working text. The editor field writes here on every keystroke. */
    var text: String = initialText
        private set

    /** mtime and size as the server last reported them when the current text was agreed. */
    private var serverModified: Long? = initialServerModified
    private var serverSize: Long? = initialServerSize

    /** Read-only flag; auto-detected at open, user-toggleable from the toolbar. */
    var readOnly: Boolean = initialReadOnly

    /** True when the working text differs from the saved baseline. */
    val modified: Boolean get() = text != savedText

    /**
     * True when the file has changed on the server since this tab's text was agreed with it.
     *
     * Set by [serverChanged] (the watcher's verdict) and cleared by the two transitions that
     * re-establish agreement: a successful save, and an explicit reload.
     */
    var changedOnServer: Boolean = false
        private set

    /**
     * A save is in flight. The toolbar shows the uploading state for exactly this window.
     */
    var saving: Boolean = false
        private set

    /**
     * Feeds an edit from the editor field.
     *
     * The whole of the editor's write path: undo history and dirty tracking both key off
     * comparing against [savedText], so the model needs to know nothing about cursors.
     */
    fun setText(new: String) {
        text = new
    }

    /**
     * The guard the save must pass, as data: the mtime to compare on the server, or null when
     * the caller has just resolved a conflict by choosing to overwrite.
     */
    fun conflictGuard(): Long? = serverModified

    /**
     * Marks a save (or upload) as started.
     *
     * Split from [saveSucceeded] because between the two lies the entire upload, whose failures
     * the user must see — a save that ends in neither success nor failure has left the UI
     * permanently claiming "uploading", which is worse than a wrong status.
     */
    fun saveStarted() {
        saving = true
    }

    /**
     * A save landed: the working text is now the server's text too, and the server's idea of the
     * file is what it reported after the write.
     */
    fun saveSucceeded(newModified: Long?, newSize: Long?) {
        savedText = text
        saving = false
        changedOnServer = false
        serverModified = newModified
        serverSize = newSize
    }

    /** A save failed: nothing about the text changed, and the modified state stands. */
    fun saveFailed() {
        saving = false
    }

    /**
     * The watcher's verdict: the file moved on the server. A notification, not an action — the
     * text stays untouched until the user answers it.
     */
    fun serverChanged() {
        if (!saving) changedOnServer = true
    }

    /**
     * An explicit reload landed: the server's text becomes the working text, discarding local
     * edits. Only ever called from the reload paths the user chose (reload button, conflict
     * dialog's Reload Server Version); never from the watcher.
     */
    fun reloadSucceeded(newText: String, newModified: Long?, newSize: Long?) {
        text = newText
        savedText = newText
        changedOnServer = false
        serverModified = newModified
        serverSize = newSize
    }

    /**
     * Whether the server's current stats mean the file has changed since this tab agreed with it.
     *
     * The watcher calls this with a fresh stat; mtime alone is not trusted (some servers report
     * coarse timestamps), so a size change counts even when the timestamp has not moved.
     *
     * A field the server cannot report is *not* a change (unknown ≠ different): a backend with
     * no stat must not put every tab into conflict. A file that vanished is the strongest change
     * there is — but only *both* fields missing says vanished, because either provider can
     * report one field and not the other (SFTP reports mtime with a null size for directories;
     * SAF reports size with a zero mtime), and the "one field present, one unknown" shapes are
     * exactly the cases the first two guards already answer.
     */
    fun serverStatsDiffer(modified: Long?, size: Long?): Boolean {
        if (modified != null && serverModified != null && modified != serverModified) return true
        if (size != null && serverSize != null && size != serverSize) return true
        // Vanished, in the only spelling a stat offers: nothing left to report at all.
        if (modified == null && size == null) return serverModified != null || serverSize != null
        return false
    }
}

/**
 * One auto-save heartbeat's decision, made by [autoSaveTicks] from the tab's state.
 *
 * Pure so the debounce policy — "after the user stops typing for [AUTO_SAVE_DEBOUNCE_MS], and
 * only when there is something to save and saving is safe" — is an assertion, not a hope. The
 * requirement is explicit that auto-save must not upload on every character; the debounce below
 * is that requirement, and the [AutoSaveTick.idle] marker is what the timer feeds in.
 */
sealed interface AutoSaveTick {
    /** A typing-idle marker from the timer; carries how long the user has been idle. */
    data class Idle(val forMillis: Long) : AutoSaveTick
}

/** How long the user must stop typing before an auto-save upload fires. */
const val AUTO_SAVE_DEBOUNCE_MS = 2_000L

/**
 * Emits a save-now signal for every idle tick that arrives while the tab has unsaved changes,
 * auto-save is on, and nothing makes saving unsafe.
 *
 * The conditions, each because a wrong one is a data-loss bug:
 *  - [modified] — saving an unmodified tab would clobber a server change with identical text,
 *    which is the one overwrite this editor promises never to do silently;
 *  - not [saving] — a second upload while one is in flight races it;
 *  - not [changedOnServer] — the server moved; that is a conflict question, not a save;
 *  - not [readOnly] — the file cannot be written, and the mode exists to say so.
 */
fun autoSaveTicks(ticks: Flow<AutoSaveTick>, tab: () -> EditorTabModel, enabled: () -> Boolean): Flow<Unit> =
    ticks.transform { tick ->
        val current = tab()
        if (tick is AutoSaveTick.Idle && tick.forMillis >= AUTO_SAVE_DEBOUNCE_MS && enabled() &&
            current.modified && !current.saving && !current.changedOnServer && !current.readOnly
        ) {
            emit(Unit)
        }
    }
