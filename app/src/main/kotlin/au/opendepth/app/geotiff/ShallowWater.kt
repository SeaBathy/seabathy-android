package au.opendepth.app.geotiff

import kotlin.math.max

/**
 * Marks shallow water in red: water shallower than a depth picked in Boat &
 * navigation is drawn with red stripes over every depth layer, whatever the
 * layer's colours and range, and a solid red rim round each patch of it, so
 * a shoal too small to catch a stripe is still red. A guide only: the depths
 * come from surveys and take no account of the tide.
 *
 * The marks themselves are pure and Android-free, so they are unit-tested
 * on the JVM.
 */
object ShallowWater {

    /**
     * The depth picked, in metres (2.0 marks water shallower than 2 m), or 0
     * for none. One for the whole app, like the tile size: set at startup and
     * whenever Boat & navigation is saved, read by both ways a depth layer is
     * drawn.
     */
    @Volatile
    var markMetres: Double = 0.0
        set(value) {
            field = if (value.isFinite() && value > 0.0) value else 0.0
        }

    /** The stripes' red, as the web app's (229, 20, 30), and their thin white edge. */
    const val RED: Int = 0xFFE5141E.toInt()
    const val EDGE: Int = 0xFFFFFFFF.toInt()

    /** Between the stripes: the layer's own colour shows. */
    const val NONE: Int = 0

    /** True for water (at or below the surface) shallower than [shallowMetres]; never when that is 0. */
    fun isMarked(depth: Float, shallowMetres: Double): Boolean =
        shallowMetres > 0.0 && depth <= 0f && depth > -shallowMetres

    /**
     * The stripe colour at a pixel, or [NONE] between the stripes. They run on
     * the slant: of every [period] pixels, three eighths are red and one
     * eighth white, so they show on any colour, red included.
     */
    fun stripe(x: Int, y: Int, period: Int): Int {
        val at = (x + y) % period * 8
        return when {
            at < period * 3 -> RED
            at < period * 4 -> EDGE
            else -> NONE
        }
    }

    /**
     * True where the marked cell at column [x], row [y] of [values] ([cols] to
     * a row, [rows] rows) is on the rim of its patch: one of the eight cells
     * round it is not marked (deeper water, dry ground or no depth).
     *
     * [rimAtEdges] says what lies past the grid's own edge. A whole layer ends
     * there, so its edge cells are rim. A map tile carries on into the next
     * tile, whose depths this one does not have: a cell past its edge counts
     * as marked, so no line is drawn just because the tile ends.
     */
    fun isRim(
        values: FloatArray,
        cols: Int,
        rows: Int,
        x: Int,
        y: Int,
        shallowMetres: Double,
        rimAtEdges: Boolean
    ): Boolean {
        if (x > 0 && y > 0 && x < cols - 1 && y < rows - 1) {
            // Away from the edges, which is nearly every cell: the eight round it, no bounds to check.
            val here = y * cols + x
            val up = here - cols
            val down = here + cols
            return !(
                isMarked(values[up - 1], shallowMetres) && isMarked(values[up], shallowMetres) &&
                    isMarked(values[up + 1], shallowMetres) && isMarked(values[here - 1], shallowMetres) &&
                    isMarked(values[here + 1], shallowMetres) && isMarked(values[down - 1], shallowMetres) &&
                    isMarked(values[down], shallowMetres) && isMarked(values[down + 1], shallowMetres)
                )
        }
        for (ny in y - 1..y + 1) {
            for (nx in x - 1..x + 1) {
                if (nx == x && ny == y) continue
                if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
                    if (rimAtEdges) return true
                } else if (!isMarked(values[ny * cols + nx], shallowMetres)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * The red drawn over the cell at [index] of [values], or [NONE] to leave
     * the layer's own colour there: the one rule for both ways a depth layer
     * is drawn. A marked cell on the rim of its patch (see [isRim]) is solid
     * [RED], whatever the stripes say; the cells inside take the stripes. So
     * a shoal one or two cells wide is all red and a bigger one has a red
     * outline: none can fall between two stripes and go unmarked.
     */
    fun markAt(
        values: FloatArray,
        cols: Int,
        rows: Int,
        index: Int,
        shallowMetres: Double,
        period: Int,
        rimAtEdges: Boolean
    ): Int {
        if (!isMarked(values[index], shallowMetres)) return NONE
        val y = index / cols
        val x = index - y * cols
        return if (isRim(values, cols, rows, x, y, shallowMetres, rimAtEdges)) RED else stripe(x, y, period)
    }

    /**
     * The stripes' spacing on a map tile [tilePixels] across. A tile is drawn
     * about 256dp wide whatever its pixel size, so the spacing grows with it:
     * a stripe every 16dp or so, clear at arm's length on a phone. It divides
     * every tile size the app draws, so the stripes run on from one tile to
     * the next.
     */
    fun tilePeriod(tilePixels: Int): Int = max(MIN_PERIOD, tilePixels / 16)

    /**
     * The stripes' spacing on a whole grid stretched over the chart. A cell
     * has no fixed size on screen, so the spacing goes by the grid: about 64
     * stripes along its longer side. With the whole layer in view that is a
     * fine hatch; a few zoom steps in, broad bands, still clearly stripes.
     */
    fun gridPeriod(cols: Int, rows: Int): Int = max(MIN_PERIOD, max(cols, rows) / 64)

    /** Three cells of red and one of white at the least, as in the web app. */
    private const val MIN_PERIOD = 8
}
