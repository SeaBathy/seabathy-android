package au.opendepth.app.geotiff

import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.Rasters
import mil.nga.tiff.TIFFImage
import mil.nga.tiff.TiffWriter
import java.io.File

/**
 * Writes small single-band float GeoTIFFs so the reader can be exercised
 * end to end from a JVM unit test, with no device and no sample data files.
 */
object SyntheticGeoTiff {

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_DEFLATE = 8
    const val PREDICTOR_FLOATINGPOINT = 3
    private const val PHOTOMETRIC_BLACK_IS_ZERO = 1
    private const val PLANAR_CHUNKY = 1
    private const val SAMPLE_FORMAT_FLOAT = 3
    private const val BITS_PER_SAMPLE = 32

    /** GeoKeyDirectory declaring a geographic CRS such as EPSG:4326. */
    fun geographicKeys(code: Int): List<Int> =
        listOf(1, 1, 0, 2) + listOf(1024, 0, 1, 2) + listOf(2048, 0, 1, code)

    /** GeoKeyDirectory declaring a projected CRS such as EPSG:3857. */
    fun projectedKeys(code: Int): List<Int> =
        listOf(1, 1, 0, 2) + listOf(1024, 0, 1, 1) + listOf(3072, 0, 1, code)

    /**
     * @param originX projected x of the upper-left corner of the raster.
     * @param originY projected y of the upper-left corner of the raster.
     * @param pixelSizeX projected width of one pixel.
     * @param pixelSizeY projected height of one pixel, as a positive value.
     */
    @Suppress("LongParameterList")
    fun write(
        file: File,
        width: Int,
        height: Int,
        originX: Double,
        originY: Double,
        pixelSizeX: Double,
        pixelSizeY: Double,
        geoKeys: List<Int>? = geographicKeys(4326),
        noData: String? = null,
        compression: Int = COMPRESSION_NONE,
        predictor: Int? = null,
        rowsPerStrip: Int? = null,
        sample: (column: Int, row: Int) -> Float
    ): File {
        val rasters = Rasters(width, height, 1, FieldType.FLOAT)
        for (row in 0 until height) {
            for (column in 0 until width) {
                rasters.setFirstPixelSample(column, row, sample(column, row))
            }
        }

        val directory = FileDirectory(rasters).apply {
            setImageWidth(width)
            setImageHeight(height)
            setBitsPerSample(BITS_PER_SAMPLE)
            setCompression(compression)
            setPhotometricInterpretation(PHOTOMETRIC_BLACK_IS_ZERO)
            setSamplesPerPixel(1)
            setSampleFormat(SAMPLE_FORMAT_FLOAT)
            setPlanarConfiguration(PLANAR_CHUNKY)
            setRowsPerStrip(rowsPerStrip ?: rasters.calculateRowsPerStrip(PLANAR_CHUNKY))
            if (predictor != null) setPredictor(predictor)
            modelPixelScale = listOf(pixelSizeX, pixelSizeY, 0.0)
            modelTiepoint = listOf(0.0, 0.0, 0.0, originX, originY, 0.0)
            if (geoKeys != null) {
                setUnsignedIntegerListEntryValue(FieldTagType.GeoKeyDirectory, geoKeys)
            }
            if (noData != null) {
                setStringEntryValue(FieldTagType.GDAL_NODATA, noData)
            }
        }

        TiffWriter.writeTiff(file, TIFFImage(directory))
        return file
    }
}
