package au.opendepth.app.conditions

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** One contour line: its [level] (grid units) and points as lng, lat pairs. */
class ContourLine(val level: Double, val points: DoubleArray) {
    val size: Int get() = points.size / 2
}

/** A warm or cold eddy's middle: the highest or lowest sea level in its neighbourhood. */
class EddyCentre(val lat: Double, val lng: Double, val valueCm: Double) {
    val warm: Boolean get() = valueCm > 0
}

/**
 * Contour lines from a grid (marching squares), for temperature lines
 * (isotherms), the edges of a chosen temperature band, and eddy outlines
 * on the sea level map. Pure Kotlin, unit-tested.
 */
object Contours {

    /** Steps offered for temperature lines, in the user's degrees. */
    val STEPS = doubleArrayOf(0.25, 0.5, 1.0, 2.0)

    /** A tidy step that gives about 6 to 12 lines over [span]. */
    fun autoStep(span: Double): Double {
        if (!span.isFinite() || span <= 0) return 1.0
        return STEPS.firstOrNull { span / it <= 12 } ?: (ceil(span / 12 / 2) * 2)
    }

    /** Whole multiples of [step] from [low] to [high]. */
    fun levels(low: Double, high: Double, step: Double): DoubleArray {
        if (!low.isFinite() || !high.isFinite() || step <= 0 || high < low) return DoubleArray(0)
        val first = ceil(low / step).toLong()
        val last = floor(high / step).toLong()
        if (last - first > 200) return DoubleArray(0)
        return DoubleArray((last - first + 1).toInt().coerceAtLeast(0)) { (first + it) * step }
    }

    /** The 1st to 99th percentile of [values], or null with none. */
    fun range(values: FloatArray): Pair<Double, Double>? {
        val finite = values.filter { it.isFinite() }.sorted()
        if (finite.isEmpty()) return null
        return finite[(finite.size * 0.01).toInt()].toDouble() to finite[(finite.size * 0.99).toInt().coerceAtMost(finite.size - 1)].toDouble()
    }

