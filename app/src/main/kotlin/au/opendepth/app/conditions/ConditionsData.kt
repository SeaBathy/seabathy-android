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

    /** The same points as [other]. */
    fun sameAs(other: GeoGrid): Boolean =
        rows == other.rows && cols == other.cols &&
            lats.indices.all { kotlin.math.abs(lats[it] - other.lats[it]) < 1e-9 } && lngs.indices.all { kotlin.math.abs(lngs[it] - other.lngs[it]) < 1e-9 }

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

        /**
         * Carries values up to [passes] cells into the blanks beside them: each blank cell takes the
         * mean of its neighbours that have a value. For coarse data that stops short of the shore.
         */
        fun fillOutward(grid: GeoGrid, values: FloatArray, passes: Int, offset: Int = 0) {
            val rows = grid.rows
            val cols = grid.cols
            repeat(passes) {
                val before = values.copyOfRange(offset, offset + rows * cols)
                var changed = false
                for (r in 0 until rows) for (c in 0 until cols) {
                    if (!before[r * cols + c].isNaN()) continue
                    var sum = 0f
                    var count = 0
                    for (dr in -1..1) for (dc in -1..1) {
                        val rr = r + dr
                        val cc = c + dc
                        if (rr < 0 || cc < 0 || rr >= rows || cc >= cols) continue
                        val v = before[rr * cols + cc]
                        if (!v.isNaN()) {
                            sum += v
                            count++
                        }
                    }
                    if (count > 0) {
                        values[offset + r * cols + c] = sum / count
                        changed = true
                    }
                }
                if (!changed) return
            }
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
     * This forecast's hours and wind, with [kept]'s sea values (everything but the wind) laid in
     * hour for hour, ending at [kept]'s last hour: a forecast whose wind was downloaded afresh
     * and whose waves, swell, currents and sea temperature were kept. Both on the same points.
     */
    fun withSeaFrom(kept: ForecastGrid): ForecastGrid {
        require(grid.sameAs(kept.grid)) { "the kept forecast is on other points" }
        val hours = times.count { it <= kept.endMillis }
        require(hours > 0) { "the kept forecast ends before this one starts" }
        val t = times.copyOf(hours)
        val at = HashMap<Long, Int>(kept.times.size * 2)
        kept.times.forEachIndexed { i, time -> at[time] = i }
        val out = HashMap<ForecastField, FloatArray>()
        for ((field, values) in fields) if (field in WIND_FIELDS) out[field] = values.copyOf(hours * grid.size)
        for ((field, values) in kept.fields) {
            if (field in WIND_FIELDS) continue
            val copy = FloatArray(hours * grid.size) { Float.NaN }
            for (h in 0 until hours) {
                val k = at[t[h]] ?: continue
                System.arraycopy(values, k * grid.size, copy, h * grid.size, grid.size)
            }
            out[field] = copy
        }
        return ForecastGrid(grid, t, out)
    }

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
        /** What comes from the wind service; the rest is the sea forecast's. */
        val WIND_FIELDS = setOf(ForecastField.WIND_U, ForecastField.WIND_V, ForecastField.GUST)
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
    TEMP_BELOW("Temperature at depth", "°C")
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
    val belowDepthM: Int = DEFAULT_BELOW_DEPTH_M,
    /** Where the forecast came from: [SOURCE_MAIN], or [SOURCE_BACKUP] when the main service couldn't be had. */
    val source: Int = SOURCE_MAIN
) {
    /** The forecast on show is the backup's: coarser, with no gusts, currents or sea temperature. */
    val isBackup: Boolean get() = source == SOURCE_BACKUP && forecast != null

    /** A copy with some parts replaced. */
    fun copyWith(
        name: String = this.name, forecast: ForecastGrid? = this.forecast,
        satellites: Map<SatelliteKind, SatelliteGrid> = this.satellites, belowDepthM: Int = this.belowDepthM
    ) = ConditionsArea(id, name, south, west, north, east, fetchedMillis, forecast, satellites, belowDepthM, source)

    /** The temperature breaks, worked out once from the saved temperature. */
    val breaks: SatelliteGrid? by lazy { satellites[SatelliteKind.SEA_TEMP]?.let(::temperatureBreaks) }

    private val shoreward = java.util.concurrent.ConcurrentHashMap<String, SatelliteGrid>()

    /**
     * [kind]'s picture for drawing right up to a finer coastline: its values carried a cell or two
     * into the blanks beside them. The satellites leave out the cells along the shore, which left
     * the colours stopping short of it in steps; bigger gaps (cloud) stay empty. Worked out once.
     */
    fun toShore(kind: SatelliteKind): SatelliteGrid? = satellites[kind]?.let { shoreward.getOrPut(kind.name) { filled(it, if (kind == SatelliteKind.CHLOROPHYLL) 1 else SHORE_FILL_CELLS) } }

    /** [breaks], carried to the shore the same way. */
    val breaksToShore: SatelliteGrid? get() = breaks?.let { shoreward.getOrPut("breaks") { filled(it, SHORE_FILL_CELLS) } }

    private fun filled(grid: SatelliteGrid, cells: Int): SatelliteGrid {
        val values = grid.values.copyOf()
        GeoGrid.fillOutward(grid.grid, values, cells)
        return SatelliteGrid(grid.kind, grid.grid, grid.dateMillis, values)
    }


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
        const val DEFAULT_BELOW_DEPTH_M = 10
        const val SOURCE_MAIN = 0
        const val SOURCE_BACKUP = 1
        /** How far a satellite picture's values are carried towards the shore, in its own cells. */
        const val SHORE_FILL_CELLS = 2
        /** Sea conditions stop here: the forecast models don't go nearer the poles. */
        const val MAX_LAT = 80.0
        const val MAX_LNG = 180.0
    }
}
