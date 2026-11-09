package au.opendepth.app.gdal

import au.opendepth.app.diagnostics.ResourceTracker
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.geotiff.DepthGrid
import au.opendepth.app.geotiff.DepthGridRenderer
import au.opendepth.app.geotiff.DepthHistogram
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.geotiff.GeoTiffReader
import au.opendepth.app.geotiff.MapProjection
import au.opendepth.app.model.DepthRampSettings
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.tiles.WebMercatorTiles
import org.gdal.gdal.Band
import org.gdal.gdal.Dataset
import org.gdal.gdalconst.gdalconstConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Semaphore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh

/**
 * A big GeoTIFF served through GDAL: tiles are drawn when
 * [au.opendepth.app.tiles.LocalTileServer] asks for them and cached, so
 * nothing is built up front and full detail is kept.
 *
 * Keeps one GDAL [Dataset] open until [close]. GDAL isn't safe for parallel
 * calls on one dataset, so native calls go through [lock]; cached tiles
 * don't need it. Native code, so not covered by the JVM tests.
 */
class GdalTileSource private constructor(
    private val dataset: Dataset,
    private val info: GdalDepthImporter.RasterInfo,
    private val cache: GdalTileCache,
    /**
     * Each colour tile's depths, as read from the file: a depth-range or
     * palette change re-colours from these instead of reading the (often
     * huge, compressed) survey again, which took minutes at high quality.
     */
    private val valuesCache: GdalTileCache,
    /** Opens another handle on the same file, for reading tiles in parallel; null = primary only. */
    private val openReader: (() -> Dataset)? = null
) : DepthSource, AutoCloseable {

    private val lock = Any()
    private val timing = TileTiming()

    /**
     * Extra handles on the file, one per parallel tile read. One GDAL
     * dataset can't be read from two threads at once, so with a single
     * handle every tile waited for the one before it; a few handles let
     * tiles decode on several cores (the approach tile servers like
     * TiTiler take). Opened on demand, closed with the source.
     */
    private class Reader(val dataset: Dataset, val band: Band)
    private val idleReaders = ConcurrentLinkedDeque<Reader>()
    private val allReaders = mutableListOf<Reader>()
    private val readerSlots = Semaphore(READERS)

    /**
     * Guards the colour tile cache ([cache], [cacheRamp], [colourCacheBypassed])
     * separately from [lock]: a colour change clears the cache, and that must
     * not wait behind a multi-second GDAL read of a huge survey (it froze the
     * colours panel). Lock order, where both are held: [lock] then this.
     */
    private val cacheLock = Any()

    /**
     * Depths of recently rendered tiles, so a colour change only repaints
     * them instead of reading the whole visible area from the file again.
     * Bounded by bytes, shared between every open survey; see [VALUE_CACHE_BYTES].
     */
    private val valueCache = LinkedHashMap<String, FloatArray>(32, 0.75f, true)

    private fun cachePut(key: String, array: FloatArray) = synchronized(valueCache) {
        valueCache.put(key, array)?.let { valueCacheBytes -= it.size * 4L }
        valueCacheBytes += array.size * 4L
        // Two or three big surveys at once mustn't each take the whole
        // budget: that ran the app out of memory.
        val budget = VALUE_CACHE_BYTES / openSources.get().coerceAtLeast(1)
        val oldest = valueCache.entries.iterator()
        while (valueCacheBytes > budget && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) break
            valueCacheBytes -= entry.value.size * 4L
            oldest.remove()
        }
    }

    /** Guarded by [valueCache]. */
    private var valueCacheBytes = 0L

    /**
     * Written under [lock]; volatile so the lock-free cache reads can bail out
     * once closed — a tile request can still hold this source after [close].
     */
    @Volatile
    private var closed = false

    /**
     * Guarded by [cacheLock]: the ramp every tile in [cache] was rendered with,
     * set by [clearCache]. Null until the first ramp change, when the cache
     * holds tiles from the layer's saved ramp, which is still current.
     */
    private var cacheRamp: DepthRampSettings? = null

    @Volatile
    private var cachedPreview: DepthGrid? = null

    init {
        openSources.incrementAndGet()
    }
    @Volatile
    private var cachedHistogram: DepthHistogram? = null

    /**
     * Edge length, in pixels, of the depth-colour tiles this source renders.
     * MapLibre shows each tile at 256dp whatever its pixel size, so on a 3x
     * screen a 256px tile stretches every pixel over 3x3 screen pixels;
     * bigger tiles carry more of the survey's real detail. Set from the
     * GeoTIFF quality setting (see [setColourTileSize]).
     */
    @Volatile
    var colourTileSize: Int = defaultColourTileSize
        private set

    override val bounds: GeoBounds get() = info.bounds
    override val shallowestMetres: Double = info.shallowestMetres
    override val deepestMetres: Double = info.deepestMetres
    override val hasData: Boolean get() = shallowestMetres.isFinite() && deepestMetres.isFinite()

    val projectionLabel: String get() = info.projection.label
    val nativeWidth: Int get() = info.width
    val nativeHeight: Int get() = info.height

    /** The zoom past which a screen pixel is smaller than a survey cell: zooming further shows no more detail. */
    val nativeMaxZoom: Int = run {
        val centerLatRadians = Math.toRadians((info.bounds.north + info.bounds.south) / 2.0)
        val geographic = info.projection is MapProjection.Geographic
        val pixelWidthMetres = abs(info.transform.pixelWidth) * if (geographic) DEGREE_METRES * cos(centerLatRadians) else 1.0
        val pixelHeightMetres = abs(info.transform.pixelHeight) * if (geographic) DEGREE_METRES else 1.0
        val sourceMetresPerPixel = min(pixelWidthMetres, pixelHeightMetres)
        val zoom = WebMercatorTiles.nativeMaxZoomForGroundResolution(sourceMetresPerPixel)
        DiagnosticLog.log(
            TAG,
            "native max zoom resolved: sourceMetresPerPixel=$sourceMetresPerPixel zoom=$zoom " +
                "(nativeWidth=$nativeWidth nativeHeight=$nativeHeight projection=$projectionLabel)"
        )
        zoom
    }

    /** PNG bytes for tile (z, x, y) — from cache if already generated, else rendered now and cached. Null if this tile does not overlap [bounds] at all. */
    fun renderTile(z: Int, x: Int, y: Int, ramp: DepthRampSettings): ByteArray? {
        if (!colourCacheBypassed) readCached(cache, z, x, y)?.let { return it }
        if (closed) return null

        val size = colourTileSize
        val tileBoundsWgs84 = WebMercatorTiles.tileBounds(z, x, y) ?: return null
        val window = tileWindowFor(z, x, y, size) ?: return null

        val t0 = System.nanoTime()
        val valueKey = "$size/$z/$x/$y"
        val values = synchronized(valueCache) { valueCache[valueKey] }
            ?: readStoredValues(z, x, y, size)?.also { cachePut(valueKey, it) }
            ?: (readTileValues(window, size) ?: return null).also {
                cachePut(valueKey, it)
                storeValues(z, x, y, size, it)
            }
        // A tile with no survey in it (at the edges): skip colouring and encoding.
        if (values.all { it.isNaN() }) return emptyPng(size)

        val grid = DepthGrid(
            bounds = tileBoundsWgs84,
            cols = size,
            rows = size,
            values = values,
            shallowestMetres = shallowestMetres,
            deepestMetres = deepestMetres,
            coveredCells = values.count { !it.isNaN() }
        )
        val t1 = System.nanoTime()
        // Shading depends only on the depths and its strength, so a colour
        // change reuses it and just repaints.
        val shadeKey = "$valueKey/shade/${ramp.hillshadeExaggeration}"
        val shade = synchronized(valueCache) { valueCache[shadeKey] }
            ?: DepthGridRenderer.shadeFor(grid, ramp)?.also { cachePut(shadeKey, it) }
        val bitmap = DepthGridRenderer.render(grid, ramp, shade = shade)
        val t2 = System.nanoTime()
        val png = encodePng(bitmap)
        bitmap.recycle()
        timing.add(t1 - t0, t2 - t1, System.nanoTime() - t2, size)

        synchronized(cacheLock) {
            // A render that started before the ramp last changed is still
            // served (its request URL carries the old ramp), but not cached:
            // the cache must only ever hold tiles in the current colours.
            // Likewise for a render that started before the tile size changed.
            if (!closed && !colourCacheBypassed && size == colourTileSize && (cacheRamp == null || cacheRamp == ramp)) {
                writeCached(cache, z, x, y, png)
            }
        }
        return png
    }

    /** The depth at [lat]/[lng] read straight from the file (the nearest cell), whatever the tiles show. */
    override fun sampleMetres(lat: Double, lng: Double): Double? {
        if (!bounds.contains(lat, lng)) return null
        val projected = info.projection.forward(lat, lng)
        val pixel = info.transform.toPixel(projected[0], projected[1])
        val col = pixel[0].toInt()
        val row = pixel[1].toInt()
        if (col !in 0 until nativeWidth || row !in 0 until nativeHeight) return null

        synchronized(lock) {
            if (closed) return null
            val buffer = FloatArray(1)
            val result = info.band.ReadRaster(col, row, 1, 1, 1, 1, gdalconstConstants.GDT_Float32, buffer)
            if (result != gdalconstConstants.CE_None) return null

            val raw = buffer[0]
            val noData = info.noData
            val isNoData = raw.isNaN() || raw.isInfinite() ||
                (noData != null && !noData.isNaN() && abs(raw - noData) <= abs(noData) * 1e-6 + 1e-6)
            if (isNoData) return null
            return (if (info.positiveDown) -raw else raw).toDouble()
        }
    }

    /**
     * Depths over [area] in one windowed GDAL read, at the survey's own
     * resolution or coarser (at most [maxDim] cells along the longer side) —
     * never finer, so the 3D view interpolates real cells instead of
     * repeating each one in steps. The returned grid's bounds are those of
     * the whole source pixels actually read. Null when [area] misses the
     * raster or the source is closed.
     */
    fun readArea(area: GeoBounds, maxDim: Int): DepthGrid? {
        var minCol = Double.POSITIVE_INFINITY
        var maxCol = Double.NEGATIVE_INFINITY
        var minRow = Double.POSITIVE_INFINITY
        var maxRow = Double.NEGATIVE_INFINITY
        for ((lat, lng) in listOf(area.north to area.west, area.north to area.east, area.south to area.west, area.south to area.east)) {
            val projected = info.projection.forward(lat, lng)
            val pixel = info.transform.toPixel(projected[0], projected[1])
            minCol = min(minCol, pixel[0]); maxCol = max(maxCol, pixel[0])
            minRow = min(minRow, pixel[1]); maxRow = max(maxRow, pixel[1])
        }
        val x0 = floor(minCol).toInt().coerceIn(0, nativeWidth - 1)
        val y0 = floor(minRow).toInt().coerceIn(0, nativeHeight - 1)
        val x1 = ceil(maxCol).toInt().coerceIn(x0 + 1, nativeWidth)
        val y1 = ceil(maxRow).toInt().coerceIn(y0 + 1, nativeHeight)
        val width = x1 - x0
        val height = y1 - y0
        if (width < 2 || height < 2) return null

        val scale = min(1.0, maxDim.toDouble() / max(width, height))
        val outWidth = max(2, (width * scale).roundToInt())
        val outHeight = max(2, (height * scale).roundToInt())
        val values = synchronized(lock) {
            if (closed) return null
            GdalDepthImporter.readDecimatedWindow(
                band = info.band,
                sourceXOffset = x0,
                sourceYOffset = y0,
                sourceXSize = width,
                sourceYSize = height,
                outWidth = outWidth,
                outHeight = outHeight,
                noData = info.noData,
                positiveDown = info.positiveDown
            )
        }

        // Geographic bounds of the pixel window that was read.
        var west = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        for ((col, row) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
            val projected = info.transform.toProjected(col.toDouble(), row.toDouble())
            val latLng = info.projection.inverse(projected[0], projected[1]) ?: return null
            south = min(south, latLng[0]); north = max(north, latLng[0])
            west = min(west, latLng[1]); east = max(east, latLng[1])
        }
        return DepthGrid(
            bounds = GeoBounds(west, south, east, north),
            cols = outWidth,
            rows = outHeight,
            values = values,
            shallowestMetres = shallowestMetres,
            deepestMetres = deepestMetres,
            coveredCells = values.count { !it.isNaN() }
        )
    }

    /** A capped-resolution overview — for the layer panel's auto-fit ramp and a cheap initial render, not the tiles MapLibre actually displays. */
    /**
     * GDAL's approximate histogram (it reads overviews, or skips through the
     * file), so even a survey of several hundred MB answers in a second or two.
     */
    override fun histogram(): DepthHistogram? {
        cachedHistogram?.let { return it }
        val lo = info.rawMin
        val hi = info.rawMax
        if (!lo.isFinite() || !hi.isFinite() || hi <= lo) return null
        val buckets = IntArray(DepthHistogram.BINS)
        val ok = synchronized(lock) {
            if (closed) return null
            runCatching { info.band.GetHistogram(lo, hi, buckets, false, true) == gdalconstConstants.CE_None }
                .onFailure { DiagnosticLog.logError(TAG, "histogram FAILED", it) }
                .getOrDefault(false)
        }
        if (!ok) return null
        // Depths are negative down; a positive-down survey's bins run the other way.
        val counts = LongArray(buckets.size) { i -> buckets[if (info.positiveDown) buckets.size - 1 - i else i].toLong() }
        val histogram = if (info.positiveDown) DepthHistogram(-hi, -lo, counts) else DepthHistogram(lo, hi, counts)
        return histogram.takeUnless { it.isEmpty }?.also { cachedHistogram = it }
    }

    override fun previewGrid(): DepthGrid {
        cachedPreview?.let { return it }
        synchronized(lock) {
            cachedPreview?.let { return it }
            check(!closed) { "GDAL tile source is closed" }

            val longestEdge = max(nativeWidth, nativeHeight)
            val scale = if (longestEdge <= GeoTiffReader.DEFAULT_MAX_DIMENSION) {
                1.0
            } else {
                GeoTiffReader.DEFAULT_MAX_DIMENSION.toDouble() / longestEdge
            }
            val cols = (nativeWidth * scale).toInt().coerceAtLeast(1)
            val rows = (nativeHeight * scale).toInt().coerceAtLeast(1)

            val values = GdalDepthImporter.readDecimatedWindow(
                band = info.band,
                sourceXOffset = 0,
                sourceYOffset = 0,
                sourceXSize = nativeWidth,
                sourceYSize = nativeHeight,
                outWidth = cols,
                outHeight = rows,
                noData = info.noData,
                positiveDown = info.positiveDown
            )
            val grid = DepthGrid(
                bounds = bounds,
                cols = cols,
                rows = rows,
                values = values,
                shallowestMetres = shallowestMetres,
                deepestMetres = deepestMetres,
                coveredCells = values.count { !it.isNaN() }
            )
            cachedPreview = grid
            return grid
        }
    }

    /**
     * Drops every cached colour tile; from now on only tiles rendered with
     * [ramp] are cached.
     */
    fun clearCache(ramp: DepthRampSettings) {
        synchronized(cacheLock) {
            if (closed) return
            cacheRamp = ramp
            try {
                cache.clear()
            } catch (error: Exception) {
                // A full disk here must not crash the app. The stale-colour tiles left
                // behind are never served again this session.
                colourCacheBypassed = true
                DiagnosticLog.logError(TAG, "colour tile cache clear FAILED; bypassing it this session", error)
            }
        }
    }

    /**
     * Switches the depth-colour tile size and drops every cached colour tile,
     * which were rendered at the old size. The caller re-attaches the layer
     * with a URL carrying the new size, so MapLibre refetches as well.
     */
    fun setColourTileSize(size: Int) {
        synchronized(cacheLock) {
            if (size == colourTileSize) return
            colourTileSize = size
            try {
                cache.clear()
                cache.setContentKey(colourCacheKey(size))
                valuesCache.clear()
                valuesCache.setContentKey(valuesCacheKey(size))
            } catch (error: Exception) {
                colourCacheBypassed = true
                DiagnosticLog.logError(TAG, "colour tile cache clear FAILED after size change; bypassing it this session", error)
            }
        }
    }

    /** A tile's depths from [valuesCache], if they were stored at this tile size. */
    private fun readStoredValues(z: Int, x: Int, y: Int, size: Int): FloatArray? {
        if (size != colourTileSize) return null
        val bytes = runCatching { readCached(valuesCache, z, x, y) }.getOrNull() ?: return null
        return TileValueCodec.decode(bytes, size * size)
    }

    private fun storeValues(z: Int, x: Int, y: Int, size: Int, values: FloatArray) {
        val bytes = TileValueCodec.encode(values)
        synchronized(cacheLock) {
            if (!closed && size == colourTileSize) writeCached(valuesCache, z, x, y, bytes)
        }
    }

    /** Set once [clearCache] fails: [cache] may hold tiles in old colours, so it is neither read nor written. */
    @Volatile
    private var colourCacheBypassed = false

    /**
     * Cache hits stay off [lock] so they never wait behind a slow GDAL read;
     * a read that loses the race with [close] just counts as a miss.
     */
    private fun readCached(store: GdalTileCache, z: Int, x: Int, y: Int): ByteArray? {
        if (closed) return null
        return try {
            store.readTile(z, x, y)
        } catch (error: IllegalStateException) {
            if (closed) null else throw error
        }
    }

    /**
     * Caching is best-effort: a tile that rendered fine is still served when
     * the write fails (a full disk — SQLiteFullException — or a cache file
     * deleted underneath it). Logged once per source so a full disk does not
     * also flood the log. Called under [lock].
     */
    private fun writeCached(store: GdalTileCache, z: Int, x: Int, y: Int, png: ByteArray) {
        try {
            store.writeTile(z, x, y, png)
        } catch (error: Exception) {
            if (!cacheWriteFailureLogged) {
                cacheWriteFailureLogged = true
                DiagnosticLog.logError(TAG, "tile cache write FAILED (tiles still served, not cached)", error)
            }
        }
    }

    /** Guarded by [cacheLock] (colour tiles) or [lock] (elevation tiles); only ever flips to true. */
    @Volatile
    private var cacheWriteFailureLogged = false

    /** Null if the source was closed mid-request. */
    private fun readTileValues(window: TileWindow, tileSize: Int): FloatArray? {
        val values = FloatArray(tileSize * tileSize) { DepthGrid.NO_DATA }
        readerSlots.acquire()
        try {
            val reader = idleReaders.pollFirst() ?: newReader()
            if (reader != null) {
                try {
                    synchronized(reader) {
                        if (closed) return null
                        sample(read(reader.band, window), window, values, tileSize)
                    }
                } finally {
                    idleReaders.offerFirst(reader)
                }
                return values
            }
        } finally {
            readerSlots.release()
        }
        // No extra handle could be opened: read through the main one.
        synchronized(lock) {
            if (closed) return null
            sample(read(info.band, window), window, values, tileSize)
        }
        return values
    }

    private fun newReader(): Reader? {
        val open = openReader ?: return null
        synchronized(allReaders) {
            if (closed || allReaders.size >= READERS) return null
            return runCatching {
                val dataset = open()
                val band = dataset.GetRasterBand(1) ?: run {
                    dataset.delete()
                    error("no band")
                }
                Reader(dataset, band)
            }.onFailure { DiagnosticLog.logError(TAG, "extra tile reader FAILED; reading through the main handle", it) }
                .getOrNull()
                ?.also { allReaders += it }
        }
    }

    private fun read(band: Band, window: TileWindow): FloatArray =
        GdalDepthImporter.readDecimatedWindow(
            band = band,
            sourceXOffset = window.sourceX,
            sourceYOffset = window.sourceY,
            sourceXSize = window.sourceWidth,
            sourceYSize = window.sourceHeight,
            outWidth = window.readWidth,
            outHeight = window.readHeight,
            noData = info.noData,
            positiveDown = info.positiveDown
        )

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            openSources.decrementAndGet()
            // Under both locks, so no in-flight tile write can race these closes.
            // Each independently: a failing cache close must not skip the
            // native dataset release.
            synchronized(cacheLock) {
                runCatching { cache.close() }.onFailure { DiagnosticLog.logError(TAG, "colour cache close FAILED", it) }
            }
            synchronized(valueCache) {
                valueCache.clear()
                valueCacheBytes = 0
            }
            synchronized(cacheLock) {
                runCatching { valuesCache.close() }.onFailure { DiagnosticLog.logError(TAG, "values cache close FAILED", it) }
            }
            dataset.delete()
            ResourceTracker.gdalDatasetClosed()
            // Each reader is closed under its own lock, so a read in progress finishes first.
            synchronized(allReaders) {
                allReaders.forEach { reader ->
                    synchronized(reader) {
                        runCatching { reader.dataset.delete() }
                        ResourceTracker.gdalDatasetClosed()
                    }
                }
                allReaders.clear()
                idleReaders.clear()
            }
        }
    }

    // Tile <-> native pixel mapping -------------------------------------

    /**
     * The part of the raster a tile needs ([sourceX]..+[sourceWidth] native
     * pixels, read at [readWidth] x [readHeight]) and, every [SAMPLE_STEP]
     * output pixels, the exact native pixel each tile position falls on.
     */
    private class TileWindow(
        val sourceX: Int,
        val sourceY: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val readWidth: Int,
        val readHeight: Int,
        /** Grid nodes per side. */
        val nodes: Int,
        val nodeCol: DoubleArray,
        val nodeRow: DoubleArray
    )

    /**
     * Where tile [z]/[x]/[y] lies in the raster. A projected survey (UTM)
     * is turned a little against the web map's grid, and Mercator latitude
     * isn't linear, so each tile position is projected on its own (exactly
     * at grid nodes [SAMPLE_STEP] pixels apart, in between by interpolation)
     * rather than reading one straight rectangle: a rectangle skews every
     * tile slightly, and neighbouring tiles then don't meet.
     */
    private fun tileWindowFor(z: Int, x: Int, y: Int, tilePixels: Int): TileWindow? {
        val tiles = (1L shl z).toDouble()
        val steps = ceil(tilePixels.toDouble() / SAMPLE_STEP).toInt()
        val nodes = steps + 1
        val nodeCol = DoubleArray(nodes * nodes)
        val nodeRow = DoubleArray(nodes * nodes)
        var minCol = Double.POSITIVE_INFINITY
        var maxCol = Double.NEGATIVE_INFINITY
        var minRow = Double.POSITIVE_INFINITY
        var maxRow = Double.NEGATIVE_INFINITY
        for (j in 0 until nodes) {
            val py = min(j * SAMPLE_STEP, tilePixels).toDouble() / tilePixels
            // Web Mercator: y runs 0 (north edge of the world) to 1 (south).
            val mercator = PI * (1 - 2 * (y + py) / tiles)
            val lat = Math.toDegrees(atan(sinh(mercator)))
            for (i in 0 until nodes) {
                val px = min(i * SAMPLE_STEP, tilePixels).toDouble() / tilePixels
                val lng = (x + px) / tiles * 360.0 - 180.0
                val projected = info.projection.forward(lat, lng)
                val pixel = info.transform.toPixel(projected[0], projected[1])
                if (!pixel[0].isFinite() || !pixel[1].isFinite()) return null
                val k = j * nodes + i
                nodeCol[k] = pixel[0]
                nodeRow[k] = pixel[1]
                minCol = min(minCol, pixel[0]); maxCol = max(maxCol, pixel[0])
                minRow = min(minRow, pixel[1]); maxRow = max(maxRow, pixel[1])
            }
        }
        // Entirely outside the raster -> no tile here, let the caller 404.
        if (maxCol <= 0.0 || minCol >= nativeWidth || maxRow <= 0.0 || minRow >= nativeHeight) return null

        val sourceX = floor(minCol).toInt().coerceIn(0, nativeWidth - 1)
        val sourceY = floor(minRow).toInt().coerceIn(0, nativeHeight - 1)
        val sourceEndX = ceil(maxCol).toInt().coerceIn(sourceX + 1, nativeWidth)
        val sourceEndY = ceil(maxRow).toInt().coerceIn(sourceY + 1, nativeHeight)
        val sourceWidth = sourceEndX - sourceX
        val sourceHeight = sourceEndY - sourceY
        // Full resolution when zoomed in; when zoomed out, about one read
        // value per tile pixel (GDAL picks them, using overviews if it has them).
        val colsPerPixel = (maxCol - minCol) / tilePixels
        val rowsPerPixel = (maxRow - minRow) / tilePixels
        val readWidth = if (colsPerPixel <= 1.0) sourceWidth else ceil(sourceWidth / colsPerPixel).toInt().coerceIn(1, sourceWidth)
        val readHeight = if (rowsPerPixel <= 1.0) sourceHeight else ceil(sourceHeight / rowsPerPixel).toInt().coerceIn(1, sourceHeight)
        return TileWindow(sourceX, sourceY, sourceWidth, sourceHeight, readWidth, readHeight, nodes, nodeCol, nodeRow)
    }

    /** Fills [canvas] (a [tileSize]-square tile) from [decoded], the window as read. */
    private fun sample(decoded: FloatArray, window: TileWindow, canvas: FloatArray, tileSize: Int) {
        val n = window.nodes
        val scaleX = window.readWidth.toDouble() / window.sourceWidth
        val scaleY = window.readHeight.toDouble() / window.sourceHeight
        for (row in 0 until tileSize) {
            val fy = (row + 0.5) / SAMPLE_STEP
            val j = floor(fy).toInt().coerceIn(0, n - 2)
            val ty = fy - j
            for (col in 0 until tileSize) {
                val fx = (col + 0.5) / SAMPLE_STEP
                val i = floor(fx).toInt().coerceIn(0, n - 2)
                val tx = fx - i
                val a = j * n + i
                val b = a + 1
                val c = a + n
                val d = c + 1
                val nativeCol = (window.nodeCol[a] * (1 - tx) + window.nodeCol[b] * tx) * (1 - ty) +
                    (window.nodeCol[c] * (1 - tx) + window.nodeCol[d] * tx) * ty
                val nativeRow = (window.nodeRow[a] * (1 - tx) + window.nodeRow[b] * tx) * (1 - ty) +
                    (window.nodeRow[c] * (1 - tx) + window.nodeRow[d] * tx) * ty
                if (nativeCol < 0.0 || nativeRow < 0.0 || nativeCol >= nativeWidth || nativeRow >= nativeHeight) continue
                val readCol = floor((nativeCol - window.sourceX) * scaleX).toInt()
                val readRow = floor((nativeRow - window.sourceY) * scaleY).toInt()
                if (readCol !in 0 until window.readWidth || readRow !in 0 until window.readHeight) continue
                canvas[row * tileSize + col] = decoded[readRow * window.readWidth + readCol]
            }
        }
    }

    /** A fully transparent tile of [size], encoded once. */
    private fun emptyPng(size: Int): ByteArray = synchronized(emptyTiles) {
        emptyTiles.getOrPut(size) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            encodePng(bitmap).also { bitmap.recycle() }
        }
    }

    private val emptyTiles = mutableMapOf<Int, ByteArray>()

    /**
     * Lossless WebP where the phone has it (Android 11+): the same pixels as
     * PNG, but several times quicker to make (measured 2–13 ms a tile against
     * PNG's 4–69 ms), which is most of the wait for a new tile after a colour
     * change. PNG on older phones. Either is read back the same by the map.
     */
    private fun encodePng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, WEBP_EFFORT, stream)
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return stream.toByteArray()
    }

    companion object {
        private const val TAG = "GdalTileSource"

        /** Rough metres-per-degree at the equator, scaled by cos(latitude) for longitude — plenty accurate for a max-zoom estimate. */
        private const val DEGREE_METRES = 111_320.0

        /**
         * Bumped whenever tile rendering changes (2 = relief shading baked
         * in, 3 = depths past the range take the end colours, 4 = hidden or
         * end colours as the ramp says), so caches
         * written by an older version are emptied on open. Also in the tile
         * URLs, so MapLibre's own cache doesn't serve old tiles either.
         */
        const val RENDER_VERSION = 5

        /** Lossless WebP effort, 0–100: quality is the same at any; low is quick and still compact. */
        private const val WEBP_EFFORT = 25

        /** Tile pixels between exactly projected points; in between is interpolated (well under a pixel off). */
        private const val SAMPLE_STEP = 16

        /** Parallel tile reads per layer: a core or two left for the map and the UI. */
        private val READERS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 3)

        /**
         * Memory for [valueCache]: about a screenful of tiles at the largest
         * tile size (768 px = 2.4 MB of depths each), less on small heaps.
         */
        /** Surveys open now, sharing [VALUE_CACHE_BYTES]. */
        private val openSources = java.util.concurrent.atomic.AtomicInteger()

        private val VALUE_CACHE_BYTES: Long = min(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024)

        /** What a colour-tile cache holds: renderer version and tile size. */
        fun colourCacheKey(tilePixels: Int): Int = RENDER_VERSION * 10_000 + tilePixels

        /** What a values cache holds: its encoding and tile size (not the colours). */
        fun valuesCacheKey(tilePixels: Int): Int = TileValueCodec.VERSION * 10_000 + tilePixels

        /** Tile size new sources start with; set from the GeoTIFF quality setting at startup. */
        @Volatile
        var defaultColourTileSize: Int = WebMercatorTiles.TILE_SIZE

        /**
         * Opens a GDAL [Dataset] and resolves its raster info once, up
         * front — fast (no tile pixels are read), unlike the previous
         * eager pyramid build this replaces for new imports.
         */
        fun open(context: Context, sourcePath: String, cacheFile: File): GdalTileSource {
            val dataset = GdalDepthImporter.openDataset(context, sourcePath)
            var cache: GdalTileCache? = null
            return try {
                val statsKey = statsKeyFor(sourcePath)
                val stats = context.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE)
                val known = statsKey?.let { key ->
                    stats.getString(key, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
                        ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }
                val info = GdalDepthImporter.resolveRasterInfo(dataset, known)
                if (known == null && statsKey != null && info.rawMin.isFinite() && info.rawMax.isFinite()) {
                    stats.edit().putString(statsKey, "${info.rawMin},${info.rawMax}").apply()
                }
                cache = GdalTileCache.open(cacheFile, colourCacheKey(defaultColourTileSize))
                deleteOldElevationCache(cacheFile)
                val valuesCache = GdalTileCache.open(valuesCacheFileFor(cacheFile), valuesCacheKey(defaultColourTileSize))
                GdalTileSource(dataset, info, cache, valuesCache) { GdalDepthImporter.openDataset(context, sourcePath) }
            } catch (error: Throwable) {
                // The colour cache, opened first, isn't closed by a later failure on its own.
                cache?.close()
                dataset.delete()
                ResourceTracker.gdalDatasetClosed()
                throw error
            }
        }

        private const val STATS_PREFS = "gdal_band_stats"

        /** Identifies one exact version of a file: path, size and modified time. */
        private fun statsKeyFor(path: String): String? {
            val file = File(path)
            if (!file.isFile) return null
            return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        }

        /**
         * Older builds also cached elevation tiles for a MapLibre
         * hillshade layer, next to [cacheFile] as `.dem`. The shading has
         * been baked into the colour tiles since, so that file is only space.
         */
        private fun deleteOldElevationCache(cacheFile: File) {
            listOf("", "-journal", "-wal", "-shm").forEach { File(cacheFile.parentFile, cacheFile.name + ".dem" + it).delete() }
        }

        /** The stored tile depths, likewise next to [cacheFile]. */
        fun valuesCacheFileFor(cacheFile: File): File = File(cacheFile.parentFile, cacheFile.name + ".values")
    }
}

/** Average time per rendered tile (read, colour, encode), logged every [EVERY] tiles. */
internal class TileTiming {
    private var count = 0
    private var readNs = 0L
    private var renderNs = 0L
    private var encodeNs = 0L

    @Synchronized
    fun add(read: Long, render: Long, encode: Long, size: Int) {
        count++
        readNs += read; renderNs += render; encodeNs += encode
        if (count == EVERY) {
            DiagnosticLog.log(
                "GdalTileSource",
                "tile timing ${size}px avg over $EVERY: read=${readNs / count / 1_000_000}ms " +
                    "colour=${renderNs / count / 1_000_000}ms encode=${encodeNs / count / 1_000_000}ms"
            )
            count = 0; readNs = 0; renderNs = 0; encodeNs = 0
        }
    }

    private companion object {
        const val EVERY = 40
    }
}
