package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowGridTest {
    private val out = DoubleArray(2)

    @Test
    fun uniformFlowReadsBackExactly() {
        val grid = FlowGrid.build(-21.0, 148.0, -20.0, 149.0, 32) { _, _, o -> o[0] = 2.0; o[1] = -1.0; true }
        assertTrue(grid.at(-20.37, 148.61, out))
        assertEquals(2.0, out[0], 1e-6)
        assertEquals(-1.0, out[1], 1e-6)
    }

    @Test
    fun landAndOutsideHaveNoFlow() {
        // West half land.
        val grid = FlowGrid.build(-21.0, 148.0, -20.0, 149.0, 32) { _, lng, o -> if (lng < 148.5) false else { o[0] = 1.0; o[1] = 0.0; true } }
        assertFalse(grid.at(-20.5, 148.2, out))
        assertFalse(grid.at(-19.0, 148.7, out))
        assertTrue(grid.at(-20.5, 148.8, out))
    }

    @Test
    fun theCoastDoesNotWeakenTheFlow() {
        val grid = FlowGrid.build(-21.0, 148.0, -20.0, 149.0, 32) { _, lng, o -> if (lng < 148.5) false else { o[0] = 3.0; o[1] = 0.0; true } }
        // Just seaward of the coast line, part of the cell is land: the flow stays full strength.
        assertTrue(grid.at(-20.5, 148.505, out))
        assertEquals(3.0, out[0], 1e-6)
    }
}
