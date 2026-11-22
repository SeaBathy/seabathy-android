package au.opendepth.app.tides

import au.opendepth.app.nav.NavGeo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Official tide predictions from Maritime Safety Queensland, used instead of the
 * tide model near its stations ([MsqStationIndex]). Each station's yearly CSV of
 * highs and lows (about 50 KB) is downloaded straight from data.qld.gov.au and kept
 * in [dir]: this year's, and next year's once MSQ has put it out (around September),
 * so the tides work offline to the end of the year after. Where half a cosine
 * between highs and lows is a poor fit ([MsqStation.useInterval]), the year's
 * 10-minute predictions (about 1.8 MB) are downloaded too, kept small, and the tide
 * is drawn from them; the high/low file still gives the published highs and lows.
 * Plain Kotlin with the download passed in ([fetch]), so it is unit-tested.
 */
class OfficialTides(
    private val dir: File,
    val index: MsqStationIndex,
    /** Downloads a URL's text; throws [NotFound] for a 404 and IOException for other failures. */
    private val fetch: (String) -> String
) {
    /** The address answered 404: nothing there (yet). */
    class NotFound(url: String) : IOException("Not found: $url")

    /** data.qld.gov.au has no predictions for that year yet. */
    class NotPublished(station: String, year: Int) : IOException("The official tides for $station in $year aren't out yet.")

    /** The two kinds of yearly file: highs and lows (CSV, kept as is), and 10-minute predictions (kept small). */
    private enum class Kind(val suffix: String, val checkedSuffix: String) {
        HILO(".csv", ""),
        INTERVAL(".i10", "_10min")
    }

    private class Choice(val station: MsqStation, val lat: Double, val lng: Double)

    private val chosen = ConcurrentHashMap<String, Choice>()
    /** Saved highs and lows read so far: station number → year → predictions. */
    private val years = ConcurrentHashMap<String, Map<Int, MsqPredictions>>()
    /** Saved 10-minute predictions read so far: station number → year → readings. */
    private val intervals = ConcurrentHashMap<String, Map<Int, MsqIntervalYear>>()
    /** Curves built from them, by station number, with what they're from. */
    private val curves = ConcurrentHashMap<String, Pair<String, MsqCurve.Built>>()
    /** When each "station_year" (and "station_year_10min") was last looked for, so it isn't asked for too often. */
    private val checked: MutableMap<String, Long> by lazy { readChecked() }

    init {
        dir.mkdirs()
    }

    /** The station whose predictions hold at [lat]/[lng], or null for the model there. */
    fun stationAt(lat: Double, lng: Double): MsqStation? = index.nearest(lat, lng)

    /**
     * From now on [key] (a tide place, or "follow me") uses the predictions of the
     * station near [lat]/[lng], reading what's saved of them; with none near, it goes
     * back to the model. Reads files: call off the main thread.
     */
    fun choose(key: String, lat: Double, lng: Double): MsqStation? {
        val station = index.nearest(lat, lng)
        if (station == null) {
            chosen.remove(key)
        } else {
            load(station)
            chosen[key] = Choice(station, lat, lng)
        }
        return station
    }

    /** The station [key] uses, or null. */
    fun chosenStation(key: String): MsqStation? = chosen[key]?.station

    /** True when [key] uses [station], chosen within [withinM] of [lat]/[lng]. */
    fun isChosen(key: String, station: MsqStation, lat: Double, lng: Double, withinM: Double): Boolean {
        val c = chosen[key] ?: return false
        return c.station.id == station.id && NavGeo.distanceM(lat, lng, c.lat, c.lng) <= withinM
    }

    fun forget(key: String) {
        chosen.remove(key)
    }

    /** [key]'s official tides, when its station's saved predictions cover [now]; null means the model. */
    fun forecast(key: String, now: Long): TideForecast? {
        val c = chosen[key] ?: return null
        return forecastFor(c.station, c.lat, c.lng, now)
    }

    /** True when [key] has official predictions covering [now]. */
    fun covers(key: String, now: Long): Boolean {
        val c = chosen[key] ?: return false
        return curve(c.station, now)?.curve?.covers(now) == true
    }

    /** Official tides at [lat]/[lng] from what is saved, without choosing them for a place. Reads files. */
    fun forecastAt(lat: Double, lng: Double, now: Long): TideForecast? {
        val station = index.nearest(lat, lng) ?: return null
        load(station)
        return forecastFor(station, lat, lng, now)
    }

    private fun forecastFor(station: MsqStation, lat: Double, lng: Double, now: Long): TideForecast? {
        val built = curve(station, now) ?: return null
        if (!built.curve.covers(now)) return null
        return TideForecast(
            requestLat = lat,
            requestLng = lng,
            modelLat = station.lat,
            modelLng = station.lng,
            fetchedMillis = files(station).maxOfOrNull { it.lastModified() } ?: now,
            curve = built.curve,
            savedUntilMillis = built.curve.endMillis,
            storedBytes = storedBytes(station),
            // The curve's heights are from the mean level, which is this far above LAT.
            lowestTideM = -built.meanAboveLatM,
            station = station,
            tenMinute = built.fromInterval
        )
    }

    /** The kinds of file [station] needs each year. */
    private fun wanted(station: MsqStation): List<Kind> =
        if (station.useInterval) listOf(Kind.HILO, Kind.INTERVAL) else listOf(Kind.HILO)

    private fun has(station: MsqStation, kind: Kind, year: Int): Boolean = when (kind) {
        Kind.HILO -> years[station.id]?.containsKey(year) == true
        Kind.INTERVAL -> intervals[station.id]?.containsKey(year) == true
    }

    /** The years of [station]'s predictions that are saved in full (every kind it needs). Reads files the first time. */
    fun savedYears(station: MsqStation): Set<Int> {
        load(station)
        val hilo = years[station.id]?.keys.orEmpty()
        return if (station.useInterval) hilo intersect intervals[station.id]?.keys.orEmpty() else hilo
    }

    /**
     * True when [station] hasn't this year's highs and lows, or something else it needs
     * may be had and hasn't been looked for lately: this year's 10-minute predictions
     * (every few hours), next year's files from September (every few days). Any time
     * with [ignoreWait], when the user asks.
     */
    fun needsDownload(station: MsqStation, now: Long, ignoreWait: Boolean = false): Boolean {
        load(station)
        val year = yearAt(now)
        if (!has(station, Kind.HILO, year)) return true
        val due = buildList {
            wanted(station).forEach { add(Triple(it, year, CURRENT_WAIT_MS)) }
            if (monthAt(now) >= NEXT_YEAR_MONTH) wanted(station).forEach { add(Triple(it, year + 1, NEXT_YEAR_WAIT_MS)) }
        }
        return due.any { (kind, y, wait) ->
            !has(station, kind, y) && (ignoreWait || now - (checked[checkedKey(station, kind, y)] ?: 0L) >= wait)
        }
    }

    /**
     * Downloads what [station] is missing: this year's highs and lows (failing if they
     * can't be had), then this year's 10-minute predictions where they're used, and next
     * year's files once they are out. Whatever comes is kept; a failed connection is
     * reported after the rest is tried. Drops years before last year.
     */
    @Synchronized
    fun download(station: MsqStation, now: Long) {
        load(station)
        val year = yearAt(now)
        if (!has(station, Kind.HILO, year)) save(station, Kind.HILO, year, now)
        var failed: IOException? = null
        val more = buildList {
            if (station.useInterval) add(Kind.INTERVAL to year)
            if (monthAt(now) >= NEXT_YEAR_MONTH) wanted(station).forEach { add(it to year + 1) }
        }
        for ((kind, y) in more) {
            if (has(station, kind, y)) continue
            try {
                save(station, kind, y, now)
            } catch (notOut: NotPublished) {
                // Looked for again later.
            } catch (error: IOException) {
                failed = failed ?: error
            }
        }
        prune(year)
        curves.remove(station.id)
        failed?.let { throw it }
    }

    fun storedBytes(station: MsqStation): Long = files(station).sumOf { it.length() }

    /** The end of the official tides saved for [lat]/[lng] (from the file names), or null with none for this year on. */
    fun savedUntilAt(lat: Double, lng: Double, now: Long): Long? {
        val station = index.nearest(lat, lng) ?: return null
        val last = files(station, Kind.HILO).mapNotNull { yearOf(it, Kind.HILO) }.maxOrNull()?.takeIf { it >= yearAt(now) } ?: return null
        return java.time.LocalDate.of(last + 1, 1, 1).atStartOfDay().toInstant(QLD).toEpochMilli() - 60_000L
    }

    private fun checkedKey(station: MsqStation, kind: Kind, year: Int) = "${station.id}_$year${kind.checkedSuffix}"

    private fun save(station: MsqStation, kind: Kind, year: Int, now: Long) {
        val (links, dataset, pattern) = when (kind) {
            Kind.HILO -> Triple(station.years, station.dataset, Regex("_${year}_hilo(?:_[a-z]+)?\\.csv$", RegexOption.IGNORE_CASE))
            Kind.INTERVAL -> Triple(station.intervalYears, station.intervalDataset, Regex("_${year}_10mins?(?:_[a-z]+)?(?:\\.csv)+$", RegexOption.IGNORE_CASE))
        }
        val listed = links[year]?.takeIf(::allowed)
        // A failed connection throws from here before anything is noted, so it is tried
        // again soon; "not out yet" and an answer of any kind put off the next look.
        val text = try {
            listed?.let(::fetchOrNull)
                ?: dataset?.let { lookUp(it, pattern) }?.takeIf { it != listed }?.let(::fetchOrNull)
                ?: throw NotPublished(station.name, year)
        } catch (notOut: NotPublished) {
            noteChecked(checkedKey(station, kind, year), now)
            throw notOut
        }
        noteChecked(checkedKey(station, kind, year), now)
        val file = file(station, kind, year)
        val part = File(dir, file.name + ".part")
        var predictions: MsqPredictions? = null
        var readings: MsqIntervalYear? = null
        when (kind) {
            Kind.HILO -> {
                val p = MsqCsv.parse(text).also { check(station, year, it) }
                part.writeText(text)
                predictions = p
            }
            Kind.INTERVAL -> {
                val r = MsqCsv.parseInterval(text).also { checkInterval(station, year, it) }
                part.outputStream().use { r.write(it) }
                readings = r
            }
        }
        if (!part.renameTo(file)) {
            file.delete()
            if (!part.renameTo(file)) {
                part.delete()
                throw IOException("Couldn't save the tides for ${station.name}.")
            }
        }
        // Only once the file is in place, so what is in memory is what a restart finds.
        predictions?.let { years[station.id] = years[station.id].orEmpty() + (year to it) }
        readings?.let { intervals[station.id] = intervals[station.id].orEmpty() + (year to it) }
    }

    private fun noteChecked(key: String, now: Long) {
        checked[key] = now
        writeChecked()
    }

    /**
     * A year's file must be for this place (a dataset's gauge can be replaced, but not
     * moved far) and cover the whole year without a break: else it is refused, or
     * deleted when saved, and downloaded again.
     */
    private fun check(station: MsqStation, year: Int, predictions: MsqPredictions) {
        if (!samePlace(station, predictions.lat, predictions.lng)) throw IOException("The tide file for ${station.name} is for another place.")
        if (!coversYear(predictions.extremes, year)) {
            throw IOException("The tide file for ${station.name} doesn't cover all of $year.")
        }
    }

    private fun checkInterval(station: MsqStation, year: Int, readings: MsqIntervalYear) {
        if (!samePlace(station, readings.lat, readings.lng)) throw IOException("The 10-minute tide file for ${station.name} is for another place.")
        if (!coversYear(readings, year)) throw IOException("The 10-minute tide file for ${station.name} doesn't cover all of $year.")
    }

    private fun samePlace(station: MsqStation, lat: Double, lng: Double): Boolean =
        NavGeo.distanceM(lat, lng, station.lat, station.lng) <= SAME_PLACE_M

    private fun fetchOrNull(url: String): String? = try {
        fetch(url)
    } catch (missing: NotFound) {
        null
    }

    /** A year's CSV link matching [pattern] from [dataset]'s record on data.qld.gov.au (CKAN), if it lists one. */
    private fun lookUp(dataset: String, pattern: Regex): String? {
        if (!DATASET.matches(dataset)) return null
        val body = fetchOrNull("$CKAN/package_show?id=$dataset") ?: return null
        val resources = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("result")?.getAsJsonArray("resources")
        }.getOrNull() ?: return null
        return resources.mapNotNull { r -> runCatching { r.asJsonObject.get("url")?.asString }.getOrNull() }
            .firstOrNull { pattern.containsMatchIn(it) && allowed(it) }
    }

    /** Only data.qld.gov.au's own files are downloaded. */
    private fun allowed(url: String): Boolean = isDataQld(url)

    /**
     * Reads [station]'s saved files the first time it is wanted. computeIfAbsent, so a read
     * running beside a download can't put back what was there before a year was saved.
     */
    private fun load(station: MsqStation) {
        years.computeIfAbsent(station.id) {
            files(station, Kind.HILO).mapNotNull { f ->
                val year = yearOf(f, Kind.HILO) ?: return@mapNotNull null
                runCatching { year to MsqCsv.parse(f.readText()).also { check(station, year, it) } }.getOrElse {
                    f.delete()
                    null
                }
            }.toMap()
        }
        intervals.computeIfAbsent(station.id) {
            files(station, Kind.INTERVAL).mapNotNull { f ->
                val year = yearOf(f, Kind.INTERVAL) ?: return@mapNotNull null
                runCatching { year to f.inputStream().use { MsqIntervalYear.read(it) }.also { checkInterval(station, year, it) } }.getOrElse {
                    f.delete()
                    null
                }
            }.toMap()
        }
    }

    /**
     * The curve around [now]: from the 10-minute predictions where they're used and saved
     * for this year, else half cosines through the highs and lows; this year with the
     * years either side that are saved.
     */
    private fun curve(station: MsqStation, now: Long): MsqCurve.Built? {
        val hilo = years[station.id].orEmpty()
        val tenMinute = intervals[station.id].orEmpty()
        val year = yearAt(now)
        // Heights from mean sea level: the station's (from the 10-minute predictions), or
        // this calendar year's mean, so it doesn't move as years come and go.
        fun around(saved: Set<Int>) = listOfNotNull((year - 1).takeIf { it in saved }, year, (year + 1).takeIf { it in saved })
        if (station.useInterval && year in tenMinute) {
            val run = around(tenMinute.keys)
            val withList = run.all { it in hilo }
            val key = "i$run$withList"
            curves[station.id]?.let { (k, built) -> if (k == key && built.curve.covers(now)) return built }
            val mean = station.mslAboveLatM ?: tenMinute.getValue(year).meanM
            // The published highs and lows when every year has them; else found on the curve.
            val extremes = if (withList) run.flatMap { hilo.getValue(it).extremes } else null
            val built = MsqCurve.buildInterval(run.map { tenMinute.getValue(it) }, extremes, now, mean) ?: return null
            curves[station.id] = key to built
            trimCurves(station.id)
            return built
        }
        if (year !in hilo) return null
        val run = around(hilo.keys)
        val key = "h$run"
        curves[station.id]?.let { (k, built) -> if (k == key && built.curve.covers(now)) return built }
        val mean = station.mslAboveLatM ?: MsqCurve.curveMean(hilo.getValue(year).extremes)
        val built = MsqCurve.build(run.flatMap { hilo.getValue(it).extremes }, now, mean) ?: return null
        curves[station.id] = key to built
        trimCurves(station.id)
        return built
    }

    /**
     * Keeps the built curves (about 2 MB each from 10-minute predictions) of the stations in
     * use, [keep] and at most [MAX_CURVES] in all: the rest are built again if wanted.
     */
    private fun trimCurves(keep: String) {
        if (curves.size <= MAX_CURVES) return
        val inUse = chosen.values.mapTo(HashSet()) { it.station.id } + keep
        curves.keys.filter { it !in inUse }.take(curves.size - MAX_CURVES).forEach { curves.remove(it) }
    }

    /** How many built curves there are now (tests). */
    internal val curveCount: Int get() = curves.size

    /** Deletes saved years before last year (any station, either kind). */
    private fun prune(year: Int) {
        for (kind in Kind.entries) {
            dir.listFiles { f -> f.name.endsWith(kind.suffix) }.orEmpty().forEach { f ->
                val y = yearOf(f, kind) ?: return@forEach
                if (y < year - 1) f.delete()
            }
        }
        years.replaceAll { _, saved -> saved.filterKeys { it >= year - 1 } }
        intervals.replaceAll { _, saved -> saved.filterKeys { it >= year - 1 } }
    }

    private fun file(station: MsqStation, kind: Kind, year: Int) = File(dir, "${safe(station.id)}_$year${kind.suffix}")

    private fun files(station: MsqStation, kind: Kind? = null): List<File> {
        val prefix = safe(station.id) + "_"
        val kinds = kind?.let { listOf(it) } ?: Kind.entries
        return dir.listFiles { f -> f.name.startsWith(prefix) && kinds.any { f.name.endsWith(it.suffix) } }.orEmpty().toList()
    }

    private fun yearOf(f: File, kind: Kind): Int? = f.name.removeSuffix(kind.suffix).substringAfterLast('_').toIntOrNull()

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() }

    private fun checkedFile() = File(dir, "checked.json")

    private fun readChecked(): MutableMap<String, Long> {
        val map = ConcurrentHashMap<String, Long>()
        runCatching {
            JsonParser.parseString(checkedFile().readText()).asJsonObject.entrySet().forEach { (k, v) -> map[k] = v.asLong }
        }
        return map
    }

    private fun writeChecked() {
        runCatching {
            checkedFile().writeText(JsonObject().apply { checked.forEach { (k, v) -> addProperty(k, v) } }.toString())
        }
    }

    companion object {
        /** Queensland time, which the files use all year. */
        val QLD: ZoneOffset = ZoneOffset.ofHours(10)

        fun yearAt(millis: Long): Int = Instant.ofEpochMilli(millis).atOffset(QLD).year
        fun monthAt(millis: Long): Int = Instant.ofEpochMilli(millis).atOffset(QLD).monthValue

        /**
         * True when [extremes] cover [year] from end to end: the first by 2 January, the
         * last from 30 December (each station's first and last fall within a day of the
         * year's ends), and no two more than [MsqCurve.MAX_GAP_MS] apart.
         */
        fun coversYear(extremes: List<TideExtreme>, year: Int): Boolean {
            if (extremes.size < 2) return false
            val start = java.time.LocalDate.of(year, 1, 2).atStartOfDay().toInstant(QLD).toEpochMilli()
            val end = java.time.LocalDate.of(year, 12, 30).atStartOfDay().toInstant(QLD).toEpochMilli()
            if (extremes.first().timeMillis > start || extremes.last().timeMillis < end) return false
            if (extremes.any { yearAt(it.timeMillis) != year }) return false
            return extremes.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis <= MsqCurve.MAX_GAP_MS }
        }

        /** True when 10-minute [readings] run from the start of [year] to its last day, all inside it. */
        fun coversYear(readings: MsqIntervalYear, year: Int): Boolean {
            val start = java.time.LocalDate.of(year, 1, 1).atStartOfDay().toInstant(QLD).toEpochMilli()
            val next = java.time.LocalDate.of(year + 1, 1, 1).atStartOfDay().toInstant(QLD).toEpochMilli()
            return readings.startMillis in start..start + DAY_MS && readings.endMillis in next - DAY_MS until next
        }

        /** True for an https address on www.data.qld.gov.au, the only place tide files come from. */
        fun isDataQld(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(DATA_QLD_HOST, ignoreCase = true) && uri.userInfo == null
        }.getOrDefault(false)

        /** Reads at most [maxBytes] from [input]; more than that is refused (a year of highs and lows is about 50 KB). */
        fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw IOException("Tide predictions file is too big")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * What a position update does about official tides: [chosenHere] when the place
         * already uses this station from about here, [covers] when its saved predictions
         * cover now, [downloadDue] when a download is wanted and not held back after a failure.
         */
        fun actionOnUpdate(chosenHere: Boolean, covers: Boolean, downloadDue: Boolean, force: Boolean): UpdateAction = when {
            force || !chosenHere || downloadDue -> UpdateAction.REFRESH
            covers -> UpdateAction.NOTHING
            // Saved, but not for this moment (before the year's first tide, after the last):
            // the model fills in, without going round again on every GPS fix.
            else -> UpdateAction.USE_MODEL
        }

        const val DATA_QLD_HOST = "www.data.qld.gov.au"

        private const val CKAN = "https://www.data.qld.gov.au/api/3/action"
        private val DATASET = Regex("^[a-z0-9_-]+$")
        /** MSQ puts out next year's predictions around September. */
        private const val NEXT_YEAR_MONTH = 9
        private const val NEXT_YEAR_WAIT_MS = 3 * 24 * 3_600_000L
        /** This year's 10-minute predictions, when not found, are looked for again after this. */
        private const val CURRENT_WAIT_MS = 6 * 3_600_000L
        private const val DAY_MS = 24 * 3_600_000L
        /** How far a file's position may be from the station's: positions are to the minute (under 2 km). */
        private const val SAME_PLACE_M = 5_000.0
        /** Built curves kept beyond the stations in use: a route's tide windows can read several. */
        internal const val MAX_CURVES = 4
    }
}

/** See [OfficialTides.actionOnUpdate]. */
enum class UpdateAction { REFRESH, USE_MODEL, NOTHING }
