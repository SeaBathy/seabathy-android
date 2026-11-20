package au.opendepth.app.download

import au.opendepth.app.model.DataSource
import au.opendepth.app.model.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDetailTest {

    /** About 11 km each way, off Moreton Bay. */
    private val bay = GeoBounds(west = 153.2, south = -27.4, east = 153.3, north = -27.3)

    /** About 1,100 km each way. */
    private val coast = GeoBounds(west = 145.0, south = -28.0, east = 155.0, north = -18.0)

    // The zooms saved ---------------------------------------------------------

    @Test
    fun `a detail at or past the usual first zoom is saved from that zoom in`() {
        assertEquals(10..14, OfflineDetail.zoomRange(14))
        assertEquals(10..17, OfflineDetail.zoomRange(17))
        assertEquals(10..10, OfflineDetail.zoomRange(10))
    }

    @Test
    fun `a lower detail is saved with the few zooms further out`() {
        assertEquals(5..9, OfflineDetail.zoomRange(9))
        assertEquals(0..3, OfflineDetail.zoomRange(3))
        assertEquals(0..0, OfflineDetail.zoomRange(0))
    }

    @Test
    fun `the tile count is the estimator's for those zooms`() {
        val expected = OfflineDownloadEstimator.estimateTiles(bay, 10..14).tileCount

        assertEquals(expected, OfflineDetail.tiles(bay, 14))
    }

    // The detail offered ------------------------------------------------------

    @Test
    fun `a small box is offered all the detail the map has`() {
        assertEquals(14, OfflineDetail.suggested(bay, top = 14))
        assertTrue(OfflineDetail.tiles(bay, 14) <= OfflineDetail.SUGGESTED_TILES)
    }

    @Test
    fun `a big box is offered the deepest detail that stays a sensible size`() {
        val offered = OfflineDetail.suggested(coast, top = 17)

        assertTrue(offered < 17)
        assertTrue(OfflineDetail.tiles(coast, offered) <= OfflineDetail.SUGGESTED_TILES)
        assertTrue(
            "one step deeper would be past the sensible size",
            OfflineDetail.tiles(coast, offered + 1) > OfflineDetail.SUGGESTED_TILES
        )
    }

    @Test
    fun `the detail never goes past what the map has, or below the least`() {
        assertEquals(14, OfflineDetail.step(14, +1, top = 14))
        assertEquals(13, OfflineDetail.step(14, -1, top = 14))
        assertEquals(OfflineDetail.LEAST_ZOOM, OfflineDetail.step(OfflineDetail.LEAST_ZOOM, -1, top = 14))
        assertEquals(OfflineDetail.LEAST_ZOOM..14, OfflineDetail.limits(14))
    }

    @Test
    fun `a map with very little detail still has a least no higher than its most`() {
        assertEquals(2..2, OfflineDetail.limits(2))
        assertEquals(2, OfflineDetail.suggested(coast, top = 2))
    }

    // What the map has --------------------------------------------------------

    @Test
    fun `the built-in satellite map stops at its real detail`() {
        assertEquals(OfflineDetail.SENTINEL2_TOP_ZOOM, OfflineDetail.topZoom(DataSource.DEFAULT_PRESET.urlTemplate))
    }

    @Test
    fun `a map the app knows uses its own most, and a link it doesn't know a sensible one`() {
        assertEquals(
            9,
            OfflineDetail.topZoom(
                "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_NOAA20_CorrectedReflectance_TrueColor/" +
                    "default/default/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpg"
            )
        )
        assertEquals(OfflineDetail.UNKNOWN_TOP_ZOOM, OfflineDetail.topZoom("https://example.com/{z}/{x}/{y}.png"))
    }

    // The count line ----------------------------------------------------------

    @Test
    fun `a count within the limit is shown with its rough size`() {
        assertTrue(OfflineDetail.fits(1_000))
        assertEquals("1,000 tiles, roughly 24 MB.", OfflineDetail.countLine(1_000))
        assertEquals("1 tile, roughly 24 KB.", OfflineDetail.countLine(1))
    }

    @Test
    fun `too many tiles are refused, saying what to do`() {
        val tooMany = OfflineDetail.MAX_TILES + 1

        assertTrue(OfflineDetail.fits(OfflineDetail.MAX_TILES))
        assertFalse(OfflineDetail.fits(tooMany))
        assertFalse(OfflineDetail.fits(0))
        assertEquals(
            "200,001 tiles is too many (the most is 200,000). Make the box smaller or lower the detail.",
            OfflineDetail.countLine(tooMany)
        )
    }

    // What a zoom means -------------------------------------------------------

    @Test
    fun `a zoom is described by its detail on the ground`() {
        assertEquals("about 10 m detail", OfflineDetail.meaning(14, 0.0))
        assertEquals("about 9 m detail", OfflineDetail.meaning(14, -27.0))
        assertEquals("about 1.2 m detail", OfflineDetail.meaning(17, 0.0))
        assertEquals("about 20 km detail", OfflineDetail.meaning(3, 0.0))
        assertEquals("about 4.9 km detail", OfflineDetail.meaning(5, 0.0))
    }

    // The box as bounds -------------------------------------------------------

    @Test
    fun `a drawn box becomes bounds a download can take`() {
        val bounds = OfflineDetail.boundsOf(south = -27.4, west = 153.2, north = -27.3, east = 153.3)

        assertEquals(bay, bounds)
        assertTrue(bounds.isValid)
    }

    @Test
    fun `a box past the edge of the map is kept inside it`() {
        val bounds = OfflineDetail.boundsOf(south = -89.0, west = 170.0, north = 10.0, east = 190.0)

        assertEquals(180.0, bounds.east, 0.0)
        assertEquals(-85.0511, bounds.south, 0.0)
        assertTrue(bounds.isValid)
    }
}
