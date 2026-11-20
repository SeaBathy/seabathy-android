package au.opendepth.app.geotiff

import au.opendepth.app.model.DepthRampSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A whole depth layer's picture is stretched by the map evenly in Web
 * Mercator, so its rows are laid out that way when it is written, each
 * showing the depths at the latitude it is drawn at.
 */
class MercatorRowsTest {

    private fun identity(rows: Int) = IntArray(rows) { it }

    /** Web Mercator's height for a latitude and back, by other formulas than the renderer's. */
    private fun mercator(lat: Double) = asinh(tan(Math.toRadians(lat)))
    private fun latitude(y: Double) = Math.toDegrees(atan(sinh(y)))

    /** The row of depths at the latitude where the middle of picture row [j] is drawn. */
    private fun expectedRow(j: Int, rows: Int, north: Double, south: Double): Int {
        val top = mercator(north)
        val lat = latitude(top - (j + 0.5) / rows * (top - mercator(south)))
        return floor((north - lat) / (north - south) * rows).toInt().coerceIn(0, rows - 1)
    }

    @Test
    fun `a small layer on the equator is drawn row for row`() {
        assertArrayEquals(identity(200), DepthGridRenderer.mercatorRows(200, north = 0.01, south = -0.01))
        assertArrayEquals(identity(1600), DepthGridRenderer.mercatorRows(1600, north = 0.05, south = -0.05))
    }

    @Test
    fun `the middle of a layer a degree tall at 23 south moves about 100 m, towards the equator`() {
        // Fine rows, to measure by: 16000 to the degree is about 7 m each.
        val rows = 16_000
        val map = DepthGridRenderer.mercatorRows(rows, north = -22.5, south = -23.5)
        val metresPerRow = 111_320.0 / rows

        // Each picture row shows depths from further south than before, so what is drawn moves north;
        // by most in the middle, by nothing at the top and bottom.
        for (j in 0 until rows) assertTrue("row $j shows ${map[j]}", map[j] >= j)
        assertEquals(0, map[0])
        assertEquals(rows - 1, map[rows - 1])
        val most = (0 until rows).maxOf { map[it] - it }
        assertEquals(103.0, most * metresPerRow, 6.0)
        assertEquals(most.toDouble(), (map[rows / 2] - rows / 2).toDouble(), 1.0)
        // A quarter of the way down, three quarters of that.
        assertEquals(most * 0.75, (map[rows / 4] - rows / 4).toDouble(), 1.5)
    }

    @Test
    fun `every row shows the depths at the latitude it is drawn at`() {
        for ((north, south) in listOf(-22.5 to -23.5, 23.5 to 22.5, -10.0 to -44.0, 61.0 to 54.5, 3.0 to -9.0)) {
            val rows = 1600
            val map = DepthGridRenderer.mercatorRows(rows, north, south)
            for (j in 0 until rows) {
                // To the row, give or take one where a latitude falls on the line between two.
                assertTrue("row $j of $north to $south", abs(map[j] - expectedRow(j, rows, north, south)) <= 1)
            }
        }
        // The imported size of a layer a degree tall: the middle is a row or two out, 70 to 140 m.
        val south = DepthGridRenderer.mercatorRows(1600, north = -22.5, south = -23.5)
        assertEquals(801, south[800])
    }

    @Test
    fun `north of the equator the rows move the other way by as much`() {
        val rows = 16_000
        val south = DepthGridRenderer.mercatorRows(rows, north = -22.5, south = -23.5)
        val north = DepthGridRenderer.mercatorRows(rows, north = 23.5, south = 22.5)

        for (j in 0 until rows) {
            assertTrue("row $j shows ${north[j]}", north[j] <= j)
            // The same layer upside down.
            assertTrue(abs(north[j] - (rows - 1 - south[rows - 1 - j])) <= 1)
        }
        assertEquals((0 until rows).maxOf { south[it] - it }.toDouble(), (0 until rows).maxOf { it - north[it] }.toDouble(), 1.0)
    }

    @Test
    fun `a layer across the equator keeps its middle and moves each half towards it`() {
        val rows = 2000
        val map = DepthGridRenderer.mercatorRows(rows, north = 20.0, south = -20.0)

        assertEquals(rows / 2 - 1, map[rows / 2 - 1])
        assertEquals(rows / 2, map[rows / 2])
        for (j in 0 until rows / 2) assertTrue(map[j] <= j)
        for (j in rows / 2 until rows) assertTrue(map[j] >= j)
        assertTrue((0 until rows).maxOf { abs(map[it] - it) } > 3)
    }

