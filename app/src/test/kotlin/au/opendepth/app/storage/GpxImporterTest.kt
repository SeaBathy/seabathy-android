package au.opendepth.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class GpxImporterTest {

    private fun parse(xml: String) = GpxImporter.parse(xml.byteInputStream())

    @Test
    fun `reads waypoints, routes and tracks`() {
        val result = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="-18.65" lon="147.17"><name>Reef</name><desc>Good spot</desc></wpt>
              <wpt lat="-18.70" lon="147.20"/>
              <rte><name>Home run</name>
                <rtept lat="-18.6" lon="147.1"><name>A</name></rtept>
                <rtept lat="-18.7" lon="147.2"/>
              </rte>
              <rte><rtept lat="1" lon="1"/></rte>
              <trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk>
              <trk><name>Sunday sail</name>
                <trkseg><trkpt lat="-18.6" lon="147.1"><time>2024-06-21T03:15:00Z</time></trkpt>
                  <trkpt lat="-18.61" lon="147.1"><time>2024-06-21T03:20:00.500+00:00</time></trkpt></trkseg>
                <trkseg><trkpt lat="-18.62" lon="147.1"/></trkseg>
              </trk>
            </gpx>
            """.trimIndent()
        )
        assertEquals(2, result.waypoints.size)
        assertEquals("Reef", result.waypoints[0].name)
        assertEquals("Good spot", result.waypoints[0].description)
        assertNull(result.waypoints[1].name)
        // The one-point route is dropped.
        assertEquals(1, result.routes.size)
        assertEquals("Home run", result.routes[0].name)
        assertEquals("A", result.routes[0].points[0].name)
        assertEquals(147.2, result.routes[0].points[1].lng, 1e-9)
        // The one-point track is dropped; segments are joined, times read when present.
        val track = result.tracks.single()
        assertEquals("Sunday sail", track.name)
        assertEquals(3, track.points.size)
        assertEquals(1718939700000L, track.points[0].timeMillis)
        assertEquals(1718940000500L, track.points[1].timeMillis)
        assertEquals(0L, track.points[2].timeMillis)
    }

    @Test
    fun `GPX 1_0 without a namespace works too`() {
        val result = parse("""<gpx version="1.0"><wpt lat="10" lon="20"><name>X</name></wpt></gpx>""")
        assertEquals("X", result.waypoints.single().name)
    }

    @Test
    fun `out of range coordinates are skipped`() {
        val result = parse("""<gpx><wpt lat="95" lon="20"/><wpt lat="10" lon="200"/><wpt lat="1" lon="2"/></gpx>""")
        assertEquals(1, result.waypoints.size)
    }

    @Test(expected = IOException::class)
    fun `not GPX is rejected`() {
        parse("""<kml><Placemark/></kml>""")
    }

    @Test(expected = IOException::class)
    fun `external entities are refused`() {
        parse(
            """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <gpx><wpt lat="1" lon="1"><name>&x;</name></wpt></gpx>"""
        )
    }
}
