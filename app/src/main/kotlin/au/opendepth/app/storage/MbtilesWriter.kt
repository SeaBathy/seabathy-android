package au.opendepth.app.storage

import au.opendepth.app.diagnostics.ResourceTracker
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import au.opendepth.app.model.GeoBounds
import java.io.File

/**
 * Writes a standard MBTiles file from downloaded tiles
 * (`au.opendepth.app.download.TileDownloadRunner`), readable by this app and
 * other MBTiles readers. Rows are stored TMS style (0 at the south):
 * [tmsRowFor] is the inverse of the conversion `LocalTileServer` makes when
 * reading. SQLite, so only [tmsRowFor] is unit tested.
 */
class MbtilesWriter(file: File) : AutoCloseable {

    private val database: SQLiteDatabase =
        ResourceTracker.trackDatabase(SQLiteDatabase.openOrCreateDatabase(file, null))

    init {
        try {
            database.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            database.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)"
            )
            database.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
        } catch (error: Throwable) {
            // The caller's use {} never runs if construction throws.
            database.close()
            throw error
        }
    }

    fun writeMetadata(
        name: String,
        format: String,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int,
        attribution: String
    ) {
        fun put(key: String, value: String) {
            database.execSQL("INSERT INTO metadata (name, value) VALUES (?, ?)", arrayOf(key, value))
        }
        put("name", name)
        put("format", format)
        put("minzoom", minZoom.toString())
        put("maxzoom", maxZoom.toString())
        put("bounds", "${bounds.west},${bounds.south},${bounds.east},${bounds.north}")
        put("type", "baselayer")
        put("version", "1.1")
        if (attribution.isNotBlank()) put("attribution", attribution)
    }

    /** [x]/[y] are XYZ/Google-addressed (y=0 at north) — see class doc for the TMS conversion this applies before writing. */
    fun writeTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val values = ContentValues().apply {
            put("zoom_level", z)
            put("tile_column", x)
            put("tile_row", tmsRowFor(z, y))
            put("tile_data", data)
        }
        database.insertWithOnConflict("tiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Runs [block] in one transaction. Callers wrap each batch of tile
     * writes in this — without it, SQLite's default per-statement
     * auto-commit makes thousands of individual tile inserts extremely slow.
     */
    fun <T> inTransaction(block: () -> T): T {
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() = database.close()

    companion object {
        /** The MBTiles (TMS) row for XYZ row [xyzY] at zoom [z]. Its own inverse; `LocalTileServer` uses it to read. */
        fun tmsRowFor(z: Int, xyzY: Int): Int = (1 shl z) - 1 - xyzY
    }
}