    @Test
    fun `rows stay inside the layer and never go back up it`() {
        val layers = listOf(
            Triple(1600, -22.5, -23.5),
            Triple(1600, 23.5, 22.5),
            Triple(1201, -10.0, -60.0),
            Triple(977, 72.0, 30.0),
            Triple(500, 40.0, -40.0),
            // Past where the web map ends.
            Triple(300, 90.0, 60.0),
            Triple(300, -70.0, -90.0),
            Triple(2, 10.0, 9.0),
            Triple(3, -1.0, -2.0)
        )
        for ((rows, north, south) in layers) {
            val map = DepthGridRenderer.mercatorRows(rows, north, south)
            assertEquals(rows, map.size)
            for (j in 0 until rows) {
                assertTrue("row $j of $north to $south shows ${map[j]}", map[j] in 0 until rows)
                if (j > 0) assertTrue("row $j of $north to $south goes back", map[j] >= map[j - 1])
            }
        }
    }

    @Test
    fun `a layer with no height, or of one row, is left as it is`() {
        assertArrayEquals(identity(40), DepthGridRenderer.mercatorRows(40, north = -23.0, south = -23.0))
        assertArrayEquals(identity(40), DepthGridRenderer.mercatorRows(40, north = -24.0, south = -23.0))
        assertArrayEquals(identity(40), DepthGridRenderer.mercatorRows(40, north = Double.NaN, south = -23.0))
        assertArrayEquals(identity(1), DepthGridRenderer.mercatorRows(1, north = -22.0, south = -23.0))
        assertArrayEquals(IntArray(0), DepthGridRenderer.mercatorRows(0, north = -22.0, south = -23.0))
    }

    // ---------------------------------------------------------------- written into the picture

    /** The picture as the bitmap gets it: each run of rows copied where [DepthGridRenderer.forEachRun] says. */
    private fun laidOut(pixels: IntArray, cols: Int, rowMap: IntArray): IntArray {
        val picture = IntArray(cols * rowMap.size) { -1 }
        DepthGridRenderer.forEachRun(rowMap) { from, to, count ->
            System.arraycopy(pixels, from * cols, picture, to * cols, count * cols)
        }
        return picture
    }

    @Test
    fun `rows that follow on are written together, all at once where nothing moved`() {
        val runs = ArrayList<List<Int>>()
        DepthGridRenderer.forEachRun(identity(1600)) { from, to, count -> runs += listOf(from, to, count) }
        assertEquals(listOf(listOf(0, 0, 1600)), runs)

        // A row shown twice, then one left out.
        runs.clear()
        DepthGridRenderer.forEachRun(intArrayOf(0, 1, 1, 2, 4, 5)) { from, to, count -> runs += listOf(from, to, count) }
        assertEquals(listOf(listOf(0, 0, 2), listOf(1, 2, 2), listOf(4, 4, 2)), runs)

        runs.clear()
        DepthGridRenderer.forEachRun(IntArray(0)) { from, to, count -> runs += listOf(from, to, count) }
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `each row of the picture is the row of colours the map says, whole`() {
        val cols = 7
        val rows = 900
        val map = DepthGridRenderer.mercatorRows(rows, north = -12.0, south = -31.0)
        // Every cell its own number.
        val pixels = IntArray(cols * rows) { it }
        val picture = laidOut(pixels, cols, map)

        for (j in 0 until rows) {
            for (x in 0 until cols) assertEquals(map[j] * cols + x, picture[j * cols + x])
        }
    }

    @Test
    fun `colours, shading, slope colours and shallow marks all move together`() {
        // A seabed that deepens southwards, shaded and marked, 40 rows over 30 degrees so rows really move.
        val cols = 6
        val rows = 40
        val values = FloatArray(cols * rows) { -0.5f - (it / cols) * 0.5f }
        val shade = FloatArray(cols * rows) { 0.6f + (it / cols) * 0.01f }
        val slopes = FloatArray(cols * rows) { (it / cols).toFloat() }
        val map = DepthGridRenderer.mercatorRows(rows, north = -20.0, south = -50.0)
        assertTrue(map.indices.any { map[it] != it })

        for (ramp in listOf(
            DepthRampSettings(shallowMetres = 0.0, deepMetres = -25.0, hillshadeExaggeration = 1.0),
            DepthRampSettings(shallowMetres = 0.0, deepMetres = -25.0, colourBySlope = true, slopeMaxDegrees = 40.0)
        )) {
            val pixels = IntArray(values.size)
            DepthGridRenderer.paint(values, cols, ramp, pixels, shade, slopes, shallowMetres = 3.0, stripePeriod = 8)
            val picture = laidOut(pixels, cols, map)

            // Picture row j is, cell for cell, what was painted for the row of depths it shows.
            for (j in 0 until rows) {
                for (x in 0 until cols) assertEquals(pixels[map[j] * cols + x], picture[j * cols + x])
            }
            assertTrue(picture.toSet().size > rows / 2)
        }
    }

    // ---------------------------------------------------------------- the red rim on rows left out

    private val deep = DepthRampSettings(shallowMetres = -10.0, deepMetres = -30.0, hillshadeExaggeration = 0.0)

    private fun painted(values: FloatArray, cols: Int, rowMap: IntArray, shallowMetres: Double = 2.0): IntArray {
        val pixels = IntArray(values.size)
        DepthGridRenderer.paint(values, cols, deep, pixels, shallowMetres = shallowMetres, stripePeriod = 8)
        DepthGridRenderer.keepSkippedRims(values, cols, pixels, rowMap, shallowMetres)
        return pixels
    }

    @Test
    fun `a shoal one row tall on a row left out is drawn on the next row shown`() {
        val cols = 10
        val values = FloatArray(cols * 6) { if (it / cols == 2 && it % cols in 3..6) -0.5f else -20f }
        // Row 2 is left out; row 5 is shown twice.
        val map = intArrayOf(0, 1, 3, 4, 5, 5)
        val picture = laidOut(painted(values, cols, map), cols, map)

        for (x in 0 until cols) {
            assertEquals("column $x", if (x in 3..6) ShallowWater.RED else picture[x], picture[2 * cols + x])
        }
        assertEquals(4, picture.count { it == ShallowWater.RED })
    }

    @Test
    fun `a shoal whose edge row is left out keeps its outline whole`() {
        val cols = 12
        val rows = 10
        val values = FloatArray(cols * rows) { if (it / cols in 2..7 && it % cols in 2..9) -0.5f else -20f }
        // The shoal's top row, 2, is left out.
        val map = intArrayOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 9)
        val picture = laidOut(painted(values, cols, map), cols, map)

        // Its outline is now along picture row 2 (the depths' row 3), all the way across.
        for (x in 2..9) assertEquals("column $x", ShallowWater.RED, picture[2 * cols + x])
        // And down both sides and along the bottom as before.
        for (j in 2..6) {
            assertEquals(ShallowWater.RED, picture[j * cols + 2])
            assertEquals(ShallowWater.RED, picture[j * cols + 9])
        }
        for (x in 2..9) assertEquals(ShallowWater.RED, picture[6 * cols + x])
    }

