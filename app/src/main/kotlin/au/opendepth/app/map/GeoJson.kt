package au.opendepth.app.map

import au.opendepth.app.model.RoutePoint

/**
 * Minimal GeoJSON writers for the app's own geometry.
 *
 * These build the small, fixed-shape documents fed to MapLibre's GeoJsonSource.
 * Coordinates come from Double.toString, which is locale independent, so the
 * output is always valid JSON regardless of device locale.
 */
object GeoJson {

    fun featureCollection(features: List<String>): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    fun point(lat: Double, lng: Double, properties: String = "{}"): String =
        """{"type":"Feature","geometry":{"type":"Point",""" +
            """"coordinates":[$lng,$lat]},"properties":$properties}"""

    fun lineString(points: List<RoutePoint>, properties: String = "{}"): String {
        val coordinates = points.joinToString(",") { "[${it.lng},${it.lat}]" }
        return """{"type":"Feature","geometry":{"type":"LineString",""" +
            """"coordinates":[$coordinates]},"properties":$properties}"""
    }

    /** A line through [points] plus a point marker at each vertex. */
    fun lineWithVertices(points: List<RoutePoint>): String {
        val features = mutableListOf<String>()
        if (points.size >= 2) features += lineString(points)
        points.forEach { features += point(it.lat, it.lng) }
        return featureCollection(features)
    }

    /** Escapes [value] for a JSON string, for names the user typed (a quote would break the whole collection). */
    fun jsonEscape(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}
