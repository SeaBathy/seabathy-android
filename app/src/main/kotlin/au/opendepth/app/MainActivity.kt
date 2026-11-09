package au.opendepth.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import au.opendepth.app.data.DataSourceRepository
import au.opendepth.app.data.DataSourceStore
import au.opendepth.app.data.OverlayLayerRepository
import au.opendepth.app.data.WaypointRepository
import au.opendepth.app.data.WaypointStore
import au.opendepth.app.diagnostics.DebugLogShareService
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.diagnostics.MapLibreDiagnostics
import au.opendepth.app.diagnostics.ResourceTracker
import au.opendepth.app.download.OfflineTileDownloadController
import au.opendepth.app.download.isOfflineDownload
import au.opendepth.app.download.CompletedDownloads
import au.opendepth.app.download.DownloadPrompts
import au.opendepth.app.map.DepthQueryController
import au.opendepth.app.map.GpsController
import au.opendepth.app.map.GraticuleController
import au.opendepth.app.map.LocalTileConnectivity
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MapStyleCoordinator
import au.opendepth.app.map.MeasureController
import au.opendepth.app.map.OverlayLayerController
import au.opendepth.app.map.RouteController
import au.opendepth.app.model.RoutePoint
import au.opendepth.app.nav.NavGeo
import au.opendepth.app.nav.NavigationFeature
import au.opendepth.app.map.WaypointOverlayController
import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.geotiff.GeoTiffReader
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.model.Waypoint
import au.opendepth.app.settings.AppSettingsRepository
import au.opendepth.app.settings.BasemapType
import au.opendepth.app.settings.DepthDisplayMode
import au.opendepth.app.settings.VisualSettings
import au.opendepth.app.settings.XyzTilePreset
import au.opendepth.app.storage.GeoTiffImporter
import au.opendepth.app.storage.GpxExportService
import au.opendepth.app.storage.LayerImportCoordinator
import au.opendepth.app.storage.MbtilesImporter
import au.opendepth.app.storage.SeabedDownload
import au.opendepth.app.terrain.Terrain3DScreen
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.BasemapSelector
import au.opendepth.app.ui.ChartHud
import au.opendepth.app.ui.CoordinateEditorDialog
import au.opendepth.app.ui.CustomBasemapDialog
import au.opendepth.app.ui.DataSourceDialog
import au.opendepth.app.ui.DownloadZoneDialog
import au.opendepth.app.ui.DepthRampDialog
import au.opendepth.app.ui.ImportProgressDialog
import au.opendepth.app.ui.InsetBinder
import au.opendepth.app.ui.LayerPanelBinder
import au.opendepth.app.ui.MainViews
import au.opendepth.app.ui.NewWaypointSheet
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.SettingsPanel
import au.opendepth.app.ui.SidePanel
import au.opendepth.app.ui.ToolMode
import au.opendepth.app.ui.WaypointEditDialog
import au.opendepth.app.ui.WaypointPanelBinder
import au.opendepth.app.ui.XyzTileDialog
import com.google.gson.Gson
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Hosts the chart screen and wires the map, overlay stack, tools and panels
 * together.
 *
 * Domain behaviour lives in the `map`, `ui`, `data`, `storage` and `geotiff`
 * packages. What is left here is activity-level work: lifecycle, the armed tool
 * mode, and the handful of decisions that need to see more than one
 * collaborator at once.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var preferences: SharedPreferences
    private lateinit var views: MainViews

    private val gson = Gson()

    /**
     * Logs and swallows any exception a child coroutine didn't catch (for
     * example an import finishing while the activity closes), rather than
     * crashing the app.
     */
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Unhandled coroutine exception", error)
            DiagnosticLog.logError(TAG, "unhandled coroutine exception", error)
        }
    )
    private val mapHolder = MapHolder()

    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var waypointStore: WaypointStore
    private lateinit var dataSourceStore: DataSourceStore

    private lateinit var overlays: OverlayLayerController
    private lateinit var styleCoordinator: MapStyleCoordinator
    private lateinit var waypointOverlay: WaypointOverlayController
    private lateinit var graticule: GraticuleController
    private lateinit var navFeature: NavigationFeature
    private lateinit var conditions: au.opendepth.app.conditions.ConditionsFeature
    private val routeController: RouteController get() = navFeature.routes
    private lateinit var measureController: MeasureController
    private lateinit var depthQuery: DepthQueryController
    private lateinit var gpsController: GpsController
    private lateinit var layerImports: LayerImportCoordinator
    private lateinit var offlineDownloads: OfflineTileDownloadController

    private lateinit var hud: ChartHud
    private lateinit var sidePanel: SidePanel
    private lateinit var basemapSelector: BasemapSelector
    private lateinit var panels: PanelDialogs
    private lateinit var importProgressDialog: ImportProgressDialog
    private lateinit var coordinateEditor: CoordinateEditorDialog
    private lateinit var layerPanel: LayerPanelBinder
    private lateinit var waypointSheet: NewWaypointSheet
    private lateinit var waypointEditDialog: WaypointEditDialog
    private lateinit var depthRampDialog: DepthRampDialog
    private lateinit var settingsPanel: SettingsPanel
    private lateinit var xyzDialog: XyzTileDialog
    private lateinit var dataSourceDialog: DataSourceDialog
    private lateinit var downloadZoneDialog: DownloadZoneDialog
    private lateinit var customBasemapDialog: CustomBasemapDialog
    private lateinit var gpxExport: GpxExportService
    private lateinit var debugLogShare: DebugLogShareService
    private lateinit var terrain3d: Terrain3DScreen

    private var visual = VisualSettings()
    private var tool = ToolMode.NONE
    private var depthQueryJob: Job? = null
    private var resourceSnapshotJob: Job? = null

    private val downloadPrompts by lazy {
        DownloadPrompts(
            context = this,
            scope = scope,
            isLoaded = { path -> overlays.layerWithSource(path) != null },
            offerImport = { pending ->
                closePanelsForImport()
                offlineDownloads.offerImportForCompletedDownload(pending.path, pending.sourceName, pending.attribution)
            },
            showMessage = ::showAlert
        )
    }

    private val mbtilesPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                closePanelsForImport()
                layerImports.importMbtiles(it)
            }
        }
    }

    private val geoTiffPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // A persisted grant is what lets this URI be reopened again
                // later — after the app restarts — without ever having
                // copied the file into private storage.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                closePanelsForImport()
                layerImports.importGeoTiff(uri)
            }
        }
    }

    private val manageStorageSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasManageExternalStorage()) {
            launchGeoTiffFilePicker()
        } else {
            toast("Full file access was not granted — cannot import large GeoTIFF files")
        }
    }

    private val legacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            launchGeoTiffFilePicker()
        } else {
            toast("Storage permission is needed to import GeoTIFF files")
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            gpsController.start()
        } else {
            // Drop back to off so the button does not claim to be tracking.
            gpsController.turnOff()
            explainLocationRefused(approximateOnly = granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        }
        updateGpsButton()
    }

    /** Only for the download's progress notification: downloads work without it. */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagnosticLog.init(applicationContext)
        recordCrashes()
        if (BuildConfig.DEBUG) installStrictMode()
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        DiagnosticLog.log(TAG, "=== cold start: onCreate (savedState=${savedInstanceState != null}) ===")
        MapLibreDiagnostics.install(applicationContext)
        LocalTileConnectivity.install(applicationContext)
        setContentView(R.layout.activity_main)

        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        views = MainViews(this)

        buildDomain()
        buildUi()

        waypointStore.load()
        dataSourceStore.load()
        visual = settingsRepository.loadVisual()
        settingsRepository.loadRasterQuality(this).let { quality ->
            GeoTiffReader.importMaxDimension = quality.maxDimension
            GdalTileSource.defaultColourTileSize = quality.gdalTilePixels
        }
        waypointOverlay.setNamesVisible(settingsRepository.loadShowWaypointNames())
        styleCoordinator.loadSettings()

        InsetBinder.bind(views)
        sidePanel.bind()
        basemapSelector.bind()
        waypointSheet.bind()
        bindTopBar()
        bindPanelActions()
        bindCards()

        views.applyPalette()
        hud.applyVisualSettings(visual)
        highlightTools()
        refreshWaypointPanel()
        layerPanel.rebuild()

        setupMap(savedInstanceState)
        handleIncomingIntent(intent)
    }

    /**
     * Writes any crash (with its stack trace) to the debug log before the app
     * closes, so Settings > Export Debug Log shows what went wrong. Once only,
     * even when the screen is recreated.
     */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    private class CrashRecorder(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { DiagnosticLog.logError(TAG, "CRASH on thread ${thread.name}", error) }
            next?.uncaughtException(thread, error)
        }
    }

    /** Debug builds: main-thread disk/network use and leaked closeables go to the debug log. */
    private fun installStrictMode() {
        val threadPolicy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
        val vmPolicy = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectActivityLeaks()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val immediateExecutor = java.util.concurrent.Executor { it.run() }
            threadPolicy.penaltyListener(immediateExecutor) { violation ->
                DiagnosticLog.logError("StrictMode", "main-thread violation", violation)
            }
            vmPolicy.penaltyListener(immediateExecutor) { violation ->
                DiagnosticLog.logError("StrictMode", "VM policy violation (leaked resource)", violation)
            }
        } else {
            threadPolicy.penaltyLog()
            vmPolicy.penaltyLog()
        }

        StrictMode.setThreadPolicy(threadPolicy.build())
        StrictMode.setVmPolicy(vmPolicy.build())
    }

    /** The "download finished" notification was tapped (at a cold start or while running): offer the file as a layer. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != ACTION_OFFER_IMPORT_DOWNLOAD) return
        val path = intent.getStringExtra(EXTRA_DOWNLOADED_FILE_PATH) ?: return
        if (!isOfflineDownload(path)) {
            DiagnosticLog.log(TAG, "ignoring import offer for a path outside the offline downloads folder")
            intent.action = null
            return
        }
        val sourceName = intent.getStringExtra(EXTRA_DOWNLOADED_SOURCE_NAME) ?: "Download"
        val attribution = intent.getStringExtra(EXTRA_DOWNLOADED_ATTRIBUTION).orEmpty()
        // Consumed so rotation or another unrelated onNewIntent call does not re-show this.
        intent.action = null
        // The same download is also recorded for the in-app offer; offer it once.
        CompletedDownloads.clear(this)
        closePanelsForImport()
        offlineDownloads.offerImportForCompletedDownload(path, sourceName, attribution)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Construction ---------------------------------------------------------

    private fun buildDomain() {
        settingsRepository = AppSettingsRepository(preferences)
        waypointStore = WaypointStore(WaypointRepository(preferences, gson))
        dataSourceStore = DataSourceStore(DataSourceRepository(preferences, gson))

        // Built here, ahead of the rest of buildUi()'s dialogs, because
        // LayerImportCoordinator (also built in this function) needs it
        // for ImportProgressDialog — PanelDialogs itself has
        // no dependency on anything else this Activity builds.
        panels = PanelDialogs(this)
        importProgressDialog = ImportProgressDialog(this, panels)

        overlays = OverlayLayerController(
            context = this,
            mapHolder = mapHolder,
            repository = OverlayLayerRepository(preferences, gson),
            scope = scope,
            onStackChanged = ::onOverlayStackChanged,
            onMessage = ::showAlert,
            onVisibilityChanged = ::onOverlayVisibilityChanged
        )

        measureController = MeasureController(mapHolder)
        waypointOverlay = WaypointOverlayController(this, mapHolder, waypointStore.all)
        depthQuery = DepthQueryController(mapHolder, overlays)
        gpsController = GpsController(
            this,
            mapHolder,
            onLocationChanged = { location ->
                navFeature.onLocation(location)
                if (::conditions.isInitialized) conditions.onLocation(location.latitude, location.longitude)
                terrain3d.updateBoat(location)
            },
            followBearing = { location -> navFeature.chartBearing(location) }
        )

        graticule = GraticuleController(mapHolder).apply {
            setVisible(settingsRepository.loadShowGrid())
        }

        styleCoordinator = MapStyleCoordinator(
            graticule = graticule,
            mapHolder = mapHolder,
            settings = settingsRepository,
            overlays = overlays,
            onStyleInstalled = ::onStyleInstalled,
            onAttributionChanged = { text -> hud.showAttribution(text) },
            onMessage = ::toast,
            isOnline = ::hasWorkingInternet,
            extraAttributions = { if (::conditions.isInitialized) conditions.credits() else emptyList() }
        )
        watchConnectivity()

        layerImports = LayerImportCoordinator(
            context = this,
            contentResolver = contentResolver,
            filesDir = filesDir,
            scope = scope,
            overlays = overlays,
            importProgress = importProgressDialog,
            onResult = ::toastLong,
            onError = ::showAlert,
            onShowArea = ::showArea
        )
    }

    private fun buildUi() {
        hud = ChartHud(views)
        navFeature = NavigationFeature(
            activity = this,
            views = views,
            hud = hud,
            panels = panels,
            mapHolder = mapHolder,
            scope = scope,
            preferences = preferences,
            gson = gson,
            waypointStore = waypointStore,
            callbacks = object : NavigationFeature.Callbacks {
                override fun depthSampler(): (Double, Double) -> Double? {
                    val sources = overlays.visibleDepthSourcesTopFirst()
                    return { lat, lng -> sources.firstNotNullOfOrNull { runCatching { it.sampleMetres(lat, lng) }.getOrNull() } }
                }
                override fun routeToolActive() = tool == ToolMode.ROUTE
                override fun mapCentre() = mapHolder.map?.cameraPosition?.target?.let { it.latitude to it.longitude }
                override fun startGpsFollow() = followWithGps()
                override fun lastKnownPosition() = lastKnownPhonePosition()
                override fun onNavigationStarted() {
                    sidePanel.close()
                    panels.dismissAll()
                    if (tool != ToolMode.NONE) toggleTool(tool)
                }
                override fun waypointsChanged() = refreshWaypoints()
                override fun launchGpxImport() = launchGpxImportPicker()
                override fun shareGpx(points: List<RoutePoint>, name: String) = shareRouteGpx(points, name)
                override fun toast(message: String) = this@MainActivity.toastLong(message)
                override fun alert(message: String) = showAlert(message)
                override fun startGps() {
                    if (gpsController.mode == GpsController.Mode.OFF) cycleGps()
                }
                override fun closePanels() {
                    panels.dismissAll()
                    sidePanel.close()
                }
                override fun requestNotifications() = requestNotificationPermissionIfNeeded()
                override fun unitsChanged() {
                    if (::conditions.isInitialized) conditions.unitsChanged()
                }
                override fun depthReadoutChanged() {
                    if (tool == ToolMode.MEASURE) return
                    mapHolder.map?.cameraPosition?.target?.let { scheduleDepthQuery(it.latitude, it.longitude) }
                }
                override fun northUp() {
                    mapHolder.map?.let { map ->
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()))
                    }
                }
            }
        )
        conditions = au.opendepth.app.conditions.ConditionsFeature(
            activity = this,
            panels = panels,
            mapHolder = mapHolder,
            scope = scope,
            preferences = preferences,
            units = { navFeature.settings.units },
            callbacks = object : au.opendepth.app.conditions.ConditionsFeature.Callbacks {
                override fun toast(message: String) = this@MainActivity.toastLong(message)
                override fun showArea(bounds: GeoBounds) = this@MainActivity.showArea(bounds)
                override fun creditsChanged() = styleCoordinator.refreshAttribution()
                override fun isOnline() = hasWorkingInternet()
                override fun lastKnownPosition() = lastKnownPhonePosition()
                override fun startGps() {
                    if (gpsController.mode == GpsController.Mode.OFF) cycleGps()
                }
                override fun isNavigating() = navFeature.isNavigating
            }
        )
        sidePanel = SidePanel(views)
        coordinateEditor = CoordinateEditorDialog(this, panels)
        gpxExport = GpxExportService(this)
        debugLogShare = DebugLogShareService(this)
        terrain3d = Terrain3DScreen(
            activity = this,
            scope = scope,
            units = { navFeature.settings.units },
            onAddWaypoint = { lat, lng, depth ->
                val waypoint = waypointStore.add(
                    name = waypointStore.nextDefaultName(),
                    lat = lat,
                    lng = lng,
                    color = Waypoint.DEFAULT_COLOR,
                    description = String.format(Locale.US, "Depth %.1f m (marked in 3D)", kotlin.math.abs(depth))
                )
                refreshWaypoints()
                waypoint
            },
            onStartGps = {
                // Off -> locate, through the same permission flow as the GPS button.
                if (gpsController.mode == GpsController.Mode.OFF) cycleGps()
            },
            mapLayers = { overlays.terrainDrapes() },
            onSaveOffline = { source, bounds, maxZoom ->
                requestNotificationPermissionIfNeeded()
                offlineDownloads.beginDownload(source, bounds, maxZoom)
            },
            onShowingChanged = { showing ->
                // The sea conditions' moving lines would keep drawing, unseen, under the 3D view.
                if (showing) conditions.onPause() else conditions.onResume()
            },
            onEditWaypoint = { id ->
                waypointStore.all.firstOrNull { it.id == id }?.let { waypoint ->
                    // "Go to" from 3D centres the pin there instead of moving the hidden chart.
                    showWaypointEditor(waypoint) { terrain3d.focusWaypoint(it.id) }
                }
            }
        )
        depthRampDialog = DepthRampDialog(this, panels)
        xyzDialog = XyzTileDialog(this, panels)
        dataSourceDialog = DataSourceDialog(this, panels)
        downloadZoneDialog = DownloadZoneDialog(this, panels)
        settingsPanel = SettingsPanel(this, panels, xyzDialog, dataSourceDialog)
        customBasemapDialog = CustomBasemapDialog(this, panels)
        basemapSelector = BasemapSelector(this, views, styleCoordinator, customBasemapDialog)

        layerPanel = LayerPanelBinder(
            context = this,
            container = views.layersList,
            controller = overlays,
            panels = panels,
            onEditDepthColours = ::showDepthRampDialog,
            onOpen3d = ::open3d,
            onShowLayer = ::showLayer
        )

        waypointEditDialog = WaypointEditDialog(this, panels, coordinateEditor)
        waypointSheet = NewWaypointSheet(views, coordinateEditor, ::saveNewWaypoint)

        offlineDownloads = OfflineTileDownloadController(
            context = this,
            mapHolder = mapHolder,
            scope = scope,
            panels = panels,
            downloadZoneDialog = downloadZoneDialog,
            layerImports = layerImports,
            isOnline = ::hasWorkingInternet,
            onProgress = ::toast,
            onError = ::showAlert
        )
    }

    // Connectivity -----------------------------------------------------------

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** The newest position any of the phone's location providers remembers, when allowed. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownPhonePosition(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return runCatching {
            val manager = getSystemService(android.location.LocationManager::class.java) ?: return null
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    /** True when the phone's network has been checked to reach the internet. */
    private fun hasWorkingInternet(): Boolean = runCatching {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = manager?.getNetworkCapabilities(manager.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    /** Brings the online basemap back when the internet returns (it was swapped out while offline). */
    private fun watchConnectivity() {
        val manager = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    styleCoordinator.onConnectivityRestored()
                    if (::conditions.isInitialized) conditions.onConnectivityRestored()
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { DiagnosticLog.logError(TAG, "couldn't watch connectivity", it) }
    }

    // Map ------------------------------------------------------------------

    private fun setupMap(savedState: Bundle?) {
        views.mapView.onCreate(savedState)
        views.mapView.addOnDidFailLoadingMapListener { errorMessage ->
            DiagnosticLog.log(
                TAG,
                "onDidFailLoadingMap: $errorMessage; ${MapLibreDiagnostics.describeConnectivity(this)}"
            )
            styleCoordinator.handleStyleLoadFailure()
        }
        views.mapView.getMapAsync { map ->
            mapHolder.map = map
            map.cameraPosition = settingsRepository.loadCameraPosition()
            // The credits line under the chart names every source shown; MapLibre's
            // logo and its (i) button only took room at the bottom.
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false

            styleCoordinator.rebuild()

            map.addOnMapClickListener { position ->
                val waypoint = waypointOverlay.waypointIdAt(position)
                    ?.let { waypointStore.byId(it) }
                when {
                    waypoint == null -> handleMapTap(position.latitude, position.longitude)
                    // Route and measure snap to a tapped waypoint instead of
                    // opening its editor, so a route can be planned through marks.
                    tool == ToolMode.ROUTE -> navFeature.addRoutePoint(waypoint.lat, waypoint.lng, waypoint.name)
                    tool == ToolMode.MEASURE -> {
                        measureController.add(waypoint.lat, waypoint.lng)
                        measureController.refresh()
                        hud.showMeasureSummary(measureController.summary())
                    }
                    else -> showWaypointEditor(waypoint)
                }
                true
            }

            // A drag (not a pinch) while following lets go of the boat, as on
            // a chartplotter; tap GPS again to follow.
            map.addOnMoveListener(object : org.maplibre.android.maps.MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                    if (gpsController.releaseFollow()) updateGpsButton()
                }
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })

            map.addOnCameraMoveListener {
                map.cameraPosition.target?.let { target ->
                    hud.showCoordinates(target.latitude, target.longitude)
                }
            }

            map.addOnCameraIdleListener {
                val position = map.cameraPosition
                val target = position.target ?: return@addOnCameraIdleListener
                settingsRepository.saveCameraPosition(position)
                graticule.refresh()
                hud.showCoordinates(target.latitude, target.longitude)
                if (tool != ToolMode.MEASURE) {
                    scheduleDepthQuery(target.latitude, target.longitude)
                }
            }
        }
    }

    /** Called after every style rebuild, once the overlay stack is attached. */
    private fun onStyleInstalled() {
        // Waypoints go on last so they stay above imported rasters.
        waypointOverlay.install()
        navFeature.onStyleInstalled()
        conditions.onStyleInstalled()
        navFeature.placeCompass()
        measureController.refresh()
        hud.showMeasureSummary(measureController.summary())
        hud.setCrosshairVisible(tool != ToolMode.MEASURE)
        hud.applyVisualSettings(visual)
        layerPanel.rebuild()
    }

    /** A layer was shown or hidden: its credit comes or goes, and the depth under the crosshair may change. */
    private fun onOverlayVisibilityChanged() {
        navFeature.onLayersChanged()
        styleCoordinator.refreshAttribution()
        mapHolder.map?.cameraPosition?.target?.let { target ->
            scheduleDepthQuery(target.latitude, target.longitude)
        }
    }

    private fun onOverlayStackChanged() {
        layerPanel.rebuild()
        navFeature.onLayersChanged()
        styleCoordinator.refreshAttribution()
        mapHolder.map?.cameraPosition?.target?.let { target ->
            scheduleDepthQuery(target.latitude, target.longitude)
        }
    }

    /**
     * The waypoint and route tools place their point at the centre crosshair
     * rather than where the finger landed, so a mark can be positioned
     * precisely by panning. The tap position is only a fallback for when the
     * camera target is unavailable.
     */
    private fun handleMapTap(lat: Double, lng: Double) {
        when (tool) {
            ToolMode.WAYPOINT -> showWaypointSheetAtCentre(lat, lng)

            ToolMode.ROUTE -> {
                val centre = mapHolder.map?.cameraPosition?.target
                val pointLat = centre?.latitude ?: lat
                val pointLng = centre?.longitude ?: lng
                // A second tap without moving the map would add a 0 m leg.
                val last = navFeature.lastRoutePoint()
                if (last != null && NavGeo.distanceM(last.lat, last.lng, pointLat, pointLng) < 1.0) {
                    toast("Move the map to put the crosshair on the next point")
                } else {
                    navFeature.addRoutePoint(pointLat, pointLng)
                }
            }

            ToolMode.MEASURE -> {
                measureController.add(lat, lng)
                measureController.refresh()
                hud.showMeasureSummary(measureController.summary())
            }

            ToolMode.NONE -> Unit
        }
    }

    private fun showWaypointSheetAtCentre(fallbackLat: Double, fallbackLng: Double) {
        // Guards against a single tap being reported more than once (seen on
        // a cold-start map before its first frame has settled), which would
        // otherwise reset the sheet's pending position mid-gesture.
        if (waypointSheet.isVisible) return
        val centre = mapHolder.map?.cameraPosition?.target
        waypointSheet.show(
            centre?.latitude ?: fallbackLat,
            centre?.longitude ?: fallbackLng
        )
    }

    // Top bar --------------------------------------------------------------

    private fun bindTopBar() {
        ToolMode.SELECTABLE.forEach { mode ->
            views.toolButtons[mode]?.setOnClickListener { toggleTool(mode) }
        }

        views.btnGps.setOnClickListener { cycleGps() }

        views.coordinates.setOnClickListener {
            val target = mapHolder.map?.cameraPosition?.target ?: return@setOnClickListener
            coordinateEditor.show(
                title = "Go to coordinates",
                initialLat = target.latitude,
                initialLng = target.longitude,
                positiveLabel = "Create waypoint",
                showMoveButton = true,
                onMoveMap = ::flyTo
            ) { lat, lng -> waypointSheet.show(lat, lng) }
        }
    }

    private fun toggleTool(mode: ToolMode) {
        val next = if (tool == mode) ToolMode.NONE else mode

        if (tool == ToolMode.MEASURE && next != ToolMode.MEASURE) {
            clearMeasurement()
        }

        tool = next
        hud.showHint(if (next == ToolMode.NONE) null else getString(next.hintResId))
        hud.setCrosshairVisible(next != ToolMode.MEASURE)
        navFeature.showRouteSummary()
        hud.showDepth("")
        highlightTools()
    }

    private fun highlightTools() {
        val palette = AppTheme.palette
        views.toolButtons.forEach { (mode, button) ->
            button.imageTintList = ColorStateList.valueOf(
                if (mode == tool) palette.accent else palette.secondaryText
            )
        }
        updateGpsButton()
    }

    private fun cycleGps() {
        if (gpsController.cycleMode() == GpsController.Mode.OFF) {
            gpsController.stop()
            gpsController.clearMarker()
            // Otherwise a GPS-speed ETA keeps using the last fix's speed.
            routeController.updateGpsSpeedKnots(null)
            navFeature.refreshRoute()
        } else if (hasLocationPermission()) {
            gpsController.start()
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        updateGpsButton()
    }

    private fun updateGpsButton() {
        views.btnGps.imageTintList = ColorStateList.valueOf(
            when (gpsController.mode) {
                GpsController.Mode.LOCATE -> Color.parseColor(GPS_LOCATE_COLOR)
                GpsController.Mode.FOLLOW -> Color.parseColor(GPS_FOLLOW_COLOR)
                GpsController.Mode.OFF -> AppTheme.palette.secondaryText
            }
        )
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // Side panel actions ---------------------------------------------------

    private fun bindPanelActions() {
        views.btnSettings.setOnClickListener {
            settingsPanel.show(
                SettingsPanel.Callbacks(
                    currentVisual = { visual },
                    onVisualChanged = ::updateVisualSettings,
                    customBasemapConfigured = { styleCoordinator.customBasemapConfigured },
                    xyz = xyzCallbacks(),
                    onLoadMbtiles = ::launchMbtilesPicker,
                    onLoadGeoTiff = ::launchGeoTiffPicker,
                    onEditDepthColours = ::pickDepthColourLayer,
                    onExportGpx = ::exportGpx,
                    onShareDebugLog = ::shareDebugLog,
                    rasterQuality = { settingsRepository.loadRasterQuality(this) },
                    onRasterQualityChanged = { quality ->
                        settingsRepository.saveRasterQuality(quality)
                        GeoTiffReader.importMaxDimension = quality.maxDimension
                        // Large (GDAL) layers re-render straight away; smaller
                        // in-memory ones are decoded at the new size next start.
                        overlays.setGdalTileSize(quality.gdalTilePixels)
                        toastLong("${quality.label} quality set. Large surveys redraw now; smaller ones change the next time SeaBathy starts.")
                    },
                    showWaypointNames = { waypointOverlay.namesVisible },
                    onShowWaypointNamesChanged = { visible ->
                        settingsRepository.saveShowWaypointNames(visible)
                        waypointOverlay.setNamesVisible(visible)
                    },
                    onNavigationSettings = navFeature::showNavSettings,
                    onRoutes = navFeature::showRoutes,
                    // These work on the chart, so the drawer gets out of the way.
                    onTides = { sidePanel.close(); navFeature.showTides() },
                    onAnchor = { sidePanel.close(); navFeature.showAnchor() },
                    onTracks = { sidePanel.close(); navFeature.showTracks() },
                    onConditions = { sidePanel.close(); conditions.show() },
                    onImportGpx = ::launchGpxImportPicker,
                    showGrid = { graticule.visible },
                    onShowGridChanged = { visible ->
                        settingsRepository.saveShowGrid(visible)
                        graticule.setVisible(visible)
                    },
                    onGetSeabed = ::getSeabedDepths,
                    dataSources = DataSourceDialog.Callbacks(
                        sources = { dataSourceStore.all },
                        onAdd = { name, urlTemplate, attribution ->
                            dataSourceStore.add(name, urlTemplate, attribution)
                        },
                        onRemove = { source -> dataSourceStore.remove(source.id) },
                        onDownload = { source ->
                            requestNotificationPermissionIfNeeded()
                            offlineDownloads.beginDownload(source)
                        }
                    )
                )
            )
        }
    }

    /** Downloads free depth data for the area on screen and opens it like any survey. */
    private fun getSeabedDepths() {
        val map = mapHolder.map ?: return
        val visible = map.projection.visibleRegion.latLngBounds
        val target = map.cameraPosition.target ?: return
        val area = SeabedDownload.areaFor(
            visible.latitudeSouth, visible.longitudeWest, visible.latitudeNorth, visible.longitudeEast,
            target.latitude, target.longitude
        )
        val note = if (area.trimmed) " The most one download holds is about ${SeabedDownload.MAX_SPAN_KM} km across, so this is the middle of the screen." else ""
        panels.confirm(
            title = "Get seabed depths?",
            message = "Downloads depths for the area on screen from GMRT, which blends ship surveys with the global " +
                "GEBCO grid, and adds them as a layer that also works in 3D and offline. Detail is best where ships " +
                "have surveyed; elsewhere it's much coarser. Up to about 55 MB.$note Not for navigation.",
            confirmLabel = "Download",
            destructive = false
        ) {
            sidePanel.close()
            layerImports.downloadSeabed(area)
        }
    }

    private fun launchMbtilesPicker() {
        mbtilesPicker.launch(pickFileIntent(MbtilesImporter.EXTENSION))
    }

    /**
     * GDAL needs a real filesystem path for the file the user picks (see
     * [au.opendepth.app.storage.RealPathResolver]), so importing a GeoTIFF
     * needs full file access first. That's asked for here, on first import
     * tap, rather than at app startup — most sessions never touch this
     * feature at all.
     */
    private fun launchGeoTiffPicker() {
        if (hasManageExternalStorage()) {
            launchGeoTiffFilePicker()
        } else {
            showManageStorageExplanation()
        }
    }

    private fun launchGeoTiffFilePicker() {
        // ACTION_OPEN_DOCUMENT (not ACTION_GET_CONTENT) is what lets the
        // returned URI's permission be persisted across app restarts, which
        // is what lets this layer be reopened again later without this app
        // ever having copied the file into private storage.
        geoTiffPicker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, GeoTiffImporter.ACCEPTED_MIME_TYPES)
            }
        )
    }

    private fun hasManageExternalStorage(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showManageStorageExplanation() {
        AlertDialog.Builder(this, ALERT_THEME)
            .setTitle("Full file access needed")
            .setMessage("SeaBathy needs full file access to read large depth survey files directly.")
            .setPositiveButton("Continue") { _, _ -> requestManageExternalStorage() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun requestManageExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manageStorageSettings.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            legacyStoragePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun pickFileIntent(extensionHint: String): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Providers report chart formats inconsistently, so the filter
            // stays wide and validation happens on import instead.
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, extensionHint)
        }

    /**
     * Imagery goes on part-transparent so reef and rock detail blends with the
     * bathymetry underneath instead of hiding it; other tile types go on solid.
     */
    private fun presetOpacityFor(preset: XyzTilePreset): Float =
        if (preset.category == XyzTilePreset.Category.SATELLITE) {
            SATELLITE_DEFAULT_OPACITY
        } else {
            1f
        }

    private fun xyzCallbacks() = XyzTileDialog.Callbacks(
        currentBasemapUrl = { styleCoordinator.customUrl },
        currentBasemapAttribution = { styleCoordinator.customAttribution },
        onAddPreset = { preset ->
            overlays.addXyzPreset(preset, opacity = presetOpacityFor(preset))
            toast("Added ${preset.label}")
        },
        onAddOverlay = { name, url, attribution ->
            overlays.addXyz(name, url, attribution)
            toast("Added $name")
        },
        onSaveBasemap = { url, attribution ->
            styleCoordinator.saveCustomBasemap(url, attribution)
            styleCoordinator.rebuild(BasemapType.CUSTOM)
            basemapSelector.sync()
            toast("Custom basemap saved")
        },
        onClearBasemap = {
            styleCoordinator.clearCustomBasemap()
            basemapSelector.sync()
            toast("Custom basemap cleared")
        }
    )

    // Cards ----------------------------------------------------------------

    private fun bindCards() {
        views.btnMeasureClear.setOnClickListener {
            clearMeasurement()
            if (tool == ToolMode.MEASURE) toggleTool(ToolMode.MEASURE)
        }

        navFeature.bind()
        conditions.bind(
            root = views.mapView.parent as android.view.ViewGroup,
            mapView = views.mapView,
            topViews = { navFeature.topViews },
            bottomViews = listOf(views.hint, views.routeCard, views.measureCard, views.mapAttribution)
        )
        navFeature.extraTopViews = listOf(conditions.boxView)
    }

    private fun clearMeasurement() {
        measureController.clear()
        measureController.refresh()
        hud.showMeasureSummary(measureController.summary())
    }

    // Waypoints ------------------------------------------------------------

    private fun refreshWaypointPanel() {
        WaypointPanelBinder(
            context = this,
            container = views.waypointsList,
            waypoints = waypointStore.all,
            onGoTo = { waypoint ->
                flyTo(waypoint.lat, waypoint.lng)
                sidePanel.close()
            },
            onEdit = ::showWaypointEditor
        ).rebuild()
    }

    private fun refreshWaypoints() {
        waypointOverlay.render()
        refreshWaypointPanel()
        waypointStore.save()
        if (terrain3d.isOpen) terrain3d.updateWaypoints(waypointStore.all)
    }

    private fun showWaypointEditor(
        waypoint: Waypoint,
        onJumpTo: (Waypoint) -> Unit = { target ->
            flyTo(target.lat, target.lng)
            sidePanel.close()
        }
    ) {
        waypointEditDialog.show(
            waypoint = waypoint,
            isNameTaken = { name -> waypointStore.all.any { it !== waypoint && it.name.equals(name, ignoreCase = true) } },
            onChanged = ::refreshWaypoints,
            onJumpTo = onJumpTo,
            onDelete = { target ->
                waypointStore.remove(target)
                refreshWaypoints()
            }
        )
    }

    /** @return an error message to show on the name field, or null on success. */
    private fun saveNewWaypoint(request: NewWaypointSheet.Request): String? {
        val name = request.name.ifBlank { waypointStore.nextDefaultName() }
        if (waypointStore.isNameTaken(name)) {
            return "A waypoint called \"$name\" already exists"
        }

        waypointStore.add(
            name = name,
            lat = request.lat,
            lng = request.lng,
            color = request.color,
            description = request.description,
            icon = request.icon
        )
        refreshWaypoints()

        if (tool == ToolMode.WAYPOINT) toggleTool(ToolMode.WAYPOINT)
        return null
    }

    private val gpxImportPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                closePanelsForImport()
                navFeature.importGpx(uri)
            }
        }
    }

    private fun launchGpxImportPicker() {
        gpxImportPicker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // Providers label .gpx inconsistently; the importer checks the content.
                type = "*/*"
            }
        )
    }

    /** Follow mode with GPS on, asking for location permission if needed (used when navigating). */
    private fun followWithGps() {
        gpsController.followMode()
        if (hasLocationPermission()) {
            gpsController.start()
        } else {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        updateGpsButton()
    }

    private fun shareRouteGpx(points: List<RoutePoint>, name: String) {
        if (points.size < 2) {
            toast("Add at least two points to export a route")
            return
        }
        val intent = runCatching { gpxExport.buildShareIntent(emptyList(), points, name) }.getOrNull()
        if (intent == null) {
            toast("Couldn't create the GPX file")
            return
        }
        runCatching { startActivity(intent) }.onFailure { toast("No app available to receive the GPX file") }
    }

    private fun exportGpx() {
        val intent = runCatching {
            gpxExport.buildShareIntent(waypointStore.all, routeController.routePoints, routeController.savedName ?: "Route")
        }.getOrElse { error ->
            DiagnosticLog.logError(TAG, "GPX export failed", error)
            toast("Couldn't create the GPX file")
            return
        }
        if (intent == null) {
            toast("Add a waypoint or route before exporting")
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { error ->
                Log.e(TAG, "GPX share failed", error)
                toast("No app available to receive the GPX file")
            }
    }

    private fun shareDebugLog() {
        val intent = runCatching { debugLogShare.buildShareIntent() }.getOrElse { error ->
            DiagnosticLog.logError(TAG, "Debug log export failed", error)
            toast("Couldn't prepare the debug log for sharing")
            return
        }
        if (intent == null) {
            toast("The debug log is empty — there is nothing to share yet")
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { error ->
                Log.e(TAG, "Debug log share failed", error)
                toast("No app available to receive the log file")
            }
    }

    // Depth layers ---------------------------------------------------------

    /**
     * Entry point from Settings, independent of the per-layer list. Only
     * layers with a [au.opendepth.app.geotiff.DepthSource] qualify, so
     * MBTiles layers never show up here and are never touched by it.
     */
    private fun pickDepthColourLayer() {
        val depthLayers = overlays.stackTopFirst.filter { overlays.depthSourceFor(it.id) != null }
        when {
            depthLayers.isEmpty() ->
                toast("Load a GeoTIFF depth layer first")

            depthLayers.size == 1 ->
                showDepthRampDialog(depthLayers.first())

            else -> panels.showChoicePanel(
                title = "Choose a depth layer",
                labels = depthLayers.map { it.name }
            ) { index -> showDepthRampDialog(depthLayers[index]) }
        }
    }

    private fun showDepthRampDialog(layer: OverlayLayer) {
        val source = overlays.depthSourceFor(layer.id)
        if (source == null) {
            toast("${layer.name} has no depth data to recolour")
            return
        }
        // The colours sheet leaves the map visible; the layer list would cover it.
        sidePanel.close()

        scope.launch {
            // How the depths are spread (a second or two the first time on a big survey).
            val histogram = withContext(Dispatchers.IO) { runCatching { source.histogram() }.getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            depthRampDialog.show(
                layerName = layer.name,
                dataShallowestMetres = source.shallowestMetres,
                dataDeepestMetres = source.deepestMetres,
                initial = layer.ramp ?: OverlayLayerController.autoFitRamp(source),
                // Every depth layer is shaded now, not only GDAL-backed ones.
                hillshadeAvailable = true,
                histogram = histogram
            ) { ramp -> overlays.setRamp(layer.id, ramp) }
        }
    }

    private fun open3d(layer: OverlayLayer) {
        val source = overlays.depthSourceFor(layer.id)
        if (source == null) {
            toast("${layer.name} has no depth data to show in 3D")
            return
        }
        sidePanel.close()
        terrain3d.open(
            name = layer.name,
            depthSource = source,
            marks = waypointStore.all,
            region = visibleRegionWithin(source.bounds),
            detail = settingsRepository.loadRasterQuality(this).terrainDetail,
            ramp = layer.ramp ?: OverlayLayerController.autoFitRamp(source)
        )
    }

    /**
     * The part of [layerBounds] on screen, when that is clearly smaller than
     * the whole layer: a large survey opened in 3D then starts on the area
     * being looked at, at close to the survey's own resolution, instead of
     * the whole file averaged down to a few thousand cells. Null means use
     * the whole layer.
     */
    private fun visibleRegionWithin(layerBounds: GeoBounds): GeoBounds? {
        val visible = mapHolder.map?.projection?.visibleRegion?.latLngBounds ?: return null
        val west = maxOf(layerBounds.west, visible.longitudeWest)
        val east = minOf(layerBounds.east, visible.longitudeEast)
        val south = maxOf(layerBounds.south, visible.latitudeSouth)
        val north = minOf(layerBounds.north, visible.latitudeNorth)
        if (east <= west || north <= south) return null
        val visibleArea = (east - west) * (north - south)
        val layerArea = (layerBounds.east - layerBounds.west) * (layerBounds.north - layerBounds.south)
        return if (visibleArea < layerArea * REGION_3D_FRACTION) GeoBounds(west, south, east, north) else null
    }

    // Settings -------------------------------------------------------------

    private fun updateVisualSettings(settings: VisualSettings) {
        visual = settings
        settingsRepository.saveVisual(settings)
        hud.applyVisualSettings(settings)
        hud.showDepth("")
    }

    // Depth readout --------------------------------------------------------

    private fun scheduleDepthQuery(lat: Double, lng: Double) {
        depthQueryJob?.cancel()
        if (!depthEnabledForCurrentTool()) {
            hud.showDepth("")
            return
        }

        depthQueryJob = scope.launch {
            delay(DEPTH_QUERY_DEBOUNCE_MS)
            hud.showDepth(depthQuery.query(lat, lng, navFeature::formatDepth))
        }
    }

    private fun depthEnabledForCurrentTool(): Boolean = when (visual.depthDisplayMode) {
        DepthDisplayMode.OFF -> false
        DepthDisplayMode.NORMAL -> tool == ToolMode.NONE
        DepthDisplayMode.WAYPOINT -> tool == ToolMode.WAYPOINT
        else -> tool == ToolMode.NONE || tool == ToolMode.WAYPOINT
    }

    // Helpers --------------------------------------------------------------

    /** Out of the way of the map, which then moves to the new layer. */
    private fun closePanelsForImport() {
        panels.dismissAll()
        sidePanel.close()
    }

    /** Closes the panel and fits the layer on screen (tapping its name in the list). */
    private fun showLayer(layer: OverlayLayer) {
        val bounds = overlays.boundsOf(layer.id) ?: run {
            toast("${layer.name} has no known area to show")
            return
        }
        sidePanel.close()
        if (gpsController.releaseFollow()) updateGpsButton()
        val map = mapHolder.map ?: return
        val minZoom = overlays.minZoomOf(layer.id)
        val box = LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west)
        val padding = (SHOW_AREA_PADDING_DP * resources.displayMetrics.density).toInt()
        val fit = map.getCameraForLatLngBounds(box, intArrayOf(padding, padding, padding, padding))
        // A map pack drawn only from zoom 10 in shows nothing when its whole
        // area is fitted on screen: go in to where it shows, at its middle.
        if (fit != null && minZoom != null && fit.zoom < minZoom) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(fit.target ?: box.center, minZoom))
        } else {
            showArea(bounds)
        }
    }

    /** Fits [bounds] on screen, e.g. a survey that was just loaded. */
    private fun showArea(bounds: GeoBounds) {
        val box = LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west)
        val padding = (SHOW_AREA_PADDING_DP * resources.displayMetrics.density).toInt()
        mapHolder.map?.animateCamera(CameraUpdateFactory.newLatLngBounds(box, padding))
    }

    private fun flyTo(lat: Double, lng: Double) {
        mapHolder.map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), GO_TO_ZOOM)
        )
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toastLong(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /**
     * Approximate location can be kilometres out, which is unsafe on a chart,
     * so only Precise is used. Once Android stops asking (two refusals), the
     * only way back is the app's settings page, so offer it.
     */
    private fun explainLocationRefused(approximateOnly: Boolean) {
        val blocked = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val message = when {
            approximateOnly ->
                "SeaBathy needs Precise location to show your position: Approximate location can be " +
                    "kilometres out. Turn on \"Use precise location\" for SeaBathy in Settings."
            blocked -> "Location is turned off for SeaBathy. To show your position on the chart, allow location in Settings."
            else -> null
        }
        if (message == null) {
            toast("Location permission is needed to show your position")
            return
        }
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this, ALERT_THEME)
            .setTitle("Location")
            .setMessage(message)
            .setPositiveButton("Open settings") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showAlert(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this, ALERT_THEME)
            .setTitle("SeaBathy")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // Lifecycle ------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        views.mapView.onStart()
        if (BuildConfig.DEBUG) startResourceSnapshots()
    }

    /** Debug builds only: a resource snapshot every minute while the app is visible; see [ResourceTracker]. */
    private fun startResourceSnapshots() {
        resourceSnapshotJob?.cancel()
        resourceSnapshotJob = scope.launch {
            while (true) {
                ResourceTracker.snapshot(this@MainActivity, "periodic", overlays.serverLayerCount)
                delay(RESOURCE_SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        views.mapView.onResume()
        terrain3d.onResume()
        if (gpsController.isActive && hasLocationPermission()) gpsController.start()
        downloadPrompts.onResume()
        navFeature.onResume()
        if (!terrain3d.isOpen) conditions.onResume()
    }


    override fun onPause() {
        downloadPrompts.onPause()
        navFeature.onPause()
        conditions.onPause()
        terrain3d.onPause()
        waypointStore.save()
        gpsController.stop()
        views.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        resourceSnapshotJob?.cancel()
        resourceSnapshotJob = null
        ResourceTracker.snapshot(this, "background", overlays.serverLayerCount)
        views.mapView.onStop()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ResourceTracker.snapshot(this, "onTrimMemory level=$level", overlays.serverLayerCount)
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        navFeature.onDestroy()
        conditions.release()
        terrain3d.close()
        gpsController.stop()
        depthQueryJob?.cancel()
        scope.cancel()
        overlays.release()
        mapHolder.clear()
        views.mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        views.mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        views.mapView.onLowMemory()
    }

    @Deprecated("Overridden so back dismisses the sheet and panel first")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            terrain3d.isOpen -> terrain3d.close()
            waypointSheet.isVisible -> waypointSheet.hide()
            sidePanel.isOpen -> sidePanel.close()
            else -> super.onBackPressed()
        }
    }

    /** Public: the download service uses these to build its notification's intent. */
    companion object {
        const val TAG = "SeaBathy"
        const val PREFERENCES_NAME = "od"
        const val DEPTH_QUERY_DEBOUNCE_MS = 60L
        const val REGION_3D_FRACTION = 0.6
        const val RESOURCE_SNAPSHOT_INTERVAL_MS = 60_000L
        const val GO_TO_ZOOM = 14.0
        const val SHOW_AREA_PADDING_DP = 32
        /** Dark alert dialogs; the default one drew this app's light text on a white box. */
        val ALERT_THEME = androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
        const val SATELLITE_DEFAULT_OPACITY = 0.7f
        const val GPS_LOCATE_COLOR = "#22C55E"
        const val GPS_FOLLOW_COLOR = "#F97316"
        const val ACTION_OFFER_IMPORT_DOWNLOAD = "au.opendepth.app.ACTION_OFFER_IMPORT_DOWNLOAD"
        const val EXTRA_DOWNLOADED_FILE_PATH = "downloaded_file_path"
        const val EXTRA_DOWNLOADED_SOURCE_NAME = "downloaded_source_name"
        const val EXTRA_DOWNLOADED_ATTRIBUTION = "downloaded_attribution"
    }
}
