package au.opendepth.app.geotiff

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Fills [buffer] from [position] with positioned reads, which don't move the
 * channel's cursor, so several threads can read one channel at once.
 */
internal fun FileChannel.readFullyAt(
    position: Long,
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size
) {
    val target = ByteBuffer.wrap(buffer, offset, length)
    var pos = position
    while (target.hasRemaining()) {
        val read = read(target, pos)
        if (read < 0) throw EOFException("Unexpected end of file at position $pos")
        pos += read
    }
}
