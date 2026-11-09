package au.opendepth.app.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import au.opendepth.app.diagnostics.DiagnosticLog
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copies a picked file into private storage: to a temporary file first,
 * renamed only once the caller's check passes, so a failed import leaves
 * nothing behind and never overwrites a layer. Copies from the real path
 * when [RealPathResolver] finds one (faster), else from the `content://`
 * stream.
 */
class PrivateFileImporter(
    private val contentResolver: ContentResolver,
    private val destinationDir: File,
    private val extension: String,
    private val fallbackPrefix: String,
    private val context: Context? = null
) {
    data class ImportedFile(val displayName: String, val file: File)

    init {
        destinationDir.mkdirs()
    }

    /**
     * @param validate called with the fully copied temporary file. Throw from
     * here to reject the import; the temporary file is always cleaned up.
     */
    fun import(uri: Uri, validate: (File) -> Unit = {}): ImportedFile {
        val originalName = queryDisplayName(uri)
        val safeName = FilenamePolicy.safeBaseName(
            originalName.substringBeforeLast('.'),
            FilenamePolicy.timestampedFallback(fallbackPrefix)
        )

        val finalFile = uniqueDestination(safeName)
        val temporaryFile = File(destinationDir, ".import-${UUID.randomUUID()}.tmp")

        try {
            val realPath = context?.let { RealPathResolver.resolve(it, uri) }
                ?.takeIf { File(it).canRead() }
            DiagnosticLog.log(
                TAG,
                "copy start: uri=$uri extension=$extension " +
                    "via=${if (realPath != null) "realPath($realPath)" else "contentResolver stream"}"
            )
            val copyStart = System.currentTimeMillis()
            if (realPath != null) {
                File(realPath).copyTo(temporaryFile, overwrite = true)
            } else {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot read the selected file" }
                    temporaryFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val copyElapsedMs = System.currentTimeMillis() - copyStart
            DiagnosticLog.log(
                TAG,
                "copy finished: bytes=${temporaryFile.length()} elapsedMs=$copyElapsedMs"
            )

            if (!temporaryFile.exists() || temporaryFile.length() <= 0L) {
                throw IOException("The selected file is empty")
            }

            validate(temporaryFile)

            if (!temporaryFile.renameTo(finalFile)) {
                temporaryFile.copyTo(finalFile, overwrite = false)
                temporaryFile.delete()
            }

            return ImportedFile(finalFile.nameWithoutExtension, finalFile)
        } catch (error: Throwable) {
            DiagnosticLog.logError(TAG, "copy/validate failed", error)
            temporaryFile.delete()
            // Only the destination this import created is removed, because
            // uniqueDestination() never selects an existing file.
            finalFile.delete()
            throw error
        }
    }

    private companion object {
        const val TAG = "PrivateFileImporter"
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
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
        ?: "${FilenamePolicy.timestampedFallback(fallbackPrefix)}.$extension"

    private fun uniqueDestination(baseName: String): File {
        var candidate = File(destinationDir, "$baseName.$extension")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(destinationDir, "$baseName-$suffix.$extension")
            suffix++
        }
        return candidate
    }
}
