package au.opendepth.app.nav

import au.opendepth.app.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class NavMathTest {

    @Test
    fun `cross and along track distances`() {
        val a = RoutePoint(0.0, 0.0)
        val b = RoutePoint(0.0, 1.0) // due east along the equator
        // 0.01° north of the line is to port when heading east.
        assertEquals(-1112.0, NavGeo.crossTrackM(a, b, 0.01, 0.5), 5.0)
        assertEquals(55_597.0, NavGeo.alongTrackM(a, b, 0.01, 0.5), 50.0)
        assertEquals(-11_119.0, NavGeo.alongTrackM(a, b, 0.0, -0.1), 20.0)
    }

    @Test
    fun `route plan legs, times and fuel`() {
        val plan = RoutePlan(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.1, 0.0), RoutePoint(0.1, 0.1)), speedKn = 6.0, burnLph = 10.0)
        assertEquals(2, plan.legs.size)
        assertEquals(6.0, plan.legs[0].distanceNm, 0.05)
        assertEquals(0.0, plan.legs[0].bearingTrue, 0.1)
        assertEquals(90.0, plan.legs[1].bearingTrue, 0.2)
        assertEquals(plan.totalNm, plan.legs[1].cumulativeNm, 1e-9)
        assertEquals(2.0, plan.totalHours!!, 0.02)
        assertEquals(20.0, plan.totalFuelLitres!!, 0.2)
        assertEquals((3_600_000L + 1000).toDouble(), plan.arrivalMillis(plan.legs[0], 1000)!!.toDouble(), 60_000.0)
    }

    @Test
    fun `no speed means no times and no fuel`() {
        val plan = RoutePlan(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.1, 0.0)), speedKn = 0.0, burnLph = 10.0)
        assertNull(plan.totalHours)
        assertNull(plan.totalFuelLitres)
    }

    @Test
    fun `formatting in each unit`() {
        val metric = NavFormat(NavUnits(distance = DistanceUnit.KILOMETRES, speed = SpeedUnit.KMH, depth = DepthUnit.METRES))
        assertEquals("1.85 km", metric.distance(1.0))
        assertEquals("926 m", metric.distance(0.5))
        assertEquals("11.1 km/h", metric.speed(6.0))
        val imperial = NavFormat(NavUnits(distance = DistanceUnit.STATUTE_MILES, depth = DepthUnit.FEET, fuel = FuelUnit.US_GALLONS))
        assertEquals("10.0 ft", imperial.depth(3.048))
        assertEquals("2.6 US gal", imperial.fuel(10.0))
        assertEquals("328 ft", imperial.offset(100.0))
        val nautical = NavFormat(NavUnits())
        assertEquals("93 m", nautical.distance(0.05))
        assertEquals("0.20 nm", nautical.offset(370.4))
    }

    @Test
    fun `magnetic bearings subtract an easterly variation`() {
        val magnetic = NavFormat(NavUnits(bearing = BearingReference.MAGNETIC))
        assertEquals("080°M", magnetic.bearing(90.0, variation = 10.0))
        assertEquals("355°M", magnetic.bearing(5.0, variation = 10.0))
        assertEquals("090°T", NavFormat(NavUnits()).bearing(90.0, 10.0))
    }

    @Test
    fun `durations and clock times`() {
        val f = NavFormat(NavUnits(), TimeZone.getTimeZone("UTC"))
        assertEquals("<1m", f.duration(0.01))
        assertEquals("45m", f.duration(0.75))
        assertEquals("2h 05m", f.duration(2.0 + 5.0 / 60))
        assertEquals("3d 2h", f.duration(74.0))
        assertEquals("01:00", f.clock(3_600_000L, nowMillis = 0L))
        assertEquals("Fri 00:00", f.clock(86_400_000L, nowMillis = 0L))
    }

    @Test
    fun `turns take the short way round`() {
        assertEquals(20.0, NavFormat.turnDegrees(350.0, 10.0), 1e-9)
        assertEquals(-20.0, NavFormat.turnDegrees(10.0, 350.0), 1e-9)
    }

    @Test
    fun `fuel range keeps the reserve back`() {
        val s = NavSettings(cruiseSpeedKn = 10.0, fuelBurnLph = 20.0, tankLitres = 100.0, reservePercent = 20.0)
        assertEquals(80.0, s.usableFuelLitres, 1e-9)
        assertEquals(2.0, s.litresPerNm!!, 1e-9)
        assertEquals(40.0, s.rangeNm!!, 1e-9)
        val partFull = s.copy(fuelOnBoardLitres = 50.0)
        assertEquals(30.0, partFull.usableFuelLitres, 1e-9)
    }
}
