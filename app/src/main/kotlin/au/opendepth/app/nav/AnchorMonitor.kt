package au.opendepth.app.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import au.opendepth.app.MainActivity
import au.opendepth.app.diagnostics.DiagnosticLog

/**
 * The anchor watch itself, shared by [AnchorWatchService] (which feeds it
 * GPS and keeps it running with the screen off) and the chart (which shows
 * it). One place decides when the alarm sounds, so it never sounds twice.
 */
object AnchorMonitor {

    fun interface Listener {
        fun anchorChanged()
    }

    /** Why the alarm is sounding, if it is. */
    enum class Alarm { DRAGGING, NO_GPS }

    private val listeners = mutableSetOf<Listener>()
    private var loaded = false
    private var alarms: NavAlarms? = null
    private var siren: AnchorSiren? = null

    var watch: AnchorWatch? = null
        private set
    var status: AnchorStatus? = null
        private set
    var alarm: Alarm? = null
        private set
    /**
     * Silenced for this alarm; it can sound again once the boat is back
     * inside (or GPS is back). Until then the alarm keeps sounding, even if
     * the boat swings back inside: dragging once is worth waking up for.
     */
    var silenced = false
        private set
    private var lastFix: Location? = null
    private var lastFixAt = 0L

    private fun repository(context: Context) =
        AnchorRepository(context.applicationContext.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE))

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        watch = repository(context).load()?.let { AnchorWatch(it) }
        lastFixAt = SystemClock.elapsedRealtime()
    }

    fun isWatching(context: Context): Boolean {
        load(context)
        return watch != null
    }

    fun anchor(context: Context): Anchor? {
        load(context)
        return watch?.anchor
    }

    fun lastRadiusM(context: Context) = repository(context).lastRadiusM

    fun setLastRadiusM(context: Context, radiusM: Double) {
        repository(context).lastRadiusM = radiusM
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Sets, moves or (null) lifts the anchor. With [keepStats] (a new circle
     * size) the furthest swing so far is kept.
     */
    fun set(context: Context, anchor: Anchor?, keepStats: Boolean = false) {
        load(context)
        repository(context).save(anchor)
        val previous = watch
        watch = anchor?.let { next ->
            AnchorWatch(next).also { w -> if (keepStats && previous != null) w.carryOver(previous) }
        }
        if (anchor == null || !keepStats || previous == null) status = null
        calm()
        if (anchor != null) {
            lastFixAt = SystemClock.elapsedRealtime()
            lastFix?.let { evaluate(context, it) }
            ensureService(context)
        } else {
            context.stopService(Intent(context, AnchorWatchService::class.java))
        }
        notifyListeners()
    }

    /** Starts the service for an anchor that should be watched (after a restart, or once location is allowed). */
    fun ensureService(context: Context) {
        if (!isWatching(context) || AnchorWatchService.isRunning) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AnchorWatchService::class.java))
        }.onFailure { DiagnosticLog.logError(TAG, "couldn't start the anchor watch", it) }
    }

    /** A GPS fix from the service. */
    fun onFix(context: Context, location: Location) {
        lastFix = location
        lastFixAt = SystemClock.elapsedRealtime()
        evaluate(context, location)
    }

    /**
     * Run every few seconds: GPS silent for a minute is an alarm too, and
     * a boat sitting outside the circle keeps sounding even if fixes stop changing.
     */
    fun tick(context: Context) {
        if (!isWatching(context)) return
        if (SystemClock.elapsedRealtime() - lastFixAt > GPS_QUIET_MS) {
            raise(context, Alarm.NO_GPS)
            notifyListeners()
        } else {
            lastFix?.let { evaluate(context, it) }
        }
    }

    /** Stops the sound until the alarm clears and comes back. */
    fun silence() {
        if (alarm == null) return
        silenced = true
        siren?.stop()
        notifyListeners()
    }

    private fun evaluate(context: Context, location: Location) {
        val w = watch ?: return
        val s = w.update(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy.toDouble() else null)
        status = s
        when {
            s.dragging -> raise(context, Alarm.DRAGGING)
            // Not acknowledged yet: keep ringing although the boat is back inside (or GPS is back).
            alarm != null && !silenced -> raise(context, alarm!!)
            !s.gpsPoor -> calm()
            // A rough fix still proves GPS is alive.
            alarm == Alarm.NO_GPS -> calm()
        }
        notifyListeners()
    }

    private fun raise(context: Context, why: Alarm) {
        if (alarm != why) {
            alarm = why
            silenced = false
        }
        if (silenced) {
            siren?.stop()
            return
        }
        val settings = NavSettingsRepository(context.applicationContext.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)).load()
        val ringer = siren ?: AnchorSiren(context).also { siren = it }
        ringer.start(sound = settings.alarmSound, vibrate = settings.alarmVibrate)
        if (settings.alarmSound && !ringer.isRinging) {
            // No alarm tone on this phone: beep instead.
            val sounder = alarms ?: NavAlarms(context.applicationContext).also { alarms = it }
            sounder.soundOn = true
            sounder.vibrateOn = false
            sounder.trigger(NavAlarm.ANCHOR)
        }
    }

    private fun calm() {
        alarm = null
        silenced = false
        siren?.stop()
        alarms?.clear(NavAlarm.ANCHOR)
    }

    /** What the dragging alarm says: where the boat is now, inside the circle again or not. */
    fun draggingText(context: Context, f: NavFormat): String {
        val r = anchor(context)?.radiusM
        val d = status?.distanceM
        val circle = r?.let { "the ${f.offset(it)} circle" } ?: "the circle"
        return when {
            d == null -> "The boat went outside $circle."
            r != null && d <= r -> "The boat went outside $circle. It's ${f.offset(d)} from the anchor now: check the anchor is holding."
            else -> "The boat is ${f.offset(d)} from the anchor, outside $circle."
        }
    }

    /** The watch service is going away: nothing is left to keep ringing. */
    fun stopSound() {
        siren?.stop()
    }

    private fun notifyListeners() = listeners.toList().forEach { it.anchorChanged() }

    private const val TAG = "AnchorMonitor"
    const val GPS_QUIET_MS = 60_000L
}
