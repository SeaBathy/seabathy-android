package au.opendepth.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorWatchTest {

    private val anchor = Anchor(-19.0, 147.0, radiusM = 40.0, setMillis = 0L)

    /** A point [metres] north of the anchor. */
    private fun north(metres: Double) = -19.0 + metres / 111_195.0

    @Test
    fun `swinging inside the circle is fine`() {
        val watch = AnchorWatch(anchor)
        repeat(5) { assertFalse(watch.update(north(35.0), 147.0, 5.0).dragging) }
        assertEquals(35.0, watch.maxDistanceM, 0.5)
    }

    @Test
    fun `one stray fix outside doesn't sound, two in a row do`() {
        val watch = AnchorWatch(anchor)
        assertFalse(watch.update(north(55.0), 147.0, 5.0).dragging)
        assertFalse(watch.update(north(20.0), 147.0, 5.0).dragging)
        assertFalse(watch.update(north(55.0), 147.0, 5.0).dragging)
        val status = watch.update(north(60.0), 147.0, 5.0)
        assertTrue(status.dragging)
        assertEquals(60.0, status.distanceM, 0.5)
        assertEquals(0.0, status.bearingFromAnchor, 1.0)
        // Back inside: the alarm clears.
        assertFalse(watch.update(north(30.0), 147.0, 5.0).dragging)
    }

    @Test
    fun `very rough fixes are reported, not counted`() {
        val watch = AnchorWatch(anchor)
        val first = watch.update(north(90.0), 147.0, 120.0)
        val second = watch.update(north(90.0), 147.0, 120.0)
        assertTrue(second.gpsPoor)
        assertFalse(first.dragging || second.dragging)
        assertEquals(0.0, watch.maxDistanceM, 0.0)
    }
}
