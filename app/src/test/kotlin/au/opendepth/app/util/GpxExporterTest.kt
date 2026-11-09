package au.opendepth.app.util

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class GpxExporterTest {

    private fun waypoint(name: String, desc: String? = null) = Waypoint(
        id = "id-$name",
        name = name,
        lat = -18.25,
        lng = 147.5,
        desc = desc
    )

    private fun parse(gpx: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(gpx.byteInputStream())

    @Test
    fun `waypoint names use the GPX 1_1 name element`() {
        val gpx = GpxExporter.build(listOf(waypoint("Reef edge")), emptyList())

        assertTrue(gpx.contains("<name>Reef edge</name>"))
        assertFalse("the invalid <n> tag must not be emitted", gpx.contains("<n>"))
    }

    @Test
    fun `route names use the GPX 1_1 name element`() {
        val gpx = GpxExporter.build(
            emptyList(),
            listOf(RoutePoint(-18.0, 147.0), RoutePoint(-18.1, 147.1)),
            routeName = "Passage north"
        )

        assertTrue(gpx.contains("<name>Passage north</name>"))
        assertFalse(gpx.contains("<n>"))
    }

    @Test
    fun `special characters are escaped rather than corrupting the document`() {
        val gpx = GpxExporter.build(
            listOf(waypoint("Rocks & \"shoal\" <5m> 'here'")),
            emptyList()
        )

        assertTrue(gpx.contains("&amp;"))
        assertTrue(gpx.contains("&quot;"))
        assertTrue(gpx.contains("&lt;5m&gt;"))
        assertTrue(gpx.contains("&apos;"))
    }

    @Test
    fun `output stays well formed XML for hostile waypoint names`() {
        val hostileName = "</name></wpt></gpx><script>"
        val gpx = GpxExporter.build(
            listOf(
                waypoint(hostileName, desc = "note & more"),
                waypoint("Ampersand & co"),
                waypoint("Unicode ✛ ° é")
            ),
            listOf(RoutePoint(-18.0, 147.0), RoutePoint(-18.2, 147.2)),
            routeName = "Route <with> \"quotes\" & ampersand"
        )

        val document = parse(gpx)

        assertEquals("gpx", document.documentElement.nodeName)
        assertEquals(3, document.getElementsByTagName("wpt").length)
        assertEquals(1, document.getElementsByTagName("rte").length)
        // Three waypoint names plus the route name.
        assertEquals(4, document.getElementsByTagName("name").length)
        assertEquals(
            hostileName,
            document.getElementsByTagName("name").item(0).textContent
        )
        assertEquals(
            "note & more",
            document.getElementsByTagName("desc").item(0).textContent
        )
    }

    @Test
    fun `illegal control characters are dropped instead of breaking the parse`() {
        val gpx = GpxExporter.build(
            listOf(waypoint("Bell" + 7.toChar() + "and" + 0.toChar() + "null")),
            emptyList()
        )

        val document = parse(gpx)

        assertEquals(
            "Bellandnull",
            document.getElementsByTagName("name").item(0).textContent
        )
    }

    @Test
    fun `coordinates are written with a decimal point regardless of locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val gpx = GpxExporter.build(listOf(waypoint("Comma test")), emptyList())
            assertTrue(gpx.contains("lat=\"-18.25\""))
            assertTrue(gpx.contains("lon=\"147.5\""))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `coordinates near zero are not written in scientific notation`() {
        val gpx = GpxExporter.build(
            listOf(Waypoint(id = "z", name = "Equator", lat = -0.0001, lng = 0.00005)),
            listOf(RoutePoint(0.0001, 0.0), RoutePoint(0.0002, 0.0))
        )

        assertFalse(gpx.contains("E-"))
        assertTrue(gpx.contains("lat=\"-0.0001\""))
        assertTrue(gpx.contains("lon=\"0.00005\""))
        parse(gpx)
    }

    @Test
    fun `a single route point is not written as a route`() {
        val gpx = GpxExporter.build(emptyList(), listOf(RoutePoint(-18.0, 147.0)))

        assertFalse(gpx.contains("<rte>"))
        parse(gpx)
    }

    @Test
    fun `an empty export is still a valid document`() {
        parse(GpxExporter.build(emptyList(), emptyList()))
    }

    @Test
    fun `a blank description is omitted`() {
        val gpx = GpxExporter.build(listOf(waypoint("No notes", desc = "  ")), emptyList())

        assertFalse(gpx.contains("<desc>"))
    }
}
