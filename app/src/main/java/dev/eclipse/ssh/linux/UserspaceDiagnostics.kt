package dev.eclipse.ssh.linux

import android.util.Log
import dev.eclipse.ssh.ssh.scrub
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The lifecycle of a userspace install or repair, recorded as structured events that carry no
 * secrets — the Linux-runtime answer to [SessionDiagnostics], which until now was SSH-only and
 * left an install with nothing to send but the newest progress line (audit F11).
 *
 * Two sinks, deliberately, both copied from the session trace's design:
 *
 *  - [Log] with the single stable tag, so `adb logcat -s EclipseSSH` is a complete trace;
 *  - an in-memory ring the app can export, because a user whose install failed has no `adb`.
 *
 * The export runs through [LinuxUserspaceController.installLog] and the Settings → Linux Userspace
 * "Install log" row, which offers the ring to Copy, Save and Clear — the same three actions the
 * session trace's own dialog offers, for the same reason: the person who needs the evidence is the
 * one whose install just failed, and that person has no `adb`.
 *
 * ## What must never appear here
 * The same rules the session trace holds: passwords, keys, tokens, terminal contents. Every
 * `detail` passes through the SSH trace's [scrub] before it enters the ring, because the one
 * rich source of detail here — a failed command's captured output — is exactly the shape of text
 * that can carry something it should not. It is then stripped of the terminal escapes that output
 * carries by construction ([stripEscapes]): the commands run on a pty, so their colour and
 * progress sequences would otherwise surround every error line quoted here.
 */
class UserspaceDiagnostics {

    private val entries = ArrayDeque<UserspaceDiagnosticEvent>()
    private var sequence = 0L

    private val _events = MutableStateFlow<List<UserspaceDiagnosticEvent>>(emptyList())

    /** The ring, newest last, for the diagnostics export. */
    val events: StateFlow<List<UserspaceDiagnosticEvent>> = _events

    /**
     * Records one event. Cheap and non-suspending on purpose — a diagnostic that could be dropped
     * by cancellation would be missing exactly when an install was torn down, which is when it
     * matters most.
     */
    fun record(
        category: UserspaceDiagnosticCategory,
        event: String,
        detail: String? = null,
        exitCode: Int? = null,
        durationMs: Long? = null,
    ) {
        val entry: UserspaceDiagnosticEvent
        synchronized(entries) {
            sequence += 1
            entry = UserspaceDiagnosticEvent(
                sequence = sequence,
                atMs = System.currentTimeMillis(),
                category = category,
                event = event,
                detail = detail?.let { stripEscapes(scrub(it)).take(MAX_DETAIL) },
                exitCode = exitCode,
                durationMs = durationMs,
            )
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            _events.value = entries.toList()
        }
        Log.i(TAG, entry.line())
    }

    /** The whole ring as text, for Save logs and a bug report. */
    fun export(): String = synchronized(entries) { entries.joinToString("\n") { it.line() } }

    /** Empties the ring — a user clearing the log to capture one clean reproduction. */
    fun clear() {
        synchronized(entries) {
            entries.clear()
            _events.value = emptyList()
        }
    }

    companion object {
        /** The same single tag the session trace uses, so one logcat filter covers both. */
        const val TAG = "EclipseSSH"
        const val MAX_ENTRIES = 500
        const val MAX_DETAIL = 200
    }
}

/**
 * The terminal control sequences a command's output carries when it ran on a pty, removed.
 *
 * dpkg and apt colour and re-draw their progress lines whenever their output is a terminal, and
 * under proot it always is — so the one line of a failure worth reading, taken verbatim from the
 * command's output, arrives as `ESC[1mdpkg:ESC[0m ESC[1;31merror:ESC[0m error creating new backup
 * file …`. The escapes are also what [UserspaceDiagnosticEvent.line] would quote into the export
 * and into logcat, where they read as noise around the sentence that names the cause.
 *
 * Only presentation is removed — colour, cursor movement, erase, charset: anything else in the text
 * is evidence. The shapes a pty actually emits, and the reason the list is longer than "CSI":
 *
 *  - **CSI** — `ESC [ params intermediates final`. SGR colour, the erase family, addressed cursor
 *    moves.
 *  - **SS3** — `ESC O final`. What a terminal in DECCKM mode sends for an arrow key, and it is three
 *    bytes, not two: stopping at the introducer would leave the final byte behind as a letter.
 *  - **Two-character escapes** — `ESC` and one byte, in both the private range (`ESC 7`, `ESC 8`,
 *    `ESC =`, `ESC >`) and the Fe/Fs range (`ESC M`, `ESC D`, `ESC c`). This is the family a
 *    CSI-only pattern misses, and the miss is visible rather than theoretical: `ESC 7` / `ESC 8` are
 *    DECSC/DECRC — save and restore the cursor — which is exactly what dpkg wraps each progress-bar
 *    redraw in, so their final byte survives as a literal digit glued to the front of the very line
 *    this function exists to make readable.
 *  - **Charset designations** — `ESC ( B` and its `)`, `#` and `%` siblings, whose third byte is a
 *    designation rather than a letter.
 *
 * The two single-byte classes are written as ranges with `[` and `O` carved out, because those two
 * introducers are the two shapes above and must not be consumed a byte at a time.
 */
