package au.opendepth.app.geotiff

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Hand-writes minimal little-endian classic TIFFs with genuinely
 * predictor-encoded strip data.
 *
 * `mil.nga.tiff`'s own `TiffWriter` cannot produce this: it has no predictor
 * *encoder*, so setting a `Predictor` tag through it stamps a label onto
 * otherwise-plain bytes rather than actually differencing them (see
 * [TiffBlockReader]'s class doc). A fixture built that way would decode
 * "correctly" by accident, whether or not [TiffBlockReader]'s un-prediction
 * is right, because there would be nothing to undo.
 *
 * These fixtures encode the prediction by hand — the same transform
 * GDAL/libtiff apply — so a round trip through [GeoTiffReader] is a real
 * test of undoing it.
 */
object PredictorEncodedGeoTiff {

    const val PREDICTOR_HORIZONTAL = 2
    const val PREDICTOR_FLOATING_POINT = 3
    const val SAMPLE_FORMAT_UNSIGNED_INT = 1
    const val SAMPLE_FORMAT_FLOAT = 3

    /** A single-band Float32 raster with genuine Predictor-3 + Deflate strip encoding. */
    @Suppress("LongParameterList")
    fun writeFloat32(
        file: File,
        width: Int,
        height: Int,
        originX: Double,
        originY: Double,
        pixelSizeX: Double,
        pixelSizeY: Double,
        rowsPerStrip: Int = height,
        sample: (column: Int, row: Int) -> Float
    ): File = write(
        file = file,
        width = width,
        height = height,
        rowsPerStrip = rowsPerStrip,
        bitsPerSample = 32,
        sampleFormat = SAMPLE_FORMAT_FLOAT,
        predictor = PREDICTOR_FLOATING_POINT,
        originX = originX,
        originY = originY,
        pixelSizeX = pixelSizeX,
        pixelSizeY = pixelSizeY
    ) { row -> floatRowBytes(width) { col -> sample(col, row) } }

    /** A single-band UInt16 raster with genuine Predictor-2 + Deflate strip encoding. */
    @Suppress("LongParameterList")
    fun writeUInt16(
        file: File,
        width: Int,
        height: Int,
        originX: Double,
        originY: Double,
        pixelSizeX: Double,
        pixelSizeY: Double,
        rowsPerStrip: Int = height,
        sample: (column: Int, row: Int) -> Int
    ): File = write(
        file = file,
        width = width,
        height = height,
        rowsPerStrip = rowsPerStrip,
        bitsPerSample = 16,
        sampleFormat = SAMPLE_FORMAT_UNSIGNED_INT,
        predictor = PREDICTOR_HORIZONTAL,
        originX = originX,
        originY = originY,
        pixelSizeX = pixelSizeX,
        pixelSizeY = pixelSizeY
    ) { row -> uint16RowBytes(width) { col -> sample(col, row) } }

    private fun floatRowBytes(width: Int, value: (Int) -> Float): ByteArray {
        val buffer = ByteBuffer.allocate(width * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (col in 0 until width) buffer.putFloat(value(col))
        return buffer.array()
    }

    private fun uint16RowBytes(width: Int, value: (Int) -> Int): ByteArray {
        val buffer = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (col in 0 until width) buffer.putShort(value(col).toShort())
        return buffer.array()
    }

    @Suppress("LongParameterList")
    private fun write(
        file: File,
        width: Int,
        height: Int,
        rowsPerStrip: Int,
        bitsPerSample: Int,
        sampleFormat: Int,
        predictor: Int,
        originX: Double,
        originY: Double,
        pixelSizeX: Double,
        pixelSizeY: Double,
        rowBytes: (row: Int) -> ByteArray
    ): File {
        val bytesPerSample = bitsPerSample / 8
        val strips = ceilDiv(height, rowsPerStrip)
        val stripBlobs = ArrayList<ByteArray>(strips)

        for (strip in 0 until strips) {
            val startRow = strip * rowsPerStrip
            val stripRows = minOf(rowsPerStrip, height - startRow)
            val plain = ByteArray(stripRows * width * bytesPerSample)
            for (r in 0 until stripRows) {
                val row = rowBytes(startRow + r)
                System.arraycopy(row, 0, plain, r * row.size, row.size)
            }
            when (predictor) {
                PREDICTOR_HORIZONTAL -> encodeHorizontal(plain, width, stripRows, bytesPerSample)
                PREDICTOR_FLOATING_POINT -> encodeFloatingPoint(plain, width, stripRows, bytesPerSample)
            }
            stripBlobs += deflate(plain)
        }

        return writeContainer(
            file = file,
            width = width,
            height = height,
            rowsPerStrip = rowsPerStrip,
            bitsPerSample = bitsPerSample,
            sampleFormat = sampleFormat,
            predictor = predictor,
            originX = originX,
            originY = originY,
            pixelSizeX = pixelSizeX,
            pixelSizeY = pixelSizeY,
            geoKeys = SyntheticGeoTiff.geographicKeys(4326),
            stripBlobs = stripBlobs
        )
    }

    // Forward predictor encoders — single-band (samplesPerPixel = 1) only,
    // which is all these fixtures ever need. Each is the exact inverse of
    // the corresponding decoder in TiffBlockReader.

    private fun encodeHorizontal(bytes: ByteArray, width: Int, rows: Int, bytesPerSample: Int) {
        val rowLength = width * bytesPerSample
        for (row in 0 until rows) {
            val rowStart = row * rowLength
            when (bytesPerSample) {
                1 -> for (i in width - 1 downTo 1) {
                    val pos = rowStart + i
                    bytes[pos] = (bytes[pos] - bytes[pos - 1]).toByte()
                }

                // Absolute ByteBuffer indices are array-relative regardless of
                // any offset passed to wrap(), so rowStart is folded into pos
                // by hand here (see TiffBlockReader.unpredictHorizontal).
                2 -> {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in width - 1 downTo 1) {
                        val pos = rowStart + i * 2
                        val prevPos = pos - 2
                        val diff = (buffer.getShort(pos).toInt() and 0xFFFF) -
                            (buffer.getShort(prevPos).toInt() and 0xFFFF)
                        buffer.putShort(pos, diff.toShort())
                    }
                }

                4 -> {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in width - 1 downTo 1) {
                        val pos = rowStart + i * 4
                        val prevPos = pos - 4
                        val diff = buffer.getInt(pos) - buffer.getInt(prevPos)
                        buffer.putInt(pos, diff)
                    }
                }
            }
        }
    }

    private fun encodeFloatingPoint(bytes: ByteArray, width: Int, rows: Int, bytesPerSample: Int) {
        val rowSamples = width
        val rowLength = rowSamples * bytesPerSample
        for (row in 0 until rows) {
            val rowStart = row * rowLength

            // Step 1: shuffle into byte planes, most significant byte first,
            // as libtiff's fpDiff does. This fixture is little-endian, so a
            // sample's most significant byte is its last.
            val original = bytes.copyOfRange(rowStart, rowStart + rowLength)
            for (sample in 0 until rowSamples) {
                for (plane in 0 until bytesPerSample) {
                    bytes[rowStart + plane * rowSamples + sample] =
                        original[sample * bytesPerSample + (bytesPerSample - 1 - plane)]
                }
            }

            // Step 2: horizontal-difference the shuffled row as a plain byte stream.
            for (i in rowLength - 1 downTo 1) {
                val pos = rowStart + i
                bytes[pos] = (bytes[pos] - bytes[pos - 1]).toByte()
            }
        }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val output = ByteArrayOutputStream(bytes.size)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun ceilDiv(numerator: Int, denominator: Int): Int =
        (numerator + denominator - 1) / denominator

    // Minimal classic-TIFF container assembly ------------------------------

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PREDICTOR = 317
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_MODEL_PIXEL_SCALE = 33550
    private const val TAG_MODEL_TIEPOINT = 33922
    private const val TAG_GEO_KEY_DIRECTORY = 34735

    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_DOUBLE = 12
    private const val COMPRESSION_DEFLATE = 8

    private class Entry(
        val tag: Int,
        val type: Int,
        val count: Int,
        var inline: Int? = null,
        val blob: ByteArray? = null
    ) {
        var resolvedOffset: Int = 0
    }

    @Suppress("LongParameterList")
    private fun writeContainer(
        file: File,
        width: Int,
        height: Int,
        rowsPerStrip: Int,
        bitsPerSample: Int,
        sampleFormat: Int,
        predictor: Int,
        originX: Double,
        originY: Double,
        pixelSizeX: Double,
        pixelSizeY: Double,
        geoKeys: List<Int>,
        stripBlobs: List<ByteArray>
    ): File {
        val entries = ArrayList<Entry>()
        entries += Entry(TAG_IMAGE_WIDTH, TYPE_LONG, 1, inline = width)
        entries += Entry(TAG_IMAGE_LENGTH, TYPE_LONG, 1, inline = height)
        entries += Entry(TAG_BITS_PER_SAMPLE, TYPE_SHORT, 1, inline = bitsPerSample)
        entries += Entry(TAG_COMPRESSION, TYPE_SHORT, 1, inline = COMPRESSION_DEFLATE)
        entries += Entry(TAG_PHOTOMETRIC, TYPE_SHORT, 1, inline = 1)

        val stripByteCounts = stripBlobs.map { it.size }
        val stripOffsetsEntry: Entry
        val stripByteCountsEntry: Entry
        if (stripBlobs.size == 1) {
            stripOffsetsEntry = Entry(TAG_STRIP_OFFSETS, TYPE_LONG, 1)
            stripByteCountsEntry = Entry(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, inline = stripByteCounts[0])
        } else {
            stripOffsetsEntry = Entry(TAG_STRIP_OFFSETS, TYPE_LONG, stripBlobs.size, blob = ByteArray(stripBlobs.size * 4))
            val countsBuffer = ByteBuffer.allocate(stripBlobs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            stripByteCounts.forEach { countsBuffer.putInt(it) }
            stripByteCountsEntry = Entry(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, stripBlobs.size, blob = countsBuffer.array())
        }
        entries += stripOffsetsEntry
        entries += Entry(TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, inline = 1)
        entries += Entry(TAG_ROWS_PER_STRIP, TYPE_LONG, 1, inline = rowsPerStrip)
        entries += stripByteCountsEntry
        entries += Entry(TAG_PREDICTOR, TYPE_SHORT, 1, inline = predictor)
        entries += Entry(TAG_SAMPLE_FORMAT, TYPE_SHORT, 1, inline = sampleFormat)

        val pixelScaleBuffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        pixelScaleBuffer.putDouble(pixelSizeX)
        pixelScaleBuffer.putDouble(pixelSizeY)
        pixelScaleBuffer.putDouble(0.0)
        entries += Entry(TAG_MODEL_PIXEL_SCALE, TYPE_DOUBLE, 3, blob = pixelScaleBuffer.array())

        val tiepointBuffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        tiepointBuffer.putDouble(0.0)
        tiepointBuffer.putDouble(0.0)
        tiepointBuffer.putDouble(0.0)
        tiepointBuffer.putDouble(originX)
        tiepointBuffer.putDouble(originY)
        tiepointBuffer.putDouble(0.0)
        entries += Entry(TAG_MODEL_TIEPOINT, TYPE_DOUBLE, 6, blob = tiepointBuffer.array())

        val geoKeyBuffer = ByteBuffer.allocate(geoKeys.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        geoKeys.forEach { geoKeyBuffer.putShort(it.toShort()) }
        entries += Entry(TAG_GEO_KEY_DIRECTORY, TYPE_SHORT, geoKeys.size, blob = geoKeyBuffer.array())

        entries.sortBy { it.tag }

        val entryCount = entries.size
        val ifdOffset = 8
        val ifdSize = 2 + entryCount * 12 + 4
        var cursor = ifdOffset + ifdSize

        for (entry in entries) {
            if (entry.blob != null) {
                entry.resolvedOffset = cursor
                cursor += entry.blob.size
                if (cursor % 2 != 0) cursor++
            }
        }

        val stripStart = cursor
        val resolvedStripOffsets = ArrayList<Int>(stripBlobs.size)
        var stripCursor = stripStart
        for (blob in stripBlobs) {
            resolvedStripOffsets += stripCursor
            stripCursor += blob.size
        }

        if (stripBlobs.size == 1) {
            stripOffsetsEntry.inline = resolvedStripOffsets[0]
        } else {
            val offsetsBuffer = ByteBuffer.allocate(stripBlobs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            resolvedStripOffsets.forEach { offsetsBuffer.putInt(it) }
            System.arraycopy(offsetsBuffer.array(), 0, stripOffsetsEntry.blob!!, 0, offsetsBuffer.array().size)
        }

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)

            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.put('I'.code.toByte())
            header.put('I'.code.toByte())
            header.putShort(42.toShort())
            header.putInt(ifdOffset)
            raf.write(header.array())

            raf.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(entryCount.toShort()).array())

            for (entry in entries) {
                val record = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                record.putShort(entry.tag.toShort())
                record.putShort(entry.type.toShort())
                record.putInt(entry.count)
                record.putInt(if (entry.blob != null) entry.resolvedOffset else (entry.inline ?: 0))
                raf.write(record.array())
            }

            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())

            for (entry in entries) {
                if (entry.blob != null) {
                    raf.seek(entry.resolvedOffset.toLong())
                    raf.write(entry.blob)
                }
            }

            raf.seek(stripStart.toLong())
            for (blob in stripBlobs) raf.write(blob)
        }

        return file
    }
}
