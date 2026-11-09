package au.opendepth.app.track

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One recorded position. */
data class TrackPoint(val timeMillis: Long, val lat: Double, val lng: Double)

/** A saved track as listed: the points live in their own file. */
data class TrackSummary(
    val id: String,
    val name: String,
    val startMillis: Long,
    val endMillis: Long,
    val distanceM: Double,
    val movingMillis: Long,
    val maxSpeedMs: Double,
    val pointCount: Int
) {
    val averageSpeedMs: Double get() = if (movingMillis > 0) distanceM / (movingMillis / 1000.0) else 0.0

    /** Never under the average: tracks saved by older builds could store a lower (even zero) top speed. */
    val topSpeedMs: Double get() = max(maxSpeedMs, averageSpeedMs)
}

/**
 * Decides which GPS fixes go into a track. A boat sitting still (or swinging
 * at anchor) adds nothing, rough fixes are dropped, and a lone fix that
 * jumps impossibly far is ignored as a glitch.
 */
class TrackFilter(private var last: TrackPoint? = null) {

    /** @return the point to record, or null to skip this fix. */
    fun accept(timeMillis: Long, lat: Double, lng: Double, accuracyM: Double?): TrackPoint? {
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return null
        val point = TrackPoint(timeMillis, lat, lng)
        val previous = last
        if (previous != null) {
            val metres = TrackMath.distanceM(previous, point)
            if (metres < MIN_STEP_M) return null
            val seconds = (timeMillis - previous.timeMillis) / 1000.0
            if (seconds <= 0) return null
            // Faster than any small boat over a short gap: a GPS jump, not the boat.
            if (seconds < GLITCH_WINDOW_S && metres / seconds > MAX_SPEED_MS) return null
        }
        last = point
        return point
    }

    companion object {
        const val MAX_ACCURACY_M = 50.0
        const val MIN_STEP_M = 10.0
        /** About 60 knots. */
        const val MAX_SPEED_MS = 31.0
        const val GLITCH_WINDOW_S = 60.0
    }
}

object TrackMath {

    private const val EARTH_RADIUS_M = 6_371_008.8
    /** A gap this long that also moved [GAP_JUMP_M] splits the track (GPS lost, or the phone off). */
    const val GAP_MS = 10 * 60_000L
    /** Sitting at anchor for hours is not a gap: the boat comes back to the same spot. */
    private const val GAP_JUMP_M = 500.0
    /** Slower than this between points counts as stopped, not moving. */
    private const val MOVING_MS = 0.25
    /** Top speed is measured over at least this long, so one noisy fix can't set it. */
    private const val MAX_SPEED_WINDOW_MS = 30_000L

    fun distanceM(a: TrackPoint, b: TrackPoint): Double = distanceM(a.lat, a.lng, b.lat, b.lng)

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** True when [b] follows [a] after lost GPS rather than steady recording. */
    fun isGap(a: TrackPoint, b: TrackPoint): Boolean =
        b.timeMillis - a.timeMillis > GAP_MS && distanceM(a, b) > GAP_JUMP_M

