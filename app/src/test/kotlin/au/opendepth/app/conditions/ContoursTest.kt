package au.opendepth.app.conditions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContoursTest {
    private val axis = DoubleArray(5) { it.toDouble() }
    private val grid = GeoGrid(axis, axis)

    @Test
    fun straightLineThroughARamp() {
        val values = FloatArray(25) { (it % 5).toFloat() } // rises to the east
        val lines = Contours.lines(grid, values, doubleArrayOf(2.5))
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(5, line.size)
        for (i in 0 until line.size) assertEquals(2.5, line.points[2 * i], 1e-9)
    }

    @Test
    fun ringAroundAPeakCloses() {
        val values = FloatArray(25) { i -> (10 - hypot(i % 5 - 2.0, i / 5 - 2.0) * 3).toFloat() }
        val lines = Contours.lines(grid, values, doubleArrayOf(7.0))
        assertEquals(1, lines.size)
        val p = lines[0].points
        assertEquals(p[0], p[p.size - 2], 1e-9)
        assertEquals(p[1], p[p.size - 1], 1e-9)
    }

    @Test
    fun landBreaksLines() {
        val values = FloatArray(25) { (it % 5).toFloat() }
        for (c in 0 until 5) values[2 * 5 + c] = Float.NaN
        val lines = Contours.lines(grid, values, doubleArrayOf(2.5), minPoints = 2)
        assertEquals(2, lines.size)
    }

    @Test
    fun levelsAndSteps() {
        assertArrayEquals(doubleArrayOf(21.0, 21.5, 22.0), Contours.levels(20.8, 22.2, 0.5), 1e-9)
        assertEquals(0.25, Contours.autoStep(2.0), 1e-9)
        assertEquals(1.0, Contours.autoStep(9.0), 1e-9)
        assertEquals(2.0, Contours.autoStep(20.0), 1e-9)
    }

    @Test
    fun oneEddyPerPeak() {
        val values = FloatArray(25) { i -> if (i == 12) 25f else if (i == 6) -15f else 2f }
        val eddies = Contours.eddies(grid, values, radius = 1)
        assertTrue(eddies.any { it.warm && it.lat == 2.0 && it.lng == 2.0 })
        assertTrue(eddies.any { !it.warm && it.lat == 1.0 && it.lng == 1.0 })
        // Beside land or on the edge: none.
        values[13] = Float.NaN
        assertTrue(Contours.eddies(grid, values, radius = 1).none { it.warm })
        values[0] = -40f
        assertTrue(Contours.eddies(grid, values, radius = 1).none { it.lat == 0.0 })
    }

    @Test
    fun geoJsonHasKinds() {
        val values = FloatArray(25) { (it % 5).toFloat() }
        val json = Contours.geoJson(Contours.lines(grid, values, doubleArrayOf(2.5)).map { it to ("temp" to "2.5°") })
        assertTrue(json.contains("\"kind\":\"temp\""))
        assertTrue(json.contains("[2.5000,0.0000]"))
    }
}
