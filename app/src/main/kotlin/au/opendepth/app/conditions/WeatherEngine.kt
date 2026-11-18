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

/** What the engine needs from the network. The app's goes through [ConditionsClient] and [Coastline]; tests use a fake. */
interface WeatherNet {
    fun forecast(south: Double, west: Double, north: Double, east: Double, days: Int, onProgress: (Double) -> Unit): ForecastGrid
    fun satellite(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double, depthM: Int): SatelliteGrid
    /** For each point: where the marine model has sea at or next to it, or null on land. */
    fun seaAt(points: List<Pair<Double, Double>>): List<Pair<Double, Double>?>
    /** When the newest forecast run came out, or null when it can't be read. */
    fun latestRun(): Long?
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
    /** A download finished: [area] null when it got nothing. Messages only matter for ones the user asked for. */
    fun finished(area: ConditionsArea?, problems: List<String>, userAsked: Boolean, note: String?)
    /** The coastline for an area is saved: the picture can be cut sharper. */
    fun coastlineReady() {}
}

/**
 * The sea conditions' engine: what to download and when, one download at a time, and what's
 * saved. The screens ask it, and show whatever is saved meanwhile: a download never takes away
 * what's on show.
 *
 * - The spot's area (200 km round the boat, or over the nearest sea inland) is downloaded when
 *   there's none, the boat has moved 100 km since, the forecast runs out within 12 hours, a newer
 *   forecast run is out, or it's 6 hours old ([SpotRules]).
 * - One download at a time, in order; every one, however it ends, leaves the engine ready for
 *   the next.
 * - A failed automatic download is tried again after 30 s, 1, 2, 5, 10, then every 15 minutes;
 *   back online or asked by the user, at once. Offline it waits for the internet.
 * - No more than [MAX_AUTO_PER_HOUR] automatic downloads an hour, whatever happens, so it can
 *   never hammer the free forecast service (one area is about 356 of its 600 calls a minute).
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

    private var spot: Pair<Double, Double>? = null
    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private var failures = 0
    private var retryAt = 0L
    private var cancelRetry: (() -> Unit)? = null
    private val autoTimes = ArrayDeque<Long>()
    private var lastRunCheck = 0L
    private var runChecking = false
    /** Where the spot's area goes for a spot (by spot rounded to about 10 km): its middle, and the nearest sea when inland. */
    private val seaCentres = HashMap<String, Pair<Pair<Double, Double>, Pair<Double, Double>?>>()

    private sealed class Job {
        abstract val userAsked: Boolean
        abstract val key: String

        data class Spot(val at: Pair<Double, Double>, override val userAsked: Boolean, val why: String) : Job() {
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
        val now = clock()
        val why = if (userAsked) "asked" else SpotRules.needs(at, spotArea, origin(), latestRun, now) ?: run {
            // Nothing needed: a wait for a retry or the internet is over.
            if (state is WeatherState.Waiting && running == null) setState(WeatherState.Idle)
            return null
        }
        if (running?.key == "spot" || queue.any { it.key == "spot" }) return null
        if (!online()) {
            if (running == null) setState(WeatherState.Waiting(0L, "no internet: will try again"))
            return "No internet: the saved data is still shown"
        }
        if (!userAsked) {
            if (now < retryAt) return null
            if (!allowAuto(now)) return null
        }
        enqueue(Job.Spot(at, userAsked, why))
        return null
    }

    /** Downloads [south]..[east] as an area of its own (or updating [replacingId]). */
    fun downloadArea(south: Double, west: Double, north: Double, east: Double, replacingId: String?, userAsked: Boolean, note: String? = null): String? {
        if (!online()) return "No internet: the saved data is still shown"
        val job = Job.Area(south, west, north, east, replacingId, userAsked, note)
        if (running?.key == job.key || queue.any { it.key == job.key }) return null
        if (!userAsked && !allowAuto(clock())) return null
        enqueue(job)
        return null
    }

    /** Keeps [area] (one on the map that isn't the spot's) up to date, after the spot. */
    fun keepFresh(area: ConditionsArea) {
        if (area.id == ConditionsFeature.SPOT_ID || !settings().autoUpdate || !online()) return
        if (!ConditionsPolicy.needsUpdate(area.fetchedMillis, latestRun, clock())) return
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
                val run = withContext(io) { runCatching { net.latestRun() }.getOrNull() }
                if (run != null) latestRun = run
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
        if (!job.userAsked) autoTimes.addLast(clock())
        scope.launch {
            var ok = false
            try {
                ok = when (job) {
                    is Job.Spot -> runSpot(job)
                    is Job.Area -> runArea(job)
                    is Job.Depth -> runDepth(job)
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
        if (ok) {
            if (job is Job.Spot || !job.userAsked) {
                failures = 0
                retryAt = 0L
            }
            setState(WeatherState.Idle)
            return
        }
        if (job.userAsked && job !is Job.Spot) {
            setState(WeatherState.Idle)
            return
        }
        // The spot (or an automatic update) failed: try again later, keeping what's saved on show.
        failures++
        val wait = RETRIES_MS[min(failures, RETRIES_MS.size) - 1]
        retryAt = clock() + wait
        cancelRetry?.invoke()
        cancelRetry = timer.after(wait) {
            cancelRetry = null
            retryAt = 0L
            evaluate()
        }
        val minutes = wait / 60_000L
        val reason = when {
            !online() -> "no internet: will try again"
            minutes < 1 -> "couldn't download: trying again in a moment"
            else -> "couldn't download: trying again in $minutes min"
        }
        setState(WeatherState.Waiting(retryAt, reason))
        log("download failed (${failures} in a row): trying again in ${wait / 1000} s")
    }

    /** Automatic downloads stay under [MAX_AUTO_PER_HOUR] an hour. */
    private fun allowAuto(now: Long): Boolean {
        while (autoTimes.isNotEmpty() && now - autoTimes.first() > HOUR_MS) autoTimes.removeFirst()
        if (autoTimes.size < MAX_AUTO_PER_HOUR) return true
        log("held: already $MAX_AUTO_PER_HOUR automatic downloads in the last hour")
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
                else net.seaAt(SpotBlock.ringPoints(lat, lng)).let { sea -> SpotBlock.centre(lat, lng, sea) to SpotBlock.nearestSea(lat, lng, sea) }
            }.onFailure { DiagnosticLog.logError(TAG, "finding the nearest sea FAILED", it) }.getOrNull()
        }?.also { seaCentres[key] = it }
        val (cLat, cLng) = found?.first ?: (lat to lng)
        found?.second?.let { near ->
            kv.put(KEY_SPOT_SEA, String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lat, lng, near.first, near.second))
            log(String.format(Locale.US, "inland: the area goes over the sea, %.0f km away", NavGeo.distanceM(lat, lng, cLat, cLng) / 1000))
        } ?: run { if (found != null) kv.put(KEY_SPOT_SEA, null) }
        val b = ConditionsFeature.boxAround(cLat, cLng, SpotRules.RADIUS_KM)
        val saved = fetchAndSave(ConditionsFeature.SPOT_ID, ConditionsFeature.SPOT_NAME, b, isSpot = true, job.userAsked, note = null)
        if (saved) kv.put(KEY_SPOT_FROM, String.format(Locale.US, "%.5f,%.5f", lat, lng))
        return saved
    }

    private suspend fun runArea(job: Job.Area): Boolean {
        val b = doubleArrayOf(job.south, job.west, job.north, job.east)
        val id = job.replacingId ?: withContext(io) {
            store.summaries().firstOrNull { it.sameBoundsAs(job.south, job.west, job.north, job.east) }?.id
        } ?: java.util.UUID.randomUUID().toString()
        return fetchAndSave(id, ConditionsFeature.defaultName(job.south, job.west, job.north, job.east), b, isSpot = id == ConditionsFeature.SPOT_ID, job.userAsked, job.note)
    }

    private suspend fun runDepth(job: Job.Depth): Boolean {
        val depth = settings().depthM
        setState(WeatherState.Working("Downloading the temperature down below…", true))
        val a = withContext(io) { store.load(job.areaId) } ?: return false
        val updated = withContext(io) {
            runCatching {
                val grid = net.satellite(SatelliteKind.TEMP_BELOW, a.south, a.west, a.north, a.east, depth)
                // The forecast's age stays as it was: only this picture is new.
                ConditionsArea(a.id, a.name, a.south, a.west, a.north, a.east, a.fetchedMillis, a.forecast,
                    a.satellites + (SatelliteKind.TEMP_BELOW to grid), depth).also { store.save(it) }
            }.onFailure { DiagnosticLog.logError(TAG, "temperature at depth download FAILED", it) }.getOrNull()
        }
        if (updated == null) {
            events.finished(null, listOf("Temperature down below: couldn't download"), true, null)
            return false
        }
        publish(updated, updated.id == ConditionsFeature.SPOT_ID)
        events.finished(updated, emptyList(), true, null)
        return true
    }

    /**
     * The forecast for [b] (saved and shown as soon as it's in), then the satellite pictures
     * when due, then the coastline. True when anything new was saved.
     */
    private suspend fun fetchAndSave(id: String, name: String, b: DoubleArray, isSpot: Boolean, userAsked: Boolean, note: String?): Boolean {
        val s = settings()
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())
        val old = withContext(io) { runCatching { store.load(id) }.getOrNull() }
        setState(WeatherState.Working("Downloading the forecast…", userAsked))
        val started = System.nanoTime()
        val forecast = withContext(io) {
            runCatching { net.forecast(b[0], b[1], b[2], b[3], s.days) {} }
                .onFailure { DiagnosticLog.logError(TAG, "forecast download FAILED", it); problems += "Forecast: ${message(it)}" }
                .getOrNull()
        }
        if (forecast != null) log("forecast (${s.days} days) in ${(System.nanoTime() - started) / 1_000_000} ms")
        // Pictures saved for this same block are kept (and topped up); a block that moved starts afresh.
        val sameBlock = old != null && overlap(old, b) >= SAME_BLOCK_OVERLAP
        val keptSats = if (sameBlock) old!!.satellites.filterKeys { !(it == SatelliteKind.TEMP_BELOW && old.belowDepthM != s.depthM) } else emptyMap()
        val now = clock()
        var area = ConditionsArea(
            id = id, name = old?.name ?: name, south = b[0], west = b[1], north = b[2], east = b[3],
            fetchedMillis = if (forecast != null) now else old?.fetchedMillis ?: now,
            forecast = forecast ?: old?.forecast?.takeIf { sameBlock },
            satellites = keptSats, belowDepthM = s.depthM
        )
        var savedAny = false
        if (forecast != null) {
            savedAny = withContext(io) { runCatching { store.save(area) }.onFailure { problems += "Saving: ${message(it)}" }.isSuccess }
            if (savedAny) publish(area, isSpot)
        }
        // Satellite pictures: once a day.
        val kinds = if (s.withSatellite) satelliteKindsDue(area, sameBlock, now) else emptyList()
        if (kinds.isNotEmpty() && online()) {
            setState(WeatherState.Working("Getting satellite pictures…", userAsked))
            val t0 = System.nanoTime()
            val got = coroutineScope {
                kinds.map { kind ->
                    async(parallel) {
                        kind to runCatching { net.satellite(kind, b[0], b[1], b[2], b[3], s.depthM) }
                            .onFailure { if (kind.required) problems += "${kind.label}: ${message(it)}" }
                            .getOrNull()
                    }
                }.awaitAll()
            }
            val fresh = got.mapNotNull { (k, g) -> g?.let { k to it } }.toMap()
            log("satellite pictures ${fresh.keys.joinToString { it.name }} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            kv.put(KEY_SATELLITES_AT + id, clock().toString())
            if (fresh.isNotEmpty()) {
                area = ConditionsArea(area.id, area.name, area.south, area.west, area.north, area.east, area.fetchedMillis,
                    area.forecast, area.satellites + fresh, area.belowDepthM)
                if (area.forecast != null || area.satellites.isNotEmpty()) {
                    val ok = withContext(io) { runCatching { store.save(area) }.isSuccess }
                    if (ok) {
                        savedAny = true
                        publish(area, isSpot)
                    }
                }
            }
        }
        val result = area.takeIf { savedAny }
        events.finished(result, problems.toList(), userAsked, note)
        if (savedAny) {
            // The shore, for the cut: after everything else, and never holding it up.
            scope.launch {
                val ok = withContext(io) { runCatching { net.coastline(b[0], b[1], b[2], b[3]) }.isSuccess }
                if (ok) events.coastlineReady()
            }
        }
        return forecast != null
    }

    private fun publish(area: ConditionsArea, isSpot: Boolean) {
        if (isSpot) spotArea = area
        events.saved(area, isSpot)
    }

    /** The pictures to fetch: all of them once a day; sooner only the main ones that are missing, tried again after an hour. */
    private fun satelliteKindsDue(area: ConditionsArea, sameBlock: Boolean, now: Long): List<SatelliteKind> {
        val all = SatelliteKind.entries.toList()
        if (!sameBlock || area.satellites.isEmpty()) return all
        val last = kv.get(KEY_SATELLITES_AT + area.id)?.toLongOrNull() ?: 0L
        val age = now - last
        if (age > SATELLITE_EVERY_MS) return all
        return all.filter { it !in area.satellites && it.required && age > SATELLITE_RETRY_MS }
    }

    private fun origin(): Pair<Double, Double>? =
        kv.get(KEY_SPOT_FROM)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    companion object {
        private const val TAG = "SeaBathyConditions"
        const val KEY_SPOT_FROM = "conditions_spot_from"
        const val KEY_SPOT_SEA = "conditions_spot_sea"
        const val KEY_SATELLITES_AT = "conditions_sat_at_"
        const val HOUR_MS = 3_600_000L
        const val RUN_CHECK_MS = 15 * 60_000L
        /** Waits before trying a failed download again, then every 15 minutes. */
        val RETRIES_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L)
        const val MAX_AUTO_PER_HOUR = 6
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
    /** This old: a new one, even with no word of a newer forecast run. */
    const val MAX_AGE_MS = 6 * 3_600_000L

    /** Why the spot at [at] needs a download (for the log), or null when what's saved is fine. */
    fun needs(at: Pair<Double, Double>, saved: ConditionsArea?, origin: Pair<Double, Double>?, latestRun: Long?, now: Long): String? {
        if (saved == null) return "nothing saved yet"
        val moved = if (origin != null) NavGeo.distanceM(origin.first, origin.second, at.first, at.second) > MOVE_KM * 1000
        else !saved.middleContains(at.first, at.second)
        if (moved) return "the boat moved well away from where its area was downloaded"
        val forecast = saved.forecast ?: return "no forecast saved"
        if (forecast.endMillis - now < RUNNING_OUT_MS) return "the forecast runs out soon"
        if (latestRun != null && latestRun > saved.fetchedMillis) return "a newer forecast is out"
        if (now - saved.fetchedMillis > MAX_AGE_MS) return "saved ${(now - saved.fetchedMillis) / 3_600_000L} h ago"
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
