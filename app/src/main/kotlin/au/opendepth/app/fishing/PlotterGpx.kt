package au.opendepth.app.fishing

import au.opendepth.app.util.XmlEscape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Writes what is on the chart as a GPX 1.1 file a chartplotter can load from
 * an SD card: points as waypoints, lines as tracks, each line its own named
 * track ("20 m contour", "21.5 °C"). Plotters hold only so much, so lines are
 * simplified (Douglas-Peucker, in metres) and the counts capped; what had to
 * be left out is counted.
 *
 * Pure Kotlin, unit-tested on the JVM.
 */
object PlotterGpx {

    class Point(val name: String, val lat: Double, val lng: Double, val desc: String? = null)

    /**
     * A line as longitude, latitude pairs. [group] is the kind of line
     * (contours, zone edges...): when there are more lines than tracks a
     * plotter takes, each group gets its turn, so no kind is crowded out.
     */
    class Line(val name: String, val points: DoubleArray, val desc: String? = null, val group: String = "") {
        val size: Int get() = points.size / 2
    }

    /**
     * What the file may hold. The defaults suit most plotters: a few hundred
     * tracks of up to a couple of thousand points (older Garmin units take
     * 200 saved tracks, Lowrance and Simrad similar), and a thousand
     * waypoints. [toleranceM] is how far a simplified line may stray.
     */
    class Limits(
        val maxWaypoints: Int = 1000,
        val maxTracks: Int = 200,
        val maxPointsPerTrack: Int = 2000,
        val maxTotalPoints: Int = 30_000,
        val toleranceM: Double = 2.0
    )

    class Built(val gpx: String, val waypoints: Int, val tracks: Int, val trackPoints: Int, val leftOut: Int)

