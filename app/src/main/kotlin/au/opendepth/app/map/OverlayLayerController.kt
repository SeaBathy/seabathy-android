package au.opendepth.app.map

import au.opendepth.app.tiles.WebMercatorTiles
import kotlin.math.ln
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import au.opendepth.app.data.OverlayLayerRepository
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.diagnostics.ResourceTracker
import au.opendepth.app.download.redactUrl
import au.opendepth.app.gdal.GdalSupport
import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.geotiff.DepthGrid
import au.opendepth.app.geotiff.DepthGridRenderer
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.geotiff.GeoTiffReader
import au.opendepth.app.geotiff.InMemoryDepthGrid
import au.opendepth.app.geotiff.TiledDepthPyramid
import au.opendepth.app.model.DepthRampSettings
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.LayerKind
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.settings.XyzTilePreset
import au.opendepth.app.storage.AppOwnedArtifacts
import au.opendepth.app.storage.DescriptorPaths
import au.opendepth.app.storage.GeoTiffImporter
import au.opendepth.app.storage.MbtilesImporter
import au.opendepth.app.storage.RealPathResolver
import au.opendepth.app.terrain.TerrainDrape
import au.opendepth.app.tiles.LocalTileServer
import au.opendepth.app.util.UserFacingErrors
import au.opendepth.app.util.openOnIoClosingOnCancel
import au.opendepth.app.util.runCatchingCancellable
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Owns the ordered overlay stack: imported MBTiles archives, imported GeoTIFF
 * rasters and remote XYZ tile overlays.
 *
 * The list runs bottom to top. Every overlay is inserted below
 * [MapLayerIds.OVERLAY_ANCHOR], so appending puts a new layer on top of the
 * existing overlays while still leaving waypoints, routes and measurements
 * above everything.
 */
