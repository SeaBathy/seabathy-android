package au.opendepth.app.map

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.util.GeoMath
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/** Tracks the active measurement path and its on-map geometry. */
class MeasureController(private val mapHolder: MapHolder) {

    private val points = mutableListOf<RoutePoint>()

    fun add(lat: Double, lng: Double) {
        points += RoutePoint(lat, lng)
    }

    fun clear() {
        points.clear()
    }

    fun refresh() {
        mapHolder.style
            ?.getSourceAs<GeoJsonSource>(MapLayerIds.MEASURE_SOURCE)
            ?.setGeoJson(GeoJson.lineWithVertices(points))
    }

    /** The readout shown in the measurement card, or null when inactive. */
    fun summary(): String? = when {
        points.isEmpty() -> null
        points.size < 2 -> "Tap another point…"
        else -> {
            val nauticalMiles = GeoMath.totalNm(points.map { it.lat to it.lng })
            // The bearing of the last leg drawn: for a single measurement, the
            // bearing from the first point to the second.
            val bearing = GeoMath.bearingDegrees(points[points.size - 2], points.last())
            String.format(Locale.US, "%s  —  %03.0f°T", GeoMath.formatDistance(nauticalMiles), bearing)
        }
    }
}
