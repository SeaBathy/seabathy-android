package au.opendepth.app.geotiff

import au.opendepth.app.diagnostics.ResourceTracker
import android.database.sqlite.SQLiteDatabase
import au.opendepth.app.model.GeoBounds
import java.io.File

/**
 * The SQLite file behind [TiledDepthPyramid]: one row per zoom, column and
 * row, like MBTiles, but holding depths ([DepthTileCodec]) rather than
 * images. SQLite, so not covered by the JVM tests; [PyramidGeometry] and
 * [DepthTileCodec] are.
 */
internal class TiledDepthPyramidStore private constructor(
    val file: File,
    private val database: SQLiteDatabase
) : AutoCloseable {

    val geometry: PyramidGeometry = PyramidGeometry(
        sourceWidth = metadataInt(KEY_SOURCE_WIDTH),
        sourceHeight = metadataInt(KEY_SOURCE_HEIGHT),
        tileSize = metadataInt(KEY_TILE_SIZE)
    )

    val bounds: GeoBounds = GeoBounds(
        west = metadataDouble(KEY_BOUNDS_WEST),
        south = metadataDouble(KEY_BOUNDS_SOUTH),
        east = metadataDouble(KEY_BOUNDS_EAST),
        north = metadataDouble(KEY_BOUNDS_NORTH)
    )

    val shallowestMetres: Double = metadataDouble(KEY_SHALLOWEST_METRES)
    val deepestMetres: Double = metadataDouble(KEY_DEEPEST_METRES)
    val projectionLabel: String = metadataString(KEY_PROJECTION_LABEL) ?: ""

    /** Writes (or overwrites) one tile's samples. [values] must be [PyramidGeometry.tilePixelSize]-sized. */
    fun writeTile(zoom: Int, tileCol: Int, tileRow: Int, values: FloatArray) {
        database.execSQL(
            "INSERT OR REPLACE INTO tiles (zoom_level, tile_col, tile_row, data) VALUES (?, ?, ?, ?)",
            arrayOf(zoom, tileCol, tileRow, DepthTileCodec.encode(values))
        )
    }

    /** The decoded samples for one tile, or null if it was never written (e.g. entirely out of coverage). */
    fun readTile(zoom: Int, tileCol: Int, tileRow: Int): FloatArray? {
        database.rawQuery(
            "SELECT data FROM tiles WHERE zoom_level = ? AND tile_col = ? AND tile_row = ? LIMIT 1",
            arrayOf(zoom.toString(), tileCol.toString(), tileRow.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DepthTileCodec.decode(cursor.getBlob(0))
        }
    }

    override fun close() {
        database.close()
    }

    private fun metadataInt(key: String): Int = metadataString(key)?.toIntOrNull()
        ?: error("Missing or invalid pyramid metadata: $key")

    private fun metadataDouble(key: String): Double = metadataString(key)?.toDoubleOrNull()
        ?: error("Missing or invalid pyramid metadata: $key")

    private fun metadataString(key: String): String? = database.rawQuery(
        "SELECT value FROM metadata WHERE name = ? LIMIT 1",
        arrayOf(key)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    companion object {
        private const val KEY_SOURCE_WIDTH = "source_width"
        private const val KEY_SOURCE_HEIGHT = "source_height"
        private const val KEY_TILE_SIZE = "tile_size"
        private const val KEY_BOUNDS_WEST = "bounds_west"
        private const val KEY_BOUNDS_SOUTH = "bounds_south"
        private const val KEY_BOUNDS_EAST = "bounds_east"
        private const val KEY_BOUNDS_NORTH = "bounds_north"
        private const val KEY_SHALLOWEST_METRES = "shallowest_metres"
        private const val KEY_DEEPEST_METRES = "deepest_metres"
        private const val KEY_PROJECTION_LABEL = "projection_label"
        private const val KEY_FORMAT_VERSION = "format_version"
        private const val FORMAT_VERSION = "1"

        /** File extension used for a pyramid this app generated, so restore can tell it apart from a raw TIFF copy. */
        const val EXTENSION = "sbdp"

        /** Creates a new, empty pyramid file with its schema and metadata written, ready for [writeTile]. */
        fun create(
            file: File,
            geometry: PyramidGeometry,
            bounds: GeoBounds,
            shallowestMetres: Double,
            deepestMetres: Double,
            projectionLabel: String
        ): TiledDepthPyramidStore {
            file.parentFile?.mkdirs()
            file.delete()
            val database = ResourceTracker.trackDatabase(SQLiteDatabase.openOrCreateDatabase(file, null))

            database.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
            database.execSQL(
                "CREATE TABLE tiles (" +
                    "zoom_level INTEGER NOT NULL, " +
                    "tile_col INTEGER NOT NULL, " +
                    "tile_row INTEGER NOT NULL, " +
                    "data BLOB NOT NULL, " +
                    "PRIMARY KEY (zoom_level, tile_col, tile_row))"
            )

            fun put(name: String, value: String) = database.execSQL(
                "INSERT INTO metadata (name, value) VALUES (?, ?)",
                arrayOf(name, value)
            )
            put(KEY_FORMAT_VERSION, FORMAT_VERSION)
            put(KEY_SOURCE_WIDTH, geometry.sourceWidth.toString())
            put(KEY_SOURCE_HEIGHT, geometry.sourceHeight.toString())
            put(KEY_TILE_SIZE, geometry.tileSize.toString())
            put(KEY_BOUNDS_WEST, bounds.west.toString())
            put(KEY_BOUNDS_SOUTH, bounds.south.toString())
            put(KEY_BOUNDS_EAST, bounds.east.toString())
            put(KEY_BOUNDS_NORTH, bounds.north.toString())
            put(KEY_SHALLOWEST_METRES, shallowestMetres.toString())
            put(KEY_DEEPEST_METRES, deepestMetres.toString())
            put(KEY_PROJECTION_LABEL, projectionLabel)

            return TiledDepthPyramidStore(file, database)
        }

        /** Opens a pyramid file written by [create], for rendering/querying. */
        fun open(file: File): TiledDepthPyramidStore {
            val database = ResourceTracker.trackDatabase(
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            )
            // The constructor reads metadata and throws on a corrupt or
            // truncated pyramid; the connection would otherwise leak.
            return try {
                TiledDepthPyramidStore(file, database)
            } catch (error: Throwable) {
                database.close()
                throw error
            }
        }
    }
}
