package au.opendepth.app.map

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The NSW seabed reef extent 2023 (NSW Department of Climate Change, Energy, the Environment and
 * Water, CC BY 4.0): about 51,600 rocky reef outlines mapped from marine lidar, multibeam sonar and
 * aerial photos. Far too much to build in (tens of MB even simplified, and simplifying loses the
 * reef edges that make it worth having), so the outlines are fetched from the department's own
 * map service for the part of the NSW coast on the chart, one map tile ([ZOOM]) at a time, and
 * each tile is kept on the phone, so an area looked at once works offline after.
 *
 * Only the service's "Reef" outlines are asked for: its "Unconsolidated" ones are areas where no
 * reef shows in the data.
 */
object NswReefTiles {

    const val ZOOM = 12

    const val QUERY = "https://mapprod3.environment.nsw.gov.au/arcgis/rest/services/Marine/NSW_seabed_reef_extent/MapServer/1/query"

    /** Where the data is (the service's extent, with a margin): nothing is asked for outside it. */
    const val WEST = 149.8
    const val SOUTH = -37.6
    const val EAST = 153.75
    const val NORTH = -28.1

    /** Outlines in one answer; a tile with more is asked for again from where the last stopped. */
    const val PAGE_SIZE = 1000

    /** Pages a tile is asked for at most (the busiest tiles have about 500 reefs). */
    const val MAX_PAGES = 10

    private const val N = 1 shl ZOOM

    /** One tile of the grid the outlines are fetched and kept by. */
    data class Tile(val x: Int, val y: Int) {
        val name: String get() = "${ZOOM}_${x}_$y"
        val west: Double get() = x.toDouble() / N * 360 - 180
        val east: Double get() = (x + 1).toDouble() / N * 360 - 180
        val north: Double get() = latitudeOf(y)
        val south: Double get() = latitudeOf(y + 1)
    }

    /** One reef outline: the service's id for it, and the GeoJSON feature drawn for it. */
    class Reef(val id: Long, val json: String)

    /** One answer from the service: its outlines, and whether there are more to ask for. */
    class Page(val reefs: List<Reef>, val more: Boolean)

    private fun latitudeOf(y: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / N))))

    private fun tileX(lng: Double): Int = floor((lng + 180) / 360 * N).toInt().coerceIn(0, N - 1)

    private fun tileY(lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * N).toInt().coerceIn(0, N - 1)
    }

    /**
     * The tiles over an area, only where the data is, nearest the middle of the area first (they
     * are fetched in this order, so what is in the middle of the screen comes first).
     */
    fun tilesFor(south: Double, west: Double, north: Double, east: Double): List<Tile> {
        val s = maxOf(south, SOUTH)
        val n = minOf(north, NORTH)
        val w = maxOf(west, WEST)
        val e = minOf(east, EAST)
        if (s >= n || w >= e) return emptyList()
        val x0 = tileX(w)
        val x1 = tileX(e)
        val y0 = tileY(n)
        val y1 = tileY(s)
        val cx = (x0 + x1) / 2.0
        val cy = (y0 + y1) / 2.0
        return buildList { for (x in x0..x1) for (y in y0..y1) add(Tile(x, y)) }
            .sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
    }

    /**
     * The service's answer for [tile]'s reefs from [offset] on: outlines to about 3 m, positions
     * to 5 decimals (about 1 m), in a fixed order so pages follow on.
     */
    fun queryUrl(tile: Tile, offset: Int): String {
        fun f(v: Double) = String.format(Locale.US, "%.6f", v)
        return QUERY + "?where=REEF%3D%27Reef%27" +
            "&geometry=${f(tile.west)}%2C${f(tile.south)}%2C${f(tile.east)}%2C${f(tile.north)}" +
            "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects" +
            "&outFields=OBJECTID&returnGeometry=true&outSR=4326&geometryPrecision=5&maxAllowableOffset=0.00003" +
            "&orderByFields=OBJECTID&resultOffset=$offset&resultRecordCount=$PAGE_SIZE&f=geojson"
    }

    /** Reads one answer (or a saved tile, which is written the same way). */
    fun parsePage(text: String): Page {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IOException("the reef service's answer isn't JSON")
        root.get("error")?.let { throw IOException("the reef service answered with an error: $it") }
        val features = root.getAsJsonArray("features") ?: throw IOException("the reef service's answer has no reefs list")
        val reefs = features.mapNotNull { element ->
            val f = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = f.get("id")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: f.getAsJsonObject("properties")?.get("OBJECTID")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: return@mapNotNull null
            val geometry = f.get("geometry")?.takeIf { it.isJsonObject } ?: return@mapNotNull null
            Reef(id, """{"type":"Feature","id":$id,"geometry":$geometry,"properties":{}}""")
        }
        val exceeded = root.get("exceededTransferLimit")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            root.getAsJsonObject("properties")?.get("exceededTransferLimit")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        return Page(reefs, exceeded || features.size() >= PAGE_SIZE)
    }

    /**
     * One collection of the tiles' reefs for the map, each reef once: a reef across a tile edge
     * comes back with both tiles, and drawn twice its fill would be twice as strong.
     */
    fun featureCollection(tiles: Collection<List<Reef>>): String {
        val seen = HashSet<Long>()
        val out = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (tile in tiles) for (reef in tile) {
            if (!seen.add(reef.id)) continue
            if (!first) out.append(',')
            out.append(reef.json)
            first = false
        }
        return out.append("]}").toString()
    }
}

