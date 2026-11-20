package au.opendepth.app.geotiff

import kotlin.math.atan
import kotlin.math.hypot

/**
 * How steep the seabed is at each cell of a depth grid, for colouring a
 * layer by slope instead of by depth: ledges and drop-offs then stand out
 * whatever their depth. The same sums as the web app and the 3D view.
 *
 * Pure and Android-free, so it is unit-tested on the JVM.
 */
object SeabedSlope {

    /** In [degreesByCell]'s list of cells: a pixel that shows none. */
    const val NO_CELL = -1

    /**
     * The slope at each cell in degrees (0 flat, 90 a wall), from the cells
     * either side of it. [cellX]/[cellY] are the cell sizes in metres;
     * [values] are metres, NaN for no data, and stay NaN here.
     */
    fun degrees(values: FloatArray, cols: Int, rows: Int, cellX: Double, cellY: Double): FloatArray {
        val out = FloatArray(cols * rows) { Float.NaN }
        if (cols < 2 || rows < 2 || !(cellX > 0.0) || !(cellY > 0.0)) return out
        for (r in 0 until rows) {
            val here = r * cols
            for (c in 0 until cols) {
                val i = here + c
                if (values[i].isNaN()) continue
                out[i] = steepness(eastward(values, cols, i, c, cellX).orFlat(), southward(values, cols, rows, i, r, cellY).orFlat())
            }
        }
        return out
    }

    /**
     * How the seabed rises to the east at cell [i] (column [c]), in metres a
     * metre: from the neighbour each side where there is one with a depth,
     * else this cell itself, so at the edge of the survey the slope comes
     * from the one side there is. NaN with a depth on neither side.
     */
    private fun eastward(values: FloatArray, cols: Int, i: Int, c: Int, cellX: Double): Double {
        val left = if (c > 0 && !values[i - 1].isNaN()) i - 1 else i
        val right = if (c < cols - 1 && !values[i + 1].isNaN()) i + 1 else i
        return if (right > left) (values[right] - values[left]) / ((right - left) * cellX) else Double.NaN
    }

    /** Likewise to the south, at cell [i] of row [r]. */
    private fun southward(values: FloatArray, cols: Int, rows: Int, i: Int, r: Int, cellY: Double): Double {
        val up = if (r > 0 && !values[i - cols].isNaN()) i - cols else i
        val down = if (r < rows - 1 && !values[i + cols].isNaN()) i + cols else i
        return if (down > up) (values[down] - values[up]) / ((down - up) / cols * cellY) else Double.NaN
    }

    private fun Double.orFlat(): Double = if (isNaN()) 0.0 else this

    private fun steepness(dzdx: Double, dzdy: Double): Float = Math.toDegrees(atan(hypot(dzdx, dzdy))).toFloat()

    /**
     * The slope at each pixel of a picture drawn closer in than its depths
     * are fine, where one depth cell fills two or three pixels. Measured
     * from one pixel to the next the seabed reads flat inside every cell
     * and too steep along its edges: a lattice. Here it is measured from
     * cell to cell instead, across whole cells, and every pixel of a cell
     * takes its cell's slope.
     *
     * [cellOf] says which cell each pixel shows, numbered row by row over a
     * grid [cellCols] by [cellRows] ([NO_CELL] for none); [cellX]/[cellY]
     * are a cell's size in metres. [values] are the pixels' depths, NaN for
     * no data, which stays NaN here.
     *
     * The picture need not show every cell of that grid: a survey turned a
     * little against the map leaves the grid's corners outside it. A cell
     * with nothing shown on either side of it, at the tip of such a corner,
     * takes that part of its slope from the cell above or below (or, for
     * the slope down the grid, from the one beside it).
     */
    fun degreesByCell(
        values: FloatArray,
        cellOf: IntArray,
        cellCols: Int,
        cellRows: Int,
        cellX: Double,
        cellY: Double
    ): FloatArray {
        val cells = FloatArray(cellCols * cellRows) { Float.NaN }
        for (i in values.indices) {
            val cell = cellOf[i]
            if (cell >= 0) cells[cell] = values[i]
        }
        val slopes = FloatArray(cells.size) { Float.NaN }
        if (cellCols >= 2 && cellRows >= 2 && cellX > 0.0 && cellY > 0.0) {
            for (i in cells.indices) {
                if (cells[i].isNaN()) continue
                val r = i / cellCols
                val c = i - r * cellCols
                var dzdx = eastward(cells, cellCols, i, c, cellX)
                if (dzdx.isNaN() && r > 0 && !cells[i - cellCols].isNaN()) dzdx = eastward(cells, cellCols, i - cellCols, c, cellX)
                if (dzdx.isNaN() && r < cellRows - 1 && !cells[i + cellCols].isNaN()) dzdx = eastward(cells, cellCols, i + cellCols, c, cellX)
                var dzdy = southward(cells, cellCols, cellRows, i, r, cellY)
                if (dzdy.isNaN() && c > 0 && !cells[i - 1].isNaN()) dzdy = southward(cells, cellCols, cellRows, i - 1, r, cellY)
                if (dzdy.isNaN() && c < cellCols - 1 && !cells[i + 1].isNaN()) dzdy = southward(cells, cellCols, cellRows, i + 1, r, cellY)
                slopes[i] = steepness(dzdx.orFlat(), dzdy.orFlat())
            }
        }
        return FloatArray(values.size) { i ->
            val cell = cellOf[i]
            if (cell >= 0 && !values[i].isNaN()) slopes[cell] else Float.NaN
        }
    }
}
