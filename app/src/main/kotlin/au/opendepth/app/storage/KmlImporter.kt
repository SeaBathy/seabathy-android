package au.opendepth.app.storage

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.track.TrackPoint
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads Google Earth files (KML, and KMZ: a zip with a KML inside) into
 * the same waypoints, routes and tracks as a GPX import. Each point becomes
 * a waypoint; a line becomes a route, or a track when it has more points
 * than a route is planned with. Shapes (areas) are skipped. Pure JVM XML,
 * unit-tested.
 */
object KmlImporter {

    /** Lines longer than this are recorded paths, not planned routes. */
    const val MAX_ROUTE_POINTS = 100
    /** A KML inside a KMZ is read up to this size. */
    private const val MAX_KML_BYTES = 50L * 1024 * 1024

    /** Reads GPX, KML or KMZ, telling them apart by their content, not the file name. */
    fun parseAny(bytes: ByteArray): GpxImporter.Result {
        if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return parse(kmlFromKmz(bytes))
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        return when {
            head.contains("<kml") -> parse(bytes)
            head.contains("<gpx") -> GpxImporter.parse(ByteArrayInputStream(bytes))
            else -> throw IOException("This file isn't GPX, KML or KMZ")
        }
    }

    fun parse(bytes: ByteArray): GpxImporter.Result {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs or external entities: the file comes from anywhere.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            isExpandEntityReferences = false
        }
        val document = runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }
            .getOrElse { throw IOException("This file isn't valid KML (${it.message})") }
        val root = document.documentElement
        if (root == null || root.local() != "kml") throw IOException("This file isn't KML")

        val waypoints = mutableListOf<GpxImporter.ImportedWaypoint>()
        val routes = mutableListOf<GpxImporter.ImportedRoute>()
        val tracks = mutableListOf<GpxImporter.ImportedTrack>()
        for (placemark in root.descendants("Placemark")) {
            val name = placemark.childText("name")
            val description = placemark.childText("description")?.let(::plainText)
            for (point in placemark.descendants("Point")) {
                val (lng, lat) = coordinates(point.childText("coordinates")).firstOrNull() ?: continue
                waypoints += GpxImporter.ImportedWaypoint(name, description, lat, lng)
            }
            for (line in placemark.descendants("LineString")) {
                val coords = coordinates(line.childText("coordinates"))
                if (coords.size < 2) continue
                if (coords.size <= MAX_ROUTE_POINTS) {
                    routes += GpxImporter.ImportedRoute(name, coords.map { (lng, lat) -> RoutePoint(lat, lng) })
                } else {
                    tracks += GpxImporter.ImportedTrack(name, coords.map { (lng, lat) -> TrackPoint(0L, lat, lng) })
                }
            }
        }
        return GpxImporter.Result(waypoints, routes, tracks)
    }

    /** The first .kml in a KMZ (usually doc.kml). */
    private fun kmlFromKmz(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.lowercase().endsWith(".kml")) continue
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_KML_BYTES) throw IOException("The KML inside this KMZ is too big")
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        }
        throw IOException("This KMZ has no KML inside")
    }

    /** "lng,lat[,alt] lng,lat…" → (lng, lat) pairs, skipping anything unreadable or off the globe. */
    internal fun coordinates(text: String?): List<Pair<Double, Double>> =
        text.orEmpty().trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(',')
            val lng = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else lng to lat
        }

    /** Descriptions are often HTML: keep the words. */
    private fun plainText(html: String): String? =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .trim().takeIf { it.isNotEmpty() }

    private fun org.w3c.dom.Node.local(): String = (localName ?: nodeName).substringAfter(':')

    private fun Element.descendants(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val all = getElementsByTagNameNS("*", name)
        for (i in 0 until all.length) (all.item(i) as? Element)?.let { out += it }
        if (out.isEmpty()) {
            val plain = getElementsByTagName(name)
            for (i in 0 until plain.length) (plain.item(i) as? Element)?.let { out += it }
        }
        return out
    }

    private fun Element.childText(name: String): String? {
        var node = firstChild
        while (node != null) {
            if (node is Element && node.local() == name) return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
            node = node.nextSibling
        }
        return null
    }
}
