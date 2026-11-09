package au.opendepth.app.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WebMercatorTilesTest {

    @Test
    fun `the single zoom-0 tile covers the whole standard Web Mercator extent`() {
        val bounds = WebMercatorTiles.tileBounds(0, 0, 0)!!

        assertTrue(abs(bounds.west - -180.0) < 1e-6)
        assertTrue(abs(bounds.east - 180.0) < 1e-6)
        // The standard EPSG:3857 clamp latitude, not +/-90.
        assertTrue(abs(bounds.north - 85.05112878) < 1e-4)
        assertTrue(abs(bounds.south - -85.05112878) < 1e-4)
    }

    @Test
    fun `an out-of-range x or y is rejected`() {
        assertNull(WebMercatorTiles.tileBounds(2, 4, 0))
        assertNull(WebMercatorTiles.tileBounds(2, 0, 4))
        assertNull(WebMercatorTiles.tileBounds(2, -1, 0))
        assertNull(WebMercatorTiles.tileBounds(-1, 0, 0))
    }

    @Test
    fun `y=0 is the north edge and tiles at a zoom tile the world without gaps`() {
        val northWest = WebMercatorTiles.tileBounds(1, 0, 0)!!
        val northEast = WebMercatorTiles.tileBounds(1, 1, 0)!!
        val southWest = WebMercatorTiles.tileBounds(1, 0, 1)!!

        // y=0 sits at the north edge, not the south.
        assertTrue(northWest.north > northWest.south)
        assertTrue(abs(northWest.north - 85.05112878) < 1e-4)
        assertTrue(abs(southWest.south - -85.05112878) < 1e-4)

        // Adjacent tiles share an edge to within the sub-millimetre nudge
        // tileBounds applies at a projected boundary (see its doc) -- no
        // gap or overlap a real tile request could ever notice.
        assertTrue(abs(northWest.east - northEast.west) < 1e-6)
        assertTrue(abs(northWest.south - southWest.north) < 1e-6)
    }

    @Test
    fun `ground resolution halves with every zoom level, matching the well-known zoom-0 constant`() {
        assertTrue(abs(WebMercatorTiles.metresPerPixelAtZoom(0) - 156543.03392804097) < 1e-3)
        assertTrue(
            abs(WebMercatorTiles.metresPerPixelAtZoom(1) - WebMercatorTiles.metresPerPixelAtZoom(0) / 2.0) < 1e-6
        )
    }

    @Test
    fun `native max zoom is the coarsest zoom whose resolution is at least as fine as the source`() {
        val sourceMetresPerPixel = WebMercatorTiles.metresPerPixelAtZoom(14)
        val zoom = WebMercatorTiles.nativeMaxZoomForGroundResolution(sourceMetresPerPixel)

        assertEquals(14, zoom)
    }

    @Test
    fun `a finer source resolution than any sane zoom is capped, not left unbounded`() {
        val zoom = WebMercatorTiles.nativeMaxZoomForGroundResolution(0.0001)
        assertEquals(WebMercatorTiles.MAX_SANE_ZOOM, zoom)
    }

    @Test
    fun `a non-finite or non-positive resolution falls back to the sane maximum rather than crashing`() {
        assertEquals(WebMercatorTiles.MAX_SANE_ZOOM, WebMercatorTiles.nativeMaxZoomForGroundResolution(0.0))
        assertEquals(WebMercatorTiles.MAX_SANE_ZOOM, WebMercatorTiles.nativeMaxZoomForGroundResolution(-5.0))
        assertEquals(WebMercatorTiles.MAX_SANE_ZOOM, WebMercatorTiles.nativeMaxZoomForGroundResolution(Double.NaN))
    }

    @Test
    fun `the whole world at zoom 0 covers exactly the single 0,0 tile`() {
        val range = WebMercatorTiles.tileRangeCovering(WebMercatorTiles.tileBounds(0, 0, 0)!!, 0)

        assertEquals(0, range.minX)
        assertEquals(0, range.maxX)
        assertEquals(0, range.minY)
        assertEquals(0, range.maxY)
        assertEquals(1L, range.tileCount)
    }

    @Test
    fun `a single tile's own bounds cover exactly that tile, round-tripped through tileBounds`() {
        val bounds = WebMercatorTiles.tileBounds(3, 5, 2)!!

        val range = WebMercatorTiles.tileRangeCovering(bounds, 3)

        assertEquals(5, range.minX)
        assertEquals(5, range.maxX)
        assertEquals(2, range.minY)
        assertEquals(2, range.maxY)
        assertEquals(1L, range.tileCount)
    }

    @Test
    fun `bounds spanning two adjacent tiles cover both, not more or fewer`() {
        val left = WebMercatorTiles.tileBounds(3, 5, 2)!!
        val right = WebMercatorTiles.tileBounds(3, 6, 2)!!
        val spanning = au.opendepth.app.model.GeoBounds(
            west = left.west,
            south = minOf(left.south, right.south),
            east = right.east,
            north = maxOf(left.north, right.north)
        )

        val range = WebMercatorTiles.tileRangeCovering(spanning, 3)

        assertEquals(5, range.minX)
        assertEquals(6, range.maxX)
        assertEquals(2, range.minY)
        assertEquals(2, range.maxY)
        assertEquals(2L, range.tileCount)
    }

    @Test
    fun `tile count multiplies the x and y span sizes`() {
        val range = WebMercatorTiles.TileRange(z = 5, minX = 10, maxX = 14, minY = 20, maxY = 22)

        assertEquals(15L, range.tileCount)
    }

    @Test
    fun `a bounding box is clamped to the valid tile grid at very coarse zooms`() {
        val worldish = au.opendepth.app.model.GeoBounds(west = -180.0, south = -85.0, east = 180.0, north = 85.0)

        val range = WebMercatorTiles.tileRangeCovering(worldish, 1)

        assertEquals(0, range.minX)
        assertEquals(1, range.maxX)
        assertEquals(0, range.minY)
        assertEquals(1, range.maxY)
        assertEquals(4L, range.tileCount)
    }
}
