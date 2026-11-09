package au.opendepth.app.geotiff

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One pyramid tile's depths (metres, negative down, [DepthGrid.NO_DATA] for
 * none) as little-endian `Float32` bytes, row by row. Not compressed: it
 * saves little on depth values and would slow every depth lookup.
 */
object DepthTileCodec {

    fun encode(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        return buffer.array()
    }

    /** @throws IllegalArgumentException if [bytes] is not an exact multiple of 4. */
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0) {
            "Tile blob size ${bytes.size} is not a whole number of Float32 samples"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat(it * Float.SIZE_BYTES) }
    }
}
