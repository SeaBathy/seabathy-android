package au.opendepth.app.map

import au.opendepth.app.nav.DistanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {

    private fun label(spanMetres: Double, unit: DistanceUnit) = ChartScale.pick(spanMetres, unit)?.label

    @Test
    fun `round numbers are 1, 2 or 5 times a power of ten, never more than asked`() {
        assertEquals("1", ChartScale.roundDown(1.0)!!.text)
        assertEquals("1", ChartScale.roundDown(1.99)!!.text)
        assertEquals("2", ChartScale.roundDown(2.0)!!.text)
        assertEquals("2", ChartScale.roundDown(4.99)!!.text)
        assertEquals("5", ChartScale.roundDown(5.0)!!.text)
        assertEquals("5", ChartScale.roundDown(9.99)!!.text)
        assertEquals("10", ChartScale.roundDown(10.0)!!.text)
        assertEquals("2000", ChartScale.roundDown(4321.0)!!.text)
        assertEquals("0.5", ChartScale.roundDown(0.73)!!.text)
        assertEquals("0.2", ChartScale.roundDown(0.2)!!.text)
        assertEquals("0.1", ChartScale.roundDown(0.1)!!.text)
        assertEquals("0.05", ChartScale.roundDown(0.0999)!!.text)
        assertEquals("0.002", ChartScale.roundDown(0.0021)!!.text)
        assertEquals(2000.0, ChartScale.roundDown(4321.0)!!.value, 1e-9)
        assertEquals(0.05, ChartScale.roundDown(0.0999)!!.value, 1e-12)
    }

    @Test
    fun `whole powers of ten come out as themselves`() {
        for (exponent in -6..9) {
            val power = Math.pow(10.0, exponent.toDouble())
            assertEquals("10^$exponent", power, ChartScale.roundDown(power)!!.value, power * 1e-9)
        }
    }

    @Test
    fun `nothing to round gives nothing`() {
        assertNull(ChartScale.roundDown(0.0))
        assertNull(ChartScale.roundDown(-3.0))
        assertNull(ChartScale.roundDown(Double.NaN))
        assertNull(ChartScale.roundDown(Double.POSITIVE_INFINITY))
        assertNull(ChartScale.pick(0.0, DistanceUnit.NAUTICAL_MILES))
        assertNull(ChartScale.pick(-10.0, DistanceUnit.KILOMETRES))
        assertNull(ChartScale.pick(Double.NaN, DistanceUnit.STATUTE_MILES))
    }

    @Test
    fun `nautical miles down to a tenth, then metres`() {
        assertEquals("10 nm", label(20_000.0, DistanceUnit.NAUTICAL_MILES))
        assertEquals("1 nm", label(3_000.0, DistanceUnit.NAUTICAL_MILES))
        assertEquals("0.5 nm", label(1_000.0, DistanceUnit.NAUTICAL_MILES))
        assertEquals("0.2 nm", label(500.0, DistanceUnit.NAUTICAL_MILES))
        assertEquals("0.1 nm", label(200.0, DistanceUnit.NAUTICAL_MILES))
        // 150 m is 0.08 nm: under a tenth, so metres.
        assertEquals("100 m", label(150.0, DistanceUnit.NAUTICAL_MILES))
        assertEquals("20 m", label(37.0, DistanceUnit.NAUTICAL_MILES))
        assertEquals("2 m", label(2.4, DistanceUnit.NAUTICAL_MILES))
    }

    @Test
    fun `kilometres down to one, then metres`() {
        assertEquals("500 km", label(900_000.0, DistanceUnit.KILOMETRES))
        assertEquals("2 km", label(2_500.0, DistanceUnit.KILOMETRES))
        assertEquals("1 km", label(1_500.0, DistanceUnit.KILOMETRES))
        assertEquals("500 m", label(900.0, DistanceUnit.KILOMETRES))
        assertEquals("5 m", label(7.0, DistanceUnit.KILOMETRES))
    }

    @Test
    fun `miles down to a tenth, then feet`() {
        assertEquals("1 mi", label(2_000.0, DistanceUnit.STATUTE_MILES))
        assertEquals("0.2 mi", label(402.0, DistanceUnit.STATUTE_MILES))
        // 145 m is 0.09 mi, 475 ft.
        assertEquals("200 ft", label(145.0, DistanceUnit.STATUTE_MILES))
        assertEquals("10 ft", label(4.0, DistanceUnit.STATUTE_MILES))
    }

    @Test
    fun `the bar stands for the distance on its label`() {
        assertEquals(0.2 * 1852.0, ChartScale.pick(500.0, DistanceUnit.NAUTICAL_MILES)!!.metres, 1e-6)
        assertEquals(100.0, ChartScale.pick(150.0, DistanceUnit.NAUTICAL_MILES)!!.metres, 1e-6)
        assertEquals(2000.0, ChartScale.pick(2_500.0, DistanceUnit.KILOMETRES)!!.metres, 1e-6)
        assertEquals(200 * 0.3048, ChartScale.pick(145.0, DistanceUnit.STATUTE_MILES)!!.metres, 1e-6)
        assertEquals(1609.344, ChartScale.pick(2_000.0, DistanceUnit.STATUTE_MILES)!!.metres, 1e-6)
    }

    @Test
    fun `at every zoom the bar fits the stretch measured and is not a stub`() {
        for (unit in DistanceUnit.entries) {
            var span = 0.4
            while (span < 3.0e7) {
                val bar = ChartScale.pick(span, unit)
                assertNotNull("$unit $span", bar)
                val share = bar!!.metres / span
                assertTrue("$unit $span m: ${bar.label} is longer than the stretch", share <= 1.0 + 1e-9)
                // Stepping down a unit can leave a little less than the 40% a 1-2-5 step alone would.
                assertTrue("$unit $span m: ${bar.label} is only ${share * 100}% of the stretch", share >= 0.3)
                span *= 1.07
            }
        }
    }

    @Test
    fun `the label is a round number and a unit`() {
        val shape = Regex("""(1|2|5)0*|0\.0*(1|2|5)""")
        for (unit in DistanceUnit.entries) {
            var span = 0.4
            while (span < 3.0e7) {
                val (number, name) = ChartScale.pick(span, unit)!!.label.split(' ')
                assertTrue("$unit $span m: $number", shape.matches(number))
                assertTrue(name in setOf("nm", "km", "mi", "m", "ft"))
                span *= 1.13
            }
        }
    }
}
