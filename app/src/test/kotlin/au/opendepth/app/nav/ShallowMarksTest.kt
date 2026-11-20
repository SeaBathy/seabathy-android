package au.opendepth.app.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ShallowMarksTest {

    @Test
    fun `round figures are offered in each depth unit`() {
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 10.0, 20.0), ShallowMarks.offered(DepthUnit.METRES))
        assertEquals(listOf(3.0, 6.0, 10.0, 15.0, 30.0, 60.0), ShallowMarks.offered(DepthUnit.FEET))
        assertEquals(listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0), ShallowMarks.offered(DepthUnit.FATHOMS))
    }

    @Test
    fun `the choices are Off and then each depth, in the unit's own words`() {
        assertEquals(
            listOf("Off", "Shallower than 1 m", "Shallower than 2 m", "Shallower than 3 m", "Shallower than 5 m", "Shallower than 10 m", "Shallower than 20 m"),
            ShallowMarks.choiceLabels(DepthUnit.METRES)
        )
        assertEquals("Shallower than 0.5 fm", ShallowMarks.choiceLabels(DepthUnit.FATHOMS)[1])
        assertEquals("Shallower than 60 ft", ShallowMarks.choiceLabels(DepthUnit.FEET).last())
    }

    @Test
    fun `a choice is kept in metres and found again in its own unit`() {
        for (unit in DepthUnit.entries) {
            ShallowMarks.choiceLabels(unit).indices.forEach { index ->
                val metres = ShallowMarks.metresFor(index, unit)
                assertEquals("$unit choice $index", index, ShallowMarks.choiceIndex(metres, unit))
            }
        }
        assertEquals(0.0, ShallowMarks.metresFor(0, DepthUnit.FEET), 0.0)
        assertEquals(3.0, ShallowMarks.metresFor(3, DepthUnit.METRES), 0.0)
        assertEquals(1.8288, ShallowMarks.metresFor(2, DepthUnit.FEET), 1e-4)
        assertEquals(0.9144, ShallowMarks.metresFor(1, DepthUnit.FATHOMS), 1e-4)
    }

    @Test
    fun `a mark picked in another unit is none of the choices, and is written to a tenth`() {
        // 2 m is not one of the round figures in feet.
        assertEquals(-1, ShallowMarks.choiceIndex(2.0, DepthUnit.FEET))
        assertEquals("Shallower than 6.6 ft", ShallowMarks.label(2.0, DepthUnit.FEET))
        assertEquals("Shallower than 1.1 fm", ShallowMarks.label(2.0, DepthUnit.FATHOMS))
        // 10 ft is 3.05 m: near 3 m, but not the same mark.
        assertEquals(-1, ShallowMarks.choiceIndex(ShallowMarks.metresFor(3, DepthUnit.FEET), DepthUnit.METRES))
    }

    @Test
    fun `the row says what is marked, or Off`() {
        assertEquals("Off", ShallowMarks.label(0.0, DepthUnit.METRES))
        assertEquals("Shallower than 3 m", ShallowMarks.label(3.0, DepthUnit.METRES))
        assertEquals("Shallower than 6 ft", ShallowMarks.label(ShallowMarks.metresFor(2, DepthUnit.FEET), DepthUnit.FEET))
        assertEquals("Shallower than 0.5 fm", ShallowMarks.label(ShallowMarks.metresFor(1, DepthUnit.FATHOMS), DepthUnit.FATHOMS))
    }

    @Test
    fun `the setting is off until a depth is picked`() {
        assertEquals(0.0, NavSettings().shallowMarkM, 0.0)
    }
}
