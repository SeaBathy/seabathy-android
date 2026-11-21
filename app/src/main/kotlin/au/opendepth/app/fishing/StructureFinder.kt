package au.opendepth.app.fishing

import au.opendepth.app.geotiff.SeabedSlope
import au.opendepth.app.map.DepthContours
import au.opendepth.app.nav.DepthUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds likely fishing structure in a grid of chart depths: pinnacles and
 * bommies (highs standing clear of the seabed round them), ledges and
 * drop-offs (steep runs between two levels) and holes (lows). The sums follow
 * NOAA's Benthic Terrain Modeler: the bathymetric position index (how far a
 * cell sits above or below the mean of a ring round it) at a fine and a broad
 * scale, and the slope.
 *
 * An automatic guess from the depths, nothing more. It works on a grid of at
 * most [MAX_SIDE] cells a side, so it is quick on a phone, and leaves out what
 * the data can't really show: bumps within the roughness of the seabed round
 * them or within a step of the depths' own rounding, single-cell spikes, a
 * high that is only the top of a slope, and anything at the edge of the
 * depths or beside land (land and drying cells count as no depth).
 *
 * Pure Kotlin on plain arrays, unit-tested on the JVM.
 */
object StructureFinder {

    enum class Kind { PINNACLE, LEDGE, HOLE }

    /** One likely piece of structure. Depths are metres of water, positive down. */
    class Mark(
        val kind: Kind,
        val lat: Double,
        val lng: Double,
        /** Where it is fished: a pinnacle's top, a hole's bottom, a ledge's top edge. */
        val depthM: Double,
        /** How far it rises (pinnacle), drops (ledge) or sinks (hole) against the seabed round it. */
        val reliefM: Double,
        /** The seabed round a pinnacle or hole, or a ledge's foot. */
        val otherDepthM: Double,
        /** A ledge's length along its face, metres; 0 for the others. */
        val lengthM: Double,
        /** How clearly it stands out: its relief over the least that counts there. 1 is only just. */
        val score: Double
    ) {
        val shallowM: Double get() = min(depthM, otherDepthM)
        val deepM: Double get() = max(depthM, otherDepthM)
    }

    /** What was found, best first, and the size of the cells it was worked out on. */
    class Result(val marks: List<Mark>, val cellMetres: Double, val cellsWorked: Int)

    /** The most cells along a side of the grid worked on: bigger grids are averaged down to it. */
    const val MAX_SIDE = 320

    /** The most marks kept. */
    const val MAX_MARKS = 40

    /** A ledge dropping this far or more is called a drop-off. */
    const val DROP_OFF_M = 10.0

    /** The fine scale, in cells: a high must be the highest within this many cells. */
    private const val R_FINE = 3

    /** The broad ring the seabed round a mark is measured on: from this many cells out... */
    private const val R_RING_IN = 4

    /** ...to this many. */
    private const val R_RING_OUT = 12

    /** Never less relief than this counts, metres. */
    private const val MIN_RELIEF_M = 1.0

    /** Nor less than this share of the depth: a metre is nothing at 150 m. */
    private const val DEPTH_SHARE = 0.03

    /** Nor less than this many times the seabed's ordinary roughness (robust spread of the fine index). */
    private const val ROUGHNESS_TIMES = 4.0

    /** Nor less than this many steps of the depths' own rounding (whole metres, say). */
    private const val ROUNDING_STEPS = 2.0

    /** A high's neighbours must stand at least this share of its relief clear too: not a lone spike. */
    private const val SUPPORT_SHARE = 0.3

    /** And it must stand this share of its relief above each side of the ring: not just the top of a slope. */
    private const val SIDE_SHARE = 0.25

    /** A ledge must drop at least this many times the least relief that counts there. */
    private const val LEDGE_TIMES = 2.0

    /** Slopes flatter than this are never a ledge, degrees. */
    private const val MIN_LEDGE_DEG = 1.5

    /** Fewer steep cells than this joined together are not a ledge. */
    private const val MIN_LEDGE_CELLS = 3

    /** A ledge mostly within the broad ring of a high or low is only that high's or low's side. */
    private const val CLAIM_SHARE = 0.6

