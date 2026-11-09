package au.opendepth.app.tiles

import android.database.sqlite.SQLiteDatabase
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.model.DepthRampSettings
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A local HTTP server on 127.0.0.1 (a free port) that serves MBTiles and
 * on-demand GDAL tiles to MapLibre as ordinary `{z}/{x}/{y}` sources.
 * MapLibre's own `mbtiles://` gives no way to draw a missing GDAL tile, so
 * both kinds go through here. One for the app, started with the first tile
 * layer and stopped when none are left.
 */
class LocalTileServer private constructor(port: Int) : NanoHTTPD(LOOPBACK_HOST, port) {

    // Written from the main thread, read from NanoHTTPD's per-request worker threads.
    private val mbtilesDatabases = ConcurrentHashMap<String, SQLiteDatabase>()
    private val mbtilesIsVector = ConcurrentHashMap<String, Boolean>()
    private val gdalSources = ConcurrentHashMap<String, GdalTileSource>()
    private val gdalRamps = ConcurrentHashMap<String, DepthRampSettings>()

    // Diagnostic only: which (kind, layer) pairs have had at least one
    // request, and how many misses have been logged, so the log shows
    // whether MapLibre is requesting a layer's tiles at all without one
    // line per tile for every layer.
    private val seenRequests = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val loggedMisses = java.util.concurrent.atomic.AtomicInteger(0)
    private val loggedTileErrors = java.util.concurrent.atomic.AtomicInteger(0)

    // Separate from loggedMisses: any app on the device can send junk to the
    // loopback port, and must not use up the budget for real tile misses.
    private val loggedUnroutable = java.util.concurrent.atomic.AtomicInteger(0)

    /** How many layers currently rely on this server — 0 means it is safe to [stop]. */
    val activeLayerCount: Int get() = mbtilesDatabases.size + gdalSources.size

    fun putMbtiles(layerId: String, database: SQLiteDatabase, isVector: Boolean) {
        mbtilesDatabases[layerId] = database
        mbtilesIsVector[layerId] = isVector
    }

    fun removeMbtiles(layerId: String) {
        mbtilesDatabases.remove(layerId)
        mbtilesIsVector.remove(layerId)
    }

    fun putGdal(layerId: String, source: GdalTileSource, ramp: DepthRampSettings) {
        gdalSources[layerId] = source
        gdalRamps[layerId] = ramp
    }

    /** Applied to the next tile generated; already-cached tiles keep their old colours until evicted — see [GdalTileSource]'s doc. */
    fun updateGdalRamp(layerId: String, ramp: DepthRampSettings) {
        if (gdalSources.containsKey(layerId)) gdalRamps[layerId] = ramp
    }

    fun removeGdal(layerId: String) {
        gdalSources.remove(layerId)
        gdalRamps.remove(layerId)
    }

    /** The `{z}/{x}/{y}` URL template MapLibre's `TileSet` should point at for this layer. */
    fun urlTemplateFor(layerId: String, kind: Kind): String =
        "http://$LOOPBACK_HOST:$listeningPort/${kind.segment}/$layerId/{z}/{x}/{y}.png"

    enum class Kind(val segment: String) { MBTILES("mbtiles"), GDAL("gdal") }

    override fun serve(session: IHTTPSession): Response {
        val request = parseTilePath(session.uri)
        if (request == null) {
            // Any app on the device can reach loopback: cap this so it cannot flood the log.
            if (loggedUnroutable.incrementAndGet() <= MAX_LOGGED_MISSES) {
                DiagnosticLog.log(TAG, "tile request: unroutable path=${session.uri.take(200).filter { it >= ' ' }}")
            }
            return notFound()
        }

        val registered = mbtilesDatabases.containsKey(request.layerId) || gdalSources.containsKey(request.layerId)
        if (registered && seenRequests.add("${request.kind}/${request.layerId}")) {
            DiagnosticLog.log(
                TAG,
                "first tile request: kind=${request.kind} layerId=${request.layerId} " +
                    "z=${request.z} x=${request.x} y=${request.y}"
            )
        }

        return when (request.kind) {
            Kind.MBTILES -> serveMbtiles(request)
            Kind.GDAL -> serveGdal(request)
        }
    }

    private fun serveMbtiles(request: TileRequest): Response {
        val database = mbtilesDatabases[request.layerId] ?: run {
            logMiss("mbtiles layer NOT REGISTERED", request)
            return notFound()
        }
        // MBTiles rows are TMS-addressed (tile_row counts from the SOUTH);
        // the request's y, like every other tile this app requests or
        // serves, is XYZ/Google-addressed (counts from the NORTH).
        val tmsRow = (1 shl request.z) - 1 - request.y
        // runCatching: the layer may be removed, and its database closed,
        // while this request is in flight.
        val blob = runCatching {
            database.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                arrayOf(request.z.toString(), request.x.toString(), tmsRow.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        }.onFailure { error ->
            // A read error (corrupt or closed database) is not a missing tile;
            // without this, a broken layer is indistinguishable from no coverage.
            logTileError("mbtiles tile read FAILED", request, error)
        }.getOrNull() ?: run {
            logMiss("mbtiles tile not in archive", request)
            return notFound()
        }

        val mimeType = if (mbtilesIsVector[request.layerId] == true) MIME_PBF else MIME_PNG
        return newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(blob), blob.size.toLong())
    }

