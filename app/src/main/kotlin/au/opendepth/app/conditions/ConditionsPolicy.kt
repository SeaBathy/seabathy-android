package au.opendepth.app.conditions

/**
 * When saved sea conditions are updated, which copy is used for a place,
 * and which are removed. Kept apart from the screen code so each rule can
 * be tested on its own.
 */
object ConditionsPolicy {

    /** Updated at least this often when online, even with no word of a new forecast (the satellites are daily). */
    const val MAX_AGE_MS = 12 * 3_600_000L

    /** A saved area whose forecast ran out this long ago is no use, and is removed. */
    const val EXPIRED_KEEP_MS = 2 * 24 * 3_600_000L

    /** A saved area with satellite pictures only is removed this long after it was downloaded. */
    const val SATELLITE_ONLY_KEEP_MS = 7 * 24 * 3_600_000L

    /**
     * True when there's newer data to get than a copy downloaded at
     * [fetchedMillis]: a forecast run came out after it ([latestRunMillis],
     * when known), or it's older than [MAX_AGE_MS].
     */
    fun needsUpdate(fetchedMillis: Long, latestRunMillis: Long?, nowMillis: Long): Boolean =
        (latestRunMillis != null && latestRunMillis > fetchedMillis) || nowMillis - fetchedMillis > MAX_AGE_MS

    /** The newest saved area covering [lat]/[lng]: the one to use there. */
    fun newestCovering(lat: Double, lng: Double, areas: List<ConditionsStore.Summary>): ConditionsStore.Summary? =
        areas.filter { lat in it.south..it.north && lng in it.west..it.east }.maxByOrNull { it.fetchedMillis }

    /** Why an area is removed. */
    enum class Reason { EXPIRED, COVERED_BY_NEWER }

    /**
     * Saved areas to remove, never [keepId] (the area kept round the spot,
     * which each update replaces instead): a forecast that ran out more than
     * [EXPIRED_KEEP_MS] ago, satellite pictures more than
     * [SATELLITE_ONLY_KEEP_MS] old, or an older copy of the same block of
     * water: wholly inside a newer one about the same size, with everything
     * it has. A small area inside a much bigger one is kept: being smaller,
     * its points are closer together, the detail it was saved for.
     */
    fun toRemove(areas: List<ConditionsStore.Summary>, keepId: String, nowMillis: Long): List<Pair<ConditionsStore.Summary, Reason>> =
        areas.filter { it.id != keepId }.mapNotNull { area ->
            val until = area.forecastUntilMillis
            val expired = if (until != null) nowMillis - until > EXPIRED_KEEP_MS else nowMillis - area.fetchedMillis > SATELLITE_ONLY_KEEP_MS
            when {
                expired -> area to Reason.EXPIRED
                areas.any { newer -> newer.id != area.id && covers(newer, area) } -> area to Reason.COVERED_BY_NEWER
                else -> null
            }
        }

    /** [newer] is a newer copy of [older]'s block: newer, holds all of its water, no more than [SAME_BLOCK] times as big, with every kind of data it has. */
    private fun covers(newer: ConditionsStore.Summary, older: ConditionsStore.Summary): Boolean {
        if (newer.fetchedMillis <= older.fetchedMillis) return false
        if (span(newer) > span(older) * SAME_BLOCK) return false
        val slack = 1e-6
        val inside = older.south >= newer.south - slack && older.north <= newer.north + slack &&
            older.west >= newer.west - slack && older.east <= newer.east + slack
        val hasForecast = older.forecastUntilMillis == null || newer.forecastUntilMillis != null
        return inside && hasForecast && newer.satellites.containsAll(older.satellites)
    }

    private fun span(area: ConditionsStore.Summary) = maxOf(area.north - area.south, area.east - area.west)

    /** How much bigger a newer area may be and still count as a copy of the same block. */
    private const val SAME_BLOCK = 1.25
}
