package au.opendepth.app.gdal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Packs a tile's depths for [GdalTileSource]'s values cache: 16-bit steps
 * between the tile's own lowest and highest depth (under 2 cm apart even
 * for a 1000 m range — finer than any survey), gaps as one reserved step,
 * then deflated (fast setting; the gaps and smooth seabed pack well).
 * Pure Kotlin, unit-tested.
 */
object TileValueCodec {
    /** Bumped if the layout changes, so old caches are emptied (see [GdalTileSource.valuesCacheKey]). */
    const val VERSION = 2

    private const val GAP: Int = 0xFFFF
    private const val STEPS = 0xFFFE
    private const val HEADER_BYTES = 12

    fun encode(values: FloatArray): ByteArray {
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isNaN()) continue
            if (v < low) low = v
            if (v > high) high = v
        }
        val any = low <= high
        val step = if (any && high > low) (high - low) / STEPS else 1f
        val raw = ByteBuffer.allocate(HEADER_BYTES + values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        raw.putInt(values.size)
        raw.putFloat(if (any) low else 0f)
        raw.putFloat(step)
        for (v in values) {
            val code = if (v.isNaN()) GAP else ((v - low) / step).toInt().coerceIn(0, STEPS)
            raw.putShort(code.toShort())
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw.array())
            deflater.finish()
            val out = ByteArrayOutputStream(values.size / 2 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** The depths back, or null if [bytes] isn't a tile of [expectedCount] values. */
    fun decode(bytes: ByteArray, expectedCount: Int): FloatArray? {
        val raw = ByteArray(HEADER_BYTES + expectedCount * 2)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            var done = 0
            while (done < raw.size && !inflater.finished()) {
                val n = inflater.inflate(raw, done, raw.size - done)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                done += n
            }
            if (done != raw.size) return null
        } catch (error: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != expectedCount) return null
        val low = buffer.float
        val step = buffer.float
        return FloatArray(expectedCount) {
            val code = buffer.short.toInt() and 0xFFFF
            if (code == GAP) Float.NaN else low + code * step
        }
    }
}
