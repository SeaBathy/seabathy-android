package au.opendepth.app.map

import android.content.Context
import android.location.Location
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

    fun stop() = locationController.stop()

    /** Clears the on-map position marker without changing the mode. */
    fun clearMarker() {
        mapHolder.style
            ?.getSourceAs<GeoJsonSource>(MapLayerIds.GPS_SOURCE)
            ?.setGeoJson(MapLayerIds.EMPTY_GEOJSON)
    }

    private fun handleLocation(location: Location) {
        val previous = lastLocation
        lastLocation = location
        onLocationChanged(location)
        // GPS reports every second even when still; don't redraw the chart for the same spot.
        if (previous != null && centredOnce && previous.distanceTo(location) < STILL_M) return
        mapHolder.style
            ?.getSourceAs<GeoJsonSource>(MapLayerIds.GPS_SOURCE)
            ?.setGeoJson(
                GeoJson.featureCollection(
                    listOf(GeoJson.point(location.latitude, location.longitude))
                )
            )

        when (mode) {
            Mode.LOCATE -> if (!centredOnce) {
                mapHolder.map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        LOCATE_ZOOM
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
