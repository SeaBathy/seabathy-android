package au.opendepth.app.geotiff

import android.graphics.Bitmap
import android.graphics.Color
import au.opendepth.app.model.DepthRampSettings
import kotlin.math.cos

/**
 * Turns a [DepthGrid] into an ARGB bitmap using the selected palette and depth
 * range. Rendering is pure and cheap enough to re-run while the user drags a
 * range slider, which is what makes live recolouring possible without touching
 * the source file again.
 */
object DepthGridRenderer {

    private const val LOOKUP_SIZE = 512
    private const val MIN_SPAN_METRES = 0.1
    private const val METRES_PER_DEGREE = 111_320.0

    /**
     * Scratch buffers for one depth layer.
     *
     * A full-size grid is several megabytes of pixels, so re-allocating per
     * slider step would churn the heap. The target keeps one mutable bitmap and
     * one pixel array per layer and rewrites them in place.
     */
    class Target(private val cols: Int, private val rows: Int) {
        private val pixels = IntArray(cols * rows)
        private val bitmap: Bitmap =
            Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)

        fun matches(grid: DepthGrid): Boolean = grid.cols == cols && grid.rows == rows

        internal fun fill(block: (IntArray) -> Unit): Bitmap {
            block(pixels)
            bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
            return bitmap
        }

        fun recycle() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun newTarget(grid: DepthGrid): Target = Target(grid.cols, grid.rows)

    fun render(
        grid: DepthGrid,
        settings: DepthRampSettings,
        target: Target = newTarget(grid),
        /** Precomputed [shadeFor] result, when the caller keeps it between recolours. */
        shade: FloatArray? = shadeFor(grid, settings)
    ): Bitmap {
        require(target.matches(grid)) { "Render target does not match the grid size" }

        val ramp = settings.normalised()
        val lookup = DepthPalette.byId(ramp.paletteId).toLookupTable(LOOKUP_SIZE)
        val span = ramp.spanMetres.coerceAtLeast(MIN_SPAN_METRES)

        return target.fill { pixels ->
            for (index in pixels.indices) {
                val value = grid.values[index]
                if (value.isNaN()) {
                    pixels[index] = Color.TRANSPARENT
                    continue
                }

                val position = rampPosition(value.toDouble(), ramp, span)
                if (position == null) {
                    pixels[index] = Color.TRANSPARENT
                    continue
                }

                val ramped = if (ramp.invert) 1.0 - position else position
                val colour = lookup[
                    (ramped * (LOOKUP_SIZE - 1))
                        .toInt()
                        .coerceIn(0, LOOKUP_SIZE - 1)
                ]
                pixels[index] = if (shade == null) colour else shaded(colour, shade[index])
            }
        }
    }

    /**
     * Where [value] falls on the ramp, 0 (shallow end) to 1 (deep end), or
     * null to leave the cell clear. Depths outside the range are hidden, so
     * picking -5 to -15 m shows just that band — unless the ramp's
     * [DepthRampSettings.showOutside] is on: then they take the nearest end's
     * colour, and only dry land is left clear (when the range is all water),
     * so the chart's coastline shows through.
     */
    fun rampPosition(value: Double, ramp: DepthRampSettings, span: Double = ramp.spanMetres.coerceAtLeast(MIN_SPAN_METRES)): Double? {
        // Depths are negative-down, so the shallow bound is the larger
        // number and positions grow towards the deep end of the ramp.
        val position = (ramp.shallowMetres - value) / span
        if (!ramp.showOutside) return position.takeIf { it in 0.0..1.0 }
        if (value > 0.0 && ramp.shallowMetres <= 0.0) return null
        return position.coerceIn(0.0, 1.0)
    }

    /** Relief shading for [grid] at the ramp's strength (null when off), at the grid's own resolution. */
    fun shadeFor(grid: DepthGrid, settings: DepthRampSettings): FloatArray? {
        val midLat = Math.toRadians((grid.bounds.north + grid.bounds.south) / 2.0)
        return ReliefShading.shade(
            values = grid.values,
            cols = grid.cols,
            rows = grid.rows,
            cellX = (grid.bounds.east - grid.bounds.west) / grid.cols * METRES_PER_DEGREE * cos(midLat),
            cellY = (grid.bounds.north - grid.bounds.south) / grid.rows * METRES_PER_DEGREE,
            strength = settings.hillshadeExaggeration
        )
    }

    private fun shaded(colour: Int, brightness: Float): Int = Color.argb(
        Color.alpha(colour),
        (Color.red(colour) * brightness).toInt().coerceAtMost(255),
        (Color.green(colour) * brightness).toInt().coerceAtMost(255),
        (Color.blue(colour) * brightness).toInt().coerceAtMost(255)
    )

    /** Renders a horizontal preview strip of the ramp for the settings UI. */
    fun renderLegend(
        settings: DepthRampSettings,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val ramp = settings.normalised()
        val palette = DepthPalette.byId(ramp.paletteId)
        val width = widthPx.coerceAtLeast(2)
        val height = heightPx.coerceAtLeast(1)

        val row = IntArray(width) { column ->
            var position = column.toDouble() / (width - 1)
            if (ramp.invert) position = 1.0 - position
            palette.colorAt(position)
        }

        val pixels = IntArray(width * height)
        for (line in 0 until height) {
            row.copyInto(pixels, line * width)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
