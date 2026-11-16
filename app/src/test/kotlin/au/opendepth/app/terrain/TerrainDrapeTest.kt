package au.opendepth.app.terrain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainDrapeTest {
    @Test
    fun `any online map can be saved offline, an offline pack can't be again`() {
        assertTrue(TerrainDrape("a", "Esri", 0, 19, urlTemplate = "https://server.arcgisonline.com/x/{z}/{y}/{x}").isSavable)
        assertTrue(TerrainDrape("b", "S2", 0, 14, urlTemplate = "https://tiles.maps.eox.at/x/{z}/{y}/{x}.jpg").isSavable)
        assertTrue(TerrainDrape("c", "OSM", 0, 19, urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png").isSavable)
        assertFalse(TerrainDrape("d", "Pack", 0, 14).isSavable)
    }
}
