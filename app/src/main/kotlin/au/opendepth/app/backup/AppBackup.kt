package au.opendepth.app.backup

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One file with everything the user made: waypoints, routes, tracks,
 * waypoint photos and settings. Map files (surveys, chart packs) and
 * downloads that can be fetched again (tides, sea conditions) are left out:
 * they are big, and the layer list points at this phone's own copies.
 *
 * The file is a zip: [MANIFEST] holds the settings and a summary, and the
 * folders in [DIRS] are copied in as they are. A backup from SeaBathy
 * Desktop holds only its waypoints and routes, which are added to the
 * phone's own.
 */
object AppBackup {

    const val FORMAT = 1
    const val MANIFEST = "seabathy-backup.json"
    /** Folders under the app's files folder that go into a backup. */
    val DIRS = listOf("tracks", "photos")
    const val KEY_FOLDER = "backup_folder_uri"
    const val KEY_LAST = "backup_last_millis"
    const val KEY_WAYPOINTS = "wpts"
    const val KEY_ROUTES = "saved_routes"
    private const val PENDING = "restore-pending.zip"

    /** Settings that point at this phone's own files and folders, or are only a cache. */
    private val SKIP = setOf("overlay_layers_v2", "mbt_layers", "conditions_preview", KEY_FOLDER, KEY_LAST)

    class Summary(val app: String, val createdMillis: Long, val waypoints: Int, val routes: Int, val tracks: Int, val photos: Int)

    private val gson = Gson()

