package au.opendepth.app.geotiff

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hillshade brightness for a depth grid, baked into the depth colours so
 * reefs, rocks, ledges and channels read as relief rather than as one flat
 * colour band. Computed at whatever resolution the grid is drawn (each tile of
 * a large layer at its own pixel size), so it stays as sharp as the tile.
 *
 * Pure and Android-free, so it is unit-tested on the JVM.
 */
object ReliefShading {

    // Sun from the north-west, 45 degrees up — the cartographic convention.
    private const val LIGHT_X = -0.5
    private const val LIGHT_Y = 0.5
    private const val LIGHT_Z = 0.70710678

    private const val AMBIENT = 0.45
    private const val DIRECT = 0.75
    private const val MAX_BRIGHTNESS = 1.15

    /** The strength setting (0..5) times this is the vertical exaggeration used for shading. */
    const val EXAGGERATION_PER_STRENGTH = 5.0

    /**
     * A brightness multiplier per cell (around 1.0; darker on slopes facing
     * away from the light), or null when [strength] is zero. [cellX]/[cellY]
     * are the cell sizes in metres; [values] are metres, NaN for no data.
     */
    fun shade(values: FloatArray, cols: Int, rows: Int, cellX: Double, cellY: Double, strength: Double): FloatArray? {
        if (strength <= 0.0 || cols < 2 || rows < 2 || cellX <= 0.0 || cellY <= 0.0) return null
        val z = strength * EXAGGERATION_PER_STRENGTH
        val out = FloatArray(cols * rows) { 1f }
        for (r in 0 until rows) {
            val up = max(0, r - 1) * cols
            val down = min(rows - 1, r + 1) * cols
            val here = r * cols
            for (c in 0 until cols) {
                val v = values[here + c]
                if (v.isNaN()) continue
                // Missing neighbours fall back to this cell, so no-data edges stay flat-lit.
                val left = values[here + max(0, c - 1)].orElse(v)
                val right = values[here + min(cols - 1, c + 1)].orElse(v)
                val north = values[up + c].orElse(v)
                val south = values[down + c].orElse(v)
                val dzdx = (right - left) / (2 * cellX) * z
                val dzdy = (north - south) / (2 * cellY) * z
                val lambert = (-dzdx * LIGHT_X - dzdy * LIGHT_Y + LIGHT_Z) / sqrt(dzdx * dzdx + dzdy * dzdy + 1.0)
                out[here + c] = min(MAX_BRIGHTNESS, AMBIENT + DIRECT * max(0.0, lambert)).toFloat()
            }
        }
        return out
    }

    private fun Float.orElse(fallback: Float): Float = if (isNaN()) fallback else this
}
