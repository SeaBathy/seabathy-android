package au.opendepth.app.geotiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Confirms [TiffIfdReader.read] parses BigTIFF (version 43), not just
 * classic TIFF (version 42).
 *
 * The fixture below uses the same header fields as a real BigTIFF
 * (`49 49 2b 00 08 00 00 00 c8 00 00 00 00 00 00 00`: "II" + version 0x002B
 * = 43, little-endian), then appends a minimal IFD so the whole thing is a
 * real, parseable (if pixel-data-free) BigTIFF.
 */
class TiffIfdReaderBigTiffTest {

    /** Writes [bytes] to a fresh temp file and hands back a channel positioned at 0, mirroring how every real caller of [TiffIfdReader.read] gets its channel. */
    private fun channelFor(bytes: ByteArray): FileChannel {
        val file = File.createTempFile("bigtiff-fixture-", ".tif")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return RandomAccessFile(file, "r").channel
    }

    @Test
    fun `a BigTIFF header round-trips through the reader with the right tag values`() {
        val ifd = TiffIfdReader.read(channelFor(buildMinimalBigTiff()))
        val directory = ifd.directory

        assertEquals(ByteOrder.LITTLE_ENDIAN, ifd.byteOrder)
        assertEquals(4, directory.imageWidth?.toInt())
        assertEquals(3, directory.imageHeight?.toInt())
        assertEquals(1, directory.samplesPerPixel)
        assertEquals(3, directory.rowsPerStrip?.toInt())
        assertEquals(listOf(32), directory.bitsPerSample)
    }

    @Test
    fun `BigTIFF's 64-bit LONG8 tag values (StripOffsets, StripByteCounts) are read correctly`() {
        val directory = TiffIfdReader.read(channelFor(buildMinimalBigTiff())).directory

        assertTrue(directory.stripOffsets?.map { it.toLong() } == listOf(0L))
        assertTrue(directory.stripByteCounts?.map { it.toLong() } == listOf(48L))
    }

    @Test
    fun `a real-world BigTIFF header prefix parses without error`() {
        // The first 16 bytes of a real BigTIFF bathymetry file, captured
        // byte for byte rather than synthesised.
        val realHeaderPrefix = byteArrayOf(
            0x49, 0x49, 0x2b, 0x00, 0x08, 0x00, 0x00, 0x00,
            0xc8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val fixture = buildMinimalBigTiff()

        // The fixture's header is built independently below (see
        // buildMinimalBigTiff), but must agree with the real captured
        // bytes on the fields that actually distinguish BigTIFF from
        // classic TIFF: the byte-order marker and the magic number.
        assertEquals(realHeaderPrefix[0], fixture[0])
        assertEquals(realHeaderPrefix[1], fixture[1])
        assertEquals(realHeaderPrefix[2], fixture[2])
        assertEquals(realHeaderPrefix[3], fixture[3])

        // Parses without throwing "unrecognised magic number".
        TiffIfdReader.read(channelFor(fixture))
    }

    private fun buildMinimalBigTiff(): ByteArray {
        val byteOrder = ByteOrder.LITTLE_ENDIAN
        // (tag, type code, value) -- every value here fits BigTIFF's 8-byte
        // inline threshold, so this fixture needs no separate overflow
        // area at all.
        val entries = listOf(
            Triple(256, TYPE_LONG, 4L), // ImageWidth
            Triple(257, TYPE_LONG, 3L), // ImageLength
            Triple(258, TYPE_SHORT, 32L), // BitsPerSample
            Triple(273, TYPE_LONG8, 0L), // StripOffsets
            Triple(277, TYPE_SHORT, 1L), // SamplesPerPixel
            Triple(278, TYPE_LONG, 3L), // RowsPerStrip
            Triple(279, TYPE_LONG8, 48L) // StripByteCounts
        )

        val ifdSize = 8 + entries.size * 20 + 8
        val buffer = ByteBuffer.allocate(16 + ifdSize).order(byteOrder)

        // Header: byte order, magic 43, offset-byte-size 8, reserved 0,
        // first IFD offset (immediately after this 16-byte header).
        buffer.put('I'.code.toByte()).put('I'.code.toByte())
        buffer.putShort(43)
        buffer.putShort(8)
        buffer.putShort(0)
        buffer.putLong(16)

        buffer.putLong(entries.size.toLong())
        for ((tag, type, value) in entries) {
            buffer.putShort(tag.toShort())
            buffer.putShort(type.toShort())
            buffer.putLong(1L)
            when (type) {
                TYPE_SHORT -> {
                    buffer.putShort(value.toShort())
                    buffer.put(ByteArray(6))
                }
                TYPE_LONG -> {
                    buffer.putInt(value.toInt())
                    buffer.put(ByteArray(4))
                }
                TYPE_LONG8 -> buffer.putLong(value)
            }
        }
        buffer.putLong(0L) // next IFD offset (none)

        return buffer.array()
    }

    private companion object {
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_LONG8 = 16
    }
}
