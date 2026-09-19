package dev.eclipse.ssh.ui.actions

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
 */
sealed interface ActionAnswer {

    /** One row of the transfer actions window, named rather than expressed as a lambda. */
    data class TransferAction(val transferId: String, val action: TransferActionKind) : ActionAnswer
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
