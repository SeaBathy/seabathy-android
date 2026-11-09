package au.opendepth.app.track

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.opendepth.app.MainActivity
import au.opendepth.app.R
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavSettingsRepository

/**
 * Records the track with the screen off or another app in front: a
 * location foreground service, with a notification showing the distance
 * so far and a Stop button.
 */
class TrackRecordingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private var listener: LocationListener? = null
    private var lastNotifiedAt = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Track recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while SeaBathy records a track"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrackRecorder.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted by Android after the recording was stopped, or location was taken away.
        if (!TrackRecorder.isRecording(this) || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.onFailure { DiagnosticLog.logError(TAG, "couldn't start recording in the foreground", it) }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (listener != null) return
        val active = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                TrackRecorder.onFix(this@TrackRecordingService, location)
                // The notification's distance, at most every 30 s.
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotifiedAt > NOTIFY_EVERY_MS) {
                    lastNotifiedAt = now
                    runCatching { notificationManager.notify(NOTIFICATION_ID, notification()) }
                }
            }
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, active)
            listener = active
        }.onFailure { DiagnosticLog.logError(TAG, "GPS unavailable for track recording", it) }
    }

    override fun onDestroy() {
        listener?.let { runCatching { locationManager.removeUpdates(it) } }
        listener = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun notification(): Notification {
        val units = NavSettingsRepository(getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)).load().units
        val distance = NavFormat(units).distance(TrackRecorder.distanceM(this) / METRES_PER_NM)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anchor_line)
            .setContentTitle("Recording track")
            .setContentText("$distance so far")
            .setWhen(TrackRecorder.startedMillis(this))
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop and save", stop)
            .build()
    }

    companion object {
        /** True while the service is recording in this process. */
        @Volatile
        var isRunning = false
            private set

        private const val TAG = "TrackRecordingService"
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_STOP = "au.opendepth.app.track.ACTION_STOP"
        private const val UPDATE_MS = 2_000L
        private const val NOTIFY_EVERY_MS = 30_000L
        private const val METRES_PER_NM = 1852.0
    }
}
