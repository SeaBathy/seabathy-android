package au.opendepth.app.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class SeaUnitsTest {
    private val feet = NavFormat(NavUnits(tide = HeightUnit.FEET, waves = HeightUnit.FEET))
    private val metres = NavFormat(NavUnits())

    @Test
    fun tideHeights() {
        assertEquals("1.50 m", metres.tideHeight(1.5))
        assertEquals("4.9 ft", feet.tideHeight(1.5))
        assertEquals("4.9 ft", feet.tideMark(1.5))
        assertEquals("—", feet.tideHeight(null))
        assertEquals("0.30 m/h", metres.tideRate(0.3))
        assertEquals("1.0 ft/h", feet.tideRate(0.3))
    }

    @Test
    fun tideChartLinesSuitTheUnit() {
        assertEquals(0.5, metres.tideChartStep(2.0), 0.0)
        assertEquals(1.0, metres.tideChartStep(4.0), 0.0)
        assertEquals(1.0, feet.tideChartStep(1.5), 0.0)
        assertEquals(2.0, feet.tideChartStep(3.0), 0.0)
        assertEquals(5.0, feet.tideChartStep(5.0), 0.0)
    }

    @Test
    fun heightsConvertBack() {
        assertEquals(2.75, HeightUnit.FEET.toMetres(HeightUnit.FEET.of(2.75)), 1e-9)
    }

    @Test
    fun waveHeights() {
        assertEquals("2.0 m", metres.waveHeight(2.0))
        assertEquals("6.6 ft", feet.waveHeight(2.0))
    }

    @Test
    fun seaLevelFollowsTheTideUnit() {
        assertEquals("+10 cm", metres.seaLevel(10.0))
        assertEquals("+3.9 in", feet.seaLevel(10.0))
    }

    @Test
    fun windAndCurrentEachKeepTheirOwnUnit() {
        val f = NavFormat(NavUnits(wind = SeaSpeedUnit.METRES_PER_SECOND, current = SeaSpeedUnit.KMH))
        assertEquals("5.1 m/s", f.wind(10.0))
        assertEquals("18.5 km/h", f.current(10.0))
        assertEquals("—", f.wind(Double.NaN))
    }

    @Test
    fun beaufortForces() {
        val cases = mapOf(0.5 to 0, 1.0 to 1, 3.9 to 1, 10.0 to 3, 20.0 to 5, 33.9 to 7, 47.0 to 9, 63.9 to 11, 64.0 to 12, 100.0 to 12)
        cases.forEach { (knots, force) -> assertEquals("$knots kn", force, SeaSpeedUnit.beaufort(knots)) }
        assertEquals("5 Bft", NavFormat(NavUnits(wind = SeaSpeedUnit.BEAUFORT)).wind(20.0))
    }

    @Test
    fun startsFromWhatTheAppDidBefore() {
        assertEquals(SeaSpeedUnit.MPH, SeaSpeedUnit.like(SpeedUnit.MPH))
        assertEquals(HeightUnit.METRES, HeightUnit.defaultFor(DepthUnit.METRES, "AU"))
        assertEquals(HeightUnit.FEET, HeightUnit.defaultFor(DepthUnit.FATHOMS, "AU"))
        assertEquals(HeightUnit.FEET, HeightUnit.defaultFor(DepthUnit.METRES, "us"))
    }
}
