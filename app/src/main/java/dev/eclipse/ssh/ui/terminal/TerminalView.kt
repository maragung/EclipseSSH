package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import dev.eclipse.ssh.terminal.TerminalCell
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalLayout
import dev.eclipse.ssh.terminal.TerminalSelection
import dev.eclipse.ssh.terminal.terminalLayout
import dev.eclipse.ssh.terminal.TerminalColor
import dev.eclipse.ssh.terminal.TerminalStyle
import dev.eclipse.ssh.terminal.TerminalViewport
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The size of one character cell, and the only honest source for how many of them fit.
 *
 * Measured rather than assumed. A monospace advance depends on the font the device ships and on the
 * user's font-scale setting, so a hard-coded width reports a column count the remote pty does not
 * have - which arrives as a full-screen program formatting itself for a window three times the real
 * one. [width] is deliberately a float: an advance is rarely a whole number of pixels, and rounding
 * each glyph before multiplying compounds into several lost columns across a line.
 */
data class TerminalCellMetrics(val width: Float, val height: Float, val baseline: Float) {
    fun columnsIn(pixels: Float): Int = if (width <= 0f) 0 else floor(pixels / width).toInt()
    fun rowsIn(pixels: Float): Int = if (height <= 0f) 0 else floor(pixels / height).toInt()

    /**
     * The grid that fits in [widthPx] x [heightPx], and the corner to start drawing it from.
     *
     * A cell is rarely a whole number of pixels and a window is never an exact multiple of one, so a
     * remainder is unavoidable: at 13sp on a 1080-pixel phone it is up to a column across and a whole
     * row down. Drawn from the origin, every pixel of that remainder collects along the right edge and
     * under the last line, which is what made the text look pushed into the corner of its own window -
     * a gutter three times the intended margin on one side and the intended margin on the other, with
     * nothing on screen to say it was rounding rather than layout. Splitting it puts half on each side,
     * so the grid sits centred and the margin the caller asked for is the margin that appears.
     *
     * Bounded below at zero because a window narrower than one cell still has to report one column -
     * the pty cannot be told it has none - and that makes the remainder negative.
     */
    fun gridIn(widthPx: Float, heightPx: Float): TerminalGrid {
        val columns = columnsIn(widthPx).coerceAtLeast(1)
        val rows = rowsIn(heightPx).coerceAtLeast(1)
        return TerminalGrid(
            columns = columns,
            rows = rows,
            originX = ((widthPx - columns * width) / 2f).coerceAtLeast(0f),
            originY = ((heightPx - rows * height) / 2f).coerceAtLeast(0f),
        )
    }
}

/** How many cells fit in a window, and the offset that centres them in it. */
data class TerminalGrid(val columns: Int, val rows: Int, val originX: Float, val originY: Float)

/**
 * The same grid, widened to at least [minColumns] columns.
 *
 * This is the whole of the wide-terminal setting. A phone fits somewhere around forty-five columns of
 * legible monospace, and a terminal that tells the remote side it has forty-five is a terminal where
 * the remote side does the cutting: `ls -l` drops its last column, `git log --oneline` breaks a
 * subject line mid-word, a URL or a sha256 or a `/very/long/path` folds at whatever character happens
 * to sit at the margin, and a table's alignment is gone. None of that is recoverable afterwards,
 * because the newline the server sent is indistinguishable from one the user asked for.
 *
 * Asking for eighty instead - the width every command-line program on earth is written for - moves the
 * decision back to where it can be undone: the lines arrive whole, and the part that does not fit on
 * screen is wrapped at a word boundary by [dev.eclipse.ssh.terminal.terminalLayout], or panned to on the
 * alternate screen where wrapping would move a full-screen program's rows. Height is the other axis and
 * has its own two functions, [atLeastRows] and [atMostRows], because a floor is not the only thing a
 * height can be: the app-wide setting raises it, a host's own choice lowers it, and the screen decides
 * nothing at all.
 *
 * Bounded by the same range the pty and the buffer accept, so a stored setting can never ask for a
 * grid that one of them would silently refuse.
 */
fun TerminalGrid.atLeastColumns(minColumns: Int): TerminalGrid {
    val wanted = minColumns.coerceAtMost(TERMINAL_COLUMN_RANGE.last)
    return if (wanted <= columns) this else copy(columns = wanted)
}