    /** The track split wherever [isGap]. */
    fun segments(points: List<TrackPoint>): List<List<TrackPoint>> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<List<TrackPoint>>()
        var start = 0
        for (i in 1 until points.size) {
            if (isGap(points[i - 1], points[i])) {
                result += points.subList(start, i)
                start = i
            }
        }
        result += points.subList(start, points.size)
        return result
    }

    fun summarise(id: String, name: String, points: List<TrackPoint>): TrackSummary {
        var distance = 0.0
        var moving = 0L
        for (segment in segments(points)) {
            for (i in 1 until segment.size) {
                val metres = distanceM(segment[i - 1], segment[i])
                val dt = segment[i].timeMillis - segment[i - 1].timeMillis
                distance += metres
                if (dt > 0 && metres / (dt / 1000.0) >= MOVING_MS) moving += dt
            }
        }
        // The top speed is a straight line over a stretch, so on a winding
        // track it can come out under the average; it is never really slower.
        val average = if (moving > 0) distance / (moving / 1000.0) else 0.0
        return TrackSummary(
            id = id,
            name = name,
            startMillis = points.firstOrNull()?.timeMillis ?: 0L,
            endMillis = points.lastOrNull()?.timeMillis ?: 0L,
            distanceM = distance,
            movingMillis = moving,
            maxSpeedMs = max(maxSpeedMs(points), average),
            pointCount = points.size
        )
    }

    /**
     * The fastest straight-line speed over any stretch of at least 30 s
     * (within one segment): a fix that jumps off the line and back can't set it.
     * A segment shorter than that uses its whole length, so a short track
     * doesn't show a top speed of zero.
     */
    fun maxSpeedMs(points: List<TrackPoint>): Double {
        var best = 0.0
        for (segment in segments(points)) {
            if (segment.size < 2) continue
            val spanMillis = segment.last().timeMillis - segment.first().timeMillis
            if (spanMillis < MAX_SPEED_WINDOW_MS) {
                if (spanMillis > 0) best = max(best, distanceM(segment.first(), segment.last()) / (spanMillis / 1000.0))
                continue
            }
            var j = 0
            for (i in segment.indices) {
                if (j < i) j = i
                while (j < segment.size && segment[j].timeMillis - segment[i].timeMillis < MAX_SPEED_WINDOW_MS) j++
                if (j >= segment.size) break
                val seconds = (segment[j].timeMillis - segment[i].timeMillis) / 1000.0
                best = max(best, distanceM(segment[i], segment[j]) / seconds)
            }
        }
        return best
    }

    /**
     * Fewer points for drawing (Douglas–Peucker): drops points that lie
     * within [toleranceM] of the line through their neighbours.
     */
    fun simplify(points: List<TrackPoint>, toleranceM: Double): List<TrackPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val cosLat = cos(Math.toRadians(points[0].lat))
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var worst = -1
            var worstM = toleranceM
            for (i in first + 1 until last) {
                val d = offLineM(points[i], points[first], points[last], cosLat)
                if (d > worstM) {
                    worst = i
                    worstM = d
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(intArrayOf(first, worst))
                stack.addLast(intArrayOf(worst, last))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance of [p] from the segment [a]–[b], on a flat local projection. */
    private fun offLineM(p: TrackPoint, a: TrackPoint, b: TrackPoint, cosLat: Double): Double {
        val metresPerDeg = 111_320.0
        val ax = a.lng * metresPerDeg * cosLat
        val ay = a.lat * metresPerDeg
        val bx = b.lng * metresPerDeg * cosLat - ax
        val by = b.lat * metresPerDeg - ay
        val px = p.lng * metresPerDeg * cosLat - ax
        val py = p.lat * metresPerDeg - ay
        val lengthSq = bx * bx + by * by
        if (lengthSq == 0.0) return sqrt(px * px + py * py)
        val t = ((px * bx + py * by) / lengthSq).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /** "2 h 05 min", "12 min", "45 s". */
    fun duration(millis: Long): String {
        val totalMinutes = abs(millis) / 60_000
        return when {
            totalMinutes >= 60 -> String.format(java.util.Locale.US, "%d h %02d min", totalMinutes / 60, totalMinutes % 60)
            totalMinutes >= 1 -> "$totalMinutes min"
            else -> "${abs(millis) / 1000} s"
        }
    }
}

/** A track's points as text, one "time,lat,lng" line each, so recording only ever appends. */
object TrackCodec {

    fun line(point: TrackPoint): String = "${point.timeMillis},${point.lat},${point.lng}\n"

    /** Reads the lines back, skipping any that are damaged (a half-written last line, say). */
    fun parse(text: String): List<TrackPoint> = text.lineSequence().mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 3) return@mapNotNull null
        val time = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
        val lat = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
        val lng = parts[2].trim().toDoubleOrNull() ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
        TrackPoint(time, lat, lng)
    }.toList()
}
