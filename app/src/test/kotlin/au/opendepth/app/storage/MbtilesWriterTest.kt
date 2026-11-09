package au.opendepth.app.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [MbtilesWriter.tmsRowFor] -- the one piece of this writer's logic
 * that is not SQLite I/O and so can actually run in this app's plain-JVM
 * unit test suite. Asserted directly against
 * [au.opendepth.app.tiles.LocalTileServer]'s own read-side formula
 * (`tmsRow = 2^z - 1 - y`), not just
 * re-derived here, since a mismatch between write and read would make the
 * app unable to display its own downloaded files.
 */
class MbtilesWriterTest {

    @Test
    fun `the north edge (xyz y=0) maps to the highest TMS row`() {
        assertEquals(15, MbtilesWriter.tmsRowFor(z = 4, xyzY = 0))
    }

    @Test
    fun `the south edge (highest xyz y) maps to TMS row 0`() {
        assertEquals(0, MbtilesWriter.tmsRowFor(z = 4, xyzY = 15))
    }

    @Test
    fun `applying the conversion twice returns the original row (self-inverse)`() {
        val z = 6
        val originalY = 21

        val tmsRow = MbtilesWriter.tmsRowFor(z, originalY)
        val roundTripped = MbtilesWriter.tmsRowFor(z, tmsRow)

        assertEquals(originalY, roundTripped)
    }

    @Test
    fun `matches LocalTileServer's own read-side formula exactly`() {
        for (z in 0..8) {
            for (y in 0 until (1 shl z)) {
                val expected = (1 shl z) - 1 - y
                assertEquals(expected, MbtilesWriter.tmsRowFor(z, y))
            }
        }
    }
}
