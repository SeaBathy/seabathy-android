package au.opendepth.app.tides

import au.opendepth.app.nav.NavGeo
import com.google.gson.JsonParser
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * A Maritime Safety Queensland tide station: one of the ports, reefs and islands
 * MSQ publishes predicted tides for on data.qld.gov.au (CC BY 4.0). [years] are
 * the links to its yearly CSVs of highs and lows known when the app was built;
 * later years are looked up on data.qld.gov.au.
 */
data class MsqStation(
    /** MSQ's station number, as in its files. */
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** How far from the gauge its predictions are used, metres. */
    val reachM: Double,
    /** The dataset's name on data.qld.gov.au (CKAN). */
    val dataset: String,
    val years: Map<Int, String>,
    /**
     * Mean sea level above LAT, metres: the mean of a whole year of MSQ's 10-minute
     * predictions here (worked out by tools/msq_tide_index.py); null if unknown.
     */
    val mslAboveLatM: Double? = null,
    /**
     * Half a cosine between highs and lows is poor here (the Gulf, Torres Strait, much
     * of the reef coast): the tide is drawn from MSQ's 10-minute predictions
     * ([intervalDataset], links [intervalYears]), about 1.8 MB a year to download.
     */
    val useInterval: Boolean = false,
    val intervalDataset: String? = null,
    val intervalYears: Map<Int, String> = emptyMap()
)

/** Water near a station with tides of its own (a channel behind an island), where no station is used. */
data class MsqArea(val name: String, val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lng: Double): Boolean = lat in south..north && lng in west..east
}

/**
 * The stations bundled with the app (assets/[ASSET], built by tools/msq_tide_index.py).
 * A spot uses the nearest station within that station's reach (15 km; 8 km for gauges
 * up rivers), unless it is in one of the [separate] waters.
 */
class MsqStationIndex(val stations: List<MsqStation>, val separate: List<MsqArea>) {

    /** The station whose predictions hold at [lat]/[lng], or null for the tide model there. */
    fun nearest(lat: Double, lng: Double): MsqStation? {
        if (separate.any { it.contains(lat, lng) }) return null
        var best: MsqStation? = null
        var bestM = Double.MAX_VALUE
        for (s in stations) {
            // About 0.25 degrees is beyond any reach: skips the distance sum for most.
            if (kotlin.math.abs(s.lat - lat) > 0.25 || kotlin.math.abs(s.lng - lng) > 0.3) continue
            val m = NavGeo.distanceM(lat, lng, s.lat, s.lng)
            if (m <= s.reachM && m < bestM) {
                best = s
                bestM = m
            }
        }
        return best
    }

    fun byId(id: String): MsqStation? = stations.firstOrNull { it.id == id }

    /** The waters with tides of their own that [lat]/[lng] is in, if any. */
    fun separateAt(lat: Double, lng: Double): MsqArea? = separate.firstOrNull { it.contains(lat, lng) }

    companion object {
        const val ASSET = "tides/msq_stations.json"

        fun parse(json: String): MsqStationIndex {
            val root = JsonParser.parseString(json).asJsonObject
            val stations = root.getAsJsonArray("stations").map { e ->
                val o = e.asJsonObject
                MsqStation(
                    id = o.get("id").asString,
                    name = o.get("name").asString,
                    lat = o.get("lat").asDouble,
                    lng = o.get("lng").asDouble,
                    reachM = (o.get("reachKm")?.asDouble ?: DEFAULT_REACH_KM) * 1000.0,
                    dataset = o.get("dataset").asString,
                    years = o.getAsJsonObject("years")?.entrySet()
                        ?.mapNotNull { (year, url) -> year.toIntOrNull()?.let { it to url.asString } }
                        ?.toMap()
                        .orEmpty(),
                    mslAboveLatM = o.get("mslAboveLatM")?.takeIf { !it.isJsonNull }?.asDouble?.takeIf { it.isFinite() && it in 0.0..MAX_CHART_DATUM_M },
                    useInterval = o.get("useInterval")?.asBoolean == true && o.get("intervalDataset") != null,
                    intervalDataset = o.get("intervalDataset")?.takeIf { !it.isJsonNull }?.asString,
                    intervalYears = o.getAsJsonObject("intervalYears")?.entrySet()
                        ?.mapNotNull { (year, url) -> year.toIntOrNull()?.let { it to url.asString } }
                        ?.toMap()
                        .orEmpty()
                )
            }
            val separate = root.getAsJsonArray("separate")?.map { e ->
                val o = e.asJsonObject
                MsqArea(o.get("name").asString, o.get("south").asDouble, o.get("west").asDouble, o.get("north").asDouble, o.get("east").asDouble)
            }.orEmpty()
            return MsqStationIndex(stations, separate)
        }

        private const val DEFAULT_REACH_KM = 15.0
    }
}

