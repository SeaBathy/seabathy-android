package au.opendepth.app.geotiff

import android.graphics.Bitmap
import au.opendepth.app.model.DepthRampSettings
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * Turns a [DepthGrid] into an ARGB bitmap using the selected palette and depth
 * range, or the slope colours when the ramp says so, with red stripes over
 * water shallower than the depth picked in Boat & navigation. Rendering is
 * pure and cheap enough to re-run while the user drags a range slider, which
 * is what makes live recolouring possible without touching the source file
 * again.
 *
 * A whole layer's picture is laid out for the map as it is written: see
 * [mercatorRows].
 */
object DepthGridRenderer {

    private const val LOOKUP_SIZE = 512
    private const val MIN_SPAN_METRES = 0.1
    private const val METRES_PER_DEGREE = 111_320.0
    private const val TRANSPARENT = 0
    private const val ALPHA_MASK = 0xFF shl 24

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

        /**
         * [block] colours the cells, a row for each row of depths; they are
         * then written into the picture, row [rowMap]`[j]` of them as its
         * row j (as they are when there is no [rowMap]).
         */
        internal fun fill(rowMap: IntArray?, block: (IntArray) -> Unit): Bitmap {
            block(pixels)
            if (rowMap == null) {
                bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
            } else {
                forEachRun(rowMap) { from, to, count ->
                    bitmap.setPixels(pixels, from * cols, cols, 0, to, cols, count)
                }
            }
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
        shade: FloatArray? = shadeFor(grid, settings),
        /** Precomputed [slopesFor] result, likewise; only read when the ramp colours by slope. */
        slopes: FloatArray? = slopesFor(grid, settings),
        /** Water shallower than this gets red stripes over it; 0 for none. See [ShallowWater]. */
        shallowMetres: Double = 0.0,
        /** The stripes' spacing in pixels; a map tile's differs from a whole grid's. */
        stripePeriod: Int = ShallowWater.gridPeriod(grid.cols, grid.rows),
        /**
         * True for a map tile: its rows are already where the map draws them,
         * and its edges are not where the depths end. A whole layer (false)
         * is laid out for the map here, by [grid]'s own north and south.
         */
        mapTile: Boolean = false
    ): Bitmap {
        require(target.matches(grid)) { "Render target does not match the grid size" }
        val rowMap = if (mapTile) null else mercatorRows(grid.rows, grid.bounds.north, grid.bounds.south)
        return target.fill(rowMap) { pixels ->
            paint(grid.values, grid.cols, settings, pixels, shade, slopes, shallowMetres, stripePeriod, rimAtEdges = !mapTile)
            if (rowMap != null) keepSkippedRims(grid.values, grid.cols, pixels, rowMap, shallowMetres)
        }
    }

    /**
     * Which row of a whole layer's depths each row of its picture shows.
     *
     * The depths' rows are evenly spaced in latitude, but the map stretches
     * a picture evenly in Web Mercator between its corners, where a degree
     * of latitude is drawn taller the further it is from the equator. Drawn
     * row for row, the middle of a tall layer sat off its true place (about
     * 100 m on a layer 1 degree tall at 23 S), away from the contour lines
     * and the depth read at a point, which go by true latitude. So row j of
     * the picture, evenly spaced in Mercator, takes the row of depths at the
     * latitude it is drawn at. Everything painted moves together: colours,
     * shading, slope colours and the shallow-water marks.
     *
     * Only the picture is laid out this way. A place on the chart is still
     * turned into a row of depths by its latitude, as [DepthGrid.sampleMetres] does.
     */
    internal fun mercatorRows(rows: Int, north: Double, south: Double): IntArray {
        val top = mercatorY(north)
        val bottom = mercatorY(south)
        val latSpan = north - south
        if (rows < 2 || !(latSpan > 0.0) || !(top > bottom)) return IntArray(rows.coerceAtLeast(0)) { it }
        return IntArray(rows) { j ->
            val lat = latitudeOf(top - (j + 0.5) / rows * (top - bottom))
            floor((north - lat) / latSpan * rows).toInt().coerceIn(0, rows - 1)
        }
    }

