package au.opendepth.app.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A latitude and longitude grid for what's on screen, as GeoJSON. Works out
 * lines and labels only; [GraticuleController] puts them on the map.
 */
object Graticule {

    /** Degrees between lines, coarse to fine: the round figures charts use, down to a tenth of a minute. */
    private val STEPS = doubleArrayOf(
        30.0, 20.0, 10.0, 5.0, 2.0, 1.0, 30 / 60.0, 20 / 60.0, 10 / 60.0, 5 / 60.0, 2 / 60.0, 1 / 60.0,
        0.5 / 60, 0.2 / 60, 0.1 / 60
    )
    private const val MAX_LINES_ACROSS = 6.0
    private const val MAX_LAT = 85.0
    /** Where the labels sit, as a share of what's on screen: under the top bar, and just in from the left. */
    private const val LABEL_INSET_TOP = 0.13
    private const val LABEL_INSET_LEFT = 0.02

    /** The spacing for an area [spanDegrees] across its longer side. */
    fun stepFor(spanDegrees: Double): Double = STEPS.firstOrNull { spanDegrees / it >= MAX_LINES_ACROSS - 2 && spanDegrees / it <= MAX_LINES_ACROSS + 2 }
        ?: STEPS.firstOrNull { spanDegrees / it >= MAX_LINES_ACROSS - 2 } ?: STEPS.last()

    /** "18°30′S", with the minutes only when the spacing needs them. */
    fun label(value: Double, isLatitude: Boolean, step: Double): String {
        val hemisphere = when {
            // The equator, the prime meridian and the date line belong to neither side.
            abs(value) < 1e-9 || (!isLatitude && abs(abs(value) - 180) < 1e-9) -> ""
            isLatitude -> if (value < 0) "S" else "N"
            else -> if (value < 0) "W" else "E"
        }
        var whole = floor(abs(value) + 1e-9).toInt()
        val fraction = (abs(value) - whole) * 60
        return when {
            step >= 1 -> "$whole°$hemisphere"
            step >= 1 / 60.0 - 1e-9 -> {
                var minutes = fraction.roundToInt()
                if (minutes == 60) {
                    minutes = 0
                    whole += 1
                }
                String.format(Locale.US, "%d°%02d′%s", whole, minutes, hemisphere)
            }
            else -> String.format(Locale.US, "%d°%04.1f′%s", whole, fraction, hemisphere)
        }
    }

    /**
     * Lines and labels for the area on screen. [east] may be below [west] when
     * the date line is on screen.
     */
    fun geoJson(south: Double, west: Double, north: Double, east: Double): String {
        val s = south.coerceIn(-MAX_LAT, MAX_LAT)
        val n = north.coerceIn(-MAX_LAT, MAX_LAT)
        val e = if (east < west) east + 360 else east
        val width = (e - west).coerceAtMost(360.0)
        val height = n - s
        if (height <= 0 || width <= 0) return MapLayerIds.EMPTY_GEOJSON
        val step = stepFor(max(height, width))
        val features = ArrayList<String>()
        fun line(a: Double, b: Double, c: Double, d: Double) {
            features += String.format(Locale.US, """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[%.6f,%.6f],[%.6f,%.6f]]},"properties":{}}""", a, b, c, d)
        }
        fun text(lng: Double, lat: Double, label: String) {
            features += String.format(Locale.US, """{"type":"Feature","geometry":{"type":"Point","coordinates":[%.6f,%.6f]},"properties":{"label":"%s"}}""", lng, lat, label)
        }
        val labelLat = n - height * LABEL_INSET_TOP
        val labelLng = west + width * LABEL_INSET_LEFT
        // Counted from zero, so lines land on round figures rather than building up rounding errors.
        for (i in ceil(west / step - 1e-9).toLong()..floor(e / step + 1e-9).toLong()) {
            val lng = i * step
            line(lng, s, lng, n)
            // On the map beyond the date line a longitude still reads as -180 to 180.
            val shown = ((lng + 540) % 360) - 180
            text(lng, labelLat, label(shown, isLatitude = false, step))
        }
        for (i in ceil(s / step - 1e-9).toLong()..floor(n / step + 1e-9).toLong()) {
            val lat = i * step
            line(west, lat, e, lat)
            text(labelLng, lat, label(lat, isLatitude = true, step))
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }
}

/** Shows or hides the grid, and redraws it when the map stops moving. Off by default. */
class GraticuleController(private val mapHolder: MapHolder) {

    var visible: Boolean = false
        private set

    /** Adds the source and layers to a new style, over the imported layers and under routes and waypoints. */
    fun addTo(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.GRID_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        val shown = if (visible) Property.VISIBLE else Property.NONE
        style.addLayer(
            LineLayer(MapLayerIds.GRID_LINE, MapLayerIds.GRID_SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
                .withProperties(
                    PropertyFactory.lineColor("#F4F6F8"),
                    PropertyFactory.lineOpacity(0.35f),
                    PropertyFactory.lineWidth(1f),
                    PropertyFactory.visibility(shown)
                )
        )
        style.addLayer(
            SymbolLayer(MapLayerIds.GRID_LABEL, MapLayerIds.GRID_SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor("#F4F6F8"),
                    PropertyFactory.textHaloColor("#0B1622"),
                    PropertyFactory.textHaloWidth(1.2f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                    PropertyFactory.textOffset(arrayOf(0.3f, 0f)),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.visibility(shown)
                )
        )
        refresh()
    }

    fun setVisible(value: Boolean) {
        visible = value
        val style = mapHolder.style ?: return
        val shown = if (value) Property.VISIBLE else Property.NONE
        runCatching {
            style.getLayer(MapLayerIds.GRID_LINE)?.setProperties(PropertyFactory.visibility(shown))
            style.getLayer(MapLayerIds.GRID_LABEL)?.setProperties(PropertyFactory.visibility(shown))
        }
        refresh()
    }

    /** Called when the map has stopped moving. */
    fun refresh() {
        if (!visible) return
        val map = mapHolder.map ?: return
        val style = mapHolder.style ?: return
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapLayerIds.GRID_SOURCE)?.setGeoJson(
                Graticule.geoJson(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
            )
        }
    }
}
