package au.opendepth.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import au.opendepth.app.MainActivity
import au.opendepth.app.R
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.diagnostics.ResourceTracker
import au.opendepth.app.model.DataSource
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.util.UserFacingErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one offline map download as a foreground service with a progress
 * notification. Without one, Android cuts a background download's network
 * once the screen locks. The work itself is in [TileDownloadRunner].
 */
class OfflineTileDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var downloadJob: Job? = null
    @Volatile private var lastPercent = 0
    private lateinit var runner: TileDownloadRunner
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        runner = TileDownloadRunner(isOnline = {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        })
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DiagnosticLog.log(TAG, "download cancelled by user from notification")
            downloadJob?.cancel()
            runner.cancelInFlight()
            return START_NOT_STICKY
        }

        if (downloadJob?.isActive == true) {
            DiagnosticLog.log(TAG, "ignoring start command: a download is already running")
            return START_NOT_STICKY
        }

        val source = intent?.readDataSource()
        val bounds = intent?.readBounds()
        val zoomRange = intent?.readZoomRange()
        if (source == null || bounds == null || zoomRange == null) {
            DiagnosticLog.log(TAG, "start command missing required extras -- stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(source.name, "Starting…", percent = 0),
            SERVICE_TYPE
        )

        isRunning = true
        downloadJob = serviceScope.launch {
            runCatching {
                runner.download(
                    source,
                    bounds,
                    zoomRange,
                    onWaitingForNetwork = {
                        notify(buildProgressNotification(source.name, "No internet — waiting for the connection to come back…", lastPercent))
                    }
                ) { done, total, bytesDone ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                    lastPercent = percent
                    val text = "$done / $total tiles — ${OfflineDownloadEstimator.formatBytes(bytesDone)}"
                    notify(buildProgressNotification(source.name, text, percent))
                }
            }.onSuccess { result ->
                DiagnosticLog.log(
                    TAG,
                    "service download finished: file=${result.file.absolutePath} bytes=${result.totalBytes} " +
                        "failedTiles=${result.failedTiles}/${result.totalTiles}"
                )
                // Also offered inside the app next time it is on screen.
                CompletedDownloads.record(this@OfflineTileDownloadService, result.file.absolutePath, source.name, source.attribution)
                val saved = "${OfflineDownloadEstimator.formatBytes(result.totalBytes)} saved to ${result.file.absolutePath}"
                notify(
                    buildCompletionNotification(
                        title = if (result.failedTiles > 0) "Download incomplete" else "Download complete",
                        text = if (result.failedTiles > 0) {
                            "${source.name}: ${result.failedTiles} of ${result.totalTiles} tiles could not be " +
                                "downloaded and will be blank offline. $saved"
                        } else {
                            "${source.name}: $saved"
                        },
                        filePath = result.file.absolutePath,
                        sourceName = source.name,
                        attribution = source.attribution
                    )
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    DiagnosticLog.log(TAG, "download job cancelled: ${error.message}")
                    notify(buildSimpleNotification("Download cancelled", source.name))
                } else {
                    DiagnosticLog.logError(TAG, "service download failed", error)
                    notify(
                        buildSimpleNotification(
                            "Download failed",
                            UserFacingErrors.describe(error, "${source.name} could not be downloaded")
                        )
                    )
                }
            }

            isRunning = false
            ResourceTracker.snapshot(this@OfflineTileDownloadService, "download finished")
            ServiceCompat.stopForeground(this@OfflineTileDownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            // With startId: if another download was started meanwhile, this
            // does not stop the service out from under it.
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        downloadJob?.cancel()
        runner.cancelInFlight()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ allows a data-sync foreground service about 6 hours a day.
     * When that runs out, stop cleanly (the partial file is tidied up as for a
     * cancel) instead of letting the system kill the app.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.log(TAG, "download reached Android's foreground-service time limit; stopping")
        downloadJob?.cancel()
        runner.cancelInFlight()
        notify(
            buildSimpleNotification(
                "Download stopped",
                "Android's daily time limit for background downloads was reached. Start it again later to carry on."
            )
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun notify(notification: Notification) {
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress for offline map tile downloads"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(sourceName: String, text: String, percent: Int): Notification {
        val cancelIntent = Intent(this, OfflineTileDownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anchor_line)
            .setContentTitle("Downloading $sourceName")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun buildCompletionNotification(
        title: String,
        text: String,
        filePath: String,
        sourceName: String,
        attribution: String
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = MainActivity.ACTION_OFFER_IMPORT_DOWNLOAD
            putExtra(MainActivity.EXTRA_DOWNLOADED_FILE_PATH, filePath)
            putExtra(MainActivity.EXTRA_DOWNLOADED_SOURCE_NAME, sourceName)
            putExtra(MainActivity.EXTRA_DOWNLOADED_ATTRIBUTION, attribution)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anchor_line)
            .setContentTitle(title)
            .setContentText(text)
            // The plain single-line contentText truncates a full absolute
            // path; BigTextStyle lets the user expand the notification to
            // read/copy the whole thing.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildSimpleNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anchor_line)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    companion object {
        /** Lets the UI refuse a second download up front instead of having it silently ignored here. */
        @Volatile
        var isRunning = false
            private set

        private const val TAG = "OfflineTileDownload"
        private const val CHANNEL_ID = "offline_tile_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_CANCEL = "au.opendepth.app.download.ACTION_CANCEL"
        private const val SERVICE_TYPE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_SOURCE_URL_TEMPLATE = "source_url_template"
        private const val EXTRA_SOURCE_ATTRIBUTION = "source_attribution"
        private const val EXTRA_SOURCE_BUILT_IN = "source_built_in"
        private const val EXTRA_BOUNDS_WEST = "bounds_west"
        private const val EXTRA_BOUNDS_SOUTH = "bounds_south"
        private const val EXTRA_BOUNDS_EAST = "bounds_east"
        private const val EXTRA_BOUNDS_NORTH = "bounds_north"
        private const val EXTRA_ZOOM_MIN = "zoom_min"
        private const val EXTRA_ZOOM_MAX = "zoom_max"

        /** Builds the start [Intent] for [context]; extras are primitives only, deliberately not a `Serializable`/`Parcelable` payload, so this never breaks across a process restart or an app update between starting and handling it. */
        fun startIntent(context: Context, source: DataSource, bounds: GeoBounds, zoomRange: IntRange): Intent =
            Intent(context, OfflineTileDownloadService::class.java).apply {
                putExtra(EXTRA_SOURCE_ID, source.id)
                putExtra(EXTRA_SOURCE_NAME, source.name)
                putExtra(EXTRA_SOURCE_URL_TEMPLATE, source.urlTemplate)
                putExtra(EXTRA_SOURCE_ATTRIBUTION, source.attribution)
                putExtra(EXTRA_SOURCE_BUILT_IN, source.builtIn)
                putExtra(EXTRA_BOUNDS_WEST, bounds.west)
                putExtra(EXTRA_BOUNDS_SOUTH, bounds.south)
                putExtra(EXTRA_BOUNDS_EAST, bounds.east)
                putExtra(EXTRA_BOUNDS_NORTH, bounds.north)
                putExtra(EXTRA_ZOOM_MIN, zoomRange.first)
                putExtra(EXTRA_ZOOM_MAX, zoomRange.last)
            }

        private fun Intent.readDataSource(): DataSource? {
            val id = getStringExtra(EXTRA_SOURCE_ID) ?: return null
            val name = getStringExtra(EXTRA_SOURCE_NAME) ?: return null
            val urlTemplate = getStringExtra(EXTRA_SOURCE_URL_TEMPLATE) ?: return null
            val attribution = getStringExtra(EXTRA_SOURCE_ATTRIBUTION).orEmpty()
            val builtIn = getBooleanExtra(EXTRA_SOURCE_BUILT_IN, false)
            return DataSource(id, name, urlTemplate, attribution, builtIn)
        }

        private fun Intent.readBounds(): GeoBounds? {
            if (!hasExtra(EXTRA_BOUNDS_WEST)) return null
            return GeoBounds(
                west = getDoubleExtra(EXTRA_BOUNDS_WEST, 0.0),
                south = getDoubleExtra(EXTRA_BOUNDS_SOUTH, 0.0),
                east = getDoubleExtra(EXTRA_BOUNDS_EAST, 0.0),
                north = getDoubleExtra(EXTRA_BOUNDS_NORTH, 0.0)
            )
        }

        private fun Intent.readZoomRange(): IntRange? {
            if (!hasExtra(EXTRA_ZOOM_MIN) || !hasExtra(EXTRA_ZOOM_MAX)) return null
            return getIntExtra(EXTRA_ZOOM_MIN, 0)..getIntExtra(EXTRA_ZOOM_MAX, 0)
        }
    }
}
