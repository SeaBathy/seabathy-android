package au.opendepth.app.nav

import au.opendepth.app.model.RoutePoint

/** One leg of a route plan. Times are hours from departure. */
data class PlanLeg(
    /** 1-based leg number. */
    val number: Int,
    val from: RoutePoint,
    val to: RoutePoint,
    val distanceNm: Double,
    val bearingTrue: Double,
    val cumulativeNm: Double,
    val hours: Double?,
    val cumulativeHours: Double?,
    val fuelLitres: Double?
)

/**
 * A route worked out leg by leg at [speedKn], with fuel at [burnLph] when
 * set. Pure maths, so it is unit-tested directly.
 */
class RoutePlan(val points: List<RoutePoint>, speedKn: Double, burnLph: Double) {

    val legs: List<PlanLeg>
    val totalNm: Double
    val totalHours: Double?
    val totalFuelLitres: Double?

    init {
        val speed = speedKn.takeIf { it > 0 }
        var cumulative = 0.0
        var cumulativeHours: Double? = if (speed != null) 0.0 else null
        legs = points.zipWithNext().mapIndexed { index, (from, to) ->
            val nm = NavGeo.distanceM(from, to) / NavFormat.METRES_PER_NM
            cumulative += nm
            val hours = speed?.let { nm / it }
            cumulativeHours = if (hours != null && cumulativeHours != null) cumulativeHours!! + hours else null
            PlanLeg(
                number = index + 1,
                from = from,
                to = to,
                distanceNm = nm,
                bearingTrue = NavGeo.bearing(from, to),
                cumulativeNm = cumulative,
                hours = hours,
                cumulativeHours = cumulativeHours,
                fuelLitres = if (hours != null && burnLph > 0) hours * burnLph else null
            )
        }
        totalNm = cumulative
        totalHours = speed?.let { cumulative / it }
        totalFuelLitres = if (totalHours != null && burnLph > 0) totalHours * burnLph else null
    }

    /** Arrival time at the end of each leg for a departure at [departureMillis]. */
    fun arrivalMillis(leg: PlanLeg, departureMillis: Long): Long? =
        leg.cumulativeHours?.let { departureMillis + (it * HOUR_MS).toLong() }

    companion object {
        const val HOUR_MS = 3_600_000.0
    }
}
