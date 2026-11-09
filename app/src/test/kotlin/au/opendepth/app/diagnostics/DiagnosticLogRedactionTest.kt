package au.opendepth.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticLogRedactionTest {

    @Test
    fun `query strings with api keys are removed`() {
        val line = "restore: XYZ:Topo(source=https://api.maptiler.com/maps/topo/{z}/{x}/{y}.png?key=SECRET123)"
        val redacted = DiagnosticLog.redactUrls(line)
        assertFalse(redacted.contains("SECRET123"))
        assertEquals("restore: XYZ:Topo(source=https://api.maptiler.com/maps/topo/{z}/{x}/{y}.png?…)", redacted)
    }

    @Test
    fun `credentials in the authority are removed`() {
        val redacted = DiagnosticLog.redactUrls("failed: http://user:pa55@tiles.example.org/1/2/3.png")
        assertEquals("failed: http://tiles.example.org/1/2/3.png", redacted)
    }

    @Test
    fun `several urls in one native message are all redacted`() {
        val redacted = DiagnosticLog.redactUrls(
            "native W [mbgl] Failed to load https://a.example/t.png?access_token=AAA and https://b.example/s.json?k=BBB"
        )
        assertFalse(redacted.contains("AAA"))
        assertFalse(redacted.contains("BBB"))
    }

    @Test
    fun `lines without urls and plain paths are unchanged`() {
        val path = "restore MBTiles Reef: path=/storage/emulated/0/SeaBathy/OfflineDownloads/reef_-18.3_147.7.mbtiles"
        assertEquals(path, DiagnosticLog.redactUrls(path))
        assertEquals("tile url http://127.0.0.1:41234/gdal/layer_1/3/4/5.png", DiagnosticLog.redactUrls("tile url http://127.0.0.1:41234/gdal/layer_1/3/4/5.png"))
    }
}
