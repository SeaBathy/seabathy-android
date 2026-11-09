package au.opendepth.app.geotiff

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * Zoom and tile maths for [TiledDepthPyramid]: zoom levels from the file's
 * own resolution at [maxZoom] down to 0, each split into
 * [tileSize] x [tileSize] depth tiles. No I/O, so unit tested.
 */
class PyramidGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val tileSize: Int = DEFAULT_TILE_SIZE
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(tileSize > 0) { "Tile size must be positive" }
    }

    /** The zoom level whose tiles hold the source raster at native resolution. */
    val maxZoom: Int = run {
        val longestEdge = max(sourceWidth, sourceHeight).toDouble()
        if (longestEdge <= tileSize) {
            0
        } else {
            ceil(ln(longestEdge / tileSize) / ln(2.0)).toInt().coerceAtLeast(0)
        }
    }

    /** This level's full width in samples; exactly [sourceWidth] at [maxZoom]. */
    fun levelWidth(zoom: Int): Int = scaledDimension(sourceWidth, zoom)

    /** This level's full height in samples; exactly [sourceHeight] at [maxZoom]. */
    fun levelHeight(zoom: Int): Int = scaledDimension(sourceHeight, zoom)

    fun tilesAcross(zoom: Int): Int = ceilDiv(levelWidth(zoom), tileSize)

    fun tilesDown(zoom: Int): Int = ceilDiv(levelHeight(zoom), tileSize)

    /** Which tile a level-space sample column/row falls in, and its offset within that tile. */
    fun tileFor(zoom: Int, levelCol: Int, levelRow: Int): TileAddress = TileAddress(
        zoom = zoom,
        tileCol = levelCol / tileSize,
        tileRow = levelRow / tileSize,
        localCol = levelCol % tileSize,
        localRow = levelRow % tileSize
    )

    /** A tile's width and height: [tileSize], or less at a level's right and bottom edges (tiles aren't padded). */
    fun tilePixelSize(zoom: Int, tileCol: Int, tileRow: Int): Pair<Int, Int> {
        val width = (levelWidth(zoom) - tileCol * tileSize).coerceIn(0, tileSize)
        val height = (levelHeight(zoom) - tileRow * tileSize).coerceIn(0, tileSize)
        return width to height
    }

    /** The coarsest zoom with at least [minDimension] cells along its longer side, or [maxZoom] for a smaller source. */
    fun previewZoom(minDimension: Int): Int {
        for (zoom in 0..maxZoom) {
            if (max(levelWidth(zoom), levelHeight(zoom)) >= minDimension) return zoom
        }
        return maxZoom
    }

    private fun scaledDimension(sourceDimension: Int, zoom: Int): Int {
        val shift = (maxZoom - zoom).coerceAtLeast(0)
        if (shift == 0) return sourceDimension
        val scale = 1L shl shift
        return ceilDiv(sourceDimension.toLong(), scale).toInt().coerceAtLeast(1)
    }

    private fun ceilDiv(numerator: Int, denominator: Int): Int =
        ((numerator.toLong() + denominator - 1) / denominator).toInt()

    private fun ceilDiv(numerator: Long, denominator: Long): Long =
        (numerator + denominator - 1) / denominator

    companion object {
        const val DEFAULT_TILE_SIZE = 256
    }
}

/** One tile's coordinates, plus a sample's offset within it. */
data class TileAddress(
    val zoom: Int,
    val tileCol: Int,
    val tileRow: Int,
    val localCol: Int,
    val localRow: Int
)
