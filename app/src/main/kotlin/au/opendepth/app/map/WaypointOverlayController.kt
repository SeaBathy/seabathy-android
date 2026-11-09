package au.opendepth.app.map

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.core.content.ContextCompat
import au.opendepth.app.model.Waypoint
import au.opendepth.app.model.WaypointIcon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Draws the waypoints: a coloured dot, a white icon on it and an optional
 * name label, as three layers on one GeoJSON source, so names can be turned
 * off on their own. The layers sit above every overlay. Style errors are
 * caught here so they can't break the rest of the map.
 */
class WaypointOverlayController(
    private val context: Context,
    private val mapHolder: MapHolder,
    private val waypoints: List<Waypoint>
) {
    /** Whether name labels are currently shown; see [setNamesVisible]. */
    var namesVisible: Boolean = false
        private set

    /** The waypoint under a tapped screen point, if any. */
    fun waypointIdAt(latLng: LatLng): String? {
        val map = mapHolder.map ?: return null
        return runCatching {
            val point = map.projection.toScreenLocation(latLng)
            val hitBox = RectF(
                point.x - HIT_RADIUS_PX,
                point.y - HIT_RADIUS_PX,
                point.x + HIT_RADIUS_PX,
                point.y + HIT_RADIUS_PX
            )
            map.queryRenderedFeatures(hitBox, MapLayerIds.WAYPOINT_LAYER)
                .firstOrNull()
                ?.getStringProperty(PROPERTY_ID)
        }.onFailure { Log.w(TAG, "Waypoint hit-test failed", it) }.getOrNull()
    }

    /** Adds the style source and layers to a freshly created style. */
    fun install() {
        val style = mapHolder.style ?: return
        runCatching {
            val geoJson = buildGeoJson()
            val source = style.getSourceAs<GeoJsonSource>(MapLayerIds.WAYPOINT_SOURCE)
            if (source == null) {
                style.addSource(GeoJsonSource(MapLayerIds.WAYPOINT_SOURCE, geoJson))
            } else {
                source.setGeoJson(geoJson)
            }

            // Added every time: MapLibre drops custom images with the old style.
            registerIcons(style)

            if (style.getLayer(MapLayerIds.WAYPOINT_LAYER) == null) {
                style.addLayer(
                    CircleLayer(MapLayerIds.WAYPOINT_LAYER, MapLayerIds.WAYPOINT_SOURCE)
                        .withProperties(
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleColor(Expression.get(PROPERTY_COLOR)),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor(WHITE)
                        )
                )
            }

            if (style.getLayer(MapLayerIds.WAYPOINT_ICON_LAYER) == null) {
                style.addLayer(
                    SymbolLayer(MapLayerIds.WAYPOINT_ICON_LAYER, MapLayerIds.WAYPOINT_SOURCE)
                        .withProperties(
                            PropertyFactory.iconImage(Expression.get(PROPERTY_ICON)),
                            PropertyFactory.iconSize(ICON_SIZE),
                            // Every waypoint's icon should always draw, same
                            // as the circle underneath it — unlike text
                            // labels, marker glyphs are not expected to
                            // collision-avoid each other.
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true)
                        )
                )
            }

            if (style.getLayer(MapLayerIds.WAYPOINT_LABEL_LAYER) == null) {
                style.addLayer(
                    SymbolLayer(MapLayerIds.WAYPOINT_LABEL_LAYER, MapLayerIds.WAYPOINT_SOURCE)
                        .withProperties(
                            PropertyFactory.textField(Expression.get(PROPERTY_NAME)),
                            PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
                            PropertyFactory.textSize(labelSizeExpression()),
                            PropertyFactory.textOpacity(labelOpacityExpression()),
                            PropertyFactory.textColor(WHITE),
                            PropertyFactory.textHaloColor(BLACK),
                            PropertyFactory.textHaloWidth(1.2f),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                            PropertyFactory.textOffset(arrayOf(0f, LABEL_OFFSET_EM)),
                            PropertyFactory.textJustify(Property.TEXT_JUSTIFY_CENTER),
                            // Labels avoid each other (the icons don't), so a cluster of waypoints
                            // doesn't show a pile of overlapping names.
                            PropertyFactory.textAllowOverlap(false),
                            PropertyFactory.visibility(
                                if (namesVisible) Property.VISIBLE else Property.NONE
                            )
                        )
                )
            }
        }.onFailure { Log.e(TAG, "Waypoint layer install failed", it) }
    }

    /** Re-syncs the source with the current waypoint list. */
    fun render() {
        runCatching {
            mapHolder.style
                ?.getSourceAs<GeoJsonSource>(MapLayerIds.WAYPOINT_SOURCE)
                ?.setGeoJson(buildGeoJson())
        }.onFailure { Log.e(TAG, "Waypoint render failed", it) }
    }

    /** Live-toggles the name-label layer, from the Settings "Show waypoint names on map" switch. */
    fun setNamesVisible(visible: Boolean) {
        namesVisible = visible
        runCatching {
            mapHolder.style
                ?.getLayerAs<SymbolLayer>(MapLayerIds.WAYPOINT_LABEL_LAYER)
                ?.setProperties(
                    PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
                )
        }.onFailure { Log.e(TAG, "Waypoint label visibility toggle failed", it) }
    }

    private fun registerIcons(style: Style) {
        WaypointIcon.ALL.forEach { icon ->
            ContextCompat.getDrawable(context, icon.drawableRes)?.let { drawable ->
                style.addImage(icon.id, drawable)
            }
        }
    }

    private fun buildGeoJson(): String = GeoJson.featureCollection(
        waypoints.map { waypoint ->
            GeoJson.point(
                lat = waypoint.lat,
                lng = waypoint.lng,
                properties = """{"$PROPERTY_ID":"${waypoint.id}",""" +
                    """"$PROPERTY_COLOR":"${waypoint.color}",""" +
                    """"$PROPERTY_ICON":"${WaypointIcon.byId(waypoint.icon).id}",""" +
                    """"$PROPERTY_NAME":"${GeoJson.jsonEscape(waypoint.name)}"}"""
            )
        }
    )

    /**
     * Names are hidden up to [LABEL_MIN_ZOOM], fade in over the next zoom, and
     * grow from [LABEL_MIN_SIZE_SP] to [LABEL_MAX_SIZE_SP] at [LABEL_MAX_ZOOM].
     */
    private fun labelSizeExpression(): Expression = Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(LABEL_MIN_ZOOM, LABEL_MIN_SIZE_SP),
        Expression.stop(LABEL_MID_ZOOM, LABEL_MID_SIZE_SP),
        Expression.stop(LABEL_MAX_ZOOM, LABEL_MAX_SIZE_SP)
    )

    /** Fades in from fully invisible at [LABEL_MIN_ZOOM] to fully opaque one zoom level later. */
    private fun labelOpacityExpression(): Expression = Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(LABEL_MIN_ZOOM, 0f),
        Expression.stop(LABEL_MIN_ZOOM + LABEL_FADE_IN_SPAN, 1f)
    )

    private companion object {
        const val TAG = "SeaBathyWaypoints"
        const val PROPERTY_ID = "id"
        const val PROPERTY_COLOR = "color"
        const val PROPERTY_ICON = "icon"
        const val PROPERTY_NAME = "name"
        const val WHITE = "#FFFFFF"
        const val BLACK = "#000000"
        const val HIT_RADIUS_PX = 24f

        /** Multiplier against each icon drawable's own intrinsic (density-scaled) size. */
        const val ICON_SIZE = 0.6f

        // Zoom-stop tuning for name labels — see labelSizeExpression()/
        // labelOpacityExpression()'s own doc for how these combine.
        const val LABEL_MIN_ZOOM = 11.0
        const val LABEL_MID_ZOOM = 13.0
        const val LABEL_MAX_ZOOM = 18.0
        const val LABEL_FADE_IN_SPAN = 1.0
        const val LABEL_MIN_SIZE_SP = 10f
        const val LABEL_MID_SIZE_SP = 12f
        const val LABEL_MAX_SIZE_SP = 16f
        const val LABEL_OFFSET_EM = 1.4f
    }
}
