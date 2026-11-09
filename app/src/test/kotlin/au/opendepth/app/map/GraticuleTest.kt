package au.opendepth.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraticuleTest {

    @Test
    fun `labels read as a chart's do`() {
        assertEquals("20°S", Graticule.label(-20.0, isLatitude = true, step = 1.0))
        assertEquals("149°30′E", Graticule.label(149.5, isLatitude = false, step = 0.5))
        assertEquals("20°10′S", Graticule.label(-20.0 - 10 / 60.0, isLatitude = true, step = 10 / 60.0))
        assertEquals("0°", Graticule.label(0.0, isLatitude = true, step = 5.0))
        assertEquals("180°", Graticule.label(-180.0, isLatitude = false, step = 1.0))
        assertEquals("88°00′W", Graticule.label(-88.0, isLatitude = false, step = 5 / 60.0))
        assertEquals("20°00.5′S", Graticule.label(-20.0 - 0.5 / 60, isLatitude = true, step = 0.5 / 60))
    }

    @Test
    fun `about the same number of lines at any zoom`() {
        for (span in listOf(300.0, 100.0, 30.0, 8.0, 2.0, 0.5, 0.1, 0.02, 0.005)) {
            val lines = span / Graticule.stepFor(span)
            assertTrue("span $span gave $lines lines", lines in 3.0..12.0)
        }
    }

    @Test
    fun `lines fall on round figures`() {
        val json = Graticule.geoJson(-20.3, 149.2, -19.6, 149.9)
        assertTrue(json, "\"label\":\"149°30′E\"" in json)
        assertTrue(json, "\"label\":\"20°00′S\"" in json)
    }

    @Test
    fun `the date line on screen still gives true longitudes`() {
        val json = Graticule.geoJson(-20.0, 175.0, -10.0, -175.0)
        assertTrue(json, "\"label\":\"178°E\"" in json)
        assertTrue(json, "\"label\":\"178°W\"" in json)
    }

    @Test
    fun `nothing to draw with no area`() {
        assertEquals(MapLayerIds.EMPTY_GEOJSON, Graticule.geoJson(10.0, 0.0, 10.0, 5.0))
    }
}
