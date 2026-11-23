package au.opendepth.app.conditions

import au.opendepth.app.conditions.ColourChoice.CHLOROPHYLL
import au.opendepth.app.conditions.ColourChoice.SALINITY
import au.opendepth.app.conditions.ColourChoice.SEA_LEVEL
import au.opendepth.app.conditions.ColourChoice.SEA_TEMP_BREAKS
import au.opendepth.app.conditions.ColourChoice.SEA_TEMP_FORECAST
import au.opendepth.app.conditions.ColourChoice.SEA_TEMP_SATELLITE
import au.opendepth.app.conditions.ColourChoice.WATER_CLARITY
import au.opendepth.app.conditions.ColourChoice.WIND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoxCycleTest {

    private val all = SatelliteKind.entries.toSet()

    @Test
    fun `the Sea line steps from temperature to chlorophyll, then off`() {
        val cycle = BoxCycle.of("Sea", all, forecastSeaTemp = true)
        assertEquals(listOf(SEA_TEMP_SATELLITE, CHLOROPHYLL), cycle)
        assertEquals(CHLOROPHYLL, BoxCycle.next(cycle, SEA_TEMP_SATELLITE))
        assertNull(BoxCycle.next(cycle, CHLOROPHYLL))
        assertEquals("1 of 2", BoxCycle.label(cycle, SEA_TEMP_SATELLITE))
        assertEquals("2 of 2", BoxCycle.label(cycle, CHLOROPHYLL))
        // Temperature breaks (picked in Settings) count as the temperature step.
        assertEquals(CHLOROPHYLL, BoxCycle.next(cycle, SEA_TEMP_BREAKS))
        assertEquals("1 of 2", BoxCycle.label(cycle, SEA_TEMP_BREAKS))
    }

    @Test
    fun `the Water line steps sea level, water clarity, then salinity`() {
        val cycle = BoxCycle.of("Water", all, forecastSeaTemp = false)
        assertEquals(listOf(SEA_LEVEL, WATER_CLARITY, SALINITY), cycle)
        assertEquals(WATER_CLARITY, BoxCycle.next(cycle, SEA_LEVEL))
        assertEquals(SALINITY, BoxCycle.next(cycle, WATER_CLARITY))
        assertNull(BoxCycle.next(cycle, SALINITY))
        assertEquals("2 of 3", BoxCycle.label(cycle, WATER_CLARITY))
    }

    @Test
    fun `only the maps the area has, and no count for a single one`() {
        val clarityOnly = BoxCycle.of("Water", setOf(SatelliteKind.WATER_CLARITY), forecastSeaTemp = false)
        assertEquals(listOf(WATER_CLARITY), clarityOnly)
        assertNull(BoxCycle.label(clarityOnly, WATER_CLARITY))
        assertNull(BoxCycle.next(clarityOnly, WATER_CLARITY))
        // The forecast's sea temperature when there's no satellite picture.
        assertEquals(listOf(SEA_TEMP_FORECAST), BoxCycle.of("Sea", emptySet(), forecastSeaTemp = true))
        // Lines with one map of their own aren't cycled here.
        assertEquals(emptyList<ColourChoice>(), BoxCycle.of("Wind", all, forecastSeaTemp = true))
        // A map that isn't one of the line's: no next, no count.
        val sea = BoxCycle.of("Sea", all, forecastSeaTemp = true)
        assertNull(BoxCycle.next(sea, WIND))
        assertNull(BoxCycle.label(sea, WIND))
    }

    @Test
    fun `the line shows only the reading of the map that is showing`() {
        val water = listOf(SEA_LEVEL to "sea level 8 cm above average", WATER_CLARITY to "visibility about 16 m (estimate)", SALINITY to "salinity 35.2")
        assertEquals("Visibility about 16 m (estimate)", BoxCycle.textFor(water, WATER_CLARITY))
        assertEquals("Salinity 35.2", BoxCycle.textFor(water, SALINITY))
        assertEquals("Sea level 8 cm above average", BoxCycle.textFor(water, SEA_LEVEL))
        val sea = listOf(SEA_TEMP_SATELLITE to "Surface temp 24.9 °C (20 Nov)", CHLOROPHYLL to "chlorophyll 0.41 mg/m³")
        assertEquals("Chlorophyll 0.41 mg/m³", BoxCycle.textFor(sea, CHLOROPHYLL))
        // Temperature breaks show the sea temperature reading.
        assertEquals("Surface temp 24.9 °C (20 Nov)", BoxCycle.textFor(sea, SEA_TEMP_BREAKS))
        // The map shows but has no reading at this point (a cloud gap, say).
        assertEquals("Chlorophyll: no reading here", BoxCycle.textFor(sea.take(1), CHLOROPHYLL))
        // A line without parts keeps its own words.
        assertNull(BoxCycle.textFor(emptyList(), WIND))
    }
}
