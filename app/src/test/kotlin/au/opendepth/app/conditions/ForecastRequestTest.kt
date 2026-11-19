package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The forecast's requests: counted, asked again when one drops, stopped by a limit, and backed up. */
class ForecastRequestTest {
    private val hour = 3_600_000L
    private val start = 1_800_000_000_000L / hour * hour
    private val now = AtomicLong(start)
    private val grid = GeoGrid(doubleArrayOf(-20.0, -19.5), doubleArrayOf(150.0, 150.5, 151.0))
    private val asked = ConcurrentLinkedQueue<String>()
    private val pauses = ConcurrentLinkedQueue<Long>()
    private val budget = CallBudget { now.get() }

    /** Answers like the forecast service: every point asked for, six hours, each variable in the request. */
    private fun answer(url: String): String {
        fun list(name: String) = url.substringAfter("$name=").substringBefore('&').split(',')
        val lats = list("latitude")
        val lngs = list("longitude")
        val variables = url.substringAfter("hourly=").substringBefore('&').split(',')
        val times = (0 until 6).joinToString(",") { ((start - 3 * hour + it * hour) / 1000).toString() }
        return lats.indices.joinToString(",", "[", "]") { i ->
            val series = variables.joinToString(",") { v -> "\"$v\":[" + (0 until 6).joinToString(",") { "1.0" } + "]" }
            "{\"latitude\":${lats[i]},\"longitude\":${lngs[i]},\"hourly\":{\"time\":[$times],$series}}"
        }
    }

    private fun client(http: (String) -> String) = ConditionsClient(
        http = { url -> asked += url; http(url) },
        // Waiting moves the clock on, as it would.
        pause = { pauses += it; now.addAndGet(it) },
        clock = { now.get() },
        budget = budget
    )

    private fun marine(url: String) = "marine-api.open-meteo.com" in url
    private fun wind(url: String) = "api.open-meteo.com/v1/forecast" in url

    @Test
    fun `the days asked for are whole days ahead of now`() {
        val points = listOf(-20.0 to 150.0)
        for (url in listOf(ConditionsClient.marineUrl(points, 2), ConditionsClient.windUrl(points, 2))) {
            // Two days, and the twelve hours more that let the sea values be kept between the wave model's runs.
            assertTrue(url, "forecast_hours=61" in url && "past_hours=3" in url)
            // Not the service's own days, which start at midnight UTC.
            assertFalse(url, "forecast_days" in url)
        }
        assertTrue("forecast_hours=37" in ConditionsClient.marineUrl(points, 1))
        assertTrue("forecast_hours=205" in ConditionsClient.marineUrl(points, 99))
    }

    @Test
    fun `the wind is asked for over the water, as the waves are`() {
        // By the shore the service's own choice is a cell on land, where the wind is lighter.
        val points = listOf(-34.06 to 151.27)
        assertTrue("cell_selection=sea" in ConditionsClient.windUrl(points, 2))
        assertTrue("cell_selection=sea" in ConditionsClient.marineUrl(points, 2))
    }

    @Test
    fun `with nothing newer out for the sea, only the wind is asked for`() {
        val c = client(::answer)
        val full = c.forecast(grid, 2)
        assertEquals(6, budget.used(hour, CallBudget.MARINE))
        asked.clear()
        // An hour on: the sea values kept, the wind afresh.
        now.addAndGet(hour)
        val renewed = c.forecast(grid, 2, keepSea = full)
        assertTrue(asked.toString(), asked.isNotEmpty() && asked.all(::wind))
        assertEquals(6, budget.used(2 * hour, CallBudget.MARINE))
        assertEquals(12, budget.used(2 * hour, CallBudget.WEATHER))
        // Asked to the kept forecast's last hour, no further.
        val hours = ((full.endMillis - now.get()) / hour).toInt() + 1
        assertTrue(asked.first(), "forecast_hours=$hours&" in asked.first())
        assertTrue(renewed.has(ForecastField.WAVE_HEIGHT) && renewed.has(ForecastField.CURRENT_U) && renewed.has(ForecastField.WIND_U) && renewed.has(ForecastField.GUST))
        assertEquals(full.endMillis, renewed.endMillis)
        assertEquals(1f, renewed.sample(ForecastField.WAVE_HEIGHT, -19.75, 150.5, renewed.endMillis), 0.001f)
    }

