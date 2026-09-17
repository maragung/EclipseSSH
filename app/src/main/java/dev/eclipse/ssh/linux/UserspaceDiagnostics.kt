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
 * Only SGR ("colour") sequences and the cursor/erase families a progress line uses are removed:
 * this is presentation, and anything else in the text is evidence.
 */
internal fun stripEscapes(text: String): String = ANSI_ESCAPES.replace(text, "")

private val ANSI_ESCAPES = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

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
