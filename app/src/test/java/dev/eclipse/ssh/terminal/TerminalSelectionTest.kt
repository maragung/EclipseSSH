package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Selection geometry: what the renderer highlights and what the clipboard gets, which must be the
 * same cells.
 *
 * The failure mode this guards is quiet. An off-by-one between [TerminalSelection.contains] and
 * [TerminalSelection.toTextRange] is invisible in a screenshot and shows up the first time someone
 * copies a path out of a terminal and loses its last character - by which point they have already
 * pasted it somewhere.
 */
class TerminalSelectionTest {

    @Test
    fun `a fresh selection covers exactly one cell`() {
        val selection = TerminalSelection.at(line = 5, column = 3)
        assertThat(selection.isSingleCell).isTrue()
        assertThat(selection.contains(5, 3)).isTrue()
        assertThat(selection.contains(5, 2)).isFalse()
        assertThat(selection.contains(5, 4)).isFalse()
        assertThat(selection.contains(4, 3)).isFalse()
    }

    @Test
    fun `a forward drag keeps anchor first`() {
        val selection = TerminalSelection.at(2, 4).movedTo(6, 1)
        assertThat(selection.startLine).isEqualTo(2)
        assertThat(selection.startColumn).isEqualTo(4)
        assertThat(selection.endLine).isEqualTo(6)
        assertThat(selection.endColumn).isEqualTo(1)
        assertThat(selection.isSingleCell).isFalse()
    }

    @Test
    fun `a backward drag is normalised so start is always before end`() {
        // Dragging upward is ordinary, and every consumer wants the ordered pair.
        val selection = TerminalSelection.at(6, 1).movedTo(2, 4)
        assertThat(selection.startLine).isEqualTo(2)
        assertThat(selection.startColumn).isEqualTo(4)
        assertThat(selection.endLine).isEqualTo(6)
        assertThat(selection.endColumn).isEqualTo(1)
    }

    @Test
    fun `a right to left drag on one line is normalised by column`() {
        val selection = TerminalSelection.at(3, 9).movedTo(3, 2)
        assertThat(selection.startLine).isEqualTo(3)
        assertThat(selection.startColumn).isEqualTo(2)
        assertThat(selection.endColumn).isEqualTo(9)
    }

    @Test
    fun `contains covers the whole middle line of a multi line selection`() {
        val selection = TerminalSelection(anchorLine = 1, anchorColumn = 5, focusLine = 3, focusColumn = 2)
        // Start line: from the anchor column rightwards.
        assertThat(selection.contains(1, 4)).isFalse()
        assertThat(selection.contains(1, 5)).isTrue()
        assertThat(selection.contains(1, 500)).isTrue()
        // Middle line: all of it.
        assertThat(selection.contains(2, 0)).isTrue()
        assertThat(selection.contains(2, 999)).isTrue()
        // End line: up to and including the focus column.
        assertThat(selection.contains(3, 2)).isTrue()
        assertThat(selection.contains(3, 3)).isFalse()
        // Outside entirely.
        assertThat(selection.contains(0, 5)).isFalse()
        assertThat(selection.contains(4, 0)).isFalse()
    }

    @Test
    fun `firstColumnOn and lastColumnOn let a renderer skip unselected rows`() {
        val selection = TerminalSelection(1, 5, 3, 2)
        val columns = 80
        assertThat(selection.firstColumnOn(0)).isEqualTo(-1)
        assertThat(selection.lastColumnOn(0, columns)).isEqualTo(-1)
        assertThat(selection.firstColumnOn(1)).isEqualTo(5)
        assertThat(selection.lastColumnOn(1, columns)).isEqualTo(columns - 1)
        assertThat(selection.firstColumnOn(2)).isEqualTo(0)
        assertThat(selection.lastColumnOn(2, columns)).isEqualTo(columns - 1)
        assertThat(selection.firstColumnOn(3)).isEqualTo(0)
        assertThat(selection.lastColumnOn(3, columns)).isEqualTo(2)
        assertThat(selection.firstColumnOn(4)).isEqualTo(-1)
    }

    @Test
    fun `lastColumnOn clamps an end column past the row width`() {
        // A drag can leave the right edge; the highlight must stop at the last real cell.
        val selection = TerminalSelection(2, 0, 2, 400)
        assertThat(selection.lastColumnOn(2, columns = 80)).isEqualTo(79)
    }

    @Test
    fun `the range handed to the buffer makes the end column exclusive`() {
        // The selection is inclusive at both ends; AnsiTerminalBuffer.textIn is half open. Converting
        // in exactly one place is what stops the highlight and the copied text disagreeing.
        val range = TerminalSelection(1, 5, 3, 2).toTextRange()
        assertThat(range.fromLine).isEqualTo(1)
        assertThat(range.fromColumn).isEqualTo(5)
        assertThat(range.toLine).isEqualTo(3)
        assertThat(range.toColumn).isEqualTo(3)
    }

    @Test
    fun `a single cell range is one column wide not zero`() {
        val range = TerminalSelection.at(4, 7).toTextRange()
        assertThat(range.toColumn - range.fromColumn).isEqualTo(1)
    }

