package au.opendepth.app.tides

import android.content.Context
import android.content.SharedPreferences
import au.opendepth.app.BuildConfig
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.nav.NavGeo
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class TideMode { OFF, MODEL, MANUAL }

/** What the survey's depths are measured from. */
enum class TideDatum { MEAN_SEA_LEVEL, CHART_DATUM }

/**
 * How tides are applied. Heights are shown above chart datum (the lowest
 * tide, LAT) like a tide table: worked out from the tide model, unless
 * [chartDatumAuto] is off and [chartDatumBelowMslM] is typed in. [placeId]
 * null means "follow me": tides for where the boat is, refreshed as it travels.
 */
data class TideSettings(
    val mode: TideMode = TideMode.MODEL,
    val datum: TideDatum = TideDatum.MEAN_SEA_LEVEL,
    val chartDatumAuto: Boolean = true,
    val chartDatumBelowMslM: Double = 0.0,
    val manualHeightM: Double = 0.0,
    val showChip: Boolean = true,
    /** Add the tide to depth readouts (crosshair, navigation, alarms). */
    val correctDepths: Boolean = true,
    val placeId: String? = null,
    /** Set once the user has chosen where tides come from. */
    val locationChosen: Boolean = false
)

/** Chart datum below mean sea level, metres: the biggest tides on Earth put it about 8 m down. */
fun validChartDatum(m: Double): Boolean = m.isFinite() && m in 0.0..MAX_CHART_DATUM_M

/** A tide height above chart datum, metres: a little below it in odd weather, up to the biggest tides. */
fun validTideHeight(m: Double): Boolean = m.isFinite() && m in -2.0..MAX_TIDE_HEIGHT_M

const val MAX_CHART_DATUM_M = 10.0
const val MAX_TIDE_HEIGHT_M = 17.0

class TideSettingsRepository(private val preferences: SharedPreferences) {
    fun load(): TideSettings {
        val d = TideSettings()
        return TideSettings(
            mode = runCatching { TideMode.valueOf(preferences.getString(K_MODE, null) ?: "") }.getOrDefault(d.mode),
            datum = runCatching { TideDatum.valueOf(preferences.getString(K_DATUM, null) ?: "") }.getOrDefault(d.datum),
            chartDatumBelowMslM = preferences.getString(K_OFFSET, null)?.toDoubleOrNull()?.takeIf(::validChartDatum) ?: d.chartDatumBelowMslM,
            // In older builds the offset was always typed in; a sensible typed one is kept.
            chartDatumAuto = if (preferences.contains(K_AUTO)) {
                preferences.getBoolean(K_AUTO, true)
            } else {
                preferences.getString(K_OFFSET, null)?.toDoubleOrNull()?.takeIf { it != 0.0 && validChartDatum(it) } == null
            },
            manualHeightM = preferences.getString(K_MANUAL, null)?.toDoubleOrNull()?.takeIf(::validTideHeight) ?: d.manualHeightM,
            showChip = preferences.getBoolean(K_CHIP, d.showChip),
            correctDepths = preferences.getBoolean(K_CORRECT, d.correctDepths),
            placeId = preferences.getString(K_PLACE, null),
            locationChosen = preferences.getBoolean(K_CHOSEN, d.locationChosen)
        )
    }

    fun save(s: TideSettings) {
        preferences.edit()
            .putString(K_MODE, s.mode.name)
            .putString(K_DATUM, s.datum.name)
            .putString(K_OFFSET, s.chartDatumBelowMslM.toString())
            .putBoolean(K_AUTO, s.chartDatumAuto)
            .putString(K_MANUAL, s.manualHeightM.toString())
            .putBoolean(K_CHIP, s.showChip)
            .putBoolean(K_CORRECT, s.correctDepths)
            .putString(K_PLACE, s.placeId)
            .putBoolean(K_CHOSEN, s.locationChosen)
            .apply()
    }

    private companion object {
        const val K_MODE = "tide_mode"
        const val K_DATUM = "tide_datum"
        const val K_OFFSET = "tide_datum_offset_m"
        const val K_AUTO = "tide_datum_auto"
        const val K_MANUAL = "tide_manual_m"
        const val K_CHIP = "tide_show_chip"
        const val K_CORRECT = "tide_correct_depths"
        const val K_PLACE = "tide_place_id"
        const val K_CHOSEN = "tide_location_chosen"
    }
}

