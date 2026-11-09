package au.opendepth.app.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Installs the fixed sources and layers that every style needs: the overlay
 * insertion anchor plus the route, measurement and GPS renderers.
 */
object BaseMapLayers {

    private const val ACCENT = "#00C8B4"
    private const val MEASURE = "#F59E0B"
    private const val NAV = "#E040FB"
    private const val WHITE = "#FFFFFF"
    private const val TRACK = "#F43F5E"

    // A dark outline under the route and measure lines: without it a teal
    // route vanishes over teal depth colours (and amber over amber).
    private const val CASING = "#0B1622"

    fun install(style: Style, graticule: GraticuleController? = null) {
        addAnchor(style)
        graticule?.addTo(style)
        addTrackLayers(style)
        addRouteLayers(style)
        addMeasureLayers(style)
        addNavLayers(style)
        addAnchorWatchLayers(style)
        addGpsLayers(style)
    }

    /** Recorded tracks: the one being recorded (solid) and a saved one being shown (dashed), under the route. */
    private fun addTrackLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.TRACK_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(
            LineLayer(MapLayerIds.TRACK_CASING, MapLayerIds.TRACK_SOURCE).withProperties(
                PropertyFactory.lineColor(CASING),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            )
        )
        style.addLayer(
            LineLayer(MapLayerIds.TRACK_LIVE, MapLayerIds.TRACK_SOURCE)
                .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("live")))
                .withProperties(
                    PropertyFactory.lineColor(TRACK),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                )
        )
        style.addLayer(
            LineLayer(MapLayerIds.TRACK_SAVED, MapLayerIds.TRACK_SOURCE)
                .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("saved")))
                .withProperties(
                    PropertyFactory.lineColor(TRACK),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
        )
    }

    /** The anchor alarm's swing circle and the anchor itself. */
    private fun addAnchorWatchLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.ANCHOR_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(
            FillLayer(MapLayerIds.ANCHOR_FILL, MapLayerIds.ANCHOR_SOURCE)
                .withFilter(isGeometry("Polygon"))
                .withProperties(PropertyFactory.fillColor(MEASURE), PropertyFactory.fillOpacity(0.12f))
        )
        style.addLayer(
            LineLayer(MapLayerIds.ANCHOR_RING, MapLayerIds.ANCHOR_SOURCE)
                .withFilter(isGeometry("Polygon"))
                .withProperties(
                    PropertyFactory.lineColor(MEASURE),
                    PropertyFactory.lineWidth(2.5f),
                    PropertyFactory.lineDasharray(arrayOf(3f, 2f))
                )
        )
        style.addLayer(
            CircleLayer(MapLayerIds.ANCHOR_POINT, MapLayerIds.ANCHOR_SOURCE)
                .withFilter(isGeometry("Point"))
                .withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(MEASURE),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(CASING)
                )
        )
    }

    private fun addAnchor(style: Style) {
        style.addSource(
            GeoJsonSource(MapLayerIds.OVERLAY_ANCHOR_SOURCE, MapLayerIds.EMPTY_GEOJSON)
        )
        style.addLayer(
            CircleLayer(MapLayerIds.OVERLAY_ANCHOR, MapLayerIds.OVERLAY_ANCHOR_SOURCE)
                .withProperties(
                    PropertyFactory.circleRadius(0f),
                    PropertyFactory.circleOpacity(0f)
                )
        )
    }

    private fun addRouteLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.ROUTE_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(casing(MapLayerIds.ROUTE_CASING, MapLayerIds.ROUTE_SOURCE, width = 7f))
        style.addLayer(
            LineLayer(MapLayerIds.ROUTE_LINE, MapLayerIds.ROUTE_SOURCE)
                .withFilter(isGeometry("LineString"))
                .withProperties(
                    PropertyFactory.lineColor(ACCENT),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1f))
                )
        )
        style.addLayer(
            CircleLayer(MapLayerIds.ROUTE_POINTS, MapLayerIds.ROUTE_SOURCE)
                .withFilter(isGeometry("Point"))
                .withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(ACCENT),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(WHITE)
                )
        )
    }

    private fun addMeasureLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.MEASURE_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(casing(MapLayerIds.MEASURE_CASING, MapLayerIds.MEASURE_SOURCE, width = 6f))
        style.addLayer(
            LineLayer(MapLayerIds.MEASURE_LINE, MapLayerIds.MEASURE_SOURCE)
                .withFilter(isGeometry("LineString"))
                .withProperties(
                    PropertyFactory.lineColor(MEASURE),
                    PropertyFactory.lineWidth(2f)
                )
        )
        style.addLayer(
            CircleLayer(MapLayerIds.MEASURE_POINTS, MapLayerIds.MEASURE_SOURCE)
                .withFilter(isGeometry("Point"))
                .withProperties(
                    PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleColor(MEASURE),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleStrokeColor(CASING)
                )
        )
    }

    /**
     * Active navigation, in the magenta plotters use: the current leg solid,
     * the line from the boat to the next point dashed, and a ring on it.
     */
    private fun addNavLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.NAV_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(
            LineLayer(MapLayerIds.NAV_LEG, MapLayerIds.NAV_SOURCE)
                .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("leg")))
                .withProperties(
                    PropertyFactory.lineColor(NAV),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                )
        )
        style.addLayer(
            LineLayer(MapLayerIds.NAV_TO_TARGET, MapLayerIds.NAV_SOURCE)
                .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("toTarget")))
                .withProperties(
                    PropertyFactory.lineColor(NAV),
                    PropertyFactory.lineWidth(2.5f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 2f))
                )
        )
        style.addLayer(
            CircleLayer(MapLayerIds.NAV_TARGET, MapLayerIds.NAV_SOURCE)
                .withFilter(isGeometry("Point"))
                .withProperties(
                    PropertyFactory.circleRadius(14f),
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(NAV)
                )
        )
    }

    private fun addGpsLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.GPS_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(
            CircleLayer(MapLayerIds.GPS_ACCURACY, MapLayerIds.GPS_SOURCE).withProperties(
                PropertyFactory.circleRadius(22f),
                PropertyFactory.circleColor(Color.parseColor("#1500C8B4")),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(ACCENT)
            )
        )
        style.addLayer(
            CircleLayer(MapLayerIds.GPS_DOT, MapLayerIds.GPS_SOURCE).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(ACCENT),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(WHITE)
            )
        )
    }

    private fun casing(id: String, source: String, width: Float): LineLayer =
        LineLayer(id, source)
            .withFilter(isGeometry("LineString"))
            .withProperties(
                PropertyFactory.lineColor(CASING),
                PropertyFactory.lineWidth(width),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )

    private fun isGeometry(type: String): Expression =
        Expression.eq(Expression.geometryType(), Expression.literal(type))
}
