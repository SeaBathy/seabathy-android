package au.opendepth.app.geotiff

import android.graphics.Bitmap
import android.graphics.Color
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.model.GeoBounds
import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FileDirectory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes a GeoTIFF into something the map can draw.
 *
 * Single-band rasters become a [DepthGrid] that is recoloured live from the
 * depth-ramp UI. Multi-band and palette rasters become a finished [Bitmap],
 * which is how RGB imagery such as orthophotos and satellite exports arrive.
 *
 * The raster is always resampled onto a north-up WGS84 grid, so projected
 * sources (Web Mercator, UTM) land in the right place rather than being
 * stretched into a bounding box.
 */
object GeoTiffReader {

    /** Raised for a readable TIFF this app deliberately does not handle. */
    class UnsupportedGeoTiffException(message: String) : IOException(message)

    /**
     * Output grid edge cap. The decoded grid, its pixel buffer and the GL
     * bitmap are all held at this size, so it trades raster detail against a
     * predictable per-layer memory cost.
     */
    const val DEFAULT_MAX_DIMENSION = 1600

    /**
     * The output grid edge used when a caller does not pass one: set from the
     * user's GeoTIFF quality setting (see [au.opendepth.app.settings.RasterQuality]).
     */
    @Volatile
    var importMaxDimension: Int = DEFAULT_MAX_DIMENSION

    sealed class Result {
        abstract val bounds: GeoBounds
        abstract val projectionLabel: String
        abstract val sourceWidth: Int
        abstract val sourceHeight: Int

        class Depth(
            val grid: DepthGrid,
            val positiveDownConverted: Boolean,
            override val bounds: GeoBounds,
            override val projectionLabel: String,
            override val sourceWidth: Int,
            override val sourceHeight: Int
        ) : Result()

        class Image(
            val bitmap: Bitmap,
            override val bounds: GeoBounds,
            override val projectionLabel: String,
            override val sourceWidth: Int,
            override val sourceHeight: Int
        ) : Result()
    }

