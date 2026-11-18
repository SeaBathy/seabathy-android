package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearestSeaTest {
    private val lats = doubleArrayOf(-28.0, -27.5, -27.0)
    private val lngs = doubleArrayOf(153.0, 153.5, 154.0)

    @Test
    fun theClosestSeaPointIsChosen() {
        // Sea only east of 153.25 (the coast): from a spot on land at 153.1, the nearest is 153.5 on its own row.
        val sea = { _: Double, lng: Double -> lng > 153.25 }
        assertEquals(-27.5 to 153.5, ConditionsFeature.nearestMatching(lats, lngs, -27.45, 153.1, sea))
    }

    @Test
    fun noSeaInTheAreaGivesNothing() {
        assertNull(ConditionsFeature.nearestMatching(lats, lngs, -27.45, 153.1) { _, _ -> false })
    }
}
