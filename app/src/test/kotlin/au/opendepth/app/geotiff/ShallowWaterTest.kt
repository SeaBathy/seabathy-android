package au.opendepth.app.geotiff

import au.opendepth.app.model.DepthRampSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShallowWaterTest {

    @Test
    fun `stripes run on the slant, three of red and one of white in every eight`() {
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val expected = when ((x + y) and 7) {
                    0, 1, 2 -> ShallowWater.RED
                    3 -> ShallowWater.EDGE
                    else -> ShallowWater.NONE
                }
                assertEquals("at $x,$y", expected, ShallowWater.stripe(x, y, 8))
            }
        }
    }

    @Test
    fun `wider stripes keep the same share of red, white and clear`() {
        val row = (0 until 48).map { ShallowWater.stripe(it, 0, 24) }

        assertEquals(18, row.count { it == ShallowWater.RED })
        assertEquals(6, row.count { it == ShallowWater.EDGE })
        assertEquals(24, row.count { it == ShallowWater.NONE })
        // One run of each to a stripe, not a scatter.
        assertEquals(List(9) { ShallowWater.RED } + List(3) { ShallowWater.EDGE } + List(12) { ShallowWater.NONE }, row.take(24))
    }

    @Test
    fun `stripes run on from one map tile to the next at every tile size`() {
        for (size in listOf(256, 384, 512, 768)) {
            val period = ShallowWater.tilePeriod(size)
            assertEquals("a $size pixel tile", 0, size % period)
            for (i in 0 until size step 7) {
                // The pixel just past this tile's edge is the next tile's first.
                assertEquals(ShallowWater.stripe(i + size, 5, period), ShallowWater.stripe(i, 5, period))
                assertEquals(ShallowWater.stripe(5, i + size, period), ShallowWater.stripe(5, i, period))
            }
        }
    }

    @Test
    fun `a stripe on a tile is about sixteen of the 256 it is drawn across`() {
        assertEquals(16, ShallowWater.tilePeriod(256))
        assertEquals(48, ShallowWater.tilePeriod(768))
    }

    @Test
    fun `a whole grid gets about sixty-four stripes along its longer side, never finer than eight cells`() {
        assertEquals(64, ShallowWater.gridPeriod(4096, 2000))
        assertEquals(25, ShallowWater.gridPeriod(900, 1600))
        assertEquals(8, ShallowWater.gridPeriod(200, 120))
    }

    @Test
    fun `only water shallower than the mark is marked`() {
        assertTrue(ShallowWater.isMarked(-0.4f, 2.0))
        assertTrue(ShallowWater.isMarked(-1.99f, 2.0))
        assertTrue(ShallowWater.isMarked(0f, 2.0))
        assertFalse(ShallowWater.isMarked(-2f, 2.0))
        assertFalse(ShallowWater.isMarked(-30f, 2.0))
        // Dry land is not shallow water.
        assertFalse(ShallowWater.isMarked(0.5f, 2.0))
        assertFalse(ShallowWater.isMarked(Float.NaN, 2.0))
        // Off.
        assertFalse(ShallowWater.isMarked(-0.4f, 0.0))
    }

    @Test
    fun `the mark is a depth or nothing`() {
        val before = ShallowWater.markMetres
        try {
            ShallowWater.markMetres = 3.0
            assertEquals(3.0, ShallowWater.markMetres, 0.0)
            ShallowWater.markMetres = -1.0
            assertEquals(0.0, ShallowWater.markMetres, 0.0)
            ShallowWater.markMetres = Double.NaN
            assertEquals(0.0, ShallowWater.markMetres, 0.0)
        } finally {
            ShallowWater.markMetres = before
        }
    }

    // ---------------------------------------------------------------- drawn into a layer

    private val inshore = DepthRampSettings(shallowMetres = -10.0, deepMetres = -30.0, hillshadeExaggeration = 0.0)

    @Test
    fun `shallow water is striped whatever the layer's range, and the rest is as before`() {
        // Top half a metre deep, outside the 10 to 30 m range; bottom half 20 m deep, inside it.
        val cols = 16
        val values = FloatArray(cols * 16) { if (it / cols < 8) -1f else -20f }
        val plain = IntArray(values.size)
        val marked = IntArray(values.size)
        DepthGridRenderer.paint(values, cols, inshore, plain)
        DepthGridRenderer.paint(values, cols, inshore, marked, shallowMetres = 2.0, stripePeriod = 8)

        for (i in values.indices) {
            val x = i % cols
            val y = i / cols
            if (y < 8) {
                // Hidden by the range, so clear between the stripes.
                assertEquals("unmarked at $x,$y", 0, plain[i])
                // Solid red where the shallow water meets the deep, and where the layer ends.
                val onRim = y == 0 || y == 7 || x == 0 || x == cols - 1
                assertEquals("marked at $x,$y", if (onRim) ShallowWater.RED else ShallowWater.stripe(x, y, 8), marked[i])
            } else {
                assertEquals("deep water at $x,$y", plain[i], marked[i])
                assertTrue(plain[i] != 0)
            }
        }
        assertTrue(marked.any { it == ShallowWater.RED } && marked.any { it == ShallowWater.EDGE })
    }

    @Test
    fun `land and missing depths are never striped`() {
        val values = floatArrayOf(4f, Float.NaN, 4f, Float.NaN, 4f, Float.NaN, 4f, Float.NaN)
        val pixels = IntArray(values.size) { 7 }
        DepthGridRenderer.paint(values, 8, inshore, pixels, shallowMetres = 20.0, stripePeriod = 8)

        pixels.forEach { assertEquals(0, it) }
    }

    @Test
    fun `stripes show between them the layer's own colour where it has one`() {
        // A map tile lying wholly in shallow water: no rim, as its edges are not where the shallows end.
        val shallows = DepthRampSettings(shallowMetres = 0.0, deepMetres = -30.0, hillshadeExaggeration = 0.0)
        val values = FloatArray(64) { -1f }
        val plain = IntArray(64)
        val marked = IntArray(64)
        DepthGridRenderer.paint(values, 8, shallows, plain)
        DepthGridRenderer.paint(values, 8, shallows, marked, shallowMetres = 2.0, stripePeriod = 8, rimAtEdges = false)

        for (i in values.indices) {
            val stripe = ShallowWater.stripe(i % 8, i / 8, 8)
            assertEquals(if (stripe == ShallowWater.NONE) plain[i] else stripe, marked[i])
        }
    }

    // ---------------------------------------------------------------- the rim

    /** Water 20 m deep, [cols] by [rows], with the cells [shallow] says half a metre deep. */
    private fun seabed(cols: Int, rows: Int, shallow: (x: Int, y: Int) -> Boolean): FloatArray =
        FloatArray(cols * rows) { if (shallow(it % cols, it / cols)) -0.5f else -20f }

    /** The mark at every cell, as a whole layer is drawn ([whole]) or a map tile. */
    private fun marks(values: FloatArray, cols: Int, period: Int, whole: Boolean = true): IntArray {
        val rows = values.size / cols
        return IntArray(values.size) { ShallowWater.markAt(values, cols, rows, it, 2.0, period, rimAtEdges = whole) }
    }

    @Test
    fun `a shoal of one cell is solid red wherever it lies among the stripes`() {
        // Every place in a stripe's 25 cells, the clear half included.
        for (at in 0 until 25) {
            val values = seabed(40, 9) { x, y -> x == 7 + at && y == 4 }
            val marks = marks(values, 40, period = 25)

            assertEquals("a shoal at column ${7 + at}", ShallowWater.RED, marks[4 * 40 + 7 + at])
            assertEquals(1, marks.count { it != ShallowWater.NONE })
        }
    }

    @Test
    fun `a shoal two cells wide is solid red all over`() {
        // Two cells side by side, and a strip two wide and twelve long.
        val pair = seabed(40, 9) { x, y -> y == 4 && (x == 20 || x == 21) }
        assertEquals(2, marks(pair, 40, period = 25).count { it == ShallowWater.RED })

        val strip = seabed(40, 20) { x, y -> x in 14..15 && y in 4..15 }
        val marks = marks(strip, 40, period = 25)
        for (i in strip.indices) {
            assertEquals(if (strip[i] > -2f) ShallowWater.RED else ShallowWater.NONE, marks[i])
        }
    }

    @Test
    fun `a bommie six cells across between two stripes still has a red ring`() {
        // On a 1600-cell layer a stripe is 25 cells, 13 of them clear: these 6 lie wholly in the clear band.
        val period = ShallowWater.gridPeriod(900, 1600)
        val values = seabed(60, 60) { x, y -> x in 7..12 && y in 7..12 }
        for (y in 7..12) for (x in 7..12) assertEquals(ShallowWater.NONE, ShallowWater.stripe(x, y, period))

        val marks = marks(values, 60, period)
        for (y in 7..12) {
            for (x in 7..12) {
                val onRim = x == 7 || x == 12 || y == 7 || y == 12
                assertEquals("at $x,$y", if (onRim) ShallowWater.RED else ShallowWater.NONE, marks[y * 60 + x])
            }
        }
        assertEquals(20, marks.count { it == ShallowWater.RED })
    }

    @Test
    fun `a big shoal has a red outline one cell wide and stripes inside`() {
        val values = seabed(48, 48) { x, y -> x in 6..40 && y in 5..41 }
        val marks = marks(values, 48, period = 8)

        for (y in 0 until 48) {
            for (x in 0 until 48) {
                val expected = when {
                    x !in 6..40 || y !in 5..41 -> ShallowWater.NONE
                    x == 6 || x == 40 || y == 5 || y == 41 -> ShallowWater.RED
                    else -> ShallowWater.stripe(x, y, 8)
                }
                assertEquals("at $x,$y", expected, marks[y * 48 + x])
            }
        }
    }

    @Test
    fun `a corner touching deeper water makes a rim, as do dry ground and missing depths`() {
        // All shallow but one cell: the eight cells round it are rim, corners included.
        for (other in listOf(-20f, 3f, Float.NaN)) {
            val values = FloatArray(81) { -0.5f }.also { it[4 * 9 + 4] = other }
            for (y in 1..7) {
                for (x in 1..7) {
                    val beside = kotlin.math.abs(x - 4) <= 1 && kotlin.math.abs(y - 4) <= 1 && !(x == 4 && y == 4)
                    assertEquals("at $x,$y beside $other", beside, ShallowWater.isRim(values, 9, 9, x, y, 2.0, rimAtEdges = false))
                }
            }
            assertEquals(ShallowWater.NONE, ShallowWater.markAt(values, 9, 9, 4 * 9 + 4, 2.0, 8, rimAtEdges = true))
        }
    }

    @Test
    fun `a whole layer's own edge is a rim, where its depths end`() {
        val values = FloatArray(12 * 10) { -0.5f }
        val marks = marks(values, 12, period = 8, whole = true)

        for (y in 0 until 10) {
            for (x in 0 until 12) {
                val onEdge = x == 0 || y == 0 || x == 11 || y == 9
                assertEquals("at $x,$y", if (onEdge) ShallowWater.RED else ShallowWater.stripe(x, y, 8), marks[y * 12 + x])
            }
        }
    }

    @Test
    fun `a map tile's edge is not a rim, so no line is drawn where one tile meets the next`() {
        val values = FloatArray(16 * 16) { -0.5f }
        val marks = marks(values, 16, period = 8, whole = false)

        for (i in values.indices) assertEquals(ShallowWater.stripe(i % 16, i / 16, 8), marks[i])
    }

    @Test
    fun `on a map tile the shallows still get their rim where they end inside it, up to its edge`() {
        // Shallow in the left three columns, to the top and bottom of the tile.
        val values = seabed(16, 16) { x, _ -> x < 3 }
        val marks = marks(values, 16, period = 8, whole = false)

        for (y in 0 until 16) {
            assertEquals(ShallowWater.stripe(0, y, 8), marks[y * 16])
            assertEquals(ShallowWater.stripe(1, y, 8), marks[y * 16 + 1])
            assertEquals("the rim at row $y", ShallowWater.RED, marks[y * 16 + 2])
            assertEquals(ShallowWater.NONE, marks[y * 16 + 3])
        }
        // One cell in the tile's very corner: its neighbours in the tile are deeper, so it is red.
        val corner = seabed(16, 16) { x, y -> x == 15 && y == 0 }
        assertEquals(ShallowWater.RED, marks(corner, 16, period = 48, whole = false)[15])
    }

    @Test
    fun `nothing is marked with the mark off, or on a grid of one cell that is not shallow`() {
        val values = FloatArray(9) { -0.5f }
        for (i in values.indices) assertEquals(ShallowWater.NONE, ShallowWater.markAt(values, 3, 3, i, 0.0, 8, rimAtEdges = true))
        assertEquals(ShallowWater.NONE, ShallowWater.markAt(floatArrayOf(-9f), 1, 1, 0, 2.0, 8, rimAtEdges = true))
        // A layer of one shallow cell is all rim.
        assertEquals(ShallowWater.RED, ShallowWater.markAt(floatArrayOf(-1f), 1, 1, 0, 2.0, 8, rimAtEdges = true))
    }

    @Test
    fun `a tile is drawn without a rim along its edges and a whole layer with one`() {
        val values = FloatArray(64) { -1f }
        val tile = IntArray(64)
        val layer = IntArray(64)
        DepthGridRenderer.paint(values, 8, inshore, tile, shallowMetres = 2.0, stripePeriod = 8, rimAtEdges = false)
        DepthGridRenderer.paint(values, 8, inshore, layer, shallowMetres = 2.0, stripePeriod = 8)

        for (i in values.indices) {
            val x = i % 8
            val y = i / 8
            assertEquals(ShallowWater.stripe(x, y, 8), tile[i])
            val onEdge = x == 0 || y == 0 || x == 7 || y == 7
            assertEquals(if (onEdge) ShallowWater.RED else ShallowWater.stripe(x, y, 8), layer[i])
        }
    }
}
