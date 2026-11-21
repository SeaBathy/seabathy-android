package au.opendepth.app.ui

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.download.OfflineDownloadEstimator
import au.opendepth.app.download.offlineDownloadsDir
import au.opendepth.app.download.offlineDownloadsLabel
import au.opendepth.app.storage.StorageUsage
import au.opendepth.app.storage.StorageUsage.Group
import au.opendepth.app.terrain.TERRAIN_TILE_CACHE_DIR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource
import java.io.File
import kotlin.coroutines.resume

/**
 * Settings → Storage: what the app keeps on this phone and how big each part
 * is ([StorageUsage]), worked out off the main thread, with the free space
 * left. Its one action clears temporary files: only caches that are safe to
 * empty while the app runs, each through its owner.
 */
class StoragePanel(
    private val context: Context,
    private val panels: PanelDialogs,
    private val scope: CoroutineScope,
    /** Empties the 3D view's saved map tiles through its own cache; called off the main thread. */
    private val clear3dTiles: () -> Unit,
    /** Deletes the kept NSW reef outlines through their owner; called off the main thread. */
    private val clearReefOutlines: () -> Unit = {}
) {
    fun show() {
        val body = panels.body()
        panels.showPanel("Storage", body)
        refresh(body, cleared = null)
    }

    /** Measures and fills [body]; [cleared] is what the temporary files came to before a clear just made. */
    private fun refresh(body: LinearLayout, cleared: Long?) {
        body.removeAllViews()
        body.addView(panels.caption("Working it out…", topPaddingDp = 0))
        applyReadableText(body)
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { StorageUsage.measure(places()) }
                    .onFailure { DiagnosticLog.logError(TAG, "measuring storage FAILED", it) }
                    .getOrNull()
            }
            body.removeAllViews()
            if (report == null) {
                body.addView(panels.caption("Couldn't work out what the app keeps.", topPaddingDp = 0))
            } else {
                fill(body, report, cleared)
            }
            // As the panel does for what it is first shown with.
            applyReadableText(body)
            // Added after that: it would take the word "remove" for a warning and colour the note red.
            if (report != null) {
                body.addView(panels.caption(
                    "To free more, remove layers you no longer use in the layers panel. Offline map files are " +
                        "deleted with a file manager, in ${offlineDownloadsLabel(context)}.",
                    topPaddingDp = 0
                ).apply { setTextColor(AppTheme.palette.primaryText) })
            }
        }
    }

    private fun fill(body: LinearLayout, report: StorageUsage.Report, cleared: Long?) {
        if (cleared != null) {
            val freed = cleared - report[Group.TEMPORARY]
            body.addView(panels.caption(if (freed > 0) "Cleared ${size(freed)}." else "There was nothing to clear.", topPaddingDp = 0))
        }
        body.addView(panels.sectionHeader("ON THIS PHONE"))
        line(body, "Charts and depth layers", report[Group.CHARTS], "copied into the app, or downloaded")
        line(body, "Drawn tiles of big surveys", report[Group.SURVEY_TILES], "kept so they draw fast; they go with the layer")
        line(body, "Offline maps", report[Group.OFFLINE_MAPS], "in ${offlineDownloadsLabel(context)}")
        line(body, "Sea conditions and tides", report[Group.CONDITIONS], "saved for use offline")
        line(body, "Reef outlines (NSW)", report[Group.REEF_OUTLINES], "kept from looking at the coast, for offline")
        line(body, "Waypoint photos", report[Group.PHOTOS])
        line(body, "Tracks", report[Group.TRACKS])
        line(body, "Temporary files", report[Group.TEMPORARY], "map pictures kept from online maps, working files")
        line(body, "Settings and other app files", report[Group.OTHER], "settings, the debug log, data the app needs")

        body.addView(panels.sectionHeader("TOTAL"))
        panels.addRow(body, still(panels.row("Kept by SeaBathy", size(report.totalBytes)) {}))
        panels.addRow(body, still(panels.row("Free on this phone", size(report.freeBytes)) {}))

        body.addView(panels.sectionHeader("FREE UP SPACE"))
        panels.addRow(body, panels.row("Clear temporary files", "Map pictures kept from online maps, and left-over working files") {
            panels.confirm(
                title = "Clear temporary files?",
                message = "This removes the map pictures the app kept while you looked at online maps, on the chart and in 3D, " +
                    "and working files left behind by imports and downloads that didn't finish. " +
                    "Online maps will need the internet to show again.\n\n" +
                    "Nothing you made or downloaded is touched: charts, depth layers, offline maps, saved tides and " +
                    "sea conditions, waypoints, photos and tracks all stay.",
                confirmLabel = "Clear"
            ) { clear(body, report[Group.TEMPORARY]) }
        })
        if (report[Group.REEF_OUTLINES] > 0L) {
            panels.addRow(body, panels.row("Clear reef outlines (NSW)", "${size(report[Group.REEF_OUTLINES])} of NSW seabed reef outlines") {
                panels.confirm(
                    title = "Clear the NSW reef outlines?",
                    message = "This removes the NSW seabed reef outlines kept on the phone. They load again from the internet " +
                        "when you look at the NSW coast with the layer on, and won't show offline until then.",
                    confirmLabel = "Clear"
                ) { clearReefs(body) }
            })
        }
    }

    private fun clearReefs(body: LinearLayout) {
        body.removeAllViews()
        body.addView(panels.caption("Clearing…", topPaddingDp = 0))
        applyReadableText(body)
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { clearReefOutlines() }
                    .onFailure { DiagnosticLog.logError(TAG, "clearing the NSW reef outlines FAILED", it) }
            }
            refresh(body, cleared = null)
        }
    }

    /** One part's row: its size, then a few words on what it is. */
    private fun line(body: LinearLayout, title: String, bytes: Long, what: String = "") {
        val value = when {
            bytes <= 0L -> "Nothing"
            what.isEmpty() -> size(bytes)
            else -> "${size(bytes)} · $what"
        }
        panels.addRow(body, still(panels.row(title, value) {}))
    }

    /** A row that only shows something: it looks like the others but isn't a button. */
    private fun still(row: TextView): TextView = row.apply {
        isClickable = false
        isFocusable = false
    }

    private fun size(bytes: Long): String = OfflineDownloadEstimator.formatBytes(bytes)

    private fun clear(body: LinearLayout, before: Long) {
        body.removeAllViews()
        body.addView(panels.caption("Clearing…", topPaddingDp = 0))
        applyReadableText(body)
        scope.launch {
            // The chart's own cache of online tiles: the map empties it itself.
            withTimeoutOrNull(MAP_CACHE_TIMEOUT_MS) { clearMapCache() }
            withContext(Dispatchers.IO) {
                runCatching { clear3dTiles() }
                    .onFailure { DiagnosticLog.logError(TAG, "clearing the 3D tiles FAILED", it) }
                runCatching { StorageUsage.clearOldCacheFiles(places(), appStartedMillis(), CACHE_LEFT_ALONE) }
                    .onSuccess { DiagnosticLog.log(TAG, "cleared temporary files: $it bytes of old working files") }
                    .onFailure { DiagnosticLog.logError(TAG, "clearing old working files FAILED", it) }
            }
            refresh(body, cleared = before)
        }
    }

    /** Asks the map to empty its cache of online tiles, and waits for it to say it has. */
    private suspend fun clearMapCache() = suspendCancellableCoroutine { waiting ->
        runCatching {
            OfflineManager.getInstance(context).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    if (waiting.isActive) waiting.resume(Unit)
                }

                override fun onError(message: String) {
                    DiagnosticLog.log(TAG, "clearing the map's tile cache FAILED: $message")
                    if (waiting.isActive) waiting.resume(Unit)
                }
            })
        }.onFailure {
            DiagnosticLog.logError(TAG, "clearing the map's tile cache FAILED", it)
            if (waiting.isActive) waiting.resume(Unit)
        }
    }

    /** Where everything is, in this build. Looks at the disk: call off the main thread. */
    private fun places() = StorageUsage.Places(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        offlineMapsDir = offlineDownloadsDir(context),
        mapCacheDir = runCatching { File(FileSource.getResourcesCachePath(context)) }.getOrDefault(context.filesDir),
        otherRoots = listOfNotNull(context.dataDir, context.getExternalFilesDir(null)?.parentFile)
    )

    /** When this run of the app began: a working file older than that isn't in use now. */
    private fun appStartedMillis(): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    private companion object {
        const val TAG = "Storage"
        const val MAP_CACHE_TIMEOUT_MS = 20_000L

        /**
         * Left alone in the cache folder: the 3D view's saved tiles (emptied through its own
         * cache, not from under it) and the 3D web view's own files (it may have them open).
         */
        val CACHE_LEFT_ALONE = setOf(TERRAIN_TILE_CACHE_DIR, "WebView", "org.chromium.android_webview")
    }
}
