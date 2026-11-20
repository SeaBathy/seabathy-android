package au.opendepth.app.map

import au.opendepth.app.model.DepthRampSettings
import au.opendepth.app.model.LayerKind
import au.opendepth.app.model.OverlayLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStackTest {

    private val stack = listOf("bottom", "low", "middle", "high", "top")

    @Test
    fun `a layer goes under the next one above it that is on the map`() {
        val onMap = setOf("bottom", "high", "top")
        assertEquals("high", LayerStack.firstOnMapAbove(stack, "middle") { it in onMap })
        assertEquals("high", LayerStack.firstOnMapAbove(stack, "low") { it in onMap })
        assertEquals("top", LayerStack.firstOnMapAbove(stack, "high") { it in onMap })
    }

    @Test
    fun `hidden layers above it are passed over`() {
        // "middle" and "high" are hidden, so are not on the map.
        val onMap = setOf("bottom", "low", "top")
        assertEquals("top", LayerStack.firstOnMapAbove(stack, "low") { it in onMap })
        assertEquals("low", LayerStack.firstOnMapAbove(stack, "bottom") { it in onMap })
    }

    @Test
    fun `with none above it on the map it goes on top`() {
        assertNull(LayerStack.firstOnMapAbove(stack, "top") { true })
        assertNull(LayerStack.firstOnMapAbove(stack, "middle") { it == "bottom" || it == "low" })
        assertNull(LayerStack.firstOnMapAbove(stack, "middle") { false })
    }

    @Test
    fun `itself and the layers below never count`() {
        // It may still be on the map from before it is put back.
        assertNull(LayerStack.firstOnMapAbove(stack, "top") { it == "top" })
        assertEquals("high", LayerStack.firstOnMapAbove(stack, "middle") { it != "top" })
    }

    @Test
    fun `a layer that is not in the stack has no place`() {
        assertNull(LayerStack.firstOnMapAbove(stack, "gone") { true })
        assertNull(LayerStack.firstOnMapAbove(emptyList(), "any") { true })
    }

    @Test
    fun `showing hidden layers one by one, in any order, rebuilds the stack in order`() {
        listOf(
            listOf("middle", "top", "bottom", "high", "low"),
            listOf("top", "high", "middle", "low", "bottom"),
            listOf("bottom", "low", "middle", "high", "top")
        ).forEach { order ->
            // The map's own draw order, bottom first, as each layer is put under the one found for it.
            val drawn = mutableListOf<String>()
            order.forEach { id ->
                val above = LayerStack.firstOnMapAbove(stack, id) { it in drawn }
                if (above == null) drawn.add(id) else drawn.add(drawn.indexOf(above), id)
            }
            assertEquals("shown in the order $order", stack, drawn)
        }
    }

    private fun layer(kind: LayerKind, gdal: Boolean = false, ramp: DepthRampSettings? = null) =
        OverlayLayer(id = "layer_1", kind = kind, name = "A layer", source = "/somewhere", gdalOnDemand = gdal, ramp = ramp)

    @Test
    fun `only a GeoTIFF's file is closed while it is hidden`() {
        assertTrue(LayerStack.closesWhenHidden(layer(LayerKind.GEOTIFF)))
        assertTrue(LayerStack.closesWhenHidden(layer(LayerKind.GEOTIFF, gdal = true)))
        // A pack stays open for the 3D view; an online layer has no file.
        assertFalse(LayerStack.closesWhenHidden(layer(LayerKind.MBTILES)))
        assertFalse(LayerStack.closesWhenHidden(layer(LayerKind.XYZ)))
    }

    @Test
    fun `a depth layer is told from what is saved with it`() {
        assertTrue(LayerStack.holdsDepths(layer(LayerKind.GEOTIFF, gdal = true)))
        assertTrue(LayerStack.holdsDepths(layer(LayerKind.GEOTIFF, ramp = DepthRampSettings())))
        // A picture GeoTIFF has no colours saved with it.
        assertFalse(LayerStack.holdsDepths(layer(LayerKind.GEOTIFF)))
        assertFalse(LayerStack.holdsDepths(layer(LayerKind.MBTILES, ramp = DepthRampSettings())))
        assertFalse(LayerStack.holdsDepths(layer(LayerKind.XYZ)))
    }
}
