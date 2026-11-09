package au.opendepth.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthRampSettingsTest {

    @Test
    fun `a range entered the wrong way round is normalised`() {
        val normalised = DepthRampSettings(shallowMetres = -100.0, deepMetres = -6.0)
            .normalised()

        assertEquals(-6.0, normalised.shallowMetres, 0.0)
        assertEquals(-100.0, normalised.deepMetres, 0.0)
    }

    @Test
    fun `an already ordered range is left alone`() {
        val settings = DepthRampSettings(shallowMetres = -10.0, deepMetres = -30.0)
        assertEquals(settings, settings.normalised())
    }

    @Test
    fun `span is the distance between the ends`() {
        assertEquals(
            94.0,
            DepthRampSettings(shallowMetres = -6.0, deepMetres = -100.0).spanMetres,
            1e-9
        )
    }

    @Test
    fun `defaults describe a usable coastal range`() {
        val defaults = DepthRampSettings()
        assertEquals("rainbow", defaults.paletteId)
        assertTrue(defaults.spanMetres > 0)
        assertFalse(defaults.invert)
        assertEquals(defaults, defaults.normalised())
    }

    @Test
    fun `default hillshade exaggeration matches the value this app used before it was adjustable`() {
        assertEquals(0.6, DepthRampSettings().hillshadeExaggeration, 0.0)
    }

    @Test
    fun `hillshade exaggeration within range is not altered`() {
        assertEquals(2.3, DepthRampSettings.coerceHillshadeExaggeration(2.3), 0.0)
    }

    @Test
    fun `hillshade exaggeration below zero is clamped to zero`() {
        assertEquals(0.0, DepthRampSettings.coerceHillshadeExaggeration(-1.0), 0.0)
    }

    @Test
    fun `hillshade exaggeration above the max is clamped to the max`() {
        assertEquals(
            DepthRampSettings.MAX_HILLSHADE_EXAGGERATION,
            DepthRampSettings.coerceHillshadeExaggeration(999.0),
            0.0
        )
    }
}
