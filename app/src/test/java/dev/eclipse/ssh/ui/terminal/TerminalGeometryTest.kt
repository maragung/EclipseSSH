package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import org.junit.Test

/**
 * The arithmetic between a window in pixels and a grid of character cells.
 *
 * Pure on purpose: this is the part of the terminal that decides how much of the screen the text
 * gets, and it is the part that used to be wrong in a way no test could see - the leftover pixels of
 * a division that does not come out even were all spent on one edge. Testing it here means the
 * geometry is checked without a device, a font, or a running Compose tree.
 */
class TerminalGeometryTest {
    private val cell = TerminalCellMetrics(width = 10f, height = 20f, baseline = 15f)

    @Test
    fun `a window that divides evenly leaves no gap to distribute`() {
        val grid = cell.gridIn(widthPx = 200f, heightPx = 400f)

        assertThat(grid.columns).isEqualTo(20)
        assertThat(grid.rows).isEqualTo(20)
        assertThat(grid.originX).isEqualTo(0f)
        assertThat(grid.originY).isEqualTo(0f)
    }

    @Test
    fun `the remainder of an uneven window is split between the two edges`() {
        // 7px across and 13px down cannot be used by another cell, so they are margin either way.
        // The point is which margin: 3.5 each side rather than 7 on the right.
        val grid = cell.gridIn(widthPx = 207f, heightPx = 413f)

        assertThat(grid.columns).isEqualTo(20)
        assertThat(grid.rows).isEqualTo(20)
        assertThat(grid.originX).isEqualTo(3.5f)
        assertThat(grid.originY).isEqualTo(6.5f)
    }

    @Test
    fun `a fractional cell width does not lose columns to rounding`() {
        // A real monospace advance at 13sp on a 3x phone is 23.4px, not 23, and the fraction is why
        // the remainder has to be computed from the same number the columns were: 46 cells of 23.4
        // leave 3.6px, where 46 of a rounded 23 would account for 22px that the grid does not occupy -
        // a margin off by most of a column, which is exactly what the centring would then mis-split.
        val fractional = TerminalCellMetrics(width = 23.4f, height = 39.6f, baseline = 30f)

        val grid = fractional.gridIn(widthPx = 1080f, heightPx = 2000f)

        assertThat(grid.columns).isEqualTo(46)
        assertThat(grid.rows).isEqualTo(50)
        assertThat(grid.originX).isWithin(0.01f).of(1.8f)
        assertThat(grid.originY).isWithin(0.01f).of(10f)
    }

    @Test
    fun `a window smaller than one cell still reports a grid the pty can be told about`() {
        val grid = cell.gridIn(widthPx = 4f, heightPx = 9f)

        // Not zero: a channel cannot be resized to a window with no columns, and MINA rejects it.
        assertThat(grid.columns).isEqualTo(1)
        assertThat(grid.rows).isEqualTo(1)
        // And the negative remainder that produces must not become a negative offset.
        assertThat(grid.originX).isEqualTo(0f)
        assertThat(grid.originY).isEqualTo(0f)
    }

    @Test
    fun `unmeasured metrics report a grid instead of dividing by zero`() {
        val unmeasured = TerminalCellMetrics(width = 0f, height = 0f, baseline = 0f)

        val grid = unmeasured.gridIn(widthPx = 1080f, heightPx = 2000f)

        assertThat(grid.columns).isEqualTo(1)
        assertThat(grid.rows).isEqualTo(1)
    }

    @Test
    fun `the text margin is one percent of the screen on every side`() {
        val phone = terminalTextInset(screenWidthDp = 360, screenHeightDp = 800)

        // One value, not one per axis: 1% of the shorter edge. Per axis this phone would get 3.6dp
        // across and 8dp down, which is both visibly uneven and *more* vertical padding than the 4dp
        // it replaces - the opposite of giving the text more of the screen.
        assertThat(phone.value).isWithin(TOLERANCE).of(3.6f)
    }

    @Test
    fun `the margin scales with the screen rather than staying a fixed number of dp`() {
        val phone = terminalTextInset(360, 800)
        val tablet = terminalTextInset(1280, 800)

        // The property the fixed 8dp did not have: a larger screen gets a proportionally larger gap,
        // and a phone is not asked to spend 4.4% of its columns on one.
        assertThat(tablet).isGreaterThan(phone)
        assertThat(tablet.value).isWithin(TOLERANCE).of(8f)
        assertThat(phone).isLessThan(8.dp)
    }

    @Test
    fun `orientation does not change the margin`() {
        // Rotating the device swaps the two dimensions, and a margin taken from the shorter edge is
        // the same gap either way round - so the text does not reflow to a different inset on rotate.
        assertThat(terminalTextInset(360, 800)).isEqualTo(terminalTextInset(800, 360))
    }

    @Test
    fun `an unmeasured window yields no padding rather than a negative one`() {
        // Compose throws on a negative padding, and a configuration can report zero before layout.
        assertThat(terminalTextInset(0, 0)).isEqualTo(0.dp)
    }

