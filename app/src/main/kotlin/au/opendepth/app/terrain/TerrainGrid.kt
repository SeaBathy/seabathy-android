package au.opendepth.app.terrain

import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.geotiff.InMemoryDepthGrid
import au.opendepth.app.model.GeoBounds
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A north-up grid of depths (metres, negative down, NaN = no data) for the 3D
 * view, sampled from a layer's [DepthSource] over [bounds]. Cell sizes are in
 * metres so the viewer can build true-scale geometry.
 */
class TerrainGrid(
    val bounds: GeoBounds,
    val cols: Int,
    val rows: Int,
    val values: FloatArray,
    val minMetres: Double,
    val maxMetres: Double
) {
    init {
        require(values.size == cols * rows) { "Terrain grid value count mismatch" }
    }

    private val midLatRadians = Math.toRadians((bounds.north + bounds.south) / 2.0)

    val cellXMetres: Double get() = (bounds.east - bounds.west) / cols * METRES_PER_DEGREE_LAT_EQUATOR * cos(midLatRadians)
    val cellYMetres: Double get() = (bounds.north - bounds.south) / rows * METRES_PER_DEGREE_LAT

    companion object {
        const val METRES_PER_DEGREE_LAT = 110_574.0
        const val METRES_PER_DEGREE_LAT_EQUATOR = 111_320.0
    }
}

object TerrainGridSampler {

    /**
     * Samples [source] over [bounds] at up to [maxDim] cells along the longer
     * side, keeping square-ish cells in metres. Depths outside [range]
     * (deep to shallow, negative down) become no-data. Returns null when
     * nothing in [bounds] has data.
     *
     * An in-memory source is sampled bilinearly from its own grid, so zooming
     * into a small isolated area gives smooth slopes instead of stair-step
     * terraces; other sources fall back to [DepthSource.sampleMetres].
     */
    fun sample(
        source: DepthSource,
        bounds: GeoBounds,
        maxDim: Int,
        range: ClosedFloatingPointRange<Double>? = null
    ): TerrainGrid? {
        val midLat = Math.toRadians((bounds.north + bounds.south) / 2.0)
        val widthMetres = (bounds.east - bounds.west) * TerrainGrid.METRES_PER_DEGREE_LAT_EQUATOR * cos(midLat)
        val heightMetres = (bounds.north - bounds.south) * TerrainGrid.METRES_PER_DEGREE_LAT
        if (widthMetres <= 0 || heightMetres <= 0) return null

        var cols: Int
        var rows: Int
        if (widthMetres >= heightMetres) {
            cols = maxDim
            rows = max(2, (maxDim * heightMetres / widthMetres).roundToInt())
        } else {
            rows = maxDim
            cols = max(2, (maxDim * widthMetres / heightMetres).roundToInt())
        }

        val inMemory = (source as? InMemoryDepthGrid)?.grid
        if (inMemory != null) {
            // No point sampling much finer than the data itself.
            val sourceCols = inMemory.cols * (bounds.east - bounds.west) / (inMemory.bounds.east - inMemory.bounds.west)
            val sourceRows = inMemory.rows * (bounds.north - bounds.south) / (inMemory.bounds.north - inMemory.bounds.south)
            val scale = min(1.0, max(sourceCols * 2 / cols, sourceRows * 2 / rows))
            cols = max(2, (cols * scale).roundToInt())
            rows = max(2, (rows * scale).roundToInt())
        }

        val values = FloatArray(cols * rows)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (r in 0 until rows) {
            val lat = bounds.north - (r + 0.5) / rows * (bounds.north - bounds.south)
            for (c in 0 until cols) {
                val lng = bounds.west + (c + 0.5) / cols * (bounds.east - bounds.west)
                val depth = if (inMemory != null) bilinear(inMemory, lat, lng) else source.sampleMetres(lat, lng)
                val kept = depth?.takeIf { it.isFinite() && (range == null || it in range) }
                values[r * cols + c] = kept?.toFloat() ?: Float.NaN
                if (kept != null) {
                    if (kept < lo) lo = kept
                    if (kept > hi) hi = kept
                }
            }
        }
        if (lo == Double.POSITIVE_INFINITY) return null
        return TerrainGrid(bounds, cols, rows, values, lo, hi)
    }

