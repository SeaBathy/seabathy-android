package au.opendepth.app.gdal

import au.opendepth.app.geotiff.SeabedSlope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Which survey cell each pixel of a map tile shows, and the slope colours of
 * a tile drawn closer in than the survey is fine, where one cell fills
 * several pixels.
 */
class TileWindowTest {

    private val tile = 48
    private val native = 1000

    /**
     * The window of a [tile]-pixel tile whose top-left corner is at native
     * pixel ([col0], [row0]), drawn [pixelsPerCell] pixels to a survey cell,
     * the survey turned [degrees] against the tile.
     */
    private fun window(col0: Double, row0: Double, pixelsPerCell: Double, degrees: Double = 0.0): TileWindow {
        val nodes = tile / SAMPLE_STEP + 1
        val turn = Math.toRadians(degrees)
        val nodeCol = DoubleArray(nodes * nodes)
        val nodeRow = DoubleArray(nodes * nodes)
        for (j in 0 until nodes) {
            for (i in 0 until nodes) {
                val x = i * SAMPLE_STEP / pixelsPerCell
                val y = j * SAMPLE_STEP / pixelsPerCell
                nodeCol[j * nodes + i] = col0 + x * cos(turn) - y * sin(turn)
                nodeRow[j * nodes + i] = row0 + x * sin(turn) + y * cos(turn)
            }
        }
        val sourceX = floor(nodeCol.min()).toInt().coerceIn(0, native - 1)
        val sourceY = floor(nodeRow.min()).toInt().coerceIn(0, native - 1)
        val sourceWidth = ceil(nodeCol.max()).toInt().coerceIn(sourceX + 1, native) - sourceX
        val sourceHeight = ceil(nodeRow.max()).toInt().coerceIn(sourceY + 1, native) - sourceY
        return TileWindow(sourceX, sourceY, sourceWidth, sourceHeight, sourceWidth, sourceHeight, nodes, nodeCol, nodeRow)
    }

    /** The tile's depths as the tile source fills them: each pixel takes the cell it shows. */
    private fun TileWindow.tileOf(decoded: FloatArray): FloatArray {
        val values = FloatArray(tile * tile) { Float.NaN }
        forEachPixel(tile, native, native) { pixel, cell -> values[pixel] = decoded[cell] }
        return values
    }

    /** A seabed falling ten degrees to the east, in survey cells ten metres across. */
    private fun TileWindow.tenDegreesEast(): FloatArray {
        val drop = 10.0 * tan(Math.toRadians(10.0))
        return FloatArray(readWidth * readHeight) { (-5.0 - (it % readWidth) * drop).toFloat() }
    }

    @Test
    fun `each pixel of a tile drawn three pixels to a cell shows the cell under it`() {
        val window = window(col0 = 100.0, row0 = 200.0, pixelsPerCell = 3.0)
        val cells = IntArray(tile * tile) { -1 }
        window.forEachPixel(tile, native, native) { pixel, cell -> cells[pixel] = cell }

        assertEquals(16, window.readWidth)
        for (row in 0 until tile) {
            for (col in 0 until tile) assertEquals("at $col,$row", row / 3 * 16 + col / 3, cells[row * tile + col])
        }
    }

    @Test
    fun `pixels off the edge of the survey show no cell`() {
        // The tile's left 12 pixels and top 6 lie outside the survey.
        val window = window(col0 = -4.0, row0 = -2.0, pixelsPerCell = 3.0)
        val shown = BooleanArray(tile * tile)
        window.forEachPixel(tile, native, native) { pixel, cell ->
            shown[pixel] = true
            assertTrue(cell in 0 until window.readWidth * window.readHeight)
        }

        for (row in 0 until tile) {
            for (col in 0 until tile) assertEquals("at $col,$row", col >= 12 && row >= 6, shown[row * tile + col])
        }
    }

