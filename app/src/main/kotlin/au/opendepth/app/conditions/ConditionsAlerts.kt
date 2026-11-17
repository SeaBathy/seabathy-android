package au.opendepth.app.conditions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.opendepth.app.MainActivity
import au.opendepth.app.R
import au.opendepth.app.diagnostics.DiagnosticLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** A place the alert watches: the forecast within [radiusKm] of it, the worst of its points counting. */
data class AlertPlace(val id: String, val name: String, val lat: Double, val lng: Double, val radiusKm: Double)

/**
 * Good-weather alerts: the user picks up to [AlertRules.MAX_PLACES] places
 * (a point and a radius on the map), the most wind, gusts and waves they'll
 * go out in, and the hours and days that suit them. Every few hours Android
 * wakes a small job for a few seconds (the app itself doesn't run) that
 * fetches each place's forecast and says when a day starts, or stops,
 * looking good.
 */
data class AlertRules(
    val enabled: Boolean = false,
    val windMaxKn: Double = 12.0,
    /** 0: no limit on gusts. */
    val gustMaxKn: Double = 0.0,
    val waveMaxM: Double = 1.5,
    /** Today and up to this many days after it. */
    val daysAhead: Int = 3,
    /** Calendar.SUNDAY (1) to Calendar.SATURDAY (7). */
    val weekdays: Set<Int> = ALL_DAYS,
    /** Hours in a row the limits must hold. */
    val minHours: Int = 4,
    /** The earliest hour a good stretch may start; null: sunrise. */
    val fromHour: Int? = null,
    /** The hour it must be over by; null: sunset. */
    val toHour: Int? = null,
    /** None: the Sea conditions spot instead. */
    val places: List<AlertPlace> = emptyList(),
    /** Saving the place's forecast, so it's there when the app opens: [SAVE_ANY], [SAVE_WIFI] or [SAVE_OFF]. */
    val saveForecast: String = SAVE_ANY
) {
    companion object {
        val ALL_DAYS = (1..7).toSet()
        val WEEKENDS = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        const val SAVE_ANY = "any"
        const val SAVE_WIFI = "wifi"
        const val SAVE_OFF = "off"
        const val MAX_PLACES = 3
    }
}

/** A good stretch of a day: [from] to [to] (the end of its last hour), with the worst and calmest of it. */
data class GoodWindow(
    val day: Long, val from: Long, val to: Long, val maxWindKn: Double, val maxWaveM: Double,
    val minWindKn: Double = maxWindKn,
    val maxGustKn: Double = Double.NaN,
    /** Where the wind mostly comes from, degrees; NaN when unknown. */
    val windFromDeg: Double = Double.NaN
)

/** What changed at a place since the user was last told: days that now look good, and ones that no longer do. */
data class PlaceNews(val place: AlertPlace, val good: List<GoodWindow>, val newDays: List<GoodWindow>, val goneDays: List<Long>) {
    val hasNews: Boolean get() = newDays.isNotEmpty() || goneDays.isNotEmpty()
}

object ConditionsAlerts {

    const val KEY_RULES = "conditions_alert_rules"
    private const val KEY_SEEN = "conditions_alert_seen"
    /** The old "days told" list (one spot): cleared with the rules. */
    private const val KEY_TOLD = "conditions_alert_told"
    private const val WORK = "conditions-alerts"
    private const val CHANNEL = "conditions_alerts"
    private const val HOUR_MS = 3_600_000L
    private const val KNOTS_PER_MS = 1.943844
    /** How often the job runs. Android may run it a little later to save battery. */
    const val CHECK_HOURS = 4L
    /** No notifications from this hour of the evening to [MORNING_HOUR]: they wait for the first check after it. */
    private const val EVENING_HOUR = 21
    private const val MORNING_HOUR = 7
    /** The saved forecast's area: this far each way from the place (more if its radius is bigger). */
    private const val SAVE_RADIUS_KM = 100.0
    /** A saved forecast covering the place that's newer than this isn't downloaded again. */
    private const val FRESH_MS = 6 * HOUR_MS
    const val AREA_PREFIX = "alert-"
    private const val ALERT_ID = 4207
    private val gson = Gson()