/**
 * The same grid, made at least [minRows] rows tall.
 *
 * This is what makes the taller choices mean anything, and it is the width rule applied to the other
 * axis. A height used to be a ceiling - the screen cut it down to what fits, on the reasoning that a
 * row past the bottom edge is nowhere while a column past the right edge can be panned to. The second
 * half of that is still true, and is why a *host's* height stays a ceiling; what it got wrong was
 * concluding that a taller terminal is therefore pointless. A pty told it has 1000 rows is one whose
 * `ls` prints a thousand entries before it pages, whose `apt-get` output stays in the app's scrollback
 * instead of being paged away by the program, and whose `less` pages a thousand lines at a time - and
 * what the user reads is the window around the cursor, because `AnsiTerminalBuffer.frame` anchors
 * there and the scroll gesture walks back through the rest. Nothing is drawn past the bottom edge:
 * the view draws the height it has, and the pty is simply told a bigger number than that.
 *
 * Bounded by the same range the pty and the buffer accept, so a stored setting can never ask for a
 * grid that one of them would silently refuse.
 */
fun TerminalGrid.atLeastRows(minRows: Int): TerminalGrid {
    val wanted = minRows.coerceAtMost(TERMINAL_ROW_RANGE.last)
    return if (wanted <= rows) this else copy(rows = wanted)
}

/**
 * The narrowest grid to give a host's pty: the wider of the app-wide floor and this host's own choice.
 *
 * A per-host width has to be a floor *here*, at the view, and not only an argument to the pty when the
 * shell opens. The viewport is reported on every measurement pass and each report resizes the pty, so a
 * width applied at open time and nowhere else survives exactly until the first frame is measured - which
 * is a few milliseconds later, before any output has arrived. The setting looked as if it did nothing.
 *
 * The wider of the two rather than the more specific of the two, because both are floors and neither is
 * a ceiling: the app-wide setting says how narrow a terminal the user is willing to read anywhere, and
 * the host's says this particular server needs at least that much to format itself.
 */
internal fun minTerminalColumns(settingColumns: Int, hostColumns: Int): Int =
    maxOf(settingColumns.coerceAtLeast(0), hostColumns.coerceAtLeast(0))

/**
 * The same grid, shortened to a host's chosen height when that is shorter than the app-wide one.
 *
 * A host's `Rows` field is the one place a per-host value is a ceiling rather than a floor, and it is
 * the only way to tell a particular server that the window is short - which some programs want to know,
 * and which no app-wide setting can express without shortening every host. So it only ever lowers: a
 * host asking for fewer rows than the floor gets exactly what it asked for, and a host asking for more
 * is left at the floor, because a value that raised the height here would be a per-host copy of the
 * app-wide setting and would silently override it.
 *
 * Only the grid is affected. What the *pty* is told is a separate decision made at the view, where an
 * alternate screen asks for the on-screen height instead - see [TerminalViewport].
 *
 * Zero means "no opinion", the same sentinel the stored setting uses.
 */
fun TerminalGrid.atMostRows(hostRows: Int): TerminalGrid =
    if (hostRows in 1 until rows) copy(rows = hostRows) else this

/** Remembers the cell metrics for [style], measuring a run of glyphs rather than a single one. */
@Composable
fun rememberTerminalCellMetrics(style: TextStyle, measurer: TextMeasurer = rememberTextMeasurer()): TerminalCellMetrics =
    remember(style, measurer) {
        val measured = measurer.measure(AnnotatedString(MEASURE_RUN), style)
        TerminalCellMetrics(
            width = measured.size.width.toFloat() / MEASURE_RUN.length,
            height = measured.size.height.toFloat(),
            baseline = measured.firstBaseline,
        )
    }

private const val MEASURE_RUN = "MMMMMMMMMMMMMMMMMMMM"

