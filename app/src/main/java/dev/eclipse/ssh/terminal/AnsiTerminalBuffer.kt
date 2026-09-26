package dev.eclipse.ssh.terminal

/**
 * ESC, built from its code point rather than written as a literal.
 *
 * A raw 0x1B in a source file is invisible in every diff and every review, and this file is full of
 * places one could hide in. Naming it once is what keeps the sequences below readable, and the same
 * reasoning is why the C0 bytes further down are matched by code rather than by character literal.
 */
private val ESC: Char = Char(0x1B)

private const val MAX_SCROLLBACK = 2_000
private const val DEFAULT_COLUMNS = 120
private const val DEFAULT_ROWS = 40

/**
 * How many bytes an escape sequence may accumulate before it is treated as malformed.
 *
 * Generous next to anything real - a CSI sequence is a handful of bytes and the longest OSC an app
 * like this sees is a window title - and the point is only to make "never terminated" a bounded
 * condition rather than an unbounded one.
 */
private const val MAX_CONTROL_LENGTH = 256

/** Where the default tab stops sit, on this terminal and on every other one since the VT100. */
private const val TAB_WIDTH = 8

// The C0 control bytes this terminal acts on.
private const val C0_NUL = 0x00
private const val C0_BEL = 0x07
private const val C0_BS = 0x08
private const val C0_HT = 0x09
private const val C0_LF = 0x0A
private const val C0_VT = 0x0B
private const val C0_FF = 0x0C
private const val C0_CR = 0x0D
private const val C0_SO = 0x0E
private const val C0_SI = 0x0F
private const val C0_ESC = 0x1B

/**
 * The window sizes a terminal may be set to, shared with [dev.eclipse.ssh.ssh.TerminalChannel].
 *
 * Two independent clamps used to sit at the two ends of the same resize: the pty accepted up to
 * 1000x500 and this buffer up to 400x200, so any request in between was sent to the server and
 * silently refused locally. The remote shell then wrapped its output at a width the display did not
 * have, and every line past the buffer's last column folded into the next row - the whole screen
 * sheared, with nothing to say why. Reachable today on a large external display at the smallest font
 * size, and reachable by any future caller that trusts one clamp without knowing about the other.
 *
 * The display is the tighter constraint, because a grid of cells is real memory and the pty is only
 * two integers, so the display's limits are the shared ones. That way the server is never told about
 * a window that cannot be drawn.
 *
 * The rows reach 1000 because an app-wide height is a *floor* the view scrolls through rather than a
 * ceiling the screen cuts down - a value the screen overruled on every device would be a setting that
 * never did anything, which is why the choices used to stop at 60. What that costs is bounded here
 * and nowhere else: at this range's corner (1000 rows by 400 columns) the grid is the largest thing
 * this class will ever hold, some tens of megabytes of cells for a terminal the user asked to be that
 * tall. Every other height is proportional to it, and the default is still "fit the screen".
 */
val TERMINAL_COLUMN_RANGE = 20..400
val TERMINAL_ROW_RANGE = 5..1000

data class TerminalColor(val value: Int = COLOR_DEFAULT) {
    val isDefault: Boolean get() = value == COLOR_DEFAULT
    val isTrueColor: Boolean get() = value and TRUE_COLOR_MASK == TRUE_COLOR_MASK
    val rgb: Int get() = value and RGB_MASK

    companion object {
        const val COLOR_DEFAULT = -1
        const val TRUE_COLOR_MASK = 0x01000000
        const val RGB_MASK = 0x00FFFFFF
    }
}

data class TerminalStyle(
    val foreground: TerminalColor = TerminalColor(),
    val background: TerminalColor = TerminalColor(),
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    /** SGR 8. Drawn as blanks: the whole point of conceal is that the text is not readable. */
    val hidden: Boolean = false,
)

/**
 * One cell of the grid: the character it shows, the style it shows it in, and whatever zero-width
 * characters belong on top of it.
 *
 * [value] is the cell's base and is always exactly one `Char`, so a line is an array of characters
 * that can be indexed by a column. That is what the whole emulator is built on, and it is why the
 * width table's other arm is not acted on yet - see [terminalCharWidth]. [combining] holds the code
 * points that take no column of their own and belong to the character before them: an `e` followed by
 * U+0301 is one cell in two code points, and the zero-width joiner in a composed emoji is another.
 * They are stored here rather than in cells of their own because a cell *is* a column, and the program
 * on the far side of the pty does not count them as one - which is the bug this field exists to fix.
 *
 * Everything that draws or copies a cell reads [value] plus [combining]. Everything that addresses a
 * cell by column reads [value] alone and needs no part of [combining], because a mark always sits on
 * a base: the base is what decides whether a cell is a word character or a painted one, so those two
 * tests are unchanged by a mark, and no cell ever holds a mark without a base under it.
 */
data class TerminalCell(
    val value: Char = ' ',
    val style: TerminalStyle = TerminalStyle(),
    val combining: String = "",
)

data class TerminalSnapshot(
    val lines: List<List<TerminalCell>>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val columns: Int,
    val rows: Int,
)

/**
 * One drawable window onto the buffer: exactly the lines the view can show, and nothing else.
 *
 * [TerminalSnapshot] copies the entire scrollback - 2 000 lines by 120 columns is a quarter of a
 * million cells - and the renderer asked for one on every frame of a live session. A window of
 * `rows` lines is two orders of magnitude cheaper and is all a screen can display; the rest of the
 * scrollback only matters when the user scrolls back into it, which moves [firstLine] rather than
 * copying anything extra.
 *
 * [revision] is the cheap way to know whether anything changed at all. It advances on every mutation
 * of the buffer, so a recomposition can skip redrawing without comparing several thousand cells.
 *
 * "Every mutation" includes a cursor that only moved, and that is not a detail: the renderer memoizes
 * the layout it draws the cursor box from on this value, so a move that does not advance it is a move
 * the user cannot see. The left arrow is the case that proves it - its byte is `^H`, a bare control
 * character rather than a CSI, and it goes through [setCursorColumn] for exactly this reason. Every
 * path that changes the cursor, the style or a cell reports it here, including the ones a caller also
 * bumps for; a second increment costs nothing because only "did it change" is ever asked.
 */
data class TerminalFrame(
    val lines: List<List<TerminalCell>>,
    /** Index into the whole buffer of `lines[0]`, so a selection can be addressed absolutely. */
    val firstLine: Int,
    val totalLines: Int,
    /** Row of the cursor within [lines], or -1 when the user has scrolled it out of view. */
    val cursorRow: Int,
    val cursorColumn: Int,
    val columns: Int,
    val rows: Int,
    /**
     * The widest column any line in this window actually paints into, and how far the view may pan.
     *
     * Not the same number as [columns], in either direction. It is *smaller* for an ordinary shell
     * screen, where nothing reaches the right margin and panning into that emptiness would only be a
     * way to lose the text; it is *larger* whenever the history was printed at a wider terminal than
     * the one on display now - after a rotation, or on a phone whose screen fits fewer columns than
     * the pty was given - because those lines keep every character they were printed with. Counted
     * the way the renderer counts a painted cell, so a coloured blank in a status bar extends it and
     * the trailing spaces of a short line do not, plus the cursor's own cell: the cell being typed
     * into is blank until the character lands, and the view has to be able to follow it there.
     */
    val contentColumns: Int = 0,
    val cursorVisible: Boolean,
    val revision: Long,
    val title: String? = null,
    val alternateScreen: Boolean = false,
    /**
     * Whether this frame is a screen a program painted by address on the *primary* screen.
     *
     * `top` is why this exists rather than [alternateScreen] being the whole test; the reasoning is on
     * `AnsiTerminalBuffer`'s own flag of the same name. Like [alternateScreen] it tells the display that
     * the geometry carries the meaning and that re-wrapping would destroy it.
     */
    val positionalScreen: Boolean = false,
    /** DECCKM. Decides whether an arrow key sends `ESC [ A` or `ESC O A`; see [TerminalKeys]. */
    val applicationCursorKeys: Boolean = false,
    /** DECKPAM. Some full-screen apps expect the keypad to send SS3 sequences. */
    val applicationKeypad: Boolean = false,
    /** `ESC [ ? 2004 h`. A paste is bracketed when the remote asked for it, and never otherwise. */
    val bracketedPaste: Boolean = false,
) {
    companion object {
        val EMPTY = TerminalFrame(
            lines = emptyList(),
            firstLine = 0,
            totalLines = 0,
            cursorRow = -1,
            cursorColumn = 0,
            columns = DEFAULT_COLUMNS,
            rows = DEFAULT_ROWS,
            cursorVisible = false,
            revision = 0L,
        )
    }
}

