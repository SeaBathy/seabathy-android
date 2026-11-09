package au.opendepth.app.geotiff

import au.opendepth.app.diagnostics.DiagnosticLog
import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.FileDirectoryEntry
import mil.nga.tiff.Rasters
import mil.nga.tiff.io.ByteReader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.TreeSet
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.min

/**
 * Reads raster samples one strip or tile at a time, straight from the
 * offsets in the TIFF, with a small cache of recent blocks. Memory depends on
 * the block size, not the file size.
 *
 * `mil.nga.tiff` decompresses, but its predictor decoding is broken for rows
 * wider than one pixel. Predictor-encoded blocks are decompressed and
 * un-predicted here (TIFF Predictor 2 and 3, as in libtiff's
 * `tif_predict.c`), then handed to the library as uncompressed. Other
 * blocks go through the library as they are.
 */
internal class TiffBlockReader(
    private val channel: FileChannel,
    directory: FileDirectory,
    private val bandIndexes: IntArray,
    private val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : AutoCloseable {

    private val imageWidth = directory.imageWidth.toInt()
    private val imageHeight = directory.imageHeight.toInt()
    private val tiled = directory.get(FieldTagType.TileWidth) != null

    private val blockWidth = if (tiled) directory.tileWidth.toInt() else imageWidth
    private val blockHeight = if (tiled) {
        directory.tileHeight.toInt()
    } else {
        (directory.rowsPerStrip?.toInt() ?: imageHeight).coerceAtLeast(1)
    }

    private val blocksAcross = ceilDiv(imageWidth, blockWidth)
    private val blocksDown = ceilDiv(imageHeight, blockHeight)
    private val planarConfiguration = directory.planarConfiguration ?: 1

    private val blockOffsets: List<Long> =
        (if (tiled) directory.tileOffsets else directory.stripOffsets)
            ?.map { it.toLong() } ?: emptyList()

    private val blockByteCounts: List<Long> =
        (if (tiled) directory.tileByteCounts else directory.stripByteCounts)
            ?.map { it.toLong() } ?: emptyList()

    // BitsPerSample and SampleFormat are both optional in the TIFF spec —
    // real files commonly omit SampleFormat for plain unsigned-integer
    // imagery — so a missing tag falls back to its spec-defined default
    // rather than propagating a null into the per-block directory below.
    private val bitsPerSample = directory.bitsPerSample
        ?: List(directory.samplesPerPixel) { DEFAULT_BITS_PER_SAMPLE }
    private val sampleFormat = directory.sampleFormat
        ?: List(directory.samplesPerPixel) { DEFAULT_SAMPLE_FORMAT }
    private val compression = directory.compression ?: COMPRESSION_NONE
    private val photometric = directory.photometricInterpretation ?: 1
    private val samplesPerPixel = directory.samplesPerPixel
    private val predictor = directory.predictor
    private val colorMapEntry = directory.get(FieldTagType.ColorMap)

    /**
     * Decoded blocks to keep. The resampling pass walks the output row by
     * row across the whole width, so a tiled file needs a whole row of tiles
     * cached or every output row re-decodes every tile in it (a fixed 8
     * thrashed on anything over 8 tiles wide). Bounded by
     * [CACHE_BUDGET_BYTES] so memory stays capped whatever the block size.
     */
    private val cacheCapacity = blockCacheCapacity(
        blocksPerRow = blocksAcross * (if (planarConfiguration == PLANAR_SEPARATE) bandIndexes.size else 1),
        blockBytes = blockWidth.toLong() * blockHeight *
            (if (planarConfiguration == PLANAR_SEPARATE) 1 else samplesPerPixel) *
            (((bitsPerSample.maxOrNull() ?: DEFAULT_BITS_PER_SAMPLE) + 7) / 8)
    )

    private val cache = object : LinkedHashMap<Int, Rasters?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Rasters?>): Boolean =
            size > cacheCapacity
    }

    private var blocksDecoded = 0
    private var lastProgressLogAt = System.currentTimeMillis()

    init {
        DiagnosticLog.log(
            TAG,
            "TiffBlockReader init: tiled=$tiled blockSize=${blockWidth}x$blockHeight " +
                "blocksAcross=$blocksAcross blocksDown=$blocksDown " +
                "totalBlocks=${blocksAcross * blocksDown} compression=$compression predictor=$predictor " +
                "cacheCapacity=$cacheCapacity"
        )
    }

    /** The sample at ([col], [row]) for output band [band], or null if unavailable. */
    fun sample(col: Int, row: Int, band: Int): Double? {
        if (col !in 0 until imageWidth || row !in 0 until imageHeight) return null
        val realBand = bandIndexes.getOrNull(band) ?: return null

        val tileX = col / blockWidth
        val tileY = row / blockHeight
        val localCol = col - tileX * blockWidth
        val localRow = row - tileY * blockHeight

        val blockIndex = if (planarConfiguration == PLANAR_SEPARATE) {
            realBand * blocksAcross * blocksDown + tileY * blocksAcross + tileX
        } else {
            tileY * blocksAcross + tileX
        }

        // Not getOrPut: it never caches a null, so a sparse or undecodable
        // block would be decoded (and logged) again for every pixel in it.
        val rasters = if (cache.containsKey(blockIndex)) {
            cache[blockIndex]
        } else {
            decodeBlock(blockIndex, tileY).also { cache[blockIndex] = it }
        } ?: return null
        val sampleBand = if (planarConfiguration == PLANAR_SEPARATE) 0 else realBand
        return runCatching {
            rasters.getPixelSample(sampleBand, localCol, localRow)?.toDouble()
        }.getOrNull()
    }

    private fun decodeBlock(blockIndex: Int, tileY: Int): Rasters? {
        DiagnosticLog.log(TAG, "decode block $blockIndex start")
        val decodeStart = System.currentTimeMillis()
        val result = decodeBlockInner(blockIndex, tileY)
        DiagnosticLog.log(
            TAG,
            "decode block $blockIndex finished: elapsedMs=${System.currentTimeMillis() - decodeStart}"
        )
        recordProgress()
        return result
    }

    private fun recordProgress() {
        blocksDecoded++
        val now = System.currentTimeMillis()
        if (blocksDecoded % PROGRESS_LOG_EVERY_BLOCKS == 0 ||
            now - lastProgressLogAt >= PROGRESS_LOG_INTERVAL_MS
        ) {
            DiagnosticLog.log(TAG, "progress: blocksDecoded=$blocksDecoded")
            lastProgressLogAt = now
        }
    }

    private fun decodeBlockInner(blockIndex: Int, tileY: Int): Rasters? {
        if (blockIndex !in blockOffsets.indices || blockIndex !in blockByteCounts.indices) return null
        val offset = blockOffsets[blockIndex]
        val byteCount = blockByteCounts[blockIndex].toInt()
        if (offset <= 0L || byteCount <= 0) return null

        val chunk = ByteArray(byteCount)
        // A positioned FileChannel read carries its own offset per call, so
        // concurrent decodes on the same channel need no external lock —
        // unlike the RandomAccessFile.seek()+read() pair this replaced, which
        // shared one file-pointer cursor across every caller.
        channel.readFullyAt(offset, chunk)

        // Tiles are always stored at their full declared size, padding the
        // real edge tiles; strips are not padded, so the last one may hold
        // fewer rows than every other strip.
        val blockPixelHeight = if (tiled) {
            blockHeight
        } else {
            min(blockHeight, imageHeight - tileY * blockHeight)
        }

        val bandHere = planarConfiguration == PLANAR_SEPARATE
        val blockBitsPerSample = if (bandHere) listOf(bitsPerSample[0]) else bitsPerSample
        val blockSamplesPerPixel = if (bandHere) 1 else samplesPerPixel
        val blockSampleFormat = if (bandHere) listOf(sampleFormat[0]) else sampleFormat

        // If this block is predictor-encoded, undo compression and the
        // predictor ourselves (see class doc) instead of handing the
        // library a Predictor tag it cannot reliably decode. The library
        // then only ever sees Predictor=None, Compression=None data.
        val usesPredictor = predictor != null && predictor != PREDICTOR_NONE
        val finalBytes: ByteArray
        val finalCompression: Int
        if (usesPredictor) {
            val decompressed = decompressForPredictor(chunk, compression)
            unpredict(
                bytes = decompressed,
                predictor = predictor!!,
                width = blockWidth,
                height = blockPixelHeight,
                samplesPerPixel = blockSamplesPerPixel,
                bitsPerSample = blockBitsPerSample
            )
            finalBytes = decompressed
            finalCompression = COMPRESSION_NONE
        } else {
            finalBytes = chunk
            finalCompression = compression
        }

        val entries = TreeSet<FileDirectoryEntry>()
        entries += scalarEntry(FieldTagType.ImageWidth, blockWidth)
        entries += scalarEntry(FieldTagType.ImageLength, blockPixelHeight)
        entries += listEntry(FieldTagType.BitsPerSample, blockBitsPerSample)
        entries += scalarEntry(FieldTagType.Compression, finalCompression)
        entries += scalarEntry(FieldTagType.PhotometricInterpretation, photometric)
        entries += scalarEntry(FieldTagType.SamplesPerPixel, blockSamplesPerPixel)
        entries += listEntry(FieldTagType.SampleFormat, blockSampleFormat)
        entries += scalarEntry(FieldTagType.PlanarConfiguration, PLANAR_CHUNKY)
        // No Predictor entry: either the block never had one, or it has
        // already been undone above and finalBytes/finalCompression reflect
        // plain, unpredicted samples.
        colorMapEntry?.let { entries += it }

        if (tiled) {
            entries += scalarEntry(FieldTagType.TileWidth, blockWidth)
            entries += scalarEntry(FieldTagType.TileLength, blockHeight)
            entries += longListEntry(FieldTagType.TileOffsets, listOf(0L))
            entries += longListEntry(FieldTagType.TileByteCounts, listOf(finalBytes.size.toLong()))
        } else {
            entries += scalarEntry(FieldTagType.RowsPerStrip, blockPixelHeight)
            entries += longListEntry(FieldTagType.StripOffsets, listOf(0L))
            entries += longListEntry(FieldTagType.StripByteCounts, listOf(finalBytes.size.toLong()))
        }

        // finalBytes are still in the file's byte order (unpredict writes back in it too).
        val block = FileDirectory(entries, ByteReader(finalBytes, byteOrder))
        return runCatching { block.readRasters() }.getOrNull()
    }

    // Predictor handling ------------------------------------------------

    /**
     * Decompresses a predictor-encoded block before [unpredict]. Deflate and no
     * compression only (GDAL's default for Float32); LZW with a predictor is
     * refused rather than risk wrong depths.
     */
    private fun decompressForPredictor(chunk: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE -> chunk
        COMPRESSION_DEFLATE, COMPRESSION_ADOBE_DEFLATE -> inflate(chunk)
        else -> throw GeoTiffReader.UnsupportedGeoTiffException(
            "This TIFF combines a predictor with compression code $compression, which this " +
                "app cannot decode yet — re-save it with Deflate/ZIP compression, or without a predictor"
        )
    }

    private fun inflate(chunk: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(chunk)
        val output = ByteArrayOutputStream(chunk.size * 3)
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                output.write(buffer, 0, count)
            }
        } catch (error: DataFormatException) {
            throw GeoTiffReader.UnsupportedGeoTiffException(
                "This TIFF's Deflate-compressed pixel data is corrupt or uses an unsupported variant"
            )
        } finally {
            inflater.end()
        }
        return output.toByteArray()
    }

    /**
     * Undoes TIFF Predictor 2 (horizontal) or 3 (floating point) in place, row
     * by row: each row starts afresh, so a block can be done on its own. As in
     * libtiff's `tif_predict.c`.
     */
    private fun unpredict(
        bytes: ByteArray,
        predictor: Int,
        width: Int,
        height: Int,
        samplesPerPixel: Int,
        bitsPerSample: List<Int>
    ) {
        val bytesPerSample = (bitsPerSample.firstOrNull() ?: 8) / 8
        if (bytesPerSample <= 0 || width <= 0 || height <= 0) return
        val rowBytes = width * samplesPerPixel * bytesPerSample
        if (rowBytes <= 0 || bytes.size < rowBytes * height) return

        when (predictor) {
            PREDICTOR_HORIZONTAL -> {
                if (bytesPerSample !in HORIZONTAL_SUPPORTED_SAMPLE_SIZES) {
                    throw GeoTiffReader.UnsupportedGeoTiffException(
                        "This TIFF uses a horizontal predictor with $bytesPerSample-byte " +
                            "samples, which this app does not yet decode"
                    )
                }
                for (row in 0 until height) {
                    unpredictHorizontal(bytes, row * rowBytes, width, samplesPerPixel, bytesPerSample)
                }
            }

            PREDICTOR_FLOATING_POINT -> {
                for (row in 0 until height) {
                    unpredictFloatingPoint(bytes, row * rowBytes, width, samplesPerPixel, bytesPerSample)
                }
            }

            else -> throw GeoTiffReader.UnsupportedGeoTiffException(
                "This TIFF declares predictor $predictor, which is not a value the TIFF " +
                    "specification defines (only 2 and 3 are)"
            )
        }
    }

    /**
     * Predictor 2: each sample is stored as the difference from the previous
     * pixel's. The buffer wraps the whole block, so `rowStart` is added to every
     * index (absolute `getShort`/`putShort` ignore a `wrap` offset).
     */
    private fun unpredictHorizontal(
        bytes: ByteArray,
        rowStart: Int,
        width: Int,
        samplesPerPixel: Int,
        bytesPerSample: Int
    ) {
        val elementCount = width * samplesPerPixel
        when (bytesPerSample) {
            1 -> for (i in samplesPerPixel until elementCount) {
                val pos = rowStart + i
                bytes[pos] = (bytes[pos] + bytes[pos - samplesPerPixel]).toByte()
            }

            2 -> {
                val buffer = ByteBuffer.wrap(bytes).order(byteOrder)
                for (i in samplesPerPixel until elementCount) {
                    val pos = rowStart + i * 2
                    val prevPos = pos - samplesPerPixel * 2
                    val sum = (buffer.getShort(pos).toInt() and 0xFFFF) +
                        (buffer.getShort(prevPos).toInt() and 0xFFFF)
                    buffer.putShort(pos, sum.toShort())
                }
            }

            4 -> {
                val buffer = ByteBuffer.wrap(bytes).order(byteOrder)
                for (i in samplesPerPixel until elementCount) {
                    val pos = rowStart + i * 4
                    val prevPos = pos - samplesPerPixel * 4
                    val sum = buffer.getInt(pos) + buffer.getInt(prevPos)
                    buffer.putInt(pos, sum)
                }
            }
        }
    }

    /**
     * Predictor 3: each row's bytes were split into byte planes (all most
     * significant bytes first) and then differenced as bytes. Undone in
     * reverse: add the bytes back (stride samplesPerPixel), then regroup the
     * planes into samples. Works for either byte order.
     */
    private fun unpredictFloatingPoint(
        bytes: ByteArray,
        rowStart: Int,
        width: Int,
        samplesPerPixel: Int,
        bytesPerSample: Int
    ) {
        val rowSamples = width * samplesPerPixel
        val rowLength = rowSamples * bytesPerSample
        if (rowLength <= 0) return

        // Step 1: undo the horizontal byte differencing across the whole row.
        for (i in samplesPerPixel until rowLength) {
            val pos = rowStart + i
            bytes[pos] = (bytes[pos] + bytes[pos - samplesPerPixel]).toByte()
        }

        // Step 2: undo the byte-plane shuffle. libtiff writes the planes in the
        // reverse of the file's byte order (checked against GDAL-written files in
        // GdalWrittenGeoTiffTest), so taking them in reverse gives each sample in the
        // file's byte order.
        val shuffled = bytes.copyOfRange(rowStart, rowStart + rowLength)
        for (sample in 0 until rowSamples) {
            for (b in 0 until bytesPerSample) {
                bytes[rowStart + sample * bytesPerSample + b] =
                    shuffled[(bytesPerSample - 1 - b) * rowSamples + sample]
            }
        }
    }

    override fun close() {
        cache.clear()
    }

    private fun scalarEntry(tag: FieldTagType, value: Int): FileDirectoryEntry =
        FileDirectoryEntry(tag, FieldType.SHORT, 1L, value)

    private fun listEntry(tag: FieldTagType, values: List<Int>): FileDirectoryEntry =
        FileDirectoryEntry(tag, FieldType.SHORT, values.size.toLong(), ArrayList(values))

    private fun longListEntry(tag: FieldTagType, values: List<Long>): FileDirectoryEntry =
        FileDirectoryEntry(tag, FieldType.LONG, values.size.toLong(), ArrayList(values))

    private fun ceilDiv(numerator: Int, denominator: Int): Int =
        if (denominator <= 0) 1 else (numerator + denominator - 1) / denominator

    companion object {
        /** Ceiling on decoded blocks held by one reader's LRU. */
        internal const val CACHE_BUDGET_BYTES = 64L * 1024 * 1024

        /** The pre-existing fixed capacity, kept as a floor for small blocks (see [blockCacheCapacity]). */
        internal const val MIN_CACHE_BLOCKS = 8

        /**
         * One full row of blocks plus one, never below [MIN_CACHE_BLOCKS]
         * (a reprojected output row can straddle several strips), never
         * more than [CACHE_BUDGET_BYTES] worth, and always at least 1.
         */
        internal fun blockCacheCapacity(blocksPerRow: Int, blockBytes: Long): Int {
            val wanted = maxOf(MIN_CACHE_BLOCKS, blocksPerRow + 1)
            val affordable = if (blockBytes <= 0L) wanted.toLong() else CACHE_BUDGET_BYTES / blockBytes
            return minOf(wanted.toLong(), affordable).coerceAtLeast(1L).toInt()
        }

        private const val TAG = "TiffBlockReader"
        private const val PROGRESS_LOG_EVERY_BLOCKS = 50
        private const val PROGRESS_LOG_INTERVAL_MS = 2000L
        private const val PLANAR_CHUNKY = 1
        private const val PLANAR_SEPARATE = 2
        private const val DEFAULT_BITS_PER_SAMPLE = 8
        private const val DEFAULT_SAMPLE_FORMAT = 1
        private const val PREDICTOR_NONE = 1
        private const val PREDICTOR_HORIZONTAL = 2
        private const val PREDICTOR_FLOATING_POINT = 3
        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_DEFLATE = 8
        private const val COMPRESSION_ADOBE_DEFLATE = 32946
        private val HORIZONTAL_SUPPORTED_SAMPLE_SIZES = intArrayOf(1, 2, 4)
    }
}
