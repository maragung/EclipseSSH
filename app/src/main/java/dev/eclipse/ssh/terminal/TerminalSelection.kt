package dev.eclipse.ssh.terminal

/**
 * A rectangle-free, line-ordered cell selection: everything from one cell to another, the way a
 * terminal selects rather than the way a spreadsheet does.
 *
 * Addressed in absolute buffer lines, not screen rows, for the same reason [AnsiTerminalBuffer.textIn]
 * is: output keeps arriving while the user is dragging. A selection stored as "row 3 of the screen"
 * slides onto different text every time the shell prints a line, so the highlight the user is looking
 * at and the text they eventually copy would be two different things. Absolute lines are stable until
 * the scrollback evicts them, which is the only case where a selection genuinely has stopped existing.
 *
 * Both ends are *inclusive*, so [contains] is what the renderer highlights and [toTextRange] is what
 * the clipboard gets. Keeping one inclusive model and converting once, rather than storing a half-open
 * range and asking the renderer to compensate, is what stops the highlight from being one cell wider or
 * narrower than the text - a discrepancy that is invisible in a screenshot and obvious the first time
 * someone copies a path and loses its last character.
 *
 * Immutable: a drag produces a new value per movement, which is what lets Compose diff it.
 */
data class TerminalSelection(
    /** Where the drag started. May be after [focusLine]/[focusColumn] - dragging upward is normal. */
    val anchorLine: Int,
    val anchorColumn: Int,
    /** Where the finger or pointer is now. */
    val focusLine: Int,
    val focusColumn: Int,
) {
    private val forward: Boolean =
        anchorLine < focusLine || (anchorLine == focusLine && anchorColumn <= focusColumn)

    val startLine: Int get() = if (forward) anchorLine else focusLine
    val startColumn: Int get() = if (forward) anchorColumn else focusColumn
    val endLine: Int get() = if (forward) focusLine else anchorLine
    val endColumn: Int get() = if (forward) focusColumn else anchorColumn

    /** A selection of a single cell. Still worth copying - one character is a legitimate thing to want. */
    val isSingleCell: Boolean get() = startLine == endLine && startColumn == endColumn

    /** Whether ([line], [column]) is inside the selection, for drawing the highlight. */
    fun contains(line: Int, column: Int): Boolean {
        if (line < startLine || line > endLine) return false
        if (line == startLine && column < startColumn) return false
        if (line == endLine && column > endColumn) return false
        return true
    }

    /** The first selected column on [line], or -1 if [line] has none. Lets a renderer skip whole rows. */
    fun firstColumnOn(line: Int): Int = when {
        line < startLine || line > endLine -> -1
        line == startLine -> startColumn
        else -> 0
    }

    /** The last selected column on [line] given its width, or -1 if [line] has none. */
    fun lastColumnOn(line: Int, columns: Int): Int = when {
        line < startLine || line > endLine -> -1
        line == endLine -> minOf(endColumn, columns - 1)
        else -> columns - 1
    }

    /** The same selection with its focus moved, which is what a drag does. */
    fun movedTo(line: Int, column: Int): TerminalSelection = copy(focusLine = line, focusColumn = column)

    /**
     * The half-open range [AnsiTerminalBuffer.textIn] expects: the end column becomes exclusive.
     *
     * Returned as four values rather than as a call into the buffer so this class stays free of it -
     * the selection is a piece of UI state and has no business holding a reference to the emulator.
     */
    fun toTextRange(): TextRange = TextRange(startLine, startColumn, endLine, endColumn + 1)

    data class TextRange(val fromLine: Int, val fromColumn: Int, val toLine: Int, val toColumn: Int)

    companion object {
        /** A selection of exactly the cell at ([line], [column]) - where every drag begins. */
        fun at(line: Int, column: Int): TerminalSelection = TerminalSelection(line, column, line, column)

        /**
         * The selection covering the word under [column] in [text], or null if that cell is blank.
         *
         * [text] is one character per cell - [terminalCellText] is what makes it so - because [column]
         * is a column: a line the user reads has a cell carrying a combining mark contributing its
         * whole cluster, and every character after such a cell would then sit one place to the left of
         * the cell that was tapped.
         *
         * Long-press-to-select-word is not a convenience on a phone, it is the only usable way in.
         * A finger covers roughly three cells at a readable font size, so asking someone to place a
         * character-precise selection boundary by touch does not work; what they actually want almost
         * every time is a path, a hostname, a PID or a hash, and all four are single words. Dragging
         * from a word is then a refinement rather than the whole interaction.
         *
         * "Word" is deliberately wider than a shell's idea of one. It includes the characters that
         * appear inside the things people copy out of a terminal - `/` and `.` and `-` and `_` and `~`
         * and `:` - because selecting `usr` out of `/usr/local/bin` is never what was wanted, and
         * selecting `192` out of an IP address even less so.
         */
        fun wordAt(line: Int, column: Int, text: String): TerminalSelection? {
            if (column < 0 || column >= text.length) return null
            if (!isWordCharacter(text[column])) return null
            var start = column
            while (start > 0 && isWordCharacter(text[start - 1])) start--
            var end = column
            while (end + 1 < text.length && isWordCharacter(text[end + 1])) end++
            return TerminalSelection(line, start, line, end)
        }

        /** The selection covering all of [line], given a row [columns] wide. */
        fun wholeLine(line: Int, columns: Int): TerminalSelection =
            TerminalSelection(line, 0, line, (columns - 1).coerceAtLeast(0))

        /**
         * What counts as part of one word, here and in [terminalLayout].
         *
         * Shared rather than duplicated: wrapping a line and selecting a token have to agree on where a
         * token ends, or the display contradicts itself - a path the user long-presses and gets whole,
         * broken across two rows by the wrap.
         */
        internal fun isWordCharacter(char: Char): Boolean =
            char.isLetterOrDigit() || char in WORD_PUNCTUATION

        private const val WORD_PUNCTUATION = "/._-~:+@=%#$"
    }
}