    @Test
    fun `a selection copied out of the buffer matches what contains highlighted`() {
        // The end-to-end version of the off-by-one check, against the real emulator.
        val buffer = AnsiTerminalBuffer()
        buffer.resize(20, 4)
        buffer.feed("/usr/local/bin")
        val selection = TerminalSelection(0, 5, 0, 9)
        val highlighted = (0..13).filter { selection.contains(0, it) }
        val range = selection.toTextRange()
        val copied = buffer.textIn(range.fromLine, range.fromColumn, range.toLine, range.toColumn)
        assertThat(copied).isEqualTo("local")
        assertThat(highlighted).hasSize(copied.length)
    }

    @Test
    fun `wordAt selects a path whole rather than one segment of it`() {
        val text = "cd /usr/local/bin && ls"
        val selection = TerminalSelection.wordAt(line = 7, column = 8, text = text)
        assertThat(selection).isNotNull()
        val range = selection!!.toTextRange()
        assertThat(text.substring(range.fromColumn, range.toColumn)).isEqualTo("/usr/local/bin")
        assertThat(selection.anchorLine).isEqualTo(7)
        assertThat(selection.focusLine).isEqualTo(7)
    }

    @Test
    fun `wordAt selects an ip address whole`() {
        // Selecting 192 out of an address is never what was wanted.
        val text = "ping 192.168.1.10"
        val selection = TerminalSelection.wordAt(0, 7, text)!!
        val range = selection.toTextRange()
        assertThat(text.substring(range.fromColumn, range.toColumn)).isEqualTo("192.168.1.10")
    }

    @Test
    fun `wordAt selects the things people actually copy out of a terminal`() {
        val cases = mapOf(
            "user@host.example.com" to "user@host.example.com",
            "process-name_v2" to "process-name_v2",
            "~/.ssh/id_ed25519" to "~/.ssh/id_ed25519",
            "sha256:9f86d081" to "sha256:9f86d081",
            "100%" to "100%",
            "a+b" to "a+b",
            "\$HOME" to "\$HOME",
            "PATH=/bin" to "PATH=/bin",
            "issue#42" to "issue#42",
        )
        cases.forEach { (text, expected) ->
            val selection = TerminalSelection.wordAt(0, text.length / 2, text)
            assertThat(selection).isNotNull()
            val range = selection!!.toTextRange()
            assertThat(text.substring(range.fromColumn, range.toColumn)).isEqualTo(expected)
        }
    }

    @Test
    fun `wordAt stops at a space and at a quote`() {
        val text = "echo 'hello world'"
        val selection = TerminalSelection.wordAt(0, 7, text)!!
        val range = selection.toTextRange()
        assertThat(text.substring(range.fromColumn, range.toColumn)).isEqualTo("hello")
    }

    @Test
    fun `wordAt returns null on a blank cell so a long press there does not copy a space`() {
        // Columns 2 and 3 are the two spaces in "cd  /tmp"; column 4 is the '/' that starts a word.
        assertThat(TerminalSelection.wordAt(0, 2, "cd  /tmp")).isNull()
        assertThat(TerminalSelection.wordAt(0, 3, "cd  /tmp")).isNull()
        assertThat(TerminalSelection.wordAt(0, 4, "cd  /tmp")).isNotNull()
    }

    @Test
    fun `wordAt returns null outside the line rather than throwing`() {
        // A long press lands wherever the finger did, including past the end of a short line.
        assertThat(TerminalSelection.wordAt(0, 99, "short")).isNull()
        assertThat(TerminalSelection.wordAt(0, -1, "short")).isNull()
        assertThat(TerminalSelection.wordAt(0, 0, "")).isNull()
    }

    @Test
    fun `wordAt on a single character word selects just it`() {
        val selection = TerminalSelection.wordAt(0, 0, "x y")!!
        assertThat(selection.isSingleCell).isTrue()
        assertThat(selection.toTextRange().toColumn).isEqualTo(1)
    }

    @Test
    fun `wordAt at the very start and very end of a line still terminates`() {
        val text = "abc"
        assertThat(TerminalSelection.wordAt(0, 0, text)!!.toTextRange().fromColumn).isEqualTo(0)
        assertThat(TerminalSelection.wordAt(0, 2, text)!!.toTextRange().toColumn).isEqualTo(3)
    }

    @Test
    fun `wholeLine covers every column of a row`() {
        val selection = TerminalSelection.wholeLine(line = 9, columns = 80)
        assertThat(selection.startColumn).isEqualTo(0)
        assertThat(selection.endColumn).isEqualTo(79)
        assertThat(selection.contains(9, 79)).isTrue()
        assertThat(selection.contains(9, 80)).isFalse()
        assertThat(selection.toTextRange().toColumn).isEqualTo(80)
    }

    @Test
    fun `wholeLine on a zero width row does not produce a negative column`() {
        // Reachable during the first composition, before the view has been measured.
        val selection = TerminalSelection.wholeLine(0, columns = 0)
        assertThat(selection.endColumn).isEqualTo(0)
    }

    @Test
    fun `a selection is a value so compose can diff a drag`() {
        assertThat(TerminalSelection.at(1, 1).movedTo(2, 2))
            .isEqualTo(TerminalSelection(1, 1, 2, 2))
        assertThat(TerminalSelection.at(1, 1)).isNotEqualTo(TerminalSelection.at(1, 2))
    }
}
