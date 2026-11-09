package au.opendepth.app.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [parseTilePath] in isolation from NanoHTTPD itself -- the only
 * part of [LocalTileServer] pure enough to run on the plain JVM, unlike
 * everything that actually binds a socket or touches SQLite/GDAL.
 */
class LocalTileServerRoutingTest {

    @Test
    fun `a well-formed gdal tile path parses`() {
        val request = parseTilePath("/gdal/layer-1/12/34/56.png")!!

        assertEquals(LocalTileServer.Kind.GDAL, request.kind)
        assertEquals("layer-1", request.layerId)
        assertEquals(12, request.z)
        assertEquals(34, request.x)
        assertEquals(56, request.y)
    }

    @Test
    fun `a well-formed mbtiles tile path parses`() {
        val request = parseTilePath("mbtiles/abc/3/2/1.png")!!

        assertEquals(LocalTileServer.Kind.MBTILES, request.kind)
        assertEquals("abc", request.layerId)
        assertEquals(3, request.z)
        assertEquals(2, request.x)
        assertEquals(1, request.y)
    }

    @Test
    fun `the final segment's extension is cosmetic -- pbf, mvt or none all parse the same y`() {
        assertEquals(7, parseTilePath("/mbtiles/vector-layer/5/6/7.pbf")!!.y)
        assertEquals(7, parseTilePath("/mbtiles/vector-layer/5/6/7.mvt")!!.y)
        assertEquals(7, parseTilePath("/mbtiles/vector-layer/5/6/7")!!.y)
    }

    @Test
    fun `an unknown route prefix is rejected`() {
        assertNull(parseTilePath("/favicon.ico"))
        assertNull(parseTilePath("/other/layer/1/2/3.png"))
    }

    @Test
    fun `missing or malformed segments are rejected`() {
        assertNull(parseTilePath("/gdal/layer/1/2"))
        assertNull(parseTilePath("/gdal/layer/1/2/3/4.png"))
        assertNull(parseTilePath("/gdal//1/2/3.png"))
        assertNull(parseTilePath("/gdal/layer/z/2/3.png"))
        assertNull(parseTilePath("/gdal/layer/1/x/3.png"))
        assertNull(parseTilePath("/gdal/layer/1/2/y.png"))
    }

    @Test
    fun `out-of-range zoom levels are rejected`() {
        assertNull(parseTilePath("/mbtiles/abc/-1/0/0.png"))
        assertNull(parseTilePath("/mbtiles/abc/40/0/0.png"))
    }
}
