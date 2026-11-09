package au.opendepth.app.gdal

import au.opendepth.app.geotiff.MapProjection
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [GdalDepthImporter.resolveProjection]'s WKT-to-[MapProjection]
 * heuristic in isolation from GDAL itself: only [Regex] and this app's own
 * [MapProjection] are touched, so this much of the GDAL import path is
 * exercised on the plain JVM, unlike everything around it that actually
 * calls into native GDAL.
 *
 * The WKT fixtures below are trimmed to the parts this heuristic reads
 * (nested AUTHORITY clauses and the outer CRS keyword) rather than full
 * real-world GDAL output.
 */
class GdalDepthImporterWktTest {

    @Test
    fun `a plain geographic WKT resolves to WGS84`() {
        val wkt = """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],""" +
            """PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433],AUTHORITY["EPSG","4326"]]"""

        assertTrue(GdalDepthImporter.resolveProjection(wkt) is MapProjection.Geographic)
    }

    @Test
    fun `a projected WKT resolves using the outermost EPSG code, not the base GEOGCS's`() {
        val wkt = """PROJCS["WGS 84 / UTM zone 55S",""" +
            """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],""" +
            """AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0],""" +
            """UNIT["degree",0.0174532925199433],AUTHORITY["EPSG","4326"]],""" +
            """PROJECTION["Transverse_Mercator"],UNIT["metre",1],AUTHORITY["EPSG","32755"]]"""

        val projection = GdalDepthImporter.resolveProjection(wkt)
        assertTrue(projection is MapProjection.Utm)
        assertTrue((projection as MapProjection.Utm).zone == 55 && !projection.northernHemisphere)
    }

    @Test
    fun `Web Mercator EPSG 3857 resolves`() {
        val wkt = """PROJCS["WGS 84 / Pseudo-Mercator",""" +
            """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],""" +
            """AUTHORITY["EPSG","4326"]],PROJECTION["Mercator_1SP"],""" +
            """UNIT["metre",1],AUTHORITY["EPSG","3857"]]"""

        assertTrue(GdalDepthImporter.resolveProjection(wkt) is MapProjection.WebMercator)
    }

    @Test
    fun `an unsupported projected EPSG code resolves to null`() {
        val wkt = """PROJCS["Some other CRS",GEOGCS["WGS 84",AUTHORITY["EPSG","4326"]],""" +
            """AUTHORITY["EPSG","9999999"]]"""

        assertNull(GdalDepthImporter.resolveProjection(wkt))
    }

    @Test
    fun `blank or null WKT resolves to null`() {
        assertNull(GdalDepthImporter.resolveProjection(null))
        assertNull(GdalDepthImporter.resolveProjection("  "))
        assertNull(GdalDepthImporter.resolveProjection(""))
    }

    @Test
    fun `WKT with no EPSG authority at all resolves to null`() {
        val wkt = """LOCAL_CS["Non-Earth (Metre)",LOCAL_DATUM["Local Datum",0],UNIT["metre",1]]"""

        assertNull(GdalDepthImporter.resolveProjection(wkt))
    }
}