/** One year of a station's predicted highs and lows: heights metres above LAT. */
class MsqPredictions(
    val stationId: String,
    val stationName: String,
    val lat: Double,
    val lng: Double,
    val extremes: List<TideExtreme>
)

/**
 * One year of a station's 10-minute predictions: heights metres above LAT from
 * [startMillis], one every [stepMillis], with none missing. Kept on the phone in a
 * small file of its own ([write]/[read], heights to the millimetre: about 105 KB a
 * year instead of the CSV's 1.8 MB).
 */
class MsqIntervalYear(
    val stationId: String,
    val lat: Double,
    val lng: Double,
    val startMillis: Long,
    val stepMillis: Long,
    val heightsM: FloatArray
) {
    val endMillis: Long get() = startMillis + (heightsM.size - 1) * stepMillis

    /** The mean of the year's heights: mean sea level above LAT. */
    val meanM: Double get() = heightsM.sumOf { it.toDouble() } / heightsM.size

    fun write(out: java.io.OutputStream) {
        val data = java.io.DataOutputStream(java.io.BufferedOutputStream(out))
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(stationId)
        data.writeDouble(lat)
        data.writeDouble(lng)
        data.writeLong(startMillis)
        data.writeLong(stepMillis)
        data.writeInt(heightsM.size)
        heightsM.forEach { data.writeShort(kotlin.math.round(it * 1000f).toInt()) }
        data.flush()
    }

    companion object {
        private const val MAGIC = 0x4D535149 // "MSQI"
        private const val VERSION = 1

        fun read(input: java.io.InputStream): MsqIntervalYear {
            val data = java.io.DataInputStream(java.io.BufferedInputStream(input))
            if (data.readInt() != MAGIC || data.readInt() != VERSION) throw IOException("Not a saved 10-minute tide file")
            val id = data.readUTF()
            val lat = data.readDouble()
            val lng = data.readDouble()
            val start = data.readLong()
            val step = data.readLong()
            val count = data.readInt()
            if (step <= 0 || count !in 2..MAX_READINGS) throw IOException("Saved 10-minute tide file is damaged")
            val heights = FloatArray(count) { data.readShort() / 1000f }
            return MsqIntervalYear(id, lat, lng, start, step, heights)
        }

        /** A leap year of readings every 10 minutes is 52,704; anything far beyond isn't a year. */
        const val MAX_READINGS = 60_000
    }
}

/**
 * Reads MSQ's predicted tide CSVs: a header ("Tidal Station Number =,060010A",
 * position, time zone, datum, how many readings and the last one's time), then
 * "Date , Time , Ind , Reading" rows. In high/low files Ind is 1 for high water and
 * -1 for low; in 10-minute ("interval") files every row is a reading. Times are
 * Queensland time (UTC+10, all year) and heights are above Lowest Astronomical Tide;
 * a file to any other datum, or cut short, is refused.
 */
object MsqCsv {

    private class Row(val millis: Long, val ind: String, val height: Double)

