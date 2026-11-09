package au.opendepth.app.geotiff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthTileCodecTest {

    @Test
    fun `round-trips ordinary depth values`() {
        val values = floatArrayOf(-1.5f, -12.25f, 0f, -999.9f)

        val decoded = DepthTileCodec.decode(DepthTileCodec.encode(values))

        assertArrayEquals(values, decoded, 0f)
    }

    @Test
    fun `round-trips NO_DATA as NaN`() {
        val values = floatArrayOf(DepthGrid.NO_DATA, -5f, DepthGrid.NO_DATA)

        val decoded = DepthTileCodec.decode(DepthTileCodec.encode(values))

        assertTrue(decoded[0].isNaN())
        assertEquals(-5f, decoded[1], 0f)
        assertTrue(decoded[2].isNaN())
    }

    @Test
    fun `encoded size is exactly 4 bytes per sample`() {
        val values = FloatArray(256 * 256) { -it.toFloat() }

        assertEquals(values.size * 4, DepthTileCodec.encode(values).size)
    }

    @Test
    fun `an empty tile round-trips to an empty array`() {
        assertArrayEquals(FloatArray(0), DepthTileCodec.decode(DepthTileCodec.encode(FloatArray(0))), 0f)
    }

    @Test
    fun `a blob whose size is not a multiple of 4 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthTileCodec.decode(ByteArray(6))
        }
    }
}
