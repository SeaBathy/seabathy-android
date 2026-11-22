package au.opendepth.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos

class TideWindowTest {

    private val hour = 3_600_000L
    private val period = (12.42 * hour).toLong()
    private val from = 1_800_000_000_000L

    /** A plain semidiurnal tide above the survey's datum: 0.5 m to 2.5 m, high at [from]. */
    private fun tide(t: Long): Double = 1.5 + cos(2 * PI * (t - from) / period)

    @Test
    fun `clear while the tide is above what the draft needs`() {
        // Charted 1.0 m, needs 2.0 m: a tide of 1.0 m or more, i.e. cos >= -0.5.
        val r = TideWindow.compute(1.0, 2.0, from, from + 48 * hour) { tide(it) }!!
        assertEquals(1.0, r.neededTideM, 1e-9)
        assertFalse(r.alwaysClear)
        assertFalse(r.neverClear)
        // The tide drops below 1.0 m a third of a cycle after high water, and is back two thirds in.
        val firstEnd = from + (acos(-0.5) / (2 * PI) * period).toLong()
        assertEquals(from, r.periods[0].startMillis)
        assertEquals(firstEnd.toDouble(), r.periods[0].endMillis.toDouble(), 60_000.0)
        val secondStart = from + period - (acos(-0.5) / (2 * PI) * period).toLong()
        assertEquals(secondStart.toDouble(), r.periods[1].startMillis.toDouble(), 60_000.0)
        // Four lows in two days, so four breaks: five clear spells, the last still open.
        assertEquals(5, r.periods.size)
        assertEquals(from + 48 * hour, r.periods.last().endMillis)
        r.periods.forEach { p -> assertTrue(tide((p.startMillis + p.endMillis) / 2) >= 1.0) }
        assertEquals(2.5, r.highestTideM, 0.01)
        assertEquals(0.5, r.lowestTideM, 0.01)
    }

    @Test
    fun `deep enough at any tide`() {
        val r = TideWindow.compute(5.0, 2.0, from, from + 24 * hour) { tide(it) }!!
        assertTrue(r.alwaysClear)
        assertEquals(true, r.clearAt(from + 7 * hour))
    }

    @Test
    fun `never enough when even the highest tide falls short`() {
        // Charted 0.2 m, needs 3.0 m: a 2.8 m tide, more than the 2.5 m highs.
        val r = TideWindow.compute(0.2, 3.0, from, from + 24 * hour) { tide(it) }!!
        assertTrue(r.neverClear)
        assertEquals(2.5, r.highestTideM, 0.01)
        assertNull(r.nextClear(from))
    }

    @Test
    fun `drying bank needs the tide to cover it first`() {
        // Dries 0.5 m (charted -0.5), boat needs 1.5 m: a 2.0 m tide.
        val r = TideWindow.compute(-0.5, 1.5, from, from + 24 * hour) { tide(it) }!!
        assertEquals(2.0, r.neededTideM, 1e-9)
        r.periods.forEach { p -> assertTrue(p.endMillis - p.startMillis < period / 2) }
        assertEquals(false, r.clearAt(from + period / 2))
        assertEquals(true, r.clearAt(from))
    }

    @Test
    fun `stops where the tides do`() {
        val end = from + 10 * hour
        val r = TideWindow.compute(1.0, 2.0, from, from + 48 * hour) { t -> if (t > end) null else tide(t) }!!
        assertTrue(r.knownUntilMillis <= end)
        assertTrue(r.knownUntilMillis > end - 10 * 60_000L)
        assertNull(r.clearAt(end + hour))
        assertTrue(r.periods.all { it.endMillis <= r.knownUntilMillis })
    }

    @Test
    fun `no window without the tide now`() {
        assertNull(TideWindow.compute(1.0, 2.0, from, from + hour) { null })
    }

    @Test
    fun `each leg's shallow point is checked at its own time`() {
        val needed = 2.0
        // Leg 1 deep enough at any tide; leg 2's bar (1.2 m) is passed near low water;
        // leg 3 (1.0 m, the shallowest charted) is passed near high water.
        val points = listOf(
            TideWindow.PassPoint(1, 4.0, from, ::tide),
            TideWindow.PassPoint(2, 1.2, from + period / 2, ::tide),
            TideWindow.PassPoint(3, 1.0, from + period, ::tide),
            TideWindow.PassPoint(4, 0.5, from + period, null)
        )
        val p = TideWindow.passage(points, needed)
        // Leg 2 at low water: 1.2 + 0.5 = 1.7 m, the least met, and short of 2.0 m.
        assertEquals(2, p.worst!!.legNumber)
        assertEquals(1.7, p.worst!!.waterM, 1e-6)
        assertEquals(listOf(2), p.short.map { it.legNumber })
        assertEquals(listOf(4), p.unknownLegs)
        // Leaving at a better time, all clear: the least is still reported.
        val later = TideWindow.passage(points.take(3).map { it.copy(arrivalMillis = it.arrivalMillis + period / 4) }, needed)
        assertTrue(later.short.isEmpty())
        assertEquals(3, later.worst!!.legNumber)
    }

    @Test
    fun `no tides anywhere means no check`() {
        val p = TideWindow.passage(listOf(TideWindow.PassPoint(1, 1.0, from, null)), 2.0)
        assertNull(p.worst)
        assertEquals(listOf(1), p.unknownLegs)
    }

    @Test
    fun `next clear time from a shallow moment`() {
        val r = TideWindow.compute(1.0, 2.0, from, from + 24 * hour) { tide(it) }!!
        val low = from + period / 2
        assertEquals(false, r.clearAt(low))
        assertEquals(r.periods[1].startMillis, r.nextClear(low))
        assertEquals(from + hour, r.nextClear(from + hour))
    }

    @Test
    fun `enough water now with the tides ending straight after is not a warning`() {
        // Only the tide now is known (the end of the saved predictions), and it is enough.
        val r = TideWindow.compute(1.0, 2.0, from, from + 48 * hour) { t -> if (t == from) tide(t) else null }!!
        assertFalse(r.neverClear)
        assertTrue(r.alwaysClear)
        assertEquals(from, r.knownUntilMillis)
        assertEquals(true, r.clearAt(from))
        // Not enough now, and nothing known after: still never clear.
        val low = from + period / 2
        val shallow = TideWindow.compute(1.0, 2.0, low, low + 48 * hour) { t -> if (t == low) tide(t) else null }!!
        assertTrue(shallow.neverClear)
    }
}
