package au.opendepth.app.util

import au.opendepth.app.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun `one minute of latitude is one nautical mile`() {
        assertEquals(1.0, GeoMath.distanceNm(0.0 to 0.0, (1.0 / 60.0) to 0.0), 0.001)
    }

    @Test
    fun `distance is symmetric`() {
        val a = -18.3 to 147.7
        val b = -18.4 to 147.9
        assertEquals(GeoMath.distanceNm(a, b), GeoMath.distanceNm(b, a), 1e-9)
    }

    @Test
    fun `total distance sums the legs`() {
        val legs = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)
        assertEquals(
            GeoMath.distanceNm(legs[0], legs[1]) + GeoMath.distanceNm(legs[1], legs[2]),
            GeoMath.totalNm(legs),
            1e-9
        )
    }

    @Test
    fun `a single point has no length`() {
        assertEquals(0.0, GeoMath.totalNm(listOf(-18.3 to 147.7)), 0.0)
        assertEquals(0.0, GeoMath.totalNm(emptyList()), 0.0)
    }

    @Test
    fun `bearing due north is zero and due east is ninety`() {
        assertEquals(
            0.0,
            GeoMath.bearingDegrees(RoutePoint(0.0, 0.0), RoutePoint(1.0, 0.0)),
            1e-6
        )
        assertEquals(
            90.0,
            GeoMath.bearingDegrees(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)),
            1e-6
        )
    }

    @Test
    fun `bearing is always normalised into zero to three sixty`() {
        val bearing = GeoMath.bearingDegrees(RoutePoint(0.0, 0.0), RoutePoint(0.0, -1.0))
        assertEquals(270.0, bearing, 1e-6)
    }

    @Test
    fun `eta handles whole hours, minutes and impossible speeds`() {
        assertEquals("2h0m", GeoMath.eta(12.0, 6.0))
        assertEquals("30m", GeoMath.eta(3.0, 6.0))
        assertEquals("—", GeoMath.eta(3.0, 0.0))
        assertEquals("—", GeoMath.eta(0.0, 6.0))
        assertEquals("—", GeoMath.eta(-1.0, 6.0))
        // Under a minute reads "<1m", not "0m".
        assertEquals("<1m", GeoMath.eta(0.05, 6.0))
    }

    @Test
    fun `short distances are shown in metres, longer ones in nautical miles and km`() {
        assertEquals("19 m", GeoMath.formatDistance(0.01))
        assertEquals("183 m", GeoMath.formatDistance(0.099))
        assertEquals("0.10 nm (0.2 km)", GeoMath.formatDistance(0.1))
        assertEquals("12.50 nm (23.2 km)", GeoMath.formatDistance(12.5))
    }
}