    /** The saved rules; anything missing or unreadable falls back to the defaults (Gson leaves absent fields null). */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun load(prefs: SharedPreferences): AlertRules {
        val raw = prefs.getString(KEY_RULES, null) ?: return AlertRules()
        val r = runCatching { gson.fromJson(raw, AlertRules::class.java) }.getOrNull() ?: return AlertRules()
        val d = AlertRules()
        val days: Set<Int>? = r.weekdays
        val places: List<AlertPlace?>? = r.places
        val save: String? = r.saveForecast
        return AlertRules(
            enabled = r.enabled,
            windMaxKn = r.windMaxKn.takeIf { it > 0 } ?: d.windMaxKn,
            gustMaxKn = r.gustMaxKn.coerceAtLeast(0.0),
            waveMaxM = r.waveMaxM.takeIf { it > 0 } ?: d.waveMaxM,
            daysAhead = r.daysAhead.coerceIn(1, 7),
            weekdays = days?.filter { it in 1..7 }?.toSet()?.ifEmpty { null } ?: AlertRules.ALL_DAYS,
            minHours = r.minHours.takeIf { it > 0 } ?: d.minHours,
            fromHour = r.fromHour?.takeIf { it in 0..23 },
            toHour = r.toHour?.takeIf { it in 1..24 },
            places = places.orEmpty().filterNotNull().filter { it.id != null && it.name != null }.take(AlertRules.MAX_PLACES),
            saveForecast = save?.takeIf { it in setOf(AlertRules.SAVE_ANY, AlertRules.SAVE_WIFI, AlertRules.SAVE_OFF) } ?: d.saveForecast
        )
    }

