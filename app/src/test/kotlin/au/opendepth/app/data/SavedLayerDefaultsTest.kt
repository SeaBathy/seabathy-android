package au.opendepth.app.data

import au.opendepth.app.map.DepthContours
import au.opendepth.app.model.ContourSettings
import au.opendepth.app.model.DepthRampSettings
import au.opendepth.app.model.LayerKind
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.nav.DepthUnit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layers are saved as JSON and read back by Gson, which can leave a field
 * the JSON does not have at zero, false or null instead of its Kotlin
 * default. Layers saved before slope colours and contour lines existed must
 * still come back coloured by depth, with no lines.
 */
class SavedLayerDefaultsTest {
    private val gson = Gson()

    private fun read(raw: String): List<OverlayLayer> = gson.fromJson(
        OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw),
        object : TypeToken<List<OverlayLayer>>() {}.type
    )

    /** As version 1.3.8 saved a depth layer. */
    private val saved = """[{"id":"layer_1","kind":"GEOTIFF","name":"Harbour survey","source":"content://surveys/harbour.tif",""" +
        """"visible":true,"opacity":0.8,"attribution":"","ramp":{"paletteId":"ocean","shallowMetres":-2.0,"deepMetres":-40.0,""" +
        """"invert":false,"showOutside":true,"hillshadeExaggeration":1.5},"maxZoom":18,"gdalOnDemand":false}]"""

    @Test
    fun `a layer saved before slope colours existed is coloured by depth`() {
        val ramp = read(saved).single().ramp!!

        assertFalse(ramp.colourBySlope)
        // Whatever Gson left in the field, the angle read for drawing is the default.
        assertEquals(DepthRampSettings.DEFAULT_SLOPE_MAX_DEGREES, ramp.steepestDegrees, 0.0)
        // And what it did have is as it was.
        assertEquals("ocean", ramp.paletteId)
        assertEquals(-2.0, ramp.shallowMetres, 0.0)
        assertEquals(-40.0, ramp.deepMetres, 0.0)
        assertTrue(ramp.showOutside)
        assertEquals(1.5, ramp.hillshadeExaggeration, 0.0)
    }

    @Test
    fun `a layer saved before contour lines existed has none`() {
        val layer = read(saved).single()

        assertEquals(LayerKind.GEOTIFF, layer.kind)
        assertFalse(layer.contourSettings.on)
        assertEquals(ContourSettings.AUTO, layer.contourSettings.everyMetres, 0.0)
    }

    @Test
    fun `contour settings with a field missing read as off and Auto`() {
        val layer = read(saved.replace(""""maxZoom":18""", """"maxZoom":18,"contours":{}""")).single()

        assertFalse(layer.contourSettings.on)
        assertEquals(ContourSettings.AUTO, layer.contourSettings.everyMetres, 0.0)
        // And Auto is what the lines are drawn with, whatever the depth unit.
        DepthUnit.values().forEach { assertEquals(DepthContours.AUTO, DepthContours.pickedIn(layer.contourSettings.everyMetres, it)) }
    }

    @Test
    fun `slope colours and contour lines are saved with the layer`() {
        val layer = read(saved).single().let {
            it.copy(
                ramp = it.ramp!!.copy(colourBySlope = true, slopeMaxDegrees = 35.0),
                contours = ContourSettings(on = true, everyMetres = 5.0)
            )
        }
        val back = read(gson.toJson(listOf(layer))).single()

        assertEquals(layer, back)
        assertTrue(back.ramp!!.colourBySlope)
        assertEquals(35.0, back.ramp!!.steepestDegrees, 0.0)
        assertEquals(ContourSettings(on = true, everyMetres = 5.0), back.contourSettings)
    }

    @Test
    fun `a spacing saved as whole metres still means metres`() {
        // As saved while the spacing was a whole number of metres.
        val layer = read(saved.replace(""""maxZoom":18""", """"maxZoom":18,"contours":{"on":true,"everyMetres":5}""")).single()

        assertTrue(layer.contourSettings.on)
        assertEquals(5.0, layer.contourSettings.everyMetres, 0.0)
        assertEquals(5, DepthContours.pickedIn(layer.contourSettings.everyMetres, DepthUnit.METRES))
        // With feet chosen it is drawn at the closest round number of feet: 16.4 ft as 20 ft.
        assertEquals(20, DepthContours.pickedIn(layer.contourSettings.everyMetres, DepthUnit.FEET))
    }

    @Test
    fun `a spacing picked in feet comes back as the same feet`() {
        val picked = ContourSettings(on = true, everyMetres = DepthContours.metresFor(5, DepthUnit.FEET))
        val layer = read(saved).single().copy(contours = picked)
        val back = read(gson.toJson(listOf(layer))).single()

        assertEquals(5, DepthContours.pickedIn(back.contourSettings.everyMetres, DepthUnit.FEET))
        // Changing the unit does not change what is saved: metres now, 5 ft (1.5 m) is drawn as 2 m.
        assertEquals(2, DepthContours.pickedIn(back.contourSettings.everyMetres, DepthUnit.METRES))
    }

    @Test
    fun `a layer with no ramp at all still has none`() {
        val layer = read("""[{"id":"layer_2","kind":"XYZ","name":"Tiles","source":"https://example.org/{z}/{x}/{y}.png"}]""").single()

        assertNull(layer.ramp)
        assertNull(layer.contours)
        assertFalse(layer.contourSettings.on)
    }
}
