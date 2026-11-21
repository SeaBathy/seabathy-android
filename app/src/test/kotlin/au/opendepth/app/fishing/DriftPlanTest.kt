package au.opendepth.app.fishing

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.nav.NavGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DriftPlanTest {

    private val markLat = -27.25
    private val markLng = 153.40

    /** Fixes every [everyS] seconds for [seconds], drifting [speed] m/s towards [towards], with GPS wander of [noiseM]. */
    private fun drift(speed: Double, towards: Double, seconds: Int, everyS: Int = 1, noiseM: Double = 0.0, seed: Long = 3): List<DriftPlan.Fix> {
        val random = Random(seed)
        return (0..seconds step everyS).map { t ->
            val (lat, lng) = DriftPlan.destination(markLat, markLng, towards, speed * t)
            val (nLat, nLng) = DriftPlan.destination(lat, lng, random.nextDouble() * 360, noiseM * random.nextGaussian())
            DriftPlan.Fix(nLat, nLng, 1_700_000_000_000L + t * 1000L, accuracyM = 4.0)
        }
    }

    @Test
    fun `the destination goes as far as asked along the bearing`() {
        val (lat, lng) = DriftPlan.destination(markLat, markLng, 135.0, 500.0)
        assertEquals(500.0, NavGeo.distanceM(markLat, markLng, lat, lng), 0.01)
        assertEquals(135.0, NavGeo.bearing(markLat, markLng, lat, lng), 0.01)
        // Across the date line it stays a longitude.
        val (_, east) = DriftPlan.destination(0.0, 179.999, 90.0, 1000.0)
        assertTrue(east < -179.9)
    }

    @Test
    fun `a test drift gives its speed and direction, through the GPS wander`() {
        val m = DriftPlan.measure(drift(0.4, 120.0, 90, noiseM = 3.0))
        assertNull(m.problem)
        val d = m.drift!!
        assertEquals(0.4, d.speedMs, 0.03)
        assertEquals(120.0, d.towardsDegrees, 4.0)
        assertEquals(90.0, d.seconds, 1e-9)
        assertTrue(d.scatterM in 1.0..6.0)
    }

    @Test
    fun `too short, too few, or too little movement is said`() {
        assertEquals(DriftPlan.Problem.TOO_FEW, DriftPlan.measure(drift(0.5, 0.0, 3)).problem)
        assertEquals(DriftPlan.Problem.TOO_SHORT, DriftPlan.measure(drift(0.5, 0.0, 20)).problem)
        // 2 cm/s for a minute is 1.2 m, within the GPS's own wander.
        val slow = DriftPlan.measure(drift(0.02, 0.0, 60, noiseM = 2.0))
        assertEquals(DriftPlan.Problem.TOO_LITTLE, slow.problem)
        assertNotNull(slow.drift)
    }

    @Test
    fun `the start is up-drift, and the drift line passes over the mark`() {
        val drift = DriftPlan.Drift(speedMs = 0.5, towardsDegrees = 60.0)
        val plan = DriftPlan.plan(markLat, markLng, drift, leadSeconds = 180.0)!!
        assertEquals(240.0, plan.upDriftDegrees, 1e-9)
        assertEquals(90.0, plan.leadMetres, 1e-9)
        assertEquals(90.0, NavGeo.distanceM(markLat, markLng, plan.startLat, plan.startLng), 0.01)
        // From the start the boat drifts towards the mark...
        assertEquals(60.0, NavGeo.bearing(plan.startLat, plan.startLng, markLat, markLng), 0.01)
        // ...and the line from start to end goes right over it, halfway along.
        val start = RoutePoint(plan.startLat, plan.startLng)
        val end = RoutePoint(plan.endLat, plan.endLng)
        assertEquals(0.0, NavGeo.crossTrackM(start, end, markLat, markLng), 0.05)
        assertEquals(90.0, NavGeo.alongTrackM(start, end, markLat, markLng), 0.05)
        // 40 m across the mark at half a metre a second.
        assertEquals(80.0, plan.overMarkSeconds, 1e-9)
    }

    @Test
    fun `no drift, no plan`() {
        assertNull(DriftPlan.plan(markLat, markLng, DriftPlan.Drift(0.0, 0.0)))
        assertNull(DriftPlan.plan(markLat, markLng, DriftPlan.Drift(0.01, 90.0)))
        assertNull(DriftPlan.plan(markLat, markLng, DriftPlan.Drift(0.5, Double.NaN)))
    }

    @Test
    fun `the forecast estimate is the current plus a share of the wind`() {
        // Current 0.3 m/s to the north, wind 10 m/s blowing to the east (a westerly).
        val d = DriftPlan.estimate(0.0, 0.3, 10.0, 0.0, leeway = 0.04)
        assertTrue(d.estimate)
        assertEquals(0.5, d.speedMs, 1e-9)
        assertEquals(53.13, d.towardsDegrees, 0.01)
        // No wind: the current alone.
        val still = DriftPlan.estimate(-0.2, 0.0, 0.0, 0.0)
        assertEquals(0.2, still.speedMs, 1e-9)
        assertEquals(270.0, still.towardsDegrees, 1e-9)
    }

    @Test
    fun `rough and repeated fixes are left out, and enough is known`() {
        val fixes = listOf(
            DriftPlan.Fix(0.0, 0.0, 1000, 5.0),
            DriftPlan.Fix(0.0, 0.0, 1000, 5.0),
            DriftPlan.Fix(0.0, 0.0, 2000, 80.0),
            DriftPlan.Fix(0.0, 0.0, 3000, 0.0)
        )
        assertEquals(listOf(1000L, 3000L), DriftPlan.usable(fixes).map { it.timeMillis })
        assertTrue(DriftPlan.enough(drift(0.5, 0.0, 40)))
        assertTrue(!DriftPlan.enough(drift(0.5, 0.0, 20)))
    }

    @Test
    fun `a last known position from before the drift started is left out`() {
        val started = 1_700_000_000_000L
        // The phone's last known position, four minutes old and 300 m away, then four fresh fixes.
        val stale = DriftPlan.Fix(markLat + 0.003, markLng, started - 240_000L, 10.0)
        val fresh = (0..3).map { DriftPlan.Fix(markLat, markLng + it * 1e-5, started + 500L + it * 1000L, 4.0) }
        val all = listOf(stale) + fresh
        // Counted in, it would make four seconds look like four minutes, and enough to measure.
        assertTrue(DriftPlan.enough(DriftPlan.usable(all)))
        val kept = DriftPlan.usable(all, startedMillis = started)
        assertEquals(fresh.map { it.timeMillis }, kept.map { it.timeMillis })
        assertTrue(!DriftPlan.enough(kept))
        // A fix a second before the start is the start; one more than two seconds before isn't.
        assertTrue(DriftPlan.takenSince(started - 1_000L, started))
        assertTrue(!DriftPlan.takenSince(started - 2_500L, started))
    }

    @Test
    fun `a drift across the date line is measured the short way round`() {
        val fixes = (0..60).map { t ->
            val (lat, lng) = DriftPlan.destination(-16.0, 179.9999, 90.0, 0.5 * t)
            DriftPlan.Fix(lat, lng, 1_700_000_000_000L + t * 1000L, 4.0)
        }
        // It does cross: from east longitudes to west ones.
        assertTrue(fixes.first().lng > 179.0 && fixes.last().lng < -179.0)
        val d = DriftPlan.measure(fixes).drift!!
        assertEquals(0.5, d.speedMs, 0.01)
        assertEquals(90.0, d.towardsDegrees, 1.0)
        assertEquals(0.2, DriftPlan.lngDifference(-179.9, 179.9), 1e-9)
        assertEquals(-0.2, DriftPlan.lngDifference(179.9, -179.9), 1e-9)
        assertEquals(-180.0, DriftPlan.lngDifference(0.0, 180.0), 1e-9)
    }

    @Test
    fun `compass points`() {
        assertEquals("N", DriftPlan.compass(359.0))
        assertEquals("NNE", DriftPlan.compass(20.0))
        assertEquals("SE", DriftPlan.compass(135.0))
        assertEquals("W", DriftPlan.compass(-90.0))
    }
}