/**
 * The gap between the terminal text and the edges of the window it is drawn in: one percent of the
 * screen, on all four sides.
 *
 * Proportional to the screen, because the pair of fixed values it replaces could not be. 8dp either
 * side was 4.4% of a 360dp phone and 1.5% of a tablet, so the same constant was a wide gutter on the
 * device with the fewest columns to spare and a hairline on the one with the most - on a phone it spent
 * nearly two columns of a 46-column window on blank space. The vertical 4dp was the opposite mistake,
 * thin enough that the last line's descenders ran into the key row below it.
 *
 * One percent of the *shorter* edge, and the same value on every side, so the frame is even. Measured
 * per axis instead, a phone would get 3.6dp across and 8dp down - a gap that looks like a mistake
 * rather than a margin, and one that spends the dimension the terminal has least of: the tab strip and
 * the key row already take a fixed bite out of the height, and 1% of the long edge is where the
 * percentage costs a whole row of output.
 *
 * [screenWidthDp] and [screenHeightDp] come from the window configuration, which reports 0 for a window
 * that has not been measured yet; the floor keeps that from becoming a negative padding, which Compose
 * rejects at runtime.
 */
fun terminalTextInset(screenWidthDp: Int, screenHeightDp: Int): Dp =
    (minOf(screenWidthDp, screenHeightDp) * TEXT_INSET_FRACTION).dp.coerceAtLeast(0.dp)

private const val TEXT_INSET_FRACTION = 0.01f

/**
 * How far the grid may be panned to the left, in pixels.
 *
 * Only *painted* columns are pannable. A shell screen is mostly empty on the right, and being able to
 * drag the text away to stare at that emptiness is a way to lose the output rather than a feature - so
 * the extent comes from [dev.eclipse.ssh.terminal.TerminalLayout.contentColumns], which counts what is
 * actually on the widest drawn row (including the cursor's own cell), and not from the width the pty was
 * given. After wrapping that is usually the window itself and there is nothing to pan; what is left is
 * the alternate screen, and the single token too wide to break.
 */
internal fun maxPanPx(contentColumns: Int, visibleColumns: Int, cellWidth: Float): Float =
    ((maxOf(contentColumns, visibleColumns) - visibleColumns) * cellWidth).coerceAtLeast(0f)

/**
 * The pan offset that keeps the cursor on screen, given where it is now.
 *
 * The cursor is the one thing a terminal may never hide. With an 80-column grid on a phone that shows
 * forty-five, a command long enough to pass the right edge would otherwise be typed out of sight -
 * the characters arrive, the line is correct, and the user cannot see what they are editing. So the
 * view follows: it pans just far enough to bring the cursor's cell inside the window, from whichever
 * side it left, and otherwise leaves the offset exactly where it was. That "otherwise" is what makes a
 * deliberate pan sideways stick while output is arriving - it is given up only when the cursor moves
 * somewhere it cannot be seen.
 *
 * [current] is re-clamped on every call, because the extent shrinks under it: a narrower frame, or
 * scrolling back to a region of short lines, can leave a stored offset past the end of what exists.
 */
internal fun panForCursor(
    current: Float,
    cursorColumn: Int,
    cursorOnScreen: Boolean,
    cellWidth: Float,
    visibleWidthPx: Float,
    maxPan: Float,
): Float {
    val ceiling = maxPan.coerceAtLeast(0f)
    val clamped = current.coerceIn(0f, ceiling)
    if (!cursorOnScreen || cellWidth <= 0f || visibleWidthPx <= 0f) return clamped
    val left = cursorColumn * cellWidth
    val right = left + cellWidth
    return when {
        right > clamped + visibleWidthPx -> (right - visibleWidthPx).coerceIn(0f, ceiling)
        left < clamped -> left.coerceIn(0f, ceiling)
        else -> clamped
    }
}

