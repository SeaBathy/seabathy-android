package au.opendepth.app.download

import android.os.Environment
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.model.DataSource
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.storage.MbtilesWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One offline map download: lists the tiles, fetches them a few at a time
 * with retries and writes a new MBTiles file. Run by
 * [OfflineTileDownloadService]; no UI of its own.
 */
class TileDownloadRunner(
    /** Whether the device has an internet connection right now (always true in tests). */
    private val isOnline: () -> Boolean = { true }
) {

    private val fetchClient = TileFetchClient()

    /** Aborts in-flight fetches so a cancelled [download] finishes promptly; see [TileFetchClient.cancelInFlight]. */
    fun cancelInFlight() = fetchClient.cancelInFlight()

    /** [failedTiles] > 0 means the archive has holes: those tiles will be blank offline. */
    class Result(val file: File, val totalBytes: Long, val totalTiles: Long, val failedTiles: Long)

    suspend fun download(
        source: DataSource,
        bounds: GeoBounds,
        zoomRange: IntRange,
        onWaitingForNetwork: () -> Unit = {},
        onProgress: (done: Long, total: Long, bytesDone: Long) -> Unit
    ): Result {
        fetchClient.resetLogBudget()
        fetchClient.resetCancellation()
        deleteStaleDownloadParts(offlineDownloadsDir()).takeIf { it.isNotEmpty() }?.let { removed ->
            DiagnosticLog.log(TAG, "removed partial files left by an interrupted download: ${removed.joinToString { it.name }}")
        }
        val destination = destinationFile(source, bounds)
        val tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange)
        // Written under a .part name and renamed only once complete, so a
        // download cut short by process death never leaves a file that
        // looks like a finished, importable .mbtiles.
        val partial = partFileFor(destination)
        val failedTiles = try {
            downloadTiles(source, bounds, zoomRange, tiles, partial, onWaitingForNetwork, onProgress)
        } catch (error: Throwable) {
            deletePart(partial)
            throw error
        }
        if (!partial.renameTo(destination)) {
            deletePart(partial)
            throw IOException("Download finished but couldn't be saved as ${destination.absolutePath}")
        }
        // SQLite's journal (TRUNCATE mode can leave an empty one) keeps the .part name.
        File(partial.path + "-journal").delete()

        // The write calls not throwing is not, by itself, proof the file is
        // really at the path about to be reported as "done" — a fresh
        // exists()/length() check catches a write that silently landed
        // nowhere, not just a write-call exception.
        if (!destination.exists() || destination.length() <= 0L) {
            throw IOException(
                "Download finished but the file is missing at ${destination.absolutePath}: nothing was saved"
            )
        }
        DiagnosticLog.log(
            TAG,
            "verified on disk: path=${destination.absolutePath} bytes=${destination.length()}"
        )

        return Result(destination, destination.length(), tiles.size.toLong(), failedTiles)
    }

    private suspend fun downloadTiles(
        source: DataSource,
        bounds: GeoBounds,
        zoomRange: IntRange,
        tiles: List<Triple<Int, Int, Int>>,
        destination: File,
        onWaitingForNetwork: () -> Unit,
        onTileProgress: (done: Long, total: Long, bytesDone: Long) -> Unit
    ): Long {
        val total = tiles.size.toLong()

        DiagnosticLog.log(
            TAG,
            "downloadTiles start: source=${source.name} zoomRange=$zoomRange totalTiles=$total " +
                "concurrency=$CONCURRENT_FETCH_LIMIT"
        )
        val start = System.currentTimeMillis()
        var done = 0L
        var bytesDone = 0L
        var succeeded = 0L
        var failed = 0L
        val succeededByZoom = mutableMapOf<Int, Long>()
        val failedByZoom = mutableMapOf<Int, Long>()
        var lastProgressAt = start
        val jobContext = currentCoroutineContext()
        // Tiles that could not be fetched, retried once at the end; and
        // whether the connection stayed down so long that the rest was skipped.
        val failedCoords = mutableListOf<Triple<Int, Int, Int>>()
        var gaveUpWaiting = false

        destination.parentFile?.mkdirs()
        try {
            MbtilesWriter(destination).use { writer ->
                writer.writeMetadata(
                    name = source.name,
                    format = TILE_FORMAT,
                    bounds = bounds,
                    minZoom = zoomRange.first,
                    maxZoom = zoomRange.last,
                    attribution = source.attribution
                )

                // Committed in batches, so an interrupted download keeps what it has and the
                // progress counts only saved tiles.
                for (chunk in tiles.chunked(WRITE_BATCH_SIZE)) {
                    jobContext.ensureActive()
                    // Signal drops at sea: wait for it to come back rather
                    // than burning every remaining tile as a failure.
                    if (!gaveUpWaiting && !waitForNetwork(onWaitingForNetwork)) gaveUpWaiting = true
                    if (gaveUpWaiting) {
                        failed += chunk.size
                        failedCoords += chunk
                        chunk.forEach { failedByZoom.merge(it.first, 1L, Long::plus) }
                        done += chunk.size
                        onTileProgress(done, total, bytesDone)
                        continue
                    }

                    // Fetched a few at a time, but written only from this coroutine, so the
                    // SQLite writer is never used from two threads.
                    val fetched = boundedConcurrentMap(chunk, Dispatchers.IO, CONCURRENT_FETCH_LIMIT) { (z, x, y) ->
                        Triple(z, x, y) to fetchClient.fetchTile(source.urlTemplate, z, x, y)
                    }

                    writer.inTransaction {
                        for ((coord, data) in fetched) {
                            val (z, x, y) = coord
                            if (data != null) {
                                writer.writeTile(z, x, y, data)
                                succeeded++
                                bytesDone += data.size
                                succeededByZoom.merge(z, 1L, Long::plus)
                            } else {
                                failed++
                                failedByZoom.merge(z, 1L, Long::plus)
                                failedCoords += coord
                            }

                            done++
                            val now = System.currentTimeMillis()
                            if (done % PROGRESS_EVERY_TILES == 0L ||
                                now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                                done == total
                            ) {
                                onTileProgress(done, total, bytesDone)
                                DiagnosticLog.log(
                                    TAG,
                                    "progress: done=$done/$total succeeded=$succeeded failed=$failed bytes=$bytesDone"
                                )
                                lastProgressAt = now
                            }
                        }
                    }
                }

                // One more go at whatever failed (a dropped connection
                // mid-batch, a busy server), once the connection is back.
                if (failedCoords.isNotEmpty() && !gaveUpWaiting && waitForNetwork(onWaitingForNetwork)) {
                    DiagnosticLog.log(TAG, "retrying ${failedCoords.size} failed tiles")
                    for (chunk in failedCoords.toList().chunked(WRITE_BATCH_SIZE)) {
                        jobContext.ensureActive()
                        if (!waitForNetwork(onWaitingForNetwork)) break
                        val fetched = boundedConcurrentMap(chunk, Dispatchers.IO, CONCURRENT_FETCH_LIMIT) { (z, x, y) ->
                            Triple(z, x, y) to fetchClient.fetchTile(source.urlTemplate, z, x, y)
                        }
                        writer.inTransaction {
                            for ((coord, data) in fetched) {
                                if (data == null) continue
                                writer.writeTile(coord.first, coord.second, coord.third, data)
                                succeeded++
                                failed--
                                bytesDone += data.size
                                succeededByZoom.merge(coord.first, 1L, Long::plus)
                                failedByZoom.merge(coord.first, -1L, Long::plus)
                            }
                        }
                        onTileProgress(done, total, bytesDone)
                    }
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }

        val byZoomSummary = zoomRange.joinToString(", ") { z ->
            val ok = succeededByZoom[z] ?: 0L
            val bad = failedByZoom[z] ?: 0L
            "z$z:$ok/${ok + bad}"
        }
        DiagnosticLog.log(
            TAG,
            "downloadTiles finished: source=${source.name} elapsedMs=${System.currentTimeMillis() - start} " +
                "succeeded=$succeeded failed=$failed bytes=$bytesDone byZoom=[$byZoomSummary]"
        )

        if (succeeded == 0L) {
            destination.delete()
            throw IOException("None of the $total tiles in this area could be downloaded")
        }
        return failed
    }

    /**
     * Returns at once when online; otherwise reports waiting and polls until
     * the connection is back (true) or [NETWORK_WAIT_MS] passes (false).
     * Cancelling the download interrupts the wait.
     */
    private suspend fun waitForNetwork(onWaiting: () -> Unit): Boolean {
        if (isOnline()) return true
        onWaiting()
        DiagnosticLog.log(TAG, "no internet: waiting for the connection to come back")
        val deadline = System.currentTimeMillis() + NETWORK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(NETWORK_POLL_MS)
            if (isOnline()) {
                DiagnosticLog.log(TAG, "internet back: resuming download")
                return true
            }
        }
        DiagnosticLog.log(TAG, "still no internet after ${NETWORK_WAIT_MS / 60_000} min: finishing with what was downloaded")
        return false
    }

    /**
     * `SeaBathy/OfflineDownloads/<source>_<lat>_<lng>_<yyyyMMdd>.mbtiles` in
     * shared storage, where a file manager can find it (the app's own
     * `Android/data` folder is hidden). A `_2`, `_3`… suffix is added when the
     * name is taken, so a download only ever writes or deletes its own file.
     */
    private fun destinationFile(source: DataSource, bounds: GeoBounds): File {
        val dir = offlineDownloadsDir()
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException(
                "Couldn't create the SeaBathy/OfflineDownloads folder. Allow SeaBathy \"All files access\" " +
                    "in Android Settings, then try again."
            )
        }

        val centreLat = (bounds.north + bounds.south) / 2.0
        val centreLng = (bounds.west + bounds.east) / 2.0
        val slug = source.name
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "source" }
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val baseName = String.format(Locale.US, "%s_%.1f_%.1f_%s", slug, centreLat, centreLng, date)
        return freeMbtilesFile(dir, baseName)
    }

    private companion object {
        const val TAG = "OfflineTileDownload"
        const val PROGRESS_EVERY_TILES = 20L
        const val PROGRESS_INTERVAL_MS = 1000L
        /** Tiles per SQLite transaction — batched rather than one transaction for the whole download. */
        const val WRITE_BATCH_SIZE = 200
        /** Never more than this many tile fetches in flight at once. */
        const val CONCURRENT_FETCH_LIMIT = 6
        /** Written as the MBTiles metadata "format" — this app's own reader (OverlayLayerController.readMbtilesMetadata) only branches on "pbf"/"mvt" (vector) vs anything else (raster), so the exact raster format string only matters to other MBTiles consumers, not to this app reading its own file back. */
        const val TILE_FORMAT = "jpg"
        /** How long a download waits for a lost connection before finishing with what it has. */
        const val NETWORK_WAIT_MS = 15 * 60_000L
        const val NETWORK_POLL_MS = 2_000L
    }
}

