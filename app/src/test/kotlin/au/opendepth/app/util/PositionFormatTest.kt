package au.opendepth.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** How positions are written everywhere, by the one setting [CoordinateFormatter.mode]. */
class PositionFormatTest {

    @After
    fun backToTheDefault() {
        CoordinateFormatter.mode = CoordinateFormatter.MODE_DDM
    }

    @Test
    fun `positions are written in degrees and decimal minutes until another format is chosen`() {
        assertEquals(CoordinateFormatter.MODE_DDM, CoordinateFormatter.mode)
        assertEquals(CoordinateFormatter.formatDdm(-18.3, 147.7), CoordinateFormatter.format(-18.3, 147.7))
        assertEquals("18°18.000'S 147°42.000'E", CoordinateFormatter.format(-18.3, 147.7))
    }

    @Test
    fun `the chosen format is the one written`() {
        CoordinateFormatter.mode = CoordinateFormatter.MODE_DMS
        assertEquals("18°18'00.00\"S 147°42'00.00\"E", CoordinateFormatter.format(-18.3, 147.7))
        CoordinateFormatter.mode = CoordinateFormatter.MODE_DD
        assertEquals("-18.300000, 147.700000", CoordinateFormatter.format(-18.3, 147.7))
        CoordinateFormatter.mode = CoordinateFormatter.MODE_DDM
        assertEquals("18°18.000'S 147°42.000'E", CoordinateFormatter.format(-18.3, 147.7))
    }

    @Test
    fun `anything that is not a format reads back as the default`() {
        CoordinateFormatter.mode = CoordinateFormatter.MODE_DD
        CoordinateFormatter.mode = "furlongs"
        assertEquals(CoordinateFormatter.MODE_DDM, CoordinateFormatter.mode)
        CoordinateFormatter.mode = ""
        assertEquals(CoordinateFormatter.MODE_DDM, CoordinateFormatter.mode)
    }

    @Test
    fun `whatever is written reads back as the same position`() {
        for (mode in CoordinateFormatter.MODES) {
            CoordinateFormatter.mode = mode
            val read = CoordinateFormatter.parsePair(CoordinateFormatter.format(-33.856789, 151.215296))!!
            // To the last figure written: a thousandth of a minute is the coarsest.
            assertEquals(mode, -33.856789, read.first, 2e-5)
            assertEquals(mode, 151.215296, read.second, 2e-5)
        }
    }

    @Test
    fun `one edge of an area is written in the chosen format too`() {
        assertEquals("18°18.000'S", CoordinateFormatter.formatLatitude(-18.3))
        assertEquals("147°42.000'E", CoordinateFormatter.formatLongitude(147.7))
        assertEquals("122°30.000'W", CoordinateFormatter.formatLongitude(-122.5))

        CoordinateFormatter.mode = CoordinateFormatter.MODE_DMS
        assertEquals("18°18'00.00\"S", CoordinateFormatter.formatLatitude(-18.3))
        assertEquals("147°42'00.00\"E", CoordinateFormatter.formatLongitude(147.7))

        CoordinateFormatter.mode = CoordinateFormatter.MODE_DD
        assertEquals("-18.300000", CoordinateFormatter.formatLatitude(-18.3))
        assertEquals("147.700000", CoordinateFormatter.formatLongitude(147.7))
    }

    @Test
    fun `the two edges together are the position`() {
        for (mode in listOf(CoordinateFormatter.MODE_DDM, CoordinateFormatter.MODE_DMS)) {
            CoordinateFormatter.mode = mode
            assertEquals(
                CoordinateFormatter.format(-18.3, 147.7),
                "${CoordinateFormatter.formatLatitude(-18.3)} ${CoordinateFormatter.formatLongitude(147.7)}"
            )
        }
    }

    @Test
    fun `each format has its name`() {
        assertEquals("Marine — degrees and decimal minutes", CoordinateFormatter.labelFor(CoordinateFormatter.MODE_DDM))
        assertEquals("Decimal degrees", CoordinateFormatter.labelFor(CoordinateFormatter.MODE_DD))
        assertEquals("Degrees, minutes and seconds", CoordinateFormatter.labelFor(CoordinateFormatter.MODE_DMS))
        assertEquals(CoordinateFormatter.labelFor(CoordinateFormatter.MODE_DDM), CoordinateFormatter.labelFor("furlongs"))
    }

    @Test
    fun `the choices are the three formats, each showing a position written in it`() {
        val choices = CoordinateFormatter.choiceLabels(-18.3, 147.7)
        assertEquals(CoordinateFormatter.MODES.size, choices.size)
        assertEquals("Marine — degrees and decimal minutes\n18°18.000'S 147°42.000'E", choices[0])
        assertEquals("Decimal degrees\n-18.300000, 147.700000", choices[1])
        assertEquals("Degrees, minutes and seconds\n18°18'00.00\"S 147°42'00.00\"E", choices[2])
    }

    @Test
    fun `the choices do not depend on the format in use`() {
        val before = CoordinateFormatter.choiceLabels(-18.3, 147.7)
        CoordinateFormatter.mode = CoordinateFormatter.MODE_DD
        assertEquals(before, CoordinateFormatter.choiceLabels(-18.3, 147.7))
    }

    @Test
    fun `with no position to show, the choices are just the names`() {
        assertEquals(CoordinateFormatter.MODE_LABELS, CoordinateFormatter.choiceLabels(null, null))
        assertEquals(CoordinateFormatter.MODE_LABELS, CoordinateFormatter.choiceLabels(Double.NaN, 147.7))
    }
}
