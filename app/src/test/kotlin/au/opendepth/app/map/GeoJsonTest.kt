package au.opendepth.app.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [GeoJson.jsonEscape], added when waypoint names (free-typed user
 * text) started being embedded into this file's hand-built JSON strings for
 * the first time -- every other property here so far has been machine
 * generated and never needed escaping.
 */
class GeoJsonTest {

    @Test
    fun `a plain name is left untouched`() {
        assertEquals("Reef marker", GeoJson.jsonEscape("Reef marker"))
    }

    @Test
    fun `a double quote is escaped`() {
        assertEquals("6\\\" hole", GeoJson.jsonEscape("6\" hole"))
    }

    @Test
    fun `a backslash is escaped`() {
        assertEquals("a\\\\b", GeoJson.jsonEscape("a\\b"))
    }

    @Test
    fun `newlines and tabs are escaped`() {
        assertEquals("a\\nb\\tc", GeoJson.jsonEscape("a\nb\tc"))
    }

    @Test
    fun `escaped output stays valid as a JSON string body`() {
        val name = """Wreck "Endeavour" \ north"""
        val escaped = GeoJson.jsonEscape(name)
        val feature = GeoJson.point(lat = 1.0, lng = 2.0, properties = """{"name":"$escaped"}""")

        // A real parser round-trip is the actual proof this stays valid,
        // not just eyeballing the escaped string.
        val parsed = com.google.gson.JsonParser.parseString(feature).asJsonObject
        assertEquals(name, parsed.getAsJsonObject("properties").get("name").asString)
    }
}
