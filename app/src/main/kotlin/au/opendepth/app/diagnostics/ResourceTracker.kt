package au.opendepth.app.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Debug
import au.opendepth.app.BuildConfig
import au.opendepth.app.gdal.TileCacheBudget
import au.opendepth.app.map.OverlayLayerController
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only: counts open GDAL datasets and SQLite databases and logs
 * a snapshot at key moments (layer added or removed, import or download
 * done, backgrounding, low memory, every minute). A count that never drops
 * back after its layers are gone is a leak. Does nothing in the phone build.
 */
object ResourceTracker {
    private const val TAG = "Resources"

    private val gdalDatasets = AtomicInteger(0)
    private val databases: MutableSet<SQLiteDatabase> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    /** Snapshots do disk I/O (cache sizes), so they never run on the caller's thread. */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "resource-snapshot").apply { isDaemon = true }
        }
    }

    fun gdalDatasetOpened() {
        if (BuildConfig.DEBUG) gdalDatasets.incrementAndGet()
    }

    fun gdalDatasetClosed() {
        if (BuildConfig.DEBUG) gdalDatasets.decrementAndGet()
    }

    /** Registers [database] for counting; returns it so an open call can be wrapped in place. */
    fun trackDatabase(database: SQLiteDatabase): SQLiteDatabase {
        if (BuildConfig.DEBUG) databases.add(database)
        return database
    }

    val openGdalDatasets: Int get() = gdalDatasets.get()

    /** Open tracked databases; closed ones are pruned as they are counted. */
    val openDatabases: Int
        get() = synchronized(databases) {
            databases.removeAll { !it.isOpen }
            databases.size
        }

    /**
     * Logs one snapshot line, off the calling thread. [serverLayers] is read
     * by the caller (on the main thread, which owns the layer registry), or
     * null when the caller has no view of it.
     */
    fun snapshot(context: Context, reason: String, serverLayers: Int? = null) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        executor.execute {
            // filesDir checks the disk, so it's looked up here too.
            runCatching { DiagnosticLog.log(TAG, describe(reason, app.filesDir, serverLayers)) }
        }
    }

    private fun describe(reason: String, filesDir: File, serverLayers: Int?): String {
        val runtime = Runtime.getRuntime()
        val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
        return formatSnapshot(
            reason = reason,
            javaHeapBytes = javaHeapUsed,
            javaHeapMaxBytes = runtime.maxMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            gdalDatasets = openGdalDatasets,
            sqliteDatabases = openDatabases,
            serverLayers = serverLayers,
            threads = threadCount(),
            tileCacheBytes = directoryBytes(File(filesDir, OverlayLayerController.GEOTIFF_TILE_CACHE_DIR)),
            tileCacheLiveBytes = TileCacheBudget.usage().sumOf { it.second },
            tileCacheBudgetBytes = TileCacheBudget.currentBudgetBytes()
        )
    }

    /** Every thread in the process, native ones included — [Thread.activeCount] only sees one Java thread group. */
    private fun threadCount(): Int =
        File("/proc/self/task").list()?.size ?: Thread.activeCount()

    internal fun directoryBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal fun formatSnapshot(
        reason: String,
        javaHeapBytes: Long,
        javaHeapMaxBytes: Long,
        nativeHeapBytes: Long,
        gdalDatasets: Int,
        sqliteDatabases: Int,
        serverLayers: Int?,
        threads: Int,
        tileCacheBytes: Long,
        tileCacheLiveBytes: Long,
        tileCacheBudgetBytes: Long
    ): String = "snapshot [$reason] javaHeapMB=${mb(javaHeapBytes)}/${mb(javaHeapMaxBytes)} " +
        "nativeHeapMB=${mb(nativeHeapBytes)} gdalDatasets=$gdalDatasets sqliteDbs=$sqliteDatabases " +
        "serverLayers=${serverLayers ?: "-"} threads=$threads tileCacheFileMB=${mb(tileCacheBytes)} " +
        "tileCacheLiveMB=${mb(tileCacheLiveBytes)} tileCacheBudgetMB=${mb(tileCacheBudgetBytes)}"

    private fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
}
