package au.opendepth.app.terrain

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import au.opendepth.app.BuildConfig
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.geotiff.DepthPalette
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.model.DataSource
import au.opendepth.app.model.DepthRampSettings
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.model.Waypoint
import au.opendepth.app.settings.XyzTilePresets
import com.google.gson.Gson
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen 3D view of one GeoTIFF depth layer, drawn over the chart.
 *
 * The viewer itself is the three.js page bundled in `assets/terrain3d` (built
 * from `terrain3d-web/`). It is loaded from a virtual https origin and every
 * request is answered here: the page's own files from assets, and the depth
 * grid from memory — so nothing is written to disk and nothing touches the
 * network, except the tiles of an online map laid over the terrain
 * (`/drape/…`, see [serveDrape]). The `SeaBathy3D` JavaScript bridge carries
 * close, isolate and detail requests back.
 */
class Terrain3DScreen(
    private val activity: Activity,
    private val scope: CoroutineScope,
    /** Depth and speed units for the 3D readouts, as chosen in Boat & navigation. */
    private val units: () -> au.opendepth.app.nav.NavUnits = { au.opendepth.app.nav.NavUnits() },
    /** Saves a waypoint picked in 3D (lat, lng, depth in metres) and returns it, or null if it could not be added. */
    private val onAddWaypoint: (Double, Double, Double) -> Waypoint?,
    /** Turns GPS on (asking for permission if needed), for the boat marker. */
    private val onStartGps: () -> Unit = {},
    /** Opens the app's waypoint editor (rename, notes, delete) for a pin tapped in 3D. */
    private val onEditWaypoint: (String) -> Unit = {},
    /** The layer stack's maps that can cover the terrain (offline packs, XYZ links). */
    private val mapLayers: () -> List<TerrainDrape> = { emptyList() },
    /** Opens the offline download screen for an online map over the 3D area, up to a max zoom. */
    private val onSaveOffline: (DataSource, GeoBounds, Int) -> Unit = { _, _, _ -> },
    /** The view opened or closed: what's under it has no need to keep animating. */
    private val onShowingChanged: (Boolean) -> Unit = {}
) {
    private class Payload(val meta: ByteArray, val grid: ByteArray)

    private val gson = Gson()
    private var webView: WebView? = null
    private var source: DepthSource? = null
    private var layerName = ""
    private var waypoints: List<Waypoint> = emptyList()
    private var detail = DEFAULT_DETAIL
    /**
     * How far 3D is stepped down because this phone's GPU ran short, 0 (none)
     * to [MAX_SAFETY]: smaller map memory and lower drawing resolution at each
     * level, no shadows from 1, a coarser grid from 2, no sharpening at 3.
     * Remembered between opens, so one crash isn't repeated every time.
     */
    private var safety = 0
    /** How many times this opening of 3D has lost its drawing and been brought back. */
    private var recoveries = 0
    private var openedAt = 0L
    /** The page has its terrain up; until then [watchdog] is waiting. */
    @Volatile private var pageReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        if (webView != null && !pageReady) {
            DiagnosticLog.log(TAG, "3D page never came up")
            recover()
        }
    }

    /** The grid currently shown, and its serialised form; replaced whole, read from WebView worker threads. */
    @Volatile private var grid: TerrainGrid? = null
    @Volatile private var payload: Payload? = null
    @Volatile private var isolated: ClosedFloatingPointRange<Double>? = null
    /** The layer's colours on the chart, so 3D starts out looking the same. */
    private var chartRamp: DepthRampSettings? = null

    /** The maps offered for the terrain's surface, by id, in the order shown; read from WebView worker threads. */
    @Volatile private var drapes: Map<String, TerrainDrape> = emptyMap()
    /** When a tile last couldn't reach its server: until [NO_SIGNAL_MS] later, only saved tiles are tried. */
    @Volatile private var lastNoSignalAt = 0L
    /** An ordinary HTTP cache, kept for as long as each server says. */
    private val tileClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .cache(Cache(File(activity.cacheDir, "terrain-tiles"), TILE_CACHE_BYTES))
            .build()
    }
    private val customPrefs by lazy { activity.getSharedPreferences(CUSTOM_PREFS, Activity.MODE_PRIVATE) }

    /** Whether the grid covers the whole layer (not the on-screen area or an isolated range). */
    @Volatile private var wholeLayer = true

    val isOpen: Boolean get() = webView != null

    /**
     * [region] limits the first view to part of the layer (the area on the
     * chart); Show all in the viewer goes back to the whole layer. [detail]
     * is the starting grid size from the quality setting.
     */
    fun open(
        name: String,
        depthSource: DepthSource,
        marks: List<Waypoint>,
        region: GeoBounds? = null,
        detail: Int = DEFAULT_DETAIL,
        ramp: DepthRampSettings? = null
    ) {
        if (isOpen) return
        source = depthSource
        chartRamp = ramp?.normalised()
        layerName = name
        waypoints = marks.toList()
        isolated = null
        refreshDrapes()
        this.detail = detail.coerceIn(MIN_DETAIL, MAX_DETAIL)
        recoveries = 0
        openedAt = SystemClock.elapsedRealtime()
        safety = customPrefs.getInt(SAFETY_KEY, 0)
        // Lighter settings earned under an older version say nothing about this one, whose map handling is new.
        val freshVersion = customPrefs.getInt(SAFETY_VERSION_KEY, 0) != BuildConfig.VERSION_CODE
        if (freshVersion) {
            DiagnosticLog.log(TAG, "new version: lighter settings reset from $safety")
            safety = 0
            customPrefs.edit().putInt(SAFETY_VERSION_KEY, BuildConfig.VERSION_CODE).putBoolean(OPEN_KEY, false).apply()
        }
        // Still marked open: last time took the whole app down, so start a step lighter.
        if (customPrefs.getBoolean(OPEN_KEY, false)) {
            safety = (safety + 1).coerceAtMost(MAX_SAFETY)
            DiagnosticLog.log(TAG, "3D did not close cleanly last time: safety now $safety")
        }
        customPrefs.edit().putBoolean(OPEN_KEY, true).putInt(SAFETY_KEY, safety).apply()
        toast(if (region != null) "Building 3D of the area on screen…" else "Building 3D view…")
        scope.launch {
            val built = buildGrid(region ?: depthSource.bounds, null)
            if (built == null) {
                toast("This layer has no depth data to show in 3D")
                return@launch
            }
            show()
        }
    }

    /**
     * Moves the boat marker to a live GPS fix. Heading is the course over
     * ground when the fix has one; speed is reported in knots.
     */
    /** Shows the app's current waypoints, e.g. after one was renamed or deleted. */
    fun updateWaypoints(marks: List<Waypoint>) {
        waypoints = marks.toList()
        val json = gson.toJson(waypoints.map(::waypointJson))
        webView?.evaluateJavascript("window.setWaypointList && window.setWaypointList($json)", null)
    }

    /** Flies the 3D camera to a waypoint's pin. */
    fun focusWaypoint(id: String) {
        webView?.evaluateJavascript("window.focusWaypoint && window.focusWaypoint(${gson.toJson(id)})", null)
    }

    private fun waypointJson(w: Waypoint) =
        mapOf("id" to w.id, "name" to w.name, "color" to w.color, "lat" to w.lat, "lng" to w.lng, "desc" to w.desc)

    fun updateBoat(location: android.location.Location) {
        val view = webView ?: return
        val fix = mapOf(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "heading" to if (location.hasBearing()) location.bearing.toDouble() else null,
            "knots" to if (location.hasSpeed()) location.speed * METRES_PER_SECOND_TO_KNOTS else null,
            "accuracy" to if (location.hasAccuracy()) location.accuracy.toDouble() else null
        )
        view.evaluateJavascript("window.updateBoat && window.updateBoat(${gson.toJson(fix)})", null)
    }

    /** Stops drawing while the app is in the background. */
    fun onPause() {
        webView?.onPause()
    }

    fun onResume() {
        webView?.onResume()
    }

    fun close() {
        mainHandler.removeCallbacks(watchdog)
        // A long, trouble-free session earns a step back up.
        if (recoveries == 0 && safety > 0 && SystemClock.elapsedRealtime() - openedAt > TRUSTED_SESSION_MS) safety--
        customPrefs.edit().putBoolean(OPEN_KEY, false).putInt(SAFETY_KEY, safety).apply()
        destroyView()
        onShowingChanged(false)
        grid = null
        payload = null
        source = null
    }

    private suspend fun buildGrid(bounds: GeoBounds, range: ClosedFloatingPointRange<Double>?): TerrainGrid? {
        val depthSource = source ?: return null
        wholeLayer = range == null && bounds == depthSource.bounds
        val sampled = withContext(Dispatchers.Default) {
            runCatching {
                // A large GDAL-backed survey is read as one window at its own
                // resolution; point-by-point sampling would be slow and would
                // repeat each source cell in steps.
                if (depthSource is GdalTileSource) {
                    depthSource.readArea(bounds, gridMaxDim(bounds))?.let { TerrainGridSampler.fromDepthGrid(it, range) }
                } else {
                    TerrainGridSampler.sample(depthSource, bounds, gridMaxDim(bounds), range)
                }
            }
                .onFailure { DiagnosticLog.logError(TAG, "terrain sampling failed", it) }
                .getOrNull()
        } ?: return null
        grid = sampled
        payload = withContext(Dispatchers.Default) { serialise(sampled) }
        DiagnosticLog.log(TAG, "grid ready: ${sampled.cols}x${sampled.rows} range=${sampled.minMetres}..${sampled.maxMetres}")
        return sampled
    }

    private fun serialise(g: TerrainGrid): Payload {
        val bytes = ByteBuffer.allocate(g.values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(g.values)
        val meta = mapOf(
            "name" to layerName,
            "cols" to g.cols,
            "rows" to g.rows,
            "cellX" to g.cellXMetres,
            "cellY" to g.cellYMetres,
            "min" to g.minMetres,
            "max" to g.maxMetres,
            "detail" to detail,
            "detailLimit" to detailLimit(g.bounds),
            "drapeMaxSize" to drapeMaxSize(),
            "safety" to safety,
            "pixelRatio" to PIXEL_RATIOS[safety],
            "bounds" to mapOf("west" to g.bounds.west, "south" to g.bounds.south, "east" to g.bounds.east, "north" to g.bounds.north),
            "isolated" to isolated?.let { mapOf("deep" to it.start, "shallow" to it.endInclusive) },
            // Showing less than the whole layer (an isolated range or the
            // on-screen area), so Show all has something to go back to.
            "partial" to !wholeLayer,
            "chart" to chartRamp?.let { r ->
                val p = DepthPalette.byId(r.paletteId)
                // The page's stops run deep to shallow; the chart's shallow to deep.
                val hex = p.stops.map { String.format("#%06X", it and 0xFFFFFF) }
                mapOf(
                    "label" to p.label,
                    "stops" to if (r.invert) hex else hex.reversed(),
                    "shallow" to r.shallowMetres,
                    "deep" to r.deepMetres,
                    // The chart hides depths outside the range: so does 3D, to start with.
                    "hide" to !r.showOutside
                )
            },
            "waypoints" to waypoints.map(::waypointJson),
            "drapes" to drapeList()
        )
        return Payload(gson.toJson(meta).toByteArray(), bytes.array())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun show() {
        if (isOpen) return
        val view = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(BACKGROUND)
            addJavascriptInterface(Bridge(), "SeaBathy3D")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    serve(request.url.host, request.url.path.orEmpty())

                // The page never navigates; anything else would leave the viewer.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                // The page's process died (usually Android reclaiming memory): without this the
                // whole app goes down, or the view is left white. Bring it back lighter instead.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    DiagnosticLog.log(TAG, "3D renderer gone: crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                    if (view === webView) recover() else view.destroy()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(TAG, "js: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    // The browser's ResizeObserver notice is harmless and comes with every layout change.
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && !message.message().startsWith("ResizeObserver loop")) {
                        DiagnosticLog.log(TAG, "js error: ${message.message()}")
                    }
                    return true
                }
            }
        }
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        webView = view
        pageReady = false
        onShowingChanged(true)
        mainHandler.postDelayed(watchdog, PAGE_READY_TIMEOUT_MS)
        view.loadUrl("https://$HOST/terrain3d/index.html")
    }

    /** Answers every request from the viewer page; anything not ours is refused. */
    private fun serve(host: String?, path: String): WebResourceResponse {
        if (host != HOST) return notFound()
        val current = payload
        return when {
            path == "/terrain/meta.json" && current != null -> response("application/json", current.meta)
            path == "/terrain/grid.bin" && current != null -> response("application/octet-stream", current.grid)
            path.startsWith("/drape/") -> serveDrape(path)
            path.startsWith("/terrain3d/") && !path.contains("..") -> runCatching {
                val name = path.removePrefix("/")
                response(mimeFor(name), activity.assets.open(name).use { it.readBytes() })
            }.getOrElse { notFound() }
            else -> notFound()
        }
    }

    /**
     * One tile of a map laid over the terrain: `/drape/<id>/<z>/<x>/<y>`.
     * Runs on a WebView worker thread, so the read or download blocks only
     * that request. A tile that isn't there (offline, outside the pack) is a
     * 404, which the page leaves transparent.
     */
    private fun serveDrape(path: String): WebResourceResponse {
        val parts = path.removePrefix("/drape/").split('/')
        if (parts.size != 4) return notFound()
        val drape = drapes[Uri.decode(parts[0])] ?: return notFound()
        val (z, x, y) = parts.drop(1).map { it.toIntOrNull() ?: return notFound() }
        if (z !in 0..MAX_DRAPE_ZOOM || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return notFound()
        val bytes = drape.readTile?.invoke(z, x, y)
            ?: drape.urlTemplate?.let { fetchTile(it, z, x, y) }
            ?: return notFound()
        return response(imageMime(bytes), bytes)
    }

    /**
     * One attempt only: a missing tile just shows a coarser one, and with no
     * signal retries would only slow the rest. At sea a dead connection is
     * normal, and each try could hold a request open for seconds, so after one
     * fails only tiles already saved are tried for a while. They come at once.
     */
    private fun fetchTile(template: String, z: Int, x: Int, y: Int): ByteArray? {
        val url = template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        val builder = Request.Builder().url(url).header("User-Agent", "SeaBathy/${BuildConfig.VERSION_NAME} (Android 3D view)")
        if (SystemClock.elapsedRealtime() - lastNoSignalAt < NO_SIGNAL_MS) builder.cacheControl(CacheControl.FORCE_CACHE)
        return try {
            tileClient.newCall(builder.build()).execute().use { response ->
                val body = response.body
                if (response.isSuccessful && body != null && body.contentLength() <= MAX_TILE_BYTES) body.bytes() else null
            }
        } catch (e: java.io.IOException) {
            // Cancelled by the page moving on isn't a lost signal, and neither is a request already for saved tiles.
            if (e !is java.io.InterruptedIOException || e is java.net.SocketTimeoutException) lastNoSignalAt = SystemClock.elapsedRealtime()
            null
        }
    }

    private fun imageMime(bytes: ByteArray) = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 11 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/png"
    }

    /** Rebuilds the Map list: the app's default maps, then the layer stack's, then the user's own links. */
    private fun refreshDrapes() {
        drapes = (defaultDrapes() + mapLayers() + customDrapes()).associateBy { it.id }
    }

    private fun drapeList() = drapes.values.map {
        mapOf(
            "id" to it.id,
            "label" to it.label,
            "minzoom" to it.minZoom,
            "maxzoom" to it.maxZoom,
            "group" to it.group.key,
            "offline" to it.isOffline,
            // Only an online map whose provider allows offline copies can be downloaded for the area.
            "savable" to it.isSavable,
            "colour" to it.colour,
            "attribution" to it.attribution,
            "bounds" to it.bounds?.let { b -> mapOf("west" to b.west, "south" to b.south, "east" to b.east, "north" to b.north) }
        )
    }

    /**
     * The online maps offered: Esri satellite (view only, under Esri's terms)
     * and Sentinel-2 cloudless, whose licence allows saving it offline.
     */
    private fun defaultDrapes(): List<TerrainDrape> = listOfNotNull(
        XyzTilePresets.DEFAULT_SATELLITE.let {
            TerrainDrape(
                "online-${it.id}", "Esri satellite", 0, it.maxZoom, urlTemplate = it.urlTemplate,
                group = TerrainDrape.Group.DEFAULT, attribution = it.attribution, colour = "#15803d"
            )
        },
        DataSource.DEFAULT_PRESET.let {
            TerrainDrape(
                "online-${it.id}", "Sentinel-2 satellite (saves offline)", 0, SENTINEL2_MAX_ZOOM,
                urlTemplate = it.urlTemplate, group = TerrainDrape.Group.DEFAULT,
                attribution = it.attribution, colour = "#0e7490"
            )
        }
    )

    private class CustomDrape(val id: String = "", val name: String = "", val url: String = "")

    private fun loadCustom(): List<CustomDrape> = runCatching {
        gson.fromJson(customPrefs.getString(CUSTOM_KEY, "[]"), Array<CustomDrape>::class.java).toList()
    }.getOrDefault(emptyList()).filter { it.id.isNotBlank() && XyzTilePresets.validationError(it.url) == null }

    private fun saveCustom(list: List<CustomDrape>) {
        customPrefs.edit().putString(CUSTOM_KEY, gson.toJson(list)).apply()
    }

    private fun customDrapes() = loadCustom().map {
        // A link the user added: its credit names the server the tiles come from.
        val host = runCatching { Uri.parse(it.url.replace("{", "").replace("}", "")).host }.getOrNull() ?: it.name
        TerrainDrape(
            it.id, it.name, 0, OverlayLayer.DEFAULT_MAX_ZOOM, urlTemplate = it.url,
            group = TerrainDrape.Group.CUSTOM, attribution = "Map: $host"
        )
    }

    private fun response(mime: String, bytes: ByteArray) =
        WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeFor(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        else -> "application/octet-stream"
    }

    private fun destroyView() {
        val view = webView ?: return
        webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    /**
     * The 3D view lost its drawing (the GPU dropped the WebGL context, Android
     * stopped the page's process for memory, or the page never came up). Starts
     * again in a new WebView, since the old one's GPU memory has to go first,
     * one safety level lighter. After [MAX_RECOVERIES] it goes back to the chart
     * with a message, so the screen is never left blank.
     */
    private fun recover() {
        mainHandler.removeCallbacks(watchdog)
        recoveries++
        safety = (safety + 1).coerceAtMost(MAX_SAFETY)
        customPrefs.edit().putInt(SAFETY_KEY, safety).apply()
        DiagnosticLog.log(TAG, "3D recovery #$recoveries: safety=$safety")
        if (recoveries > MAX_RECOVERIES) {
            close()
            toast("The 3D view ran out of memory. Try a smaller area on the chart.")
            return
        }
        destroyView()
        toast("3D view ran low on memory: reopening with a lighter map")
        scope.launch {
            delay(GPU_RELEASE_MS)
            val current = grid ?: return@launch
            val built = if (safety >= 2) buildGrid(current.bounds, isolated) else {
                payload = withContext(Dispatchers.Default) { serialise(current) }
                current
            }
            if (built == null) close() else if (grid != null) show()
        }
    }

    /** True while Android says this phone is short of memory right now. */
    private fun lowOnMemory(): Boolean {
        val manager = activity.getSystemService(Activity.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.lowMemory
    }

    /** RAM in GiB, which sets how much 3D this phone is asked to hold. */
    private fun totalMemoryGib(): Double {
        val manager = activity.getSystemService(Activity.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 0.0
        if (manager.isLowRamDevice) return 0.0
        val info = android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return info.totalMem / (1024.0 * 1024 * 1024)
    }

    /** Longest side of the whole-survey map, which also sets the memory for the sharper levels round the view. */
    private fun drapeMaxSize(): Int {
        val gib = totalMemoryGib()
        val base = when {
            gib < 3.5 -> MIN_DRAPE_SIZE
            gib < 5.5 -> 1536
            else -> MAX_DRAPE_SIZE
        }
        return (base shr safety).coerceAtLeast(MIN_DRAPE_SIZE / 2)
    }

    /**
     * Grid cells along the longer side: the chosen detail, held to what this
     * phone can draw.
     */
    private fun gridMaxDim(bounds: GeoBounds): Int = min(detail, detailLimit(bounds))

    /** The most cells along the longer side this phone holds for [bounds] right now. */
    private fun detailLimit(bounds: GeoBounds): Int {
        val cells = TerrainBudget.cells(totalMemoryGib(), lowOnMemory(), safety)
        val wide = (bounds.east - bounds.west) * cos(Math.toRadians((bounds.north + bounds.south) / 2))
        val tall = bounds.north - bounds.south
        val aspect = max(wide, tall) / min(wide, tall).coerceAtLeast(1e-9)
        return TerrainBudget.longestSide(cells, aspect).coerceAtLeast(MIN_DETAIL)
    }

    private fun reloadPage(busyText: String?) {
        webView?.evaluateJavascript("window.showBusy(null); window.reloadGrid()", null)
        busyText?.let { toast(it) }
    }

    private fun showBusy(text: String) {
        val quoted = gson.toJson(text)
        webView?.evaluateJavascript("window.showBusy($quoted)", null)
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    /** Called from the page on a WebView binder thread; every action hops back to the main thread. */
    private inner class Bridge {
        /** The depth and speed units, for the page's readouts. */
        @JavascriptInterface
        fun units(): String {
            val u = this@Terrain3DScreen.units()
            return gson.toJson(
                mapOf(
                    "depthPerM" to u.depth.perMetre,
                    "depthLabel" to u.depth.label,
                    "speedPerKnot" to u.speed.perKnot,
                    "speedLabel" to u.speed.label
                )
            )
        }

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread { this@Terrain3DScreen.close() }
        }

        @JavascriptInterface
        fun isolate(deep: Double, shallow: Double) {
            scope.launch {
                val current = grid ?: return@launch
                val range = minOf(deep, shallow)..maxOf(deep, shallow)
                val area = TerrainGridSampler.boundsWithin(current, range)
                if (area == null) {
                    toast("No data in that depth range")
                    return@launch
                }
                showBusy("Isolating depths…")
                isolated = range
                if (buildGrid(area, range) == null) {
                    isolated = null
                    toast("No data in that depth range")
                    webView?.evaluateJavascript("window.showBusy(null)", null)
                    return@launch
                }
                reloadPage(null)
            }
        }

        @JavascriptInterface
        fun addWaypoint(lat: Double, lng: Double, depth: Double) {
            activity.runOnUiThread {
                val added = onAddWaypoint(lat, lng, depth) ?: return@runOnUiThread
                waypoints = waypoints + added
                val json = gson.toJson(waypointJson(added))
                webView?.evaluateJavascript("window.onWaypointAdded($json)", null)
            }
        }

        @JavascriptInterface
        fun editWaypoint(id: String) {
            activity.runOnUiThread { onEditWaypoint(id) }
        }

        @JavascriptInterface
        fun startGps() {
            activity.runOnUiThread { onStartGps() }
        }

        @JavascriptInterface
        fun showAll() {
            scope.launch {
                val all = source?.bounds ?: return@launch
                showBusy("Loading whole layer…")
                isolated = null
                buildGrid(all, null)
                reloadPage(null)
            }
        }

        /** Adds a custom map link; returns { id, drapes } or { error }. */
        @JavascriptInterface
        fun addCustomDrape(name: String, url: String): String {
            val link = url.trim()
            XyzTilePresets.validationError(link)?.let { return gson.toJson(mapOf("error" to it)) }
            val list = loadCustom()
            if (list.any { it.url == link }) return gson.toJson(mapOf("error" to "That map is already in the list"))
            val label = name.trim().ifBlank { runCatching { Uri.parse(link).host }.getOrNull() ?: "Custom map" }
            val added = CustomDrape("custom-${UUID.randomUUID()}", label.take(MAX_NAME_LENGTH), link)
            saveCustom(list + added)
            refreshDrapes()
            DiagnosticLog.log(TAG, "custom map added: ${added.name}")
            return gson.toJson(mapOf("id" to added.id, "drapes" to drapeList()))
        }

        @JavascriptInterface
        fun removeCustomDrape(id: String): String {
            saveCustom(loadCustom().filterNot { it.id == id })
            refreshDrapes()
            return gson.toJson(drapeList())
        }

        /** Closes 3D and opens the offline download screen for [id]'s map over the area shown. */
        @JavascriptInterface
        fun saveOffline(id: String) {
            activity.runOnUiThread {
                val drape = drapes[id]?.takeIf { it.isSavable } ?: return@runOnUiThread
                val template = drape.urlTemplate ?: return@runOnUiThread
                val bounds = grid?.bounds ?: return@runOnUiThread
                val source = DataSource(id = drape.id, name = drape.label, urlTemplate = template, attribution = drape.attribution)
                this@Terrain3DScreen.close()
                onSaveOffline(source, bounds, drape.maxZoom.coerceAtMost(MAX_SAVE_ZOOM))
            }
        }

        /** The page's WebGL context was lost: the GPU ran short. */
        @JavascriptInterface
        fun contextLost() {
            activity.runOnUiThread {
                DiagnosticLog.log(TAG, "3D WebGL context lost")
                if (webView != null) recover()
            }
        }

        /** The page has its terrain up. */
        @JavascriptInterface
        fun ready() {
            pageReady = true
            mainHandler.removeCallbacks(watchdog)
        }

        /** WebGL wouldn't start at all: a new WebView may do it, but after a GPU failure only restarting the app will. */
        @JavascriptInterface
        fun noWebGL() {
            activity.runOnUiThread {
                DiagnosticLog.log(TAG, "3D page: WebGL not available (recoveries=$recoveries)")
                if (webView == null) return@runOnUiThread
                if (recoveries == 0) {
                    recover()
                } else {
                    close()
                    toast("The phone's graphics are out of memory. Close and reopen SeaBathy to use 3D again.")
                }
            }
        }

        /** The user asked for full quality again: the view restarts with nothing stepped down. */
        @JavascriptInterface
        fun resetSafety() {
            activity.runOnUiThread {
                mainHandler.removeCallbacks(watchdog)
                safety = 0
                recoveries = 0
                customPrefs.edit().putInt(SAFETY_KEY, 0).apply()
                destroyView()
                scope.launch {
                    delay(GPU_RELEASE_MS)
                    val current = grid ?: return@launch
                    if (buildGrid(current.bounds, isolated) == null) close() else if (grid != null) show()
                }
            }
        }

        /** A line for the debug log. */
        @JavascriptInterface
        fun log(text: String) {
            DiagnosticLog.log(TAG, text.take(MAX_LOG_LENGTH))
        }

        @JavascriptInterface
        fun setDetail(cells: Int) {
            scope.launch {
                detail = cells.coerceIn(MIN_DETAIL, MAX_DETAIL)
                val current = grid ?: return@launch
                showBusy("Rebuilding…")
                buildGrid(current.bounds, isolated)
                reloadPage(null)
            }
        }
    }

    private companion object {
        const val TAG = "Terrain3D"
        const val HOST = "appassets.androidplatform.net"
        const val DEFAULT_DETAIL = 512
        const val MIN_DETAIL = 128
        const val MAX_DETAIL = 2048
        const val BACKGROUND = 0xFF07111C.toInt()
        const val METRES_PER_SECOND_TO_KNOTS = 1.9438444924574
        const val MAX_DRAPE_ZOOM = 22
        const val MAX_DRAPE_SIZE = 2048
        const val MIN_DRAPE_SIZE = 1024
        const val MAX_SAFETY = 3
        /** Drawing resolution at each safety level, in screen pixels per CSS pixel. */
        val PIXEL_RATIOS = listOf(2.0, 1.5, 1.25, 1.0)
        /** Times the view is brought back after losing its drawing, before giving up. */
        const val MAX_RECOVERIES = 3
        /** Time for the old view's GPU memory to go before the new one asks for its own. */
        const val GPU_RELEASE_MS = 800L
        const val PAGE_READY_TIMEOUT_MS = 30_000L
        const val TRUSTED_SESSION_MS = 120_000L
        const val MAX_LOG_LENGTH = 400
        const val SAFETY_KEY = "safety_level"
        const val OPEN_KEY = "view_open"
        const val SAFETY_VERSION_KEY = "safety_version"
        const val MAX_TILE_BYTES = 4L * 1024 * 1024

        const val TILE_CACHE_BYTES = 100L * 1024 * 1024
        const val NO_SIGNAL_MS = 20_000L
        /** Saving an area offline suggests no deeper than this (about 1 m per pixel). */
        const val MAX_SAVE_ZOOM = 17
        /** Sentinel-2 cloudless is 10 m imagery: zoom 14 is its full detail. */
        const val SENTINEL2_MAX_ZOOM = 14
        const val MAX_NAME_LENGTH = 60
        const val CUSTOM_PREFS = "terrain3d"
        const val CUSTOM_KEY = "custom_drapes"

    }
}