/** A downloaded tide forecast for one place. */
class TideForecast(
    val requestLat: Double,
    val requestLng: Double,
    val modelLat: Double,
    val modelLng: Double,
    val fetchedMillis: Long,
    val curve: TideCurve,
    /** End of the tides saved for offline use, or null if none are. */
    val savedUntilMillis: Long? = null,
    /** Space the place's tide files take, bytes. */
    val storedBytes: Long = 0,
    /** The lowest tide here (LAT), metres from mean sea level (negative), once worked out. */
    val lowestTideM: Double? = null
) {
    /** How far the model's nearest ocean point is from the spot asked for. */
    val modelDistanceM: Double get() = NavGeo.distanceM(requestLat, requestLng, modelLat, modelLng)
}

/** How far ahead tides are saved for offline use. */
enum class SavePeriod(val label: String, private val days: Long = 0, private val months: Long = 0) {
    WEEK("1 week", days = 7),
    TWO_WEEKS("2 weeks", days = 14),
    MONTH("1 month", months = 1),
    THREE_MONTHS("3 months", months = 3),
    SIX_MONTHS("6 months", months = 6),
    YEAR("1 year", months = 12),
    TWO_YEARS("2 years", months = 24);

    /** The end of the period, at the end of that day. */
    fun endFrom(now: java.time.ZonedDateTime): java.time.ZonedDateTime =
        now.plusDays(days).plusMonths(months).toLocalDate().plusDays(1).atStartOfDay(now.zone).minusSeconds(1)
}

/** A town or place found by name. */
data class PlaceResult(val name: String, val detail: String, val lat: Double, val lng: Double)

/**
 * Modelled sea level from the Open-Meteo marine API (free, no key), one
 * forecast per place (or "follow me"), each cached to a file so it keeps
 * working offline for the days it covers (about nine). The API picks the nearest ocean
 * cell to the spot asked for, so a town or a point on land still gets the
 * tide just offshore. This is a global model, not an official tide table.
 */
class TideStore(context: Context) {

    private val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    /** Short-range model forecasts, as downloaded. */
    private val forecasts = mutableMapOf<String, TideForecast>()

    /** Long-range tides (harmonic fits) saved for offline use. */
    private class Saved(
        val harmonics: TideHarmonics,
        val untilMillis: Long,
        val requestLat: Double,
        val requestLng: Double,
        val modelLat: Double,
        val modelLng: Double,
        val savedMillis: Long
    )
    private val saved = mutableMapOf<String, Saved>()

    /** The lowest tide (LAT) worked out for a place, metres from mean sea level. */
    private class Lowest(val metres: Double, val requestLat: Double, val requestLng: Double)
    private val lowest = mutableMapOf<String, Lowest>()

    /** Forecast and saved tides joined into one curve, rebuilt as time moves on. */
    private val merged = mutableMapOf<String, Pair<Long, TideForecast>>()

    init {
        // Before places, there was one cache file for wherever the user was.
        val legacy = File(context.filesDir, "tide_forecast.json")
        if (legacy.isFile) legacy.renameTo(File(cacheDir, "$FOLLOW_KEY.json"))
    }

