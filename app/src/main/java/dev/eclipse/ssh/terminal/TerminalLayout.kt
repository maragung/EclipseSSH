package dev.eclipse.ssh.terminal

/**
 * One drawable row of the view: the columns `[from, to)` of [line] within a [TerminalFrame]'s window.
 *
 * [line] is an index into [TerminalFrame.lines], not an absolute buffer line, because that is what the
 * renderer holds; a caller that needs the absolute one adds [TerminalFrame.firstLine], exactly as it
 * already does for a selection.
 */
data class TerminalVisualRow(val line: Int, val from: Int, val to: Int) {
    /** How many columns this row paints - the number the pan extent is measured in. */
    val width: Int get() = to - from
}

/**
 * Where every drawable row of a frame goes, once lines too wide for the window have been wrapped.
 *
 * The emulator is deliberately not involved. [AnsiTerminalBuffer] keeps hard-wrapping at the pty width,
 * so `contentColumns`, selection coordinates, resize and every existing emulator test keep their
 * meaning; this is a display layer that decides which columns of which line land on which row of the
 * screen, and nothing else. That separation is what makes the whole feature a pure function of a frame
 * and a width, testable without a Canvas, and reversible: drop it and the view pans as before.
 *
 * [rows] is bottom-anchored when wrapping produces more rows than fit. The bottom of a terminal is
 * where the cursor and the newest output are, so a screen that has to lose rows loses the oldest ones,
 * which are one flick of the scrollback away.
 */
class TerminalLayout(
    val rows: List<TerminalVisualRow>,
    /**
     * The widest row here, and therefore how far the view may pan.
     *
     * Usually the window width or less - that is the point of wrapping - and larger only where a single
     * token was too wide to break. Replaces [TerminalFrame.contentColumns] at the view, because after
     * wrapping the frame's number describes a line that is no longer drawn as one row.
     */
    val contentColumns: Int,
    /** Index into [rows] of the row holding the cursor, or -1 when the cursor is not on screen. */
    val cursorRow: Int,
    /** The cursor's column *within* its row, which is not its column within the line once wrapped. */
    val cursorColumn: Int,
) {
    companion object {
        val EMPTY = TerminalLayout(rows = emptyList(), contentColumns = 0, cursorRow = -1, cursorColumn = 0)
    }
}

/**
 * Lays [frame] out for a window [width] columns wide and [maxRows] rows tall.
 *
 * Wrapping is on for output that flowed and off for a screen that was painted, and that is the whole
 * rule - there is no setting, because there is no answer that suits both. Flowed output is a stream of
 * sentences and paths that the reader wants whole: a URL, a sha256 or a `/very/long/path` printed at 80
 * columns on a phone that fits 46 is currently reachable only by dragging the text sideways, one screen
 * at a time, which is the complaint this exists to answer. A painted screen is the opposite: `htop`,
 * `vim`, `nano`, `less` and `top` address every cell positionally and draw their own borders, status
 * lines and columns to the width they were told they had, so wrapping row 3 into two rows shifts
 * everything below it and the display is simply wrong. There the existing horizontal pan is right.
 *
 * Two flags say "painted", because one of them was not enough. [TerminalFrame.alternateScreen] catches
 * the four programs that switch screens, and it was originally the whole condition here; probing the
 * real binaries through a real pty showed that `top` is not one of them - procps repaints the *primary*
 * screen in place - so the emulator now also reports [TerminalFrame.positionalScreen], and the program
 * this file's own documentation used as its example of the alternate screen is the reason it exists.
 *
 * A token wider than the window is never broken. It goes on a row of its own, over-wide, and stays
 * reachable by panning - because breaking it is exactly the damage being avoided: a hostname, a hash, a
 * JSON blob or a numeric column split across rows reads as two different values, and a reader has no way
 * to tell that break from one the server sent.
 */
