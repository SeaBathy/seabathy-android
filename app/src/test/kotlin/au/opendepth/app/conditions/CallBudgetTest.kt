package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The count that keeps the app inside the free forecast service's limits. */
class CallBudgetTest {
    private val minute = 60_000L
    private val hour = 3_600_000L
    private var now = 1_800_000_000_000L
    private val budget = CallBudget { now }

    /** A download of 356 calls in requests of 48, each waiting for room as the app does. Returns how long it waited. */
    private fun area(): Long {
        var waited = 0L
        var left = 356
        while (left > 0) {
            val calls = minOf(48, left)
            var wait = budget.reserve(calls)
            while (wait > 0) {
                now += wait
                waited += wait
                wait = budget.reserve(calls)
            }
            left -= calls
        }
        return waited
    }

    private fun limitOf(block: () -> Unit): LimitReachedException? = runCatching(block).exceptionOrNull() as? LimitReachedException

    @Test
    fun `one area goes straight through`() {
        assertEquals(0L, area())
        assertEquals(356, budget.used(minute))
    }

    @Test
    fun `a second area in the same minute waits instead of being refused`() {
        area()
        now += 5_000
        val waited = area()
        assertTrue("waited $waited ms", waited in 1..(minute + 2_000))
        // Never more than the service's 600 a minute, with room to spare.
        assertTrue(budget.used(minute) <= CallBudget.MINUTE_CAP)
    }

    @Test
    fun `three areas back to back never pass the minute's limit`() {
        var most = 0
        repeat(3) {
            area()
            most = maxOf(most, budget.used(minute))
        }
        assertTrue("$most in a minute", most <= CallBudget.MINUTE_CAP)
    }

    @Test
    fun `automatic downloads stop short of the day's limit and leave the rest for the user`() {
        var automatic = 0
        while (limitOf { budget.check(356, userAsked = false) } == null) {
            area()
            automatic++
            now += 30 * minute
        }
        assertTrue("$automatic automatic downloads", automatic in 6..16)
        assertTrue(budget.used(CallBudget.DAY_MS) <= CallBudget.DAY_AUTO_CAP)
        // The user's own tap still goes.
        assertEquals(null, limitOf { budget.check(356, userAsked = true) })
        var asked = 0
        while (limitOf { budget.check(356, userAsked = true) } == null) {
            area()
            asked++
            now += 30 * minute
        }
        assertTrue("$asked more by hand", asked >= 3)
        // And never the service's 10,000 a day.
        assertTrue(budget.used(CallBudget.DAY_MS) <= CallBudget.DAY_CAP)
    }

    @Test
    fun `the hour's share is kept too`() {
        var downloads = 0
        while (limitOf { budget.check(356, userAsked = false) } == null) {
            area()
            downloads++
            now += 2 * minute
        }
        assertTrue(budget.used(hour) <= CallBudget.HOUR_AUTO_CAP)
        val until = limitOf { budget.check(356, userAsked = false) }!!.untilMillis
        assertTrue(until > now && until <= now + hour + minute)
        // By then there's room again.
        now = until
        assertEquals(null, limitOf { budget.check(356, userAsked = false) })
        assertTrue(downloads >= 6)
    }

    @Test
    fun `when the service says the hour is used up nothing is sent until the next`() {
        val until = budget.limited(LimitKind.HOUR)
        assertTrue(until > now && until <= now + hour + minute)
        assertEquals(until, limitOf { budget.check(1, userAsked = true) }!!.untilMillis)
        assertEquals(until, limitOf { budget.reserve(1) }!!.untilMillis)
        now = until
        assertEquals(0L, budget.reserve(1))
    }

    @Test
    fun `the service's minute is waited out`() {
        budget.limited(LimitKind.MINUTE)
        val wait = budget.reserve(48)
        assertTrue(wait in 1..(minute + 1_000))
        now += wait
        assertEquals(0L, budget.reserve(48))
    }

    @Test
    fun `which limit the service names`() {
        assertEquals(LimitKind.MINUTE, CallBudget.kindOf("Minutely API request limit exceeded. Please try again in one minute."))
        assertEquals(LimitKind.HOUR, CallBudget.kindOf("Hourly API request limit exceeded. Please try again in the next hour."))
        assertEquals(LimitKind.DAY, CallBudget.kindOf("Daily API request limit exceeded. Please try again tomorrow."))
        assertEquals(LimitKind.MINUTE, CallBudget.kindOf(null))
    }

    @Test
    fun `what was spent is remembered across a restart`() {
        var saved = ""
        budget.onChange = { saved = it }
        area()
        budget.limited(LimitKind.DAY)
        val after = CallBudget { now }
        after.restore(saved)
        assertEquals(356, after.used(hour))
        assertTrue(after.blockedUntilMillis > now)
        // A day on, it's all forgotten.
        now += CallBudget.DAY_MS + hour
        val later = CallBudget { now }
        later.restore(saved)
        assertEquals(0, later.used(CallBudget.DAY_MS))
        assertEquals(0L, later.blockedUntilMillis)
    }