/**
 * Draws a [TerminalFrame] and turns touches into terminal input.
 *
 * A `Canvas` rather than a `LazyColumn` of `Text`, and that is the whole point of this file. The
 * previous renderer built one `AnnotatedString` per line and handed each to a `Text` composable, so
 * every frame - thirty a second while output is arriving - re-ran Compose layout for the whole
 * viewport and rebuilt a span tree per row. On a low-end phone that alone kept a core busy. Drawing
 * measures one run of identically-styled cells at a time and paints it in a single call, which for
 * ordinary output is a handful of draws per line and no composition at all.
 *
 * The frame is a plain value, so recomposition is driven by [TerminalFrame.revision] rather than by a
 * deep comparison of a quarter of a million cells.
 *
 * Rows are drawn through [dev.eclipse.ssh.terminal.terminalLayout] rather than one per buffer line, so a
 * line wider than the window occupies as many rows as it needs, broken at word boundaries. Every
 * coordinate the view deals in - the touch that starts a selection, the tint that shows it, the cursor,
 * the pan extent - goes through that same mapping, which is why it is one pure function and not a
 * sprinkling of arithmetic.
 *
 * Gestures, because a terminal has to spend them carefully. A drag scrolls - vertically through the
 * scrollback and horizontally across whatever is still wider than the screen, which after wrapping is
 * the alternate screen and the occasional unbreakable token. Selection is therefore behind a long press: the press alone selects the word under
 * the finger, and holding and then dragging extends the selection by cell. A plain drag used to start
 * a selection, which meant a terminal that could not be scrolled with a finger at all - every attempt
 * painted a selection and put a fragment of a line on the clipboard instead.
 *
 * @param minColumns the narrowest grid to give the pty regardless of how many columns fit on screen;
 *   0 fits the screen exactly. See [atLeastColumns] and [minTerminalColumns].
 * @param minRows the shortest grid to give the terminal regardless of how many rows fit on screen; 0
 *   fits the screen exactly. See [atLeastRows]. The view draws the rows it has either way - a taller
 *   grid is one the pty was told about, not one that is painted - so the extra height buys scrollback
 *   the shell will not page away, not pixels.
 * @param hostRows the height this host asked for, which can only shorten the grid; 0 has no opinion.
 *   See [atMostRows].
 * @param onScroll called with a line delta; positive scrolls back into the history.
 * @param onSelectionChange the live drag selection, in absolute buffer lines, or null when cleared.
 */