/** `<baseName>.mbtiles` in [dir], or the first free `<baseName>_2.mbtiles`, `_3`... if taken. */
internal fun freeMbtilesFile(dir: File, baseName: String): File =
    generateSequence(1) { it + 1 }
        .map { n -> File(dir, if (n == 1) "$baseName.mbtiles" else "${baseName}_$n.mbtiles") }
        .first { !it.exists() && !File(it.path + "-journal").exists() && !partFileFor(it).exists() }

/** Where a download into [destination] is written until it completes. */
internal fun partFileFor(destination: File): File = File(destination.path + PART_SUFFIX)

/**
 * Deletes `.mbtiles.part` files (and their SQLite journals) in [dir] —
 * what a download killed mid-way (process death, reboot) leaves behind.
 * Only called when starting a download, and the service runs one at a
 * time, so none of these can belong to a download still in progress.
 */
internal fun deleteStaleDownloadParts(dir: File): List<File> =
    (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && (it.name.endsWith(".mbtiles$PART_SUFFIX") || it.name.endsWith(".mbtiles$PART_SUFFIX-journal")) }
        .filter { it.delete() }

private fun deletePart(partial: File) {
    partial.delete()
    File(partial.path + "-journal").delete()
}

private const val PART_SUFFIX = ".part"

/** Shared-storage folder offline downloads are written to; see [TileDownloadRunner]'s destination doc. */
fun offlineDownloadsDir(): File = File(Environment.getExternalStorageDirectory(), "SeaBathy/OfflineDownloads")

/**
 * True only for an `.mbtiles` file directly inside [offlineDownloadsDir].
 * MainActivity is exported, so the path in an "import this download" intent
 * may come from another app, not from this app's own notification.
 */
fun isOfflineDownload(path: String, downloadsDir: File = offlineDownloadsDir()): Boolean = runCatching {
    val file = File(path).canonicalFile
    file.extension.equals("mbtiles", ignoreCase = true) &&
        file.parentFile == downloadsDir.canonicalFile
}.getOrDefault(false)
