package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/** What one drawn row reads as, trailing blanks dropped the way the renderer drops them. */
private fun List<TerminalCell>.text(): String = joinToString("") { it.value.toString() }.trimEnd()

class AnsiTerminalBufferTest {
    @Test
    fun `ansi control sequences do not leak into plain terminal text`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[31mred$E[0m normal")

        assertThat(buffer.plainText()).contains("red normal")
        assertThat(buffer.snapshot().lines.first()[0].style.foreground.isDefault).isFalse()
    }

    @Test
    fun `cursor movement overwrites at requested position`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)

        buffer.feed("hello$E[2DXX")

        assertThat(buffer.plainText().lineSequence().first()).startsWith("helXX")
    }

    @Test
    fun `line feed retains scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2, scrollbackLimit = 10)

        buffer.feed("one\ntwo\nthree")

        assertThat(buffer.plainText()).contains("one")
        assertThat(buffer.plainText()).contains("three")
    }

    // --- Streaming: a channel delivers arbitrary chunks, not whole sequences ---

    @Test
    fun `an escape sequence split across reads is still interpreted`() {
        val whole = AnsiTerminalBuffer(columns = 20, rows = 3)
        val split = AnsiTerminalBuffer(columns = 20, rows = 3)
        val output = "$E[32mgreen$E[0m done"

        whole.feed(output)
        // The same bytes arriving one at a time must produce the same screen.
        output.forEach { split.feed(it.toString()) }

        assertThat(split.plainText()).isEqualTo(whole.plainText())
        assertThat(split.snapshot().lines.first()[0].style)
            .isEqualTo(whole.snapshot().lines.first()[0].style)
    }

    @Test
    fun `an OSC title sequence does not swallow the output that follows it`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E]0;my title\u0007ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `a garbage escape byte is dropped without corrupting the line`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("a$E b")

        assertThat(buffer.plainText()).isEqualTo("ab")
    }

    // --- Bounded memory: a chatty server must not grow the buffer forever ---

    @Test
    fun `scrollback is bounded so a noisy session cannot exhaust memory`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 8)

        repeat(500) { buffer.feed("line $it\n") }

        assertThat(buffer.snapshot().lines.size).isAtMost(4 + 8)
        // The newest output is what survives.
        assertThat(buffer.plainText()).contains("line 499")
        assertThat(buffer.plainText()).doesNotContain("line 0")
    }

    @Test
    fun `output wider than the terminal wraps instead of overflowing the row`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 4)

        buffer.feed("x".repeat(25))

        val snapshot = buffer.snapshot()
        assertThat(snapshot.lines.all { it.size == 20 }).isTrue()
        assertThat(snapshot.lines[0].count { it.value == 'x' }).isEqualTo(20)
        assertThat(snapshot.lines[1].count { it.value == 'x' }).isEqualTo(5)
    }

    @Test
    fun `carriage return rewrites the line in place like a progress bar`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("10%\r100%")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("100%")
    }

    @Test
    fun `backspace stops at the left margin`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)

        buffer.feed("\b\b\bok")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("ok")
    }

    // --- Screen-clearing sequences used by top, htop and vim ---

    @Test
    fun `erase display clears the whole screen`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("first\nsecond")

        buffer.feed("$E[2J")

        assertThat(buffer.plainText()).isEmpty()
    }

    @Test
    fun `erase to end of line leaves the prefix intact`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 2)
        buffer.feed("keep this away")

        buffer.feed("$E[10D$E[K")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("keep")
    }

    @Test
    fun `absolute cursor addressing writes at the requested cell`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 5)

        buffer.feed("$E[3;5HX")

        assertThat(buffer.snapshot().lines[2][4].value).isEqualTo('X')
    }

    @Test
    fun `cursor addressing beyond the screen is clamped instead of throwing`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[999;999HX")

        val snapshot = buffer.snapshot()
        assertThat(snapshot.cursorRow).isAtMost(snapshot.lines.lastIndex)
        assertThat(snapshot.cursorColumn).isAtMost(snapshot.columns)
        assertThat(buffer.plainText()).contains("X")
    }

    @Test
    fun `save and restore cursor returns to the saved cell`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("abc$E[s$E[3;1Hxyz$E[uQ")

        // Q lands where the cursor was saved: row 0, column 3.
        assertThat(buffer.snapshot().lines[0][3].value).isEqualTo('Q')
    }

    @Test
    fun `full reset clears the screen and the pending style`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("$E[31mred\nmore")

        buffer.feed("${E}cplain")

        assertThat(buffer.plainText()).isEqualTo("plain")
        assertThat(buffer.snapshot().lines[0][0].style.foreground.isDefault).isTrue()
    }

    // --- Styling ---

    @Test
    fun `256 colour and true colour sequences both set a foreground`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E[38;5;208mA$E[38;2;10;20;30mB")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.foreground.isDefault).isFalse()
        assertThat(line[1].style.foreground.isTrueColor).isTrue()
        assertThat(line[1].style.foreground.rgb).isEqualTo((10 shl 16) or (20 shl 8) or 30)
    }

    @Test
    fun `bold and underline are cleared by their reset codes`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E[1;4mA$E[22;24mB")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.bold).isTrue()
        assertThat(line[0].style.underline).isTrue()
        assertThat(line[1].style.bold).isFalse()
        assertThat(line[1].style.underline).isFalse()
    }

    @Test
    fun `a truncated true colour sequence is ignored rather than mis-parsed`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E[38;2;10mA")

        assertThat(buffer.snapshot().lines[0][0].style.foreground.isDefault).isTrue()
    }

    // --- Resize: happens on rotation and on soft-keyboard show/hide ---

    @Test
    fun `resizing keeps every row exactly as wide as the new terminal`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6)
        buffer.feed("hello world\nsecond line")

        buffer.resize(20, 4)

        val snapshot = buffer.snapshot()
        assertThat(snapshot.columns).isEqualTo(20)
        assertThat(snapshot.lines.all { it.size == 20 }).isTrue()
        assertThat(buffer.plainText()).contains("hello world")
    }

    @Test
    fun `absurd resize requests are clamped to a usable geometry`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6)

        buffer.resize(0, 0)

        val small = buffer.snapshot()
        assertThat(small.columns).isAtLeast(20)
        assertThat(small.rows).isAtLeast(5)

        buffer.resize(100_000, 100_000)

        val large = buffer.snapshot()
        assertThat(large.columns).isAtMost(400)
        assertThat(large.rows).isAtMost(200)
    }

    /**
     * [AnsiTerminalBuffer.plainText] still renders exactly what the obvious expression renders.
     *
     * It was that expression, verbatim, until it turned out to allocate a `String` per cell and to be
     * called once per rendered frame. The replacement builds the same text into one StringBuilder, so
     * the risk is no longer performance but a subtle mismatch: where the per-line `trimEnd()` stops,
     * whether the separator survives a trim, what an entirely blank screen renders as. The reference
     * is kept here rather than described, and compared over inputs chosen to hit each of those.
     */
    @Test
    fun `plainText matches the straightforward rendering`() {
        fun reference(snapshot: TerminalSnapshot): String = snapshot.lines
            .joinToString("\n") { line -> line.joinToString("") { it.value.toString() }.trimEnd() }
            .trimEnd()

        val cases = mapOf(
            "an untouched screen" to "",
            "one word" to "hello",
            "trailing spaces on a line" to "hello   ",
            "several lines" to "one\ntwo\nthree",
            // A gap of blank lines in the middle must survive; only the trailing run is dropped.
            "a blank line between two full ones" to "top\n\nbottom",
            // Ends on a blank line, which is what the outer trimEnd() exists for.
            "output that ends with newlines" to "text\n\n\n",
            "a line as wide as the screen" to "x".repeat(20),
            // Wider than the screen, so it wraps and the wrapped part has no trailing space to trim.
            "a line wider than the screen" to "y".repeat(45),
            // Enough to push the first lines into scrollback.
            "more lines than the screen has rows" to (1..12).joinToString("\n") { "line $it" },
            "a line of nothing but spaces" to "   \nafter",
            "tabs and non-latin text" to "kolom\tnilai\nsandi-üñïçø∂é",
            "styling around the text" to "$E[1;31mbold red$E[0m plain",
            // Cursor addressing leaves untouched cells as spaces mid-line.
            "cursor addressing that leaves gaps" to "$E[2;5Hmid$E[4;1Hlow",
        )

        cases.forEach { (name, input) ->
            val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
            buffer.feed(input)

            assertWithMessage(name).that(buffer.plainText()).isEqualTo(reference(buffer.snapshot()))
        }
    }

    @Test
    fun `writing after a shrink stays inside the buffer`() {
        val buffer = AnsiTerminalBuffer(columns = 200, rows = 50)
        buffer.feed("$E[40;180HX")

        buffer.resize(20, 5)
        buffer.feed("${E}[Hafter")

        assertThat(buffer.plainText()).contains("after")
        assertThat(buffer.snapshot().lines.all { it.size == 20 }).isTrue()
    }

    /**
     * Shrinking to fit the device keeps the output on screen instead of scrolling it into history.
     *
     * This is the first thing that happens to every session. The buffer starts at its default forty
     * rows, the shell greets it with a banner and a prompt, and then the view finishes measuring
     * itself and resizes to however many rows the device actually has. Because the screen is the tail
     * of the buffer, that shrink used to re-anchor the window several rows further down and leave the
     * banner, the prompt and the cursor above it: a session that had connected fine and authenticated
     * fine displayed an empty grid, and went on displaying one until enough output arrived to fill the
     * window. Every assertion here is about the frame rather than [AnsiTerminalBuffer.plainText],
     * because the bug was invisible in the text - the lines were all still in the buffer, just not in
     * the part of it that gets drawn.
     */
    @Test
    fun `shrinking to fit an almost empty screen keeps its output and cursor on screen`() {
        val buffer = AnsiTerminalBuffer(columns = 120, rows = 40)
        buffer.feed("eclipse-shell\r\n\$ ")

        buffer.resize(120, 34)

        val frame = buffer.frame()
        assertThat(frame.rows).isEqualTo(34)
        assertThat(frame.lines).hasSize(34)
        assertThat(frame.firstLine).isEqualTo(0)
        assertThat(frame.lines[0].text()).isEqualTo("eclipse-shell")
        assertThat(frame.lines[1].text()).isEqualTo("\$")
        assertThat(frame.cursorRow).isEqualTo(1)
        assertThat(frame.cursorColumn).isEqualTo(2)
    }

    /** The other half of the rule: a screen with output on every row does lose its topmost rows. */
    @Test
    fun `shrinking a full screen still scrolls its topmost rows into the scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 10, scrollbackLimit = 100)
        buffer.feed((1..10).joinToString("\r\n") { "line $it" })

        buffer.resize(20, 6)

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(6)
        assertThat(frame.lines.first().text()).isEqualTo("line 5")
        assertThat(frame.lines.last().text()).isEqualTo("line 10")
        assertThat(frame.cursorRow).isEqualTo(5)
        // Scrolled away, not thrown away.
        assertThat(buffer.plainText()).contains("line 1")
    }

    /**
     * A blank row with the cursor on it is not spare space - the shell is about to print there.
     *
     * A shell that has just been handed a newline sits on an empty row until it writes its next
     * prompt. Reclaiming that row on a shrink would move the cursor up onto the previous line and the
     * prompt would then overwrite the command the user had just run.
     */
    @Test
    fun `a shrink keeps the blank row the cursor is waiting on`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 10, scrollbackLimit = 100)
        buffer.feed("only line\r\n")

        buffer.resize(20, 5)
        buffer.feed("next\$ ")

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(5)
        assertThat(frame.lines[0].text()).isEqualTo("only line")
        assertThat(frame.lines[1].text()).isEqualTo("next\$")
        assertThat(frame.cursorRow).isEqualTo(1)
    }

    /** The same rule where it bites hardest: the cursor is on the very last row of a full screen. */
    @Test
    fun `a shrink keeps a cursor waiting at the bottom of a full screen`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 10, scrollbackLimit = 100)
        buffer.feed((1..9).joinToString("\r\n") { "line $it" } + "\r\n")

        buffer.resize(20, 6)
        buffer.feed("next\$ ")

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(6)
        assertThat(frame.cursorRow).isEqualTo(5)
        assertThat(frame.lines.first().text()).isEqualTo("line 5")
        assertThat(frame.lines.last().text()).isEqualTo("next\$")
    }

    /** Rotating a device shrinks and then grows again; both halves have to come out even. */
    @Test
    fun `rotating back to the taller geometry neither duplicates nor loses a line`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 40)
        buffer.feed("banner\r\n\$ ")

        buffer.resize(40, 20)
        buffer.resize(40, 40)

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(40)
        assertThat(frame.totalLines).isEqualTo(40)
        assertThat(frame.lines[0].text()).isEqualTo("banner")
        assertThat(frame.lines[1].text()).isEqualTo("\$")
        assertThat(frame.lines.drop(2).all { it.text().isEmpty() }).isTrue()
        assertThat(frame.cursorRow).isEqualTo(1)
    }

    /**
     * Rotating while a full-screen program is up must not lose the shell waiting behind it.
     *
     * The primary screen is stored away untouched while the alternate one is on display, so it needs
     * the same treatment on a shrink as the visible one - otherwise quitting `vim` after a rotation
     * dropped the user onto a shell whose prompt had been pushed into the scrollback.
     */
    @Test
    fun `leaving the alternate screen after a shrink brings the shell back on screen`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 40)
        buffer.feed("banner\r\n\$ vim")
        buffer.feed("$E[?1049h")
        buffer.feed("~\r\n~")

        buffer.resize(40, 24)
        buffer.feed("$E[?1049l")

        val frame = buffer.frame()
        assertThat(frame.alternateScreen).isFalse()
        assertThat(frame.lines).hasSize(24)
        assertThat(frame.lines[0].text()).isEqualTo("banner")
        assertThat(frame.lines[1].text()).isEqualTo("\$ vim")
        assertThat(frame.cursorRow).isEqualTo(1)
    }

    // --- Malformed input: every byte here is the server's word, and a terminal is fed by whatever
    // is on the other end of the socket. None of these may throw, hang, or grow without bound.

    /**
     * A negative parameter cannot move the cursor outside the buffer.
     *
     * This is the crash it was: a CSI sequence ends only at a byte in `'@'..'~'`, and "-" is not one,
     * so "-5" arrived as a parameter value of minus five. Every relative move then went the wrong
     * way past a bound that was only checked on the side the sequence was supposed to travel —
     * `ESC [ -5 A` is "up", so only the top was checked, and the cursor landed five rows below the
     * last line. The next printable character indexed the line list out of bounds, inside the
     * terminal output collector, where an exception reaches the default handler and kills the app.
     *
     * Each case is asserted with the cursor already parked at an extreme, because that is where the
     * off-by-a-buffer lands, and each is followed by a write so the index is actually used.
     */
    @Test
    fun `a negative csi parameter cannot push the cursor out of the buffer`() {
        val moves = listOf("A", "B", "C", "D", "E", "F", "G", "d", "H", "f", "`")
        val corners = listOf("$E[H", "$E[999;999H")

        corners.forEach { corner ->
            moves.forEach { move ->
                val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

                buffer.feed("$corner$E[-5$move" + "X")

                val snapshot = buffer.snapshot()
                assertWithMessage("$corner ESC[-5$move row")
                    .that(snapshot.cursorRow).isIn(0..snapshot.lines.lastIndex)
                assertWithMessage("$corner ESC[-5$move column")
                    .that(snapshot.cursorColumn).isIn(0..snapshot.columns)
                assertWithMessage("$corner ESC[-5$move printed").that(buffer.plainText()).contains("X")
            }
        }
    }

    /**
     * The same, for the sequences that repeat an edit rather than move.
     *
     * A clamped-to-zero parameter reads as "absent" here, which is what the sequences mean by their
     * own default of one — so each of these performs a single edit rather than none, and the point of
     * the test is that the edit stays inside the buffer and the terminal keeps printing.
     */
    @Test
    fun `a negative csi parameter cannot corrupt an edit sequence`() {
        listOf("P", "@", "L", "M").forEach { edit ->
            val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 10)

            buffer.feed("hello$E[-5$edit" + "!")

            val snapshot = buffer.snapshot()
            assertWithMessage("ESC[-5$edit").that(snapshot.lines.size).isAtMost(14)
            assertWithMessage("ESC[-5$edit").that(snapshot.cursorRow).isIn(0..snapshot.lines.lastIndex)
            assertWithMessage("ESC[-5$edit").that(buffer.plainText()).contains("!")
        }
    }

    /**
     * An absurd repeat count finishes promptly.
     *
     * Every iteration past the width of the buffer changes nothing, but it was still executed: a
     * billion list splices under this object's monitor, which froze the session's output for minutes
     * and was indistinguishable from a dead connection. The timeout is the assertion; the clamp
     * turns it into four hundred operations.
     */
    @Test(timeout = 10_000)
    fun `an absurd repeat count is clamped instead of run`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 10)

        buffer.feed("hello$E[2147483647P$E[2147483647@$E[2147483647L$E[2147483647M" + "done")

        assertThat(buffer.snapshot().lines.size).isAtMost(14)
        assertThat(buffer.plainText()).contains("done")
    }

    /**
     * An escape sequence that never ends is abandoned rather than accumulated.
     *
     * Digits and ';' do not terminate a CSI sequence, so a server stuck mid-sequence fed a
     * StringBuilder that nothing bounded — the process died of OutOfMemoryError given long enough.
     * Feeding far more than the cap and then a legitimate line proves both halves: the buffer let go,
     * and it went back to printing.
     */
    @Test(timeout = 10_000)
    fun `an unterminated csi sequence is abandoned so printing resumes`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[" + "1;".repeat(50_000))
        buffer.feed("\nrecovered")

        assertThat(buffer.plainText()).contains("recovered")
    }

    /**
     * The same for OSC, where the failure was silence rather than memory.
     *
     * An OSC string ends at BEL or ST, and until one arrived every byte was swallowed. One stray
     * `ESC ]` in a corrupted stream therefore blanked the terminal for the rest of the session.
     */
    @Test(timeout = 10_000)
    fun `an unterminated osc sequence stops swallowing output`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E]0;" + "title ".repeat(10_000))
        buffer.feed("recovered")

        assertThat(buffer.plainText()).contains("recovered")
    }

    /** A tab at the right margin stops there, rather than wrapping the next character. */
    @Test
    fun `a tab at the right margin does not force a line feed`() {
        val buffer = AnsiTerminalBuffer(columns = 8, rows = 3)

        buffer.feed("abcdefg\tX")

        assertThat(buffer.snapshot().cursorRow).isEqualTo(0)
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("abcdefgX")
    }
}
