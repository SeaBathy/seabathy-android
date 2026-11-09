package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reproduces the reported bug directly: a small TIFF (no predictor) works,
 * a larger real-world-shaped TIFF (Deflate + a predictor, exactly what
 * GDAL/QGIS write by default for compressed Float32 depth grids) previously
 * decoded to nothing, silently.
 *
 * These fixtures are genuinely predictor-encoded (see
 * [PredictorEncodedGeoTiff]'s class doc for why that matters) so a passing
 * test here is real evidence [TiffBlockReader] undoes the prediction
 * correctly, not evidence that nothing needed undoing.
 */
class PredictorDecodeTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `predictor 3 float32 single strip decodes to the original values`() {
        val width = 6
        val height = 4
        val file = PredictorEncodedGeoTiff.writeFloat32(
            file = folder.newFile("predictor3-single-strip.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01
        ) { column, row -> -(row * width + column + 1).toFloat() }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(width * height, result.grid.coveredCells)
        assertEquals(-1.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-(width * height).toDouble(), result.grid.deepestMetres, 1e-6)
        assertEquals(-1.0, result.grid.sampleMetres(-18.0 - 0.005, 147.005)!!, 1e-6)
        assertEquals(
            -(width * height).toDouble(),
            result.grid.sampleMetres(-18.0 - 0.035, 147.055)!!,
            1e-6
        )
    }

    @Test
    fun `predictor 3 float32 across many strips resets per row and per strip`() {
        // 6 rows over a 2-row strip forces 3 strips; a predictor bug that
        // leaks state across a row or strip boundary would corrupt every
        // row after the first in each strip.
        val width = 5
        val height = 6
        val file = PredictorEncodedGeoTiff.writeFloat32(
            file = folder.newFile("predictor3-many-strips.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01,
            rowsPerStrip = 2
        ) { column, row -> -(row * width + column + 1).toFloat() * 0.5f }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(width * height, result.grid.coveredCells)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val expected = -(row * width + column + 1).toDouble() * 0.5
                val lat = -18.0 - (row + 0.5) * 0.01
                val lng = 147.0 + (column + 0.5) * 0.01
                assertEquals(
                    "row=$row column=$column",
                    expected,
                    result.grid.sampleMetres(lat, lng)!!,
                    1e-6
                )
            }
        }
    }

    @Test
    fun `predictor 3 float32 handles a non-trivial fractional pattern`() {
        // Sequential integers (as used above) mostly exercise small
        // differences; real bathymetry has noisy, non-monotonic values,
        // which stresses the sign-extension and byte-shuffle logic more.
        val width = 8
        val height = 3
        val values = listOf(
            -12.34f, -0.5f, -100.125f, -3.75f, -45.0f, -0.001f, -9999.9f, -1.0f,
            -2.5f, -8.125f, -0.0625f, -300.3f, -17.75f, -0.0f, -6.6f, -60.06f,
            -1.1f, -22.22f, -333.3f, -4.0f, -55.5f, -0.9f, -700.7f, -8.8f
        )
        val file = PredictorEncodedGeoTiff.writeFloat32(
            file = folder.newFile("predictor3-fractional.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01
        ) { column, row -> values[row * width + column] }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(width * height, result.grid.coveredCells)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val lat = -18.0 - (row + 0.5) * 0.01
                val lng = 147.0 + (column + 0.5) * 0.01
                assertEquals(
                    "row=$row column=$column",
                    values[row * width + column].toDouble(),
                    result.grid.sampleMetres(lat, lng)!!,
                    1e-4
                )
            }
        }
    }

    @Test
    fun `predictor 2 uint16 single strip decodes to the original values`() {
        val width = 6
        val height = 4
        val file = PredictorEncodedGeoTiff.writeUInt16(
            file = folder.newFile("predictor2-uint16.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01
        ) { column, row -> row * width + column + 1 }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        // Positive-only source data, so the app's positive-down convention
        // flips the sign — this exercises the full pipeline, not just decode.
        assertTrue(result.positiveDownConverted)
        assertEquals(width * height, result.grid.coveredCells)
        assertEquals(-1.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-(width * height).toDouble(), result.grid.deepestMetres, 1e-6)
        assertEquals(-1.0, result.grid.sampleMetres(-18.0 - 0.005, 147.005)!!, 1e-6)
    }

    @Test
    fun `an unsupported predictor and compression combination fails loudly instead of silently`() {
        // LZW-compressed, predictor-encoded blocks are deliberately not
        // decoded (see TiffBlockReader's class doc) — this must surface as
        // an actionable error, not as a blank layer with no explanation,
        // which was the original bug.
        val width = 4
        val height = 4
        val file = PredictorEncodedGeoTiff.writeFloat32(
            file = folder.newFile("predictor-unsupported.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01
        ) { _, _ -> -1f }

        // Corrupt the Compression tag after writing to simulate a codec this
        // app doesn't self-decompress (e.g. LZW) while keeping everything
        // else — including the Predictor tag — genuine.
        TiffCompressionCodeOverride.overwrite(file, newCompressionCode = 5) // LZW

        val failure = runCatching { GeoTiffReader.read(file) }.exceptionOrNull()
        assertTrue(
            "expected a clear unsupported-combination error, got $failure",
            failure is GeoTiffReader.UnsupportedGeoTiffException
        )
    }
}
