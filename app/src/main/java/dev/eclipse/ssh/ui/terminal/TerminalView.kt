package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import dev.eclipse.ssh.terminal.TerminalCell
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalSelection
import dev.eclipse.ssh.terminal.TerminalColor
import dev.eclipse.ssh.terminal.TerminalStyle
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
    selection: TerminalSelection? = null,
    onSelectionChange: (TerminalSelection?) -> Unit = {},
    onSelectionFinished: (TerminalSelection) -> Unit = {},
    onScroll: (Int) -> Unit = {},
    onTap: () -> Unit = {},
    onViewportChange: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    onLongPressCell: (line: Int, column: Int) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
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

    BoxWithConstraints(modifier.background(background)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val grid = remember(metrics, widthPx, heightPx) { metrics.gridIn(widthPx, heightPx) }
        val origin = remember(grid) { Offset(grid.originX, grid.originY) }
        // Reported on every size change, including the one the software keyboard causes: the pty has
        // to know the window it is drawing into or a full-screen program wraps its own status line.
        LaunchedEffect(grid.columns, grid.rows) { viewportChange(grid.columns, grid.rows) }

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

        Box(
            Modifier
                .fillMaxSize()
                .scrollable(scrollState, Orientation.Vertical, reverseDirection = true)
                .pointerInput(metrics, origin) {
                    detectTapGestures(
                        onTap = { tap() },
                        onLongPress = { position ->
                            val (line, column) = position.toCell(currentFrame, metrics, origin)
                            longPress(line, column)
                        },
                    )
                }
                .pointerInput(metrics, origin) {
                    var anchor: TerminalSelection? = null
                    detectDragGestures(
                        onDragStart = { position ->
                            val (line, column) = position.toCell(currentFrame, metrics, origin)
                            anchor = TerminalSelection.at(line, column).also(selectionCallback)
                        },
                        onDrag = { change, _ ->
                            val (line, column) = change.position.toCell(currentFrame, metrics, origin)
                            anchor = anchor?.movedTo(line, column)?.also(selectionCallback)
                        },
                        onDragEnd = { anchor?.let(selectionFinished) },
                        onDragCancel = { selectionCallback(null) },
                    )
                }
                .drawBehind {
                    // Translated rather than drawn from the corner, so the half-cell that does not
                    // divide evenly is shared between the two edges - see [TerminalCellMetrics.gridIn].
                    translate(grid.originX, grid.originY) {
                        drawFrame(
                            frame = currentFrame,
                            measurer = measurer,
                            style = style,
                            foreground = foreground,
                            background = background,
                            metrics = metrics,
                            maxRows = grid.rows,
                            selection = selection,
                            selectionColour = selectionColour,
                            cursorColour = cursorColour,
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
 */
private fun Offset.toCell(frame: TerminalFrame, metrics: TerminalCellMetrics, origin: Offset): Pair<Int, Int> {
    val row = if (metrics.height <= 0f) 0 else floor((y - origin.y) / metrics.height).toInt()
    val column = if (metrics.width <= 0f) 0 else floor((x - origin.x) / metrics.width).toInt()
    val boundedRow = row.coerceIn(0, (frame.lines.size - 1).coerceAtLeast(0))
    return (frame.firstLine + boundedRow) to column.coerceAtLeast(0)
}

/**
 * Paints one frame: backgrounds, then glyph runs, then the selection tint, then the cursor.
 *
 * Runs, not cells. Consecutive cells that share a style are measured and drawn as one string, so a
 * line of ordinary output is a single draw call and a colourful prompt is a few - against one call
 * per character, which is what made the old renderer's cost scale with the text rather than with the
 * styling. Trailing blanks are skipped entirely: most of a terminal grid is empty space.
 */
private fun DrawScope.drawFrame(
    frame: TerminalFrame,
    measurer: TextMeasurer,
    style: TextStyle,
    foreground: Color,
    background: Color,
    metrics: TerminalCellMetrics,
    maxRows: Int,
    selection: TerminalSelection?,
    selectionColour: Color,
    cursorColour: Color,
) {
    val cellWidth = metrics.width
    val cellHeight = metrics.height
    if (cellWidth <= 0f || cellHeight <= 0f) return

    frame.lines.forEachIndexed { row, line ->
        // Bounded by the grid rather than by `size.height`: a draw modifier does not clip, and the
        // translate that centres the grid moves the bottom row past the height this scope reports, so
        // a height test would let a partial row paint into the margin below it.
        if (row >= maxRows) return@forEachIndexed
        val top = row * cellHeight
        val absoluteLine = frame.firstLine + row
        // Trailing empty cells are skipped, but only the ones that are truly empty: a blank with a
        // background set is a painted cell, which is how a highlighted selection bar or a status
        // line's coloured padding is drawn.
        val lastPainted = line.indexOfLast { it.value != ' ' || !it.style.background.isDefault || it.style.inverse }
        var column = 0
        while (column <= lastPainted) {
            val cell = line[column]
            var end = column + 1
            while (end <= lastPainted && line[end].style == cell.style) end++
            val text = buildString(end - column) { for (index in column until end) append(line[index].value) }
            val left = column * cellWidth
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
            val from = selection.firstColumnOn(absoluteLine)
            val to = selection.lastColumnOn(absoluteLine, frame.columns)
            if (to >= from) {
                drawRect(
                    selectionColour,
                    topLeft = Offset(from * cellWidth, top),
                    size = Size((to - from + 1) * cellWidth, cellHeight),
                )
            }
        }
    }

    if (frame.cursorVisible && frame.cursorRow in frame.lines.indices && frame.cursorRow < maxRows) {
        // A hollow block, so the character underneath stays legible - a filled one hides whatever the
        // cursor is on, which on a phone is exactly the character the user is trying to check.
        val left = frame.cursorColumn * cellWidth
        val top = frame.cursorRow * cellHeight
        drawRect(
            cursorColour,
            topLeft = Offset(left, top),
            size = Size(cellWidth, cellHeight),
            style = Stroke(width = CURSOR_STROKE),
        )
    }
}

private const val CURSOR_STROKE = 2f

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
