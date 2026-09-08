package dev.eclipse.ssh.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A copied secret leaves the clipboard even if the process that copied it does not survive.
 *
 * The bug these tests pin down: the delayed wipe lived only in a [android.os.Handler], so it existed
 * only for as long as the process did. Copy a password out of the vault, swipe the app away, and the
 * runnable dies with the process while the password stays on the system clipboard - readable by the
 * next app the user pastes into. The deadline is now written down, and the next resume finishes it.
 *
 * "A new process" is modelled by a second [SecureClipboard] over the same context: a fresh instance
 * has no queued runnable and nothing in memory, which is exactly the state after a restart. The
 * looper stays paused unless a test idles it, so the first instance's runnable cannot interfere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureClipboardTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)!!

    private fun guard() = context.getSharedPreferences("clipboard_guard", Context.MODE_PRIVATE)
    private fun recordedDeadline(): Long = guard().getLong("clear_at", 0L)
    private fun setDeadline(atMillis: Long) = guard().edit().putLong("clear_at", atMillis).commit()

    /** What is on the clipboard, treating "empty clip" and "no clip" alike - both mean wiped. */
    private fun clipboardText(): String? = clipboard.primaryClip
        ?.let { clip ->
            (0 until clip.itemCount).mapNotNull { runCatching { clip.getItemAt(it).text?.toString() }.getOrNull() }
                .joinToString(separator = "")
        }
        ?.ifEmpty { null }

    private fun copyFromAnotherApp(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Shopping list", text))
    }

    @Test
    fun `a copied secret is on the clipboard, marked sensitive, with a deadline recorded`() {
        val before = System.currentTimeMillis()
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = 30)

        assertThat(clipboardText()).isEqualTo("hunter2")
        // Keeps the value out of the system paste-preview toast on Android 13+.
        assertThat(clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
            .isTrue()
        assertThat(recordedDeadline()).isAtLeast(before + 30_000)
    }

    @Test
    fun `the countdown wipes the secret when it fires`() {
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = 30)

        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)

        assertThat(clipboardText()).isNull()
        assertWithMessage("a fired wipe must not leave a deadline behind").that(recordedDeadline())
            .isEqualTo(0L)
    }

    @Test
    fun `a wipe interrupted by the end of the process is finished on the next resume`() {
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = 30)
        // The process died before the runnable could fire, and enough time passed that it was due.
        setDeadline(System.currentTimeMillis() - 1_000)

        SecureClipboard(context).resumePendingClear()

        assertThat(clipboardText()).isNull()
        assertThat(recordedDeadline()).isEqualTo(0L)
    }

    @Test
    fun `a deadline still in the future is re-armed for what is left of it`() {
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = 300)
        setDeadline(System.currentTimeMillis() + 20_000)

        SecureClipboard(context).resumePendingClear()

        // Still there: the copy is meant to last, and cutting it short would be a bug of its own.
        assertThat(clipboardText()).isEqualTo("hunter2")

        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertThat(clipboardText()).isNull()
    }

    @Test
    fun `a clip the user replaced in the meantime is left alone`() {
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = 30)
        copyFromAnotherApp("milk, bread")
        setDeadline(System.currentTimeMillis() - 1_000)

        SecureClipboard(context).resumePendingClear()

        assertWithMessage("wiped something the user copied").that(clipboardText()).isEqualTo("milk, bread")
        assertWithMessage("a stale deadline must not survive to wipe a later clip")
            .that(recordedDeadline()).isEqualTo(0L)
    }

    @Test
    fun `a deadline left behind by a moved clock wipes immediately`() {
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = 600)
        // Further away than any delay the app can configure: the wall clock moved under us, from a
        // manual change or a restore onto another device, so the stored instant means nothing. For a
        // secret, wiping early is the harmless direction.
        setDeadline(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10))

        SecureClipboard(context).resumePendingClear()

        assertThat(clipboardText()).isNull()
        assertThat(recordedDeadline()).isEqualTo(0L)
    }

    @Test
    fun `no recorded deadline means the clipboard is not touched`() {
        copyFromAnotherApp("milk, bread")

        SecureClipboard(context).resumePendingClear()

        assertThat(clipboardText()).isEqualTo("milk, bread")
    }

    @Test
    fun `keeping a copy until it is replaced leaves no deadline behind`() {
        val clipboardHelper = SecureClipboard(context)
        clipboardHelper.copy("hunter2", clearAfterSeconds = 30)
        // "Keep until I replace it" - the previous copy's deadline must not wipe this one.
        clipboardHelper.copy("token-abc", clearAfterSeconds = 0)

        assertThat(recordedDeadline()).isEqualTo(0L)

        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertThat(clipboardText()).isEqualTo("token-abc")
    }

    @Test
    fun `an absurd delay is capped rather than trusted`() {
        SecureClipboard(context).copy("hunter2", clearAfterSeconds = Int.MAX_VALUE)
        // Read after the call, not before it: the deadline is measured from the moment the copy
        // happened, and the clock moves between the two statements. Comparing against a reading taken
        // beforehand fails by a millisecond and says nothing about the cap.
        val ceiling = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(600)

        assertThat(recordedDeadline()).isAtMost(ceiling)
        // Without the cap this would be twenty-four million days away, so the lower bound is what
        // shows the value is a real deadline rather than zero.
        assertThat(recordedDeadline()).isGreaterThan(System.currentTimeMillis())
    }

    @Test
    fun `paste returns null rather than an empty string when there is nothing to paste`() {
        assertThat(SecureClipboard(context).paste()).isNull()
    }

    @Test
    fun `paste returns what was copied`() {
        val clipboardHelper = SecureClipboard(context)
        clipboardHelper.copy("hunter2", clearAfterSeconds = 30)

        assertThat(clipboardHelper.paste()).isEqualTo("hunter2")
    }

    // -----------------------------------------------------------------------------------------
    // normalizePastedSecret: the credential-field half of the paste path
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the newline a password manager appends is stripped from a pasted secret`() {
        assertThat(normalizePastedSecret("hunter2\r\n")).isEqualTo("hunter2")
        assertThat(normalizePastedSecret("\nhunter2")).isEqualTo("hunter2")
        assertThat(normalizePastedSecret("line one\r\nline two")).isEqualTo("line oneline two")
    }

    @Test
    fun `spaces and tabs in a pasted secret are kept exactly as they are`() {
        // Credentials are stored exactly as entered - the same policy ProxyConfigTest pins for
        // proxy credentials - and a password with a leading or trailing space is a real password
        // that a trim would silently corrupt into a wrong one.
        assertThat(normalizePastedSecret(" hunter2 ")).isEqualTo(" hunter2 ")
        assertThat(normalizePastedSecret("\thunter2\t")).isEqualTo("\thunter2\t")
        assertThat(normalizePastedSecret("pass word\twith\ttabs")).isEqualTo("pass word\twith\ttabs")
    }

    @Test
    fun `a clip that was only newlines normalizes to nothing`() {
        assertThat(normalizePastedSecret("\r\n\n")).isEmpty()
    }
}
