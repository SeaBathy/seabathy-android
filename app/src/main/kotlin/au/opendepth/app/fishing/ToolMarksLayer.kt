package au.opendepth.app.fishing

import android.graphics.RectF
import android.util.Log
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MapLayerIds
import au.opendepth.app.map.MapStyleFactory
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * A fishing tool's marks on the chart (the structure finder's, the drift
 * planner's): lines, dots and short labels from one GeoJSON source, just
 * under the waypoints. Features carry "colour" and "label"; lines may carry
 * "dashed". What was last shown is put back after a style rebuild
 * ([install], from the activity's style-installed hook). Style errors are
 * caught here so they can't break the rest of the map.
 */
class ToolMarksLayer(private val mapHolder: MapHolder, private val id: String) {

    private val sourceId = "$id-src"
    private val lineId = "$id-line"
    private val dashId = "$id-dash"
    private val dotId = "$id-dot"
    private val labelId = "$id-label"

    private var geoJson: String = MapLayerIds.EMPTY_GEOJSON

    val isEmpty: Boolean get() = geoJson == MapLayerIds.EMPTY_GEOJSON

    /** Adds the source and layers to a new style, with what was last shown. */
    fun install() {
        val style = mapHolder.style ?: return
        runCatching {
            val source = style.getSourceAs<GeoJsonSource>(sourceId)
            if (source == null) style.addSource(GeoJsonSource(sourceId, geoJson)) else source.setGeoJson(geoJson)
            val isLine = Expression.eq(Expression.geometryType(), Expression.literal("LineString"))
            val isPoint = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
            val dashed = Expression.toBool(Expression.coalesce(Expression.get("dashed"), Expression.literal(false)))
            add(style, LineLayer(lineId, sourceId).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("colour"))),
                PropertyFactory.lineWidth(2.2f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            ).withFilter(Expression.all(isLine, Expression.not(dashed))))
            add(style, LineLayer(dashId, sourceId).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("colour"))),
                PropertyFactory.lineWidth(2.2f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))
            ).withFilter(Expression.all(isLine, dashed)))
            add(style, CircleLayer(dotId, sourceId).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("colour"))),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            ).withFilter(isPoint))
            add(style, SymbolLayer(labelId, sourceId).withProperties(
                PropertyFactory.textField(Expression.coalesce(Expression.get("label"), Expression.literal(""))),
                PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#0B1220"),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                PropertyFactory.textOffset(arrayOf(0.9f, 0f)),
                PropertyFactory.textAllowOverlap(false)
            ).withFilter(isPoint))
        }.onFailure { Log.e(TAG, "tool marks install FAILED ($id)", it) }
    }

    /** Shows [collection] (a GeoJSON FeatureCollection) in place of what was shown. */
    fun show(collection: String) {
        geoJson = collection
        runCatching { mapHolder.style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(collection) }
            .onFailure { Log.e(TAG, "tool marks show FAILED ($id)", it) }
    }

    fun clear() = show(MapLayerIds.EMPTY_GEOJSON)

    /** The "i" numbers of the dots (and their labels) drawn inside [box], a part of the screen. */
    fun indicesIn(box: RectF): List<Int> {
        if (isEmpty) return emptyList()
        val map = mapHolder.map ?: return emptyList()
        return runCatching {
            map.queryRenderedFeatures(box, dotId, labelId).mapNotNull { feature ->
                if (feature.hasProperty("i")) feature.getNumberProperty("i")?.toInt() else null
            }.distinct()
        }.onFailure { Log.w(TAG, "tool marks hit test FAILED ($id)", it) }.getOrDefault(emptyList())
    }

    /** Under the waypoints, which stay on top; above everything else. */
    private fun add(style: Style, layer: Layer) {
        if (style.getLayer(layer.id) != null) return
        if (style.getLayer(MapLayerIds.WAYPOINT_LAYER) != null) style.addLayerBelow(layer, MapLayerIds.WAYPOINT_LAYER) else style.addLayer(layer)
    }

    companion object {
        private const val TAG = "SeaBathyToolMarks"

        /** A point feature for [show]; [index] (the mark's place in its list) is what [indicesIn] finds. */
        fun point(lat: Double, lng: Double, colour: String, label: String, index: Int? = null): String {
            val number = if (index != null) ""","i":$index""" else ""
            return """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},""" +
                """"properties":{"colour":"$colour","label":"${au.opendepth.app.map.GeoJson.jsonEscape(label)}"$number}}"""
        }

        /** A line feature through (lat, lng) points, for [show]. */
        fun line(points: List<Pair<Double, Double>>, colour: String, dashed: Boolean = false): String =
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[""" +
                points.joinToString(",") { (lat, lng) -> "[$lng,$lat]" } +
                """]},"properties":{"colour":"$colour","dashed":$dashed}}"""
    }
}
