package au.opendepth.app.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.gdal.GdalTileCache
import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.geotiff.GeoTiffImportRouter
import au.opendepth.app.geotiff.GeoTiffReader
import au.opendepth.app.geotiff.TiffIfdReader
import au.opendepth.app.geotiff.readFullyAt
import mil.nga.tiff.FieldTagType
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * Opens a picked GeoTIFF from its real file path ([RealPathResolver]; the
 * app asks for all-files access for this, since GDAL can't open a
 * `content://` document).
 *
 * [GeoTiffImportRouter] decides: big files and BigTIFF open as a
 * [GdalTileSource] ([ImportedGeoTiff.OnDemand], quick, as only the size,
 * position and depth range are read); the rest are decoded by
 * [GeoTiffReader] ([ImportedGeoTiff.InMemory]), falling back to GDAL if that
 * fails. With no real path, only files up to
 * [SMALL_FILE_FALLBACK_MAX_BYTES] can be opened, from a private copy.
 *
 * [uri] must come from `ACTION_OPEN_DOCUMENT` with a persisted permission,
 * so the layer can be reopened after a restart.
 */
class GeoTiffImporter(private val context: Context, private val tileCacheDir: File) {

    private val contentResolver get() = context.contentResolver

    /** What one import produced — either fits in memory, or is served on demand from GDAL. */
    sealed class ImportedGeoTiff {
        abstract val displayName: String

        data class InMemory(
            override val displayName: String,
            val sourceUri: String,
            val result: GeoTiffReader.Result
        ) : ImportedGeoTiff()

        /** GDAL-routed: [tileSource] serves tiles live as the map requests them, not a precomputed file. */
        class OnDemand(
            override val displayName: String,
            val sourceUri: String,
            val tileSource: GdalTileSource,
            val cacheFile: File
        ) : ImportedGeoTiff()
    }

    fun import(uri: Uri): ImportedGeoTiff {
        val displayName = displayNameFor(contentResolver, uri)
        val (path, tempFile) = resolvePath(context, uri, displayName)

        try {
            // A temp copy works for this session only: GDAL reopens its layer by
            // real path on restore, and this file has none. Only the in-memory
            // Kotlin path (which re-copies on restore) is allowed for it.
            return importFromLocalFile(path, displayName, sourceLabel = uri.toString(), gdalAllowed = tempFile == null)
        } finally {
            tempFile?.delete()
        }
    }

    /** Opens a file the app itself saved: the layer records its plain path. */
    fun importFile(file: File): ImportedGeoTiff =
        importFromLocalFile(file.absolutePath, file.nameWithoutExtension, sourceLabel = file.absolutePath, gdalAllowed = true)

    /**
     * Decodes the readable local file at [path], routing it to GDAL or the
     * Kotlin reader as described in the class doc. [sourceLabel] is what the
     * resulting layer records as its source — [import] passes the original
     * `content://` URI so the layer can be reopened on restore.
     */
    private fun importFromLocalFile(
        path: String,
        displayName: String,
        sourceLabel: String,
        gdalAllowed: Boolean
    ): ImportedGeoTiff {
        RandomAccessFile(path, "r").use { raf ->
            val channel = raf.channel
            val header = ByteArray(GeoTiffImportRouter.HEADER_BYTES_NEEDED)
            channel.readFullyAt(0, header)
            val format = GeoTiffImportRouter.detectFormat(header)

            val useGdal = when (format) {
                GeoTiffImportRouter.TiffFormat.BIG_TIFF -> true
                GeoTiffImportRouter.TiffFormat.CLASSIC -> {
                    val (imageBytes, blockBytes) = estimateClassicSizes(channel)
                    GeoTiffImportRouter.shouldUseGdal(format, imageBytes, blockBytes)
                }
                GeoTiffImportRouter.TiffFormat.UNKNOWN -> false
            }
            DiagnosticLog.log(TAG, "import routing: format=$format useGdal=$useGdal file=$displayName")

            if (useGdal) {
                if (!gdalAllowed) throw IOException(NO_REAL_PATH_MESSAGE)
                return importViaGdal(path, sourceLabel, displayName)
            }

            val kotlinAttempt = runCatching {
                GeoTiffReader.read(channel, raf.length(), displayName = displayName)
            }
            kotlinAttempt.getOrNull()?.let { result ->
                return ImportedGeoTiff.InMemory(displayName, sourceLabel, result)
            }

            DiagnosticLog.log(
                TAG,
                "Kotlin decode failed, falling back to GDAL: " +
                    "${kotlinAttempt.exceptionOrNull()?.javaClass?.simpleName}: " +
                    "${kotlinAttempt.exceptionOrNull()?.message}"
            )
            if (!gdalAllowed) throw kotlinAttempt.exceptionOrNull() ?: IOException(NO_REAL_PATH_MESSAGE)
            return runCatching { importViaGdal(path, sourceLabel, displayName) }
                .getOrElse { gdalError ->
                    DiagnosticLog.logError(TAG, "GDAL fallback also failed", gdalError)
                    // The original Kotlin error is usually the more
                    // useful one to show for a file that is not a valid
                    // TIFF at all; GDAL's own error there tends to be
                    // more generic.
                    throw kotlinAttempt.exceptionOrNull() ?: gdalError
                }
        }
    }