    /**
     * A copy with each point averaged with its sea neighbours, [passes]
     * times, so 1 km satellite noise doesn't make the lines jagged. Land
     * (NaN) stays land.
     */
    fun smooth(grid: GeoGrid, values: FloatArray, passes: Int = 1): FloatArray {
        var src = values
        repeat(passes) {
            val out = FloatArray(src.size)
            for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
                val here = src[grid.index(r, c)]
                if (here.isNaN()) {
                    out[grid.index(r, c)] = Float.NaN
                    continue
                }
                var sum = 0.0
                var n = 0
                for (dr in -1..1) for (dc in -1..1) {
                    val rr = r + dr
                    val cc = c + dc
                    if (rr < 0 || cc < 0 || rr >= grid.rows || cc >= grid.cols) continue
                    val v = src[grid.index(rr, cc)]
                    if (v.isFinite()) {
                        sum += v
                        n++
                    }
                }
                out[grid.index(r, c)] = (sum / n).toFloat()
            }
            src = out
        }
        return src
    }

    /** Contour lines of [values] on [grid] at each of [levels]; lines shorter than [minPoints] are dropped. */
    fun lines(grid: GeoGrid, values: FloatArray, levels: DoubleArray, minPoints: Int = 3): List<ContourLine> {
        val out = mutableListOf<ContourLine>()
        if (grid.rows < 2 || grid.cols < 2) return out
        for (level in levels) out += linesAt(grid, values, level, minPoints)
        return out
    }

    private fun linesAt(grid: GeoGrid, values: FloatArray, level: Double, minPoints: Int): List<ContourLine> {
        val cols = grid.cols
        // Edges are numbered: 2 * corner + 0 for the edge east of the corner, + 1 for the edge north of it.
        val segA = ArrayList<Int>()
        val segB = ArrayList<Int>()
        fun v(r: Int, c: Int) = values[grid.index(r, c)].toDouble()
        for (r in 0 until grid.rows - 1) for (c in 0 until cols - 1) {
            val v00 = v(r, c)
            val v01 = v(r, c + 1)
            val v11 = v(r + 1, c + 1)
            val v10 = v(r + 1, c)
            if (v00.isNaN() || v01.isNaN() || v11.isNaN() || v10.isNaN()) continue
            val b00 = v00 >= level
            val b01 = v01 >= level
            val b11 = v11 >= level
            val b10 = v10 >= level
            val bottom = 2 * (r * cols + c)
            val top = 2 * ((r + 1) * cols + c)
            val left = 2 * (r * cols + c) + 1
            val right = 2 * (r * cols + c + 1) + 1
            val crossed = ArrayList<Int>(4)
            if (b00 != b01) crossed += bottom
            if (b01 != b11) crossed += right
            if (b11 != b10) crossed += top
            if (b10 != b00) crossed += left
            when (crossed.size) {
                2 -> { segA += crossed[0]; segB += crossed[1] }
                4 -> {
                    // A saddle: the middle decides which corners join up.
                    val middle = (v00 + v01 + v11 + v10) / 4 >= level
                    if (middle == b00) {
                        segA += bottom; segB += right
                        segA += top; segB += left
                    } else {
                        segA += bottom; segB += left
                        segA += right; segB += top
                    }
                }
            }
        }
        if (segA.isEmpty()) return emptyList()

        // Where each edge's crossing lies, and which segments touch it.
        fun point(edge: Int, into: MutableList<Double>) {
            val corner = edge / 2
            val r = corner / cols
            val c = corner % cols
            val north = edge % 2 == 1
            val r2 = if (north) r + 1 else r
            val c2 = if (north) c else c + 1
            val a = v(r, c)
            val b = v(r2, c2)
            val t = if (b == a) 0.5 else ((level - a) / (b - a)).coerceIn(0.0, 1.0)
            into += grid.lngs[c] + (grid.lngs[c2] - grid.lngs[c]) * t
            into += grid.lats[r] + (grid.lats[r2] - grid.lats[r]) * t
        }
        val touching = HashMap<Int, IntArray>(segA.size * 2)
        fun attach(edge: Int, seg: Int) {
            val list = touching[edge]
            touching[edge] = if (list == null) intArrayOf(seg) else list + seg
        }
        for (i in segA.indices) {
            attach(segA[i], i)
            attach(segB[i], i)
        }
        val used = BooleanArray(segA.size)
        val lines = mutableListOf<ContourLine>()
        for (start in segA.indices) {
            if (used[start]) continue
            used[start] = true
            // Walk forward from B, then backward from A, joining segments that share an edge.
            val forward = ArrayList<Int>()
            forward += segA[start]
            forward += segB[start]
            fun walk(chain: ArrayList<Int>) {
                while (true) {
                    val end = chain.last()
                    val next = touching[end]?.firstOrNull { !used[it] } ?: return
                    used[next] = true
                    chain += if (segA[next] == end) segB[next] else segA[next]
                }
            }
            walk(forward)
            val backward = ArrayList<Int>()
            backward += segA[start]
            walk(backward)
            val edges = ArrayList<Int>(forward.size + backward.size)
            for (i in backward.size - 1 downTo 1) edges += backward[i]
            edges += forward
            if (edges.size < minPoints) continue
            val pts = ArrayList<Double>(edges.size * 2)
            for (e in edges) point(e, pts)
            lines += ContourLine(level, pts.toDoubleArray())
        }
        return lines
    }

    /**
     * Eddy middles: points whose sea level is at least [minCm] from average
     * and the highest (or lowest) within [radius] grid steps, away from
     * land and the area's edge.
     */
    fun eddies(grid: GeoGrid, valuesCm: FloatArray, minCm: Double = 10.0, radius: Int = 3): List<EddyCentre> {
        val out = mutableListOf<EddyCentre>()
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val here = valuesCm[grid.index(r, c)]
            if (!here.isFinite() || abs(here) < minCm) continue
            // Not at the area's edge or beside land: a high or low there is cut off, not an eddy.
            if (r == 0 || c == 0 || r == grid.rows - 1 || c == grid.cols - 1) continue
            if ((-1..1).any { dr -> (-1..1).any { dc -> !valuesCm[grid.index(r + dr, c + dc)].isFinite() } }) continue
            val warm = here > 0
            var best = true
            loop@ for (dr in -radius..radius) for (dc in -radius..radius) {
                if (dr == 0 && dc == 0) continue
                val rr = r + dr
                val cc = c + dc
                if (rr < 0 || cc < 0 || rr >= grid.rows || cc >= grid.cols) continue
                val v = valuesCm[grid.index(rr, cc)]
                if (!v.isFinite()) continue
                // Ties go to the first found, so a flat top gives one middle.
                if (if (warm) v > here || (v == here && (dr < 0 || (dr == 0 && dc < 0))) else v < here || (v == here && (dr < 0 || (dr == 0 && dc < 0)))) {
                    best = false
                    break@loop
                }
            }
            if (best) out += EddyCentre(grid.lats[r], grid.lngs[c], here.toDouble())
        }
        return out
    }

    /**
     * GeoJSON for the map: lines carry "kind" and "label"; eddy middles are
     * points with kind "warm" or "cold".
     */
    fun geoJson(lines: List<Pair<ContourLine, Pair<String, String>>>, eddies: List<EddyCentre> = emptyList()): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for ((line, meta) in lines) {
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","properties":{"kind":"""").append(meta.first).append("""","label":"""").append(meta.second.replace("\"", ""))
                .append(""""},"geometry":{"type":"LineString","coordinates":[""")
            for (i in 0 until line.size) {
                if (i > 0) sb.append(',')
                sb.append(String.format(Locale.US, "[%.4f,%.4f]", line.points[2 * i], line.points[2 * i + 1]))
            }
            sb.append("]}}")
        }
        for (e in eddies) {
            if (!first) sb.append(',')
            first = false
            sb.append(String.format(Locale.US, """{"type":"Feature","properties":{"kind":"%s","label":"%s"},"geometry":{"type":"Point","coordinates":[%.4f,%.4f]}}""",
                if (e.warm) "warm" else "cold", if (e.warm) "W" else "C", e.lng, e.lat))
        }
        return sb.append("]}").toString()
    }
}
