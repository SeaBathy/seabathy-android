package au.opendepth.app.geotiff

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Patches the `Compression` tag's inline value in an already-written
 * little-endian classic TIFF, leaving every other byte — including the
 * `Predictor` tag and the actual strip bytes — untouched.
 *
 * Used to build a fixture that claims a compression code this app doesn't
 * self-decompress (e.g. LZW) while everything about the predictor encoding
 * underneath stays genuine, so the resulting test proves the *combination*
 * is rejected rather than proving nothing about a fabricated file.
 */
object TiffCompressionCodeOverride {

    private const val TAG_COMPRESSION = 259

    fun overwrite(file: File, newCompressionCode: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val countBytes = ByteArray(2)
            raf.seek(8)
            raf.readFully(countBytes)
            val entryCount = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

            for (index in 0 until entryCount) {
                val entryOffset = 10L + index * 12L
                val header = ByteArray(4)
                raf.seek(entryOffset)
                raf.readFully(header)
                val tag = ByteBuffer.wrap(header, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (tag == TAG_COMPRESSION) {
                    val value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    value.putInt(newCompressionCode)
                    raf.seek(entryOffset + 8)
                    raf.write(value.array())
                    return
                }
            }
            error("Compression tag not found in $file")
        }
    }
}
