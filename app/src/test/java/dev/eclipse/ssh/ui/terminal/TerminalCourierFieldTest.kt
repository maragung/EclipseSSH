package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the courier field does with the edits a keyboard hands it, without a keyboard.
 *
 * The field used to reset to the sentinel inside `onValueChange` on every keystroke, and the two
 * field reports that came of it are the reason this suite exists: symbols sometimes doubled (`/`
 * arriving as `//`), and the last character before Enter sometimes vanished (`help` running as
 * `hel`). Both are races between the reset and the IME, and a race is exactly what a pure function
 * makes testable: every interleaving that matters is a pair of strings in a test, rather than a
 * matter of which keyboard the user installed and how fast their thumbs were.
 *
 * The invariants under test:
 *  - one committed character reaches the shell exactly once, whatever the field held before it;
 *  - a commit that races the previous one is diffed against the content the platform actually held;
 *  - a deletion reaches the shell as Backspaces, in proportion to what was deleted;
 *  - the field resets only at moments where the IME is between words - never mid-keystroke - and
 *    never runs empty of the sentinel Backspace needs.
 */
class TerminalCourierFieldTest {

    private fun sentinel(tail: String = "") = SENTINEL_TEST + tail

    // The sentinel is private to the file under test; this mirrors it by its definition - eight
    // U+200B - and every test below depends on that being what the field actually pads with.
    private val SENTINEL_TEST = Char(0x200B).toString().repeat(8)

    @Test
    fun `an ordinary keystroke is one insertion`() {
        val edit = diffTerminalEdit(sentinel(), sentinel("h"))
        assertThat(edit.deleted).isEqualTo(0)
        assertThat(edit.inserted).isEqualTo("h")
    }

    @Test
    fun `a second keystroke is diffed against the field that holds the first`() {
        // The doubled-symbol bug: the platform holds "…/" when "/" arrives. If the diff base were
        // the stale sentinel - what a reset wrote but had not pushed - the common prefix would be
        // the sentinel alone and the insertion would be "//", re-sending the first slash with the
        // second. Against the real base, the insertion is the one new slash.
        val edit = diffTerminalEdit(sentinel("/"), sentinel("//"))
        assertThat(edit.deleted).isEqualTo(0)
        assertThat(edit.inserted).isEqualTo("/")
    }

    @Test
    fun `a keystroke racing the previous line's reset is not doubled`() {
        // Enter reset the field; the next keystroke arrives computed against whatever the platform
        // held. A reset pending from a pre-reset tail still carries that tail, so the base is the
        // pre-reset content and the new keystroke diffs against it exactly once.
        val preReset = sentinel("help")
        val base = pendingResetBase(preReset, sentinel("hel"))
        assertThat(base).isEqualTo(preReset)
        val edit = diffTerminalEdit(base, sentinel("hel"))
        assertThat(edit.deleted).isEqualTo(1)
        assertThat(edit.inserted).isEmpty()
    }

    @Test
    fun `an edit after the reset landed uses the fresh sentinel as its base`() {
        // The reset did reach the platform, so the incoming text no longer carries the pre-reset
        // tail; the base is the incoming text itself and the diff is the plain insertion.
        val base = pendingResetBase(sentinel("help"), sentinel("n"))
        assertThat(base).isEqualTo(sentinel("n"))
        val edit = diffTerminalEdit(base, sentinel("n"))
        assertThat(edit.deleted).isEqualTo(0)
        assertThat(edit.inserted).isEqualTo("n")
    }

    @Test
    fun `a pending reset with an empty tail treats the incoming text as the base`() {
        // The common case: the reset was from a bare sentinel, so there is no tail to match and the
        // only honest base is what arrived.
        val base = pendingResetBase(SENTINEL_TEST, sentinel("x"))
        assertThat(base).isEqualTo(sentinel("x"))
    }

    @Test
    fun `deleting a character the user typed is one Backspace`() {
        val edit = diffTerminalEdit(sentinel("hel"), sentinel("he"))
        assertThat(edit.deleted).isEqualTo(1)
        assertThat(edit.inserted).isEmpty()
    }

    @Test
    fun `deleting into the sentinel is still Backspaces, not zero`() {
        // The user held Backspace through everything they typed and into the padding. The shell
    // needs one Backspace per deleted character; clamping at the sentinel would swallow the last
    // few presses of a deliberate long press.
        val edit = diffTerminalEdit(sentinel("a"), SENTINEL_TEST.dropLast(1))
        assertThat(edit.deleted).isEqualTo(2)
    }

    @Test
    fun `replacing the tail sends the deletions before the insertion`() {
        // Autocorrect rewriting the end of a word: the shell has to eat the old characters before
        // the new ones are appended, or the correction lands on top of what it was meant to replace.
        val edit = diffTerminalEdit(sentinel("teh"), sentinel("the"))
        assertThat(edit.deleted).isEqualTo(2)
        assertThat(edit.inserted).isEqualTo("he")
    }

    @Test
    fun `the field adopts an ordinary edit as its own value`() {
        // The crux of the fix: adopting means no programmatic edit between keystrokes, so there is
        // nothing for the IME to trip over mid-composition.
        val change = TextFieldValue(sentinel("hel"), TextRange(sentinel("hel").length))
        val next = nextFieldValue(change)
        assertThat(next).isEqualTo(change)
    }

    @Test
    fun `a newline in the committed text resets the tail`() {
        // The line is finished; the next keystroke belongs to a new prompt. Keeping the tail would
        // make the first Backspace of the next line delete the end of the previous one.
        val change = TextFieldValue(sentinel("help\n"), TextRange(sentinel("help\n").length))
        val next = nextFieldValue(change)
        assertThat(next.text).isEqualTo(SENTINEL_TEST)
    }

    @Test
    fun `the hard cap keeps the last of a very long tail and the sentinel`() {
        val longTail = "x".repeat(100)
        val change = TextFieldValue(sentinel(longTail), TextRange(sentinel(longTail).length))
        val next = nextFieldValue(change)
        assertThat(next.text).isEqualTo(SENTINEL_TEST + "x".repeat(56))
        assertThat(next.selection.start).isEqualTo(next.text.length)
    }

    @Test
    fun `a change with no sentinel is re-padded, not adopted`() {
        // However the platform lost the padding, the field's own invariant is that Backspace always
        // has something to consume - so the next value restores it before adopting anything.
        val change = TextFieldValue("help", TextRange(4))
        val next = nextFieldValue(change)
        assertThat(next.text).isEqualTo(SENTINEL_TEST + "help")
    }
}
