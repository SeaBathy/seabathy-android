package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AstroTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun sydneySunNewYearsDay() {
        // Local midnight 1 Jan 2026 in Sydney (AEDT, UTC+11).
        val day = Astro.day(ms("2025-12-31T13:00:00Z"), -33.86, 151.21)
        val minutes = 60_000.0
        assertEquals(ms("2025-12-31T18:47:00Z").toDouble(), day.sunrise!!.toDouble(), 8 * minutes)
        assertEquals(ms("2026-01-01T09:09:00Z").toDouble(), day.sunset!!.toDouble(), 8 * minutes)
        assertTrue(day.firstLight!! < day.sunrise!!)
        assertTrue(day.lastLight!! > day.sunset!!)
    }

    @Test
    fun moonPhases() {
        assertTrue(Astro.moonPhase(ms("2026-01-03T10:03:00Z")).first > 0.97)
        assertTrue(Astro.moonPhase(ms("2026-01-18T19:52:00Z")).first < 0.03)
        assertTrue(Astro.moonPhase(ms("2026-01-10T00:00:00Z")).second.not()) // waning after full
    }

    @Test
    fun solunarPeriodsExist() {
        val day = Astro.day(ms("2026-03-10T14:00:00Z"), -20.0, 116.0)
        assertTrue(day.majors.size in 1..2)
        assertTrue(day.minors.size in 0..2)
        assertNotNull(day.phaseName)
    }
}
