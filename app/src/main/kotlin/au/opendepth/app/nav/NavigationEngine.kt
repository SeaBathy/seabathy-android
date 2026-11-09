package au.opendepth.app.nav

import au.opendepth.app.model.RoutePoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** One GPS reading. [sogKn] and [cogTrue] are null when the receiver gives none. */
data class NavFix(
    val lat: Double,
    val lng: Double,
    val sogKn: Double?,
    val cogTrue: Double?,
    val timeMillis: Long
)

/** Everything the navigation display shows, for one fix. */
data class NavState(
    /** Index into the route's points of the point being steered to. */
    val targetIndex: Int,
    val target: RoutePoint,
    val targetLabel: String,
    val pointCount: Int,
    /** Start of the current leg: the previous point, or where navigation began. */
    val origin: RoutePoint,
    val dtwNm: Double,
    val btwTrue: Double,
    val legBearingTrue: Double,
    /** Cross-track error in metres; positive = boat is to starboard of the track line. */
    val xteM: Double,
    /** Turn from the current course to the bearing to the waypoint; positive = to starboard. */
    val steerDegrees: Double?,
    val sogKn: Double?,
    val cogTrue: Double?,
    /** Speed made good towards the waypoint. */
    val vmgKn: Double?,
    val ttgNextHours: Double?,
    val etaNextMillis: Long?,
    val remainingNm: Double,
    val ttgEndHours: Double?,
    val etaEndMillis: Long?,
    val fuelToEndLitres: Double?,
    val offCourse: Boolean,
    /** Set on the update that reached [arrivedLabel]. */
    val arrivedLabel: String?,
    val finished: Boolean
)

/**
 * Follows a route from GPS fixes: works out the values chartplotters show
 * (DTW, BTW, XTE, VMG, TTG, ETA) and moves on to the next point on arrival,
 * either inside the arrival circle or once the boat has passed abeam of it.
 * Pure Kotlin, unit-tested.
 */
