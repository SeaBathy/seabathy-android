package au.opendepth.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerUsesTest {

    @Test
    fun `a second tap on the same tool is ignored while the first is under way`() {
        val uses = LayerUses()
        assertTrue(uses.begin("x", LayerUse.COLOURS))
        assertFalse(uses.begin("x", LayerUse.COLOURS))
        // Once it is done the tool can be used again.
        assertTrue(uses.end("x", LayerUse.COLOURS))
        assertTrue(uses.begin("x", LayerUse.COLOURS))
    }

    @Test
    fun `different tools, and different layers, do not block each other`() {
        val uses = LayerUses()
        assertTrue(uses.begin("x", LayerUse.GO_TO))
        assertTrue(uses.begin("x", LayerUse.COLOURS))
        assertTrue(uses.begin("y", LayerUse.GO_TO))
        assertTrue(uses.isUnderWay("x", LayerUse.COLOURS))
        assertFalse(uses.isUnderWay("y", LayerUse.COLOURS))
    }

    @Test
    fun `the layer is let go of only when its last use is done`() {
        val uses = LayerUses()
        // The name tapped, then COLOURS, while the hidden layer opens.
        uses.begin("x", LayerUse.GO_TO)
        uses.begin("x", LayerUse.COLOURS)
        // The go-to finishes first: the colours still need the layer open.
        assertFalse(uses.end("x", LayerUse.GO_TO))
        assertTrue(uses.inUse("x"))
        // The colours sheet closed: now it can be closed.
        assertTrue(uses.end("x", LayerUse.COLOURS))
        assertFalse(uses.inUse("x"))
    }

    @Test
    fun `one layer's use ending does not let go of another`() {
        val uses = LayerUses()
        uses.begin("x", LayerUse.VIEW_3D)
        uses.begin("y", LayerUse.GO_TO)
        assertTrue(uses.end("y", LayerUse.GO_TO))
        assertTrue(uses.inUse("x"))
    }

    @Test
    fun `ending a use twice, or one never begun, changes nothing`() {
        val uses = LayerUses()
        assertFalse(uses.end("x", LayerUse.VIEW_3D))
        uses.begin("x", LayerUse.VIEW_3D)
        uses.begin("x", LayerUse.GO_TO)
        assertFalse(uses.end("x", LayerUse.VIEW_3D))
        // The 3D view's second "done" must not count as the go-to's.
        assertFalse(uses.end("x", LayerUse.VIEW_3D))
        assertTrue(uses.inUse("x"))
        assertTrue(uses.end("x", LayerUse.GO_TO))
    }

    @Test
    fun `a removed layer's uses are forgotten`() {
        val uses = LayerUses()
        uses.begin("x", LayerUse.COLOURS)
        uses.forget("x")
        assertFalse(uses.inUse("x"))
        // The sheet closing afterwards finds nothing to give back.
        assertFalse(uses.end("x", LayerUse.COLOURS))
    }

    @Test
    fun `clearing forgets every layer`() {
        val uses = LayerUses()
        uses.begin("x", LayerUse.COLOURS)
        uses.begin("y", LayerUse.VIEW_3D)
        uses.clear()
        assertFalse(uses.inUse("x"))
        assertFalse(uses.inUse("y"))
        assertTrue(uses.begin("x", LayerUse.COLOURS))
    }
}
