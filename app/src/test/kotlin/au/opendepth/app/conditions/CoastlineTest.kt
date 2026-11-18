package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CoastlineTest {

    // A tiny vector tile writer: just enough to make a "water" layer to read back.
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    private fun field(out: ByteArrayOutputStream, number: Int, bytes: ByteArray) {
        varint(out, (number.toLong() shl 3) or 2)
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun varintField(out: ByteArrayOutputStream, number: Int, value: Long) {
        varint(out, (number.toLong() shl 3) or 0)
        varint(out, value)
    }

    private fun packed(values: List<Int>) = ByteArrayOutputStream().also { o -> values.forEach { varint(o, it.toLong() and 0xFFFFFFFFL) } }.toByteArray()

    private fun zz(v: Int) = (v shl 1) xor (v shr 31)

    /** A square ring as vector tile geometry: MoveTo, LineTo x3, ClosePath. */
    private fun square(x: Int, y: Int, side: Int): List<Int> =
        listOf((1 shl 3) or 1, zz(x), zz(y), (3 shl 3) or 2, zz(side), zz(0), zz(0), zz(side), zz(-side), zz(0), (1 shl 3) or 7)

    private fun feature(tags: List<Int>, geometry: List<Int>) = ByteArrayOutputStream().also { f ->
        field(f, 2, packed(tags))
        varintField(f, 3, 3)
        field(f, 4, packed(geometry))
    }.toByteArray()

    private fun value(s: String) = ByteArrayOutputStream().also { field(it, 1, s.toByteArray()) }.toByteArray()

    private fun tile(): ByteArray {
        val layer = ByteArrayOutputStream()
        field(layer, 1, "water".toByteArray())
        field(layer, 2, feature(listOf(0, 0), square(0, 0, 4096)))   // ocean: the whole tile
        field(layer, 2, feature(listOf(0, 1), square(100, 100, 50)))  // a lake: not the sea
        field(layer, 3, "class".toByteArray())
        field(layer, 4, value("ocean"))
        field(layer, 4, value("lake"))
        varintField(layer, 15, 2)
        val other = ByteArrayOutputStream()
        field(other, 1, "transportation".toByteArray())
        return ByteArrayOutputStream().also { t ->
            field(t, 3, other.toByteArray())
            field(t, 3, layer.toByteArray())
        }.toByteArray()
    }

    @Test
    fun readsOnlyTheOceanFromAMapTile() {
        val rings = Coastline.oceanRings(tile())
        assertEquals(1, rings.size)
        val r = rings[0]
        // Four corners of the whole tile.
        assertEquals(8, r.size)
        assertEquals(listOf(0, 0, 4096, 0, 4096, 4096, 0, 4096), r.map { it.toInt() })
    }

    @Test
    fun readsGzippedTilesToo() {
        val zipped = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(tile()) } }.toByteArray()
        assertEquals(1, Coastline.oceanRings(zipped).size)
    }

    @Test
    fun theMaskMakesLandSeeThrough() {
        val argb = intArrayOf(0xFF102030.toInt(), 0xFF102030.toInt(), 0x80102030.toInt())
        Coastline.applyMask(argb, byteArrayOf(0xFF.toByte(), 0, 0x80.toByte()))
        assertEquals(0xFF102030.toInt(), argb[0])
        assertEquals(0x00102030, argb[1])
        // Half sea at the shore: half as see-through again.
        assertEquals(0x40, argb[2] ushr 24)
        assertEquals(0x102030, argb[2] and 0xFFFFFF)
    }

    @Test
    fun theWindingNumberTellsSeaFromLand() {
        // Sea: the whole tile, with an island (a hole, wound the other way) in the middle.
        val sea = shortArrayOf(0, 0, 4096, 0, 4096, 4096, 0, 4096)
        val island = shortArrayOf(1000, 1000, 1000, 2000, 2000, 2000, 2000, 1000)
        val rings = arrayOf(sea, island)
        assertTrue(Coastline.windingNumber(rings, 500.0, 500.0) != 0)
        assertEquals(0, Coastline.windingNumber(rings, 1500.0, 1500.0))
        assertEquals(0, Coastline.windingNumber(arrayOf(shortArrayOf(0, 0, 100, 0, 100, 100)), 3000.0, 3000.0))
    }

    @Test
    fun tileMaths() {
        val n = 1 shl Coastline.ZOOM
        assertEquals(n / 2, Coastline.tileX(0.0, n))
        assertEquals(n / 2, Coastline.tileY(-0.001, n))
        // Sydney at zoom 10.
        assertEquals(942, Coastline.tileX(151.2, n))
        assertEquals(614, Coastline.tileY(-33.87, n))
        assertEquals(-33.87, Coastline.latitudeOf(Coastline.mercatorY(-33.87)), 1e-9)
        assertTrue(Coastline.mercatorY(-33.87) < 0)
    }
}
