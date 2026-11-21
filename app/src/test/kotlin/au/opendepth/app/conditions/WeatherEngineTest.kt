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
    private val latestFlags = mutableListOf<Boolean>()
    private var settings = WeatherSettings(days = 2, withSatellite = true, depthM = 10, autoUpdate = true)

    /** A network that answers from a simple model: sea everywhere east of [coastLng]. */
    private inner class FakeNet : WeatherNet {
        var forecasts = 0
        var backups = 0
        var satellites = 0
        var seaQueries = 0
        var fail: (() -> Throwable)? = null
        /** The backup is off unless a test turns it on. */
        var backupFail: (() -> Throwable)? = { IOException("no backup in this test") }
        /** What a picture's server does, by picture. */
        var satelliteFail: ((SatelliteKind) -> Throwable?)? = null
        var run: Long? = null
        /** The sea models' newest run, when it isn't the newest of all ([run]). */
        var seaRun: Long? = null
        /** Downloads that were the wind alone. */
        var windOnly = 0
        /** The wind alone can't be had (the whole forecast still can). */
        var windOnlyFail: (() -> Throwable)? = null
        var coastLng = -1000.0
        /** The hours ahead of now the service gives, whatever was asked (null: what was asked). */
        var hoursAhead: Int? = null
        /** The whole forecast comes with the backup's wind, as the app's does when the main service's wind fails. */
        var backupWind = false
        /** Which area the engine said it was downloading the forecast of, as each request came. */
        val workingAtForecast = mutableListOf<String?>()
        val workingAtSea = mutableListOf<String?>()
        val workingAtSatellite = mutableListOf<String?>()

        override fun forecast(south: Double, west: Double, north: Double, east: Double, days: Int, userAsked: Boolean, keepSea: ForecastGrid?, onProgress: (Double) -> Unit): ForecastGrid {
            forecasts++
            workingAtForecast += engine.workingOnId
            fail?.let { throw it() }
            if (keepSea != null) windOnlyFail?.let { throw it() }
            val fresh = answer(south, west, north, east, hoursAhead ?: (days * 24 + 1))
            if (keepSea == null) return if (backupWind) ForecastGrid(fresh.grid, fresh.times, fresh.fields, windFromBackup = true) else fresh
            // As the app's does: the wind alone, laid over the sea values kept, to their last hour.
            windOnly++
            return fresh.withSeaFrom(keepSea)
        }

        override fun backupForecast(south: Double, west: Double, north: Double, east: Double, days: Int): ForecastGrid {
            backups++
            backupFail?.let { throw it() }
            return answer(south, west, north, east, days * 24 + 1)
        }

        /** As the service answers: from three hours back to [ahead] hours on. */
        private fun answer(south: Double, west: Double, north: Double, east: Double, ahead: Int): ForecastGrid {
            val grid = GeoGrid(DoubleArray(5) { south + (north - south) * it / 4 }, DoubleArray(5) { west + (east - west) * it / 4 })
            val times = LongArray(ahead + 3) { now - 3 * hour + it * hour }
            val n = times.size * grid.size
            return ForecastGrid(grid, times, mapOf(
                ForecastField.WIND_U to FloatArray(n) { 5f }, ForecastField.WIND_V to FloatArray(n) { 0f },
                ForecastField.WAVE_HEIGHT to FloatArray(n) { 1f }
            ))
        }

        override fun satellite(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double, depthM: Int): SatelliteGrid {
            satellites++
            workingAtSatellite += engine.workingOnId
            fail?.let { throw it() }
            satelliteFail?.invoke(kind)?.let { throw it }
            val grid = GeoGrid(doubleArrayOf(south, north), doubleArrayOf(west, east))
            return SatelliteGrid(kind, grid, now, FloatArray(grid.size) { 20f })
        }

        override fun seaAt(points: List<Pair<Double, Double>>): List<Pair<Double, Double>?> {
            seaQueries++
            workingAtSea += engine.workingOnId
            return points.map { if (it.second > coastLng) it else null }
        }

        override fun latestRuns() = RunTimes(run, seaRun ?: run)
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
            override fun finished(area: ConditionsArea?, problems: List<String>, userAsked: Boolean, note: String?, alreadyLatest: Boolean) {
                finished += area
                latestFlags += alreadyLatest
                said += problems
            }
        },
        log = {}
    )

    private val said = mutableListOf<List<String>>()
    private val atSea = -20.0 to 150.0

    private fun advance(ms: Long) {
        now += ms
        val due = timers.filter { it.first <= now }
        timers.removeAll(due)
        due.forEach { it.second() }
    }

    /** A GPS fix every ten seconds for [hours], the timers firing as they fall due. */
    private fun sail(hours: Int) = repeat(hours * 360) {
        advance(10_000L)
        engine.setSpot(atSea)
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
    fun aNewerForecastRunIsDownloadedOnceWhatIsSavedIsAFewHoursOld() {
        engine.setSpot(atSea)
        advance(hour)
        net.run = now
        engine.checkRun(force = true)
        // Not every run of every model is chased: an hour-old forecast is still fine.
        assertEquals(1, net.forecasts)
        advance(4 * hour)
        engine.setSpot(atSea)
        assertEquals(2, net.forecasts)
        // That run is in hand now: it isn't downloaded again.
        sail(6)
        assertEquals(2, net.forecasts)
    }

    @Test
    fun whenOnlyTheWindHasANewRunOnlyTheWindIsDownloaded() {
        // As the service gives it: the days wanted and twelve hours more.
        net.hoursAhead = 2 * 24 + 13
        val seaRun = now - hour
        net.run = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        assertEquals(1, net.forecasts)
        assertEquals(0, net.windOnly)
        val first = engine.spotArea!!
        // Six hours on the wind model has a new run; the wave and current models have not.
        advance(6 * hour)
        net.run = now
        net.seaRun = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        assertEquals(2, net.forecasts)
        assertEquals(1, net.windOnly)
        val second = engine.spotArea!!
        // The same block, waves and all, to the same last hour, and still the days wanted.
        assertTrue(second.sameBoundsAs(first.south, first.west, first.north, first.east))
        assertTrue(second.forecast!!.has(ForecastField.WAVE_HEIGHT) && second.forecast!!.has(ForecastField.WIND_U))
        assertEquals(first.forecast!!.endMillis, second.forecast!!.endMillis)
        assertTrue(second.forecast!!.endMillis - now >= 2 * 24 * hour)
        assertEquals(now, second.fetchedMillis)
        // That wind run is in hand: nothing more is downloaded for it.
        sail(3)
        assertEquals(2, net.forecasts)
        // Twelve hours after the first, the wave model's run: everything is downloaded.
        advance(3 * hour)
        net.run = now
        net.seaRun = now
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        assertEquals(3, net.forecasts)
        assertEquals(1, net.windOnly)
        assertTrue(engine.spotArea!!.forecast!!.endMillis > first.forecast!!.endMillis)
    }

    @Test
    fun whenTheWindAloneCantBeHadTheWholeForecastIsAskedFor() {
        net.hoursAhead = 2 * 24 + 13
        val seaRun = now - hour
        net.run = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        advance(6 * hour)
        net.run = now
        net.seaRun = seaRun
        net.windOnlyFail = { IOException("the wind service is down") }
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        // One try of the wind alone, then everything: saved, and nothing left waiting on a retry.
        assertEquals(3, net.forecasts)
        assertEquals(0, net.windOnly)
        assertEquals(now, engine.spotArea!!.fetchedMillis)
        assertTrue(engine.state is WeatherState.Idle)
        // The sea values came down afresh: the next new wind run is the wind alone again.
        net.windOnlyFail = null
        advance(6 * hour)
        net.run = now
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        assertEquals(4, net.forecasts)
        assertEquals(1, net.windOnly)
    }

    @Test
    fun theSeaValuesAreNotKeptPastTheDaysWanted() {
        net.hoursAhead = 2 * 24 + 13
        val seaRun = now - hour
        net.run = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        // The wave model's run is late: a day on there is still nothing newer of it, but there is of the wind.
        advance(24 * hour)
        net.run = now
        net.seaRun = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        // What's saved no longer reaches two days ahead: all of it is downloaded.
        assertEquals(2, net.forecasts)
        assertEquals(0, net.windOnly)
        assertTrue(engine.spotArea!!.forecast!!.endMillis - now >= 2 * 24 * hour)
    }

    @Test
    fun aBoatSomewhereNewGetsItsWholeForecastThere() {
        net.hoursAhead = 2 * 24 + 13
        val seaRun = now - hour
        net.run = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        // 300 km on, with nothing newer out for the sea: the new place still gets everything.
        advance(hour)
        val far = atSea.first - 2.7 to atSea.second
        engine.setSpot(far)
        assertEquals(2, net.forecasts)
        assertEquals(0, net.windOnly)
        assertTrue(engine.spotArea!!.middleContains(far.first, far.second))
    }

    @Test
    fun withTheSeaModelsStatusUnknownEverythingIsDownloaded() {
        net.hoursAhead = 2 * 24 + 13
        engine.setSpot(atSea)
        // No status was read before the first download: what the sea values' run was isn't known.
        advance(6 * hour)
        net.run = now
        net.seaRun = now - 12 * hour
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        assertEquals(2, net.forecasts)
        assertEquals(0, net.windOnly)
    }

    @Test
    fun withNoNewerRunOutNothingIsDownloadedAllDay() {
        net.run = now - hour
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        // The service is asked (free) and has nothing newer: no downloads on the off-chance.
        repeat(20) {
            advance(hour)
            engine.checkRun(force = true)
            engine.setSpot(atSea)
        }
        assertEquals(1, net.forecasts)
    }

    @Test
    fun oneDayOfForecastIsDownloadedOnce() {
        settings = settings.copy(days = 1)
        engine.setSpot(atSea)
        sail(5)
        assertEquals(1, net.forecasts)
    }

    @Test
    fun aForecastThatStaysShortIsAskedForNoMoreThanHourly() {
        // A service that only ever has ten hours ahead: this went round six times an hour before.
        net.hoursAhead = 10
        engine.setSpot(atSea)
        sail(5)
        assertTrue("${net.forecasts} downloads in five hours", net.forecasts <= 6)
    }

    @Test
    fun aPhoneClockSetBehindDoesNotLoop() {
        // The service's newest run reads as six hours in the future by this phone's clock.
        net.run = now + 6 * hour
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        sail(12)
        assertEquals(1, net.forecasts)
        // A run that really is newer is still fetched.
        net.run = net.run!! + hour
        engine.checkRun(force = true)
        assertEquals(2, net.forecasts)
    }

    @Test
    fun askedForWithNothingNewerOutKeepsWhatIsSaved() {
        net.run = now - hour
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        advance(10 * 60_000L)
        assertNull(engine.evaluate(userAsked = true))
        // No download spent on the same forecast, and the user is told so.
        assertEquals(1, net.forecasts)
        assertTrue(latestFlags.last())
        assertNotNull(finished.last())
        // A newer run comes out: asked again, it downloads.
        advance(20 * 60_000L)
        net.run = now
        assertNull(engine.evaluate(userAsked = true))
        assertEquals(2, net.forecasts)
        assertFalse(latestFlags.last())
    }

    @Test
    fun askedForAfterTheSpotMovedDownloads() {
        net.run = now - hour
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        engine.setSpot(atSea.first - 1.35 to atSea.second)
        assertEquals(2, net.forecasts)
    }

    @Test
    fun theBackupTakesOverWhenTheMainServiceIsDown() {
        net.backupFail = null
        net.fail = { IOException("down") }
        engine.setSpot(atSea)
        assertEquals(1, net.backups)
        assertTrue(engine.spotArea!!.isBackup)
        assertTrue(engine.state is WeatherState.Waiting)
        // Still down an hour on: the main one is tried now and then, the backup not fetched again.
        sail(1)
        assertEquals(1, net.backups)
        assertTrue("${net.forecasts} tries of the main service", net.forecasts <= 1 + WeatherEngine.MAX_AUTO_PER_HOUR)
        // Back: the main forecast replaces the backup.
        net.fail = null
        sail(1)
        assertFalse(engine.spotArea!!.isBackup)
        assertEquals(WeatherState.Idle, engine.state)
    }

    @Test
    fun aGoodSavedForecastIsKeptRatherThanTheBackup() {
        net.backupFail = null
        engine.setSpot(atSea)
        advance(5 * hour)
        net.run = now
        net.fail = { IOException("down") }
        engine.checkRun(force = true)
        // Five hours old with two days still to run: better than the coarse backup.
        assertEquals(2, net.forecasts)
        assertEquals(0, net.backups)
        assertFalse(engine.spotArea!!.isBackup)
    }

    @Test
    fun aReachedLimitIsWaitedOutNotHammered() {
        val until = now + 2 * hour
        net.fail = { LimitReachedException(until, "the free forecast service's hourly limit is reached") }
        engine.setSpot(atSea)
        assertEquals(1, net.forecasts)
        assertTrue((engine.state as WeatherState.Waiting).untilMillis >= until)
        sail(1)
        assertEquals(1, net.forecasts)
        net.fail = null
        sail(2)
        assertEquals(2, net.forecasts)
        assertNotNull(engine.spotArea)
    }

    @Test
    fun movingFarWhileALimitIsWaitedOutStillGetsTheNewPlaceItsBackup() {
        net.backupFail = null
        val until = now + 2 * hour
        net.fail = { LimitReachedException(until, "the free forecast service's daily limit is reached") }
        engine.setSpot(atSea)
        assertEquals(1, net.backups)
        // 300 km on, an hour later, the limit still in force.
        advance(hour)
        val far = atSea.first - 2.7 to atSea.second
        engine.setSpot(far)
        assertEquals(2, net.backups)
        assertTrue(engine.spotArea!!.middleContains(far.first, far.second))
        // And it doesn't go round: the same place, fix after fix, asks for nothing more.
        repeat(600) {
            now += 1_000
            engine.setSpot(far)
        }
        assertEquals(2, net.backups)
        assertTrue("${net.forecasts} tries of the main service", net.forecasts <= 3)
    }

    @Test
    fun aPictureWhoseServerFailedIsFetchedAgainByItself() {
        var down = true
        net.satelliteFail = { kind -> if (kind == SatelliteKind.SEA_LEVEL && down) IOException("503") else null }
        engine.setSpot(atSea)
        assertFalse(SatelliteKind.SEA_LEVEL in engine.spotArea!!.satellites)
        down = false
        sail(2)
        assertTrue(SatelliteKind.SEA_LEVEL in engine.spotArea!!.satellites)
        // Just the picture: the forecast wasn't downloaded again for it.
        assertEquals(1, net.forecasts)
        assertEquals(SatelliteKind.entries.size + 1, net.satellites)
    }

    @Test
    fun aPictureWithNothingHereTodayWaitsForTomorrow() {
        net.satelliteFail = { kind -> if (kind == SatelliteKind.SALINITY) ConditionsClient.NoDataException("nothing here today") else null }
        engine.setSpot(atSea)
        val asked = net.satellites
        sail(3)
        assertEquals(asked, net.satellites)
    }

    @Test
    fun automaticDownloadsAreCappedEachHour() {
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
    fun aSatelliteKindNewInAnUpdateComesWithTheNextUpdate() {
        // Saved by an older version: every picture but water clarity, and no record of what was asked for.
        net.satelliteFail = { kind -> if (kind == SatelliteKind.WATER_CLARITY) ConditionsClient.NoDataException("none") else null }
        engine.setSpot(atSea)
        kv.keys.filter { it.startsWith(WeatherEngine.KEY_SATELLITES_TRIED) }.forEach { kv.remove(it) }
        net.satelliteFail = null
        val before = net.satellites
        // Asked for again soon after: only the new kind comes, not the day's whole set.
        advance(5 * 60_000L)
        engine.evaluate(userAsked = true)
        assertEquals(before + 1, net.satellites)
        assertNotNull(engine.spotArea!!.satellites[SatelliteKind.WATER_CLARITY])
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
    fun farInlandWithNoSeaInReachNothingIsDownloaded() {
        // The coast 1,000 km to the east: no sea within the rings' 260 km.
        net.coastLng = 160.0
        val inland = -20.0 to 150.0
        engine.setSpot(inland)
        assertEquals(0, net.forecasts)
        assertNull(engine.spotArea)
        assertTrue(engine.noSeaInReach)
        // Fix after fix, for hours: nothing more is asked of the service.
        val looks = net.seaQueries
        repeat(600) {
            now += 1_000
            engine.setSpot(inland)
        }
        advance(5 * hour)
        engine.setSpot(inland)
        assertEquals(looks, net.seaQueries)
        assertEquals(0, net.forecasts + net.backups)
        // After six it looks once more (the service may only have been missing its data), and no oftener.
        advance(2 * hour)
        engine.setSpot(inland)
        assertEquals(looks + 2, net.seaQueries)
        repeat(600) {
            now += 1_000
            engine.setSpot(inland)
        }
        assertEquals(looks + 2, net.seaQueries)
        assertEquals(0, net.forecasts + net.backups)
        // Asked for by hand: it says why not, and still downloads nothing.
        engine.evaluate(userAsked = true)
        assertEquals(0, net.forecasts)
        assertTrue(said.last().toString(), said.last().any { "No sea within 260 km" in it })
        // Towed to within reach of the coast: the sea is found and the area comes.
        val nearer = -20.0 to 159.5
        engine.setSpot(nearer)
        assertEquals(1, net.forecasts)
        assertFalse(engine.noSeaInReach)
        assertNotNull(engine.nearestSeaFor(nearer))
    }

    @Test
    fun aFirstDownloadIsMarkedWithTheRunsItCameFrom() {
        net.hoursAhead = 2 * 24 + 13
        // Nothing asked of the service yet when the first fix comes: the status is read first.
        val seaRun = now - hour
        net.run = seaRun
        engine.setSpot(atSea)
        assertEquals(1, net.forecasts)
        assertEquals(seaRun, engine.runSeen(ConditionsFeature.SPOT_ID))
        // So the next new wind run is the wind alone, from the first day.
        advance(6 * hour)
        net.run = now
        net.seaRun = seaRun
        engine.checkRun(force = true)
        engine.setSpot(atSea)
        assertEquals(1, net.windOnly)
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

    /** The spot downloaded with the backup's wind (the main service's wind failed), nothing newer out for the sea. */
    private fun spotWithBackupWind(): ConditionsArea {
        net.hoursAhead = 2 * 24 + 13
        net.run = now - hour
        engine.checkRun(force = true)
        net.backupWind = true
        engine.setSpot(atSea)
        net.backupWind = false
        assertEquals(1, net.forecasts)
        return engine.spotArea!!
    }

    @Test
    fun theBackupsWindIsSavedAsSuchNotPassedOffAsTheMainForecast() {
        val a = spotWithBackupWind()
        assertTrue(a.windFromBackup)
        assertFalse(a.isBackup)
        // In the file too, so it's known after a restart.
        assertTrue(ConditionsStore(dir).load(ConditionsFeature.SPOT_ID)!!.windFromBackup)
        // The user is told, and the engine means to try the usual wind again.
        assertTrue(said.last().toString(), said.last().any { "backup's" in it })
        val waiting = engine.state as WeatherState.Waiting
        assertTrue(waiting.reason, "backup wind" in waiting.reason)
        // Asked for by hand with no newer run out: not "already the latest". The usual wind is
        // fetched, alone, over the sea values that are still the newest.
        advance(2 * 60_000L)
        assertNull(engine.evaluate(userAsked = true))
        assertEquals(2, net.forecasts)
        assertEquals(1, net.windOnly)
        assertFalse(latestFlags.last())
        assertFalse(engine.spotArea!!.windFromBackup)
        assertEquals(WeatherState.Idle, engine.state)
        // Now it is the latest: asked again, nothing is downloaded.
        advance(20 * 60_000L)
        assertNull(engine.evaluate(userAsked = true))
        assertEquals(2, net.forecasts)
        assertTrue(latestFlags.last())
    }

    @Test
    fun withTheBackupsWindOnShowTheUsualWindIsTriedAgainBeforeLong() {
        spotWithBackupWind()
        // A few minutes: left alone.
        repeat(30) { advance(10_000L); engine.setSpot(atSea) }
        assertEquals(1, net.forecasts)
        // Then the wind alone, by itself; and once it's in, quiet.
        sail(2)
        assertEquals(2, net.forecasts)
        assertEquals(1, net.windOnly)
        assertFalse(engine.spotArea!!.windFromBackup)
        assertEquals(WeatherState.Idle, engine.state)
    }

    @Test
    fun aWindThatStillFailsDoesNotDownloadTheSameSeaForecastAgain() {
        val a = spotWithBackupWind()
        net.windOnlyFail = { IOException("the wind service is down") }
        advance(11 * 60_000L)
        engine.setSpot(atSea)
        // The wind alone was tried; not the whole forecast, whose sea values would be the same.
        assertEquals(2, net.forecasts)
        assertEquals(0, net.windOnly)
        assertEquals(a.fetchedMillis, engine.spotArea!!.fetchedMillis)
        assertTrue(engine.spotArea!!.windFromBackup)
        assertTrue((engine.state as WeatherState.Waiting).reason.contains("backup wind"))
        // And it's tried no oftener than the backup's rules allow.
        sail(1)
        assertTrue("${net.forecasts} tries in an hour", net.forecasts <= 2 + WeatherEngine.MAX_AUTO_PER_HOUR)
    }

    @Test
    fun onlyTheAreaWhoseForecastIsDownloadingShowsUpdating() {
        engine.setSpot(atSea)
        // While the spot's sea was looked for and its forecast came: the spot's, no other's.
        assertTrue(net.workingAtSea.isNotEmpty() && net.workingAtSea.all { it == ConditionsFeature.SPOT_ID })
        assertEquals(listOf<String?>(ConditionsFeature.SPOT_ID), net.workingAtForecast)
        // While its pictures followed, the forecast saved: nobody's.
        assertTrue(net.workingAtSatellite.isNotEmpty() && net.workingAtSatellite.all { it == null })
        assertFalse(engine.isWorkingOn(ConditionsFeature.SPOT_ID))
        // Another area: its own id while its forecast comes, not the spot's.
        assertNull(engine.downloadArea(-30.0, 150.0, -29.0, 151.0, "other", userAsked = true))
        assertEquals("other", net.workingAtForecast.last())
        // The temperature at depth for the spot: not its forecast, so not "Updating".
        net.workingAtSatellite.clear()
        settings = settings.copy(depthM = 50)
        engine.downloadDepth(ConditionsFeature.SPOT_ID)
        assertEquals(listOf<String?>(null), net.workingAtSatellite)
        assertNull(engine.workingOnId)
        assertFalse(engine.isWorkingOn("other"))
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
