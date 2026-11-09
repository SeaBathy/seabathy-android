package au.opendepth.app.geotiff

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decides from a file's header and first IFD whether the Kotlin reader can
 * open it or it goes to GDAL. No I/O of its own, so fully unit tested.
 */
object GeoTiffImportRouter {

    /** What a TIFF's first bytes declare it to be. */
    enum class TiffFormat {
        /** Classic 32-bit-offset TIFF (version number 42) — what the Kotlin path decodes. */
        CLASSIC,

        /** BigTIFF, 64-bit offsets (version number 43) — always routed to GDAL. */
        BIG_TIFF,

        /** Neither marker matched; not a TIFF this app recognises at all. */
        UNKNOWN
    }

    /**
     * Classic TIFFs bigger than this uncompressed go to GDAL: the in-memory
     * grid, its bitmap and the resampling all grow with the raster.
     */
    const val GDAL_SIZE_THRESHOLD_BYTES = 300L * 1024 * 1024

    /**
     * Reads the byte-order marker and version number out of a TIFF's first 4
     * bytes. [header] must be at least [HEADER_BYTES_NEEDED] bytes; anything
     * shorter, or a header matching neither marker, is [TiffFormat.UNKNOWN].
     */
    fun detectFormat(header: ByteArray): TiffFormat {
        if (header.size < HEADER_BYTES_NEEDED) return TiffFormat.UNKNOWN

        val byteOrder = when {
            header[0] == BYTE_I && header[1] == BYTE_I -> ByteOrder.LITTLE_ENDIAN
            header[0] == BYTE_M && header[1] == BYTE_M -> ByteOrder.BIG_ENDIAN
            else -> return TiffFormat.UNKNOWN
        }

        val version = ByteBuffer.wrap(header).order(byteOrder).getShort(2).toInt()
        return when (version) {
            CLASSIC_VERSION -> TiffFormat.CLASSIC
            BIG_TIFF_VERSION -> TiffFormat.BIG_TIFF
            else -> TiffFormat.UNKNOWN
        }
    }

    /**
     * Uncompressed size of the image (what decoding has to hold, whatever the
     * compression), or null when an input is not positive or it would overflow,
     * so an unreadable IFD goes to GDAL.
     */
    fun estimateUncompressedBytes(
        width: Long,
        height: Long,
        samplesPerPixel: Int,
        maxBitsPerSample: Int
    ): Long? {
        if (width <= 0 || height <= 0 || samplesPerPixel <= 0 || maxBitsPerSample <= 0) return null
        val bytesPerSample = (maxBitsPerSample + 7) / 8

        return runCatching {
            Math.multiplyExact(
                Math.multiplyExact(Math.multiplyExact(width, height), samplesPerPixel.toLong()),
                bytesPerSample.toLong()
            )
        }.getOrNull()
    }

    /**
     * The biggest strip or tile the Kotlin reader decodes. [TiffBlockReader]
     * holds a whole block (several copies of it) at once, so a file stored as
     * one huge strip goes to GDAL, which reads it in windows.
     */
    const val MAX_KOTLIN_BLOCK_BYTES = 16L * 1024 * 1024

    /**
     * Uncompressed bytes of one strip/tile: [blockWidth] x [blockHeight]
     * pixels of [samplesPerBlockPixel] samples (1 for planar-separate
     * files, which store each band in its own blocks). Null on a
     * non-positive input or overflow, which routes to GDAL like an
     * unreadable size does.
     */
    fun estimateBlockBytes(
        blockWidth: Long,
        blockHeight: Long,
        samplesPerBlockPixel: Int,
        maxBitsPerSample: Int
    ): Long? = estimateUncompressedBytes(blockWidth, blockHeight, samplesPerBlockPixel, maxBitsPerSample)

    /**
     * BigTIFF always goes to GDAL (the Kotlin reader can't open it), and so does
     * a classic TIFF over [GDAL_SIZE_THRESHOLD_BYTES], with a block over
     * [MAX_KOTLIN_BLOCK_BYTES], or with an unreadable size.
     */
    fun shouldUseGdal(
        format: TiffFormat,
        estimatedUncompressedBytes: Long?,
        estimatedBlockBytes: Long? = 0L
    ): Boolean = when (format) {
        TiffFormat.BIG_TIFF -> true
        TiffFormat.CLASSIC -> estimatedUncompressedBytes == null ||
            estimatedUncompressedBytes > GDAL_SIZE_THRESHOLD_BYTES ||
            estimatedBlockBytes == null ||
            estimatedBlockBytes > MAX_KOTLIN_BLOCK_BYTES
        TiffFormat.UNKNOWN -> false
    }

    const val HEADER_BYTES_NEEDED = 4
    private const val BYTE_I = 'I'.code.toByte()
    private const val BYTE_M = 'M'.code.toByte()
    private const val CLASSIC_VERSION = 42
    private const val BIG_TIFF_VERSION = 43
}
