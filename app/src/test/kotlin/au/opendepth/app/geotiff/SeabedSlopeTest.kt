package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class SeabedSlopeTest {

    /** A made-up seabed, [cols] by [rows], with the depth at each cell from [depth]. */
    private fun grid(cols: Int, rows: Int, depth: (col: Int, row: Int) -> Float): FloatArray =
        FloatArray(cols * rows) { depth(it % cols, it / cols) }

    @Test
    fun `a flat seabed has no slope anywhere`() {
        val slopes = SeabedSlope.degrees(grid(6, 5) { _, _ -> -12f }, 6, 5, 10.0, 10.0)

        slopes.forEach { assertEquals(0f, it, 0f) }
    }

    @Test
    fun `a seabed falling ten degrees to the east reads ten degrees, edges included`() {
        // Ten-metre cells, each deeper than the one to its west by what a 10 degree slope drops in ten metres.
        val drop = 10.0 * tan(Math.toRadians(10.0))
        val slopes = SeabedSlope.degrees(grid(7, 4) { col, _ -> (-5.0 - col * drop).toFloat() }, 7, 4, 10.0, 10.0)

        slopes.forEach { assertEquals(10f, it, 0.01f) }
    }

    @Test
    fun `a forty-five degree drop to the south reads forty-five degrees`() {
        // Cells 4 m across and 2 m down the chart, each row 2 m deeper: a metre down for every metre south.
        val slopes = SeabedSlope.degrees(grid(4, 6) { _, row -> -3f - row * 2f }, 4, 6, 4.0, 2.0)

        slopes.forEach { assertEquals(45f, it, 0.01f) }
    }

    @Test
    fun `slopes east and south add up as one steeper slope`() {
        // A metre down per metre east and per metre south: the steepest way is the diagonal.
        val slopes = SeabedSlope.degrees(grid(5, 5) { col, row -> -(col + row).toFloat() }, 5, 5, 1.0, 1.0)

        val expected = Math.toDegrees(kotlin.math.atan(kotlin.math.sqrt(2.0))).toFloat()
        slopes.forEach { assertEquals(expected, it, 0.01f) }
    }

    @Test
    fun `cells with no depth stay empty and their neighbours use the side they have`() {
        val values = grid(5, 3) { col, _ -> -col.toFloat() }
        values[1 * 5 + 2] = Float.NaN
        val slopes = SeabedSlope.degrees(values, 5, 3, 1.0, 1.0)

        assertTrue(slopes[1 * 5 + 2].isNaN())
        // Either side of the gap the slope comes from the one neighbour left: still a metre per metre.
        assertEquals(45f, slopes[1 * 5 + 1], 0.01f)
        assertEquals(45f, slopes[1 * 5 + 3], 0.01f)
    }

    @Test
    fun `a single row or a cell size of nothing gives no slopes rather than a wrong one`() {
        assertTrue(SeabedSlope.degrees(floatArrayOf(-1f, -2f, -3f), 3, 1, 1.0, 1.0).all { it.isNaN() })
        assertTrue(SeabedSlope.degrees(grid(3, 3) { col, _ -> -col.toFloat() }, 3, 3, 0.0, 1.0).all { it.isNaN() })
    }

    // ---------------------------------------------------------------- a picture drawn closer in than its depths

    /** What a 10 degree slope drops across a ten-metre cell. */
    private val drop = 10.0 * tan(Math.toRadians(10.0))

    @Test
    fun `an even ten degree slope with each cell repeated three times reads ten degrees everywhere`() {
        val cellCols = 8
        val cellRows = 5
        val cols = cellCols * 3
        val rows = cellRows * 3
        val values = grid(cols, rows) { col, _ -> (-5.0 - col / 3 * drop).toFloat() }
        val cellOf = IntArray(cols * rows) { (it / cols) / 3 * cellCols + (it % cols) / 3 }

        val slopes = SeabedSlope.degreesByCell(values, cellOf, cellCols, cellRows, 10.0, 10.0)
        slopes.forEach { assertEquals(10f, it, 0.01f) }

        // From one pixel to the next it reads flat inside each cell and nearly 15 degrees along its edges: the lattice.
        val lattice = SeabedSlope.degrees(values, cols, rows, 10.0 / 3, 10.0 / 3)
        assertEquals(0f, lattice[cols + 4], 0.01f)
        assertEquals(14.8f, lattice[cols + 3], 0.1f)
        assertEquals(14.8f, lattice[cols + 5], 0.1f)
    }

    @Test
    fun `cells drawn two and three pixels wide by turns still read ten degrees`() {
        // Two and a half pixels to a cell, both ways.
        val cols = 20
        val rows = 10
        val cellCols = 8
        val cellRows = 4
        fun cell(pixel: Int) = ((pixel + 0.5) / 2.5).toInt()
        val values = grid(cols, rows) { col, _ -> (-5.0 - cell(col) * drop).toFloat() }
        val cellOf = IntArray(cols * rows) { cell(it / cols) * cellCols + cell(it % cols) }

        SeabedSlope.degreesByCell(values, cellOf, cellCols, cellRows, 10.0, 10.0).forEach { assertEquals(10f, it, 0.01f) }
    }

    @Test
    fun `every pixel of a cell takes the slope of its cell among the cells`() {
        // An uneven seabed in cells 8 m by 12 m, each drawn two pixels wide and three tall.
        val cellCols = 6
        val cellRows = 5
        val cells = grid(cellCols, cellRows) { col, row -> -(col * col * 0.3f + row * 1.7f + (col * row % 3)) }
        val expected = SeabedSlope.degrees(cells, cellCols, cellRows, 8.0, 12.0)
        val cols = cellCols * 2
        val rows = cellRows * 3
        val cellOf = IntArray(cols * rows) { (it / cols) / 3 * cellCols + (it % cols) / 2 }
        val values = FloatArray(cols * rows) { cells[cellOf[it]] }

        val slopes = SeabedSlope.degreesByCell(values, cellOf, cellCols, cellRows, 8.0, 12.0)
        for (i in slopes.indices) assertEquals(expected[cellOf[i]], slopes[i], 0f)
        assertTrue(slopes.toSet().size > 10)
    }

    @Test
    fun `a pixel that shows no cell, or has no depth, has no slope`() {
        val cellCols = 4
        val cellRows = 3
        val cols = cellCols * 2
        val rows = cellRows * 2
        val values = grid(cols, rows) { col, _ -> (-5.0 - col / 2 * drop).toFloat() }
        val cellOf = IntArray(cols * rows) { (it / cols) / 2 * cellCols + (it % cols) / 2 }
        // The top-left pixel is off the survey; the cell at 2, 1 has no depth.
        cellOf[0] = SeabedSlope.NO_CELL
        for (i in values.indices) if (cellOf[i] == 1 * cellCols + 2) values[i] = Float.NaN

        val slopes = SeabedSlope.degreesByCell(values, cellOf, cellCols, cellRows, 10.0, 10.0)
        for (i in slopes.indices) {
            if (i == 0 || cellOf[i] == 1 * cellCols + 2) assertTrue(slopes[i].isNaN()) else assertEquals(10f, slopes[i], 0.01f)
        }
    }

    @Test
    fun `a cell with nothing shown either side of it takes that part of its slope from the next row`() {
        // The tip of a turned survey's corner: one cell of the top row is in the picture, and all three of the next.
        val eastward = floatArrayOf((-5.0 - drop).toFloat(), -5f, (-5.0 - drop).toFloat(), (-5.0 - 2 * drop).toFloat())
        val across = SeabedSlope.degreesByCell(eastward, intArrayOf(1, 3, 4, 5), 3, 2, 10.0, 10.0)
        across.forEach { assertEquals(10f, it, 0.01f) }

        // The same on its side: one cell of the left column, all three of the right, falling to the south.
        val southward = floatArrayOf(-5f, (-5.0 - drop).toFloat(), (-5.0 - drop).toFloat(), (-5.0 - 2 * drop).toFloat())
        val down = SeabedSlope.degreesByCell(southward, intArrayOf(1, 2, 3, 5), 2, 3, 10.0, 10.0)
        down.forEach { assertEquals(10f, it, 0.01f) }

        // A cell all on its own has nothing to measure against: flat, as before.
        val alone = SeabedSlope.degreesByCell(floatArrayOf(-7f), intArrayOf(4), 3, 3, 10.0, 10.0)
        assertEquals(0f, alone[0], 0f)
    }
}