    /** A whole area as the app downloads it: 256 sea and 256 wind calls in requests of 48, each waiting for room. */
    private fun bothServices(): Long {
        var waited = 0L
        for (api in listOf(CallBudget.MARINE, CallBudget.WEATHER)) {
            var left = 256
            while (left > 0) {
                val calls = minOf(48, left)
                var wait = budget.reserve(calls, api)
                while (wait > 0) {
                    now += wait
                    waited += wait
                    wait = budget.reserve(calls, api)
                }
                left -= calls
            }
        }
        return waited
    }

    private val area = mapOf(CallBudget.MARINE to 256, CallBudget.WEATHER to 256)

    @Test
    fun `both services together stay under the free day's limit`() {
        var automatic = 0
        while (limitOf { budget.check(area, userAsked = false) } == null) {
            bothServices()
            automatic++
            now += 30 * minute
        }
        // Each service alone would have let twice as many through: 12,000 a day between them.
        assertTrue("$automatic automatic downloads", automatic in 6..CallBudget.TOTAL_DAY_AUTO_CAP / 512)
        assertTrue(budget.usedTotal(CallBudget.DAY_MS) <= CallBudget.TOTAL_DAY_AUTO_CAP)
        // The user's own taps still go, up to the total cap and no further.
        assertEquals(null, limitOf { budget.check(area, userAsked = true) })
        while (limitOf { budget.check(area, userAsked = true) } == null) {
            bothServices()
            now += 30 * minute
        }
        assertTrue(budget.usedTotal(CallBudget.DAY_MS) <= CallBudget.TOTAL_DAY_CAP)
        assertTrue(budget.usedTotal(CallBudget.DAY_MS) < 10_000)
    }

    @Test
    fun `a download that fits each service's share but not both together is not started`() {
        // Spent on the sea service (tides count there too), within its own share.
        repeat(57) {
            budget.count(100, CallBudget.MARINE)
            now += 10 * minute
        }
        assertEquals(null, limitOf { budget.check(256, userAsked = false, api = CallBudget.MARINE) })
        assertEquals(null, limitOf { budget.check(256, userAsked = false, api = CallBudget.WEATHER) })
        // Both at once: 5,700 + 512 is past the automatic total.
        val refused = limitOf { budget.check(area, userAsked = false) }
        assertTrue(refused != null && refused.untilMillis > now)
        // The user's tap has the rest of the day's total.
        assertEquals(null, limitOf { budget.check(area, userAsked = true) })
    }

    @Test
    fun `the hour's total over both services is kept too`() {
        while (limitOf { budget.check(area, userAsked = false) } == null) {
            bothServices()
            now += 2 * minute
        }
        assertTrue(budget.usedTotal(hour) <= CallBudget.TOTAL_HOUR_AUTO_CAP)
        val until = limitOf { budget.check(area, userAsked = false) }!!.untilMillis
        assertTrue(until > now && until <= now + hour + minute)
    }

    @Test
    fun `a minute holds one whole area over both services and no more`() {
        assertEquals(0L, bothServices())
        assertEquals(512, budget.usedTotal(minute))
        // The sea service alone has room in its own minute, but both together don't.
        assertTrue(budget.used(minute, CallBudget.MARINE) + 48 <= CallBudget.MINUTE_CAP)
        assertTrue(budget.reserve(48, CallBudget.MARINE) > 0)
        // Areas back to back never pass the free 600 a minute between them.
        var most = 0
        repeat(3) {
            bothServices()
            most = maxOf(most, budget.usedTotal(minute))
        }
        assertTrue("$most in a minute", most <= CallBudget.TOTAL_MINUTE_CAP)
    }

    @Test
    fun `the total caps sit under the free terms' limits`() {
        assertTrue(CallBudget.TOTAL_MINUTE_CAP < 600 && CallBudget.TOTAL_HOUR_CAP < 5_000 && CallBudget.TOTAL_DAY_CAP < 10_000)
        assertTrue(CallBudget.TOTAL_HOUR_AUTO_CAP < CallBudget.TOTAL_HOUR_CAP && CallBudget.TOTAL_DAY_AUTO_CAP < CallBudget.TOTAL_DAY_CAP)
        // A whole area (256 sea and 256 wind) still goes in one minute.
        assertTrue(CallBudget.TOTAL_MINUTE_CAP >= 512)
    }

    @Test
    fun `a saved count that makes no sense is ignored`() {
        budget.restore("rubbish")
        budget.restore("1;x;y;1:2:3,,:")
        budget.restore(null)
        assertEquals(0, budget.used(hour))
        // A clock set back since: counts "from the future" and a limit a week away are dropped.
        budget.restore("1;${now + 7 * CallBudget.DAY_MS};DAY;${now + hour}:500")
        assertEquals(0, budget.used(CallBudget.DAY_MS))
        assertFalse(budget.blockedUntilMillis > 0)
    }
}
