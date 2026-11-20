package au.opendepth.app.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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

    fun install(style: Style, graticule: GraticuleController? = null, navMarks: NavMarksController? = null, zones: MarineZonesController? = null) {
        addAnchor(style)
        runCatching { zones?.addAreas(style) }
        navMarks?.addTo(style)
        runCatching { zones?.addPoints(style) }
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

    /** The accuracy circle's radius in pixels at a zoom whose scale is [scale] times zoom 0's. */
    private fun accuracyRadius(scale: Float): org.maplibre.android.style.expressions.Expression {
        return org.maplibre.android.style.expressions.Expression.max(
            org.maplibre.android.style.expressions.Expression.literal(22f),
            org.maplibre.android.style.expressions.Expression.product(org.maplibre.android.style.expressions.Expression.coalesce(org.maplibre.android.style.expressions.Expression.toNumber(org.maplibre.android.style.expressions.Expression.get("r0")), org.maplibre.android.style.expressions.Expression.literal(0f)), org.maplibre.android.style.expressions.Expression.literal(scale))
        )
    }

    private fun addGpsLayers(style: Style) {
        style.addSource(GeoJsonSource(MapLayerIds.GPS_SOURCE, MapLayerIds.EMPTY_GEOJSON))
        style.addLayer(
            CircleLayer(MapLayerIds.GPS_ACCURACY, MapLayerIds.GPS_SOURCE).withProperties(
                // As big as the position is uncertain (never smaller than the usual ring).
                PropertyFactory.circleRadius(
                    org.maplibre.android.style.expressions.Expression.interpolate(
                        org.maplibre.android.style.expressions.Expression.exponential(2f),
                        org.maplibre.android.style.expressions.Expression.zoom(),
                        org.maplibre.android.style.expressions.Expression.stop(0, accuracyRadius(1f)),
                        org.maplibre.android.style.expressions.Expression.stop(22, accuracyRadius(4194304f))
                    )
                ),
                PropertyFactory.circleColor(Color.parseColor("#1500C8B4")),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(ACCENT)
            )
        )
        // The course arrow goes on first, so the dot sits on top of it. Its image is
        // added to every style: a new style (a change of basemap) starts with none.
        runCatching {
            style.addImage(MapLayerIds.GPS_COURSE_IMAGE, courseArrow())
            style.addLayer(
                SymbolLayer(MapLayerIds.GPS_COURSE, MapLayerIds.GPS_SOURCE)
                    // Only a moving boat's marker has a course (see GpsMarker): no course, no arrow.
                    .withFilter(Expression.has(GpsMarker.COURSE))
                    .withProperties(
                        PropertyFactory.iconImage(MapLayerIds.GPS_COURSE_IMAGE),
                        PropertyFactory.iconRotate(Expression.get(GpsMarker.COURSE)),
                        // Turned with the chart, so it stays true when the chart is turned or course-up.
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
            )
        }
        style.addLayer(
            CircleLayer(MapLayerIds.GPS_DOT, MapLayerIds.GPS_SOURCE).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(ACCENT),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(WHITE)
            )
        )
    }

    /**
     * The arrow for the boat's course: a pointer just clear of the dot, in the
     * dot's own colour and white outline, pointing up (north) so that turning
     * it by the course points it along the course. The dot is at the middle
     * of the picture, which is what the picture turns about.
     */
    private fun courseArrow(): Bitmap = courseArrowImage ?: run {
        val density = Resources.getSystem().displayMetrics.density
        val size = (ARROW_BOX_DP * density).toInt().coerceAtLeast(2)
        val middle = size / 2f
        val shape = Path().apply {
            moveTo(middle, middle - ARROW_TIP_DP * density)
            lineTo(middle + ARROW_HALF_WIDTH_DP * density, middle - ARROW_BASE_DP * density)
            lineTo(middle, middle - ARROW_NOTCH_DP * density)
            lineTo(middle - ARROW_HALF_WIDTH_DP * density, middle - ARROW_BASE_DP * density)
            close()
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // The map sizes the picture by this: drawn for this screen, shown at the same size on it.
        bitmap.density = Resources.getSystem().displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
        // The outline first, the fill over its inner half: a 2 dp white edge like the dot's.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * ARROW_OUTLINE_DP * density
        paint.color = Color.parseColor(WHITE)
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(ACCENT)
        canvas.drawPath(shape, paint)
        bitmap.also { courseArrowImage = it }
    }

    /** Drawn once: every style is given the same picture. */
    private var courseArrowImage: Bitmap? = null

    // The dot is 8 dp across its radius with a 2 dp outline: the arrow starts just outside it.
    private const val ARROW_BOX_DP = 52f
    private const val ARROW_TIP_DP = 23f
    private const val ARROW_BASE_DP = 11.5f
    private const val ARROW_NOTCH_DP = 14f
    private const val ARROW_HALF_WIDTH_DP = 7f
    private const val ARROW_OUTLINE_DP = 2f

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
