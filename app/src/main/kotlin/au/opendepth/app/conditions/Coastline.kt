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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Where the sea ends, to cut the sea-conditions picture at the shore rather
 * than in blocks over the land. Two sources:
 *
 * - OpenStreetMap's coastline (ODbL), the same one the chart draws: the
 *   "ocean" water polygons from OpenFreeMap's map tiles at zoom [ZOOM] (about
 *   10 m), fetched for each downloaded area and kept as small files, one a
 *   tile, until no saved area needs them.
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

    private fun file(t: Tile) = File(dir, "${t.x}_${t.y}.bin")

    fun has(t: Tile) = file(t).exists()

    /** All of the area's coastline is saved. */
    fun covers(south: Double, west: Double, north: Double, east: Double) = tilesFor(south, west, north, east).all(::has)

    // Fetching ---------------------------------------------------------------------------

    /**
     * Saves the coastline tiles for the area that aren't saved yet, a few at a time. Off the
     * main thread. Returns how many it couldn't get.
     */
    fun fetch(south: Double, west: Double, north: Double, east: Double): Int {
        val missing = tilesFor(south, west, north, east).filterNot(::has)
        if (missing.isEmpty()) return 0
        val template = tileTemplate()
        val started = System.nanoTime()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            missing.map { t ->
                pool.submit {
                    runCatching { save(t, oceanRings(download(template.replace("{z}", "$ZOOM").replace("{x}", "${t.x}").replace("{y}", "${t.y}")))) }
                        .onFailure { failed.incrementAndGet() }
                }
            }.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdown()
        }
        DiagnosticLog.log(TAG, "coastline: ${missing.size - failed.get()} of ${missing.size} tiles in ${(System.nanoTime() - started) / 1_000_000} ms")
        return failed.get()
    }

    private fun save(t: Tile, rings: List<ShortArray>) {
        val tmp = File(dir, "${t.x}_${t.y}.part")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(rings.size)
            for (r in rings) {
                out.writeInt(r.size)
                r.forEach { out.writeShort(it.toInt()) }
            }
        }
        if (!tmp.renameTo(file(t))) tmp.delete()
        synchronized(tiles) { tiles.remove("${t.x}_${t.y}") }
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
        val keep = areas.flatMap { tilesFor(it[0], it[1], it[2], it[3]) }.map { "${it.x}_${it.y}.bin" }.toSet()
        val gone = dir.listFiles { f -> f.name.endsWith(".bin") && f.name !in keep }.orEmpty()
        gone.forEach { it.delete() }
        if (gone.isNotEmpty()) {
            synchronized(tiles) { tiles.clear() }
            DiagnosticLog.log(TAG, "coastline: removed ${gone.size} tiles no saved area needs")
        }
    }

    // The mask ---------------------------------------------------------------------------

    /**
     * How much of each pixel of a [width]×[height] picture of the area is sea, 0 to 255 (rows
     * even in Mercator, as [ConditionsPicture.render] draws them): from OpenStreetMap's
     * coastline where it's saved for the whole area, otherwise from the built-in outline.
     * Null when neither can tell (the built-in outline couldn't be read).
     */
    fun mask(south: Double, west: Double, north: Double, east: Double, width: Int, height: Int): ByteArray? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val yTop = mercatorY(north)
        val mapHeight = yTop - mercatorY(south)
        val widthDeg = east - west
        fun px(lng: Double) = ((lng - west) / widthDeg * width).toFloat()
        fun py(mercY: Double) = ((yTop - mercY) / mapHeight * height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        val needed = tilesFor(south, west, north, east)
        if (needed.all(::has)) {
            // OpenStreetMap: draw the sea, tile by tile, each cut to its own square (tiles overlap a little).
            val n = (1 shl ZOOM).toDouble()
            for (t in needed) {
                val rings = read(t) ?: continue
                if (rings.isEmpty()) continue
                val left = px(t.x / n * 360 - 180)
                val right = px((t.x + 1) / n * 360 - 180)
                val top = py(PI - 2 * PI * t.y / n)
                val bottom = py(PI - 2 * PI * (t.y + 1) / n)
                val path = Path().apply { fillType = Path.FillType.WINDING }
                for (r in rings) {
                    var i = 0
                    while (i + 1 < r.size) {
                        val gx = (t.x + r[i] / EXTENT) / n
                        val gy = (t.y + r[i + 1] / EXTENT) / n
                        val x = px(gx * 360 - 180)
                        val y = py(PI - 2 * PI * gy)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        i += 2
                    }
                    path.close()
                }
                canvas.save()
                canvas.clipRect(left, top, right, bottom)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        } else {
            // The built-in outline: all sea, then the land taken out.
            val polygons = landPolygons() ?: return null
            canvas.drawColor(Color.BLACK)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            for (p in polygons) {
                if (p.north < south || p.south > north || p.east < west || p.west > east) continue
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
                for (r in p.rings) {
                    var i = 0
                    while (i + 1 < r.size) {
                        val lat = r[i] / 1e5
                        val lng = r[i + 1] / 1e5
                        val x = px(lng)
                        val y = py(mercatorY(lat))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        i += 2
                    }
                    path.close()
                }
                canvas.drawPath(path, paint)
            }
        }
        // Rows may be padded: copied whole, then packed.
        val rowBytes = bitmap.rowBytes
        val raw = ByteArray(rowBytes * height)
        bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(raw))
        bitmap.recycle()
        if (rowBytes == width) return raw
        val out = ByteArray(width * height)
        for (y in 0 until height) System.arraycopy(raw, y * rowBytes, out, y * width, width)
        return out
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

        fun mercatorY(lat: Double): Double = ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))
        fun latitudeOf(mercY: Double): Double = Math.toDegrees(2 * atan(exp(mercY)) - PI / 2)

        /** Applies [mask] to [argb] (non-premultiplied colours): land pixels see-through, the shore soft. */
        fun applyMask(argb: IntArray, mask: ByteArray) {
            for (i in argb.indices) {
                val m = mask[i].toInt() and 0xFF
                if (m == 255) continue
                val c = argb[i]
                val a = (c ushr 24) * m / 255
                argb[i] = (a shl 24) or (c and 0x00FFFFFF)
            }
        }

        // Map tile reading: just enough of the vector tile format for the water layer ------------------

        /** The "ocean" rings of a map tile (gzip or not), in tile units: x0, y0, x1, y1… */
        fun oceanRings(raw: ByteArray): List<ShortArray> {
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(raw)).readBytes() else raw
            val out = mutableListOf<ShortArray>()
            Pb(bytes, 0, bytes.size).forEach { field, layer ->
                if (field == 3) readLayer(layer, out)
            }
            return out
        }

        private fun readLayer(layer: Pb, out: MutableList<ShortArray>) {
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
                var isOcean = false
                var k = 0
                while (k + 1 < t.size) {
                    if (t[k] == classKey && values.getOrNull(t[k + 1]) == "ocean") isOcean = true
                    k += 2
                }
                if (isOcean) geometry?.let { decodeRings(it, out) }
            }
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
