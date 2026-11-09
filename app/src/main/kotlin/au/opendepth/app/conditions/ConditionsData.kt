package au.opendepth.app.conditions

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A grid of points: [lats] south to north, [lngs] west to east, each in
 * order but not necessarily evenly spaced. Values are stored row by row from
 * the south ([index]).
 */
class GeoGrid(val lats: DoubleArray, val lngs: DoubleArray) {
    init {
        require(lats.isNotEmpty() && lngs.isNotEmpty()) { "empty grid" }
    }

    // Evenly spaced axes (the usual case) are looked up by arithmetic, not searched.
    private val latStep = evenStep(lats)
    private val lngStep = evenStep(lngs)

    val rows: Int get() = lats.size
    val cols: Int get() = lngs.size
    val size: Int get() = rows * cols
    val south: Double get() = lats.first()
    val north: Double get() = lats.last()
    val west: Double get() = lngs.first()
    val east: Double get() = lngs.last()

    fun index(row: Int, col: Int): Int = row * cols + col

    fun contains(lat: Double, lng: Double): Boolean {
        val padLat = if (rows > 1) (north - south) / (rows - 1) / 2 else 0.05
        val padLng = if (cols > 1) (east - west) / (cols - 1) / 2 else 0.05
        return lat >= south - padLat && lat <= north + padLat && lng >= west - padLng && lng <= east + padLng
    }

    /**
     * The value at [lat]/[lng], blended from the four nearest points. Land
     * (NaN) is left blank where the nearest point is land, so values don't
     * bleed onto the shore; one blank corner is otherwise ignored. With
     * [strict] off, any sea corner gives a value: for when a finer
     * coastline (the satellite's) does the masking instead.
     */
    fun sample(values: FloatArray, offset: Int, lat: Double, lng: Double, strict: Boolean = true): Float {
        if (!contains(lat, lng)) return Float.NaN
        // Written without objects (no pairs, no closures): it runs for every pixel and every flow speck.
        val ry = position(lats, latStep, lat)
        val cx = position(lngs, lngStep, lng)
        val r0 = ry.toInt().coerceIn(0, rows - 1)
        val c0 = cx.toInt().coerceIn(0, cols - 1)
        val fr = (ry - r0).coerceIn(0.0, 1.0)
        val fc = (cx - c0).coerceIn(0.0, 1.0)
        val r1 = (r0 + 1).coerceAtMost(rows - 1)
        val c1 = (c0 + 1).coerceAtMost(cols - 1)
        val nearest = values[offset + index(if (fr < 0.5) r0 else r1, if (fc < 0.5) c0 else c1)]
        if (nearest.isNaN() && strict) return Float.NaN
        var sum = 0.0
        var weight = 0.0
        val v00 = values[offset + index(r0, c0)]
        val w00 = (1 - fr) * (1 - fc)
        if (!v00.isNaN() && w00 > 0) { sum += v00 * w00; weight += w00 }
        val v01 = values[offset + index(r0, c1)]
        val w01 = (1 - fr) * fc
        if (!v01.isNaN() && w01 > 0) { sum += v01 * w01; weight += w01 }
        val v10 = values[offset + index(r1, c0)]
        val w10 = fr * (1 - fc)
        if (!v10.isNaN() && w10 > 0) { sum += v10 * w10; weight += w10 }
        val v11 = values[offset + index(r1, c1)]
        val w11 = fr * fc
        if (!v11.isNaN() && w11 > 0) { sum += v11 * w11; weight += w11 }
        return if (weight > 0) (sum / weight).toFloat() else Float.NaN
    }

    companion object {
        /** The spacing of an evenly spaced axis (to a millionth of a step), or 0 when it isn't even. */
        internal fun evenStep(axis: DoubleArray): Double {
            if (axis.size < 2) return 0.0
            val step = (axis.last() - axis.first()) / (axis.size - 1)
            if (step <= 0) return 0.0
            for (i in axis.indices) {
                if (abs(axis[i] - (axis.first() + step * i)) > step * 1e-6) return 0.0
            }
            return step
        }

        /** Where [value] falls along [axis] as a fractional index (2.25 = a quarter of the way from the 3rd point to the 4th). */
        internal fun position(axis: DoubleArray, step: Double, value: Double): Double {
            if (axis.size == 1 || value <= axis.first()) return 0.0
            if (value >= axis.last()) return (axis.size - 1).toDouble()
            if (step > 0) return (value - axis.first()) / step
            var lo = 0
            var hi = axis.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (axis[mid] <= value) lo = mid else hi = mid
            }
            val span = axis[hi] - axis[lo]
            return lo + if (span > 0) (value - axis[lo]) / span else 0.0
        }

        /** Points every [step] degrees covering [south]..[north], [west]..[east], at most [maxPerSide] a side. */
        fun covering(south: Double, west: Double, north: Double, east: Double, step: Double, maxPerSide: Int): GeoGrid {
            fun axis(from: Double, to: Double): DoubleArray {
                val count = (floor((to - from) / step + 1e-9).toInt() + 1).coerceIn(2, maxPerSide)
                return DoubleArray(count) { i -> from + (to - from) * i / (count - 1) }
            }
            return GeoGrid(axis(south, north), axis(west, east))
        }
    }
}

