package au.opendepth.app.nav

import au.opendepth.app.model.RoutePoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle geometry for navigation, in metres and true degrees. */
object NavGeo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLng - aLng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: RoutePoint, b: RoutePoint): Double = distanceM(a.lat, a.lng, b.lat, b.lng)

    /** Initial true bearing from a to b, 0..360. */
    fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dl = Math.toRadians(bLng - aLng)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return NavFormat.normaliseDegrees(Math.toDegrees(atan2(y, x)))
    }

    fun bearing(a: RoutePoint, b: RoutePoint): Double = bearing(a.lat, a.lng, b.lat, b.lng)

    /**
     * Signed distance of [p] from the great circle through [from] → [to]:
     * positive when [p] is to the right (starboard) of the track.
     */
    fun crossTrackM(from: RoutePoint, to: RoutePoint, pLat: Double, pLng: Double): Double {
        val d13 = distanceM(from.lat, from.lng, pLat, pLng) / EARTH_RADIUS_M
        val t13 = Math.toRadians(bearing(from.lat, from.lng, pLat, pLng))
        val t12 = Math.toRadians(bearing(from, to))
        return asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
    }

    /**
     * How far along the track from [from] towards [to] the point abeam of
     * [p] is; negative when [p] is behind [from].
     */
    fun alongTrackM(from: RoutePoint, to: RoutePoint, pLat: Double, pLng: Double): Double {
        val d13 = distanceM(from.lat, from.lng, pLat, pLng) / EARTH_RADIUS_M
        val xt = crossTrackM(from, to, pLat, pLng) / EARTH_RADIUS_M
        val cosRatio = (cos(d13) / cos(xt)).coerceIn(-1.0, 1.0)
        val along = acos(cosRatio) * EARTH_RADIUS_M
        val t13 = Math.toRadians(bearing(from.lat, from.lng, pLat, pLng))
        val t12 = Math.toRadians(bearing(from, to))
        return if (cos(t13 - t12) < 0 && abs(d13) > 0) -along else along
    }
}
