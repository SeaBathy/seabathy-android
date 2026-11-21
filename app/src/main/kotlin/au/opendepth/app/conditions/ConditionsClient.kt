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
 * - NOAA's ERDDAP servers, worldwide, with a backup on another server for each where
 *   one exists, as these servers do go down or slow to a crawl: daily satellite sea
 *   temperature (NASA JPL MUR, about 1 km, cloud-free; then NOAA Coral Reef Watch
 *   CoralTemp and NOAA Geo-polar Blended, about 5 km),
 *   chlorophyll (a 5-day blend from about 4 km pictures, so clouds leave few
 *   gaps), sea level anomaly from altimetry and salinity (both about 25 km).
 * - HYCOM: sea temperature below the surface, from its ocean model (about 9 km).
 * - When Open-Meteo can't be had: [BackupForecast] (NOAA GFS wind and WaveWatch III waves).
 *
 * Open-Meteo's free service counts every point of a request as a call, with limits a minute,
 * an hour and a day: every request goes through the [CallBudget], which paces them and stops
 * short of the limits.
 */
class ConditionsClient(
    private val http: (String) -> String = ::httpGet,
    private val httpBytes: (String) -> ByteArray = ::httpGetBytes,
    /** Before asking a busy server again. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /** Satellite pictures: a server that's down is given up on quickly (see [SATELLITE_READ_TIMEOUT_S]). */
    private val satelliteHttp: (String) -> String = ::httpGetQuick,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Counts the forecast service's calls. The app passes the one all its requests share: see [forApp]. */
    private val budget: CallBudget = CallBudget(clock),
    /** Asks a satellite server that was only slow once more, with far more time; none: no second try. */
    private val patientHttp: ((String) -> String)? = null
) {
    /** Satellite servers that failed just now (host → until when they're left alone). */
    private val downHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The forecast for [grid], [days] days from now; [onProgress] gets 0..1. The wind is asked
     * on [windGrid] and laid onto [grid].
     *
     * [keepSea]: a saved forecast on the same points whose sea values are still the newest there
     * are. Then only the wind is asked for, and the sea forecast's calls aren't spent: see
     * [windOver].
     */
    fun forecast(
        grid: GeoGrid, days: Int, windGrid: GeoGrid = grid, userAsked: Boolean = false,
        keepSea: ForecastGrid? = null, onProgress: (Double) -> Unit = {}
    ): ForecastGrid {
        if (keepSea != null) return windOver(keepSea, windGrid, userAsked, onProgress)
        val points = pointsOf(grid)
        val windPoints = pointsOf(windGrid)
        // All of it fits in what's left of the hour and the day (each service's and both together), or none of it is asked for.
        budget.check(mapOf(CallBudget.MARINE to points.size, CallBudget.WEATHER to windPoints.size), userAsked)
        val chunks = points.chunked(POINTS_PER_REQUEST)
        val windChunks = windPoints.chunked(POINTS_PER_REQUEST)
        val total = (chunks.size + windChunks.size).toDouble()
        // A few requests at once (the same requests, so the same use of the free service), in order.
        val done = java.util.concurrent.atomic.AtomicInteger()
        fun fetch(url: String, calls: Int): java.util.concurrent.Future<List<PointSeries>> = pool.submit<List<PointSeries>> {
            openMeteo(url, calls, ::parseOpenMeteo).also { onProgress(done.incrementAndGet() / total) }
        }
        val marineJobs = chunks.map { fetch(marineUrl(it, days), it.size) }
        val windJobs = windChunks.map { fetch(windUrl(it, days), it.size) }
        fun <T> java.util.concurrent.Future<T>.value(): T = try {
            get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
        val marine = try {
            marineJobs.flatMap { it.value() }
        } catch (e: Throwable) {
            (marineJobs + windJobs).forEach { it.cancel(true) }
            throw e
        }
        // Wind comes from another server. When it fails the sea forecast still stands, and
        // the wind is taken from the backup rather than left out.
        val weather = runCatching { windJobs.flatMap { it.value() } }.getOrNull()
        val forecast = assemble(grid, points, marine, weather, windGrid = windGrid)
        return if (weather == null) withBackupWind(forecast) else forecast
    }

    /**
     * The backup forecast for [grid], [days] days ahead of now: see [BackupForecast]. Its waves
     * or its wind may be missing (two requests, and the waves run fewer days); throws when
     * neither comes.
     */
    fun backupForecast(grid: GeoGrid, days: Int, windGrid: GeoGrid = grid, userAsked: Boolean = false): ForecastGrid {
        // The main service's own wind first. Its wind and its sea forecast are counted apart, so
        // the wind (with gusts) is often still to be had when the sea forecast is at its limit.
        // The days wanted and no more: the backup's values are never kept for a later download.
        val hours = hoursAhead(days) - KEEP_HOURS
        val mainWind = runCatching { windOnly(grid, hours, windGrid, userAsked) }.getOrNull()
        val start = clock() / HOUR_MS * HOUR_MS - PAST_HOURS * HOUR_MS
        val times = mainWind?.times ?: LongArray(hours + PAST_HOURS) { start + it * HOUR_MS }
        val fields = HashMap<ForecastField, FloatArray>()
        mainWind?.let { fields.putAll(it.fields) }
        val waves = runCatching { BackupForecast.waves(::backupHttp, grid, times) }.onSuccess { fields.putAll(it) }
        val wind = if (mainWind != null) null else runCatching { BackupForecast.wind(::backupHttp, grid, times) }.onSuccess { fields.putAll(it) }
        if (fields.isEmpty()) throw waves.exceptionOrNull() ?: wind?.exceptionOrNull() ?: IOException("The backup forecast has nothing for this area")
        return ForecastGrid(grid, times, fields)
    }

    /**
     * Fresh wind over the sea values already saved ([kept]: its waves, swell, currents and sea
     * temperature), on [kept]'s own points and to its last hour, so every hour has both.
     *
     * The wind models bring out a run every 6 hours, the wave model every 12 and the currents
     * every 24. Fetching everything at each new run downloaded the same sea forecast again two
     * times in three: 256 calls of the service whose limit is the one that's reached.
     */
    private fun windOver(kept: ForecastGrid, windGrid: GeoGrid, userAsked: Boolean, onProgress: (Double) -> Unit): ForecastGrid {
        val thisHour = clock() / HOUR_MS * HOUR_MS
        val hours = ((kept.endMillis - thisHour) / HOUR_MS).toInt() + 1
        if (hours < 2) throw IOException("The saved sea forecast has run out")
        return windOnly(kept.grid, hours.coerceAtMost(hoursAhead(MAX_DAYS)), windGrid, userAsked, onProgress).withSeaFrom(kept)
    }

    /** The main service's wind and gusts alone, on [grid], for [hours] from now. */
    private fun windOnly(grid: GeoGrid, hours: Int, windGrid: GeoGrid, userAsked: Boolean, onProgress: (Double) -> Unit = {}): ForecastGrid {
        val points = pointsOf(grid)
        val windPoints = pointsOf(windGrid)
        budget.check(windPoints.size, userAsked, CallBudget.WEATHER)
        val chunks = windPoints.chunked(POINTS_PER_REQUEST)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val jobs = chunks.map { chunk ->
            pool.submit<List<PointSeries>> {
                openMeteo(windUrl(chunk, 0, hours), chunk.size, ::parseOpenMeteo).also { onProgress(done.incrementAndGet() / chunks.size.toDouble()) }
            }
        }
        val weather = try {
            jobs.flatMap { it.get() }
        } catch (e: java.util.concurrent.ExecutionException) {
            jobs.forEach { it.cancel(true) }
            throw e.cause ?: e
        }
        // The hours, with no sea values: the wind is laid on the grid as it always is.
        val times = weather.first().times
        return assemble(grid, points, points.map { (lat, lng) -> PointSeries(lat, lng, times, emptyMap()) }, weather, windGrid = windGrid)
    }

    /**
     * Which wind model the service's "best match" is at a place: one of [WIND_MODELS], or null
     * when it's none of them or can't be asked. The service picks by region (the European model
     * round Australia, the American one round the Americas, the German one round Europe), and
     * each brings out its runs at its own times: the app watches the one in use ([latestRunMillis]).
     * One small request.
     */
    fun windModelAt(lat: Double, lng: Double): String? {
        val models = WIND_MODELS.keys.toList()
        val url = "https://api.open-meteo.com/v1/forecast?" + coords(listOf(lat to lng)) +
            "&hourly=wind_speed_10m&forecast_hours=$MODEL_PROBE_HOURS&wind_speed_unit=ms&timeformat=unixtime&cell_selection=sea&models=best_match," + models.joinToString(",")
        val hourly = openMeteo(url, 1) { JsonParser.parseString(it).asJsonObject.getAsJsonObject("hourly") } ?: return null
        val best = numbers(hourly, "wind_speed_10m_best_match", MODEL_PROBE_HOURS)
        fun apart(model: String): Double {
            val values = numbers(hourly, "wind_speed_10m_$model", MODEL_PROBE_HOURS)
            val pairs = best.indices.filter { best[it].isFinite() && values[it].isFinite() }
            return if (pairs.size < MODEL_PROBE_HOURS / 2) Double.MAX_VALUE else pairs.sumOf { kotlin.math.abs(best[it] - values[it]) } / pairs.size
        }
        return models.minByOrNull(::apart)?.takeIf { apart(it) < MODEL_SAME_MS }
    }

    /**
     * [forecast] with the backup's wind added, when the main service's wind didn't come (it has no
     * gusts). Marked [ForecastGrid.windFromBackup], so it's saved as such and the main wind is
     * asked for again before long.
     */
    private fun withBackupWind(forecast: ForecastGrid): ForecastGrid {
        val wind = runCatching { BackupForecast.wind(::backupHttp, forecast.grid, forecast.times) }.getOrNull() ?: return forecast
        return ForecastGrid(forecast.grid, forecast.times, forecast.fields + wind, windFromBackup = true)
    }

    private fun backupHttp(url: String): String = retrying { http(url) }

    /**
     * One request of [calls] points to the forecast service: counted and paced by the
     * [budget], and asked again when the connection drops, the server fails for a moment or
     * the answer comes cut short, so one bad request no longer loses the whole forecast.
     */
    private fun <T> openMeteo(url: String, calls: Int, parse: (String) -> T): T {
        val api = CallBudget.apiOf(url)
        var failures = 0
        var waitedForMinute = false
        while (true) {
            waitForRoom(calls, api)
            try {
                return parse(http(url))
            } catch (e: ServiceLimitException) {
                // "Too many requests": nothing more is sent until that limit has passed. The
                // minute's is waited out here, once; the hour's and the day's are for the caller.
                val until = budget.limited(e.kind, api)
                if (e.kind != LimitKind.MINUTE || waitedForMinute) throw LimitReachedException(until, CallBudget.text(e.kind))
                waitedForMinute = true
            } catch (e: InterruptedException) {
                throw e
            } catch (e: ServiceRefusedException) {
                throw e
            } catch (e: LimitReachedException) {
                throw e
            } catch (e: Exception) {
                // A server that doesn't answer in time gets one more try; other slips, two.
                failures += if (e is java.net.SocketTimeoutException) 2 else 1
                if (failures >= REQUEST_ATTEMPTS) throw e
                pause(REQUEST_PAUSES_MS[failures - 1])
            }
        }
    }

    /** Counts a request of [calls], first waiting for room when the minute is full. */
    private fun waitForRoom(calls: Int, api: String) {
        repeat(MINUTE_WAITS) {
            val wait = budget.reserve(calls, api)
            if (wait <= 0L) return
            pause(wait)
        }
        budget.count(calls, api)
    }

    /**
     * For each of [points]: where the marine model has sea at or right next to it, or null on
     * land. One small request (a day of wave heights).
     */
    fun seaAt(points: List<Pair<Double, Double>>): List<Pair<Double, Double>?> {
        val url = "https://marine-api.open-meteo.com/v1/marine?" + coords(points) +
            "&hourly=wave_height&forecast_days=1&timeformat=unixtime&cell_selection=sea"
        val reply = openMeteo(url, points.size) { JsonParser.parseString(it) }
        val results = if (reply.isJsonArray) reply.asJsonArray.map { it.asJsonObject } else listOf(reply.asJsonObject)
        return results.map { r ->
            val heights = r.getAsJsonObject("hourly")?.getAsJsonArray("wave_height")
            if (heights == null || heights.count { !it.isJsonNull } < 6) null
            else (r.get("latitude")?.asDouble ?: return@map null) to (r.get("longitude")?.asDouble ?: return@map null)
        }
    }

    private fun pointsOf(grid: GeoGrid) = buildList { for (r in 0 until grid.rows) for (c in 0 until grid.cols) add(grid.lats[r] to grid.lngs[c]) }

    /**
     * When the newest forecast run of the models behind the sea forecast
     * came out (Open-Meteo's small per-model status files: currents, waves
     * and the wind model in use at the place, [windModel] from [windModelAt];
     * all the wind models when that isn't known), or null when none could be read. Free: a few small files,
     * not forecast requests, so they don't count against the service's limits
     * and can be asked every few minutes. This is how the app knows whether
     * there is anything newer to download before it spends a download on it.
     */
    fun latestRunMillis(windModel: String? = null): Long? = latestRuns(windModel).newest

    /**
     * The same, with the sea models' newest run (waves and currents) on its own: that one is
     * known only when every one of their status files was read.
     */
    fun latestRuns(windModel: String? = null): RunTimes {
        val got = runStatusUrls(windModel).map { url ->
            url to pool.submit<Long?> { runCatching { parseLatestRun(http(url)) }.getOrNull() }
        }.map { (url, job) -> url to runCatching { job.get() }.getOrNull() }
        val sea = got.filter { (url, _) -> "//marine-api." in url }
        val seaRuns = sea.mapNotNull { it.second }
        return RunTimes(got.mapNotNull { it.second }.maxOrNull(), if (sea.isNotEmpty() && seaRuns.size == sea.size) seaRuns.max() else null)
    }

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
        var noData: NoDataException? = null
        var slow: String? = null
        for (url in satelliteUrls(kind, south, west, north, east)) {
            val host = hostOf(url)
            if ((downHosts[host] ?: 0L) > clock()) {
                failure = failure ?: IOException("${kind.label}: its server is down just now")
                continue
            }
            try {
                return parseErddapCsv(satelliteHttp(url), kind)
            } catch (e: NoDataException) {
                // The server works and has nothing here today: another source may.
                noData = e
            } catch (e: IOException) {
                failure = e
                // Down or not answering: leave the whole server alone for a while, so its other
                // pictures don't each wait for it too.
                if (e is ServiceBusyException || e is ServiceLimitException || e is java.net.SocketTimeoutException || e is java.net.ConnectException) {
                    downHosts[host] = clock() + HOST_DOWN_MS
                    if (e is java.net.SocketTimeoutException && slow == null) slow = url
                }
            }
        }
        // These servers have slow days, answering after 20 seconds or so: the first that was
        // only slow is asked once more with far more time (nobody is waiting on a picture).
        val again = slow
        if (again != null && patientHttp != null) {
            try {
                return parseErddapCsv(patientHttp.invoke(again), kind).also { downHosts.remove(hostOf(again)) }
            } catch (e: NoDataException) {
                noData = e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw noData ?: failure ?: IOException("No source for ${kind.label}")
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

    /** The server understood and said no (a bad request, nothing at this address): asking again won't help. */
    class ServiceRefusedException(message: String) : IOException(message)

    /** The server said "too many requests", and which of its limits that was. */
    class ServiceLimitException(val kind: LimitKind, message: String) : IOException(message)

    /** The server works but has nothing for this place or day: not its failure, and not worth asking again soon. */
    class NoDataException(message: String) : IOException(message)

    companion object {
        const val MAX_DAYS = 8
        private const val HOUR_MS = 3_600_000L
        /** Hours kept from before now. */
        const val PAST_HOURS = 3

        /**
         * [days] full days ahead of now, in hours. The service's own "days" start at midnight
         * UTC, which left "1 day" ending mid-morning in Australia and under 12 hours long for
         * half of every day.
         */
        fun hoursAhead(days: Int): Int = days.coerceIn(1, MAX_DAYS) * 24 + 1 + KEEP_HOURS

        /**
         * Hours asked for beyond the days wanted. The sea values are kept while only the wind
         * is renewed (see [windOver]), for up to the 12 hours between runs of the wave model:
         * with these, what's kept still reaches the days wanted at the end of that. They cost
         * no calls (a call is a point, for anything up to two weeks).
         */
        const val KEEP_HOURS = 12

        /** Tries of one forecast request (a timeout counts double). */
        private const val REQUEST_ATTEMPTS = 3
        private val REQUEST_PAUSES_MS = longArrayOf(1_000L, 3_000L)
        /** Waits for room in the minute before a request goes anyway. */
        private const val MINUTE_WAITS = 3

        /** The client the app uses: its forecast calls counted in the one shared [CallBudget], remembered in [prefs]. */
        fun forApp(prefs: android.content.SharedPreferences): ConditionsClient =
            ConditionsClient(budget = CallBudget.shared.rememberedIn(prefs), patientHttp = ::httpGetPatient)
        /** Forecast requests sent at once. */
        private const val PARALLEL_REQUESTS = 4
        private val pool by lazy {
            java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_REQUESTS) { r -> Thread(r, "conditions-fetch").apply { isDaemon = true } }
        }
        /** A satellite server that failed is left alone this long. */
        const val HOST_DOWN_MS = 30 * 60_000L
        /** A satellite server silent this long is taken as down (a working one starts sending within a few seconds). */
        private const val SATELLITE_READ_TIMEOUT_S = 12L
        /** The second, patient try of a slow one. */
        private const val SATELLITE_PATIENT_TIMEOUT_S = 45L

        private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')
        private const val POINTS_PER_REQUEST = 48
        const val MAX_SATELLITE_SIDE = 250
        /** Five days of pictures come back as one row per point per day. */
        private const val MAX_BLEND_SIDE = 120
        private const val BLEND_DAYS = 5
        private const val RETRY_PAUSE_MS = 2_500L

        /**
         * The wind models the service's "best match" uses, by region, and each one's status file.
         * (Measured: the full-detail European model round Australia, the American one round the
         * Americas, the German one round Europe. The coarser copies of them the app used to watch
         * come out earlier than the ones in use, so each new wind run was fetched one run late.)
         */
        val WIND_MODELS = linkedMapOf("ecmwf_ifs" to "ecmwf_ifs", "gfs_seamless" to "ncep_gfs013", "icon_seamless" to "dwd_icon")
        private const val MODEL_PROBE_HOURS = 24
        /** Wind speeds this close on average (m/s) are the same model. */
        private const val MODEL_SAME_MS = 0.1

        /** The status files to watch: the waves' and currents' models (the same everywhere), and the wind model in use. */
        fun runStatusUrls(windModel: String?): List<String> =
            listOf("meteofrance_currents", "meteofrance_wave").map { "https://marine-api.open-meteo.com/data/$it/static/meta.json" } +
                (WIND_MODELS[windModel]?.let(::listOf) ?: WIND_MODELS.values).map { "https://api.open-meteo.com/data/$it/static/meta.json" }

        /** When a model's latest run became available, from its status file. */
        fun parseLatestRun(body: String): Long? =
            JsonParser.parseString(body).asJsonObject.get("last_run_availability_time")?.asLong?.times(1000)

        private const val MARINE_VARIABLES = "ocean_current_velocity,ocean_current_direction,wave_height,wave_direction," +
            "wave_period,swell_wave_height,swell_wave_direction,swell_wave_period,sea_surface_temperature"
        private const val WIND_VARIABLES = "wind_speed_10m,wind_direction_10m,wind_gusts_10m"

        private val client by lazy {
            // A connection that's gone quiet is given up on in seconds, not left "downloading" for minutes.
            OkHttpClient.Builder().dns(au.opendepth.app.util.FallbackDns).connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
        }

        /** One GET: the body handed to [read], or the server's refusal as the error it means. */
        private fun <T> get(client: OkHttpClient, url: String, read: (okhttp3.ResponseBody?) -> T): T =
            client.newCall(Request.Builder().url(url).header("User-Agent", "SeaBathy (Android sea conditions)").build()).execute().use { response ->
                if (!response.isSuccessful) throw serviceError(url, response.code, response.body?.string().orEmpty())
                read(response.body)
            }

        fun httpGet(url: String): String = get(client, url) { it?.string().orEmpty() }

        private val quickClient by lazy {
            client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(SATELLITE_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        }

        /** As [httpGet], giving up sooner on a server that doesn't answer. */
        fun httpGetQuick(url: String): String = get(quickClient, url) { it?.string().orEmpty() }

        private val patientClient by lazy {
            client.newBuilder().readTimeout(SATELLITE_PATIENT_TIMEOUT_S, TimeUnit.SECONDS).build()
        }

        /** As [httpGet], waiting far longer for a slow server. */
        fun httpGetPatient(url: String): String = get(patientClient, url) { it?.string().orEmpty() }

        fun httpGetBytes(url: String): ByteArray = get(client, url) { it?.bytes() ?: ByteArray(0) }

        private fun serviceError(url: String, code: Int, body: String): IOException {
            val service = when {
                "open-meteo" in url -> "Open-Meteo"
                "hycom" in url -> "HYCOM"
                "pacioos" in url -> "The backup forecast service"
                else -> "NOAA"
            }
            val reason = runCatching { JsonParser.parseString(body).asJsonObject.get("reason")?.asString }.getOrNull()
                ?: Regex("message=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return when {
                code == 429 -> ServiceLimitException(CallBudget.kindOf(reason ?: body), "$service is busy (too many requests). Try again in a minute.")
                code in 500..599 -> ServiceBusyException("$service isn't answering just now ($code)")
                reason != null -> ServiceRefusedException("$service: $reason")
                else -> ServiceRefusedException("$service answered $code")
            }
        }

        private fun coords(points: List<Pair<Double, Double>>): String =
            "latitude=" + points.joinToString(",") { String.format(Locale.US, "%.4f", it.first) } +
                "&longitude=" + points.joinToString(",") { String.format(Locale.US, "%.4f", it.second) }

        fun marineUrl(points: List<Pair<Double, Double>>, days: Int): String =
            "https://marine-api.open-meteo.com/v1/marine?" + coords(points) +
                "&hourly=$MARINE_VARIABLES&wind_speed_unit=ms&timeformat=unixtime&cell_selection=sea" +
                "&forecast_hours=${hoursAhead(days)}&past_hours=$PAST_HOURS"

        /**
         * The wind over the water. Left to itself the service answers a point near the shore from
         * a cell on land, as far as 17 km off, where the wind is a good deal lighter: 10 km off the
         * beach it gave 6 knots where the model's sea cell beside the boat had 18. So the sea cells
         * are asked for, as they are for the waves. (The wind is only ever shown on the water.)
         */
        fun windUrl(points: List<Pair<Double, Double>>, days: Int, hours: Int = hoursAhead(days)): String =
            "https://api.open-meteo.com/v1/forecast?" + coords(points) +
                "&hourly=$WIND_VARIABLES&wind_speed_unit=ms&timeformat=unixtime&cell_selection=sea" +
                "&forecast_hours=$hours&past_hours=$PAST_HOURS"

        /** Where to get [kind] for the area, best first. */
        fun satelliteUrls(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double): List<String> {
            /** One griddap request. Coarse data reaches a cell beyond the area, so its picture fills the area. */
            fun url(base: String, dataset: String, variable: String, resolution: Double, time: String = "last", altitude: Boolean = false, northFirst: Boolean = false, maxSide: Int = MAX_SATELLITE_SIDE, lon360: Boolean = false): String? {
                val pad = if (resolution >= 0.04) resolution else 0.0
                val latLimit = 90 - resolution / 2
                val lngLimit = 180 - resolution / 2
                val s = (south - pad).coerceIn(-latLimit, latLimit)
                val n = (north + pad).coerceIn(-latLimit, latLimit)
                var w = (west - pad).coerceIn(-lngLimit, lngLimit)
                var e = (east + pad).coerceIn(-lngLimit, lngLimit)
                if (lon360) {
                    // This server counts longitude from 0 to 360. An area across Greenwich would
                    // take two requests there: it's left to the other sources.
                    if (w < 0 && e >= 0) return null
                    if (w < 0) {
                        w += 360
                        e += 360
                    }
                }
                val stride = max(1, ceil(max(n - s, e - w) / resolution / maxSide).toInt())
                fun f(v: Double) = String.format(Locale.US, "%.3f", v)
                val lat = if (northFirst) "(${f(n)}):$stride:(${f(s)})" else "(${f(s)}):$stride:(${f(n)})"
                return "$base/griddap/$dataset.csv?$variable%5B$time%5D" + (if (altitude) "%5B(0.0)%5D" else "") +
                    "%5B$lat%5D%5B(${f(w)}):$stride:(${f(e)})%5D"
            }
            return when (kind) {
                SatelliteKind.SEA_TEMP -> listOfNotNull(
                    url(PFEG, "jplMURSST41", "analysed_sst", 0.01),
                    url(OCEANWATCH, "CRW_sst_v3_1", "analysed_sst", 0.05, lon360 = true),
                    url(NOAA, "noaacwBLENDEDsstDNDaily", "analysed_sst", 0.05)
                )
                SatelliteKind.CHLOROPHYLL -> {
                    val days = "last-${BLEND_DAYS - 1}:1:last"
                    listOfNotNull(
                        url(NOAA, "noaacwN20VIIRSchlaDaily", "chlor_a", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE),
                        url(POLARWATCH, "noaacwN20VIIRSchlaDaily", "chlor_a", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE),
                        url(NOAA, "noaacwS3AOLCIchlaDaily", "chlor_a", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE)
                    )
                }
                SatelliteKind.SEA_LEVEL -> listOfNotNull(
                    url(NOAA, "noaacwBLENDEDsshDaily", "sla", 0.25),
                    url(POLARWATCH, "noaacwBLENDEDsshDaily", "sla", 0.25)
                )
                SatelliteKind.SALINITY -> listOfNotNull(
                    url(NOAA, "noaacwSMAPsssDaily", "sss", 0.25, altitude = true),
                    url(POLARWATCH, "noaacwSMAPsssDaily", "sss", 0.25, altitude = true),
                    url(NOAA, "noaacwSMOSsssDaily", "sss", 0.25, altitude = true)
                )
                SatelliteKind.TEMP_BELOW -> emptyList()
                // VIIRS S-NPP Kd490, 4 km, blended over days like chlorophyll as clouds leave gaps in each.
                SatelliteKind.WATER_CLARITY -> {
                    val days = "last-${BLEND_DAYS - 1}:1:last"
                    listOfNotNull(
                        url(NOAA, CLARITY_DATASET, "kd_490", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE),
                        url(POLARWATCH, CLARITY_DATASET, "kd_490", 0.0375, days, altitude = true, northFirst = true, maxSide = MAX_BLEND_SIDE)
                    )
                }
            }
        }

        private const val PFEG = "https://coastwatch.pfeg.noaa.gov/erddap"
        /** NOAA OceanWatch (Hawaii): its own copy of the 5 km sea temperature. Longitudes 0 to 360. */
        private const val OCEANWATCH = "https://oceanwatch.pifsc.noaa.gov/erddap"
        /** NOAA PolarWatch: another door to [NOAA]'s datasets, which has answered when that one refused. */
        private const val POLARWATCH = "https://polarwatch.noaa.gov/erddap"
        private const val NOAA = "https://coastwatch.noaa.gov/erddap"
        /** NOAA CoastWatch's near-real-time Kd490 (also served by PFEG as nesdisVHNkd490Daily, which redirects here). */
        private const val CLARITY_DATASET = "noaacwNPPVIIRSkd490Daily"
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
            if (!any) throw NoDataException("HYCOM has no temperature this deep here (land or shallow water)")
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
            if (lines.size < 3) throw NoDataException("NOAA sent no ${kind.label.lowercase(Locale.US)} for this area")
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
                // A server that counts 0 to 360 is brought back to degrees east.
                val lng = cells[lngCol].toDoubleOrNull()?.let { if (it > 180.0) it - 360.0 else it } ?: continue
                val value = cells[valueCol].toDoubleOrNull() ?: Double.NaN
                if (timeCol >= 0 && cells[timeCol] != lastTime) {
                    lastTime = cells[timeCol]
                    dateMillis = max(dateMillis, runCatching { java.time.Instant.parse(lastTime).toEpochMilli() }.getOrDefault(0L))
                }
                lats += lat
                lngs += lng
                rows += doubleArrayOf(lat, lng, value)
            }
            if (lats.isEmpty() || lngs.isEmpty()) throw NoDataException("NOAA sent no ${kind.label.lowercase(Locale.US)} for this area")
            val grid = GeoGrid(lats.toDoubleArray(), lngs.toDoubleArray())
            val latIndex = grid.lats.withIndex().associate { it.value to it.index }
            val lngIndex = grid.lngs.withIndex().associate { it.value to it.index }
            val sums = DoubleArray(grid.size)
            val counts = IntArray(grid.size)
            val blendLogs = kind == SatelliteKind.CHLOROPHYLL || kind == SatelliteKind.WATER_CLARITY
            for ((lat, lng, value) in rows) {
                if (!value.isFinite() || (blendLogs && value <= 0)) continue
                val at = grid.index(latIndex.getValue(lat), lngIndex.getValue(lng))
                sums[at] += if (blendLogs) kotlin.math.ln(value) else value
                counts[at]++
            }
            if (counts.all { it == 0 }) throw NoDataException("NOAA has no ${kind.label.lowercase(Locale.US)} here today (land, or no data)")
            val values = FloatArray(grid.size) { i ->
                if (counts[i] == 0) return@FloatArray Float.NaN
                val mean = sums[i] / counts[i]
                when (kind) {
                    SatelliteKind.CHLOROPHYLL, SatelliteKind.WATER_CLARITY -> kotlin.math.exp(mean).toFloat()
                    SatelliteKind.SEA_LEVEL -> (mean * 100).toFloat()
                    else -> mean.toFloat()
                }
            }
            return SatelliteGrid(kind, grid, dateMillis, values)
        }
    }
}
