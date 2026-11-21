package au.opendepth.app.conditions

import android.content.SharedPreferences
import au.opendepth.app.nav.HeightUnit
import au.opendepth.app.nav.NavGeo
import au.opendepth.app.nav.NavSettings
import au.opendepth.app.nav.NavSettingsRepository
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.nav.SeaSpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

class ConditionsAlertsTest {
    private val zone = TimeZone.getTimeZone("Australia/Sydney")
    private val hour = 3_600_000L
    // Midnight Mon 16 Nov 2026 in Sydney (AEDT, UTC+11), and 8 days of hours.
    private val start = Instant.parse("2026-11-15T13:00:00Z").toEpochMilli()
    private val times = LongArray(8 * 24) { start + it * hour }
    private val lat = -33.86
    private val lng = 151.21

    @Test
    fun findsTheCalmStretchInDaylight() {
        // Calm 08:00 to 14:00 on Tuesday only.
        val tueCalm = start + 24 * hour + 8 * hour until start + 24 * hour + 14 * hour
        val wind = { t: Long -> if (t in tueCalm) 6.0 else 20.0 }
        val wave = { _: Long -> 0.8 }
        val w = ConditionsAlerts.findWindows(times, wind, wave, AlertRules(enabled = true, daysAhead = 3), start, lat, lng, zone)
        assertEquals(1, w.size)
        assertEquals(start + 24 * hour + 8 * hour, w[0].from)
        assertEquals(start + 24 * hour + 14 * hour, w[0].to)
        assertEquals(6.0, w[0].maxWindKn, 1e-9)
    }

    @Test
    fun respectsDaysHoursAndLookAhead() {
        val calm = { _: Long -> 5.0 }
        val flat = { _: Long -> 0.5 }
        // Every day calm: today plus 3 → 4 days.
        assertEquals(4, ConditionsAlerts.findWindows(times, calm, flat, AlertRules(daysAhead = 3), start, lat, lng, zone).size)
        // Weekends only within 6 days of Monday: Sat and Sun.
        val weekends = ConditionsAlerts.findWindows(times, calm, flat, AlertRules(daysAhead = 6, weekdays = AlertRules.WEEKENDS), start, lat, lng, zone)
        assertEquals(2, weekends.size)
        val c = Calendar.getInstance(zone).apply { timeInMillis = weekends[0].day }
        assertEquals(Calendar.SATURDAY, c.get(Calendar.DAY_OF_WEEK))
        // Never more than the daylight: no window longer than about 14 h in November.
        assertTrue(weekends.all { it.to - it.from <= 15 * hour })
    }

    @Test
    fun shortOrMissingStretchesDontCount() {
        val twoHours = start + 10 * hour until start + 12 * hour
        val wind = { t: Long -> if (t in twoHours) 5.0 else 25.0 }
        assertTrue(ConditionsAlerts.findWindows(times, wind, { 0.5 }, AlertRules(daysAhead = 0, minHours = 4), start, lat, lng, zone).isEmpty())
        // No wave forecast (NaN): not good.
        assertTrue(ConditionsAlerts.findWindows(times, { 5.0 }, { Double.NaN }, AlertRules(daysAhead = 0), start, lat, lng, zone).isEmpty())
        // Hours already gone today don't count.
        val late = start + 17 * hour
        assertTrue(ConditionsAlerts.findWindows(times, { 5.0 }, { 0.5 }, AlertRules(daysAhead = 0, minHours = 4), late, lat, lng, zone).isEmpty())
    }

    @Test
    fun gustsAndSetHoursCount() {
        val calm = { _: Long -> 8.0 }
        val flat = { _: Long -> 0.5 }
        // Gusty 10:00 to 12:00 on Monday: with a gust limit the day splits; without one it doesn't.
        val gusty = start + 10 * hour until start + 12 * hour
        val gust = { t: Long -> if (t in gusty) 25.0 else 10.0 }
        val limited = AlertRules(daysAhead = 0, minHours = 4, gustMaxKn = 20.0)
        val split = ConditionsAlerts.findWindows(times, calm, flat, limited, start, lat, lng, zone, gustKn = gust)
        assertTrue(split.single().from >= start + 12 * hour)
        val noLimit = ConditionsAlerts.findWindows(times, calm, flat, limited.copy(gustMaxKn = 0.0), start, lat, lng, zone, gustKn = gust)
        assertTrue(noLimit.single().to - noLimit.single().from > 8 * hour)
        // 06:00 to 10:00 only.
        val morning = ConditionsAlerts.findWindows(times, calm, flat, AlertRules(daysAhead = 0, minHours = 2, fromHour = 6, toHour = 10), start, lat, lng, zone)
        assertEquals(start + 6 * hour, morning.single().from)
        assertEquals(start + 10 * hour, morning.single().to)
    }