class NavigationEngine(
    val points: List<RoutePoint>,
    var settings: NavSettings,
    startTargetIndex: Int? = null
) {
    init {
        require(points.isNotEmpty()) { "A route needs at least one point" }
    }

    var targetIndex: Int = startTargetIndex?.coerceIn(0, points.lastIndex) ?: -1
        private set

    /** Where the boat was when it set off towards the first point. */
    private var startOrigin: RoutePoint? = null
    var finished: Boolean = false
        private set
    private var announcedIndex = -1

    /**
     * Picks the first point to steer to from the boat's position: the second
     * point if already at the first, otherwise the first.
     */
    fun start(fix: NavFix) {
        if (targetIndex >= 0) return
        val atStart = NavGeo.distanceM(fix.lat, fix.lng, points[0].lat, points[0].lng) <= settings.arrivalRadiusM
        targetIndex = if (atStart && points.size > 1) 1 else 0
        if (targetIndex == 0) startOrigin = RoutePoint(fix.lat, fix.lng)
        finished = false
    }

    fun skip() {
        if (targetIndex < points.lastIndex) {
            targetIndex++
            finished = false
        }
    }

    fun previous(fix: NavFix?) {
        if (targetIndex > 0) {
            targetIndex--
            finished = false
            if (targetIndex == 0 && fix != null) startOrigin = RoutePoint(fix.lat, fix.lng)
        }
    }

    fun label(index: Int): String = points[index].name?.takeIf { it.isNotBlank() } ?: "Point ${index + 1}"

    fun update(fix: NavFix): NavState {
        if (targetIndex < 0) start(fix)
        var arrivedLabel: String? = null

        // Announced once per point, even while waiting inside its circle
        // with auto-advance off.
        if (!finished && announcedIndex != targetIndex && hasArrived(fix)) {
            arrivedLabel = label(targetIndex)
            announcedIndex = targetIndex
            when {
                targetIndex == points.lastIndex -> finished = true
                settings.autoAdvance -> targetIndex++
            }
        }
        return state(fix, arrivedLabel)
    }

    private fun origin(): RoutePoint =
        if (targetIndex == 0) startOrigin ?: points[0] else points[targetIndex - 1]

    private fun hasArrived(fix: NavFix): Boolean {
        val target = points[targetIndex]
        val dtwM = NavGeo.distanceM(fix.lat, fix.lng, target.lat, target.lng)
        if (dtwM <= settings.arrivalRadiusM) return true
        // Passed abeam: further along the leg than its length, and near the line.
        val origin = origin()
        val legM = NavGeo.distanceM(origin, target)
        if (legM < 1.0) return false
        val along = NavGeo.alongTrackM(origin, target, fix.lat, fix.lng)
        val xte = abs(NavGeo.crossTrackM(origin, target, fix.lat, fix.lng))
        return along >= legM && xte <= max(settings.arrivalRadiusM * 4, settings.xteLimitM * 2)
    }

    private fun state(fix: NavFix, arrivedLabel: String?): NavState {
        val target = points[targetIndex]
        val origin = origin()
        val dtwNm = NavGeo.distanceM(fix.lat, fix.lng, target.lat, target.lng) / NavFormat.METRES_PER_NM
        val btw = NavGeo.bearing(fix.lat, fix.lng, target.lat, target.lng)
        val legBearing = if (NavGeo.distanceM(origin, target) > 1.0) NavGeo.bearing(origin, target) else btw
        val xte = if (NavGeo.distanceM(origin, target) > 1.0) NavGeo.crossTrackM(origin, target, fix.lat, fix.lng) else 0.0

        val sog = fix.sogKn?.takeIf { it.isFinite() && it >= 0 }
        val cog = fix.cogTrue?.takeIf { it.isFinite() && sog != null && sog >= MIN_COURSE_SPEED_KN }
        val vmg = if (sog != null && cog != null) sog * cos(Math.toRadians(btw - cog)) else null
        val steer = cog?.let { NavFormat.turnDegrees(it, btw) }

        val laterNm = (targetIndex until points.lastIndex).sumOf {
            NavGeo.distanceM(points[it], points[it + 1]) / NavFormat.METRES_PER_NM
        }
        val remainingNm = if (finished) 0.0 else dtwNm + laterNm

        // ETA speed: live GPS speed when chosen and moving, else the planned cruise speed.
        val liveSpeed = sog?.takeIf { settings.speedSource == PlanSpeedSource.GPS && it >= MIN_COURSE_SPEED_KN }
        val nextSpeed = liveSpeed?.let { vmg?.takeIf { v -> v >= MIN_COURSE_SPEED_KN } ?: it } ?: settings.cruiseSpeedKn
        val endSpeed = liveSpeed ?: settings.cruiseSpeedKn
        val ttgNext = if (nextSpeed > 0) dtwNm / nextSpeed else null
        val ttgEnd = if (endSpeed > 0) remainingNm / endSpeed else null
        val fuelToEnd = if (settings.fuelSet && settings.cruiseSpeedKn > 0) {
            remainingNm / settings.cruiseSpeedKn * settings.fuelBurnLph
        } else {
            null
        }

        return NavState(
            targetIndex = targetIndex,
            target = target,
            targetLabel = label(targetIndex),
            pointCount = points.size,
            origin = origin,
            dtwNm = dtwNm,
            btwTrue = btw,
            legBearingTrue = legBearing,
            xteM = xte,
            steerDegrees = steer,
            sogKn = sog,
            cogTrue = cog,
            vmgKn = vmg,
            ttgNextHours = ttgNext,
            etaNextMillis = ttgNext?.let { fix.timeMillis + (it * RoutePlan.HOUR_MS).toLong() },
            remainingNm = remainingNm,
            ttgEndHours = ttgEnd,
            etaEndMillis = ttgEnd?.let { fix.timeMillis + (it * RoutePlan.HOUR_MS).toLong() },
            fuelToEndLitres = fuelToEnd,
            offCourse = !finished && abs(xte) > settings.xteLimitM,
            arrivedLabel = arrivedLabel,
            finished = finished
        )
    }

    private companion object {
        /** Below this, GPS course is noise (drifting at anchor, say). */
        const val MIN_COURSE_SPEED_KN = 0.5
    }
}
