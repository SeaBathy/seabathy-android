package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapProjectionTest {

    @Test
    fun `geographic projection is the identity on latitude and longitude`() {
        val forward = MapProjection.Geographic.forward(-18.3, 147.7)
        assertEquals(147.7, forward[0], 0.0)
        assertEquals(-18.3, forward[1], 0.0)

        val inverse = MapProjection.Geographic.inverse(147.7, -18.3)!!
        assertEquals(-18.3, inverse[0], 0.0)
        assertEquals(147.7, inverse[1], 0.0)
    }

    @Test
    fun `geographic projection rejects impossible coordinates`() {
        assertNull(MapProjection.Geographic.inverse(200.0, 0.0))
        assertNull(MapProjection.Geographic.inverse(0.0, 100.0))
    }

    @Test
    fun `web mercator origin is the equator on the prime meridian`() {
        val forward = MapProjection.WebMercator.forward(0.0, 0.0)
        assertEquals(0.0, forward[0], 1e-9)
        assertEquals(0.0, forward[1], 1e-9)
    }

    @Test
    fun `web mercator x is proportional to longitude`() {
        val forward = MapProjection.WebMercator.forward(0.0, 180.0)
        assertEquals(Math.PI * MapProjection.EARTH_RADIUS, forward[0], 1e-3)
    }

    @Test
    fun `web mercator round trips`() {
        assertRoundTrip(MapProjection.WebMercator, -18.3, 147.7, 1e-9)
        assertRoundTrip(MapProjection.WebMercator, 51.5, -0.12, 1e-9)
    }

    @Test
    fun `utm central meridian maps to the false easting`() {
        // Zone 13N has a central meridian of 105 degrees west.
        val forward = MapProjection.Utm(13, northernHemisphere = true)
            .forward(40.0, -105.0)
        assertEquals(500000.0, forward[0], 0.001)
    }

    @Test
    fun `utm southern hemisphere applies the false northing`() {
        val forward = MapProjection.Utm(55, northernHemisphere = false)
            .forward(-18.3, 147.7)
        // A southern point sits below the 10 000 km false northing.
        assertTrue(forward[1] in 7_900_000.0..8_100_000.0)
        assertTrue(forward[0] in 100_000.0..900_000.0)
    }

    @Test
    fun `utm round trips to sub millimetre accuracy`() {
        assertRoundTrip(MapProjection.Utm(55, false), -18.3, 147.7, 1e-8)
        assertRoundTrip(MapProjection.Utm(56, false), -33.86, 151.21, 1e-8)
        assertRoundTrip(MapProjection.Utm(30, true), 51.5, -0.12, 1e-8)
        assertRoundTrip(MapProjection.Utm(13, true), 40.0, -105.0, 1e-8)
    }

    @Test
    fun `supported projected CRS codes resolve`() {
        assertTrue(MapProjection.fromProjectedCode(3857) is MapProjection.WebMercator)

        val northern = MapProjection.fromProjectedCode(32655) as MapProjection.Utm
        assertEquals(55, northern.zone)
        assertTrue(northern.northernHemisphere)

        val southern = MapProjection.fromProjectedCode(32755) as MapProjection.Utm
        assertEquals(55, southern.zone)
        assertTrue(!southern.northernHemisphere)
    }

    @Test
    fun `unsupported projected CRS codes are reported as unknown`() {
        assertNull(MapProjection.fromProjectedCode(3577))
        assertNull(MapProjection.fromProjectedCode(0))
    }

    @Test
    fun `supported geographic CRS codes resolve`() {
        assertNotNull(MapProjection.fromGeographicCode(4326))
        assertNotNull(MapProjection.fromGeographicCode(0))
        assertNull(MapProjection.fromGeographicCode(4277))
    }

    private fun assertRoundTrip(
        projection: MapProjection,
        lat: Double,
        lng: Double,
        tolerance: Double
    ) {
        val forward = projection.forward(lat, lng)
        val inverse = projection.inverse(forward[0], forward[1])
        assertNotNull("${projection.label} could not invert ($lat, $lng)", inverse)
        assertTrue(
            "${projection.label} latitude drifted by ${abs(inverse!![0] - lat)}",
            abs(inverse[0] - lat) < tolerance
        )
        assertTrue(
            "${projection.label} longitude drifted by ${abs(inverse[1] - lng)}",
            abs(inverse[1] - lng) < tolerance
        )
    }

    @Test
    fun `GDA94 and GDA2020 MGA zones resolve to UTM south`() {
        val gda94 = MapProjection.fromProjectedCode(28356) as MapProjection.Utm
        val gda2020 = MapProjection.fromProjectedCode(7856) as MapProjection.Utm
        assertEquals(56, gda94.zone)
        assertEquals(56, gda2020.zone)
        assertFalse(gda94.northernHemisphere)
        assertEquals(MapProjection.Geographic, MapProjection.fromGeographicCode(7844))
    }
}