class OverlayLayerController(
    private val context: Context,
    private val mapHolder: MapHolder,
    private val repository: OverlayLayerRepository,
    private val scope: CoroutineScope,
    private val onStackChanged: () -> Unit,
    private val onMessage: (String) -> Unit,
    /** A layer was shown or hidden (its credit and the depth readout change; the list itself doesn't). */
    private val onVisibilityChanged: () -> Unit = {}
) {
    private val layers = mutableListOf<OverlayLayer>()
    private val databases = mutableMapOf<String, SQLiteDatabase>()
    private val depthSources = mutableMapOf<String, DepthSource>()
    private val imageBitmaps = mutableMapOf<String, Bitmap>()
    private val layerBounds = mutableMapOf<String, GeoBounds>()
    private val renderTargets = mutableMapOf<String, DepthGridRenderer.Target>()

    // Recolours render off the main thread (a 4096-cell grid takes long
    // enough to freeze the UI). Every touch of [renderTargets]' reused
    // bitmaps — rendering, handing one to MapLibre, recycling — holds this.
    private val renderLock = Any()
    private val recolourJobs = mutableMapOf<String, Job>()

    /** Serves MBTiles and GDAL tiles to MapLibre; started with the first such layer, stopped after the last. */
    private var tileServer: LocalTileServer? = null

    private var restoreJob: Job? = null
    private var restored = false

    /** Decides which files [remove] and the post-restore sweep may delete; see its doc. */
    private val artifacts = AppOwnedArtifacts(
        context.filesDir,
        listOf(
            MBTILES_DIR, GEOTIFF_TILE_CACHE_DIR, SEABED_DIR, GeoTiffImporter.SURVEY_COPY_DIR,
            LEGACY_GEOTIFF_DIR, LEGACY_GEOTIFF_PYRAMID_DIR
        )
    )

    /** While the first restore runs: the stack it is reloading, and layers added before it began. */
    private var restoreSaved: List<OverlayLayer>? = null
    private var restoreAddedEarly: List<OverlayLayer> = emptyList()

    /** The newest [attachOrRestore] caller's callback, run when the restore finishes. */
    private var pendingRestoreCompletion: (() -> Unit)? = null

    /**
     * Saved layers that failed to reload this session although their file was
     * not confirmed missing (a revoked permission, a damaged file, a transient
     * error). Kept in the saved stack — below the loaded layers — and tried
     * again on the next launch; never drawn, and removable from the panel.
     */
    private val unloaded = mutableListOf<OverlayLayer>()

    /** Top-to-bottom order, as listed in the layers panel. */
    val stackTopFirst: List<OverlayLayer> get() = layers.asReversed().toList()

    /** Saved layers that could not be loaded this session; see [unloaded]. */
    val unavailableLayers: List<OverlayLayer> get() = unloaded.toList()

    /** Layers the local tile server currently serves — for the debug resource snapshot. */
    val serverLayerCount: Int get() = tileServer?.activeLayerCount ?: 0

    /** The depth data backing a GeoTIFF layer, or null for other kinds. */
    fun depthSourceFor(id: String): DepthSource? = depthSources[id]

    // Style lifecycle -------------------------------------------------------

    /**
     * Loads the saved layers the first time, then adds them all to the current
     * style. Called after every style rebuild.
     *
     * A rebuild during the first load doesn't cancel it (that would leak what it
     * had opened and read every file again): each layer goes onto whichever
     * style is current when it's ready, and only the newest [onComplete] runs.
     */
    fun attachOrRestore(generation: Int, onComplete: () -> Unit) {
        DiagnosticLog.log(
            TAG,
            "attachOrRestore: generation=$generation restored=$restored loaded=${layers.size} " +
                "restoreJobActive=${restoreJob?.isActive == true} styleCurrent=${mapHolder.styleFor(generation) != null}"
        )

        if (restored) {
            DiagnosticLog.log(TAG, "attachOrRestore: already restored, re-attaching ${layers.map { "${it.kind}:${it.name}" }}")
            attachAll(generation)
            onComplete()
            return
        }

        if (restoreJob?.isActive == true) {
            DiagnosticLog.log(
                TAG,
                "attachOrRestore: restore already in progress, joining it for generation=$generation; " +
                    "re-attaching ${layers.size} already-loaded layer(s)"
            )
            attachAll(generation)
            pendingRestoreCompletion = onComplete
            return
        }

        // Layers added before the first restore (a finished download opened while
        // the style loads) go back on top of the restored stack. persist() hasn't
        // saved them, so the saved stack still has every earlier layer.
        val addedBeforeRestore = layers.toList()
        layers.clear()

        val saved = repository.load()
        restoreSaved = saved
        restoreAddedEarly = addedBeforeRestore
        DiagnosticLog.log(
            TAG,
            "restore: ${saved.size} persisted record(s): " +
                saved.joinToString {
                    "${it.kind}:${it.name}(id=${it.id}, visible=${it.visible}, opacity=${it.opacity}, " +
                        "gdalOnDemand=${it.gdalOnDemand}, source=${loggableSource(it)})"
                }
        )
        if (saved.isEmpty()) {
            addedBeforeRestore.forEach { layer ->
                layers.add(layer)
                attach(layer, generation)
            }
            restored = true
            if (addedBeforeRestore.isNotEmpty()) {
                persist()
                onStackChanged()
            }
            onComplete()
            return
        }

        pendingRestoreCompletion = onComplete
        restoreJob = scope.launch {
            val missing = mutableListOf<OverlayLayer>()
            for (record in saved) {
                val prepared = prepare(record)
                // Only a file confirmed gone drops its layer; any other failure
                // keeps the record to retry next launch (see [unloaded]).
                val confirmedMissing = prepared == null && isConfirmedMissing(record)
                DiagnosticLog.log(
                    TAG,
                    "restore prepare ${record.kind}:${record.name} -> " +
                        when {
                            prepared != null -> "ok"
                            confirmedMissing -> "NULL, file confirmed missing (dropped)"
                            else -> "NULL, file not confirmed missing (kept, retried next launch)"
                        }
                )
                if (!isActive) {
                    // Only release() cancels a restore now, and the opens in
                    // prepare() close their own resource if cancelled
                    // mid-open; this covers a cancel landing after it returned.
                    DiagnosticLog.log(TAG, "restore CANCELLED after preparing ${record.kind}:${record.name}; discarding")
                    prepared?.discard()
                    return@launch
                }
                when {
                    prepared != null -> {
                        commit(prepared)
                        attach(prepared.layer, mapHolder.generation)
                    }
                    confirmedMissing -> missing += record
                    else -> unloaded += record
                }
            }

            if (missing.isNotEmpty()) {
                DiagnosticLog.log(
                    TAG,
                    "restore PRUNING records whose file is confirmed missing: " +
                        missing.joinToString { "${it.kind}:${it.name}(${loggableSource(it)})" }
                )
                onMessage(
                    "Removed from your layers because the file is no longer on this device: " +
                        missing.joinToString { it.name }
                )
            }
            addedBeforeRestore.forEach { layer ->
                layers.add(layer)
                attach(layer, mapHolder.generation)
            }
            restored = true
            restoreSaved = null
            restoreAddedEarly = emptyList()
            // Always: changes made to layers while restore ran were held back.
            persist()
            DiagnosticLog.log(
                TAG,
                "restore complete: ${layers.size}/${saved.size} layer(s) loaded, generation=${mapHolder.generation}"
            )
            onStackChanged()
            ResourceTracker.snapshot(context, "restore complete", serverLayerCount)
            sweepOrphanedArtifacts()
            val completion = pendingRestoreCompletion
            pendingRestoreCompletion = null
            completion?.invoke()
        }
    }

    /**
     * Deletes app files no saved layer uses (left by older builds or a crashed
     * import). Runs once after the restore, when every layer is known; files
     * from this session are never touched (see [AppOwnedArtifacts.sweepOrphans]).
     */
    private fun sweepOrphanedArtifacts() {
        // Restore has just persisted exactly these, so they are the saved stack.
        val referenced = (unloaded + layers).flatMap(::artifactPathsOf)
        scope.launch(Dispatchers.IO) {
            val deleted = artifacts.sweepOrphans(referenced, olderThanMillis = PROCESS_START_MILLIS)
            if (deleted.isNotEmpty()) {
                DiagnosticLog.log(
                    TAG,
                    "orphan sweep: deleted ${deleted.size} file(s), ${deleted.sumOf { it.length() }} bytes: " +
                        deleted.joinToString { it.name }
                )
            }
        }
    }

    /** Re-adds every already-loaded layer to a freshly created style. */
    fun attachAll(generation: Int) {
        layers.forEach { layer ->
            if (!mapHolder.isCurrent(generation)) return
            attach(layer, generation)
        }
    }

    /** Removes and re-adds the whole stack, applying the current order. */
    private fun reattachStack() {
        val style = mapHolder.style ?: return
        layers.forEach { layer -> detach(style, layer.id) }
        layers.forEach { layer -> attach(layer, mapHolder.generation) }
    }

    // Adding layers ---------------------------------------------------------

    fun addMbtiles(imported: MbtilesImporter.ImportedMbtiles, attribution: String = "") {
        val id = OverlayLayerRepository.newLayerId()
        databases[id] = imported.database

        val layer = OverlayLayer(
            id = id,
            kind = LayerKind.MBTILES,
            name = imported.displayName,
            source = imported.file.absolutePath,
            attribution = attribution
        )
        // Imagery and raster charts go under depth surveys; a vector chart's
        // lines and labels stay on top.
        val metadata = readMbtilesMetadata(imported.database)
        mbtilesMetadata[id] = metadata
        appendLayer(layer, belowDepthLayers = !metadata.isVector)
    }

    /** The loaded layer opened from [source] (a picked document's URI), if any. */
    fun layerWithSource(source: String): OverlayLayer? = layers.firstOrNull { it.source == source }

    /** Where a loaded layer lies, when known. */
    fun boundsOf(id: String): GeoBounds? = layerBounds[id] ?: depthSources[id]?.bounds

    private val layerMinZoom = mutableMapOf<String, Double>()
    /** Each MBTiles layer's metadata, read once (off the main thread on restore): attaching runs at every style change. */
    private val mbtilesMetadata = mutableMapOf<String, MbtilesMetadata>()

    /** The least zoom a layer draws at (an MBTiles pack's minzoom), when it has one. */
    fun minZoomOf(id: String): Double? = layerMinZoom[id]

    /**
     * The raster layers that can cover the 3D terrain, top of the stack
     * first: offline MBTiles packs, read straight from the file, and XYZ
     * links. Vector packs (contours) are left out.
     */
    fun terrainDrapes(): List<TerrainDrape> = stackTopFirst.mapNotNull { layer ->
        when (layer.kind) {
            LayerKind.XYZ -> TerrainDrape(
                layer.id, layer.name, 0, layer.maxZoom.coerceIn(1, OverlayLayer.DEFAULT_MAX_ZOOM),
                urlTemplate = layer.source, attribution = layer.attribution
            )
            LayerKind.MBTILES -> {
                val database = databases[layer.id] ?: return@mapNotNull null
                val metadata = mbtilesMetadata.getOrPut(layer.id) { readMbtilesMetadata(database) }
                if (metadata.isVector) return@mapNotNull null
                TerrainDrape(
                    layer.id, layer.name, metadata.minZoom, metadata.maxZoom,
                    attribution = layer.attribution.ifBlank { metadata.attribution },
                    bounds = metadata.bounds ?: layerBounds[layer.id]
                ) { z, x, y ->
                    // MBTiles rows count from the south (TMS). The layer may be
                    // removed, and its file closed, while the 3D view reads it.
                    runCatching {
                        database.rawQuery(
                            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                            arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())
                        ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
                    }.getOrNull()
                }
            }
            LayerKind.GEOTIFF -> null
        }
    }

    /** Adds the imported file as a new layer and returns its id. */
    fun addGeoTiff(imported: GeoTiffImporter.ImportedGeoTiff, attribution: String = ""): String {
        val id = OverlayLayerRepository.newLayerId()

        var gdalOnDemand = false
        var gdalCachePath: String? = null
        var maxZoom = OverlayLayer.DEFAULT_MAX_ZOOM

        val (source, ramp) = when (imported) {
            is GeoTiffImporter.ImportedGeoTiff.InMemory -> {
                val result = imported.result
                layerBounds[id] = result.bounds
                imported.sourceUri to when (result) {
                    is GeoTiffReader.Result.Depth -> {
                        depthSources[id] = InMemoryDepthGrid(result.grid)
                        autoFitRamp(result.grid)
                    }

                    is GeoTiffReader.Result.Image -> {
                        imageBitmaps[id] = result.bitmap
                        null
                    }
                }
            }

            is GeoTiffImporter.ImportedGeoTiff.OnDemand -> {
                val tileSource = imported.tileSource
                layerBounds[id] = tileSource.bounds
                depthSources[id] = tileSource
                gdalOnDemand = true
                gdalCachePath = imported.cacheFile.absolutePath
                maxZoom = tileSource.nativeMaxZoom
                imported.sourceUri to autoFitRamp(tileSource)
            }
        }

        appendLayer(
            OverlayLayer(
                id = id,
                kind = LayerKind.GEOTIFF,
                name = imported.displayName,
                source = source,
                ramp = ramp,
                attribution = attribution,
                maxZoom = maxZoom,
                gdalOnDemand = gdalOnDemand,
                gdalCachePath = gdalCachePath
            )
        )
        return id
    }

    fun addXyz(
        name: String,
        urlTemplate: String,
        attribution: String,
        opacity: Float = 1f,
        maxZoom: Int = OverlayLayer.DEFAULT_MAX_ZOOM
    ) {
        appendLayer(
            OverlayLayer(
                id = OverlayLayerRepository.newLayerId(),
                kind = LayerKind.XYZ,
                name = name,
                source = urlTemplate,
                opacity = opacity.coerceIn(0f, 1f),
                attribution = attribution,
                maxZoom = maxZoom
            ),
            // Online imagery/maps are opaque: under any depth survey.
            belowDepthLayers = true
        )
    }

    fun addXyzPreset(preset: XyzTilePreset, opacity: Float = 1f) {
        addXyz(
            name = preset.label,
            urlTemplate = preset.urlTemplate,
            attribution = preset.attribution,
            opacity = opacity,
            maxZoom = preset.maxZoom
        )
    }

    /**
     * Adds [layer] at the top, or with [belowDepthLayers] just under the
     * lowest depth survey: an opaque imagery download or online map layered
     * over a survey hid it completely.
     */
    private fun appendLayer(layer: OverlayLayer, belowDepthLayers: Boolean = false) {
        val lowestDepth = layers.indexOfFirst { depthSources.containsKey(it.id) }
        if (belowDepthLayers && lowestDepth >= 0) {
            layers.add(lowestDepth, layer)
            reattachStack()
        } else {
            layers.add(layer)
            attach(layer, mapHolder.generation)
        }
        persist()
        onStackChanged()
        ResourceTracker.snapshot(context, "layer attach ${layer.kind}", serverLayerCount)
    }

    // Mutating layers -------------------------------------------------------

    fun setVisible(id: String, visible: Boolean) {
        val layer = updateLayer(id) { it.copy(visible = visible) }
        // The only writer of a persisted visible=false; logged so a layer
        // that restores hidden can be traced back to when it was hidden.
        DiagnosticLog.log(
            TAG,
            "setVisible: layer=${layer?.name ?: "unknown"} (id=$id) visible=$visible " +
                "restored=$restored loaded=${layers.size}"
        )
        layer?.let { applyPaint(it) }
        persist()
        onVisibilityChanged()
    }

    fun setOpacity(id: String, opacity: Float, persistNow: Boolean) {
        updateLayer(id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
            ?.let { applyPaint(it) }
        if (persistNow) persist()
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        updateLayer(id) { it.copy(name = trimmed) }
        persist()
        onStackChanged()
    }

    /** Applies a new palette or depth range without re-reading the file. */
    /**
     * Applies a new depth-colour tile size (from the GeoTIFF quality setting)
     * to every GDAL-backed layer, and re-attaches them so MapLibre fetches
     * the sharper tiles instead of its cached ones.
     */
    fun setGdalTileSize(size: Int) {
        GdalTileSource.defaultColourTileSize = size
        val changed = layers.toList().mapNotNull { layer ->
            val source = depthSources[layer.id] as? GdalTileSource ?: return@mapNotNull null
            if (source.colourTileSize == size) null else layer to source
        }
        if (changed.isEmpty()) return
        // Emptying a big tile cache takes a while, so it runs off the main
        // thread; on it, Android would report the app as not responding.
        scope.launch {
            withContext(Dispatchers.IO) { changed.forEach { (_, source) -> source.setColourTileSize(size) } }
            changed.forEach { (layer, _) ->
                layers.firstOrNull { it.id == layer.id }?.let { attach(it, mapHolder.generation) }
            }
        }
    }

    fun setRamp(id: String, ramp: DepthRampSettings) {
        val layer = updateLayer(id) { it.copy(ramp = ramp.normalised()) } ?: return
        val depthSource = depthSources[id] ?: return

        if (layer.gdalOnDemand) {
            // Tiles drawn with the old colours are in this cache and in MapLibre's: the
            // first is emptied, and the layer is re-added with a new tile URL to skip the
            // second. Emptying a big survey's cache takes a while, so it runs off the
            // main thread, and only the last of a burst of changes (a slider drag)
            // re-adds the layer.
            val effectiveRamp = layer.ramp ?: ramp
            recolourJobs.remove(id)?.cancel()
            recolourJobs[id] = scope.launch {
                withContext(Dispatchers.IO) { (depthSource as? GdalTileSource)?.clearCache(effectiveRamp) }
                tileServer?.updateGdalRamp(id, effectiveRamp)
                layers.firstOrNull { it.id == id }?.let { attach(it, mapHolder.generation) }
            }
            persist()
            onStackChanged()
            return
        }

        val mapSource = mapHolder.style?.getSourceAs<ImageSource>(MapLayerIds.sourceFor(id))
        if (mapSource == null) {
            attach(layer, mapHolder.generation)
        } else {
            val effectiveRamp = layer.ramp ?: ramp
            recolourJobs.remove(id)?.cancel()
            recolourJobs[id] = scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.Default) {
                        renderDepth(layer.id, depthSource.previewGrid(), effectiveRamp)
                    }
                    // Re-fetched: the style may have been rebuilt while rendering.
                    synchronized(renderLock) {
                        mapHolder.style?.getSourceAs<ImageSource>(MapLayerIds.sourceFor(id))?.setImage(bitmap)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Recolour failed for ${layer.name}", error)
                    DiagnosticLog.logError(TAG, "recolour FAILED: layer=${layer.name}", error)
                    onMessage("Could not apply the new colours to ${layer.name}")
                }
            }
        }
        persist()
        onStackChanged()
    }

    /** Moves a layer up or down the draw order by [delta] positions. */
    fun move(id: String, delta: Int) {
        val currentIndex = layers.indexOfFirst { it.id == id }
        if (currentIndex < 0) return
        val newIndex = (currentIndex + delta).coerceIn(0, layers.lastIndex)
        if (newIndex == currentIndex) return

        layers.add(newIndex, layers.removeAt(currentIndex))
        reattachStack()
        persist()
        onStackChanged()
    }

    /** Sends a layer to the bottom of the overlay stack. */
    fun sendToBottom(id: String) {
        val currentIndex = layers.indexOfFirst { it.id == id }
        if (currentIndex <= 0) return
        layers.add(0, layers.removeAt(currentIndex))
        reattachStack()
        persist()
        onStackChanged()
    }

    /**
     * Removes a layer from the map. The user's own files are never deleted, only
     * the app's files for the layer (its tile cache, a private copy).
     * [AppOwnedArtifacts] refuses any other path.
     */
    fun remove(id: String) {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) {
            removeUnloaded(id)
            return
        }
        val layer = layers.removeAt(index)

        mapHolder.style?.let { style -> detach(style, id) }
        releaseResources(id)
        releaseContentUriPermission(layer)
        deleteArtifactsOf(layer)

        persist()
        onStackChanged()
        // Queued behind this layer's close and file deletion, so the counts reflect them.
        val serverLayers = serverLayerCount
        RELEASE_EXECUTOR.execute { ResourceTracker.snapshot(context, "layer remove ${layer.kind}", serverLayers) }
    }

    /** As [remove], for a layer in [unloaded]: nothing is attached or open, so only bookkeeping and app-owned files. */
    private fun removeUnloaded(id: String) {
        val index = unloaded.indexOfFirst { it.id == id }
        if (index < 0) return
        val layer = unloaded.removeAt(index)
        releaseContentUriPermission(layer)
        deleteArtifactsOf(layer)
        persist()
        onStackChanged()
    }

    /**
     * True only when [record]'s file is positively gone: a plain path that no
     * longer exists, or a `content://` document its provider reports as not
     * found. A permission error or any other failure is not proof — the file
     * may still be there — so it returns false and the layer is kept.
     *
     * A plain path outside this app's own storage only counts while the app
     * still has storage access: with all-files access revoked, `exists()` is
     * false for another app's file that is still there, and pruning on that
     * would silently drop the user's layer for good.
     */
    private suspend fun isConfirmedMissing(record: OverlayLayer): Boolean {
        if (record.kind == LayerKind.XYZ) return false
        val source = record.source
        return withContext(Dispatchers.IO) {
            if (source.startsWith("content://")) {
                try {
                    context.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.close()
                    false
                } catch (notFound: java.io.FileNotFoundException) {
                    true
                } catch (other: Exception) {
                    false
                }
            } else {
                val file = File(source)
                !file.exists() && (isAppPrivate(file) || hasSharedStorageAccess())
            }
        }
    }

    private fun isAppPrivate(file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun hasSharedStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private inline fun updateLayer(
        id: String,
        transform: (OverlayLayer) -> OverlayLayer
    ): OverlayLayer? {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(layers[index])
        layers[index] = updated
        return updated
    }

    // Depth lookups ---------------------------------------------------------

    /**
     * The visible GeoTIFF depth layers' sources, topmost first — read on the
     * main thread, which owns the layer stack, and then sampled off it by
     * [DepthQueryController] (a GDAL sample can wait behind a tile render).
     */
    fun visibleDepthSourcesTopFirst(): List<DepthSource> =
        layers.asReversed().filter { it.visible }.mapNotNull { depthSources[it.id] }

    /** Vector MBTiles layer ids, which is where contour features live. */
    fun contourLayerIds(): Array<String> {
        val style = mapHolder.style ?: return emptyArray()
        return layers
            .filter { it.visible && it.kind == LayerKind.MBTILES }
            .map { MapLayerIds.layerFor(it.id) }
            .filter { style.getLayer(it) is LineLayer }
            .toTypedArray()
    }

    /** Attribution strings for every visible overlay, for the credit line. */
    fun visibleAttributions(): List<String> = layers
        .filter { it.visible }
        // A pack loaded without a credit uses the one written in the file.
        .map { it to it.attribution.ifBlank { mbtilesMetadata[it.id]?.attribution.orEmpty() } }
        .filter { (_, text) -> text.isNotBlank() }
        .map { (layer, text) ->
            // Esri's terms ask for "Powered by Esri"; layers added by older builds were saved without it.
            if (layer.source.contains("arcgis", ignoreCase = true) && !text.contains("Powered by Esri")) {
                "$text · Powered by Esri"
            } else {
                text
            }
        }
        .distinct()

    // Teardown --------------------------------------------------------------

    fun release() {
        saveHeldBackLayers()
        restoreJob?.cancel()
        restoreJob = null
        pendingRestoreCompletion = null
        // Stop the server first, so no request thread can be handed a database or
        // GDAL source that is about to be closed.
        runCatching { tileServer?.stop() }
        tileServer = null
        databases.values.toSet().forEach { database ->
            runCatching { database.close() }
        }
        databases.clear()
        depthSources.values.toSet().forEach(::closeOffMainThread)
        depthSources.clear()
        imageBitmaps.clear()
        layerBounds.clear()
        layerMinZoom.clear()
        mbtilesMetadata.clear()
        recolourJobs.values.forEach { it.cancel() }
        recolourJobs.clear()
        synchronized(renderLock) {
            renderTargets.values.forEach { it.recycle() }
            renderTargets.clear()
            shades.clear()
        }
    }

    private fun releaseResources(id: String) {
        // As in release(): take the layer off the server before closing what it
        // served from.
        tileServer?.let { server ->
            server.removeMbtiles(id)
            server.removeGdal(id)
        }

        databases.remove(id)?.let { database -> runCatching { database.close() } }
        depthSources.remove(id)?.let(::closeOffMainThread)
        imageBitmaps.remove(id)
        layerBounds.remove(id)
        layerMinZoom.remove(id)
        mbtilesMetadata.remove(id)
        recolourJobs.remove(id)?.cancel()
        synchronized(renderLock) {
            renderTargets.remove(id)?.recycle()
            shades.remove(id)
        }

        tileServer?.let { server ->
            if (server.activeLayerCount == 0) {
                runCatching { server.stop() }
                tileServer = null
            }
        }
    }

    /**
     * A [GdalTileSource]'s close waits for its dataset lock, which a tile
     * render on a NanoHTTPD thread can hold for seconds (a low-zoom tile of a
     * large raster without overviews reads much of the file) — on the main
     * thread that is an ANR when removing a layer or leaving the app. The
     * source is already unregistered from the tile server, so nothing new can
     * reach it; it is closed on [RELEASE_EXECUTOR] instead.
     */
    private fun closeOffMainThread(source: DepthSource) {
        if (source !is AutoCloseable) return
        RELEASE_EXECUTOR.execute {
            runCatching { source.close() }
                .onFailure { DiagnosticLog.logError(TAG, "closing a depth source FAILED", it) }
        }
    }

    /** Starts the shared local tile server on first use; see [tileServer]'s doc. */
    private fun ensureTileServer(): LocalTileServer =
        tileServer ?: LocalTileServer.start().also { tileServer = it }

    /** Runs after [releaseResources]; deletion is queued behind its closes. */
    private fun deleteArtifactsOf(layer: OverlayLayer) {
        val stillReferenced = (unloaded + layers).flatMap(::artifactPathsOf).toSet()
        val paths = artifactPathsOf(layer).filter { it !in stillReferenced }
        if (paths.isEmpty()) return
        // Same single thread as closeOffMainThread, so a cache is only
        // deleted after its source has closed it — and not skipped when the
        // Activity scope is cancelled right after a removal.
        RELEASE_EXECUTOR.execute {
            val deleted = paths.flatMap(artifacts::delete)
            DiagnosticLog.log(
                TAG,
                "remove ${layer.kind}:${layer.name}: deleted ${deleted.size} app-owned file(s): " +
                    deleted.joinToString { it.name }
            )
        }
    }

    /**
     * Every path a layer *might* own. Deliberately unfiltered (and so free of
     * disk I/O on the main thread): [AppOwnedArtifacts] makes the actual
     * app-owned decision on the IO thread, and refuses a real user path.
     */
    private fun artifactPathsOf(layer: OverlayLayer): List<String> = listOfNotNull(
        // GEOTIFF too: a layer imported by a pre-1.0 build may still load
        // from its private copy (or pyramid) in a legacy directory, and must
        // count as a reference so the sweep keeps that file.
        layer.source.takeIf { layer.kind == LayerKind.MBTILES || layer.kind == LayerKind.GEOTIFF },
        layer.gdalCachePath
    )

    /**
     * Releases this app's own persisted read-permission grant for a
     * `content://`-sourced layer — bookkeeping for a permission grant, not
     * a file.
     */
    private fun releaseContentUriPermission(layer: OverlayLayer) {
        if (layer.kind == LayerKind.XYZ) return
        if (!layer.source.startsWith("content://")) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(layer.source),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * Saves the stack — but never before the first restore has finished:
     * until then [layers] holds only part of it (or only a layer added early),
     * and saving that would overwrite every layer not yet reloaded. Restore
     * persists once it completes, which captures any change held back here.
     */
    private fun persist() {
        if (restored) repository.save(unloaded + layers)
    }

    /**
     * [persist] holds back saves until the first restore finishes; if this
     * controller is torn down first, layers added in the meantime would be
     * lost (and their app-owned files later swept as orphans). Saves the
     * saved stack plus those additions instead. Edits to restored layers made
     * during the restore are not kept — they are reloaded as last saved.
     */
    private fun saveHeldBackLayers() {
        if (restored) return
        val base = restoreSaved ?: repository.load()
        val baseIds = base.map { it.id }.toSet()
        val added = (restoreAddedEarly + layers + unloaded).filter { it.id !in baseIds }.distinctBy { it.id }
        if (added.isNotEmpty()) repository.save(base + added)
    }

    /** A layer's source for the shareable debug log: XYZ templates can carry an API key. */
    private fun loggableSource(layer: OverlayLayer): String =
        if (layer.kind == LayerKind.XYZ) redactUrl(layer.source) else layer.source

    // Restoring from disk ---------------------------------------------------

    /**
     * Everything a restored layer needs, loaded off the main thread but not yet
     * published.
     *
     * Loading happens without touching shared state so a restore that gets
     * superseded mid-read can be discarded wholesale, rather than half-writing
     * over the state of the restore that replaced it.
     */
    private class Prepared(
        val layer: OverlayLayer,
        val database: SQLiteDatabase? = null,
        val depthSource: DepthSource? = null,
        val bitmap: Bitmap? = null,
        val bounds: GeoBounds? = null,
        val mbtiles: MbtilesMetadata? = null
    ) {
        fun discard() {
            database?.let { runCatching { it.close() } }
            (depthSource as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    private suspend fun prepare(record: OverlayLayer): Prepared? = when (record.kind) {
        LayerKind.XYZ -> Prepared(record)

        LayerKind.MBTILES -> {
            val file = File(record.source)
            if (!existsOffMain(file)) {
                DiagnosticLog.log(TAG, "restore MBTiles ${record.name}: file.exists()=false")
                null
            } else {
                runCatchingCancellable {
                    openOnIoClosingOnCancel({ MbtilesImporter.openReadOnly(file) }) { it.close() }
                }.onFailure { error ->
                    Log.e(TAG, "Reopening ${record.name} failed", error)
                    DiagnosticLog.logError(TAG, "restore MBTiles ${record.name}: openReadOnly FAILED", error)
                    onMessage("Could not reload ${record.name}")
                }.map { database ->
                    val metadata = withContext(Dispatchers.IO) { readMbtilesMetadata(database) }
                    val bounds = metadata.bounds
                        ?: withContext(Dispatchers.IO) { mbtilesTileBounds(database, metadata.maxZoom) }
                    Prepared(record, database = database, bounds = bounds, mbtiles = metadata)
                }.getOrNull()
            }
        }

        LayerKind.GEOTIFF -> if (record.gdalOnDemand) prepareGdalOnDemand(record) else prepareGeoTiff(record)
    }

    /** A GeoTIFF layer saved before on-demand rendering: a tile pyramid on disk, or the whole file in memory. */
    private suspend fun prepareGeoTiff(record: OverlayLayer): Prepared? {
        val asFile = File(record.source)
        if (!TiledDepthPyramid.isPyramidFile(asFile)) {
            return readGeoTiffForRestore(record)?.let { result ->
                when (result) {
                    is GeoTiffReader.Result.Depth -> Prepared(
                        layer = if (record.ramp == null) record.copy(ramp = autoFitRamp(result.grid)) else record,
                        depthSource = InMemoryDepthGrid(result.grid),
                        bounds = result.bounds
                    )

                    is GeoTiffReader.Result.Image -> Prepared(
                        layer = record,
                        bitmap = result.bitmap,
                        bounds = result.bounds
                    )
                }
            }
        }
        if (!existsOffMain(asFile)) return null
        return runCatchingCancellable { openOnIoClosingOnCancel({ TiledDepthPyramid.open(asFile) }) { it.close() } }
            .onFailure { error ->
                Log.e(TAG, "Reopening pyramid ${record.name} failed", error)
                DiagnosticLog.logError(TAG, "restore pyramid ${record.name} FAILED", error)
                onMessage("Could not reload ${record.name}")
            }
            .map { pyramid ->
                Prepared(
                    layer = if (record.ramp == null) record.copy(ramp = autoFitRamp(pyramid)) else record,
                    depthSource = pyramid,
                    bounds = pyramid.bounds
                )
            }
            .getOrNull()
    }

    /**
     * Reopens a GDAL layer: finds the real path for its saved `content://` URI
     * again ([RealPathResolver]) and reuses its tile cache, so tiles drawn
     * before don't have to be drawn again.
     */
    private suspend fun prepareGdalOnDemand(record: OverlayLayer): Prepared? {
        val cachePath = record.gdalCachePath
        if (cachePath == null) {
            Log.e(TAG, "GDAL layer ${record.name} has no recorded tile cache path")
            DiagnosticLog.log(TAG, "restore GDAL ${record.name}: no recorded tile cache path -> dropping")
            return null
        }

        // A plain filesystem path is already real; RealPathResolver only
        // resolves content:// URIs, so only those are passed through it.
        val isContentUri = record.source.startsWith("content://")
        val realPath = if (isContentUri) {
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(record.source)
                RealPathResolver.readablePath(context, uri) ?: runCatching {
                    GdalSupport.ensureInitialized(context)
                    DescriptorPaths.gdalPathFor(context, uri)
                }.getOrNull()
            }
        } else {
            record.source
        }
        val isInPlace = realPath?.startsWith("/vsiseabathyfd/") == true
        if (realPath == null || (!isInPlace && !withContext(Dispatchers.IO) { RealPathResolver.canOpen(realPath) })) {
            Log.e(TAG, "Reopening GDAL layer ${record.name} failed: no readable real path")
            DiagnosticLog.log(TAG, "restore GDAL ${record.name}: no readable real path (source=${record.source})")
            onMessage("Could not reload ${record.name} — its access permission may have been revoked")
            return null
        }

        return runCatchingCancellable {
            openOnIoClosingOnCancel({ GdalTileSource.open(context, realPath, File(cachePath)) }) { it.close() }
        }.onFailure { error ->
            Log.e(TAG, "Reopening GDAL layer ${record.name} failed", error)
            DiagnosticLog.logError(TAG, "restore GDAL ${record.name} FAILED", error)
            onMessage("Could not reload ${record.name}")
        }.map { tileSource ->
            Prepared(
                layer = if (record.ramp == null) record.copy(ramp = autoFitRamp(tileSource)) else record,
                depthSource = tileSource,
                bounds = tileSource.bounds
            )
        }.getOrNull()
    }

    /**
     * Reopens a persisted GeoTIFF layer's source, which is either a
     * `content://` URI (reopened the same way [GeoTiffImporter] resolves
     * one) or a plain filesystem path (a private copy made by a pre-1.0
     * build, still read so those layers keep loading).
     */
    private suspend fun readGeoTiffForRestore(record: OverlayLayer): GeoTiffReader.Result? {
        val isContentUri = record.source.startsWith("content://")

        val outcome = if (isContentUri) {
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    GeoTiffImporter.readFrom(context, Uri.parse(record.source))
                        ?: throw GeoTiffReader.UnsupportedGeoTiffException(
                            "Cannot reopen this file — its access permission may have been revoked"
                        )
                }
            }
        } else {
            val file = File(record.source)
            if (!existsOffMain(file)) return null
            runCatchingCancellable { withContext(Dispatchers.IO) { GeoTiffReader.read(file) } }
        }

        return outcome.onFailure { error ->
            Log.e(TAG, "Re-reading ${record.name} failed", error)
            DiagnosticLog.logError(TAG, "restore GeoTIFF ${record.name} FAILED", error)
            onMessage("Could not reload ${record.name}")
        }.getOrNull()
    }

    /** Restore runs on the main dispatcher; even a stat is disk I/O to StrictMode. */
    private suspend fun existsOffMain(file: File): Boolean =
        withContext(Dispatchers.IO) { file.exists() }

    /** Publishes a prepared layer into the live stack. */
    private fun commit(prepared: Prepared) {
        val id = prepared.layer.id
        prepared.database?.let { databases[id] = it }
        prepared.depthSource?.let { depthSources[id] = it }
        prepared.bitmap?.let { imageBitmaps[id] = it }
        prepared.bounds?.let { layerBounds[id] = it }
        prepared.mbtiles?.let { mbtilesMetadata[id] = it }
        layers.add(prepared.layer)
    }

    // Attaching a single layer ---------------------------------------------

    private fun attach(layer: OverlayLayer, generation: Int) {
        val style = mapHolder.styleFor(generation) ?: run {
            DiagnosticLog.log(
                TAG,
                "attach SKIPPED (no current style): ${layer.kind}:${layer.name} generation=$generation " +
                    "current=${mapHolder.generation} styleSet=${mapHolder.style != null}"
            )
            return
        }
        detach(style, layer.id)

        runCatching {
            when (layer.kind) {
                LayerKind.MBTILES -> attachMbtiles(style, layer)
                LayerKind.GEOTIFF -> attachGeoTiff(style, layer)
                LayerKind.XYZ -> attachXyz(style, layer)
            }
        }.onSuccess {
            val mode = if (layer.kind == LayerKind.GEOTIFF) {
                if (layer.gdalOnDemand) " mode=gdalOnDemand(LocalTileServer)" else " mode=ImageSource(in-memory)"
            } else {
                ""
            }
            DiagnosticLog.log(
                TAG,
                "attach ${layer.kind} SUCCESS: layer=${layer.name} visible=${layer.visible} opacity=${layer.opacity}$mode " +
                    "styleHasLayer=${style.getLayer(MapLayerIds.layerFor(layer.id)) != null}"
            )
        }.onFailure { error ->
            Log.e(TAG, "Attaching ${layer.name} failed", error)
            DiagnosticLog.logError(TAG, "attach ${layer.kind} FAILED: layer=${layer.name}", error)
            // Tell the user: otherwise the layer sits in the stack with
            // working controls but never draws, with no explanation.
            onMessage(UserFacingErrors.describe(error, "Could not display ${layer.name}"))
        }
    }

    private fun detach(style: Style, id: String) {
        runCatching { style.removeLayer(MapLayerIds.layerFor(id)) }
        runCatching { style.removeSource(MapLayerIds.sourceFor(id)) }
    }

    private fun attachMbtiles(style: Style, layer: OverlayLayer) {
        val database = databases[layer.id] ?: run {
            DiagnosticLog.log(TAG, "attachMbtiles: NO open database registered for ${layer.name} (id=${layer.id}) -- nothing added")
            return
        }
        val metadata = mbtilesMetadata.getOrPut(layer.id) { readMbtilesMetadata(database) }
        // Where it lies, for "go to layer" in the list (the tile scan only once).
        if (layer.id !in layerBounds) {
            (metadata.bounds ?: mbtilesTileBounds(database, metadata.maxZoom))?.let { layerBounds[layer.id] = it }
        }
        layerMinZoom[layer.id] = metadata.minZoom.toDouble()
        val server = ensureTileServer()
        server.putMbtiles(layer.id, database, metadata.isVector)
        DiagnosticLog.log(
            TAG,
            "attachMbtiles: layer=${layer.name} url=${server.urlTemplateFor(layer.id, LocalTileServer.Kind.MBTILES)} " +
                "minZoom=${metadata.minZoom} maxZoom=${metadata.maxZoom} isVector=${metadata.isVector} " +
                "mapLibreConnected=${runCatching { org.maplibre.android.MapLibre.isConnected() }.getOrNull()}"
        )

        val tileSet = TileSet(TILEJSON_VERSION, server.urlTemplateFor(layer.id, LocalTileServer.Kind.MBTILES)).apply {
            minZoom = metadata.minZoom.toFloat()
            maxZoom = metadata.maxZoom.toFloat()
        }
        val sourceId = MapLayerIds.sourceFor(layer.id)
        val layerId = MapLayerIds.layerFor(layer.id)
        val opacity = paintOpacity(layer)

        if (metadata.isVector) {
            style.addSource(VectorSource(sourceId, tileSet))
            val contourLayer = LineLayer(layerId, sourceId).apply {
                setSourceLayer(VECTOR_CONTOUR_SOURCE_LAYER)
                setProperties(
                    PropertyFactory.lineColor(CONTOUR_COLOR),
                    PropertyFactory.lineWidth(1f),
                    PropertyFactory.lineOpacity(opacity)
                )
            }
            style.addLayerBelow(contourLayer, MapLayerIds.OVERLAY_ANCHOR)
        } else {
            style.addSource(RasterSource(sourceId, tileSet, RASTER_TILE_SIZE))
            style.addLayerBelow(
                RasterLayer(layerId, sourceId).withProperties(
                    PropertyFactory.rasterOpacity(opacity)
                ),
                MapLayerIds.OVERLAY_ANCHOR
            )
        }
    }

    private fun attachGeoTiff(style: Style, layer: OverlayLayer) {
        if (layer.gdalOnDemand) {
            attachGdalOnDemand(style, layer)
            return
        }

        val bounds = layerBounds[layer.id] ?: throw GeoTiffReader.UnsupportedGeoTiffException(
            "This layer has no recorded geographic bounds"
        )
        val bitmap = imageBitmaps[layer.id]
            ?: depthSources[layer.id]?.let { source ->
                renderDepth(layer.id, source.previewGrid(), layer.ramp ?: autoFitRamp(source))
            }
            ?: throw GeoTiffReader.UnsupportedGeoTiffException(
                "This layer's pixel data was never decoded into memory " +
                    "(neither an image nor a depth source is cached for it)"
            )

        val sourceId = MapLayerIds.sourceFor(layer.id)
        style.addSource(ImageSource(sourceId, bounds.toQuad(), bitmap))
        style.addLayerBelow(
            RasterLayer(MapLayerIds.layerFor(layer.id), sourceId).withProperties(
                PropertyFactory.rasterOpacity(paintOpacity(layer)),
                // Depth cells are meaningful values, not photographs, so keep
                // the cell edges crisp instead of smearing between them.
                PropertyFactory.rasterResampling(
                    if (depthSources.containsKey(layer.id)) {
                        Property.RASTER_RESAMPLING_NEAREST
                    } else {
                        Property.RASTER_RESAMPLING_LINEAR
                    }
                ),
                PropertyFactory.rasterFadeDuration(0f)
            ),
            MapLayerIds.OVERLAY_ANCHOR
        )
    }

    /**
     * A GDAL layer as a raster source from [LocalTileServer], drawn on demand
     * up to [OverlayLayer.maxZoom], the file's real detail.
     */
    private fun attachGdalOnDemand(style: Style, layer: OverlayLayer) {
        val source = depthSources[layer.id] as? GdalTileSource
            ?: throw GeoTiffReader.UnsupportedGeoTiffException(
                "This layer's GDAL tile source is not loaded"
            )
        val bounds = layerBounds[layer.id] ?: source.bounds
        val server = ensureTileServer()
        val ramp = layer.ramp ?: autoFitRamp(source)
        server.putGdal(layer.id, source, ramp)

        // Ramp-versioned so a later setRamp's re-attach forces MapLibre to
        // treat the source as new and refetch every visible tile, rather
        // than serving stale-coloured tiles out of its own client cache.
        // Size-versioned for the same reason: a quality change re-renders every tile.
        val tileSize = source.colourTileSize
        val url = server.urlTemplateFor(layer.id, LocalTileServer.Kind.GDAL) + "?ramp=${ramp.hashCode()}&px=$tileSize&v=${GdalTileSource.RENDER_VERSION}"
        // A tile of N x 256 pixels already holds the source's full detail one
        // zoom level (or more) earlier, so stop there and let MapLibre
        // overzoom, rather than rendering tiles finer than the data itself.
        val extraZoom = floor(ln(tileSize / WebMercatorTiles.TILE_SIZE.toDouble()) / ln(2.0)).toInt().coerceAtLeast(0)
        val tileSet = TileSet(TILEJSON_VERSION, url).apply {
            minZoom = 0f
            maxZoom = (layer.sanitisedMaxZoom - extraZoom).coerceAtLeast(1f)
            setBounds(bounds.west.toFloat(), bounds.south.toFloat(), bounds.east.toFloat(), bounds.north.toFloat())
        }

        val sourceId = MapLayerIds.sourceFor(layer.id)
        style.addSource(RasterSource(sourceId, tileSet, RASTER_TILE_SIZE))
        style.addLayerBelow(
            RasterLayer(MapLayerIds.layerFor(layer.id), sourceId).withProperties(
                PropertyFactory.rasterOpacity(paintOpacity(layer)),
                // Linear, not nearest: when MapLibre stretches a tile, nearest
                // turns every survey cell into a hard-edged block.
                PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
                PropertyFactory.rasterFadeDuration(0f)
            ),
            MapLayerIds.OVERLAY_ANCHOR
        )
        // Relief shading is now baked into the colour tiles themselves, at
        // their full pixel size (see ReliefShading); MapLibre's own hillshade
        // only had 256px elevation tiles to work from and showed far less.
        DiagnosticLog.log(
            TAG,
            "attachGdalOnDemand: layer=${layer.name} url=$url maxZoom=${layer.sanitisedMaxZoom} " +
                "bounds=$bounds"
        )
    }

    private fun attachXyz(style: Style, layer: OverlayLayer) {
        val sourceId = MapLayerIds.sourceFor(layer.id)
        val tileSet = TileSet(TILEJSON_VERSION, layer.source).apply {
            minZoom = 0f
            maxZoom = layer.sanitisedMaxZoom
        }
        style.addSource(RasterSource(sourceId, tileSet, RASTER_TILE_SIZE))
        style.addLayerBelow(
            RasterLayer(MapLayerIds.layerFor(layer.id), sourceId).withProperties(
                PropertyFactory.rasterOpacity(paintOpacity(layer))
            ),
            MapLayerIds.OVERLAY_ANCHOR
        )
    }

    /** Renders a depth grid into that layer's reusable bitmap target. */
    private fun renderDepth(
        id: String,
        grid: DepthGrid,
        ramp: DepthRampSettings
    ): Bitmap = synchronized(renderLock) {
        val target = renderTargets[id]?.takeIf { it.matches(grid) }
            ?: DepthGridRenderer.newTarget(grid).also { fresh ->
                renderTargets.remove(id)?.recycle()
                renderTargets[id] = fresh
            }
        // Shading only changes with its strength (or a new grid): a colour
        // change reuses it, which roughly halves a recolour of a big survey.
        val cached = shades[id]?.takeIf { it.grid === grid && it.strength == ramp.hillshadeExaggeration }
        val shade = cached?.shade ?: DepthGridRenderer.shadeFor(grid, ramp).also {
            shades[id] = ShadeCache(grid, ramp.hillshadeExaggeration, it)
        }
        DepthGridRenderer.render(grid, ramp, target, shade)
    }

    private class ShadeCache(val grid: DepthGrid, val strength: Double, val shade: FloatArray?)

    /** Guarded by [renderLock]. */
    private val shades = mutableMapOf<String, ShadeCache>()

    /** Pushes visibility and opacity into an already attached layer. */
    private fun applyPaint(layer: OverlayLayer) {
        val opacity = paintOpacity(layer)
        when (val mapLayer = mapHolder.style?.getLayer(MapLayerIds.layerFor(layer.id))) {
            is RasterLayer -> mapLayer.setProperties(PropertyFactory.rasterOpacity(opacity))
            is LineLayer -> mapLayer.setProperties(PropertyFactory.lineOpacity(opacity))
            else -> Unit
        }
    }

    private fun paintOpacity(layer: OverlayLayer): Float =
        if (layer.visible) layer.sanitisedOpacity else 0f

    // MBTiles metadata ------------------------------------------------------

    private data class MbtilesMetadata(
        val minZoom: Int,
        val maxZoom: Int,
        val isVector: Boolean,
        val bounds: GeoBounds? = null,
        /** The pack's own credit (this app writes its source's), for a layer loaded without one. */
        val attribution: String = ""
    )

    private fun readMbtilesMetadata(database: SQLiteDatabase): MbtilesMetadata {
        var minZoom = DEFAULT_MIN_ZOOM
        var maxZoom = DEFAULT_MAX_ZOOM
        var format = DEFAULT_FORMAT
        var bounds: GeoBounds? = null
        var attribution = ""

        runCatching {
            database.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val value = cursor.getString(1)
                    when (cursor.getString(0)?.lowercase()) {
                        "minzoom" -> minZoom = value?.toIntOrNull() ?: minZoom
                        "maxzoom" -> maxZoom = value?.toIntOrNull() ?: maxZoom
                        "format" -> format = value ?: format
                        "attribution" -> attribution = value?.trim().orEmpty()
                        // "left,bottom,right,top" in degrees, per the MBTiles spec.
                        "bounds" -> bounds = value?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                            ?.takeIf { it.size == 4 }
                            ?.let { GeoBounds(it[0], it[1], it[2], it[3]) }
                            ?.takeIf { it.isValid }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "MBTiles metadata unreadable", it) }

        return MbtilesMetadata(
            minZoom = minZoom,
            maxZoom = maxZoom,
            isVector = format.lowercase() in VECTOR_FORMATS,
            bounds = bounds,
            attribution = attribution
        )
    }

    /** The area the tiles at [zoom] cover, for files without a "bounds" entry. */
    private fun mbtilesTileBounds(database: SQLiteDatabase, zoom: Int): GeoBounds? = runCatching {
        database.rawQuery(
            "SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level = ?",
            arrayOf(zoom.toString())
        ).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return@use null
            val n = 1 shl zoom
            fun lng(x: Int) = x.toDouble() / n * 360.0 - 180.0
            // MBTiles rows count up from the south (TMS).
            fun lat(tmsRow: Int): Double {
                val y = n - tmsRow
                return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2.0 * y / n))))
            }
            GeoBounds(lng(c.getInt(0)), lat(c.getInt(2)), lng(c.getInt(1) + 1), lat(c.getInt(3) + 1)).takeIf { it.isValid }
        }
    }.getOrNull()

    private fun GeoBounds.toQuad(): LatLngQuad = LatLngQuad(
        LatLng(north, west),
        LatLng(north, east),
        LatLng(south, east),
        LatLng(south, west)
    )

    companion object {
        const val MBTILES_DIR = "mbtiles"

        /**
         * When the first controller in this process was created — before
         * any import could start. The orphan sweep only deletes files older
         * than this, so an in-flight import's not-yet-saved files are safe.
         */
        private val PROCESS_START_MILLIS = System.currentTimeMillis()

        /** Where a [au.opendepth.app.gdal.GdalTileCache] file lives — an app-owned on-demand tile cache, not a copy of the source. */
        const val GEOTIFF_TILE_CACHE_DIR = "geotiff_tile_cache"

        /** Seabed depths the app downloaded: its own files, removed with their layer. */
        const val SEABED_DIR = "seabed"

        /** Private GeoTIFF copies made by pre-1.0 builds; read for those layers, never written. */
        private const val LEGACY_GEOTIFF_DIR = "geotiff"

        /** Precomputed depth pyramids (`.sbdp`) from those same pre-1.0 builds. */
        private const val LEGACY_GEOTIFF_PYRAMID_DIR = "geotiff_pyramids"

        private const val TAG = "SeaBathyLayers"

        /**
         * One background thread for closing layer resources and deleting their
         * app-owned files, in submission order. Process-wide (not tied to the
         * Activity), so teardown work queued by onDestroy still completes.
         */
        private val RELEASE_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "layer-release").apply { isDaemon = true }
        }
        private const val TILEJSON_VERSION = "2.1.0"
        private const val RASTER_TILE_SIZE = 256
        private const val DEFAULT_MIN_ZOOM = 0
        private const val DEFAULT_MAX_ZOOM = 22
        private const val DEFAULT_FORMAT = "png"
        private const val CONTOUR_COLOR = "#00C8B4"
        private const val VECTOR_CONTOUR_SOURCE_LAYER = "contours"
        private val VECTOR_FORMATS = setOf("pbf", "mvt")

        /** Left out at each end when fitting colours to a layer's depths. */
        private const val FIT_TAIL = 0.01

        /**
         * The colour range that fits a layer: its depths, from the surface
         * down. Land in a survey (a coast or islands) would otherwise take
         * the shallow colours and squash the water into a few.
         */
        fun fitRange(shallowestMetres: Double, deepestMetres: Double, histogram: au.opendepth.app.geotiff.DepthHistogram? = null): Pair<Double, Double> {
            // The bulk of the survey: one deep hole or a spike shouldn't
            // stretch the colours so the rest is all one shade.
            if (histogram != null && !histogram.isEmpty) {
                val deepest = histogram.percentile(FIT_TAIL)
                val shallowest = histogram.percentile(1 - FIT_TAIL)
                if (deepest.isFinite() && shallowest.isFinite() && shallowest - deepest >= 1.0) {
                    return fitRange(shallowest, deepest)
                }
            }
            val deep = floor(deepestMetres)
            val shallow = ceil(shallowestMetres).let { if (deep < 0.0) minOf(it, 0.0) else it }
            return shallow to deep
        }

        /**
         * Starts a depth layer on a ramp that spans its own data, rounded to
         * whole metres, so an imported raster always renders with usable
         * contrast before the user touches the range controls.
         */
        fun autoFitRamp(grid: DepthGrid): DepthRampSettings =
            autoFitRamp(grid.shallowestMetres, grid.deepestMetres)

        /** As above, from a [DepthSource]'s real extremes rather than a decoded grid's. */
        fun autoFitRamp(source: DepthSource): DepthRampSettings =
            autoFitRamp(source.shallowestMetres, source.deepestMetres)

        private fun autoFitRamp(shallowestMetres: Double, deepestMetres: Double): DepthRampSettings {
            val (shallow, deep) = fitRange(shallowestMetres, deepestMetres)
            if (!shallow.isFinite() || !deep.isFinite() || shallow - deep < 1.0) {
                return DepthRampSettings()
            }
            return DepthRampSettings(
                paletteId = DepthRampSettings.DEFAULT_PALETTE_ID,
                shallowMetres = shallow,
                deepMetres = deep
            )
        }
    }
}
