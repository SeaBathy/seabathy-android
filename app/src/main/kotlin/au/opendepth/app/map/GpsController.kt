package au.opendepth.app.map

import android.content.Context
import android.location.Location
import android.os.SystemClock
import au.opendepth.app.location.LocationController
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The GPS button (off, locate, follow) and the position marker. Locate
 * centres once; follow keeps the boat on screen.
 *
 * [onLocationChanged] gets every fix while the GPS is on, whatever the mode:
 * this is the app's only location listener.
 */
class GpsController(
    context: Context,
    private val mapHolder: MapHolder,
    private val onLocationChanged: (Location) -> Unit = {},
    /** Chart bearing to use when following (course up), or null to keep the current one. */
    private val followBearing: (Location) -> Float? = { null }
) {
    enum class Mode { OFF, LOCATE, FOLLOW }

    private val locationController = LocationController(context, ::handleLocation)
    private var centredOnce = false
    private var lastLocation: Location? = null

    var mode: Mode = Mode.OFF
        private set

    val isActive: Boolean get() = mode != Mode.OFF

    /** The latest fix, if any. */
    val last: Location? get() = lastLocation

    /** Advances to the next mode and reports the new one. */
    fun cycleMode(): Mode {
        mode = when (mode) {
            Mode.OFF -> Mode.LOCATE
            Mode.LOCATE -> Mode.FOLLOW
            Mode.FOLLOW -> Mode.OFF
        }
        centredOnce = false

        // Locking onto follow should snap to the last known fix straight away
        // rather than waiting for the next GPS callback, which may be seconds
        // away (or may never arrive at all if the vessel is stationary).
        if (mode == Mode.FOLLOW) {
            lastLocation?.let { recentreOn(it) }
        }
        return mode
    }

    /** Straight to follow mode (navigation keeps the boat on screen). */
    fun followMode() {
        mode = Mode.FOLLOW
        centredOnce = false
        lastLocation?.let { recentreOn(it) }
    }

    /**
     * The user moved the chart themselves (a pan, or going to a layer):
     * follow drops to locate so the next fix doesn't drag the chart back to
     * the boat. Returns true when the mode changed.
     */
    fun releaseFollow(): Boolean {
        if (mode != Mode.FOLLOW) return false
        mode = Mode.LOCATE
        centredOnce = true
        return true
    }

    /** Forces the mode back to off, e.g. when location permission is denied. */
    fun turnOff() {
        mode = Mode.OFF
        centredOnce = false
        stop()
        clearMarker()
    }

    fun start() = locationController.start()

    /** Location is switched on in Android's settings. */
    val isLocationOn: Boolean get() = locationController.isLocationOn

    /** When the last position came in (wall clock), 0 before any. */
    var receivedAt = 0L
        private set

    fun stop() = locationController.stop()

    /** Clears the on-map position marker without changing the mode (and with it the course arrow: the GPS was switched off). */
    fun clearMarker() {
        drawnAt = null
        drawnCourse = null
        arrow = GpsMarker.arrow(arrow, way = null, nowMs = SystemClock.elapsedRealtime())
        mapHolder.style
            ?.getSourceAs<GeoJsonSource>(MapLayerIds.GPS_SOURCE)
            ?.setGeoJson(MapLayerIds.EMPTY_GEOJSON)
    }

    /**
     * A new style starts with an empty marker source (the basemap was
     * changed): the boat goes back where the GPS last had it, with its
     * course arrow, instead of waiting for it to move.
     */
    fun onStyleInstalled() {
        if (mode == Mode.OFF) return
        val location = lastLocation ?: return
        drawMarker(location, arrow.course)
    }

    /** Where, and with what course arrow (null for none), the marker was last drawn. */
    private var drawnAt: Location? = null
    private var drawnCourse: Float? = null

    /** The course arrow, carried from fix to fix so it doesn't blink on and off (see [GpsMarker.arrow]). */
    private var arrow = GpsMarker.Arrow.NONE

    private fun wayOf(location: Location) =
        GpsMarker.Way(location.hasBearing(), location.bearing, location.hasSpeed(), location.speed)

    private fun drawMarker(location: Location, course: Float?) {
        drawnAt = location
        drawnCourse = course
        mapHolder.style
            ?.getSourceAs<GeoJsonSource>(MapLayerIds.GPS_SOURCE)
            ?.setGeoJson(
                GeoJson.featureCollection(
                    listOf(GeoJson.point(location.latitude, location.longitude, markerProperties(location, course)))
                )
            )
    }

    private fun handleLocation(location: Location) {
        lastLocation = location
        receivedAt = System.currentTimeMillis()
        onLocationChanged(location)
        // GPS reports every second even when still; the chart isn't redrawn for the same spot.
        // Measured from where the marker is drawn, so a slow drift still moves it in the end.
        val moved = drawnAt?.let { it.distanceTo(location) >= STILL_M } ?: true
        // Even on the same spot the arrow may have to change: it goes when the boat stops
        // (or fixes have had no course for a few seconds), comes back when it sets off, and follows a turn.
        arrow = GpsMarker.arrow(arrow, wayOf(location), SystemClock.elapsedRealtime())
        val course = arrow.course
        val arrowChanged = GpsMarker.courseChanged(drawnCourse, course)
        if (centredOnce && !moved && !arrowChanged) return
        drawMarker(location, course)
        // The arrow alone doesn't move the chart.
        if (centredOnce && !moved) return

        when (mode) {
            Mode.LOCATE -> if (!centredOnce) {
                mapHolder.map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        locateZoom(location)
                    )
                )
                centredOnce = true
            }

            Mode.FOLLOW -> {
                recentreOn(location)
                centredOnce = true
            }

            Mode.OFF -> Unit
        }
    }

    /**
     * For the accuracy circle: "r0", the accuracy's radius in screen pixels at zoom 0 (it doubles
     * with each zoom level), so the circle on the chart is as big as the position is uncertain.
     * And for the arrow, the [course] it points along, when it has one.
     */
    private fun markerProperties(location: Location, course: Float?): String {
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
        val metresPerPixelZoom0 = 78271.517 * Math.cos(Math.toRadians(location.latitude)).coerceAtLeast(0.01)
        return GpsMarker.properties(accuracy / metresPerPixelZoom0, course)
    }

    /** The zoom for jumping to the boat: the usual one, or further out so a rough position's whole circle is on screen. */
    private fun locateZoom(location: Location): Double {
        if (!location.hasAccuracy() || location.accuracy < 200f) return LOCATE_ZOOM
        val metresPerPixelZoom0 = 78271.517 * Math.cos(Math.toRadians(location.latitude)).coerceAtLeast(0.01)
        // The circle about 300 px in radius.
        val zoom = Math.log(300.0 * metresPerPixelZoom0 / location.accuracy) / Math.log(2.0)
        return zoom.coerceIn(4.0, LOCATE_ZOOM)
    }

    private fun recentreOn(location: Location) {
        val map = mapHolder.map ?: return
        val target = LatLng(location.latitude, location.longitude)
        val bearing = followBearing(location)
        if (bearing == null) {
            map.animateCamera(CameraUpdateFactory.newLatLng(target))
        } else {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    org.maplibre.android.camera.CameraPosition.Builder(map.cameraPosition)
                        .target(target)
                        .bearing(bearing.toDouble())
                        .build()
                )
            )
        }
    }

    private companion object {
        const val LOCATE_ZOOM = 14.0
        /** Closer than this to the last fix counts as not having moved, metres. */
        const val STILL_M = 1f
    }
}
