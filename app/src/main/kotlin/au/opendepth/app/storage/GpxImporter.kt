package au.opendepth.app.storage

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.track.TrackPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads waypoints, routes and tracks from a GPX 1.0/1.1 file (from a
 * chartplotter, OpenCPN, Navionics…). Pure JVM XML, unit-tested.
 */
object GpxImporter {

    data class ImportedWaypoint(val name: String?, val description: String?, val lat: Double, val lng: Double)
    data class ImportedRoute(val name: String?, val points: List<RoutePoint>)
    data class ImportedTrack(val name: String?, val points: List<TrackPoint>)
    data class Result(val waypoints: List<ImportedWaypoint>, val routes: List<ImportedRoute>, val tracks: List<ImportedTrack>)

    fun parse(input: InputStream): Result {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs or external entities: a GPX file comes from anywhere.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            isExpandEntityReferences = false
        }
        val document = runCatching { factory.newDocumentBuilder().parse(input) }
            .getOrElse { throw IOException("This file isn't valid GPX (${it.message})") }
        val root = document.documentElement
        if (root == null || root.localNameOrTag() != "gpx") throw IOException("This file isn't GPX")

        val waypoints = root.childElements("wpt").mapNotNull { wpt ->
            val (lat, lng) = wpt.latLng() ?: return@mapNotNull null
            ImportedWaypoint(wpt.childText("name"), wpt.childText("desc") ?: wpt.childText("cmt"), lat, lng)
        }
        val routes = root.childElements("rte").mapNotNull { rte ->
            val points = rte.childElements("rtept").mapNotNull { pt ->
                val (lat, lng) = pt.latLng() ?: return@mapNotNull null
                RoutePoint(lat, lng, pt.childText("name"))
            }
            if (points.size < 2) null else ImportedRoute(rte.childText("name"), points)
        }
        val tracks = root.childElements("trk").mapNotNull { trk ->
            val points = trk.childElements("trkseg").flatMap { seg ->
                seg.childElements("trkpt").mapNotNull { pt ->
                    val (lat, lng) = pt.latLng() ?: return@mapNotNull null
                    TrackPoint(pt.childText("time")?.let(::parseTime) ?: 0L, lat, lng)
                }
            }
            if (points.size < 2) null else ImportedTrack(trk.childText("name"), points)
        }
        return Result(waypoints, routes, tracks)
    }

    /** GPX times are ISO 8601 UTC ("2024-06-21T03:15:00Z", sometimes with an offset or fractions). */
    private fun parseTime(text: String): Long? =
        runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()

    private fun Element.latLng(): Pair<Double, Double>? {
        val lat = getAttribute("lat").toDoubleOrNull() ?: return null
        val lng = getAttribute("lon").toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun Node.localNameOrTag(): String = (localName ?: nodeName).substringAfter(':')

    private fun Element.childElements(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.localNameOrTag() == name) out += node
        }
        return out
    }

    private fun Element.childText(name: String): String? =
        childElements(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
