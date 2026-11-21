package au.opendepth.app.download

import android.content.Context
import android.view.ViewGroup
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import au.opendepth.app.map.MapHolder
import au.opendepth.app.model.DataSource
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.storage.LayerImportCoordinator
import au.opendepth.app.ui.AreaPicker
import au.opendepth.app.ui.DownloadZoneDialog
import au.opendepth.app.ui.OfflineDetailControl
import au.opendepth.app.ui.PanelDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapView

/**
 * Downloading an offline map: the area is drawn as a box on the chart
 * ([AreaPicker], with the detail to save and a live tile count), then
 * [DownloadZoneDialog] shows the zooms, checks the size (a few sample tiles,
 * see [TileSampler]) and downloads. The 3D view's own area goes straight to
 * that second step.
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
    private val onError: (String) -> Unit,
    /** The chart, and the view the area picker is laid over (the chart's parent). */
    private val mapView: MapView,
    private val pickerRoot: ViewGroup,
    /** Takes the settings and layers panels off the chart, so the area can be drawn on it. */
    private val closePanels: () -> Unit
) {
    private val sampleClient = TileFetchClient()
    private val tileSampler = TileSampler(sampleClient::fetch)

    /** The area picker, while it is up. */
    private var picker: AreaPicker? = null

    /**
     * Saving [source] offline starts on the chart: the panels close and the person draws the
     * area as a box, with the DETAIL to save and how many tiles that needs. "Next" opens the
     * download screen for exactly that box. Nothing is fetched until "Assess".
     */
    fun beginDownload(source: DataSource) {
        noOfflineReason(source.urlTemplate)?.let { onError(it); return }
        val map = mapHolder.map
        if (map == null) {
            onError("The map hasn't finished loading. Try again in a moment.")
            return
        }
        closePanels()
        picker?.dismiss()
        val detail = OfflineDetailControl(context, OfflineDetail.topZoom(source.urlTemplate)) {
            map.cameraPosition.target?.latitude ?: 0.0
        }
        picker = AreaPicker(
            context = context,
            root = pickerRoot,
            mapView = mapView,
            map = map,
            // The limit is the number of tiles, which the DETAIL control counts.
            maxKm = null,
            confirmLabel = "Next",
            describe = { _, show -> show(null) },
            onConfirm = { box ->
                picker = null
                val zooms = OfflineDetail.zoomRange(detail.detail)
                openDownloadScreen(
                    source,
                    OfflineDetail.boundsOf(box.south, box.west, box.north, box.east),
                    zooms.first,
                    zooms.last,
                    "The box you drew on the chart. To change it, close this and draw it again."
                )
            },
            onCancel = { picker = null },
            title = "OFFLINE MAP",
            extra = detail
        ).also { it.show() }
    }

    /**
     * The download screen for an area that is already chosen: the 3D view's.
     * Its max zoom starts as deep as [maxZoom] allows while staying a
     * sensible size, since a 3D area is usually small and wanted sharp.
     */
    fun beginDownload(
        source: DataSource,
        bounds: GeoBounds,
        maxZoom: Int,
        areaNote: String = "The area shown in 3D. Load the file as an MBTiles layer when it's done, and it's in 3D's Map list."
    ) {
        noOfflineReason(source.urlTemplate)?.let { onError(it); return }
        var deepest = maxZoom.coerceAtLeast(DEFAULT_MIN_ZOOM)
        while (deepest > DEFAULT_MIN_ZOOM &&
            OfflineDownloadEstimator.estimateTiles(bounds, DEFAULT_MIN_ZOOM..deepest).tileCount > SUGGESTED_TILE_COUNT
        ) deepest--
        openDownloadScreen(source, bounds, DEFAULT_MIN_ZOOM, deepest, areaNote)
    }

    /** The download screen for [bounds], its zooms starting at [minZoom] and [maxZoom]; [areaNote] says what the area is. */
    private fun openDownloadScreen(source: DataSource, bounds: GeoBounds, minZoom: Int, maxZoom: Int, areaNote: String) {
        downloadZoneDialog.show(
            sourceName = source.name,
            bounds = bounds,
            defaultMinZoom = minZoom,
            defaultMaxZoom = maxZoom,
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
                    "choose a smaller area or lower the max zoom"
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
                sampleClient.resetPoliteness()
                tileSampler.sample(source.urlTemplate, bounds, zoomRange)
            }
            sampleClient.refusal?.let {
                onFailure("$it ${TileFetchClient.TRY_A_KEY}")
                return@launch
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
            onError("That's too big to download: choose a smaller area or lower the max zoom, then assess again")
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
                    "or finished message: the file goes to ${offlineDownloadsLabel(context)}, to load as an MBTiles layer."
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

    companion object {
        /**
         * Tile servers whose rules forbid saving areas for offline use, and block apps that do:
         * OpenStreetMap's own server (operations.osmfoundation.org/policies/tiles) and
         * OpenTopoMap's volunteer server. Everything else is left to the user and the map's terms.
         */
        private val NO_OFFLINE_HOSTS = listOf("tile.openstreetmap.org", "opentopomap.org")

        /** Why [urlTemplate] can't be saved offline, or null when it can. */
        fun noOfflineReason(urlTemplate: String): String? {
            au.opendepth.app.settings.XyzTilePresets.onlineOnly(urlTemplate)?.let { preset ->
                return "${preset.label} is an online-only layer in SeaBathy: it isn't saved for offline use. Pick another map to save."
            }
            val host = runCatching { java.net.URI(urlTemplate.replace("{", "").replace("}", "")).host }.getOrNull()?.lowercase()
                ?: return null
            if (NO_OFFLINE_HOSTS.none { host == it || host.endsWith(".$it") }) return null
            return "This map's tile server doesn't allow saving areas for offline use, and blocks apps that do. " +
                "Pick another map to save, or view this one online."
        }

        // One set of numbers for the area picker's DETAIL and for this screen: see [OfflineDetail].
        private const val DEFAULT_MIN_ZOOM = OfflineDetail.DEFAULT_MIN_ZOOM
        /** More than this is almost always a mistake (an area or zoom range far too big): refused. */
        private const val MAX_REASONABLE_TILE_COUNT = OfflineDetail.MAX_TILES
        /** The 3D area's suggested download stops its max zoom here (tens of MB of imagery). */
        private const val SUGGESTED_TILE_COUNT = OfflineDetail.SUGGESTED_TILES
    }
}
