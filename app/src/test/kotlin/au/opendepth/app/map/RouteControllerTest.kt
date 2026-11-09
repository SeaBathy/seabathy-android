package au.opendepth.app.map

import au.opendepth.app.nav.NavSettings
import au.opendepth.app.nav.PlanSpeedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteControllerTest {

    private var settings = NavSettings()

    private fun controller() = RouteController(MapHolder(), settings = { settings })

    private fun twoPointRoute(): RouteController = controller().apply {
        add(-33.86, 151.20)
        add(-33.87, 151.21)
    }

    @Test
    fun `a second tap on the same spot adds no zero-length leg`() {
        val route = controller().apply {
            add(-33.86, 151.20)
            add(-33.86, 151.20, "WPT 8")
            add(-33.860001, 151.200001)
            add(-33.87, 151.21)
        }
        assertEquals(2, route.routePoints.size)
    }

    @Test
    fun `plans at the cruise speed by default`() {
        settings = NavSettings(cruiseSpeedKn = 10.0)
        val summary = twoPointRoute().summary(routeToolActive = true)!!
        assertTrue(summary, summary.contains("at 10.0 kn (cruise)"))
    }

    @Test
    fun `GPS speed is used when chosen and available`() {
        settings = NavSettings(cruiseSpeedKn = 10.0, speedSource = PlanSpeedSource.GPS)
        val route = twoPointRoute()
        route.updateGpsSpeedKnots(4.5)
        assertTrue(route.summary(true)!!.contains("at 4.5 kn (GPS)"))
    }

    @Test
    fun `GPS chosen but no fix falls back to cruise speed, not zero`() {
        settings = NavSettings(cruiseSpeedKn = 8.0, speedSource = PlanSpeedSource.GPS)
        val summary = twoPointRoute().summary(true)!!
        assertTrue(summary, summary.contains("at 8.0 kn (cruise, no GPS fix)"))
    }

    @Test
    fun `a three point route reports its first leg, not first to last`() {
        val route = controller()
        route.add(0.0, 0.0)
        route.add(1.0, 0.0) // due north
        route.add(1.0, 1.0) // then east
        assertTrue(route.summary(true)!!.contains("1st leg 000°T"))
    }

    @Test
    fun `fuel is shown and checked against usable fuel when a burn is set`() {
        settings = NavSettings(cruiseSpeedKn = 6.0, fuelBurnLph = 12.0, tankLitres = 10.0, reservePercent = 0.0)
        val route = controller()
        route.add(0.0, 0.0)
        route.add(0.1, 0.0) // 6 nm: 1 h, 12 L
        val summary = route.summary(true)!!
        assertTrue(summary, summary.contains("Fuel 12.0 L"))
        assertTrue(summary, summary.contains("more than the usable 10.0 L"))
    }

    @Test
    fun `no summary for an empty route when the tool is off`() {
        assertNull(controller().summary(routeToolActive = false))
    }

    @Test
    fun `reverse swaps the ends`() {
        val route = twoPointRoute()
        route.reverse()
        assertEquals(-33.87, route.routePoints.first().lat, 1e-9)
    }
}
