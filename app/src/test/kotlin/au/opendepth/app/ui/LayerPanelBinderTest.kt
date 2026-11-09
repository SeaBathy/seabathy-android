package au.opendepth.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerPanelBinderTest {

    @Test
    fun formatsSliderStepsAsWholePercent() {
        for (step in 0..LayerPanelBinder.OPACITY_STEPS) {
            val fraction = step.toFloat() / LayerPanelBinder.OPACITY_STEPS
            assertEquals("$step%", LayerPanelBinder.formatOpacity(fraction))
        }
    }

    @Test
    fun clampsOutOfRangeOpacity() {
        assertEquals("0%", LayerPanelBinder.formatOpacity(-0.2f))
        assertEquals("100%", LayerPanelBinder.formatOpacity(1.7f))
    }
}
