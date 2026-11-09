package au.opendepth.app.download

import au.opendepth.app.model.GeoBounds
import au.opendepth.app.tiles.WebMercatorTiles
import java.util.Locale

/**
 * How many tiles an area and zoom range needs, and roughly how many bytes.
 * Tile sizes vary a lot (open water is tiny, coastline is not), so the size
 * is only an estimate.
 */
object OfflineDownloadEstimator {

    /**
     * A typical 256x256 JPEG map/satellite tile is roughly in this
     * ballpark; a PNG source (common for non-photographic basemaps) often
     * runs larger. Tune freely — this is the only place the number lives.
     */
    const val ASSUMED_AVERAGE_TILE_BYTES = 25_000L

    class TileEstimate(val tileCount: Long, val estimatedBytes: Long)

    /** Sums [WebMercatorTiles.tileRangeCovering]'s tile count across every zoom in [zoomRange], not just one level. */
    fun estimateTiles(
        bounds: GeoBounds,
        zoomRange: IntRange,
        averageTileBytes: Long = ASSUMED_AVERAGE_TILE_BYTES
    ): TileEstimate {
        val tileCount = zoomRange.sumOf { z -> WebMercatorTiles.tileRangeCovering(bounds, z).tileCount }
        return TileEstimate(tileCount, tileCount * averageTileBytes)
    }

    /** Every (z, x, y) tile in [zoomRange]: the one list both the size check and the download use. */
    fun tilesToFetch(bounds: GeoBounds, zoomRange: IntRange): List<Triple<Int, Int, Int>> =
        zoomRange.flatMap { z ->
            val range = WebMercatorTiles.tileRangeCovering(bounds, z)
            (range.minX..range.maxX).flatMap { x -> (range.minY..range.maxY).map { y -> Triple(z, x, y) } }
        }

    /** "340 MB" / "1.2 GB" style formatting for the confirmation prompt. */
    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes bytes"
        }
    }

    /** "4,096" style thousands-grouped formatting for a tile count. */
    fun formatTileCount(count: Long): String = String.format(Locale.US, "%,d", count)
}
