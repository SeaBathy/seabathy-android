package au.opendepth.app.map

import au.opendepth.app.model.RoutePoint
import au.opendepth.app.util.GeoMath
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The shallowest sounding along a planned route, sampled from the loaded
 * depth layers: a quick grounding check for a passage plan. Positions with no
 * depth data are skipped, and [coveredFraction] says how much of the route
 * the answer actually covers.
 */
data class RouteDepthCheck(
    /** Shallowest depth found, metres, negative down (a positive value dries). */
    val shallowestMetres: Double,
    val latitude: Double,
    val longitude: Double,
    val coveredFraction: Double
) {
    /** Short text for the route card, e.g. "shallowest 4.2 m" or "dries +0.6 m". */
    fun label(): String {
        val metres = abs(shallowestMetres)
        val base = if (shallowestMetres > 0) {
            String.format(Locale.US, "dries +%.1f m", metres)
        } else {
            String.format(Locale.US, "shallowest %.1f m", metres)
        }
        return if (coveredFraction < FULL_COVERAGE) {
            String.format(Locale.US, "%s (%d%% of route charted)", base, (coveredFraction * 100).toInt())
        } else {
            base
        }
    }

    companion object {
        private const val FULL_COVERAGE = 0.98

        /** Samples every [spacingMetres] along each leg, capped at [maxSamples] in total. */
        fun compute(
            points: List<RoutePoint>,
            spacingMetres: Double = 25.0,
            maxSamples: Int = 4000,
            depthAt: (lat: Double, lng: Double) -> Double?
        ): RouteDepthCheck? {
            if (points.size < 2) return null
            val legMetres = points.zipWithNext { a, b ->
                GeoMath.distanceNm(a.lat to a.lng, b.lat to b.lng) * GeoMath.KM_PER_NM * 1000.0
            }
            val totalMetres = legMetres.sum()
            if (totalMetres <= 0.0) return null
            val spacing = max(spacingMetres, totalMetres / maxSamples)

            var best: RouteDepthCheck? = null
            var samples = 0
            var covered = 0
            points.zipWithNext().forEachIndexed { index, (a, b) ->
                val steps = max(1, ceil(legMetres[index] / spacing).toInt())
                // The last leg also samples its end point; the others leave it to the next leg.
                val last = if (index == points.size - 2) steps else steps - 1
                for (step in 0..last) {
                    val t = step.toDouble() / steps
                    val lat = a.lat + (b.lat - a.lat) * t
                    val lng = a.lng + (b.lng - a.lng) * t
                    samples++
                    val depth = depthAt(lat, lng)?.takeIf { it.isFinite() } ?: continue
                    covered++
                    if (best == null || depth > best!!.shallowestMetres) {
                        best = RouteDepthCheck(depth, lat, lng, 0.0)
                    }
                }
            }
            return best?.copy(coveredFraction = min(1.0, covered.toDouble() / samples))
        }
    }
}
