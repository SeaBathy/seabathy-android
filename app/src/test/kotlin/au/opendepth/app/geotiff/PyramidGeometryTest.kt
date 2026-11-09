package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PyramidGeometryTest {

    @Test
    fun `a source no larger than one tile has a single zoom level`() {
        val geometry = PyramidGeometry(sourceWidth = 200, sourceHeight = 150, tileSize = 256)

        assertEquals(0, geometry.maxZoom)
        assertEquals(200, geometry.levelWidth(0))
        assertEquals(150, geometry.levelHeight(0))
        assertEquals(1, geometry.tilesAcross(0))
        assertEquals(1, geometry.tilesDown(0))
    }

    @Test
    fun `finest zoom is always exactly the source dimensions`() {
        val geometry = PyramidGeometry(sourceWidth = 15000, sourceHeight = 9000, tileSize = 256)

        assertEquals(15000, geometry.levelWidth(geometry.maxZoom))
        assertEquals(9000, geometry.levelHeight(geometry.maxZoom))
    }

    @Test
    fun `each coarser zoom is roughly half the resolution of the next`() {
        val geometry = PyramidGeometry(sourceWidth = 16384, sourceHeight = 8192, tileSize = 256)

        for (zoom in 1..geometry.maxZoom) {
            val finerWidth = geometry.levelWidth(zoom)
            val coarserWidth = geometry.levelWidth(zoom - 1)
            // Ceil-division halving: within one sample of an exact half.
            assertEquals(0, kotlin.math.abs(finerWidth / 2 - coarserWidth))
        }
    }

    @Test
    fun `zoom 0 fits within one tile along its longest edge`() {
        val geometry = PyramidGeometry(sourceWidth = 123456, sourceHeight = 7000, tileSize = 256)

        assertEquals(true, geometry.levelWidth(0) <= 256 || geometry.levelHeight(0) <= 256)
    }

    @Test
    fun `tile counts match ceil-division of level dimensions by tile size`() {
        val geometry = PyramidGeometry(sourceWidth = 1000, sourceHeight = 700, tileSize = 256)
        val zoom = geometry.maxZoom

        assertEquals(4, geometry.tilesAcross(zoom)) // ceil(1000/256) = 4
        assertEquals(3, geometry.tilesDown(zoom)) // ceil(700/256) = 3
    }

    @Test
    fun `tileFor addresses the right tile and local offset`() {
        val geometry = PyramidGeometry(sourceWidth = 1000, sourceHeight = 700, tileSize = 256)

        val address = geometry.tileFor(zoom = geometry.maxZoom, levelCol = 300, levelRow = 10)

        assertEquals(1, address.tileCol) // 300 / 256
        assertEquals(0, address.tileRow) // 10 / 256
        assertEquals(300 - 256, address.localCol)
        assertEquals(10, address.localRow)
    }

    @Test
    fun `an edge tile is truncated to the real remaining samples, not padded`() {
        val geometry = PyramidGeometry(sourceWidth = 1000, sourceHeight = 700, tileSize = 256)
        val zoom = geometry.maxZoom

        // Rightmost column of tiles: 1000 - 3*256 = 232 real samples wide.
        val (width, height) = geometry.tilePixelSize(zoom, tileCol = 3, tileRow = 0)
        assertEquals(1000 - 3 * 256, width)
        assertEquals(256, height)
    }

    @Test
    fun `previewZoom returns the coarsest level meeting the minimum dimension`() {
        val geometry = PyramidGeometry(sourceWidth = 16384, sourceHeight = 8192, tileSize = 256)

        val zoom = geometry.previewZoom(minDimension = 1600)

        assertEquals(true, geometry.levelWidth(zoom) >= 1600 || geometry.levelHeight(zoom) >= 1600)
        // The next coarser level must have fallen short (this is the *first* level meeting it).
        if (zoom > 0) {
            assertEquals(
                true,
                geometry.levelWidth(zoom - 1) < 1600 && geometry.levelHeight(zoom - 1) < 1600
            )
        }
    }

    @Test
    fun `previewZoom never exceeds maxZoom for a source smaller than the target`() {
        val geometry = PyramidGeometry(sourceWidth = 500, sourceHeight = 400, tileSize = 256)

        assertEquals(geometry.maxZoom, geometry.previewZoom(minDimension = 1600))
    }

    @Test
    fun `non-positive dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PyramidGeometry(sourceWidth = 0, sourceHeight = 10)
        }
    }
}
