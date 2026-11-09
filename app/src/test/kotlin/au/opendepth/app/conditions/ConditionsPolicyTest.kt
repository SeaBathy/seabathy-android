package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsPolicyTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1000 * day

    private fun area(
        id: String, fetched: Long, until: Long? = fetched + 7 * day,
        south: Double = -19.0, west: Double = 146.0, north: Double = -17.0, east: Double = 148.0,
        satellites: Set<SatelliteKind> = setOf(SatelliteKind.SEA_TEMP)
    ) = ConditionsStore.Summary(id, id, south, west, north, east, fetched, until, satellites, 1000L)

    @Test
    fun `updates when a newer forecast run is out, or the copy is half a day old`() {
        // Saved an hour ago; the newest run came out 3 hours ago: nothing new.
        assertFalse(ConditionsPolicy.needsUpdate(now - hour, now - 3 * hour, now))
        // A run came out after it was saved.
        assertTrue(ConditionsPolicy.needsUpdate(now - 3 * hour, now - hour, now))
        // Run unknown (status couldn't be read): only by age.
        assertFalse(ConditionsPolicy.needsUpdate(now - 11 * hour, null, now))
        assertTrue(ConditionsPolicy.needsUpdate(now - 13 * hour, null, now))
    }

    @Test
    fun `the newest area covering a place is the one used`() {
        val old = area("old", now - 5 * hour)
        val new = area("new", now - hour, south = -18.5, north = -17.5, west = 146.5, east = 147.5)
        val elsewhere = area("elsewhere", now, south = -30.0, north = -29.0, west = 150.0, east = 151.0)
        val all = listOf(old, new, elsewhere)
        assertEquals("new", ConditionsPolicy.newestCovering(-18.0, 147.0, all)!!.id)
        // Only the old one covers this corner.
        assertEquals("old", ConditionsPolicy.newestCovering(-18.9, 146.1, all)!!.id)
        assertNull(ConditionsPolicy.newestCovering(-40.0, 140.0, all))
    }

    @Test
    fun `old copies and run-out forecasts are removed, never the spot's`() {
        val spot = area(ConditionsFeature.SPOT_ID, now - hour)
        // An older copy of the spot's block (same size, a little to one side): redundant.
        val sameBlock = area("sameBlock", now - 5 * hour, south = -18.9, north = -17.1, west = 146.1, east = 147.9)
        // A small area inside the spot's: closer points, the detail it was saved for, so kept.
        val inside = area("inside", now - 5 * hour, south = -18.5, north = -17.5, west = 146.5, east = 147.5)
        // Bigger than the spot's area: it has water the spot's doesn't, so it stays.
        val bigger = area("bigger", now - 5 * hour, south = -20.0, north = -16.0, west = 145.0, east = 149.0)
        // Its forecast ran out three days ago.
        val expired = area("expired", now - 10 * day, until = now - 3 * day, south = -30.0, north = -29.0, west = 150.0, east = 151.0)
        // Ran out yesterday: still kept (it may be all there is offline).
        val recent = area("recent", now - 8 * day, until = now - day, south = -35.0, north = -34.0, west = 150.0, east = 151.0)
        // Has chlorophyll the newer one doesn't: not a copy.
        val moreData = area("moreData", now - 5 * hour, south = -18.9, north = -17.1, west = 146.1, east = 147.9,
            satellites = setOf(SatelliteKind.SEA_TEMP, SatelliteKind.CHLOROPHYLL))
        // An old spot area still isn't removed here: each update replaces it.
        val oldSpot = area(ConditionsFeature.SPOT_ID, now - 20 * day, until = now - 13 * day)

        val removed = ConditionsPolicy.toRemove(listOf(spot, sameBlock, inside, bigger, expired, recent, moreData), ConditionsFeature.SPOT_ID, now)
            .associate { it.first.id to it.second }
        assertEquals(mapOf("sameBlock" to ConditionsPolicy.Reason.COVERED_BY_NEWER, "expired" to ConditionsPolicy.Reason.EXPIRED), removed)
        assertTrue(ConditionsPolicy.toRemove(listOf(oldSpot), ConditionsFeature.SPOT_ID, now).isEmpty())
    }

    @Test
    fun `satellite-only areas go after a week`() {
        val sixDays = area("six", now - 6 * day, until = null)
        val eightDays = area("eight", now - 8 * day, until = null, south = -30.0, north = -29.0)
        val removed = ConditionsPolicy.toRemove(listOf(sixDays, eightDays), ConditionsFeature.SPOT_ID, now).map { it.first.id }
        assertEquals(listOf("eight"), removed)
    }

    @Test
    fun `reads when a model's newest run came out`() {
        val body = """{"chunk_time_length":360,"last_run_availability_time":1793110885,"last_run_initialisation_time":1793059200,"update_interval_seconds":86400}"""
        assertEquals(1_793_110_885_000L, ConditionsClient.parseLatestRun(body))
        assertNull(ConditionsClient.parseLatestRun("""{"chunk_time_length":360}"""))
    }

    @Test
    fun `the newest of the models' runs is used`() {
        val bodies = mapOf(
            "currents" to """{"last_run_availability_time":1000}""",
            "waves" to """{"last_run_availability_time":3000}""",
            "wind" to "not json"
        )
        var asked = 0
        val client = ConditionsClient(http = { url ->
            asked++
            when {
                "currents" in url -> bodies.getValue("currents")
                "meteofrance_wave" in url -> bodies.getValue("waves")
                else -> bodies.getValue("wind")
            }
        })
        assertEquals(3_000_000L, client.latestRunMillis())
        assertEquals(5, asked)
    }
}
