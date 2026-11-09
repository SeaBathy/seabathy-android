package au.opendepth.app.tides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class TideCurveTest {

    private val hour = TideCurve.HOUR_MS

    /** A 12.42 h semidiurnal tide, amplitude 1.2 m, sampled hourly for 3 days. */
    private fun semidiurnal(): TideCurve {
        val period = 12.42
        val times = LongArray(72) { it * hour }
        val heights = DoubleArray(72) { 1.2 * cos(2 * PI * it / period) }
        return TideCurve(times, heights)
    }

    @Test
    fun `heights between samples follow the curve`() {
        val curve = semidiurnal()
        val t = (2.5 * hour).toLong()
        val expected = 1.2 * cos(2 * PI * 2.5 / 12.42)
        assertEquals(expected, curve.heightAt(t)!!, 0.02)
        assertNull(curve.heightAt(-1))
    }

    @Test
    fun `highs and lows land at the right times`() {
        val curve = semidiurnal()
        val extremes = curve.extremes(hour, 30 * hour)
        // Lows at 6.21 h and 18.63 h, highs at 12.42 h and 24.84 h.
        assertEquals(4, extremes.size)
        assertTrue(!extremes[0].isHigh)
        assertEquals(6.21, extremes[0].timeMillis / hour.toDouble(), 0.1)
        assertEquals(-1.2, extremes[0].heightM, 0.03)
        assertTrue(extremes[1].isHigh)
        assertEquals(12.42, extremes[1].timeMillis / hour.toDouble(), 0.1)
        assertEquals(1.2, extremes[1].heightM, 0.03)
    }

    @Test
    fun `rising and falling`() {
        val curve = semidiurnal()
        assertTrue(curve.rateMPerHour(3 * hour)!! < 0) // falling towards the low
        assertTrue(curve.rateMPerHour(9 * hour)!! > 0) // rising towards the high
    }

    @Test
    fun `tide above datum for each mode`() {
        val curve = semidiurnal()
        val forecast = TideForecast(0.0, 0.0, 0.0, 0.0, 0L, curve)
        assertEquals(1.2, TideSettings(datum = TideDatum.MEAN_SEA_LEVEL).heightAboveDatum(forecast, 0)!!, 1e-9)
        assertEquals(2.4, TideSettings(datum = TideDatum.CHART_DATUM, chartDatumAuto = false, chartDatumBelowMslM = 1.2).heightAboveDatum(forecast, 0)!!, 1e-9)
        assertEquals(0.7, TideSettings(mode = TideMode.MANUAL, datum = TideDatum.CHART_DATUM, manualHeightM = 0.7).heightAboveDatum(forecast, 0)!!, 1e-9)
        assertNull(TideSettings(mode = TideMode.OFF).heightAboveDatum(forecast, 0))
        // Chart-datum survey before chart datum is known here: depths stay as charted.
        assertNull(TideSettings(datum = TideDatum.CHART_DATUM).heightAboveDatum(forecast, 0))
    }

    @Test
    fun `heights are shown above the lowest tide once it is known`() {
        val curve = semidiurnal()
        val low = curve.extremes(0, 12 * hour).first { !it.isHigh }
        val unknown = TideForecast(0.0, 0.0, 0.0, 0.0, 0L, curve)
        val known = TideForecast(0.0, 0.0, 0.0, 0.0, 0L, curve, lowestTideM = -1.5)
        val s = TideSettings()
        // From mean sea level the low reads below zero; above LAT it doesn't.
        assertTrue(s.shownHeight(unknown, low.timeMillis)!! < 0)
        assertTrue(s.shownHeight(known, low.timeMillis)!! >= 0)
        assertEquals(1.2 + 1.5, s.shownHeight(known, 0)!!, 1e-9)
        assertTrue(s.showsChartDatum(known))
        // Depth correction for a mean-sea-level survey is unchanged by how it's shown.
        assertEquals(1.2, s.heightAboveDatum(known, 0)!!, 1e-9)
        // A chart-datum survey adds the height above LAT.
        assertEquals(2.7, s.copy(datum = TideDatum.CHART_DATUM).heightAboveDatum(known, 0)!!, 1e-9)
        // A tide-table height (above LAT) on a mean-sea-level survey.
        assertEquals(0.5 - 1.5, s.copy(mode = TideMode.MANUAL, manualHeightM = 0.5).heightAboveDatum(known, 0)!!, 1e-9)
        // A typed chart datum wins over the worked-out one.
        assertEquals(1.2 + 2.0, s.copy(chartDatumAuto = false, chartDatumBelowMslM = 2.0).shownHeight(known, 0)!!, 1e-9)
    }
}
