package au.opendepth.app.gdal

import au.opendepth.app.diagnostics.DiagnosticLog
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One disk budget shared by every open [GdalTileCache] (colour and DEM, all
 * layers): `min(`[MAX_BUDGET_BYTES]`, `[FREE_SPACE_FRACTION]` of free space)`.
 *
 * Usage is measured as *live* data, `(page_count - freelist_count) *
 * page_size`, not file length: deleted rows leave the file the same size
 * (their pages go on SQLite's freelist and are reused by later writes), so a
 * file-size check would never see eviction take effect and would keep
 * evicting. The file therefore stops growing once the budget is reached
 * rather than shrinking; nothing here runs VACUUM. Removing a layer deletes
 * its cache files outright, which is when that space comes back.
 *
 * Every [CHECK_EVERY_WRITES] cache writes, a check is queued on one
 * background thread — never the tile-serving or main thread. When over
 * budget it deletes the oldest-written rows (lowest rowid: `INSERT OR
 * REPLACE` gives every write a fresh, increasing rowid) from the largest
 * cache, in batches of [EVICTION_BATCH_ROWS], until back under budget. No
 * schema change, so caches written by earlier builds work as they are.
 */
object TileCacheBudget {
    private const val TAG = "TileCacheBudget"

    const val MAX_BUDGET_BYTES = 1024L * 1024 * 1024
    const val FREE_SPACE_FRACTION = 0.10
    const val CHECK_EVERY_WRITES = 200
    const val EVICTION_BATCH_ROWS = 500

    /** Caps one check's work; anything left over is picked up by the next check. */
    private const val MAX_BATCHES_PER_CHECK = 200

    /** What eviction needs from a cache; [GdalTileCache] implements it over SQLite. */
    interface Evictable {
        val name: String
        fun liveBytes(): Long
        /** Deletes up to [rows] oldest-written tiles; returns how many were deleted. */
        fun evictOldest(rows: Int): Int
    }

    private val caches: MutableSet<Evictable> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private val writesSinceCheck = AtomicInteger(0)
    private val checkQueued = AtomicBoolean(false)
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tile-cache-budget").apply { isDaemon = true } }
    }

    /** Where the budget's free-space fraction is measured; set by [GdalTileCache.open]. */
    @Volatile
    private var storageDir: File? = null

    internal fun register(cache: Evictable, dir: File) {
        storageDir = dir
        caches.add(cache)
    }

    internal fun unregister(cache: Evictable) {
        caches.remove(cache)
    }

    /** Called after each successful cache write, from tile threads; cheap unless a check is due. */
    internal fun onWrite() {
        if (writesSinceCheck.incrementAndGet() < CHECK_EVERY_WRITES) return
        writesSinceCheck.set(0)
        if (!checkQueued.compareAndSet(false, true)) return
        executor.execute {
            try {
                enforce()
            } finally {
                checkQueued.set(false)
            }
        }
    }

    /** The current budget in bytes. */
    fun currentBudgetBytes(): Long = budgetBytes(storageDir?.usableSpace ?: 0L)

    /** Live bytes per open cache, for the debug resource snapshot. Does SQLite I/O: call off the main thread. */
    fun usage(): List<Pair<String, Long>> = snapshotCaches().map { it.name to safeLiveBytes(it) }

    private fun snapshotCaches(): List<Evictable> = synchronized(caches) { caches.toList() }

    private fun safeLiveBytes(cache: Evictable): Long = runCatching { cache.liveBytes() }.getOrDefault(0L)

    private fun enforce() {
        // usableSpace reads 0 for a missing folder; that must not become a
        // zero budget that evicts every cached tile.
        if (storageDir?.isDirectory != true) return
        val budget = currentBudgetBytes()
        val result = runCatching {
            // Not safeLiveBytes: a measuring failure must reach the log below, not read as "empty".
            evictToBudget(snapshotCaches(), budget, EVICTION_BATCH_ROWS, MAX_BATCHES_PER_CHECK) { it.liveBytes() }
        }
        result.onSuccess { evicted ->
            if (evicted.rowsDeleted > 0) {
                DiagnosticLog.log(
                    TAG,
                    "evicted ${evicted.rowsDeleted} tile(s): budgetMB=${budget / MB} " +
                        "liveMB before=${evicted.liveBytesBefore / MB} after=${evicted.liveBytesAfter / MB}"
                )
            }
        }.onFailure { DiagnosticLog.logError(TAG, "budget check FAILED", it) }
    }

    // Pure policy (unit tested) ------------------------------------------

    /** `min(MAX_BUDGET_BYTES, FREE_SPACE_FRACTION x free bytes)`, never negative. */
    internal fun budgetBytes(freeBytes: Long): Long =
        minOf(MAX_BUDGET_BYTES, (freeBytes.coerceAtLeast(0L) * FREE_SPACE_FRACTION).toLong())

    /** Bytes SQLite pages actually hold: freelist pages are free space inside the file. */
    internal fun liveBytes(pageCount: Long, freelistCount: Long, pageSize: Long): Long =
        ((pageCount - freelistCount).coerceAtLeast(0L)) * pageSize.coerceAtLeast(0L)

    internal class EvictionResult(val rowsDeleted: Int, val liveBytesBefore: Long, val liveBytesAfter: Long)

    /**
     * Deletes batches of oldest rows from whichever cache is currently
     * largest until the total is within [budget], a cache runs out of rows,
     * or [maxBatches] is reached.
     */
    internal fun evictToBudget(
        caches: List<Evictable>,
        budget: Long,
        batchRows: Int,
        maxBatches: Int,
        measure: (Evictable) -> Long
    ): EvictionResult {
        val sizes = caches.associateWith(measure).toMutableMap()
        val before = sizes.values.sum()
        var deleted = 0
        var batches = 0
        while (sizes.values.sum() > budget && batches < maxBatches) {
            val victim = sizes.maxByOrNull { it.value }?.key ?: break
            val removed = victim.evictOldest(batchRows)
            batches++
            deleted += removed
            if (removed == 0) {
                // Nothing left to delete here (or it was closed): stop considering it.
                sizes.remove(victim)
                if (sizes.isEmpty()) break
                continue
            }
            sizes[victim] = measure(victim)
        }
        return EvictionResult(deleted, before, caches.sumOf(measure))
    }

    private const val MB = 1024L * 1024
}
