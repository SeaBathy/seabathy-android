package au.opendepth.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeabedDownloadTest {

    @Test
    fun `a small area on screen is asked for as it is`() {
        val a = SeabedDownload.areaFor(-20.2, 149.4, -19.9, 149.7, -20.05, 149.55)
        assertEquals(-20.2, a.south, 1e-9)
        assertEquals(149.7, a.east, 1e-9)
        assertFalse(a.trimmed)
        assertTrue(a.name, "20.05S" in a.name && "149.55E" in a.name)
    }

    @Test
    fun `zoomed far out asks for the middle only`() {
        val a = SeabedDownload.areaFor(-40.0, 120.0, 0.0, 170.0, -20.0, 149.0)
        assertTrue(a.trimmed)
        assertEquals(4.0, a.east - a.west, 1e-9)
        assertEquals(149.0, (a.east + a.west) / 2, 1e-9)
    }

    @Test
    fun `the date line on screen keeps the crosshair's side`() {
        val a = SeabedDownload.areaFor(-20.0, 179.0, -18.0, -179.0, -19.0, 179.5)
        assertEquals(180.0, a.east, 1e-9)
        assertTrue(a.west < 180.0)
        val b = SeabedDownload.areaFor(-20.0, 179.0, -18.0, -179.0, -19.0, -179.5)
        assertEquals(-180.0, b.west, 1e-9)
    }

    @Test
    fun `the link asks for a GeoTIFF of that area`() {
        val url = SeabedDownload.url(SeabedDownload.Area(28.0, -88.5, 28.5, -88.0, false))
        assertTrue(url, "west=-88.50000" in url && "north=28.50000" in url && "format=geotiff" in url)
    }
}