internal fun stripEscapes(text: String): String = ANSI_ESCAPES.replace(text, "")

/**
 * The one line of a command's captured output that answers the question it was asked.
 *
 * A command's output is one stream: stdout and stderr interleaved on a pty, in the order they
 * arrived (see `ProotCommandResult.outputText`). So a field asking "what did `whoami` print" cannot
 * be the whole capture, because proot writes its own notes to that same stream — and it writes them
 * at *teardown*, after the command has already answered. From a device, verbatim:
 *
 * ```
 * root
 * proot warning: cant chmod 'bash': Permission denied
 * proot warning: cant chmod 'run-parts': Permission denied
 * proot warning: cant chmod 'locale-check': Permission denied
 * proot warning: cant chmod 'whoami': Permission denied
 * proot error: cant remove '.../tmp/exec-8254-bervFx': Directory not empty
 * ```
 *
 * Read whole, that is the session's "account name"; `accountCorrect` compares it against `root`,
 * finds it unequal, and the health check reports a correctly-root session as not root — and withholds
 * the host card over it. The answer is the last line left after [stripEscapes] and [PROOT_ERROR] have
 * had their turn: the escapes because a pty always carries them, proot's own lines because they are
 * written last and must not be mistaken for the answer. Last rather than first, because the noise
 * that *precedes* an answer is the ordinary kind — a login profile's banner, a wrapper's notice —
 * and `bash --login` is how every one of these commands runs.
 *
 * Null when nothing survives: an empty capture, or one that was nothing but proot's notes. A caller
 * reading a value it must judge gets "no answer" rather than a line of proot's prose.
 */
internal fun String.answerLine(): String? =
    lineSequence()
        .map { stripEscapes(it).trim() }
        .filter { it.isNotEmpty() && !PROOT_ERROR.containsMatchIn(it) }
        .lastOrNull()

private val ANSI_ESCAPES = Regex(
    "\\u001B\\[[0-9;?]*[ -/]*[@-~]" + // CSI
        "|\\u001BO[@-~]" + // SS3
        "|\\u001B[()#%][0-9A-Za-z]" + // charset designation
        "|\\u001B[0-9:<=>?]" + // two-character escapes, private range: ESC 7 / ESC 8 / ESC =
        "|\\u001B[@-NQ-Z\\\\^_a-np-z]", // two-character escapes, Fe/Fs range; [ is CSI, O is SS3
)

/**
 * The category prefix every line carries: the subsystems of an install — storage, the rootfs
 * download and extraction, proot itself, DNS, and apt — so a trace can be `grep`ed by subsystem
 * rather than read whole. Every one of the six is recorded by something: [RootfsInstaller] owns
 * storage, download and rootfs, [UbuntuDistributionManager] the rest.
 */
enum class UserspaceDiagnosticCategory(val tag: String) {
    STORAGE("storage"),
    ROOTFS("rootfs"),
    DOWNLOAD("download"),
    PROOT("proot"),
    DNS("dns"),
    APT("apt"),
}

/** One recorded moment in an install's or repair's life. */
data class UserspaceDiagnosticEvent(
    val sequence: Long,
    val atMs: Long,
    val category: UserspaceDiagnosticCategory,
    /** What happened, in the subsystem's own vocabulary: "rung failed", "resolv.conf written". */
    val event: String,
    /** The one line of evidence — an Err: line, a mirror base — never a transcript. */
    val detail: String? = null,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
) {
    /**
     * The event as one line of `key=value` fields, shaped like the session trace's lines: absent
     * fields left out entirely, durations in seconds, detail quoted.
     */
    fun line(): String = buildString {
        append(atMs)
        append(" [")
        append(category.tag)
        append("] ")
        append(event)
        exitCode?.let { append(" exit=").append(it) }
        durationMs?.let { append(" dur=").append(it / 1_000).append('s') }
        detail?.let { append(" detail=\"").append(it.replace('"', '\'')).append('"') }
    }
}