@Composable
fun TerminalView(
    frame: TerminalFrame,
    style: TextStyle,
    background: Color,
    foreground: Color,
    metrics: TerminalCellMetrics,
    modifier: Modifier = Modifier,
    minColumns: Int = 0,
    minRows: Int = 0,
    hostRows: Int = 0,
    selection: TerminalSelection? = null,
    onSelectionChange: (TerminalSelection?) -> Unit = {},
    onSelectionFinished: (TerminalSelection) -> Unit = {},
    onScroll: (Int) -> Unit = {},
    onTap: () -> Unit = {},
    onViewportChange: (TerminalViewport) -> Unit = {},
    onLongPressCell: (line: Int, column: Int) -> Unit = { _, _ -> },
    /**
     * Reports a pinch as a font-size scale factor relative to the size in force when the gesture
     * started. The view does not own the font size - the setting does - so it reports the factor and
     * lets the caller decide what size that lands on and when to persist it.
     */
    onZoom: (scale: Float, ended: Boolean) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Whether the cursor is in its "on" half-period. A [mutableStateOf] read from inside the draw
    // lambda rather than from the composable body, so a blink invalidates the draw phase only - the
    // same trick the pan uses. Read as `.value` up here it would recompose the whole grid twice a
    // second, which on a phone is a measurable amount of work to make a rectangle flash.
    val cursorOn = remember { mutableStateOf(true) }
    // Focus, not lifecycle: a terminal behind another app, behind the lock screen, or in the recents
    // list has nothing to blink at, and the loop below is the kind of 2 Hz timer that costs nothing
    // per tick and a noticeable amount of battery per night. Window focus covers all three, and unlike
    // a lifecycle observer it needs no extra dependency here.
    val focused = LocalWindowInfo.current.isWindowFocused
    val cursorColour = remember(foreground) { foreground.copy(alpha = 0.85f) }
    val selectionColour = remember(foreground) { foreground.copy(alpha = 0.30f) }
    // The callbacks are read from inside long-lived pointer and scroll handlers, which are keyed on
    // the metrics rather than on the lambdas; without this a recomposition would leave them calling
    // the previous frame's closures and selection would be computed against stale coordinates.
    val currentFrame by rememberUpdatedState(frame)
    val scrollCallback by rememberUpdatedState(onScroll)
    val selectionCallback by rememberUpdatedState(onSelectionChange)
    val selectionFinished by rememberUpdatedState(onSelectionFinished)
    val longPress by rememberUpdatedState(onLongPressCell)
    val tap by rememberUpdatedState(onTap)
    val viewportChange by rememberUpdatedState(onViewportChange)
    val zoom by rememberUpdatedState(onZoom)

    BoxWithConstraints(modifier.background(background)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Two grids, and the difference between them is the whole feature. `visible` is what fits on
        // screen; `grid` is what the pty is told it has, which is at least as wide and usually as tall.
        // They are the same object whenever both settings are "fit screen".
        val visible = remember(metrics, widthPx, heightPx) { metrics.gridIn(widthPx, heightPx) }
        val grid = remember(visible, minColumns, minRows, hostRows) {
            visible.atLeastColumns(minColumns).atLeastRows(minRows).atMostRows(hostRows)
        }
        // What the pty is told it has, which is the grid except while a program is painting the screen.
        //
        // `vim`, `htop`, `less` and `nano` address every cell positionally and draw their own borders
        // and status lines for the height they were told; a thousand-row `vim` on a forty-row screen is
        // a status line nobody can see and a body of text whose bottom half does not exist. The primary
        // screen has no such problem, because a shell's output flows and the window around the cursor is
        // what the reader looks at - so the two cases get different numbers, which is the same
        // distinction [dev.eclipse.ssh.terminal.terminalLayout] already draws for wrapping.
        //
        // Bounded by the grid as well as by the screen, so a host that asked for a short window keeps
        // it: capping only at the screen would let a full-screen program be told forty rows on a host
        // explicitly configured for thirty.
        val ptyRows = if (frame.alternateScreen) minOf(visible.rows, grid.rows) else grid.rows
        val origin = remember(visible) { Offset(visible.originX, visible.originY) }
        val viewport = TerminalViewport(
            columns = grid.columns,
            ptyRows = ptyRows,
            bufferRows = grid.rows,
            visibleRows = visible.rows,
        )
        // Reported on every size change, including the one the software keyboard causes: the pty has
        // to know the window it is drawing into or a full-screen program wraps its own status line.
        // Keyed on the whole value, so entering and leaving an alternate screen reports the changed
        // height too - that flip is a resize as far as the far end is concerned.
        LaunchedEffect(viewport) { viewportChange(viewport) }

        // How far the grid is panned to the left, in pixels. A float rather than a column index so a
        // drag moves smoothly instead of snapping a character at a time, and a `MutableFloatState`
        // rather than a plain `var` so the draw below re-runs on a pan without recomposing anything.
        val pan = remember { mutableFloatStateOf(0f) }
        val visibleWidthPx = visible.columns * metrics.width
        // Where every row of this frame goes once lines too wide for the window have been wrapped at a
        // word boundary. Keyed on what the answer depends on rather than on the frame, whose equality is
        // a comparison of several thousand cells: the revision covers every mutation of the buffer and
        // `firstLine` covers a scroll, which moves the window without mutating anything.
        //
        // The height here is the *visible* one. A tall grid is a taller pty, not a taller screen, and the
        // frame the buffer hands over is already the window around the cursor - laying it out for a
        // thousand rows would wrap and bottom-anchor against space that is not on the phone.
        val layout = remember(frame.revision, frame.firstLine, frame.lines.size, visible.columns, visible.rows) {
            terminalLayout(frame, visible.columns, visible.rows)
        }
        // Read from the long-lived gesture handlers, which are keyed on the metrics and would otherwise
        // map a touch through the layout of whichever frame was on screen when they were installed.
        val currentLayout by rememberUpdatedState(layout)
        // From the layout, not the frame: after wrapping, the frame's widest line is no longer drawn as
        // one row, and panning to reach a column that is now on the row below would only lose the text.
        val maxPan = maxPanPx(layout.contentColumns, visible.columns, metrics.width)

        // Follows the cursor, which is the one thing that must never be off-screen: with an 80-column
        // grid on a 45-column phone, typing a long command would otherwise run out of sight and the
        // user would be editing a line they cannot see. Keyed on the cursor rather than on the frame,
        // so this settles once per cursor move and a manual pan is left alone until the cursor moves
        // again - and re-clamped here too, because a narrower frame can strand an old pan past its end.
        LaunchedEffect(layout.cursorColumn, layout.cursorRow, maxPan, visible.columns, metrics.width) {
            pan.floatValue = panForCursor(
                current = pan.floatValue,
                // The cursor's column *within its row*. On a wrapped line the two differ, and following
                // the line's column would pan a window whose text is already on screen.
                cursorColumn = layout.cursorColumn,
                cursorOnScreen = layout.cursorRow >= 0,
                cellWidth = metrics.width,
                visibleWidthPx = visibleWidthPx,
                maxPan = maxPan,
            )
        }

        // Keyed on the cursor's position as well as on whether it exists, so every move restarts the
        // loop at the start of its "on" half-period: a cursor that happened to be in its dark phase when
        // the user pressed a key would otherwise be invisible at the moment they looked for it. While
        // output is streaming the restart happens per frame and the cursor simply stays solid, which is
        // what every other terminal does too.
        LaunchedEffect(frame.cursorVisible, focused, layout.cursorRow, layout.cursorColumn) {
            if (!frame.cursorVisible || !focused) {
                // Solid, not dark. A cursor frozen mid-blink reads as a rendering bug, and a terminal
                // whose window is not focused should still show where the caret is.
                cursorOn.value = true
                return@LaunchedEffect
            }
            while (true) {
                cursorOn.value = true
                delay(CURSOR_BLINK_MS)
                cursorOn.value = false
                delay(CURSOR_BLINK_MS)
            }
        }

        // Fractional lines are accumulated because a drag of a few pixels is less than one row and
        // would otherwise be discarded, making a slow scroll feel dead.
        val scrollRemainder = remember { FloatArray(1) }
        val scrollState = rememberScrollableState { delta ->
            val lines = (scrollRemainder[0] - delta / metrics.height)
            val whole = lines.roundToInt()
            scrollRemainder[0] = lines - whole
            if (whole != 0) scrollCallback(whole)
            delta
        }

        // Pixels, not columns, and clamped rather than accumulated: a pan has no rounding to carry
        // because the offset it produces is the offset that is drawn. Reporting only what was actually
        // consumed is what lets a drag past either end hand the gesture back instead of swallowing it.
        val panState = rememberScrollableState { delta ->
            val before = pan.floatValue
            val after = (before - delta).coerceIn(0f, maxPan)
            pan.floatValue = after
            before - after
        }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .scrollable(scrollState, Orientation.Vertical, reverseDirection = true)
                .scrollable(panState, Orientation.Horizontal)
                // First in the chain and therefore outermost, but it only ever consumes once a second
                // finger is down, so the single-finger handlers below - tap, long press, scroll, pan -
                // are untouched. A two-finger gesture is not something any of them can serve: vertical
                // `scrollable` would read the pinch as a scroll and throw the scrollback around while
                // the user was trying to make the text bigger.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var scale = 1f
                        var pinching = false
                        do {
                            val event = awaitPointerEvent()
                            val down = event.changes.count { it.pressed }
                            if (down >= 2) {
                                pinching = true
                                val step = event.calculateZoom()
                                if (step != 0f && step != 1f) {
                                    scale *= step
                                    zoom(scale, false)
                                }
                                // Consumed only while two fingers are down, which is what stops the
                                // scroll handlers from also acting on the same pointers.
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) zoom(scale, true)
                    }
                }
                .pointerInput(metrics, origin) {
                    detectTapGestures(
                        onTap = { tap() },
                        onLongPress = { position ->
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue)
                            longPress(line, column)
                        },
                    )
                }
                .pointerInput(metrics, origin) {
                    var anchor: TerminalSelection? = null
                    var moved = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            // Anchored but deliberately not published. The long press above has already
                            // selected the word under the finger, and replacing that with a one-cell
                            // selection before the user has moved would undo it in front of them.
                            moved = false
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue)
                            anchor = TerminalSelection.at(line, column)
                        },
                        onDrag = { change, _ ->
                            moved = true
                            val (line, column) =
                                change.position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue)
                            anchor = anchor?.movedTo(line, column)?.also(selectionCallback)
                        },
                        onDragEnd = { if (moved) anchor?.let(selectionFinished) },
                        onDragCancel = { if (moved) selectionCallback(null) },
                    )
                }
                .drawBehind {
                    // Translated rather than drawn from the corner, so the half-cell that does not
                    // divide evenly is shared between the two edges - see [TerminalCellMetrics.gridIn].
                    // The pan is part of the same translate, which is why panning costs a redraw and
                    // not a recomposition.
                    translate(visible.originX - pan.floatValue, visible.originY) {
                        drawFrame(
                            frame = currentFrame,
                            layout = currentLayout,
                            measurer = measurer,
                            style = style,
                            foreground = foreground,
                            background = background,
                            metrics = metrics,
                            // The rows there is room for, not the rows the pty was told about: a tall
                            // grid is a taller pty, and a row below the bottom edge is a row of cells
                            // painted where nobody can see them.
                            maxRows = visible.rows,
                            selection = selection,
                            selectionColour = selectionColour,
                            cursorColour = cursorColour,
                            cursorOn = cursorOn.value,
                        )
                    }
                },
        )
    }
}

