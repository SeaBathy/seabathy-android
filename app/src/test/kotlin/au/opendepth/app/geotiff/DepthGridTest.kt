package au.opendepth.app.geotiff

import au.opendepth.app.model.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthGridTest {

    /**
     * A two-by-two grid spanning one degree in each direction:
     *
     *     -10  -20
     *     -30  no data
     */
    private val grid = DepthGrid(
        bounds = GeoBounds(west = 147.0, south = -19.0, east = 148.0, north = -18.0),
        cols = 2,
        rows = 2,
        values = floatArrayOf(-10f, -20f, -30f, DepthGrid.NO_DATA),
        shallowestMetres = -10.0,
        deepestMetres = -30.0,
        coveredCells = 3
    )

    @Test
    fun `each quadrant reads its own cell`() {
        assertEquals(-10.0, grid.sampleMetres(-18.25, 147.25)!!, 1e-6)
        assertEquals(-20.0, grid.sampleMetres(-18.25, 147.75)!!, 1e-6)
        assertEquals(-30.0, grid.sampleMetres(-18.75, 147.25)!!, 1e-6)
    }

    @Test
    fun `no data cells read as unknown`() {
        assertNull(grid.sampleMetres(-18.75, 147.75))
    }

    @Test
    fun `points outside the bounds read as unknown`() {
        assertNull(grid.sampleMetres(-17.0, 147.5))
        assertNull(grid.sampleMetres(-18.5, 150.0))
    }

    @Test
    fun `the far edges clamp inside the grid rather than overflowing`() {
        assertEquals(-30.0, grid.sampleMetres(-19.0, 147.0)!!, 1e-6)
        assertEquals(-10.0, grid.sampleMetres(-18.0, 147.0)!!, 1e-6)
    }

    @Test
    fun `coverage is reported`() {
        assertTrue(grid.hasData)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a value count that does not match the size is rejected`() {
        DepthGrid(
            bounds = GeoBounds(147.0, -19.0, 148.0, -18.0),
            cols = 2,
            rows = 2,
            values = floatArrayOf(-1f),
            shallowestMetres = -1.0,
            deepestMetres = -1.0,
            coveredCells = 1
        )
    }
}
