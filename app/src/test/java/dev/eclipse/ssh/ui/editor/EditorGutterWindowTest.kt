package dev.eclipse.ssh.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two pieces of arithmetic the editor's chrome rests on, tested where they are cheap to test.
 *
 * Neither needs a device, a window or a composition: one decides which line numbers are worth
 * composing, the other reads the status line's three numbers off the document. They are pulled out of
 * their composables for exactly that reason — a bug in either is a status line that lies or a gutter
 * that drops a number, and both are the kind of thing that is far easier to find here than on a
 * phone.
 *
 * The windowing one matters more than it looks. The gutter used to compose a text node per line, so
 * a thousand-line file paid for a thousand composables on every keystroke; the saving is real only if
 * the window is *correct*, and correctness is what these assert — the visible lines are inside it,
 * and it never names a line that is not there.
 */
class EditorGutterWindowTest {

    /** A document of [lines] lines, each [heightPx] tall, in the layout's own coordinates. */
    private fun tops(lines: Int, heightPx: Float = 20f) =
        FloatArray(lines + 1) { it * heightPx }

    @Test
    fun `a document at the top shows the lines at the top`() {
        val window = gutterWindow(tops(100), scrollOffsetPx = 0f, viewportHeightPx = 200f)
        assertThat(window.first).isEqualTo(0)
        // Ten lines fit in 200px, plus the overscan below them.
        assertThat(window.last).isAtLeast(9)
    }

    @Test
    fun `scrolling down moves the window down with the text`() {
        // Line 50 starts at 1000px, so a viewport there must contain line 50 and must not contain
        // line 5 — which is the whole failure mode this guards: numbers that belong to no line
        // currently on screen, and a blank gutter where the numbers should be.
        val window = gutterWindow(tops(100), scrollOffsetPx = 1000f, viewportHeightPx = 200f)
        assertThat(window).contains(50)
        assertThat(window).doesNotContain(5)
        assertThat(window.first).isAtMost(50)
        assertThat(window.last).isAtLeast(59)
    }

    @Test
    fun `the window never runs past the last line`() {
        // The end of the document is where an off-by-one asks for a line that does not exist — and
        // the gutter would index its own height array with it.
        val window = gutterWindow(tops(100), scrollOffsetPx = 1900f, viewportHeightPx = 400f)
        assertThat(window.last).isEqualTo(99)
    }

    @Test
    fun `the window never starts before the first line`() {
        val window = gutterWindow(tops(100), scrollOffsetPx = 0f, viewportHeightPx = 40f)
        assertThat(window.first).isEqualTo(0)
    }

    @Test
    fun `a viewport that has not been measured yet still shows the top of the document`() {
        // The first composition runs before the layout has reported a size, so the height is zero.
        // Returning nothing at all would blink an empty gutter on every open; returning everything
        // would be the cost this exists to avoid.
        val window = gutterWindow(tops(1000), scrollOffsetPx = 0f, viewportHeightPx = 0f)
        assertThat(window.first).isEqualTo(0)
        assertThat(window.last).isLessThan(20)
    }

    @Test
    fun `a one-line document has exactly one line in its window`() {
        val window = gutterWindow(tops(1), scrollOffsetPx = 0f, viewportHeightPx = 200f)
        assertThat(window).isEqualTo(0..0)
    }

    @Test
    fun `an empty height array is not a crash`() {
        // Nothing to draw a number against; the gutter's own layout is what fills this in.
        assertThat(gutterWindow(FloatArray(0), scrollOffsetPx = 0f, viewportHeightPx = 100f)).isEqualTo(0..0)
        assertThat(gutterWindow(FloatArray(1), scrollOffsetPx = 0f, viewportHeightPx = 100f)).isEqualTo(0..0)
    }
}

/**
 * The status line's three numbers, from one walk of the document.
 *
 * They used to be two walks — one stopping at the caret, one to the end of the file for the line
 * count — on every recomposition, which is why they were folded together. Folding them is only safe
 * if the results are the same, so that is what this pins: the line and column of a caret, and the
 * line count of the document it is in.
 */
class EditorDocumentPositionTest {

    @Test
    fun `an empty document is line one, column one, one line`() {
        assertThat(documentPosition("", 0)).isEqualTo(DocumentPosition(line = 1, column = 1, lines = 1))
    }

    @Test
    fun `a caret at the start of a line is column one`() {
        assertThat(documentPosition("abc", 0)).isEqualTo(DocumentPosition(line = 1, column = 1, lines = 1))
    }

    @Test
    fun `a caret at the end of a line is one past its last character`() {
        // The convention the status line has always used: the column is where the next character
        // would go, so a three-character first line reports Col 4 at its end.
        assertThat(documentPosition("abc", 3)).isEqualTo(DocumentPosition(line = 1, column = 4, lines = 1))
    }

    @Test
    fun `a newline ends its line and starts the next at column one`() {
        assertThat(documentPosition("a\nb", 2)).isEqualTo(DocumentPosition(line = 2, column = 1, lines = 2))
    }

    @Test
    fun `a trailing newline is a line the caret can sit on`() {
        // A file ending in `\n` has one more line than it has newline-separated runs, and editors
        // show the caret there; the count has always included it.
        assertThat(documentPosition("a\nb\n", 4)).isEqualTo(DocumentPosition(line = 3, column = 1, lines = 3))
    }

    @Test
    fun `a caret past the end of the text is clamped, not refused`() {
        // A selection can outlive the text it indexed into for the one frame between a shortening
        // replace and the selection that follows it. Crashing there would take the editor down for
        // an edit the user makes all the time.
        assertThat(documentPosition("abc", 99)).isEqualTo(DocumentPosition(line = 1, column = 4, lines = 1))
    }

    @Test
    fun `the line count does not depend on where the caret is`() {
        val text = "one\ntwo\nthree"
        val atStart = documentPosition(text, 0)
        val atEnd = documentPosition(text, text.length)
        assertThat(atStart.lines).isEqualTo(3)
        assertThat(atEnd.lines).isEqualTo(3)
        assertThat(atStart).isNotEqualTo(atEnd)
    }
}
