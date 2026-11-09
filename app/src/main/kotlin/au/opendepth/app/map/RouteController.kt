package au.opendepth.app.map

import au.opendepth.app.data.RouteRepository
import au.opendepth.app.data.SavedRoute
import au.opendepth.app.model.RoutePoint
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavSettings
import au.opendepth.app.nav.PlanSpeedSource
import au.opendepth.app.nav.RoutePlan
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The route being planned on the chart: its points, which saved route (if
 * any) it is, and the plan summary shown in the route card. Speeds, fuel and
 * units come from [settings] (the boat profile).
 */
class RouteController(
    private val mapHolder: MapHolder,
    /** Where the route is saved as it changes; null keeps it in memory only (tests). */
    private val repository: RouteRepository? = null,
    private val settings: () -> NavSettings = { NavSettings() },
    /** Magnetic variation (east-positive degrees) at a position, for magnetic bearings. */
    private val variation: (lat: Double, lng: Double) -> Double = { _, _ -> 0.0 }
) {

    private val points = repository?.load().orEmpty().toMutableList()
    private var gpsSpeedKnots: Double? = null

    /** The saved route this plan was loaded from or saved as, if any. */
    var savedId: String? = repository?.loadSavedId()
        private set
    var savedName: String? = repository?.loadSavedName()
        private set

    val routePoints: List<RoutePoint> get() = points.toList()

    /**
     * [knots] is null whenever there is no live speed to report right now
     * (no GPS fix yet, or the reading fed in has no speed component).
     */
    fun updateGpsSpeedKnots(knots: Double?) {
        gpsSpeedKnots = knots?.takeIf { it > 0.0 }
    }

    fun add(lat: Double, lng: Double, name: String? = null) {
        // A double tap, or a tap on the last point again, would add a leg of no length.
        points.lastOrNull()?.let { last ->
            if (au.opendepth.app.nav.NavGeo.distanceM(last.lat, last.lng, lat, lng) < SAME_POINT_M) return
        }
        points += RoutePoint(lat, lng, name)
        changed()
    }

    fun undo() {
        points.removeLastOrNull()
        changed()
    }

    fun clear() {
        points.clear()
        savedId = null
        savedName = null
        changed()
    }

    /** Swaps start and end, e.g. for the trip home. */
    fun reverse() {
        points.reverse()
        changed()
    }

    fun load(route: SavedRoute) {
        points.clear()
        points += route.points
        savedId = route.id
        savedName = route.name
        changed()
    }

    fun markSaved(route: SavedRoute) {
        savedId = route.id
        savedName = route.name
        changed()
    }

    /** Forgets the saved-route link when that route is deleted. */
    fun forgetSaved(id: String) {
        if (savedId == id) {
            savedId = null
            savedName = null
            changed()
        }
    }

    fun refresh() {
        mapHolder.style
            ?.getSourceAs<GeoJsonSource>(MapLayerIds.ROUTE_SOURCE)
            ?.setGeoJson(GeoJson.lineWithVertices(points))
    }

    /** The plan at the current planning speed. */
    fun plan(): RoutePlan = RoutePlan(points, planSpeed().first, settings().fuelBurnLph)

    /** The planning speed and where it came from. */
    fun planSpeed(): Pair<Double, String> {
        val s = settings()
        val gps = gpsSpeedKnots
        return when {
            s.speedSource == PlanSpeedSource.GPS && gps != null -> gps to "GPS"
            s.speedSource == PlanSpeedSource.GPS -> s.cruiseSpeedKn to "cruise, no GPS fix"
            else -> s.cruiseSpeedKn to "cruise"
        }
    }

    /** The readout shown in the route card, or null when the card is idle. */
    fun summary(routeToolActive: Boolean, nowMillis: Long = System.currentTimeMillis()): String? {
        val s = settings()
        val format = NavFormat(s.units)
        val title = savedName ?: "Unsaved route"
        return when {
            points.isEmpty() ->
                if (routeToolActive) "Tap the map to add a point at the centre dot" else null

            points.size < 2 -> "$title — 1 point, add another"

            else -> {
                val plan = plan()
                val (speed, speedLabel) = planSpeed()
                val first = plan.legs.first()
                val legs = plan.legs.size
                val firstVariation = variation(first.from.lat, first.from.lng)
                val eta = plan.totalHours?.let {
                    " · ETA ${format.clock(nowMillis + (it * RoutePlan.HOUR_MS).toLong(), nowMillis)}"
                }.orEmpty()
                val lines = mutableListOf(
                    "$title — $legs ${if (legs == 1) "leg" else "legs"}, ${format.distance(plan.totalNm)}",
                    "${format.duration(plan.totalHours)} at ${format.speed(speed)} ($speedLabel)$eta",
                    "${if (legs > 1) "1st leg" else "Course"} ${format.bearing(first.bearingTrue, firstVariation)}"
                )
                plan.totalFuelLitres?.let { fuel ->
                    val usable = s.usableFuelLitres
                    val check = when {
                        usable <= 0 -> ""
                        fuel > usable -> " · ⚠ more than the usable ${format.fuel(usable)}"
                        else -> " · ${format.fuel(usable - fuel)} spare"
                    }
                    lines += "Fuel ${format.fuel(fuel)}$check"
                }
                lines.joinToString("\n")
            }
        }
    }

    private fun changed() {
        repository?.save(points)
        repository?.saveLink(savedId, savedName)
    }

    private companion object {
        /** Closer than this to the last point is the same point. */
        const val SAME_POINT_M = 5.0
    }
}