    @Test
    fun `sea values are kept hour for hour, and only over the hours both have`() {
        fun grid(times: LongArray, wind: Float?, wave: (Int) -> Float) = ForecastGrid(
            grid, times,
            buildMap {
                if (wind != null) put(ForecastField.WIND_U, FloatArray(times.size * grid.size) { wind })
                put(ForecastField.WAVE_HEIGHT, FloatArray(times.size * grid.size) { wave(it / grid.size) })
            }
        )
        // Kept: hours 0 to 9, its waves numbered by hour. Fresh: hours 4 to 15, new wind, and waves that mustn't be used.
        val kept = grid(LongArray(10) { start + it * hour }, 2f) { it.toFloat() }
        val fresh = grid(LongArray(12) { start + (4 + it) * hour }, 7f) { 99f }
        val both = fresh.withSeaFrom(kept)
        assertEquals(start + 4 * hour, both.startMillis)
        assertEquals(start + 9 * hour, both.endMillis)
        assertEquals(6, both.times.size)
        for (h in 0 until 6) {
            assertEquals((4 + h).toFloat(), both.fields[ForecastField.WAVE_HEIGHT]!![h * grid.size], 0f)
            assertEquals(7f, both.fields[ForecastField.WIND_U]!![h * grid.size], 0f)
        }
        // A kept forecast on other points is refused, not blended.
        val elsewhere = ForecastGrid(GeoGrid(doubleArrayOf(-21.0, -20.5), doubleArrayOf(150.0, 150.5, 151.0)), kept.times, kept.fields)
        assertTrue(runCatching { fresh.withSeaFrom(elsewhere) }.isFailure)
    }

    @Test
    fun `the sea models' newest run is told apart from the wind's`() {
        val c = client { url ->
            when {
                "meteofrance_wave" in url -> "{\"last_run_availability_time\": 1795000000}"
                "meteofrance_currents" in url -> "{\"last_run_availability_time\": 1794990000}"
                "meta.json" in url -> "{\"last_run_availability_time\": 1795020000}"
                else -> answer(url)
            }
        }
        val runs = c.latestRuns("ecmwf_ifs")
        assertEquals(1_795_020_000_000L, runs.newest)
        assertEquals(1_795_000_000_000L, runs.sea)
        // One of the sea models' files unread: its newest run isn't known, and nothing is kept on a guess.
        val broken = client { url ->
            if ("meteofrance_currents" in url) throw IOException("timeout") else "{\"last_run_availability_time\": 1795000000}"
        }
        assertEquals(null, broken.latestRuns("ecmwf_ifs").sea)
        assertEquals(1_795_000_000_000L, broken.latestRuns("ecmwf_ifs").newest)
    }

    @Test
    fun `every point is counted, and the free check for a newer run is not`() {
        val c = client { url -> if ("meta.json" in url) "{\"last_run_availability_time\": 1795000000}" else answer(url) }
        val forecast = c.forecast(grid, 2)
        assertTrue(forecast.has(ForecastField.WAVE_HEIGHT) && forecast.has(ForecastField.WIND_U) && forecast.has(ForecastField.GUST))
        // Six sea points and six wind points, each counted against its own service.
        assertEquals(6, budget.used(hour, CallBudget.MARINE))
        assertEquals(6, budget.used(hour, CallBudget.WEATHER))
        assertEquals(1_795_000_000_000L, c.latestRunMillis())
        assertEquals(6, budget.used(hour, CallBudget.MARINE))
        assertEquals(6, budget.used(hour, CallBudget.WEATHER))
    }

    @Test
    fun `a request that drops is asked again and the forecast still comes`() {
        val dropped = AtomicInteger()
        val c = client { url ->
            if (marine(url) && dropped.getAndIncrement() == 0) throw IOException("unexpected end of stream")
            answer(url)
        }
        val forecast = c.forecast(grid, 2)
        assertTrue(forecast.has(ForecastField.WAVE_HEIGHT) && forecast.has(ForecastField.WIND_U))
        assertEquals(2, asked.count(::marine))
        assertEquals(1, asked.count(::wind))
    }

    @Test
    fun `an answer cut short is asked for again too`() {
        val cut = AtomicInteger()
        val c = client { url -> if (marine(url) && cut.getAndIncrement() == 0) answer(url).dropLast(40) else answer(url) }
        assertTrue(c.forecast(grid, 2).has(ForecastField.WAVE_HEIGHT))
        assertEquals(2, asked.count(::marine))
    }

    @Test
    fun `a server that never answers is given up on after a few tries`() {
        val c = client { url -> if (marine(url)) throw ConditionsClient.ServiceBusyException("502") else answer(url) }
        assertTrue(runCatching { c.forecast(grid, 2) }.exceptionOrNull() is ConditionsClient.ServiceBusyException)
        assertEquals(3, asked.count(::marine))
        // One that times out, fewer: each try is a long wait.
        asked.clear()
        val slow = client { url -> if (marine(url)) throw java.net.SocketTimeoutException("timeout") else answer(url) }
        assertTrue(runCatching { slow.forecast(grid, 2) }.isFailure)
        assertEquals(2, asked.count(::marine))
    }

