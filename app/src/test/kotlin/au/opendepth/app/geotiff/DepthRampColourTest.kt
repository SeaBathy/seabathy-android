package au.opendepth.app.geotiff

import au.opendepth.app.map.OverlayLayerController
import au.opendepth.app.model.DepthRampSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthRampColourTest {
    private val inshore = DepthRampSettings(shallowMetres = -10.0, deepMetres = -30.0)
    private val inshoreAll = inshore.copy(showOutside = true)

    @Test
    fun `depths inside the range spread along the ramp`() {
        assertEquals(0.0, DepthGridRenderer.rampPosition(-10.0, inshore)!!, 1e-9)
        assertEquals(0.5, DepthGridRenderer.rampPosition(-20.0, inshore)!!, 1e-9)
        assertEquals(1.0, DepthGridRenderer.rampPosition(-30.0, inshore)!!, 1e-9)
    }

    @Test
    fun `depths outside the range are hidden by default`() {
        assertNull(DepthGridRenderer.rampPosition(-2.0, inshore))
        assertNull(DepthGridRenderer.rampPosition(-800.0, inshore))
        assertNull(DepthGridRenderer.rampPosition(12.0, inshore))
    }

    @Test
    fun `or take the end colours when asked, with land left clear`() {
        assertEquals(0.0, DepthGridRenderer.rampPosition(-2.0, inshoreAll)!!, 1e-9)
        assertEquals(1.0, DepthGridRenderer.rampPosition(-800.0, inshoreAll)!!, 1e-9)
        assertNull(DepthGridRenderer.rampPosition(12.0, inshoreAll))
        val withLand = DepthRampSettings(shallowMetres = 20.0, deepMetres = -30.0, showOutside = true)
        assertEquals(0.16, DepthGridRenderer.rampPosition(12.0, withLand)!!, 1e-9)
    }

    @Test
    fun `fitting a survey with land covers just the water`() {
        assertEquals(0.0 to -890.0, OverlayLayerController.fitRange(154.0, -889.5))
        assertEquals(-3.0 to -41.0, OverlayLayerController.fitRange(-3.2, -40.1))
        // A land-only model keeps its own range.
        assertEquals(120.0 to 4.0, OverlayLayerController.fitRange(119.4, 4.6))
    }
}