    /**
     * Reads a GeoTIFF that exists as a real file on disk. Prefer [read] with a
     * [FileChannel] directly when the source is a `content://` URI, so no
     * private copy of the picked file is ever needed.
     */
    fun read(file: File, maxDimension: Int = importMaxDimension): Result {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("The selected TIFF file is empty")
        }
        RandomAccessFile(file, "r").use { raf ->
            return read(raf.channel, raf.length(), maxDimension, displayName = file.name)
        }
    }

    /**
     * Reads a GeoTIFF from [channel] (a file or a `content://` document). Only
     * the IFD is read up front; pixels come a block at a time through
     * [TiffBlockReader], so the file is never loaded whole.
     */
    fun read(
        channel: FileChannel,
        length: Long,
        maxDimension: Int = importMaxDimension,
        displayName: String = "<content>"
    ): Result {
        if (length <= 0L) {
            throw IOException("The selected TIFF file is empty")
        }

        val meta = resolveMetadata(channel, displayName)

        val outputScale = min(
            1.0,
            maxDimension.toDouble() / max(meta.sourceWidth, meta.sourceHeight)
        )
        val cols = max(2, (meta.sourceWidth * outputScale).roundToInt())
        val rows = max(2, (meta.sourceHeight * outputScale).roundToInt())

        return decode(channel, meta, meta.fullBounds, cols, rows)
    }

    // Shared metadata resolution ---------------------------------------------

    private class Metadata(
        val directory: FileDirectory,
        val byteOrder: ByteOrder,
        val transform: PixelTransform,
        val projection: MapProjection,
        val fullBounds: GeoBounds,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private fun resolveMetadata(channel: FileChannel, displayName: String): Metadata {
        DiagnosticLog.log(TAG, "IFD parse start: file=$displayName")
        val ifdStart = System.currentTimeMillis()
        val ifd = TiffIfdReader.read(channel)
        val directory = ifd.directory
        DiagnosticLog.log(TAG, "IFD parse finished: elapsedMs=${System.currentTimeMillis() - ifdStart}")

        val sourceWidth = directory.imageWidth?.toInt() ?: 0
        val sourceHeight = directory.imageHeight?.toInt() ?: 0
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw IOException("This TIFF reports an empty image size")
        }

        val tiled = directory.get(FieldTagType.TileWidth) != null
        DiagnosticLog.log(
            TAG,
            "image params: width=$sourceWidth height=$sourceHeight tiled=$tiled " +
                "compression=${directory.compression} predictor=${directory.predictor} " +
                "samplesPerPixel=${directory.samplesPerPixel}"
        )

        val transform = PixelTransform.from(directory)
        val projection = resolveProjection(directory, transform, sourceWidth, sourceHeight)
        val fullBounds = geographicBounds(transform, projection, sourceWidth, sourceHeight)
        if (!fullBounds.isValid) {
            throw UnsupportedGeoTiffException(
                "The georeferencing in this TIFF does not resolve to a valid " +
                    "latitude and longitude extent"
            )
        }

        return Metadata(directory, ifd.byteOrder, transform, projection, fullBounds, sourceWidth, sourceHeight)
    }

    private fun decode(
        channel: FileChannel,
        meta: Metadata,
        bounds: GeoBounds,
        cols: Int,
        rows: Int
    ): Result {
        val samplesPerPixel = meta.directory.samplesPerPixel
        val colorMap = runCatching { meta.directory.colorMap }.getOrNull()
        val isPaletted = samplesPerPixel == 1 &&
            meta.directory.photometricInterpretation == PHOTOMETRIC_PALETTE &&
            colorMap != null &&
            colorMap.isNotEmpty()

        return when {
            samplesPerPixel >= 3 -> readImage(
                channel, meta.directory, meta.byteOrder, meta.transform, meta.projection, bounds,
                cols, rows, meta.sourceWidth, meta.sourceHeight, samplesPerPixel, null
            )

            isPaletted -> readImage(
                channel, meta.directory, meta.byteOrder, meta.transform, meta.projection, bounds,
                cols, rows, meta.sourceWidth, meta.sourceHeight, 1, colorMap
            )

            samplesPerPixel == 1 -> readDepth(
                channel, meta.directory, meta.byteOrder, meta.transform, meta.projection, bounds,
                cols, rows, meta.sourceWidth, meta.sourceHeight
            )

            else -> throw UnsupportedGeoTiffException(
                "This TIFF has $samplesPerPixel bands per pixel, which is " +
                    "neither a depth raster nor an image"
            )
        }
    }

    // Depth rasters ---------------------------------------------------------

    private fun readDepth(
        channel: FileChannel,
        directory: FileDirectory,
        byteOrder: ByteOrder,
        transform: PixelTransform,
        projection: MapProjection,
        bounds: GeoBounds,
        cols: Int,
        rows: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Result.Depth {
        val noData = readNoDataValue(directory)
        val values = FloatArray(cols * rows) { DepthGrid.NO_DATA }

        var covered = 0
        var minValue = Double.POSITIVE_INFINITY
        var maxValue = Double.NEGATIVE_INFINITY

        TiffBlockReader(channel, directory, intArrayOf(0), byteOrder).use { reader ->
            forEachOutputCell(
                bounds, projection, transform, cols, rows,
                sourceWidth, sourceHeight
            ) { outputIndex, sourceCol, sourceRow ->
                val raw = reader.sample(sourceCol, sourceRow, 0)
                if (raw != null && !isNoData(raw, noData)) {
                    values[outputIndex] = raw.toFloat()
                    covered++
                    if (raw < minValue) minValue = raw
                    if (raw > maxValue) maxValue = raw
                }
            }
        }

        if (covered == 0) {
            throw UnsupportedGeoTiffException(
                "Every pixel in this TIFF is marked as no-data"
            )
        }

        // Bathymetry ships in both sign conventions. When nothing is negative
        // the file holds positive-down soundings, so flip it into the
        // negative-down form the rest of the app assumes.
        val positiveDown = minValue >= 0.0 && maxValue > 0.0
        if (positiveDown) {
            for (index in values.indices) {
                if (!values[index].isNaN()) values[index] = -values[index]
            }
            val flippedMin = -maxValue
            maxValue = -minValue
            minValue = flippedMin
        }

        val grid = DepthGrid(
            bounds = bounds,
            cols = cols,
            rows = rows,
            values = values,
            shallowestMetres = maxValue,
            deepestMetres = minValue,
            coveredCells = covered
        )

        return Result.Depth(
            grid = grid,
            positiveDownConverted = positiveDown,
            bounds = bounds,
            projectionLabel = projection.label,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
    }

    // Image rasters ---------------------------------------------------------

    private fun readImage(
        channel: FileChannel,
        directory: FileDirectory,
        byteOrder: ByteOrder,
        transform: PixelTransform,
        projection: MapProjection,
        bounds: GeoBounds,
        cols: Int,
        rows: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        bandCount: Int,
        colorMap: List<Int>?
    ): Result.Image {
        val bands = min(bandCount, MAX_IMAGE_BANDS)
        val bandIndexes = IntArray(bands) { it }
        val scale = sampleScaleForBits(directory.maxBitsPerSample ?: 8)
        val pixels = IntArray(cols * rows)

        TiffBlockReader(channel, directory, bandIndexes, byteOrder).use { reader ->
            forEachOutputCell(
                bounds, projection, transform, cols, rows,
                sourceWidth, sourceHeight
            ) { outputIndex, sourceCol, sourceRow ->
                val first = reader.sample(sourceCol, sourceRow, 0)
                if (first != null) {
                    pixels[outputIndex] = if (colorMap != null) {
                        colorFromColorMap(colorMap, first.toInt())
                    } else {
                        val green = reader.sample(sourceCol, sourceRow, 1) ?: first
                        val blue = reader.sample(sourceCol, sourceRow, 2) ?: first
                        val alpha = if (bands >= 4) {
                            reader.sample(sourceCol, sourceRow, 3) ?: OPAQUE_SAMPLE
                        } else {
                            OPAQUE_SAMPLE
                        }
                        Color.argb(
                            toByteRange(alpha * scale),
                            toByteRange(first * scale),
                            toByteRange(green * scale),
                            toByteRange(blue * scale)
                        )
                    }
                }
            }
        }

        return Result.Image(
            bitmap = Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888),
            bounds = bounds,
            projectionLabel = projection.label,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
    }

    private fun sampleScaleForBits(bitsPerSample: Int): Double = when {
        bitsPerSample <= 8 -> 1.0
        bitsPerSample <= 16 -> 255.0 / 65535.0
        else -> 255.0 / 4294967295.0
    }

    private fun toByteRange(value: Double): Int =
        if (value.isNaN()) 0 else value.roundToInt().coerceIn(0, 255)

    private fun colorFromColorMap(colorMap: List<Int>, index: Int): Int {
        val entries = colorMap.size / 3
        if (entries <= 0 || index < 0 || index >= entries) return Color.TRANSPARENT
        return Color.argb(
            255,
            toByteRange(colorMap[index] * COLOR_MAP_SCALE),
            toByteRange(colorMap[entries + index] * COLOR_MAP_SCALE),
            toByteRange(colorMap[2 * entries + index] * COLOR_MAP_SCALE)
        )
    }

    // Resampling driver -----------------------------------------------------

    /**
     * Walks the output grid row by row, mapping each cell back to a source
     * pixel. [TiffBlockReader] decodes and caches whichever strip or tile a
     * source pixel falls in on demand, so callers just ask for samples.
     */
    private inline fun forEachOutputCell(
        bounds: GeoBounds,
        projection: MapProjection,
        transform: PixelTransform,
        cols: Int,
        rows: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        onCell: (outputIndex: Int, sourceCol: Int, sourceRow: Int) -> Unit
    ) {
        val lngSpan = bounds.east - bounds.west
        val latSpan = bounds.north - bounds.south
        val sourceCols = IntArray(cols)
        val sourceRows = IntArray(cols)

        for (row in 0 until rows) {
            val lat = bounds.north - (row + 0.5) / rows * latSpan
            var minRow = Int.MAX_VALUE
            var maxRow = Int.MIN_VALUE

            for (col in 0 until cols) {
                val lng = bounds.west + (col + 0.5) / cols * lngSpan
                val projected = projection.forward(lat, lng)
                val pixel = transform.toPixel(projected[0], projected[1])
                val pixelCol = pixel[0].toInt()
                val pixelRow = pixel[1].toInt()

                if (pixelCol in 0 until sourceWidth && pixelRow in 0 until sourceHeight) {
                    sourceCols[col] = pixelCol
                    sourceRows[col] = pixelRow
                    if (pixelRow < minRow) minRow = pixelRow
                    if (pixelRow > maxRow) maxRow = pixelRow
                } else {
                    sourceCols[col] = OUT_OF_BOUNDS
                }
            }

            if (maxRow < minRow) continue

            val outputRowOffset = row * cols
            for (col in 0 until cols) {
                val sourceCol = sourceCols[col]
                if (sourceCol != OUT_OF_BOUNDS) {
                    onCell(outputRowOffset + col, sourceCol, sourceRows[col])
                }
            }
        }
    }

    // Georeferencing --------------------------------------------------------

    /** Affine mapping between raster pixel indices and projected units. */
    private class PixelTransform(
        val originX: Double,
        val originY: Double,
        val scaleX: Double,
        val scaleY: Double
    ) {
        fun toProjected(col: Double, row: Double): DoubleArray = doubleArrayOf(
            originX + col * scaleX,
            originY + row * scaleY
        )

        fun toPixel(x: Double, y: Double): DoubleArray = doubleArrayOf(
            (x - originX) / scaleX,
            (y - originY) / scaleY
        )

        companion object {
            fun from(directory: FileDirectory): PixelTransform {
                val matrix = directory.doubleListOrNull(FieldTagType.ModelTransformation)
                if (matrix != null && matrix.size >= MODEL_TRANSFORM_SIZE) {
                    if (abs(matrix[1]) > ROTATION_EPSILON ||
                        abs(matrix[4]) > ROTATION_EPSILON
                    ) {
                        throw UnsupportedGeoTiffException(
                            "This TIFF is rotated relative to north, which is " +
                                "not supported"
                        )
                    }
                    return PixelTransform(
                        originX = matrix[3],
                        originY = matrix[7],
                        scaleX = matrix[0],
                        scaleY = matrix[5]
                    )
                }

                val pixelScale = directory.modelPixelScale
                val tiePoint = directory.modelTiepoint
                if (pixelScale == null || pixelScale.size < 2 ||
                    tiePoint == null || tiePoint.size < TIE_POINT_SIZE
                ) {
                    throw UnsupportedGeoTiffException(
                        "This TIFF has no georeferencing, so it cannot be " +
                            "placed on the map"
                    )
                }

                val scaleX = pixelScale[0]
                val scaleY = pixelScale[1]
                if (scaleX == 0.0 || scaleY == 0.0) {
                    throw UnsupportedGeoTiffException(
                        "This TIFF reports a zero pixel size"
                    )
                }

                return PixelTransform(
                    originX = tiePoint[3] - tiePoint[0] * scaleX,
                    originY = tiePoint[4] + tiePoint[1] * scaleY,
                    scaleX = scaleX,
                    scaleY = -scaleY
                )
            }
        }
    }

    private fun resolveProjection(
        directory: FileDirectory,
        transform: PixelTransform,
        width: Int,
        height: Int
    ): MapProjection {
        val geoKeys = readGeoKeys(directory)
        val projectedCode = geoKeys[PROJECTED_CS_TYPE_KEY] ?: 0
        val geographicCode = geoKeys[GEOGRAPHIC_TYPE_KEY] ?: 0
        val modelType = geoKeys[MODEL_TYPE_KEY] ?: 0

        if (projectedCode > 0 && projectedCode != UNDEFINED_CODE) {
            return MapProjection.fromProjectedCode(projectedCode)
                ?: throw UnsupportedGeoTiffException(
                    "Projected CRS EPSG:$projectedCode is not supported, so " +
                        "reproject to EPSG:4326 or EPSG:3857 first"
                )
        }

        if (modelType == MODEL_TYPE_GEOGRAPHIC || geographicCode > 0) {
            MapProjection.fromGeographicCode(geographicCode)?.let { return it }
            throw UnsupportedGeoTiffException(
                "Geographic CRS EPSG:$geographicCode is not supported, so " +
                    "reproject to EPSG:4326 first"
            )
        }

        // No usable geo keys. Fall back to degrees when the corner coordinates
        // are plausible as latitude and longitude, and to Web Mercator when
        // they are clearly metres.
        val corner = transform.toProjected(width.toDouble(), height.toDouble())
        val plausibleDegrees = abs(transform.originX) <= 180.0 &&
            abs(transform.originY) <= 90.0 &&
            abs(corner[0]) <= 180.0 &&
            abs(corner[1]) <= 90.0
        if (plausibleDegrees) return MapProjection.Geographic

        val plausibleMercator = abs(transform.originX) <= MERCATOR_LIMIT &&
            abs(transform.originY) <= MERCATOR_LIMIT &&
            abs(corner[0]) <= MERCATOR_LIMIT &&
            abs(corner[1]) <= MERCATOR_LIMIT
        if (plausibleMercator) return MapProjection.WebMercator

        throw UnsupportedGeoTiffException(
            "This TIFF does not declare a coordinate system and its extent " +
                "could not be identified"
        )
    }

    private fun readGeoKeys(directory: FileDirectory): Map<Int, Int> {
        val raw = runCatching {
            directory.getIntegerListEntryValue(FieldTagType.GeoKeyDirectory)
        }.getOrNull() ?: return emptyMap()
        if (raw.size < GEO_KEY_HEADER_SIZE) return emptyMap()

        val available = (raw.size - GEO_KEY_HEADER_SIZE) / GEO_KEY_ENTRY_SIZE
        val keyCount = min(raw[3], available)
        val keys = HashMap<Int, Int>(keyCount.coerceAtLeast(1))

        for (index in 0 until keyCount) {
            val offset = GEO_KEY_HEADER_SIZE + index * GEO_KEY_ENTRY_SIZE
            // A zero tag location means the value is stored inline, which is
            // the only form the CRS keys read here ever use.
            if (raw[offset + 1] == 0) keys[raw[offset]] = raw[offset + 3]
        }
        return keys
    }

    private fun geographicBounds(
        transform: PixelTransform,
        projection: MapProjection,
        width: Int,
        height: Int
    ): GeoBounds {
        var west = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY

        fun consider(col: Double, row: Double) {
            val projected = transform.toProjected(col, row)
            val geographic = projection.inverse(projected[0], projected[1]) ?: return
            west = min(west, geographic[1])
            east = max(east, geographic[1])
            south = min(south, geographic[0])
            north = max(north, geographic[0])
        }

        // Sample the whole perimeter, not just the corners: reprojected edges
        // bow outwards and the corners alone would clip real coverage.
        for (step in 0..PERIMETER_SAMPLES) {
            val alongX = width.toDouble() * step / PERIMETER_SAMPLES
            val alongY = height.toDouble() * step / PERIMETER_SAMPLES
            consider(alongX, 0.0)
            consider(alongX, height.toDouble())
            consider(0.0, alongY)
            consider(width.toDouble(), alongY)
        }

        if (!west.isFinite() || !east.isFinite() ||
            !south.isFinite() || !north.isFinite()
        ) {
            throw UnsupportedGeoTiffException(
                "The extent of this TIFF could not be converted to latitude " +
                    "and longitude"
            )
        }

        return GeoBounds(
            west = west.coerceIn(-180.0, 180.0),
            south = south.coerceIn(-90.0, 90.0),
            east = east.coerceIn(-180.0, 180.0),
            north = north.coerceIn(-90.0, 90.0)
        )
    }

    private fun readNoDataValue(directory: FileDirectory): Double? = runCatching {
        directory.getStringEntryValue(FieldTagType.GDAL_NODATA)
            ?.trim()
            ?.toDoubleOrNull()
    }.getOrNull()

    private fun isNoData(value: Double, noData: Double?): Boolean {
        if (value.isNaN() || value.isInfinite()) return true
        if (abs(value) >= SENTINEL_MAGNITUDE) return true
        if (noData == null || noData.isNaN()) return false
        return abs(value - noData) <= abs(noData) * NO_DATA_TOLERANCE + NO_DATA_TOLERANCE
    }

    private fun FileDirectory.doubleListOrNull(
        tag: FieldTagType
    ): List<Double>? = runCatching { getDoubleListEntryValue(tag) }.getOrNull()

    private const val TAG = "GeoTiffReader"
    private const val OUT_OF_BOUNDS = -1
    private const val PERIMETER_SAMPLES = 64
    private const val GEO_KEY_HEADER_SIZE = 4
    private const val GEO_KEY_ENTRY_SIZE = 4
    private const val MODEL_TRANSFORM_SIZE = 16
    private const val TIE_POINT_SIZE = 6
    private const val MODEL_TYPE_KEY = 1024
    private const val GEOGRAPHIC_TYPE_KEY = 2048
    private const val PROJECTED_CS_TYPE_KEY = 3072
    private const val MODEL_TYPE_GEOGRAPHIC = 2
    private const val UNDEFINED_CODE = 32767
    private const val PHOTOMETRIC_PALETTE = 3
    private const val MAX_IMAGE_BANDS = 4
    private const val OPAQUE_SAMPLE = 255.0
    private const val COLOR_MAP_SCALE = 255.0 / 65535.0
    private const val SENTINEL_MAGNITUDE = 1.0e30
    private const val NO_DATA_TOLERANCE = 1.0e-6
    private const val ROTATION_EPSILON = 1.0e-9
    private const val MERCATOR_LIMIT = 2.0037508e7
}
