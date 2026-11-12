package au.opendepth.app.conditions

import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone

class ConditionsTest {

    private val hour = ForecastGrid.HOUR_MS

    @Test
    fun `grid blends between points and leaves land blank`() {
        val grid = GeoGrid(doubleArrayOf(-20.0, -19.0), doubleArrayOf(149.0, 150.0))
        // South-west, south-east, north-west, north-east.
        val values = floatArrayOf(10f, 20f, 30f, Float.NaN)
        assertEquals(10f, grid.sample(values, 0, -20.0, 149.0), 1e-4f)
        assertEquals(15f, grid.sample(values, 0, -20.0, 149.5), 1e-4f)
        // Nearest point is land: blank, not smeared onto the shore.
        assertTrue(grid.sample(values, 0, -19.1, 149.9).isNaN())
        // When a finer coastline does the masking, the sea corners reach the shore.
        assertEquals(20f, grid.sample(values, 0, -19.1, 149.9, strict = false), 6f)
        // Off the grid altogether.
        assertTrue(grid.sample(values, 0, -10.0, 149.5).isNaN())
    }

    @Test
    fun `even and uneven grids blend the same`() {
        // The same points, one axis nudged off even spacing so it is searched rather than computed.
        val even = GeoGrid(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(10.0, 11.0, 12.0))
        val uneven = GeoGrid(doubleArrayOf(0.0, 1.0000001, 2.0), doubleArrayOf(10.0, 11.0, 12.0))
        val values = FloatArray(9) { it.toFloat() }
        for ((lat, lng) in listOf(0.0 to 10.0, 0.5 to 10.5, 1.25 to 11.75, 2.0 to 12.0, 1.9 to 10.1)) {
            assertEquals(even.sample(values, 0, lat, lng), uneven.sample(values, 0, lat, lng), 1e-4f)
        }
        assertEquals(4f, even.sample(values, 0, 1.0, 11.0), 1e-6f)
    }

    @Test
    fun `flow directions are compass bearings`() {
        val (u, v) = Flow.parts(2.0, 90.0)
        assertEquals(2.0, u, 1e-9)
        assertEquals(0.0, v, 1e-9)
        val flow = Flow.of(-1.0, -1.0)
        assertEquals(225.0, flow.towardsDegrees, 1e-9)
        assertEquals(45.0, flow.fromDegrees, 1e-9)
        assertEquals("SW", ConditionsFeature.compass(flow.towardsDegrees))
        assertEquals("N", ConditionsFeature.compass(359.0))
    }

    private fun marineJson(points: List<Triple<Double, Double, Double>>, speeds: List<Double>): String =
        points.joinToString(",", "[", "]") { (lat, lng, dir) ->
            """{"latitude":$lat,"longitude":$lng,"hourly":{"time":[0,3600],""" +
                """"ocean_current_velocity":[${speeds[0]},${speeds[1]}],"ocean_current_direction":[$dir,$dir],""" +
                """"wave_height":[1.0,1.2],"wave_direction":[90,90],"wave_period":[7,7],""" +
                """"swell_wave_height":[0.5,null],"swell_wave_direction":[90,90],"swell_wave_period":[10,10],""" +
                """"sea_surface_temperature":[26.5,26.7]}}"""
        }

