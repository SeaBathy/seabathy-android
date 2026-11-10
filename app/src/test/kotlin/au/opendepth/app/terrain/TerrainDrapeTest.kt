package au.opendepth.app.terrain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainDrapeTest {
    @Test
    fun `view-only providers are not offered for offline saving`() {
        assertFalse(TerrainDrape.allowsOfflineCopies("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"))
        assertFalse(TerrainDrape.allowsOfflineCopies("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertFalse(TerrainDrape.allowsOfflineCopies("https://a.tile.opentopomap.org/{z}/{x}/{y}.png"))
    }

    @Test
    fun `providers that allow offline copies are offered`() {
        assertTrue(TerrainDrape.allowsOfflineCopies("https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg"))
        assertTrue(TerrainDrape.allowsOfflineCopies("https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"))
    }

    @Test
    fun `an Esri drape is not savable but a Sentinel-2 one is`() {
        assertFalse(TerrainDrape("a", "Esri", 0, 19, urlTemplate = "https://server.arcgisonline.com/x/{z}/{y}/{x}").isSavable)
        assertTrue(TerrainDrape("b", "S2", 0, 14, urlTemplate = "https://tiles.maps.eox.at/x/{z}/{y}/{x}.jpg").isSavable)
        assertFalse(TerrainDrape("c", "Pack", 0, 14).isSavable)
    }
}
