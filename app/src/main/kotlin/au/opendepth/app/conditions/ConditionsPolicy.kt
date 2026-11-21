package au.opendepth.app.conditions

/**
 * When saved sea conditions are updated, which copy is used for a place,
 * and which are removed. Kept apart from the screen code so each rule can
 * be tested on its own.
 */
object ConditionsPolicy {

    private const val HOUR_MS = 3_600_000L

    // When a saved forecast wants downloading again. The automatic downloads ([SpotRules],
    // [WeatherEngine.keepFresh]) and the box's dot ([freshness]) all go by [updateReason], so the
    // dot turns red exactly when the app would start a download by itself.

    /** Updated this often when the forecast service's "is there a newer run?" can't be read. */
    const val NO_WORD_MAX_AGE_MS = 6 * HOUR_MS

    /**
     * A newer forecast run is only fetched once the saved copy is this old. Several models bring
     * out runs through the day; chasing each one would spend the free service's daily allowance
     * for changes too small to matter on the water.
     */
    const val NEW_RUN_MIN_AGE_MS = 4 * HOUR_MS

    /** Updated this often whatever the service says. */
    const val OLD_AGE_MS = 24 * HOUR_MS

    /** The forecast is "running out" when it has less than this left: a new one is downloaded. */
    const val RUNNING_OUT_MS = 12 * HOUR_MS

    /** A forecast that's still running out after a download is asked for again no sooner than this. */
    const val RUNNING_OUT_RETRY_MS = HOUR_MS

    /** With the backup forecast (or the backup's wind) on show, the main one is tried again once it's this old. */
    const val BACKUP_RECHECK_MS = 10 * 60_000L

    /** Saved "later than now" by more than this: the phone's clock was set back. */
    const val CLOCK_SLACK_MS = 10 * 60_000L

    /**
     * Saved data this old is called old in the box's note ([ConditionsFeature.staleNote]), even
     * online: half a day. Not a download rule: those are above.
     */
    const val MAX_AGE_MS = 12 * HOUR_MS

    /**
     * Why a saved forecast wants downloading again, or null when it's fine. [fetchedMillis]: when
     * it was downloaded; [forecastEndMillis]: its last hour (null: no forecast); [runSeenMillis]:
     * the newest run there was when it was downloaded (a newer one is told from it by the
     * service's own times, so a phone clock that's wrong can't make every forecast look old);
     * [backup]: the backup's forecast or wind is on show ([ConditionsArea.wantsMainAgain]).
     */
    fun updateReason(
        fetchedMillis: Long, forecastEndMillis: Long?, latestRunMillis: Long?, nowMillis: Long,
        runSeenMillis: Long? = null, backup: Boolean = false
    ): String? {
        val age = nowMillis - fetchedMillis
        if (age < -CLOCK_SLACK_MS) return "the phone's clock was set back"
        if (backup) return if (age > BACKUP_RECHECK_MS) "the backup forecast (or its wind) is on show" else null
        if (forecastEndMillis != null && forecastEndMillis - nowMillis < RUNNING_OUT_MS && age > RUNNING_OUT_RETRY_MS) return "the forecast runs out soon"
        val newer = latestRunMillis != null && latestRunMillis > (runSeenMillis ?: fetchedMillis)
        if (newer && age > NEW_RUN_MIN_AGE_MS) return "a newer forecast is out"
        if (latestRunMillis == null && age > NO_WORD_MAX_AGE_MS) return "saved ${age / HOUR_MS} h ago, and the service couldn't say whether there's a newer one"
        if (age > OLD_AGE_MS) return "saved ${age / HOUR_MS} h ago"
        return null
    }

    /** A saved area whose forecast ran out this long ago is no use, and is removed. */
    const val EXPIRED_KEEP_MS = 2 * 24 * 3_600_000L

    /** A saved area with satellite pictures only is removed this long after it was downloaded. */
    const val SATELLITE_ONLY_KEEP_MS = 7 * 24 * 3_600_000L

    /**
     * True when there's newer data worth getting than a copy downloaded at [fetchedMillis], by
     * its age and the runs alone ([updateReason] without the forecast's end or the backup): a
     * newer run than [runSeenMillis] once the copy is [NEW_RUN_MIN_AGE_MS] old; with no word from
     * the service, [NO_WORD_MAX_AGE_MS]; and [OLD_AGE_MS] whatever it says.
     */
    fun needsUpdate(fetchedMillis: Long, latestRunMillis: Long?, nowMillis: Long, runSeenMillis: Long? = null): Boolean =
        updateReason(fetchedMillis, null, latestRunMillis, nowMillis, runSeenMillis) != null

    /** How up to date the sea conditions on show are: the dot on the box. */
    enum class Freshness { CURRENT, UPDATING, OUT_OF_DATE }

    /**
     * [Freshness.UPDATING] while this area's forecast is downloading; [Freshness.OUT_OF_DATE]
     * when the app would download it again ([updateReason]); otherwise [Freshness.CURRENT].
     * Pure, unit-tested.
     */
    fun freshness(
        fetchedMillis: Long, forecastEndMillis: Long?, latestRunMillis: Long?, nowMillis: Long, downloading: Boolean,
        runSeenMillis: Long? = null, backup: Boolean = false
    ): Freshness = when {
        downloading -> Freshness.UPDATING
        updateReason(fetchedMillis, forecastEndMillis, latestRunMillis, nowMillis, runSeenMillis, backup) != null -> Freshness.OUT_OF_DATE
        else -> Freshness.CURRENT
    }

    /** [area]'s dot: as above, with its backup ([ConditionsArea.wantsMainAgain]) taken into account. */
    fun freshness(area: ConditionsArea, latestRunMillis: Long?, nowMillis: Long, downloading: Boolean, runSeenMillis: Long?): Freshness =
        freshness(area.fetchedMillis, area.forecast?.endMillis, latestRunMillis, nowMillis, downloading, runSeenMillis, area.wantsMainAgain)

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
