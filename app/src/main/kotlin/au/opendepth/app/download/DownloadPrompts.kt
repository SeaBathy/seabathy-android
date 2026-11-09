package au.opendepth.app.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the app tells the user about background downloads whenever it comes
 * on screen: a finished download not yet offered for import (offered at
 * once if it finishes while the app is open), and a download the system
 * stopped part-way, whose partial file is removed.
 */
class DownloadPrompts(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Whether a file is already loaded as a layer, so it is not offered twice. */
    private val isLoaded: (path: String) -> Boolean,
    private val offerImport: (CompletedDownloads.Pending) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> offerCompleted() }

    fun onResume() {
        CompletedDownloads.prefs(context).registerOnSharedPreferenceChangeListener(listener)
        offerCompleted()
        reportInterrupted()
    }

    fun onPause() {
        CompletedDownloads.prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun offerCompleted() {
        val pending = CompletedDownloads.pending(context) ?: return
        CompletedDownloads.clear(context)
        if (!isOfflineDownload(pending.path) || !File(pending.path).isFile) return
        if (isLoaded(pending.path)) return
        offerImport(pending)
    }

    /**
     * A download the system stopped (the app was closed under memory
     * pressure, or the phone restarted) used to vanish without a word,
     * leaving a partial file behind. Removes it and says so.
     */
    private fun reportInterrupted() {
        if (OfflineTileDownloadService.isRunning) return
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching {
                    val cutoff = System.currentTimeMillis() - STALE_PART_MS
                    (offlineDownloadsDir().listFiles() ?: emptyArray())
                        .filter { it.isFile && it.name.contains(".mbtiles.part") && it.lastModified() < cutoff }
                        .filter { it.delete() }
                }.getOrDefault(emptyList())
            }
            if (removed.any { it.name.endsWith(".part") }) {
                showMessage(
                    "An offline map download was interrupted before it finished (the phone closed the app) " +
                        "and the partial file was removed. Start it again from Settings → Offline data sources."
                )
            }
        }
    }

    private companion object {
        /** A partial download untouched for this long belongs to no running download. */
        const val STALE_PART_MS = 2 * 60_000L
    }
}