    private class Raw(val fields: Map<String, String>, val rows: List<Row>, val offset: ZoneOffset?) {
        val id: String get() = fields["Tidal Station Number"] ?: throw IOException("Not a tide prediction file")
        val lat: Double get() = degrees(fields["Latitude  Degrees Minutes"] ?: fields.entries.firstOrNull { it.key.startsWith("Latitude") }?.value)
        val lng: Double get() = degrees(fields["Longitude Degrees Minutes"] ?: fields.entries.firstOrNull { it.key.startsWith("Longitude") }?.value)
    }

    private fun read(text: String, wanted: (String) -> Boolean): Raw {
        val fields = mutableMapOf<String, String>()
        var datum: String? = null
        val rows = ArrayList<Row>(1500)
        var offset: ZoneOffset? = null
        var readings = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r', ' ')
            if (!readings) {
                when {
                    "=," !in line && line.startsWith("Date") && line.contains("Reading") -> {
                        offset = zone(fields["Time Zone"]) ?: throw IOException("Tide file has no time zone")
                        readings = true
                    }
                    line.startsWith("Readings in this file are referred to") -> datum = line.substringAfter(',').trim()
                    "=," in line -> fields[line.substringBefore("=,").trim()] = line.substringAfter("=,").trim().trimEnd(',').trim()
                }
                continue
            }
            val parts = line.split(',')
            if (parts.size < 4) continue
            val ind = parts[2].trim()
            if (!wanted(ind)) continue
            val height = parts[3].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -2.0..MAX_TIDE_HEIGHT_M } ?: continue
            val time = rowMillis(parts[0].trim(), parts[1].trim(), offset!!) ?: continue
            rows += Row(time, ind, height)
        }
        fields["Tidal Station Number"] ?: throw IOException("Not a tide prediction file")
        if (datum?.contains("LOWEST ASTRONOMICAL TIDE", ignoreCase = true) != true) {
            throw IOException("Tide file isn't to chart datum (LAT)")
        }
        return Raw(fields, rows, offset)
    }

    /** A file cut short (a broken download) has fewer readings than its header says, or ends early. */
    private fun checkComplete(raw: Raw, count: Int, lastMillis: Long) {
        raw.fields["Number of valid readings"]?.toIntOrNull()?.let { expected ->
            if (count < expected) throw IOException("Tide file is incomplete ($count of $expected readings)")
        }
        raw.fields["Date and time of last reading"]?.let { last ->
            val headerLast = runCatching { LocalDateTime.parse(last.trim(), HEADER_TIME).toInstant(raw.offset!!).toEpochMilli() }.getOrNull()
            if (headerLast != null && lastMillis != headerLast) throw IOException("Tide file is incomplete (ends early)")
        }
    }

    /** A high/low file. */
    fun parse(text: String): MsqPredictions {
        val raw = read(text) { it == "1" || it == "-1" }
        if (raw.fields["Data Class"]?.equals("HILO", ignoreCase = true) == false) throw IOException("Not a high and low tide file")
        val extremes = raw.rows.map { TideExtreme(it.millis, it.height, it.ind == "1") }.sortedBy { it.timeMillis }
        if (extremes.size < 2) throw IOException("No tides in the file")
        checkComplete(raw, extremes.size, extremes.last().timeMillis)
        return MsqPredictions(raw.id, raw.fields["Tidal Station Name"].orEmpty(), raw.lat, raw.lng, extremes)
    }

    /** A 10-minute ("interval") file: every reading must be there, evenly spaced. */
    fun parseInterval(text: String): MsqIntervalYear {
        val raw = read(text) { it.isNotEmpty() }
        if (raw.fields["Data Class"]?.equals("INTERVAL", ignoreCase = true) != true) throw IOException("Not a 10-minute tide file")
        val minutes = raw.fields.entries.firstOrNull { it.key.startsWith("The readings have a fixed interval") }?.value?.trim()?.toIntOrNull()
            ?: throw IOException("10-minute tide file doesn't say how far apart its readings are")
        if (minutes !in 1..60) throw IOException("10-minute tide file has readings $minutes minutes apart")
        val rows = raw.rows
        if (rows.size < 2 || rows.size > MsqIntervalYear.MAX_READINGS) throw IOException("No tides in the file")
        val step = minutes * 60_000L
        for (i in 1 until rows.size) {
            if (rows[i].millis - rows[i - 1].millis != step) throw IOException("10-minute tide file has a gap")
        }
        checkComplete(raw, rows.size, rows.last().millis)
        return MsqIntervalYear(raw.id, raw.lat, raw.lng, rows[0].millis, step, FloatArray(rows.size) { rows[it].height.toFloat() })
    }

    /** "1000E" → UTC+10:00; "0930E" → +9:30. */
    fun zone(code: String?): ZoneOffset? {
        val m = Regex("""^(\d{2})(\d{2})([EW])$""").find(code?.trim().orEmpty()) ?: return null
        val minutes = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        if (minutes > 14 * 60) return null
        return ZoneOffset.ofTotalSeconds((if (m.groupValues[3] == "E") 1 else -1) * minutes * 60)
    }

    /** "01/01/2026", "02:41" in [offset] → epoch millis; null if malformed. Done by hand: a 10-minute file has 52,560 rows. */
    private fun rowMillis(date: String, time: String, offset: ZoneOffset): Long? {
        if (date.length != 10 || time.length != 5 || date[2] != '/' || date[5] != '/' || time[2] != ':') return null
        val day = date.substring(0, 2).toIntOrNull() ?: return null
        val month = date.substring(3, 5).toIntOrNull() ?: return null
        val year = date.substring(6, 10).toIntOrNull() ?: return null
        val hour = time.substring(0, 2).toIntOrNull() ?: return null
        val minute = time.substring(3, 5).toIntOrNull() ?: return null
        return runCatching { LocalDateTime.of(year, month, day, hour, minute).toInstant(offset).toEpochMilli() }.getOrNull()
    }

    /** "-21 17" (degrees and minutes) → -21.2833; NaN when missing. */
    private fun degrees(text: String?): Double {
        val parts = text?.trim()?.split(Regex("\\s+")) ?: return Double.NaN
        val d = parts.getOrNull(0)?.toIntOrNull() ?: return Double.NaN
        val m = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val sign = if (parts[0].startsWith("-")) -1 else 1
        return d + sign * m / 60.0
    }

    /** The header's "31 12 2026 23:44". */
    private val HEADER_TIME = DateTimeFormatter.ofPattern("dd MM yyyy HH:mm")
}

