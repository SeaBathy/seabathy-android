package au.opendepth.app.conditions

/**
 * A flow field sampled once onto a regular latitude-longitude grid, for [FlowLinesView]: the
 * specks read it every frame, so the forecast itself is sampled once per time and layer instead
 * of hundreds of times a frame. Cells with no flow (land, outside the data) stay empty.
 */
class FlowGrid private constructor(
    private val south: Double,
    private val west: Double,
    private val north: Double,
    private val east: Double,
    private val side: Int,
    private val u: FloatArray,
    private val v: FloatArray
) : FlowLinesView.Field {

    override fun at(lat: Double, lng: Double, out: DoubleArray): Boolean {
        if (lat < south || lat > north || lng < west || lng > east) return false
        val fy = (lat - south) / (north - south) * (side - 1)
        val fx = (lng - west) / (east - west) * (side - 1)
        val r = fy.toInt().coerceIn(0, side - 2)
        val c = fx.toInt().coerceIn(0, side - 2)
        val ty = fy - r
        val tx = fx - c
        var sumU = 0.0
        var sumV = 0.0
        var weight = 0.0
        // Bilinear over the corners that have flow, so the coast doesn't shrink the field.
        for (k in 0 until 4) {
            val rr = r + (k shr 1)
            val cc = c + (k and 1)
            val i = rr * side + cc
            val uu = u[i]
            if (uu.isNaN()) continue
            val w = (if (k shr 1 == 1) ty else 1 - ty) * (if (k and 1 == 1) tx else 1 - tx)
            sumU += uu * w
            sumV += v[i] * w
            weight += w
        }
        if (weight < 0.25) return false
        out[0] = sumU / weight
        out[1] = sumV / weight
        return true
    }

    companion object {
        /** Samples [field] at [side] × [side] points over the area. */
        fun build(south: Double, west: Double, north: Double, east: Double, side: Int, field: FlowLinesView.Field): FlowGrid {
            val u = FloatArray(side * side) { Float.NaN }
            val v = FloatArray(side * side) { Float.NaN }
            val out = DoubleArray(2)
            for (r in 0 until side) {
                val lat = south + (north - south) * r / (side - 1)
                for (c in 0 until side) {
                    val lng = west + (east - west) * c / (side - 1)
                    if (field.at(lat, lng, out)) {
                        u[r * side + c] = out[0].toFloat()
                        v[r * side + c] = out[1].toFloat()
                    }
                }
            }
            return FlowGrid(south, west, north, east, side, u, v)
        }
    }
}
