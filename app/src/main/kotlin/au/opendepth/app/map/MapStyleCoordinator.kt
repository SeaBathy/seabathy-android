package au.opendepth.app.map

import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.settings.AppSettingsRepository
import au.opendepth.app.settings.BasemapType
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.Style

/**
 * Owns every basemap and style change.
 *
 * MapLibre discards custom sources and layers on `setStyle()`, so all style
 * changes funnel through [rebuild], which reinstalls the fixed layers, reattaches
 * the overlay stack and only then reports readiness. Work from a superseded
 * rebuild is dropped by checking the map generation.
 */
class MapStyleCoordinator(
    private val mapHolder: MapHolder,
    private val settings: AppSettingsRepository,
    private val overlays: OverlayLayerController,
    private val onStyleInstalled: () -> Unit,
    private val onAttributionChanged: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    /** Whether the phone has working internet now (checked, not just a network). */
    private val isOnline: () -> Boolean = { true },
    /** Credits for data drawn outside the overlay stack (sea conditions). */
    private val extraAttributions: () -> List<String> = { emptyList() },
    private val graticule: GraticuleController? = null
) {
    /** Showing the blank offline canvas instead of the chosen online basemap. */
    var usingOfflineFallback = false
        private set

    var currentType: String = BasemapType.DARK
        private set

    var customUrl: String = ""
        private set

    var customAttribution: String = ""
        private set

    val customBasemapConfigured: Boolean get() = customUrl.isNotBlank()

    // Tracks the style fetch that [rebuild] is currently waiting on, so a
    // later report that it failed (from the map's own fail-loading listener)
    // can tell whether it's still relevant and whether it's worth falling
    // back for (only a remote basemap's style document can fail this way).
    private var pendingGeneration: Int = -1
    private var pendingIsRemote: Boolean = false

    /** Loads the persisted basemap selection into memory. */
    fun loadSettings() {
        settings.loadBasemap().let { saved ->
            currentType = saved.current
            customUrl = saved.customUrl
            customAttribution = saved.customAttribution
        }
    }

    fun saveCustomBasemap(url: String, attribution: String) {
        customUrl = url
        customAttribution = attribution
        settings.saveCustomBasemap(url, attribution)
    }

    fun clearCustomBasemap() {
        customUrl = ""
        customAttribution = ""
        settings.clearCustomBasemap()
        if (currentType == BasemapType.CUSTOM) {
            rebuild(BasemapType.DARK)
        } else {
            refreshAttribution()
        }
    }

    fun rebuild(requestedType: String = currentType, onReady: (() -> Unit)? = null) {
        val type = resolveType(requestedType)
        currentType = type
        settings.saveCurrentBasemap(type)

        val map = mapHolder.map ?: return
        // Some styles (dark-matter too) set their own centre and zoom when they load;
        // the camera is put back afterwards so the view doesn't jump.
        val preservedCamera = map.cameraPosition
        val generation = mapHolder.beginRebuild()
        pendingGeneration = generation
        pendingIsRemote = MapStyleFactory.requiresNetwork(type)
        DiagnosticLog.log(TAG, "rebuild: type=$type remote=$pendingIsRemote generation=$generation")

        // With no internet the online style can only fail, after a long
        // timeout — a blank chart (and no layers) for ten seconds or more at
        // sea. Go straight to the offline canvas; the basemap comes back
        // when the connection does (see [onConnectivityRestored]).
        if (pendingIsRemote && !isOnline()) {
            pendingGeneration = -1
            usingOfflineFallback = true
            DiagnosticLog.log(TAG, "rebuild: offline, using the offline style straight away")
            onMessage("No internet — showing your own layers. The online basemap comes back when you're connected.")
            map.setStyle(MapStyleFactory.offlineFallback()) { loadedStyle ->
                if (!mapHolder.isCurrent(generation)) return@setStyle
                finishStyleLoad(generation, loadedStyle, preservedCamera, onReady, path = "offlineStart:$type")
            }
            return
        }
        usingOfflineFallback = false

        map.setStyle(MapStyleFactory.create(type, customUrl)) { loadedStyle ->
            if (pendingGeneration == generation) pendingGeneration = -1
            if (!mapHolder.isCurrent(generation)) {
                DiagnosticLog.log(TAG, "style loaded but superseded: generation=$generation current=${mapHolder.generation}")
                return@setStyle
            }
            finishStyleLoad(generation, loadedStyle, preservedCamera, onReady, path = "primary:$type")
        }
    }

    /**
     * The online basemap's style failed to load. Overlays are added only after
     * the style loads, so this switches to the blank offline style and the
     * user's own layers still show.
     */
    fun handleStyleLoadFailure() {
        val generation = pendingGeneration
        if (generation == -1 || !mapHolder.isCurrent(generation) || !pendingIsRemote) {
            DiagnosticLog.log(
                TAG,
                "handleStyleLoadFailure ignored: pendingGeneration=$generation " +
                    "current=${mapHolder.generation} pendingIsRemote=$pendingIsRemote"
            )
            return
        }
        pendingGeneration = -1
        val map = mapHolder.map ?: run {
            DiagnosticLog.log(TAG, "handleStyleLoadFailure ignored: no map")
            return
        }
        usingOfflineFallback = true
        DiagnosticLog.log(TAG, "handleStyleLoadFailure: falling back to offline style, generation=$generation")
        onMessage("Couldn't load the online basemap (no internet, or the basemap address isn't working) — showing offline layers only")
        val preservedCamera = map.cameraPosition
        map.setStyle(MapStyleFactory.offlineFallback()) { loadedStyle ->
            if (!mapHolder.isCurrent(generation)) {
                DiagnosticLog.log(TAG, "fallback style loaded but superseded: generation=$generation current=${mapHolder.generation}")
                return@setStyle
            }
            finishStyleLoad(generation, loadedStyle, preservedCamera, onReady = null, path = "offlineFallback")
        }
    }

    /** The internet is back: swap the offline canvas for the chosen basemap. */
    fun onConnectivityRestored() {
        if (!usingOfflineFallback || !MapStyleFactory.requiresNetwork(currentType)) return
        DiagnosticLog.log(TAG, "connectivity restored: reloading the $currentType basemap")
        rebuild(currentType)
    }

    private fun finishStyleLoad(
        generation: Int,
        loadedStyle: Style,
        preservedCamera: CameraPosition,
        onReady: (() -> Unit)?,
        path: String
    ) {
        DiagnosticLog.log(TAG, "finishStyleLoad: path=$path generation=$generation")
        mapHolder.style = loadedStyle
        mapHolder.map?.cameraPosition = preservedCamera

        BaseMapLayers.install(loadedStyle, graticule)
        overlays.attachOrRestore(generation) {
            if (!mapHolder.isCurrent(generation)) return@attachOrRestore
            DiagnosticLog.log(TAG, "overlays ready: path=$path generation=$generation")
            onStyleInstalled()
            refreshAttribution()
            onReady?.invoke()
        }
    }

    /** Recomputes the credit line from the basemap and visible overlays. */
    fun refreshAttribution() {
        val credits = buildList {
            baseAttribution()?.let { add(it) }
            addAll(overlays.visibleAttributions())
            addAll(extraAttributions())
        }
        onAttributionChanged(credits.distinct().joinToString(SEPARATOR))
    }

    private fun baseAttribution(): String? = when {
        usingOfflineFallback -> null
        currentType == BasemapType.DARK || currentType == BasemapType.OSM -> "OpenFreeMap © OpenMapTiles • © OpenStreetMap contributors"
        currentType == BasemapType.CUSTOM -> customAttribution.trim().ifBlank { "Custom online tiles" }.let {
            // Esri's terms ask for "Powered by Esri" with any of its basemaps.
            if (customUrl.contains("arcgis", ignoreCase = true) && !it.contains("Powered by Esri")) "$it · Powered by Esri" else it
        }

        else -> null
    }

    /** Falls back off "custom" when no custom URL has been configured. */
    private fun resolveType(requestedType: String): String {
        if (requestedType != BasemapType.CUSTOM || customUrl.isNotBlank()) {
            return requestedType
        }
        onMessage("Set a custom basemap link in Settings first")
        return if (currentType == BasemapType.CUSTOM) BasemapType.DARK else currentType
    }

    private companion object {
        const val SEPARATOR = " • "
        const val TAG = "SeaBathyStyle"
    }
}
