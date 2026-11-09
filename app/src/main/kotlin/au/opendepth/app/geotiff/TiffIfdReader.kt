package au.opendepth.app.geotiff

import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.FileDirectoryEntry
import mil.nga.tiff.io.ByteReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.TreeSet

/**
 * Reads the TIFF header and first IFD with seeks, rather than loading the
 * whole file the way `mil.nga.tiff`'s `TiffReader` does.
 *
 * Reads classic TIFF and BigTIFF. BigTIFF always goes to GDAL, but its
 * parsing is kept and tested. BigTIFF's `LONG8` offsets are read as longs
 * and reported as [FieldType.LONG], which the library lacks a type for; the
 * values themselves are right. Only the tags the app uses are read; the rest
 * (thumbnails, EXIF, XMP) are skipped.
 *
 * The [FileDirectory] has no reader: pixels are read by [TiffBlockReader],
 * never by [FileDirectory.readRasters].
 */
object TiffIfdReader {

    class Ifd(val directory: FileDirectory, val byteOrder: ByteOrder)

    fun read(channel: FileChannel): Ifd {
        // Big enough for either header shape; a real TIFF file is always at
        // least this long regardless of format, so one read covers both.
        val header = ByteArray(BIG_TIFF_HEADER_BYTES)
        channel.readFullyAt(0, header)

        val byteOrder = when {
            header[0] == BYTE_I && header[1] == BYTE_I -> ByteOrder.LITTLE_ENDIAN
            header[0] == BYTE_M && header[1] == BYTE_M -> ByteOrder.BIG_ENDIAN
            else -> throw IOException("Not a TIFF file (unrecognised byte order marker)")
        }

        val headerBuffer = wrap(header, byteOrder)
        val magic = headerBuffer.getShort(2).toInt()
        val isBigTiff = when (magic) {
            CLASSIC_MAGIC_NUMBER -> false
            BIG_TIFF_MAGIC_NUMBER -> true
            else -> throw IOException("Not a TIFF file (unrecognised magic number)")
        }

        val firstIfdOffset = if (isBigTiff) {
            headerBuffer.getLong(8)
        } else {
            headerBuffer.getInt(4).toLong() and UNSIGNED_INT_MASK
        }

        val entries = readDirectoryEntries(channel, byteOrder, firstIfdOffset, isBigTiff)
        // FileDirectory only works out tiling (isTiled, tile size) when built through
        // this constructor. The reader passed is never used: metadata only.
        val directory = FileDirectory(entries, EMPTY_READER)
        return Ifd(directory, byteOrder)
    }

    private fun readDirectoryEntries(
        channel: FileChannel,
        byteOrder: ByteOrder,
        offset: Long,
        isBigTiff: Boolean
    ): TreeSet<FileDirectoryEntry> {
        val entries = TreeSet<FileDirectoryEntry>()

        val entryCount: Long
        val tableOffset: Long
        val entrySize: Int
        val inlineValueBytes: Int
        if (isBigTiff) {
            val countBytes = ByteArray(8)
            channel.readFullyAt(offset, countBytes)
            entryCount = wrap(countBytes, byteOrder).getLong(0)
            tableOffset = offset + 8
            entrySize = BIG_TIFF_ENTRY_BYTES
            inlineValueBytes = BIG_TIFF_INLINE_VALUE_BYTES
        } else {
            val countBytes = ByteArray(2)
            channel.readFullyAt(offset, countBytes)
            entryCount = (wrap(countBytes, byteOrder).getShort(0).toInt() and UNSIGNED_SHORT_MASK).toLong()
            tableOffset = offset + 2
            entrySize = ENTRY_BYTES
            inlineValueBytes = INLINE_VALUE_BYTES
        }
        if (entryCount <= 0L || entryCount > MAX_ENTRIES) return entries

        val table = ByteArray((entryCount * entrySize).toInt())
        channel.readFullyAt(tableOffset, table)
        val tableBuffer = wrap(table, byteOrder)

        for (index in 0 until entryCount.toInt()) {
            val base = index * entrySize
            val tagId = tableBuffer.getShort(base).toInt() and UNSIGNED_SHORT_MASK
            val fieldTag = FieldTagType.getById(tagId)
            if (fieldTag == null || fieldTag !in INTERESTING_TAGS) continue

            val typeCode = tableBuffer.getShort(base + 2).toInt() and UNSIGNED_SHORT_MASK
            val isLong8 = typeCode == TYPE_CODE_LONG8
            val fieldType = if (isLong8) {
                FieldType.LONG
            } else {
                runCatching { FieldType.getFieldType(typeCode) }.getOrNull() ?: continue
            }

            val count = if (isBigTiff) {
                tableBuffer.getLong(base + 4)
            } else {
                tableBuffer.getInt(base + 4).toLong() and UNSIGNED_INT_MASK
            }
            if (count <= 0L || count > MAX_ENTRY_COUNT) continue

            val elementBytes = if (isLong8) LONG8_BYTES else fieldType.bytes
            val valueByteSize = count * elementBytes
            val valuePos = base + entrySize - inlineValueBytes
            val valueBytes: ByteArray
            val valueOffsetInBytes: Int
            if (valueByteSize <= inlineValueBytes) {
                valueBytes = table
                valueOffsetInBytes = valuePos
            } else {
                val valueOffset = if (isBigTiff) {
                    tableBuffer.getLong(valuePos)
                } else {
                    tableBuffer.getInt(valuePos).toLong() and UNSIGNED_INT_MASK
                }
                valueBytes = ByteArray(valueByteSize.toInt())
                channel.readFullyAt(valueOffset, valueBytes)
                valueOffsetInBytes = 0
            }

            val value = runCatching {
                if (isLong8) {
                    readLong8Value(count.toInt(), valueBytes, valueOffsetInBytes, byteOrder)
                } else {
                    readValue(fieldTag, fieldType, count.toInt(), valueBytes, valueOffsetInBytes, byteOrder)
                }
            }.getOrNull() ?: continue

            runCatching {
                entries.add(FileDirectoryEntry(fieldTag, fieldType, count, value))
            }
        }

        return entries
    }