    @Test
    fun `rows left out after the last one shown stand on it`() {
        val cols = 5
        val values = FloatArray(cols * 5) { if (it / cols == 4 && it % cols == 2) -0.5f else -20f }
        val map = intArrayOf(0, 1, 2, 3)
        val pixels = painted(values, cols, map)

        assertEquals(ShallowWater.RED, pixels[3 * cols + 2])
    }

    @Test
    fun `nothing is added where no row is left out, or with the mark off`() {
        val cols = 10
        val values = FloatArray(cols * 6) { if (it / cols == 2 && it % cols in 3..6) -0.5f else -20f }
        val plain = IntArray(values.size)
        DepthGridRenderer.paint(values, cols, deep, plain, shallowMetres = 2.0, stripePeriod = 8)

        assertArrayEquals(plain, painted(values, cols, identity(6)))
        // Rows shown twice leave none out either.
        assertArrayEquals(plain, painted(values, cols, intArrayOf(0, 0, 1, 2, 3, 4, 5)))

        val off = IntArray(values.size)
        DepthGridRenderer.paint(values, cols, deep, off)
        assertArrayEquals(off, painted(values, cols, intArrayOf(0, 1, 3, 4, 5, 5), shallowMetres = 0.0))
    }

    @Test
    fun `on a tall layer a small shoal on any row at all is still red in the picture`() {
        val cols = 9
        val rows = 400
        val map = DepthGridRenderer.mercatorRows(rows, north = -10.0, south = -60.0)
        val shown = map.toSet()
        val leftOut = (0 until rows).filter { it !in shown }
        assertTrue("rows are left out of a layer this tall", leftOut.size > 10)

        for (row in listOf(leftOut.first(), leftOut[leftOut.size / 2], leftOut.last(), 0, rows - 1, map[rows / 2])) {
            // One cell, and nothing else shallow in the layer.
            val values = FloatArray(cols * rows) { if (it == row * cols + 4) -0.5f else -20f }
            val picture = laidOut(painted(values, cols, map), cols, map)

            assertTrue("a shoal on row $row", picture.any { it == ShallowWater.RED })
            // In its own column, and nowhere else.
            for (i in picture.indices) if (picture[i] == ShallowWater.RED) assertEquals(4, i % cols)
        }
    }
}