    /**
     * Tides for a place: the model forecast while it lasts, then the saved
     * long-range prediction. Null when neither has been downloaded.
     */
    fun forecast(key: String): TideForecast? {
        val now = System.currentTimeMillis()
        merged[key]?.let { (builtAt, value) -> if (now - builtAt < REBUILD_MS) return value }
        val raw = forecasts[key]
        val long = saved[key]
        val result = when {
            // Saved tides that have run out are left for the user to renew or delete.
            long == null || long.untilMillis < now -> raw
            else -> {
                val from = minOf(raw?.curve?.startMillis ?: now, now - 2 * DAY_MS)
                val to = maxOf(long.untilMillis, raw?.curve?.endMillis ?: 0L)
                val start = from - from % TideCurve.HOUR_MS
                val count = ((to - start) / TideCurve.HOUR_MS).toInt() + 1
                val times = LongArray(count) { start + it * TideCurve.HOUR_MS }
                val predicted = long.harmonics.heights(start, count)
                // Where the forecast ends, ease from its last level into the prediction
                // instead of jumping (the two differ by the day's weather, often 5-15 cm).
                val rawEnd = raw?.curve?.endMillis
                val step = if (raw != null && rawEnd != null) {
                    (raw.curve.heightAt(rawEnd) ?: 0.0) - long.harmonics.heightAt(rawEnd)
                } else {
                    0.0
                }
                val heights = DoubleArray(count) { i ->
                    raw?.curve?.heightAt(times[i]) ?: run {
                        val fade = if (rawEnd == null) 0.0 else (1.0 - (times[i] - rawEnd).toDouble() / SEAM_MS).coerceIn(0.0, 1.0)
                        predicted[i] + step * fade
                    }
                }
                TideForecast(
                    requestLat = raw?.requestLat ?: long.requestLat,
                    requestLng = raw?.requestLng ?: long.requestLng,
                    modelLat = raw?.modelLat ?: long.modelLat,
                    modelLng = raw?.modelLng ?: long.modelLng,
                    fetchedMillis = raw?.fetchedMillis ?: long.savedMillis,
                    curve = TideCurve(times, heights),
                    savedUntilMillis = long.untilMillis
                )
            }
        }?.let {
            TideForecast(
                it.requestLat, it.requestLng, it.modelLat, it.modelLng, it.fetchedMillis, it.curve, it.savedUntilMillis,
                storedBytes(key), lowest[key]?.metres
            )
        }
        if (result == null) merged.remove(key) else merged[key] = now to result
        return result
    }

    /** Loads a place's downloaded and saved tides from disk. */
    suspend fun load(key: String): TideForecast? = withContext(Dispatchers.IO) {
        if (key !in forecasts) {
            val file = file(key)
            if (file.isFile) {
                runCatching { parse(file.readText(), file.lastModified()) }
                    .onFailure { DiagnosticLog.logError(TAG, "tide cache unreadable; ignoring it", it) }
                    .getOrNull()
                    ?.let { forecasts[key] = it }
            }
        }
        if (key !in saved) {
            val file = savedFile(key)
            if (file.isFile) {
                runCatching { parseSaved(file.readText()) }
                    .onFailure { DiagnosticLog.logError(TAG, "saved tides unreadable; ignoring them", it) }
                    .getOrNull()
                    ?.let { saved[key] = it }
            }
        }
        if (key !in lowest) {
            val file = lowestFile(key)
            if (file.isFile) {
                runCatching {
                    val o = JsonParser.parseString(file.readText()).asJsonObject
                    Lowest(o.get("lowest").asDouble, o.get("requestLat").asDouble, o.get("requestLng").asDouble)
                }.getOrNull()?.let { lowest[key] = it }
            }
        }
        merged.remove(key)
        forecast(key)
    }

    /**
     * Downloads the past year of tides for [lat]/[lng], fits the tidal
     * harmonics and keeps them, so the tides are known offline for [period].
     * Takes a few seconds; the saved file is a few KB whatever the period.
     */
    suspend fun saveOffline(key: String, lat: Double, lng: Double, period: SavePeriod): TideForecast = withContext(Dispatchers.IO) {
        val (harmonics, root) = fitYear(lat, lng)
        val low = lowestFrom(key, harmonics, lat, lng)
        val now = System.currentTimeMillis()
        val until = period.endFrom(java.time.ZonedDateTime.now()).toInstant().toEpochMilli()
        val entry = Saved(
            harmonics = harmonics,
            untilMillis = until,
            requestLat = lat,
            requestLng = lng,
            modelLat = root.get("latitude")?.asDouble ?: lat,
            modelLng = root.get("longitude")?.asDouble ?: lng,
            savedMillis = now
        )
        savedFile(key).writeText(savedJson(entry))
        saved[key] = entry
        lowest[key] = low
        merged.remove(key)
        forecast(key)!!
    }

    /** True when the place's lowest tide (chart datum) isn't known yet, or was worked out somewhere else. */
    fun needsLowest(key: String, lat: Double, lng: Double): Boolean {
        val l = lowest[key] ?: return true
        return NavGeo.distanceM(lat, lng, l.requestLat, l.requestLng) > REFRESH_DISTANCE_M
    }