    /**
     * Looks for structure in [values] ([cols] to a row, north row first,
     * metres negative down, NaN for no depth), whose cells are [cellXMetres]
     * by [cellYMetres] and lie where [placement] says. Only marks [within]
     * says yes to are kept (the part on screen). [cancelled] is checked as it
     * goes, so a search nobody wants any more stops early.
     */
    fun find(
        values: FloatArray,
        cols: Int,
        rows: Int,
        cellXMetres: Double,
        cellYMetres: Double,
        placement: DepthContours.Placement,
        maxMarks: Int = MAX_MARKS,
        within: (lat: Double, lng: Double) -> Boolean = { _, _ -> true },
        cancelled: () -> Boolean = { false }
    ): Result {
        require(values.size == cols * rows) { "grid size" }
        if (cols < 2 || rows < 2 || !(cellXMetres > 0.0) || !(cellYMetres > 0.0)) return Result(emptyList(), 0.0, 0)

        // Averaged down to at most MAX_SIDE a side, keeping where each block's shallowest and deepest cell is.
        val f = max(1, ceil(max(cols, rows).toDouble() / MAX_SIDE).toInt())
        val w = (cols + f - 1) / f
        val h = (rows + f - 1) / f
        val z = FloatArray(w * h) { Float.NaN }
        val topAt = IntArray(w * h) { -1 }
        val bottomAt = IntArray(w * h) { -1 }
        for (bj in 0 until h) {
            for (bi in 0 until w) {
                var sum = 0.0
                var n = 0
                var total = 0
                var hi = Float.NEGATIVE_INFINITY
                var lo = Float.POSITIVE_INFINITY
                for (y in bj * f until min(rows, (bj + 1) * f)) {
                    for (x in bi * f until min(cols, (bi + 1) * f)) {
                        total++
                        val at = y * cols + x
                        val v = values[at]
                        // No depth, or land and drying banks: not seabed to fish.
                        if (v.isNaN() || v >= 0f) continue
                        sum += v
                        n++
                        if (v > hi) {
                            hi = v
                            topAt[bj * w + bi] = at
                        }
                        if (v < lo) {
                            lo = v
                            bottomAt[bj * w + bi] = at
                        }
                    }
                }
                // A block mostly without depths is left out, so the edge of the depths stays where it is.
                if (n > 0 && n * 2 >= total) z[bj * w + bi] = (sum / n).toFloat()
            }
        }
        val cx = cellXMetres * f
        val cy = cellYMetres * f
        val cell = sqrt(cx * cy)
        if (cancelled()) return Result(emptyList(), cell, w * h)

        val table = Table(z, w, h)
        val rounding = roundingStep(values, cols, rows)

        // The seabed's ordinary roughness: the robust spread of the fine-scale index.
        val fine = FloatArray(w * h) { Float.NaN }
        val ring = FloatArray(w * h) { Float.NaN }
        var spreadCount = 0
        val spread = FloatArray(w * h)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val at = j * w + i
                if (z[at].isNaN()) continue
                table.ringMean(i, j, 1, R_FINE, MIN_COVER)?.let { mean ->
                    fine[at] = (z[at] - mean).toFloat()
                    spread[spreadCount++] = abs(fine[at])
                }
                table.ringMean(i, j, R_RING_IN, R_RING_OUT, MIN_COVER)?.let { mean -> ring[at] = mean.toFloat() }
            }
        }
        val roughness = if (spreadCount == 0) 0.0 else 1.4826 * median(spread, spreadCount)
        fun leastRelief(depthM: Double): Double = maxOf(MIN_RELIEF_M, DEPTH_SHARE * depthM, ROUGHNESS_TIMES * roughness, ROUNDING_STEPS * rounding)

        val found = ArrayList<Mark>()
        val claimed = BooleanArray(w * h)

        // Highs and lows ------------------------------------------------------
        for (j in 0 until h) {
            if (cancelled()) return Result(emptyList(), cell, w * h)
            for (i in 0 until w) {
                val at = j * w + i
                val here = z[at]
                val around = ring[at]
                if (here.isNaN() || around.isNaN()) continue
                val position = here - around
                val sign = if (position > 0) 1 else -1
                val relief = abs(position).toDouble()
                if (relief < MIN_RELIEF_M) continue
                val depth = -here.toDouble()
                val least = leastRelief(depth)
                if (relief < least) continue
                if (!isExtreme(z, w, h, i, j, sign)) continue
                if (!supported(z, w, h, i, j, around, sign, relief)) continue
                if (!clearOnEverySide(table, i, j, here, sign, relief)) continue
                val source = if (sign > 0) topAt[at] else bottomAt[at]
                if (source < 0) continue
                val x = source % cols
                val y = source / cols
                val lat = placement.lat(y.toDouble())
                val lng = placement.lng(x.toDouble())
                val extreme = -values[source].toDouble()
                found += Mark(
                    kind = if (sign > 0) Kind.PINNACLE else Kind.HOLE,
                    lat = lat,
                    lng = lng,
                    depthM = extreme,
                    reliefM = abs(-around - extreme),
                    otherDepthM = -around.toDouble(),
                    lengthM = 0.0,
                    score = relief / least
                )
                claim(claimed, w, h, i, j, R_RING_OUT)
            }
        }

        // Ledges and drop-offs --------------------------------------------------
        val slope = SeabedSlope.degrees(z, w, h, cx, cy)
        val slopes = FloatArray(w * h)
        var slopeCount = 0
        for (s in slope) if (!s.isNaN()) slopes[slopeCount++] = s
        if (slopeCount > 0) {
            // Steeper than most of the seabed here by a step this grid can show: a metre over two cells.
            val steep = maxOf(MIN_LEDGE_DEG, median(slopes, slopeCount) + Math.toDegrees(atan(1.0 / (2 * cell))))
            val seen = BooleanArray(w * h)
            val queue = IntArray(w * h)
            for (start in 0 until w * h) {
                if (seen[start] || slope[start].isNaN() || slope[start] < steep) continue
                if (cancelled()) return Result(emptyList(), cell, w * h)
                // One run of steep cells, joined at sides and corners.
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var minI = w
                var maxI = -1
                var minJ = h
                var maxJ = -1
                var claimedCells = 0
                var bestDrop = 0.0
                var best = -1
                var bestTop = 0.0
                var bestBottom = 0.0
                while (head < tail) {
                    val at = queue[head++]
                    val i = at % w
                    val j = at / w
                    minI = min(minI, i)
                    maxI = max(maxI, i)
                    minJ = min(minJ, j)
                    maxJ = max(maxJ, j)
                    if (claimed[at]) claimedCells++
                    // The step across it: the change in the broad index over the cells round it, which a
                    // plain even slope doesn't have, and the depths at its top and foot.
                    var hiIndex = Double.NEGATIVE_INFINITY
                    var loIndex = Double.POSITIVE_INFINITY
                    var top = Double.NEGATIVE_INFINITY
                    var bottom = Double.POSITIVE_INFINITY
                    for (dj in -2..2) {
                        for (di in -2..2) {
                            val ii = i + di
                            val jj = j + dj
                            if (ii < 0 || jj < 0 || ii >= w || jj >= h) continue
                            val k = jj * w + ii
                            val v = z[k]
                            if (v.isNaN()) continue
                            if (v > top) top = v.toDouble()
                            if (v < bottom) bottom = v.toDouble()
                            val r = ring[k]
                            if (r.isNaN()) continue
                            val index = (v - r).toDouble()
                            if (index > hiIndex) hiIndex = index
                            if (index < loIndex) loIndex = index
                        }
                    }
                    val drop = hiIndex - loIndex
                    if (drop.isFinite() && drop > bestDrop) {
                        bestDrop = drop
                        best = at
                        bestTop = -top
                        bestBottom = -bottom
                    }
                    for (dj in -1..1) {
                        for (di in -1..1) {
                            val ii = i + di
                            val jj = j + dj
                            if (ii < 0 || jj < 0 || ii >= w || jj >= h) continue
                            val k = jj * w + ii
                            if (seen[k] || slope[k].isNaN() || slope[k] < steep) continue
                            seen[k] = true
                            queue[tail++] = k
                        }
                    }
                }
                val cells = tail
                if (cells < MIN_LEDGE_CELLS || best < 0) continue
                if (claimedCells >= CLAIM_SHARE * cells) continue
                val lengthCells = hypot((maxI - minI + 1).toDouble(), (maxJ - minJ + 1).toDouble())
                if (lengthCells < MIN_LEDGE_CELLS) continue
                val least = LEDGE_TIMES * leastRelief(bestTop)
                if (bestDrop < least) continue
                val bi = best % w
                val bj = best / w
                val x = min(cols - 1.0, bi * f + (f - 1) / 2.0)
                val y = min(rows - 1.0, bj * f + (f - 1) / 2.0)
                found += Mark(
                    kind = Kind.LEDGE,
                    lat = placement.lat(y),
                    lng = placement.lng(x),
                    depthM = bestTop,
                    // As worded, top to foot; the index's step (which an even slope doesn't have) only decided it counts.
                    reliefM = bestBottom - bestTop,
                    otherDepthM = bestBottom,
                    lengthM = hypot((maxI - minI + 1) * cx, (maxJ - minJ + 1) * cy),
                    // A long ledge counts for more than a short one, up to a point.
                    score = bestDrop / least * sqrt(lengthCells.coerceIn(MIN_LEDGE_CELLS.toDouble(), 30.0) / MIN_LEDGE_CELLS)
                )
            }
        }

        // Best first; one mark for each feature; only the part asked for.
        val kept = ArrayList<Mark>()
        val apart = R_FINE * 1.5 * cell
        for (mark in found.filter { within(it.lat, it.lng) }.sortedByDescending { it.score }) {
            if (kept.size >= maxMarks) break
            if (kept.any { it.kind == mark.kind && metresApart(it, mark) < apart }) continue
            kept += mark
        }
        return Result(kept, cell, w * h)
    }

    // ---------------------------------------------------------------- words

    /** "Pinnacle, rises 4.2 m, top 18 m", in [unit]. */
    fun describe(mark: Mark, unit: DepthUnit): String = when (mark.kind) {
        Kind.PINNACLE -> "Pinnacle, rises ${amount(mark.reliefM, unit)}, top ${depth(mark.depthM, unit)}"
        Kind.HOLE -> "Hole, ${amount(mark.reliefM, unit)} deep, bottom ${depth(mark.depthM, unit)}"
        Kind.LEDGE -> "${ledgeName(mark)}, drops ${amount(mark.reliefM, unit)}, " +
            "${number(mark.depthM, unit)} to ${depth(mark.otherDepthM, unit)}"
    }

    /** A short name for a waypoint saved at [mark]: "Pinnacle 18 m". */
    fun name(mark: Mark, unit: DepthUnit): String = when (mark.kind) {
        Kind.PINNACLE -> "Pinnacle ${depth(mark.depthM, unit)}"
        Kind.HOLE -> "Hole ${depth(mark.depthM, unit)}"
        Kind.LEDGE -> "${ledgeName(mark)} ${depth(mark.depthM, unit)}"
    }

    private fun ledgeName(mark: Mark) = if (mark.reliefM >= DROP_OFF_M) "Drop-off" else "Ledge"

    /** A height, to a tenth: "4.2 m". */
    private fun amount(metres: Double, unit: DepthUnit) = String.format(Locale.US, "%.1f %s", metres * unit.perMetre, unit.label)

    /** A depth: whole from 10 up, else to a tenth. */
    private fun number(metres: Double, unit: DepthUnit): String {
        val v = metres * unit.perMetre
        return if (v >= 10) Math.round(v).toString() else String.format(Locale.US, "%.1f", v)
    }

    private fun depth(metres: Double, unit: DepthUnit) = "${number(metres, unit)} ${unit.label}"

    // ---------------------------------------------------------------- depth bands

    /** A range of depths the list can be cut to, in metres; [toM] is infinite for the deepest. */
    class Band(val label: String, val fromM: Double, val toM: Double) {
        /** True for a mark with any of its depths in this band. */
        fun holds(mark: Mark): Boolean = mark.deepM >= fromM && mark.shallowM < toM
    }

    /** The bands offered, in round numbers of [unit], "All depths" first. */
    fun bands(unit: DepthUnit): List<Band> {
        val edges = when (unit) {
            DepthUnit.METRES -> listOf(10, 20, 40, 80)
            DepthUnit.FEET -> listOf(30, 60, 120, 250)
            DepthUnit.FATHOMS -> listOf(5, 10, 20, 40)
        }
        val l = unit.label
        fun m(v: Int) = v / unit.perMetre
        val out = mutableListOf(Band("All depths", 0.0, Double.POSITIVE_INFINITY))
        out += Band("Shallower than ${edges.first()} $l", 0.0, m(edges.first()))
        for (k in 0 until edges.size - 1) out += Band("${edges[k]} to ${edges[k + 1]} $l", m(edges[k]), m(edges[k + 1]))
        out += Band("Deeper than ${edges.last()} $l", m(edges.last()), Double.POSITIVE_INFINITY)
        return out
    }

    // ---------------------------------------------------------------- pieces

    /** Box sums of the depths and of how many cells have one, for ring means in a few steps. */
    private class Table(z: FloatArray, private val w: Int, private val h: Int) {
        private val sums = DoubleArray((w + 1) * (h + 1))
        private val counts = IntArray((w + 1) * (h + 1))

        init {
            for (j in 0 until h) {
                var rowSum = 0.0
                var rowCount = 0
                for (i in 0 until w) {
                    val v = z[j * w + i]
                    if (!v.isNaN()) {
                        rowSum += v
                        rowCount++
                    }
                    val k = (j + 1) * (w + 1) + i + 1
                    sums[k] = sums[k - (w + 1)] + rowSum
                    counts[k] = counts[k - (w + 1)] + rowCount
                }
            }
        }

        /** Sum and count over columns [i0]..[i1] and rows [j0]..[j1], clipped to the grid. */
        fun box(i0: Int, j0: Int, i1: Int, j1: Int): Pair<Double, Int> {
            val a = max(0, i0)
            val b = max(0, j0)
            val c = min(w - 1, i1)
            val d = min(h - 1, j1)
            if (c < a || d < b) return 0.0 to 0
            val s = sums[(d + 1) * (w + 1) + c + 1] - sums[b * (w + 1) + c + 1] - sums[(d + 1) * (w + 1) + a] + sums[b * (w + 1) + a]
            val n = counts[(d + 1) * (w + 1) + c + 1] - counts[b * (w + 1) + c + 1] - counts[(d + 1) * (w + 1) + a] + counts[b * (w + 1) + a]
            return s to n
        }

        /**
         * The mean depth of the square ring from [inner] to [outer] cells
         * round (i, j), or null when fewer than [cover] of its cells (counting
         * those off the grid) have a depth: at the edge of the depths the
         * ring is mostly unknown, and a mean of what's left would mislead.
         */
        fun ringMean(i: Int, j: Int, inner: Int, outer: Int, cover: Double): Double? {
            val (so, no) = box(i - outer, j - outer, i + outer, j + outer)
            val (si, ni) = box(i - inner + 1, j - inner + 1, i + inner - 1, j + inner - 1)
            val n = no - ni
            val full = (2 * outer + 1) * (2 * outer + 1) - (2 * inner - 1) * (2 * inner - 1)
            if (n <= 0 || n < cover * full) return null
            return (so - si) / n
        }

        /** The mean of a rectangle, or null when fewer than [cover] of its cells have a depth. */
        fun mean(i0: Int, j0: Int, i1: Int, j1: Int, cover: Double): Double? {
            val (s, n) = box(i0, j0, i1, j1)
            val full = (i1 - i0 + 1) * (j1 - j0 + 1)
            if (n <= 0 || n < cover * full) return null
            return s / n
        }
    }

    /** At least this share of a ring has to have depths for its mean to count. */
    private const val MIN_COVER = 0.6

    /**
     * The highest ([sign] 1) or lowest (-1) cell within [R_FINE] cells, with
     * its eight neighbours and most of that window having depths. On a flat
     * top the first such cell in reading order is the one.
     */
    private fun isExtreme(z: FloatArray, w: Int, h: Int, i: Int, j: Int, sign: Int): Boolean {
        val here = z[j * w + i] * sign
        var known = 0
        for (dj in -R_FINE..R_FINE) {
            for (di in -R_FINE..R_FINE) {
                if (di == 0 && dj == 0) continue
                val ii = i + di
                val jj = j + dj
                if (ii < 0 || jj < 0 || ii >= w || jj >= h) {
                    if (abs(di) <= 1 && abs(dj) <= 1) return false
                    continue
                }
                val v = z[jj * w + ii]
                if (v.isNaN()) {
                    if (abs(di) <= 1 && abs(dj) <= 1) return false
                    continue
                }
                known++
                val s = v * sign
                if (s > here) return false
                if (s == here && (dj < 0 || (dj == 0 && di < 0))) return false
            }
        }
        val window = (2 * R_FINE + 1) * (2 * R_FINE + 1) - 1
        return known >= 0.8 * window
    }

    /** Its neighbours stand clear of the ring as well: a shape, not one cell of noise. */
    private fun supported(z: FloatArray, w: Int, h: Int, i: Int, j: Int, around: Float, sign: Int, relief: Double): Boolean {
        var sum = 0.0
        var n = 0
        for (dj in -1..1) {
            for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val v = z[(j + dj) * w + i + di]
                sum += v
                n++
            }
        }
        return sign * (sum / n - around) >= SUPPORT_SHARE * relief
    }

    /**
     * It stands clear of the ring on all four sides, not only on average:
     * the top edge of a ledge or of a slope is higher than the ring's mean,
     * but no higher than the seabed on its own side. At most one side may be
     * unknown (beside the edge of the depths).
     */
    private fun clearOnEverySide(table: Table, i: Int, j: Int, here: Float, sign: Int, relief: Double): Boolean {
        val sides = arrayOf(
            table.mean(i - R_RING_OUT, j - R_RING_OUT, i + R_RING_OUT, j - R_RING_IN, MIN_COVER),
            table.mean(i - R_RING_OUT, j + R_RING_IN, i + R_RING_OUT, j + R_RING_OUT, MIN_COVER),
            table.mean(i - R_RING_OUT, j - R_RING_OUT, i - R_RING_IN, j + R_RING_OUT, MIN_COVER),
            table.mean(i + R_RING_IN, j - R_RING_OUT, i + R_RING_OUT, j + R_RING_OUT, MIN_COVER)
        )
        var unknown = 0
        for (mean in sides) {
            if (mean == null) {
                unknown++
                continue
            }
            if (sign * (here - mean) < SIDE_SHARE * relief) return false
        }
        return unknown <= 1
    }

    /** Marks the cells within [radius] of (i, j) as belonging to a high or low. */
    private fun claim(claimed: BooleanArray, w: Int, h: Int, i: Int, j: Int, radius: Int) {
        for (jj in max(0, j - radius)..min(h - 1, j + radius)) {
            for (ii in max(0, i - radius)..min(w - 1, i + radius)) claimed[jj * w + ii] = true
        }
    }

    /**
     * The step the depths are rounded to (1 for whole metres), or 0 when they
     * aren't: the smallest difference between neighbours, over a sample.
     */
    internal fun roundingStep(values: FloatArray, cols: Int, rows: Int): Double {
        var smallest = Double.POSITIVE_INFINITY
        val step = max(1, (cols * rows) / 50_000)
        var at = 0
        while (at < values.size - 1) {
            if ((at + 1) % cols != 0) {
                val a = values[at]
                val b = values[at + 1]
                if (!a.isNaN() && !b.isNaN()) {
                    val d = abs(a - b).toDouble()
                    if (d > 1e-3 && d < smallest) smallest = d
                }
            }
            at += step
        }
        // Finer than 5 cm is no rounding worth allowing for.
        return if (smallest.isFinite() && smallest >= 0.05) smallest else 0.0
    }

    private fun median(values: FloatArray, count: Int): Double {
        val copy = values.copyOf(count)
        copy.sort()
        return copy[count / 2].toDouble()
    }

    private fun metresApart(a: Mark, b: Mark): Double {
        val dLat = (a.lat - b.lat) * METRES_PER_DEGREE
        val dLng = (a.lng - b.lng) * METRES_PER_DEGREE * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(dLat, dLng)
    }

    private const val METRES_PER_DEGREE = 111_195.0
}
