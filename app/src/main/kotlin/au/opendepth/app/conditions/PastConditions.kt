package au.opendepth.app.conditions

import au.opendepth.app.BuildConfig
import au.opendepth.app.nav.NavFormat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The wind, waves and swell at a point at a past hour, from Open-Meteo's
 * archived forecasts (CC BY 4.0): two small requests, for a photo taken
 * before the saved forecast. Lines are worded like the values box and say
 * where they came from. Empty when there's no connection or no data.
 */
object PastConditions {

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    fun lines(lat: Double, lng: Double, time: Long, format: NavFormat): List<String> {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
        val hour = (time / HOUR_MS) * HOUR_MS
        val recent = System.currentTimeMillis() - time < 80 * DAY_MS
        val out = mutableListOf<String>()
        val loc = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", lat, lng)
        val windHost = if (recent) "https://api.open-meteo.com/v1/forecast" else "https://historical-forecast-api.open-meteo.com/v1/forecast"
        runCatching {
            val j = get("$windHost?$loc&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=kn&timeformat=unixtime&start_date=$day&end_date=$day")
            val h = j.getJSONObject("hourly")
            val i = index(h, hour) ?: return@runCatching
            val speed = h.getJSONArray("wind_speed_10m").optDouble(i)
            val from = h.getJSONArray("wind_direction_10m").optDouble(i)
            val gust = h.getJSONArray("wind_gusts_10m").optDouble(i)
            if (speed.isFinite() && from.isFinite()) {
                out += "Wind ${format.wind(speed)} from ${compass(from)}" + if (gust.isFinite() && gust > speed * 1.15) ", gusts ${format.wind(gust)}" else ""
            }
        }
        runCatching {
            val j = get("https://marine-api.open-meteo.com/v1/marine?$loc&hourly=wave_height,wave_period,wave_direction," +
                "swell_wave_height,swell_wave_period,swell_wave_direction,sea_surface_temperature&timeformat=unixtime&start_date=$day&end_date=$day")
            val h = j.getJSONObject("hourly")
            val i = index(h, hour) ?: return@runCatching
            fun wave(name: String, height: String, period: String, dir: String) {
                val m = h.getJSONArray(height).optDouble(i)
                val s = h.getJSONArray(period).optDouble(i)
                val d = h.getJSONArray(dir).optDouble(i)
                if (m.isFinite()) out += "$name ${String.format(Locale.US, "%.1f m", m)}" +
                    (if (s.isFinite()) String.format(Locale.US, ", %.0f s", s) else "") + (if (d.isFinite()) " from ${compass(d)}" else "")
            }
            wave("Waves", "wave_height", "wave_period", "wave_direction")
            wave("Swell", "swell_wave_height", "swell_wave_period", "swell_wave_direction")
            h.optJSONArray("sea_surface_temperature")?.optDouble(i)?.takeIf { it.isFinite() }?.let { out += "Surface temp ${format.temperature(it)}" }
        }
        if (out.isNotEmpty()) out += "(Wind and sea from Open-Meteo's archived forecasts, CC BY 4.0)"
        return out
    }

    private fun index(hourly: JSONObject, hour: Long): Int? {
        val t = hourly.getJSONArray("time")
        for (k in 0 until t.length()) if (t.getLong(k) * 1000 == hour) return k
        return null
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "SeaBathy/${BuildConfig.VERSION_NAME} (Android)")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            c.disconnect()
        }
    }

    private fun compass(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
    }
}
