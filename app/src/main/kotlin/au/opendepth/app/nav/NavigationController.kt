package au.opendepth.app.nav

import android.app.Activity
import android.content.SharedPreferences
import android.location.Location
import android.view.WindowManager
import au.opendepth.app.map.GeoJson
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MapLayerIds
import au.opendepth.app.model.RoutePoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Runs route navigation: feeds GPS fixes to the [NavigationEngine], checks
 * the water under and ahead of the boat, raises alarms, draws the active
 * leg on the map and keeps the [NavHud] up to date. The active route is
 * saved, so navigation can be resumed after the app is closed.
 */
class NavigationController(
    private val activity: Activity,
    private val mapHolder: MapHolder,
    private val hud: NavHud,
    private val scope: CoroutineScope,
    private val preferences: SharedPreferences,
    private val gson: Gson,
    private val settings: () -> NavSettings,
    /** Tide above the survey datum now, metres, or null when not applied. */
    /** The tide to add at a position, or null (off, unknown, or too far from the tide spot). */
    private val tideNow: (lat: Double, lng: Double) -> Double?,
    /** The tide now as a tide table gives it, for the HUD. */
    private val tideShown: () -> Double?,
    /** Survey depth at a point, metres (negative down), off the main thread. */
    private val depthAt: suspend (lat: Double, lng: Double) -> Double?,
    private val variation: (lat: Double, lng: Double) -> Double,
    private val onMessage: (String) -> Unit
) {
    private val alarms = NavAlarms(activity)
    private var engine: NavigationEngine? = null
    private var routeName: String? = null
    private var lastFix: NavFix? = null
    private var depthJob: Job? = null
    private var depthNowM: Double? = null
    private var depthAheadM: Double? = null
    private var aheadDistanceM: Double? = null

    val isNavigating: Boolean get() = engine != null

    fun start(points: List<RoutePoint>, name: String?) {
        if (points.isEmpty()) return
        engine = NavigationEngine(points, settings())
        routeName = name
        alarms.muted = false
        depthNowM = null
        depthAheadM = null
        applyScreenOn()
        save()
        lastFix?.let { onFix(it) } ?: run {
            hud.show(placeholderState(), extras(null), settings())
            onMessage("Waiting for GPS…")
        }
    }

    fun stop() {
        engine = null
        routeName = null
        depthJob?.cancel()
        NavAlarm.entries.forEach { alarms.clear(it) }
        hud.hide()
        clearMap()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferences.edit().remove(K_POINTS).remove(K_TARGET).remove(K_NAME).apply()
    }

    fun skip() {
        engine?.skip()
        alarms.clear(NavAlarm.OFF_COURSE)
        save()
        lastFix?.let { onFix(it) }
    }

    fun previous() {
        engine?.previous(lastFix)
        alarms.clear(NavAlarm.OFF_COURSE)
        save()
        lastFix?.let { onFix(it) }
    }

    val muted: Boolean get() = alarms.muted

    /** Turns alarm sound and vibration off or on; returns whether they are now off. */
    fun toggleMute(): Boolean {
        alarms.muted = !alarms.muted
        return alarms.muted
    }

    /** New settings (units, limits, alarms) take effect at once. */
    fun settingsChanged() {
        engine?.settings = settings()
        applyScreenOn()
        lastFix?.let { onFix(it) }
    }

    fun onLocation(location: Location) {
        val fix = NavFix(
            lat = location.latitude,
            lng = location.longitude,
            sogKn = if (location.hasSpeed()) location.speed * MS_TO_KNOTS else null,
            cogTrue = if (location.hasBearing()) location.bearing.toDouble() else null,
            timeMillis = if (location.time > 0) location.time else System.currentTimeMillis()
        )
        lastFix = fix
        if (engine != null) onFix(fix)
    }

    /** Redraws the map lines after a style rebuild. */
    fun refreshMap() {
        val fix = lastFix
        val engine = engine
        if (fix != null && engine != null) drawMap(fix, engine.update(fix))
    }

    /** Offers to carry on with a route that was being followed when the app closed. */
    fun pendingResume(): Pair<String, () -> Unit>? {
        val raw = preferences.getString(K_POINTS, null) ?: return null
        val points = runCatching {
            gson.fromJson<List<RoutePoint>>(raw, object : TypeToken<List<RoutePoint>>() {}.type)
        }.getOrNull().orEmpty()
        if (points.isEmpty()) {
            preferences.edit().remove(K_POINTS).apply()
            return null
        }
        val name = preferences.getString(K_NAME, null)
        val target = preferences.getInt(K_TARGET, -1)
        val label = name ?: "the route"
        return label to {
            engine = NavigationEngine(points, settings(), target.takeIf { it >= 0 })
            routeName = name
            applyScreenOn()
            lastFix?.let { onFix(it) } ?: hud.show(placeholderState(), extras(null), settings())
        }
    }

    fun release() {
        alarms.release()
    }

    private fun onFix(fix: NavFix) {
        val engine = engine ?: return
        val before = engine.targetIndex
        val state = engine.update(fix)
        if (engine.targetIndex != before) save()
        checkDepth(fix)
        render(fix, state)
    }

    private fun render(fix: NavFix, state: NavState) {
        val s = settings()
        alarms.soundOn = s.alarmSound
        alarms.vibrateOn = s.alarmVibrate

        state.arrivedLabel?.let { label ->
            if (s.arrivalAlarm) alarms.trigger(if (state.finished) NavAlarm.FINISHED else NavAlarm.ARRIVAL)
            onMessage(if (state.finished) "Arrived: $label (end of route)" else "Arrived: $label")
        }
        if (state.offCourse && s.offCourseAlarm) alarms.trigger(NavAlarm.OFF_COURSE) else alarms.clear(NavAlarm.OFF_COURSE)

        val minDepth = s.minimumDepthM
        val shallowNow = depthNowM?.let { it < minDepth } == true
        val shallowAhead = depthAheadM?.let { it < minDepth } == true
        if ((shallowNow || shallowAhead) && s.shallowAlarm) alarms.trigger(NavAlarm.SHALLOW) else alarms.clear(NavAlarm.SHALLOW)

        val format = NavFormat(s.units)
        val alarmText = when {
            shallowNow -> "SHALLOW  ${format.depth(depthNowM)} (need ${format.depth(minDepth)})"
            shallowAhead -> "SHALLOW AHEAD  ${format.depth(depthAheadM)} in ${format.offset(aheadDistanceM ?: 0.0)}"
            state.offCourse -> "OFF COURSE  ${format.offset(state.xteM)} ${if (state.xteM > 0) "right" else "left"} of the line"
            else -> null
        }
        hud.show(state, extras(alarmText, fix), s)
        drawMap(fix, state)
    }

    /** Samples the survey under the boat and along its course for the next minute or so. */
    private fun checkDepth(fix: NavFix) {
        if (depthJob?.isActive == true) return
        depthJob = scope.launch {
            val tide = tideNow(fix.lat, fix.lng) ?: 0.0
            fun water(surveyMetres: Double?): Double? = surveyMetres?.let { -it + tide }
            depthNowM = water(depthAt(fix.lat, fix.lng))
            val cog = fix.cogTrue
            val sog = fix.sogKn
            if (cog != null && sog != null && sog > 0.5) {
                val lookM = (sog / MS_TO_KNOTS * LOOKAHEAD_S).coerceIn(MIN_LOOKAHEAD_M, MAX_LOOKAHEAD_M)
                var shallowest: Double? = null
                for (step in 1..LOOKAHEAD_SAMPLES) {
                    val d = lookM * step / LOOKAHEAD_SAMPLES
                    val (lat, lng) = offset(fix.lat, fix.lng, cog, d)
                    val w = water(depthAt(lat, lng)) ?: continue
                    shallowest = shallowest?.let { min(it, w) } ?: w
                }
                depthAheadM = shallowest
                aheadDistanceM = lookM
            } else {
                depthAheadM = null
                aheadDistanceM = null
            }
            // Show the new depths (and raise a shallow alarm) without waiting for the next fix.
            val engine = engine ?: return@launch
            lastFix?.let { render(it, engine.update(it)) }
        }
    }

    private fun extras(alarmText: String?, fix: NavFix? = lastFix): NavExtras = NavExtras(
        depthNowM = depthNowM,
        depthAheadM = depthAheadM,
        tideM = tideShown(),
        variation = fix?.let { variation(it.lat, it.lng) } ?: 0.0,
        alarmText = alarmText
    )

    private fun drawMap(fix: NavFix, state: NavState) {
        val features = mutableListOf(
            GeoJson.lineString(listOf(state.origin, state.target), """{"kind":"leg"}"""),
            GeoJson.lineString(listOf(RoutePoint(fix.lat, fix.lng), state.target), """{"kind":"toTarget"}"""),
            GeoJson.point(state.target.lat, state.target.lng)
        )
        mapHolder.style?.getSourceAs<GeoJsonSource>(MapLayerIds.NAV_SOURCE)
            ?.setGeoJson(GeoJson.featureCollection(features))
    }

    private fun clearMap() {
        mapHolder.style?.getSourceAs<GeoJsonSource>(MapLayerIds.NAV_SOURCE)?.setGeoJson(MapLayerIds.EMPTY_GEOJSON)
    }

    private fun applyScreenOn() {
        if (engine != null && settings().keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun save() {
        val engine = engine ?: return
        preferences.edit()
            .putString(K_POINTS, gson.toJson(engine.points))
            .putInt(K_TARGET, engine.targetIndex)
            .putString(K_NAME, routeName)
            .apply()
    }

    /** Shown before the first fix arrives. */
    private fun placeholderState(): NavState {
        val engine = engine!!
        val target = engine.points[max(0, engine.targetIndex)]
        return NavState(
            targetIndex = max(0, engine.targetIndex), target = target, targetLabel = engine.label(max(0, engine.targetIndex)),
            pointCount = engine.points.size, origin = target, dtwNm = Double.NaN, btwTrue = Double.NaN,
            legBearingTrue = Double.NaN, xteM = 0.0, steerDegrees = null, sogKn = null, cogTrue = null, vmgKn = null,
            ttgNextHours = null, etaNextMillis = null, remainingNm = Double.NaN, ttgEndHours = null, etaEndMillis = null,
            fuelToEndLitres = null, offCourse = false, arrivedLabel = null, finished = false
        )
    }

    private companion object {
        const val MS_TO_KNOTS = 1.943844
        /** How far ahead to look for shallow water: this much time at the current speed. */
        const val LOOKAHEAD_S = 60.0
        const val MIN_LOOKAHEAD_M = 50.0
        const val MAX_LOOKAHEAD_M = 600.0
        const val LOOKAHEAD_SAMPLES = 6
        const val K_POINTS = "nav_active_points"
        const val K_TARGET = "nav_active_target"
        const val K_NAME = "nav_active_name"
        private const val EARTH_RADIUS_M = 6_371_008.8

        /** The point [distanceM] from lat/lng on [bearing] (true degrees). */
        fun offset(lat: Double, lng: Double, bearing: Double, distanceM: Double): Pair<Double, Double> {
            val d = distanceM / EARTH_RADIUS_M
            val b = Math.toRadians(bearing)
            val p1 = Math.toRadians(lat)
            val l1 = Math.toRadians(lng)
            val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
            val l2 = l1 + Math.atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
            return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
        }
    }
}
