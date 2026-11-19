package au.opendepth.app.conditions

import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.nav.NavGeo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * When the newest forecast run of all came out, and the newest of the sea models alone (waves and
 * currents: they come out less often than the wind's). Null when it couldn't be read.
 */
data class RunTimes(val newest: Long?, val sea: Long?)

/** What the engine needs from the network. The app's goes through [ConditionsClient] and [Coastline]; tests use a fake. */
interface WeatherNet {
    /**
     * The main forecast. [userAsked]: a download the user asked for may use the share of the service's limit kept back from automatic ones.
     * [keepSea]: the forecast saved for this same block, when its sea values are still the newest there are: only the wind is downloaded, and laid over them.
     */
    fun forecast(south: Double, west: Double, north: Double, east: Double, days: Int, userAsked: Boolean, keepSea: ForecastGrid?, onProgress: (Double) -> Unit): ForecastGrid
    /** The backup forecast (coarser; wind, waves and swell only), from another service: for when [forecast] can't be had. */
    fun backupForecast(south: Double, west: Double, north: Double, east: Double, days: Int): ForecastGrid
    fun satellite(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double, depthM: Int): SatelliteGrid
    /** For each point: where the marine model has sea at or next to it, or null on land. */
    fun seaAt(points: List<Pair<Double, Double>>): List<Pair<Double, Double>?>
    /** When the newest forecast runs came out (free to ask). */
    fun latestRuns(): RunTimes
    /** Saves the coastline for the area (for cutting the picture at the shore). */
    fun coastline(south: Double, west: Double, north: Double, east: Double)
}

/** A few small values the engine keeps between runs (the app's preferences; a map in tests). */
interface WeatherKeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Runs [run] after [delayMs] on the engine's thread; returns a way to cancel it. */
fun interface WeatherTimer {
    fun after(delayMs: Long, run: () -> Unit): () -> Unit
}

data class WeatherSettings(val days: Int, val withSatellite: Boolean, val depthM: Int, val autoUpdate: Boolean)

/** What the engine is doing: shown in the box and the settings sheet. */
sealed class WeatherState {
    object Idle : WeatherState()
    data class Working(val what: String, val userAsked: Boolean) : WeatherState()
    /** A download is due but waits: for the internet ([untilMillis] 0) or a retry at [untilMillis]. The saved data stays on show. */
    data class Waiting(val untilMillis: Long, val reason: String) : WeatherState()
}

/** What the engine tells the screens, always on the engine's (main) thread. */
interface WeatherEvents {
    /** An area was saved: the forecast first, then again with the satellite pictures. */
    fun saved(area: ConditionsArea, isSpot: Boolean)
    fun stateChanged(state: WeatherState)
    /**
     * A download finished: [area] null when it got nothing. Messages only matter for ones the user
     * asked for. [alreadyLatest]: nothing newer was out, so the saved forecast was kept as it is.
     */
    fun finished(area: ConditionsArea?, problems: List<String>, userAsked: Boolean, note: String?, alreadyLatest: Boolean)
    /** The coastline for an area is saved: the picture can be cut sharper. */
    fun coastlineReady() {}
}

/**
 * The sea conditions' engine: what to download and when, one download at a time, and what's
 * saved. The screens ask it, and show whatever is saved meanwhile: a download never takes away
 * what's on show.
 *
 * - The spot's area (200 km round the boat, or over the nearest sea inland) is downloaded when
 *   there's none, the boat has moved 100 km since, the forecast runs out within 12 hours, or a
 *   newer forecast run is out and what's saved is 4 hours old ([SpotRules]). Whether a newer run
 *   is out is asked of the service first (a few small files, free): nothing is downloaded on the
 *   off-chance, and a download the user asks for is skipped when what's saved is the latest.
 * - One download at a time, in order; every one, however it ends, leaves the engine ready for
 *   the next.
 * - A failed automatic download is tried again after 30 s, 1, 2, 5, 10, then every 15 minutes;
 *   back online or asked by the user, at once. Offline it waits for the internet.
 * - No more than [MAX_AUTO_PER_HOUR] automatic downloads an hour, and none within
 *   [MIN_AUTO_GAP_MS] of the last, whatever the rules read. Every request is also counted
 *   against the free forecast service's limits ([CallBudget]: one area is up to 256 calls of
 *   each of its two services), so
 *   the app can't use them up by itself.
 * - When the main service can't be had (down, or its limit reached) and what's saved is
 *   missing, old or running out, the backup forecast is saved instead ([BackupForecast]), and
 *   the main one is tried again later.
 * - The forecast is saved and shown as soon as it's in; the satellite pictures follow (once a
 *   day: they change daily), then the coastline.
 */
class WeatherEngine(
    private val store: ConditionsStore,
    private val net: WeatherNet,
    private val kv: WeatherKeyValues,
    private val settings: () -> WeatherSettings,
    private val clock: () -> Long,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    /** Where downloads and file work run (a background thread in the app). */
    private val io: CoroutineDispatcher,
    /** Where the satellite pictures are fetched side by side. */
    private val parallel: CoroutineDispatcher,
    private val timer: WeatherTimer,
    private val events: WeatherEvents,
    private val log: (String) -> Unit = { DiagnosticLog.log(TAG, it) }
) {
    /** The spot's saved area, kept in memory: what the box shows for the boat. */
    var spotArea: ConditionsArea? = null
        private set
    var state: WeatherState = WeatherState.Idle
        private set
    /** When the newest forecast run came out, as last asked. */
    var latestRun: Long? = null
        private set
    /** The newest run of the sea models alone, from the same look. */
    private var latestSeaRun: Long? = null

    private var spot: Pair<Double, Double>? = null
    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private var failures = 0
    private var retryAt = 0L
    private var cancelRetry: (() -> Unit)? = null
    private val autoTimes = ArrayDeque<Long>()
    /** When the last automatic download of the spot's area succeeded. */
    private var lastAutoSpotAt = 0L
    /** From the job just run: the service's limit lasts until then, and the backup forecast is what's on show. */
    private var limitUntil = 0L
    private var backupShown = false
    /** The job just run found no sea in reach and downloaded nothing: it doesn't hold the next download back. */
    private var lookedOnly = false
    private var stateBefore: WeatherState = WeatherState.Idle
    private var heldSaidAt = 0L
    private var lastRunCheck = 0L
    private var runChecking = false
    /** Where the spot's area goes for a spot (by spot rounded to about 10 km): its middle, and the nearest sea when inland. */
    private val seaCentres = HashMap<String, Pair<Pair<Double, Double>, Pair<Double, Double>?>>()

    private sealed class Job {
        abstract val userAsked: Boolean
        abstract val key: String

        data class Spot(val at: Pair<Double, Double>, override val userAsked: Boolean, val why: String, val moved: Boolean = false) : Job() {
            override val key = "spot"
        }

        data class Area(
            val south: Double, val west: Double, val north: Double, val east: Double,
            val replacingId: String?, override val userAsked: Boolean, val note: String?
        ) : Job() {
            override val key = "area:${replacingId ?: String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", south, west, north, east)}"
        }

        data class Depth(val areaId: String, override val userAsked: Boolean) : Job() {
            override val key = "depth:$areaId"
        }

        /** Only the satellite pictures that are due for the spot's area: no forecast is asked for. */
        object Pictures : Job() {
            override val userAsked = false
            override val key = "pictures"
        }
    }

    // Asking ------------------------------------------------------------------------------

    /** The saved spot area, read at start-up. */
    fun start(savedSpot: ConditionsArea?) {
        spotArea = savedSpot
    }

    /** Where the spot is now (the boat, a set spot, or null while there's no position). Cheap: call on every fix. */
    fun setSpot(at: Pair<Double, Double>?) {
        spot = at
        evaluate()
    }

    /**
     * Downloads the spot's area if it needs it (or now, with [userAsked]). Returns why it didn't
     * start one, for a message when the user asked: null when it did (or nothing was needed).
     */
    fun evaluate(userAsked: Boolean = false): String? {
        val at = spot ?: spotArea?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 }
            ?: return "Waiting for the GPS: set a spot, or turn the GPS on"
        if (!userAsked && !settings().autoUpdate) return null
        // Far inland, where the last look found no sea in reach: there's nothing to download
        // until the boat is somewhere else (or it's asked for by hand, which says why not).
        if (!userAsked && noSeaNear(at)) {
            if (state is WeatherState.Waiting && running == null) setState(WeatherState.Idle)
            return null
        }
        val now = clock()
        val need = if (userAsked) SpotRules.Need("asked") else SpotRules.needs(at, spotArea, origin(), latestRun, now, runSeen(ConditionsFeature.SPOT_ID)) ?: run {
            // Nothing needed: a wait for the internet is over. (A wait for a retry lasts until its time.)
            if (state is WeatherState.Waiting && running == null && now >= retryAt) setState(WeatherState.Idle)
            topUpPictures(now)
            return null
        }
        if (running?.key == "spot" || queue.any { it.key == "spot" }) return null
        if (!online()) {
            if (running == null) setState(WeatherState.Waiting(0L, "no internet: will try again"))
            return "No internet: the saved data is still shown"
        }
        if (!userAsked) {
            // A wait for a retry or a limit holds, but not for a boat that's somewhere new: the
            // backup can serve the new place at once, and a limit that's reached costs nothing to find out.
            if (now < retryAt && !need.moved) {
                topUpPictures(now)
                return null
            }
            // Whatever the rules read, one automatic download doesn't follow another closely
            // (a boat that has moved right away aside): the last line against a download loop.
            if (!need.moved && now - lastAutoSpotAt in 0 until MIN_AUTO_GAP_MS) return null
            if (!allowAuto(now)) return null
        }
        enqueue(Job.Spot(at, userAsked, need.why, need.moved))
        return null
    }

    /** Downloads [south]..[east] as an area of its own (or updating [replacingId]). */
    fun downloadArea(south: Double, west: Double, north: Double, east: Double, replacingId: String?, userAsked: Boolean, note: String? = null): String? {
        if (!online()) return "No internet: the saved data is still shown"
        val job = Job.Area(south, west, north, east, replacingId, userAsked, note)
        if (running?.key == job.key || queue.any { it.key == job.key }) return null
        if (!userAsked && (clock() < retryAt || !allowAuto(clock()))) return null
        enqueue(job)
        return null
    }

    /**
     * The spot's area has satellite pictures due (missing, or their server failed last time) and
     * it's a while since they were tried: just those are fetched. Their servers have bad hours;
     * this brings a picture back without waiting for the next forecast.
     */
    private fun topUpPictures(now: Long) {
        val a = spotArea ?: return
        if (running != null || queue.isNotEmpty() || !settings().withSatellite || !online()) return
        val last = kv.get(KEY_SATELLITES_AT + a.id)?.toLongOrNull() ?: return
        if (now - last <= SATELLITE_RETRY_MS || satelliteKindsDue(a, true, now).isEmpty()) return
        enqueue(Job.Pictures)
    }

    /** Keeps [area] (one on the map that isn't the spot's) up to date, after the spot. */
    fun keepFresh(area: ConditionsArea) {
        if (area.id == ConditionsFeature.SPOT_ID || !settings().autoUpdate || !online()) return
        val now = clock()
        // The backup on show: the main forecast is tried again.
        val backupDue = area.isBackup && now - area.fetchedMillis > SpotRules.BACKUP_RECHECK_MS
        if (!backupDue && !ConditionsPolicy.needsUpdate(area.fetchedMillis, latestRun, now, runSeen(area.id))) return
        downloadArea(area.south, area.west, area.north, area.east, area.id, userAsked = false)
    }

    /** Fetches only the temperature at the depth now set, for [areaId]. */
    fun downloadDepth(areaId: String): String? {
        if (!online()) return "No internet: the temperature at that depth comes with the next update"
        enqueue(Job.Depth(areaId, userAsked = true))
        return null
    }

    /** The internet is back: whatever was waiting goes now. */
    fun onOnline() {
        retryAt = 0L
        cancelRetry?.invoke()
        cancelRetry = null
        if (state is WeatherState.Waiting && running == null) setState(WeatherState.Idle)
        checkRun(force = true)
        evaluate()
    }

    /** At most every 15 minutes (or now, with [force]): is a newer forecast run out? Then the spot is looked at again. */
    fun checkRun(force: Boolean = false) {
        val now = clock()
        if (runChecking || !online() || (!force && now - lastRunCheck < RUN_CHECK_MS)) return
        lastRunCheck = now
        runChecking = true
        scope.launch {
            try {
                val runs = withContext(io) { runCatching { net.latestRuns() }.getOrNull() }
                val run = runs?.newest
                if (run != null) {
                    latestRun = run
                    latestSeaRun = runs.sea
                }
                log("update check: newest forecast run ${run?.let { ago(it, clock()) } ?: "couldn't be read"}")
            } finally {
                runChecking = false
            }
            evaluate()
        }
    }

    /** The saved spot area was deleted by the user. */
    fun spotDeleted() {
        spotArea = null
        kv.put(KEY_SPOT_FROM, null)
        // Not straight back: it downloads again at the next update.
        retryAt = clock() + RETRIES_MS.first()
    }

    val busy: Boolean get() = running != null

    /** The spot is far inland: the last look found no sea within the rings' reach, so nothing is downloaded for it. */
    val noSeaInReach: Boolean get() = spot?.let { noSeaNear(it) } == true

    /** Found none near [at], and not long ago: it's looked for again after [NO_SEA_KEEP_MS], in case the service was only missing its data. */
    private fun noSeaNear(at: Pair<Double, Double>): Boolean {
        val v = kv.get(KEY_NO_SEA)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 } ?: return false
        return clock() - v[2].toLong() in 0..NO_SEA_KEEP_MS && NavGeo.distanceM(v[0], v[1], at.first, at.second) < NO_SEA_KEEP_KM * 1000
    }

    /** For an inland spot: the nearest sea found for it (where the box reads), or null. */
    fun nearestSeaFor(at: Pair<Double, Double>): Pair<Double, Double>? {
        val v = kv.get(KEY_SPOT_SEA)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 } ?: return null
        if (NavGeo.distanceM(v[0], v[1], at.first, at.second) > SpotRules.MOVE_KM * 1000) return null
        return v[2] to v[3]
    }

    // The queue ---------------------------------------------------------------------------

    private fun enqueue(job: Job) {
        // The newest spot request replaces one still waiting.
        queue.removeAll { it.key == job.key }
        if (job is Job.Spot) queue.addFirst(job) else queue.addLast(job)
        pump()
    }

    private fun pump() {
        if (running != null) return
        val job = queue.removeFirstOrNull() ?: return
        running = job
        limitUntil = 0L
        backupShown = false
        lookedOnly = false
        stateBefore = state
        // The pictures come from other servers: they don't count against the forecast's.
        if (!job.userAsked && job !is Job.Pictures) autoTimes.addLast(clock())
        scope.launch {
            var ok = false
            try {
                ok = when (job) {
                    is Job.Spot -> runSpot(job)
                    is Job.Area -> runArea(job)
                    is Job.Depth -> runDepth(job)
                    is Job.Pictures -> runPictures()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiagnosticLog.logError(TAG, "sea conditions job FAILED", e)
            } finally {
                running = null
                afterJob(job, ok)
            }
            pump()
        }
    }

    private fun afterJob(job: Job, ok: Boolean) {
        if (job is Job.Pictures) {
            // Nothing about the forecast changed: a wait that was on show stays on show.
            setState(stateBefore.takeIf { it is WeatherState.Waiting } ?: WeatherState.Idle)
            return
        }
        if (ok) {
            if (job is Job.Spot || !job.userAsked) {
                failures = 0
                retryAt = 0L
            }
            if (job is Job.Spot && !job.userAsked && !lookedOnly) lastAutoSpotAt = clock()
            setState(WeatherState.Idle)
            return
        }
        if (job.userAsked && job !is Job.Spot) {
            setState(WeatherState.Idle)
            return
        }
        // The spot (or an automatic update) failed: try again later, keeping what's saved on show.
        failures++
        val now = clock()
        val limited = limitUntil > now
        // A limit that's reached is waited out, not asked about every few minutes.
        val wait = max(RETRIES_MS[min(failures, RETRIES_MS.size) - 1], if (limited) limitUntil - now + LIMIT_SLACK_MS else 0L)
        retryAt = now + wait
        cancelRetry?.invoke()
        cancelRetry = timer.after(wait) {
            cancelRetry = null
            retryAt = 0L
            evaluate()
        }
        val minutes = wait / 60_000L
        val again = when {
            minutes < 1 -> "in a moment"
            minutes < 90 -> "in $minutes min"
            else -> "in ${(minutes + 30) / 60} h"
        }
        val reason = when {
            !online() -> "no internet: will try again"
            limited && backupShown -> "backup forecast on (free service's limit reached): main one again $again"
            limited -> "free forecast service's limit reached: trying again $again"
            backupShown -> "backup forecast on: main one tried again $again"
            else -> "couldn't download: trying again $again"
        }
        setState(WeatherState.Waiting(retryAt, reason))
        log("download failed (${failures} in a row${if (limited) ", limit reached" else ""}${if (backupShown) ", backup on show" else ""}): trying again in ${wait / 1000} s")
    }

    /** Automatic downloads stay under [MAX_AUTO_PER_HOUR] an hour. */
    private fun allowAuto(now: Long): Boolean {
        while (autoTimes.isNotEmpty() && now - autoTimes.first() > HOUR_MS) autoTimes.removeFirst()
        if (autoTimes.size < MAX_AUTO_PER_HOUR) return true
        // Said once in a while, not on every GPS fix.
        if (now - heldSaidAt > HELD_SAY_MS) {
            heldSaidAt = now
            log("held: already $MAX_AUTO_PER_HOUR automatic downloads in the last hour")
        }
        val wait = HOUR_MS - (now - autoTimes.first()) + 1_000L
        if (cancelRetry == null) cancelRetry = timer.after(wait) { cancelRetry = null; evaluate() }
        return false
    }

    private fun setState(new: WeatherState) {
        if (new == state) return
        state = new
        events.stateChanged(new)
    }

    // The jobs ----------------------------------------------------------------------------

    private suspend fun runSpot(job: Job.Spot): Boolean {
        val (lat, lng) = job.at
        log("updating the spot: ${job.why}")
        setState(WeatherState.Working("Finding the sea…", job.userAsked))
        val key = String.format(Locale.US, "%.1f,%.1f", lat, lng)
        val found = seaCentres[key] ?: withContext(io) {
            runCatching {
                // The boat first (one point); the rings only when it isn't on the water.
                if (net.seaAt(listOf(lat to lng)).first() != null) (lat to lng) to null
                else net.seaAt(SpotBlock.ringPoints(lat, lng)).let { sea ->
                    if (sea.none { it != null }) NO_SEA else SpotBlock.centre(lat, lng, sea) to SpotBlock.nearestSea(lat, lng, sea)
                }
            }.onFailure { DiagnosticLog.logError(TAG, "finding the nearest sea FAILED", it) }.getOrNull()
        // "None in reach" isn't kept here: it's asked again whenever a download is next tried.
        }?.also { if (it !== NO_SEA) seaCentres[key] = it }
        if (found === NO_SEA) {
            // An area here would be all land: 500 calls for a forecast with nothing in it to show.
            val reach = SpotBlock.RINGS_KM.last().toInt()
            kv.put(KEY_SPOT_SEA, null)
            kv.put(KEY_NO_SEA, String.format(Locale.US, "%.5f,%.5f,%d", lat, lng, clock()))
            lookedOnly = true
            log("no sea within $reach km of the spot: nothing to download")
            if (job.userAsked) events.finished(null, listOf("No sea within $reach km of here."), true, null, false)
            return true
        }
        if (found != null) kv.put(KEY_NO_SEA, null)
        val (cLat, cLng) = found?.first ?: (lat to lng)
        found?.second?.let { near ->
            kv.put(KEY_SPOT_SEA, String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lat, lng, near.first, near.second))
            log(String.format(Locale.US, "inland: the area goes over the sea, %.0f km away", NavGeo.distanceM(lat, lng, cLat, cLng) / 1000))
        } ?: run { if (found != null) kv.put(KEY_SPOT_SEA, null) }
        val b = ConditionsFeature.boxAround(cLat, cLng, SpotRules.RADIUS_KM)
        val saved = fetchAndSave(ConditionsFeature.SPOT_ID, ConditionsFeature.SPOT_NAME, b, isSpot = true, job.userAsked, note = null, freshBlock = job.moved)
        // Where the area on show was made for: this place when it was made just now (the main
        // forecast or the backup), not when an older one was kept as it was.
        if (spotArea?.sameBoundsAs(b[0], b[1], b[2], b[3]) == true) kv.put(KEY_SPOT_FROM, String.format(Locale.US, "%.5f,%.5f", lat, lng))
        return saved
    }

    private suspend fun runArea(job: Job.Area): Boolean {
        val b = doubleArrayOf(job.south, job.west, job.north, job.east)
        val id = job.replacingId ?: withContext(io) {
            store.summaries().firstOrNull { it.sameBoundsAs(job.south, job.west, job.north, job.east) }?.id
        } ?: java.util.UUID.randomUUID().toString()
        return fetchAndSave(id, ConditionsFeature.defaultName(job.south, job.west, job.north, job.east), b, isSpot = id == ConditionsFeature.SPOT_ID, job.userAsked, job.note)
    }

    private suspend fun runPictures(): Boolean {
        val a = spotArea ?: return true
        fetchAndSave(a.id, a.name, doubleArrayOf(a.south, a.west, a.north, a.east), isSpot = true, userAsked = false, note = null, keepForecast = true)
        return true
    }

    private suspend fun runDepth(job: Job.Depth): Boolean {
        val depth = settings().depthM
        setState(WeatherState.Working("Downloading the temperature down below…", true))
        val a = withContext(io) { store.load(job.areaId) } ?: return false
        val updated = withContext(io) {
            runCatching {
                val grid = net.satellite(SatelliteKind.TEMP_BELOW, a.south, a.west, a.north, a.east, depth)
                // The forecast's age stays as it was: only this picture is new.
                a.copyWith(satellites = a.satellites + (SatelliteKind.TEMP_BELOW to grid), belowDepthM = depth).also { store.save(it) }
            }.onFailure { DiagnosticLog.logError(TAG, "temperature at depth download FAILED", it) }.getOrNull()
        }
        if (updated == null) {
            events.finished(null, listOf("Temperature down below: couldn't download"), true, null, false)
            return false
        }
        publish(updated, updated.id == ConditionsFeature.SPOT_ID)
        events.finished(updated, emptyList(), true, null, false)
        return true
    }

    /**
     * The forecast for [wanted] (saved and shown as soon as it's in), then the satellite pictures
     * when due, then the coastline. True when the main forecast is in hand: downloaded, or
     * already the latest there is.
     *
     * - Asked for by the user when nothing newer is out, what's saved is kept as it is: no calls
     *   are spent fetching the same forecast again.
     * - When the main service can't be had and what's saved is missing, old or running out, the
     *   backup forecast is saved instead; false then, so the main one is tried again later.
     */
    private suspend fun fetchAndSave(
        id: String, name: String, wanted: DoubleArray, isSpot: Boolean, userAsked: Boolean, note: String?,
        /** Only the satellite pictures: the saved forecast stays as it is. */
        keepForecast: Boolean = false,
        /** The boat is somewhere new: its block is made afresh round it, whatever is saved. */
        freshBlock: Boolean = false
    ): Boolean {
        val s = settings()
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())
        val old = withContext(io) { runCatching { store.load(id) }.getOrNull() }
        if (keepForecast && old == null) return true
        // Asked by hand, or not yet known (a first download): is anything newer out? (A few small
        // files, free.) What's downloaded is then marked with the runs it came from.
        if ((userAsked && clock() - lastRunCheck > RUN_CHECK_MS) || latestRun == null) {
            lastRunCheck = clock()
            withContext(io) { runCatching { net.latestRuns() }.getOrNull() }?.takeIf { it.newest != null }?.let {
                latestRun = it.newest
                latestSeaRun = it.sea
            }
        }
        val runAtStart = latestRun
        val seaRunAtStart = latestSeaRun
        val upToDate = old != null && (keepForecast || (userAsked && isLatest(old, id, wanted, s, clock())))
        // The sea models have brought out nothing since what's saved: only the wind is downloaded.
        val keepSea = old?.takeIf { !upToDate && !freshBlock && seaIsLatest(it, id, wanted, s, clock()) }
        // Kept as it is (or its sea values kept), it keeps its own block.
        val b = if (upToDate || keepSea != null) doubleArrayOf(old!!.south, old.west, old.north, old.east) else wanted
        var forecast: ForecastGrid? = null
        /** The download that came was the wind alone, over the sea values saved. */
        var seaKept = keepSea != null
        if (upToDate) {
            if (!keepForecast) log("asked for, but what's saved is the latest forecast: nothing downloaded")
        } else {
            setState(WeatherState.Working("Downloading the forecast…", userAsked))
            val started = System.nanoTime()
            forecast = withContext(io) {
                var got = runCatching { net.forecast(b[0], b[1], b[2], b[3], s.days, userAsked, keepSea?.forecast) {} }
                // The wind alone couldn't be had: the whole forecast is asked for, as it always
                // was (its wind then comes from the backup). Not at a limit, which would refuse that too.
                if (got.isFailure && keepSea != null && got.exceptionOrNull() !is LimitReachedException && online()) {
                    log("the wind alone couldn't be had (${got.exceptionOrNull()?.let(::message)}): asking for the whole forecast")
                    seaKept = false
                    got = runCatching { net.forecast(b[0], b[1], b[2], b[3], s.days, userAsked, null) {} }
                }
                got
                    .onFailure {
                        DiagnosticLog.logError(TAG, "forecast download FAILED", it)
                        problems += "Forecast: ${message(it)}"
                        if (it is LimitReachedException) limitUntil = it.untilMillis
                    }
                    .getOrNull()
            }
            if (forecast != null) log("forecast (${s.days} days${if (seaKept) ", the wind alone: no newer sea forecast is out" else ""}) in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        // Pictures saved for this same block are kept (and topped up); a block that moved starts afresh.
        val sameBlock = old != null && overlap(old, b) >= SAME_BLOCK_OVERLAP
        val keptSats = if (sameBlock) old!!.satellites.filterKeys { !(it == SatelliteKind.TEMP_BELOW && old.belowDepthM != s.depthM) } else emptyMap()
        val now = clock()
        val kept = old?.takeIf { sameBlock && it.forecast != null }
        var source = ConditionsArea.SOURCE_MAIN
        if (forecast == null && !upToDate && online()) {
            // The main service can't be had. What's saved stays when it's still good; otherwise the backup.
            val keptIsGood = kept != null && kept.forecast!!.endMillis - now >= SpotRules.RUNNING_OUT_MS && now - kept.fetchedMillis in 0..BACKUP_AFTER_MS
            if (keptIsGood) {
                backupShown = kept!!.isBackup
            } else {
                setState(WeatherState.Working("Getting the backup forecast…", userAsked))
                forecast = withContext(io) {
                    runCatching { net.backupForecast(b[0], b[1], b[2], b[3], s.days) }
                        .onFailure { DiagnosticLog.logError(TAG, "backup forecast FAILED", it) }
                        .getOrNull()
                }
                if (forecast != null) {
                    source = ConditionsArea.SOURCE_BACKUP
                    backupShown = true
                    problems += if (forecast.has(ForecastField.GUST)) "The backup's waves and swell are shown meanwhile (coarser, and no currents)."
                    else "The backup forecast is shown meanwhile (coarser, with no gusts or currents)."
                    log("the backup forecast is saved: the main one is tried again later")
                }
            }
        }
        var area = ConditionsArea(
            id = id, name = old?.name ?: name, south = b[0], west = b[1], north = b[2], east = b[3],
            fetchedMillis = if (forecast != null) now else old?.fetchedMillis ?: now,
            forecast = forecast ?: kept?.forecast,
            satellites = keptSats, belowDepthM = s.depthM,
            source = if (forecast != null) source else kept?.source ?: ConditionsArea.SOURCE_MAIN
        )
        var savedAny = false
        if (forecast != null) {
            savedAny = withContext(io) { runCatching { store.save(area) }.onFailure { problems += "Saving: ${message(it)}" }.isSuccess }
            if (savedAny) {
                publish(area, isSpot)
                // The newest run there was when this came down: a newer one is told from it by the service's own times.
                if (source == ConditionsArea.SOURCE_MAIN) {
                    kv.put(KEY_RUN_AT + id, runAtStart?.toString())
                    // The sea values' own run: as it was, when only the wind came down.
                    if (!seaKept) kv.put(KEY_SEA_RUN + id, seaRunAtStart?.toString())
                } else kv.put(KEY_SEA_RUN + id, null)
            }
        }
        // Satellite pictures: once a day.
        val kinds = if (s.withSatellite) satelliteKindsDue(area, sameBlock, now) else emptyList()
        if (kinds.isNotEmpty() && online()) {
            setState(WeatherState.Working("Getting satellite pictures…", userAsked))
            val t0 = System.nanoTime()
            val again = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val got = coroutineScope {
                kinds.map { kind ->
                    async(parallel) {
                        kind to runCatching { net.satellite(kind, b[0], b[1], b[2], b[3], s.depthM) }
                            .onFailure {
                                if (kind.required) problems += "${kind.label}: ${message(it)}"
                                // A server that failed is asked again within hours; "nothing here today" waits for tomorrow.
                                if (it !is ConditionsClient.NoDataException) again += kind.name
                            }
                            .getOrNull()
                    }
                }.awaitAll()
            }
            val fresh = got.mapNotNull { (k, g) -> g?.let { k to it } }.toMap()
            log("satellite pictures ${fresh.keys.joinToString { it.name }} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            kv.put(KEY_SATELLITES_AT + id, clock().toString())
            kv.put(KEY_SATELLITES_RETRY + id, again.joinToString(",").ifEmpty { null })
            if (fresh.isNotEmpty()) {
                area = area.copyWith(satellites = area.satellites + fresh)
                if (area.forecast != null || area.satellites.isNotEmpty()) {
                    val ok = withContext(io) { runCatching { store.save(area) }.isSuccess }
                    if (ok) {
                        savedAny = true
                        publish(area, isSpot)
                    }
                }
            }
        }
        val result = area.takeIf { savedAny || upToDate }
        events.finished(result, problems.toList(), userAsked, note, upToDate && !keepForecast)
        if (savedAny) {
            // The shore, for the cut: after everything else, and never holding it up.
            scope.launch {
                val ok = withContext(io) { runCatching { net.coastline(b[0], b[1], b[2], b[3]) }.isSuccess }
                if (ok) events.coastlineReady()
            }
        }
        return upToDate || (forecast != null && source == ConditionsArea.SOURCE_MAIN)
    }

    /**
     * Whether [old] already holds the newest forecast there is for the block [b], for all the
     * days wanted: the service has brought out no run since it was downloaded.
     */
    private fun isLatest(old: ConditionsArea, id: String, b: DoubleArray, s: WeatherSettings, now: Long): Boolean {
        val forecast = old.forecast ?: return false
        val newest = latestRun ?: return false
        val seen = runSeen(id) ?: return false
        return !old.isBackup && newest <= seen &&
            old.middleContains((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) &&
            now - old.fetchedMillis in 0..SpotRules.MAX_AGE_MS &&
            forecast.endMillis - now >= (s.days * 24 - LATEST_SLACK_HOURS) * HOUR_MS &&
            forecast.has(ForecastField.WIND_U) && forecast.has(ForecastField.WAVE_HEIGHT)
    }

    /**
     * Whether [old]'s sea values (waves, swell, currents, sea temperature) are still the newest
     * there are for the block [b] and reach the days wanted: then only the wind is downloaded.
     * Anything not known (the sea models' status, what was seen at the last download) says no.
     */
    private fun seaIsLatest(old: ConditionsArea, id: String, b: DoubleArray, s: WeatherSettings, now: Long): Boolean {
        val forecast = old.forecast ?: return false
        val newest = latestSeaRun ?: return false
        val seen = kv.get(KEY_SEA_RUN + id)?.toLongOrNull() ?: return false
        return !old.isBackup && newest <= seen &&
            old.middleContains((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) &&
            forecast.endMillis - now >= (s.days * 24 - LATEST_SLACK_HOURS) * HOUR_MS &&
            forecast.has(ForecastField.WAVE_HEIGHT)
    }

    /** The newest forecast run there was when [id]'s saved forecast was downloaded (the service's time), or null when not known. */
    fun runSeen(id: String): Long? = kv.get(KEY_RUN_AT + id)?.toLongOrNull()

    private fun publish(area: ConditionsArea, isSpot: Boolean) {
        if (isSpot) spotArea = area
        events.saved(area, isSpot)
    }

    /** The pictures to fetch: all of them once a day; sooner only missing ones (the main ones, and any whose server failed), tried again after an hour. */
    private fun satelliteKindsDue(area: ConditionsArea, sameBlock: Boolean, now: Long): List<SatelliteKind> {
        val all = SatelliteKind.entries.toList()
        if (!sameBlock || area.satellites.isEmpty()) return all
        val last = kv.get(KEY_SATELLITES_AT + area.id)?.toLongOrNull() ?: 0L
        val age = now - last
        if (age > SATELLITE_EVERY_MS) return all
        // Sooner: the main ones that are missing, and any whose server failed last time.
        val again = kv.get(KEY_SATELLITES_RETRY + area.id)?.split(',').orEmpty()
        return all.filter { it !in area.satellites && (it.required || it.name in again) && age > SATELLITE_RETRY_MS }
    }

    private fun origin(): Pair<Double, Double>? =
        kv.get(KEY_SPOT_FROM)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    companion object {
        private const val TAG = "SeaBathyConditions"
        const val KEY_SPOT_FROM = "conditions_spot_from"
        const val KEY_SPOT_SEA = "conditions_spot_sea"
        /** The sea models' newest run when an area's sea values were downloaded, by area. */
        const val KEY_SEA_RUN = "conditions_sea_run_"
        /** Where a look for the nearest sea found none in reach. */
        const val KEY_NO_SEA = "conditions_no_sea"
        /** The boat this far from there is looked at again (the rings are 40 to 70 km apart). */
        const val NO_SEA_KEEP_KM = 30.0
        /** And after this long wherever it is: an answer with nothing in it may have been the service's bad hour. */
        const val NO_SEA_KEEP_MS = 6 * 3_600_000L
        /** What the look for the nearest sea gives when there's none in reach. */
        private val NO_SEA: Pair<Pair<Double, Double>, Pair<Double, Double>?> = (Double.NaN to Double.NaN) to null
        const val KEY_SATELLITES_AT = "conditions_sat_at_"
        /** The pictures whose server failed at the last try, by area. */
        const val KEY_SATELLITES_RETRY = "conditions_sat_retry_"
        /** The newest forecast run there was when an area's forecast was downloaded, by area. */
        const val KEY_RUN_AT = "conditions_run_at_"
        const val HOUR_MS = 3_600_000L
        const val RUN_CHECK_MS = 15 * 60_000L
        /** Waits before trying a failed download again, then every 15 minutes. */
        val RETRIES_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L)
        const val MAX_AUTO_PER_HOUR = 4
        private const val HELD_SAY_MS = 10 * 60_000L
        /** One automatic download of the spot's area doesn't follow another sooner than this. */
        const val MIN_AUTO_GAP_MS = HOUR_MS
        /** With the main service out of reach, what's saved is kept this long before the backup replaces it. */
        const val BACKUP_AFTER_MS = 6 * HOUR_MS
        /** After a limit has passed, a little longer before asking. */
        const val LIMIT_SLACK_MS = 5_000L
        /** "All the days wanted" allows for the saved forecast's age. */
        const val LATEST_SLACK_HOURS = 7
        const val SATELLITE_EVERY_MS = 20 * HOUR_MS
        const val SATELLITE_RETRY_MS = HOUR_MS
        /** A new block overlapping the old this much is the same place: its pictures are kept. */
        const val SAME_BLOCK_OVERLAP = 0.6

        /** How much of block [b] (south, west, north, east) [a] covers, 0 to 1. */
        fun overlap(a: ConditionsArea, b: DoubleArray): Double {
            val h = min(a.north, b[2]) - max(a.south, b[0])
            val w = min(a.east, b[3]) - max(a.west, b[1])
            if (h <= 0 || w <= 0) return 0.0
            return (h * w) / ((b[2] - b[0]) * (b[3] - b[1]))
        }

        fun message(error: Throwable): String = when (error) {
            is java.net.UnknownHostException, is java.net.ConnectException -> "no internet connection"
            is java.net.SocketTimeoutException -> "the service didn't answer in time"
            else -> error.message ?: error.javaClass.simpleName
        }

        private fun ago(millis: Long, now: Long): String = ConditionsFeature.ago(millis, now)
    }
}

/** When the spot's area needs downloading. Pure, unit-tested. */
object SpotRules {
    const val RADIUS_KM = 200.0
    /** The boat this far from where the area was downloaded for: a new one. */
    const val MOVE_KM = 100.0
    /** The forecast running out this soon: a new one. */
    const val RUNNING_OUT_MS = 12 * 3_600_000L
    /** A forecast that's still running out after a download is asked for again no sooner than this. */
    const val RUNNING_OUT_RETRY_MS = 3_600_000L
    /** This old with no word from the service of whether a newer run is out: a new one. */
    const val MAX_AGE_MS = 6 * 3_600_000L
    /** With the backup forecast on show, the main one is tried again once it's this old. */
    const val BACKUP_RECHECK_MS = 10 * 60_000L
    /** Saved "later than now" by more than this: the phone's clock was set back. */
    const val CLOCK_SLACK_MS = 10 * 60_000L

    /** Why a download is needed (for the log); [moved]: the boat is somewhere new, which doesn't wait. */
    class Need(val why: String, val moved: Boolean = false)

    /**
     * Why the spot at [at] needs a download, or null when what's saved is fine. [runSeen]: the
     * newest forecast run there was when [saved] was downloaded; a newer one is told from it by
     * the service's own times, so a phone clock that's wrong can't make every forecast look old.
     */
    fun needs(at: Pair<Double, Double>, saved: ConditionsArea?, origin: Pair<Double, Double>?, latestRun: Long?, now: Long, runSeen: Long? = null): Need? {
        if (saved == null) return Need("nothing saved yet")
        val moved = if (origin != null) NavGeo.distanceM(origin.first, origin.second, at.first, at.second) > MOVE_KM * 1000
        else !saved.middleContains(at.first, at.second)
        if (moved) return Need("the boat moved well away from where its area was downloaded", moved = true)
        val forecast = saved.forecast ?: return Need("no forecast saved")
        val age = now - saved.fetchedMillis
        if (age < -CLOCK_SLACK_MS) return Need("the phone's clock was set back")
        if (saved.isBackup) return if (age > BACKUP_RECHECK_MS) Need("the backup forecast is on show") else null
        if (forecast.endMillis - now < RUNNING_OUT_MS && age > RUNNING_OUT_RETRY_MS) return Need("the forecast runs out soon")
        if (ConditionsPolicy.needsUpdate(saved.fetchedMillis, latestRun, now, runSeen)) {
            return Need(if (latestRun != null && latestRun > (runSeen ?: saved.fetchedMillis)) "a newer forecast is out" else "saved ${age / 3_600_000L} h ago")
        }
        if (latestRun == null && age > MAX_AGE_MS) return Need("saved ${age / 3_600_000L} h ago, and the service couldn't say whether there's a newer one")
        return null
    }
}

/** Which saved data answers for a point, and where in it to read. Pure, unit-tested. */
object WeatherReadings {
    /** [area] read at [lat]/[lng]: the point itself, or the nearest sea [distanceKm] away. */
    data class Reading(val area: ConditionsArea, val lat: Double, val lng: Double, val distanceKm: Double)

    /** How far the nearest sea is looked for, when the point is on land. */
    const val SHORE_KM = 150.0

    /**
     * The best reading for [lat]/[lng] from [areas]: the newest area that has sea values at the
     * point (one whose forecast still covers [time] first); on land, the nearest point with sea
     * values in the newest area covering it (within [SHORE_KM]). Null when no area covers it.
     */
    fun best(areas: List<ConditionsArea>, lat: Double, lng: Double, time: Long, nearest: Boolean = true, hasSea: (ConditionsArea, Double, Double) -> Boolean): Reading? {
        val covering = areas.distinctBy { it.id }.filter { it.contains(lat, lng) }
            .sortedWith(compareByDescending<ConditionsArea> { (it.forecast?.endMillis ?: 0L) >= time }.thenByDescending { it.fetchedMillis })
        if (covering.isEmpty()) return null
        covering.firstOrNull { hasSea(it, lat, lng) }?.let { return Reading(it, lat, lng, 0.0) }
        if (!nearest) return null
        for (a in covering) {
            val grid = a.forecast?.grid ?: continue
            val near = ConditionsFeature.nearestMatching(grid.lats, grid.lngs, lat, lng) { pLat, pLng -> hasSea(a, pLat, pLng) } ?: continue
            val km = NavGeo.distanceM(lat, lng, near.first, near.second) / 1000
            if (km <= SHORE_KM) return Reading(a, near.first, near.second, km)
        }
        return null
    }
}
