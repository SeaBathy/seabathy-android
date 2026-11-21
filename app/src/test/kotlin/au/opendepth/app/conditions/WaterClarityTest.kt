package au.opendepth.app.conditions

import au.opendepth.app.nav.DepthUnit
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterClarityTest {

    @Test
    fun `the clarity comes from NOAA CoastWatch's Kd490, blended over five days`() {
        val urls = ConditionsClient.satelliteUrls(SatelliteKind.WATER_CLARITY, -27.5, 153.2, -27.0, 153.6)
        assertEquals(2, urls.size)
        val first = urls.first()
        assertTrue(first, first.startsWith("https://coastwatch.noaa.gov/erddap/griddap/noaacwNPPVIIRSkd490Daily.csv?kd_490"))
        assertTrue(urls[1], urls[1].startsWith("https://polarwatch.noaa.gov/erddap/griddap/noaacwNPPVIIRSkd490Daily.csv?kd_490"))
        // Five days, the surface, north first (as the dataset stores it).
        assertTrue(first, "%5Blast-4:1:last%5D%5B(0.0)%5D" in first)
        assertTrue(first, "%5B(-27.000):1:(-27.500)%5D" in first)
        assertTrue(first, "%5B(153.200):1:(153.600)%5D" in first)
        // A big area is thinned to keep the download small.
        val wide = ConditionsClient.satelliteUrls(SatelliteKind.WATER_CLARITY, -30.0, 150.0, -20.0, 160.0).first()
        assertTrue(wide, ":3:" in wide)
    }

    @Test
    fun `days are blended on a log scale, as Kd490 spans orders of magnitude`() {
        val csv = "time,altitude,latitude,longitude,kd_490\nUTC,m,degrees_north,degrees_east,m^-1\n" +
            "2026-11-09T12:00:00Z,0.0,-27.0,153.2,0.1\n2026-11-09T12:00:00Z,0.0,-27.0,153.24,NaN\n" +
            "2026-11-11T12:00:00Z,0.0,-27.0,153.2,1.0\n2026-11-11T12:00:00Z,0.0,-27.0,153.24,0.05\n"
        val grid = ConditionsClient.parseErddapCsv(csv, SatelliteKind.WATER_CLARITY)
        // The geometric mean of 0.1 and 1.0; a cloudy day leaves the other day's value.
        assertEquals(0.3162f, grid.values[0], 1e-4f)
        assertEquals(0.05f, grid.values[1], 1e-6f)
        assertEquals(java.time.Instant.parse("2026-11-11T12:00:00Z").toEpochMilli(), grid.dateMillis)
    }

    @Test
    fun `visibility is about 1_7 over Kd490, kept within what means anything`() {
        assertEquals(8.5f, WaterClarity.visibilityMetres(0.2f), 1e-4f)
        assertEquals(34f, WaterClarity.visibilityMetres(0.05f), 1e-4f)
        assertEquals(WaterClarity.MAX_VISIBILITY_M.toFloat(), WaterClarity.visibilityMetres(0.016f), 0f)
        assertEquals(WaterClarity.MIN_VISIBILITY_M.toFloat(), WaterClarity.visibilityMetres(30f), 0f)
        assertTrue(WaterClarity.visibilityMetres(Float.NaN).isNaN())
        assertTrue(WaterClarity.visibilityMetres(0f).isNaN())
    }

    @Test
    fun `the colours run from murky to clear on a fixed log scale`() {
        val scale = ColourScale.forChoice(ColourChoice.WATER_CLARITY, floatArrayOf(0.1f, 0.2f))
        assertTrue(scale.logarithmic)
        assertEquals(1.0, scale.low, 0.0)
        assertEquals(30.0, scale.high, 0.0)
        assertEquals(0.0, scale.position(1.0), 1e-9)
        assertEquals(1.0, scale.position(30.0), 1e-9)
        // Halfway on a log scale is the geometric middle, about 5.5 m.
        assertEquals(0.5, scale.position(Math.sqrt(30.0)), 1e-9)
        assertEquals(WaterClarity.COLOURS.first(), scale.colour(0.5))
        assertEquals(WaterClarity.COLOURS.last(), scale.colour(45.0))
        assertTrue(ColourChoice.WATER_CLARITY.fixedRange)
    }

    @Test
    fun `it is a satellite picture of past days, so no flow lines and no time slider`() {
        assertEquals(SatelliteKind.WATER_CLARITY, ColourChoice.WATER_CLARITY.satellite)
        assertTrue(!ColourChoice.WATER_CLARITY.forecast)
        assertEquals(ArrowChoice.NONE, ArrowChoice.SAME.resolve(ColourChoice.WATER_CLARITY))
    }

    @Test
    fun `clarity reaches one cell to the shore, like chlorophyll, not two`() {
        // One row of 4 km cells: clear water offshore, then the blank cells along the shore.
        val grid = GeoGrid(doubleArrayOf(-27.0), doubleArrayOf(153.0, 153.04, 153.08, 153.12))
        fun kind(k: SatelliteKind) = SatelliteGrid(k, grid, 0L, floatArrayOf(0.05f, Float.NaN, Float.NaN, Float.NaN))
        val area = ConditionsArea(
            "a", "Test", -27.1, 153.0, -26.9, 153.12, 0L, null,
            mapOf(SatelliteKind.WATER_CLARITY to kind(SatelliteKind.WATER_CLARITY), SatelliteKind.SEA_LEVEL to kind(SatelliteKind.SEA_LEVEL))
        )
        val clarity = area.toShore(SatelliteKind.WATER_CLARITY)!!.values
        assertEquals(0.05f, clarity[1], 1e-6f)
        assertTrue(clarity[2].isNaN())
        // The coarse layers still go two cells.
        assertTrue(!area.toShore(SatelliteKind.SEA_LEVEL)!!.values[2].isNaN())
    }

    @Test
    fun `visibility is worded in whole units, or a tenth when under 3 m`() {
        val metres = NavFormat(NavUnits())
        assertEquals("8 m", WaterClarity.text(8.4, metres))
        assertEquals("2.5 m", WaterClarity.text(2.46, metres))
        val feet = NavFormat(NavUnits(depth = DepthUnit.FEET))
        assertEquals("26.2 ft", WaterClarity.text(8.0, feet))
    }
}
