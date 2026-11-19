package au.opendepth.app.conditions

import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The backup forecast, for when the main service can't be reached or its limit is used up:
 * NOAA's GFS wind and WaveWatch III waves and swell, which PacIOOS (the Pacific Islands Ocean
 * Observing System, University of Hawaii) serves for a whole area in one request each, free and
 * with no account. Coarser than the main forecast (points about 55 km apart), with no gusts,
 * currents or sea temperature. Wind runs about 7 days ahead, waves about 5.
 */
object BackupForecast {
    private const val SERVER = "https://pae-paha.pacioos.hawaii.edu/erddap/griddap"
    private const val WIND = "ncep_global"
    private const val WAVES = "ww3_global"
    /** The models' spacing, degrees. */
    private const val STEP = 0.5
    private const val HOUR_MS = 3_600_000L
    /** How far the sea's values are carried over the models' land: their coast is some 50 km wide. */
    private const val FILL_CELLS = 2
    /** The server counts longitude from 0 to here. */
    private const val MAX_LNG = 359.5

    /** A dataset's answer: each variable's values hold [times].size × [lats].size × [lngs].size, in that order. */
    class Table(val times: LongArray, val lats: DoubleArray, val lngs: DoubleArray, val values: Map<String, FloatArray>)

    /** Wind towards east and north, m/s, on [grid] at each of [times] (blank outside what the model has). */
    fun wind(http: (String) -> String, grid: GeoGrid, times: LongArray): Map<ForecastField, FloatArray> {
        val table = table(http, WIND, listOf("ugrd10m", "vgrd10m"), depth = false, maxLat = 90.0, grid, times)
        return mapOf(
            ForecastField.WIND_U to onto(table, table.values.getValue("ugrd10m"), grid, times),
            ForecastField.WIND_V to onto(table, table.values.getValue("vgrd10m"), grid, times)
        )
    }

    /** Waves and swell on [grid] at each of [times]: heights, peak periods, and directions as unit vectors of where they're going. */
    fun waves(http: (String) -> String, grid: GeoGrid, times: LongArray): Map<ForecastField, FloatArray> {
        val table = table(http, WAVES, listOf("Thgt", "Tper", "Tdir", "shgt", "sper", "sdir"), depth = true, maxLat = 77.5, grid, times)
        val out = HashMap<ForecastField, FloatArray>()
        out[ForecastField.WAVE_HEIGHT] = onto(table, table.values.getValue("Thgt"), grid, times)
        out[ForecastField.WAVE_PERIOD] = onto(table, table.values.getValue("Tper"), grid, times)
        direction(table, "Tdir", grid, times).let { (u, v) -> out[ForecastField.WAVE_U] = u; out[ForecastField.WAVE_V] = v }
        out[ForecastField.SWELL_HEIGHT] = onto(table, table.values.getValue("shgt"), grid, times)
        out[ForecastField.SWELL_PERIOD] = onto(table, table.values.getValue("sper"), grid, times)
        direction(table, "sdir", grid, times).let { (u, v) -> out[ForecastField.SWELL_U] = u; out[ForecastField.SWELL_V] = v }
        return out
    }

    /** A "coming from" direction in degrees, as east and north parts of where it's going, blended like any other value and made unit length again. */
    private fun direction(table: Table, name: String, grid: GeoGrid, times: LongArray): Pair<FloatArray, FloatArray> {
        val degrees = table.values.getValue(name)
        val east = FloatArray(degrees.size) { if (degrees[it].isNaN()) Float.NaN else sin(Math.toRadians(degrees[it] + 180.0)).toFloat() }
        val north = FloatArray(degrees.size) { if (degrees[it].isNaN()) Float.NaN else cos(Math.toRadians(degrees[it] + 180.0)).toFloat() }
        val u = onto(table, east, grid, times)
        val v = onto(table, north, grid, times)
        for (i in u.indices) {
            val length = hypot(u[i], v[i])
            if (length.isNaN() || length < 1e-6f) {
                u[i] = Float.NaN
                v[i] = Float.NaN
            } else {
                u[i] /= length
                v[i] /= length
            }
        }
        return u to v
    }