    @Test
    fun `a plain no is not asked again`() {
        val c = client { url -> if (marine(url)) throw ConditionsClient.ServiceRefusedException("Open-Meteo: bad request") else answer(url) }
        assertTrue(runCatching { c.forecast(grid, 2) }.exceptionOrNull() is ConditionsClient.ServiceRefusedException)
        assertEquals(1, asked.count(::marine))
    }

    @Test
    fun `the hour's limit stops every request until the hour is over`() {
        val c = client { throw ConditionsClient.ServiceLimitException(LimitKind.HOUR, "Hourly API request limit exceeded") }
        val first = runCatching { c.forecast(grid, 2) }.exceptionOrNull()
        assertTrue("$first", first is LimitReachedException)
        val until = (first as LimitReachedException).untilMillis
        assertTrue(until > now.get() && until <= now.get() + hour + 60_000L)
        // Asked again straight away: refused here, with nothing sent.
        asked.clear()
        assertTrue(runCatching { c.forecast(grid, 2) }.exceptionOrNull() is LimitReachedException)
        assertTrue(runCatching { c.seaAt(listOf(-20.0 to 150.0)) }.exceptionOrNull() is LimitReachedException)
        assertEquals(0, asked.size)
    }

    @Test
    fun `the minute's limit is waited out and the forecast still comes`() {
        // Each of the two services says "wait a minute" once.
        val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val c = client { url ->
            if (refused.add(CallBudget.apiOf(url))) throw ConditionsClient.ServiceLimitException(LimitKind.MINUTE, "Minutely API request limit exceeded")
            answer(url)
        }
        assertTrue(c.forecast(grid, 2).has(ForecastField.WAVE_HEIGHT))
        assertTrue("waits $pauses", pauses.any { it >= 55_000L })
    }

    @Test
    fun `a download that doesn't fit in what's left of the day isn't started`() {
        // Nearly all of the automatic share is spent.
        repeat(CallBudget.DAY_AUTO_CAP / 40) {
            budget.count(40)
            now.addAndGet(120_000L)
        }
        val c = client(::answer)
        assertTrue(runCatching { c.forecast(grid, 2) }.exceptionOrNull() is LimitReachedException)
        assertEquals(0, asked.size)
        // The user's own tap has the rest.
        assertTrue(c.forecast(grid, 2, userAsked = true).has(ForecastField.WAVE_HEIGHT))
    }

    private fun pacioos(url: String): String? = when {
        "pacioos" !in url -> null
        "time%5B(last)%5D" in url -> "time\nUTC\n${java.time.Instant.ofEpochMilli(start + 48 * hour)}\n"
        "ww3_global" in url -> buildString {
            append("time,depth,latitude,longitude,Thgt,Tper,Tdir,shgt,sper,sdir\nUTC,m,degrees_north,degrees_east,meters,second,degrees,meters,seconds,degrees\n")
            for (t in listOf(start - 6 * hour, start + 6 * hour)) for (lat in listOf(-20.5, -20.0, -19.5, -19.0)) for (lng in listOf(149.5, 150.0, 150.5, 151.0, 151.5)) {
                append("${java.time.Instant.ofEpochMilli(t)},0.0,$lat,$lng,2.0,9.0,90.0,1.5,12.0,180.0\n")
            }
        }
        else -> throw IOException("no backup wind in this test")
    }

    @Test
    fun `with the sea forecast at its limit the usual wind is kept and only the waves are the backup's`() {
        // The two services are counted apart: the sea forecast says "tomorrow", the wind still answers.
        val c = client { url ->
            pacioos(url) ?: if (marine(url)) throw ConditionsClient.ServiceLimitException(LimitKind.DAY, "Daily API request limit exceeded. Please try again tomorrow.") else answer(url)
        }
        assertTrue(runCatching { c.forecast(grid, 2) }.exceptionOrNull() is LimitReachedException)
        assertTrue(budget.blockedUntil(CallBudget.MARINE) > now.get())
        assertEquals(0L, budget.blockedUntil(CallBudget.WEATHER))
        val backup = c.backupForecast(grid, 2)
        // The main service's wind, gusts and all; the backup's waves; and no currents.
        assertTrue(backup.has(ForecastField.GUST) && backup.has(ForecastField.WIND_U))
        assertEquals(2f, backup.sample(ForecastField.WAVE_HEIGHT, -20.0, 150.0, start), 0.001f)
        assertFalse(backup.has(ForecastField.CURRENT_U))
        // Nothing more is sent to the service that said to wait.
        asked.clear()
        c.backupForecast(grid, 2)
        assertEquals(0, asked.count(::marine))
    }

