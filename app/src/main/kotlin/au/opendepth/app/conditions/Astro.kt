package au.opendepth.app.conditions

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun and moon times worked out on the device (no data, no download):
 * sunrise, sunset, first and last light, moonrise and moonset, the moon's
 * phase, and solunar periods (majors when the moon is overhead or
 * underfoot, minors at moonrise and moonset). Low-precision formulas,
 * good to a few minutes. Pure Kotlin, unit-tested.
 */
object Astro {

    class Day(
        val sunrise: Long?,
        val sunset: Long?,
        /** Civil dawn and dusk: sun 6° below the horizon. */
        val firstLight: Long?,
        val lastLight: Long?,
        val moonrise: Long?,
        val moonset: Long?,
        /** Moon overhead and underfoot: the solunar major periods' middles. */
        val majors: List<Long>,
        /** Moonrise and moonset: the minor periods' middles. */
        val minors: List<Long>,
        /** 0 new to 1 full. */
        val illumination: Double,
        val waxing: Boolean
    ) {
        val phaseName: String
            get() = when {
                illumination < 0.03 -> "new moon"
                illumination > 0.97 -> "full moon"
                abs(illumination - 0.5) < 0.06 -> if (waxing) "first quarter" else "last quarter"
                illumination < 0.5 -> if (waxing) "waxing crescent" else "waning crescent"
                else -> if (waxing) "waxing gibbous" else "waning gibbous"
            }
    }

    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000L
    private const val STEP_MS = 5 * 60_000L

    /** The day starting at [startMillis] (local midnight) at [lat]/[lng]. */
    fun day(startMillis: Long, lat: Double, lng: Double): Day {
        val sunUp = crossings(startMillis, -0.833) { sunAltitude(it, lat, lng) }
        val light = crossings(startMillis, -6.0) { sunAltitude(it, lat, lng) }
        val moonUp = crossings(startMillis, 0.125) { moonAltitude(it, lat, lng) }
        val majors = transits(startMillis, lat, lng)
        val (illumination, waxing) = moonPhase(startMillis + DAY_MS / 2)
        return Day(
            sunrise = sunUp.first, sunset = sunUp.second,
            firstLight = light.first, lastLight = light.second,
            moonrise = moonUp.first, moonset = moonUp.second,
            majors = majors,
            minors = listOfNotNull(moonUp.first, moonUp.second).sorted(),
            illumination = illumination, waxing = waxing
        )
    }

    /** First rising and first setting through [threshold]° during the day. */
    private fun crossings(start: Long, threshold: Double, altitude: (Long) -> Double): Pair<Long?, Long?> {
        var rise: Long? = null
        var set: Long? = null
        var t = start
        var prev = altitude(t) - threshold
        while (t < start + DAY_MS) {
            val next = t + STEP_MS
            val now = altitude(next) - threshold
            if (prev < 0 && now >= 0 && rise == null) rise = t + ((-prev / (now - prev)) * STEP_MS).toLong()
            if (prev >= 0 && now < 0 && set == null) set = t + ((prev / (prev - now)) * STEP_MS).toLong()
            prev = now
            t = next
        }
        return rise to set
    }

    /** When the moon crosses the meridian, above and below, during the day. */
    private fun transits(start: Long, lat: Double, lng: Double): List<Long> {
        val out = mutableListOf<Long>()
        var t = start
        var prev = moonHourAngle(t, lng)
        while (t < start + DAY_MS) {
            val next = t + STEP_MS
            val now = moonHourAngle(next, lng)
            // Overhead: the hour angle goes from negative to positive. Underfoot: it wraps from +180 to -180.
            if (prev < 0 && now >= 0 && now - prev < 90) out += t + ((-prev / (now - prev)) * STEP_MS).toLong()
            if (prev > 90 && now < -90) {
                val span = now + 360 - prev
                out += t + (((180 - prev) / span) * STEP_MS).toLong()
            }
            prev = now
            t = next
        }
        return out
    }

    private fun daysSinceJ2000(millis: Long): Double = millis / DAY_MS.toDouble() - 10957.5

    private fun siderealDegrees(d: Double, lng: Double): Double = 280.46061837 + 360.98564736629 * d + lng

    private fun obliquity(d: Double): Double = (23.439 - 0.0000004 * d) * RAD

    private class Position(val ra: Double, val dec: Double, val longitude: Double)

    private fun sun(d: Double): Position {
        val g = (357.528 + 0.9856003 * d) * RAD
        val lambda = (280.460 + 0.9856474 * d + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val e = obliquity(d)
        return Position(atan2(cos(e) * sin(lambda), cos(lambda)), asin(sin(e) * sin(lambda)), lambda)
    }

    private fun moon(d: Double): Position {
        val l = (218.316 + 13.176396 * d) * RAD
        val m = (134.963 + 13.064993 * d) * RAD
        val f = (93.272 + 13.229350 * d) * RAD
        val lon = l + 6.289 * RAD * sin(m)
        val b = 5.128 * RAD * sin(f)
        val e = obliquity(d)
        val ra = atan2(sin(lon) * cos(e) - (sin(b) / cos(b)) * sin(e), cos(lon))
        val dec = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(lon))
        return Position(ra, dec, lon)
    }

    private fun altitude(p: Position, d: Double, lat: Double, lng: Double): Double {
        val h = siderealDegrees(d, lng) * RAD - p.ra
        val phi = lat * RAD
        return asin(sin(phi) * sin(p.dec) + cos(phi) * cos(p.dec) * cos(h)) / RAD
    }

    fun sunAltitude(millis: Long, lat: Double, lng: Double): Double {
        val d = daysSinceJ2000(millis)
        return altitude(sun(d), d, lat, lng)
    }

    fun moonAltitude(millis: Long, lat: Double, lng: Double): Double {
        val d = daysSinceJ2000(millis)
        return altitude(moon(d), d, lat, lng)
    }

    /** The moon's hour angle, -180..180°. */
    private fun moonHourAngle(millis: Long, lng: Double): Double {
        val d = daysSinceJ2000(millis)
        var h = (siderealDegrees(d, lng) - moon(d).ra / RAD) % 360
        if (h > 180) h -= 360
        if (h <= -180) h += 360
        return h
    }

    /** Lit fraction (0..1) and whether it's growing. */
    fun moonPhase(millis: Long): Pair<Double, Boolean> {
        val d = daysSinceJ2000(millis)
        var elongation = ((moon(d).longitude - sun(d).longitude) / RAD) % 360
        if (elongation < 0) elongation += 360
        return (1 - cos(elongation * RAD)) / 2 to (elongation < 180)
    }
}
