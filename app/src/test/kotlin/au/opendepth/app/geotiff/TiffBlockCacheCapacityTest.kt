package au.opendepth.app.geotiff

import au.opendepth.app.geotiff.TiffBlockReader.Companion.CACHE_BUDGET_BYTES
import au.opendepth.app.geotiff.TiffBlockReader.Companion.MIN_CACHE_BLOCKS
import au.opendepth.app.geotiff.TiffBlockReader.Companion.blockCacheCapacity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiffBlockCacheCapacityTest {

    private val tile256Float32 = 256L * 256 * 4

    @Test
    fun `a wide tiled image caches a whole row of tiles so rows are not re-decoded`() {
        // 8,000 px wide in 256 px tiles = 32 across; a smaller cache would
        // evict each tile before the next row needs it.
        assertEquals(33, blockCacheCapacity(blocksPerRow = 32, blockBytes = tile256Float32))
    }

    @Test
    fun `small images keep the original eight-block floor`() {
        assertEquals(MIN_CACHE_BLOCKS, blockCacheCapacity(blocksPerRow = 1, blockBytes = 40_000))
    }

    @Test
    fun `memory stays within the budget however wide the image is`() {
        val capacity = blockCacheCapacity(blocksPerRow = 10_000, blockBytes = tile256Float32)
        assertTrue(capacity * tile256Float32 <= CACHE_BUDGET_BYTES)
        assertEquals((CACHE_BUDGET_BYTES / tile256Float32).toInt(), capacity)
    }

    @Test
    fun `worst-case block at the Kotlin-path cap still gets at least one slot within budget`() {
        val capacity = blockCacheCapacity(blocksPerRow = 1, blockBytes = GeoTiffImportRouter.MAX_KOTLIN_BLOCK_BYTES)
        assertEquals(4, capacity)
        assertTrue(capacity * GeoTiffImportRouter.MAX_KOTLIN_BLOCK_BYTES <= CACHE_BUDGET_BYTES)
    }

    @Test
    fun `a block bigger than the whole budget still gets one slot`() {
        assertEquals(1, blockCacheCapacity(blocksPerRow = 1, blockBytes = CACHE_BUDGET_BYTES * 2))
    }
}