    @Test
    fun `a limit the service names is asked about again within a couple of hours, not left for a day`() {
        val until = budget.limited(LimitKind.DAY)
        assertTrue(until - now.get() <= CallBudget.DAY_RETRY_MS)
        assertTrue(budget.limited(LimitKind.HOUR, CallBudget.WEATHER) - now.get() <= CallBudget.HOUR_RETRY_MS)
    }

    @Test
    fun `the wind model in use at a place is told from the service's own answer`() {
        fun reply(best: String, ifs: String, gfs: String, icon: String) = "{\"hourly\":{\"time\":[1,2],\"wind_speed_10m_best_match\":[$best]," +
            "\"wind_speed_10m_ecmwf_ifs\":[$ifs],\"wind_speed_10m_gfs_seamless\":[$gfs],\"wind_speed_10m_icon_seamless\":[$icon]}}"
        val day = (1..24).joinToString(",") { "%.1f".format(java.util.Locale.US, 5 + it * 0.1) }
        val other = (1..24).joinToString(",") { "%.1f".format(java.util.Locale.US, 7 + it * 0.2) }
        val nearly = (1..24).joinToString(",") { "%.2f".format(java.util.Locale.US, 5.03 + it * 0.1) }
        assertEquals("ecmwf_ifs", client { reply(day, day, other, other) }.windModelAt(-34.0, 151.6))
        assertEquals("gfs_seamless", client { reply(day, other, day, other) }.windModelAt(24.4, -81.0))
        // The German one is blended a little at the seams: nearly the same counts.
        assertEquals("icon_seamless", client { reply(day, other, other, nearly) }.windModelAt(54.5, 4.0))
        // None of the three: not known.
        assertEquals(null, client { reply(day, other, other, other) }.windModelAt(35.0, 139.0))
        assertTrue(asked.all { "models=best_match,ecmwf_ifs,gfs_seamless,icon_seamless" in it })
    }

    @Test
    fun `new runs are watched for of the models in use, not of every model`() {
        val european = ConditionsClient.runStatusUrls("ecmwf_ifs")
        assertEquals(3, european.size)
        assertTrue(european.any { "/ecmwf_ifs/" in it } && european.any { "meteofrance_wave" in it } && european.any { "meteofrance_currents" in it })
        assertTrue(ConditionsClient.runStatusUrls("gfs_seamless").any { "ncep_gfs013" in it })
        // Not known: all three wind models.
        assertEquals(5, ConditionsClient.runStatusUrls(null).size)
        // The newest of those watched: an American run that came out later doesn't count round Australia.
        val c = client { url ->
            val out = when {
                "ncep_gfs013" in url -> 2_000L
                "ecmwf_ifs" in url -> 1_000L
                else -> 500L
            }
            "{\"last_run_availability_time\": $out}"
        }
        assertEquals(1_000_000L, c.latestRunMillis("ecmwf_ifs"))
        assertEquals(2_000_000L, c.latestRunMillis(null))
    }

    @Test
    fun `when the wind doesn't come the sea forecast stands and the backup's wind fills in`() {
        val c = client { url ->
            when {
                wind(url) -> throw ConditionsClient.ServiceBusyException("502")
                "pacioos" in url && "time%5B(last)%5D" in url -> "time\nUTC\n${java.time.Instant.ofEpochMilli(start + 48 * hour)}\n"
                "pacioos" in url && "ncep_global" in url -> buildString {
                    append("time,latitude,longitude,ugrd10m,vgrd10m\nUTC,degrees_north,degrees_east,m s-1,m s-1\n")
                    for (t in listOf(start - 6 * hour, start + 6 * hour)) for (lat in listOf(-20.5, -20.0, -19.5, -19.0)) for (lng in listOf(149.5, 150.0, 150.5, 151.0, 151.5)) {
                        append("${java.time.Instant.ofEpochMilli(t)},$lat,$lng,4.0,-3.0\n")
                    }
                }
                else -> answer(url)
            }
        }
        val forecast = c.forecast(grid, 2)
        assertTrue(forecast.has(ForecastField.WAVE_HEIGHT))
        assertEquals(4f, forecast.sample(ForecastField.WIND_U, -20.0, 150.0, start), 0.001f)
        assertEquals(-3f, forecast.sample(ForecastField.WIND_V, -20.0, 150.0, start), 0.001f)
        // The backup has no gusts.
        assertFalse(forecast.has(ForecastField.GUST))
    }
}
