package au.opendepth.app.model

import au.opendepth.app.settings.XyzTilePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCreditsTest {

    private val old = "Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH " +
        "(Contains modified Copernicus Sentinel data 2024)"
    private val now = "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH " +
        "(Contains modified Copernicus Sentinel data 2024)"

    @Test
    fun `the built-in satellite map carries its credit as worded now`() {
        assertEquals(now, MapCredits.EOX_CLOUDLESS)
        assertEquals(now, DataSource.DEFAULT_PRESET.attribution)
        assertEquals(now, XyzTilePresets.DEFAULT_SATELLITE.attribution)
    }

    @Test
    fun `a credit saved in the old built-in wording is shown in the new`() {
        assertEquals(now, MapCredits.current(old))
        assertEquals(now, MapCredits.current("  $old\n"))
    }

    @Test
    fun `any other saved credit is shown exactly as it was saved`() {
        assertEquals("", MapCredits.current(""))
        assertEquals("© My chart company", MapCredits.current("© My chart company"))
        assertEquals(now, MapCredits.current(now))
        // Only the whole old wording counts: a credit somebody typed that merely contains it is theirs.
        assertEquals("$old and others", MapCredits.current("$old and others"))
        assertEquals("Sentinel-2 cloudless", MapCredits.current("Sentinel-2 cloudless"))
    }

    @Test
    fun `no built-in map adds a provider's name to a credit`() {
        XyzTilePresets.ALL.forEach { preset ->
            assertFalse(preset.label, preset.attribution.contains("Powered by", ignoreCase = true))
        }
    }

    private val basemap = MapCredit(MapCredit.Group.BASEMAP, "", "OpenFreeMap © OpenMapTiles • © OpenStreetMap contributors")
    private val survey = MapCredit(MapCredit.Group.LAYER, "Outer reef", "Depths: GMRT, Ryan et al. 2009 (CC BY 4.0)")
    private val pack = MapCredit(MapCredit.Group.LAYER, "Satellite pack", now)
    private val zones = MapCredit(MapCredit.Group.BUILT_IN, "", "Marine park zones: DCCEEW (CC BY)")

    @Test
    fun `the line starts with not for navigation and lists each credit once`() {
        val twice = MapCredit(MapCredit.Group.LAYER, "Outer reef, east", survey.text)
        assertEquals(
            "Not for navigation • ${basemap.text} • ${survey.text} • ${pack.text} • ${zones.text}",
            MapCredits.line(listOf(basemap, survey, twice, pack, zones))
        )
    }

    @Test
    fun `with nothing to credit the line still says not for navigation`() {
        assertEquals("Not for navigation", MapCredits.line(emptyList()))
        assertEquals("Not for navigation", MapCredits.line(listOf(MapCredit(MapCredit.Group.LAYER, "My survey", "  "))))
    }

    @Test
    fun `the full list has the basemap, then each layer by name, then built-in data`() {
        val sections = MapCredits.sections(listOf(basemap, survey, pack, zones))
        assertEquals(listOf("BASEMAP", "LAYERS ON THE CHART", "BUILT-IN DATA"), sections.map { it.title })
        assertEquals(listOf(basemap.text), sections[0].lines)
        assertEquals(listOf("Outer reef: ${survey.text}", "Satellite pack: $now"), sections[1].lines)
        assertEquals(listOf(zones.text), sections[2].lines)
    }

    @Test
    fun `two layers with the same credit are each listed, unlike on the line`() {
        val twice = MapCredit(MapCredit.Group.LAYER, "Outer reef, east", survey.text)
        val lines = MapCredits.sections(listOf(survey, twice)).single().lines
        assertEquals(listOf("Outer reef: ${survey.text}", "Outer reef, east: ${survey.text}"), lines)
    }

    @Test
    fun `a heading with nothing under it is left out`() {
        assertTrue(MapCredits.sections(emptyList()).isEmpty())
        assertEquals(listOf("LAYERS ON THE CHART"), MapCredits.sections(listOf(survey)).map { it.title })
        // A layer with no credit has nothing to list.
        assertTrue(MapCredits.sections(listOf(MapCredit(MapCredit.Group.LAYER, "My survey", ""))).isEmpty())
    }

    @Test
    fun `everything on the line is in the full list`() {
        val credits = listOf(basemap, survey, pack, zones)
        val listed = MapCredits.sections(credits).flatMap { it.lines }.joinToString("\n")
        MapCredits.line(credits).split(MapCredits.SEPARATOR).drop(1).forEach { credit ->
            assertTrue("$credit is missing from the full list", listed.contains(credit))
        }
    }
}
