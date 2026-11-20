package au.opendepth.app.geotiff

import au.opendepth.app.model.DepthRampSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.tan

class SlopeColourTest {
    private val flatBlue = DepthPalette.SLOPE.stops.first()
    private val steepRed = DepthPalette.SLOPE.stops.last()

    /** No shading, so a colour is exactly the palette's. */
    private val bySlope = DepthRampSettings(
        shallowMetres = 0.0, deepMetres = -100.0, hillshadeExaggeration = 0.0, colourBySlope = true
    )

    /** Ten-metre cells, a flat shelf at 20 m for the first [flat] columns, then falling [degrees] to the east. */
    private fun shelfAndSlope(cols: Int, rows: Int, flat: Int, degrees: Double): FloatArray {
        val drop = 10.0 * tan(Math.toRadians(degrees))
        return FloatArray(cols * rows) { i -> (-20.0 - maxOf(0, i % cols - flat) * drop).toFloat() }
    }

    private fun paint(values: FloatArray, cols: Int, ramp: DepthRampSettings): IntArray {
        val slopes = if (ramp.colourBySlope) SeabedSlope.degrees(values, cols, values.size / cols, 10.0, 10.0) else null
        return IntArray(values.size).also { DepthGridRenderer.paint(values, cols, ramp, it, slopes = slopes) }
    }

    @Test
    fun `flat seabed is blue and a slope just past the ramp's angle is red`() {
        val cols = 12
        val pixels = paint(shelfAndSlope(cols, 4, flat = 5, degrees = 20.5), cols, bySlope)

        // Well inside the shelf, and well down the slope: the cells at the join are part of each.
        assertEquals(flatBlue, pixels[cols + 2])
        assertEquals(steepRed, pixels[cols + 9])
    }

    @Test
    fun `steeper than the angle is still red, and gentler is in between`() {
        val cols = 12
        val cliff = paint(shelfAndSlope(cols, 4, flat = 5, degrees = 50.0), cols, bySlope)
        val gentle = paint(shelfAndSlope(cols, 4, flat = 5, degrees = 10.0), cols, bySlope)

        assertEquals(steepRed, cliff[cols + 9])
        assertNotEquals(flatBlue, gentle[cols + 9])
        assertNotEquals(steepRed, gentle[cols + 9])
    }

    @Test
    fun `the angle drawn red can be picked, and none saved means twenty degrees`() {
        val cols = 12
        val values = shelfAndSlope(cols, 4, flat = 5, degrees = 12.0)

        assertNotEquals(steepRed, paint(values, cols, bySlope)[cols + 9])
        assertEquals(steepRed, paint(values, cols, bySlope.copy(slopeMaxDegrees = 10.0))[cols + 9])
        assertEquals(steepRed, paint(values, cols, bySlope.copy(slopeMaxDegrees = 5.0))[cols + 9])
        // A ramp from before slope colours existed can carry 0 here.
        assertArrayEquals(paint(values, cols, bySlope), paint(values, cols, bySlope.copy(slopeMaxDegrees = 0.0)))
        assertEquals(20.0, bySlope.copy(slopeMaxDegrees = 0.0).steepestDegrees, 0.0)
    }

    @Test
    fun `the depth range still decides which depths are shown`() {
        val cols = 12
        // The shelf is at 20 m; this range starts below it.
        val pixels = paint(shelfAndSlope(cols, 4, flat = 5, degrees = 30.0), cols, bySlope.copy(shallowMetres = -25.0))

        assertEquals(0, pixels[cols + 2])
        assertEquals(steepRed, pixels[cols + 9])
    }

    @Test
    fun `reversing the colours is for depth palettes, not slope`() {
        val cols = 12
        val values = shelfAndSlope(cols, 4, flat = 5, degrees = 20.0)

        assertArrayEquals(paint(values, cols, bySlope), paint(values, cols, bySlope.copy(invert = true)))
    }

    @Test
    fun `colouring by depth is as it was`() {
        val byDepth = DepthRampSettings(shallowMetres = -10.0, deepMetres = -30.0, hillshadeExaggeration = 0.0)
        val rainbow = DepthPalette.byId("rainbow").stops
        val pixels = paint(floatArrayOf(-10f, -30f, -5f, -10f, -30f, -5f), 3, byDepth)

        assertEquals(rainbow.first(), pixels[0])
        assertEquals(rainbow.last(), pixels[1])
        assertEquals(0, pixels[2])
    }

    @Test
    fun `the legend shows the slope colours, flat to steep, when a layer is coloured by slope`() {
        val row = DepthGridRenderer.legendRow(bySlope.copy(invert = true), 64)

        assertEquals(flatBlue, row.first())
        assertEquals(steepRed, row.last())

        val byDepth = DepthGridRenderer.legendRow(bySlope.copy(colourBySlope = false, paletteId = "ocean"), 64)
        assertEquals(DepthPalette.byId("ocean").stops.first(), byDepth.first())
        assertEquals(DepthPalette.byId("ocean").stops.last(), byDepth.last())
    }
}
