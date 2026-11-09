package au.opendepth.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CoordinateFormatterTest {

    @Test
    fun `degrees and decimal minutes format is locale independent`() {
        withLocale(Locale.GERMANY) {
            assertEquals(
                "18°18.000'S 147°42.000'E",
                CoordinateFormatter.formatDdm(-18.3, 147.7)
            )
        }
    }

    @Test
    fun `decimal degrees format is locale independent`() {
        withLocale(Locale.GERMANY) {
            assertEquals("-18.300000, 147.700000", CoordinateFormatter.formatDd(-18.3, 147.7))
        }
    }

    @Test
    fun `decimal degrees round trip through the parser`() {
        val parsed = CoordinateFormatter.parsePair(
            CoordinateFormatter.formatDd(-18.3, 147.7)
        )
        assertEquals(-18.3, parsed!!.first, 1e-6)
        assertEquals(147.7, parsed.second, 1e-6)
    }

    @Test
    fun `marine format round trips through the parser`() {
        val parsed = CoordinateFormatter.parsePair(
            CoordinateFormatter.formatDdm(-18.3, 147.7)
        )
        assertEquals(-18.3, parsed!!.first, 1e-4)
        assertEquals(147.7, parsed.second, 1e-4)
    }

    @Test
    fun `degrees minutes seconds round trips through the parser`() {
        val parsed = CoordinateFormatter.parsePair(
            CoordinateFormatter.formatDms(-18.3, 147.7)
        )
        assertEquals(-18.3, parsed!!.first, 1e-4)
        assertEquals(147.7, parsed.second, 1e-4)
    }

    @Test
    fun `minutes that round up carry into the degrees instead of showing 60`() {
        assertEquals("34°00.000'S 151°00.000'E", CoordinateFormatter.formatDdm(-33.99999999, 150.99999999))
        assertEquals("34°00'00.00\"S 151°00'00.00\"E", CoordinateFormatter.formatDms(-33.99999999, 150.99999999))
    }

    @Test
    fun `minutes or seconds of 60 or more are rejected`() {
        assertNull(CoordinateFormatter.parseSingle("34 75 S", true))
        assertNull(CoordinateFormatter.parseSingle("34 10 60 S", true))
    }

    @Test
    fun `a leading minus sign is kept for positions within a degree of zero`() {
        assertEquals(-0.5, CoordinateFormatter.parseSingle("-0 30", true)!!, 1e-9)
        assertEquals(-0.5, CoordinateFormatter.parseSingle("-0.5", true)!!, 1e-9)
    }

    @Test
    fun `hemisphere letters set the sign`() {
        assertEquals(-33.5, CoordinateFormatter.parseSingle("33 30.0 S", true)!!, 1e-9)
        assertEquals(151.0, CoordinateFormatter.parseSingle("151 00.0 E", false)!!, 1e-9)
        assertEquals(-151.0, CoordinateFormatter.parseSingle("151 00.0 W", false)!!, 1e-9)
    }

    @Test
    fun `comma separated decimal pairs parse`() {
        val parsed = CoordinateFormatter.parsePair("-18.3, 147.7")
        assertEquals(-18.3, parsed!!.first, 1e-9)
        assertEquals(147.7, parsed.second, 1e-9)
    }

    @Test
    fun `out of range and unparseable input is rejected`() {
        assertNull(CoordinateFormatter.parseSingle("95", true))
        assertNull(CoordinateFormatter.parseSingle("no numbers", true))
        assertNull(CoordinateFormatter.parsePair(""))
        assertNull(CoordinateFormatter.parsePair("not coordinates"))
    }

    @Test
    fun `formatPair honours the requested mode`() {
        assertEquals(
            CoordinateFormatter.formatDd(-18.3, 147.7),
            CoordinateFormatter.formatPair(-18.3, 147.7, CoordinateFormatter.MODE_DD)
        )
        assertEquals(
            CoordinateFormatter.formatDms(-18.3, 147.7),
            CoordinateFormatter.formatPair(-18.3, 147.7, CoordinateFormatter.MODE_DMS)
        )
        assertEquals(
            CoordinateFormatter.formatDdm(-18.3, 147.7),
            CoordinateFormatter.formatPair(-18.3, 147.7, CoordinateFormatter.MODE_DDM)
        )
    }

    @Test
    fun `every declared mode has a label`() {
        assertEquals(CoordinateFormatter.MODES.size, CoordinateFormatter.MODE_LABELS.size)
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
