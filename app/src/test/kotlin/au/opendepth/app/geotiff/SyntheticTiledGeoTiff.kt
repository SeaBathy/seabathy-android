package au.opendepth.app.geotiff

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand-assembles a minimal tiled, uncompressed, single-band float32 GeoTIFF.
 *
 * `mil.nga.tiff`'s [mil.nga.tiff.TiffWriter] only ever emits striped output
 * (see [SyntheticGeoTiff]), so there is no library path to produce a tiled
 * fixture. This builds the handful of IFD tags and the tile grid directly,
 * byte for byte, so [TiffBlockReader]'s tiled path — the layout real
 * Cloud Optimized GeoTIFFs use — is exercised against a real file on disk
 * rather than only against striped ones.
 */
object SyntheticTiledGeoTiff {

    @Suppress("LongParameterList")
    fun write(
        file: File,
        width: Int,
        height: Int,
        tileWidth: Int,
        tileHeight: Int,
        originX: Double,
        originY: Double,
        pixelSizeX: Double,
        pixelSizeY: Double,
        geoKeys: List<Int> = SyntheticGeoTiff.geographicKeys(4326),
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        sample: (column: Int, row: Int) -> Float
    ): File {
        val tilesAcross = (width + tileWidth - 1) / tileWidth
        val tilesDown = (height + tileHeight - 1) / tileHeight
        val tileCount = tilesAcross * tilesDown
        val tileByteCount = tileWidth * tileHeight * BYTES_PER_SAMPLE

        fun shorts(tag: Int, vararg values: Int) =
            Entry(tag, TYPE_SHORT, intValues = values.toList())
        fun longs(tag: Int, values: List<Int>) =
            Entry(tag, TYPE_LONG, intValues = values)
        fun doubles(tag: Int, vararg values: Double) =
            Entry(tag, TYPE_DOUBLE, doubleValues = values.toList())

        val entries = listOf(
            shorts(TAG_IMAGE_WIDTH, width),
            shorts(TAG_IMAGE_LENGTH, height),
            shorts(TAG_BITS_PER_SAMPLE, 32),
            shorts(TAG_COMPRESSION, 1),
            shorts(TAG_PHOTOMETRIC, 1),
            shorts(TAG_SAMPLES_PER_PIXEL, 1),
            shorts(TAG_PLANAR_CONFIG, 1),
            shorts(TAG_TILE_WIDTH, tileWidth),
            shorts(TAG_TILE_LENGTH, tileHeight),
            longs(TAG_TILE_OFFSETS, List(tileCount) { 0 }),
            longs(TAG_TILE_BYTE_COUNTS, List(tileCount) { tileByteCount }),
            shorts(TAG_SAMPLE_FORMAT, 3),
            doubles(TAG_MODEL_PIXEL_SCALE, pixelSizeX, pixelSizeY, 0.0),
            doubles(TAG_MODEL_TIEPOINT, 0.0, 0.0, 0.0, originX, originY, 0.0),
            Entry(TAG_GEO_KEY_DIRECTORY, TYPE_SHORT, intValues = geoKeys)
        )

        val sorted = entries.sortedBy { it.tag }

        val ifdTableBytes = IFD_HEADER_BYTES + sorted.size * ENTRY_BYTES + IFD_NEXT_BYTES
        val extraStart = HEADER_BYTES + ifdTableBytes

        var cursor = extraStart
        val extraOffsets = HashMap<Int, Int>()
        for (entry in sorted) {
            if (entry.byteSize > INLINE_BYTES) {
                extraOffsets[entry.tag] = cursor
                cursor += entry.byteSize
            }
        }
        val tileDataStart = cursor

        val finalEntries = sorted.map { entry ->
            if (entry.tag == TAG_TILE_OFFSETS) {
                entry.copy(intValues = List(tileCount) { tileDataStart + it * tileByteCount })
            } else {
                entry
            }
        }

        val totalSize = tileDataStart + tileCount * tileByteCount
        val buffer = ByteBuffer.allocate(totalSize).order(byteOrder)

        // Header: byte order marker, magic number, first IFD offset.
        val marker = if (byteOrder == ByteOrder.BIG_ENDIAN) 'M' else 'I'
        buffer.put(marker.code.toByte())
        buffer.put(marker.code.toByte())
        buffer.putShort(42)
        buffer.putInt(HEADER_BYTES)

        // IFD: entry count, then each 12-byte entry, then next-IFD offset (none).
        buffer.putShort(finalEntries.size.toShort())
        for (entry in finalEntries) {
            buffer.putShort(entry.tag.toShort())
            buffer.putShort(entry.type.code.toShort())
            buffer.putInt(entry.count)

            val valueFieldPosition = buffer.position()
            if (entry.byteSize <= INLINE_BYTES) {
                writeValues(buffer, entry)
                buffer.position(valueFieldPosition + INLINE_BYTES)
            } else {
                buffer.putInt(extraOffsets.getValue(entry.tag))
            }
        }
        buffer.putInt(0)

        // Out-of-line value arrays, at the offsets just declared above.
        for (entry in finalEntries) {
            if (entry.byteSize > INLINE_BYTES) {
                check(buffer.position() == extraOffsets.getValue(entry.tag)) {
                    "tag ${entry.tag}: expected offset ${extraOffsets.getValue(entry.tag)}, " +
                        "was at ${buffer.position()}"
                }
                writeValues(buffer, entry)
            }
        }
        check(buffer.position() == tileDataStart) {
            "expected tile data at $tileDataStart, got ${buffer.position()}"
        }

        // Tile pixel data, one full (possibly edge-padded) tile at a time.
        for (tileIndex in 0 until tileCount) {
            val tileX = tileIndex % tilesAcross
            val tileY = tileIndex / tilesAcross
            for (localRow in 0 until tileHeight) {
                for (localCol in 0 until tileWidth) {
                    val sourceRow = tileY * tileHeight + localRow
                    val sourceCol = tileX * tileWidth + localCol
                    val value = if (sourceRow < height && sourceCol < width) {
                        sample(sourceCol, sourceRow)
                    } else {
                        0f
                    }
                    buffer.putFloat(value)
                }
            }
        }

        file.writeBytes(buffer.array())
        return file
    }

