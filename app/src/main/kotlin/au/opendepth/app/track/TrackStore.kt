package au.opendepth.app.track

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileWriter

/**
 * Saved tracks on disk: each track's points in "<id>.csv" (appended to while
 * recording) and a small index of names and totals in "index.json".
 */
class TrackStore(private val dir: File, private val gson: Gson = Gson()) {

    private var index: MutableList<TrackSummary>? = null

    @Synchronized
    fun summaries(): List<TrackSummary> = loadIndex().sortedByDescending { it.startMillis }

    @Synchronized
    fun summary(id: String): TrackSummary? = loadIndex().firstOrNull { it.id == id }

    @Synchronized
    fun points(id: String): List<TrackPoint> =
        pointsFile(id).takeIf { it.exists() }?.let { TrackCodec.parse(it.readText()) } ?: emptyList()

    @Synchronized
    fun append(id: String, point: TrackPoint) {
        dir.mkdirs()
        FileWriter(pointsFile(id), true).use { it.write(TrackCodec.line(point)) }
    }

    /**
     * Files a finished recording under [name]. A track with fewer than two
     * points (the boat never moved) is thrown away and null returned.
     */
    @Synchronized
    fun finish(id: String, name: String): TrackSummary? {
        val points = points(id)
        if (points.size < 2) {
            pointsFile(id).delete()
            return null
        }
        return put(TrackMath.summarise(id, name, points))
    }

    /** Saves [points] as a new track (from a GPX file). */
    @Synchronized
    fun add(id: String, name: String, points: List<TrackPoint>): TrackSummary? {
        if (points.size < 2) return null
        dir.mkdirs()
        pointsFile(id).writeText(buildString { points.forEach { append(TrackCodec.line(it)) } })
        return put(TrackMath.summarise(id, name, points))
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val list = loadIndex()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(name = name)
        saveIndex()
    }

    @Synchronized
    fun delete(id: String) {
        pointsFile(id).delete()
        if (loadIndex().removeAll { it.id == id }) saveIndex()
    }

    @Synchronized
    fun isNameTaken(name: String, exceptId: String? = null): Boolean =
        loadIndex().any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun put(summary: TrackSummary): TrackSummary {
        val list = loadIndex()
        list.removeAll { it.id == summary.id }
        list += summary
        saveIndex()
        return summary
    }

    private fun pointsFile(id: String) = File(dir, "$id.csv")

    private fun loadIndex(): MutableList<TrackSummary> {
        index?.let { return it }
        val file = File(dir, INDEX)
        val loaded = runCatching {
            gson.fromJson<List<TrackSummary>>(file.readText(), object : TypeToken<List<TrackSummary>>() {}.type)
        }.getOrNull().orEmpty()
            // Gson can leave "non-null" fields null in a damaged file.
            .filter { runCatching { it.id.isNotBlank() && it.name.isNotEmpty() }.getOrDefault(false) }
            .toMutableList()
        index = loaded
        return loaded
    }

    private fun saveIndex() {
        dir.mkdirs()
        // Written beside and renamed over, so a crash mid-write can't lose every track's name.
        val temp = File(dir, "$INDEX.tmp")
        temp.writeText(gson.toJson(index.orEmpty()))
        val target = File(dir, INDEX)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private companion object {
        const val INDEX = "index.json"
    }
}
