package au.opendepth.app.fishing

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sums behind the drift planner. A boat with its engine off drifts with
 * the wind and the current; a short test drift near the mark measures how
 * fast and which way, and the boat then starts that far up-drift of the mark
 * so the drift carries it over the top.
 *
 * Pure Kotlin, unit-tested on the JVM. Bearings are true degrees, speeds m/s.
 */
object DriftPlan {

    /** A GPS fix during a test drift. [accuracyM] is 0 when not known. */
    class Fix(val lat: Double, val lng: Double, val timeMillis: Long, val accuracyM: Double = 0.0)

    /**
     * How the boat drifts: [speedMs] towards [towardsDegrees], measured over
     * [seconds] and [metres] with the fixes [scatterM] either side of a
     * straight line on average; or, with [estimate], worked out from a forecast.
     */
    data class Drift(
        val speedMs: Double,
        val towardsDegrees: Double,
        val seconds: Double = 0.0,
        val metres: Double = 0.0,
        val scatterM: Double = 0.0,
        val estimate: Boolean = false
    )

    enum class Problem {
        /** Fewer than [MIN_FIXES] fixes came in. */
        TOO_FEW,
        /** Shorter than [MIN_SECONDS]. */
        TOO_SHORT,
        /** The boat moved no further than the GPS wanders: too slow to measure, or the GPS too rough. */
        TOO_LITTLE
    }

    /** What a test drift measured: the drift, when there is one, and why it can't be trusted, when it can't. */
    class Measurement(val drift: Drift?, val problem: Problem?)

    /** Where to start, and what the drift then does. */
    class Plan(
        val drift: Drift,
        val targetLat: Double,
        val targetLng: Double,
        val startLat: Double,
        val startLng: Double,
        /** As far past the mark as the start is before it, where the drift line ends. */
        val endLat: Double,
        val endLng: Double,
        /** From the mark to the start: up-drift. */
        val upDriftDegrees: Double,
        val leadMetres: Double,
        val leadSeconds: Double,
        /** How long the drift stays within [markRadiusM] of the mark. */
        val overMarkSeconds: Double,
        val markRadiusM: Double
    )

    const val MIN_FIXES = 5
    const val MIN_SECONDS = 30.0

    /** A test drift stops by itself after this long. */
    const val MAX_SECONDS = 120.0

    /** It must cover at least this far, and twice the GPS's own accuracy, to count. */
    private const val MIN_MOVE_M = 5.0

    /** Slower than this (under 0.05 kn) there is no drift to plan with. */
    const val MIN_SPEED_MS = 0.025

    /**
     * A small boat with no sea anchor drifts downwind at about this share of
     * the wind speed (its leeway), on top of the current. Hulls and windage
     * differ a lot, so a forecast drift is only a rough guide.
     */
    const val LEEWAY = 0.035

    /** The mark counts as passed over within this distance of it. */
    const val MARK_RADIUS_M = 20.0

    /** How far up-drift to start, offered as minutes of drift. */
    val LEAD_CHOICES_S = listOf(60.0, 120.0, 180.0, 300.0, 600.0)
    const val DEFAULT_LEAD_S = 180.0

    private const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * The drift over a test drift's [fixes] (oldest first): a straight line
     * fitted through them by least squares, so a wandering fix or two hardly
     * matters.
     */
    fun measure(fixes: List<Fix>): Measurement {
        if (fixes.size < MIN_FIXES) return Measurement(null, Problem.TOO_FEW)
        val first = fixes.first()
        val seconds = (fixes.last().timeMillis - first.timeMillis) / 1000.0
        if (seconds < MIN_SECONDS) return Measurement(null, Problem.TOO_SHORT)
        val metresPerDegLat = Math.toRadians(1.0) * EARTH_RADIUS_M
        val metresPerDegLng = metresPerDegLat * cos(Math.toRadians(first.lat))
        val n = fixes.size
        val t = DoubleArray(n) { (fixes[it].timeMillis - first.timeMillis) / 1000.0 }
        // Across the date line 179.9 to -179.9 is 0.2 degrees east, not 359.8 west.
        val x = DoubleArray(n) { lngDifference(fixes[it].lng, first.lng) * metresPerDegLng }
        val y = DoubleArray(n) { (fixes[it].lat - first.lat) * metresPerDegLat }
        val tMean = t.average()
        val xMean = x.average()
        val yMean = y.average()
        var tt = 0.0
        var tx = 0.0
        var ty = 0.0
        for (i in 0 until n) {
            val dt = t[i] - tMean
            tt += dt * dt
            tx += dt * (x[i] - xMean)
            ty += dt * (y[i] - yMean)
        }
        if (tt <= 0.0) return Measurement(null, Problem.TOO_SHORT)
        val east = tx / tt
        val north = ty / tt
        var squares = 0.0
        for (i in 0 until n) {
            val dt = t[i] - tMean
            val ex = x[i] - (xMean + east * dt)
            val ey = y[i] - (yMean + north * dt)
            squares += ex * ex + ey * ey
        }
        val speed = hypot(east, north)
        val drift = Drift(
            speedMs = speed,
            towardsDegrees = bearingOf(east, north),
            seconds = seconds,
            metres = speed * seconds,
            scatterM = sqrt(squares / n)
        )
        val accuracy = fixes.map { it.accuracyM }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val enough = maxOf(MIN_MOVE_M, 2 * accuracy, 3 * drift.scatterM)
        return Measurement(drift, if (drift.metres < enough) Problem.TOO_LITTLE else null)
    }

