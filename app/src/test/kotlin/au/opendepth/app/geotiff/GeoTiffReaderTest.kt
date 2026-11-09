package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * End-to-end cover for the GeoTIFF depth path: georeferencing, resampling,
 * no-data handling and the sign convention.
 *
 * The image path is not covered here because it builds an Android Bitmap,
 * which needs an instrumented test or Robolectric.
 */
class GeoTiffReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * A 4 x 4 raster covering 147..148 E and 18..19 S in EPSG:4326, with
     * depth increasing to the south.
     */
    private fun writeReefTile(
        noData: String? = null,
        sample: (column: Int, row: Int) -> Float
    ) = SyntheticGeoTiff.write(
        file = folder.newFile("reef-${System.nanoTime()}.tif"),
        width = 4,
        height = 4,
        originX = 147.0,
        originY = -18.0,
        pixelSizeX = 0.25,
        pixelSizeY = 0.25,
        noData = noData,
        sample = sample
    )

    @Test
    fun `a geographic depth raster is placed and sampled correctly`() {
        // Row 0 is -10 m, row 1 is -20 m, and so on southwards.
        val file = writeReefTile { _, row -> -10f * (row + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals("WGS84 geographic", result.projectionLabel)
        assertEquals(147.0, result.bounds.west, 1e-9)
        assertEquals(148.0, result.bounds.east, 1e-9)
        assertEquals(-19.0, result.bounds.south, 1e-9)
        assertEquals(-18.0, result.bounds.north, 1e-9)
        assertTrue(result.bounds.isValid)

        assertEquals(4, result.grid.cols)
        assertEquals(4, result.grid.rows)
        assertEquals(16, result.grid.coveredCells)
        assertEquals(-10.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-40.0, result.grid.deepestMetres, 1e-6)
        assertFalse(result.positiveDownConverted)

        // The northernmost band is the shallow one, the southernmost the deep one.
        assertEquals(-10.0, result.grid.sampleMetres(-18.125, 147.5)!!, 1e-6)
        assertEquals(-20.0, result.grid.sampleMetres(-18.375, 147.5)!!, 1e-6)
        assertEquals(-30.0, result.grid.sampleMetres(-18.625, 147.5)!!, 1e-6)
        assertEquals(-40.0, result.grid.sampleMetres(-18.875, 147.5)!!, 1e-6)
    }

    @Test
    fun `positive down soundings are converted to negative down`() {
        val file = writeReefTile { _, row -> 10f * (row + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertTrue(result.positiveDownConverted)
        assertEquals(-10.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-40.0, result.grid.deepestMetres, 1e-6)
        assertEquals(-10.0, result.grid.sampleMetres(-18.125, 147.5)!!, 1e-6)
    }

    @Test
    fun `mixed elevation data keeps its own signs`() {
        // Land above water in the north, seabed below it in the south.
        val file = writeReefTile { _, row -> if (row < 2) 5f else -25f }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertFalse(result.positiveDownConverted)
        assertEquals(5.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-25.0, result.grid.deepestMetres, 1e-6)
    }

    @Test
    fun `the declared no data value is excluded from coverage and range`() {
        val file = writeReefTile(noData = "-9999") { column, _ ->
            if (column == 0) -9999f else -15f
        }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(12, result.grid.coveredCells)
        assertEquals(-15.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-15.0, result.grid.deepestMetres, 1e-6)
        // The masked western column reads as unknown, the rest reads a depth.
        assertEquals(null, result.grid.sampleMetres(-18.5, 147.1))
        assertEquals(-15.0, result.grid.sampleMetres(-18.5, 147.6)!!, 1e-6)
    }

    @Test
    fun `float sentinels are treated as no data even when undeclared`() {
        val file = writeReefTile { column, _ ->
            if (column == 0) Float.NaN else -3.4e38f
        }

        val failure = runCatching { GeoTiffReader.read(file) }.exceptionOrNull()
        assertTrue(
            "expected an unsupported-TIFF error, got $failure",
            failure is GeoTiffReader.UnsupportedGeoTiffException
        )
    }

    @Test
    fun `a web mercator raster is reprojected into latitude and longitude`() {
        // Roughly 147..148 E and 18..19 S expressed in EPSG:3857 metres.
        val file = SyntheticGeoTiff.write(
            file = folder.newFile("mercator.tif"),
            width = 8,
            height = 8,
            originX = 16364206.0,
            originY = -2035006.0,
            pixelSizeX = 13915.0,
            pixelSizeY = 14700.0,
            geoKeys = SyntheticGeoTiff.projectedKeys(3857)
        ) { _, row -> -5f * (row + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals("Web Mercator", result.projectionLabel)
        assertTrue(result.bounds.isValid)
        assertEquals(147.0, result.bounds.west, 0.05)
        assertEquals(148.0, result.bounds.east, 0.05)
        assertEquals(-18.0, result.bounds.north, 0.05)
        assertEquals(-19.0, result.bounds.south, 0.05)
        assertTrue(result.grid.hasData)
    }

    @Test
    fun `a UTM raster is reprojected into latitude and longitude`() {
        // A 20 km square in UTM zone 55S near the outer Great Barrier Reef.
        val file = SyntheticGeoTiff.write(
            file = folder.newFile("utm.tif"),
            width = 8,
            height = 8,
            originX = 564000.0,
            originY = 7977000.0,
            pixelSizeX = 2500.0,
            pixelSizeY = 2500.0,
            geoKeys = SyntheticGeoTiff.projectedKeys(32755)
        ) { _, row -> -8f * (row + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals("UTM zone 55S", result.projectionLabel)
        assertTrue(result.bounds.isValid)
        assertEquals(147.6, result.bounds.west, 0.2)
        assertEquals(-18.3, result.bounds.north, 0.2)
        assertTrue(result.grid.hasData)
    }

    @Test
    fun `an unsupported projected CRS is rejected with a helpful message`() {
        val file = SyntheticGeoTiff.write(
            file = folder.newFile("albers.tif"),
            width = 4,
            height = 4,
            originX = 400000.0,
            originY = 8000000.0,
            pixelSizeX = 1000.0,
            pixelSizeY = 1000.0,
            geoKeys = SyntheticGeoTiff.projectedKeys(3577)
        ) { _, _ -> -12f }

        val failure = runCatching { GeoTiffReader.read(file) }.exceptionOrNull()
        assertTrue(failure is GeoTiffReader.UnsupportedGeoTiffException)
        assertTrue(failure!!.message!!.contains("3577"))
    }

    @Test
    fun `a TIFF with no georeferencing is rejected`() {
        val rasters = folder.newFile("plain.tif")
        val file = SyntheticGeoTiff.write(
            file = rasters,
            width = 4,
            height = 4,
            originX = 0.0,
            originY = 0.0,
            pixelSizeX = 1.0,
            pixelSizeY = 1.0,
            geoKeys = null
        ) { _, _ -> -12f }

        // Without geo keys the corner coordinates still look like degrees, so
        // this one is accepted as EPSG:4326 rather than rejected.
        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth
        assertEquals("WGS84 geographic", result.projectionLabel)
    }

    @Test
    fun `oversized rasters are downsampled to the requested cap`() {
        val file = SyntheticGeoTiff.write(
            file = folder.newFile("large.tif"),
            width = 200,
            height = 100,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.005,
            pixelSizeY = 0.005
        ) { column, _ -> -1f * (column + 1) }

        val result = GeoTiffReader.read(file, maxDimension = 50)
            as GeoTiffReader.Result.Depth

        assertEquals(50, result.grid.cols)
        assertEquals(25, result.grid.rows)
        assertTrue(result.grid.coveredCells > 0)
        assertEquals(200, result.sourceWidth)
        assertEquals(100, result.sourceHeight)
        // Shallow in the west, deep in the east, preserved through resampling.
        assertTrue(
            result.grid.sampleMetres(-18.1, 147.1)!! >
                result.grid.sampleMetres(-18.1, 147.9)!!
        )
    }

    @Test
    fun `an empty file is rejected`() {
        val empty = folder.newFile("empty.tif")
        val failure = runCatching { GeoTiffReader.read(empty) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IOException)
    }

    @Test
    fun `a raster spanning many strips reads correctly end to end`() {
        // 40 rows over a 4-row strip forces 10 strips, so this only passes if
        // TiffBlockReader walks the strip table correctly rather than reading
        // a single block.
        val width = 20
        val height = 40
        val file = SyntheticGeoTiff.write(
            file = folder.newFile("many-strips.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01,
            rowsPerStrip = 4
        ) { _, row -> -1f * (row + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(width, result.sourceWidth)
        assertEquals(height, result.sourceHeight)
        assertEquals(width * height, result.grid.coveredCells)
        // Shallowest is the northernmost (first) row, deepest the last row.
        assertEquals(-1.0, result.grid.shallowestMetres, 1e-6)
        assertEquals(-40.0, result.grid.deepestMetres, 1e-6)
        assertEquals(-1.0, result.grid.sampleMetres(-18.0 - 0.005, 147.005)!!, 1e-6)
        assertEquals(-40.0, result.grid.sampleMetres(-18.0 - 0.395, 147.005)!!, 1e-6)
    }

    @Test
    fun `deflate compressed strips decode correctly`() {
        // mil.nga.tiff's own predictor *encoder* (horizontal and floating
        // point alike) mis-writes multi-column data, so a predictor fixture
        // from this writer cannot be trusted as a correctness check (see
        // PredictorDecodeTest for predictor coverage). Compression alone (no
        // predictor) round-trips correctly and still exercises
        // TiffBlockReader's decode-one-block-at-a-time path, with
        // decompression itself delegated to mil.nga.tiff's own decoder.
        val width = 8
        val height = 6
        val file = SyntheticGeoTiff.write(
            file = folder.newFile("deflate.tif"),
            width = width,
            height = height,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01,
            compression = SyntheticGeoTiff.COMPRESSION_DEFLATE,
            rowsPerStrip = 2
        ) { column, row -> (row * width + column + 1).toFloat() }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(width * height, result.grid.coveredCells)
        assertEquals(-(width * height).toDouble(), result.grid.deepestMetres, 1e-6)
        assertEquals(-1.0, result.grid.sampleMetres(-18.0 - 0.005, 147.005)!!, 1e-6)
    }

    @Test
    fun `a tiled raster reads correctly from every tile, including edge tiles`() {
        // 10x10 pixels over 4x4 tiles: a 3x3 tile grid where the last row and
        // column of tiles are only partially covered by real image data, the
        // layout every Cloud Optimized GeoTIFF uses.
        val width = 10
        val height = 10
        val file = SyntheticTiledGeoTiff.write(
            file = folder.newFile("tiled.tif"),
            width = width,
            height = height,
            tileWidth = 4,
            tileHeight = 4,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01
        ) { column, row -> -1f * (row * width + column + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(width, result.sourceWidth)
        assertEquals(height, result.sourceHeight)
        assertEquals(width * height, result.grid.coveredCells)
        assertEquals(-1.0, result.grid.shallowestMetres, 1e-6)
        assertEquals((-width * height).toDouble(), result.grid.deepestMetres, 1e-6)

        // A point inside the bottom-right tile, which is only partially
        // covered by real pixels (the tile itself is padded to 4x4).
        val lastRowLastCol = -(width * height).toFloat()
        assertEquals(
            lastRowLastCol.toDouble(),
            result.grid.sampleMetres(-18.0 - 0.095, 147.0 + 0.095)!!,
            1e-6
        )
    }

    @Test
    fun `a big-endian (MM) tiled raster decodes the same depths`() {
        val width = 10
        val height = 10
        val file = SyntheticTiledGeoTiff.write(
            file = folder.newFile("tiled-mm.tif"),
            width = width,
            height = height,
            tileWidth = 4,
            tileHeight = 4,
            originX = 147.0,
            originY = -18.0,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01,
            byteOrder = java.nio.ByteOrder.BIG_ENDIAN
        ) { column, row -> -1f * (row * width + column + 1) }

        val result = GeoTiffReader.read(file) as GeoTiffReader.Result.Depth

        assertEquals(-1.0, result.grid.shallowestMetres, 1e-6)
        assertEquals((-width * height).toDouble(), result.grid.deepestMetres, 1e-6)
        assertEquals(-1.0, result.grid.sampleMetres(-18.0 - 0.005, 147.005)!!, 1e-6)
    }
}
