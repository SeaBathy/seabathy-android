package au.opendepth.app.gdal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileValueCodecTest {
    @Test
    fun `depths come back to within two centimetres, gaps as gaps`() {
        val values = FloatArray(768 * 768) { i ->
            when {
                i % 97 == 0 -> Float.NaN
                else -> -890f + (i % 1000) * 1.044f // -890 m up to about +154 m
            }
        }
        val bytes = TileValueCodec.encode(values)
        assertTrue("packed ${bytes.size} bytes", bytes.size < values.size * 2)
        val back = TileValueCodec.decode(bytes, values.size)!!
        for (i in values.indices) {
            if (values[i].isNaN()) assertTrue(back[i].isNaN()) else assertEquals(values[i], back[i], 0.02f)
        }
    }

    @Test
    fun `a flat or empty tile round-trips`() {
        val flat = FloatArray(256 * 256) { -12.5f }
        assertTrue(TileValueCodec.decode(TileValueCodec.encode(flat), flat.size)!!.all { it == -12.5f })
        val empty = FloatArray(256 * 256) { Float.NaN }
        assertTrue(TileValueCodec.decode(TileValueCodec.encode(empty), empty.size)!!.all { it.isNaN() })
    }

    @Test
    fun `the wrong size or junk is refused`() {
        val bytes = TileValueCodec.encode(FloatArray(256 * 256) { -3f })
        assertNull(TileValueCodec.decode(bytes, 512 * 512))
        assertNull(TileValueCodec.decode(byteArrayOf(1, 2, 3), 256 * 256))
    }
}
