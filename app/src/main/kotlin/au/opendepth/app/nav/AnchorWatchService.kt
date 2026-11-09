package au.opendepth.app.nav

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.opendepth.app.MainActivity
import au.opendepth.app.R
import au.opendepth.app.diagnostics.DiagnosticLog

/**
 * Keeps the anchor alarm watching with the screen off or another app in
 * front: a location foreground service feeding [AnchorMonitor], holding the
 * CPU awake so the GPS-lost check and the alarm sound keep running. Shows the
 * distance from the anchor, and a loud notification with Silence when it alarms.
 */
class AnchorWatchService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private var listener: LocationListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedAt = 0L
    private var lastAlarm: AnchorMonitor.Alarm? = null
    private var lastSilenced = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            AnchorMonitor.tick(this@AnchorWatchService)
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val monitorListener = AnchorMonitor.Listener { updateNotifications() }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Anchor watch", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the anchor alarm is on"
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Anchor alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The anchor is dragging or GPS is lost"
                // SeaBathy sounds the alarm itself, on the alarm volume.
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SILENCE) {
            AnchorMonitor.silence()
            return START_STICKY
        }
        if (!AnchorMonitor.isWatching(this) || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, WATCH_ID, watchNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.onFailure { DiagnosticLog.logError(TAG, "couldn't start the anchor watch in the foreground", it) }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            acquireWakeLock()
            startUpdates()
            AnchorMonitor.addListener(monitorListener)
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_MS)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (listener != null) return
        val active = object : LocationListener {
            override fun onLocationChanged(location: Location) = AnchorMonitor.onFix(this@AnchorWatchService, location)
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, active)
            listener = active
        }.onFailure { DiagnosticLog.logError(TAG, "GPS unavailable for the anchor watch", it) }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        // Without it the 5 s check (and the repeating alarm) can stall while the phone sleeps.
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeaBathy:anchor").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        AnchorMonitor.removeListener(monitorListener)
        listener?.let { runCatching { locationManager.removeUpdates(it) } }
        listener = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        notificationManager.cancel(ALARM_ID)
        AnchorMonitor.stopSound()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Notifications ---------------------------------------------------------------

    private fun updateNotifications() {
        val alarm = AnchorMonitor.alarm
        val silenced = AnchorMonitor.silenced
        val changed = alarm != lastAlarm || silenced != lastSilenced
        lastAlarm = alarm
        lastSilenced = silenced
        val now = SystemClock.elapsedRealtime()
        val due = changed || now - lastNotifiedAt > NOTIFY_EVERY_MS
        if (alarm == null || silenced) {
            if (changed) notificationManager.cancel(ALARM_ID)
        } else if (due) {
            // Re-posted with the latest distance; it only pops up and alerts the first time.
            runCatching { notificationManager.notify(ALARM_ID, alarmNotification(alarm)) }
        }
        if (due) {
            lastNotifiedAt = now
            runCatching { notificationManager.notify(WATCH_ID, watchNotification()) }
        }
    }

    private fun format(): NavFormat =
        NavFormat(NavSettingsRepository(getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)).load().units)

    private fun watchNotification(): Notification {
        val f = format()
        val anchor = AnchorMonitor.anchor(this)
        val status = AnchorMonitor.status
        val text = when {
            AnchorMonitor.alarm == AnchorMonitor.Alarm.NO_GPS -> "No GPS — can't see the boat"
            status == null -> "Waiting for GPS…"
            else -> "Boat ${f.offset(status.distanceM)} from anchor · ${anchor?.let { f.offset(it.radiusM) } ?: "—"} circle"
        }
        return NotificationCompat.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_anchor_line)
            .setContentTitle("Anchor alarm on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .build()
    }

    private fun alarmNotification(alarm: AnchorMonitor.Alarm): Notification {
        val f = format()
        val (title, text) = when (alarm) {
            AnchorMonitor.Alarm.DRAGGING -> "Anchor dragging" to AnchorMonitor.draggingText(this, f)
            AnchorMonitor.Alarm.NO_GPS -> "No GPS" to "SeaBathy lost GPS for over a minute, so it couldn't watch the anchor."
        }
        val silence = PendingIntent.getService(
            this,
            2,
            Intent(this, AnchorWatchService::class.java).apply { action = ACTION_SILENCE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_anchor_line)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            // Opens the chart over the lock screen, where the alarm can be silenced.
            .setFullScreenIntent(openApp(), true)
            .addAction(0, "Silence", silence)
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        /** True while the service is watching in this process. */
        @Volatile
        var isRunning = false
            private set

        private const val TAG = "AnchorWatchService"
        private const val CHANNEL_WATCH = "anchor_watch"
        private const val CHANNEL_ALARM = "anchor_alarm"
        private const val WATCH_ID = 4103
        private const val ALARM_ID = 4104
        private const val ACTION_SILENCE = "au.opendepth.app.nav.ACTION_SILENCE"
        private const val UPDATE_MS = 1_000L
        private const val TICK_MS = 5_000L
        private const val NOTIFY_EVERY_MS = 15_000L
    }
}
