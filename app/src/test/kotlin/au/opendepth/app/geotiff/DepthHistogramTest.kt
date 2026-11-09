package au.opendepth.app.geotiff

import au.opendepth.app.map.OverlayLayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthHistogramTest {

    /** A shelf from -1 to -70 m, plus one deep hole at -890 m and an island at +154 m. */
    private fun shelfWithOutliers(): FloatArray {
        val shelf = FloatArray(10_000) { i -> -1f - 69f * i / 9_999f }
        return shelf + floatArrayOf(-890f, 154f, Float.NaN)
    }

    @Test
    fun `percentiles and shares follow the bulk of the depths`() {
        val h = DepthHistogram.of(shelfWithOutliers())!!
        assertEquals(-890.0, h.lowest, 1e-6)
        assertEquals(154.0, h.highest, 1e-6)
        assertEquals(-35.5, h.percentile(0.5), 1.5)
        assertEquals(0.5, h.fractionWithin(-1.0, -35.5), 0.03)
        assertEquals(1.0, h.fractionWithin(200.0, -1000.0), 1e-9)
        assertEquals(0.0, h.fractionWithin(-100.0, -800.0), 0.01)
    }

    @Test
    fun `fit ignores a lone deep hole and island`() {
        val values = shelfWithOutliers()
        val h = DepthHistogram.of(values)!!
        val (shallow, deep) = OverlayLayerController.fitRange(154.0, -890.0, h)
        assertTrue("shallow $shallow", shallow in -2.0..0.0)
        assertTrue("deep $deep", deep in -71.0..-67.0)
        // Without the spread it falls back to the extremes (water only: shallow end at 0).
        assertEquals(0.0 to -890.0, OverlayLayerController.fitRange(154.0, -890.0))
    }

    @Test
    fun `no data gives no histogram`() {
        assertNull(DepthHistogram.of(floatArrayOf(Float.NaN, Float.NaN)))
        assertNull(DepthHistogram.of(floatArrayOf(-5f, -5f)))
    }
}
