package au.opendepth.app.download

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import au.opendepth.app.map.MapHolder
import au.opendepth.app.model.DataSource
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.storage.LayerImportCoordinator
import au.opendepth.app.ui.DownloadZoneDialog
import au.opendepth.app.ui.PanelDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloading an offline map of the area on screen: pick zooms in
 * [DownloadZoneDialog], check the size (a few sample tiles, see
 * [TileSampler]), then download.
 *
 * The size check runs here on the activity's [scope]. The download itself
 * runs in [OfflineTileDownloadService], a foreground service, so it keeps
 * going with the screen off. Both list tiles with
 * [OfflineDownloadEstimator.tilesToFetch], so they agree on the area.
 */
class OfflineTileDownloadController(
    private val context: Context,
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val panels: PanelDialogs,
    private val downloadZoneDialog: DownloadZoneDialog,
    private val layerImports: LayerImportCoordinator,
    private val isOnline: () -> Boolean,
    private val onProgress: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val sampleClient = TileFetchClient()
    private val tileSampler = TileSampler(sampleClient::fetch)

    /** The current map viewport, or null if the map has not finished loading yet. */
    fun currentViewportBounds(): GeoBounds? {
        val map = mapHolder.map ?: return null
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return null
        return GeoBounds(
            west = bounds.longitudeWest,
            south = bounds.latitudeSouth,
            east = bounds.longitudeEast,
            north = bounds.latitudeNorth
        )
    }

    /** Opens the download screen for [source] over the area on screen. Nothing is fetched until "Assess". */
    fun beginDownload(source: DataSource) {
        val bounds = currentViewportBounds()
        if (bounds == null || !bounds.isValid) {
            onError("Could not read the current map view — try again once the map has finished loading")
            return
        }
        beginDownload(source, bounds, DEFAULT_MAX_ZOOM, "The area on screen. To change it, close this, move the map and open it again.")
    }

    /**
     * The download screen for an area other than the one on screen: the 3D
     * view's. Its max zoom starts as deep as [maxZoom] allows while staying a
     * sensible size, since a 3D area is usually small and wanted sharp.
     */
    fun beginDownload(
        source: DataSource,
        bounds: GeoBounds,
        maxZoom: Int,
        areaNote: String = "The area shown in 3D. Check the map's terms allow offline copies. Load the file as an MBTiles layer when it's done, and it's in 3D's Map list."
    ) {
        var deepest = maxZoom.coerceAtLeast(DEFAULT_MIN_ZOOM)
        while (deepest > DEFAULT_MIN_ZOOM &&
            OfflineDownloadEstimator.estimateTiles(bounds, DEFAULT_MIN_ZOOM..deepest).tileCount > SUGGESTED_TILE_COUNT
        ) deepest--
        downloadZoneDialog.show(
            sourceName = source.name,
            bounds = bounds,
            defaultMinZoom = DEFAULT_MIN_ZOOM,
            defaultMaxZoom = deepest,
            areaNote = areaNote,
            onAssess = { zoomRange, onSuccess, onFailure -> assess(source, bounds, zoomRange, onSuccess, onFailure) },
            onConfirm = { zoomRange -> startDownload(source, bounds, zoomRange) }
        )
    }

    /** Counts the tiles, then fetches a few ([TileSampler]) to estimate the size from real tiles. */
    private fun assess(
        source: DataSource,
        bounds: GeoBounds,
        zoomRange: IntRange,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val quickEstimate = OfflineDownloadEstimator.estimateTiles(bounds, zoomRange)
        if (quickEstimate.tileCount <= 0L) {
            onFailure("No tiles in this area at these zooms")
            return
        }
        if (quickEstimate.tileCount > MAX_REASONABLE_TILE_COUNT) {
            onFailure(
                "That's ${OfflineDownloadEstimator.formatTileCount(quickEstimate.tileCount)} tiles — " +
                    "zoom in or lower the max zoom"
            )
            return
        }
        if (!isOnline()) {
            onFailure("No internet: connect to check the size and download")
            return
        }

        scope.launch {
            val sampleResult = withContext(Dispatchers.IO) {
                sampleClient.resetLogBudget()
                tileSampler.sample(source.urlTemplate, bounds, zoomRange)
            }

            val averageBytes = sampleResult.averageBytes
            val estimate = if (averageBytes != null) {
                OfflineDownloadEstimator.estimateTiles(bounds, zoomRange, averageTileBytes = averageBytes)
            } else {
                quickEstimate
            }

            val basis = if (sampleResult.sampledSizes.isNotEmpty()) {
                "from ${sampleResult.sampledSizes.size} sample tiles"
            } else {
                "from a typical tile size: no sample tiles came back, so check the link if the download fails"
            }

            onSuccess(
                "About ${OfflineDownloadEstimator.formatTileCount(quickEstimate.tileCount)} tiles, " +
                    "~${OfflineDownloadEstimator.formatBytes(estimate.estimatedBytes)} ($basis)."
            )
        }
    }

    /** Hands the download to [OfflineTileDownloadService]; its notification shows the progress. */
    private fun startDownload(source: DataSource, bounds: GeoBounds, zoomRange: IntRange) {
        if (OfflineTileDownloadService.isRunning) {
            onError("Another download is still running. Wait for it to finish, or cancel it from its notification.")
            return
        }
        if (OfflineDownloadEstimator.estimateTiles(bounds, zoomRange).tileCount > MAX_REASONABLE_TILE_COUNT) {
            onError("That's too big to download: zoom in or lower the max zoom, then assess again")
            return
        }
        if (!isOnline()) {
            onError("No internet: connect, then download")
            return
        }
        val intent = OfflineTileDownloadService.startIntent(context, source, bounds, zoomRange)
        ContextCompat.startForegroundService(context, intent)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            onProgress("Downloading ${source.name}: progress is in the notification")
        } else {
            // Progress, the result and the "import it now?" offer all arrive as notifications.
            onError(
                "Downloading ${source.name}. Notifications are off for SeaBathy, so there's no progress " +
                    "or finished message: the file goes to SeaBathy/OfflineDownloads, to load as an MBTiles layer."
            )
        }
    }

    /** The app was opened from the "download finished" notification: offer to load the file as a layer. */
    fun offerImportForCompletedDownload(filePath: String, sourceName: String, attribution: String) {
        panels.confirm(
            title = "Download complete",
            message = "$sourceName finished downloading to:\n$filePath\n\nImport it as a layer now?",
            confirmLabel = "Import",
            destructive = false
        ) {
            layerImports.importMbtilesFromLocalFile(filePath, sourceName, attribution)
        }
    }

    private companion object {
        const val DEFAULT_MIN_ZOOM = 10
        const val DEFAULT_MAX_ZOOM = 14
        /** More than this is almost always a mistake (an area or zoom range far too big): refused. */
        const val MAX_REASONABLE_TILE_COUNT = 200_000L
        /** The 3D area's suggested download stops its max zoom here (tens of MB of imagery). */
        const val SUGGESTED_TILE_COUNT = 30_000L
    }
}
