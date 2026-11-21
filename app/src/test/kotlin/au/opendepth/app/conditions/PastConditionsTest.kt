package au.opendepth.app.conditions

import au.opendepth.app.nav.HeightUnit
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conditions at an old photo's hour: in the user's units, and counted like every other call. */
class PastConditionsTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val budget = CallBudget { now }
    /** Three days before now. */
    private val taken = now - 72 * hour + 20 * 60_000L
    private val photoHour = taken / hour * hour
    private val asked = mutableListOf<String>()

    /** As the service answers: an hour before the photo's, then its own. */
    private fun reply(vararg series: Pair<String, Double>): String =
        "{\"hourly\":{\"time\":[${photoHour / 1000 - 3600},${photoHour / 1000}]," +
            series.joinToString(",") { "\"${it.first}\":[null,${it.second}]" } + "}}"

    private fun http(url: String): String {
        asked += url
        return if ("marine-api" in url) reply(
            "wave_height" to 1.0, "wave_period" to 9.0, "wave_direction" to 90.0,
            "swell_wave_height" to 2.0, "swell_wave_period" to 12.0, "swell_wave_direction" to 180.0,
            "sea_surface_temperature" to 25.0
        ) else reply("wind_speed_10m" to 12.0, "wind_direction_10m" to 135.0, "wind_gusts_10m" to 20.0)
    }

    private fun lines(format: NavFormat = NavFormat(NavUnits()), http: (String) -> String = ::http) =
        PastConditions.lines(-20.0, 150.0, taken, format, budget, http, now)

    @Test
    fun `waves and swell are in the user's units`() {
        val feet = lines(NavFormat(NavUnits(waves = HeightUnit.FEET)))
        assertTrue(feet.toString(), feet.contains("Waves 3.3 ft, 9 s from E"))
        assertTrue(feet.toString(), feet.contains("Swell 6.6 ft, 12 s from S"))
        val metres = lines()
        assertTrue(metres.toString(), metres.contains("Waves 1.0 m, 9 s from E"))
        assertTrue(metres.toString(), metres.contains("Wind 12.0 kn from SE, gusts 20.0 kn"))
        assertTrue(metres.last().startsWith("(Wind and sea from Open-Meteo"))
    }

    @Test
    fun `each request is a call counted in the budget`() {
        lines()
        assertEquals(1, budget.used(hour, CallBudget.MARINE))
        assertEquals(1, budget.used(hour, CallBudget.WEATHER))
        assertEquals(2, asked.size)
    }

    @Test
    fun `a request the budget won't allow is left out, not sent`() {
        budget.limited(LimitKind.DAY, CallBudget.MARINE)
        val out = lines()
        assertTrue(out.toString(), out.any { it.startsWith("Wind") })
        assertFalse(out.toString(), out.any { it.startsWith("Waves") })
        assertTrue(asked.none { "marine-api" in it })
        // The day's automatic share used up: nothing is asked at all, and nothing goes wrong.
        val spent = CallBudget { now }
        spent.count(CallBudget.TOTAL_DAY_AUTO_CAP, CallBudget.WEATHER)
        asked.clear()
        assertTrue(PastConditions.lines(-20.0, 150.0, taken, NavFormat(NavUnits()), spent, ::http, now).isEmpty())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `too many requests is told to the budget, so the forecast's downloads wait too`() {
        val out = lines(http = { url -> if ("marine-api" in url) throw ConditionsClient.ServiceLimitException(LimitKind.HOUR, "busy") else http(url) })
        assertTrue(budget.blockedUntil(CallBudget.MARINE) > now)
        assertTrue(out.any { it.startsWith("Wind") })
    }
}