    @Test
    fun `open-meteo points go on the grid, and inland points have wind but no sea values`() {
        val grid = GeoGrid(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.1))
        val requested = listOf(-20.0 to 149.0, -20.0 to 149.1, -19.9 to 149.0, -19.9 to 149.1)
        // The last point was on land: Open-Meteo moved it 30 km out to sea.
        val marine = ConditionsClient.parseOpenMeteo(
            marineJson(
                listOf(Triple(-20.0, 149.0, 90.0), Triple(-20.0, 149.1, 90.0), Triple(-19.9, 149.0, 90.0), Triple(-20.1, 149.35, 90.0)),
                listOf(1.0, 2.0)
            )
        )
        val wind = ConditionsClient.parseOpenMeteo(
            requested.joinToString(",", "[", "]") { (lat, lng) ->
                """{"latitude":$lat,"longitude":$lng,"hourly":{"time":[0,3600],"wind_speed_10m":[5,5],"wind_direction_10m":[180,180],"wind_gusts_10m":[8,8]}}"""
            }
        )
        val f = ConditionsClient.assemble(grid, requested, marine, wind)
        val current = f.vector(ForecastField.CURRENT_U, ForecastField.CURRENT_V, -20.0, 149.0, 0)!!
        assertEquals(1.0, current.speed, 1e-5)
        assertEquals(90.0, current.towardsDegrees, 1e-3)
        // Half way through the hour, half way between the speeds.
        assertEquals(1.5, f.vector(ForecastField.CURRENT_U, ForecastField.CURRENT_V, -20.0, 149.0, hour / 2)!!.speed, 1e-5)
        // Waves "from 90" go west.
        assertEquals(270.0, f.vector(ForecastField.WAVE_U, ForecastField.WAVE_V, -20.0, 149.0, 0)!!.towardsDegrees, 1e-3)
        // Wind from the south blows north.
        val w = f.vector(ForecastField.WIND_U, ForecastField.WIND_V, -20.0, 149.0, 0)!!
        assertEquals(0.0, w.towardsDegrees, 1e-3)
        assertEquals(180.0, w.fromDegrees, 1e-3)
        assertTrue(f.sample(ForecastField.CURRENT_U, -19.9, 149.1, 0).isNaN())
        assertEquals(5.0, f.vector(ForecastField.WIND_U, ForecastField.WIND_V, -19.9, 149.1, 0)!!.speed, 1e-4)
        // A missing hour is blank, not zero.
        assertEquals(0.5f, f.sample(ForecastField.SWELL_HEIGHT, -20.0, 149.0, 0), 1e-5f)
        assertTrue(f.sample(ForecastField.SWELL_HEIGHT, -20.0, 149.0, hour).isNaN())
        // Between a known hour and a missing one, the known one stands.
        assertEquals(0.5f, f.sample(ForecastField.SWELL_HEIGHT, -20.0, 149.0, hour / 2), 1e-5f)
        // Outside the forecast hours.
        assertTrue(f.sample(ForecastField.SEA_TEMP, -20.0, 149.0, 10 * hour).isNaN())
    }

    @Test
    fun `water near the coast keeps the nearest ocean cell's values`() {
        val grid = GeoGrid(doubleArrayOf(-21.10), doubleArrayOf(149.85))
        val requested = listOf(-21.10 to 149.85)
        // Close inshore: the nearest current-model cell is 11 km away.
        val marine = ConditionsClient.parseOpenMeteo(
            """{"latitude":-21.201668,"longitude":149.85002,"hourly":{"time":[0],"ocean_current_velocity":[0.8],"ocean_current_direction":[0]}}"""
        )
        val f = ConditionsClient.assemble(grid, requested, marine, null)
        assertEquals(0.8, f.vector(ForecastField.CURRENT_U, ForecastField.CURRENT_V, -21.10, 149.85, 0)!!.speed, 1e-4)
    }

    @Test
    fun `the satellite coastline reaches the shore`() {
        // West column land, east column sea, 1 km apart.
        val grid = GeoGrid(doubleArrayOf(-20.0, -19.99), doubleArrayOf(149.0, 149.01))
        val sst = SatelliteGrid(SatelliteKind.SEA_TEMP, grid, 0L, floatArrayOf(Float.NaN, 27f, Float.NaN, 27f))
        val area = ConditionsArea("a", "a", -20.0, 149.0, -19.99, 149.01, 0L, null, mapOf(SatelliteKind.SEA_TEMP to sst))
        // Nearer the land cell than the sea one, but between them: sea.
        assertEquals(true, area.isSea(-19.995, 149.003))
        // On the land cell itself.
        assertEquals(false, area.isSea(-20.0, 149.0))
    }

    @Test
    fun `wind from a coarser grid is blended onto the sea points`() {
        val grid = GeoGrid(doubleArrayOf(-20.0, -19.95, -19.9), doubleArrayOf(149.0, 149.05, 149.1))
        val requested = (0 until 3).flatMap { r -> (0 until 3).map { c -> grid.lats[r] to grid.lngs[c] } }
        val marine = ConditionsClient.parseOpenMeteo(
            requested.joinToString(",", "[", "]") { (lat, lng) ->
                """{"latitude":$lat,"longitude":$lng,"hourly":{"time":[0],"ocean_current_velocity":[0.5],"ocean_current_direction":[0]}}"""
            }
        )
        // Wind on the corners only: 4 m/s from the west on the west side, 8 m/s on the east.
        val windGrid = GeoGrid(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.1))
        val wind = ConditionsClient.parseOpenMeteo(
            listOf(4, 8, 4, 8).joinToString(",", "[", "]") { speed ->
                """{"latitude":0,"longitude":0,"hourly":{"time":[0],"wind_speed_10m":[$speed],"wind_direction_10m":[270],"wind_gusts_10m":[${speed + 2}]}}"""
            }
        )
        val f = ConditionsClient.assemble(grid, requested, marine, wind, windGrid = windGrid)
        val middle = f.vector(ForecastField.WIND_U, ForecastField.WIND_V, -19.95, 149.05, 0)!!
        assertEquals(6.0, middle.speed, 1e-4)
        assertEquals(90.0, middle.towardsDegrees, 1e-3)
        assertEquals(8f, f.sample(ForecastField.GUST, -19.95, 149.05, 0), 1e-4f)
    }

    @Test
    fun `old data is flagged, offline sooner`() {
        val now = 100L * 24 * 3_600_000L
        assertNull(ConditionsFeature.staleNote(now - 10 * 60_000L, now, online = false))
        assertTrue(ConditionsFeature.staleNote(now - 3 * 3_600_000L, now, online = false)!!.startsWith("Offline: saved data from 3 h ago"))
        assertNull(ConditionsFeature.staleNote(now - 3 * 3_600_000L, now, online = true))
        assertEquals("Old data: downloaded 13 h ago", ConditionsFeature.staleNote(now - 13 * 3_600_000L, now, online = true))
        assertTrue(ConditionsFeature.staleNote(now - 3 * 24 * 3_600_000L, now, online = false)!!.contains("3 days ago"))
    }

    @Test
    fun `the spot's area reaches 200 km each way`() {
        val b = ConditionsFeature.boxAround(-20.0, 149.0, 200.0)
        assertEquals(-20.0 - 200 / 111.0, b[0], 1e-9)
        assertEquals(-20.0 + 200 / 111.0, b[2], 1e-9)
        // Wider in longitude away from the equator.
        assertTrue(b[3] - b[1] > b[2] - b[0])
        val area = ConditionsArea("spot", "x", b[0], b[1], b[2], b[3], 0L, null, emptyMap())
        assertTrue(area.middleContains(-20.0, 149.0))
        // 150 km north is still inside, but out of the middle: time to move the area.
        assertTrue(area.contains(-20.0 + 150 / 111.0, 149.0))
        assertTrue(!area.middleContains(-20.0 + 150 / 111.0, 149.0))
    }

    @Test
    fun `flow lines follow the map layer unless set`() {
        assertEquals(ArrowChoice.WIND, ArrowChoice.SAME.resolve(ColourChoice.WIND))
        assertEquals(ArrowChoice.CURRENT, ArrowChoice.SAME.resolve(ColourChoice.SEA_TEMP_SATELLITE))
        assertEquals(ArrowChoice.SWELL, ArrowChoice.SAME.resolve(ColourChoice.SWELL))
        assertEquals(ArrowChoice.WIND, ArrowChoice.WIND.resolve(ColourChoice.CURRENT))
        assertNull(ArrowChoice.SAME.fields)
    }

    @Test
    fun `a single open-meteo location is an object, not an array`() {
        val one = ConditionsClient.parseOpenMeteo(
            """{"latitude":-20.0,"longitude":149.0,"hourly":{"time":[0],"wave_height":[null]}}"""
        )
        assertEquals(1, one.size)
        assertTrue(one[0].values.getValue("wave_height")[0].isNaN())
    }

    @Test
    fun `erddap tables become a grid either way up`() {
        val north = """time,altitude,latitude,longitude,chlor_a
UTC,m,degrees_north,degrees_east,mg m^-3
2026-10-27T12:00:00Z,0.0,-19.4583,148.9583,NaN
2026-10-27T12:00:00Z,0.0,-19.4583,149.0417,1.48
2026-10-27T12:00:00Z,0.0,-19.5417,148.9583,0.42
2026-10-27T12:00:00Z,0.0,-19.5417,149.0417,0.30
"""
        val chl = ConditionsClient.parseErddapCsv(north, SatelliteKind.CHLOROPHYLL)
        assertEquals(2, chl.grid.rows)
        assertEquals(-19.5417, chl.grid.south, 1e-9)
        assertEquals(0.42f, chl.sample(-19.5417, 148.9583), 1e-5f)
        assertEquals(1.48f, chl.sample(-19.4583, 149.0417), 1e-5f)
        assertEquals(java.time.Instant.parse("2026-10-27T12:00:00Z").toEpochMilli(), chl.dateMillis)

        val sst = ConditionsClient.parseErddapCsv(
            "time,latitude,longitude,analysed_sst\nUTC,degrees_north,degrees_east,degree_C\n" +
                "2026-10-28T09:00:00Z,-20.5,149.0,NaN\n2026-10-28T09:00:00Z,-20.5,149.01,27.4\n",
            SatelliteKind.SEA_TEMP
        )
        assertEquals(27.4f, sst.sample(-20.5, 149.01), 1e-5f)
    }

    @Test(expected = java.io.IOException::class)
    fun `an all-land erddap answer is an error, not an empty layer`() {
        ConditionsClient.parseErddapCsv(
            "time,latitude,longitude,analysed_sst\nUTC,degrees_north,degrees_east,degree_C\n2026-10-28T09:00:00Z,-20.5,149.0,NaN\n",
            SatelliteKind.SEA_TEMP
        )
    }

    @Test
    fun `areas save and load intact`() {
        val grid = GeoGrid(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.1))
        val forecast = ForecastGrid(grid, longArrayOf(0, hour), mapOf(ForecastField.WAVE_HEIGHT to floatArrayOf(1f, 2f, Float.NaN, 4f, 5f, 6f, 7f, 8f)))
        val sat = SatelliteGrid(SatelliteKind.SEA_TEMP, grid, 123L, floatArrayOf(25f, 26f, Float.NaN, 27f))
        val area = ConditionsArea("id1", "Inshore reef", -20.0, 149.0, -19.9, 149.1, 99L, forecast, mapOf(SatelliteKind.SEA_TEMP to sat))
        val bytes = ByteArrayOutputStream().also { ConditionsStore.write(area, it) }.toByteArray()
        val back = ConditionsStore.read(ByteArrayInputStream(bytes))
        assertEquals("Inshore reef", back.name)
        assertEquals(99L, back.fetchedMillis)
        assertEquals(hour, back.forecast!!.endMillis)
        assertEquals(5f, back.forecast!!.fields.getValue(ForecastField.WAVE_HEIGHT)[4], 0f)
        assertTrue(back.forecast!!.fields.getValue(ForecastField.WAVE_HEIGHT)[2].isNaN())
        assertEquals(123L, back.satellites.getValue(SatelliteKind.SEA_TEMP).dateMillis)
        assertEquals(27f, back.satellites.getValue(SatelliteKind.SEA_TEMP).values[3], 0f)
    }

    @Test
    fun `store lists, renames and deletes`() {
        val dir = kotlin.io.path.createTempDirectory("conditions").toFile()
        try {
            val store = ConditionsStore(dir)
            val grid = GeoGrid(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.1))
            val sat = SatelliteGrid(SatelliteKind.CHLOROPHYLL, grid, 5L, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
            store.save(ConditionsArea("a", "Reef", -20.0, 149.0, -19.9, 149.1, 10L, null, mapOf(SatelliteKind.CHLOROPHYLL to sat)))
            // Nothing read yet: the main thread gets nothing rather than waiting on the disk.
            assertNull(ConditionsStore(dir).cachedSummaries())
            val listed = store.summaries().single()
            assertEquals(listed, store.cachedSummaries()!!.single())
            assertTrue(listed.sameBoundsAs(-20.0, 149.0, -19.9, 149.1))
            assertEquals("Reef", listed.name)
            assertNull(listed.forecastUntilMillis)
            assertEquals(setOf(SatelliteKind.CHLOROPHYLL), listed.satellites)
            store.rename("a", "Outer reef")
            assertEquals("Outer reef", store.summaries().single().name)
            assertEquals("Outer reef", ConditionsStore(dir).load("a")!!.name)
            store.delete("a")
            assertTrue(store.summaries().isEmpty())
            assertNull(store.load("a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sea temperature is stretched to the area so breaks show`() {
        val temps = FloatArray(1000) { 26f + it / 1000f * 1.6f }
        val scale = ColourScale.forChoice(ColourChoice.SEA_TEMP_SATELLITE, temps)
        assertTrue(scale.low > 25.9 && scale.low < 26.2)
        assertTrue(scale.high > 27.4 && scale.high < 27.7)
        // A flat area still gets a 1 °C range.
        val flat = ColourScale.forChoice(ColourChoice.SEA_TEMP_SATELLITE, FloatArray(10) { 20f })
        assertEquals(1.0, flat.high - flat.low, 1e-9)
        // Speeds keep a fixed range, so a colour means the same everywhere.
        val current = ColourScale.forChoice(ColourChoice.CURRENT)
        assertEquals(0.0, current.low, 0.0)
        assertEquals(0, ColourScale.forChoice(ColourChoice.WAVES).colour(Double.NaN))
        // Chlorophyll is on a log scale.
        val chl = ColourScale.forChoice(ColourChoice.CHLOROPHYLL, floatArrayOf(0.1f, 0.2f, 0.5f, 1f, 2f))
        assertEquals(0.5, chl.position(Math.sqrt(chl.low * chl.high)), 1e-6)
    }

    @Test
    fun `wind is coloured like windy`() {
        val wind = ColourScale.forChoice(ColourChoice.WIND)
        // 0 m/s is the palette's blue, 9 m/s (17.5 kn) its green, 21 m/s its purple.
        assertEquals(0xFF6271B7.toInt(), wind.colour(0.0))
        assertEquals(0xFF359F35.toInt(), wind.colour(9 * 1.943844))
        assertEquals(0xFF754A93.toInt(), wind.colour(60.0))
        assertEquals(21 * 1.943844, wind.high, 1e-9)
    }

    @Test
    fun `picture rows follow the map projection`() {
        val rowLats = sortedSetOf<Double>()
        val pixels = ConditionsPicture.render(
            -60.0, 0.0, 60.0, 10.0, 100, ColourScale(0.0, 100.0, false, ColourScale.SPECTRUM),
            sampler = { lat, _ ->
                rowLats += lat
                lat.toFloat() + 60f
            }
        )
        // A picture nobody wants stops at once.
        var asked = 0
        ConditionsPicture.render(-60.0, 0.0, 60.0, 10.0, 100, ColourScale(0.0, 100.0, false, ColourScale.SPECTRUM), { _, _ -> asked++; 0f }) { true }
        assertEquals(0, asked)
        // Mercator stretches the high latitudes: the area is taller than it is wide.
        assertTrue(pixels.height > pixels.width)
        // Rows are evenly spaced on the map, so they are further apart in latitude near the equator.
        val lats = rowLats.toList()
        val nearEquator = lats.zipWithNext { x, y -> y - x }[lats.size / 2]
        val nearTop = lats.zipWithNext { x, y -> y - x }.last()
        assertTrue("$nearEquator vs $nearTop", nearEquator > nearTop * 1.5)
        assertTrue(lats.first() > -60.0 && lats.last() < 60.0)
        assertEquals(0.0, ConditionsPicture.latitudeOf(ConditionsPicture.mercatorY(0.0)), 1e-9)
        assertEquals(-33.5, ConditionsPicture.latitudeOf(ConditionsPicture.mercatorY(-33.5)), 1e-9)
    }

    @Test
    fun `the values box says what is there`() {
        val grid = GeoGrid(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.1))
        fun field(v: Float) = FloatArray(grid.size) { v }
        val (cu, cv) = Flow.parts(0.5, 45.0)
        val (wu, wv) = Flow.parts(5.0, 0.0)
        val forecast = ForecastGrid(
            grid, longArrayOf(0),
            mapOf(
                ForecastField.CURRENT_U to field(cu.toFloat()), ForecastField.CURRENT_V to field(cv.toFloat()),
                ForecastField.WIND_U to field(wu.toFloat()), ForecastField.WIND_V to field(wv.toFloat()),
                ForecastField.GUST to field(8f),
                ForecastField.WAVE_HEIGHT to field(1.2f), ForecastField.WAVE_PERIOD to field(7f),
                ForecastField.WAVE_U to field(-1f), ForecastField.WAVE_V to field(0f),
                ForecastField.SEA_TEMP to field(26.0f)
            )
        )
        val sat = SatelliteGrid(SatelliteKind.SEA_TEMP, grid, 0L, field(26.4f))
        val area = ConditionsArea("a", "x", -20.0, 149.0, -19.9, 149.1, 0L, forecast, mapOf(SatelliteKind.SEA_TEMP to sat))
        val values = ConditionsFeature.values(area, -19.95, 149.05, 0, NavFormat(NavUnits(), TimeZone.getTimeZone("UTC")))
        val text = values.joinToString("\n") { it.text }
        assertEquals("Current 1.0 kn to NE", values[0].text)
        assertEquals(45.0, values[0].towardsDegrees!!, 1e-3)
        assertEquals("Wind 9.7 kn from S, gusts 15.6 kn", values[1].text)
        // The arrow shows where the wind goes: north, from the south.
        assertEquals(0.0, values[1].towardsDegrees!!, 1e-3)
        assertEquals("Waves 1.2 m, 7 s from E", values[2].text)
        // The satellite temperature is preferred to the model's; it has no arrow.
        assertTrue(text, text.contains("Surface 26.4 °C"))
        assertNull(values.last().towardsDegrees)
        assertNotNull(area.forecast)
    }

    // Worldwide -----------------------------------------------------------------

    @Test
    fun `an area stops at the date line and still counts as centred there`() {
        val b = ConditionsFeature.boxAround(-19.0, 179.5, 200.0)
        assertEquals(180.0, b[3], 0.0)
        val area = ConditionsArea("a", "Fiji", b[0], b[1], b[2], b[3], 0L, null, emptyMap())
        assertTrue(area.contains(-19.0, 179.5))
        assertTrue(area.middleContains(-19.0, 179.5))
        val west = ConditionsFeature.boxAround(52.0, -179.8, 200.0)
        assertEquals(-180.0, west[1], 0.0)
        assertTrue(ConditionsArea("b", "Aleutians", west[0], west[1], west[2], west[3], 0L, null, emptyMap()).middleContains(52.0, -179.8))
    }

    @Test
    fun `requests stay inside each data set at the edge of the world`() {
        for (kind in listOf(SatelliteKind.SEA_TEMP, SatelliteKind.CHLOROPHYLL, SatelliteKind.SEA_LEVEL, SatelliteKind.SALINITY)) {
            val urls = ConditionsClient.satelliteUrls(kind, 79.0, 178.0, 80.0, 180.0)
            assertTrue(urls.isNotEmpty())
            urls.forEach { assertTrue(it, !it.contains("(180.000)")) }
        }
        assertTrue(ConditionsClient.satelliteUrls(SatelliteKind.SEA_TEMP, 0.0, 0.0, 1.0, 1.0).size >= 2)
    }

    @Test
    fun `a server that is down moves on to the backup`() {
        val asked = mutableListOf<String>()
        val client = ConditionsClient(
            http = { url ->
                asked += url
                if ("jplMURSST41" in url) throw ConditionsClient.ServiceBusyException("down")
                "time,latitude,longitude,analysed_sst\nUTC,degrees_north,degrees_east,degree_C\n" +
                    "2026-11-02T12:00:00Z,-20.0,149.0,27.5\n2026-11-02T12:00:00Z,-20.0,149.05,27.6\n"
            },
            pause = {}
        )
        val sst = client.satellite(SatelliteKind.SEA_TEMP, -20.0, 149.0, -19.9, 149.1)
        assertEquals(27.5f, sst.values[0], 0.001f)
        // Each MUR server asked twice, then the blended data.
        assertEquals(5, asked.size)
        assertTrue("noaacwBLENDEDsstDNDaily" in asked.last())
    }

    @Test
    fun `salinity falls through to the second satellite when the first has none`() {
        val client = ConditionsClient(
            http = { url ->
                val value = if ("SMAP" in url) "NaN" else "35.5"
                "time,altitude,latitude,longitude,sss\nUTC,m,degrees_north,degrees_east,PSU\n" +
                    "2026-11-01T12:00:00Z,0.0,-19.0,179.0,$value\n2026-11-01T12:00:00Z,0.0,-19.0,179.25,$value\n"
            },
            pause = {}
        )
        assertEquals(35.5f, client.satellite(SatelliteKind.SALINITY, -19.0, 179.0, -18.5, 179.5).values[0], 0.001f)
    }

    @Test
    fun `chlorophyll days blend on a log scale and keep the newest date`() {
        val csv = "time,altitude,latitude,longitude,chlor_a\nUTC,m,degrees_north,degrees_east,mg\n" +
            "2026-10-29T12:00:00Z,0.0,-20.0,149.0,0.1\n2026-10-29T12:00:00Z,0.0,-20.0,149.04,NaN\n" +
            "2026-10-30T12:00:00Z,0.0,-20.0,149.0,1.0\n2026-10-30T12:00:00Z,0.0,-20.0,149.04,0.5\n"
        val chl = ConditionsClient.parseErddapCsv(csv, SatelliteKind.CHLOROPHYLL)
        assertEquals(0.316f, chl.values[0], 0.001f)
        assertEquals(0.5f, chl.values[1], 0.001f)
        assertEquals(java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli(), chl.dateMillis)
    }

    @Test
    fun `sea level comes in metres and is kept in centimetres`() {
        val csv = "time,latitude,longitude,sla\nUTC,degrees_north,degrees_east,m\n2026-11-02T00:00:00Z,-20.0,149.0,0.12\n2026-11-02T00:00:00Z,-20.0,149.25,-0.03\n"
        val level = ConditionsClient.parseErddapCsv(csv, SatelliteKind.SEA_LEVEL)
        assertEquals(12f, level.values[0], 0.001f)
        assertEquals(-3f, level.values[1], 0.001f)
    }

    @Test
    fun `temperature breaks are strongest where the temperature changes`() {
        val grid = GeoGrid(doubleArrayOf(0.0, 0.1, 0.2), doubleArrayOf(0.0, 0.1, 0.2))
        // A 1 degree step across the first two columns; land in one corner.
        val sst = SatelliteGrid(SatelliteKind.SEA_TEMP, grid, 0L, floatArrayOf(20f, 21f, 21f, 20f, 21f, 21f, Float.NaN, 21f, 21f))
        val breaks = temperatureBreaks(sst)
        assertTrue(breaks.values[3] > breaks.values[5])
        assertEquals(0f, breaks.values[5], 0.001f)
        assertTrue(breaks.values[6].isNaN())
        // 1 degree over 0.1 degrees of longitude at the equator, about 11 km.
        assertEquals(1f / 11.12f, breaks.values[3], 0.002f)
    }

    @Test
    fun `sea level colours are centred on the average`() {
        val scale = ColourScale.forChoice(ColourChoice.SEA_LEVEL, floatArrayOf(-3f, 2f, 18f, 4f))
        assertEquals(-scale.low, scale.high, 0.001)
        assertEquals(0.5, scale.position(0.0), 0.001)
    }

    @Test
    fun `the depth an area was downloaded for survives saving`() {
        val grid = GeoGrid(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val sat = SatelliteGrid(SatelliteKind.TEMP_BELOW, grid, 5L, floatArrayOf(1f, 2f, 3f, 4f))
        val out = java.io.ByteArrayOutputStream()
        ConditionsStore.write(ConditionsArea("a", "x", 0.0, 0.0, 1.0, 1.0, 1L, null, mapOf(SatelliteKind.TEMP_BELOW to sat), belowDepthM = 200), out)
        val back = ConditionsStore.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(200, back.belowDepthM)
        assertEquals(3f, back.satellites.getValue(SatelliteKind.TEMP_BELOW).values[2], 0f)
    }

    @Test
    fun `an area saved before the depth was kept still reads`() {
        // A version 1 file with no depth at the end, as earlier releases wrote.
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(java.util.zip.GZIPOutputStream(bytes)).use { o ->
            o.writeInt(0x53424344); o.writeInt(1); o.writeUTF("a"); o.writeUTF("x")
            repeat(4) { o.writeDouble(0.0) }
            o.writeLong(1L); o.writeInt(0); o.writeBoolean(false)
        }
        val back = ConditionsStore.read(java.io.ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(ConditionsArea.DEFAULT_BELOW_DEPTH_M, back.belowDepthM)
    }

    @Test
    fun `HYCOM's NetCDF answer becomes a picture, south up and west first`() {
        val nc = netCdf(
            lats = doubleArrayOf(-20.0, -19.9),
            lngs = doubleArrayOf(359.8, 359.9),
            temps = shortArrayOf(5000, -30000, 4000, 3000)
        )
        val grid = ConditionsClient.parseHycom(nc, SatelliteKind.TEMP_BELOW)
        assertEquals(-0.2, grid.grid.lngs[0], 1e-6)
        assertEquals(25f, grid.values[0], 0.001f)
        assertTrue(grid.values[1].isNaN())
        assertEquals(24f, grid.values[2], 0.001f)
        assertEquals(java.time.Instant.parse("2000-01-02T12:00:00Z").toEpochMilli(), grid.dateMillis)
    }

    @Test
    fun `HYCOM's random failures are asked through`() {
        var asked = 0
        val good = netCdf(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.08), shortArrayOf(5000, 5000, 5000, 5000))
        val client = ConditionsClient(
            httpBytes = {
                if (++asked < 5) throw ConditionsClient.ServiceBusyException("No space left on device")
                good
            },
            pause = {}
        )
        assertEquals(25f, client.satellite(SatelliteKind.TEMP_BELOW, -20.0, 149.0, -19.9, 149.1).values[0], 0.001f)
        assertEquals(5, asked)
    }

    @Test
    fun `longitudes past the date line stay in order`() {
        val nc = netCdf(doubleArrayOf(-20.0, -19.9), doubleArrayOf(180.0, 180.08), shortArrayOf(1000, 2000, 3000, 4000))
        val grid = ConditionsClient.parseHycom(nc, SatelliteKind.TEMP_BELOW)
        assertEquals(-180.0, grid.grid.lngs[0], 1e-6)
        assertEquals(21f, grid.values[0], 0.001f)
    }

    /** A small NetCDF-3 file laid out the way HYCOM's server does: time, depth, lat, lon. */
    private fun netCdf(lats: DoubleArray, lngs: DoubleArray, temps: ShortArray): ByteArray {
        val head = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(head)
        fun name(s: String) { d.writeInt(s.length); d.writeBytes(s); repeat((4 - s.length % 4) % 4) { d.writeByte(0) } }
        d.writeBytes("CDF\u0001"); d.writeInt(0)
        d.writeInt(10); d.writeInt(4)
        name("time"); d.writeInt(1); name("depth"); d.writeInt(1); name("lat"); d.writeInt(lats.size); name("lon"); d.writeInt(lngs.size)
        d.writeInt(0); d.writeInt(0)
        d.writeInt(11); d.writeInt(4)
        val headerSize = 512
        var at = headerSize
        fun variable(n: String, dims: IntArray, attrs: () -> Unit, type: Int, bytes: Int) {
            name(n); d.writeInt(dims.size); dims.forEach { d.writeInt(it) }
            attrs()
            d.writeInt(type); d.writeInt(bytes); d.writeInt(at); at += (bytes + 3) / 4 * 4
        }
        fun noAttrs() { d.writeInt(0); d.writeInt(0) }
        variable("time", intArrayOf(0), {
            d.writeInt(12); d.writeInt(1)
            name("units"); d.writeInt(2); d.writeInt(31); d.writeBytes("hours since 2000-01-01 12:00:00"); d.writeByte(0)
        }, 6, 8)
        variable("lat", intArrayOf(2), ::noAttrs, 6, lats.size * 8)
        variable("lon", intArrayOf(3), ::noAttrs, 6, lngs.size * 8)
        variable("water_temp", intArrayOf(0, 1, 2, 3), {
            d.writeInt(12); d.writeInt(3)
            name("scale_factor"); d.writeInt(5); d.writeInt(1); d.writeFloat(0.001f)
            name("add_offset"); d.writeInt(5); d.writeInt(1); d.writeFloat(20f)
            name("_FillValue"); d.writeInt(3); d.writeInt(1); d.writeShort(-30000); d.writeShort(0)
        }, 3, temps.size * 2)
        check(head.size() <= headerSize)
        while (head.size() < headerSize) d.writeByte(0)
        d.writeDouble(24.0)
        lats.forEach { d.writeDouble(it) }
        lngs.forEach { d.writeDouble(it) }
        temps.forEach { d.writeShort(it.toInt()) }
        return head.toByteArray()
    }
}