/**
 * Small, allocation-conscious terminal emulator core. It intentionally keeps
 * terminal state outside Compose so output can continue while the UI is paused.
 *
 * The model is one flat list of lines whose *tail* is the screen: `lines.size - rows` is the top of
 * the visible area and everything above it is scrollback. That is what lets the scrollback exist at
 * all without a second data structure, and it is why every screen-relative coordinate in here is
 * computed from [screenTop] rather than stored.
 *
 * The alternate screen (`ESC [ ? 1049 h`) swaps that whole list for a fresh one of exactly `rows`
 * lines and puts the primary aside. It is what makes `vim`, `less`, `htop` and every other
 * full-screen program leave the shell's scrollback exactly as they found it - without it their
 * redraws were painted into the history, and a user who quit `htop` was left scrolling through a
 * few hundred half-overwritten copies of its screen instead of the commands they had run.
 */
class AnsiTerminalBuffer(
    private var columns: Int = DEFAULT_COLUMNS,
    private var rows: Int = DEFAULT_ROWS,
    private val scrollbackLimit: Int = MAX_SCROLLBACK,
) {
    private var lines = ArrayList<MutableList<TerminalCell>>()
    private var cursorRow = 0
    private var cursorColumn = 0

    /**
     * The cursor has printed in the last column and the *next* printable character wraps first.
     *
     * Deferred wrapping itself is not new - it used to be expressed by letting `cursorColumn` reach
     * `columns`, one past the last real cell. Holding it in a flag instead is what makes the column
     * always a valid index: erase, insert and delete no longer have to coerce a possibly-out-of-range
     * cursor before touching a line, and the renderer can put a caret on a cell that exists. It is
     * also the only way DECAWM becomes expressible - with the old marker there was nowhere to record
     * that autowrap was *off*, so `ESC [ ? 7 l` had to be ignored, and a program that turned wrapping
     * off to draw a full-width status bar had its last column pushed onto the next row.
     */
    private var wrapPending = false

    /**
     * Whether the program on the other end is painting this screen by address instead of streaming it.
     *
     * The alternate screen was supposed to be the whole answer to "may this frame be re-wrapped", and
     * it is not, because `top` does not use it: procps homes the cursor with `ESC [ H` and rewrites the
     * screen in place on the primary one, exactly eleven newlines on a twelve-row screen so that it
     * never scrolls. A frame like that is a grid whose meaning is its geometry - the load averages are
     * on row one because row one is where the program put them - so re-wrapping row three into two rows
     * pushes everything below it down and the reader is looking at a screen the program never drew. On
     * a phone this is not hypothetical: the pty is eighty columns wide by default, whatever the screen
     * shows, and the view fits about half that - so every one of `top`'s rows would have reflowed.
     *
     * Set by an upward cursor move that a sequence asked for, which is the tell: output that flows only
     * ever goes down. Cleared when the screen scrolls under flowing output, which is the opposite tell
     * and is how the flag lets go - `top`'s own exit does it.
     *
     * And cleared by `ESC [ 3 J`, which is what makes `clear` not a false positive. Measured on this
     * machine at 80x12: `clear` writes `ESC[H ESC[2J ESC[3J`, and `top`'s startup writes `ESC[H ESC[2J`
     * and nothing else of the kind - one ED2, never an ED3, in a whole run. So the erase cannot tell
     * the two apart and the home is common to both, but dropping the scrollback separates them exactly,
     * and it is the honest signal: a program that means to keep repainting a screen does not throw the
     * history away first. Without this the screenful after every `clear` was laid out unwrapped, which
     * is what 1.0.6 did to every line and the thing this feature exists to stop.
     */
    private var positionalScreen = false

    private var savedRow = 0
    private var savedColumn = 0
    private var savedStyle = TerminalStyle()
    private var savedGraphics = false
    private var style = TerminalStyle()
    private var escapeState = EscapeState.NORMAL
    private val controlBuffer = StringBuilder()

    /** The character [repeatLastCharacter] repeats, which is what CSI b means by "last". */
    private var lastPrinted: Char? = null

    /** Scroll region, as rows relative to the top of the screen; the full screen unless DECSTBM says otherwise. */
    private var topMargin = 0
    private var bottomMargin = rows - 1

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var cursorVisible = true
    private var applicationCursorKeys = false
    private var applicationKeypad = false
    private var bracketedPaste = false
    private var title: String? = null

    /** DEC special graphics selected into G0/G1, and which of the two SI/SO has shifted in. */
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftedOut = false

    private var tabStops = BooleanArray(0)

    /** Set while the alternate screen is up; holds everything the primary screen needs back. */
    private var primary: PrimaryScreen? = null

    private var revision = 0L

    /**
     * Replies the remote asked for (device attributes, cursor position) waiting to be written back.
     *
     * Queued rather than sent from inside the parser: [responder] writes to the SSH channel, which
     * can block, and doing that while holding this object's monitor would stall every other thread
     * that wants to feed or render the buffer - including the one draining the channel that the
     * write is waiting on.
     */
    private val pendingReplies = ArrayList<String>()

    /**
     * Where answers to device-status queries go, normally the pty's input.
     *
     * A terminal that never answers `ESC [ 6 n` is a terminal that hangs its own line editor:
     * `bash` with `checkwinsize`, and readline on several configurations, ask for the cursor position
     * at startup and wait for the report. Answering is not optional politeness.
     */
    @Volatile
    var responder: ((String) -> Unit)? = null

    init {
        repeat(rows) { lines += blankLine() }
        resetTabStops()
    }

    @Synchronized
    fun resize(newColumns: Int, newRows: Int) {
        val previousRows = rows
        val wasFullRegion = topMargin == 0 && bottomMargin == previousRows - 1
        columns = newColumns.coerceIn(TERMINAL_COLUMN_RANGE)
        rows = newRows.coerceIn(TERMINAL_ROW_RANGE)
        while (lines.size < rows) lines += blankLine()
        trimBlankRowsForShrink(lines, cursorRow)
        normalizeScreen(lines)
        // A region that covered the whole screen still covers it at the new height. One that did not
        // is clamped instead, so a shrink cannot leave a margin pointing past the last row - every
        // scroll after that would have indexed outside the list.
        if (wasFullRegion) {
            topMargin = 0
            bottomMargin = rows - 1
        } else {
            topMargin = topMargin.coerceIn(0, rows - 1)
            bottomMargin = bottomMargin.coerceIn(topMargin, rows - 1)
        }
        // The primary screen is resized too, not just the one on display. Rotating the device while
        // `vim` is open otherwise dropped the user back onto a shell screen still laid out for the
        // old width, with every wrapped line folded at the wrong column.
        primary?.let { saved ->
            while (saved.lines.size < rows) saved.lines += blankLine()
            trimBlankRowsForShrink(saved.lines, saved.cursorRow)
            normalizeScreen(saved.lines)
        }
        cursorRow = cursorRow.coerceIn(0, lines.lastIndex)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        wrapPending = false
        resetTabStops()
        revision++
    }

    /**
     * Feeds decoded output in, then hands any replies the stream asked for to [responder].
     *
     * Split in two on purpose: the parsing happens under this object's monitor and the replying does
     * not. See [pendingReplies].
     */
    fun feed(value: String) {
        val replies = feedLocked(value)
        if (replies.isEmpty()) return
        val sink = responder ?: return
        replies.forEach { reply -> sink(reply) }
    }

    @Synchronized
    private fun feedLocked(value: String): List<String> {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            // A variation selector from the supplement block (U+E0100-U+E01EF) is astral *and*
            // zero-width, so it has to reach [putCodePoint] as one character or it spends two cells on
            // a character the far side counts as none - the exact defect this walk exists for. Only a
            // pair the width table calls zero-width is joined: an astral character of any other width
            // already occupies the two cells the far side counts two columns for, so pairing it here
            // would change how the printer has always stored it without moving anything.
            //
            // Joined only in the normal state, too. The escape states are an ASCII grammar in which a
            // surrogate is noise, and pairing one there would swallow the byte after it.
            if (escapeState == EscapeState.NORMAL && Character.isHighSurrogate(char) &&
                index + 1 < value.length && Character.isLowSurrogate(value[index + 1])
            ) {
                val pair = Character.toCodePoint(char, value[index + 1])
                if (terminalCharWidth(pair) == 0) {
                    // Straight to the printer rather than through [consume]. In the normal state the
                    // only thing `consume` would do with it is hand it to the printer, and the printer
                    // is the one place that can take a character that is two `Char`s long.
                    putCodePoint(pair)
                    index += 2
                    continue
                }
            }
            consume(char)
            index++
        }
        if (pendingReplies.isEmpty()) return emptyList()
        val replies = pendingReplies.toList()
        pendingReplies.clear()
        return replies
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(
        lines = lines.map { it.toList() },
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
        columns = columns,
        rows = rows,
    )

    /**
     * The window the view can actually draw: [viewportRows] lines ending at the cursor, [scrollOffset]
     * lines above it.
     *
     * The cursor is the anchor rather than the bottom of the buffer, and the two are the same thing
     * whenever the cursor is on the last line - which it is in every session whose pty is exactly as
     * tall as the buffer. They part company precisely when the pty is *taller*, which is the setting
     * this exists for: with a 1000-row pty and 200 lines of output the cursor sits on row 200 while
     * the last line of the buffer is row 999, so a window measured from the bottom is 800 rows of
     * blank screen with the prompt somewhere far above it. Anchoring on the cursor keeps what the
     * shell is writing on the screen, and the blank rows below the cursor are never drawn at all.
     *
     * An offset of zero is "following the output", which is the state a terminal is in unless the
     * user has deliberately scrolled back. The offset walks *up* from that anchor, and it is measured
     * the same way by [maxScrollOffset] so a scrolled-back view holds the text it was showing while
     * output arrives below: the caller adds the growth back, which cancels the anchor's own movement
     * exactly as it used to cancel the buffer's. Offsets past the top are clamped rather than
     * rejected, because the buffer shrinks under the view whenever the scrollback limit evicts a line.
     */
    @Synchronized
    fun frame(scrollOffset: Int = 0, viewportRows: Int = rows): TerminalFrame {
        val take = viewportRows.coerceIn(1, lines.size)
        // Never above the cursor, and never so near the start that the window would begin before the
        // buffer does - the lower bound is what stops a cursor on row 3 of a tall screen from pulling
        // the window up past the first line the terminal ever wrote.
        val anchor = (cursorRow + 1).coerceIn(take, lines.size)
        val offset = scrollOffset.coerceIn(0, anchor - take)
        val first = anchor - take - offset
        val window = ArrayList<List<TerminalCell>>(take)
        var content = 0
        for (index in first until first + take) {
            val line = lines[index]
            window += line.toList()
            val painted = terminalPaintedWidth(line)
            if (painted > content) content = painted
        }
        val relativeCursor = cursorRow - first
        if (cursorVisible && relativeCursor in 0 until take) content = maxOf(content, cursorColumn + 1)
        return TerminalFrame(
            lines = window,
            firstLine = first,
            totalLines = lines.size,
            cursorRow = if (relativeCursor in 0 until take) relativeCursor else -1,
            cursorColumn = cursorColumn.coerceIn(0, columns - 1),
            columns = columns,
            rows = rows,
            contentColumns = content,
            cursorVisible = cursorVisible,
            revision = revision,
            title = title,
            alternateScreen = primary != null,
            positionalScreen = positionalScreen,
            applicationCursorKeys = applicationCursorKeys,
            applicationKeypad = applicationKeypad,
            bracketedPaste = bracketedPaste,
        )
    }

    /**
     * Gives a shrinking viewport its rows back from the bottom, where they are blank, instead of
     * taking them off the top, where the output is.
     *
     * The screen is the *tail* of [lines] - `lines.size - rows` is its first row - so lowering [rows]
     * on its own moves the top of the screen *down* and turns everything above it into scrollback.
     * That is right for a full screen and completely wrong for an empty one, which is the state every
     * session starts in: the shell prints a banner and a prompt on the first two rows, and the very
     * next thing that happens is the view measuring itself and resizing from the default forty rows to
     * however many the device actually fits. Re-anchoring to the last thirty-odd lines put the only two
     * lines that had anything on them into the history, so a freshly connected terminal showed a blank
     * grid with its cursor off-screen, and stayed that way until enough output arrived to fill the
     * window. It looked exactly like a session that had connected and hung.
     *
     * A terminal shrinks by discarding blank rows from the bottom and only scrolls the top away once
     * the screen is genuinely full. Two things are therefore never removed: a row with anything on it,
     * because output below the cursor is still output, and the cursor's own row, because a shell that
     * has just been given a newline is about to print its prompt there.
     */
    private fun trimBlankRowsForShrink(target: ArrayList<MutableList<TerminalCell>>, cursorRow: Int) {
        while (target.size > rows && target.lastIndex > cursorRow && isBlank(target[target.lastIndex])) {
            target.removeAt(target.lastIndex)
        }
    }

    private fun isBlank(line: List<TerminalCell>): Boolean = line.all { it == BLANK_CELL }

    /**
     * The viewport height the buffer is sized to, without building a frame to find out.
     *
     * Exists because reading it off [frame] was costing a whole viewport copy per call, and the two
     * callers that need it — pinning a scrolled-back view as output arrives, and clamping a stale
     * offset after a resize — run on the terminal's hot path at frame rate. `rows` itself stays
     * private: it is mutable state that only [resize] may change.
     */
    val viewportRows: Int
        @Synchronized get() = rows

    /**
     * How far back the view may scroll while showing [viewportRows] lines.
     *
     * Measured to the cursor rather than to the end of the buffer, because [frame] anchors there: the
     * distance that matters is from the cursor up to the first line of scrollback, and measuring to
     * the buffer's last line instead would hand the view a range of offsets whose upper half draws
     * exactly the same screen. A tall pty with a little output is where that shows - 960 offsets that
     * all clamp to the top of the buffer is a scroll gesture that stops responding.
     */
    @Synchronized
    fun maxScrollOffset(viewportRows: Int): Int {
        val take = viewportRows.coerceIn(1, lines.size)
        return (cursorRow + 1).coerceIn(take, lines.size) - take
    }

    @Synchronized
    fun lineCount(): Int = lines.size

    /**
     * The line [frame] anchors its window at: the cursor's own line, or the last line there is.
     *
     * The frame's window is `[cursorLine - viewportRows - offset, cursorLine - offset)`, so this is the
     * single number that decides where the window sits, and moving it by one moves the window by one.
     * That is why the caller that keeps a scrolled-back view still ([MainViewModel.pinScrollback]) reads
     * it before and after feeding output and gives the difference back: the line count is the same
     * number in a session whose pty is exactly as tall as the buffer, and a different one in a session
     * whose pty is taller - where output moves this down the screen without adding a line at all.
     *
     * Synchronized and allocation-free for the same reason [viewportRows] is: it is read twice per
     * collector iteration, on the terminal's hot path.
     */
    val cursorLine: Int
        @Synchronized get() = (cursorRow + 1).coerceIn(1, lines.size)

    /** Whether a paste has to be wrapped in `ESC [ 200 ~` and `ESC [ 201 ~`. */
    @Synchronized
    fun bracketedPasteEnabled(): Boolean = bracketedPaste

    /**
     * DECCKM, for deciding what an arrow key sends. See [TerminalFrame.applicationCursorKeys].
     *
     * A direct read for the same reason as [viewportRows]: the caller is a keystroke, and taking this
     * off [frame] copied the whole viewport to look at one boolean.
     */
    @Synchronized
    fun applicationCursorKeysEnabled(): Boolean = applicationCursorKeys

    /**
     * The text of the cell range ([fromLine], [fromColumn]) up to but excluding ([toLine],
     * [toColumn]) - what "copy" puts on the clipboard.
     *
     * Addressed in absolute buffer lines so a selection survives new output arriving underneath it:
     * the numbers come from [TerminalFrame.firstLine], not from a screen row.
     *
     * Trimmed the same way [plainText] trims: each line loses its right padding, and blank lines at
     * the *end* of the selection are dropped while blank lines *inside* it are kept. Both halves of
     * that matter. A terminal row is a fixed-width array of cells, so every line the shell wrote is
     * followed by spaces out to the margin - copying those turns a two-word command into a
     * twenty-character one, and pasting it somewhere that cares (a diff, a YAML file, another shell)
     * carries the padding along. Blank rows at the end are the same problem one dimension up: a drag
     * on a phone is an imprecise gesture, the rows below the last line of output look identical to
     * the rows above it, and a selection that overshoots by a few rows should not silently append
     * newlines to what the user copied. Interior blanks are real content - a program that prints a
     * paragraph break meant it - so those stay.
     *
     * Agreeing with [plainText] is itself the point: the same region copied to the clipboard and
     * saved to a file has to come out the same, or the user has to learn which of the two to trust.
     */
    @Synchronized
    fun textIn(fromLine: Int, fromColumn: Int, toLine: Int, toColumn: Int): String {
        if (lines.isEmpty()) return ""
        val startLine = fromLine.coerceIn(0, lines.lastIndex)
        val endLine = toLine.coerceIn(0, lines.lastIndex)
        if (startLine > endLine) return ""
        val out = StringBuilder()
        for (index in startLine..endLine) {
            val line = lines[index]
            val from = if (index == startLine) fromColumn.coerceIn(0, line.size) else 0
            val to = if (index == endLine) toColumn.coerceIn(0, line.size) else line.size
            if (index > startLine) out.append('\n')
            val lineStart = out.length
            for (column in from until to) {
                val cell = line[column]
                out.append(cell.value)
                // A mark is part of the character the user selected, not a cell of its own: an accent
                // dropped here is an accent dropped from what they paste.
                out.append(cell.combining)
            }
            var lineEnd = out.length
            while (lineEnd > lineStart && out[lineEnd - 1] == ' ') lineEnd--
            out.setLength(lineEnd)
        }
        // Each line is already right-trimmed, so a trailing blank row is now exactly one '\n'.
        var end = out.length
        while (end > 0 && out[end - 1] == '\n') end--
        out.setLength(end)
        return out.toString()
    }

    /**
     * The whole buffer as text, each line right-trimmed and trailing blank lines dropped.
     *
     * Written against a StringBuilder rather than as the two nested `joinToString`s it used to be.
     * That version allocated a `String` per *cell* - `Char.toString()` - plus one per line, so a full
     * 2 000-line scrollback cost around a quarter of a million short-lived objects every time it was
     * called, and it was called once per rendered frame. `plainText matches the straightforward
     * rendering` pins the result against the original expression so the two cannot drift.
     */
    @Synchronized
    fun plainText(): String {
        val out = StringBuilder(lines.size * (columns + 1))
        lines.forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            // Right-trim this line only, never back past the separator: the same scope
            // joinToString gave each line's own trimEnd().
            val lineStart = out.length
            line.forEach { cell ->
                out.append(cell.value)
                // The same cluster [textIn] writes, for the same reason: this is the text a person
                // reads, and the reason the two have to agree is spelled out above.
                out.append(cell.combining)
            }
            var lineEnd = out.length
            while (lineEnd > lineStart && out[lineEnd - 1].isWhitespace()) lineEnd--
            out.setLength(lineEnd)
        }
        // The outer trimEnd(), which is what removes the run of newlines left by blank lines at the
        // bottom of an otherwise empty screen.
        var end = out.length
        while (end > 0 && out[end - 1].isWhitespace()) end--
        out.setLength(end)
        return out.toString()
    }

    private fun consume(char: Char) {
        when (escapeState) {
            EscapeState.NORMAL -> when (char.code) {
                C0_ESC -> escapeState = EscapeState.ESC
                // VT and FF move down a line exactly as LF does on every terminal in practice.
                C0_LF, C0_VT, C0_FF -> lineFeed()
                // CR and BS are the two cursor moves that arrive as bare control bytes rather than as
                // a CSI, and they go through [setCursorColumn] so they advance the revision like every
                // other move. BS in particular is what a left arrow costs: readline's `cub1` is `^H`
                // while its `cuf1` is `ESC [ C`, so this branch is the only thing standing between a
                // left arrow and a cursor that visibly moves. Setting the column directly here left the
                // revision untouched, the frame was drawn from a layout memoized on that revision, and
                // the cursor sat still until the next character was typed.
                C0_CR -> setCursorColumn(0)
                C0_BS -> setCursorColumn(cursorColumn - 1)
                C0_HT -> tabForward(1)
                // A bell has no display; padding NULs must not print as blanks over real output.
                C0_NUL, C0_BEL -> Unit
                // SO / SI, which shift the alternate character set in and out. `ncurses` draws every
                // box and every menu border through these, and ignoring them printed the raw letters:
                // `lqqqk` across the top of a dialog instead of a line.
                C0_SO -> { shiftedOut = true; revision++ }
                C0_SI -> { shiftedOut = false; revision++ }
                else -> if (!char.isISOControl()) put(char)
            }
            EscapeState.ESC -> when (char) {
                '[' -> { controlBuffer.clear(); escapeState = EscapeState.CSI }
                ']' -> { controlBuffer.clear(); escapeState = EscapeState.OSC }
                // DCS / SOS / PM / APC: payloads this terminal has no use for, skipped as a unit so
                // that what follows them is still printed instead of being taken for text.
                'P', 'X', '^', '_' -> { controlBuffer.clear(); escapeState = EscapeState.STRING }
                '(' -> escapeState = EscapeState.CHARSET_G0
                ')' -> escapeState = EscapeState.CHARSET_G1
                // SS3, the three-byte single-shift sequences. `ESC O` had no case at all, so its final
                // byte fell through to the printer: a program that sends `ESC O D` for cursor-left put
                // a literal `D` on screen instead of moving anything. See [consumeSs3].
                'O' -> escapeState = EscapeState.SS3
                '7' -> { saveCursor(); escapeState = EscapeState.NORMAL }
                '8' -> { restoreCursor(); escapeState = EscapeState.NORMAL }
                'c' -> reset()
                'D' -> { index(); escapeState = EscapeState.NORMAL }
                'E' -> { lineFeed(); escapeState = EscapeState.NORMAL }
                'M' -> { reverseIndex(); escapeState = EscapeState.NORMAL }
                'H' -> { setTabStop(cursorColumn); escapeState = EscapeState.NORMAL }
                '=' -> { applicationKeypad = true; escapeState = EscapeState.NORMAL }
                '>' -> { applicationKeypad = false; escapeState = EscapeState.NORMAL }
                else -> escapeState = EscapeState.NORMAL
            }
            EscapeState.CHARSET_G0 -> {
                g0Graphics = char == '0'
                escapeState = EscapeState.NORMAL
                revision++
            }
            EscapeState.CHARSET_G1 -> {
                g1Graphics = char == '0'
                escapeState = EscapeState.NORMAL
                revision++
            }
            EscapeState.SS3 -> {
                consumeSs3(char)
                escapeState = EscapeState.NORMAL
            }
            EscapeState.CSI -> {
                controlBuffer.append(char)
                if (char in '@'..'~') {
                    executeCsi(controlBuffer.toString())
                    controlBuffer.clear()
                    escapeState = EscapeState.NORMAL
                } else if (controlBuffer.length > MAX_CONTROL_LENGTH) {
                    // A CSI sequence only ends at a byte in '@'..'~', so digits and ';' accumulate
                    // until one arrives. Nothing bounded that: a server stuck mid-sequence, or a
                    // corrupted stream, could grow this StringBuilder until the process died of
                    // OutOfMemoryError. A real sequence is a handful of bytes, so abandoning one this
                    // long discards only input that was never going to parse.
                    abandonControlSequence()
                }
            }
            EscapeState.OSC -> when {
                char.code == C0_BEL -> { finishOsc(); escapeState = EscapeState.NORMAL }
                // ST, i.e. ESC backslash, arrives as the ESC state failing to recognise the backslash.
                char.code == C0_ESC -> { finishOsc(); escapeState = EscapeState.ESC }
                // An OSC that never terminates used to swallow every byte after it for the life of
                // the session. One stray "ESC ]" in a corrupted stream blanked the terminal
                // permanently; now the run gives up and output resumes.
                controlBuffer.length > MAX_CONTROL_LENGTH -> abandonControlSequence()
                else -> controlBuffer.append(char)
            }
            EscapeState.STRING -> when {
                char.code == C0_ESC -> { controlBuffer.clear(); escapeState = EscapeState.ESC }
                char.code == C0_BEL -> { controlBuffer.clear(); escapeState = EscapeState.NORMAL }
                controlBuffer.length > MAX_CONTROL_LENGTH -> abandonControlSequence()
                else -> controlBuffer.append(char)
            }
        }
    }

    /**
     * SS3: `ESC O` and exactly one final byte, the cursor, keypad and function keys of a VT220.
     *
     * Deliberately a copy of the handful of [executeCsi] meanings rather than a translation into a CSI
     * string. SS3 carries no parameters at all, so every one of these moves by exactly one, and
     * feeding them through the parameter parser would mean inventing a `1` for a sequence that has no
     * syntax to hold one. Anything not listed here is consumed and dropped, which is the whole point:
     * before this existed `ESC O` fell out of the ESC state and its final byte was printed as text.
     */
    private fun consumeSs3(char: Char) {
        when (char) {
            'A' -> moveCursorRow(cursorRow - 1)
            'B' -> moveCursorRow(cursorRow + 1)
            'C' -> setCursorColumn(cursorColumn + 1)
            'D' -> setCursorColumn(cursorColumn - 1)
            'H' -> setCursorColumn(0)
            'F' -> setCursorColumn(columns - 1)
            else -> Unit
        }
    }

    private fun executeCsi(command: String) {
        if (command.isEmpty()) return
        val final = command.last()
        val raw = command.dropLast(1)
        // '?' marks a DEC private mode, '>' a secondary request, '!' and '$' the soft-reset and
        // request-mode families. Kept as the leading byte rather than stripped blindly, because the
        // answer to CSI c depends on which of them introduced it.
        val prefix = raw.firstOrNull()?.takeIf { it == '?' || it == '>' || it == '!' || it == '$' }
        val body = if (prefix == null) raw else raw.substring(1)
        // Clamped at zero, and that is a crash fix rather than tidiness. ECMA-48 parameter bytes are
        // 0x30..0x3F, so "-" is not part of a number at all - but it still reaches here inside the
        // body, because a CSI sequence ends only at a byte in '@'..'~' and "-" is not one. A negative
        // parameter then reversed every relative cursor move: `ESC [ -5 A` is "up by minus five", and
        // a one-sided lower bound happily left cursorRow five rows past the last line. The next
        // printable character indexed `lines` out of bounds, and that throw surfaces inside the
        // terminal output collector - a plain `launch`, so it reached the thread's default handler and
        // killed the process. One malformed sequence from a server was enough to crash the app.
        val params = body.takeIf { it.isNotEmpty() }
            ?.split(';')
            ?.map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
            ?: emptyList()
        val first = params.firstOrNull()?.takeIf { it != 0 } ?: 1
        val private = prefix == '?'
        revision++
        when (final) {
            'A' -> { moveCursorRow(cursorRow - first); wrapPending = false }
            'B', 'e' -> { moveCursorRow(cursorRow + first); wrapPending = false }
            'C', 'a' -> setCursorColumn(cursorColumn + first)
            'D' -> setCursorColumn(cursorColumn - first)
            'E' -> { moveCursorRow(cursorRow + first); setCursorColumn(0) }
            'F' -> { moveCursorRow(cursorRow - first); setCursorColumn(0) }
            'G', '`' -> setCursorColumn((params.firstOrNull() ?: 1) - 1)
            'd' -> addressScreenRow((params.firstOrNull() ?: 1) - 1)
            'H', 'f' -> {
                addressScreenRow((params.getOrNull(0) ?: 1) - 1)
                setCursorColumn((params.getOrNull(1) ?: 1) - 1)
            }
            'I' -> tabForward(first)
            'Z' -> tabBackward(first)
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'X' -> eraseChars(first)
            // Repeat counts are clamped to what the buffer can hold. Past that every extra iteration
            // is a no-op on the result but not on the clock: `ESC [ 999999999 P` spun a billion list
            // splices while holding this object's monitor, which stalled the session's output for
            // minutes and looked exactly like a dead connection.
            'P' -> repeat(first.coerceAtMost(columns)) { deleteAtCursor() }
            '@' -> repeat(first.coerceAtMost(columns)) { insertAtCursor() }
            'L' -> insertLines(first.coerceAtMost(rows))
            'M' -> deleteLines(first.coerceAtMost(rows))
            'S' -> repeat(first.coerceAtMost(rows)) { scrollRegionUp() }
            'T' -> repeat(first.coerceAtMost(rows)) { scrollRegionDown() }
            'b' -> repeatLastCharacter(first.coerceAtMost(columns))
            'm' -> applySgr(params.ifEmpty { listOf(0) })
            'r' -> setScrollRegion(params)
            'g' -> clearTabStop(params.firstOrNull() ?: 0)
            'c' -> when (prefix) {
                // Secondary DA: "a VT220, firmware 95". Primary DA: "a VT100 with AVO", which is what
                // xterm answers and what every terminfo entry for xterm-256color expects.
                '>' -> queueReply(csi(">0;95;0c"))
                null -> queueReply(csi("?1;2c"))
                else -> Unit
            }
            'n' -> if (!private) deviceStatus(params.firstOrNull() ?: 0)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> setMode(params, private, true)
            'l' -> setMode(params, private, false)
            // DECSTR, the soft reset. `reset` is stronger than the standard asks for, and that is the
            // safer direction: the sequence is sent by programs recovering from a confused terminal.
            'p' -> if (prefix == '!') reset()
        }
    }

    /**
     * DEC private modes, and the one ANSI mode this terminal implements.
     *
     * These used to be ignored wholesale, with a comment calling them "stateful UI concerns". They
     * are not: 1049 is what stops `vim` painting itself into the scrollback, 7 is what makes a long
     * line wrap, 25 is whether there is a cursor to draw, 2004 is whether a paste may contain a
     * newline without executing it, and 1 decides which bytes an arrow key has to send. Ignoring them
     * is most of what made the terminal a command form rather than a terminal.
     */
    private fun setMode(params: List<Int>, private: Boolean, enable: Boolean) {
        params.ifEmpty { listOf(0) }.forEach { mode ->
            if (!private) {
                // IRM. The only ANSI mode with a visible effect here; LNM is a line-discipline
                // concern that belongs to the pty, not to the display.
                if (mode == 4) insertMode = enable
                return@forEach
            }
            when (mode) {
                1 -> applicationCursorKeys = enable
                6 -> {
                    originMode = enable
                    // DECOM homes the cursor: to the margin when it is on, to the screen when off.
                    setScreenRow(0)
                    setCursorColumn(0)
                }
                7 -> autoWrap = enable
                25 -> cursorVisible = enable
                2004 -> bracketedPaste = enable
                // 1047 and 1048 are the two halves that 1049 does together; all three are accepted so
                // an app using the older pair is not left drawing onto the primary screen.
                1047, 1049 ->
                    if (enable) enterAlternateScreen(withCursor = mode == 1049)
                    else leaveAlternateScreen(withCursor = mode == 1049)
                1048 -> if (enable) saveCursor() else restoreCursor()
                47 -> if (enable) enterAlternateScreen(withCursor = false) else leaveAlternateScreen(withCursor = false)
            }
        }
    }

    private fun enterAlternateScreen(withCursor: Boolean) {
        if (primary != null) return
        if (withCursor) saveCursor()
        primary = PrimaryScreen(
            lines = lines,
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
        )
        lines = ArrayList(rows)
        repeat(rows) { lines += blankLine() }
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        positionalScreen = false
        topMargin = 0
        bottomMargin = rows - 1
    }

    private fun leaveAlternateScreen(withCursor: Boolean) {
        val saved = primary ?: return
        primary = null
        lines = saved.lines
        // The screen may have been resized while the alternate one was up; resize() keeps the stored
        // lines in step, but a fresh height still has to be topped up here.
        while (lines.size < rows) lines += blankLine()
        trimBlankRowsForShrink(lines, saved.cursorRow)
        normalizeScreen(lines)
        topMargin = saved.topMargin.coerceIn(0, rows - 1)
        bottomMargin = saved.bottomMargin.coerceIn(topMargin, rows - 1)
        cursorRow = saved.cursorRow.coerceIn(0, lines.lastIndex)
        cursorColumn = saved.cursorColumn.coerceIn(0, columns - 1)
        wrapPending = false
        positionalScreen = false
        if (withCursor) restoreCursor()
    }

    private fun setScrollRegion(params: List<Int>) {
        val top = ((params.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
        val bottom = ((params.getOrNull(1)?.takeIf { it != 0 } ?: rows) - 1).coerceIn(0, rows - 1)
        // A region needs at least two rows to scroll; anything else resets to the full screen, which
        // is what xterm does rather than accepting an inverted or degenerate pair.
        if (top >= bottom) {
            topMargin = 0
            bottomMargin = rows - 1
        } else {
            topMargin = top
            bottomMargin = bottom
        }
        setScreenRow(0)
        setCursorColumn(0)
    }

    private fun deviceStatus(request: Int) {
        when (request) {
            5 -> queueReply(csi("0n"))
            // Reported relative to the scroll region when origin mode is on, exactly as it is set.
            6 -> {
                val row = cursorRow - screenTop() - if (originMode) topMargin else 0
                queueReply(csi("${row + 1};${cursorColumn + 1}R"))
            }
        }
    }

    private fun queueReply(reply: String) {
        // Bounded for the same reason the control buffer is: a remote that asks for status faster
        // than the channel drains must not be able to grow this without limit.
        if (pendingReplies.size < MAX_PENDING_REPLIES) pendingReplies += reply
    }

    private fun finishOsc() {
        // OSC 0 and 2 set the window title, which the session tab shows. Everything else - colour
        // queries, hyperlinks, and OSC 52 in particular - is discarded rather than acted on: 52 lets
        // a remote host write the *local* clipboard, which is not a capability this app is willing to
        // hand to a server it has just connected to.
        val payload = controlBuffer.toString()
        controlBuffer.clear()
        val separator = payload.indexOf(';')
        if (separator <= 0) return
        val code = payload.substring(0, separator).toIntOrNull() ?: return
        if (code != 0 && code != 2) return
        title = payload.substring(separator + 1).take(MAX_TITLE_LENGTH).takeIf { it.isNotBlank() }
        revision++
    }

    private fun applySgr(params: List<Int>) {
        var index = 0
        var next = style
        while (index < params.size) {
            when (val code = params[index]) {
                0 -> next = TerminalStyle()
                1 -> next = next.copy(bold = true)
                2 -> next = next.copy(dim = true)
                3 -> next = next.copy(italic = true)
                4 -> next = next.copy(underline = true)
                // 5 and 6 are blink, recorded as nothing on purpose: a blinking cell would have to
                // invalidate the view twice a second for as long as it is on screen, which on a
                // low-end device is a measurable battery cost for an effect most terminals now
                // render as bold anyway.
                7 -> next = next.copy(inverse = true)
                8 -> next = next.copy(hidden = true)
                9 -> next = next.copy(strikethrough = true)
                21, 22 -> next = next.copy(bold = false, dim = false)
                23 -> next = next.copy(italic = false)
                24 -> next = next.copy(underline = false)
                27 -> next = next.copy(inverse = false)
                28 -> next = next.copy(hidden = false)
                29 -> next = next.copy(strikethrough = false)
                39 -> next = next.copy(foreground = TerminalColor())
                49 -> next = next.copy(background = TerminalColor())
                in 30..37, in 90..97 -> next = next.copy(foreground = TerminalColor(ansiColor(code)))
                in 40..47, in 100..107 -> next = next.copy(background = TerminalColor(ansiColor(code - 10)))
                38, 48 -> {
                    val isForeground = code == 38
                    when (params.getOrNull(index + 1)) {
                        5 -> params.getOrNull(index + 2)?.let { color ->
                            val terminalColor = TerminalColor(ansi256(color))
                            next = if (isForeground) next.copy(foreground = terminalColor) else next.copy(background = terminalColor)
                            index += 2
                        }
                        2 -> if (index + 4 < params.size) {
                            val rgb = (params[index + 2].coerceIn(0, 255) shl 16) or
                                (params[index + 3].coerceIn(0, 255) shl 8) or params[index + 4].coerceIn(0, 255)
                            val terminalColor = TerminalColor(TerminalColor.TRUE_COLOR_MASK or rgb)
                            next = if (isForeground) next.copy(foreground = terminalColor) else next.copy(background = terminalColor)
                            index += 4
                        }
                    }
                }
            }
            index++
        }
        style = next
    }

    /** Builds a CSI reply. Exists so no string in this file has to embed a control byte. */
    private fun csi(body: String): String = "$ESC[$body"

    /**
     * The two ways a cursor position is assigned, clamped on both ends.
     *
     * Every call site used to clamp for itself, in the one direction its own sequence moved in, and a
     * parameter that reversed that direction slipped past the missing end. `cursorRow` is an array
     * index into [lines] and the cost of getting it wrong is an exception, not a mis-drawn cell, so
     * the bound belongs here rather than in eleven separate expressions.
     */
    private fun setCursorRow(row: Int) {
        cursorRow = row.coerceIn(0, lines.lastIndex)
        revision++
    }

    /**
     * A row move a sequence asked for, as opposed to one the output caused.
     *
     * Only the sequences a program uses to place its cursor go through here and through
     * [addressScreenRow]; DECOM, DECSTBM and DECRC call [setCursorRow]/[setScreenRow] directly, because
     * those move the cursor as a side effect of something else and a shell drawing a two-line prompt
     * with `ESC 7`/`ESC 8` is not a program painting a screen.
     */
    private fun moveCursorRow(row: Int) {
        val from = cursorRow
        setCursorRow(row)
        if (cursorRow < from) positionalScreen = true
    }

    /** [setScreenRow], recording that a program placed its cursor there. See [moveCursorRow]. */
    private fun addressScreenRow(row: Int) {
        val from = cursorRow
        setScreenRow(row)
        if (cursorRow < from) positionalScreen = true
    }

    private fun setCursorColumn(column: Int) {
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
        revision++
    }

    /** Absolute addressing: [row] counts from the top of the screen, or of the margin under DECOM. */
    private fun setScreenRow(row: Int) {
        val top = screenTop()
        val base = top + if (originMode) topMargin else 0
        val limit = top + if (originMode) bottomMargin else rows - 1
        cursorRow = (base + row).coerceIn(top.coerceAtMost(limit), limit)
        wrapPending = false
        revision++
    }

    /** The first line of the visible screen; everything before it is scrollback. */
    private fun screenTop(): Int = (lines.size - rows).coerceAtLeast(0)

    private fun saveCursor() {
        savedRow = cursorRow - screenTop()
        savedColumn = cursorColumn
        savedStyle = style
        savedGraphics = if (shiftedOut) g1Graphics else g0Graphics
    }

    private fun restoreCursor() {
        setScreenRow(savedRow.coerceIn(0, rows - 1))
        setCursorColumn(savedColumn)
        style = savedStyle
        if (shiftedOut) g1Graphics = savedGraphics else g0Graphics = savedGraphics
        // Its own bump, not just the ones the two moves above now carry: a program that saves, prints
        // nothing and restores has changed only the style, and a restore onto the cell the cursor was
        // already on would otherwise draw in the old style.
        revision++
    }

    /** Drops an escape sequence that has run too long to be real, and resumes printing. */
    private fun abandonControlSequence() {
        controlBuffer.clear()
        escapeState = EscapeState.NORMAL
    }

    private fun put(char: Char) = putCodePoint(char.code)

    /**
     * Prints one character - one code point, which may be a surrogate pair - at the cursor.
     *
     * The zero-width arm comes first, and it is first for two separate reasons.
     *
     * It is the fix. A character the far side counts as no column must spend no cell here either, and
     * advancing for it is what puts this buffer's cursor one cell to the right of the program's. The
     * cursor column is what every later erase, move and cursor-position reply is measured from, so a
     * program whose `ESC [ K` starts one cell too far right leaves the cells to the left of it
     * standing - characters the user can still see after the program believes it erased them. The
     * character belongs on the cell before it instead.
     *
     * And it has to come *before* the pending wrap. A mark that arrives while a wrap is armed belongs
     * to the character that armed it, which is the cell the cursor is still standing on - the wrap is
     * exactly the state in which the cursor did not advance past it. Letting the mark consume the wrap
     * would put the *next* real character on the row below and break the line where the program did
     * not break it.
     */
    private fun putCodePoint(codePoint: Int) {
        if (terminalCharWidth(codePoint) == 0) {
            attachCombining(codePoint)
            return
        }
        // An astral character the width table does not call zero-width is written as the two code units
        // it is made of, one per cell. That is what this printer has always done with them, and it is
        // what keeps a composed emoji drawing as a single glyph: the renderer joins the cells of a
        // style run before it measures them, so the two halves are shaped together even though they
        // are stored apart. Two cells is also the right count for the emoji the far side counts two
        // columns for. A *narrow* astral character - a mathematical alphanumeric, say - still gets two
        // where the far side counts one, which is a defect this change neither fixes nor worsens.
        if (codePoint > Char.MAX_VALUE.code) {
            val pair = Character.toChars(codePoint)
            putUnit(pair[0])
            putUnit(pair[1])
            return
        }
        putUnit(codePoint.toChar())
    }

    /**
     * Writes one cell - one `Char`, one column - and moves the cursor past it.
     *
     * The single-character path, and the only one that consults the DEC graphics set: a character that
     * took a cell before [terminalCharWidth] existed still takes it in exactly the same way.
     */
    private fun putUnit(char: Char) {
        if (wrapPending) {
            if (autoWrap) {
                cursorColumn = 0
                lineFeed()
            } else {
                cursorColumn = columns - 1
            }
            wrapPending = false
        }
        val glyph = translate(char)
        val line = lines[cursorRow]
        if (insertMode) {
            line.add(cursorColumn, TerminalCell(glyph, style))
            while (line.size > columns) line.removeAt(line.lastIndex)
        } else {
            line[cursorColumn] = TerminalCell(glyph, style)
        }
        lastPrinted = glyph
        if (cursorColumn >= columns - 1) wrapPending = true else cursorColumn++
        revision++
    }

    /**
     * Puts a zero-width character on the cell it belongs to, without moving the cursor.
     *
     * "The cell it belongs to" is the one the last printed character landed on. That is the cell under
     * the cursor while a wrap is armed, and the one before it otherwise: [putUnit] leaves the cursor
     * where it is when a character fills the last column, so a mark arriving after that has to look at
     * the cursor's own column rather than behind it.
     *
     * A mark with nothing under it is dropped rather than carried somewhere it does not belong. At
     * column zero there is no cell to its left at all, and a cell holding a blank is one the far side
     * counted as a space rather than as a base for it. This buffer also keeps no "last cell written"
     * that could reach back to the previous row, and inventing one would put the accent on whatever
     * happened to be there. Dropping it costs nothing in the column arithmetic, which is the point of
     * the change: a zero-width character moves the cursor on neither side of the pty.
     *
     * Deliberately does not touch [lastPrinted]. `CSI b` repeats "the last character printed", and
     * repeating an accent instead of the character under it would be a new way to be wrong.
     */
    private fun attachCombining(codePoint: Int) {
        val column = if (wrapPending) cursorColumn else cursorColumn - 1
        val line = lines[cursorRow]
        if (column !in line.indices) return
        val cell = line[column]
        if (cell.value == ' ') return
        line[column] = cell.copy(combining = cell.combining + String(Character.toChars(codePoint)))
        revision++
    }

    /** Maps a printable byte through the DEC special graphics set when one is shifted in. */
    private fun translate(char: Char): Char {
        val graphics = if (shiftedOut) g1Graphics else g0Graphics
        if (!graphics || char < '_' || char > '~') return char
        return Char(DEC_SPECIAL_GRAPHICS[char - '_'])
    }

    private fun repeatLastCharacter(count: Int) {
        val char = lastPrinted ?: return
        repeat(count) { put(char) }
    }

    private fun tabForward(count: Int) {
        repeat(count.coerceIn(1, columns)) {
            var column = cursorColumn + 1
            while (column < columns - 1 && !tabStops.getOrElse(column) { false }) column++
            // Stops at the right margin rather than one past it, which is where a tab stops on a
            // real terminal. A tab must also never be what sets the pending-wrap flag: the next
            // printable character would then open a new row, breaking a line where the shell did not.
            cursorColumn = column.coerceAtMost(columns - 1)
        }
        wrapPending = false
        revision++
    }

    private fun tabBackward(count: Int) {
        repeat(count.coerceIn(1, columns)) {
            var column = cursorColumn - 1
            while (column > 0 && !tabStops.getOrElse(column) { false }) column--
            cursorColumn = column.coerceAtLeast(0)
        }
        wrapPending = false
        revision++
    }

    private fun resetTabStops() {
        tabStops = BooleanArray(columns) { it % TAB_WIDTH == 0 && it != 0 }
    }

    private fun setTabStop(column: Int) {
        if (column in tabStops.indices) tabStops[column] = true
    }

    private fun clearTabStop(mode: Int) {
        when (mode) {
            3 -> tabStops.fill(false)
            else -> if (cursorColumn in tabStops.indices) tabStops[cursorColumn] = false
        }
    }

    /** IND: down one row inside the scroll region, scrolling at its bottom, column unchanged. */
    private fun index() {
        val bottom = screenTop() + bottomMargin
        if (cursorRow >= bottom) {
            // Output has reached the bottom and pushed the screen up, which is a stream behaving like
            // one: whatever was painted by address is now history and the next line may be wrapped.
            positionalScreen = false
            scrollRegionUp()
        } else if (cursorRow < lines.lastIndex) {
            cursorRow++
        }
        wrapPending = false
        revision++
    }

    /**
     * A newline as it arrives from a pty, which is to say NEL: down one row *and* back to column zero.
     *
     * Strictly, LF alone should leave the column where it was and the CR in "\r\n" should be what
     * resets it. This is deliberately the forgiving reading, because a pty runs with ONLCR on and
     * therefore always sends the pair - so the CR is redundant here rather than load-bearing - while
     * a server that sends a bare LF and expects a full newline is common enough that the strict
     * reading produces the classic staircase, each line starting where the last one ended. [index]
     * is the strict form, and ESC D is the sequence that means it.
     */
    private fun lineFeed() {
        index()
        cursorColumn = 0
    }

    private fun reverseIndex() {
        val top = screenTop() + topMargin
        if (cursorRow <= top) scrollRegionDown() else cursorRow--
        wrapPending = false
        revision++
    }

    /**
     * Scrolls the active region up by one line.
     *
     * When the region is the whole screen and the primary buffer is up, the line leaving the top
     * becomes scrollback - that is the only path that ever grows the history. Inside a margin, or on
     * the alternate screen, the displaced line is discarded instead, which is what a scroll region
     * means and what keeps a full-screen app's redraws out of the user's history.
     */
    private fun scrollRegionUp() {
        val fullScreen = topMargin == 0 && bottomMargin == rows - 1
        if (fullScreen && primary == null) {
            lines += blankLine()
            trimScrollback()
            cursorRow = lines.lastIndex
        } else {
            val top = screenTop() + topMargin
            val bottom = screenTop() + bottomMargin
            lines.removeAt(top)
            lines.add(bottom, blankLine())
        }
        revision++
    }

    private fun scrollRegionDown() {
        val top = screenTop() + topMargin
        val bottom = screenTop() + bottomMargin
        lines.removeAt(bottom)
        lines.add(top, blankLine())
        revision++
    }

    /**
     * Evicts the oldest scrollback, keeping every index that pointed into it pointing at the same
     * text - [cursorRow] above all, which is an index into [lines] and not a screen row.
     */
    private fun trimScrollback() {
        // No scrollback at all while the alternate screen is up: it is a fixed window that a program
        // redraws in place, and anything pushed off its top is a fragment of a frame, not history.
        val limit = rows + if (primary == null) scrollbackLimit else 0
        while (lines.size > limit) {
            lines.removeAt(0)
            cursorRow = (cursorRow - 1).coerceAtLeast(0)
        }
    }

    private fun eraseDisplay(mode: Int) {
        val top = screenTop()
        when (mode) {
            // Only the screen, never the scrollback: `clear` at a shell prompt must not throw away
            // the history the user scrolled back to read.
            2 -> for (row in top until lines.size) lines[row].fill(TerminalCell())
            0 -> { eraseLine(0); for (row in cursorRow + 1 until lines.size) lines[row].fill(TerminalCell()) }
            1 -> { eraseLine(1); for (row in top until cursorRow) lines[row].fill(TerminalCell()) }
            // `ESC [ 3 J` is the explicit "and drop the scrollback too" that `clear` sends on a modern
            // system, and the only sequence allowed to discard history. It also releases
            // [positionalScreen]: throwing the history away is a statement that nothing on the way out
            // needs preserving, which is the opposite of a program repainting a screen it means to keep.
            3 -> {
                while (lines.size > rows) { lines.removeAt(0); cursorRow = (cursorRow - 1).coerceAtLeast(0) }
                positionalScreen = false
            }
        }
        wrapPending = false
        revision++
    }

    private fun eraseLine(mode: Int) {
        val line = lines[cursorRow]
        when (mode) {
            2 -> line.fill(TerminalCell())
            1 -> for (i in 0..cursorColumn.coerceAtMost(columns - 1)) line[i] = TerminalCell()
            else -> for (i in cursorColumn.coerceIn(0, columns - 1) until columns) line[i] = TerminalCell()
        }
        wrapPending = false
        revision++
    }

    /** ECH: blank [count] cells from the cursor without moving it or shifting the rest of the line. */
    private fun eraseChars(count: Int) {
        val line = lines[cursorRow]
        val end = (cursorColumn + count).coerceAtMost(columns)
        for (i in cursorColumn until end) line[i] = TerminalCell()
        wrapPending = false
        revision++
    }

    private fun insertAtCursor() {
        lines[cursorRow].add(cursorColumn.coerceIn(0, columns - 1), TerminalCell())
        normalizeLine(lines[cursorRow])
    }

    private fun deleteAtCursor() {
        if (cursorColumn >= columns) return
        lines[cursorRow].removeAt(cursorColumn)
        lines[cursorRow] += TerminalCell()
    }

    /**
     * IL / DL, which move lines *within the scroll region* and leave the buffer exactly as long as it
     * was.
     *
     * The old pair grew and shrank `lines` instead, so `ESC [ L` inside a full-screen program pushed a
     * line into the scrollback that the program then overwrote somewhere else - the history filled
     * with fragments of a redraw, and the screen below the insertion point drifted by a row each time.
     */
    private fun insertLines(count: Int) {
        val top = screenTop() + topMargin
        val bottom = screenTop() + bottomMargin
        if (cursorRow < top || cursorRow > bottom) return
        repeat(count) {
            lines.removeAt(bottom)
            lines.add(cursorRow, blankLine())
        }
    }

    private fun deleteLines(count: Int) {
        val top = screenTop() + topMargin
        val bottom = screenTop() + bottomMargin
        if (cursorRow < top || cursorRow > bottom) return
        repeat(count) {
            lines.removeAt(cursorRow)
            lines.add(bottom, blankLine())
        }
    }

    private fun reset() {
        primary = null
        lines = ArrayList(rows)
        repeat(rows) { lines += blankLine() }
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        positionalScreen = false
        style = TerminalStyle()
        savedStyle = TerminalStyle()
        savedRow = 0
        savedColumn = 0
        savedGraphics = false
        escapeState = EscapeState.NORMAL
        controlBuffer.clear()
        topMargin = 0
        bottomMargin = rows - 1
        autoWrap = true
        originMode = false
        insertMode = false
        cursorVisible = true
        applicationCursorKeys = false
        applicationKeypad = false
        bracketedPaste = false
        g0Graphics = false
        g1Graphics = false
        shiftedOut = false
        title = null
        lastPrinted = null
        resetTabStops()
        revision++
    }

    private fun blankLine() = MutableList(columns) { TerminalCell() }

    /**
     * Fits the *screen* to the current width, and deliberately does not touch the scrollback.
     *
     * [normalizeLine] pads a short line and truncates a long one, and it used to be applied to every
     * line in the buffer on every resize. Truncating the screen is right - the region a full-screen
     * program addresses has to be exactly [columns] wide, or a redraw at the new width would leave
     * the tail of the old one standing to the right of it. Truncating the history is data loss, and
     * on a phone it is constant: turning the device to portrait cut every line of the session so far
     * at the narrower width, and turning it back showed the wreckage rather than the output. What was
     * lost was whatever sat past that column - the end of a path, of a URL, of a hash, of a table's
     * last column - and it was lost from the copy buffer and the saved log too, because both read the
     * same cells. Scrollback is finished text: no program will ever address it again, so its width is
     * simply the width it was printed at, and every character of it survives a rotation.
     *
     * The screen is the *tail* of the list, so this has to run after the row count is settled.
     */
    private fun normalizeScreen(target: ArrayList<MutableList<TerminalCell>>) {
        for (index in (target.size - rows).coerceAtLeast(0) until target.size) normalizeLine(target[index])
    }

    private fun normalizeLine(line: MutableList<TerminalCell>) {
        while (line.size < columns) line += TerminalCell()
        while (line.size > columns) line.removeAt(line.lastIndex)
    }

    /** The primary screen, set aside while the alternate one is on display. */
    private class PrimaryScreen(
        val lines: ArrayList<MutableList<TerminalCell>>,
        val cursorRow: Int,
        val cursorColumn: Int,
        val topMargin: Int,
        val bottomMargin: Int,
    )

    private enum class EscapeState { NORMAL, ESC, CSI, SS3, OSC, STRING, CHARSET_G0, CHARSET_G1 }

    private companion object {
        /** What an untouched cell holds, for telling a row with nothing on it from one with a space. */
        val BLANK_CELL = TerminalCell()

        const val MAX_PENDING_REPLIES = 32
        const val MAX_TITLE_LENGTH = 200

        /**
         * DEC special graphics, as code points indexed from '_' (0x5F) to '~' (0x7E).
         *
         * Code points rather than characters so the table cannot be silently mangled by anything that
         * re-encodes this file, and these are exactly the glyphs most likely to be mishandled: box
         * corners, the horizontal rules `ncurses` draws menus with, and the control pictures.
         */
        val DEC_SPECIAL_GRAPHICS = intArrayOf(
            0x0020, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )

        /**
         * The 16 ANSI colours.
         *
         * The bright half used to be aliased onto the normal half - 30 and 90 returned the same value -
         * so "bright black", which is what every diff, prompt and man page reaches for to dim text,
         * came out as pure black on a black background and was simply invisible. They are separate
         * entries now, and the normal half is darkened slightly so the pair is distinguishable.
         */
        fun ansiColor(code: Int): Int = when (code) {
            30 -> 0x00232838
            31 -> 0x00E05561
            32 -> 0x0038C172
            33 -> 0x00D9A343
            34 -> 0x005B8CFF
            35 -> 0x00C678DD
            36 -> 0x0056B6C2
            37 -> 0x00C7CEDB
            90 -> 0x00666F85
            91 -> 0x00FF5555
            92 -> 0x0050FA7B
            93 -> 0x00F1FA8C
            94 -> 0x006FA8FF
            95 -> 0x00FF79C6
            96 -> 0x008BE9FD
            else -> 0x00F8F8F2
        }

        /** The xterm 256-colour cube: 16 named, then 6x6x6, then 24 greys. */
        fun ansi256(index: Int): Int {
            val value = index.coerceIn(0, 255)
            if (value < 8) return ansiColor(30 + value)
            if (value < 16) return ansiColor(90 + (value - 8))
            if (value >= 232) {
                val gray = 8 + (value - 232) * 10
                return (gray shl 16) or (gray shl 8) or gray
            }
            val color = value - 16
            val red = (color / 36) * 51
            val green = ((color / 6) % 6) * 51
            val blue = (color % 6) * 51
            return (red shl 16) or (green shl 8) or blue
        }
    }
}

/**
 * How many columns of [line] carry anything: a glyph, a background colour, or an inverse.
 *
 * One function for the three places that need the answer - [TerminalFrame.contentColumns], the
 * renderer's search for the last cell worth drawing, and [terminalLayout]'s decision about whether a
 * line needs wrapping at all - because they have to agree. [TerminalFrame.contentColumns] is how far
 * the view lets the user pan, so a cell that one of them counts as painted and another does not is
 * either unreachable or a column of pannable emptiness.
 *
 * A blank counts when it has a background or an inverse: that is how a status bar's coloured padding
 * and a highlighted row in `less` are drawn, and stopping short of them would clip the bar.
 *
 * Scanned backwards because the answer is near the end: most of a terminal row is the run of blanks
 * after the text.
 *
 * A cell's base is the whole test, and nothing here has to look at [TerminalCell.combining]: a
 * zero-width character is only ever put on a cell that already holds a base, so a cell carrying one is
 * painted or blank exactly as its base is, and the pan extent is unchanged by it.
 */
internal fun terminalPaintedWidth(line: List<TerminalCell>): Int {
    var index = line.size
    while (index > 0) {
        val cell = line[index - 1]
        if (cell.value != ' ' || !cell.style.background.isDefault || cell.style.inverse) return index
        index--
    }
    return 0
}
