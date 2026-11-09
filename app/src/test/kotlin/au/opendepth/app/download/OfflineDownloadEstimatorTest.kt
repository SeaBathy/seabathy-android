package au.opendepth.app.download

import au.opendepth.app.model.GeoBounds
import au.opendepth.app.tiles.WebMercatorTiles
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDownloadEstimatorTest {

    @Test
    fun `a single-zoom range sums to that zoom's own tile count`() {
        val bounds = WebMercatorTiles.tileBounds(4, 3, 5)!!

        val estimate = OfflineDownloadEstimator.estimateTiles(bounds, 4..4)

        assertEquals(1L, estimate.tileCount)
    }

    @Test
    fun `a multi-zoom range sums tile counts across every zoom, not just the last one`() {
        val bounds = GeoBounds(west = -1.0, south = -1.0, east = 1.0, north = 1.0)

        val perZoomCounts = (3..5).map { z -> WebMercatorTiles.tileRangeCovering(bounds, z).tileCount }
        val estimate = OfflineDownloadEstimator.estimateTiles(bounds, 3..5)

        assertEquals(perZoomCounts.sum(), estimate.tileCount)
    }

    @Test
    fun `estimated bytes is tile count times the assumed average tile size`() {
        val bounds = WebMercatorTiles.tileBounds(4, 3, 5)!!

        val estimate = OfflineDownloadEstimator.estimateTiles(bounds, 4..4, averageTileBytes = 30_000L)

        assertEquals(30_000L, estimate.estimatedBytes)
    }

    @Test
    fun `formatBytes renders GB, MB, KB and bytes appropriately`() {
        assertEquals("1.5 GB", OfflineDownloadEstimator.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("340 MB", OfflineDownloadEstimator.formatBytes(340L * 1024 * 1024))
        assertEquals("12 KB", OfflineDownloadEstimator.formatBytes(12L * 1024))
        assertEquals("500 bytes", OfflineDownloadEstimator.formatBytes(500L))
    }

    @Test
    fun `formatTileCount groups thousands`() {
        assertEquals("4,096", OfflineDownloadEstimator.formatTileCount(4096L))
        assertEquals("12", OfflineDownloadEstimator.formatTileCount(12L))
    }

    // tilesToFetch is the exact list
    // TileDownloadRunner's real download loop iterates, so proving every
    // requested zoom level is present here proves the real download
    // enumerates all of them too, not just one.

    @Test
    fun `tilesToFetch includes every zoom level in the range, not just one`() {
        val bounds = GeoBounds(west = 151.20, south = -33.87, east = 151.21, north = -33.86)
        val zoomRange = 10..14

        val tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange)

        assertEquals(zoomRange.toSet(), tiles.map { it.first }.toSet())
    }

    @Test
    fun `tilesToFetch's per-zoom tile count matches tileRangeCovering's own count for every zoom`() {
        val bounds = GeoBounds(west = -1.0, south = -1.0, east = 1.0, north = 1.0)
        val zoomRange = 3..7

        val tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange)

        for (z in zoomRange) {
            val expected = WebMercatorTiles.tileRangeCovering(bounds, z).tileCount
            assertEquals("zoom $z", expected, tiles.count { it.first == z }.toLong())
        }
    }

    @Test
    fun `tilesToFetch's total size matches estimateTiles' own tile count`() {
        val bounds = GeoBounds(west = -1.0, south = -1.0, east = 1.0, north = 1.0)
        val zoomRange = 3..6

        val tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange)
        val estimate = OfflineDownloadEstimator.estimateTiles(bounds, zoomRange)

        assertEquals(estimate.tileCount, tiles.size.toLong())
    }

    @Test
    fun `tilesToFetch contains no duplicate (z, x, y) triples`() {
        val bounds = GeoBounds(west = -1.0, south = -1.0, east = 1.0, north = 1.0)

        val tiles = OfflineDownloadEstimator.tilesToFetch(bounds, 3..6)

        assertEquals(tiles.size, tiles.toSet().size)
    }
}