    @Test
    fun `a grid wider than the screen is what the pty is told it has`() {
        // The 46-column phone from the fractional case above, asked for 80. The remote side formats
        // for 80 and the lines arrive whole; the screen shows 46 of them at a time.
        val phone = TerminalCellMetrics(width = 23.4f, height = 39.6f, baseline = 30f)
        val visible = phone.gridIn(widthPx = 1080f, heightPx = 2000f)

        val wide = visible.atLeastColumns(80)

        assertThat(visible.columns).isEqualTo(46)
        assertThat(wide.columns).isEqualTo(80)
        // Rows are never widened - vertical space is not scarce in the same way, and a program that
        // thought it had more rows than the screen would draw its status line where nobody can see it.
        assertThat(wide.rows).isEqualTo(visible.rows)
        // And the centring is unchanged: the pan window is still the visible one.
        assertThat(wide.originX).isEqualTo(visible.originX)
    }

    @Test
    fun `fit-to-screen and a narrower minimum both leave the grid alone`() {
        val visible = cell.gridIn(widthPx = 500f, heightPx = 400f)

        assertThat(visible.columns).isEqualTo(50)
        assertThat(visible.atLeastColumns(0)).isEqualTo(visible)
        assertThat(visible.atLeastColumns(20)).isEqualTo(visible)
        // A large external display already fits more than 80, and must not be cut down to it.
        assertThat(visible.atLeastColumns(80).columns).isEqualTo(80)
    }

    @Test
    fun `a stored width beyond what the pty accepts is bounded rather than obeyed`() {
        // A vault file is editable text; 4 000 columns in one would otherwise be sent to a server that
        // clamps it, leaving the user panning across a grid the remote side never had.
        val visible = cell.gridIn(widthPx = 500f, heightPx = 400f)

        assertThat(visible.atLeastColumns(100_000).columns).isEqualTo(TERMINAL_COLUMN_RANGE.last)
    }

    @Test
    fun `only painted columns are pannable`() {
        // 46 on screen, 46 painted: nothing to pan to, and being able to drag the text off the edge to
        // look at blank space would only be a way to lose it.
        assertThat(maxPanPx(contentColumns = 46, visibleColumns = 46, cellWidth = 10f)).isEqualTo(0f)
        assertThat(maxPanPx(contentColumns = 20, visibleColumns = 46, cellWidth = 10f)).isEqualTo(0f)
        // 80 painted on a 46-column screen: exactly the 34 columns that do not fit.
        assertThat(maxPanPx(contentColumns = 80, visibleColumns = 46, cellWidth = 10f)).isEqualTo(340f)
    }

    @Test
    fun `history printed at a wider terminal is reachable`() {
        // The rotation case, and the reason contentColumns is not simply the grid width: the lines are
        // 120 cells long because that is what they were printed at, and every one of those characters
        // has to be reachable on a screen that now fits 46.
        assertThat(maxPanPx(contentColumns = 120, visibleColumns = 46, cellWidth = 23.4f))
            .isWithin(TOLERANCE).of(74 * 23.4f)
    }

    @Test
    fun `the pan follows the cursor off either edge and nowhere else`() {
        val cellWidth = 10f
        val window = 460f

        // Cursor at column 79 of an 80-column grid, window showing 46: the pan settles so the cursor's
        // cell ends exactly at the right edge.
        assertThat(
            panForCursor(0f, cursorColumn = 79, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(340f)

        // Already inside the window: left exactly where it was, which is what makes a deliberate pan
        // stick while output arrives.
        assertThat(
            panForCursor(200f, cursorColumn = 25, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(200f)

        // Carriage return to column 0 while panned right: comes back to the start of the line.
        assertThat(
            panForCursor(340f, cursorColumn = 0, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(0f)
    }

    @Test
    fun `a cursor the user has scrolled away from does not yank the view back`() {
        // Scrolled into the history, so the frame reports no cursor row. The pan is the user's.
        assertThat(
            panForCursor(120f, cursorColumn = 0, cursorOnScreen = false, cellWidth = 10f, visibleWidthPx = 460f, maxPan = 340f),
        ).isEqualTo(120f)
    }

    @Test
    fun `an offset stranded past the end of a narrower frame is pulled back`() {
        // Scrolling from long lines to short ones shrinks the extent under a stored pan; without the
        // clamp the view would be looking at nothing at all.
        assertThat(
            panForCursor(340f, cursorColumn = 0, cursorOnScreen = false, cellWidth = 10f, visibleWidthPx = 460f, maxPan = 0f),
        ).isEqualTo(0f)
        // And a negative extent - a window wider than its content - is not a crash.
        assertThat(
            panForCursor(10f, cursorColumn = 0, cursorOnScreen = true, cellWidth = 10f, visibleWidthPx = 460f, maxPan = -50f),
        ).isEqualTo(0f)
    }

    @Test
    fun `unmeasured metrics cannot make the pan a NaN`() {
        // gridIn already survives a zero cell; this is the other half of the same divide-by-zero.
        assertThat(
            panForCursor(0f, cursorColumn = 10, cursorOnScreen = true, cellWidth = 0f, visibleWidthPx = 0f, maxPan = 0f),
        ).isEqualTo(0f)
    }

    private companion object {
        /** One percent of a dp count is a float multiplication, so the dp it produces is not exact. */
        const val TOLERANCE = 0.001f
    }
}
