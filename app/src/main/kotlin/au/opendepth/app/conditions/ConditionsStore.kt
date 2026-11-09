package au.opendepth.app.conditions

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Saved sea-condition areas, one compressed file each in [dir], so they can
 * be shown with no connection. A few hundred KB for a week of an area.
 *
 * Reading and writing files happens off the main thread ([load], [save],
 * [rename], [delete], [summaries]); the main thread only ever reads the
 * list kept in memory ([cachedSummaries]), so it never waits on a disk or
 * on a download that is saving.
 */
class ConditionsStore(private val dir: File) {

    /** What the list shows without reading every file. */
    data class Summary(
        val id: String,
        val name: String,
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
        val fetchedMillis: Long,
        val forecastUntilMillis: Long?,
        val satellites: Set<SatelliteKind>,
        val bytes: Long
    ) {
        fun sameBoundsAs(s: Double, w: Double, n: Double, e: Double): Boolean =
            kotlin.math.abs(south - s) < 0.01 && kotlin.math.abs(west - w) < 0.01 &&
                kotlin.math.abs(north - n) < 0.01 && kotlin.math.abs(east - e) < 0.01
    }

    private val lock = Any()
    private val cache = HashMap<String, ConditionsArea>()

    @Volatile
    private var summaryCache: List<Summary>? = null

    /** The list as last read, without touching the disk: null until [summaries] has run once. */
    fun cachedSummaries(): List<Summary>? = summaryCache

    /** The saved areas, newest first (reads the files the first time). Off the main thread. */
    fun summaries(): List<Summary> = summaryCache ?: rescan()

    private fun rescan(): List<Summary> = files().mapNotNull { file ->
        runCatching { readSummary(file) }.getOrNull()
    }.sortedByDescending { it.fetchedMillis }.also { summaryCache = it }

    /** Off the main thread. */
    fun load(id: String): ConditionsArea? {
        synchronized(lock) { cache[id] }?.let { return it }
        val area = runCatching { file(id).takeIf { it.exists() }?.inputStream()?.use { read(it) } }.getOrNull() ?: return null
        synchronized(lock) { cache[id] = area }
        return area
    }

