package au.opendepth.app.geotiff

import au.opendepth.app.model.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [InMemoryDepthGrid] must delegate every [DepthSource] member unchanged. */
class InMemoryDepthGridTest {

    private val bounds = GeoBounds(west = 147.0, south = -19.0, east = 148.0, north = -18.0)

    private fun grid(): DepthGrid = DepthGrid(
        bounds = bounds,
        cols = 2,
        rows = 2,
        values = floatArrayOf(-5f, -10f, DepthGrid.NO_DATA, -20f),
        shallowestMetres = -5.0,
        deepestMetres = -20.0,
        coveredCells = 3
    )

    @Test
    fun `exposes the wrapped grid's bounds and extremes`() {
        val source = InMemoryDepthGrid(grid())

        assertEquals(bounds, source.bounds)
        assertEquals(-5.0, source.shallowestMetres, 0.0)
        assertEquals(-20.0, source.deepestMetres, 0.0)
        assertTrue(source.hasData)
    }

    @Test
    fun `previewGrid returns the same instance it was built from`() {
        val backing = grid()
        val source = InMemoryDepthGrid(backing)

        assertSame(backing, source.previewGrid())
    }

    @Test
    fun `sampleMetres delegates to the grid's own lookup`() {
        val source = InMemoryDepthGrid(grid())

        // Top-left cell of a north-up grid covering this bounds box.
        assertEquals(-5.0, source.sampleMetres(-18.1, 147.1)!!, 0.0)
        // Outside the grid's bounds entirely.
        assertNull(source.sampleMetres(0.0, 0.0))
    }

    @Test
    fun `hasData is false once every cell is no-data`() {
        val empty = DepthGrid(
            bounds = bounds,
            cols = 1,
            rows = 1,
            values = floatArrayOf(DepthGrid.NO_DATA),
            shallowestMetres = Double.NaN,
            deepestMetres = Double.NaN,
            coveredCells = 0
        )

        assertTrue(!InMemoryDepthGrid(empty).hasData)
    }
}