    /**
     * The file, with [points] in the order given (the first are kept when
     * there are too many) and [lines] in the order given, those kept chosen
     * by [byTurns]. [description] goes in the file's metadata.
     */
    fun build(points: List<Point>, lines: List<Line>, limits: Limits = Limits(), description: String? = null): Built {
        val keptPoints = points.filter { it.lat.isFinite() && it.lng.isFinite() }.take(limits.maxWaypoints)
        val candidates = lines.filter { it.size >= 2 }
        // The lines to keep, most wanted first; written in the order given.
        val keptLines = byTurns(candidates).take(limits.maxTracks)
        var tolerance = max(limits.toleranceM, 0.0)
        var simplified = keptLines.map { fit(it, tolerance, limits.maxPointsPerTrack) }
        var tries = 0
        while (simplified.sumOf { it.size } > limits.maxTotalPoints && tries < MAX_WIDENINGS) {
            tolerance = if (tolerance <= 0.0) 1.0 else tolerance * 2
            simplified = simplified.map { fit(it, tolerance, limits.maxPointsPerTrack) }
            tries++
        }
        // Still too many: the least wanted lines go.
        val chosen = HashSet<Int>()
        var total = 0
        for ((k, line) in simplified.withIndex()) {
            if (total + line.size > limits.maxTotalPoints) continue
            chosen += k
            total += line.size
        }
        val order = candidates.withIndex().associate { (k, line) -> line to k }
        val final = simplified.withIndex().filter { it.index in chosen }
            .sortedBy { order.getValue(keptLines[it.index]) }.map { it.value }
        val leftOut = (points.size - keptPoints.size) + (candidates.size - final.size)

        val gpx = StringBuilder(64 + total * 48 + keptPoints.size * 120)
        gpx.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        gpx.append(
            """<gpx version="1.1" creator="SeaBathy" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        ).append('\n')
        if (!description.isNullOrBlank()) gpx.append("  <metadata><desc>").append(XmlEscape.text(description)).append("</desc></metadata>\n")
        val pointNames = Names()
        for (p in keptPoints) {
            gpx.append("  <wpt lat=\"")
            appendCoordinate(gpx, p.lat)
            gpx.append("\" lon=\"")
            appendCoordinate(gpx, p.lng)
            gpx.append("\"><name>").append(XmlEscape.text(pointNames.unique(p.name))).append("</name>")
            if (!p.desc.isNullOrBlank()) gpx.append("<desc>").append(XmlEscape.text(p.desc)).append("</desc>")
            gpx.append("</wpt>\n")
        }
        val trackNames = Names()
        for (line in final) {
            gpx.append("  <trk><name>").append(XmlEscape.text(trackNames.unique(line.name))).append("</name>")
            if (!line.desc.isNullOrBlank()) gpx.append("<desc>").append(XmlEscape.text(line.desc)).append("</desc>")
            gpx.append("\n    <trkseg>\n")
            for (i in 0 until line.size) {
                gpx.append("      <trkpt lat=\"")
                appendCoordinate(gpx, line.points[2 * i + 1])
                gpx.append("\" lon=\"")
                appendCoordinate(gpx, line.points[2 * i])
                gpx.append("\"/>\n")
            }
            gpx.append("    </trkseg>\n  </trk>\n")
        }
        gpx.append("</gpx>\n")
        return Built(gpx.toString(), keptPoints.size, final.size, total, leftOut)
    }

    /** [line] simplified to [toleranceM], and further until it has at most [maxPoints]. */
    private fun fit(line: Line, toleranceM: Double, maxPoints: Int): Line {
        var tolerance = toleranceM
        var points = simplify(line.points, tolerance)
        var tries = 0
        while (points.size / 2 > maxPoints && tries < MAX_WIDENINGS) {
            tolerance = if (tolerance <= 0.0) 1.0 else tolerance * 2
            points = simplify(line.points, tolerance)
            tries++
        }
        return Line(line.name, points, line.desc)
    }

    /**
     * Douglas-Peucker: the fewest of [points] (lng, lat pairs) that stay
     * within [toleranceM] metres of the line. Ends are always kept, so a
     * closed line stays closed.
     */
    fun simplify(points: DoubleArray, toleranceM: Double): DoubleArray {
        val n = points.size / 2
        if (n <= 2 || toleranceM <= 0.0) return points.copyOf()
        // Metres on a flat sheet round the line's first point: fine for the few km a screen covers.
        val lat0 = points[1]
        val perLat = METRES_PER_DEGREE
        val perLng = METRES_PER_DEGREE * cos(Math.toRadians(lat0))
        val x = DoubleArray(n) { (points[2 * it] - points[0]) * perLng }
        val y = DoubleArray(n) { (points[2 * it + 1] - lat0) * perLat }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            if (b - a < 2) continue
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(x[i], y[i], x[a], y[a], x[b], y[b])
                if (d > worst) {
                    worst = d
                    at = i
                }
            }
            if (worst > toleranceM) {
                keep[at] = true
                stack.addLast(intArrayOf(a, at))
                stack.addLast(intArrayOf(at, b))
            }
        }
        val out = DoubleArray(keep.count { it } * 2)
        var k = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            out[k++] = points[2 * i]
            out[k++] = points[2 * i + 1]
        }
        return out
    }

    /**
     * The parts of the line through [points] (lng, lat pairs) inside the
     * box, cut exactly at its edges: each segment is clipped on its own
     * (Liang-Barsky), so a long segment crossing the screen with both ends
     * off it is kept too. A part that leaves the box and comes back is two
     * runs. Degrees are treated as flat, which is fine across a screen.
     */
    fun clip(points: DoubleArray, west: Double, south: Double, east: Double, north: Double): List<DoubleArray> {
        val n = points.size / 2
        val runs = ArrayList<DoubleArray>()
        if (n == 0 || !(east >= west) || !(north >= south)) return runs
        var run: ArrayList<Double>? = null
        fun close() {
            run?.let { if (it.size >= 4) runs += it.toDoubleArray() }
            run = null
        }
        if (n == 1) return runs
        for (i in 0 until n - 1) {
            val x0 = points[2 * i]
            val y0 = points[2 * i + 1]
            val x1 = points[2 * i + 2]
            val y1 = points[2 * i + 3]
            val part = clipSegment(x0, y0, x1, y1, west, south, east, north)
            if (part == null) {
                close()
                continue
            }
            val (t0, t1) = part
            val dx = x1 - x0
            val dy = y1 - y0
            // Joined to the run so far only where this segment starts inside the box, where the last one ended.
            val current = run
            if (current == null || t0 > 0.0) {
                close()
                run = arrayListOf(x0 + t0 * dx, y0 + t0 * dy)
            }
            run!!.add(x0 + t1 * dx)
            run!!.add(y0 + t1 * dy)
            // Leaves the box before its end: the run stops here.
            if (t1 < 1.0) close()
        }
        close()
        return runs
    }

    /**
     * The part of the segment from (x0, y0) to (x1, y1) inside the box, as
     * the fractions of the way along it where it enters and leaves; null when
     * none of it is inside.
     */
    internal fun clipSegment(x0: Double, y0: Double, x1: Double, y1: Double, west: Double, south: Double, east: Double, north: Double): Pair<Double, Double>? {
        val dx = x1 - x0
        val dy = y1 - y0
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(x0 - west, east - x0, y0 - south, north - y0)
        for (k in 0 until 4) {
            if (p[k] == 0.0) {
                // Parallel to this edge: wholly outside it, or no limit from it.
                if (q[k] < 0.0) return null
                continue
            }
            val t = q[k] / p[k]
            if (p[k] < 0.0) {
                if (t > t1) return null
                if (t > t0) t0 = t
            } else {
                if (t < t0) return null
                if (t < t1) t1 = t
            }
        }
        // Only touching the box at a point (a corner) is not a part of the line inside it.
        return if (t1 > t0 || (dx == 0.0 && dy == 0.0)) t0 to t1 else null
    }

    /**
     * [lines] in the order they are wanted when not all can be kept: one
     * from each group in turn, in the order the groups first appear, and
     * within a group the longest first.
     */
    internal fun byTurns(lines: List<Line>): List<Line> {
        val groups = LinkedHashMap<String, ArrayDeque<Line>>()
        for (line in lines) groups.getOrPut(line.group) { ArrayDeque() }
        for ((group, queue) in groups) queue.addAll(lines.filter { it.group == group }.sortedByDescending { lengthOf(it.points) })
        val out = ArrayList<Line>(lines.size)
        while (out.size < lines.size) {
            for (queue in groups.values) queue.removeFirstOrNull()?.let { out += it }
        }
        return out
    }

    /** A line's length in metres, near enough to rank lines by. */
    private fun lengthOf(points: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until points.size / 2 - 1) {
            val perLng = METRES_PER_DEGREE * cos(Math.toRadians(points[2 * i + 1]))
            sum += kotlin.math.hypot((points[2 * i + 2] - points[2 * i]) * perLng, (points[2 * i + 3] - points[2 * i + 1]) * METRES_PER_DEGREE)
        }
        return sum
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        if (length2 <= 0.0) return kotlin.math.hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / length2).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Makes names unique, as some plotters refuse or overwrite a second one with the same name: "Ledge 22 m 2". */
    private class Names {
        private val used = HashSet<String>()

        fun unique(name: String): String {
            val base = name.trim().ifEmpty { "Mark" }
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = "$base ${n++}"
            return candidate
        }
    }

    /** Degrees to six places (about 10 cm), without formatting each one; no exponent ever. */
    private fun appendCoordinate(out: StringBuilder, degrees: Double) {
        var scaled = Math.round(degrees * 1e6)
        if (scaled < 0) {
            out.append('-')
            scaled = abs(scaled)
        }
        out.append(scaled / 1_000_000).append('.')
        val fraction = scaled % 1_000_000
        var divisor = 100_000L
        while (divisor > 0) {
            out.append('0' + ((fraction / divisor) % 10).toInt())
            divisor /= 10
        }
    }

    private const val MAX_WIDENINGS = 12
    private const val METRES_PER_DEGREE = 111_195.0
}
