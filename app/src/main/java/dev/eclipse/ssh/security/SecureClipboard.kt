package dev.eclipse.ssh.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies short-lived secrets (passwords, key fingerprints, terminal selections) to the
 * system clipboard and wipes them again after the configured delay.
 */
@Singleton
class SecureClipboard @Inject constructor(@ApplicationContext private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * When the copied value is due to be wiped, as a wall-clock instant.
     *
     * The delayed wipe used to live only in [handler], which meant it existed only for as long as
     * the process did. A password copied out of the vault and then a swipe away from the app — the
     * app is stopped, the process is reclaimed a moment later, the [Runnable] dies with it, and the
     * password stays on the system clipboard indefinitely, readable by whatever the user pastes into
     * next. The deadline is therefore written down, and [resumePendingClear] finishes the job in the
     * next process.
     *
     * Only the *deadline* is stored, never the copied text: this file exists to get a secret off the
     * device, so writing one into a preferences file to make the bookkeeping easier would defeat it.
     * The clip's label is enough to recognise our own clip after a restart.
     */
    private val guard: SharedPreferences by lazy {
        context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
    }
    private var pendingClear: Runnable? = null

    fun copy(text: String, clearAfterSeconds: Int) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        // Keeps the pasted value out of the system paste-preview toast on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        runCatching { clipboard.setPrimaryClip(clip) }

        pendingClear?.let(handler::removeCallbacks)
        pendingClear = null
        if (clearAfterSeconds <= 0) {
            // "Keep until I replace it" is a deliberate choice, so no deadline must be left behind
            // from a previous copy to wipe this one out from under the user.
            forgetDeadline()
            return
        }
        val delayMs = clearAfterSeconds.coerceIn(1, MAX_DELAY_SECONDS) * 1_000L
        rememberDeadline(System.currentTimeMillis() + delayMs)
        val task = Runnable { clearIfStillOurs(clipboard, text) }
        pendingClear = task
        handler.postDelayed(task, delayMs)
    }

    /**
     * Finishes a wipe that the end of the previous process interrupted.
     *
     * Called from the activity's `onResume`, which is the earliest moment this can work at all:
     * Android 10 and later refuse clipboard reads, and refuse [ClipboardManager.setPrimaryClip], to
     * an app that does not have window focus. A missed deadline is honoured immediately; a deadline
     * still in the future is re-armed for whatever is left of it, so a copy made seconds before the
     * process died still gets its full configured lifetime rather than being cut short.
     *
     * A remaining time longer than the largest delay the app can configure means the wall clock has
     * moved (a manual change, or a restore onto another device) and the stored instant no longer
     * means anything. That wipes now: for a secret, clearing early is the harmless direction.
     */
    fun resumePendingClear() {
        // A countdown already running in this process is the authority; it knows the copied text and
        // can therefore recognise the clip even when the label has been replaced.
        if (pendingClear != null) return
        val deadline = guard.getLong(KEY_DEADLINE, 0L)
        if (deadline <= 0L) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L || remaining > MAX_DELAY_SECONDS * 1_000L) {
            clearIfStillOurs(clipboard, expected = null)
            return
        }
        val task = Runnable { clearIfStillOurs(clipboard, expected = null) }
        pendingClear = task
        handler.postDelayed(task, remaining)
    }

    /**
     * The clipboard's current text, or null when there is nothing readable on it.
     *
     * Reading is a privileged operation on Android 10 and later: the platform returns null to any app
     * that is not the foreground window, and logs a warning when one tries. That is a feature, not an
     * obstacle - this is only ever called from a paste the user just tapped, which by definition
     * happens in the foreground - but it does mean the null case is normal and must not be treated as
     * an error. Nothing is cached: holding a copy of whatever the user last copied, in a process that
     * keeps SSH credentials in memory, would be a liability for no benefit.
     */
    fun paste(): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        val text = (0 until clip.itemCount)
            .asSequence()
            .mapNotNull { index -> runCatching { clip.getItemAt(index).coerceToText(context) }.getOrNull() }
            .joinToString(separator = "") { it }
        return text.ifEmpty { null }
    }

    /**
     * Clears the clipboard unless the user has since copied something else.
     *
     * The previous implementation compared the [ClipData] instance returned by
     * [ClipboardManager.getPrimaryClip] with the one that was set. `ClipData` does not
     * override `equals` and the getter returns a freshly deserialized object, so that
     * check was never true and the clipboard was never cleared. Matching on the clip
     * label (which does survive the binder round trip) works from any process state;
     * reading the item text is only used as a secondary confirmation because Android 10+
     * denies clipboard reads to apps that are not in the foreground.
     *
     * [expected] is null when the wipe is being finished by a later process, which has the label but
     * not the text. That is the whole reason the label check comes first and stands on its own.
     */
    private fun clearIfStillOurs(clipboard: ClipboardManager, expected: String?) {
        pendingClear = null
        forgetDeadline()
        val current = runCatching { clipboard.primaryClip }.getOrNull()
        val ours = when {
            current == null -> false
            current.description?.label == LABEL -> true
            expected == null -> false
            else -> (0 until current.itemCount).any {
                runCatching { current.getItemAt(it).text?.toString() }.getOrNull() == expected
            }
        }
        if (!ours) return
        runCatching { clipboard.clearPrimaryClip() }
            .onFailure { runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) } }
    }

    /**
     * `commit = true`, not `apply()`: the point of the deadline is to survive a process that is about
     * to be killed, and `apply()`'s write is asynchronous with no guarantee it happens first. One long
     * value in its own file, written on a user gesture rather than in a loop.
     */
    private fun rememberDeadline(atMillis: Long) {
        runCatching { guard.edit(commit = true) { putLong(KEY_DEADLINE, atMillis) } }
    }

    private fun forgetDeadline() {
        runCatching { guard.edit(commit = true) { remove(KEY_DEADLINE) } }
    }

    private companion object {
        const val LABEL = "Eclipse SSH"
        const val MAX_DELAY_SECONDS = 600
        const val GUARD_PREFS = "clipboard_guard"
        const val KEY_DEADLINE = "clear_at"
    }
}