/**
 * Maps a touch to an absolute buffer coordinate, clamped to the frame it landed on.
 *
 * [origin] is where the grid actually starts, which is not the corner of the view: without subtracting
 * it, a tap near the left edge selects the character to its left and a long press picks the wrong word,
 * because the pixels the grid was shifted by are counted as part of the first cell.
 *
 * [pan] is added back for the same reason in the other axis - on a grid panned halfway across, the
 * character under the finger is not the one the untranslated coordinate names. It is passed as a value
 * read at the moment of the touch rather than keyed into the gesture handler, because keying a
 * long-lived pointer handler on something that changes at frame rate restarts it mid-gesture.
 */
private fun Offset.toCell(
    frame: TerminalFrame,
    layout: TerminalLayout,
    metrics: TerminalCellMetrics,
    origin: Offset,
    pan: Float = 0f,
): Pair<Int, Int> {
    val row = if (metrics.height <= 0f) 0 else floor((y - origin.y) / metrics.height).toInt()
    val column = if (metrics.width <= 0f) 0 else floor((x + pan - origin.x) / metrics.width).toInt()
    // Through the layout, because the row under the finger is a *visual* row: on a wrapped line the
    // third row of the screen can be the second half of the second line of output, and a selection
    // addressed by screen row would copy text from somewhere else entirely.
    val visual = layout.rows.getOrNull(row.coerceIn(0, layout.rows.lastIndex.coerceAtLeast(0)))
        ?: return frame.firstLine to column.coerceAtLeast(0)
    return (frame.firstLine + visual.line) to (visual.from + column).coerceAtLeast(0)
}

