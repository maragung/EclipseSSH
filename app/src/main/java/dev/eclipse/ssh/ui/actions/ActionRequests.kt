package dev.eclipse.ssh.ui.actions

import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.model.SessionTab
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * What an action window was opened on, when the thing it needs cannot travel in an intent.
 *
 * This app has no `Parcelable` model - not one - so a window that wants to show a transfer, a
 * session tab, a host or a file entry cannot be handed one through `putExtra`. The two shapes that
 * exist for that problem are both already in use: pass an *id* and let the window read the singleton
 * behind it (the file paths in [dev.eclipse.ssh.ui.preview.FilePreviewRequest] are the exception that
 * proves it, and it is why they need a registry at all), or pass a token and leave the object here.
 *
 * The rule for choosing between them is whether a singleton already holds the subject. A transfer
 * does ([dev.eclipse.ssh.data.TransferRepository] is a Room-backed singleton with a `Flow`), so
 * `TransferActionsActivity` takes a plain id and watches the item live - a download in progress keeps
 * counting up while its actions are on screen. A session tab does not: [SessionTab] is assembled by
 * the workspace's own view model, which a second window cannot reach, so the tab is snapshotted into
 * a subject and read back by token.
 */
sealed interface ActionSubject {

    /**
     * The session tab whose "why?" window is open.
     *
     * A snapshot, and deliberately only of the *header*: the tab supplies the title, the state the
     * heading is worded from, and the last recorded reason. The trace below it is read live from
     * [dev.eclipse.ssh.ssh.SessionDiagnostics] rather than carried here, because the trace is the
     * half that changes while the window is open - a reconnect ladder still climbing is exactly when
     * somebody is looking at it - and a frozen trace would be worse than no window at all.
     */
    data class SessionWhy(val tab: SessionTab) : ActionSubject

    /**
     * The explorer row whose action window is open.
     *
     * A token for the entry and nothing else, because nothing else about it is unparcelable. An
     * [dev.eclipse.ssh.data.fs.FsEntry] is a row of a listing held by the explorer's controller,
     * which the window cannot reach; `isLocal`, `supportsPermissions` and `canOpenArchive` are
     * booleans, so they ride the intent as extras and are not repeated here. That split is the rule
     * stated above, applied literally: a subject carries exactly what an intent cannot.
     */
    data class FileActions(val entry: FsEntry) : ActionSubject

    /**
     * The archive row whose action window is open.
     *
     * The same shape as [FileActions] and for the same reason - an [ArchiveEntry] is a row of a tree
     * the archive browser owns - with `canReadEntry`, a boolean, travelling in the intent.
     */
    data class ArchiveEntryActions(val entry: ArchiveEntry) : ActionSubject
}

/**
 * What the user chose in one of those windows, waiting for the workspace to act on it.
 *
 * Every transfer action is here rather than performed by the window itself, including the ones that
 * look self-contained - View file, Open, Copy details. They are not: View and Edit are a
 * [dev.eclipse.ssh.data.fs.FileSystemProvider] resolved from the transfer, Open shells out to the
 * platform's chooser, and Copy details writes to the vault-backed clipboard. All three are the
 * workspace's own helpers, and the four remaining ones are view-model calls. Re-implementing them in
 * a second window would be two implementations of the same act, which is how the two drift.
 *
 * The test is not "is it an action" but "can the window reach it". Where a window already injects the
 * singleton a row would write to, that row is performed there and is not an answer at all - deleting a
 * snippet is the one case so far, and the copy in the session window's header is the same shape. What
 * is left, which is everything here, needs the workspace's own composition: the row it was opened on,
 * a provider resolved from the session on screen, or a dialog only that screen draws.
 */
sealed interface ActionAnswer {

    /** One row of the transfer actions window, named rather than expressed as a lambda. */
    data class TransferAction(val transferId: String, val action: TransferActionKind) : ActionAnswer

    /**
     * One row of the snippets window.
     *
     * [snippetId] is null for [SnippetActionKind.SAVE_CURRENT], which is the one row of that window
     * that is not a verb on a snippet: it names the command the user has typed into the terminal's
     * command bar, which no snippet id could stand for.
     */
    data class SnippetAction(val snippetId: String?, val action: SnippetActionKind) : ActionAnswer

    /**
     * One row of the explorer's action window.
     *
     * No entry and no path: the answer names a verb, and the surface that opened the window holds the
     * entry it opened it on. A window cannot be opened on one row and answer for another, because the
     * workspace has to come back to the foreground for a second window to be opened at all.
     */
    data class FileAction(val action: FileActionKind) : ActionAnswer

