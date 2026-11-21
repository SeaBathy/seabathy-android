package au.opendepth.app.fishing

import au.opendepth.app.fishing.StructureFinder.Kind
import au.opendepth.app.map.DepthContours
import au.opendepth.app.nav.DepthUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp

class StructureFinderTest {

    private val size = 160
    private val cellMetres = 10.0

    /** Column x is at 150 + x/10000 degrees east, row y at -27 - y/10000 north. */
    private val placement = object : DepthContours.Placement {
        override fun lng(x: Double) = 150.0 + x * 1e-4
        override fun lat(y: Double) = -27.0 - y * 1e-4
        override fun col(lng: Double) = (lng - 150.0) / 1e-4
        override fun row(lat: Double) = (-27.0 - lat) / 1e-4
    }

    private fun grid(depth: (x: Int, y: Int) -> Double): FloatArray =
        FloatArray(size * size) { i -> depth(i % size, i / size).toFloat() }

    private fun bump(x: Int, y: Int, cx: Int, cy: Int, height: Double, sigma: Double): Double {
        val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()
        return height * exp(-d2 / (2 * sigma * sigma))
    }

    private fun find(values: FloatArray) = StructureFinder.find(values, size, size, cellMetres, cellMetres, placement)

    private fun StructureFinder.Mark.x() = placement.col(lng)
    private fun StructureFinder.Mark.y() = placement.row(lat)

    @Test
    fun `a bump on a flat seabed is a pinnacle, with its top and how far it rises`() {
        val values = grid { x, y -> -30.0 + bump(x, y, 60, 80, 5.0, 3.0) }
        val marks = find(values).marks
        assertEquals(1, marks.size)
        val mark = marks.single()
        assertEquals(Kind.PINNACLE, mark.kind)
        assertEquals(60.0, mark.x(), 0.5)
        assertEquals(80.0, mark.y(), 0.5)
        assertEquals(25.0, mark.depthM, 0.01)
        assertEquals(5.0, mark.reliefM, 0.8)
        assertEquals("Pinnacle, rises ${String.format(java.util.Locale.US, "%.1f", mark.reliefM)} m, top 25 m", StructureFinder.describe(mark, DepthUnit.METRES))
    }

    @Test
    fun `a dip is a hole`() {
        val values = grid { x, y -> -30.0 - bump(x, y, 100, 50, 4.0, 3.0) }
        val mark = find(values).marks.single()
        assertEquals(Kind.HOLE, mark.kind)
        assertEquals(100.0, mark.x(), 0.5)
        assertEquals(50.0, mark.y(), 0.5)
        assertEquals(34.0, mark.depthM, 0.01)
        assertTrue(StructureFinder.describe(mark, DepthUnit.METRES).startsWith("Hole, "))
    }

    @Test
    fun `a step between two levels is one ledge, and its top edge is no pinnacle`() {
        val random = Random(7)
        // 20 m to the west, 30 m to the east, over two cells; a little noise so nothing ties.
        val values = grid { x, _ ->
            val base = when {
                x < 80 -> -20.0
                x > 81 -> -30.0
                else -> -20.0 - (x - 79) * 10.0 / 3
            }
            base + (random.nextDouble() - 0.5) * 0.1
        }
        val marks = find(values).marks
        assertEquals(1, marks.size)
        val ledge = marks.single()
        assertEquals(Kind.LEDGE, ledge.kind)
        assertEquals(80.5, ledge.x(), 3.0)
        assertEquals(10.0, ledge.reliefM, 1.5)
        assertEquals(20.0, ledge.depthM, 0.5)
        assertEquals(30.0, ledge.otherDepthM, 0.5)
        // It runs the whole height of the grid.
        assertTrue(ledge.lengthM > 1000)
        assertTrue(StructureFinder.describe(ledge, DepthUnit.METRES), StructureFinder.describe(ledge, DepthUnit.METRES).startsWith("Drop-off, drops "))
    }

    @Test
    fun `noise on a flat or evenly sloping seabed finds nothing`() {
        val random = Random(1)
        val flat = grid { _, _ -> -30.0 + (random.nextDouble() - 0.5) * 0.8 }
        assertTrue(find(flat).marks.isEmpty())
        // From 10 m to 58 m across the grid: steep everywhere, but no step and no high.
        val slope = grid { x, _ -> -10.0 - x * 0.3 + (random.nextDouble() - 0.5) * 0.6 }
        assertTrue(find(slope).marks.isEmpty())
    }