    @Test
    fun saysWhatsNewAndWhatsGone() {
        val place = AlertPlace("a", "Reef", lat, lng, 10.0)
        val sat = GoodWindow(start + 5 * 24 * hour, start + 5 * 24 * hour + 7 * hour, start + 5 * 24 * hour + 12 * hour, 9.0, 0.6)
        val sun = GoodWindow(start + 6 * 24 * hour, start + 6 * 24 * hour + 7 * hour, start + 6 * 24 * hour + 12 * hour, 9.0, 0.6)
        // First time: both new.
        val first = ConditionsAlerts.compare(place, listOf(sat, sun), emptyMap(), "2026-11-16", start, zone)
        assertEquals(2, first.newDays.size)
        // Then Sunday turns bad: Sunday gone, nothing new.
        val told = mapOf("2026-11-21" to listOf(sat.from, sat.to), "2026-11-22" to listOf(sun.from, sun.to))
        val later = ConditionsAlerts.compare(place, listOf(sat), told, "2026-11-16", start, zone)
        assertTrue(later.newDays.isEmpty())
        assertEquals(listOf(sun.from), later.goneDays)
        // A good day that has simply gone by isn't news.
        val past = mapOf("2026-11-15" to listOf(start - 10 * hour, start - 5 * hour))
        assertTrue(!ConditionsAlerts.compare(place, emptyList(), past, "2026-11-16", start, zone).hasNews)
    }

    @Test
    fun theWorstPointCounts() {
        assertEquals(14.0, ConditionsAlerts.worst(doubleArrayOf(6.0, Double.NaN, 14.0, 9.0)), 1e-9)
        assertTrue(ConditionsAlerts.worst(doubleArrayOf(Double.NaN)).isNaN())
        assertEquals(listOf(lat to lng), ConditionsAlerts.placePoints(AlertPlace("s", "Spot", lat, lng, 0.0)))
    }

    @Test
    fun everyPointIsOnTheCircle() {
        for ((pLat, radius) in listOf(lat to 10.0, -10.5 to 2.0, 45.0 to 50.0)) {
            val place = AlertPlace("a", "Reef", pLat, lng, radius)
            val points = ConditionsAlerts.placePoints(place)
            assertEquals(9, points.toSet().size)
            assertTrue(pLat to lng in points)
            // The other 8 all the radius out, the ones between north and east too: the corners of a
            // square were 1.41 times as far, outside the circle.
            points.filter { it != pLat to lng }.forEach { (aLat, aLng) ->
                assertEquals(radius * 1000, NavGeo.distanceM(pLat, lng, aLat, aLng), radius * 10)
            }
            // Asked on grids holding just those points, the middle in the first.
            val grids = ConditionsAlerts.placeGrids(place)
            assertEquals(9, grids.sumOf { it.size })
            assertTrue(grids.first().lats.any { it == pLat } && grids.first().lngs.any { it == lng })
        }
    }

    @Test
    fun countsTheChecksCalls() {
        val reef = AlertPlace("a", "Reef", lat, lng, 10.0)
        val spot = AlertPlace("s", "Spot", lat, lng, 0.0)
        // Six checks a day, each point a call for the sea and one for the wind.
        assertEquals(6 * 2, ConditionsAlerts.callsPerDay(AlertRules()))
        assertEquals(6 * 9 * 2, ConditionsAlerts.callsPerDay(AlertRules(places = listOf(reef))))
        assertEquals(6 * 10 * 2, ConditionsAlerts.callsPerDay(AlertRules(places = listOf(reef, spot))))
    }

    /** A saved forecast round the place, downloaded at [fetched] and ending at [until]. */
    private fun savedCopy(fetched: Long, until: Long) =
        ConditionsStore.Summary("alert-a", "Reef", lat - 1, lng - 1, lat + 1, lng + 1, fetched, until, emptySet(), 0L)

    /** Where a download of [days] made at [now] ends: Open-Meteo's hours start at the current hour. */
    private fun downloadEnd(now: Long, days: Int) = now / hour * hour + (ConditionsClient.hoursAhead(days) - 1) * hour

    @Test
    fun aGoodDayBeyondTheSavedDaysIsntDownloadedAgain() {
        // Monday 10:00, and the good stretch is Thursday 08:00 to 14:00: past the 2 days the setting saves.
        val now = start + 10 * hour
        val thursday = start + 3 * 24 * hour + 14 * hour
        val days = ConditionsAlerts.daysToSave(thursday, 2, emptyList(), now)!!
        assertTrue(days in 3..ConditionsClient.MAX_DAYS)
        val saved = savedCopy(now + 60_000L, downloadEnd(now, days))
        assertTrue(saved.forecastUntilMillis!! >= thursday)
        // The checks after it, every 4 hours for a day: nothing to download.
        for (later in 4..23 step 4) assertNull("$later h on", ConditionsAlerts.daysToSave(thursday, 2, listOf(saved), now + later * hour))
        // A copy of only the setting's 2 days never reaches Thursday: before, every check downloaded again.
        // One that falls short is replaced however new it is.
        val short = savedCopy(now + 60_000L, downloadEnd(now, 2))
        assertTrue(ConditionsAlerts.daysToSave(thursday, 2, listOf(short), now + 4 * hour) != null)
        // So is the full copy when a later day starts looking good: Saturday, 4 hours on.
        val saturday = start + 5 * 24 * hour + 14 * hour
        assertTrue(ConditionsAlerts.daysToSave(saturday, 2, listOf(saved), now + 4 * hour) != null)
        // Older than a day, it's renewed.
        assertTrue(ConditionsAlerts.daysToSave(thursday, 2, listOf(saved), now + 25 * hour) != null)
        // The setting wins when it's longer.
        assertEquals(5, ConditionsAlerts.daysToSave(now + 6 * hour, 5, emptyList(), now))
    }

