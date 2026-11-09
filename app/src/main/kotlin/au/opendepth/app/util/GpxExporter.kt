package au.opendepth.app.util

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.model.Waypoint
import au.opendepth.app.track.TrackMath
import au.opendepth.app.track.TrackPoint
import java.math.BigDecimal
import java.time.Instant

/**
 * Builds a GPX 1.1 document from the in-app waypoint and route state.
 *
 * All user supplied text is XML-escaped, and waypoint names use the GPX 1.1
 * `<name>` element.
 */
object GpxExporter {

    fun build(
        waypoints: List<Waypoint>,
        routePoints: List<RoutePoint>,
        routeName: String = "Route"
    ): String {
        val gpx = header()

        waypoints.forEach { waypoint ->
            gpx.append("""  <wpt lat="${decimal(waypoint.lat)}" lon="${decimal(waypoint.lng)}">""")
            gpx.append("<name>${XmlEscape.text(waypoint.name)}</name>")
            waypoint.desc
                ?.takeIf { it.isNotBlank() }
                ?.let { gpx.append("<desc>${XmlEscape.text(it)}</desc>") }
            gpx.append("</wpt>")
            gpx.append('\n')
        }

        if (routePoints.size > 1) {
            gpx.append("  <rte>")
            gpx.append("<name>${XmlEscape.text(routeName)}</name>")
            routePoints.forEach { point ->
                val name = point.name?.takeIf { it.isNotBlank() }
                if (name == null) {
                    gpx.append("""<rtept lat="${decimal(point.lat)}" lon="${decimal(point.lng)}"/>""")
                } else {
                    gpx.append("""<rtept lat="${decimal(point.lat)}" lon="${decimal(point.lng)}"><name>${XmlEscape.text(name)}</name></rtept>""")
                }
            }
            gpx.append("</rte>")
            gpx.append('\n')
        }

        gpx.append("</gpx>")
        gpx.append('\n')
        return gpx.toString()
    }

    /**
     * A recorded track: one `<trkseg>` per stretch without a long GPS gap,
     * each point with its UTC time.
     */
    fun buildTrack(name: String, points: List<TrackPoint>): String {
        val gpx = header()
        gpx.append("  <trk><name>${XmlEscape.text(name)}</name>\n")
        TrackMath.segments(points).forEach { segment ->
            gpx.append("    <trkseg>\n")
            segment.forEach { p ->
                gpx.append("""      <trkpt lat="${coordinate(p.lat)}" lon="${coordinate(p.lng)}">""")
                if (p.timeMillis > 0) gpx.append("<time>${Instant.ofEpochMilli(p.timeMillis)}</time>")
                gpx.append("</trkpt>\n")
            }
            gpx.append("    </trkseg>\n")
        }
        gpx.append("  </trk>\n</gpx>\n")
        return gpx.toString()
    }

    private fun header() = StringBuilder(1024).apply {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append('\n')
        append(
            """<gpx version="1.1" creator="SeaBathy" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        )
        append('\n')
    }

    /**
     * Double.toString switches to scientific notation below 1e-3 (e.g.
     * "1.0E-4" for a point just off the equator), which xsd:decimal — and
     * so every GPX reader — rejects.
     */
    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** A recorded position to 7 decimals (about 1 cm): GPS fixes carry 17 meaningless digits. */
    private fun coordinate(value: Double): String =
        BigDecimal.valueOf(value).setScale(7, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
