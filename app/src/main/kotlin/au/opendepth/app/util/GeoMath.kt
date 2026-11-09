package au.opendepth.app.util

import au.opendepth.app.model.RoutePoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance, bearing and passage-time helpers. */
object GeoMath {

    private const val EARTH_RADIUS_NM = 3440.065

    /** Nautical miles to kilometres. */
    const val KM_PER_NM = 1.852

    fun distanceNm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val deltaLat = Math.toRadians(b.first - a.first)
        val deltaLng = Math.toRadians(b.second - a.second)
        val haversine = sin(deltaLat / 2).pow(2) +
            cos(Math.toRadians(a.first)) *
            cos(Math.toRadians(b.first)) *
            sin(deltaLng / 2).pow(2)
        return 2 * EARTH_RADIUS_NM * asin(sqrt(haversine))
    }

    fun totalNm(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (index in 1 until points.size) {
            total += distanceNm(points[index - 1], points[index])
        }
        return total
    }

    fun bearingDegrees(a: RoutePoint, b: RoutePoint): Double {
        val deltaLng = Math.toRadians(b.lng - a.lng)
        val y = sin(deltaLng) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
            sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(deltaLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun eta(distanceNm: Double, speedKnots: Double): String {
        if (distanceNm <= 0 || speedKnots <= 0) return "—"
        val hours = distanceNm / speedKnots
        val wholeHours = hours.toInt()
        val minutes = ((hours - wholeHours) * 60).toInt()
        return when {
            wholeHours > 0 -> "${wholeHours}h${minutes}m"
            minutes == 0 -> "<1m"
            else -> "${minutes}m"
        }
    }

    /**
     * Nautical miles with kilometres, or metres under a tenth of a mile:
     * short legs and measurements read "0.00 nm" before.
     */
    fun formatDistance(distanceNm: Double): String =
        if (distanceNm < SHORT_DISTANCE_NM) {
            String.format(java.util.Locale.US, "%.0f m", distanceNm * METRES_PER_NM)
        } else {
            String.format(java.util.Locale.US, "%.2f nm (%.1f km)", distanceNm, distanceNm * KM_PER_NM)
        }

    const val METRES_PER_NM = 1852.0
    private const val SHORT_DISTANCE_NM = 0.1
}
