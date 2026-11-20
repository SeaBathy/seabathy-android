package au.opendepth.app.ui

import au.opendepth.app.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointSwatchesTest {

    @Test
    fun `swatches that fit are drawn at their usual size`() {
        assertEquals(1f, WaypointSwatches.fitScale(available = 300, count = 6, naturalCell = 46), 0f)
        assertEquals(1f, WaypointSwatches.fitScale(available = 276, count = 6, naturalCell = 46), 0f)
    }

    @Test
    fun `swatches too wide for the row shrink to fill it`() {
        val scale = WaypointSwatches.fitScale(available = 240, count = 7, naturalCell = 48)

        assertTrue(scale < 1f)
        assertTrue("all seven fit", 7 * 48 * scale <= 240f)
    }

    @Test
    fun `nothing to lay out leaves the size alone`() {
        assertEquals(1f, WaypointSwatches.fitScale(available = 0, count = 6, naturalCell = 46), 0f)
        assertEquals(1f, WaypointSwatches.fitScale(available = 300, count = 0, naturalCell = 46), 0f)
    }

    @Test
    fun `every colour in the palette has a name to be read out`() {
        val names = Waypoint.PALETTE.indices.map { WaypointSwatches.colourName(it) }

        assertEquals(listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple"), names)
        assertEquals(names.size, names.toSet().size)
    }
}