/**
 * The reef tiles kept on the phone ([dir], one small gzip file a tile) and the last few read, in
 * memory. What is kept is held under [maxBytes] by deleting the tiles looked at longest ago, and
 * can be cleared from Settings → Storage. [fetch] gets a URL's text or throws; tests pass a fake one.
 */
class NswReefStore(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val fetch: (String) -> String
) {

    private val memory = object : LinkedHashMap<String, List<NswReefTiles.Reef>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NswReefTiles.Reef>>?) = size > MEMORY_TILES
    }

    private fun file(tile: NswReefTiles.Tile) = File(dir, tile.name + SUFFIX)

    /** The tile's reefs if they are kept (in memory or on the phone), or null. */
    fun cached(tile: NswReefTiles.Tile): List<NswReefTiles.Reef>? {
        synchronized(memory) { memory[tile.name] }?.let { return it }
        val f = file(tile)
        if (!f.exists()) return null
        // The file is closed even when it isn't gzip at all, or it couldn't be deleted below.
        val reefs = runCatching { NswReefTiles.parsePage(f.inputStream().use { raw -> GZIPInputStream(raw).use { it.readBytes().decodeToString() } }).reefs }
            .getOrElse {
                f.delete() // unreadable (cut short, say): fetched again next time
                return null
            }
        synchronized(memory) { memory[tile.name] = reefs }
        // Looked at now: the last to go when room is made.
        f.setLastModified(System.currentTimeMillis())
        return reefs
    }

    /** The tile's reefs from the service, every page, kept on the phone. Throws if they can't be got. */
    fun download(tile: NswReefTiles.Tile): List<NswReefTiles.Reef> {
        val reefs = mutableListOf<NswReefTiles.Reef>()
        var offset = 0
        for (page in 0 until NswReefTiles.MAX_PAGES) {
            val answer = NswReefTiles.parsePage(fetch(NswReefTiles.queryUrl(tile, offset)))
            reefs += answer.reefs
            if (!answer.more || answer.reefs.isEmpty()) break
            offset += answer.reefs.size
        }
        save(tile, reefs)
        synchronized(memory) { memory[tile.name] = reefs }
        prune()
        return reefs
    }

    /** The bytes of the tiles kept on the phone. */
    fun keptBytes(): Long = tileFiles().sumOf { it.length() }

    /**
     * Deletes the tiles looked at longest ago until what is kept is under [maxBytes] (about 800
     * tiles at the default: a long stretch of the coast).
     */
    fun prune() {
        val files = tileFiles().sortedByDescending { it.lastModified() }
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxBytes) {
                synchronized(memory) { memory.remove(f.name.removeSuffix(SUFFIX)) }
                f.delete()
            }
        }
    }

    /** Deletes every kept tile, on the phone and in memory, and returns the bytes freed. */
    fun clear(): Long {
        synchronized(memory) { memory.clear() }
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            val size = f.length()
            if (f.isFile && f.delete()) freed += size
        }
        return freed
    }

    private fun tileFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList().orEmpty()

    private fun save(tile: NswReefTiles.Tile, reefs: List<NswReefTiles.Reef>) {
        dir.mkdirs()
        val part = File(dir, tile.name + ".part")
        GZIPOutputStream(part.outputStream()).use { out ->
            out.write(NswReefTiles.featureCollection(listOf(reefs)).encodeToByteArray())
        }
        val target = file(tile)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("couldn't keep the reef tile ${tile.name}")
            }
        }
    }

    companion object {
        /** The folder in the app's files that holds the tiles. */
        const val DIR = "reefs-nsw"
        /** The most kept on the phone. */
        const val MAX_BYTES = 50L * 1024 * 1024
        private const val SUFFIX = ".json.gz"
        private const val MEMORY_TILES = 48
    }
}

