package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTiffImportRouterTest {

    private fun header(byteOrderMarker: Char, version: Int): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = byteOrderMarker.code.toByte()
        bytes[1] = byteOrderMarker.code.toByte()
        if (byteOrderMarker == 'I') {
            bytes[2] = (version and 0xFF).toByte()
            bytes[3] = ((version shr 8) and 0xFF).toByte()
        } else {
            bytes[2] = ((version shr 8) and 0xFF).toByte()
            bytes[3] = (version and 0xFF).toByte()
        }
        return bytes
    }

    // Format detection --------------------------------------------------

    @Test
    fun `little-endian classic TIFF header is detected`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.CLASSIC,
            GeoTiffImportRouter.detectFormat(header('I', 42))
        )
    }

    @Test
    fun `big-endian classic TIFF header is detected`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.CLASSIC,
            GeoTiffImportRouter.detectFormat(header('M', 42))
        )
    }

    @Test
    fun `little-endian BigTIFF header is detected`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.BIG_TIFF,
            GeoTiffImportRouter.detectFormat(header('I', 43))
        )
    }

    @Test
    fun `big-endian BigTIFF header is detected`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.BIG_TIFF,
            GeoTiffImportRouter.detectFormat(header('M', 43))
        )
    }

    @Test
    fun `neither byte-order marker is unknown`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.UNKNOWN,
            GeoTiffImportRouter.detectFormat(byteArrayOf(0, 0, 42, 0))
        )
    }

    @Test
    fun `a recognised marker with an unrecognised version is unknown`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.UNKNOWN,
            GeoTiffImportRouter.detectFormat(header('I', 7))
        )
    }

    @Test
    fun `a header shorter than 4 bytes is unknown`() {
        assertEquals(
            GeoTiffImportRouter.TiffFormat.UNKNOWN,
            GeoTiffImportRouter.detectFormat(byteArrayOf('I'.code.toByte(), 'I'.code.toByte()))
        )
    }

    // Size estimation -----------------------------------------------------

    @Test
    fun `estimates a plain 8-bit single-band raster's uncompressed size`() {
        val bytes = GeoTiffImportRouter.estimateUncompressedBytes(
            width = 1000,
            height = 500,
            samplesPerPixel = 1,
            maxBitsPerSample = 8
        )
        assertEquals(1000L * 500L, bytes)
    }

    @Test
    fun `rounds bits-per-sample up to a whole byte`() {
        // 1-bit-per-sample data still occupies a full byte per sample here,
        // matching how TiffBlockReader actually reads such a block.
        val bytes = GeoTiffImportRouter.estimateUncompressedBytes(
            width = 100,
            height = 100,
            samplesPerPixel = 1,
            maxBitsPerSample = 1
        )
        assertEquals(100L * 100L, bytes)
    }

    @Test
    fun `multiplies in bands and 32-bit float samples`() {
        val bytes = GeoTiffImportRouter.estimateUncompressedBytes(
            width = 10000,
            height = 8000,
            samplesPerPixel = 3,
            maxBitsPerSample = 32
        )
        assertEquals(10000L * 8000L * 3L * 4L, bytes)
    }

    @Test
    fun `a non-positive dimension yields no estimate`() {
        assertNull(
            GeoTiffImportRouter.estimateUncompressedBytes(
                width = 0,
                height = 100,
                samplesPerPixel = 1,
                maxBitsPerSample = 8
            )
        )
    }

    @Test
    fun `an overflowing size yields no estimate rather than a wrapped number`() {
        assertNull(
            GeoTiffImportRouter.estimateUncompressedBytes(
                width = Long.MAX_VALUE / 2,
                height = 4,
                samplesPerPixel = 4,
                maxBitsPerSample = 32
            )
        )
    }

    // Routing ---------------------------------------------------------------

    @Test
    fun `BigTIFF always routes to GDAL regardless of size`() {
        assertTrue(
            GeoTiffImportRouter.shouldUseGdal(
                GeoTiffImportRouter.TiffFormat.BIG_TIFF,
                estimatedUncompressedBytes = 10L
            )
        )
    }

    @Test
    fun `a small classic TIFF stays on the Kotlin path`() {
        assertFalse(
            GeoTiffImportRouter.shouldUseGdal(
                GeoTiffImportRouter.TiffFormat.CLASSIC,
                estimatedUncompressedBytes = 1024L
            )
        )
    }

    @Test
    fun `a classic TIFF right at the threshold stays on the Kotlin path`() {
        assertFalse(
            GeoTiffImportRouter.shouldUseGdal(
                GeoTiffImportRouter.TiffFormat.CLASSIC,
                estimatedUncompressedBytes = GeoTiffImportRouter.GDAL_SIZE_THRESHOLD_BYTES
            )
        )
    }

    @Test
    fun `a classic TIFF just over the threshold routes to GDAL`() {
        assertTrue(
            GeoTiffImportRouter.shouldUseGdal(
                GeoTiffImportRouter.TiffFormat.CLASSIC,
                estimatedUncompressedBytes = GeoTiffImportRouter.GDAL_SIZE_THRESHOLD_BYTES + 1
            )
        )
    }

    @Test
    fun `a classic TIFF with no readable size estimate routes to GDAL`() {
        assertTrue(
            GeoTiffImportRouter.shouldUseGdal(
                GeoTiffImportRouter.TiffFormat.CLASSIC,
                estimatedUncompressedBytes = null
            )
        )
    }

    @Test
    fun `an unrecognised header never routes to GDAL`() {
        assertFalse(
            GeoTiffImportRouter.shouldUseGdal(
                GeoTiffImportRouter.TiffFormat.UNKNOWN,
                estimatedUncompressedBytes = null
            )
        )
    }

    // Block size gate --------------------------------------------------------

    @Test
    fun `a small image stored as one giant strip routes to GDAL before anything is allocated`() {
        // 2,100 x 2,100 Float32 = ~17 MB, far under the 300 MB image threshold, but one strip.
        val image = GeoTiffImportRouter.estimateUncompressedBytes(2_100, 2_100, 1, 32)
        val strip = GeoTiffImportRouter.estimateBlockBytes(2_100, 2_100, 1, 32)
        assertTrue(image!! < GeoTiffImportRouter.GDAL_SIZE_THRESHOLD_BYTES)
        assertTrue(
            GeoTiffImportRouter.shouldUseGdal(GeoTiffImportRouter.TiffFormat.CLASSIC, image, strip)
        )
    }

    @Test
    fun `a block exactly at the cap stays on the Kotlin path`() {
        val atCap = GeoTiffImportRouter.MAX_KOTLIN_BLOCK_BYTES
        assertFalse(
            GeoTiffImportRouter.shouldUseGdal(GeoTiffImportRouter.TiffFormat.CLASSIC, atCap, atCap)
        )
        assertTrue(
            GeoTiffImportRouter.shouldUseGdal(GeoTiffImportRouter.TiffFormat.CLASSIC, atCap, atCap + 1)
        )
    }

    @Test
    fun `typical 256px Float32 tiles stay on the Kotlin path`() {
        val tile = GeoTiffImportRouter.estimateBlockBytes(256, 256, 1, 32)
        assertEquals(256L * 1024, tile)
        assertFalse(
            GeoTiffImportRouter.shouldUseGdal(GeoTiffImportRouter.TiffFormat.CLASSIC, 100L * 1024 * 1024, tile)
        )
    }

    @Test
    fun `an unreadable block size routes to GDAL`() {
        assertTrue(
            GeoTiffImportRouter.shouldUseGdal(GeoTiffImportRouter.TiffFormat.CLASSIC, 1_000L, null)
        )
    }
}
