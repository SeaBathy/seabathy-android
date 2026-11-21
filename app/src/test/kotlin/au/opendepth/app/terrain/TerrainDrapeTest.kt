package au.opendepth.app.terrain

import au.opendepth.app.settings.XyzTilePresets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainDrapeTest {
    @Test
    fun `an online map the downloader would save can be saved offline, an offline pack can't be again`() {
        assertTrue(TerrainDrape("a", "My map", 0, 19, urlTemplate = "https://tiles.example.com/x/{z}/{y}/{x}").isSavable)
        assertTrue(TerrainDrape("b", "S2", 0, 14, urlTemplate = "https://tiles.maps.eox.at/x/{z}/{y}/{x}.jpg").isSavable)
        assertTrue(TerrainDrape("e", "Low tide", 0, 15, urlTemplate = XyzTilePresets.byId("dea_tidal_low")!!.urlTemplate).isSavable)
        assertFalse(TerrainDrape("d", "Pack", 0, 14).isSavable)
    }

    @Test
    fun `a map the downloader refuses isn't offered for saving`() {
        // Their servers forbid offline copies, and the satellite depths are an online-only layer.
        assertFalse(TerrainDrape("c", "OSM", 0, 19, urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png").isSavable)
        assertFalse(TerrainDrape("f", "Topo", 0, 17, urlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png").isSavable)
        assertFalse(TerrainDrape("g", "Depths", 0, 10, urlTemplate = XyzTilePresets.byId("cmems_sdb_coastal")!!.urlTemplate).isSavable)
    }
}
