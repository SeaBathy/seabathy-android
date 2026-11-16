package au.opendepth.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoTakeZonesTest {
    // A 10 x 10 square with a 2 x 2 hole (an island) in the middle.
    private val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0)
    private val hole = doubleArrayOf(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0, 4.0, 4.0)

    @Test
    fun insideOutsideAndHoles() {
        val rings = listOf(square, hole)
        assertTrue(NoTakeZones.inside(rings, 1.0, 1.0))
        assertTrue(NoTakeZones.inside(rings, 9.5, 5.0))
        assertFalse(NoTakeZones.inside(rings, 5.0, 5.0))
        assertFalse(NoTakeZones.inside(rings, 11.0, 5.0))
        assertFalse(NoTakeZones.inside(rings, -0.1, -0.1))
        assertFalse(NoTakeZones.inside(emptyList(), 1.0, 1.0))
    }
}