/**
 * A tide curve through official highs and lows: half a cosine from each to the
 * next, the way tide tables are read between their times. Heights are given from
 * the mean level (like the model's, from mean sea level) and the mean is kept,
 * so heights above LAT come back exactly as published.
 */
object MsqCurve {

    class Built(
        val curve: TideCurve,
        /** The mean tide level, metres above LAT. */
        val meanAboveLatM: Double,
        /** Drawn from 10-minute predictions, not half cosines between highs and lows. */
        val fromInterval: Boolean = false
    )

    /**
     * The curve through [extremes] (heights above LAT) around [aroundMillis]: only the
     * unbroken run of highs and lows that contains it. A run breaks where two are more
     * than [MAX_GAP_MS] apart, or two highs or two lows come together (one between is
     * missing). Heights are given from [meanAboveLatM] (mean sea level above LAT); when
     * that is null, from the mean of the run's curve. Null when that time isn't covered.
     */
    fun build(extremes: List<TideExtreme>, aroundMillis: Long, meanAboveLatM: Double? = null, stepMillis: Long = STEP_MS): Built? {
        val sorted = extremes.sortedBy { it.timeMillis }.fold(ArrayList<TideExtreme>()) { out, e ->
            if (out.isEmpty() || e.timeMillis > out.last().timeMillis) out += e
            out
        }
        if (sorted.size < 2) return null
        var start = 0
        var run: List<TideExtreme>? = null
        for (i in 1..sorted.size) {
            if (i == sorted.size || sorted[i].timeMillis - sorted[i - 1].timeMillis > MAX_GAP_MS || sorted[i].isHigh == sorted[i - 1].isHigh) {
                if (aroundMillis in sorted[start].timeMillis..sorted[i - 1].timeMillis && i - start >= 2) run = sorted.subList(start, i)
                start = i
            }
        }
        run ?: return null
        val mean = meanAboveLatM ?: curveMean(run)
        val times = ArrayList<Long>(run.size * 7)
        val heights = ArrayList<Double>(run.size * 7)
        for ((a, b) in run.zipWithNext()) {
            val dt = b.timeMillis - a.timeMillis
            val steps = max(2L, (dt.toDouble() / stepMillis).roundToLong()).toInt()
            for (k in 0 until steps) {
                val u = k.toDouble() / steps
                times += a.timeMillis + (dt * u).roundToLong()
                heights += between(a.heightM, b.heightM, u) - mean
            }
        }
        times += run.last().timeMillis
        heights += run.last().heightM - mean
        return Built(
            TideCurve(times.toLongArray(), heights.toDoubleArray(), run.map { it.copy(heightM = it.heightM - mean) }),
            mean
        )
    }