    /** Asks [dataset] for [variables] over [grid] and a cell beyond, for the span of [times]. */
    private fun table(
        http: (String) -> String, dataset: String, variables: List<String>, depth: Boolean, maxLat: Double, grid: GeoGrid, times: LongArray
    ): Table {
        // What the dataset reaches to: a request past its last hour is refused outright.
        val last = lastTime(http("$SERVER/$dataset.csv?time%5B(last)%5D"))
        // A step either side, so the first and last hours have something to blend between.
        val from = times.first() - 3 * HOUR_MS
        val to = min(times.last() + 3 * HOUR_MS, last)
        if (to <= from) throw IOException("The backup forecast is out of date")
        val south = max(grid.south - STEP, -maxLat)
        val north = min(grid.north + STEP, maxLat)
        if (south > north) throw IOException("The backup forecast doesn't reach this far north or south")
        val bodies = lngRanges(grid.west - STEP, grid.east + STEP).map { (west, east) ->
            http(url(dataset, variables, depth, from, to, south, north, west, east))
        }
        return parse(bodies, variables, (grid.west + grid.east) / 2)
    }

    /** The last time in a one-value "time" table. */
    internal fun lastTime(body: String): Long {
        val line = body.lineSequence().filter { it.isNotBlank() }.drop(2).firstOrNull() ?: throw IOException("The backup forecast didn't say how far it runs")
        return runCatching { java.time.Instant.parse(line.trim()).toEpochMilli() }.getOrElse { throw IOException("The backup forecast didn't say how far it runs") }
    }

    /**
     * [west]..[east] in degrees east (−180 to 180, or a little past) as the server's 0 to 360:
     * two pieces when it spans Greenwich.
     */
    internal fun lngRanges(west: Double, east: Double): List<Pair<Double, Double>> {
        fun fit(v: Double) = v.coerceIn(0.0, MAX_LNG)
        return when {
            east - west >= MAX_LNG -> listOf(0.0 to MAX_LNG)
            west < 0 && east >= 0 -> listOf(fit(west + 360) to MAX_LNG, 0.0 to fit(east))
            west < 0 -> listOf(fit(west + 360) to fit(east + 360))
            else -> listOf(fit(west) to fit(east))
        }
    }

    internal fun url(
        dataset: String, variables: List<String>, depth: Boolean, from: Long, to: Long,
        south: Double, north: Double, west: Double, east: Double
    ): String {
        fun f(v: Double) = String.format(Locale.US, "%.2f", v)
        fun iso(millis: Long) = java.time.Instant.ofEpochMilli(millis).toString()
        val index = "%5B(${iso(from)}):1:(${iso(to)})%5D" + (if (depth) "%5B(0.0)%5D" else "") +
            "%5B(${f(south)}):1:(${f(north)})%5D%5B(${f(west)}):1:(${f(east)})%5D"
        return "$SERVER/$dataset.csv?" + variables.joinToString(",") { it + index }
    }