    /**
     * A terrain grid from depths that were already read for the right area
     * (see GdalTileSource.readArea), with values outside [range] dropped.
     */
    fun fromDepthGrid(grid: au.opendepth.app.geotiff.DepthGrid, range: ClosedFloatingPointRange<Double>? = null): TerrainGrid? {
        val values = grid.values.copyOf()
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in values.indices) {
            val v = values[i]
            if (v.isNaN()) continue
            if (range != null && v.toDouble() !in range) {
                values[i] = Float.NaN
                continue
            }
            if (v < lo) lo = v.toDouble()
            if (v > hi) hi = v.toDouble()
        }
        if (lo == Double.POSITIVE_INFINITY) return null
        return TerrainGrid(grid.bounds, grid.cols, grid.rows, values, lo, hi)
    }

    /**
     * The area of [grid] whose depths fall inside [range], padded a little so
     * the isolated features are not cut off at the edges; null when nothing
     * is in range.
     */
    fun boundsWithin(grid: TerrainGrid, range: ClosedFloatingPointRange<Double>, padFraction: Double = 0.03): GeoBounds? {
        var c0 = Int.MAX_VALUE
        var r0 = Int.MAX_VALUE
        var c1 = -1
        var r1 = -1
        for (r in 0 until grid.rows) {
            for (c in 0 until grid.cols) {
                val v = grid.values[r * grid.cols + c]
                if (!v.isNaN() && v.toDouble() in range) {
                    if (c < c0) c0 = c
                    if (c > c1) c1 = c
                    if (r < r0) r0 = r
                    if (r > r1) r1 = r
                }
            }
        }
        if (c1 < 0) return null
        val pad = max(2, (max(c1 - c0, r1 - r0) * padFraction).roundToInt())
        val b = grid.bounds
        val lngPerCol = (b.east - b.west) / grid.cols
        val latPerRow = (b.north - b.south) / grid.rows
        return GeoBounds(
            west = max(b.west, b.west + (c0 - pad) * lngPerCol),
            south = max(b.south, b.north - (r1 + 1 + pad) * latPerRow),
            east = min(b.east, b.west + (c1 + 1 + pad) * lngPerCol),
            north = min(b.north, b.north - (r0 - pad) * latPerRow)
        )
    }

    /** Bilinear depth from an in-memory grid, falling back to nearest where a neighbour has no data. */
    private fun bilinear(grid: au.opendepth.app.geotiff.DepthGrid, lat: Double, lng: Double): Double? {
        val b = grid.bounds
        if (!b.contains(lat, lng)) return null
        val x = (lng - b.west) / (b.east - b.west) * grid.cols - 0.5
        val y = (b.north - lat) / (b.north - b.south) * grid.rows - 0.5
        val c0 = floor(x).toInt().coerceIn(0, grid.cols - 1)
        val r0 = floor(y).toInt().coerceIn(0, grid.rows - 1)
        val c1 = min(grid.cols - 1, c0 + 1)
        val r1 = min(grid.rows - 1, r0 + 1)
        val fx = (x - c0).coerceIn(0.0, 1.0)
        val fy = (y - r0).coerceIn(0.0, 1.0)
        val v = grid.values
        val a = v[r0 * grid.cols + c0]
        val bb = v[r0 * grid.cols + c1]
        val c = v[r1 * grid.cols + c0]
        val d = v[r1 * grid.cols + c1]
        if (a.isNaN() || bb.isNaN() || c.isNaN() || d.isNaN()) return grid.sampleMetres(lat, lng)
        return (a * (1 - fx) + bb * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}