/**
 * Paints one frame: backgrounds, then glyph runs, then the selection tint, then the cursor.
 *
 * Runs, not cells. Consecutive cells that share a style are measured and drawn as one string, so a
 * line of ordinary output is a single draw call and a colourful prompt is a few - against one call
 * per character, which is what made the old renderer's cost scale with the text rather than with the
 * styling. Trailing blanks are skipped entirely: most of a terminal grid is empty space.
 *
 * One row of [layout] per row of the screen, so which columns of which line are drawn where is decided
 * in one pure, tested function instead of here; this loop only paints what it is handed.
 */
private fun DrawScope.drawFrame(
    frame: TerminalFrame,
    layout: TerminalLayout,
    measurer: TextMeasurer,
    style: TextStyle,
    foreground: Color,
    background: Color,
    metrics: TerminalCellMetrics,
    maxRows: Int,
    selection: TerminalSelection?,
    selectionColour: Color,
    cursorColour: Color,
    cursorOn: Boolean = true,
) {
    val cellWidth = metrics.width
    val cellHeight = metrics.height
    if (cellWidth <= 0f || cellHeight <= 0f) return

    layout.rows.forEachIndexed { row, visual ->
        // Bounded by the grid rather than by `size.height`: a draw modifier does not clip, and the
        // translate that centres the grid moves the bottom row past the height this scope reports, so
        // a height test would let a partial row paint into the margin below it.
        if (row >= maxRows) return@forEachIndexed
        val line = frame.lines.getOrNull(visual.line) ?: return@forEachIndexed
        val top = row * cellHeight
        val absoluteLine = frame.firstLine + visual.line
        // The row's own columns, and only those: the layout has already dropped the trailing blanks and
        // decided where a line too wide for the window breaks. Bounded by the line again because the
        // layout and the frame are read at slightly different moments while output is arriving.
        val lastColumn = visual.to.coerceAtMost(line.size)
        var column = visual.from
        while (column < lastColumn) {
            val cell = line[column]
            var end = column + 1
            while (end < lastColumn && line[end].style == cell.style) end++
            val text = buildString(end - column) { for (index in column until end) append(line[index].value) }
            // Relative to the row, not to the line: a wrapped continuation is drawn from the left margin.
            val left = (column - visual.from) * cellWidth
            val runWidth = (end - column) * cellWidth
            val resolved = cell.style.resolve(foreground, background)
            if (resolved.background != null) {
                drawRect(resolved.background, topLeft = Offset(left, top), size = Size(runWidth, cellHeight))
            }
            if (text.isNotBlank()) {
                val layout = measurer.measure(
                    AnnotatedString(text),
                    style.merge(
                        TextStyle(
                            color = resolved.foreground,
                            fontWeight = if (cell.style.bold) FontWeight.Bold else null,
                            fontStyle = if (cell.style.italic) FontStyle.Italic else null,
                            textDecoration = decorationFor(cell),
                        ),
                    ),
                )
                drawText(layout, topLeft = Offset(left, top))
            }
            column = end
        }
        if (selection != null && absoluteLine in selection.startLine..selection.endLine) {
            // Clipped to this row's columns, so a line selected across a wrap is tinted on each of the
            // rows it occupies and on no part of the grid that holds none of it.
            val from = maxOf(selection.firstColumnOn(absoluteLine), visual.from)
            val to = minOf(selection.lastColumnOn(absoluteLine, frame.columns), visual.to - 1)
            if (to >= from) {
                drawRect(
                    selectionColour,
                    topLeft = Offset((from - visual.from) * cellWidth, top),
                    size = Size((to - from + 1) * cellWidth, cellHeight),
                )
            }
        }
    }

    if (cursorOn && frame.cursorVisible && layout.cursorRow in 0 until maxRows) {
        // A hollow block, so the character underneath stays legible - a filled one hides whatever the
        // cursor is on, which on a phone is exactly the character the user is trying to check.
        val left = layout.cursorColumn * cellWidth
        val top = layout.cursorRow * cellHeight
        drawRect(
            cursorColour,
            topLeft = Offset(left, top),
            size = Size(cellWidth, cellHeight),
            style = Stroke(width = CURSOR_STROKE),
        )
    }
}

