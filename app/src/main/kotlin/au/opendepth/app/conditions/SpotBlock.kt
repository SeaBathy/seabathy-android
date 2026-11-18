package au.opendepth.app.conditions

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the spot's block of sea conditions goes. On the water it's centred on
 * the boat. Inland, a block centred on the boat would be mostly land with a
 * sliver of sea at its edge, so it's centred on the nearest sea instead: the
 * middle of the sea points found on the nearest rings round the boat.
 */
object SpotBlock {
    /** Rings asked about round the boat (the first is the boat itself), [BEARINGS] points each. */
    val RINGS_KM = listOf(0.0, 40.0, 80.0, 130.0, 190.0, 260.0)
    const val BEARINGS = 12

    /** The points to ask about: the boat, then each ring. */
    fun ringPoints(lat: Double, lng: Double): List<Pair<Double, Double>> = buildList {
        add(lat to lng)
        for (km in RINGS_KM.drop(1)) {
            repeat(BEARINGS) { i ->
                val b = Math.toRadians(i * 360.0 / BEARINGS)
                val pLat = (lat + km / 111.32 * cos(b)).coerceIn(-ConditionsArea.MAX_LAT, ConditionsArea.MAX_LAT)
                val pLng = lng + km / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.05)) * sin(b)
                add(pLat to ((pLng + 540.0) % 360.0 - 180.0))
            }
        }
    }

    /** The closest sea found to the boat (from [sea], as for [centre]); null when it's on the water or none is in reach. */
    fun nearestSea(lat: Double, lng: Double, sea: List<Pair<Double, Double>?>): Pair<Double, Double>? {
        if (sea.firstOrNull() != null) return null
        return sea.drop(1).filterNotNull().minByOrNull { au.opendepth.app.nav.NavGeo.distanceM(lat, lng, it.first, it.second) }
    }

    /**
     * The block's middle, from [sea] (for each of [ringPoints], where the model found sea, or
     * null): the boat when it's on the water or there's no sea in reach; otherwise the middle of
     * the sea on the nearest ring that has any and the ring after it. Pure, unit-tested.
     */
    fun centre(lat: Double, lng: Double, sea: List<Pair<Double, Double>?>): Pair<Double, Double> {
        if (sea.firstOrNull() != null) return lat to lng
        val rings = RINGS_KM.drop(1).indices.map { r -> sea.drop(1 + r * BEARINGS).take(BEARINGS).filterNotNull() }
        val first = rings.indexOfFirst { it.isNotEmpty() }
        if (first < 0) return lat to lng
        val found = rings[first] + rings.getOrElse(first + 1) { emptyList() }
        // Averaged as offsets from the boat, so a block near the date line doesn't land on the far side of the world.
        val dLng = found.map { ((it.second - lng + 540.0) % 360.0) - 180.0 }.average()
        return found.map { it.first }.average() to ((lng + dLng + 540.0) % 360.0 - 180.0)
    }
}
