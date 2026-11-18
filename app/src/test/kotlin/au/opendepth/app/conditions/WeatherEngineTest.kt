package au.opendepth.app.conditions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The sea conditions engine through the situations that went wrong before: offline, failures,
 * the boat moving far, GPS fixes every second, inland, far offshore, and never a download loop.
 * Everything runs inline (no threads), with a fake network and clock.
 */
class WeatherEngineTest {
    private val hour = 3_600_000L
    private var now = 1_800_000_000_000L
    private var online = true
    private val dir: File = kotlin.io.path.createTempDirectory("weather").toFile()
    private val kv = HashMap<String, String>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val saved = mutableListOf<ConditionsArea>()
    private val finished = mutableListOf<ConditionsArea?>()
    private var settings = WeatherSettings(days = 2, withSatellite = true, depthM = 10, autoUpdate = true)

    /** A network that answers from a simple model: sea everywhere east of [coastLng]. */
    private inner class FakeNet : WeatherNet {
        var forecasts = 0
        var satellites = 0
        var seaQueries = 0
        var fail: (() -> Throwable)? = null
        var run: Long? = null
        var coastLng = -1000.0

        override fun forecast(south: Double, west: Double, north: Double, east: Double, days: Int, onProgress: (Double) -> Unit): ForecastGrid {
            forecasts++
            fail?.let { throw it() }
            val grid = GeoGrid(DoubleArray(5) { south + (north - south) * it / 4 }, DoubleArray(5) { west + (east - west) * it / 4 })
            val times = LongArray(days * 24 + 2) { now - hour + it * hour }
            val n = times.size * grid.size
            return ForecastGrid(grid, times, mapOf(
                ForecastField.WIND_U to FloatArray(n) { 5f }, ForecastField.WIND_V to FloatArray(n) { 0f },
                ForecastField.WAVE_HEIGHT to FloatArray(n) { 1f }
            ))
        }

        override fun satellite(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double, depthM: Int): SatelliteGrid {
            satellites++
            fail?.let { throw it() }
            val grid = GeoGrid(doubleArrayOf(south, north), doubleArrayOf(west, east))
            return SatelliteGrid(kind, grid, now, FloatArray(grid.size) { 20f })
        }

        override fun seaAt(points: List<Pair<Double, Double>>): List<Pair<Double, Double>?> {
            seaQueries++
            return points.map { if (it.second > coastLng) it else null }
        }

        override fun latestRun() = run
        override fun coastline(south: Double, west: Double, north: Double, east: Double) {}
    }

    private val net = FakeNet()
    private val engine = WeatherEngine(
        store = ConditionsStore(dir),
        net = net,
        kv = object : WeatherKeyValues {
            override fun get(key: String) = kv[key]
            override fun put(key: String, value: String?) { if (value == null) kv.remove(key) else kv[key] = value }
        },
        settings = { settings },
        clock = { now },
        online = { online },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        parallel = Dispatchers.Unconfined,
        timer = { delay, run -> val t = (now + delay) to run; timers += t; ({ timers.remove(t) }) },
        events = object : WeatherEvents {
            override fun saved(area: ConditionsArea, isSpot: Boolean) { saved += area }
            override fun stateChanged(state: WeatherState) {}
            override fun finished(area: ConditionsArea?, problems: List<String>, userAsked: Boolean, note: String?) { finished += area }
        },
        log = {}
    )

    private val atSea = -20.0 to 150.0

