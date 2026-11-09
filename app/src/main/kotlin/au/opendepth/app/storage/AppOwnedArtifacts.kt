package au.opendepth.app.storage

import java.io.File

/**
 * Decides which files are the app's own (and safe to delete) rather than
 * the user's. Only direct children of [managedDirs] count: private MBTiles
 * copies, GDAL tile caches, and pre-1.0 GeoTIFF copies and pyramids. Paths
 * are compared canonically, so `..` and links can't point outside.
 *
 * A GDAL cache is several files with one base name (`.gdaltiles`, `.values`,
 * old `.dem`, and SQLite's `-journal`/`-wal`/`-shm`), handled together via
 * [baseNameOf]. Every method touches the disk: call them off the main thread.
 */
class AppOwnedArtifacts(filesDir: File, subdirectories: List<String>) {

    init {
        // "", "." or ".." would make filesDir itself (or its parent) deletable.
        require(subdirectories.all { it.isNotBlank() && '/' !in it && it != "." && it != ".." }) {
            "Managed directories must be plain subdirectory names: $subdirectories"
        }
    }

    // Lazy: canonicalising touches the disk, so it happens on the first
    // (IO-thread) call rather than wherever this object is constructed.
    private val managedDirs: List<File> by lazy {
        subdirectories.map { File(filesDir, it).canonicalFile }
    }

    /** True only for a file directly inside one of the managed directories. */
    fun isAppOwned(path: String): Boolean {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val parent = candidate.parentFile ?: return false
        return parent in managedDirs
    }

    /**
     * Deletes [path] and its sidecar family if, and only if, it is app-owned.
     * @return the files actually deleted (empty when refused or absent).
     */
    fun delete(path: String): List<File> {
        if (!isAppOwned(path)) return emptyList()
        val target = File(path).canonicalFile
        val dir = target.parentFile ?: return emptyList()
        val family = baseNameOf(target.name)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && baseNameOf(it.name) == family }
            .filter { it.delete() }
    }

    /**
     * Deletes files in the managed folders that no layer uses ([referencedPaths])
     * and that are older than [olderThanMillis], so an import still in progress
     * (its file written before its layer is saved) is left alone.
     */
    fun sweepOrphans(referencedPaths: Collection<String>, olderThanMillis: Long): List<File> {
        val referenced = referencedPaths
            .filter(::isAppOwned)
            .map { File(it).canonicalFile.let { file -> File(file.parentFile, baseNameOf(file.name)) } }
            .toSet()

        return managedDirs.flatMap { dir ->
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.lastModified() < olderThanMillis }
                .filter { File(dir, baseNameOf(it.name)) !in referenced }
                .filter { it.delete() }
        }
    }

    internal companion object {
        private val SQLITE_SIDECARS = listOf("-journal", "-wal", "-shm")
        private const val DEM_SUFFIX = ".dem"
        private const val VALUES_SUFFIX = ".values"

        /** `x.gdaltiles.dem-journal` -> `x.gdaltiles`; `x.gdaltiles.values` -> `x.gdaltiles`; `a.mbtiles-wal` -> `a.mbtiles`. */
        fun baseNameOf(name: String): String {
            var base = name
            SQLITE_SIDECARS.firstOrNull { base.endsWith(it) }?.let { base = base.removeSuffix(it) }
            if (base.endsWith(DEM_SUFFIX)) base = base.removeSuffix(DEM_SUFFIX)
            if (base.endsWith(VALUES_SUFFIX)) base = base.removeSuffix(VALUES_SUFFIX)
            return base
        }
    }
}
