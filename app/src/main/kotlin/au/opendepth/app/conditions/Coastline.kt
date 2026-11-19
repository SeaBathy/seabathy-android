package au.opendepth.app.conditions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import au.opendepth.app.diagnostics.DiagnosticLog
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Where the sea ends, to cut the sea-conditions picture at the shore rather
 * than in blocks over the land. Two sources:
 *
 * - OpenStreetMap's coastline (ODbL), the same one the chart draws: the water
 *   polygons from OpenFreeMap's map tiles at zoom [ZOOM] (about 10 m): the
 *   "ocean", and the harbours, bays and river mouths that join it, which the
 *   map draws as water of their own ([SeaWaters]). Fetched for each downloaded
 *   area and kept as small files, one a tile, until no saved area needs them.
 * - Natural Earth's land (public domain, a few hundred metres), built in: used
 *   where the tiles aren't there yet (offline, or still coming).
 *
 * The cut is a mask the size of the picture: picture pixels on land become
 * see-through, so the layers under the picture show there as before.
 */
class Coastline(context: Context) {
    private val dir = File(File(context.filesDir, "conditions"), "coast").apply { mkdirs() }
    private val assets = context.applicationContext.assets
    /** Tiles read, by "x_y". */
    private val tiles = object : LinkedHashMap<String, Array<ShortArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Array<ShortArray>>?) = size > 400
    }
    @Volatile private var land: List<LandPolygon>? = null

    /** A tile of the coastline: [x], [y] at [ZOOM]. */
    data class Tile(val x: Int, val y: Int)

    private class LandPolygon(val south: Float, val west: Float, val north: Float, val east: Float, val rings: List<IntArray>)

    // Which tiles ------------------------------------------------------------------------

    fun tilesFor(south: Double, west: Double, north: Double, east: Double): List<Tile> {
        val n = 1 shl ZOOM
        val x0 = tileX(west, n); val x1 = tileX(east, n)
        val y0 = tileY(north, n); val y1 = tileY(south, n)
        return buildList { for (x in x0..x1) for (y in y0..y1) add(Tile(x, y)) }
    }

    private fun file(t: Tile) = File(dir, "${t.x}_${t.y}$SEA")

    /** Where a saved tile's sea meets its four edges: for the tiles next door that are fetched later. */
    private fun edgeFile(t: Tile) = File(dir, "${t.x}_${t.y}$EDGES")

    /** Whether a tile is saved, remembered: this is asked for every picture and as the map moves, and mustn't touch the disk each time. */
    private val present = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun has(t: Tile): Boolean = present.getOrPut("${t.x}_${t.y}") { file(t).exists() }

    /** How many of the area's coastline tiles are saved, of how many. */
    fun saved(south: Double, west: Double, north: Double, east: Double): Pair<Int, Int> =
        tilesFor(south, west, north, east).let { tiles -> tiles.count(::has) to tiles.size }

    /**
     * Whether [lat]/[lng] is sea by OpenStreetMap's coastline, from the saved tile there; null when
     * that tile isn't saved. Quick: one tile's outline, kept in memory once read.
     */
    fun isSea(lat: Double, lng: Double): Boolean? {
        val n = 1 shl ZOOM
        val t = Tile(tileX(lng, n), tileY(lat, n))
        if (!has(t)) return null
        val rings = read(t) ?: return null
        if (rings.isEmpty()) return false
        val gx = ((lng + 180) / 360 * n - t.x) * EXTENT
        val gy = ((PI - mercatorY(lat)) / (2 * PI) * n - t.y) * EXTENT
        return windingNumber(rings, gx, gy) != 0
    }

    /** All of the area's coastline is saved. */
    fun covers(south: Double, west: Double, north: Double, east: Double) = tilesFor(south, west, north, east).all(::has)

    // Fetching ---------------------------------------------------------------------------

    /**
     * Saves the coastline tiles for the area that aren't saved yet, a few at a time. Off the
     * main thread. Returns how many it couldn't get.
     */
    fun fetch(south: Double, west: Double, north: Double, east: Double): Int = synchronized(fetching) { fetchMissing(south, west, north, east) }

    /** One fetch at a time: two at once for the same area (the engine's and the map's) each downloaded every tile. */
    private val fetching = Any()

    private fun fetchMissing(south: Double, west: Double, north: Double, east: Double): Int {
        val missing = tilesFor(south, west, north, east).filterNot(::has)
        if (missing.isEmpty()) return 0
        val template = tileTemplate()
        val started = System.nanoTime()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val waters = java.util.concurrent.ConcurrentHashMap<Tile, List<Water>>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            missing.map { t ->
                pool.submit {
                    runCatching { waters[t] = waterPolygons(download(template.replace("{z}", "$ZOOM").replace("{x}", "${t.x}").replace("{y}", "${t.y}"))) }
                        .onFailure { failed.incrementAndGet() }
                }
            }.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdown()
        }
        val fetched = System.nanoTime()
        saveSea(waters)
        DiagnosticLog.log(TAG, "coastline: ${missing.size - failed.get()} of ${missing.size} tiles in ${(fetched - started) / 1_000_000} ms, the sea worked out in ${(System.nanoTime() - fetched) / 1_000_000} ms")
        return failed.get()
    }

    /**
     * Works out which of each tile's water is the sea ([SeaWaters]) and saves it: first what
     * touches the tile's own ocean, then water running in from a tile next door (one of these,
     * or one saved before).
     */
    private fun saveSea(waters: Map<Tile, List<Water>>) {
        val layouts = waters.mapValues { SeaWaters.layout(it.value) }
        val alone = layouts.mapValues { (_, layout) -> SeaWaters.edgeSea(layout, SeaWaters.joined(layout)) }
        fun edges(x: Int, y: Int): Array<BooleanArray>? = Tile(x, y).let { alone[it] ?: readEdges(it) }
        for ((t, layout) in layouts) {
            // North, east, south, west: the neighbour's edge that meets this one.
            val facing = arrayOf(edges(t.x, t.y - 1)?.get(2), edges(t.x + 1, t.y)?.get(3), edges(t.x, t.y + 1)?.get(0), edges(t.x - 1, t.y)?.get(1))
            val sea = SeaWaters.joined(layout, SeaWaters.seeds(layout, facing))
            runCatching { save(t, SeaWaters.rings(layout, sea), SeaWaters.edgeSea(layout, sea)) }
                .onFailure { DiagnosticLog.logError(TAG, "saving a coastline tile FAILED", it) }
        }
    }

    private fun save(t: Tile, rings: List<ShortArray>, edges: Array<BooleanArray>) {
        val bits = java.util.BitSet(4 * SeaWaters.SIDE)
        for (side in 0 until 4) for (i in 0 until SeaWaters.SIDE) if (edges[side][i]) bits.set(side * SeaWaters.SIDE + i)
        edgeFile(t).writeBytes(bits.toByteArray())
        val tmp = File(dir, "${t.x}_${t.y}.part")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(rings.size)
            for (r in rings) {
                out.writeInt(r.size)
                r.forEach { out.writeShort(it.toInt()) }
            }
        }
        if (!tmp.renameTo(file(t))) tmp.delete()
        present["${t.x}_${t.y}"] = file(t).exists()
        synchronized(tiles) { tiles.remove("${t.x}_${t.y}") }
    }

    private fun readEdges(t: Tile): Array<BooleanArray>? {
        val f = edgeFile(t)
        if (!f.exists()) return null
        val bits = runCatching { java.util.BitSet.valueOf(f.readBytes()) }.getOrNull() ?: return null
        return Array(4) { side -> BooleanArray(SeaWaters.SIDE) { i -> bits.get(side * SeaWaters.SIDE + i) } }
    }

    private fun read(t: Tile): Array<ShortArray>? {
        val key = "${t.x}_${t.y}"
        synchronized(tiles) { tiles[key] }?.let { return it }
        val f = file(t)
        if (!f.exists()) return null
        val rings = runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                Array(input.readInt()) { ShortArray(input.readInt()) { input.readShort() } }
            }
        }.getOrNull() ?: return null
        synchronized(tiles) { tiles[key] = rings }
        return rings
    }

    /** Removes saved tiles no saved area needs any more (areas as south, west, north, east). */
    fun prune(areas: List<DoubleArray>) {
        val keep = areas.flatMap { tilesFor(it[0], it[1], it[2], it[3]) }.flatMap { listOf("${it.x}_${it.y}$SEA", "${it.x}_${it.y}$EDGES") }.toSet()
        // Also the files from before harbours and bays counted as sea (".bin": the ocean alone).
        val gone = dir.listFiles { f -> f.name.endsWith(".bin") || ((f.name.endsWith(SEA) || f.name.endsWith(EDGES)) && f.name !in keep) }.orEmpty()
        gone.forEach { it.delete() }
        present.clear()
        if (gone.isNotEmpty()) {
            synchronized(tiles) { tiles.clear() }
            DiagnosticLog.log(TAG, "coastline: removed ${gone.size} tiles no saved area needs")
        }
    }

    // The mask ---------------------------------------------------------------------------

    /**
     * How much of each pixel of a [width]×[height] picture of the area is sea, 0 to 255 (rows
     * even in Mercator, as [ConditionsPicture.render] draws them): see [maskBitmap].
     */
    fun mask(south: Double, west: Double, north: Double, east: Double, width: Int, height: Int): ByteArray? {
        val bitmap = maskBitmap(south, west, north, east, width, height) ?: return null
        return bytesOf(bitmap).also { bitmap.recycle() }
    }

    /**
     * The sea of the area as a [width]×[height] picture (alpha only), for cutting another picture
     * with: from OpenStreetMap's coastline in every tile where it's saved, and from the built-in
     * outline in the tiles where it isn't (yet), so one missing tile no longer coarsens the whole
     * shore. Null when neither can tell. The caller recycles it.
     */
    fun maskBitmap(south: Double, west: Double, north: Double, east: Double, width: Int, height: Int): Bitmap? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val yTop = mercatorY(north)
        val mapHeight = yTop - mercatorY(south)
        val widthDeg = east - west
        fun px(lng: Double) = ((lng - west) / widthDeg * width).toFloat()
        fun py(mercY: Double) = ((yTop - mercY) / mapHeight * height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        val needed = tilesFor(south, west, north, east)
        val saved = needed.filter(::has)
        val patchy = saved.size < needed.size
        if (patchy) {
            // The built-in outline under the tiles that aren't saved: all sea, then the land taken out.
            val polygons = landPolygons()
            if (polygons == null) {
                if (saved.isEmpty()) {
                    bitmap.recycle()
                    return null
                }
            } else {
                canvas.drawColor(Color.BLACK)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                for (p in polygons) {
                    if (p.north < south || p.south > north || p.east < west || p.west > east) continue
                    val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
                    for (r in p.rings) {
                        var i = 0
                        while (i + 1 < r.size) {
                            val x = px(r[i + 1] / 1e5)
                            val y = py(mercatorY(r[i] / 1e5))
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            i += 2
                        }
                        path.close()
                    }
                    canvas.drawPath(path, paint)
                }
                paint.xfermode = null
            }
        }
        // OpenStreetMap: the sea, tile by tile, each cut to its own square (tiles overlap a little).
        val n = (1 shl ZOOM).toDouble()
        val empty = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        for (t in saved) {
            val rings = read(t) ?: continue
            // On whole pixels: a square that ended part-way through a pixel left a faint line where it met the next.
            val left = Math.round(px(t.x / n * 360 - 180)).toFloat()
            val right = Math.round(px((t.x + 1) / n * 360 - 180)).toFloat()
            val top = Math.round(py(PI - 2 * PI * t.y / n)).toFloat()
            val bottom = Math.round(py(PI - 2 * PI * (t.y + 1) / n)).toFloat()
            if (right <= 0 || left >= width || bottom <= 0 || top >= height) continue
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            // Over the built-in outline, this tile's square starts again from nothing.
            if (patchy) canvas.drawPaint(empty)
            if (rings.isNotEmpty()) {
                val path = Path().apply { fillType = Path.FillType.WINDING }
                for (r in rings) {
                    var i = 0
                    while (i + 1 < r.size) {
                        val x = px((t.x + r[i] / EXTENT) / n * 360 - 180)
                        val y = py(PI - 2 * PI * (t.y + r[i + 1] / EXTENT) / n)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        i += 2
                    }
                    path.close()
                }
                canvas.drawPath(path, paint)
            }
            canvas.restore()
        }
        return bitmap
    }

    /** The built-in Natural Earth land, read once (about 2 MB). */
    private fun landPolygons(): List<LandPolygon>? {
        land?.let { return it }
        return runCatching {
            DataInputStream(assets.open(LAND_ASSET).buffered(64 * 1024)).use { input ->
                if (input.readInt() != LAND_MAGIC || input.readInt() != 1) throw IOException("land outline version")
                List(input.readInt()) {
                    val s = input.readFloat(); val w = input.readFloat(); val n = input.readFloat(); val e = input.readFloat()
                    val rings = List(input.readInt()) {
                        val count = input.readInt()
                        val ring = IntArray(count * 2)
                        ring[0] = input.readInt(); ring[1] = input.readInt()
                        for (k in 1 until count) {
                            ring[k * 2] = ring[k * 2 - 2] + unzigzag(readVarint(input))
                            ring[k * 2 + 1] = ring[k * 2 - 1] + unzigzag(readVarint(input))
                        }
                        ring
                    }
                    LandPolygon(s, w, n, e, rings)
                }
            }
        }.onFailure { DiagnosticLog.logError(TAG, "reading the built-in land outline FAILED", it) }.getOrNull()?.also { land = it }
    }

    companion object {
        private const val TAG = "SeaBathyConditions"
        /** About 10 m detail at the shore: zoom 10 tiles of a 4096 grid. */
        const val ZOOM = 10
        private const val EXTENT = 4096.0
        private const val PARALLEL = 4
        private const val LAND_ASSET = "ne_land.bin"
        /** A tile's saved sea, and where it meets the tile's edges. */
        private const val SEA = ".sea"
        private const val EDGES = ".edge"
        /** The water the map draws apart from the ocean that can be an arm of the sea. */
        private val ARMS = setOf("lake", "river", "dock")
        private const val LAND_MAGIC = 0x53424C44
        private const val TILEJSON = "https://tiles.openfreemap.org/planet"
        @Volatile private var template: String? = null

        private val client by lazy {
            OkHttpClient.Builder().dns(au.opendepth.app.util.FallbackDns).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        private fun download(url: String): ByteArray =
            client.newCall(Request.Builder().url(url).header("User-Agent", "SeaBathy (Android coastline)").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("map tiles answered ${r.code}")
                r.body?.bytes() ?: ByteArray(0)
            }

        /** The current tile address (it names the map data's version), from the map server's description. */
        private fun tileTemplate(): String = template ?: JsonParser.parseString(String(download(TILEJSON)))
            .asJsonObject.getAsJsonArray("tiles")[0].asString.also { template = it }

        fun tileX(lng: Double, n: Int) = floor((lng + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        fun tileY(lat: Double, n: Int): Int {
            val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        }

        /** The winding number of [rings] (x0, y0, x1, y1 …, each closed implicitly) round [x]/[y]: not 0 inside. Pure, unit-tested. */
        fun windingNumber(rings: Array<ShortArray>, x: Double, y: Double): Int {
            var wn = 0
            for (r in rings) {
                val count = r.size / 2
                if (count < 3) continue
                for (i in 0 until count) {
                    val x1 = r[i * 2].toDouble(); val y1 = r[i * 2 + 1].toDouble()
                    val j = (i + 1) % count
                    val x2 = r[j * 2].toDouble(); val y2 = r[j * 2 + 1].toDouble()
                    val side = (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1)
                    if (y1 <= y) {
                        if (y2 > y && side > 0) wn++
                    } else if (y2 <= y && side < 0) {
                        wn--
                    }
                }
            }
            return wn
        }

        /** An alpha picture's bytes, row by row with no padding. */
        fun bytesOf(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val rowBytes = bitmap.rowBytes
            val raw = ByteArray(rowBytes * height)
            bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(raw))
            if (rowBytes == width) return raw
            val out = ByteArray(width * height)
            for (y in 0 until height) System.arraycopy(raw, y * rowBytes, out, y * width, width)
            return out
        }

        fun mercatorY(lat: Double): Double = ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))
        fun latitudeOf(mercY: Double): Double = Math.toDegrees(2 * atan(exp(mercY)) - PI / 2)

        // Map tile reading: just enough of the vector tile format for the water layer ------------------

        /** The "ocean" rings of a map tile (gzip or not), in tile units: x0, y0, x1, y1… */
        fun oceanRings(raw: ByteArray): List<ShortArray> = waterPolygons(raw).filter { it.ocean }.flatMap { it.rings }

        /**
         * The water of a map tile (gzip or not) that is, or may be, sea: the ocean, and the
         * lakes, rivers and docks, some of which are harbours and estuaries ([SeaWaters] tells
         * which). Each piece is one outline with its holes.
         */
        fun waterPolygons(raw: ByteArray): List<Water> {
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(raw)).readBytes() else raw
            val out = mutableListOf<Water>()
            Pb(bytes, 0, bytes.size).forEach { field, layer ->
                if (field == 3) readLayer(layer, out)
            }
            return out
        }

        private fun readLayer(layer: Pb, out: MutableList<Water>) {
            var name = ""
            val keys = mutableListOf<String>()
            val values = mutableListOf<String?>()
            val features = mutableListOf<Pb>()
            layer.forEach { f, v ->
                when (f) {
                    1 -> name = v.string()
                    2 -> features += v
                    3 -> keys += v.string()
                    4 -> { var s: String? = null; v.forEach { vf, vv -> if (vf == 1) s = vv.string() }; values += s }
                }
            }
            if (name != "water") return
            val classKey = keys.indexOf("class")
            for (feature in features) {
                var type = 0
                var tags: IntArray? = null
                var geometry: IntArray? = null
                feature.forEach { f, v ->
                    when (f) {
                        2 -> tags = v.packed()
                        3 -> type = v.varintValue.toInt()
                        4 -> geometry = v.packed()
                    }
                }
                if (type != 3) continue
                val t = tags ?: continue
                var kind: String? = null
                var k = 0
                while (k + 1 < t.size) {
                    if (t[k] == classKey) kind = values.getOrNull(t[k + 1])
                    k += 2
                }
                if (kind != "ocean" && kind !in ARMS) continue
                val rings = mutableListOf<ShortArray>()
                geometry?.let { decodeRings(it, rings) }
                // A feature can hold several pieces: each outside ring (drawn clockwise) starts one, its holes follow it.
                var piece = mutableListOf<ShortArray>()
                for (ring in rings) {
                    if (signedArea(ring) > 0 && piece.isNotEmpty()) {
                        out += Water(kind == "ocean", piece)
                        piece = mutableListOf()
                    }
                    piece += ring
                }
                if (piece.isNotEmpty()) out += Water(kind == "ocean", piece)
            }
        }

        /** Twice a ring's area, positive for an outside ring (clockwise on the tile, y down). */
        private fun signedArea(r: ShortArray): Long {
            val n = r.size / 2
            var sum = 0L
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                sum += r[i * 2].toLong() * r[j * 2 + 1] - r[j * 2].toLong() * r[i * 2 + 1]
            }
            return sum
        }

        private fun decodeRings(g: IntArray, out: MutableList<ShortArray>) {
            var x = 0
            var y = 0
            var i = 0
            var ring = ArrayList<Short>()
            while (i < g.size) {
                val cmd = g[i] and 0x7
                val count = g[i] ushr 3
                i++
                when (cmd) {
                    1, 2 -> repeat(count) {
                        if (i + 1 >= g.size) return
                        if (cmd == 1 && ring.isNotEmpty()) { out += ring.toShortArray(); ring = ArrayList() }
                        x += unzigzag(g[i]); y += unzigzag(g[i + 1]); i += 2
                        ring.add(x.coerceIn(-32000, 32000).toShort()); ring.add(y.coerceIn(-32000, 32000).toShort())
                    }
                    7 -> { if (ring.isNotEmpty()) { out += ring.toShortArray(); ring = ArrayList() } }
                }
            }
            if (ring.isNotEmpty()) out += ring.toShortArray()
        }

        private fun unzigzag(v: Int) = (v ushr 1) xor -(v and 1)

        private fun readVarint(input: DataInputStream): Int {
            var shift = 0
            var result = 0L
            while (true) {
                val b = input.readUnsignedByte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result.toInt()
                shift += 7
            }
        }
    }

    /** A protobuf message: its fields in order (wire types 0 and 2 kept; the rest skipped). */
    private class Pb(val buf: ByteArray, val start: Int, val end: Int) {
        var varintValue = 0L

        fun string() = String(buf, start, end - start, Charsets.UTF_8)

        /** A packed repeated varint field. */
        fun packed(): IntArray {
            val out = ArrayList<Int>()
            var p = start
            while (p < end) {
                var shift = 0
                var v = 0L
                while (true) {
                    val b = buf[p++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                out += v.toInt()
            }
            return out.toIntArray()
        }

        fun forEach(each: (Int, Pb) -> Unit) {
            var p = start
            fun varint(): Long {
                var shift = 0
                var v = 0L
                while (true) {
                    val b = buf[p++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return v
                    shift += 7
                }
            }
            while (p < end) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> { val v = varint(); each(field, Pb(buf, p, p).also { it.varintValue = v }) }
                    1 -> p += 8
                    2 -> { val len = varint().toInt(); val from = p; p += len; each(field, Pb(buf, from, from + len)) }
                    5 -> p += 4
                    else -> return
                }
            }
        }
    }
}

