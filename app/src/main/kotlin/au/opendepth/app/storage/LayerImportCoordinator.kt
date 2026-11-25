package au.opendepth.app.storage

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.geotiff.GeoTiffReader
import au.opendepth.app.map.LayerUse
import au.opendepth.app.map.OverlayLayerController
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.ui.ImportProgressDialog
import au.opendepth.app.util.UserFacingErrors
import android.net.Uri
import au.opendepth.app.diagnostics.ResourceTracker
import au.opendepth.app.util.openOnIoClosingOnCancel
import au.opendepth.app.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs MBTiles and GeoTIFF imports: the work off the main thread, the new
 * layer added on it, with an [ImportProgressDialog] up throughout (a big
 * GeoTIFF can take a while).
 */
class LayerImportCoordinator(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val overlays: OverlayLayerController,
    private val importProgress: ImportProgressDialog,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = onResult,
    /** Moves the map to a layer that was just loaded (or picked again). */
    private val onShowArea: (GeoBounds) -> Unit = {}
) {
    fun importMbtiles(uri: Uri) {
        scope.launch {
            val progress = importProgress.show("Importing", "Importing MBTiles…")
            val importer = MbtilesImporter(
                contentResolver,
                File(filesDir, OverlayLayerController.MBTILES_DIR),
                context
            )

            // finally: a cancelled import (Activity destroyed) must still take its dialog down.
            val result = try {
                runCatchingCancellable {
                    openOnIoClosingOnCancel({ importer.import(uri) }) { it.database.close() }
                }
            } finally {
                progress.dismiss()
            }
            snapshot("import finished MBTiles")

            result.onSuccess { imported ->
                overlays.addMbtiles(imported)
                onResult("Loaded ${imported.displayName}")
            }.onFailure { error ->
                Log.e(TAG, "MBTiles import failed", error)
                DiagnosticLog.logError(TAG, "importMbtiles FAILED", error)
                onError(error.readableMessage("Could not import this MBTiles file"))
            }
        }
    }

    fun importGeoTiff(uri: Uri) {
        // A file that is already loaded is shown again, not added a second time.
        overlays.layerWithSource(uri.toString())?.let { existing ->
            showAgain(existing)
            return
        }
        scope.launch {
            val progress = importProgress.show("Importing", "Reading GeoTIFF… this can take a moment")
            DiagnosticLog.log(TAG, "importGeoTiff start: uri=$uri")
            val importStart = System.currentTimeMillis()
            val importer = GeoTiffImporter(context, File(filesDir, OverlayLayerController.GEOTIFF_TILE_CACHE_DIR))

            // finally: a cancelled import (Activity destroyed) must still take its dialog down.
            val result = try {
                runCatchingCancellable {
                    openOnIoClosingOnCancel({ importer.import(uri) }) { imported ->
                        (imported as? GeoTiffImporter.ImportedGeoTiff.OnDemand)?.tileSource?.close()
                    }
                }
            } finally {
                progress.dismiss()
            }
            snapshot("import finished GeoTIFF")

            result.onSuccess { imported ->
                DiagnosticLog.log(
                    TAG,
                    "importGeoTiff SUCCESS: file=${imported.displayName} " +
                        "elapsedMs=${System.currentTimeMillis() - importStart}"
                )
                val id = overlays.addGeoTiff(imported)
                overlays.boundsOf(id)?.let(onShowArea)
                onResult(describe(imported))
            }.onFailure { error ->
                Log.e(TAG, "GeoTIFF import failed", error)
                DiagnosticLog.logError(
                    TAG,
                    "importGeoTiff FAILED: elapsedMs=${System.currentTimeMillis() - importStart}",
                    error
                )
                onError(error.readableMessage("Could not import this TIFF file"))
            }
        }
    }

    /**
     * Builds high-detail depths for [area] ([HighDetailDepths]) and opens them as a layer. Can
     * take a few minutes for a big area; the dialog shows progress and can cancel.
     */
    fun downloadHighDetail(area: SeabedDownload.Area) {
        var work: kotlinx.coroutines.Job? = null
        val progress = importProgress.show("High-detail depths", "Starting…") { work?.cancel() }
        work = scope.launch {
            DiagnosticLog.log(TAG, "high-detail build: ${area.name}")
            var note = ""
            val importer = GeoTiffImporter(context, File(filesDir, OverlayLayerController.GEOTIFF_TILE_CACHE_DIR))
            val result = try {
                runCatchingCancellable {
                    val built = withContext(Dispatchers.IO) {
                        HighDetailDepths.build(context, area, File(filesDir, OverlayLayerController.SEABED_DIR)) { pct, stage ->
                            progress.update(if (pct > 0) "$pct%  $stage" else stage)
                        }
                    }
                    note = built.note
                    val existing = overlays.layerWithSource(built.file.absolutePath)
                    if (existing != null) {
                        Triple(existing, null, built.credit)
                    } else {
                        progress.update("Opening the depths…")
                        Triple(null, openOnIoClosingOnCancel({ importer.importFileOnDemand(built.file) }) { imported ->
                            (imported as? GeoTiffImporter.ImportedGeoTiff.OnDemand)?.tileSource?.close()
                        }, built.credit)
                    }
                }
            } finally {
                progress.dismiss()
            }
            result.onSuccess { (existing, imported, credit) ->
                if (existing != null) {
                    showAgain(existing)
                } else if (imported != null) {
                    // No camera move: the box was drawn where the user is looking.
                    overlays.addGeoTiff(imported, credit)
                    onResult(describe(imported) + if (note.isEmpty()) "" else ". $note")
                }
            }.onFailure { error ->
                if (error is java.io.InterruptedIOException) {
                    onResult("Stopped. Nothing was saved.")
                } else if (HighDetailDepths.australian(area) && HighDetailDepths.serverDown(error)) {
                    // IMAS's servers refusing or failing is no reason to leave the person with nothing:
                    // the worldwide depths for the same box come from a different server.
                    DiagnosticLog.logError(TAG, "high-detail build FAILED, IMAS down: getting the worldwide depths instead", error)
                    downloadSeabed(area, "High detail isn't available right now (IMAS's server isn't working), so these are the worldwide depths.")
                } else {
                    DiagnosticLog.logError(TAG, "high-detail build FAILED", error)
                    onError(error.readableMessage("Couldn't build the high-detail depths"))
                }
            }
        }
    }

    /**
     * Downloads free depth data for [area] ([SeabedDownload]) and opens it as a layer. [note] is
     * added to the message shown when it is done.
     */
    fun downloadSeabed(area: SeabedDownload.Area, note: String = "") {
        scope.launch {
            val progress = importProgress.show("Seabed depths", "Asking for the depths… this can take a moment")
            DiagnosticLog.log(TAG, "seabed download: ${area.name} from ${SeabedDownload.url(area)}")
            val importer = GeoTiffImporter(context, File(filesDir, OverlayLayerController.GEOTIFF_TILE_CACHE_DIR))
            val result = try {
                runCatchingCancellable {
                    var shownMb = 0L
                    val file = withContext(Dispatchers.IO) {
                        SeabedDownload.download(area, File(filesDir, OverlayLayerController.SEABED_DIR)) { bytes ->
                            val mb = bytes / 1_000_000
                            if (mb != shownMb) {
                                shownMb = mb
                                scope.launch { progress.update("Downloading the depths… $mb MB") }
                            }
                        }
                    }
                    val existing = overlays.layerWithSource(file.absolutePath)
                    if (existing != null) {
                        existing to null
                    } else {
                        progress.update("Opening the depths…")
                        null to openOnIoClosingOnCancel({ importer.importFile(file) }) { imported ->
                            (imported as? GeoTiffImporter.ImportedGeoTiff.OnDemand)?.tileSource?.close()
                        }
                    }
                }
            } finally {
                progress.dismiss()
            }
            result.onSuccess { (existing, imported) ->
                if (existing != null) {
                    showAgain(existing)
                } else if (imported != null) {
                    val id = overlays.addGeoTiff(imported, SeabedDownload.CREDIT)
                    overlays.boundsOf(id)?.let(onShowArea)
                    onResult(if (note.isEmpty()) describe(imported) else "$note ${describe(imported)}")
                }
            }.onFailure { error ->
                DiagnosticLog.logError(TAG, "seabed download FAILED", error)
                onError(error.readableMessage("Couldn't get the seabed depths"))
            }
        }
    }

    /**
     * As [importMbtiles], but for an MBTiles file at a known local path (an
     * offline download written by `TileDownloadRunner`), opened in place via
     * [MbtilesImporter.importFromLocalFile] rather than wrapped in a `Uri`.
     */
    fun importMbtilesFromLocalFile(path: String, displayName: String, attribution: String = "") {
        scope.launch {
            val progress = importProgress.show("Importing", "Reading downloaded tiles…")
            DiagnosticLog.log(TAG, "importMbtilesFromLocalFile start: path=$path")
            val importStart = System.currentTimeMillis()
            val importer = MbtilesImporter(
                contentResolver,
                File(filesDir, OverlayLayerController.MBTILES_DIR),
                context
            )

            // finally: a cancelled import (Activity destroyed) must still take its dialog down.
            val result = try {
                runCatchingCancellable {
                    openOnIoClosingOnCancel({ importer.importFromLocalFile(path, displayName) }) { it.database.close() }
                }
            } finally {
                progress.dismiss()
            }
            snapshot("import finished download")

            result.onSuccess { imported ->
                DiagnosticLog.log(
                    TAG,
                    "importMbtilesFromLocalFile SUCCESS: file=${imported.displayName} " +
                        "elapsedMs=${System.currentTimeMillis() - importStart}"
                )
                overlays.addMbtiles(imported, attribution)
                onResult("Loaded ${imported.displayName}")
            }.onFailure { error ->
                Log.e(TAG, "Downloaded MBTiles import failed", error)
                DiagnosticLog.logError(
                    TAG,
                    "importMbtilesFromLocalFile FAILED: elapsedMs=${System.currentTimeMillis() - importStart}",
                    error
                )
                onError(error.readableMessage("Could not import the downloaded file"))
            }
        }
    }

    /**
     * A file picked (or built) again that is already a layer: shown, if it
     * was hidden, and the map moved to it. A layer hidden since before the
     * app started has no known area until its file is open again, so the map
     * moves once it is.
     */
    private fun showAgain(existing: OverlayLayer) {
        if (!existing.visible) overlays.setVisible(existing.id, true)
        overlays.whenAwake(existing.id, LayerUse.GO_TO) { layer, done ->
            overlays.boundsOf(layer.id)?.let(onShowArea)
            done()
        }
        onResult("${existing.name} is already loaded")
    }

    private fun describe(imported: GeoTiffImporter.ImportedGeoTiff): String = when (imported) {
        is GeoTiffImporter.ImportedGeoTiff.InMemory -> {
            val result = imported.result
            val header = "Loaded ${imported.displayName} (${result.projectionLabel})"
            if (result is GeoTiffReader.Result.Depth) {
                val converted = if (result.positiveDownConverted) {
                    ", read as positive-down soundings"
                } else {
                    ""
                }
                "$header — depths ${result.grid.shallowestMetres.toInt()} to " +
                    "${result.grid.deepestMetres.toInt()} m$converted"
            } else {
                "$header — imagery overlay"
            }
        }

        is GeoTiffImporter.ImportedGeoTiff.OnDemand -> {
            val source = imported.tileSource
            "Loaded ${imported.displayName} (${source.projectionLabel}) — depths " +
                "${source.shallowestMetres.toInt()} to ${source.deepestMetres.toInt()} m " +
                "(served on demand for its size: ${source.nativeWidth}x${source.nativeHeight}, " +
                "up to zoom ${source.nativeMaxZoom})"
        }
    }

    private fun snapshot(reason: String) = ResourceTracker.snapshot(context, reason, overlays.serverLayerCount)

    /** Import failures carry actionable text, so prefer it over a generic line. */
    private fun Throwable.readableMessage(fallback: String): String =
        UserFacingErrors.describe(this, fallback)

    private companion object {
        const val TAG = "SeaBathyImport"
    }
}
