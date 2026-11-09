package au.opendepth.app.nav

import android.content.SharedPreferences
import android.hardware.GeomagneticField
import android.location.Location
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import au.opendepth.app.R
import au.opendepth.app.data.RouteLibrary
import au.opendepth.app.data.RouteRepository
import au.opendepth.app.data.SavedRoute
import au.opendepth.app.data.WaypointStore
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.RouteController
import au.opendepth.app.map.RouteDepthCheck
import au.opendepth.app.model.RoutePoint
import au.opendepth.app.model.Waypoint
import au.opendepth.app.storage.GpxImporter
import au.opendepth.app.tides.TideFeature
import au.opendepth.app.track.TrackFeature
import au.opendepth.app.ui.ChartHud
import au.opendepth.app.ui.MainViews
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.dp
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Routes, navigation and tides: the route card, saved routes, the boat
 * profile, live route navigation with its display and alarms, GPX import
 * and the tide forecast. MainActivity forwards GPS fixes, style rebuilds
 * and lifecycle calls here.
 */
class NavigationFeature(
    private val activity: AppCompatActivity,
    private val views: MainViews,
    private val hud: ChartHud,
    private val panels: PanelDialogs,
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val preferences: SharedPreferences,
    private val gson: Gson,
    private val waypointStore: WaypointStore,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        /** Survey depth sampler for the visible depth layers (blocking; call off the main thread). */
        fun depthSampler(): (lat: Double, lng: Double) -> Double?
        fun routeToolActive(): Boolean
        fun mapCentre(): Pair<Double, Double>?
        fun startGpsFollow()
        fun onNavigationStarted()
        fun waypointsChanged()
        fun launchGpxImport()
        fun shareGpx(points: List<RoutePoint>, name: String)
        fun toast(message: String)
        fun alert(message: String)
        /** Turns the chart back to north up. */
        fun northUp()
        /** GPS on (asking for permission), without changing the camera mode if already on. */
        fun startGps()
        /** The newest position the phone remembers, before this app's GPS has a fix. */
        fun lastKnownPosition(): Pair<Double, Double>?
        fun closePanels()
        fun requestNotifications()
        /** Units or the tide changed: redo the crosshair depth readout. */
        fun depthReadoutChanged()
        /** Boat & navigation settings were saved: anything showing units shows them again. */
        fun unitsChanged()
    }

    private val navRepository = NavSettingsRepository(preferences)
    var settings: NavSettings = navRepository.load()
        private set
    val tides = TideFeature(activity, panels, scope, preferences, gson, object : TideFeature.Callbacks {
        override fun mapCentre() = callbacks.mapCentre()
        override fun lastKnownPosition() = callbacks.lastKnownPosition()
        override fun startGps() = callbacks.startGps()
        override fun isNavigating() = navigation.isNavigating
        override fun units() = settings.units
        override fun tidesChanged() {
            scheduleRouteDepthCheck()
            callbacks.depthReadoutChanged()
        }
        override fun closePanels() = callbacks.closePanels()
        override fun toast(message: String) = callbacks.toast(message)
    })

    val anchor = AnchorFeature(activity, panels, mapHolder, settings = { settings }, callbacks = object : AnchorFeature.Callbacks {
        override fun mapCentre() = callbacks.mapCentre()
        override fun startGps() = callbacks.startGps()
        override fun requestNotifications() = callbacks.requestNotifications()
        override fun isNavigating() = navigation.isNavigating
        override fun toast(message: String) = callbacks.toast(message)
    })

    val tracks = TrackFeature(activity, panels, mapHolder, scope, settings = { settings }, callbacks = object : TrackFeature.Callbacks {
        override fun startGps() = callbacks.startGps()
        override fun requestNotifications() = callbacks.requestNotifications()
        override fun isNavigating() = navigation.isNavigating
        override fun fit(points: List<RoutePoint>) = fitRoute(points)
        override fun toast(message: String) = callbacks.toast(message)
        override fun closePanels() = callbacks.closePanels()
    })

    /** The anchor and recording chips, stacked under the top bar on the left. */
    private val statusChips = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    val routes = RouteController(
        mapHolder,
        RouteRepository(preferences, gson),
        settings = { settings },
        variation = ::variation
    )
    val library = RouteLibrary(preferences, gson)
    private val routeDialogs = RouteDialogs(activity, panels)
    private val navHud: NavHud = NavHud(
        activity,
        onPrevious = { navigation.previous() },
        onSkip = { navigation.skip() },
        onMute = {
            navigation.toggleMute().also { muted ->
                callbacks.toast(if (muted) "Alarms muted" else "Alarms on")
            }
        },
        onStop = { confirmStop() }
    )
    private val navigation: NavigationController = NavigationController(
        activity = activity,
        mapHolder = mapHolder,
        hud = navHud,
        scope = scope,
        preferences = preferences,
        gson = gson,
        settings = { settings },
        tideNow = { lat, lng -> tides.tideNear(lat, lng) },
        tideShown = { tides.tideShown() },
        depthAt = { lat, lng -> withContext(Dispatchers.IO) { runCatching { callbacks.depthSampler()(lat, lng) }.getOrNull() } },
        variation = ::variation,
        onMessage = callbacks::toast
    )

    private var routeDepthJob: Job? = null
    private var routeDepthNote: String? = null

    val isNavigating: Boolean get() = navigation.isNavigating

    /** Dims the screen red for night vision; lets touches through. */
    private val nightFilter = View(activity).apply {
        setBackgroundColor(NIGHT_FILTER_COLOUR)
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    // Setup ----------------------------------------------------------------

    private var compassTop = -1

    /** What sits along the top of the chart: the top bar, the tide chip and the navigation display. */
    val topViews: List<View> get() = listOf(views.topBar, tides.chipView, navHud.view)

    /** More boxes under those (the sea conditions values) for the compass to keep below. */
    var extraTopViews: List<View> = emptyList()

    /**
     * MapLibre's compass (it shows when the chart is turned, and taps back to
     * north) sits at the top right by default, under the top bar where it
     * can't be seen or tapped. Keep it just below the lowest of the top bar,
     * the tide chip and the navigation display.
     */
    fun placeCompass() {
        val map = mapHolder.map ?: return
        val shown = (topViews + extraTopViews).filter { it.visibility == View.VISIBLE }
        val top = (shown.maxOfOrNull { it.bottom } ?: 0) + activity.dp(8)
        if (top == compassTop) return
        compassTop = top
        map.uiSettings.compassGravity = Gravity.TOP or Gravity.END
        map.uiSettings.setCompassMargins(0, top, activity.dp(12), 0)
    }

    fun bind() {
        val root = views.mapView.parent as ViewGroup
        root.addView(navHud.view, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            marginStart = activity.dp(8)
            marginEnd = activity.dp(8)
        })
        (activity.window.decorView as ViewGroup).addView(
            nightFilter,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        applyNightFilter()
        tides.bind(root, views.topBar)
        root.addView(statusChips, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = activity.dp(10)
        })
        anchor.bind(statusChips)
        tracks.bind(statusChips)
        // The navigation display and the chips sit just below the top bar, whatever height the status bar gives it.
        views.topBar.addOnLayoutChangeListener { bar, _, _, _, _, _, _, _, _ ->
            val below = bar.bottom + activity.dp(6)
            (navHud.view.layoutParams as ViewGroup.MarginLayoutParams).let { if (it.topMargin != below) { it.topMargin = below; navHud.view.requestLayout() } }
            (statusChips.layoutParams as ViewGroup.MarginLayoutParams).let { if (it.topMargin != bar.bottom) { it.topMargin = bar.bottom; statusChips.requestLayout() } }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCompass() }

        views.btnRouteUndo.setOnClickListener { routes.undo(); refreshRoute() }
        views.btnRouteClear.setOnClickListener { routes.clear(); refreshRoute() }
        views.btnRouteGpx.setOnClickListener { callbacks.shareGpx(routes.routePoints, routes.savedName ?: "Route") }
        views.btnRouteSave.setOnClickListener { saveCurrentRoute() }
        views.btnRouteLibrary.setOnClickListener { showRoutes() }
        views.btnRouteLegs.setOnClickListener { showLegs() }
        views.btnRouteReverse.setOnClickListener {
            if (routes.routePoints.size < 2) return@setOnClickListener
            routes.reverse()
            refreshRoute()
            callbacks.toast("Route reversed")
        }
        views.btnRouteGo.setOnClickListener { startNavigation(routes.routePoints, routes.savedName) }

        views.btnSpeedSourceGps.setOnClickListener { updateSettings(settings.copy(speedSource = PlanSpeedSource.GPS)) }
        views.btnSpeedSourceCustom.setOnClickListener { updateSettings(settings.copy(speedSource = PlanSpeedSource.CRUISE)) }
        showCruiseSpeed()
        views.customSpeedInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (!views.customSpeedInput.hasFocus()) return
                val shown = value?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: return
                if (shown <= 0 || !shown.isFinite()) return
                val knots = shown / settings.units.speed.perKnot
                if (knots > NavSettings.MAX_CRUISE_KN) return
                settings = settings.copy(cruiseSpeedKn = knots)
                navRepository.save(settings)
                navigation.settingsChanged()
                showRouteSummary()
            }
        })
        updateSpeedButtons()

        navigation.pendingResume()?.let { (name, resume) ->
            panels.confirm(
                title = "Resume navigation?",
                message = "You were navigating $name.",
                confirmLabel = "Resume",
                destructive = false
            ) {
                resume()
                callbacks.startGpsFollow()
                tides.refreshChip()
                showRouteSummary()
            }
        }
    }

    fun onResume() {
        tides.onResume()
        anchor.onResume()
        tracks.onResume()
    }

    fun onPause() {
        tides.onPause()
        anchor.onPause()
        tracks.onPause()
    }

    fun onDestroy() {
        tides.onDestroy()
        anchor.release()
        tracks.release()
        navigation.release()
    }

    // Map / GPS hooks -----------------------------------------------------------

    fun onLocation(location: Location) {
        routes.updateGpsSpeedKnots(if (location.hasSpeed()) location.speed * MS_TO_KNOTS else null)
        navigation.onLocation(location)
        if (!routes.routePoints.isEmpty()) showRouteSummary()
        tides.onLocation(location)
        anchor.onLocation(location)
        tracks.onLocation(location)
    }

    fun onStyleInstalled() {
        routes.refresh()
        navigation.refreshMap()
        anchor.refreshMap()
        tracks.refreshMap()
        showRouteSummary()
        // The map centre is known from here on, so tides can be fetched for it.
        tides.onMapReady()
    }

    fun onLayersChanged() = scheduleRouteDepthCheck()

    /** Point added from a map tap (at the crosshair) or a tapped waypoint. */
    fun lastRoutePoint(): RoutePoint? = routes.routePoints.lastOrNull()

    fun addRoutePoint(lat: Double, lng: Double, name: String? = null) {
        routes.add(lat, lng, name)
        refreshRoute()
    }

    fun refreshRoute() {
        routes.refresh()
        showRouteSummary()
        scheduleRouteDepthCheck()
    }

    fun showRouteSummary() {
        // While navigating the display at the top has it all; keep the chart clear
        // unless the route is being edited.
        if (navigation.isNavigating && !callbacks.routeToolActive()) {
            hud.showRouteSummary(null)
            return
        }
        val summary = routes.summary(callbacks.routeToolActive())
        val note = routeDepthNote
        hud.showRouteSummary(if (summary != null && note != null) "$summary\n$note" else summary)
    }

    /**
     * The crosshair depth readout: the survey depth as charted, in the chosen
     * unit. No tide is added; the tide chip shows the tide now.
     */
    fun formatDepth(surveyMetres: Double): String {
        val format = NavFormat(settings.units)
        return if (surveyMetres > 0) "+" + format.depth(surveyMetres) + " dries" else format.depth(-surveyMetres)
    }

    // Navigation -------------------------------------------------------------------

    fun startNavigation(points: List<RoutePoint>, name: String?) {
        if (points.isEmpty()) {
            callbacks.toast("Plan or load a route first")
            return
        }
        callbacks.onNavigationStarted()
        navigation.start(points, name)
        navHud.setMuted(false)
        callbacks.startGpsFollow()
        tides.refreshChip()
        anchor.navigationChanged()
        tracks.navigationChanged()
        showRouteSummary()
    }

    private fun confirmStop() {
        panels.confirm("Stop navigating?", null, confirmLabel = "Stop") {
            navigation.stop()
            tides.refreshChip()
            anchor.navigationChanged()
            tracks.navigationChanged()
            showRouteSummary()
        }
    }

    // Dialogs ------------------------------------------------------------------------

    fun showNavSettings() {
        NavSettingsDialog(activity, panels).show(settings) { updated -> updateSettings(updated) }
    }

    fun showRoutes() {
        routeDialogs.showLibrary(library, settings, object : RouteDialogs.LibraryActions {
            override fun load(route: SavedRoute) {
                routes.load(route)
                refreshRoute()
                fitRoute(route.points)
            }

            override fun navigate(route: SavedRoute) {
                routes.load(route)
                refreshRoute()
                startNavigation(route.points, route.name)
            }

            override fun exportGpx(route: SavedRoute) = callbacks.shareGpx(route.points, route.name)
            override fun importGpx() = callbacks.launchGpxImport()
            override fun deleted(route: SavedRoute) {
                routes.forgetSaved(route.id)
                showRouteSummary()
            }

            override fun saveCurrent(): Unit? {
                if (routes.routePoints.size < 2) {
                    callbacks.toast("Plan a route with at least two points first")
                    return null
                }
                saveCurrentRoute()
                return Unit
            }
        })
    }

    fun saveCurrentRoute() {
        val points = routes.routePoints
        if (points.size < 2) {
            callbacks.toast("Add at least two points first")
            return
        }
        val existing = routes.savedId?.let { library.byId(it) }
        val suggested = existing?.name ?: library.freeName("Route ${library.all.size + 1}")
        routeDialogs.promptName(if (existing != null) "Save route" else "Save new route", suggested, library, existing?.id) { name ->
            val saved = library.save(existing?.id, name, points)
            routes.markSaved(saved)
            showRouteSummary()
            callbacks.toast("Saved $name")
        }
    }

    fun showLegs() {
        val points = routes.routePoints
        if (points.size < 2) {
            callbacks.toast("Add at least two points first")
            return
        }
        val (speed, _) = routes.planSpeed()
        val plan = RoutePlan(points, speed, settings.fuelBurnLph)
        val sampler = callbacks.depthSampler()
        scope.launch {
            val depths = withContext(Dispatchers.Default) {
                plan.legs.map { leg -> RouteDepthCheck.compute(listOf(leg.from, leg.to)) { lat, lng -> runCatching { sampler(lat, lng) }.getOrNull() } }
            }
            val anySurvey = depths.any { it != null }
            routeDialogs.showLegs(
                title = routes.savedName ?: "Route legs",
                plan = plan,
                speedKn = speed,
                settings = settings,
                variation = ::variation,
                departureMillis = System.currentTimeMillis(),
                legDepths = if (anySurvey) depths else null,
                tideM = tides.tideNear(points.first().lat, points.first().lng)
            )
        }
    }

    fun showTides() = tides.show()

    fun showAnchor() = anchor.show()

    fun showTracks() = tracks.show()

    // GPX import ---------------------------------------------------------------------

    fun importGpx(uri: Uri) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { GpxImporter.parse(it) }
                        ?: throw java.io.IOException("Couldn't open the file")
                }
            }.getOrElse { error ->
                DiagnosticLog.logError(TAG, "GPX import failed", error)
                callbacks.alert(error.message ?: "Couldn't read that GPX file")
                return@launch
            }
            var added = 0
            result.waypoints.forEach { w ->
                val base = w.name?.takeIf { it.isNotBlank() } ?: waypointStore.nextDefaultName()
                val name = uniqueWaypointName(base)
                waypointStore.add(name = name, lat = w.lat, lng = w.lng, color = Waypoint.DEFAULT_COLOR, description = w.description)
                added++
            }
            if (added > 0) callbacks.waypointsChanged()
            val savedRoutes = result.routes.map { r -> library.save(null, library.freeName(r.name ?: "Imported route"), r.points) }
            val savedTracks = withContext(Dispatchers.IO) { tracks.import(result.tracks.map { it.name to it.points }) }
            val message = buildString {
                fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"
                append("Imported ${count(added, "waypoint")}")
                append(if (savedTracks > 0) ", ${count(savedRoutes.size, "route")} and ${count(savedTracks, "track")}." else " and ${count(savedRoutes.size, "route")}.")
                if (savedRoutes.isNotEmpty()) append(" Routes are in Saved routes.")
                if (savedTracks > 0) append(" Tracks are in Settings → Tracks.")
            }
            callbacks.alert(message)
            // Shown straight away, unless that would throw away an unsaved route being planned.
            val unsavedWork = routes.routePoints.isNotEmpty() && routes.savedId == null
            if (savedRoutes.size == 1 && !unsavedWork) {
                routes.load(savedRoutes.first())
                refreshRoute()
                fitRoute(savedRoutes.first().points)
            }
        }
    }

    private fun uniqueWaypointName(base: String): String {
        if (!waypointStore.isNameTaken(base)) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { !waypointStore.isNameTaken(it) }
    }

    // Helpers ------------------------------------------------------------------------

    private fun applyNightFilter() {
        nightFilter.visibility = if (settings.nightFilter) View.VISIBLE else View.GONE
    }

    /** The course to turn the chart to (course-up), or null to leave it. */
    fun chartBearing(location: Location): Float? =
        if (settings.courseUp && location.hasBearing() && location.hasSpeed() && location.speed > COURSE_UP_MIN_SPEED_MS) location.bearing else null

    private fun updateSettings(updated: NavSettings) {
        val wasCourseUp = settings.courseUp
        settings = updated
        applyNightFilter()
        if (wasCourseUp && !updated.courseUp) callbacks.northUp()
        navRepository.save(updated)
        navigation.settingsChanged()
        showCruiseSpeed()
        updateSpeedButtons()
        refreshRoute()
        tides.refreshChip()
        callbacks.depthReadoutChanged()
        callbacks.unitsChanged()
    }

    private fun showCruiseSpeed() {
        if (views.customSpeedInput.hasFocus()) return
        views.customSpeedInput.setText(String.format(Locale.US, "%.1f", settings.cruiseSpeedKn * settings.units.speed.perKnot))
        views.customSpeedInput.hint = settings.units.speed.label
    }

    private fun updateSpeedButtons() {
        val gpsSelected = settings.speedSource == PlanSpeedSource.GPS
        views.btnSpeedSourceGps.setBackgroundResource(if (gpsSelected) R.drawable.bg_primary_button else R.drawable.bg_secondary_button)
        views.btnSpeedSourceGps.setTextColor(ContextCompat.getColor(activity, if (gpsSelected) R.color.navy else R.color.text_muted))
        views.btnSpeedSourceCustom.setBackgroundResource(if (gpsSelected) R.drawable.bg_secondary_button else R.drawable.bg_primary_button)
        views.btnSpeedSourceCustom.setTextColor(ContextCompat.getColor(activity, if (gpsSelected) R.color.text_muted else R.color.navy))
    }

    /**
     * Samples the depth layers along the route off the main thread and adds
     * the least depth (with the tide) to the route card, flagged when it is
     * less than the boat's draft + safety margin.
     */
    private fun scheduleRouteDepthCheck() {
        routeDepthJob?.cancel()
        routeDepthNote = null
        val points = routes.routePoints
        if (points.size < 2) return
        val sampler = callbacks.depthSampler()
        routeDepthJob = scope.launch {
            delay(ROUTE_DEPTH_DEBOUNCE_MS)
            val check = withContext(Dispatchers.Default) {
                RouteDepthCheck.compute(points) { lat, lng -> runCatching { sampler(lat, lng) }.getOrNull() }
            }
            routeDepthNote = check?.let { c ->
                val format = NavFormat(settings.units)
                if (c.shallowestMetres > 0) {
                    "⚠ Route crosses drying ground (+${format.depth(c.shallowestMetres)})"
                } else {
                    val tide = tides.tideNear(points.first().lat, points.first().lng)
                    val water = -c.shallowestMetres + (tide ?: 0.0)
                    val covered = if (c.coveredFraction < 0.98) " (${(c.coveredFraction * 100).toInt()}% surveyed)" else ""
                    val warn = if (water < settings.minimumDepthM) "  ⚠ under your ${format.depth(settings.minimumDepthM)}" else ""
                    "Least depth ${format.depth(water)}${if (tide != null) " incl. tide" else ""}$covered$warn"
                }
            }
            showRouteSummary()
        }
    }

    private fun fitRoute(points: List<RoutePoint>) {
        if (points.isEmpty()) return
        val map = mapHolder.map ?: return
        val builder = org.maplibre.android.geometry.LatLngBounds.Builder()
        points.forEach { builder.include(org.maplibre.android.geometry.LatLng(it.lat, it.lng)) }
        runCatching {
            map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(builder.build(), activity.dp(60)))
        }
    }

    /** Magnetic variation (declination), east-positive degrees, from Android's world magnetic model. */
    fun variation(lat: Double, lng: Double): Double =
        GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()

    private companion object {
        const val TAG = "NavigationFeature"
        const val MS_TO_KNOTS = 1.943844
        const val ROUTE_DEPTH_DEBOUNCE_MS = 400L
        const val COURSE_UP_MIN_SPEED_MS = 0.5f
        /** Dark red at about 45% opacity: dims and reddens without hiding the chart. */
        const val NIGHT_FILTER_COLOUR = 0x73400000
    }
}