    /** One row of the archive entry's action window. The same reasoning as [FileAction]. */
    data class ArchiveEntryAction(val action: ArchiveEntryActionKind) : ActionAnswer
}

/**
 * The rows of the transfer actions window.
 *
 * An enum and not one answer type per row, because the workspace's side of every one of them is a
 * single expression taking the id - so the drain is a `when` over this with one line per branch, and
 * a row added later cannot be handled by accident.
 */
enum class TransferActionKind {
    PAUSE,
    RESUME,
    CANCEL,
    RUN_NOW,
    VIEW_FILE,
    EDIT_FILE,
    OPEN_FILE,
    OPEN_FILE_WITH,
    COPY_DETAILS,
}

/**
 * The rows of the snippets window.
 *
 * Both end in the terminal, which is why both are answered rather than performed - see [ActionAnswer]
 * for the rule. [INSERT] is typed into the shell by the session that is on screen, and [SAVE_CURRENT]
 * asks the terminal to name the command the user typed, which is live text in the terminal's own
 * composition and not something a window can read.
 *
 * **Delete is deliberately not here.** The snippet store is a singleton the snippets window already
 * injects in order to draw its list, so a delete is a write that window can make itself - and making
 * it is what lets the row disappear under the finger that removed it. An answer would instead wait
 * for the workspace to come back to the front, so the row would sit there until the window closed.
 */
enum class SnippetActionKind {
    INSERT,
    SAVE_CURRENT,
}

/**
 * The rows of the explorer's per-entry action window, in the order the sheet drew them.
 *
 * [SELECT] is the one entry here that is not an operation on the file: long-press used to be the
 * explorer's selection gesture, long-press opens this window, and Select is where that gesture
 * lands now - which is why it sits above Preview rather than with the file verbs.
 */
enum class FileActionKind {
    SELECT,
    PREVIEW,
    OPEN_ARCHIVE,
    EDIT,
    RENAME,
    COPY,
    MOVE,
    TRANSFER,
    SEND_TO_HOST,
    CHMOD,
    PROPERTIES,
    DELETE,
}

/**
 * The rows of the archive entry's action window.
 *
 * [EXTRACT] is offered for a folder as "Extract folder" and for a readable file as "Download", which
 * is one verb under two names - a single entry's bytes are a range read while a subtree is a
 * scan-and-collect. That difference is in the row's label, drawn by the window; the answer is the
 * same verb either way, because it is the same picker and the same extract on the other side.
 */
enum class ArchiveEntryActionKind {
    PREVIEW,
    EXTRACT,
    COPY_PATH,
    PROPERTIES,
}

/**
 * The one-shot handoff between the workspace and the seven windows that used to be bottom sheets.
 *
 * Both directions of the same problem, in one place: the subject travels *in* under a token, and the
 * answer travels *out* through a single slot. They live together because they are one mechanism -
 * a window is opened with [put] and closed with [answer] - and because their other half is one
 * function: `MainActivity.onResume` reads the answer and is the only reader, so one exhaustive `when`
 * there is what guarantees no window can be added without the workspace learning to act on it.
 *
 * [take] and [takeAnswer] remove as they read, for the reason every other handoff in this app does:
 * an intent the system re-delivers - a rotation, the recents screen, a crash and relaunch - must find
 * nothing rather than re-open a window on a subject that is long gone, and an answer delivered twice
 * is the same action taken twice. A transfer that was paused once must not be paused again by a
 * re-resume.
 *
 * The token is a UUID so a hand-written intent cannot name somebody else's subject. Nothing sweeps
 * the map besides the takes, so a request stored and then never launched outlives its launch by one
 * small object per process death - the same trade [dev.eclipse.ssh.ui.preview.PreviewRequests] makes.
 */
object ActionRequests {

    /** The intent extra a window reads its subject token out of. */
    const val EXTRA_SUBJECT_TOKEN = "dev.eclipse.ssh.actions.SUBJECT_TOKEN"

    private val subjects = ConcurrentHashMap<String, ActionSubject>()
    private val answered = AtomicReference<ActionAnswer?>(null)

    /** Stores [subject] under a fresh token, which the caller puts in the intent. */
    fun put(subject: ActionSubject): String = UUID.randomUUID().toString().also { subjects[it] = subject }

    /** Reads and clears the subject the token names, or null when it was spent or never existed. */
    fun take(token: String?): ActionSubject? = token?.let(subjects::remove)

    /** Records the window's answer, replacing any earlier one the workspace never came back for. */
    fun answer(answer: ActionAnswer) {
        answered.set(answer)
    }

    /** Reads and clears the answer, or null when the window was closed without choosing anything. */
    fun takeAnswer(): ActionAnswer? = answered.getAndSet(null)
}