    /** Whole-image and largest-block uncompressed bytes; either is null when the IFD cannot say. */
    private fun estimateClassicSizes(channel: FileChannel): Pair<Long?, Long?> = runCatching {
        val directory = TiffIfdReader.read(channel).directory
        val width = directory.imageWidth?.toLong() ?: return@runCatching null to null
        val height = directory.imageHeight?.toLong() ?: return@runCatching null to null
        val bits = directory.maxBitsPerSample ?: 8
        val imageBytes = GeoTiffImportRouter.estimateUncompressedBytes(width, height, directory.samplesPerPixel, bits)

        val tiled = directory.get(FieldTagType.TileWidth) != null
        val blockWidth = if (tiled) directory.tileWidth.toLong() else width
        val blockHeight = if (tiled) {
            directory.tileHeight.toLong()
        } else {
            (directory.rowsPerStrip?.toLong() ?: height).coerceIn(1L, height)
        }
        val samplesPerBlockPixel = if (directory.planarConfiguration == PLANAR_SEPARATE) 1 else directory.samplesPerPixel
        val blockBytes = GeoTiffImportRouter.estimateBlockBytes(blockWidth, blockHeight, samplesPerBlockPixel, bits)
        DiagnosticLog.log(TAG, "size estimate: imageBytes=$imageBytes blockBytes=$blockBytes tiled=$tiled")
        imageBytes to blockBytes
    }.getOrDefault(null to null)

    private fun importViaGdal(path: String, sourceLabel: String, displayName: String): ImportedGeoTiff.OnDemand {
        tileCacheDir.mkdirs()
        val cacheFile = File(tileCacheDir, "${UUID.randomUUID()}.${GdalTileCache.EXTENSION}")
        val tileSource = GdalTileSource.open(context, path, cacheFile)
        return ImportedGeoTiff.OnDemand(
            displayName = displayName,
            sourceUri = sourceLabel,
            tileSource = tileSource,
            cacheFile = cacheFile
        )
    }

    companion object {
        private const val TAG = "GeoTiffImporter"
        private const val PLANAR_SEPARATE = 2

        private const val NO_REAL_PATH_MESSAGE =
            "Couldn't access this file directly — try picking it from \"This device\" in " +
                "the file browser rather than Recent."
        const val EXTENSION = "tif"

        /** With no real file path, files up to this size are opened from a private copy; bigger ones are refused. */
        private const val SMALL_FILE_FALLBACK_MAX_BYTES = 50L * 1024 * 1024

        /** MIME types the system document picker should surface. */
        val ACCEPTED_MIME_TYPES = arrayOf(
            "image/tiff",
            "image/geotiff",
            "image/tiff-fx",
            "application/octet-stream"
        )

        /** Opens [uri] and decodes it via the Kotlin path only; used to restore an in-memory layer. */
        fun readFrom(context: Context, uri: Uri): GeoTiffReader.Result? {
            // A plain path: a file the app saved itself (seabed depths).
            if (uri.scheme == null) {
                val file = File(uri.toString())
                return RandomAccessFile(file, "r").use { raf ->
                    GeoTiffReader.read(raf.channel, raf.length(), displayName = file.nameWithoutExtension)
                }
            }
            val displayName = displayNameFor(context.contentResolver, uri)
            val (path, tempFile) = runCatching { resolvePath(context, uri, displayName) }.getOrNull() ?: return null
            return try {
                RandomAccessFile(path, "r").use { raf ->
                    GeoTiffReader.read(raf.channel, raf.length(), displayName = displayName)
                }
            } finally {
                tempFile?.delete()
            }
        }

        /**
         * The real path for [uri] ([RealPathResolver]), or a temporary private copy
         * of a small file. Returns the path, and the copy to delete afterwards when
         * one was made.
         */
        private fun resolvePath(context: Context, uri: Uri, displayName: String): Pair<String, File?> {
            val realPath = RealPathResolver.resolve(context, uri)
            val realPathReadable = realPath?.let { File(it).canRead() } == true
            DiagnosticLog.log(
                TAG,
                "real path resolution: uri=$uri resolvedPath=$realPath readable=$realPathReadable file=$displayName"
            )

            if (realPathReadable) return realPath!! to null

            val size = querySize(context, uri)
            if (size == null || size <= 0L || size > SMALL_FILE_FALLBACK_MAX_BYTES) {
                DiagnosticLog.log(
                    TAG,
                    "no direct access: resolvedPath=$realPath sizeBytes=$size cap=$SMALL_FILE_FALLBACK_MAX_BYTES"
                )
                throw IOException(NO_REAL_PATH_MESSAGE)
            }

            DiagnosticLog.log(TAG, "no real path resolved; copying into a bounded temp file: sizeBytes=$size")
            val tempFile = File.createTempFile("geotiff-import-", ".$EXTENSION", context.cacheDir)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read the selected file" }
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            return tempFile.path to tempFile
        }

        private fun querySize(context: Context, uri: Uri): Long? = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && index >= 0) cursor.getLong(index) else null
            }
        }.getOrNull()

        private fun displayNameFor(contentResolver: android.content.ContentResolver, uri: Uri): String =
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                }
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.substringBeforeLast('.')
                ?: "GeoTIFF"
    }
}