    /**
     * Where to start a drift over the mark at [targetLat], [targetLng]:
     * [leadSeconds] of drift up-drift of it. Null when there is no drift to
     * speak of.
     */
    fun plan(
        targetLat: Double,
        targetLng: Double,
        drift: Drift,
        leadSeconds: Double = DEFAULT_LEAD_S,
        markRadiusM: Double = MARK_RADIUS_M
    ): Plan? {
        if (!(drift.speedMs >= MIN_SPEED_MS) || !drift.towardsDegrees.isFinite()) return null
        val lead = drift.speedMs * leadSeconds
        val up = normalise(drift.towardsDegrees + 180)
        val (startLat, startLng) = destination(targetLat, targetLng, up, lead)
        val (endLat, endLng) = destination(targetLat, targetLng, drift.towardsDegrees, lead)
        return Plan(
            drift = drift,
            targetLat = targetLat,
            targetLng = targetLng,
            startLat = startLat,
            startLng = startLng,
            endLat = endLat,
            endLng = endLng,
            upDriftDegrees = up,
            leadMetres = lead,
            leadSeconds = leadSeconds,
            overMarkSeconds = 2 * markRadiusM / drift.speedMs,
            markRadiusM = markRadiusM
        )
    }

    /**
     * A rough drift from a forecast: the current, plus [leeway] of the wind
     * downwind. All four parts are m/s, east and north, the way the
     * current and wind are going (not where the wind comes from).
     */
    fun estimate(currentEast: Double, currentNorth: Double, windEast: Double, windNorth: Double, leeway: Double = LEEWAY): Drift {
        val east = currentEast + leeway * windEast
        val north = currentNorth + leeway * windNorth
        return Drift(speedMs = hypot(east, north), towardsDegrees = bearingOf(east, north), estimate = true)
    }

    /** The point [distanceM] from a start along great-circle bearing [bearingDegrees]. */
    fun destination(lat: Double, lng: Double, bearingDegrees: Double, distanceM: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDegrees)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lng)
        val p2 = asin((sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b)).coerceIn(-1.0, 1.0))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to normaliseLng(Math.toDegrees(l2))
    }

    /** The compass point for a bearing: N, NNE, NE... */
    fun compass(degrees: Double): String = POINTS[(((normalise(degrees) + 11.25) / 22.5).toInt()) % 16]

    private val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    private fun bearingOf(east: Double, north: Double): Double = normalise(Math.toDegrees(atan2(east, north)))

    private fun normalise(degrees: Double): Double = ((degrees % 360) + 360) % 360

    private fun normaliseLng(lng: Double): Double = ((lng + 540) % 360) - 180

    /** [a] minus [b] in degrees of longitude, the short way round: in [-180, 180). */
    internal fun lngDifference(a: Double, b: Double): Double = ((a - b) % 360 + 540) % 360 - 180

    /**
     * A test drift ignores fixes timed more than this before it started: the
     * phone's last known position can come in first, minutes old.
     */
    const val START_GRACE_MS = 2_000L

    /** True for a fix timed at [fixMillis] that belongs to a drift started at [startedMillis] (on the same clock). */
    fun takenSince(fixMillis: Long, startedMillis: Long): Boolean = fixMillis >= startedMillis - START_GRACE_MS

    /**
     * The fixes worth keeping: none rougher than [maxAccuracyM] (when known),
     * none from before the drift started at [startedMillis] (see
     * [takenSince]), and none at the same moment as the one before.
     */
    fun usable(fixes: List<Fix>, maxAccuracyM: Double = 50.0, startedMillis: Long = Long.MIN_VALUE + START_GRACE_MS): List<Fix> {
        val out = ArrayList<Fix>(fixes.size)
        for (fix in fixes) {
            if (fix.accuracyM > maxAccuracyM) continue
            if (!takenSince(fix.timeMillis, startedMillis)) continue
            if (out.isNotEmpty() && fix.timeMillis <= out.last().timeMillis) continue
            out += fix
        }
        return out
    }

    /** At least [MIN_FIXES] usable fixes over at least [MIN_SECONDS]: enough to stop and measure. */
    fun enough(fixes: List<Fix>): Boolean =
        fixes.size >= MIN_FIXES && max(0L, fixes.last().timeMillis - fixes.first().timeMillis) / 1000.0 >= MIN_SECONDS
}
