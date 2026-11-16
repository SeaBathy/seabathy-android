package au.opendepth.app.conditions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Good-weather alerts: the user sets the most wind and waves they'll go out
 * in, how far ahead to look and on which days; a few times a day the app
 * fetches the forecast for the spot (one point: two small requests) and
 * says when a day has enough daylight hours inside those limits. Each day
 * is reported once.
 */
data class AlertRules(
    val enabled: Boolean = false,
    val windMaxKn: Double = 12.0,
    val waveMaxM: Double = 1.5,
    /** Today and up to this many days after it. */
    val daysAhead: Int = 3,
    /** Calendar.SUNDAY (1) to Calendar.SATURDAY (7). */
    val weekdays: Set<Int> = ALL_DAYS,
    /** Hours in a row, in daylight, the limits must hold. */
    val minHours: Int = 4
) {
    companion object {
        val ALL_DAYS = (1..7).toSet()
        val WEEKENDS = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
    }
}

/** A good stretch of a day: [from] to [to] (the end of its last hour). */
data class GoodWindow(val day: Long, val from: Long, val to: Long, val maxWindKn: Double, val maxWaveM: Double)

object ConditionsAlerts {

    const val KEY_RULES = "conditions_alert_rules"
    private const val KEY_TOLD = "conditions_alert_told"
    private const val WORK = "conditions-alerts"
    private const val CHANNEL = "conditions_alerts"
    private const val HOUR_MS = 3_600_000L
    private const val KNOTS_PER_MS = 1.943844
    private val gson = Gson()

    /** The saved rules; anything missing or unreadable falls back to the defaults (Gson leaves absent fields null). */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun load(prefs: SharedPreferences): AlertRules {
        val raw = prefs.getString(KEY_RULES, null) ?: return AlertRules()
        val r = runCatching { gson.fromJson(raw, AlertRules::class.java) }.getOrNull() ?: return AlertRules()
        val d = AlertRules()
        val days: Set<Int>? = r.weekdays
        return AlertRules(
            enabled = r.enabled,
            windMaxKn = r.windMaxKn.takeIf { it > 0 } ?: d.windMaxKn,
            waveMaxM = r.waveMaxM.takeIf { it > 0 } ?: d.waveMaxM,
            daysAhead = r.daysAhead.coerceIn(1, 7),
            weekdays = days?.filter { it in 1..7 }?.toSet()?.ifEmpty { null } ?: AlertRules.ALL_DAYS,
            minHours = r.minHours.takeIf { it > 0 } ?: d.minHours
        )
    }

