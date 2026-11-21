package com.seabathy.bathypicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The two projections the overseas sources come in, against PROJ's answers (pyproj 3.8). */
class ProjOverseasTest {

    private fun check(code: String, lon: Double, lat: Double, x: Double, y: Double) {
        val p = Proj.parse(code)
        assertNotNull(code, p)
        val o = DoubleArray(2)
        p!!.forward(lon, lat, o)
        assertEquals("$code x at $lon, $lat", x, o[0], 0.001)
        assertEquals("$code y at $lon, $lat", y, o[1], 0.001)
    }

    @Test
    fun `NZTM2000 (EPSG 2193) as PROJ has it`() {
        check("EPSG:2193", 174.14, -35.25, 1703711.8037, 6098636.7894)   // Bay of Islands
        check("EPSG:2193", 173.0, -41.0, 1600000.0, 5461242.9382)        // on the central meridian
        check("EPSG:2193", 166.5, -46.0, 1096728.1030, 4885373.7638)     // Fiordland, far west
        check("EPSG:2193", 178.5, -37.6, 2085688.0588, 5824316.1054)     // East Cape, far east
        check("EPSG:2193", 174.776, -41.2865, 1748718.8047, 5427916.8216)
    }

    @Test
    fun `PDC Mercator (EPSG 3832) as PROJ has it, across the 180th meridian`() {
        check("EPSG:3832", 177.125, -17.73, 3019541.1878, -1992964.0808)  // Mamanuca, Fiji
        check("EPSG:3832", -175.2, -21.1, 3873918.2796, -2388431.0483)    // Tonga, west longitude
        check("EPSG:3832", 150.0, 0.0, 0.0, 0.0)
        check("EPSG:3832", 179.999, -18.0, 3339473.4043, -2024351.4257)
        check("EPSG:3832", -179.999, -18.0, 3339696.0433, -2024351.4257)  // just over the line: no jump
        check("EPSG:3832", 134.5, 7.5, -1725452.1073, 831717.3558)        // Palau, west of 150 E
    }

    @Test
    fun `World Mercator is unchanged`() {
        val o = DoubleArray(2)
        Proj.parse("EPSG:3395")!!.forward(180.0, 0.0, o)
        assertEquals(6378137.0 * Math.PI, o[0], 1e-6)
    }

    @Test
    fun `a LINZ sheet name gives the tile's extent`() {
        val av29 = World.sheet("AV29")!!
        assertEquals(1684000.0, av29[0], 0.0)
        assertEquals(6090000.0, av29[1], 0.0)
        assertEquals(1708000.0, av29[2], 0.0)
        assertEquals(6126000.0, av29[3], 0.0)
        val au26 = World.sheet("AU26")!!
        assertEquals(1612000.0, au26[0], 0.0)
        assertEquals(6162000.0, au26[3], 0.0)
        // Letters skip I and O: BH is followed by BJ.
        assertEquals(World.sheet("BH30")!![1] - 36000, World.sheet("BJ30")!![1], 0.0)
        assertEquals(null, World.sheet("AI29"))
        assertEquals(null, World.sheet("collection"))
    }
}
