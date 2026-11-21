package au.opendepth.app.storage

import au.opendepth.app.MainActivity
import au.opendepth.app.map.OverlayLayerController
import java.io.File
import java.nio.file.Files

/**
 * What the app keeps on the phone and how big each part is, for Settings →
 * Storage, and the clearing of its temporary files. It walks real folders, so
 * every call belongs off the main thread. Nothing here touches Android: the
 * caller says where the folders are ([Places]), and a test uses a temp folder.
 */
object StorageUsage {

    /** The rows of the Storage screen. Every file is counted in exactly one. */
    enum class Group {
        /** Charts and surveys copied into the app, and depth downloads. */
        CHARTS,
        /** The drawn tiles of big surveys: kept so they redraw fast, and removed with their layer. */
        SURVEY_TILES,
        /** Offline map downloads. */
        OFFLINE_MAPS,
        /** Sea conditions and tides saved for use offline. */
        CONDITIONS,
        /** NSW reef outlines kept from looking at the coast with the layer on. */
        REEF_OUTLINES,
        /** Waypoint photos. */
        PHOTOS,
        /** Recorded tracks. */
        TRACKS,
        /** Caches: online map tiles kept from viewing, and working files. */
        TEMPORARY,
        /** The rest: settings, the debug log, data the app needs to run. */
        OTHER
    }

    /** Where the app keeps things on this phone. */
    class Places(
        /** The app's files folder. */
        val filesDir: File,
        /** The app's cache folder. */
        val cacheDir: File,
        /** Where offline maps are saved: it differs by build, and may be outside the app's own folders. */
        val offlineMapsDir: File,
        /** The folder holding the chart's own cache of online map tiles ([MAP_CACHE_PREFIX] files): usually [filesDir]. */
        val mapCacheDir: File,
        /** The app's other folders, for the total: its whole private folder, and its folder on shared storage. */
        val otherRoots: List<File> = emptyList()
    )

    class Report(private val bytes: Map<Group, Long>, val freeBytes: Long) {
        operator fun get(group: Group): Long = bytes[group] ?: 0L
        val totalBytes: Long get() = bytes.values.sum()
    }

    /** Folders under the files folder, by the row they are counted in. Anything else there is [Group.OTHER]. */
    private val FILES_FOLDERS: Map<Group, List<String>> = mapOf(
        Group.CHARTS to listOf(
            OverlayLayerController.MBTILES_DIR,
            GeoTiffImporter.SURVEY_COPY_DIR,
            OverlayLayerController.SEABED_DIR,
            // Copies and pyramids made by builds before 1.0 (OverlayLayerController's legacy folders).
            "geotiff",
            "geotiff_pyramids"
        ),
        Group.SURVEY_TILES to listOf(OverlayLayerController.GEOTIFF_TILE_CACHE_DIR),
        // ConditionsStore and Coastline's folder, and TideStore's.
        Group.CONDITIONS to listOf("conditions", "tides"),
        Group.REEF_OUTLINES to listOf(au.opendepth.app.map.NswReefStore.DIR),
        Group.PHOTOS to listOf(MainActivity.PHOTOS_DIR),
        // TrackRecorder's folder.
        Group.TRACKS to listOf("tracks")
    )

    /** The chart's cache of online tiles is a database with this name, and its journal files beside it. */
    const val MAP_CACHE_PREFIX = "mbgl-offline.db"

    /** How big each part is, and the free space on the volume the app's files are on. */
    fun measure(places: Places): Report {
        val bytes = mutableMapOf<Group, Long>()
        // Folders already counted, so one inside another (the files folder inside the app's
        // private folder, offline maps inside its shared folder) is never counted twice.
        val counted = mutableSetOf<File>()

        fun count(group: Group, roots: List<File>) {
            val fresh = roots.map { canonical(it) }.filter { it !in counted }.distinct()
            // Claimed before any is walked, so one root nested in another isn't counted through it.
            val before = counted.toSet()
            counted += fresh
            bytes[group] = (bytes[group] ?: 0L) + fresh.sumOf { sizeOf(it, before + (fresh - it)) }
        }

        for ((group, names) in FILES_FOLDERS) count(group, names.map { File(places.filesDir, it) })
        count(Group.OFFLINE_MAPS, listOf(places.offlineMapsDir))
        count(Group.TEMPORARY, listOf(places.cacheDir) + mapCacheFiles(places))
        count(Group.OTHER, listOf(places.filesDir) + places.otherRoots)

        return Report(bytes, runCatching { places.filesDir.usableSpace }.getOrDefault(0L))
    }

    /**
     * Deletes working files left in the cache folder by an earlier run of the app: files last
     * written before [olderThanMillis] (when this run started), so nothing an import or download
     * running now is using. Top-level entries named in [leave] are not touched: caches that
     * are emptied through their own owner, or that something else has open. Folders stay.
     *
     * @return how many bytes were freed.
     */
    fun clearOldCacheFiles(places: Places, olderThanMillis: Long, leave: Set<String>): Long {
        var freed = 0L
        fun clear(file: File) {
            if (isLink(file)) return
            if (file.isDirectory) {
                file.listFiles()?.forEach(::clear)
            } else if (file.lastModified() < olderThanMillis) {
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        places.cacheDir.listFiles()?.filter { it.name !in leave }?.forEach(::clear)
        return freed
    }

    private fun mapCacheFiles(places: Places): List<File> =
        places.mapCacheDir.listFiles { file -> file.isFile && file.name.startsWith(MAP_CACHE_PREFIX) }?.toList().orEmpty()

    /** The bytes in [root] (a file or a folder), leaving out anything in [skip] and never following links. */
    private fun sizeOf(root: File, skip: Set<File>): Long {
        if (isLink(root)) return 0L
        if (root.isFile) return root.length()
        val children = root.listFiles() ?: return 0L
        return children.sumOf { child -> if (child.isDirectory && canonical(child) in skip || child.isFile && child in skip) 0L else sizeOf(child, skip) }
    }

    /** A link is skipped: the private folder's `lib` points at the installed app, which isn't the app's data. */
    private fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
}