    fun write(prefs: SharedPreferences, filesDir: File, out: OutputStream, appVersion: String, nowMillis: Long = System.currentTimeMillis()) {
        val settings = JsonObject()
        for ((key, value) in prefs.all) {
            if (key in SKIP || value == null) continue
            val entry = JsonObject()
            when (value) {
                is String -> { entry.addProperty("t", "s"); entry.addProperty("v", value) }
                is Boolean -> { entry.addProperty("t", "b"); entry.addProperty("v", value) }
                is Int -> { entry.addProperty("t", "i"); entry.addProperty("v", value) }
                is Long -> { entry.addProperty("t", "l"); entry.addProperty("v", value) }
                is Float -> { entry.addProperty("t", "f"); entry.addProperty("v", value) }
                is Set<*> -> { entry.addProperty("t", "set"); entry.add("v", JsonArray().apply { value.forEach { add(it.toString()) } }) }
                else -> continue
            }
            settings.add(key, entry)
        }
        val files = DIRS.flatMap { dir -> File(filesDir, dir).listFiles()?.filter { it.isFile }?.map { "$dir/${it.name}" to it } ?: emptyList() }
        val manifest = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("app", "android")
            addProperty("appVersion", appVersion)
            addProperty("created", nowMillis)
            add("settings", settings)
            addProperty("waypoints", count(prefs.getString(KEY_WAYPOINTS, null)))
            addProperty("routes", count(prefs.getString(KEY_ROUTES, null)))
            addProperty("tracks", files.count { it.first.startsWith("tracks/") && it.first.endsWith(".csv") })
            addProperty("photos", files.count { it.first.startsWith("photos/") })
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(gson.toJson(manifest).toByteArray())
            zip.closeEntry()
            for ((name, file) in files) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Checks [input] is a SeaBathy backup and keeps a copy to restore at the
     * next start (see [applyPending]); null when it isn't one.
     */
    fun stage(input: InputStream, filesDir: File): Summary? {
        val pending = File(filesDir, PENDING)
        val temp = File(filesDir, "$PENDING.tmp")
        temp.outputStream().use { input.copyTo(it) }
        val summary = runCatching { summarise(temp) }.getOrNull()
        if (summary == null) {
            temp.delete()
            return null
        }
        pending.delete()
        temp.renameTo(pending)
        return summary
    }

    fun cancelPending(filesDir: File) {
        File(filesDir, PENDING).delete()
    }

    /** Reads the summary of a backup file, or throws if it isn't one. */
    fun summarise(file: File): Summary {
        val manifest = readManifest(file) ?: error("not a SeaBathy backup")
        val format = manifest.get("format")?.asInt ?: 0
        require(format in 1..FORMAT) { "made by a newer SeaBathy" }
        val app = manifest.get("app")?.asString ?: error("no app")
        require(app == "android" || app == "desktop") { "unknown app" }
        var tracks = 0
        var photos = 0
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.startsWith("tracks/") && e.name.endsWith(".csv")) tracks++
                if (e.name.startsWith("photos/")) photos++
            }
        }
        val portable = manifest.getAsJsonObject("portable")
        return Summary(
            app = app,
            createdMillis = manifest.get("created")?.asLong ?: 0L,
            waypoints = manifest.get("waypoints")?.asInt ?: count(portable?.get(KEY_WAYPOINTS)?.asString),
            routes = manifest.get("routes")?.asInt ?: count(portable?.get(KEY_ROUTES)?.asString),
            tracks = tracks,
            photos = photos
        )
    }

    /**
     * Restores a backup staged by [stage], before anything has read the
     * settings. A phone backup replaces the settings, tracks and photos;
     * a Desktop one adds its waypoints and routes. Returns true if one was applied.
     */
    fun applyPending(prefs: SharedPreferences, filesDir: File): Boolean {
        val pending = File(filesDir, PENDING)
        if (!pending.exists()) return false
        try {
            val manifest = readManifest(pending) ?: return false
            if (manifest.get("app")?.asString == "desktop") {
                val portable = manifest.getAsJsonObject("portable") ?: return false
                val edit = prefs.edit()
                edit.putString(KEY_WAYPOINTS, mergeById(prefs.getString(KEY_WAYPOINTS, null), portable.get(KEY_WAYPOINTS)?.asString))
                edit.putString(KEY_ROUTES, mergeById(prefs.getString(KEY_ROUTES, null), portable.get(KEY_ROUTES)?.asString))
                edit.commit()
                return true
            }
            val settings = manifest.getAsJsonObject("settings") ?: return false
            val keep = prefs.all.filterKeys { it in SKIP }
            val edit = prefs.edit().clear()
            for ((key, value) in keep) putAny(edit, key, value)
            for ((key, element) in settings.entrySet()) {
                if (key in SKIP) continue
                val entry = element.asJsonObject
                val v = entry.get("v")
                when (entry.get("t")?.asString) {
                    "s" -> edit.putString(key, v.asString)
                    "b" -> edit.putBoolean(key, v.asBoolean)
                    "i" -> edit.putInt(key, v.asInt)
                    "l" -> edit.putLong(key, v.asLong)
                    "f" -> edit.putFloat(key, v.asFloat)
                    "set" -> edit.putStringSet(key, v.asJsonArray.map { it.asString }.toSet())
                }
            }
            edit.commit()
            for (dir in DIRS) File(filesDir, dir).deleteRecursively()
            ZipInputStream(pending.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val dir = e.name.substringBefore('/', "")
                    val name = e.name.substringAfter('/', "")
                    // Only plain files in the known folders: nothing can be written anywhere else.
                    if (e.isDirectory || dir !in DIRS || name.isEmpty() || name.contains('/') || name.contains("..")) continue
                    val target = File(File(filesDir, dir).apply { mkdirs() }, name)
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            return true
        } finally {
            pending.delete()
        }
    }

    private fun readManifest(file: File): JsonObject? {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (e.name == MANIFEST) return JsonParser.parseString(zip.readBytes().decodeToString()).asJsonObject
            }
        }
    }

    private fun count(json: String?): Int =
        json?.let { runCatching { JsonParser.parseString(it).asJsonArray.size() }.getOrNull() } ?: 0

    /** [ours] with [theirs] added, skipping any whose id is already there. */
    internal fun mergeById(ours: String?, theirs: String?): String {
        val list = ours?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() } ?: JsonArray()
        val ids = list.mapNotNull { it.asJsonObject.get("id")?.asString }.toMutableSet()
        val add = theirs?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() } ?: JsonArray()
        for (item in add) {
            val id = item.asJsonObject.get("id")?.asString ?: continue
            if (ids.add(id)) list.add(item)
        }
        return gson.toJson(list)
    }

    private fun putAny(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it.toString() }.toSet())
        }
    }
}
