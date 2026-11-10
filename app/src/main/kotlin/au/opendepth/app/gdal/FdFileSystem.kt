package au.opendepth.app.gdal

import au.opendepth.app.diagnostics.DiagnosticLog

/**
 * GDAL's `/vsiseabathyfd/` file system (native `seabathy_fd_vsi.c`): GDAL
 * reads a picked document through its open file descriptor, so surveys of any
 * size open in place with no storage permission. Installed once, after GDAL
 * itself is loaded ([GdalSupport.ensureInitialized]).
 */
object FdFileSystem {
    private const val TAG = "FdFileSystem"

    /** True once GDAL can open `/vsiseabathyfd/` paths. */
    @Volatile
    var available = false
        private set

    @Synchronized
    fun install() {
        if (available) return
        available = runCatching {
            System.loadLibrary("seabathyfd")
            nativeInstall()
        }.onFailure { DiagnosticLog.logError(TAG, "could not install the in-place reader", it) }
            .getOrDefault(false)
        DiagnosticLog.log(TAG, "in-place reader for picked files: ${if (available) "ready" else "not available"}")
    }

    /** The path GDAL opens for the already-open descriptor [fd]. */
    fun pathFor(fd: Int) = "/vsiseabathyfd/$fd/survey.tif"

    @JvmStatic
    private external fun nativeInstall(): Boolean
}