    /**
     * The curve through 10-minute predictions (consecutive [years], heights above LAT)
     * around [aroundMillis], smooth between readings: only the unbroken run of years that
     * contains it. The highs and lows listed are [extremes] (the published ones) when
     * given, else found on the curve. Heights are given from [meanAboveLatM].
     */
    fun buildInterval(years: List<MsqIntervalYear>, extremes: List<TideExtreme>?, aroundMillis: Long, meanAboveLatM: Double): Built? {
        val sorted = years.sortedBy { it.startMillis }
        var run = mutableListOf<MsqIntervalYear>()
        var found: List<MsqIntervalYear>? = null
        for (y in sorted) {
            val last = run.lastOrNull()
            if (last != null && (y.stepMillis != last.stepMillis || y.startMillis != last.endMillis + last.stepMillis)) {
                if (aroundMillis in run.first().startMillis..run.last().endMillis) found = run
                run = mutableListOf()
            }
            run += y
        }
        if (run.isNotEmpty() && aroundMillis in run.first().startMillis..run.last().endMillis) found = run
        val chosen = found ?: return null
        val count = chosen.sumOf { it.heightsM.size }
        val times = LongArray(count)
        val heights = DoubleArray(count)
        var i = 0
        for (y in chosen) {
            for (k in y.heightsM.indices) {
                times[i] = y.startMillis + k * y.stepMillis
                heights[i] = y.heightsM[k] - meanAboveLatM
                i++
            }
        }
        val from = times.first()
        val to = times.last()
        val known = extremes?.filter { it.timeMillis in from..to }?.map { it.copy(heightM = it.heightM - meanAboveLatM) }
        return Built(TideCurve(times, heights, known), meanAboveLatM, fromInterval = true)
    }

    /** The mean level of the half-cosine curve through [extremes] (the mean of each piece is the middle of its ends). */
    fun curveMean(extremes: List<TideExtreme>): Double {
        var weighted = 0.0
        var span = 0.0
        for ((a, b) in extremes.zipWithNext()) {
            val dt = (b.timeMillis - a.timeMillis).toDouble()
            if (dt <= 0) continue
            weighted += (a.heightM + b.heightM) / 2 * dt
            span += dt
        }
        return if (span > 0) weighted / span else extremes.map { it.heightM }.average()
    }

    /** Half a cosine from [from] (u = 0) to [to] (u = 1). */
    fun between(from: Double, to: Double, u: Double): Double = (from + to) / 2 + (from - to) / 2 * cos(PI * u)

    private const val STEP_MS = 30 * 60_000L
    /** Highs and lows further apart than this mean missing data. */
    const val MAX_GAP_MS = 30 * 3_600_000L
}
