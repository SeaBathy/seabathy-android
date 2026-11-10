package au.opendepth.app.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.gdal.FdFileSystem

/**
 * Keeps picked documents open so GDAL can read them in place through
 * [FdFileSystem] (`/vsiseabathyfd/<fd>/survey.tif`), with no storage
 * permission and no copy. Each document stays open for the rest of the process,
 * since its layer reads tiles from it on demand; the same URI always gets the
 * same descriptor. After a restart the document is opened again from the
 * persisted URI grant.
 */
object DescriptorPaths {
    private const val TAG = "DescriptorPaths"
    private val open = HashMap<String, ParcelFileDescriptor>()

    /** A GDAL path to [uri]'s open document, or null when it can't be opened or the reader isn't available. */
    @Synchronized
    fun gdalPathFor(context: Context, uri: Uri): String? {
        if (!FdFileSystem.available) return null
        val key = uri.toString()
        open[key]?.let { return FdFileSystem.pathFor(it.fd) }
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .onFailure { DiagnosticLog.logError(TAG, "open failed for $uri", it) }
            .getOrNull() ?: return null
        open[key] = descriptor
        DiagnosticLog.log(TAG, "reading $uri in place via ${FdFileSystem.pathFor(descriptor.fd)}")
        return FdFileSystem.pathFor(descriptor.fd)
    }
}
