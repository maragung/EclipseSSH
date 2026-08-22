package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
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

    /** One percent of a dp count is a float multiplication, so the dp it produces is not exact. */
    private val TOLERANCE = 0.001f

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
}
