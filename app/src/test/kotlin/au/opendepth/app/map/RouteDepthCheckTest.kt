package au.opendepth.app.map

import au.opendepth.app.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDepthCheckTest {

    private val route = listOf(RoutePoint(-33.90, 151.30), RoutePoint(-33.90, 151.40), RoutePoint(-33.85, 151.40))

    @Test
    fun `finds the shallowest point along every leg`() {
        // Deep everywhere except a 4.2 m shoal around 151.35 on the first leg.
        val check = RouteDepthCheck.compute(route) { lat, lng ->
            if (lat < -33.89 && lng in 151.349..151.351) -4.2 else -30.0
        }!!
        assertEquals(-4.2, check.shallowestMetres, 1e-9)
        assertEquals(151.35, check.longitude, 0.002)
        assertEquals(1.0, check.coveredFraction, 1e-9)
        assertEquals("shallowest 4.2 m", check.label())
    }

    @Test
    fun `a drying height is reported as drying`() {
        val check = RouteDepthCheck.compute(route) { _, lng -> if (lng > 151.399) 0.6 else -12.0 }!!
        assertEquals("dries +0.6 m", check.label())
    }

    @Test
    fun `partial coverage is stated rather than implied safe`() {
        val check = RouteDepthCheck.compute(route) { _, lng -> if (lng < 151.35) -8.0 else null }!!
        assertTrue(check.coveredFraction in 0.2..0.4)
        assertTrue(check.label(), check.label().contains("% of route charted"))
    }

    @Test
    fun `no data or no route gives null`() {
        assertNull(RouteDepthCheck.compute(route) { _, _ -> null })
        assertNull(RouteDepthCheck.compute(route.take(1)) { _, _ -> -5.0 })
    }

    @Test
    fun `long routes are capped at the sample budget`() {
        var calls = 0
        RouteDepthCheck.compute(listOf(RoutePoint(-34.0, 150.0), RoutePoint(-30.0, 155.0)), maxSamples = 500) { _, _ ->
            calls++
            -50.0
        }
        assertTrue("calls=$calls", calls in 450..560)
    }
}