    private fun serveGdal(request: TileRequest): Response {
        val source = gdalSources[request.layerId] ?: return notFound()
        val ramp = gdalRamps[request.layerId] ?: DepthRampSettings()

        val png = rendering {
            runCatching { source.renderTile(request.z, request.x, request.y, ramp) }
                .onFailure { error -> logTileError("gdal tile FAILED", request, error) }
        }
        return tileResponse(png)
    }

    /**
     * Runs a tile render with at most [RENDER_SLOTS] at once. NanoHTTPD
     * gives every request its own thread, and a fast zoom asks for dozens
     * of tiles of several MB each while being drawn — enough to run a phone
     * out of memory. Extra requests wait their turn instead.
     */
    private inline fun rendering(render: () -> Result<ByteArray?>): Result<ByteArray?> {
        renderSlots.acquire()
        try {
            return render()
        } finally {
            renderSlots.release()
        }
    }

    /**
     * 404 only for a real "no tile here": MapLibre remembers those and
     * leaves the square blank. A failure (low memory, a busy file) is a
     * 503, which MapLibre retries.
     */
    private fun tileResponse(result: Result<ByteArray?>): Response {
        val png = result.getOrElse {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "busy")
        } ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, imageType(png), ByteArrayInputStream(png), png.size.toLong())
    }

    private val renderSlots = java.util.concurrent.Semaphore(RENDER_SLOTS, true)

    /**
     * Full stack traces for the first few tile errors only: one per tile
     * would roll the 4 MB diagnostic log over within minutes of panning a
     * broken layer, erasing the import/restore context that explains it.
     */
    private fun logTileError(reason: String, request: TileRequest, error: Throwable) {
        val count = loggedTileErrors.incrementAndGet()
        if (count > MAX_LOGGED_TILE_ERRORS) return
        DiagnosticLog.logError(
            TAG,
            "$reason: layerId=${request.layerId} z=${request.z} x=${request.x} y=${request.y}",
            error
        )
        if (count == MAX_LOGGED_TILE_ERRORS) {
            DiagnosticLog.log(TAG, "further tile errors are not logged until the tile server restarts")
        }
    }

    private fun logMiss(reason: String, request: TileRequest) {
        if (loggedMisses.incrementAndGet() > MAX_LOGGED_MISSES) return
        DiagnosticLog.log(TAG, "$reason: layerId=${request.layerId} z=${request.z} x=${request.x} y=${request.y}")
    }

    /** Survey tiles are lossless WebP on newer phones (PNG on older ones, and in caches from before). */
    private fun imageType(bytes: ByteArray): String =
        if (bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) MIME_WEBP else MIME_PNG

    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no such tile")

    companion object {
        private const val TAG = "LocalTileServer"
        private const val LOOPBACK_HOST = "127.0.0.1"

        /** Tiles rendered at once across all layers: enough to keep the cores busy, few enough to fit in memory. */
        private val RENDER_SLOTS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        private const val MIME_PNG = "image/png"
        private const val MIME_WEBP = "image/webp"
        private const val MIME_PBF = "application/x-protobuf"
        private const val SOCKET_READ_TIMEOUT_MS = 5_000
        private const val MAX_LOGGED_MISSES = 30
        private const val MAX_LOGGED_TILE_ERRORS = 10

        /**
         * Starts listening on loopback on an OS-assigned free port (`port = 0`)
         * and returns only once actually bound, so [urlTemplateFor] is never
         * handed to MapLibre before the server can answer it.
         */
        fun start(): LocalTileServer {
            val server = LocalTileServer(0)
            server.start(SOCKET_READ_TIMEOUT_MS, false)
            DiagnosticLog.log(TAG, "started: port=${server.listeningPort}")
            return server
        }
    }
}

/** One parsed tile request: `/{kind}/{layerId}/{z}/{x}/{y}.png`. */
internal data class TileRequest(
    val kind: LocalTileServer.Kind,
    val layerId: String,
    val z: Int,
    val x: Int,
    val y: Int
)

/**
 * The tile request in a server path, or null. The file extension is
 * ignored: the real format comes from the layer itself. A plain function, so
 * it's unit tested without a server.
 */
internal fun parseTilePath(uri: String): TileRequest? {
    val parts = uri.trim('/').split('/')
    if (parts.size != 5) return null

    val kind = when (parts[0]) {
        "mbtiles" -> LocalTileServer.Kind.MBTILES
        "gdal" -> LocalTileServer.Kind.GDAL
        else -> return null
    }
    val layerId = parts[1].takeIf { it.isNotBlank() } ?: return null
    // 0..30 keeps `1 shl z` (the TMS row flip) from overflowing.
    val z = parts[2].toIntOrNull()?.takeIf { it in 0..30 } ?: return null
    val x = parts[3].toIntOrNull() ?: return null

    val yToken = parts[4]
    val extensionIndex = yToken.lastIndexOf('.')
    val yDigits = if (extensionIndex > 0) yToken.substring(0, extensionIndex) else yToken
    val y = yDigits.toIntOrNull() ?: return null

    return TileRequest(kind, layerId, z, x, y)
}
