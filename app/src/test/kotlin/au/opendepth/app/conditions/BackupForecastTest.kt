package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The backup forecast: NOAA's wind and waves from another service, onto the app's own grid. */
class BackupForecastTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L / hour * hour
    private val start = now - 3 * hour
    private val grid = GeoGrid(doubleArrayOf(-20.0, -19.5), doubleArrayOf(150.0, 150.5, 151.0))
    private val lats = listOf(-20.5, -20.0, -19.5, -19.0)
    private val lngs = listOf(149.5, 150.0, 150.5, 151.0, 151.5)

    private fun iso(millis: Long) = java.time.Instant.ofEpochMilli(millis).toString()

    /** The wind model's table: every three hours, the wind towards the east rising by 3 m/s a step. */
    private fun windCsv(steps: Int = 3) = buildString {
        append("time,latitude,longitude,ugrd10m,vgrd10m\nUTC,degrees_north,degrees_east,m s-1,m s-1\n")
        for (s in 0 until steps) for (lat in lats) for (lng in lngs) append("${iso(start + s * 3 * hour)},$lat,$lng,${4.0 + 3 * s},-3.0\n")
    }

    /** The wave model's table, hourly: waves from the east, swell from the south, and land in the north-west corner. */
    private fun wavesCsv(hours: Int = 7) = buildString {
        append("time,depth,latitude,longitude,Thgt,Tper,Tdir,shgt,sper,sdir\nUTC,m,degrees_north,degrees_east,meters,second,degrees,meters,seconds,degrees\n")
        for (h in 0 until hours) for (lat in lats) for (lng in lngs) {
            val land = lat >= -19.5 && lng <= 150.0
            append("${iso(start + h * hour)},0.0,$lat,$lng," + (if (land) "NaN,NaN,NaN,NaN,NaN,NaN" else "2.0,9.0,90.0,1.5,12.0,180.0") + "\n")
        }
    }

    private fun http(wind: String? = windCsv(), waves: String? = wavesCsv()): (String) -> String = { url ->
        when {
            "time%5B(last)%5D" in url -> "time\nUTC\n${iso(start + 6 * hour)}\n"
            "ncep_global" in url -> wind ?: throw IOException("wind server down")
            "ww3_global" in url -> waves ?: throw IOException("wave server down")
            else -> throw IOException("unexpected $url")
        }
    }

    private fun ForecastGrid.at(field: ForecastField, hourIndex: Int, point: Int = 0) = fields.getValue(field)[hourIndex * grid.size + point]

    @Test
    fun `wind and waves come onto the app's grid for the hours asked`() {
        val forecast = ConditionsClient(http = http(), pause = {}, clock = { now }).backupForecast(grid, 2)
        assertEquals(49 + 3, forecast.times.size)
        assertEquals(start, forecast.times.first())
        // The wind, blended between the model's three-hourly steps.
        assertEquals(4f, forecast.at(ForecastField.WIND_U, 0), 0.001f)
        assertEquals(5f, forecast.at(ForecastField.WIND_U, 1), 0.001f)
        assertEquals(10f, forecast.at(ForecastField.WIND_U, 6), 0.001f)
        assertEquals(-3f, forecast.at(ForecastField.WIND_V, 3), 0.001f)
        // Past what the model has: blank, not made up.
        assertTrue(forecast.at(ForecastField.WIND_U, 7).isNaN())
        assertEquals(2f, forecast.at(ForecastField.WAVE_HEIGHT, 2), 0.001f)
        assertEquals(9f, forecast.at(ForecastField.WAVE_PERIOD, 2), 0.001f)
        assertEquals(12f, forecast.at(ForecastField.SWELL_PERIOD, 2), 0.001f)
        // "From the east" is going west; "from the south" is going north.
        assertEquals(-1f, forecast.at(ForecastField.WAVE_U, 2), 0.001f)
        assertEquals(0f, forecast.at(ForecastField.WAVE_V, 2), 0.001f)
        assertEquals(0f, forecast.at(ForecastField.SWELL_U, 2), 0.001f)
        assertEquals(1f, forecast.at(ForecastField.SWELL_V, 2), 0.001f)
        // What the backup doesn't have isn't there.
        assertFalse(forecast.has(ForecastField.GUST) || forecast.has(ForecastField.CURRENT_U) || forecast.has(ForecastField.SEA_TEMP))
    }

    @Test
    fun `the sea's waves reach a point the coarse model calls land`() {
        val forecast = ConditionsClient(http = http(), pause = {}, clock = { now }).backupForecast(grid, 1)
        // The grid's north-west point (-19.5, 150.0) is land to the wave model.
        val northWest = 1 * grid.cols + 0
        assertEquals(2f, forecast.at(ForecastField.WAVE_HEIGHT, 2, northWest), 0.001f)
        assertEquals(-1f, forecast.at(ForecastField.WAVE_U, 2, northWest), 0.001f)
    }

    @Test
    fun `one part missing still gives the other, and neither is a failure`() {
        val wavesOnly = ConditionsClient(http = http(wind = null), pause = {}, clock = { now }).backupForecast(grid, 2)
        assertTrue(wavesOnly.has(ForecastField.WAVE_HEIGHT))
        assertFalse(wavesOnly.has(ForecastField.WIND_U))
        val windOnly = ConditionsClient(http = http(waves = null), pause = {}, clock = { now }).backupForecast(grid, 2)
        assertTrue(windOnly.has(ForecastField.WIND_U))
        assertFalse(windOnly.has(ForecastField.WAVE_HEIGHT))
        assertTrue(runCatching { ConditionsClient(http = http(null, null), pause = {}, clock = { now }).backupForecast(grid, 2) }.isFailure)
    }

    @Test
    fun `the request never reaches past the model's last hour`() {
        val asked = mutableListOf<String>()
        val base = http()
        ConditionsClient(http = { url -> asked += url; base(url) }, pause = {}, clock = { now }).backupForecast(grid, 8)
        val data = asked.filter { "pacioos" in it && "time%5B(last)%5D" !in it }
        assertEquals(2, data.size)
        // Eight days were asked for; the model ends six hours on.
        data.forEach { assertTrue(it, "(${iso(start + 6 * hour)})%5D" in it) }
        // A cell beyond the area each way, in the server's own longitudes.
        assertTrue(data.first(), "(-20.50):1:(-19.00)" in data.first() && "(149.50):1:(151.50)" in data.first())
    }

    @Test
    fun `longitudes are asked the server's way, in two pieces across Greenwich`() {
        assertEquals(listOf(150.0 to 154.0), BackupForecast.lngRanges(150.0, 154.0))
        assertEquals(listOf(285.0 to 290.0), BackupForecast.lngRanges(-75.0, -70.0))
        assertEquals(listOf(358.0 to 359.5, 0.0 to 3.0), BackupForecast.lngRanges(-2.0, 3.0))
        // A cell past the date line either way stays one piece.
        assertEquals(listOf(179.5 to 184.0), BackupForecast.lngRanges(-180.5, -176.0))
        assertEquals(listOf(176.0 to 180.5), BackupForecast.lngRanges(176.0, 180.5))
    }

    @Test
    fun `two pieces across Greenwich come back as one table in degrees east`() {
        fun piece(vararg lng: Double) = buildString {
            append("time,latitude,longitude,ugrd10m,vgrd10m\nUTC,degrees_north,degrees_east,m s-1,m s-1\n")
            for (l in lng) append("${iso(start)},50.0,$l,${if (l > 180) 1.0 else 2.0},0.0\n")
        }
        val table = BackupForecast.parse(listOf(piece(359.0, 359.5), piece(0.0, 0.5)), listOf("ugrd10m", "vgrd10m"), centreLng = 0.0)
        assertEquals(listOf(-1.0, -0.5, 0.0, 0.5), table.lngs.toList())
        assertEquals(listOf(1f, 1f, 2f, 2f), table.values.getValue("ugrd10m").toList())
    }

    @Test
    fun `a table that isn't one is an error, not a crash`() {
        assertTrue(runCatching { BackupForecast.parse(listOf("<html>Service Unavailable</html>"), listOf("ugrd10m"), 150.0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { BackupForecast.parse(listOf(""), listOf("ugrd10m"), 150.0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { BackupForecast.lastTime("time\nUTC\n") }.exceptionOrNull() is IOException)
    }
}
