package au.opendepth.app.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import au.opendepth.app.diagnostics.DiagnosticLog
import java.io.File

/**
 * Finds the real file path behind a picked `content://` document. Without a
 * storage permission (the app has none, as Google Play requires) Android
 * usually won't let it be opened by path; [readablePath] says when it can,
 * and callers otherwise copy the file into the app (see GeoTiffImporter).
 *
 * Tries, in order: the path inside an `externalstorage.documents` ID
 * (`<volume>:<path>`, a "This device" pick), the Downloads provider (see
 * [resolveDownloadsDocument]), then MediaStore's DATA column. Returns null
 * when the provider has no real path; callers handle that.
 */
object RealPathResolver {

    /**
     * The real path for [uri] when this app can actually open it; null when
     * there is none or Android won't allow it (no storage permission).
     */
    fun readablePath(context: Context, uri: Uri): String? =
        resolve(context, uri)?.takeIf { canOpen(it) }

    /** Whether [path] really opens for reading (asked by opening it, not by `File.canRead()`). */
    fun canOpen(path: String): Boolean =
        runCatching { java.io.RandomAccessFile(path, "r").close(); true }.getOrDefault(false)

    fun resolve(context: Context, uri: Uri): String? {
        if (uri.authority == DOWNLOADS_AUTHORITY) {
            resolveDownloadsDocument(context, uri)?.let { path ->
                DiagnosticLog.log(TAG, "resolved via downloads document id: $path")
                return path
            }
        }
        if (uri.authority == MEDIA_DOCUMENTS_AUTHORITY) {
            resolveMediaDocument(context, uri)?.let { path ->
                DiagnosticLog.log(TAG, "resolved via media document id: $path")
                return path
            }
        }

        val viaExternalStorageDocument = if (uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            resolveExternalStorageDocument(context, uri)
        } else {
            null
        }
        if (viaExternalStorageDocument != null) {
            DiagnosticLog.log(TAG, "resolved via externalstorage document id: $viaExternalStorageDocument")
            return viaExternalStorageDocument
        }

        val viaMediaStore = resolveViaMediaStore(context, uri)
        DiagnosticLog.log(
            TAG,
            if (viaMediaStore != null) {
                "resolved via MediaStore DATA column: $viaMediaStore"
            } else {
                "both real-path resolution techniques failed for uri=$uri authority=${uri.authority}"
            }
        )
        return viaMediaStore
    }

    private fun resolveExternalStorageDocument(context: Context, uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (docId == null) {
            DiagnosticLog.log(TAG, "could not read document id from externalstorage uri=$uri")
            return null
        }

        val split = docId.split(":", limit = 2)
        if (split.size != 2) {
            DiagnosticLog.log(TAG, "externalstorage document id in unexpected shape: $docId")
            return null
        }
        val (volumeId, relativePath) = split

        if (volumeId.equals("primary", ignoreCase = true)) {
            return File(Environment.getExternalStorageDirectory(), relativePath).path
        }

        val volumePath = nonPrimaryVolumeRoot(context, volumeId)
        if (volumePath == null) {
            DiagnosticLog.log(
                TAG,
                "non-primary storage volume '$volumeId' could not be resolved to a mount path " +
                    "(known limitation: only the primary volume and API 30+ StorageManager " +
                    "lookups are supported) -- falling back to other techniques"
            )
            return null
        }
        return File(volumePath, relativePath).path
    }

    /** Where a non-primary volume (an SD card) is mounted. Android 11+ only; null and logged below that. */
    private fun nonPrimaryVolumeRoot(context: Context, volumeId: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return null

        return runCatching {
            storageManager.storageVolumes
                .firstOrNull { volume -> volume.uuid?.equals(volumeId, ignoreCase = true) == true }
                ?.directory
                ?.path
        }.getOrNull()
    }

    /**
     * The picker's Downloads shortcut. Its IDs are `raw:<path>`, `msf:<id>` (a
     * MediaStore row) or an old download ID, for which the file of the same
     * name in the Download folder is used when it exists.
     */
    private fun resolveDownloadsDocument(context: Context, uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (docId.startsWith("raw:")) {
            return docId.removePrefix("raw:").takeIf { File(it).canRead() }
        }
        if (docId.startsWith("msf:")) {
            val id = docId.removePrefix("msf:").toLongOrNull()
            if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val row = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
                queryDataColumn(context, row)?.takeIf { File(it).canRead() }?.let { return it }
            }
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        val candidate = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        return candidate.path.takeIf { candidate.isFile && candidate.canRead() }
    }

    /**
     * The picker's Images / Documents / Recent categories return
     * `com.android.providers.media.documents` URIs with IDs like `image:38`
     * (a GeoTIFF is an image as far as MediaStore is concerned). The number is
     * a MediaStore Files row ID whatever the prefix.
     */
    private fun resolveMediaDocument(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val id = docId.substringAfter(':', "").toLongOrNull() ?: return null
        val row = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
        return queryDataColumn(context, row)?.takeIf { File(it).canRead() }
    }

    private fun queryDataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun resolveViaMediaStore(context: Context, uri: Uri): String? {
        val queryUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }

        return runCatching {
            context.contentResolver.query(
                queryUri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private const val TAG = "RealPathResolver"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"
}