    @Test
    fun `a bump too small for the depth or the rounding is left out`() {
        // One metre at 60 m is under the 3% that counts there.
        val deep = grid { x, y -> -60.0 + bump(x, y, 80, 80, 1.2, 3.0) }
        assertTrue(find(deep).marks.isEmpty())
        // Depths in whole metres: a 1 m high is one step of the rounding.
        val rounded = grid { x, y -> Math.round(-20.0 + bump(x, y, 80, 80, 1.4, 3.0)).toDouble() }
        assertTrue(find(rounded).marks.isEmpty())
        assertEquals(1.0, StructureFinder.roundingStep(rounded, size, size), 1e-6)
    }

    @Test
    fun `a single bad sounding is not a pinnacle`() {
        val values = grid { _, _ -> -30.0 }
        values[80 * size + 80] = -20f
        assertTrue(find(values).marks.isEmpty())
    }

    @Test
    fun `no depths and land are skipped, and nothing is marked at their edge`() {
        // The west half has no depths, a strip is land; the seabed rises towards the land.
        val values = grid { x, y ->
            when {
                x < 50 -> Double.NaN
                x < 60 -> 2.0
                else -> -5.0 - (x - 60) * 0.2 + bump(x, y, 120, 80, 6.0, 3.0)
            }
        }
        val marks = find(values).marks
        assertEquals(1, marks.size)
        assertEquals(Kind.PINNACLE, marks.single().kind)
        assertEquals(120.0, marks.single().x(), 1.0)
        // All of it no depth: nothing, and no failure.
        assertTrue(find(FloatArray(size * size) { Float.NaN }).marks.isEmpty())
    }

    @Test
    fun `a big grid is averaged down, and the top still comes from the full grid`() {
        val big = 900
        val values = FloatArray(big * big) { i ->
            val x = i % big
            val y = i / big
            (-40.0 + bump(x, y, 450, 300, 8.0, 12.0)).toFloat()
        }
        val result = StructureFinder.find(values, big, big, 2.0, 2.0, placement)
        assertEquals(6.0, result.cellMetres, 1e-9)
        val mark = result.marks.single()
        assertEquals(Kind.PINNACLE, mark.kind)
        assertEquals(32.0, mark.depthM, 0.01)
        assertEquals(450.0, mark.x(), 0.5)
        assertEquals(300.0, mark.y(), 0.5)
    }

    @Test
    fun `marks are kept only where asked, best first`() {
        val values = grid { x, y -> -30.0 + bump(x, y, 40, 40, 3.0, 3.0) + bump(x, y, 120, 120, 6.0, 3.0) }
        val both = find(values).marks
        assertEquals(2, both.size)
        assertEquals(120.0, both.first().x(), 0.5)
        val west = StructureFinder.find(values, size, size, cellMetres, cellMetres, placement, within = { _, lng -> lng < 150.008 })
        assertEquals(1, west.marks.size)
        assertEquals(40.0, west.marks.single().x(), 0.5)
    }

    @Test
    fun `depth bands are in round numbers of the unit, and a mark is in each band it reaches`() {
        val bands = StructureFinder.bands(DepthUnit.METRES)
        assertEquals(listOf("All depths", "Shallower than 10 m", "10 to 20 m", "20 to 40 m", "40 to 80 m", "Deeper than 80 m"), bands.map { it.label })
        assertEquals("Shallower than 30 ft", StructureFinder.bands(DepthUnit.FEET)[1].label)
        val pinnacle = StructureFinder.Mark(Kind.PINNACLE, 0.0, 0.0, depthM = 18.0, reliefM = 7.0, otherDepthM = 25.0, lengthM = 0.0, score = 2.0)
        assertTrue(bands[0].holds(pinnacle))
        assertTrue(bands[2].holds(pinnacle))
        assertTrue(bands[3].holds(pinnacle))
        assertTrue(!bands[1].holds(pinnacle))
        assertTrue(!bands[4].holds(pinnacle))
    }

    @Test
    fun `marks are worded in the depth unit`() {
        val ledge = StructureFinder.Mark(Kind.LEDGE, 0.0, 0.0, depthM = 22.0, reliefM = 6.0, otherDepthM = 28.0, lengthM = 300.0, score = 2.0)
        assertEquals("Ledge, drops 6.0 m, 22 to 28 m", StructureFinder.describe(ledge, DepthUnit.METRES))
        assertEquals("Ledge, drops 19.7 ft, 72 to 92 ft", StructureFinder.describe(ledge, DepthUnit.FEET))
        assertEquals("Ledge 22 m", StructureFinder.name(ledge, DepthUnit.METRES))
        val shallow = StructureFinder.Mark(Kind.PINNACLE, 0.0, 0.0, depthM = 7.46, reliefM = 2.0, otherDepthM = 9.5, lengthM = 0.0, score = 2.0)
        assertEquals("Pinnacle 7.5 m", StructureFinder.name(shallow, DepthUnit.METRES))
    }
}