/** What is kept for each forecast point and hour. Directions are stored as east/north parts so they blend. */
enum class ForecastField {
    /** Surface current towards east / north, m/s (tidal flow included). */
    CURRENT_U, CURRENT_V,
    WAVE_HEIGHT,
    /** Unit vector of where the waves are going. */
    WAVE_U, WAVE_V,
    WAVE_PERIOD,
    SWELL_HEIGHT, SWELL_U, SWELL_V, SWELL_PERIOD,
    /** The forecast model's sea temperature, °C. */
    SEA_TEMP,
    /** Wind blowing towards east / north, m/s, at 10 m. */
    WIND_U, WIND_V,
    GUST
}

/** Hourly forecast values on a grid. Each field holds [times].size × grid.size values, hour by hour. */
class ForecastGrid(
    val grid: GeoGrid,
    val times: LongArray,
    val fields: Map<ForecastField, FloatArray>
) {
    init {
        require(times.isNotEmpty()) { "no hours" }
        fields.values.forEach { require(it.size == times.size * grid.size) { "field size" } }
    }

    val startMillis: Long get() = times.first()
    val endMillis: Long get() = times.last()

    fun has(field: ForecastField): Boolean = fields.containsKey(field)

    /**
     * The value at a place and time, blended between the hours either side;
     * NaN off the grid or outside the hours. [coastal]: reach right to the
     * shore, for when a finer coastline masks the land.
     */
    fun sample(field: ForecastField, lat: Double, lng: Double, timeMillis: Long, coastal: Boolean = false): Float {
        val values = fields[field] ?: return Float.NaN
        if (timeMillis < startMillis - HOUR_MS / 2 || timeMillis > endMillis + HOUR_MS / 2) return Float.NaN
        val hour = hourPosition(timeMillis)
        val h0 = hour.toInt().coerceIn(0, times.size - 1)
        val fh = hour - h0
        val a = grid.sample(values, h0 * grid.size, lat, lng, strict = !coastal)
        if (fh <= 0.0 || h0 + 1 >= times.size) return a
        val b = grid.sample(values, (h0 + 1) * grid.size, lat, lng, strict = !coastal)
        if (a.isNaN()) return b
        if (b.isNaN()) return a
        return (a + (b - a) * fh).toFloat()
    }

    /** A direction-and-strength pair, from its east and north parts. */
    fun vector(u: ForecastField, v: ForecastField, lat: Double, lng: Double, timeMillis: Long, coastal: Boolean = false): Flow? {
        val east = sample(u, lat, lng, timeMillis, coastal)
        val north = sample(v, lat, lng, timeMillis, coastal)
        if (east.isNaN() || north.isNaN()) return null
        return Flow.of(east.toDouble(), north.toDouble())
    }

    /** Hourly steps (the usual case) are found by arithmetic. */
    private val timeStep: Long = if (times.size > 1 && (1 until times.size).all { times[it] - times[it - 1] == times[1] - times[0] }) times[1] - times[0] else 0L

    /** Where [timeMillis] falls among [times], as a fractional index. */
    private fun hourPosition(timeMillis: Long): Double {
        if (timeMillis <= startMillis) return 0.0
        if (timeMillis >= endMillis) return (times.size - 1).toDouble()
        if (timeStep > 0) return (timeMillis - startMillis).toDouble() / timeStep
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= timeMillis) lo = mid else hi = mid
        }
        return lo + (timeMillis - times[lo]).toDouble() / (times[hi] - times[lo])
    }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}

/** A movement: [speed] (in the stored unit) towards compass bearing [towardsDegrees]. */
data class Flow(val speed: Double, val towardsDegrees: Double) {
    /** Where it comes from, as winds and waves are named. */
    val fromDegrees: Double get() = (towardsDegrees + 180) % 360

    companion object {
        fun of(east: Double, north: Double): Flow {
            val bearing = Math.toDegrees(atan2(east, north))
            return Flow(hypot(east, north), (bearing + 360) % 360)
        }

        /** East and north parts of [speed] heading towards [towardsDegrees]. */
        fun parts(speed: Double, towardsDegrees: Double): Pair<Double, Double> {
            val r = Math.toRadians(towardsDegrees)
            return speed * sin(r) to speed * cos(r)
        }
    }
}

/** A day's picture of the sea: from satellites, or for [TEMP_BELOW] the HYCOM ocean model. */
enum class SatelliteKind(val label: String, val unit: String, val required: Boolean = false) {
    SEA_TEMP("Sea temperature", "°C", required = true),
    CHLOROPHYLL("Chlorophyll", "mg/m³", required = true),
    /** Height of the sea against its long-term average: eddies stand out. Stored in cm. */
    SEA_LEVEL("Sea level", "cm"),
    SALINITY("Salinity", "PSU"),
    TEMP_BELOW("Temperature below the surface", "°C")
}

class SatelliteGrid(val kind: SatelliteKind, val grid: GeoGrid, val dateMillis: Long, val values: FloatArray) {
    init {
        require(values.size == grid.size) { "satellite size" }
    }

