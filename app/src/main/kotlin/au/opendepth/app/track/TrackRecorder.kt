package au.opendepth.app.track

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.content.ContextCompat
import au.opendepth.app.diagnostics.DiagnosticLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The track being recorded, shared by [TrackRecordingService] (which feeds
 * it GPS fixes, screen on or off) and the chart (which draws it). Whether a
 * recording is running is kept in preferences, so it carries on after the
 * app or phone restarts until it is stopped.
 */
object TrackRecorder {

    fun interface Listener {
        fun trackChanged()
    }

    private var store: TrackStore? = null
    private val listeners = mutableSetOf<Listener>()
    private var loadedId: String? = null
    private val livePoints = mutableListOf<TrackPoint>()
    private var filter = TrackFilter()
    private var distanceM = 0.0

    fun store(context: Context): TrackStore =
        store ?: TrackStore(File(context.applicationContext.filesDir, "tracks")).also { store = it }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeId(context: Context): String? = prefs(context).getString(KEY_ID, null)

    fun isRecording(context: Context): Boolean = activeId(context) != null

    fun activeName(context: Context): String = prefs(context).getString(KEY_NAME, null) ?: "Track"

    fun startedMillis(context: Context): Long = prefs(context).getLong(KEY_STARTED, 0L)

    /** The live track so far (main thread). */
    fun points(context: Context): List<TrackPoint> {
        load(context)
        return livePoints
    }

    fun distanceM(context: Context): Double {
        load(context)
        return distanceM
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Starts a new recording and its service. Location permission must already be granted. */
    fun start(context: Context) {
        if (isRecording(context)) {
            ensureService(context)
            return
        }
        val now = System.currentTimeMillis()
        val name = SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(now))
        prefs(context).edit()
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putString(KEY_NAME, name)
            .putLong(KEY_STARTED, now)
            .apply()
        load(context)
        ensureService(context)
        notifyListeners()
    }

    /** (Re)starts the service for a recording that should be running. */
    fun ensureService(context: Context) {
        if (!isRecording(context) || TrackRecordingService.isRunning) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TrackRecordingService::class.java))
        }.onFailure { DiagnosticLog.logError(TAG, "couldn't start track recording", it) }
    }

    /** Stops recording and files the track. @return it, or null if the boat never moved. */
    fun stop(context: Context): TrackSummary? {
        val id = activeId(context) ?: return null
        val name = activeName(context)
        prefs(context).edit().remove(KEY_ID).remove(KEY_NAME).remove(KEY_STARTED).apply()
        context.stopService(Intent(context, TrackRecordingService::class.java))
        val summary = runCatching { store(context).finish(id, uniqueName(context, name)) }
            .onFailure { DiagnosticLog.logError(TAG, "couldn't save the track", it) }
            .getOrNull()
        loadedId = null
        livePoints.clear()
        distanceM = 0.0
        notifyListeners()
        return summary
    }

    /** A GPS fix from the service. */
    fun onFix(context: Context, location: Location) {
        val id = activeId(context) ?: return
        load(context)
        val point = filter.accept(
            // The phone's clock, not the fix's: some GPS chips report dates years out (week rollover).
            System.currentTimeMillis(),
            location.latitude,
            location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else null
        ) ?: return
        runCatching { store(context).append(id, point) }
            .onFailure { DiagnosticLog.logError(TAG, "couldn't write a track point", it) }
        livePoints.lastOrNull()?.let { previous ->
            if (!TrackMath.isGap(previous, point)) distanceM += TrackMath.distanceM(previous, point)
        }
        livePoints += point
        notifyListeners()
    }

    /** Reads the recording's points back from disk after a restart. */
    private fun load(context: Context) {
        val id = activeId(context)
        if (id == loadedId) return
        loadedId = id
        livePoints.clear()
        distanceM = 0.0
        if (id != null) {
            livePoints += store(context).points(id)
            distanceM = TrackMath.summarise(id, "", livePoints).distanceM
        }
        filter = TrackFilter(livePoints.lastOrNull())
    }

    private fun uniqueName(context: Context, base: String): String {
        val store = store(context)
        if (!store.isNameTaken(base)) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { !store.isNameTaken(it) }
    }

    private fun notifyListeners() = listeners.toList().forEach { it.trackChanged() }

    private const val TAG = "TrackRecorder"
    private const val PREFS = "seabathy_track_recording"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_STARTED = "started"
}