    @Test
    fun aNewerShorterSpotDownloadDoesntBringTheAlertsSaveAgain() {
        // The alert saved Thursday's forecast; then My spot updated (newer, 2 days only).
        val now = start + 10 * hour
        val thursday = start + 3 * 24 * hour + 14 * hour
        val days = ConditionsAlerts.daysToSave(thursday, 2, emptyList(), now)!!
        val alertCopy = savedCopy(now + 60_000L, downloadEnd(now, days))
        val spot = savedCopy(now + 3 * hour, downloadEnd(now + 3 * hour, 2))
        // The next check sees both: the alert's copy still reaches Thursday, so nothing is downloaded.
        assertNull(ConditionsAlerts.daysToSave(thursday, 2, listOf(spot, alertCopy), now + 4 * hour))
    }

    @Test
    fun aStretchBeyondTheLongestForecastDoesntBringRepeatDownloads() {
        val now = start + 10 * hour
        val farOff = now + 12 * 24 * hour
        val days = ConditionsAlerts.daysToSave(farOff, 2, emptyList(), now)
        assertEquals(ConditionsClient.MAX_DAYS, days)
        val saved = savedCopy(now + 60_000L, downloadEnd(now, days!!))
        // It never reaches the stretch, yet isn't downloaded again for a day.
        for (later in 4..23 step 4) assertNull("$later h on", ConditionsAlerts.daysToSave(farOff, 2, listOf(saved), now + later * hour))
        assertEquals(ConditionsClient.MAX_DAYS, ConditionsAlerts.daysToSave(farOff, 2, listOf(saved), now + 25 * hour))
    }

    @Test
    fun linesUseTheUsersUnits() {
        val w = GoodWindow(start, start + 7 * hour, start + 12 * hour, maxWindKn = 10.8, maxWaveM = 0.6, minWindKn = 8.0, maxGustKn = 16.0, windFromDeg = 135.0)
        val knots = ConditionsAlerts.line(w, NavUnits(), zone)
        assertTrue(knots, "wind 8–11 kn SE, gusts 16 · waves 0.6 m" in knots)
        val metric = ConditionsAlerts.line(w, NavUnits(wind = SeaSpeedUnit.KMH, waves = HeightUnit.FEET), zone)
        assertTrue(metric, "wind 15–20 km/h SE, gusts 30 · waves 2.0 ft" in metric)
        assertFalse(metric, " kn" in metric || " m" in metric.substringAfter("waves"))
        val beaufort = ConditionsAlerts.line(w, NavUnits(wind = SeaSpeedUnit.BEAUFORT), zone)
        assertTrue(beaufort, "wind 3 Bft SE, gusts 4" in beaufort)
        // The units the rest of the app saved.
        val prefs = MemoryPrefs()
        NavSettingsRepository(prefs).save(NavSettings(units = NavUnits(wind = SeaSpeedUnit.METRES_PER_SECOND, waves = HeightUnit.FEET)))
        val units = ConditionsAlerts.units(prefs)
        assertEquals(SeaSpeedUnit.METRES_PER_SECOND, units.wind)
        assertEquals(HeightUnit.FEET, units.waves)
    }

    @Test
    fun aPlaceThatCouldntBeCheckedKeepsWhatItWasTold() {
        val prefs = MemoryPrefs()
        val reef = AlertPlace("a", "Reef", lat, lng, 10.0)
        val bay = AlertPlace("b", "Bay", lat, lng + 0.5, 10.0)
        val sat = GoodWindow(start + 5 * 24 * hour, start + 5 * 24 * hour + 7 * hour, start + 5 * 24 * hour + 12 * hour, 9.0, 0.6)
        ConditionsAlerts.remember(prefs, listOf(reef to listOf(sat), bay to listOf(sat)), zone)
        // Next time the bay's forecast couldn't be had: only the reef came back.
        ConditionsAlerts.remember(prefs, listOf(reef to listOf(sat)), zone)
        // So when the bay is checked again, its Saturday isn't news.
        assertFalse(ConditionsAlerts.news(prefs, listOf(bay to listOf(sat)), start, zone).single().hasNews)
    }

    /** In-memory [SharedPreferences]. */
    private class MemoryPrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in map
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false
            override fun putString(k: String, v: String?) = apply { puts[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { puts[k] = v?.toSet() }
            override fun putInt(k: String, v: Int) = apply { puts[k] = v }
            override fun putLong(k: String, v: Long) = apply { puts[k] = v }
            override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
            override fun remove(k: String) = apply { removes += k }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                map.putAll(puts)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