private const val CURSOR_STROKE = 2f

/**
 * Half the blink period. 500 ms on, 500 ms off - close enough to xterm's 530 that it reads as a
 * terminal cursor rather than as something flashing for attention.
 */
private const val CURSOR_BLINK_MS = 500L

private fun decorationFor(cell: TerminalCell): TextDecoration? = when {
    cell.style.underline && cell.style.strikethrough ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    cell.style.underline -> TextDecoration.Underline
    cell.style.strikethrough -> TextDecoration.LineThrough
    else -> null
}

/**
 * The colours a cell is actually painted in, after the attributes that swap or fade them.
 *
 * Inverse has to be resolved here rather than at parse time: it swaps foreground with background,
 * and a cell that never set either has to swap the *theme's* pair, which the buffer does not know.
 * `hidden` is folded in the same way - it means "draw the text in the background colour", which is
 * how a password prompt hides what it echoes, and reproducing it exactly is the point.
 */
private fun TerminalStyle.resolve(
    defaultForeground: Color,
    defaultBackground: Color,
): ResolvedColours {
    val rawForeground = if (foreground.isDefault) defaultForeground else foreground.toComposeColor()
    val rawBackground = if (background.isDefault) null else background.toComposeColor()
    var fg = if (dim) rawForeground.copy(alpha = DIM_ALPHA) else rawForeground
    var bg = rawBackground
    if (inverse) {
        val swapped = bg ?: defaultBackground
        bg = fg
        fg = swapped
    }
    if (hidden) fg = bg ?: defaultBackground
    return ResolvedColours(fg, bg)
}

/** The palette index or 24-bit value the parser recorded, as an opaque colour. */
private fun TerminalColor.toComposeColor(): Color = Color(OPAQUE or rgb)

private const val OPAQUE = 0xFF000000.toInt()

private const val DIM_ALPHA = 0.55f

private data class ResolvedColours(val foreground: Color, val background: Color?)
