package com.seabathy.bathypicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The LERC decoder against GDAL. The samples are a made-up 128 x 128 seabed in four 64 x 64
 * tiles (a rough bottom, a corner of land, a hole and an empty corner with no data, one tile all
 * one value, one in whole-metre terraces), written by GDAL as LERC 2.4 with MAX_Z_ERROR 0.001,
 * plain and wrapped in Deflate, as LINZ writes its coastal lidar; expected.f32 is GDAL's reading
 * of them. No real survey is in the samples.
 */
class Lerc2Test {

    private fun resource(name: String): File {
        val f = File.createTempFile("lerc", "-$name")
        f.deleteOnExit()
        javaClass.getResourceAsStream("/lerc/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun expected(): FloatArray {
        val bytes = javaClass.getResourceAsStream("/lerc/expected.f32")!!.use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { bb.getFloat() }
    }

    /** The whole image, decoded tile by tile through RemoteTiff as a build reads it. */
    private fun decodeAll(file: File): Pair<RemoteTiff, FloatArray> {
        val t = RemoteTiff.open(file.toURI().toString(), null)
        val L = t.levels[0]
        val out = FloatArray(L.width * L.height)
        for (ty in 0 until L.tilesY) {
            val idx = t.tileRow(L, ty, null)
            for (tx in 0 until L.tilesX) {
                val raw = Http.range(t.url, idx[0][tx], idx[1][tx].toInt(), null)
                val v = t.decode(L, raw, 0, raw.size)
                for (y in 0 until L.tileH) for (x in 0 until L.tileW) {
                    val gx = tx * L.tileW + x
                    val gy = ty * L.tileH + y
                    if (gx < L.width && gy < L.height) out[gy * L.width + gx] = v[y * L.tileW + x]
                }
            }
        }
        return t to out
    }

    private fun assertSameAsGdal(name: String) {
        val (t, got) = decodeAll(resource(name))
        assertEquals(34887, t.levels[0].compression)
        val want = expected()
        assertEquals(want.size, got.size)
        var valid = 0
        for (i in want.indices) {
            if (want[i].isNaN()) {
                assertTrue("$name pixel $i should be empty, got ${got[i]}", got[i].isNaN())
            } else {
                valid++
                assertEquals("$name pixel $i", java.lang.Float.floatToIntBits(want[i]), java.lang.Float.floatToIntBits(got[i]))
            }
        }
        assertTrue("the sample has both empty and valid pixels", valid in 1000 until want.size)
    }

    @Test
    fun `LERC tiles decode exactly as GDAL reads them`() = assertSameAsGdal("lerc.tif")

    @Test
    fun `LERC wrapped in Deflate decodes the same`() = assertSameAsGdal("lerc_deflate.tif")

    @Test
    fun `a damaged tile is refused, not misread`() {
        val file = resource("lerc.tif")
        val t = RemoteTiff.open(file.toURI().toString(), null)
        val L = t.levels[0]
        val idx = t.tileRow(L, 0, null)
        val raw = Http.range(t.url, idx[0][0], idx[1][0].toInt(), null)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x5a).toByte()
        try {
            t.decode(L, raw, 0, raw.size)
            fail("a damaged tile decoded")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("LERC"))
        }
        // Cut short: refused as well.
        val good = Http.range(t.url, idx[0][0], idx[1][0].toInt(), null)
        try {
            t.decode(L, good, 0, 40)
            fail("a cut tile decoded")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("LERC"))
        }
    }

    @Test
    fun `Fletcher-32 matches the plain definition`() {
        val rnd = java.util.Random(7)
        for (len in listOf(0, 1, 2, 5, 718, 719, 720, 5001)) {
            val b = ByteArray(len).also { rnd.nextBytes(it) }
            val got = Lerc2.fletcher32(b, 0, len)
            // Sums of 16-bit words (high byte first, as LERC pairs them), modulo 65535.
            var s1 = 0xffffL
            var s2 = 0xffffL
            var i = 0
            while (i < len) {
                val word = ((b[i].toInt() and 0xff) shl 8) + (if (i + 1 < len) b[i + 1].toInt() and 0xff else 0)
                s1 = (s1 + word) % 65535
                s2 = (s2 + s1) % 65535
                i += 2
            }
            assertEquals("length $len, first sum", s1 % 65535, (got and 0xffff) % 65535)
            assertEquals("length $len, second sum", s2 % 65535, (got ushr 16) % 65535)
        }
        assertFalse(Lerc2.fletcher32(byteArrayOf(1, 2, 3), 0, 3) == Lerc2.fletcher32(byteArrayOf(1, 2, 4), 0, 3))
    }

    @Test
    fun `offsets are stored in a smaller type when they fit`() {
        assertEquals(6, Lerc2.typeUsed(6, 0))
        assertEquals(2, Lerc2.typeUsed(6, 1))
        assertEquals(1, Lerc2.typeUsed(6, 2))
        assertEquals(6, Lerc2.typeUsed(7, 1))
        assertEquals(3, Lerc2.typeUsed(5, 1))
    }
}
