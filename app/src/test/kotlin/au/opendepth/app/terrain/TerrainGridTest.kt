package au.opendepth.app.terrain

import au.opendepth.app.geotiff.DepthGrid
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.geotiff.InMemoryDepthGrid
import au.opendepth.app.model.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainGridTest {

    // 0.1° square off Sydney, depth getting deeper eastwards from -10 to -110 m.
    private val bounds = GeoBounds(west = 151.3, south = -33.95, east = 151.4, north = -33.85)

    private fun rampSource(cols: Int = 100, rows: Int = 100): InMemoryDepthGrid {
        val values = FloatArray(cols * rows) { i -> -10f - (i % cols).toFloat() }
        return InMemoryDepthGrid(DepthGrid(bounds, cols, rows, values, -10.0, -109.0, cols * rows))
    }

    @Test
    fun `cells are roughly square in metres`() {
        val grid = TerrainGridSampler.sample(rampSource(), bounds, maxDim = 64)!!
        // 0.1° of longitude at 34°S is ~9.2 km, 0.1° of latitude ~11.1 km.
        assertTrue(grid.rows > grid.cols)
        assertEquals(grid.cellXMetres, grid.cellYMetres, grid.cellYMetres * 0.05)
        assertEquals(11_057.0, grid.cellYMetres * grid.rows, 50.0)
    }

    @Test
    fun `depths outside the range become no-data and set the min and max`() {
        val grid = TerrainGridSampler.sample(rampSource(), bounds, maxDim = 64, range = -60.0..-30.0)!!
        assertTrue(grid.values.any { it.isNaN() })
        assertTrue(grid.values.filter { !it.isNaN() }.all { it in -60f..-30f })
        assertTrue(grid.minMetres >= -60.0 && grid.maxMetres <= -30.0)
    }

    @Test
    fun `an in-memory source is not sampled much finer than its own data`() {
        val coarse = rampSource(cols = 20, rows = 20)
        val grid = TerrainGridSampler.sample(coarse, bounds, maxDim = 1024)!!
        assertTrue("cols=${grid.cols}", grid.cols <= 40)
    }

    @Test
    fun `bilinear sampling interpolates between source cells`() {
        val two = InMemoryDepthGrid(DepthGrid(bounds, 2, 1, floatArrayOf(-10f, -20f), -10.0, -20.0, 2))
        val grid = TerrainGridSampler.sample(two, bounds, maxDim = 4)!!
        val row = grid.values.copyOfRange(0, grid.cols)
        // Strictly between the two source values somewhere in the middle, not a hard step.
        assertTrue(row.any { it < -10f && it > -20f })
    }

    @Test
    fun `isolate bounds cover the in-range cells and stay inside the grid`() {
        val grid = TerrainGridSampler.sample(rampSource(), bounds, maxDim = 100)!!
        val area = TerrainGridSampler.boundsWithin(grid, -40.0..-20.0)!!
        assertTrue(area.west >= bounds.west && area.east <= bounds.east)
        assertTrue(area.east - area.west < (bounds.east - bounds.west) * 0.5)
        assertEquals(bounds.north, area.north, 1e-9)
        assertEquals(bounds.south, area.south, 1e-9)
    }

    @Test
    fun `nothing in range gives null`() {
        val grid = TerrainGridSampler.sample(rampSource(), bounds, maxDim = 32)!!
        assertNull(TerrainGridSampler.boundsWithin(grid, -500.0..-400.0))
        assertNull(TerrainGridSampler.sample(rampSource(), bounds, maxDim = 32, range = -500.0..-400.0))
    }

    @Test
    fun `other sources are sampled through sampleMetres`() {
        val source = object : DepthSource {
            override val bounds = this@TerrainGridTest.bounds
            override val shallowestMetres = -5.0
            override val deepestMetres = -5.0
            override val hasData = true
            override fun sampleMetres(lat: Double, lng: Double): Double? = -5.0
            override fun previewGrid(): DepthGrid = throw UnsupportedOperationException()
        }
        val grid = TerrainGridSampler.sample(source, bounds, maxDim = 16)
        assertNotNull(grid)
        assertTrue(grid!!.values.all { it == -5f })
    }

    @Test
    fun `a ready-read depth grid keeps its cells and drops out-of-range depths`() {
        val depth = DepthGrid(bounds, 4, 2, floatArrayOf(-5f, -10f, -15f, Float.NaN, -20f, -25f, -30f, -35f), -5.0, -35.0, 7)
        val all = TerrainGridSampler.fromDepthGrid(depth)!!
        assertEquals(4, all.cols)
        assertEquals(-35.0, all.minMetres, 1e-9)
        assertEquals(-5.0, all.maxMetres, 1e-9)

        val band = TerrainGridSampler.fromDepthGrid(depth, -26.0..-12.0)!!
        assertEquals(3, band.values.count { !it.isNaN() })
        assertEquals(-25.0, band.minMetres, 1e-9)
        assertEquals(-15.0, band.maxMetres, 1e-9)
        // The source grid itself is left untouched.
        assertEquals(-5f, depth.values[0], 0f)
        assertNull(TerrainGridSampler.fromDepthGrid(depth, -100.0..-90.0))
    }
}
