package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliefShadingTest {

    private fun grid(cols: Int, rows: Int, depth: (c: Int, r: Int) -> Float) =
        FloatArray(cols * rows) { i -> depth(i % cols, i / cols) }

    @Test
    fun `a flat seabed is evenly lit`() {
        val shade = ReliefShading.shade(grid(5, 5) { _, _ -> -20f }, 5, 5, 10.0, 10.0, 0.6)!!
        assertTrue(shade.all { kotlin.math.abs(it - shade[0]) < 1e-6 })
    }

    @Test
    fun `slopes facing the north-west light are brighter than those facing away`() {
        // A ridge running north-south: its west face looks towards the light, its east face away.
        val values = grid(9, 5) { c, _ -> -20f + 6f - kotlin.math.abs(c - 4) * 1.5f }
        val shade = ReliefShading.shade(values, 9, 5, 10.0, 10.0, 0.6)!!
        val westFace = shade[2 * 9 + 2]
        val eastFace = shade[2 * 9 + 6]
        assertTrue("west=$westFace east=$eastFace", westFace > eastFace)
    }

    @Test
    fun `a stronger setting gives more contrast`() {
        val values = grid(9, 5) { c, _ -> -20f - c * 0.5f }
        val weak = ReliefShading.shade(values, 9, 5, 10.0, 10.0, 0.2)!![2 * 9 + 4]
        val strong = ReliefShading.shade(values, 9, 5, 10.0, 10.0, 2.0)!![2 * 9 + 4]
        val flat = ReliefShading.shade(grid(9, 5) { _, _ -> -20f }, 9, 5, 10.0, 10.0, 2.0)!![0]
        assertTrue(kotlin.math.abs(strong - flat) > kotlin.math.abs(weak - flat))
    }

    @Test
    fun `no-data cells stay unshaded and zero strength turns shading off`() {
        val values = grid(4, 4) { c, r -> if (c == 1 && r == 1) Float.NaN else -10f - c }
        val shade = ReliefShading.shade(values, 4, 4, 10.0, 10.0, 1.0)!!
        assertEquals(1f, shade[1 * 4 + 1], 0f)
        assertNull(ReliefShading.shade(values, 4, 4, 10.0, 10.0, 0.0))
    }
}
