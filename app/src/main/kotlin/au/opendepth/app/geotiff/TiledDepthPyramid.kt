package au.opendepth.app.geotiff

import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.model.GeoBounds
import java.io.File
import kotlin.math.floor

/**
 * Depths from a tile pyramid on disk ([TiledDepthPyramidStore]), for a
 * GeoTIFF too big to hold in memory: [sampleMetres] reads one finest tile,
 * [previewGrid] one coarse level.
 */
class TiledDepthPyramid private constructor(
    private val store: TiledDepthPyramidStore
) : DepthSource, AutoCloseable {

    private val geometry get() = store.geometry

    // A handful of recently-touched finest-zoom tiles, so panning the map or
    // repeated taps near one spot do not re-hit SQLite for every sample —
    // the same role TiffBlockReader's own tile cache plays for the Kotlin
    // decode path.
    private val tileCache = object : LinkedHashMap<TileKey, FloatArray?>(TILE_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, FloatArray?>): Boolean =
            size > TILE_CACHE_CAPACITY
    }

    @Volatile
    private var cachedPreview: DepthGrid? = null

    override val bounds: GeoBounds get() = store.bounds
    override val shallowestMetres: Double get() = store.shallowestMetres
    override val deepestMetres: Double get() = store.deepestMetres
    override val hasData: Boolean get() = shallowestMetres.isFinite() && deepestMetres.isFinite()

    override fun sampleMetres(lat: Double, lng: Double): Double? {
        if (!bounds.contains(lat, lng)) return null
        val zoom = geometry.maxZoom
        val width = geometry.levelWidth(zoom)
        val height = geometry.levelHeight(zoom)

        val lngSpan = bounds.east - bounds.west
        val latSpan = bounds.north - bounds.south
        if (lngSpan <= 0.0 || latSpan <= 0.0) return null

        val col = floor((lng - bounds.west) / lngSpan * width).toInt().coerceIn(0, width - 1)
        val row = floor((bounds.north - lat) / latSpan * height).toInt().coerceIn(0, height - 1)

        val address = geometry.tileFor(zoom, col, row)
        val tile = tileFor(address) ?: return null
        val (tileWidth, _) = geometry.tilePixelSize(address.zoom, address.tileCol, address.tileRow)
        if (address.localCol >= tileWidth) return null

        val value = tile[address.localRow * tileWidth + address.localCol]
        return if (value.isNaN()) null else value.toDouble()
    }

    override fun previewGrid(): DepthGrid {
        cachedPreview?.let { return it }

        val zoom = geometry.previewZoom(PREVIEW_MIN_DIMENSION)
        val cols = geometry.levelWidth(zoom)
        val rows = geometry.levelHeight(zoom)
        val values = FloatArray(cols * rows) { DepthGrid.NO_DATA }
        var covered = 0

        DiagnosticLog.log(TAG, "assembling preview grid: zoom=$zoom cols=$cols rows=$rows")
        val start = System.currentTimeMillis()

        for (tileRow in 0 until geometry.tilesDown(zoom)) {
            for (tileCol in 0 until geometry.tilesAcross(zoom)) {
                val (tileWidth, tileHeight) = geometry.tilePixelSize(zoom, tileCol, tileRow)
                if (tileWidth <= 0 || tileHeight <= 0) continue
                val tile = tileFor(TileKey(zoom, tileCol, tileRow)) ?: continue

                val originCol = tileCol * geometry.tileSize
                val originRow = tileRow * geometry.tileSize
                for (localRow in 0 until tileHeight) {
                    val destRowOffset = (originRow + localRow) * cols + originCol
                    val srcRowOffset = localRow * tileWidth
                    for (localCol in 0 until tileWidth) {
                        val value = tile[srcRowOffset + localCol]
                        values[destRowOffset + localCol] = value
                        if (!value.isNaN()) covered++
                    }
                }
            }
        }

        DiagnosticLog.log(
            TAG,
            "preview grid assembled: elapsedMs=${System.currentTimeMillis() - start} coveredCells=$covered"
        )

        val grid = DepthGrid(
            bounds = bounds,
            cols = cols,
            rows = rows,
            values = values,
            shallowestMetres = shallowestMetres,
            deepestMetres = deepestMetres,
            coveredCells = covered
        )
        cachedPreview = grid
        return grid
    }

    override fun close() {
        store.close()
    }

    private fun tileFor(address: TileAddress): FloatArray? =
        tileFor(TileKey(address.zoom, address.tileCol, address.tileRow))

    // Synchronized: the depth readout samples on an IO thread while the
    // main thread may be assembling the preview; LinkedHashMap in access
    // order is mutated even by get().
    private fun tileFor(key: TileKey): FloatArray? = synchronized(tileCache) {
        tileCache.getOrPut(key) { store.readTile(key.zoom, key.tileCol, key.tileRow) }
    }

    private data class TileKey(val zoom: Int, val tileCol: Int, val tileRow: Int)

    companion object {
        private const val TAG = "TiledDepthPyramid"
        private const val TILE_CACHE_CAPACITY = 16

        /**
         * Matches [GeoTiffReader.DEFAULT_MAX_DIMENSION] — a preview at this
         * resolution costs about the same to render and hold as the
         * in-memory path's own decoded grid already does.
         */
        private const val PREVIEW_MIN_DIMENSION = GeoTiffReader.DEFAULT_MAX_DIMENSION

        fun open(file: File): TiledDepthPyramid = TiledDepthPyramid(TiledDepthPyramidStore.open(file))

        /** Whether [file] is a pyramid this app generated, by extension alone — no bytes read. */
        fun isPyramidFile(file: File): Boolean =
            file.extension.equals(TiledDepthPyramidStore.EXTENSION, ignoreCase = true)
    }
}
