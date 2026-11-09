package au.opendepth.app.storage

import au.opendepth.app.diagnostics.ResourceTracker
import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.net.Uri
import android.provider.OpenableColumns
import au.opendepth.app.diagnostics.DiagnosticLog
import java.io.File
import java.io.IOException

/**
 * Checks and opens a picked MBTiles file. When [RealPathResolver] finds a
 * readable path, the file is used in place ([importFromLocalFile]), like an
 * offline download. Otherwise (no all-files access, or a cloud provider) it
 * is copied into `filesDir/mbtiles` with [PrivateFileImporter], since SQLite
 * needs a path; that copy is deleted with its layer.
 */
class MbtilesImporter(
    private val contentResolver: ContentResolver,
    destinationDir: File,
    private val context: Context? = null
) {
    data class ImportedMbtiles(
        val displayName: String,
        val file: File,
        val database: SQLiteDatabase
    )

    private val fileImporter = PrivateFileImporter(
        contentResolver = contentResolver,
        destinationDir = destinationDir,
        extension = EXTENSION,
        fallbackPrefix = FALLBACK_PREFIX,
        context = context
    )

    fun import(uri: Uri): ImportedMbtiles {
        val realPath = context?.let { RealPathResolver.resolve(it, uri) }
            ?.takeIf { File(it).canRead() }
        if (realPath != null) {
            try {
                DiagnosticLog.log(TAG, "opening in place, no copy: uri=$uri realPath=$realPath")
                return importFromLocalFile(realPath, displayNameFor(uri, realPath))
            } catch (error: SQLiteCantOpenDatabaseException) {
                // Readable but can't be opened in place (say a WAL database in a folder where
                // SQLite can't make its -shm file): a private copy can still work. Other
                // errors (a damaged or non-MBTiles file) are passed on: a copy won't help.
                DiagnosticLog.logError(TAG, "in-place open failed; falling back to a private copy", error)
            }
        }
        return importViaPrivateCopy(uri)
    }

    private fun importViaPrivateCopy(uri: Uri): ImportedMbtiles {
        val imported = fileImporter.import(uri) { candidate ->
            openReadOnly(candidate).use { database -> validateMbtiles(database) }
        }

        val database = try {
            openReadOnly(imported.file)
        } catch (error: Throwable) {
            imported.file.delete()
            throw error
        }

        return ImportedMbtiles(
            displayName = imported.displayName,
            file = imported.file,
            database = database
        )
    }

    /**
     * Opens and validates the MBTiles file at a known local [path] in place,
     * with no copy — used for a real path resolved by [import] and for
     * offline downloads written by `TileDownloadRunner`.
     */
    fun importFromLocalFile(path: String, displayName: String): ImportedMbtiles {
        val file = File(path)
        val database = openReadOnly(file)
        try {
            validateMbtiles(database)
        } catch (error: Throwable) {
            database.close()
            throw error
        }

        return ImportedMbtiles(displayName = displayName, file = file, database = database)
    }

    private fun validateMbtiles(database: SQLiteDatabase) {
        val hasTilesObject = database.rawQuery(
            """
            SELECT 1
            FROM sqlite_master
            WHERE name = 'tiles'
              AND type IN ('table', 'view')
            LIMIT 1
            """.trimIndent(),
            null
        ).use { cursor -> cursor.moveToFirst() }

        if (!hasTilesObject) {
            throw IOException("This MBTiles database has no tiles table or view")
        }

        val foundColumns = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(tiles)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) foundColumns += cursor.getString(nameIndex)
            }
        }

        if (foundColumns.containsAll(REQUIRED_COLUMNS)) return

        /*
         * Some valid MBTiles implementations expose `tiles` as a view. PRAGMA
         * table_info normally works for views, but if a provider behaves
         * differently, selecting the expected columns directly is the final
         * compatibility check.
         */
        try {
            database.rawQuery(
                """
                SELECT zoom_level, tile_column, tile_row, tile_data
                FROM tiles
                LIMIT 1
                """.trimIndent(),
                null
            ).use { cursor -> cursor.moveToFirst() }
        } catch (error: Exception) {
            throw IOException(
                "Invalid MBTiles tiles structure. Missing: " +
                    (REQUIRED_COLUMNS - foundColumns).joinToString(),
                error
            )
        }
    }

    private fun displayNameFor(uri: Uri, realPath: String): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.substringBeforeLast('.')
        ?: File(realPath).nameWithoutExtension

    companion object {
        private const val TAG = "MbtilesImporter"
        const val EXTENSION = "mbtiles"
        private const val FALLBACK_PREFIX = "layer"

        private val REQUIRED_COLUMNS = setOf(
            "zoom_level",
            "tile_column",
            "tile_row",
            "tile_data"
        )

        fun openReadOnly(file: File): SQLiteDatabase = ResourceTracker.trackDatabase(
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        )
    }
}