    /** As far north or south as the web map goes. */
    private const val MAX_MERCATOR_LAT = 85.0511287798066

    private fun mercatorY(lat: Double): Double =
        ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)) / 2))

    private fun latitudeOf(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - PI / 2)

    /**
     * [rowMap] as runs of rows that follow on from one another: [write] is
     * given the first row of depths of a run, the picture row it goes to, and
     * how many rows there are. Where nothing moved that is one run, the whole
     * picture.
     */
    internal inline fun forEachRun(rowMap: IntArray, write: (from: Int, to: Int, count: Int) -> Unit) {
        var row = 0
        while (row < rowMap.size) {
            var end = row + 1
            while (end < rowMap.size && rowMap[end] == rowMap[end - 1] + 1) end++
            write(rowMap[row], row, end - row)
            row = end
        }
    }

    /**
     * Keeps the red rim of shallow water whole where [rowMap] leaves a row of
     * depths out of the picture (it does here and there, over the half of a
     * tall layer nearer the equator): the rim cells of a row left out are
     * drawn on the next row shown instead, so an outline is never broken and
     * a shoal one row tall is never dropped. [pixels] are as [paint] left
     * them, a row for each row of depths.
     */
    internal fun keepSkippedRims(values: FloatArray, cols: Int, pixels: IntArray, rowMap: IntArray, shallowMetres: Double) {
        if (!(shallowMetres > 0.0) || rowMap.isEmpty() || cols <= 0) return
        val rows = values.size / cols
        var next = 0
        for (j in 0..rowMap.size) {
            // After the last row shown, any rows of depths still to come stand on it.
            val shown = if (j < rowMap.size) rowMap[j] else rows
            val onto = (if (j < rowMap.size) shown else rowMap[rowMap.size - 1]) * cols
            for (skipped in next until shown) {
                for (x in 0 until cols) {
                    if (ShallowWater.isMarked(values[skipped * cols + x], shallowMetres) &&
                        ShallowWater.isRim(values, cols, rows, x, skipped, shallowMetres, rimAtEdges = true)
                    ) {
                        pixels[onto + x] = ShallowWater.RED
                    }
                }
            }
            if (shown >= next) next = shown + 1
        }
    }

    /**
     * The colour of every cell of [values] ([cols] to a row, north row first)
     * into [pixels], as ARGB: by depth, or by slope when the ramp says so and
     * [slopes] are given. Cells with no depth, or outside the range, are left
     * clear. Water shallower than [shallowMetres] gets red stripes over it
     * and a solid red rim (see [ShallowWater.markAt]), whatever the colours
     * and the range; [rimAtEdges] is false for a map tile, whose edges are
     * not where the depths end. Plain arithmetic, so it is unit-tested on
     * the JVM.
     */
    internal fun paint(
        values: FloatArray,
        cols: Int,
        settings: DepthRampSettings,
        pixels: IntArray,
        shade: FloatArray? = null,
        slopes: FloatArray? = null,
        shallowMetres: Double = 0.0,
        stripePeriod: Int = ShallowWater.gridPeriod(cols, values.size / cols.coerceAtLeast(1)),
        rimAtEdges: Boolean = true
    ) {
        val rows = values.size / cols.coerceAtLeast(1)
        val ramp = settings.normalised()
        val bySlope = ramp.colourBySlope && slopes != null
        val lookup = (if (bySlope) DepthPalette.SLOPE else DepthPalette.byId(ramp.paletteId)).toLookupTable(LOOKUP_SIZE)
        val span = ramp.spanMetres.coerceAtLeast(MIN_SPAN_METRES)
        val steepest = ramp.steepestDegrees

        for (index in pixels.indices) {
            val value = values[index]
            if (value.isNaN()) {
                pixels[index] = TRANSPARENT
                continue
            }

            if (shallowMetres > 0.0) {
                val mark = ShallowWater.markAt(values, cols, rows, index, shallowMetres, stripePeriod, rimAtEdges)
                if (mark != ShallowWater.NONE) {
                    pixels[index] = mark
                    continue
                }
            }

            val position = rampPosition(value.toDouble(), ramp, span)
            if (position == null) {
                pixels[index] = TRANSPARENT
                continue
            }

            val ramped = when {
                // Flat is the blue end; anything as steep as the ramp's angle, or steeper, is red.
                bySlope -> min(1.0, slopes!![index] / steepest)
                ramp.invert -> 1.0 - position
                else -> position
            }
            val colour = lookup[
                (ramped * (LOOKUP_SIZE - 1))
                    .toInt()
                    .coerceIn(0, LOOKUP_SIZE - 1)
            ]
            pixels[index] = if (shade == null) colour else shaded(colour, shade[index])
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
    fun shadeFor(grid: DepthGrid, settings: DepthRampSettings): FloatArray? = ReliefShading.shade(
        values = grid.values,
        cols = grid.cols,
        rows = grid.rows,
        cellX = cellWidthMetres(grid),
        cellY = cellHeightMetres(grid),
        strength = settings.hillshadeExaggeration
    )

    /** The seabed's slope at each cell of [grid], in degrees, when the ramp colours by slope; else null. */
    fun slopesFor(grid: DepthGrid, settings: DepthRampSettings): FloatArray? =
        if (settings.colourBySlope) slopesOf(grid) else null

    /** The seabed's slope at each cell of [grid], in degrees; see [SeabedSlope]. */
    fun slopesOf(grid: DepthGrid): FloatArray =
        SeabedSlope.degrees(grid.values, grid.cols, grid.rows, cellWidthMetres(grid), cellHeightMetres(grid))

    /** A cell's size on the ground, at the middle of [grid]. */
    internal fun cellWidthMetres(grid: DepthGrid): Double {
        val midLat = Math.toRadians((grid.bounds.north + grid.bounds.south) / 2.0)
        return (grid.bounds.east - grid.bounds.west) / grid.cols * METRES_PER_DEGREE * cos(midLat)
    }

    internal fun cellHeightMetres(grid: DepthGrid): Double =
        (grid.bounds.north - grid.bounds.south) / grid.rows * METRES_PER_DEGREE

    private fun shaded(colour: Int, brightness: Float): Int =
        (colour and ALPHA_MASK) or
            ((((colour shr 16) and 0xFF) * brightness).toInt().coerceAtMost(255) shl 16) or
            ((((colour shr 8) and 0xFF) * brightness).toInt().coerceAtMost(255) shl 8) or
            ((colour and 0xFF) * brightness).toInt().coerceAtMost(255)

    /**
     * Renders a horizontal preview strip of the ramp for the settings UI:
     * shallow to deep (or reversed), or flat to steep when the ramp colours
     * by slope.
     */
    fun renderLegend(
        settings: DepthRampSettings,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val width = widthPx.coerceAtLeast(2)
        val height = heightPx.coerceAtLeast(1)
        val row = legendRow(settings, width)

        val pixels = IntArray(width * height)
        for (line in 0 until height) {
            row.copyInto(pixels, line * width)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** One row of the legend, [width] colours left to right. */
    internal fun legendRow(settings: DepthRampSettings, width: Int): IntArray {
        val ramp = settings.normalised()
        val palette = if (ramp.colourBySlope) DepthPalette.SLOPE else DepthPalette.byId(ramp.paletteId)
        return IntArray(width) { column ->
            var position = column.toDouble() / (width - 1)
            // Slope colours always run flat to steep: reversing is for depth palettes.
            if (ramp.invert && !ramp.colourBySlope) position = 1.0 - position
            palette.colorAt(position)
        }
    }
}
