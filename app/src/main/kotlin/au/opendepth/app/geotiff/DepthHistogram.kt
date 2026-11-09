package au.opendepth.app.geotiff

/**
 * How a layer's depths are spread: counts in equal bins from [lowest] to
 * [highest] metres (negative = below sea level). Lets the colour range fit
 * the bulk of a survey rather than one deep hole, and say how much of the
 * layer a range shows.
 */
class DepthHistogram(val lowest: Double, val highest: Double, private val counts: LongArray) {

    private val total = counts.sum()
    private val binWidth = (highest - lowest) / counts.size

    val isEmpty: Boolean get() = total == 0L || !(highest > lowest)

    /** The depth below which [fraction] (0..1) of the layer lies. */
    fun percentile(fraction: Double): Double {
        if (isEmpty) return Double.NaN
        val target = fraction.coerceIn(0.0, 1.0) * total
        var seen = 0L
        for (i in counts.indices) {
            val next = seen + counts[i]
            if (next >= target && counts[i] > 0) {
                val within = (target - seen) / counts[i]
                return lowest + (i + within.coerceIn(0.0, 1.0)) * binWidth
            }
            seen = next
        }
        return highest
    }

    /** The share (0..1) of the layer's depths between [a] and [b], either order. */
    fun fractionWithin(a: Double, b: Double): Double {
        if (isEmpty) return Double.NaN
        val from = minOf(a, b)
        val to = maxOf(a, b)
        var inside = 0.0
        for (i in counts.indices) {
            if (counts[i] == 0L) continue
            val binLow = lowest + i * binWidth
            val binHigh = binLow + binWidth
            val overlap = (minOf(to, binHigh) - maxOf(from, binLow)).coerceAtLeast(0.0)
            inside += counts[i] * (overlap / binWidth)
        }
        return (inside / total).coerceIn(0.0, 1.0)
    }

    companion object {
        const val BINS = 1000

        /** From decoded depths (NaN = no data). */
        fun of(values: FloatArray): DepthHistogram? {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (v in values) {
                if (v.isNaN()) continue
                if (v < lo) lo = v.toDouble()
                if (v > hi) hi = v.toDouble()
            }
            if (!(hi > lo)) return null
            val counts = LongArray(BINS)
            val scale = BINS / (hi - lo)
            for (v in values) {
                if (v.isNaN()) continue
                counts[((v - lo) * scale).toInt().coerceIn(0, BINS - 1)]++
            }
            return DepthHistogram(lo, hi, counts)
        }
    }
}