    /**
     * BigTIFF's 64-bit tag value type. Always an [ArrayList], regardless of
     * [count]: in practice LONG8 only appears on the strip/tile offset and
     * byte-count tables, which [FileDirectory] always reads as a list (see
     * [ARRAY_VALUED_TAGS]).
     */
    private fun readLong8Value(count: Int, bytes: ByteArray, offset: Int, byteOrder: ByteOrder): Any {
        val buffer = wrap(bytes, byteOrder)
        return ArrayList(List(count) { i -> buffer.getLong(offset + i * 8) })
    }

    /**
     * Builds the exact `Object` shape [FileDirectory]'s getters expect for
     * each tag: a boxed scalar for a single SHORT/LONG value, an `ArrayList`
     * for anything [FileDirectory] always reads as a list (strip/tile tables,
     * per-sample tags, the GeoTIFF key directory), and a one-element string
     * list for the ASCII no-data value.
     */
    private fun readValue(
        tag: FieldTagType,
        type: FieldType,
        count: Int,
        bytes: ByteArray,
        offset: Int,
        byteOrder: ByteOrder
    ): Any? {
        if (type == FieldType.ASCII) {
            val text = String(bytes, offset, count, StandardCharsets.US_ASCII)
                .trimEnd('\u0000')
            return arrayListOf(text)
        }

        val buffer = wrap(bytes, byteOrder)
        val elements: List<Any> = when (type) {
            FieldType.SHORT -> List(count) { i ->
                buffer.getShort(offset + i * 2).toInt() and UNSIGNED_SHORT_MASK
            }

            FieldType.LONG -> List(count) { i ->
                buffer.getInt(offset + i * 4).toLong() and UNSIGNED_INT_MASK
            }

            FieldType.DOUBLE -> List(count) { i -> buffer.getDouble(offset + i * 8) }

            else -> return null
        }

        return if (type == FieldType.DOUBLE || tag in ARRAY_VALUED_TAGS) {
            ArrayList(elements)
        } else {
            elements.first()
        }
    }

    private fun wrap(bytes: ByteArray, byteOrder: ByteOrder): ByteBuffer =
        ByteBuffer.wrap(bytes).order(byteOrder)

    /** Every tag [GeoTiffReader] and [TiffBlockReader] read; everything else is skipped unread. */
    private val INTERESTING_TAGS = setOf(
        FieldTagType.ImageWidth,
        FieldTagType.ImageLength,
        FieldTagType.BitsPerSample,
        FieldTagType.Compression,
        FieldTagType.PhotometricInterpretation,
        FieldTagType.SamplesPerPixel,
        FieldTagType.RowsPerStrip,
        FieldTagType.StripOffsets,
        FieldTagType.StripByteCounts,
        FieldTagType.PlanarConfiguration,
        FieldTagType.Predictor,
        FieldTagType.ColorMap,
        FieldTagType.TileWidth,
        FieldTagType.TileLength,
        FieldTagType.TileOffsets,
        FieldTagType.TileByteCounts,
        FieldTagType.SampleFormat,
        FieldTagType.ModelPixelScale,
        FieldTagType.ModelTiepoint,
        FieldTagType.ModelTransformation,
        FieldTagType.GeoKeyDirectory,
        FieldTagType.GDAL_NODATA
    )

    /**
     * Tags [FileDirectory] always stores (and returns) as a list, even at
     * count 1 — [FieldTagType.isArray] does not actually track this.
     */
    private val ARRAY_VALUED_TAGS = setOf(
        FieldTagType.BitsPerSample,
        FieldTagType.StripOffsets,
        FieldTagType.StripByteCounts,
        FieldTagType.TileOffsets,
        FieldTagType.TileByteCounts,
        FieldTagType.SampleFormat,
        FieldTagType.ColorMap,
        FieldTagType.GeoKeyDirectory
    )

    private val EMPTY_READER = ByteReader(ByteArray(0))

    private const val BYTE_I = 'I'.code.toByte()
    private const val BYTE_M = 'M'.code.toByte()
    private const val CLASSIC_MAGIC_NUMBER = 42
    private const val BIG_TIFF_MAGIC_NUMBER = 43
    private const val BIG_TIFF_HEADER_BYTES = 16
    private const val ENTRY_BYTES = 12
    private const val BIG_TIFF_ENTRY_BYTES = 20
    private const val INLINE_VALUE_BYTES = 4
    private const val BIG_TIFF_INLINE_VALUE_BYTES = 8
    private const val TYPE_CODE_LONG8 = 16
    private const val LONG8_BYTES = 8
    private const val MAX_ENTRIES = 8192L
    private const val MAX_ENTRY_COUNT = 50_000_000L
    private const val UNSIGNED_SHORT_MASK = 0xFFFF
    private const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
}
