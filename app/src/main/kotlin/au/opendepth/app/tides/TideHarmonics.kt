package au.opendepth.app.tides

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tidal harmonic analysis: the tide as a sum of cosines at the fixed
 * frequencies of the moon's and sun's cycles. Fitted (least squares) to a
 * year of hourly heights, the amplitudes and phases predict the tide for any
 * date offline — the same method official tide tables use. The moon's
 * 18.6-year nodal cycle slowly changes the lunar constituents; [nodal]
 * corrects for it, so predictions a year or two out stay as good as the
 * first month. Pure Kotlin, unit-tested.
 */
class TideHarmonics(
    /** Mean level, metres. */
    val mean: Double,
    /** Per constituent: cosine and sine coefficients, metres. */
    val cosines: DoubleArray,
    val sines: DoubleArray,
    /** Time origin of the phases. */
    val epochMillis: Long,
    /** Fitted with nodal corrections (tides saved by older builds were not). */
    val nodal: Boolean = true
) {
    init {
        require(cosines.size == CONSTITUENTS.size && sines.size == CONSTITUENTS.size)
    }

    fun heightAt(timeMillis: Long): Double = heights(timeMillis, 1)[0]

    /**
     * [count] heights from [startMillis], one every [stepMillis]. Much faster
     * than calling [heightAt] for each: the angles are stepped by rotation
     * and the nodal factors worked out once a day.
     */
    fun heights(startMillis: Long, count: Int, stepMillis: Long = HOUR_MS.toLong()): DoubleArray {
        val n = CONSTITUENTS.size
        val out = DoubleArray(count)
        val c = DoubleArray(n)
        val s = DoubleArray(n)
        val stepCos = DoubleArray(n)
        val stepSin = DoubleArray(n)
        val a = DoubleArray(n)
        val b = DoubleArray(n)
        val factors = Nodal.Factors()
        for (i in 0 until n) {
            val step = Math.toRadians(CONSTITUENTS[i].degreesPerHour * stepMillis / HOUR_MS)
            stepCos[i] = cos(step)
            stepSin[i] = sin(step)
        }
        // Exact angles and nodal factors at the start of each day, rotation within it.
        val day = DAY_MS.toLong()
        var k = 0
        while (k < count) {
            val t = startMillis + k * stepMillis
            val hours = (t - epochMillis) / HOUR_MS
            if (nodal) Nodal.at(t, factors)
            for (i in 0 until n) {
                val angle = Math.toRadians(CONSTITUENTS[i].degreesPerHour * hours)
                c[i] = cos(angle)
                s[i] = sin(angle)
                // f * (A cos(θ+u) + B sin(θ+u)) rewritten as a cos θ + b sin θ.
                val kind = CONSTITUENTS[i].nodal
                val f = if (nodal) factors.f(kind) else 1.0
                val u = if (nodal) Math.toRadians(factors.u(kind)) else 0.0
                val cu = cos(u)
                val su = sin(u)
                a[i] = f * (cosines[i] * cu + sines[i] * su)
                b[i] = f * (sines[i] * cu - cosines[i] * su)
            }
            val nextDay = (Math.floorDiv(t, day) + 1) * day
            val end = minOf(count, k + maxOf(1L, (nextDay - t + stepMillis - 1) / stepMillis).toInt())
            while (k < end) {
                var h = mean
                for (i in 0 until n) {
                    h += a[i] * c[i] + b[i] * s[i]
                    val nc = c[i] * stepCos[i] - s[i] * stepSin[i]
                    s[i] = s[i] * stepCos[i] + c[i] * stepSin[i]
                    c[i] = nc
                }
                out[k++] = h
            }
        }
        return out
    }

    /** Hourly heights from [fromMillis] to [toMillis], as a [TideCurve]. */
    fun curve(fromMillis: Long, toMillis: Long): TideCurve {
        val start = fromMillis - fromMillis % HOUR_MS.toLong()
        val count = ((toMillis - start) / HOUR_MS.toLong()).toInt() + 2
        val times = LongArray(count) { start + it * HOUR_MS.toLong() }
        return TideCurve(times, heights(start, count))
    }

    /**
     * The lowest tide predicted over the moon's 18.6-year cycle from
     * [fromMillis]: Lowest Astronomical Tide, the chart datum tide tables and
     * charts measure from. Metres on the same level as [heightAt]. Hourly
     * first, then minute by minute around the lowest lows.
     */
    fun lowestAstronomical(fromMillis: Long): Double {
        val start = fromMillis - fromMillis % HOUR_MS.toLong()
        val count = (LAT_YEARS * 365.25 * 24).toInt()
        val hourly = heights(start, count)
        val lows = (1 until count - 1).filter { hourly[it] <= hourly[it - 1] && hourly[it] < hourly[it + 1] }
            .sortedBy { hourly[it] }
            .take(LAT_CANDIDATES)
        if (lows.isEmpty()) return hourly.minOrNull() ?: mean
        return lows.minOf { i ->
            heights(start + (i - 1) * HOUR_MS.toLong(), 121, 60_000L).min()
        }
    }

    /** Amplitude of a constituent by name, metres (for checks and display). */
    fun amplitude(name: String): Double {
        val i = CONSTITUENTS.indexOfFirst { it.name == name }
        return if (i < 0) 0.0 else sqrt(cosines[i] * cosines[i] + sines[i] * sines[i])
    }

    class Constituent(val name: String, val degreesPerHour: Double, val nodal: Nodal.Kind = Nodal.Kind.NONE)

    /**
     * Nodal corrections: the amplitude factor f and phase shift u (degrees)
     * of the lunar constituents, from the longitude of the moon's ascending
     * node (standard formulas, as in Schureman and Pugh's tables).
     */
    object Nodal {
        enum class Kind { NONE, M2, M3, M4, M6, K2, K1, O1, J1, OO1, MF, MM }

        class Factors {
            internal var fM2 = 1.0; internal var uM2 = 0.0
            internal var fK2 = 1.0; internal var uK2 = 0.0
            internal var fK1 = 1.0; internal var uK1 = 0.0
            internal var fO1 = 1.0; internal var uO1 = 0.0
            internal var fJ1 = 1.0; internal var uJ1 = 0.0
            internal var fOO1 = 1.0; internal var uOO1 = 0.0
            internal var fMf = 1.0; internal var uMf = 0.0
            internal var fMm = 1.0

            fun f(kind: Kind): Double = when (kind) {
                Kind.NONE -> 1.0
                Kind.M2 -> fM2
                Kind.M3 -> fM2.pow(1.5)
                Kind.M4 -> fM2 * fM2
                Kind.M6 -> fM2 * fM2 * fM2
                Kind.K2 -> fK2
                Kind.K1 -> fK1
                Kind.O1 -> fO1
                Kind.J1 -> fJ1
                Kind.OO1 -> fOO1
                Kind.MF -> fMf
                Kind.MM -> fMm
            }

            fun u(kind: Kind): Double = when (kind) {
                Kind.NONE, Kind.MM -> 0.0
                Kind.M2 -> uM2
                Kind.M3 -> 1.5 * uM2
                Kind.M4 -> 2 * uM2
                Kind.M6 -> 3 * uM2
                Kind.K2 -> uK2
                Kind.K1 -> uK1
                Kind.O1 -> uO1
                Kind.J1 -> uJ1
                Kind.OO1 -> uOO1
                Kind.MF -> uMf
            }
        }

        /** Fills [into] with the factors for the day containing [timeMillis]. */
        fun at(timeMillis: Long, into: Factors) {
            val midday = floor(timeMillis / DAY_MS) * DAY_MS + DAY_MS / 2
            val centuries = (midday / DAY_MS + UNIX_EPOCH_JD - J2000_JD) / 36525.0
            val n = Math.toRadians(125.0445479 - 1934.1362891 * centuries)
            val c1 = cos(n); val c2 = cos(2 * n); val c3 = cos(3 * n)
            val s1 = sin(n); val s2 = sin(2 * n); val s3 = sin(3 * n)
            into.fM2 = 1.0004 - 0.0373 * c1 + 0.0002 * c2
            into.uM2 = -2.14 * s1
            into.fK2 = 1.0241 + 0.2863 * c1 + 0.0083 * c2 - 0.0015 * c3
            into.uK2 = -17.74 * s1 + 0.68 * s2 - 0.04 * s3
            into.fK1 = 1.0060 + 0.1150 * c1 - 0.0088 * c2 + 0.0006 * c3
            into.uK1 = -8.86 * s1 + 0.68 * s2 - 0.07 * s3
            into.fO1 = 1.0089 + 0.1871 * c1 - 0.0147 * c2 + 0.0014 * c3
            into.uO1 = 10.80 * s1 - 1.34 * s2 + 0.19 * s3
            into.fJ1 = 1.1029 + 0.1676 * c1 - 0.0170 * c2 + 0.0016 * c3
            into.uJ1 = -12.94 * s1 + 1.34 * s2 - 0.19 * s3
            into.fOO1 = 1.1027 + 0.6504 * c1 + 0.0317 * c2 - 0.0014 * c3
            into.uOO1 = -36.68 * s1 + 4.02 * s2 - 0.57 * s3
            into.fMf = 1.0429 + 0.4135 * c1 - 0.0040 * c2
            into.uMf = -23.74 * s1 + 2.68 * s2 - 0.38 * s3
            into.fMm = 1.0 - 0.1300 * c1 + 0.0013 * c2
        }

        private const val UNIX_EPOCH_JD = 2440587.5
        private const val J2000_JD = 2451545.0
    }

    companion object {
        private const val HOUR_MS = 3_600_000.0
        private const val DAY_MS = 86_400_000.0
        /** A full nodal cycle and a bit, as LAT is defined over. */
        private const val LAT_YEARS = 19
        private const val LAT_CANDIDATES = 40

        /** The constituents a year of hourly data can separate. */
        val CONSTITUENTS = listOf(
            Constituent("M2", 28.9841042, Nodal.Kind.M2), Constituent("S2", 30.0), Constituent("N2", 28.4397295, Nodal.Kind.M2),
            Constituent("K2", 30.0821373, Nodal.Kind.K2), Constituent("2N2", 27.8953548, Nodal.Kind.M2),
            Constituent("MU2", 27.9682084, Nodal.Kind.M2), Constituent("NU2", 28.5125831, Nodal.Kind.M2),
            Constituent("L2", 29.5284789, Nodal.Kind.M2), Constituent("T2", 29.9589333),
            Constituent("K1", 15.0410686, Nodal.Kind.K1), Constituent("O1", 13.9430356, Nodal.Kind.O1), Constituent("P1", 14.9589314),
            Constituent("Q1", 13.3986609, Nodal.Kind.O1), Constituent("J1", 15.5854433, Nodal.Kind.J1),
            Constituent("OO1", 16.1391017, Nodal.Kind.OO1),
            Constituent("M4", 57.9682084, Nodal.Kind.M4), Constituent("MS4", 58.9841042, Nodal.Kind.M2),
            Constituent("MN4", 57.4238337, Nodal.Kind.M4),
            Constituent("M6", 86.9523127, Nodal.Kind.M6), Constituent("M3", 43.4761563, Nodal.Kind.M3),
            Constituent("MF", 1.0980331, Nodal.Kind.MF), Constituent("MM", 0.5443747, Nodal.Kind.MM),
            Constituent("SSA", 0.0821373), Constituent("SA", 0.0410686)
        )

        /**
         * Least-squares fit to hourly samples. Needs about a year for the
         * long-period constituents; shorter records still fit, with those
         * absorbing less well.
         */
        fun fit(timesMillis: LongArray, heights: DoubleArray, nodal: Boolean = true): TideHarmonics {
            require(timesMillis.size == heights.size && timesMillis.size > 2 * CONSTITUENTS.size + 1) {
                "Not enough tide data to analyse"
            }
            val epoch = timesMillis[timesMillis.size / 2]
            val n = 1 + 2 * CONSTITUENTS.size
            val ata = Array(n) { DoubleArray(n) }
            val atb = DoubleArray(n)
            val row = DoubleArray(n)
            val factors = Nodal.Factors()
            var factorsDay = Long.MIN_VALUE
            for (k in timesMillis.indices) {
                val hours = (timesMillis[k] - epoch) / HOUR_MS
                val day = Math.floorDiv(timesMillis[k], DAY_MS.toLong())
                if (nodal && day != factorsDay) {
                    Nodal.at(timesMillis[k], factors)
                    factorsDay = day
                }
                row[0] = 1.0
                for (i in CONSTITUENTS.indices) {
                    val kind = CONSTITUENTS[i].nodal
                    val f = if (nodal) factors.f(kind) else 1.0
                    val u = if (nodal) factors.u(kind) else 0.0
                    val angle = Math.toRadians(CONSTITUENTS[i].degreesPerHour * hours + u)
                    row[1 + 2 * i] = f * cos(angle)
                    row[2 + 2 * i] = f * sin(angle)
                }
                for (a in 0 until n) {
                    val ra = row[a]
                    atb[a] += ra * heights[k]
                    for (b in a until n) ata[a][b] += ra * row[b]
                }
            }
            for (a in 0 until n) for (b in 0 until a) ata[a][b] = ata[b][a]
            val x = solve(ata, atb)
            return TideHarmonics(
                mean = x[0],
                cosines = DoubleArray(CONSTITUENTS.size) { x[1 + 2 * it] },
                sines = DoubleArray(CONSTITUENTS.size) { x[2 + 2 * it] },
                epochMillis = epoch,
                nodal = nodal
            )
        }

        /** Gaussian elimination with partial pivoting; tiny ridge for near-singular fits. */
        private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
            val n = rhs.size
            val a = Array(n) { i -> matrix[i].copyOf().also { it[i] += 1e-9 } }
            val b = rhs.copyOf()
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                if (pivot != col) {
                    val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                    val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
                }
                val p = a[col][col]
                if (abs(p) < 1e-12) continue
                for (r in col + 1 until n) {
                    val f = a[r][col] / p
                    if (f == 0.0) continue
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var sum = b[r]
                for (c in r + 1 until n) sum -= a[r][c] * x[c]
                x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else sum / a[r][r]
            }
            return x
        }
    }
}
