package au.opendepth.app.conditions

import android.content.SharedPreferences
import java.io.IOException
import kotlin.math.min

/** The forecast service can't be asked until [untilMillis]: its limit is reached, or this app's share of it is. */
class LimitReachedException(val untilMillis: Long, message: String) : IOException(message)

/** Which of the forecast service's limits. */
enum class LimitKind { MINUTE, HOUR, DAY }

/**
 * Keeps the app inside the free forecast service's limits, so it can never use them up by
 * itself. Open-Meteo allows each internet address 600 calls a minute, 5,000 an hour and 10,000
 * a day, and every point of a request is a call (an area is up to 256 for the sea and the same
 * for the wind). The sea forecast and the wind are two services there, each counted on its own
 * ([MARINE], [WEATHER]): one can be at its limit while the other still answers.
 *
 * - Every request is counted here first ([reserve]); when the minute is full it says how long
 *   to wait, so downloads follow one another instead of being refused.
 * - A download is only started when the hour and the day have room for all of it ([check]).
 *   Automatic updates stop well short of the limits, leaving the rest for the user's own taps.
 * - When the service itself says "too many requests" ([limited]: other phones can share the
 *   address), nothing more is sent to it until that limit has passed.
 *
 * What was spent is remembered across restarts ([rememberedIn]). Pure, unit-tested.
 */
class CallBudget(private val clock: () -> Long = System::currentTimeMillis) {
    private class Ledger {
        /** Calls spent: time, count. Oldest first, a day at most. */
        val spent = ArrayDeque<LongArray>()
        var blockedUntil = 0L
        var blockedKind: LimitKind? = null
    }

    private val ledgers = HashMap<String, Ledger>()

    private fun of(api: String) = ledgers.getOrPut(api) { Ledger() }

    /** Told the saved form after every change. */
    @Volatile
    var onChange: ((String) -> Unit)? = null

    /** Calls spent in the last [windowMs]. */
    @Synchronized
    fun used(windowMs: Long, api: String = MARINE): Int {
        val from = clock() - windowMs
        return of(api).spent.sumOf { if (it[0] > from) it[1].toInt() else 0 }
    }

    /** When the sea forecast's limit has passed (0 when none was hit). */
    val blockedUntilMillis: Long get() = blockedUntil(MARINE)

    /** When [api]'s limit has passed (0 when none was hit). */
    @Synchronized
    fun blockedUntil(api: String): Long = of(api).blockedUntil.takeIf { clock() < it } ?: 0L

    /**
     * Throws [LimitReachedException] when a download of [calls] doesn't fit in what's left of
     * the hour or the day (less for an automatic one), or the service has said to wait. Counts
     * nothing: [reserve] does, request by request.
     */
    @Synchronized
    fun check(calls: Int, userAsked: Boolean, api: String = MARINE) {
        val now = clock()
        val ledger = of(api)
        prune(ledger, now)
        if (now < ledger.blockedUntil) throw LimitReachedException(ledger.blockedUntil, text(ledger.blockedKind ?: LimitKind.HOUR))
        val day = used(DAY_MS, api)
        val dayCap = if (userAsked) DAY_CAP else DAY_AUTO_CAP
        if (day + calls > dayCap) throw LimitReachedException(freeAt(ledger, DAY_MS, day + calls - dayCap, now), text(LimitKind.DAY))
        val hour = used(HOUR_MS, api)
        val hourCap = if (userAsked) HOUR_CAP else HOUR_AUTO_CAP
        if (hour + calls > hourCap) throw LimitReachedException(freeAt(ledger, HOUR_MS, hour + calls - hourCap, now), text(LimitKind.HOUR))
    }

    /**
     * Counts a request of [calls] and returns 0, or returns how long to wait first (nothing
     * counted) when the minute is full. Throws while the service's hourly or daily limit lasts.
     */
    @Synchronized
    fun reserve(calls: Int, api: String = MARINE): Long {
        val now = clock()
        val ledger = of(api)
        prune(ledger, now)
        if (now < ledger.blockedUntil) {
            if (ledger.blockedKind == LimitKind.MINUTE) return ledger.blockedUntil - now
            throw LimitReachedException(ledger.blockedUntil, text(ledger.blockedKind ?: LimitKind.HOUR))
        }
        val minute = used(MINUTE_MS, api)
        if (minute > 0 && minute + calls > MINUTE_CAP) return (freeAt(ledger, MINUTE_MS, minute + calls - MINUTE_CAP, now) - now).coerceAtLeast(1L)
        count(ledger, calls, now)
        return 0L
    }

    /** Counts a request of [calls] whatever the minute holds (after waiting hasn't made room). */
    @Synchronized
    fun count(calls: Int, api: String = MARINE) = count(of(api), calls, clock())