    /** Off the main thread. The file is written first, then swapped in, so a reader never sees half of it. */
    fun save(area: ConditionsArea) {
        dir.mkdirs()
        val temp = File(dir, "${area.id}.${System.nanoTime()}.tmp")
        try {
            temp.outputStream().use { write(area, it) }
            synchronized(lock) {
                val target = file(area.id)
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Couldn't save the sea conditions")
                }
                cache[area.id] = area
            }
        } finally {
            temp.delete()
        }
        rescan()
    }

    /** Off the main thread. */
    fun rename(id: String, name: String): ConditionsArea? {
        val area = load(id) ?: return null
        return ConditionsArea(area.id, name, area.south, area.west, area.north, area.east, area.fetchedMillis, area.forecast, area.satellites, area.belowDepthM)
            .also { save(it) }
    }

    /** Off the main thread. */
    fun delete(id: String) {
        synchronized(lock) {
            cache.remove(id)
            file(id).delete()
        }
        rescan()
    }

    private fun file(id: String) = File(dir, "$id$EXTENSION")

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(EXTENSION) }?.toList().orEmpty()

    private fun readSummary(file: File): Summary = DataInputStream(GZIPInputStream(file.inputStream(), BUFFER).buffered(BUFFER)).use { input ->
        val header = readHeader(input)
        var until: Long? = null
        if (input.readBoolean()) {
            readGrid(input)
            val hours = input.readInt()
            var last = 0L
            repeat(hours) { last = input.readLong() }
            until = last
        }
        val (south, west, north, east) = header.bounds
        // The satellite kinds are listed in the header so this needn't read the values.
        Summary(header.id, header.name, south, west, north, east, header.fetchedMillis, until, header.satelliteKinds, file.length())
    }

    private class Header(val id: String, val name: String, val bounds: DoubleArray, val fetchedMillis: Long, val satelliteKinds: Set<SatelliteKind>)

    private fun readHeader(input: DataInputStream): Header {
        if (input.readInt() != MAGIC) throw IOException("not a sea conditions file")
        val version = input.readInt()
        if (version != VERSION) throw IOException("sea conditions file version $version")
        val id = input.readUTF()
        val name = input.readUTF()
        val bounds = DoubleArray(4) { input.readDouble() }
        val fetched = input.readLong()
        val kinds = (0 until input.readInt()).map { SatelliteKind.valueOf(input.readUTF()) }.toSet()
        return Header(id, name, bounds, fetched, kinds)
    }

    companion object {
        private const val EXTENSION = ".sbc"
        private const val MAGIC = 0x53424344 // "SBCD"
        private const val VERSION = 1
        /** Big buffers: the default 512 bytes made reading an area many small steps (hundreds of ms on a phone). */
        private const val BUFFER = 64 * 1024

        fun write(area: ConditionsArea, stream: OutputStream) {
            DataOutputStream(GZIPOutputStream(stream, BUFFER).buffered(BUFFER)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(area.id)
                out.writeUTF(area.name)
                out.writeDouble(area.south)
                out.writeDouble(area.west)
                out.writeDouble(area.north)
                out.writeDouble(area.east)
                out.writeLong(area.fetchedMillis)
                out.writeInt(area.satellites.size)
                area.satellites.keys.forEach { out.writeUTF(it.name) }

                val forecast = area.forecast
                out.writeBoolean(forecast != null)
                if (forecast != null) {
                    writeGrid(out, forecast.grid)
                    out.writeInt(forecast.times.size)
                    forecast.times.forEach { out.writeLong(it) }
                    out.writeInt(forecast.fields.size)
                    for ((field, values) in forecast.fields) {
                        out.writeUTF(field.name)
                        writeFloats(out, values)
                    }
                }
                for ((kind, sat) in area.satellites) {
                    out.writeUTF(kind.name)
                    writeGrid(out, sat.grid)
                    out.writeLong(sat.dateMillis)
                    writeFloats(out, sat.values)
                }
                // After everything, so files from before this was kept still read.
                out.writeInt(area.belowDepthM)
            }
        }

        fun read(stream: InputStream): ConditionsArea =
            DataInputStream(GZIPInputStream(stream, BUFFER).buffered(BUFFER)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("not a sea conditions file")
                val version = input.readInt()
                if (version != VERSION) throw IOException("sea conditions file version $version")
                val id = input.readUTF()
                val name = input.readUTF()
                val south = input.readDouble()
                val west = input.readDouble()
                val north = input.readDouble()
                val east = input.readDouble()
                val fetched = input.readLong()
                val kindCount = input.readInt()
                repeat(kindCount) { input.readUTF() }

                val forecast = if (input.readBoolean()) {
                    val grid = readGrid(input)
                    val times = LongArray(input.readInt()) { input.readLong() }
                    val fields = HashMap<ForecastField, FloatArray>()
                    repeat(input.readInt()) {
                        val name = input.readUTF()
                        val values = readFloats(input)
                        // A field from a newer version is skipped rather than failing the file.
                        runCatching { ForecastField.valueOf(name) }.getOrNull()?.let { fields[it] = values }
                    }
                    ForecastGrid(grid, times, fields)
                } else {
                    null
                }
                val satellites = LinkedHashMap<SatelliteKind, SatelliteGrid>()
                repeat(kindCount) {
                    val kind = SatelliteKind.valueOf(input.readUTF())
                    val grid = readGrid(input)
                    val date = input.readLong()
                    satellites[kind] = SatelliteGrid(kind, grid, date, readFloats(input))
                }
                val belowDepth = runCatching { input.readInt() }.getOrDefault(ConditionsArea.DEFAULT_BELOW_DEPTH_M)
                ConditionsArea(id, name, south, west, north, east, fetched, forecast, satellites, belowDepth)
            }

        private fun writeGrid(out: DataOutputStream, grid: GeoGrid) {
            out.writeInt(grid.rows)
            grid.lats.forEach { out.writeDouble(it) }
            out.writeInt(grid.cols)
            grid.lngs.forEach { out.writeDouble(it) }
        }

        private fun readGrid(input: DataInputStream): GeoGrid {
            val lats = DoubleArray(input.readInt()) { input.readDouble() }
            val lngs = DoubleArray(input.readInt()) { input.readDouble() }
            return GeoGrid(lats, lngs)
        }

        // In one block each way: a week's forecast is about 700,000 numbers,
        // and reading them one at a time made opening an area slow on a phone.
        private fun writeFloats(out: DataOutputStream, values: FloatArray) {
            out.writeInt(values.size)
            val bytes = java.nio.ByteBuffer.allocate(values.size * 4)
            bytes.asFloatBuffer().put(values)
            out.write(bytes.array())
        }

        private fun readFloats(input: DataInputStream): FloatArray {
            val bytes = ByteArray(input.readInt() * 4)
            input.readFully(bytes)
            return FloatArray(bytes.size / 4).also { java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().get(it) }
        }
    }
}