    /**
     * The server's tables (a names row, a units row, then time,[depth,]latitude,longitude and a
     * value for each variable) as one [Table]. Its longitudes, 0 to 360, come back as degrees
     * east round [centreLng].
     */
    internal fun parse(bodies: List<String>, variables: List<String>, centreLng: Double): Table {
        class Row(val time: Long, val lat: Double, val lng: Double, val values: FloatArray)
        val rows = ArrayList<Row>()
        val times = sortedSetOf<Long>()
        val lats = sortedSetOf<Double>()
        val lngs = sortedSetOf<Double>()
        for (body in bodies) {
            val lines = body.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) continue
            val names = lines.next().trim().split(',')
            val timeCol = names.indexOf("time")
            val latCol = names.indexOf("latitude")
            val lngCol = names.indexOf("longitude")
            val columns = variables.map { names.indexOf(it) }
            if (timeCol < 0 || latCol < 0 || lngCol < 0 || columns.any { it < 0 }) throw IOException("The backup forecast sent an unexpected table")
            if (lines.hasNext()) lines.next()
            var timeText = ""
            var time = 0L
            for (line in lines) {
                val cells = line.split(',')
                if (cells.size < names.size) continue
                if (cells[timeCol] != timeText) {
                    timeText = cells[timeCol]
                    time = runCatching { java.time.Instant.parse(timeText).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
                }
                if (time == Long.MIN_VALUE) continue
                val lat = cells[latCol].toDoubleOrNull() ?: continue
                val raw = cells[lngCol].toDoubleOrNull() ?: continue
                val lng = raw - 360.0 * Math.round((raw - centreLng) / 360.0)
                // The models mark "nothing here" with a huge number where it isn't left blank.
                val values = FloatArray(columns.size) { cells[columns[it]].trim().toFloatOrNull()?.takeIf { v -> v.isFinite() && abs(v) < 1e8f } ?: Float.NaN }
                times += time
                lats += lat
                lngs += lng
                rows += Row(time, lat, lng, values)
            }
        }
        if (rows.isEmpty()) throw IOException("The backup forecast has nothing for this area")
        val timeAxis = times.toLongArray()
        val latAxis = lats.toDoubleArray()
        val lngAxis = lngs.toDoubleArray()
        val timeIndex = timeAxis.withIndex().associate { it.value to it.index }
        val latIndex = latAxis.withIndex().associate { it.value to it.index }
        val lngIndex = lngAxis.withIndex().associate { it.value to it.index }
        val cells = latAxis.size * lngAxis.size
        val arrays = variables.map { FloatArray(timeAxis.size * cells) { Float.NaN } }
        for (row in rows) {
            val at = timeIndex.getValue(row.time) * cells + latIndex.getValue(row.lat) * lngAxis.size + lngIndex.getValue(row.lng)
            for (k in arrays.indices) arrays[k][at] = row.values[k]
        }
        return Table(timeAxis, latAxis, lngAxis, variables.zip(arrays).toMap())
    }

    /**
     * [values] of [table] on [grid]'s points at each of [times]: blended between the model's
     * points and its hours, the sea's values first carried a couple of cells over the model's
     * land so they reach the real shore. Blank outside the model's hours.
     */
    internal fun onto(table: Table, values: FloatArray, grid: GeoGrid, times: LongArray): FloatArray {
        val source = GeoGrid(table.lats, table.lngs)
        val cells = source.size
        val atPoints = Array(table.times.size) { step ->
            val slice = values.copyOfRange(step * cells, (step + 1) * cells)
            GeoGrid.fillOutward(source, slice, FILL_CELLS)
            FloatArray(grid.size) { i -> source.sample(slice, 0, grid.lats[i / grid.cols], grid.lngs[i % grid.cols], strict = false) }
        }
        val out = FloatArray(times.size * grid.size) { Float.NaN }
        var step = 0
        for (h in times.indices) {
            val t = times[h]
            if (t < table.times.first() || t > table.times.last()) continue
            while (step + 1 < table.times.size && table.times[step + 1] <= t) step++
            val a = atPoints[step]
            val b = if (step + 1 < table.times.size && table.times[step] < t) atPoints[step + 1] else null
            val f = if (b == null) 0f else (t - table.times[step]).toFloat() / (table.times[step + 1] - table.times[step])
            val at = h * grid.size
            for (i in 0 until grid.size) {
                val va = a[i]
                val vb = b?.get(i) ?: Float.NaN
                out[at + i] = if (vb.isNaN()) va else if (va.isNaN()) vb else va + (vb - va) * f
            }
        }
        return out
    }
}
