package au.opendepth.app.fishing

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolMarksLayerTest {

    @Test
    fun pointWithoutIndexIsUnchanged() {
        assertEquals(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[151.3,-33.8]},""" +
                """"properties":{"colour":"#FBBF24","label":"3"}}""",
            ToolMarksLayer.point(-33.8, 151.3, "#FBBF24", "3")
        )
    }

    @Test
    fun pointCarriesItsIndexForTaps() {
        assertEquals(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[151.3,-33.8]},""" +
                """"properties":{"colour":"#FBBF24","label":"3","i":2}}""",
            ToolMarksLayer.point(-33.8, 151.3, "#FBBF24", "3", 2)
        )
    }
}
