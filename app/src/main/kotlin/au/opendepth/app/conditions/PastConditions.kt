package au.opendepth.app.conditions

import au.opendepth.app.BuildConfig
import au.opendepth.app.nav.NavFormat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The wind, waves and swell at a point at a past hour, from Open-Meteo's
 * archived forecasts (CC BY 4.0): two small requests, for a photo taken
 * before the saved forecast. Lines are worded like the values box, in the
 * user's units, and say where they came from. Empty when there's no
 * connection or no data.
 *
 * Each request is one call of the forecast service, counted in the app's
 * [CallBudget] like any other, from the automatic share (nobody asked for it
 * outright): one the budget won't allow is left out, never waited for.
 */
object PastConditions {

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /**
     * The lines for [lat]/[lng] at [time], worded with [format] (the user's units). [budget],
     * [http] and [now] are for tests; the app's are the defaults.
     */
    fun lines(
        lat: Double, lng: Double, time: Long, format: NavFormat,
        budget: CallBudget = CallBudget.shared,
        http: (String) -> String = ::get,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
        val hour = (time / HOUR_MS) * HOUR_MS
        val recent = now - time < 80 * DAY_MS
        val out = mutableListOf<String>()
        val loc = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", lat, lng)
        val windHost = if (recent) "https://api.open-meteo.com/v1/forecast" else "https://historical-forecast-api.open-meteo.com/v1/forecast"
        runCatching {
            val h = ask("$windHost?$loc&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=kn&timeformat=unixtime&cell_selection=sea&start_date=$day&end_date=$day", budget, http)
                ?: return@runCatching
            val i = index(h, hour) ?: return@runCatching
            val speed = value(h, "wind_speed_10m", i)
            val from = value(h, "wind_direction_10m", i)
            val gust = value(h, "wind_gusts_10m", i)
            if (speed.isFinite() && from.isFinite()) {
                out += "Wind ${format.wind(speed)} from ${compass(from)}" + if (gust.isFinite() && gust > speed * 1.15) ", gusts ${format.wind(gust)}" else ""
            }
        }
        runCatching {
            val h = ask("https://marine-api.open-meteo.com/v1/marine?$loc&hourly=wave_height,wave_period,wave_direction," +
                "swell_wave_height,swell_wave_period,swell_wave_direction,sea_surface_temperature&timeformat=unixtime&start_date=$day&end_date=$day", budget, http)
                ?: return@runCatching
            val i = index(h, hour) ?: return@runCatching
            fun wave(name: String, height: String, period: String, dir: String) {
                val m = value(h, height, i)
                val s = value(h, period, i)
                val d = value(h, dir, i)
                if (m.isFinite()) out += "$name ${format.waveHeight(m)}" +
                    (if (s.isFinite()) String.format(Locale.US, ", %.0f s", s) else "") + (if (d.isFinite()) " from ${compass(d)}" else "")
            }
            wave("Waves", "wave_height", "wave_period", "wave_direction")
            wave("Swell", "swell_wave_height", "swell_wave_period", "swell_wave_direction")
            value(h, "sea_surface_temperature", i).takeIf { it.isFinite() }?.let { out += "Surface temp ${format.temperature(it)}" }
        }
        if (out.isNotEmpty()) out += "(Wind and sea from Open-Meteo's archived forecasts, CC BY 4.0)"
        return out
    }

    /**
     * One request of one call: its "hourly" block, or null when the budget doesn't allow it now
     * (the day's or the hour's share used, the minute full, or the service said to wait). A
     * "too many requests" answer is told to the budget, so the forecast's downloads wait too.
     */
    private fun ask(url: String, budget: CallBudget, http: (String) -> String): JsonObject? {
        val api = CallBudget.apiOf(url)
        try {
            budget.check(1, userAsked = false, api)
            if (budget.reserve(1, api) > 0L) return null
        } catch (e: LimitReachedException) {
            return null
        }
        val body = try {
            http(url)
        } catch (e: ConditionsClient.ServiceLimitException) {
            budget.limited(e.kind, api)
            return null
        }
        return JsonParser.parseString(body).asJsonObject.getAsJsonObject("hourly")
    }

    private fun index(hourly: JsonObject, hour: Long): Int? {
        val t = hourly.getAsJsonArray("time") ?: return null
        for (k in 0 until t.size()) if (!t[k].isJsonNull && t[k].asLong * 1000 == hour) return k
        return null
    }

    /** [name]'s value at [i], NaN when it's missing or blank. */
    private fun value(hourly: JsonObject, name: String, i: Int): Double {
        val a = hourly.getAsJsonArray(name) ?: return Double.NaN
        if (i >= a.size() || a[i].isJsonNull) return Double.NaN
        return runCatching { a[i].asDouble }.getOrDefault(Double.NaN)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "SeaBathy/${BuildConfig.VERSION_NAME} (Android)")
        try {
            val code = c.responseCode
            if (code == 429) {
                val reason = runCatching { c.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull()
                throw ConditionsClient.ServiceLimitException(CallBudget.kindOf(reason), "Open-Meteo is busy (too many requests)")
            }
            if (code != 200) error("HTTP $code")
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private fun compass(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
    }
}
