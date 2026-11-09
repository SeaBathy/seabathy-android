package au.opendepth.app.geotiff

import au.opendepth.app.model.GeoBounds
import kotlin.math.floor

/**
 * A north-up, WGS84-aligned grid of depth samples resampled out of an imported
 * GeoTIFF.
 *
 * Values are metres using the negative-down convention, so -18.4 means 18.4
 * metres below the surface. [NO_DATA] marks cells with no coverage.
 */
class DepthGrid(
    val bounds: GeoBounds,
    val cols: Int,
    val rows: Int,
    val values: FloatArray,
    val shallowestMetres: Double,
    val deepestMetres: Double,
    val coveredCells: Int
) {
    init {
        require(cols > 0 && rows > 0) { "Depth grid must have a positive size" }
        require(values.size == cols * rows) { "Depth grid value count mismatch" }
    }

    val hasData: Boolean get() = coveredCells > 0

    /** Nearest-neighbour depth lookup, or null outside coverage. */
    fun sampleMetres(lat: Double, lng: Double): Double? {
        if (!bounds.contains(lat, lng)) return null

        val spanLng = bounds.east - bounds.west
        val spanLat = bounds.north - bounds.south
        if (spanLng <= 0.0 || spanLat <= 0.0) return null

        val col = floor((lng - bounds.west) / spanLng * cols).toInt()
            .coerceIn(0, cols - 1)
        val row = floor((bounds.north - lat) / spanLat * rows).toInt()
            .coerceIn(0, rows - 1)

        val value = values[row * cols + col]
        return if (value.isNaN()) null else value.toDouble()
    }

    companion object {
        val NO_DATA = Float.NaN
    }
}
