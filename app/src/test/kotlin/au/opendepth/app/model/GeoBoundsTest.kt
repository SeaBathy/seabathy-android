package au.opendepth.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoBoundsTest {

    private val reef = GeoBounds(west = 147.0, south = -19.0, east = 148.0, north = -18.0)

    @Test
    fun `a well formed box is valid`() {
        assertTrue(reef.isValid)
    }

    @Test
    fun `inverted or degenerate boxes are rejected`() {
        assertFalse(GeoBounds(148.0, -19.0, 147.0, -18.0).isValid)
        assertFalse(GeoBounds(147.0, -18.0, 148.0, -19.0).isValid)
        assertFalse(GeoBounds(147.0, -18.0, 147.0, -18.0).isValid)
    }

    @Test
    fun `boxes outside the world are rejected`() {
        assertFalse(GeoBounds(-181.0, -19.0, 148.0, -18.0).isValid)
        assertFalse(GeoBounds(147.0, -19.0, 181.0, -18.0).isValid)
        assertFalse(GeoBounds(147.0, -91.0, 148.0, -18.0).isValid)
        assertFalse(GeoBounds(147.0, -19.0, 148.0, 91.0).isValid)
    }

    @Test
    fun `containment includes the edges and excludes the outside`() {
        assertTrue(reef.contains(-18.5, 147.5))
        assertTrue(reef.contains(-18.0, 147.0))
        assertTrue(reef.contains(-19.0, 148.0))
        assertFalse(reef.contains(-17.9, 147.5))
        assertFalse(reef.contains(-18.5, 146.9))
    }
}
