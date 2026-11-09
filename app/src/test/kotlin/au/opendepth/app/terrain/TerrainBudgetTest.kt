package au.opendepth.app.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainBudgetTest {
    @Test
    fun morePhoneMemoryHoldsMore() {
        val sizes = listOf(2.0, 3.6, 5.5, 7.3, 11.2).map { TerrainBudget.cells(it, false, 0) }
        assertEquals(sizes.sorted(), sizes)
        assertEquals(sizes.size, sizes.distinct().size)
    }

    @Test
    fun an8GbPhoneGetsThe8GbSizeEvenThoughItReportsLess() {
        assertEquals(1_500_000.0, TerrainBudget.cells(7.2, false, 0), 0.0)
    }

    @Test
    fun aBigPhoneReachesMaximumOnAWideSurvey() {
        val mostAPhoneHolds = TerrainBudget.cells(11.2, false, 0)
        assertTrue(TerrainBudget.longestSide(mostAPhoneHolds, 1.67) >= 2048)
    }

    @Test
    fun aSmallPhoneStaysLight() {
        assertEquals(707, TerrainBudget.longestSide(TerrainBudget.cells(2.0, false, 0), 1.67))
    }

    @Test
    fun graphicsTroubleAndLowMemoryEachHoldLess() {
        val full = TerrainBudget.cells(11.2, false, 0)
        assertEquals(full, TerrainBudget.cells(11.2, false, 1), 0.0)
        assertEquals(full / 2, TerrainBudget.cells(11.2, false, 2), 0.0)
        assertEquals(full / 4, TerrainBudget.cells(11.2, false, 3), 0.0)
        assertEquals(full / 2, TerrainBudget.cells(11.2, true, 0), 0.0)
    }
}
