package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotBlockTest {
    private val lat = -20.0
    private val lng = 145.0

    @Test
    fun onTheWaterTheBlockIsRoundTheBoat() {
        val points = SpotBlock.ringPoints(lat, lng)
        val sea = points.map { it }
        assertEquals(lat to lng, SpotBlock.centre(lat, lng, sea))
    }

    @Test
    fun inlandTheBlockMovesOverTheNearestSea() {
        val points = SpotBlock.ringPoints(lat, lng)
        // Sea only to the east, more than 100 km away.
        val sea = points.map { (pLat, pLng) -> if (pLng > lng + 1.0) pLat to pLng else null }
        val (cLat, cLng) = SpotBlock.centre(lat, lng, sea)
        assertTrue("moved east: $cLng", cLng > lng + 1.2)
        assertTrue("about level with the boat: $cLat", kotlin.math.abs(cLat - lat) < 1.0)
    }

    @Test
    fun noSeaInReachKeepsTheBoat() {
        val points = SpotBlock.ringPoints(lat, lng)
        assertEquals(lat to lng, SpotBlock.centre(lat, lng, points.map { null }))
        assertEquals(1 + (SpotBlock.RINGS_KM.size - 1) * SpotBlock.BEARINGS, points.size)
    }

    @Test
    fun freshnessColours() {
        val h = 3_600_000L
        val now = 100 * h
        fun f(fetched: Long, end: Long?, run: Long?, busy: Boolean = false) = ConditionsPolicy.freshness(fetched, end, run, now, busy)
        assertEquals(ConditionsPolicy.Freshness.CURRENT, f(now - h, now + 48 * h, now - 2 * h))
        assertEquals(ConditionsPolicy.Freshness.OUT_OF_DATE, f(now - 3 * h, now + 48 * h, now - h))
        assertEquals(ConditionsPolicy.Freshness.OUT_OF_DATE, f(now - 13 * h, now + 48 * h, null))
        assertEquals(ConditionsPolicy.Freshness.OUT_OF_DATE, f(now - h, now + 2 * h, null))
        assertEquals(ConditionsPolicy.Freshness.UPDATING, f(now - 13 * h, now + 48 * h, null, busy = true))
    }
}
