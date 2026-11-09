package au.opendepth.app.tides

/** A high or low water. */
data class TideExtreme(val timeMillis: Long, val heightM: Double, val isHigh: Boolean)

/**
 * Sea level over time from evenly or unevenly spaced samples (hourly from
 * the model), smoothed with a cubic Hermite curve so the height, trend and
 * the times of high and low water fall between samples. Heights are metres
 * above the model's mean sea level. Pure Kotlin, unit-tested.
 */
class TideCurve(private val timesMillis: LongArray, private val heightsM: DoubleArray) {

    init {
        require(timesMillis.size == heightsM.size) { "times and heights differ in length" }
    }

    val startMillis: Long? get() = timesMillis.firstOrNull()
    val endMillis: Long? get() = timesMillis.lastOrNull()

    fun covers(timeMillis: Long): Boolean =
        timesMillis.size >= 2 && timeMillis >= timesMillis.first() && timeMillis <= timesMillis.last()

    /** Height at [timeMillis], or null outside the data. */
    fun heightAt(timeMillis: Long): Double? {
        if (!covers(timeMillis)) return null
        var i = timesMillis.binarySearch(timeMillis)
        if (i >= 0) return heightsM[i]
        i = -i - 2 // index of the sample before timeMillis
        val t0 = timesMillis[i].toDouble()
        val t1 = timesMillis[i + 1].toDouble()
        val span = t1 - t0
        val u = (timeMillis - t0) / span
        val m0 = slope(i) * span
        val m1 = slope(i + 1) * span
        val u2 = u * u
        val u3 = u2 * u
        return (2 * u3 - 3 * u2 + 1) * heightsM[i] + (u3 - 2 * u2 + u) * m0 +
            (-2 * u3 + 3 * u2) * heightsM[i + 1] + (u3 - u2) * m1
    }

    /** Rate of change in metres per hour; positive = rising. */
    fun rateMPerHour(timeMillis: Long): Double? {
        val a = heightAt(timeMillis - RATE_STEP_MS) ?: heightAt(timeMillis) ?: return null
        val b = heightAt(timeMillis + RATE_STEP_MS) ?: heightAt(timeMillis) ?: return null
        val hours = (2.0 * RATE_STEP_MS) / HOUR_MS
        return (b - a) / hours
    }

    /** Highs and lows between [fromMillis] and [toMillis], in time order. */
    fun extremes(fromMillis: Long, toMillis: Long): List<TideExtreme> {
        val start = maxOf(fromMillis, startMillis ?: return emptyList())
        val end = minOf(toMillis, endMillis ?: return emptyList())
        if (end - start < 2 * SCAN_STEP_MS) return emptyList()
        val out = mutableListOf<TideExtreme>()
        var tPrev = start
        var hPrev = heightAt(tPrev) ?: return emptyList()
        var t = start + SCAN_STEP_MS
        var h = heightAt(t) ?: return emptyList()
        while (t + SCAN_STEP_MS <= end) {
            val tNext = t + SCAN_STEP_MS
            val hNext = heightAt(tNext) ?: break
            val isHigh = h >= hPrev && h > hNext
            val isLow = h <= hPrev && h < hNext
            if (isHigh || isLow) {
                // The turning point lies between the scan steps: fit a parabola through
                // the three samples, so the time doesn't depend on where the scan started.
                val bend = hPrev - 2 * h + hNext
                val shift = if (bend == 0.0) 0.0 else (0.5 * (hPrev - hNext) / bend).coerceIn(-0.5, 0.5)
                val tExact = t + (shift * SCAN_STEP_MS).toLong()
                out += TideExtreme(tExact, heightAt(tExact) ?: h, isHigh)
            }
            tPrev = t; hPrev = h
            t = tNext; h = hNext
        }
        return out
    }

    fun nextExtremes(fromMillis: Long, count: Int): List<TideExtreme> =
        extremes(fromMillis, fromMillis + LOOKAHEAD_MS).take(count)

    private fun slope(i: Int): Double {
        val n = timesMillis.size
        return when {
            n < 2 -> 0.0
            i == 0 -> (heightsM[1] - heightsM[0]) / (timesMillis[1] - timesMillis[0])
            i == n - 1 -> (heightsM[i] - heightsM[i - 1]) / (timesMillis[i] - timesMillis[i - 1])
            else -> (heightsM[i + 1] - heightsM[i - 1]) / (timesMillis[i + 1] - timesMillis[i - 1])
        }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        private const val RATE_STEP_MS = 10 * 60_000L
        private const val SCAN_STEP_MS = 3 * 60_000L
        private const val LOOKAHEAD_MS = 7 * 24 * HOUR_MS
    }
}
