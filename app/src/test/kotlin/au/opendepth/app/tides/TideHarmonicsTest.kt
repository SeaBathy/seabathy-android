package au.opendepth.app.tides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TideHarmonicsTest {

    private val hour = 3_600_000L

    /** 2024-01-01 UTC: a real date, so the nodal cycle is where it really is. */
    private val start = 1_704_067_200_000L

    /** Longitude of the moon's node, radians (Meeus). */
    private fun node(t: Long): Double {
        val centuries = (t / 86_400_000.0 + 2440587.5 - 2451545.0) / 36525.0
        return Math.toRadians(125.0445479 - 1934.1362891 * centuries)
    }

    /**
     * A made-up tide: M2 1.2 m, S2 0.4 m, K1 0.35 m, O1 0.2 m, mean 0.05 m,
     * with the lunar ones changing over the nodal cycle as real tides do.
     */
    private fun truth(t: Long): Double {
        val h = (t - start) / 3_600_000.0
        val n = node(t)
        fun c(speed: Double, amp: Double, phaseDeg: Double, f: Double = 1.0, uDeg: Double = 0.0) =
            f * amp * cos(Math.toRadians(speed * h - phaseDeg + uDeg))
        return 0.05 +
            c(28.9841042, 1.2, 40.0, 1.0004 - 0.0373 * cos(n), -2.14 * sin(n)) +
            c(30.0, 0.4, 75.0) +
            c(15.0410686, 0.35, 200.0, 1.0060 + 0.1150 * cos(n), -8.86 * sin(n)) +
            c(13.9430356, 0.2, 10.0, 1.0089 + 0.1871 * cos(n), 10.80 * sin(n))
    }

    @Test
    fun `a year of data predicts two years ahead`() {
        val times = LongArray(24 * 365) { start + it * hour }
        // A little noise, like a real model has.
        val heights = DoubleArray(times.size) { truth(times[it]) + 0.02 * sin(it * 1.7) }
        val fit = TideHarmonics.fit(times, heights)
        assertEquals(0.4, fit.amplitude("S2"), 0.01)
        assertEquals(0.05, fit.mean, 0.01)

        // Two years beyond the data.
        val from = start + 24L * 365 * 3 * hour
        val predicted = fit.heights(from, 24 * 30)
        var worst = 0.0
        for (k in predicted.indices) worst = maxOf(worst, abs(predicted[k] - truth(from + k * hour)))
        assertTrue("worst error $worst", worst < 0.03)
    }

    @Test
    fun `lowest astronomical tide is the springs low over the nodal cycle`() {
        // M2 1 m, S2 0.4 m, K1 0.3 m, O1 0.2 m around a 0.1 m mean.
        val n = TideHarmonics.CONSTITUENTS.size
        val cos = DoubleArray(n)
        fun set(name: String, amp: Double) { cos[TideHarmonics.CONSTITUENTS.indexOfFirst { it.name == name }] = amp }
        set("M2", 1.0); set("S2", 0.4); set("K1", 0.3); set("O1", 0.2)
        val h = TideHarmonics(0.1, cos, DoubleArray(n), epochMillis = 0L)
        val lat = h.lowestAstronomical(1_700_000_000_000L)
        // Below an ordinary springs low, above all four at their lowest together.
        assertTrue("LAT $lat", lat < 0.1 - 1.4 && lat > 0.1 - 2.05)
        // The same as a brute-force search of all 19 years every 5 minutes.
        val all = h.heights(1_700_000_000_000L, (19 * 365.25 * 24 * 12).toInt(), 5 * 60_000L)
        assertEquals(all.min(), lat, 0.005)
        assertTrue(all.min() >= lat - 1e-6)
    }

    @Test
    fun `heights in a batch match one at a time`() {
        val times = LongArray(24 * 60) { start + it * hour }
        val fit = TideHarmonics.fit(times, DoubleArray(times.size) { truth(times[it]) })
        val from = start + 1000 * hour + 1234
        val batch = fit.heights(from, 24 * 400)
        for (k in batch.indices step 97) assertEquals(fit.heightAt(from + k * hour), batch[k], 1e-6)
    }

    @Test
    fun `tides saved before nodal corrections still predict as they did`() {
        val times = LongArray(24 * 120) { start + it * hour }
        val plain = TideHarmonics.fit(times, DoubleArray(times.size) { truth(times[it]) }, nodal = false)
        val reloaded = TideHarmonics(plain.mean, plain.cosines, plain.sines, plain.epochMillis, nodal = false)
        assertEquals(plain.heightAt(start + 50 * hour), reloaded.heightAt(start + 50 * hour), 1e-12)
    }

    @Test
    fun `curve gives hourly heights over a range`() {
        val times = LongArray(24 * 120) { start + it * hour }
        val fit = TideHarmonics.fit(times, DoubleArray(times.size) { truth(times[it]) })
        val curve = fit.curve(start + 500 * hour + 1234, start + 600 * hour)
        assertTrue(curve.covers(start + 550 * hour))
        assertEquals(truth(start + 550 * hour), curve.heightAt(start + 550 * hour)!!, 0.05)
    }
}