    fun save(context: Context, prefs: SharedPreferences, rules: AlertRules) {
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).remove(KEY_TOLD).apply()
        schedule(context, rules.enabled)
    }

    fun schedule(context: Context, on: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!on) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AlertWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * The good stretches in [times] (hourly), one per day at most: the
     * longest run of daylight hours with wind ≤ the limit and waves ≤ the
     * limit, if it's at least [AlertRules.minHours] long. [windKn] and
     * [waveM] give NaN where there's no forecast. Pure, unit-tested.
     */
    fun findWindows(
        times: LongArray, windKn: (Long) -> Double, waveM: (Long) -> Double,
        rules: AlertRules, now: Long, lat: Double, lng: Double, zone: TimeZone = TimeZone.getDefault()
    ): List<GoodWindow> {
        val out = mutableListOf<GoodWindow>()
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(rules.daysAhead + 1) {
            val dayStart = cal.timeInMillis
            val weekday = cal.get(Calendar.DAY_OF_WEEK)
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dayEnd = cal.timeInMillis
            if (weekday !in rules.weekdays) return@repeat
            val sky = Astro.day(dayStart, lat, lng)
            val light = (sky.sunrise ?: (dayStart + 6 * HOUR_MS))..(sky.sunset ?: (dayStart + 18 * HOUR_MS))
            var best: GoodWindow? = null
            var runStart = -1L
            var runEnd = -1L
            var runWind = 0.0
            var runWave = 0.0
            fun close() {
                if (runStart < 0) return
                val hours = ((runEnd - runStart) / HOUR_MS).toInt()
                if (hours >= rules.minHours && (best == null || runEnd - runStart > best!!.to - best!!.from)) {
                    best = GoodWindow(dayStart, runStart, runEnd, runWind, runWave)
                }
                runStart = -1L
            }
            for (t in times) {
                if (t < dayStart || t >= dayEnd) continue
                // An hour counts when it starts in daylight and is still to come.
                val usable = t in light && t + HOUR_MS > now
                val wind = windKn(t)
                val wave = waveM(t)
                val ok = usable && wind.isFinite() && wave.isFinite() && wind <= rules.windMaxKn && wave <= rules.waveMaxM
                if (ok) {
                    if (runStart < 0) { runStart = t; runWind = 0.0; runWave = 0.0 }
                    runEnd = t + HOUR_MS
                    runWind = maxOf(runWind, wind)
                    runWave = maxOf(runWave, wave)
                } else {
                    close()
                }
            }
            close()
            best?.let { out += it }
        }
        return out
    }

    /** The spot the alerts are for: the boat's last fix when following the GPS, else the chosen spot. */
    fun spot(prefs: SharedPreferences): Pair<Double, Double>? {
        // Same settings as ConditionsFeature's spot.
        val followGps = prefs.getBoolean("conditions_spot_gps", true)
        val raw = prefs.getString(if (followGps) "conditions_last_fix" else "conditions_spot", null) ?: return null
        val p = raw.split(',').mapNotNull { it.toDoubleOrNull() }
        return if (p.size == 2) p[0] to p[1] else null
    }

    /** Fetches the forecast for the spot and finds the good stretches. Off the main thread. */
    fun check(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Pair<AlertRules, List<GoodWindow>>? {
        val rules = load(prefs)
        val (lat, lng) = spot(prefs) ?: return null
        val grid = GeoGrid(doubleArrayOf(lat), doubleArrayOf(lng))
        val forecast = ConditionsClient().forecast(grid, (rules.daysAhead + 1).coerceIn(1, ConditionsClient.MAX_DAYS))
        fun wind(t: Long): Double {
            val u = forecast.sample(ForecastField.WIND_U, lat, lng, t)
            val v = forecast.sample(ForecastField.WIND_V, lat, lng, t)
            return if (u.isNaN() || v.isNaN()) Double.NaN else hypot(u.toDouble(), v.toDouble()) * KNOTS_PER_MS
        }
        fun wave(t: Long) = forecast.sample(ForecastField.WAVE_HEIGHT, lat, lng, t).toDouble()
        return rules to findWindows(forecast.times, ::wind, ::wave, rules, now, lat, lng)
    }

    /** Posts a notification for good days not yet reported; returns how many were new. */
    fun notifyNew(context: Context, prefs: SharedPreferences, windows: List<GoodWindow>, rules: AlertRules): Int {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val told = prefs.getStringSet(KEY_TOLD, emptySet()).orEmpty()
        val fresh = windows.filter { dayKey.format(Date(it.day)) !in told }
        // Days already past are forgotten, so the list stays short.
        val today = dayKey.format(Date())
        prefs.edit().putStringSet(KEY_TOLD, (told.filter { it >= today } + fresh.map { dayKey.format(Date(it.day)) }).toSet()).apply()
        if (fresh.isEmpty()) return 0
        val manager = context.getSystemService(NotificationManager::class.java) ?: return 0
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Good-weather alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When the forecast at your spot is inside your wind and wave limits"
        })
        val dayName = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = fresh.map { "${dayName.format(Date(it.day))}: ${clock.format(Date(it.from))}–${clock.format(Date(it.to))}" }
        val title = if (fresh.size == 1) "Good on the water ${dayName.format(Date(fresh[0].day))}" else "${fresh.size} good days coming up"
        val text = lines.joinToString(" · ") + " (wind ≤ ${rules.windMaxKn.toInt()} kn, waves ≤ ${"%.1f".format(Locale.US, rules.waveMaxM)} m)"
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_anchor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\nForecast at your spot. Check it before you go: not for navigation."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ALERT_ID, notification) }
        return fresh.size
    }

    private const val ALERT_ID = 4207

    class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val prefs = applicationContext.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (!load(prefs).enabled) return Result.success()
            return runCatching {
                val (rules, windows) = check(prefs) ?: return Result.success()
                notifyNew(applicationContext, prefs, windows, rules)
                Result.success()
            }.getOrElse {
                DiagnosticLog.logError("Alerts", "check FAILED", it)
                Result.retry()
            }
        }
    }
}