    @Test
    fun `a tile repeats cells only when drawn closer in than the survey is fine`() {
        assertTrue(window(100.0, 200.0, pixelsPerCell = 3.0).repeatsCells(tile))
        assertTrue(window(100.0, 200.0, pixelsPerCell = 1.2).repeatsCells(tile))
        assertTrue(window(100.3, 200.7, pixelsPerCell = 2.5, degrees = 4.0).repeatsCells(tile))
        // A cell to a pixel, and two cells to a pixel.
        assertFalse(window(100.0, 200.0, pixelsPerCell = 1.0).repeatsCells(tile))
        assertFalse(window(100.0, 200.0, pixelsPerCell = 0.5).repeatsCells(tile))
    }

    @Test
    fun `a thinned read is counted in the cells it was read at`() {
        // Zoomed out: 384 native pixels across the tile, read at one value for every eight.
        val wide = window(100.0, 200.0, pixelsPerCell = 0.125)
        val thinned = TileWindow(
            wide.sourceX, wide.sourceY, wide.sourceWidth, wide.sourceHeight,
            readWidth = 48, readHeight = 48, nodes = wide.nodes, nodeCol = wide.nodeCol, nodeRow = wide.nodeRow
        )

        assertEquals(48.0, thinned.cellsAcross, 1e-9)
        assertEquals(48.0, thinned.cellsDown, 1e-9)
        assertFalse(thinned.repeatsCells(tile))
    }

    @Test
    fun `an even ten degree slope drawn three pixels to a cell reads ten degrees all over the tile`() {
        val window = window(col0 = 100.0, row0 = 200.0, pixelsPerCell = 3.0)
        val values = window.tileOf(window.tenDegreesEast())
        // A cell is 10 m, so a tile pixel is a third of that.
        val slopes = window.slopesByCell(values, tile, native, native, pixelX = 10.0 / 3, pixelY = 10.0 / 3)

        slopes.forEach { assertEquals(10f, it, 0.01f) }

        // From one pixel to the next, as it was measured before: flat inside each cell, too steep along its edges.
        val lattice = SeabedSlope.degrees(values, tile, tile, 10.0 / 3, 10.0 / 3)
        val row = 20 * tile
        assertEquals(0f, lattice[row + 4], 0.01f)
        assertEquals(14.8f, lattice[row + 3], 0.1f)
        assertEquals(14.8f, lattice[row + 5], 0.1f)
    }

    @Test
    fun `it reads ten degrees too when a cell is two and a half pixels, and the tile starts part-way through one`() {
        val window = window(col0 = 100.3, row0 = 200.7, pixelsPerCell = 2.5)
        val values = window.tileOf(window.tenDegreesEast())
        val slopes = window.slopesByCell(values, tile, native, native, pixelX = 4.0, pixelY = 4.0)

        assertEquals(19.2, window.cellsAcross, 1e-9)
        slopes.forEach { assertEquals(10f, it, 0.01f) }
    }

    @Test
    fun `it reads ten degrees when the survey is turned a little against the map, as a UTM one is`() {
        val window = window(col0 = 300.3, row0 = 400.7, pixelsPerCell = 3.0, degrees = 4.0)
        val values = window.tileOf(window.tenDegreesEast())
        val slopes = window.slopesByCell(values, tile, native, native, pixelX = 10.0 / 3, pixelY = 10.0 / 3)

        // Sixteen cells along the tile's own middle line, however it lies across the survey.
        assertEquals(16.0, window.cellsAcross, 1e-9)
        assertEquals(16.0, window.cellsDown, 1e-9)
        assertTrue(values.none { it.isNaN() })
        slopes.forEach { assertEquals(10f, it, 0.01f) }
    }

    @Test
    fun `pixels with no depth have no slope, and those beside them use the side they have`() {
        val window = window(col0 = 100.0, row0 = 200.0, pixelsPerCell = 3.0)
        val decoded = window.tenDegreesEast()
        // One survey cell with no depth: the nine pixels of cell 5, 7.
        decoded[7 * 16 + 5] = Float.NaN
        val values = window.tileOf(decoded)
        val slopes = window.slopesByCell(values, tile, native, native, pixelX = 10.0 / 3, pixelY = 10.0 / 3)

        for (row in 0 until tile) {
            for (col in 0 until tile) {
                val slope = slopes[row * tile + col]
                if (row / 3 == 7 && col / 3 == 5) assertTrue(slope.isNaN()) else assertEquals("at $col,$row", 10f, slope, 0.01f)
            }
        }
    }
}