    /**
     * Works out the lowest tide (LAT) for a place, so heights can be shown
     * above chart datum like a tide table: from its saved tides if it has
     * them, otherwise from a year of the model downloaded for it (once).
     */
    suspend fun findLowest(key: String, lat: Double, lng: Double): Double {
        val s = saved[key]?.takeIf { it.harmonics.nodal && NavGeo.distanceM(lat, lng, it.requestLat, it.requestLng) <= REFRESH_DISTANCE_M }
        val entry = withContext(Dispatchers.IO) { lowestFrom(key, s?.harmonics ?: fitYear(lat, lng).first, lat, lng) }
        lowest[key] = entry
        merged.remove(key)
        return entry.metres
    }

    /** Computes and saves the lowest tide from a place's harmonics; slow-ish, off the main thread. */
    private fun lowestFrom(key: String, harmonics: TideHarmonics, lat: Double, lng: Double): Lowest {
        val entry = Lowest(harmonics.lowestAstronomical(System.currentTimeMillis()), lat, lng)
        lowestFile(key).writeText(com.google.gson.JsonObject().apply {
            addProperty("lowest", entry.metres)
            addProperty("requestLat", lat)
            addProperty("requestLng", lng)
        }.toString())
        return entry
    }

    /** Downloads the past year of modelled tides here and fits the harmonics to it. */
    private fun fitYear(lat: Double, lng: Double): Pair<TideHarmonics, com.google.gson.JsonObject> {
        val end = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2)
        val start = end.minusDays(365)
        val url = String.format(
            Locale.US,
            "https://marine-api.open-meteo.com/v1/marine?latitude=%.4f&longitude=%.4f" +
                "&hourly=sea_level_height_msl&timeformat=unixtime&cell_selection=sea&start_date=%s&end_date=%s",
            lat, lng, start, end
        )
        val root = JsonParser.parseString(get(url, "Tide service")).asJsonObject
        val year = parseHourly(root)
        if (year.first.size < 24 * 60) throw IOException("Not enough tide history here to save tides offline.")
        return TideHarmonics.fit(year.first, year.second) to root
    }

    /** Removes the place's downloaded and saved tides. */
    fun deleteSaved(key: String) {
        forecasts.remove(key)
        saved.remove(key)
        merged.remove(key)
        file(key).delete()
        savedFile(key).delete()
    }

    fun storedBytes(key: String): Long = file(key).length() + savedFile(key).length()

    /** When a place's saved tides run out, without loading them all; null if none are saved. */
    fun savedUntil(key: String): Long? = saved[key]?.untilMillis
        ?: runCatching { JsonParser.parseString(savedFile(key).readText()).asJsonObject.get("until").asLong }.getOrNull()

    /**
     * The nearest town or suburb to a spot (OpenStreetMap Nominatim, free), to
     * suggest a name. Null offshore or without a connection.
     */
    suspend fun nearbyName(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?lat=%.5f&lon=%.5f&format=json&zoom=14", lat, lng)
        val body = client.newCall(
            Request.Builder().url(url).header("User-Agent", "SeaBathy/${BuildConfig.VERSION_NAME} (Android tide places)").build()
        ).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
        val root = JsonParser.parseString(body).asJsonObject
        // A country or state means the spot is out at sea: no useful name.
        if (root.get("addresstype")?.asString in setOf("country", "state", "region")) return@withContext null
        val address = root.getAsJsonObject("address")
        listOf("suburb", "town", "village", "city_district", "city", "hamlet", "locality", "bay", "island")
            .firstNotNullOfOrNull { address?.get(it)?.asString }
            ?: root.get("name")?.asString?.takeIf { it.isNotBlank() }
    }

    /** True when the forecast is missing, far from [lat]/[lng], old, or runs out within a day. */
    fun needsRefresh(key: String, lat: Double, lng: Double, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val f = forecasts[key] ?: return true
        val far = NavGeo.distanceM(lat, lng, f.requestLat, f.requestLng) > REFRESH_DISTANCE_M
        val old = nowMillis - f.fetchedMillis > REFRESH_AGE_MS
        val runningOut = !f.curve.covers(nowMillis + TideCurve.HOUR_MS * 24)
        return far || old || runningOut
    }

    /** Downloads a fresh forecast for [lat]/[lng] and caches it under [key]. */
    suspend fun refresh(key: String, lat: Double, lng: Double): TideForecast = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US,
            "https://marine-api.open-meteo.com/v1/marine?latitude=%.4f&longitude=%.4f" +
                "&hourly=sea_level_height_msl&timeformat=unixtime&past_days=1&forecast_days=16&cell_selection=sea",
            lat,
            lng
        )
        val body = get(url, "Tide service")
        val json = JsonParser.parseString(body).asJsonObject.apply {
            addProperty(REQUEST_LAT, lat)
            addProperty(REQUEST_LNG, lng)
        }.toString()
        val forecast = parse(json, System.currentTimeMillis())
        file(key).writeText(json)
        forecasts[key] = forecast
        merged.remove(key)
        forecast(key)!!
    }

    /** Removes everything kept for a place, its chart datum too. */
    fun forget(key: String) {
        deleteSaved(key)
        lowest.remove(key)
        lowestFile(key).delete()
    }

    /** Towns and places matching [query] (Open-Meteo geocoding, free). */
    suspend fun search(query: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "10")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()
            .toString()
        val root = JsonParser.parseString(get(url, "Place search")).asJsonObject
        val results = root.getAsJsonArray("results") ?: return@withContext emptyList()
        results.mapNotNull { element ->
            val o = element.asJsonObject
            val lat = o.get("latitude")?.asDouble ?: return@mapNotNull null
            val lng = o.get("longitude")?.asDouble ?: return@mapNotNull null
            val detail = listOfNotNull(o.get("admin1")?.asString, o.get("country")?.asString).joinToString(", ")
            PlaceResult(o.get("name")?.asString ?: "Place", detail, lat, lng)
        }
    }

    private fun get(url: String, what: String): String =
        try {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", "SeaBathy/${BuildConfig.VERSION_NAME} (Android tides)").build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("$what returned an error (HTTP ${response.code})")
                response.body?.string() ?: throw IOException("$what sent an empty reply")
            }
        } catch (error: java.net.UnknownHostException) {
            throw IOException("No internet connection. Tides already downloaded keep working offline.")
        } catch (error: java.net.SocketTimeoutException) {
            throw IOException("$what didn't answer — check the connection and try again.")
        }

    /** Hourly times (ms) and heights from a marine API reply, skipping gaps. */
    private fun parseHourly(root: com.google.gson.JsonObject): Pair<LongArray, DoubleArray> {
        val hourly = root.getAsJsonObject("hourly") ?: throw IOException("No tide data in the reply")
        val times = hourly.getAsJsonArray("time")
        val heights = hourly.getAsJsonArray("sea_level_height_msl")
        val t = ArrayList<Long>(times.size())
        val h = ArrayList<Double>(times.size())
        for (i in 0 until minOf(times.size(), heights.size())) {
            val value = heights[i]
            if (value == null || value.isJsonNull) continue
            t += times[i].asLong * 1000
            h += value.asDouble
        }
        if (t.size < 6) throw IOException("No tides here — too far from the sea. Pick a spot on the water.")
        return t.toLongArray() to h.toDoubleArray()
    }

    private fun savedJson(e: Saved): String = com.google.gson.JsonObject().apply {
        addProperty("mean", e.harmonics.mean)
        add("cos", com.google.gson.JsonArray().apply { e.harmonics.cosines.forEach { add(it) } })
        add("sin", com.google.gson.JsonArray().apply { e.harmonics.sines.forEach { add(it) } })
        addProperty("epoch", e.harmonics.epochMillis)
        addProperty("until", e.untilMillis)
        addProperty("requestLat", e.requestLat)
        addProperty("requestLng", e.requestLng)
        addProperty("modelLat", e.modelLat)
        addProperty("modelLng", e.modelLng)
        addProperty("saved", e.savedMillis)
        addProperty("constituents", TideHarmonics.CONSTITUENTS.joinToString(",") { it.name })
        addProperty("nodal", e.harmonics.nodal)
    }.toString()

    private fun parseSaved(json: String): Saved {
        val o = JsonParser.parseString(json).asJsonObject
        // Saved with a different constituent list (an older version): can't be used.
        val names = TideHarmonics.CONSTITUENTS.joinToString(",") { it.name }
        if (o.get("constituents")?.asString != names) throw IOException("saved tides from another version")
        return Saved(
            harmonics = TideHarmonics(
                mean = o.get("mean").asDouble,
                cosines = o.getAsJsonArray("cos").map { it.asDouble }.toDoubleArray(),
                sines = o.getAsJsonArray("sin").map { it.asDouble }.toDoubleArray(),
                epochMillis = o.get("epoch").asLong,
                nodal = o.get("nodal")?.asBoolean ?: false
            ),
            untilMillis = o.get("until").asLong,
            requestLat = o.get("requestLat").asDouble,
            requestLng = o.get("requestLng").asDouble,
            modelLat = o.get("modelLat").asDouble,
            modelLng = o.get("modelLng").asDouble,
            savedMillis = o.get("saved").asLong
        )
    }

    private fun parse(json: String, fetched: Long): TideForecast {
        val root = JsonParser.parseString(json).asJsonObject
        val (t, h) = parseHourly(root)
        val requestLat = root.get(REQUEST_LAT)?.asDouble ?: 0.0
        val requestLng = root.get(REQUEST_LNG)?.asDouble ?: 0.0
        return TideForecast(
            requestLat = requestLat,
            requestLng = requestLng,
            modelLat = root.get("latitude")?.asDouble ?: requestLat,
            modelLng = root.get("longitude")?.asDouble ?: requestLng,
            fetchedMillis = fetched,
            curve = TideCurve(t, h)
        )
    }

    private fun safe(key: String) = key.filter { it.isLetterOrDigit() || it == '-' }
    private fun file(key: String) = File(cacheDir, safe(key) + ".json")
    private fun savedFile(key: String) = File(cacheDir, safe(key) + ".saved.json")
    private fun lowestFile(key: String) = File(cacheDir, safe(key) + ".lat.json")

    companion object {
        /** Cache key for "follow me". */
        const val FOLLOW_KEY = "follow"
        private const val TAG = "TideStore"
        private const val CACHE_DIR = "tides"
        private const val REQUEST_LAT = "seabathy_request_lat"
        private const val REQUEST_LNG = "seabathy_request_lng"
        private const val REFRESH_DISTANCE_M = 15_000.0
        private const val REFRESH_AGE_MS = 12 * TideCurve.HOUR_MS
        private const val DAY_MS = 24 * TideCurve.HOUR_MS
        /** The joined curve starts two days back, so rebuild it now and then. */
        private const val REBUILD_MS = 6 * TideCurve.HOUR_MS
        /** How long the forecast's last level takes to ease into the saved prediction. */
        private const val SEAM_MS = 24 * TideCurve.HOUR_MS
    }
}