    fun sample(lat: Double, lng: Double): Float = grid.sample(values, 0, lat, lng)

    /** As [sample], but any sea corner gives a value: for coarse data, where a finer coastline hides the land. */
    fun sampleToShore(lat: Double, lng: Double): Float = grid.sample(values, 0, lat, lng, strict = false)
}

/**
 * How fast sea temperature changes across each point of [sst], in °C per km:
 * the breaks fish gather on stand out however warm the water is.
 */
fun temperatureBreaks(sst: SatelliteGrid): SatelliteGrid {
    val grid = sst.grid
    val out = FloatArray(grid.size) { Float.NaN }
    fun at(r: Int, c: Int): Float =
        if (r < 0 || c < 0 || r >= grid.rows || c >= grid.cols) Float.NaN else sst.values[grid.index(r, c)]
    /** Change per degree along an axis, from whichever neighbours are sea; 0 with none. */
    fun slope(here: Float, lo: Float, hi: Float, loAt: Double, hereAt: Double, hiAt: Double): Double = when {
        !lo.isNaN() && !hi.isNaN() && hiAt > loAt -> (hi - lo) / (hiAt - loAt)
        !hi.isNaN() && hiAt > hereAt -> (hi - here) / (hiAt - hereAt)
        !lo.isNaN() && hereAt > loAt -> (here - lo) / (hereAt - loAt)
        else -> 0.0
    }
    for (r in 0 until grid.rows) {
        val kmPerLng = KM_PER_DEGREE * kotlin.math.cos(Math.toRadians(grid.lats[r])).coerceAtLeast(0.01)
        for (c in 0 until grid.cols) {
            val here = at(r, c)
            if (here.isNaN()) continue
            val north = slope(
                here, at(r - 1, c), at(r + 1, c),
                grid.lats[(r - 1).coerceAtLeast(0)], grid.lats[r], grid.lats[(r + 1).coerceAtMost(grid.rows - 1)]
            ) / KM_PER_DEGREE
            val east = slope(
                here, at(r, c - 1), at(r, c + 1),
                grid.lngs[(c - 1).coerceAtLeast(0)], grid.lngs[c], grid.lngs[(c + 1).coerceAtMost(grid.cols - 1)]
            ) / kmPerLng
            out[grid.index(r, c)] = hypot(north, east).toFloat()
        }
    }
    return SatelliteGrid(sst.kind, grid, sst.dateMillis, out)
}

private const val KM_PER_DEGREE = 111.195

/** A saved area: its bounds, and whatever of the forecast and satellite data was downloaded for it. */
class ConditionsArea(
    val id: String,
    val name: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val fetchedMillis: Long,
    val forecast: ForecastGrid?,
    val satellites: Map<SatelliteKind, SatelliteGrid>,
    /** The depth, in metres, [SatelliteKind.TEMP_BELOW] was downloaded for. */
    val belowDepthM: Int = DEFAULT_BELOW_DEPTH_M
) {
    /** The temperature breaks, worked out once from the saved temperature. */
    val breaks: SatelliteGrid? by lazy { satellites[SatelliteKind.SEA_TEMP]?.let(::temperatureBreaks) }


    fun contains(lat: Double, lng: Double): Boolean = lat in south..north && lng in west..east

    /**
     * Whether [lat]/[lng] is sea by the satellite temperature's coastline
     * (1–2 km cells); null when there's no satellite temperature to tell.
     * The satellite leaves out the cells along the shore, so any sea cell
     * round the point counts: the colours then reach the beach.
     */
    fun isSea(lat: Double, lng: Double): Boolean? =
        satellites[SatelliteKind.SEA_TEMP]?.takeIf { it.grid.contains(lat, lng) }
            ?.let { !it.grid.sample(it.values, 0, lat, lng, strict = false).isNaN() }

    val isEmpty: Boolean get() = forecast == null && satellites.isEmpty()

    /**
     * In the middle half of the area, well away from its edges. An edge at the
     * date line or the polar limit doesn't count: nothing lies beyond it to
     * download, and a spot beside one would otherwise never settle.
     */
    fun middleContains(lat: Double, lng: Double): Boolean {
        val dLat = (north - south) / 4
        val dLng = (east - west) / 4
        return (south <= -MAX_LAT || lat >= south + dLat) && (north >= MAX_LAT || lat <= north - dLat) &&
            (west <= -MAX_LNG || lng >= west + dLng) && (east >= MAX_LNG || lng <= east - dLng)
    }

    /** True when the given bounds cover about the same water (a re-download of this area). */
    fun sameBoundsAs(s: Double, w: Double, n: Double, e: Double): Boolean =
        abs(south - s) < 0.01 && abs(west - w) < 0.01 && abs(north - n) < 0.01 && abs(east - e) < 0.01

    companion object {
        const val DEFAULT_BELOW_DEPTH_M = 50
        /** Sea conditions stop here: the forecast models don't go nearer the poles. */
        const val MAX_LAT = 80.0
        const val MAX_LNG = 180.0
    }
}
