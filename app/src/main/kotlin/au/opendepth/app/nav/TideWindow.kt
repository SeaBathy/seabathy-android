package au.opendepth.app.nav

/** The tides a route's tide window uses, or why there are none. */
sealed class RouteTides {
    /**
     * [aboveDatum] gives the tide above the survey's datum at a time (null where
     * unknown); adding [shownOffsetM] gives the height the tide screen shows.
     */
    class Known(val source: String, val shownOffsetM: Double, val aboveDatum: (Long) -> Double?) : RouteTides()

    class Missing(val reason: String) : RouteTides()
}

/** What the route legs panel shows under TIDE WINDOW. */
class RouteTideWindow(
    /** The tides used, or why there are none. */
    val tides: RouteTides,
    /** When there's enough water, or null without tides for now. */
    val result: TideWindow.Result?,
    /** The leg with the shallowest charted point. */
    val legNumber: Int,
    /** Charted depth there, metres below the survey's datum (negative where it dries). */
    val chartDepthM: Double,
    /** How much of the route has charted depths, 0–1. */
    val coveredFraction: Double,
    /** Leaving now, about when the boat gets there; null without a speed. */
    val arrivalMillis: Long?,
    /** Leaving now, the water at each leg's shallowest point as the boat passes it; null without a speed. */
    val passage: TideWindow.Passage?,
    val fromMillis: Long,
    /** The window is worked out to here (the end of tomorrow). */
    val untilMillis: Long
)

/**
 * A route's tide window: when the tide gives enough water over its shallowest
 * point. The charted depth there plus the tide (both from the survey's datum)
 * against the boat's draft + safety margin. Pure maths, unit-tested.
 */
object TideWindow {

    /** A span of time with enough water. */
    data class Period(val startMillis: Long, val endMillis: Long)

    data class Result(
        val fromMillis: Long,
        /** The tide is known to here (the end asked for, or where the tides stop). */
        val knownUntilMillis: Long,
        /** Tide height above the survey's datum that gives enough water. */
        val neededTideM: Double,
        /** Times with enough water between [fromMillis] and [knownUntilMillis], in order. */
        val periods: List<Period>,
        /** The highest and lowest tide in that time, metres above the survey's datum. */
        val highestTideM: Double,
        val highestAtMillis: Long,
        val lowestTideM: Double
    ) {
        /** Enough water the whole time. */
        val alwaysClear: Boolean get() = periods.size == 1 && periods[0].startMillis == fromMillis && periods[0].endMillis == knownUntilMillis
        val neverClear: Boolean get() = periods.isEmpty()

        /** True when [timeMillis] has enough water; null outside the known time. */
        fun clearAt(timeMillis: Long): Boolean? =
            if (timeMillis < fromMillis || timeMillis > knownUntilMillis) null
            else periods.any { timeMillis in it.startMillis..it.endMillis }

        /** The next time with enough water at or after [timeMillis], or null. */
        fun nextClear(timeMillis: Long): Long? =
            periods.firstOrNull { it.endMillis >= timeMillis }?.let { maxOf(it.startMillis, timeMillis) }
    }

    /**
     * When [chartDepthM] (charted depth, metres below the survey's datum; negative
     * where it dries) plus the tide is at least [neededDepthM] (draft + margin), from
     * [fromMillis] to [toMillis]. [tideAt] gives the tide above the same datum, or
     * null where it isn't known; the window stops there. Null when the tide isn't
     * known at [fromMillis].
     */
    fun compute(
        chartDepthM: Double,
        neededDepthM: Double,
        fromMillis: Long,
        toMillis: Long,
        stepMillis: Long = STEP_MS,
        tideAt: (Long) -> Double?
    ): Result? {
        require(stepMillis > 0)
        val needed = neededDepthM - chartDepthM
        var tPrev = fromMillis
        var hPrev = tideAt(fromMillis) ?: return null
        var highest = hPrev
        var highestAt = fromMillis
        var lowest = hPrev
        val periods = mutableListOf<Period>()
        var openSince: Long? = if (hPrev >= needed) fromMillis else null
        var known = fromMillis
        var t = fromMillis
        while (t < toMillis) {
            t = minOf(t + stepMillis, toMillis)
            val h = tideAt(t) ?: break
            known = t
            if (h > highest) {
                highest = h
                highestAt = t
            }
            if (h < lowest) lowest = h
            val wasClear = hPrev >= needed
            val isClear = h >= needed
            if (wasClear != isClear) {
                // Where the tide crosses the needed height, between the two samples.
                val cross = tPrev + ((needed - hPrev) / (h - hPrev) * (t - tPrev)).toLong().coerceIn(0L, t - tPrev)
                if (isClear) {
                    openSince = cross
                } else {
                    val start = openSince ?: tPrev
                    if (cross > start) periods += Period(start, cross)
                    openSince = null
                }
            }
            tPrev = t
            hPrev = h
        }
        // Clear at the start counts even when the tides stop straight after (the end of the
        // saved predictions): else that would read as never enough water.
        openSince?.let { if (known > it || it == fromMillis) periods += Period(it, known) }
        return Result(fromMillis, known, needed, periods, highest, highestAt, lowest)
    }

    /** A leg's shallowest charted point as the boat passes it: [tideAt] gives the tide there (above the survey's datum), null without tides there. */
    data class PassPoint(val legNumber: Int, val chartDepthM: Double, val arrivalMillis: Long, val tideAt: ((Long) -> Double?)?)

    /** The water at a leg's shallowest point when the boat gets there. */
    data class Pass(val legNumber: Int, val arrivalMillis: Long, val waterM: Double)

    /** The least water met on the way ([worst]), every pass with less than needed ([short]), and the legs with no tide known. */
    data class Passage(val worst: Pass?, val short: List<Pass>, val unknownLegs: List<Int>)

    /** The water at each of [points] at its own arrival time, against [neededDepthM] (draft + margin). */
    fun passage(points: List<PassPoint>, neededDepthM: Double): Passage {
        val known = mutableListOf<Pass>()
        val unknown = mutableListOf<Int>()
        for (p in points) {
            val tide = p.tideAt?.invoke(p.arrivalMillis)
            if (tide == null) unknown += p.legNumber else known += Pass(p.legNumber, p.arrivalMillis, p.chartDepthM + tide)
        }
        return Passage(known.minByOrNull { it.waterM }, known.filter { it.waterM < neededDepthM }.sortedBy { it.waterM }, unknown)
    }

    private const val STEP_MS = 5 * 60_000L
}