    private fun advance(ms: Long) {
        now += ms
        val due = timers.filter { it.first <= now }
        timers.removeAll(due)
        due.forEach { it.second() }
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aFreshStartDownloadsOnceAndThenStaysQuiet() {
        engine.setSpot(atSea)
        assertEquals(1, net.forecasts)
        assertNotNull(engine.spotArea)
        assertTrue(engine.spotArea!!.contains(atSea.first, atSea.second))
        assertEquals(WeatherState.Idle, engine.state)
        // GPS fixes every second for ten minutes, the boat drifting a little: nothing more.
        repeat(600) { i ->
            now += 1_000
            engine.setSpot(atSea.first + i * 1e-5 to atSea.second)
        }
        assertEquals(1, net.forecasts)
    }

    @Test
    fun theForecastShowsBeforeTheSatellitePictures() {
        engine.setSpot(atSea)
        // Saved twice: the forecast first (no pictures yet), then with them.
        assertEquals(2, saved.size)
        assertTrue(saved[0].satellites.isEmpty())
        assertNotNull(saved[0].forecast)
        assertEquals(SatelliteKind.entries.size, saved[1].satellites.size)
    }

    @Test
    fun offlineItWaitsThenGoesWhenTheInternetIsBack() {
        online = false
        engine.setSpot(atSea)
        assertEquals(0, net.forecasts)
        assertTrue(engine.state is WeatherState.Waiting)
        assertFalse(engine.busy)
        online = true
        engine.onOnline()
        assertEquals(1, net.forecasts)
        assertEquals(WeatherState.Idle, engine.state)
    }

    @Test
    fun aFailureKeepsTheOldDataAndTriesAgainSoon() {
        engine.setSpot(atSea)
        val first = engine.spotArea
        advance(7 * hour)
        net.fail = { IOException("Open-Meteo is busy") }
        engine.setSpot(atSea)
        assertEquals(2, net.forecasts)
        // The old data is still there to show.
        assertEquals(first?.fetchedMillis, engine.spotArea?.fetchedMillis)
        val waiting = engine.state as WeatherState.Waiting
        assertEquals(now + 30_000L, waiting.untilMillis)
        // It comes back on its own.
        net.fail = null
        advance(30_000L)
        assertEquals(3, net.forecasts)
        assertEquals(WeatherState.Idle, engine.state)
        assertTrue(engine.spotArea!!.fetchedMillis > first!!.fetchedMillis)
    }

    @Test
    fun anUnexpectedErrorNeverLeavesItStuck() {
        net.fail = { IllegalStateException("something odd") }
        engine.setSpot(atSea)
        assertFalse(engine.busy)
        net.fail = null
        assertNull(engine.evaluate(userAsked = true))
        assertNotNull(engine.spotArea)
        assertFalse(engine.busy)
    }

    @Test
    fun theBoatMovingFarGetsANewArea() {
        engine.setSpot(atSea)
        // 50 km: still well inside.
        engine.setSpot(atSea.first - 0.45 to atSea.second)
        assertEquals(1, net.forecasts)
        // 150 km: a new area round the boat.
        engine.setSpot(atSea.first - 1.35 to atSea.second)
        assertEquals(2, net.forecasts)
        assertTrue(engine.spotArea!!.middleContains(atSea.first - 1.35, atSea.second))
    }

    @Test
    fun aNewerForecastRunIsDownloaded() {
        engine.setSpot(atSea)
        advance(hour)
        net.run = now
        engine.checkRun(force = true)
        assertEquals(2, net.forecasts)
    }

    @Test
    fun neverMoreThanSixAutomaticDownloadsAnHour() {
        net.fail = { IOException("down") }
        engine.setSpot(atSea)
        // An hour of retries.
        repeat(120) { advance(30_000L); engine.setSpot(atSea) }
        assertTrue("${net.forecasts} downloads in an hour", net.forecasts <= WeatherEngine.MAX_AUTO_PER_HOUR)
    }

    @Test
    fun satellitePicturesOnceADay() {
        engine.setSpot(atSea)
        assertEquals(SatelliteKind.entries.size, net.satellites)
        advance(7 * hour)
        engine.setSpot(atSea)
        assertEquals(2, net.forecasts)
        assertEquals(SatelliteKind.entries.size, net.satellites)
        // The pictures are kept with the new forecast.
        assertEquals(SatelliteKind.entries.size, engine.spotArea!!.satellites.size)
        advance(14 * hour)
        engine.setSpot(atSea)
        assertEquals(2 * SatelliteKind.entries.size, net.satellites)
    }

    @Test
    fun inlandTheAreaGoesOverTheSea() {
        // The coast at 151°E; the boat 100 km inland, west of it.
        net.coastLng = 151.0
        val inland = -20.0 to 150.05
        engine.setSpot(inland)
        val a = engine.spotArea!!
        assertTrue("middle ${(a.west + a.east) / 2}", (a.west + a.east) / 2 > 151.0)
        assertNotNull(engine.nearestSeaFor(inland))
    }

    @Test
    fun farOffshoreTheAreaIsRoundTheBoat() {
        val offshore = -30.0 to 158.0
        engine.setSpot(offshore)
        val a = engine.spotArea!!
        assertTrue(a.middleContains(offshore.first, offshore.second))
        // One point asked, not the rings.
        assertEquals(1, net.seaQueries)
    }

    @Test
    fun askingWhileOfflineSaysSoAndLeavesItReady() {
        online = false
        engine.setSpot(atSea)
        assertNotNull(engine.evaluate(userAsked = true))
        assertFalse(engine.busy)
        online = true
        assertNull(engine.evaluate(userAsked = true))
        assertNotNull(engine.spotArea)
    }

    @Test
    fun theDepthPictureLeavesTheForecastsAgeAlone() {
        engine.setSpot(atSea)
        val fetched = engine.spotArea!!.fetchedMillis
        advance(2 * hour)
        settings = settings.copy(depthM = 50)
        engine.downloadDepth(ConditionsFeature.SPOT_ID)
        assertEquals(fetched, engine.spotArea!!.fetchedMillis)
        assertEquals(50, engine.spotArea!!.belowDepthM)
    }
}

class WeatherReadingsTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun area(id: String, fetched: Long, south: Double = -21.0, west: Double = 149.0, north: Double = -19.0, east: Double = 151.0): ConditionsArea {
        val grid = GeoGrid(DoubleArray(5) { south + (north - south) * it / 4 }, DoubleArray(5) { west + (east - west) * it / 4 })
        val times = LongArray(48) { now - hour + it * hour }
        return ConditionsArea(id, id, south, west, north, east, fetched, ForecastGrid(grid, times, emptyMap()), emptyMap())
    }

    @Test
    fun atSeaThePointItself() {
        val a = area("a", now)
        val r = WeatherReadings.best(listOf(a), -20.0, 150.0, now) { _, _, _ -> true }!!
        assertEquals(0.0, r.distanceKm, 0.0)
        assertEquals(-20.0, r.lat, 0.0)
    }

    @Test
    fun onLandTheNearestSea() {
        val a = area("a", now)
        // Sea only east of 150.4.
        val r = WeatherReadings.best(listOf(a), -20.0, 150.0, now) { _, _, lng -> lng > 150.4 }!!
        assertEquals(150.5, r.lng, 1e-9)
        assertTrue(r.distanceKm in 40.0..60.0)
    }

    @Test
    fun theNewestDataWins() {
        val old = area("old", now - 5 * hour)
        val fresh = area("fresh", now - hour)
        assertEquals("fresh", WeatherReadings.best(listOf(old, fresh), -20.0, 150.0, now) { _, _, _ -> true }!!.area.id)
    }

    @Test
    fun nothingCoveringGivesNothing() {
        assertNull(WeatherReadings.best(listOf(area("a", now)), -40.0, 150.0, now) { _, _, _ -> true })
    }
}
