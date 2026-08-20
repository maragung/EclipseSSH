package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/** ESC, built from its code point so this file contains no literal control byte. */
private val ESCAPE: String = Char(0x1B).toString()

private fun chars(vararg codes: Int): String = codes.map { Char(it) }.joinToString("")

/**
 * The capabilities that separate a terminal from a log view: the alternate screen, scroll regions,
 * the modes a full-screen program negotiates before it draws, the replies it waits for, and the
 * viewport the renderer is actually handed.
 *
 * Kept apart from [AnsiTerminalBufferTest], which pins parsing, clamping and the memory bounds. The
 * split is by question rather than by size: everything here is about a program *driving* the
 * terminal, and every case is one that made the difference between `htop` working and `htop`
 * scribbling over the user's history.
 */
class AnsiTerminalFullScreenTest {
    private fun lines(buffer: AnsiTerminalBuffer): List<String> =
        buffer.snapshot().lines.map { line -> line.joinToString("") { it.value.toString() }.trimEnd() }

    private fun lines(frame: TerminalFrame): List<String> =
        frame.lines.map { line -> line.joinToString("") { it.value.toString() }.trimEnd() }

    // --- The alternate screen: what keeps vim, less and htop out of the scrollback ---

    @Test
    fun `a full screen program leaves the shell history exactly as it found it`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 20)
        buffer.feed("shell one\nshell two\n")

        // What vim sends on startup, then what it draws.
        buffer.feed("${ESCAPE}[?1049h${ESCAPE}[2J${ESCAPE}[HEDITOR")

        assertThat(buffer.plainText()).isEqualTo("EDITOR")

        // And what it sends on exit.
        buffer.feed("${ESCAPE}[?1049l")

        assertThat(buffer.plainText()).contains("shell one")
        assertThat(buffer.plainText()).contains("shell two")
        assertThat(buffer.plainText()).doesNotContain("EDITOR")
    }

    /**
     * A redraw is not history.
     *
     * Without a separate screen every frame `htop` painted was pushed into the scrollback, so a user
     * who quit it was left scrolling through a few hundred half-overwritten copies of its display
     * instead of the commands they had actually run.
     */
    @Test
    fun `the alternate screen never grows the scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 100)
        buffer.feed("${ESCAPE}[?1049h")

        repeat(50) { buffer.feed("frame $it\n") }

        assertThat(buffer.lineCount()).isEqualTo(4)
        assertThat(buffer.frame().alternateScreen).isTrue()
    }

    @Test
    fun `the older 47 and 1047 screen switches are honoured too`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3, scrollbackLimit = 20)
        buffer.feed("primary\n")

        buffer.feed("${ESCAPE}[?47halt")
        assertThat(buffer.plainText()).isEqualTo("alt")

        buffer.feed("${ESCAPE}[?47l")
        assertThat(buffer.plainText()).isEqualTo("primary")
    }

    @Test
    fun `a rotation while a full screen program is open resizes the shell underneath it`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6)
        buffer.feed("a shell line that is quite long here\n")
        buffer.feed("${ESCAPE}[?1049h")

        buffer.resize(20, 4)
        buffer.feed("${ESCAPE}[?1049l")

        val snapshot = buffer.snapshot()
        assertThat(snapshot.columns).isEqualTo(20)
        assertThat(snapshot.lines.all { it.size == 20 }).isTrue()
    }

    // --- Scroll regions: DECSTBM, which is how less and tmux hold a status line still ---

    @Test
    fun `a scroll region scrolls only its own rows`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 5, scrollbackLimit = 20)
        buffer.feed("${ESCAPE}[1;1Htop${ESCAPE}[2;1Ha${ESCAPE}[3;1Hb${ESCAPE}[4;1Hc${ESCAPE}[5;1Hbottom")

        buffer.feed("${ESCAPE}[2;4r")
        buffer.feed("${ESCAPE}[4;1H\n")

        // "a" left the region at the top; "top" and "bottom" never moved.
        assertThat(lines(buffer)).containsExactly("top", "b", "c", "", "bottom").inOrder()
        // And nothing was pushed into the history: a region scroll discards, it does not archive.
        assertThat(buffer.lineCount()).isEqualTo(5)
    }

    @Test
    fun `a reverse index at the top of a region scrolls it back down`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)
        buffer.feed("${ESCAPE}[1;1Hone${ESCAPE}[2;1Htwo${ESCAPE}[3;1Hthree")

        buffer.feed("${ESCAPE}[1;3r${ESCAPE}[1;1H${ESCAPE}M")

        assertThat(lines(buffer)).containsExactly("", "one", "two", "").inOrder()
    }

    @Test
    fun `an inverted or degenerate region falls back to the whole screen`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 10)

        buffer.feed("${ESCAPE}[4;2r")
        repeat(8) { buffer.feed("row $it\n") }

        // A full-screen region archives, so the history grew rather than the rows being discarded.
        assertThat(buffer.lineCount()).isGreaterThan(4)
        assertThat(buffer.plainText()).contains("row 7")
    }

    // --- Wrapping ---

    @Test
    fun `autowrap off keeps overwriting the last column`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)

        buffer.feed("${ESCAPE}[?7l0123456789ABC")

        assertThat(buffer.plainText()).isEqualTo("012345678C")
        assertThat(buffer.snapshot().cursorRow).isEqualTo(0)
    }

    @Test
    fun `a line exactly as wide as the screen does not consume the next row early`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)

        buffer.feed("0123456789\nnext")

        assertThat(buffer.plainText()).isEqualTo("0123456789\nnext")
        // The cursor stayed on a cell that exists, which is what lets the caret be drawn at all.
        assertThat(buffer.snapshot().cursorColumn).isAtMost(9)
    }

    // --- Keyboard modes, which decide what bytes a key press has to send ---

    @Test
    fun `application cursor key mode is reported to the key mapper`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        assertThat(buffer.frame().applicationCursorKeys).isFalse()

        buffer.feed("${ESCAPE}[?1h")
        assertThat(buffer.frame().applicationCursorKeys).isTrue()

        buffer.feed("${ESCAPE}[?1l")
        assertThat(buffer.frame().applicationCursorKeys).isFalse()
    }

    @Test
    fun `the application keypad switch is tracked`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}=")
        assertThat(buffer.frame().applicationKeypad).isTrue()

        buffer.feed("${ESCAPE}>")
        assertThat(buffer.frame().applicationKeypad).isFalse()
    }

    /**
     * Bracketed paste is a safety feature, not a nicety: without it a pasted block containing a
     * newline is executed line by line the moment it lands, with no chance to read it first.
     */
    @Test
    fun `bracketed paste mode is reported so a paste can be wrapped`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        assertThat(buffer.bracketedPasteEnabled()).isFalse()

        buffer.feed("${ESCAPE}[?2004h")
        assertThat(buffer.bracketedPasteEnabled()).isTrue()
        assertThat(buffer.frame().bracketedPaste).isTrue()

        buffer.feed("${ESCAPE}[?2004l")
        assertThat(buffer.bracketedPasteEnabled()).isFalse()
    }

    @Test
    fun `cursor visibility follows DECTCEM`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        assertThat(buffer.frame().cursorVisible).isTrue()

        buffer.feed("${ESCAPE}[?25l")
        assertThat(buffer.frame().cursorVisible).isFalse()

        buffer.feed("${ESCAPE}[?25h")
        assertThat(buffer.frame().cursorVisible).isTrue()
    }

    // --- Replies: a terminal that never answers is a terminal that hangs the shell ---

    @Test
    fun `a cursor position request is answered with the cursor position`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 5)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[3;7H${ESCAPE}[6n")

        assertThat(replies).containsExactly("${ESCAPE}[3;7R")
    }

    @Test
    fun `device attributes and device status are answered`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[c${ESCAPE}[5n${ESCAPE}[>c")

        assertThat(replies).containsExactly("${ESCAPE}[?1;2c", "${ESCAPE}[0n", "${ESCAPE}[>0;95;0c").inOrder()
    }

    @Test
    fun `ordinary output asks for no reply at all`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        var calls = 0
        buffer.responder = { calls++ }

        buffer.feed("${ESCAPE}[31mjust some coloured text${ESCAPE}[0m\n")

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `a reply is dispatched only after the input it answers has been applied`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        var seen: String? = null
        buffer.responder = { seen = buffer.plainText() }

        buffer.feed("prompt${ESCAPE}[6n")

        assertThat(seen).isEqualTo("prompt")
    }

    /**
     * The reply must not be written while the buffer's monitor is held.
     *
     * A real responder writes to the SSH channel, which blocks. If that happened inside the parse,
     * the thread draining that same channel could not feed or render while it blocked - the write
     * waits for the reader and the reader waits for the monitor, and the session freezes with no
     * error anywhere. The timeout is the assertion.
     */
    @Test(timeout = 10_000)
    fun `a reply does not hold the buffer lock while it is written`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val readerFinished = CountDownLatch(1)
        buffer.responder = {
            Thread {
                buffer.snapshot()
                readerFinished.countDown()
            }.start()
            readerFinished.await(5, TimeUnit.SECONDS)
        }

        buffer.feed("${ESCAPE}[6n")

        assertThat(readerFinished.count).isEqualTo(0L)
    }

    // --- Character sets, colours and the smaller editing sequences ---

    @Test
    fun `dec special graphics draws box rules instead of letters`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        // What ncurses sends for the top edge of a dialog.
        buffer.feed("${ESCAPE}(0lqqk${ESCAPE}(B done")

        assertThat(buffer.plainText()).isEqualTo(chars(0x250C, 0x2500, 0x2500, 0x2510) + " done")
    }

    /**
     * Bright and normal used to be the same 16 values, and "bright black" - which is what almost
     * everything reaches for to dim text - came out as pure black on a black background.
     */
    @Test
    fun `bright colours are distinguishable from their normal counterparts`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[30mA${ESCAPE}[90mB${ESCAPE}[31mC${ESCAPE}[91mD")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.foreground).isNotEqualTo(line[1].style.foreground)
        assertThat(line[2].style.foreground).isNotEqualTo(line[3].style.foreground)
        assertThat(line[1].style.foreground.rgb).isGreaterThan(line[0].style.foreground.rgb)
    }

    @Test
    fun `the 256 colour palette keeps its bright block distinct as well`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[38;5;0mA${ESCAPE}[38;5;8mB")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.foreground).isNotEqualTo(line[1].style.foreground)
    }

    @Test
    fun `the extended styles a modern shell prompt uses are recorded`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[2mA${ESCAPE}[3mB${ESCAPE}[9mC${ESCAPE}[8mD${ESCAPE}[0mE")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.dim).isTrue()
        assertThat(line[1].style.italic).isTrue()
        assertThat(line[2].style.strikethrough).isTrue()
        assertThat(line[3].style.hidden).isTrue()
        assertThat(line[4].style).isEqualTo(TerminalStyle())
    }

    @Test
    fun `index keeps the column and a newline does not`() {
        val stepped = AnsiTerminalBuffer(columns = 10, rows = 3)
        stepped.feed("abc${ESCAPE}Dx")
        assertThat(stepped.plainText()).isEqualTo("abc\n   x")

        val plain = AnsiTerminalBuffer(columns = 10, rows = 3)
        plain.feed("abc\nx")
        assertThat(plain.plainText()).isEqualTo("abc\nx")
    }

    @Test
    fun `erase characters blanks in place without shifting the rest of the line`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed("abcdefgh")

        buffer.feed("${ESCAPE}[1;3H${ESCAPE}[3X")

        assertThat(buffer.plainText()).isEqualTo("ab   fgh")
    }

    @Test
    fun `insert mode pushes the rest of the line to the right`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed("world")

        buffer.feed("${ESCAPE}[1;1H${ESCAPE}[4hhello ${ESCAPE}[4l")

        assertThat(buffer.plainText()).isEqualTo("hello world")
        assertThat(buffer.snapshot().lines[0]).hasSize(20)
    }

    @Test
    fun `repeat draws the last character again`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("-${ESCAPE}[9b")

        assertThat(buffer.plainText()).isEqualTo("-".repeat(10))
    }

    @Test
    fun `a custom tab stop replaces the default eight column grid`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[3g")
        buffer.feed("${ESCAPE}[1;5H${ESCAPE}H")
        buffer.feed("${ESCAPE}[1;1HA\tB")

        assertThat(buffer.plainText()).isEqualTo("A   B")
    }

    @Test
    fun `an osc title is captured for the session tab`() {
        val buffer = AnsiTerminalBuffer(columns = 30, rows = 3)

        buffer.feed("${ESCAPE}]0;user@host: ~${chars(0x07)}ready")

        assertThat(buffer.frame().title).isEqualTo("user@host: ~")
        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `a soft reset returns the terminal to a usable state`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("${ESCAPE}[?1049h${ESCAPE}[?7l${ESCAPE}[?25l${ESCAPE}[31mconfused")

        buffer.feed("${ESCAPE}[!p")

        val frame = buffer.frame()
        assertThat(frame.alternateScreen).isFalse()
        assertThat(frame.cursorVisible).isTrue()
        assertThat(buffer.plainText()).isEmpty()
    }

    // --- Scrollback ownership: clear must not throw away what the user scrolled back to read ---

    @Test
    fun `clearing the screen keeps the history`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 50)
        repeat(20) { buffer.feed("line $it\n") }

        buffer.feed("${ESCAPE}[H${ESCAPE}[2J")

        assertThat(buffer.plainText()).contains("line 3")
        assertThat(buffer.lineCount()).isGreaterThan(4)
    }

    @Test
    fun `erase saved lines is the one sequence allowed to drop the history`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 50)
        repeat(20) { buffer.feed("line $it\n") }
        assertThat(buffer.lineCount()).isGreaterThan(4)

        buffer.feed("${ESCAPE}[3J")

        assertThat(buffer.lineCount()).isEqualTo(4)
    }

    // --- The viewport the renderer is handed ---

    @Test
    fun `a frame draws only the viewport and addresses it absolutely`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 50)
        repeat(20) { buffer.feed("line $it\n") }
        buffer.feed("tail")

        val live = buffer.frame(scrollOffset = 0, viewportRows = 4)

        assertThat(live.lines).hasSize(4)
        assertThat(live.totalLines).isEqualTo(buffer.lineCount())
        assertThat(live.firstLine).isEqualTo(live.totalLines - 4)
        assertThat(lines(live).last()).isEqualTo("tail")
        assertThat(live.cursorRow).isEqualTo(3)

        val back = buffer.frame(scrollOffset = 4, viewportRows = 4)

        assertThat(back.firstLine).isEqualTo(live.firstLine - 4)
        assertThat(back.lines).hasSize(4)
        // The cursor is off-screen once the user has scrolled away from the live output.
        assertThat(back.cursorRow).isEqualTo(-1)
        assertThat(buffer.maxScrollOffset(4)).isEqualTo(buffer.lineCount() - 4)
    }

    @Test
    fun `a scroll offset past the top of the buffer is clamped rather than throwing`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 10)
        buffer.feed("only one line")

        val frame = buffer.frame(scrollOffset = 10_000, viewportRows = 4)

        assertThat(frame.firstLine).isEqualTo(0)
        assertThat(frame.lines).hasSize(4)
    }

    @Test
    fun `a viewport taller than the buffer is served with what exists`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 5)

        val frame = buffer.frame(scrollOffset = 0, viewportRows = 500)

        assertThat(frame.lines).hasSize(buffer.lineCount())
        assertThat(frame.firstLine).isEqualTo(0)
    }

    @Test
    fun `the revision advances on output and stands still otherwise`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)
        val before = buffer.frame().revision

        assertThat(buffer.frame().revision).isEqualTo(before)

        buffer.feed("x")

        assertThat(buffer.frame().revision).isGreaterThan(before)
    }

    // --- Selection, which is what copy reads ---

    @Test
    fun `a selection is extracted by absolute buffer coordinates`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("first line\nsecond line\nthird")

        assertThat(buffer.textIn(0, 6, 1, 6)).isEqualTo("line\nsecond")
        // Padding inside the screen is trimmed per line, so a copied block carries no trailing spaces.
        assertThat(buffer.textIn(0, 0, 2, 20)).isEqualTo("first line\nsecond line\nthird")
    }

    @Test
    fun `an out of range selection is clamped to the buffer`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("only")

        assertThat(buffer.textIn(-5, -5, 9_999, 9_999)).isEqualTo("only")
        assertThat(buffer.textIn(3, 0, 1, 0)).isEmpty()
    }
}