    private fun writeValues(buffer: ByteBuffer, entry: Entry) {
        when (entry.type) {
            TYPE_SHORT -> entry.intValues.forEach { buffer.putShort(it.toShort()) }
            TYPE_LONG -> entry.intValues.forEach { buffer.putInt(it) }
            TYPE_DOUBLE -> entry.doubleValues.forEach { buffer.putDouble(it) }
        }
    }

    private class FieldTypeSpec(val code: Int, val bytes: Int)

    private data class Entry(
        val tag: Int,
        val type: FieldTypeSpec,
        val intValues: List<Int> = emptyList(),
        val doubleValues: List<Double> = emptyList()
    ) {
        val count: Int get() = if (type == TYPE_DOUBLE) doubleValues.size else intValues.size
        val byteSize: Int get() = count * type.bytes
    }

    private val TYPE_SHORT = FieldTypeSpec(3, 2)
    private val TYPE_LONG = FieldTypeSpec(4, 4)
    private val TYPE_DOUBLE = FieldTypeSpec(12, 8)

    private const val HEADER_BYTES = 8
    private const val IFD_HEADER_BYTES = 2
    private const val IFD_NEXT_BYTES = 4
    private const val ENTRY_BYTES = 12
    private const val INLINE_BYTES = 4
    private const val BYTES_PER_SAMPLE = 4

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_PLANAR_CONFIG = 284
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_LENGTH = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_BYTE_COUNTS = 325
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_MODEL_PIXEL_SCALE = 33550
    private const val TAG_MODEL_TIEPOINT = 33922
    private const val TAG_GEO_KEY_DIRECTORY = 34735
}