/**
 * One look at the chart's NSW reefs: shows the tiles kept on the phone, then fetches the rest,
 * giving the map the whole set each time it changes, and never the same set twice running.
 * Runs on a worker thread; [shown] is set (on the main thread) to the tiles the map was last
 * given. [now] is the clock, for tests.
 */
class NswReefLoader(private val store: NswReefStore, private val now: () -> Long = System::currentTimeMillis) {

    /** The tiles in what the map was last given. */
    @Volatile var shown: Set<String> = emptySet()

    /** When each tile last failed for a reason other than being offline: not asked for again for a while. */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Until when nothing is fetched after the phone turned out to be offline: only kept tiles are shown. */
    @Volatile private var offlineUntil = 0L

    /**
     * Shows [tiles]: [show] gets the kept ones at once (when they aren't what the map has), then
     * the set again after every few fetched and after the last. Stops when [current] turns false,
     * and at the first failure that means there is no internet: the other tiles would each wait
     * as long (boat Wi-Fi with no internet can hold every name lookup for seconds).
     */
    fun load(
        tiles: List<NswReefTiles.Tile>,
        current: () -> Boolean,
        show: (Map<String, List<NswReefTiles.Reef>>) -> Unit,
        onError: (NswReefTiles.Tile, Exception) -> Unit = { _, _ -> }
    ) {
        if (!current()) return
        val have = LinkedHashMap<String, List<NswReefTiles.Reef>>()
        val missing = mutableListOf<NswReefTiles.Tile>()
        tiles.forEach { t -> store.cached(t)?.let { have[t.name] = it } ?: missing.add(t) }
        if (have.isNotEmpty() && have.keys != shown) show(LinkedHashMap(have))
        // Fetched and not yet given to the map.
        var fresh = 0
        for (t in missing) {
            if (!current() || now() < offlineUntil) break
            val failed = failedAt[t.name]
            if (failed != null && now() - failed < RETRY_MS) continue
            try {
                have[t.name] = store.download(t)
                failedAt.remove(t.name)
                if (++fresh == SHOW_EVERY) {
                    show(LinkedHashMap(have))
                    fresh = 0
                }
            } catch (e: Exception) {
                onError(t, e)
                // Offline: not held against the tile, asked for again once the internet is back.
                if (isOffline(e)) {
                    offlineUntil = now() + OFFLINE_PAUSE_MS
                    break
                }
                failedAt[t.name] = now()
            }
        }
        if (fresh > 0 && current()) show(LinkedHashMap(have))
    }

    /** The internet is back: fetch again at once. */
    fun online() {
        offlineUntil = 0L
    }

    /** The kept tiles were cleared: the map's set is shown again from scratch. */
    fun forget() {
        shown = emptySet()
        failedAt.clear()
    }

    companion object {
        /** The map is given what has come after this many tiles are fetched. */
        const val SHOW_EVERY = 4
        /** A tile that failed (the service's error, say) is asked for again after this. */
        const val RETRY_MS = 2 * 60_000L
        /** After finding the phone offline, nothing is fetched for this long (or until the internet is back). */
        const val OFFLINE_PAUSE_MS = 20_000L

        /** A failure that means no internet (no name lookup, no connection, a captive portal's certificate), not a bad tile. */
        fun isOffline(e: Throwable): Boolean =
            e is java.net.UnknownHostException || e is java.net.SocketException ||
                e is java.net.SocketTimeoutException || e is javax.net.ssl.SSLException
    }
}
