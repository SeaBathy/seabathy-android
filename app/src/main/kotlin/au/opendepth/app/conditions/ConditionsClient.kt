package au.opendepth.app.conditions

import au.opendepth.app.nav.NavGeo
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Downloads sea conditions for an area. All free, no account:
 *
 * - Open-Meteo Marine: surface currents (Météo-France SMOC, tidal flow
 *   included, about 8 km), waves, swell and sea temperature, hourly for up
 *   to 8 days. Open-Meteo forecast: wind and gusts at 10 m.
 * - NOAA CoastWatch ERDDAP, worldwide, with a backup for each where one exists
 *   as these servers do go down: daily satellite sea temperature (NASA JPL MUR,
 *   about 1 km, cloud-free; then NOAA Geo-polar Blended, about 5 km),
 *   chlorophyll (a 5-day blend from about 4 km pictures, so clouds leave few
 *   gaps), sea level anomaly from altimetry and salinity (both about 25 km).
 * - HYCOM: sea temperature below the surface, from its ocean model (about 9 km).
 */
class ConditionsClient(
    private val http: (String) -> String = ::httpGet,
    private val httpBytes: (String) -> ByteArray = ::httpGetBytes,
    /** Before asking a busy server again. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) {

    /**
     * The forecast for [grid], [days] days from today; [onProgress] gets 0..1.
     * Wind changes slowly across an area, so it is fetched on the coarser
     * [windGrid] and blended onto [grid]: fewer requests for the same detail.
     */
    fun forecast(grid: GeoGrid, days: Int, windGrid: GeoGrid = grid, onProgress: (Double) -> Unit = {}): ForecastGrid {
        val points = pointsOf(grid)
        val windPoints = pointsOf(windGrid)
        val chunks = points.chunked(POINTS_PER_REQUEST)
        val windChunks = windPoints.chunked(POINTS_PER_REQUEST)
        val total = (chunks.size + windChunks.size).toDouble()
        val marine = ArrayList<PointSeries>(points.size)
        chunks.forEachIndexed { i, chunk ->
            marine += parseOpenMeteo(http(marineUrl(chunk, days)))
            onProgress((i + 1) / total)
        }
        // Wind is a separate service; a forecast without it still has the sea.
        val weather = runCatching {
            windChunks.flatMapIndexed { i, chunk ->
                parseOpenMeteo(http(windUrl(chunk, days))).also { onProgress((chunks.size + i + 1) / total) }
            }
        }.getOrNull()
        return assemble(grid, points, marine, weather, windGrid = windGrid)
    }

    private fun pointsOf(grid: GeoGrid) = buildList { for (r in 0 until grid.rows) for (c in 0 until grid.cols) add(grid.lats[r] to grid.lngs[c]) }

    /**
     * When the newest forecast run of the models behind the sea forecast
     * came out (Open-Meteo's small per-model status files: currents, waves
     * and wind), or null when none could be read. Cheap: a few small files,
     * not forecast requests, so it can be asked every few minutes.
     */
    fun latestRunMillis(): Long? = RUN_STATUS_URLS.mapNotNull { url ->
        runCatching { parseLatestRun(http(url)) }.getOrNull()
    }.maxOrNull()

    /**
     * The latest picture of [kind] over the area, at most [MAX_SATELLITE_SIDE]
     * points a side: from the first of its sources that has it, asking a busy
     * server once more before moving on. [belowDepthM] is for [SatelliteKind.TEMP_BELOW].
     */
    fun satellite(
        kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double,
        belowDepthM: Int = ConditionsArea.DEFAULT_BELOW_DEPTH_M
    ): SatelliteGrid {
        if (kind == SatelliteKind.TEMP_BELOW) {
            return retrying(HYCOM_ATTEMPTS) { parseHycom(httpBytes(hycomUrl(south, west, north, east, belowDepthM)), kind) }
        }
        var failure: Exception? = null
        for (url in satelliteUrls(kind, south, west, north, east)) {
            try {
                return retrying { parseErddapCsv(http(url), kind) }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("No source for ${kind.label}")
    }

    private fun <T> retrying(attempts: Int = 2, fetch: () -> T): T {
        repeat(attempts - 1) {
            try {
                return fetch()
            } catch (e: ServiceBusyException) {
                pause(RETRY_PAUSE_MS)
            }
        }
        return fetch()
    }

    /** The server is overloaded or down for now: worth asking again. */
    class ServiceBusyException(message: String) : IOException(message)

    companion object {
        const val MAX_DAYS = 8
        private const val POINTS_PER_REQUEST = 48
        const val MAX_SATELLITE_SIDE = 250
        /** Five days of pictures come back as one row per point per day. */
        private const val MAX_BLEND_SIDE = 120
        private const val BLEND_DAYS = 5
        private const val RETRY_PAUSE_MS = 2_500L

        /** Status files of the models the sea forecast comes from (currents daily, waves and wind every 6–12 hours). */
        private val RUN_STATUS_URLS = listOf(
            "https://marine-api.open-meteo.com/data/meteofrance_currents/static/meta.json",
            "https://marine-api.open-meteo.com/data/meteofrance_wave/static/meta.json",
            "https://marine-api.open-meteo.com/data/ecmwf_wam025/static/meta.json",
            "https://api.open-meteo.com/data/ecmwf_ifs025/static/meta.json",
            "https://api.open-meteo.com/data/ncep_gfs025/static/meta.json"
        )

        /** When a model's latest run became available, from its status file. */
        fun parseLatestRun(body: String): Long? =
            JsonParser.parseString(body).asJsonObject.get("last_run_availability_time")?.asLong?.times(1000)

        private const val MARINE_VARIABLES = "ocean_current_velocity,ocean_current_direction,wave_height,wave_direction," +
            "wave_period,swell_wave_height,swell_wave_direction,swell_wave_period,sea_surface_temperature"
        private const val WIND_VARIABLES = "wind_speed_10m,wind_direction_10m,wind_gusts_10m"

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }

        fun httpGet(url: String): String =
            client.newCall(Request.Builder().url(url).header("User-Agent", "SeaBathy (Android sea conditions)").build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw serviceError(url, response.code, body)
                body
            }

        fun httpGetBytes(url: String): ByteArray =
            client.newCall(Request.Builder().url(url).header("User-Agent", "SeaBathy (Android sea conditions)").build()).execute().use { response ->
                if (!response.isSuccessful) throw serviceError(url, response.code, response.body?.string().orEmpty())
                response.body?.bytes() ?: ByteArray(0)
            }

        private fun serviceError(url: String, code: Int, body: String): IOException {
            val service = when {
                "open-meteo" in url -> "Open-Meteo"
                "hycom" in url -> "HYCOM"
                else -> "NOAA"
            }
            val reason = runCatching { JsonParser.parseString(body).asJsonObject.get("reason")?.asString }.getOrNull()
                ?: Regex("message=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return when {
                code == 429 -> IOException("$service is busy (too many requests). Try again in a minute.")
                code in 500..599 -> ServiceBusyException("$service isn't answering just now ($code)")
                reason != null -> IOException("$service: $reason")
                else -> IOException("$service answered $code")
            }
        }

        private fun coords(points: List<Pair<Double, Double>>): String =
            "latitude=" + points.joinToString(",") { String.format(Locale.US, "%.4f", it.first) } +
                "&longitude=" + points.joinToString(",") { String.format(Locale.US, "%.4f", it.second) }

        fun marineUrl(points: List<Pair<Double, Double>>, days: Int): String =
            "https://marine-api.open-meteo.com/v1/marine?" + coords(points) +
                "&hourly=$MARINE_VARIABLES&wind_speed_unit=ms&timeformat=unixtime&cell_selection=sea" +
                "&forecast_days=${days.coerceIn(1, MAX_DAYS)}"

        fun windUrl(points: List<Pair<Double, Double>>, days: Int): String =
            "https://api.open-meteo.com/v1/forecast?" + coords(points) +
                "&hourly=$WIND_VARIABLES&wind_speed_unit=ms&timeformat=unixtime" +
                "&forecast_days=${days.coerceIn(1, MAX_DAYS)}"

        /** Where to get [kind] for the area, best first. */
        fun satelliteUrls(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double): List<String> {
            /** One griddap request. Coarse data reaches a cell beyond the area, so its picture fills the area. */
            fun url(base: String, dataset: String, variable: String, resolution: Double, time: String = "last", altitude: Boolean = false, northFirst: Boolean = false, maxSide: Int = MAX_SATELLITE_SIDE): String {
                val pad = if (resolution >= 0.04) resolution else 0.0
                val latLimit = 90 - resolution / 2
                val lngLimit = 180 - resolution / 2
                val s = (south - pad).coerceIn(-latLimit, latLimit)
                val n = (north + pad).coerceIn(-latLimit, latLimit)
                val w = (west - pad).coerceIn(-lngLimit, lngLimit)
                val e = (east + pad).coerceIn(-lngLimit, lngLimit)
                val stride = max(1, ceil(max(n - s, e - w) / resolution / maxSide).toInt())
                fun f(v: Double) = String.format(Locale.US, "%.3f", v)
                val lat = if (northFirst) "(${f(n)}):$stride:(${f(s)})" else "(${f(s)}):$stride:(${f(n)})"
                return "$base/griddap/$dataset.csv?$variable%5B$time%5D" + (if (altitude) "%5B(0.0)%5D" else "") +
                    "%5B$lat%5D%5B(${f(w)}):$stride:(${f(e)})%5D"
            }
            return when (kind) {
                SatelliteKind.SEA_TEMP -> listOf(
                    url(PFEG, "jplMURSST41", "analysed_sst", 0.01),
                    url(UPWELL, "jplMURSST41", "analysed_sst", 0.01),
                    url(NOAA, "noaacwBLENDEDsstDNDaily", "analysed_sst", 0.05)
                )
                SatelliteKind.CHLOROPHYLL -> {
                    val days = "last-${BLEND_DAYS - 1}:1:last"
                    listOf(
                        url(NOAA, "noaacwN20VIIRSchlaDaily", "chlor_a", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE),
                        url(PFEG, "nesdisVHNnoaa20chlaDaily", "chlor_a", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE),
                        url(NOAA, "noaacwS3AOLCIchlaDaily", "chlor_a", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE)
                    )
                }
                SatelliteKind.SEA_LEVEL -> listOf(url(NOAA, "noaacwBLENDEDsshDaily", "sla", 0.25))
                SatelliteKind.SALINITY -> listOf(
                    url(NOAA, "noaacwSMAPsssDaily", "sss", 0.25, altitude = true),
                    url(NOAA, "noaacwSMOSsssDaily", "sss", 0.25, altitude = true)
                )
                SatelliteKind.TEMP_BELOW -> emptyList()
            }
        }

        private const val PFEG = "https://coastwatch.pfeg.noaa.gov/erddap"
        /** The same data as [PFEG], on another server. */
        private const val UPWELL = "https://upwell.pfeg.noaa.gov/erddap"
        private const val NOAA = "https://coastwatch.noaa.gov/erddap"
        private const val HYCOM = "https://ncss.hycom.org/thredds/ncss/FMRC_ESPC-D-V02_t3z/FMRC_ESPC-D-V02_t3z_best.ncd"
        private const val HYCOM_STEP = 0.08
        /** HYCOM's server often fails at random for a moment ("No space left on device"), and has no backup. */
        private const val HYCOM_ATTEMPTS = 5

        /** HYCOM's temperature [depthM] metres down at the moment, over the area. */
        fun hycomUrl(south: Double, west: Double, north: Double, east: Double, depthM: Int): String {
            fun f(v: Double) = String.format(Locale.US, "%.3f", v)
            val pad = HYCOM_STEP
            val stride = max(1, ceil(max(north - south, east - west) / HYCOM_STEP / MAX_SATELLITE_SIDE).toInt())
            return "$HYCOM?var=water_temp&south=${f((south - pad).coerceAtLeast(-80.0))}&north=${f((north + pad).coerceAtMost(89.9))}" +
                "&west=${f((west - pad).coerceAtLeast(-180.0))}&east=${f((east + pad).coerceAtMost(180.0))}" +
                "&vertCoord=$depthM&time=present&horizStride=$stride&accept=netcdf"
        }

        /** HYCOM's NetCDF answer as a picture. Its longitudes may run 0 to 360. */
        fun parseHycom(bytes: ByteArray, kind: SatelliteKind): SatelliteGrid {
            val file = NetCdf3.read(bytes)
            val temperature = file["water_temp"] ?: throw IOException("HYCOM sent no temperature")
            val latAxis = file["lat"]?.values ?: throw IOException("HYCOM sent no latitudes")
            val rawLngs = file["lon"]?.values ?: throw IOException("HYCOM sent no longitudes")
            // West of the date line it counts on past 180, starting from 180 itself.
            val pastDateLine = rawLngs.any { it > 180 }
            val lngAxis = rawLngs.map { if (it > 180 || (pastDateLine && it == 180.0)) it - 360 else it }
            val rows = latAxis.size
            val cols = lngAxis.size
            if (temperature.values.size != rows * cols) throw IOException("HYCOM sent an unexpected table")
            val rowOrder = latAxis.indices.sortedBy { latAxis[it] }
            val colOrder = lngAxis.indices.sortedBy { lngAxis[it] }
            val grid = GeoGrid(DoubleArray(rows) { latAxis[rowOrder[it]] }, DoubleArray(cols) { lngAxis[colOrder[it]] })
            val fill = temperature.number("_FillValue") ?: temperature.number("missing_value")
            val scale = temperature.number("scale_factor") ?: 1.0
            val offset = temperature.number("add_offset") ?: 0.0
            var any = false
            val values = FloatArray(grid.size) { Float.NaN }
            for (r in 0 until rows) for (c in 0 until cols) {
                val raw = temperature.values[rowOrder[r] * cols + colOrder[c]]
                if (raw.isNaN() || raw == fill) continue
                values[grid.index(r, c)] = (raw * scale + offset).toFloat()
                any = true
            }
            if (!any) throw IOException("HYCOM has no temperature this deep here (land or shallow water)")
            return SatelliteGrid(kind, grid, hycomTime(file["time"]), values)
        }

        /** "hours since 2000-01-01 12:00:00" and a number, as a date. */
        private fun hycomTime(time: NetCdf3.Variable?): Long {
            val hours = time?.values?.firstOrNull() ?: return 0L
            val base = Regex("""hours since (\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})""").find(time.text("units").orEmpty()) ?: return 0L
            return runCatching {
                java.time.Instant.parse("${base.groupValues[1]}T${base.groupValues[2]}Z").toEpochMilli() + (hours * 3_600_000).toLong()
            }.getOrDefault(0L)
        }

        /** One forecast point's hourly series, as Open-Meteo answered it. */
        class PointSeries(val lat: Double, val lng: Double, val times: LongArray, val values: Map<String, DoubleArray>)

        /** An Open-Meteo answer: one location is an object, several an array. */
        fun parseOpenMeteo(body: String): List<PointSeries> {
            val root = JsonParser.parseString(body)
            val items: List<JsonElement> = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
            return items.map { item ->
                val o = item.asJsonObject
                if (o.get("error")?.asBoolean == true) throw IOException("Open-Meteo: ${o.get("reason")?.asString}")
                val hourly = o.getAsJsonObject("hourly") ?: throw IOException("Open-Meteo sent no hourly data")
                val times = hourly.getAsJsonArray("time").map { it.asLong * 1000 }.toLongArray()
                val values = hourly.keySet().filter { it != "time" }.associateWith { key -> numbers(hourly, key, times.size) }
                PointSeries(o.get("latitude").asDouble, o.get("longitude").asDouble, times, values)
            }
        }

        private fun numbers(o: JsonObject, key: String, size: Int): DoubleArray {
            val array = o.getAsJsonArray(key) ?: return DoubleArray(size) { Double.NaN }
            return DoubleArray(size) { i ->
                val e = array.takeIf { i < it.size() }?.get(i)
                if (e == null || e.isJsonNull) Double.NaN else e.asDouble
            }
        }

        /**
         * Puts the points' series onto the grid. Open-Meteo moves a point to
         * the nearest cell of its ocean model; a point moved further than
         * [reachKm] is inland, and its sea values are left blank. Wind is
         * kept everywhere: it blows over land too.
         */
        fun assemble(
            grid: GeoGrid,
            requested: List<Pair<Double, Double>>,
            marine: List<PointSeries>,
            weather: List<PointSeries>?,
            reachKm: Double = SEA_REACH_KM,
            /** The grid [weather] is on, when not [grid]. */
            windGrid: GeoGrid = grid
        ): ForecastGrid {
            if (marine.size != requested.size) throw IOException("Open-Meteo sent ${marine.size} of ${requested.size} points")
            val times = marine.first().times
            val hours = times.size
            val fields = HashMap<ForecastField, FloatArray>()
            fun field(f: ForecastField) = fields.getOrPut(f) { FloatArray(hours * grid.size) { Float.NaN } }
            val sea = BooleanArray(requested.size) { i ->
                val (lat, lng) = requested[i]
                NavGeo.distanceM(lat, lng, marine[i].lat, marine[i].lng) <= reachKm * 1000
            }
            for (i in requested.indices) {
                if (!sea[i]) continue
                val p = marine[i]
                val byTime = if (p.times.contentEquals(times)) null else p.times.withIndex().associate { it.value to it.index }
                for (h in 0 until hours) {
                    val k = if (byTime == null) h else byTime[times[h]] ?: continue
                    val at = h * grid.size + i
                    vector(p, "ocean_current_velocity", "ocean_current_direction", k, towards = true)?.let { (u, v) ->
                        field(ForecastField.CURRENT_U)[at] = u
                        field(ForecastField.CURRENT_V)[at] = v
                    }
                    wave(p, "wave_height", "wave_direction", "wave_period", k, at, ForecastField.WAVE_HEIGHT, ForecastField.WAVE_U, ForecastField.WAVE_V, ForecastField.WAVE_PERIOD, ::field)
                    wave(p, "swell_wave_height", "swell_wave_direction", "swell_wave_period", k, at, ForecastField.SWELL_HEIGHT, ForecastField.SWELL_U, ForecastField.SWELL_V, ForecastField.SWELL_PERIOD, ::field)
                    p.values["sea_surface_temperature"]?.get(k)?.takeIf { it.isFinite() }?.let { field(ForecastField.SEA_TEMP)[at] = it.toFloat() }
                }
            }
            if (weather != null && weather.size == windGrid.size) {
                // Wind on its own grid first, then onto the sea points.
                val windU = FloatArray(hours * windGrid.size) { Float.NaN }
                val windV = FloatArray(hours * windGrid.size) { Float.NaN }
                val gust = FloatArray(hours * windGrid.size) { Float.NaN }
                for (i in 0 until windGrid.size) {
                    val p = weather[i]
                    val byTime = p.times.withIndex().associate { it.value to it.index }
                    for (h in 0 until hours) {
                        val k = byTime[times[h]] ?: continue
                        val at = h * windGrid.size + i
                        vector(p, "wind_speed_10m", "wind_direction_10m", k, towards = false)?.let { (u, v) ->
                            windU[at] = u
                            windV[at] = v
                        }
                        p.values["wind_gusts_10m"]?.get(k)?.takeIf { it.isFinite() }?.let { gust[at] = it.toFloat() }
                    }
                }
                for (i in requested.indices) {
                    val (lat, lng) = requested[i]
                    for (h in 0 until hours) {
                        val at = h * grid.size + i
                        val offset = h * windGrid.size
                        val u = windGrid.sample(windU, offset, lat, lng, strict = false)
                        val v = windGrid.sample(windV, offset, lat, lng, strict = false)
                        if (!u.isNaN() && !v.isNaN()) {
                            field(ForecastField.WIND_U)[at] = u
                            field(ForecastField.WIND_V)[at] = v
                        }
                        windGrid.sample(gust, offset, lat, lng, strict = false).takeIf { !it.isNaN() }?.let { field(ForecastField.GUST)[at] = it }
                    }
                }
            }
            if (fields.isEmpty()) throw IOException("There's no sea forecast for this area (all land?)")
            return ForecastGrid(grid, times, fields)
        }

        /**
         * How far a point may be from the nearest ocean-model cell and still
         * take its values. The models' coastline is coarse (cells about 9 km):
         * water by the shore, round islands and in channels is often 8–16 km
         * from the nearest cell (beside inshore islands and in narrow
         * channels, for instance). Where a satellite coastline is
         * saved it hides the land; points further out are inland.
         */
        const val SEA_REACH_KM = 20.0

        private fun vector(p: PointSeries, speedKey: String, directionKey: String, k: Int, towards: Boolean): Pair<Float, Float>? {
            val speed = p.values[speedKey]?.get(k)?.takeIf { it.isFinite() } ?: return null
            val direction = p.values[directionKey]?.get(k)?.takeIf { it.isFinite() } ?: return null
            val (u, v) = Flow.parts(speed, if (towards) direction else direction + 180)
            return u.toFloat() to v.toFloat()
        }

        private fun wave(
            p: PointSeries, heightKey: String, directionKey: String, periodKey: String, k: Int, at: Int,
            height: ForecastField, u: ForecastField, v: ForecastField, period: ForecastField,
            field: (ForecastField) -> FloatArray
        ) {
            val h = p.values[heightKey]?.get(k)?.takeIf { it.isFinite() } ?: return
            field(height)[at] = h.toFloat()
            vector(p, heightKey, directionKey, k, towards = false)?.let { (east, north) ->
                // A unit direction: the height is blended on its own.
                val length = kotlin.math.hypot(east, north).takeIf { it > 0f } ?: return@let
                field(u)[at] = east / length
                field(v)[at] = north / length
            }
            p.values[periodKey]?.get(k)?.takeIf { it.isFinite() }?.let { field(period)[at] = it.toFloat() }
        }

        /**
         * An ERDDAP griddap CSV (a names row, a units row, then
         * time,[altitude,]lat,lon,value rows). Several days of a point are
         * blended, as chlorophyll varies by orders of magnitude on a log scale;
         * the date given is the newest. Sea level comes in metres and is kept in cm.
         */
        fun parseErddapCsv(body: String, kind: SatelliteKind): SatelliteGrid {
            val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size < 3) throw IOException("NOAA sent no ${kind.label.lowercase(Locale.US)} for this area")
            val names = lines[0].split(',')
            val latCol = names.indexOf("latitude")
            val lngCol = names.indexOf("longitude")
            val timeCol = names.indexOf("time")
            val valueCol = names.size - 1
            if (latCol < 0 || lngCol < 0) throw IOException("NOAA sent an unexpected table")
            val lats = sortedSetOf<Double>()
            val lngs = sortedSetOf<Double>()
            val rows = ArrayList<DoubleArray>(lines.size)
            var dateMillis = 0L
            var lastTime = ""
            for (line in lines.drop(2)) {
                val cells = line.split(',')
                if (cells.size <= valueCol) continue
                val lat = cells[latCol].toDoubleOrNull() ?: continue
                val lng = cells[lngCol].toDoubleOrNull() ?: continue
                val value = cells[valueCol].toDoubleOrNull() ?: Double.NaN
                if (timeCol >= 0 && cells[timeCol] != lastTime) {
                    lastTime = cells[timeCol]
                    dateMillis = max(dateMillis, runCatching { java.time.Instant.parse(lastTime).toEpochMilli() }.getOrDefault(0L))
                }
                lats += lat
                lngs += lng
                rows += doubleArrayOf(lat, lng, value)
            }
            if (lats.isEmpty() || lngs.isEmpty()) throw IOException("NOAA sent no ${kind.label.lowercase(Locale.US)} for this area")
            val grid = GeoGrid(lats.toDoubleArray(), lngs.toDoubleArray())
            val latIndex = grid.lats.withIndex().associate { it.value to it.index }
            val lngIndex = grid.lngs.withIndex().associate { it.value to it.index }
            val sums = DoubleArray(grid.size)
            val counts = IntArray(grid.size)
            val blendLogs = kind == SatelliteKind.CHLOROPHYLL
            for ((lat, lng, value) in rows) {
                if (!value.isFinite() || (blendLogs && value <= 0)) continue
                val at = grid.index(latIndex.getValue(lat), lngIndex.getValue(lng))
                sums[at] += if (blendLogs) kotlin.math.ln(value) else value
                counts[at]++
            }
            if (counts.all { it == 0 }) throw IOException("NOAA has no ${kind.label.lowercase(Locale.US)} here today (land, or no data)")
            val values = FloatArray(grid.size) { i ->
                if (counts[i] == 0) return@FloatArray Float.NaN
                val mean = sums[i] / counts[i]
                when (kind) {
                    SatelliteKind.CHLOROPHYLL -> kotlin.math.exp(mean).toFloat()
                    SatelliteKind.SEA_LEVEL -> (mean * 100).toFloat()
                    else -> mean.toFloat()
                }
            }
            return SatelliteGrid(kind, grid, dateMillis, values)
        }
    }
}
