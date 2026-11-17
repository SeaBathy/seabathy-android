package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
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
        val grid = ConditionsAlerts.placeGrid(AlertPlace("a", "Reef", lat, lng, 10.0))
        assertEquals(9, grid.lats.size * grid.lngs.size)
        assertEquals(1, ConditionsAlerts.placeGrid(AlertPlace("s", "Spot", lat, lng, 0.0)).lats.size)
    }
}