/** How far chart datum (LAT) is below mean sea level here: typed in, or from the tide model; null until known. */
fun TideSettings.chartDatumBelowMsl(forecast: TideForecast?): Double? =
    if (!chartDatumAuto) chartDatumBelowMslM else forecast?.lowestTideM?.let { -it }

/**
 * The tide to add to survey depths at [timeMillis]: its height above the
 * survey's datum. Null when off or unknown — and, for a chart-datum survey,
 * until chart datum is known here, so depths stay as charted (the least
 * water there normally is) rather than being guessed.
 */
fun TideSettings.heightAboveDatum(forecast: TideForecast?, timeMillis: Long): Double? = when (mode) {
    TideMode.OFF -> null
    // A tide table's height is above chart datum.
    TideMode.MANUAL -> if (datum == TideDatum.CHART_DATUM) manualHeightM else manualHeightM - (chartDatumBelowMsl(forecast) ?: 0.0)
    TideMode.MODEL -> forecast?.curve?.heightAt(timeMillis)?.let { msl ->
        if (datum == TideDatum.MEAN_SEA_LEVEL) msl else chartDatumBelowMsl(forecast)?.let { msl + it }
    }
}

/** What shown heights are measured from: true for chart datum (LAT), false for mean sea level until that is known. */
fun TideSettings.showsChartDatum(forecast: TideForecast?): Boolean = mode == TideMode.MANUAL || chartDatumBelowMsl(forecast) != null

/** Added to the model's heights (from mean sea level) to show them above chart datum. */
fun TideSettings.shownOffset(forecast: TideForecast?): Double = chartDatumBelowMsl(forecast) ?: 0.0

/** The tide as a tide table gives it at [timeMillis]: metres above chart datum (or mean sea level until known). */
fun TideSettings.shownHeight(forecast: TideForecast?, timeMillis: Long): Double? = when (mode) {
    TideMode.OFF -> null
    TideMode.MANUAL -> manualHeightM
    TideMode.MODEL -> forecast?.curve?.heightAt(timeMillis)?.let { it + shownOffset(forecast) }
}
