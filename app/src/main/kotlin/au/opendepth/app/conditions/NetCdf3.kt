package au.opendepth.app.conditions

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the variables of a NetCDF-3 "classic" file, the only table format
 * HYCOM's server sends for a grid of ocean temperatures. Just what that needs:
 * names, sizes, attributes and numbers.
 */
object NetCdf3 {

    class Variable(val shape: IntArray, val attributes: Map<String, Any>, val values: DoubleArray) {
        fun number(attribute: String): Double? = (attributes[attribute] as? DoubleArray)?.firstOrNull()
        fun text(attribute: String): String? = attributes[attribute] as? String
    }

    fun read(bytes: ByteArray): Map<String, Variable> {
        if (bytes.size < 8 || bytes[0] != 'C'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != 'F'.code.toByte()) {
            throw IOException("not a NetCDF-3 file")
        }
        val wideOffsets = bytes[3].toInt() == 2
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.position(4)
        val records = b.int

        fun name(): String {
            val length = b.int
            val text = String(bytes, b.position(), length, Charsets.UTF_8)
            b.position(b.position() + padded(length))
            return text
        }

        fun attributes(): Map<String, Any> {
            b.int // the tag, or 0 for none
            val count = b.int
            val out = HashMap<String, Any>()
            repeat(count) {
                val key = name()
                val type = b.int
                val size = b.int
                val start = b.position()
                out[key] = if (type == TYPE_CHAR) {
                    String(bytes, start, size, Charsets.UTF_8)
                } else {
                    DoubleArray(size) { i -> value(b, start, type, i) }
                }
                b.position(start + padded(size * sizeOf(type)))
            }
            return out
        }

        b.int // the dimensions' tag
        val dimensions = IntArray(b.int) {
            name()
            b.int.let { length -> if (length == 0) records else length }
        }
        attributes()
        b.int // the variables' tag
        val variables = LinkedHashMap<String, Variable>()
        repeat(b.int) {
            val name = name()
            val shape = IntArray(b.int) { dimensions[b.int] }
            val attrs = attributes()
            val type = b.int
            b.int // size
            val begin = if (wideOffsets) b.long.toInt() else b.int
            val count = shape.fold(1) { total, d -> total * d }
            if (begin < 0 || begin.toLong() + count.toLong() * sizeOf(type) > bytes.size) throw IOException("NetCDF file is cut short")
            variables[name] = Variable(shape, attrs, DoubleArray(count) { i -> value(b, begin, type, i) })
        }
        return variables
    }

    private fun padded(length: Int) = (length + 3) / 4 * 4

    private fun sizeOf(type: Int) = when (type) {
        TYPE_BYTE, TYPE_CHAR -> 1
        TYPE_SHORT -> 2
        TYPE_INT, TYPE_FLOAT -> 4
        TYPE_DOUBLE -> 8
        else -> throw IOException("NetCDF type $type")
    }

    private fun value(b: ByteBuffer, start: Int, type: Int, index: Int): Double = when (type) {
        TYPE_BYTE, TYPE_CHAR -> b.get(start + index).toDouble()
        TYPE_SHORT -> b.getShort(start + index * 2).toDouble()
        TYPE_INT -> b.getInt(start + index * 4).toDouble()
        TYPE_FLOAT -> b.getFloat(start + index * 4).toDouble()
        TYPE_DOUBLE -> b.getDouble(start + index * 8)
        else -> throw IOException("NetCDF type $type")
    }

    private const val TYPE_BYTE = 1
    private const val TYPE_CHAR = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_INT = 4
    private const val TYPE_FLOAT = 5
    private const val TYPE_DOUBLE = 6
}
