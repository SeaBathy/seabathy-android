package au.opendepth.app.tides

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise, sunset and the moon's phase, worked out offline (NOAA's
 * simplified solar equations; a mean lunar month for the phase). Good to a
 * minute or two for the sun, and plenty for "springs or neaps".
 */
object SunMoon {

    /** Sunrise and sunset (ms) on the UTC day containing [dayMillis]; null when the sun doesn't rise or set. */
    fun sunTimes(dayMillis: Long, lat: Double, lng: Double): Pair<Long, Long>? {
        val dayStart = Math.floorDiv(dayMillis, DAY_MS) * DAY_MS
        // Noon at this longitude, then the sun's declination and the equation of time there.
        val n = (dayStart - J2000_MS) / DAY_MS.toDouble() + 0.5 - lng / 360.0
        val meanAnomaly = Math.toRadians((357.5291 + 0.98560028 * n).mod(360.0))
        val centre = 1.9148 * sin(meanAnomaly) + 0.02 * sin(2 * meanAnomaly) + 0.0003 * sin(3 * meanAnomaly)
        val eclipticLng = Math.toRadians((Math.toDegrees(meanAnomaly) + centre + 180 + 102.9372).mod(360.0))
        val transit = J2000_MS + (n + 0.0053 * sin(meanAnomaly) - 0.0069 * sin(2 * eclipticLng)) * DAY_MS
        val declination = asin(sin(eclipticLng) * sin(Math.toRadians(23.44)))
        val phi = Math.toRadians(lat)
        val cosHour = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
        if (cosHour < -1 || cosHour > 1) return null
        val halfDay = Math.toDegrees(acos(cosHour)) / 360.0 * DAY_MS
        return (transit - halfDay).roundToLong() to (transit + halfDay).roundToLong()
    }

    /** Days since the last new moon, 0 until about 29.5. */
    fun moonAge(timeMillis: Long): Double {
        val days = (timeMillis - NEW_MOON_MS) / DAY_MS.toDouble()
        return days - floor(days / SYNODIC_DAYS) * SYNODIC_DAYS
    }

    /** "New moon", "Waxing crescent", … */
    fun phaseName(timeMillis: Long): String {
        val eighth = ((moonAge(timeMillis) / SYNODIC_DAYS * 8) + 0.5).toInt() % 8
        return listOf(
            "New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
            "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"
        )[eighth]
    }

    /**
     * Springs (biggest range) come a day or two after new and full moon,
     * neaps a day or two after the quarters; null in between.
     */
    fun springOrNeap(timeMillis: Long): String? {
        // Days past the nearest new or full moon, allowing for the usual lag.
        val half = SYNODIC_DAYS / 2
        val sinceSyzygy = (moonAge(timeMillis) - TIDE_LAG_DAYS).mod(half)
        return when {
            sinceSyzygy < 2.5 || sinceSyzygy > half - 2.5 -> "spring tides"
            kotlin.math.abs(sinceSyzygy - half / 2) < 2.5 -> "neap tides"
            else -> null
        }
    }

    private const val DAY_MS = 86_400_000L
    private const val J2000_MS = 946_728_000_000L // 2000-01-01 12:00 UTC
    private const val NEW_MOON_MS = 947_182_440_000L // 2000-01-06 18:14 UTC
    private const val SYNODIC_DAYS = 29.530588853
    private const val TIDE_LAG_DAYS = 1.5
}
