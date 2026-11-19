package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of a map tile's water is the sea: the ocean, and the harbours, bays and rivers joined to it. */
class SeaWatersTest {
    /** A box in tile units (0 to 4096), drawn the way the map draws an outside ring. */
    private fun box(x0: Int, y0: Int, x1: Int, y1: Int, ocean: Boolean = false) = Water(
        ocean, listOf(shortArrayOf(x0.toShort(), y0.toShort(), x1.toShort(), y0.toShort(), x1.toShort(), y1.toShort(), x0.toShort(), y1.toShort()))
    )

    private val ocean = box(2048, 0, 4096, 4096, ocean = true)
    private val harbour = box(1000, 1000, 2048, 1500)
    private val river = box(400, 1200, 1000, 1300)
    private val lake = box(200, 3000, 600, 3400)

    private fun seaOf(pieces: List<Water>, facing: Array<BooleanArray?>? = null): List<Water> {
        val layout = SeaWaters.layout(pieces)
        val sea = SeaWaters.joined(layout, facing?.let { SeaWaters.seeds(layout, it) })
        val rings = SeaWaters.rings(layout, sea)
        return pieces.filter { it.rings.first() in rings }
    }

    @Test
    fun `a harbour that opens on the ocean is sea, and so is the river that runs into it`() {
        assertEquals(listOf(ocean, harbour, river), seaOf(listOf(ocean, harbour, river, lake)))
    }

    @Test
    fun `a lake that touches nothing stays out`() {
        assertFalse(lake in seaOf(listOf(ocean, harbour, river, lake)))
        // And with no ocean in the tile, nothing is sea by itself.
        assertEquals(emptyList<Water>(), seaOf(listOf(harbour, river, lake)))
    }

    @Test
    fun `outlines a hair apart still touch`() {
        // 4 tile units is one grid cell: a gap of one cell is bridged, a wide one isn't.
        assertTrue(box(1000, 1000, 2044, 1500).let { it in seaOf(listOf(ocean, it)) })
        assertFalse(box(1000, 1000, 2000, 1500).let { it in seaOf(listOf(ocean, it)) })
    }

    @Test
    fun `water running in from the tile next door is followed one tile further`() {
        // The tile to the east: a river from its west edge into a harbour on the ocean.
        val east = SeaWaters.layout(listOf(ocean, harbour, box(0, 1200, 1000, 1300)))
        val eastEdges = SeaWaters.edgeSea(east, SeaWaters.joined(east))
        assertTrue(eastEdges[3][312])
        assertFalse(eastEdges[3][100])
        // This tile: the same river, reaching its east edge, and a lake elsewhere.
        val upstream = box(3000, 1200, 4096, 1300)
        assertEquals(emptyList<Water>(), seaOf(listOf(upstream, lake)))
        assertEquals(listOf(upstream), seaOf(listOf(upstream, lake), arrayOf(null, eastEdges[3], null, null)))
        // Water at the edge that doesn't line up with the neighbour's sea stays out.
        val elsewhere = box(3000, 3000, 4096, 3100)
        assertEquals(emptyList<Water>(), seaOf(listOf(elsewhere), arrayOf(null, eastEdges[3], null, null)))
    }

    @Test
    fun `an island in a harbour stays a hole`() {
        val island = shortArrayOf(1400, 1200, 1400, 1300, 1600, 1300, 1600, 1200)
        val withIsland = Water(false, listOf(harbour.rings.first(), island))
        val layout = SeaWaters.layout(listOf(ocean, withIsland))
        val rings = SeaWaters.rings(layout, SeaWaters.joined(layout))
        assertTrue(island in rings)
        // Sea in the harbour, not on the island (by the same test the map points use).
        val all = rings.toTypedArray()
        assertTrue(Coastline.windingNumber(all, 1200.0, 1250.0) != 0)
        assertEquals(0, Coastline.windingNumber(all, 1500.0, 1250.0))
    }

    @Test
    fun `a point is tested against the picture's own shore`() {
        // A 4 x 2 picture of 0..2 north, 10..14 east: sea in the east half.
        val bytes = byteArrayOf(0, 0, -1, -1, 0, 100, -1, -1)
        val mask = SeaMask(bytes, 4, 2, 0.0, 10.0, 2.0, 14.0)
        assertEquals(false, mask.isSea(1.5, 10.5))
        assertEquals(true, mask.isSea(1.5, 13.5))
        assertEquals(true, mask.isSea(0.5, 12.5))
        // Under half sea is land.
        assertEquals(false, mask.isSea(0.5, 11.5))
        assertNull(mask.isSea(3.0, 12.0))
        assertNull(mask.isSea(1.0, 9.0))
    }

    @Test
    fun `the nearest sea to a point on land is found by the shore, not the forecast's points`() {
        // 100 x 100 over one degree at the equator (about 1.1 km a pixel): land in the west, sea from 30% across.
        val side = 100
        val bytes = ByteArray(side * side) { if (it % side >= 30) -1 else 0 }
        val mask = SeaMask(bytes, side, side, 0.0, 10.0, 1.0, 11.0)
        val near = mask.nearestSea(0.505, 10.205, 150.0)!!
        // Due east, at the first sea pixel: about 11 km off, not a forecast point 30 km away.
        assertEquals(0.505, near.first, 0.01)
        assertEquals(10.305, near.second, 0.001)
        // Already at sea: itself.
        assertEquals(10.505, mask.nearestSea(0.5, 10.505, 150.0)!!.second, 0.006)
        // Nothing that near.
        assertNull(mask.nearestSea(0.505, 10.205, 5.0))
        assertNull(mask.nearestSea(2.0, 10.2, 150.0))
        // A pixel turned down (its middle on the bank of a river mouth): the next nearest is taken.
        val next = mask.nearestSea(0.505, 10.205, 150.0) { _, pLng -> pLng > 10.32 }!!
        assertEquals(10.325, next.second, 0.001)
        assertNull(mask.nearestSea(0.505, 10.205, 150.0) { _, _ -> false })
        // An island of land in the sea: the shore all round is near.
        val island = ByteArray(side * side) { val x = it % side; val y = it / side; if (x in 40..60 && y in 40..60) 0 else -1 }
        val round = SeaMask(island, side, side, 0.0, 10.0, 1.0, 11.0).nearestSea(0.5, 10.42, 150.0)!!
        assertEquals(10.395, round.second, 0.001)
    }
}