/**
 * Which points of a block of water are sea, from a [Coastline.mask] of it: a quick test for the
 * flow lines and arrows, so they stop at the same shore the colours do. Pure, unit-tested.
 */
class SeaMask(
    private val bytes: ByteArray, val width: Int, val height: Int,
    val south: Double, val west: Double, val north: Double, val east: Double
) {
    private val yTop = Coastline.mercatorY(north)
    private val mapHeight = yTop - Coastline.mercatorY(south)

    /** Sea at [lat]/[lng] (most of that pixel is), or null outside the block. */
    fun isSea(lat: Double, lng: Double): Boolean? {
        if (lat < south || lat > north || lng < west || lng > east) return null
        val x = ((lng - west) / (east - west) * width).toInt().coerceIn(0, width - 1)
        val y = ((yTop - Coastline.mercatorY(lat)) / mapHeight * height).toInt().coerceIn(0, height - 1)
        return (bytes[y * width + x].toInt() and 0xFF) >= 128
    }

    /**
     * The nearest sea to [lat]/[lng] within [maxKm]: the middle of the nearest pixel that is
     * sea, or null when there's none that near (or the point is outside the block). [accept]
     * can turn a pixel down: one that is mostly water (a river mouth, a narrow channel) can
     * still have its middle on the bank, where the exact coastline says land and nothing reads.
     */
    fun nearestSea(lat: Double, lng: Double, maxKm: Double, accept: (Double, Double) -> Boolean = { _, _ -> true }): Pair<Double, Double>? {
        if (lat < south || lat > north || lng < west || lng > east) return null
        val kmPerPixel = (east - west) / width * 111.195 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        val x0 = ((lng - west) / (east - west) * width).toInt().coerceIn(0, width - 1)
        val y0 = ((yTop - Coastline.mercatorY(lat)) / mapHeight * height).toInt().coerceIn(0, height - 1)
        val reach = (maxKm / kmPerPixel).toInt().coerceAtLeast(1)
        var best = Long.MAX_VALUE
        var bestX = -1
        var bestY = -1
        fun look(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= width || y >= height || (bytes[y * width + x].toInt() and 0xFF) < 128) return
            val d = (x - x0).toLong() * (x - x0) + (y - y0).toLong() * (y - y0)
            if (d < best && accept(latOf(y), lngOf(x))) {
                best = d
                bestX = x
                bestY = y
            }
        }
        // Square rings outwards; a ring further out than the best found can't hold anything nearer.
        var r = 0
        while (r <= reach && r.toLong() * r <= best) {
            if (r == 0) look(x0, y0) else {
                for (dx in -r..r) {
                    look(x0 + dx, y0 - r)
                    look(x0 + dx, y0 + r)
                }
                for (dy in -r + 1 until r) {
                    look(x0 - r, y0 + dy)
                    look(x0 + r, y0 + dy)
                }
            }
            r++
        }
        if (bestX < 0 || best > reach.toLong() * reach) return null
        return latOf(bestY) to lngOf(bestX)
    }

    /** The middle of a pixel. */
    private fun latOf(y: Int) = Coastline.latitudeOf(yTop - (y + 0.5) / height * mapHeight)
    private fun lngOf(x: Int) = west + (x + 0.5) / width * (east - west)
}
