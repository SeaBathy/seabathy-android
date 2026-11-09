package au.opendepth.app.map

/**
 * Every MapLibre source and layer id used by the app.
 *
 * Overlay ids are derived from the persisted layer id, so a layer keeps the
 * same map objects across style rebuilds.
 */
object MapLayerIds {

    /**
     * An invisible layer that every overlay is inserted below. It gives the
     * stack a stable insertion point, which keeps waypoints, routes and
     * measurements above imported data no matter what is loaded later.
     */
    const val OVERLAY_ANCHOR_SOURCE = "overlay-anchor-src"
    const val OVERLAY_ANCHOR = "overlay-anchor"

    const val GRID_SOURCE = "grid-src"
    const val GRID_LINE = "grid-line"
    const val GRID_LABEL = "grid-label"

    const val ROUTE_SOURCE = "route-src"
    const val ROUTE_CASING = "route-casing"
    const val ROUTE_LINE = "route-line"
    const val ROUTE_POINTS = "route-pts"

    const val MEASURE_SOURCE = "meas-src"
    const val MEASURE_CASING = "meas-casing"
    const val MEASURE_LINE = "meas-line"
    const val MEASURE_POINTS = "meas-pts"

    const val NAV_SOURCE = "nav-src"
    const val NAV_LEG = "nav-leg"
    const val NAV_TO_TARGET = "nav-to-target"
    const val NAV_TARGET = "nav-target"

    const val TRACK_SOURCE = "track-src"
    const val TRACK_CASING = "track-casing"
    const val TRACK_LIVE = "track-live"
    const val TRACK_SAVED = "track-saved"

    const val ANCHOR_SOURCE = "anchor-src"
    const val ANCHOR_FILL = "anchor-fill"
    const val ANCHOR_RING = "anchor-ring"
    const val ANCHOR_POINT = "anchor-pt"

    const val GPS_SOURCE = "gps-src"
    const val GPS_ACCURACY = "gps-acc"
    const val GPS_DOT = "gps-dot"

    const val WAYPOINT_SOURCE = "waypoint-overlay-source"
    const val WAYPOINT_LAYER = "waypoint-overlay-layer"
    const val WAYPOINT_ICON_LAYER = "waypoint-overlay-icon-layer"
    const val WAYPOINT_LABEL_LAYER = "waypoint-overlay-label-layer"

    fun sourceFor(layerId: String): String = "$layerId-src"

    fun layerFor(layerId: String): String = "$layerId-layer"

    const val EMPTY_GEOJSON = """{"type":"FeatureCollection","features":[]}"""
}
