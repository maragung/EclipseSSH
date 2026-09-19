package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The four bytes these tests are built from, written as escapes rather than pasted in.
 *
 * A raw control byte in a source file is invisible in a diff and stops some tools reading the
 * file at all - this repository has already lost a test count to a literal NUL in a string - so
 * none of these is ever typed literally.
 */
private const val E = "\u001B"
private const val BS = "\u0008"
private const val CR = "\u000D"
private const val HT = "\u0009"

/**
 * A cursor that moved has to be a buffer that changed.
 *
 * The renderer memoizes the layout it draws the cursor box from on [TerminalFrame.revision], so a
 * mutation that does not advance that value is a mutation the user cannot see. Every sequence below
 * is one way a cursor moves, and the assertion is the same for all of them: the frame's revision has
 * to be strictly greater afterwards than before.
 *
 * This is the file for the bug that made a left arrow look broken. readline's `cub1` is `^H` - a bare
 * control byte, not a CSI - while its `cuf1` is `ESC [ C`, so left was the only arrow that reached the
 * buffer through a path that set the column without reporting it. Right, up and down all go through
 * `executeCsi` or `index()`, which do report it, so they moved on screen and left did not, and the
 * cursor appeared to jump only once the next character was typed. `vim` and `less` repaint by address
 * instead, which is why the same key felt fine inside them.
 *
 * The table is deliberately wider than the one key that was reported: `\r` alone is a progress bar
 * redrawing itself, `\t` is a completion, `ESC 8` is a restored cursor, and each of them was silent in
 * exactly the same way.
 */
class TerminalCursorRepaintTest {

    private val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

    /** Feeds [sequence] to a prompt holding `abc` and asserts both the column and the revision. */
    private fun assertMoves(sequence: String, toColumn: Int) {
        buffer.feed("abc")
        val before = buffer.frame()
        assertThat(before.cursorColumn).isEqualTo(3)

        buffer.feed(sequence)
        val after = buffer.frame()

        assertThat(after.cursorColumn).isEqualTo(toColumn)
        assertThat(after.revision).isGreaterThan(before.revision)
    }

    @Test
    fun `a bare backspace reports the move, which is what a left arrow costs`() {
        assertMoves(BS, toColumn = 2)
    }

    @Test
    fun `three backspaces walk three columns left and report each one`() {
        buffer.feed("abcdef")
        var revision = buffer.frame().revision

        repeat(3) {
            buffer.feed(BS)
            val frame = buffer.frame()
            assertThat(frame.revision).isGreaterThan(revision)
            revision = frame.revision
        }

        assertThat(buffer.frame().cursorColumn).isEqualTo(3)
    }

    @Test
    fun `a backspace at the left margin reports the move it did not make`() {
        // It clamps rather than wrapping to the previous row, and the clamp is still a change the
        // caller may have to draw: readline erases a character by writing `\b \b`, so the frame after
        // the space and the frame after the second backspace both have to reach the screen.
        buffer.feed("x")
        buffer.feed(CR)
        val before = buffer.frame()

        buffer.feed(BS)

        assertThat(buffer.frame().cursorColumn).isEqualTo(0)
        assertThat(buffer.frame().revision).isGreaterThan(before.revision)
    }

    @Test
    fun `a carriage return reports the move, which is a progress bar redrawing in place`() {
        assertMoves(CR, toColumn = 0)
    }

    @Test
    fun `a tab reports the move`() {
        assertMoves(HT, toColumn = 8)
    }

    // --- The same moves as CSI, which already reported them ---

    @Test
    fun `cursor back reports the move`() {
        assertMoves("${E}[D", toColumn = 2)
    }

    @Test
    fun `cursor forward reports the move`() {
        assertMoves("${E}[C", toColumn = 4)
    }

    @Test
    fun `cursor back with a count reports the move`() {
        assertMoves("${E}[3D", toColumn = 0)
    }

    @Test
    fun `cursor to column reports the move`() {
        assertMoves("${E}[1G", toColumn = 0)
    }

    @Test
    fun `erase to the end of the line reports the change`() {
        // `ESC [ K` is how readline rubs out the tail of a line it is about to redraw shorter, and how
        // `htop` clears a row it is about to rewrite. Its own bump, independent of the CSI wrapper.
        buffer.feed("abcdef")
        val before = buffer.frame()

        buffer.feed("${E}[K")

        assertThat(buffer.frame().revision).isGreaterThan(before.revision)
    }

    // --- Saving and restoring, which moves the cursor and the style ---

    @Test
    fun `restoring a saved cursor reports the move`() {
        buffer.feed("abc")
        buffer.feed("${E}7")
        buffer.feed(CR)
        val before = buffer.frame()

        buffer.feed("${E}8")

        assertThat(buffer.frame().cursorColumn).isEqualTo(3)
        assertThat(buffer.frame().revision).isGreaterThan(before.revision)
    }

    @Test
    fun `restoring onto the same cell reports the style change alone`() {
        // No move at all: the program saved where it already was and restored the style it had. The
        // colour it draws in next is the only thing that changed, and it still has to be drawn.
        buffer.feed("abc")
        buffer.feed("${E}7")
        val before = buffer.frame()

        buffer.feed("${E}8")

        assertThat(buffer.frame().cursorColumn).isEqualTo(3)
        assertThat(buffer.frame().revision).isGreaterThan(before.revision)
    }

    // --- SS3, the sequences that had no case at all ---

    @Test
    fun `an SS3 left arrow moves the cursor instead of printing its final byte`() {
        // `ESC O D` used to fall out of the ESC state unrecognised and print the `D` as text, so the
        // cursor never moved and a stray letter appeared in the line.
        buffer.feed("abc")
        val before = buffer.frame()

        buffer.feed("${E}OD")

        assertThat(buffer.frame().cursorColumn).isEqualTo(2)
        assertThat(buffer.frame().revision).isGreaterThan(before.revision)
        assertThat(buffer.plainText()).doesNotContain("D")
        assertThat(buffer.plainText()).startsWith("abc")
    }

    @Test
    fun `an SS3 right arrow moves the cursor`() {
        buffer.feed("abc")

        buffer.feed("${E}OC")

        assertThat(buffer.frame().cursorColumn).isEqualTo(4)
    }

    @Test
    fun `SS3 home and end address the ends of the line`() {
        buffer.feed("abc")
        buffer.feed("${E}OH")
        assertThat(buffer.frame().cursorColumn).isEqualTo(0)

        buffer.feed("${E}OF")
        assertThat(buffer.frame().cursorColumn).isEqualTo(19)
    }

    @Test
    fun `an SS3 sequence this terminal has no meaning for is consumed, not printed`() {
        // A keypad byte is the common case. Printing it would put a letter nobody typed on screen;
        // dropping the *next* byte instead would eat the character after it.
        buffer.feed("abc")
        val before = buffer.frame()

        buffer.feed("${E}OZ" + "def")

        assertThat(buffer.plainText()).startsWith("abcdef")
        assertThat(buffer.frame().cursorColumn).isEqualTo(6)
        assertThat(buffer.frame().revision).isGreaterThan(before.revision)
    }

    @Test
    fun `a bare SS3 introducer does not print anything on its own`() {
        buffer.feed("abc")

        buffer.feed("${E}O")

        assertThat(buffer.plainText()).startsWith("abc")
    }
}
