package au.opendepth.app.settings

import au.opendepth.app.download.OfflineTileDownloadController
import au.opendepth.app.download.TileSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DEA tidal composites (a WMS) and Copernicus satellite depths quick-add layers. */
class CoastPresetsTest {

    private val low = XyzTilePresets.byId("dea_tidal_low")!!
    private val high = XyzTilePresets.byId("dea_tidal_high")!!
    private val depths = XyzTilePresets.byId("cmems_sdb_coastal")!!

    @Test
    fun `the tidal composites are a WMS asked for one web-mercator tile at a time`() {
        listOf(low, high).forEach { p ->
            assertTrue(p.urlTemplate.startsWith("https://ows.dea.ga.gov.au/"))
            assertTrue(p.urlTemplate.contains("crs=EPSG:3857"))
            assertTrue(p.urlTemplate.contains("width=256&height=256"))
            assertTrue(p.urlTemplate.endsWith("bbox=${XyzTilePresets.BBOX_TOKEN}"))
            assertNull(XyzTilePresets.validationError(p.urlTemplate))
            assertTrue(p.offline)
            assertNull(OfflineTileDownloadController.noOfflineReason(p.urlTemplate))
        }
        assertTrue(low.urlTemplate.contains("styles=low_true"))
        assertTrue(high.urlTemplate.contains("styles=high_true"))
    }

    @Test
    fun `each new layer carries the credit its owner asks for`() {
        assertEquals(XyzTilePresets.DEA_TIDAL_CREDIT, low.attribution)
        assertTrue(low.attribution.contains("© Commonwealth of Australia (Geoscience Australia), CC BY 4.0"))
        assertTrue(low.attribution.contains("10.26186/150381"))
        assertTrue(low.attribution.contains("contains modified Copernicus Sentinel data"))
        assertEquals("Generated using E.U. Copernicus Marine Service Information; https://doi.org/10.48670/mds-00364", depths.attribution)
    }

    @Test
    fun `satellite depths are map tiles, online only, and said to be an estimate`() {
        assertTrue(depths.urlTemplate.startsWith("https://wmts.marine.copernicus.eu/teroWmts?"))
        assertTrue(depths.urlTemplate.contains("REQUEST=GetTile"))
        assertTrue(depths.urlTemplate.contains("cmems_obs-sdb_glo_phy_comp_my_100m-l4-s2_static_202511/height"))
        assertTrue(depths.urlTemplate.contains("TILEMATRIXSET=EPSG:3857"))
        assertEquals(10, depths.maxZoom)
        assertFalse(depths.offline)
        assertTrue(depths.description.contains("not for navigation"))
        assertNotNull(OfflineTileDownloadController.noOfflineReason(depths.urlTemplate))
        assertEquals(depths, XyzTilePresets.onlineOnly("  ${depths.urlTemplate} "))
        assertNull(XyzTilePresets.onlineOnly(low.urlTemplate))
    }

    @Test
    fun `layers with gaps are not offered as a basemap`() {
        listOf(low, high, depths).forEach { assertFalse(it.label, it in XyzTilePresets.BASEMAPS) }
        assertTrue(XyzTilePresets.DEFAULT_SATELLITE in XyzTilePresets.BASEMAPS)
    }

    @Test
    fun `a link needs its tile numbers or a WMS box`() {
        assertTrue(XyzTilePresets.hasTileTokens("https://a/{z}/{x}/{y}.png"))
        assertTrue(XyzTilePresets.hasTileTokens("https://a/wms?bbox={bbox-epsg-3857}"))
        assertFalse(XyzTilePresets.hasTileTokens("https://a/wms?bbox=0,0,1,1"))
        assertEquals("URL must contain {z}, {x} and {y}", XyzTilePresets.validationError("https://a/wms?bbox=0,0,1,1"))
    }

    @Test
    fun `a WMS link gets the tile's corners in web-mercator metres`() {
        // The whole world at zoom 0, and the north-east quarter at zoom 1.
        assertEquals("-20037508.342789,-20037508.342789,20037508.342789,20037508.342789", TileSampler.bbox3857(0, 0, 0))
        assertEquals("0,0,20037508.342789,20037508.342789", TileSampler.bbox3857(1, 1, 0))
        val url = TileSampler.urlFor(low.urlTemplate, 12, 3791, 2371)
        assertFalse(url.contains("{"))
        assertTrue(url.endsWith("&bbox=17053406.758536,-3169996.437043,17063190.698156,-3160212.497422"))
        // An ordinary tile link is unchanged by it.
        assertEquals("https://a/12/3791/2371.png", TileSampler.urlFor("https://a/{z}/{x}/{y}.png", 12, 3791, 2371))
    }
}
