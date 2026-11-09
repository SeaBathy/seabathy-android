package au.opendepth.app.nav

import au.opendepth.app.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    // A route due north along the equator's meridian, then east: 0,0 → 0.1N → 0.1N 0.1E.
    private val route = listOf(
        RoutePoint(0.0, 0.0, "Start"),
        RoutePoint(0.1, 0.0, "Turn"),
        RoutePoint(0.1, 0.1, "End")
    )
    private val settings = NavSettings(cruiseSpeedKn = 6.0, arrivalRadiusM = 50.0, xteLimitM = 100.0)

    private fun fix(lat: Double, lng: Double, sog: Double? = 6.0, cog: Double? = 0.0) =
        NavFix(lat, lng, sog, cog, timeMillis = 1_000_000L)

    @Test
    fun `starting at the first point steers to the second`() {
        val engine = NavigationEngine(route, settings)
        val state = engine.update(fix(0.0, 0.0))
        assertEquals(1, state.targetIndex)
        assertEquals("Turn", state.targetLabel)
        assertEquals(0.0, state.btwTrue, 0.5)
        // 0.1° of latitude is ~6 nm.
        assertEquals(6.0, state.dtwNm, 0.05)
    }

    @Test
    fun `starting away from the route steers to the first point`() {
        val engine = NavigationEngine(route, settings)
        val state = engine.update(fix(-0.05, 0.0))
        assertEquals(0, state.targetIndex)
    }

    @Test
    fun `cross-track error is positive to starboard and flags off course`() {
        val engine = NavigationEngine(route, settings)
        engine.update(fix(0.0, 0.0))
        // Heading north, east of the line is starboard: ~0.002° ≈ 222 m.
        val right = engine.update(fix(0.05, 0.002))
        assertTrue(right.xteM > 200 && right.xteM < 245)
        assertTrue(right.offCourse)
        val left = engine.update(fix(0.05, -0.0005))
        assertTrue(left.xteM < 0)
        assertFalse(left.offCourse)
    }

    @Test
    fun `steer is the turn from course to the waypoint bearing`() {
        val engine = NavigationEngine(route, settings)
        engine.update(fix(0.0, 0.0))
        // East of the line heading north: the waypoint is slightly to port.
        val state = engine.update(fix(0.05, 0.01, cog = 0.0))
        assertNotNull(state.steerDegrees)
        assertTrue(state.steerDegrees!! < 0)
    }

    @Test
    fun `VMG and ETA follow the speed towards the waypoint`() {
        val engine = NavigationEngine(route, settings.copy(speedSource = PlanSpeedSource.GPS))
        engine.update(fix(0.0, 0.0))
        val state = engine.update(fix(0.05, 0.0, sog = 6.0, cog = 0.0))
        assertEquals(6.0, state.vmgKn!!, 0.01)
        // 3 nm to go at 6 kn = 0.5 h.
        assertEquals(0.5, state.ttgNextHours!!, 0.01)
        // 3 nm + the 6 nm last leg.
        assertEquals(9.0, state.remainingNm, 0.1)
    }

    @Test
    fun `arriving inside the circle moves on and announces once`() {
        val engine = NavigationEngine(route, settings)
        engine.update(fix(0.0, 0.0))
        val arrived = engine.update(fix(0.0999, 0.0))
        assertEquals("Turn", arrived.arrivedLabel)
        assertEquals(2, arrived.targetIndex)
        val next = engine.update(fix(0.0999, 0.0001))
        assertNull(next.arrivedLabel)
    }

    @Test
    fun `passing abeam of a point counts as arriving`() {
        val engine = NavigationEngine(route, settings)
        engine.update(fix(0.0, 0.0))
        // 150 m off the line, already past the turn: outside the circle but abeam.
        val state = engine.update(fix(0.1005, 0.00135))
        assertEquals("Turn", state.arrivedLabel)
    }

    @Test
    fun `reaching the last point finishes the route`() {
        val engine = NavigationEngine(route, settings, startTargetIndex = 2)
        val state = engine.update(fix(0.1, 0.0999))
        assertTrue(state.finished)
        assertEquals(0.0, state.remainingNm, 1e-9)
    }

    @Test
    fun `without auto advance it waits at the point until skipped`() {
        val engine = NavigationEngine(route, settings.copy(autoAdvance = false))
        engine.update(fix(0.0, 0.0))
        val arrived = engine.update(fix(0.0999, 0.0))
        assertEquals("Turn", arrived.arrivedLabel)
        assertEquals(1, arrived.targetIndex)
        assertNull(engine.update(fix(0.0999, 0.0)).arrivedLabel)
        engine.skip()
        assertEquals(2, engine.targetIndex)
    }

    @Test
    fun `fuel to the end uses the cruise burn`() {
        val engine = NavigationEngine(route, settings.copy(fuelBurnLph = 12.0))
        val state = engine.update(fix(0.0, 0.0))
        // ~12 nm at 6 kn = 2 h at 12 L/h.
        assertEquals(24.0, state.fuelToEndLitres!!, 0.3)
    }
}
