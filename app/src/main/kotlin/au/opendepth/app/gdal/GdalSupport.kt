package au.opendepth.app.gdal

import android.content.Context
import au.opendepth.app.diagnostics.DiagnosticLog
import org.gdal.gdal.gdal
import java.io.File

/**
 * Sets GDAL up the first time a big GeoTIFF is opened, never at start-up.
 *
 * PROJ needs `proj.db` as a real file, so the bundled copy
 * (`assets/proj/proj.db`, built from PROJ 9.2.1's SQL sources to match the
 * PROJ in `gdal-3.7.0.aar`) is copied to [Context.getFilesDir] and set as
 * both `PROJ_DATA` and `PROJ_LIB`. Without it `GetProjection()` returns an
 * empty WKT and every raster looks unprojected.
 */
object GdalSupport {
    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return

        DiagnosticLog.log(TAG, "GDAL init start")
        val start = System.currentTimeMillis()
        try {
            val projDir = File(context.filesDir, PROJ_DIR_NAME).apply { mkdirs() }
            val copiedAssets = copyProjAssets(context, projDir)

            val projDbFile = File(projDir, "proj.db")
            DiagnosticLog.log(
                TAG,
                "proj.db post-copy check: destination=${projDbFile.absolutePath} " +
                    "exists=${projDbFile.exists()} sizeBytes=${projDbFile.length()}"
            )

            gdal.AllRegister()
            gdal.SetConfigOption("PROJ_DATA", projDir.absolutePath)
            gdal.SetConfigOption("PROJ_LIB", projDir.absolutePath)
            DiagnosticLog.log(
                TAG,
                "PROJ config options set: PROJ_DATA=${gdal.GetConfigOption("PROJ_DATA", null)} " +
                    "PROJ_LIB=${gdal.GetConfigOption("PROJ_LIB", null)}"
            )
            // GDAL's own block cache (native memory, outside the Java heap):
            // neighbouring tiles share source blocks, so a bigger cache
            // saves re-reading and re-decompressing them — sized to the
            // phone, from 64 MB on small ones to 192 MB.
            gdal.SetConfigOption("GDAL_CACHEMAX", blockCacheMb(context).toString())
            // Decompress a big read's blocks on two threads (GDAL 3.6+).
            gdal.SetConfigOption("GDAL_NUM_THREADS", "2")

            initialized = true
            DiagnosticLog.log(
                TAG,
                "GDAL init finished: elapsedMs=${System.currentTimeMillis() - start} " +
                    "version=${gdal.VersionInfo()} projAssetsCopied=$copiedAssets " +
                    "projDbPresentBeforeCopy=${copiedAssets == 0 && projDbFile.exists()}"
            )
        } catch (error: Throwable) {
            // Throwable, not Exception: a missing native library on an
            // unsupported ABI (see GDAL_SIZE_THRESHOLD_BYTES's doc and the
            // build.gradle.kts comment on the GDAL dependency) surfaces as
            // UnsatisfiedLinkError, which is an Error.
            DiagnosticLog.logError(TAG, "GDAL init FAILED", error)
            throw error
        }
    }

    /** @return how many proj.db-related asset files were found and copied (0 if none are bundled). */
    private fun copyProjAssets(context: Context, projDir: File): Int {
        val listResult = runCatching { context.assets.list(PROJ_ASSET_DIR) }
        val names = listResult.getOrNull()
        if (names == null) {
            DiagnosticLog.log(
                TAG,
                "proj asset dir listing FAILED: assetDir=$PROJ_ASSET_DIR " +
                    "error=${listResult.exceptionOrNull()?.javaClass?.simpleName}: " +
                    "${listResult.exceptionOrNull()?.message}"
            )
            return 0
        }
        if (names.isEmpty()) {
            DiagnosticLog.log(TAG, "proj asset dir is empty: assetDir=$PROJ_ASSET_DIR -- no proj.db bundled")
            return 0
        }
        DiagnosticLog.log(TAG, "proj asset dir found: names=${names.joinToString()}")

        var copied = 0
        var alreadyPresent = 0
        for (name in names) {
            val destination = File(projDir, name)
            if (destination.exists() && destination.length() > 0L) {
                alreadyPresent++
                continue
            }
            // Copy then rename: a copy cut short (app killed on first launch)
            // must not leave a truncated file the exists() check above accepts forever.
            val partial = File(projDir, "$name.part")
            context.assets.open("$PROJ_ASSET_DIR/$name").use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(destination)) {
                partial.delete()
                throw java.io.IOException("Could not install PROJ data file $name")
            }
            copied++
            DiagnosticLog.log(
                TAG,
                "copied proj asset: name=$name destination=${destination.absolutePath} " +
                    "sizeBytes=${destination.length()}"
            )
        }
        DiagnosticLog.log(TAG, "proj asset copy summary: copied=$copied alreadyPresent=$alreadyPresent")
        return copied
    }

    private const val TAG = "GdalSupport"
    private const val PROJ_DIR_NAME = "proj"
    private const val PROJ_ASSET_DIR = "proj"

    /** About a twentieth of the phone's RAM, between 64 and 192 MB. */
    private fun blockCacheMb(context: Context): Int {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        return (info.totalMem / (1024 * 1024) / 20).toInt().coerceIn(64, 192)
    }
}