    /**
     * The service answered "too many requests" for [kind]. Returns when it's asked again: when
     * that limit should have passed, or sooner for the hour's and the day's. Whether the service
     * counts by the clock or over the last hour or day isn't said, so it isn't left unasked for
     * a whole one; one small request that's refused costs nothing.
     */
    @Synchronized
    fun limited(kind: LimitKind, api: String = MARINE): Long {
        val now = clock()
        val ledger = of(api)
        val until = when (kind) {
            LimitKind.MINUTE -> now + MINUTE_MS + 1_000L
            LimitKind.HOUR -> min((now / HOUR_MS + 1) * HOUR_MS + MINUTE_MS, now + HOUR_RETRY_MS)
            LimitKind.DAY -> min((now / DAY_MS + 1) * DAY_MS + MINUTE_MS, now + DAY_RETRY_MS)
        }
        if (until > ledger.blockedUntil) {
            ledger.blockedUntil = until
            ledger.blockedKind = kind
        }
        changed()
        return ledger.blockedUntil
    }

    /** What [saved] gave back from the last run of the app. */
    @Synchronized
    fun restore(saved: String?) {
        saved ?: return
        val now = clock()
        for (part in saved.split('|')) {
            val api = if ('#' in part) part.substringBefore('#') else MARINE
            val fields = part.substringAfter('#').split(';')
            if (fields.size < 4 || fields[0] != FORMAT) continue
            val ledger = of(api)
            ledger.blockedUntil = fields[1].toLongOrNull() ?: 0L
            ledger.blockedKind = runCatching { LimitKind.valueOf(fields[2]) }.getOrNull()
            // A limit that would last longer than a day is a clock that changed: forgotten.
            if (ledger.blockedUntil - now > DAY_MS + HOUR_MS) ledger.blockedUntil = 0L
            ledger.spent.clear()
            for (entry in fields[3].split(',')) {
                val time = entry.substringBefore(':').toLongOrNull() ?: continue
                val calls = entry.substringAfter(':', "").toLongOrNull() ?: continue
                // Not from the future either (the clock was set back since).
                if (time <= now && calls > 0) ledger.spent.addLast(longArrayOf(time, calls))
            }
            prune(ledger, now)
        }
    }

    @Synchronized
    fun saved(): String = ledgers.entries.joinToString("|") { (api, l) ->
        "$api#$FORMAT;${l.blockedUntil};${l.blockedKind?.name.orEmpty()};" + l.spent.joinToString(",") { "${it[0]}:${it[1]}" }
    }

    private fun count(ledger: Ledger, calls: Int, now: Long) {
        val last = ledger.spent.lastOrNull()
        // Requests of the same few seconds are kept as one, so the list stays short.
        if (last != null && now - last[0] in 0 until MERGE_MS) last[1] += calls.toLong() else ledger.spent.addLast(longArrayOf(now, calls.toLong()))
        changed()
    }

    private fun changed() {
        onChange?.invoke(saved())
    }

    private fun prune(ledger: Ledger, now: Long) {
        while (ledger.spent.isNotEmpty() && now - ledger.spent.first()[0] > DAY_MS) ledger.spent.removeFirst()
    }

    /** When [excess] calls of the last [windowMs] will have passed out of it. */
    private fun freeAt(ledger: Ledger, windowMs: Long, excess: Int, now: Long): Long {
        var freed = 0L
        for (entry in ledger.spent) {
            if (entry[0] <= now - windowMs) continue
            freed += entry[1]
            if (freed >= excess) return entry[0] + windowMs + 1_000L
        }
        return now + windowMs
    }

    companion object {
        /** The sea forecast (waves, swell, currents, sea temperature) and the wind: two services, each with its own limits. */
        const val MARINE = "marine"
        const val WEATHER = "weather"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        /** Of the service's 600 a minute. */
        const val MINUTE_CAP = 480
        /** Of the service's 5,000 an hour: all use, and automatic updates alone. */
        const val HOUR_CAP = 4_200
        const val HOUR_AUTO_CAP = 2_400
        /** Of the service's 10,000 a day: all use, and automatic updates alone. */
        const val DAY_CAP = 9_000
        const val DAY_AUTO_CAP = 6_000
        /** A limit the service named is asked about again at least this often. */
        const val HOUR_RETRY_MS = 20 * MINUTE_MS
        const val DAY_RETRY_MS = 2 * HOUR_MS
        private const val MERGE_MS = 5_000L
        private const val FORMAT = "1"
        private const val KEY = "conditions_call_budget"

        /** The one budget all of the app's forecast requests share. */
        val shared = CallBudget()

        /** Which of the two services a request goes to. */
        fun apiOf(url: String): String = if ("marine-api" in url) MARINE else WEATHER

        fun text(kind: LimitKind): String = when (kind) {
            LimitKind.MINUTE -> "the free forecast service is busy for a minute"
            LimitKind.HOUR -> "the free forecast service's hourly limit is reached"
            LimitKind.DAY -> "the free forecast service's daily limit is reached"
        }

        /** Which limit a "too many requests" answer names; the minute's when it doesn't say. */
        fun kindOf(reason: String?): LimitKind {
            val r = reason.orEmpty().lowercase()
            return when {
                "dai" in r || "tomorrow" in r -> LimitKind.DAY
                "hour" in r -> LimitKind.HOUR
                else -> LimitKind.MINUTE
            }
        }
    }

    /** This budget, remembering what was spent in [prefs] (read back the first time). */
    fun rememberedIn(prefs: SharedPreferences): CallBudget {
        synchronized(this) {
            if (onChange == null) {
                restore(prefs.getString(KEY, null))
                onChange = { prefs.edit().putString(KEY, it).apply() }
            }
        }
        return this
    }
}
