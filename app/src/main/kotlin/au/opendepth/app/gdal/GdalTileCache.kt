package au.opendepth.app.gdal

import au.opendepth.app.diagnostics.ResourceTracker
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The on-disk cache of rendered tiles for one [GdalTileSource]: PNG bytes
 * keyed by z/x/y, filled as tiles are asked for. SQLite, so not covered by
 * the JVM tests.
 */
class GdalTileCache private constructor(
    private val database: SQLiteDatabase,
    override val name: String
) : AutoCloseable, TileCacheBudget.Evictable {

    /** The cached PNG for tile (z, x, y), or null on a cache miss. */
    fun readTile(z: Int, x: Int, y: Int): ByteArray? {
        database.rawQuery(
            "SELECT data FROM tiles WHERE zoom_level = ? AND tile_col = ? AND tile_row = ? LIMIT 1",
            arrayOf(z.toString(), x.toString(), y.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getBlob(0)
        }
    }

    fun writeTile(z: Int, x: Int, y: Int, png: ByteArray) {
        database.execSQL(
            "INSERT OR REPLACE INTO tiles (zoom_level, tile_col, tile_row, data) VALUES (?, ?, ?, ?)",
            arrayOf(z, x, y, png)
        )
        TileCacheBudget.onWrite()
    }

    /** 0 once closed (its layer was removed mid-check); any other failure throws, for the budget check to log. */
    override fun liveBytes(): Long = if (!database.isOpen) 0L else TileCacheBudget.liveBytes(
        pageCount = pragmaLong("page_count"),
        freelistCount = pragmaLong("freelist_count"),
        pageSize = pragmaLong("page_size")
    )

    /** Oldest-written first: INSERT OR REPLACE gives every write a new, higher rowid. */
    /** 0 once closed (layer removed mid-check); a real DELETE failure (full disk, corruption) throws, for the budget check to log. */
    override fun evictOldest(rows: Int): Int = try {
        database.compileStatement(
            "DELETE FROM tiles WHERE rowid IN (SELECT rowid FROM tiles ORDER BY rowid LIMIT ?)"
        ).use { statement ->
            statement.bindLong(1, rows.toLong())
            statement.executeUpdateDelete()
        }
    } catch (error: IllegalStateException) {
        if (database.isOpen) throw error
        0
    }

    private fun pragmaLong(name: String): Long =
        database.rawQuery("PRAGMA $name", null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    /** Records what the cache now holds; see [open]'s contentKey. */
    fun setContentKey(contentKey: Int) {
        database.version = contentKey
    }

    /** Drops every cached tile — used when a depth-ramp change makes them all stale colour-wise. */
    fun clear() {
        database.execSQL("DELETE FROM tiles")
    }

    override fun close() {
        TileCacheBudget.unregister(this)
        database.close()
    }

    companion object {
        /** File extension for a GDAL on-demand tile cache — distinct from [TiledDepthPyramidStore.EXTENSION]'s eager float-sample pyramids. */
        const val EXTENSION = "gdaltiles"

        /**
         * [contentKey] identifies what the tiles look like (renderer version
         * and tile size); a cache written under a different key — an older
         * app version, or another quality setting — is emptied on open
         * rather than serving tiles that no longer match.
         */
        fun open(file: File, contentKey: Int = 0): GdalTileCache {
            file.parentFile?.mkdirs()
            val database = ResourceTracker.trackDatabase(SQLiteDatabase.openOrCreateDatabase(file, null))
            try {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS tiles (" +
                        "zoom_level INTEGER NOT NULL, " +
                        "tile_col INTEGER NOT NULL, " +
                        "tile_row INTEGER NOT NULL, " +
                        "data BLOB NOT NULL, " +
                        "PRIMARY KEY (zoom_level, tile_col, tile_row))"
                )
                if (database.version != contentKey) {
                    database.execSQL("DELETE FROM tiles")
                    database.version = contentKey
                }
            } catch (error: Throwable) {
                // A full disk or corrupt file: don't leak the connection.
                database.close()
                throw error
            }
            return GdalTileCache(database, file.name).also { cache ->
                TileCacheBudget.register(cache, file.parentFile ?: file)
            }
        }
    }
}
