package au.opendepth.app.gdal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCacheBudgetTest {

    /** In-memory stand-in: rows of fixed size, oldest first. */
    private class FakeCache(override val name: String, rows: Int, val rowBytes: Long) : TileCacheBudget.Evictable {
        var rows = rows
        var evictCalls = 0
        override fun liveBytes(): Long = rows * rowBytes
        override fun evictOldest(rows: Int): Int {
            evictCalls++
            val removed = minOf(rows, this.rows)
            this.rows -= removed
            return removed
        }
    }

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `budget is ten percent of free space`() {
        assertEquals(500 * mb, TileCacheBudget.budgetBytes(5_000 * mb))
    }

    @Test
    fun `budget never exceeds one gigabyte however much space is free`() {
        assertEquals(gb, TileCacheBudget.budgetBytes(500 * gb))
    }

    @Test
    fun `no free space means no budget, never a negative one`() {
        assertEquals(0L, TileCacheBudget.budgetBytes(0))
        assertEquals(0L, TileCacheBudget.budgetBytes(-1))
    }

    @Test
    fun `live bytes exclude freelist pages, so deleted rows count as freed`() {
        // A 1,000-page file of which 400 pages were freed by eviction: the file is
        // unchanged in size but only 600 pages are live.
        assertEquals(600L * 4096, TileCacheBudget.liveBytes(pageCount = 1_000, freelistCount = 400, pageSize = 4096))
        assertEquals(0L, TileCacheBudget.liveBytes(pageCount = 10, freelistCount = 20, pageSize = 4096))
    }

    @Test
    fun `under budget nothing is evicted`() {
        val cache = FakeCache("a", rows = 100, rowBytes = 1_000)
        val result = TileCacheBudget.evictToBudget(listOf(cache), budget = 200_000, batchRows = 10, maxBatches = 100) { it.liveBytes() }
        assertEquals(0, result.rowsDeleted)
        assertEquals(0, cache.evictCalls)
    }

    @Test
    fun `over budget evicts in batches until back within it`() {
        val cache = FakeCache("a", rows = 1_000, rowBytes = 1_000)
        val result = TileCacheBudget.evictToBudget(listOf(cache), budget = 500_000, batchRows = 100, maxBatches = 100) { it.liveBytes() }
        assertEquals(500, result.rowsDeleted)
        assertEquals(500, cache.rows)
        assertEquals(1_000_000L, result.liveBytesBefore)
        assertEquals(500_000L, result.liveBytesAfter)
    }

    @Test
    fun `always evicts from whichever cache is currently largest`() {
        val big = FakeCache("big", rows = 900, rowBytes = 1_000)
        val small = FakeCache("small", rows = 300, rowBytes = 1_000)
        TileCacheBudget.evictToBudget(listOf(small, big), budget = 900_000, batchRows = 100, maxBatches = 100) { it.liveBytes() }
        // 1,200 KB down to 900 KB: three batches, all from the big one (900 -> 600 rows; still >= small).
        assertEquals(600, big.rows)
        assertEquals(300, small.rows)
    }

    @Test
    fun `batch cap bounds the work of one check`() {
        val cache = FakeCache("a", rows = 10_000, rowBytes = 1_000)
        val result = TileCacheBudget.evictToBudget(listOf(cache), budget = 0, batchRows = 10, maxBatches = 5) { it.liveBytes() }
        assertEquals(50, result.rowsDeleted)
        assertEquals(5, cache.evictCalls)
    }

    @Test
    fun `a cache that cannot evict (closed or empty) is skipped, not retried forever`() {
        val stuck = object : TileCacheBudget.Evictable {
            override val name = "closed"
            var calls = 0
            override fun liveBytes() = 10 * mb
            override fun evictOldest(rows: Int): Int { calls++; return 0 }
        }
        val other = FakeCache("other", rows = 100, rowBytes = 1_000)
        val result = TileCacheBudget.evictToBudget(listOf(stuck, other), budget = 0, batchRows = 50, maxBatches = 1_000) { it.liveBytes() }
        assertEquals(1, stuck.calls)
        assertEquals(0, other.rows)
        assertEquals(100, result.rowsDeleted)
        assertTrue(result.liveBytesAfter >= 10 * mb)
    }
}
