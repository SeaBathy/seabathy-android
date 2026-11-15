package au.opendepth.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KmlImporterTest {
    private val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Folder>
  <Placemark><name>Snapper reef</name><description><![CDATA[Good on a <b>rising</b> tide<br/>20 m]]></description>
    <Point><coordinates>151.3912,-33.8701,0</coordinates></Point></Placemark>
  <Placemark><name>Run out</name><LineString><coordinates>151.30,-33.80 151.35,-33.82
    151.40,-33.85</coordinates></LineString></Placemark>
  <Placemark><name>Area</name><Polygon><outerBoundaryIs><LinearRing><coordinates>1,1 2,2 3,3 1,1</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
  <Placemark><name>Bad</name><Point><coordinates>200,95</coordinates></Point></Placemark>
</Folder></Document></kml>"""

    @Test
    fun readsPointsAndLines() {
        val r = KmlImporter.parseAny(kml.toByteArray())
        assertEquals(1, r.waypoints.size)
        val w = r.waypoints[0]
        assertEquals("Snapper reef", w.name)
        assertEquals(-33.8701, w.lat, 1e-9)
        assertEquals(151.3912, w.lng, 1e-9)
        assertEquals("Good on a rising tide\n20 m", w.description)
        assertEquals(1, r.routes.size)
        assertEquals(3, r.routes[0].points.size)
        assertEquals(-33.85, r.routes[0].points[2].lat, 1e-9)
        assertTrue(r.tracks.isEmpty())
    }

    @Test
    fun readsKmz() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("files/icon.png")); z.write(byteArrayOf(1, 2)); z.closeEntry()
                z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(1, KmlImporter.parseAny(zip).waypoints.size)
    }

    @Test
    fun longLinesBecomeTracks() {
        val coords = (0..150).joinToString(" ") { "${150 + it * 0.001},-33" }
        val r = KmlImporter.parseAny("""<kml><Placemark><LineString><coordinates>$coords</coordinates></LineString></Placemark></kml>""".toByteArray())
        assertTrue(r.routes.isEmpty())
        assertEquals(151, r.tracks[0].points.size)
    }

    @Test
    fun stillReadsGpx() {
        val gpx = """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><wpt lat="-33" lon="151"><name>A</name></wpt></gpx>"""
        assertEquals("A", KmlImporter.parseAny(gpx.toByteArray()).waypoints[0].name)
    }

    @Test(expected = IOException::class)
    fun rejectsOtherFiles() {
        KmlImporter.parseAny("hello".toByteArray())
    }
}