    /**
     * Saves [rules] and (re)schedules the job. What the user has been told is forgotten, so the
     * next check reports afresh under the new rules; a removed place's saved forecast goes too.
     */
    fun save(context: Context, prefs: SharedPreferences, rules: AlertRules) {
        val before = load(prefs)
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).remove(KEY_SEEN).remove(KEY_TOLD).apply()
        val kept = rules.places.map { it.id }.toSet()
        val gone = before.places.filter { it.id !in kept }
        if (gone.isNotEmpty()) {
            val store = ConditionsStore(conditionsDir(context))
            gone.forEach { runCatching { store.delete(areaId(it)) } }
        }
        schedule(context, rules.enabled)
    }

    fun schedule(context: Context, on: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!on) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AlertWorker>(CHECK_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun conditionsDir(context: Context) = File(context.filesDir, "conditions")

    /** The saved-forecast id for [place]. */
    fun areaId(place: AlertPlace) = AREA_PREFIX + place.id

    /**
     * The good stretches in [times] (hourly), one per day at most: the
     * longest run of hours inside the user's hours (sunrise to sunset unless
     * set) with wind, gusts and waves within the limits, if it's at least
     * [AlertRules.minHours] long. The functions give NaN where there's no
     * forecast (no gusts: no gust limit). Pure, unit-tested.
     */
    fun findWindows(
        times: LongArray, windKn: (Long) -> Double, waveM: (Long) -> Double,
        rules: AlertRules, now: Long, lat: Double, lng: Double, zone: TimeZone = TimeZone.getDefault(),
        gustKn: (Long) -> Double = { Double.NaN },
        windFromDeg: (Long) -> Double = { Double.NaN }
    ): List<GoodWindow> {
        val out = mutableListOf<GoodWindow>()
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun hourOf(dayStart: Long, hour: Int): Long = Calendar.getInstance(zone).run {
            timeInMillis = dayStart
            add(Calendar.HOUR_OF_DAY, hour)
            timeInMillis
        }
        repeat(rules.daysAhead + 1) {
            val dayStart = cal.timeInMillis
            val weekday = cal.get(Calendar.DAY_OF_WEEK)
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dayEnd = cal.timeInMillis
            if (weekday !in rules.weekdays) return@repeat
            val sky = Astro.day(dayStart, lat, lng)
            val first = rules.fromHour?.let { hourOf(dayStart, it) } ?: (sky.sunrise ?: (dayStart + 6 * HOUR_MS))
            // Sunset: an hour starting before it counts. A set hour: the stretch is over by then.
            val lastStart = rules.toHour?.let { hourOf(dayStart, it) - HOUR_MS } ?: (sky.sunset ?: (dayStart + 18 * HOUR_MS))
            var best: GoodWindow? = null
            var runStart = -1L
            var runEnd = -1L
            var maxWind = 0.0
            var minWind = 0.0
            var maxWave = 0.0
            var maxGust = Double.NaN
            var dirX = 0.0
            var dirY = 0.0
            fun close() {
                if (runStart < 0) return
                val hours = ((runEnd - runStart) / HOUR_MS).toInt()
                if (hours >= rules.minHours && (best == null || runEnd - runStart > best!!.to - best!!.from)) {
                    val dir = if (dirX == 0.0 && dirY == 0.0) Double.NaN else (Math.toDegrees(atan2(dirX, dirY)) + 360) % 360
                    best = GoodWindow(dayStart, runStart, runEnd, maxWind, maxWave, minWind, maxGust, dir)
                }
                runStart = -1L
            }
            for (t in times) {
                if (t < dayStart || t >= dayEnd) continue
                // An hour counts when it's inside the hours and still to come.
                val usable = t >= first && t <= lastStart && t + HOUR_MS > now
                val wind = windKn(t)
                val wave = waveM(t)
                val gust = gustKn(t)
                val gustOk = rules.gustMaxKn <= 0 || !gust.isFinite() || gust <= rules.gustMaxKn
                val ok = usable && wind.isFinite() && wave.isFinite() && wind <= rules.windMaxKn && wave <= rules.waveMaxM && gustOk
                if (ok) {
                    if (runStart < 0) {
                        runStart = t; maxWind = wind; minWind = wind; maxWave = wave; maxGust = Double.NaN; dirX = 0.0; dirY = 0.0
                    }
                    runEnd = t + HOUR_MS
                    maxWind = max(maxWind, wind)
                    minWind = minOf(minWind, wind)
                    maxWave = max(maxWave, wave)
                    if (gust.isFinite()) maxGust = if (maxGust.isNaN()) gust else max(maxGust, gust)
                    val d = windFromDeg(t)
                    if (d.isFinite()) {
                        dirX += sin(Math.toRadians(d)) * max(wind, 0.1)
                        dirY += cos(Math.toRadians(d)) * max(wind, 0.1)
                    }
                } else {
                    close()
                }
            }
            close()
            best?.let { out += it }
        }
        return out
    }

    /** The Sea conditions spot, for an alert with no places of its own: the boat's last fix when following the GPS, else the chosen spot. */
    fun spot(prefs: SharedPreferences): Pair<Double, Double>? {
        // Same settings as ConditionsFeature's spot.
        val followGps = prefs.getBoolean("conditions_spot_gps", true)
        val raw = prefs.getString(if (followGps) "conditions_last_fix" else "conditions_spot", null) ?: return null
        val p = raw.split(',').mapNotNull { it.toDoubleOrNull() }
        return if (p.size == 2) p[0] to p[1] else null
    }

    /** The places to check: the user's, or the Sea conditions spot when there are none. */
    fun placesToCheck(rules: AlertRules, prefs: SharedPreferences): List<AlertPlace> =
        rules.places.ifEmpty { spot(prefs)?.let { (lat, lng) -> listOf(AlertPlace("spot", "Your spot", lat, lng, 0.0)) }.orEmpty() }

    /** The points checked for [place]: its middle, and with a radius the edges and corners round it too. */
    fun placeGrid(place: AlertPlace): GeoGrid {
        if (place.radiusKm < 1.0) return GeoGrid(doubleArrayOf(place.lat), doubleArrayOf(place.lng))
        val dLat = place.radiusKm / 111.0
        val dLng = place.radiusKm / (111.0 * cos(Math.toRadians(place.lat)).coerceAtLeast(0.2))
        return GeoGrid(
            doubleArrayOf(place.lat - dLat, place.lat, place.lat + dLat),
            doubleArrayOf(place.lng - dLng, place.lng, place.lng + dLng)
        )
    }

    /** The worst of [values] that are known; NaN when none is. */
    fun worst(values: DoubleArray): Double {
        var w = Double.NaN
        for (v in values) if (v.isFinite()) w = if (w.isNaN()) v else max(w, v)
        return w
    }

    /** Fetches each place's forecast (one small download each) and finds the good stretches. Off the main thread. */
    fun check(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Pair<AlertRules, List<Pair<AlertPlace, List<GoodWindow>>>>? {
        val rules = load(prefs)
        val places = placesToCheck(rules, prefs)
        if (places.isEmpty()) return null
        val client = ConditionsClient()
        var failure: Throwable? = null
        val results = places.mapNotNull { place ->
            runCatching { place to checkPlace(client, place, rules, now) }
                .onFailure { failure = it; DiagnosticLog.logError("Alerts", "forecast for ${place.name} FAILED", it) }
                .getOrNull()
        }
        if (results.isEmpty()) failure?.let { throw it }
        return rules to results
    }

    private fun checkPlace(client: ConditionsClient, place: AlertPlace, rules: AlertRules, now: Long): List<GoodWindow> {
        val grid = placeGrid(place)
        val forecast = client.forecast(grid, (rules.daysAhead + 1).coerceIn(1, ConditionsClient.MAX_DAYS))
        val points = grid.lats.flatMap { lat -> grid.lngs.map { lng -> lat to lng } }
        fun each(value: (Double, Double) -> Double) = DoubleArray(points.size) { i -> value(points[i].first, points[i].second) }
        fun windAt(lat: Double, lng: Double, t: Long): Double {
            val u = forecast.sample(ForecastField.WIND_U, lat, lng, t)
            val v = forecast.sample(ForecastField.WIND_V, lat, lng, t)
            return if (u.isNaN() || v.isNaN()) Double.NaN else hypot(u.toDouble(), v.toDouble()) * KNOTS_PER_MS
        }
        val wind = { t: Long -> worst(each { lat, lng -> windAt(lat, lng, t) }) }
        val gust = { t: Long -> worst(each { lat, lng -> forecast.sample(ForecastField.GUST, lat, lng, t).toDouble() * KNOTS_PER_MS }) }
        val wave = { t: Long -> worst(each { lat, lng -> forecast.sample(ForecastField.WAVE_HEIGHT, lat, lng, t).toDouble() }) }
        val from = { t: Long ->
            val u = forecast.sample(ForecastField.WIND_U, place.lat, place.lng, t)
            val v = forecast.sample(ForecastField.WIND_V, place.lat, place.lng, t)
            if (u.isNaN() || v.isNaN()) Double.NaN else (Math.toDegrees(atan2(-u.toDouble(), -v.toDouble())) + 360) % 360
        }
        return findWindows(forecast.times, wind, wave, rules, now, place.lat, place.lng, gustKn = gust, windFromDeg = from)
    }

    // What changed ---------------------------------------------------------------------

    private val seenType = object : TypeToken<Map<String, Map<String, List<Long>>>>() {}.type

    private fun dayKey(millis: Long, zone: TimeZone) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(millis))

    /** What the user was last told: place id → day → [from, to]. */
    private fun seen(prefs: SharedPreferences): Map<String, Map<String, List<Long>>> =
        prefs.getString(KEY_SEEN, null)?.let { runCatching { gson.fromJson<Map<String, Map<String, List<Long>>>>(it, seenType) }.getOrNull() }.orEmpty()

    /** What's new at each place since the user was last told. */
    fun news(prefs: SharedPreferences, results: List<Pair<AlertPlace, List<GoodWindow>>>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<PlaceNews> {
        val told = seen(prefs)
        val today = dayKey(now, zone)
        return results.map { (place, good) -> compare(place, good, told[place.id].orEmpty(), today, now, zone) }
    }

    /**
     * Days that now look good, and good days ([before]: day → [from, to])
     * that no longer do. Not ones that simply went by: today's counts only if
     * its stretch hadn't started. Pure, unit-tested.
     */
    fun compare(place: AlertPlace, good: List<GoodWindow>, before: Map<String, List<Long>>, today: String, now: Long, zone: TimeZone): PlaceNews {
        val keys = good.map { dayKey(it.day, zone) }.toSet()
        val fresh = good.filter { dayKey(it.day, zone) !in before }
        val gone = before.filter { (key, window) ->
            key !in keys && (key > today || (key == today && (window.firstOrNull() ?: 0L) > now))
        }.map { (_, window) -> window.firstOrNull() ?: 0L }.sorted()
        return PlaceNews(place, good, fresh, gone)
    }

    /** Remembers what the user has now been told, forgetting days gone by. */
    fun remember(prefs: SharedPreferences, results: List<Pair<AlertPlace, List<GoodWindow>>>, zone: TimeZone = TimeZone.getDefault()) {
        val map = results.associate { (place, good) -> place.id to good.associate { dayKey(it.day, zone) to listOf(it.from, it.to) } }
        prefs.edit().putString(KEY_SEEN, gson.toJson(map)).apply()
    }

    /** Evening and night: news waits for the morning. */
    fun quietHours(now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val hour = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return hour >= EVENING_HOUR || hour < MORNING_HOUR
    }

    // Saving the forecast for the app ------------------------------------------------------

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * For each place with a good day coming: downloads the forecast round it
     * (unless one saved in the last few hours already covers it), so it's on
     * the map straight away when the app opens, even offline. Each place has
     * one saved copy, replaced each time; the app removes it once its
     * forecast has run out, like any other.
     */
    fun saveForecasts(context: Context, prefs: SharedPreferences, rules: AlertRules, results: List<Pair<AlertPlace, List<GoodWindow>>>, now: Long) {
        if (rules.saveForecast == AlertRules.SAVE_OFF) return
        if (rules.saveForecast == AlertRules.SAVE_WIFI && !onUnmeteredNetwork(context)) {
            DiagnosticLog.log("Alerts", "forecast not saved: not on Wi-Fi")
            return
        }
        val store = ConditionsStore(conditionsDir(context))
        val days = prefs.getInt(ConditionsFeature.KEY_DAYS, 7).coerceIn(1, ConditionsClient.MAX_DAYS)
        val client = ConditionsClient()
        for ((place, good) in results) {
            if (good.isEmpty()) continue
            val lastGood = good.maxOf { it.to }
            val have = ConditionsPolicy.newestCovering(place.lat, place.lng, store.summaries())
            if (have != null && now - have.fetchedMillis < FRESH_MS && (have.forecastUntilMillis ?: 0L) >= lastGood) continue
            val b = ConditionsFeature.boxAround(place.lat, place.lng, max(SAVE_RADIUS_KM, place.radiusKm * 2))
            val span = max(b[2] - b[0], b[3] - b[1])
            val grid = GeoGrid.covering(b[0], b[1], b[2], b[3],
                max(ConditionsFeature.MIN_STEP_DEG, span / (ConditionsFeature.MAX_POINTS_PER_SIDE - 1)), ConditionsFeature.MAX_POINTS_PER_SIDE)
            val windGrid = GeoGrid.covering(b[0], b[1], b[2], b[3],
                max(ConditionsFeature.MIN_STEP_DEG, span / (ConditionsFeature.MAX_WIND_POINTS_PER_SIDE - 1)), ConditionsFeature.MAX_WIND_POINTS_PER_SIDE)
            runCatching {
                val forecast = client.forecast(grid, days, windGrid)
                val old = store.load(areaId(place))
                store.save(
                    ConditionsArea(
                        id = areaId(place), name = place.name, south = b[0], west = b[1], north = b[2], east = b[3],
                        fetchedMillis = System.currentTimeMillis(), forecast = forecast,
                        satellites = old?.satellites.orEmpty(),
                        belowDepthM = old?.belowDepthM ?: ConditionsArea.DEFAULT_BELOW_DEPTH_M
                    )
                )
                DiagnosticLog.log("Alerts", "saved the forecast round ${place.name}")
            }.onFailure { DiagnosticLog.logError("Alerts", "saving the forecast round ${place.name} FAILED", it) }
        }
    }

    // Telling the user ----------------------------------------------------------------

    private fun compass(deg: Double): String {
        if (!deg.isFinite()) return ""
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((((deg % 360) + 360) % 360) / 22.5 + 0.5).toInt() % 16]
    }

    /** One line for a good day: "Sat 22 Nov · 06:00–13:00 · wind 8–11 kn SE, gusts 15 · waves 0.6 m". */
    fun line(w: GoodWindow, zone: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = zone }
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }
        val lo = w.minWindKn.toInt()
        val hi = Math.round(w.maxWindKn).toInt()
        val from = compass(w.windFromDeg)
        val wind = (if (lo < hi) "$lo–$hi kn" else "$hi kn") + (if (from.isEmpty()) "" else " $from") +
            (if (w.maxGustKn.isFinite() && w.maxGustKn > w.maxWindKn * 1.15) ", gusts ${Math.round(w.maxGustKn)}" else "")
        return "${day.format(Date(w.day))} · ${clock.format(Date(w.from))}–${clock.format(Date(w.to))} · wind $wind · " +
            "waves ${String.format(Locale.US, "%.1f", w.maxWaveM)} m"
    }

    /** "Saturday", "Sat and Sun", "Fri, Sat and Sun", "4 days". */
    private fun dayNames(days: List<Long>): String {
        val sorted = days.sorted()
        val long = SimpleDateFormat("EEEE", Locale.getDefault())
        val short = SimpleDateFormat("EEE", Locale.getDefault())
        return when (sorted.size) {
            1 -> long.format(Date(sorted[0]))
            2, 3 -> sorted.dropLast(1).joinToString(", ") { short.format(Date(it)) } + " and " + short.format(Date(sorted.last()))
            else -> "${sorted.size} days"
        }
    }

    /** Posts one notification per place with news; returns how many were posted. */
    fun notifyNews(context: Context, news: List<PlaceNews>): Int {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return 0
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Good-weather alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When the forecast at your places is inside your wind and wave limits, or stops being"
        })
        var posted = 0
        for (n in news.filter { it.hasNews }) {
            val place = n.place
            val title = if (n.newDays.isNotEmpty()) {
                "${place.name}: ${dayNames(n.newDays.map { it.day })} look${if (n.newDays.size == 1) "s" else ""} good"
            } else {
                "${place.name}: ${dayNames(n.goneDays)} no longer look${if (n.goneDays.size == 1) "s" else ""} good"
            }
            val dayName = SimpleDateFormat("EEE d MMM", Locale.getDefault())
            val lines = n.good.sortedBy { it.day }.map { line(it) } + n.goneDays.map { "${dayName.format(Date(it))} · no longer looks good" }
            val where = if (place.radiusKm >= 1) "Worst of the forecast within ${place.radiusKm.toInt()} km. " else "Forecast at your spot. "
            val style = NotificationCompat.InboxStyle()
            lines.take(7).forEach { style.addLine(it) }
            style.setSummaryText(where + "Check it before you go.")
            val open = PendingIntent.getActivity(
                context, place.id.hashCode(),
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SHOW_ALERT_PLACE)
                    .putExtra(MainActivity.EXTRA_ALERT_LAT, place.lat)
                    .putExtra(MainActivity.EXTRA_ALERT_LNG, place.lng)
                    .putExtra(MainActivity.EXTRA_ALERT_RADIUS_KM, place.radiusKm)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_anchor)
                .setContentTitle(title)
                .setContentText(lines.firstOrNull().orEmpty())
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            if (runCatching { manager.notify(ALERT_ID + (place.id.hashCode() and 0xFFF), notification) }.isSuccess) posted++
        }
        return posted
    }

    class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val prefs = applicationContext.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (!load(prefs).enabled) return Result.success()
            return runCatching {
                val now = System.currentTimeMillis()
                val (rules, results) = check(prefs, now) ?: return Result.success()
                // The forecast first, so it's there when the notification is tapped.
                runCatching { saveForecasts(applicationContext, prefs, rules, results, now) }
                if (!quietHours(now)) {
                    notifyNews(applicationContext, news(prefs, results, now))
                    remember(prefs, results)
                }
                Result.success()
            }.getOrElse {
                DiagnosticLog.logError("Alerts", "check FAILED", it)
                Result.retry()
            }
        }
    }
}
