package au.opendepth.app.tides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SunMoonTest {

    private fun utc(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun `Sydney sunrise and sunset at midwinter`() {
        // 21 June 2024: sunrise 07:00, sunset 16:54 AEST (published times).
        val (rise, set) = SunMoon.sunTimes(utc("2024-06-21T02:00:00Z"), -33.87, 151.21)!!
        assertEquals(utc("2024-06-20T21:00:00Z").toDouble(), rise.toDouble(), 3 * 60_000.0)
        assertEquals(utc("2024-06-21T06:54:00Z").toDouble(), set.toDouble(), 3 * 60_000.0)
    }

    @Test
    fun `no sunrise in polar night`() {
        assertNull(SunMoon.sunTimes(utc("2024-12-21T12:00:00Z"), 78.2, 15.6))
    }

    @Test
    fun `moon phases match known dates`() {
        assertEquals("Full moon", SunMoon.phaseName(utc("2024-06-22T01:08:00Z")))
        assertEquals("New moon", SunMoon.phaseName(utc("2024-07-05T22:57:00Z")))
        assertEquals("First quarter", SunMoon.phaseName(utc("2024-07-13T22:49:00Z")))
        assertEquals("spring tides", SunMoon.springOrNeap(utc("2024-06-23T12:00:00Z")))
        assertEquals("neap tides", SunMoon.springOrNeap(utc("2024-07-15T12:00:00Z")))
    }

    @Test
    fun `save periods end at the end of the day`() {
        val now = ZonedDateTime.of(2026, 10, 27, 15, 30, 0, 0, ZoneId.of("Australia/Brisbane"))
        assertEquals(ZonedDateTime.of(2026, 11, 3, 23, 59, 59, 0, now.zone), SavePeriod.WEEK.endFrom(now))
        assertEquals(ZonedDateTime.of(2028, 10, 27, 23, 59, 59, 0, now.zone), SavePeriod.TWO_YEARS.endFrom(now))
    }
}