fun terminalLayout(frame: TerminalFrame, width: Int, maxRows: Int): TerminalLayout {
    if (frame.lines.isEmpty() || maxRows <= 0) return TerminalLayout.EMPTY
    val wrap = width > 0 && !frame.alternateScreen && !frame.positionalScreen
    val rows = ArrayList<TerminalVisualRow>(frame.lines.size)
    frame.lines.forEachIndexed { index, line ->
        val extent = paintedExtent(frame, index, line)
        if (!wrap || extent <= width) {
            rows += TerminalVisualRow(index, 0, extent)
        } else {
            var start = 0
            while (start < extent) {
                val end = wrapBreak(line, start, extent, width)
                rows += TerminalVisualRow(index, start, end)
                start = end
            }
        }
    }
    val shown = if (rows.size <= maxRows) rows else rows.subList(rows.size - maxRows, rows.size)
    var cursorRow = -1
    var cursorColumn = 0
    if (frame.cursorVisible && frame.cursorRow >= 0) {
        // The last row that starts at or before the cursor's column, so a cursor sitting one past the
        // end of the text - where it is during every keystroke of a command - lands on the row holding
        // that text rather than nowhere.
        val index = shown.indexOfLast { it.line == frame.cursorRow && frame.cursorColumn >= it.from }
        if (index >= 0) {
            cursorRow = index
            cursorColumn = frame.cursorColumn - shown[index].from
        }
    }
    var content = 0
    for (row in shown) if (row.width > content) content = row.width
    // The cell being typed into is blank until the character lands, and the view has to be able to
    // follow the cursor there - the same reason [TerminalFrame.contentColumns] counts it.
    if (cursorRow >= 0) content = maxOf(content, cursorColumn + 1)
    return TerminalLayout(
        rows = if (shown === rows) shown else shown.toList(),
        contentColumns = content,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
    )
}

/**
 * How many columns of `frame.lines[index]` this layout has to place.
 *
 * The painted width, plus the cursor's own cell when the cursor is on this line: trailing blanks are
 * not laid out at all, so a 200-column line with forty characters on it is one row and not five.
 * Bounded by the line's own length, because a frame captured across a resize can report a cursor
 * column the line does not have.
 */
private fun paintedExtent(frame: TerminalFrame, index: Int, line: List<TerminalCell>): Int {
    val painted = terminalPaintedWidth(line)
    val withCursor = if (index == frame.cursorRow && frame.cursorVisible) {
        maxOf(painted, frame.cursorColumn + 1)
    } else {
        painted
    }
    return withCursor.coerceAtMost(line.size)
}

/**
 * The exclusive end of the row that starts at [start], never splitting a word and never leaving a gap.
 *
 * Greedy from the right: the row is filled to the margin if the margin happens to fall between words,
 * and otherwise walks back to the last place a break does not land inside one. Walking back rather than
 * forward is what keeps the wrap at or before the margin, so a wrapped row never needs panning.
 *
 * Consecutive rows are contiguous - one row's [to] is the next row's `from` - so every column of the
 * line is drawn exactly once. Spaces at a break stay at the end of the row they belong to, and nothing
 * is swallowed: the server's indentation is the only indentation on screen, which is what keeps a
 * wrapped table or a `tree` listing readable.
 */
private fun wrapBreak(line: List<TerminalCell>, start: Int, extent: Int, width: Int): Int {
    if (extent - start <= width) return extent
    // The first column that does not fit, and therefore the first candidate: breaking here fills the row.
    val limit = start + width
    if (!splitsWord(line, limit)) return limit
    var candidate = limit - 1
    while (candidate > start) {
        if (!splitsWord(line, candidate)) return candidate
        candidate--
    }
    // Every break from here to the margin would land inside one token, so the token is wider than the
    // window: give it a row of its own, whole, and let the pan reach the rest of it.
    var end = limit + 1
    while (end < extent && splitsWord(line, end)) end++
    return end
}

/**
 * Whether a break before column [at] would land inside a word.
 *
 * Deliberately the same notion of "word" that long-press-to-select uses - see
 * [TerminalSelection.isWordCharacter] - so a token that selects as one unit also wraps as one unit. A
 * user who long-presses a path and gets the whole path, then sees that path split across two rows,
 * has been told two different things about the same characters.
 */
private fun splitsWord(line: List<TerminalCell>, at: Int): Boolean =
    TerminalSelection.isWordCharacter(line[at - 1].value) && TerminalSelection.isWordCharacter(line[at].value)
