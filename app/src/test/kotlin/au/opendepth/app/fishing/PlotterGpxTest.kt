package au.opendepth.app.fishing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class PlotterGpxTest {

    private fun parse(gpx: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(gpx.byteInputStream())

    /** A wiggly line along a parallel: [n] points over about [km] km. */
    private fun line(n: Int, km: Double = 2.0, lat: Double = -27.3): DoubleArray = DoubleArray(n * 2) { i ->
        val k = i / 2
        if (i % 2 == 0) 153.3 + k * (km / 100.0) / n else lat + if (k % 2 == 0) 0.0 else 1e-6
    }

    @Test
    fun `points are waypoints and lines are named tracks, in valid GPX 1_1`() {
        val built = PlotterGpx.build(
            points = listOf(PlotterGpx.Point("Pinnacle 18 m", -27.25, 153.4, "Rises 4.2 m & more <check>")),
            lines = listOf(PlotterGpx.Line("20 m contour", doubleArrayOf(153.30, -27.30, 153.31, -27.31, 153.32, -27.30)), PlotterGpx.Line("21.5 °C", line(3))),
            description = "From SeaBathy"
        )
        val doc = parse(built.gpx)
        val root = doc.documentElement
        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        val wpt = doc.getElementsByTagName("wpt").item(0) as Element
        assertEquals("-27.250000", wpt.getAttribute("lat"))
        assertEquals("153.400000", wpt.getAttribute("lon"))
        assertEquals("Pinnacle 18 m", wpt.getElementsByTagName("name").item(0).textContent)
        assertEquals("Rises 4.2 m & more <check>", wpt.getElementsByTagName("desc").item(0).textContent)
        val tracks = doc.getElementsByTagName("trk")
        assertEquals(2, tracks.length)
        assertEquals("20 m contour", (tracks.item(0) as Element).getElementsByTagName("name").item(0).textContent)
        assertEquals("21.5 °C", (tracks.item(1) as Element).getElementsByTagName("name").item(0).textContent)
        assertEquals(3, (tracks.item(0) as Element).getElementsByTagName("trkpt").length)
        assertEquals(1, built.waypoints)
        assertEquals(2, built.tracks)
        assertEquals(0, built.leftOut)
        assertFalse("never an exponent", built.gpx.contains("E-"))
    }

    @Test
    fun `a second line or point with the same name gets a number`() {
        val built = PlotterGpx.build(
            points = listOf(PlotterGpx.Point("Ledge 22 m", 0.0, 0.0), PlotterGpx.Point("ledge 22 m", 0.0, 0.0001)),
            lines = listOf(PlotterGpx.Line("20 m contour", line(2)), PlotterGpx.Line("20 m contour", line(2, lat = -27.4)))
        )
        val doc = parse(built.gpx)
        val names = (0 until doc.getElementsByTagName("name").length).map { doc.getElementsByTagName("name").item(it).textContent }
        assertEquals(listOf("Ledge 22 m", "ledge 22 m 2", "20 m contour", "20 m contour 2"), names)
    }

    @Test
    fun `a nearly straight line is simplified to its ends, and a corner is kept`() {
        val straight = line(500)
        val simplified = PlotterGpx.simplify(straight, 1.0)
        assertEquals(4, simplified.size)
        assertEquals(straight[0], simplified[0], 0.0)
        assertEquals(straight[straight.size - 1], simplified[3], 0.0)
        // An L: the corner is 100 m off the straight line between the ends.
        val corner = doubleArrayOf(153.0, -27.0, 153.0005, -27.0, 153.001, -27.0, 153.001, -27.0005, 153.001, -27.001)
        assertEquals(6, PlotterGpx.simplify(corner, 5.0).size)
    }

    @Test
    fun `the counts are capped and what is left out is counted`() {
        val many = (0 until 30).map { PlotterGpx.Line("Line $it", line(50, lat = -27.0 - it * 0.01)) }
        val built = PlotterGpx.build(
            points = (0 until 12).map { PlotterGpx.Point("P$it", -27.0, 153.0 + it * 0.001) },
            lines = many,
            limits = PlotterGpx.Limits(maxWaypoints = 10, maxTracks = 20, maxPointsPerTrack = 1000, maxTotalPoints = 5000, toleranceM = 0.0)
        )
        assertEquals(10, built.waypoints)
        assertEquals(20, built.tracks)
        assertEquals(2 + 10, built.leftOut)
        // Over the total: lines are simplified harder rather than dropped.
        val busy = (0 until 5).map { k -> PlotterGpx.Line("Busy $k", DoubleArray(2000 * 2) { i -> if (i % 2 == 0) 153.0 + (i / 2) * 1e-5 else -27.0 + k * 0.01 + Math.sin(i / 2 * 0.3) * 2e-4 }) }
        val capped = PlotterGpx.build(emptyList(), busy, PlotterGpx.Limits(maxPointsPerTrack = 500, maxTotalPoints = 1500, toleranceM = 0.5))
        assertEquals(5, capped.tracks)
        assertTrue(capped.trackPoints <= 1500)
        assertEquals(0, capped.leftOut)
        val doc = parse(capped.gpx)
        assertEquals(capped.trackPoints, doc.getElementsByTagName("trkpt").length)
    }

    @Test
    fun `lines are cut exactly at the edge of the screen`() {
        // Five points west to east; the box holds the middle three, and the line is cut where it crosses the edges.
        val points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 3.0, 0.0, 4.0, 0.0)
        val runs = PlotterGpx.clip(points, 0.5, -1.0, 3.5, 1.0)
        assertEquals(1, runs.size)
        assertEquals(listOf(0.5, 0.0, 1.0, 0.0, 2.0, 0.0, 3.0, 0.0, 3.5, 0.0), runs.single().toList())
        // In, out, back in: two runs, each ending or starting on the edge.
        val wavy = doubleArrayOf(0.0, 0.0, 1.0, 5.0, 2.0, 0.0)
        val parts = PlotterGpx.clip(wavy, -1.0, -1.0, 3.0, 1.0)
        assertEquals(2, parts.size)
        assertEquals(listOf(0.0, 0.0, 0.2, 1.0), parts[0].toList().map { Math.round(it * 1e9) / 1e9 })
        assertEquals(listOf(1.8, 1.0, 2.0, 0.0), parts[1].toList().map { Math.round(it * 1e9) / 1e9 })
        assertTrue(PlotterGpx.clip(points, 10.0, 10.0, 11.0, 11.0).isEmpty())
    }

    @Test
    fun `a long segment crossing the screen with both ends off it is kept`() {
        // A zone edge 6 km long; the screen is 4 km wide and holds neither end.
        val edge = doubleArrayOf(-1.0, 0.5, 5.0, 0.5)
        val runs = PlotterGpx.clip(edge, 0.0, 0.0, 4.0, 1.0)
        assertEquals(1, runs.size)
        assertEquals(listOf(0.0, 0.5, 4.0, 0.5), runs.single().toList())
        // Corner to corner across the box.
        val diagonal = PlotterGpx.clip(doubleArrayOf(-1.0, -1.0, 5.0, 2.0), 0.0, 0.0, 4.0, 1.0).single()
        assertEquals(listOf(1.0, 0.0, 3.0, 1.0), diagonal.toList().map { Math.round(it * 1e9) / 1e9 })
        // A ring round the whole screen crosses none of it; one passing beside it misses.
        val ring = doubleArrayOf(-1.0, -1.0, 5.0, -1.0, 5.0, 2.0, -1.0, 2.0, -1.0, -1.0)
        assertTrue(PlotterGpx.clip(ring, 0.0, 0.0, 4.0, 1.0).isEmpty())
        assertTrue(PlotterGpx.clip(doubleArrayOf(-1.0, 3.0, 5.0, 1.5), 0.0, 0.0, 4.0, 1.0).isEmpty())
        // Only touching a corner is nothing inside.
        assertTrue(PlotterGpx.clip(doubleArrayOf(-1.0, 1.0, 1.0, -1.0), 0.0, 0.0, 4.0, 1.0).isEmpty())
        // A square bigger than the screen, two of its sides crossing it: two runs.
        val square = doubleArrayOf(1.0, -1.0, 3.0, -1.0, 3.0, 2.0, 1.0, 2.0, 1.0, -1.0)
        val sides = PlotterGpx.clip(square, 0.0, 0.0, 4.0, 1.0)
        assertEquals(2, sides.size)
        assertEquals(listOf(3.0, 0.0, 3.0, 1.0), sides[0].toList().map { Math.round(it * 1e9) / 1e9 })
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0), sides[1].toList().map { Math.round(it * 1e9) / 1e9 })
    }

    @Test
    fun `the track cap is shared between kinds of line, so zone edges aren't crowded out`() {
        val contours = (0 until 300).map { k -> PlotterGpx.Line("20 m contour", line(5, lat = -27.0 - k * 0.001), group = "contours") }
        val zones = (0 until 20).map { k -> PlotterGpx.Line("No-take: Reef $k", line(5, lat = -28.0 - k * 0.001), group = "zones") }
        val built = PlotterGpx.build(emptyList(), contours + zones, PlotterGpx.Limits(maxTracks = 200))
        assertEquals(200, built.tracks)
        assertEquals(120, built.leftOut)
        val doc = parse(built.gpx)
        val names = (0 until doc.getElementsByTagName("trk").length).map {
            (doc.getElementsByTagName("trk").item(it) as Element).getElementsByTagName("name").item(0).textContent
        }
        assertEquals(20, names.count { it.startsWith("No-take") })
        assertEquals(180, names.count { it.startsWith("20 m contour") })
        // Still written in the order given: contours, then zones.
        assertTrue(names.indexOfFirst { it.startsWith("No-take") } > names.indexOfLast { it.startsWith("20 m contour") })
        // Within a kind the longest lines are kept first.
        val short = PlotterGpx.Line("Short", line(3, km = 0.1), group = "a")
        val long = PlotterGpx.Line("Long", line(3, km = 5.0), group = "a")
        val other = PlotterGpx.Line("Other", line(3), group = "b")
        assertEquals(listOf("Long", "Other", "Short"), PlotterGpx.byTurns(listOf(short, long, other)).map { it.name })
    }

    @Test
    fun `lines too short to be a track are left out`() {
        val built = PlotterGpx.build(emptyList(), listOf(PlotterGpx.Line("Dot", doubleArrayOf(153.0, -27.0))))
        assertEquals(0, built.tracks)
        assertFalse(built.gpx.contains("<trk>"))
    }
}
