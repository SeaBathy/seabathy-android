package au.opendepth.app.track

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MapLayerIds
import au.opendepth.app.model.RoutePoint
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavSettings
import au.opendepth.app.storage.GpxExportService
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.UiRadius
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Track recording: start and stop, the live trail on the chart and a chip
 * with the distance so far, and the saved tracks (show on the chart, share
 * as GPX, rename, delete). The recording itself runs in
 * [TrackRecordingService] so it carries on with the screen off.
 */
class TrackFeature(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val settings: () -> NavSettings,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        /** GPS on, asking for location permission if needed. */
        fun startGps()
        fun requestNotifications()
        fun isNavigating(): Boolean
        fun fit(points: List<RoutePoint>)
        fun toast(message: String)
        fun closePanels()
    }

    private val palette = AppTheme.palette
    private val store get() = TrackRecorder.store(activity)
    private val gpxExport = GpxExportService(activity)

    /** A saved track shown on the chart, and its points. */
    private var shownId: String? = null
    private var shownPoints: List<TrackPoint> = emptyList()
    /** Start was tapped before location was allowed: start on the first fix. */
    private var startWhenGpsArrives = false
    private var panel: Dialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastDrawAt = 0L
    private val drawLater = Runnable { drawNow() }
    private val listener = TrackRecorder.Listener {
        refreshChip()
        drawThrottled()
    }

    private val chipText = TextView(activity).apply {
        textSize = 13f
        setTextColor(palette.primaryText)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
    }
    private val chip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
        elevation = activity.dp(4).toFloat()
        background = activity.roundedBackground(0xE6111827.toInt(), palette.border, 16)
        visibility = View.GONE
        contentDescription = "Track recording — open"
        addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RECORD_RED)
            }
        }, LinearLayout.LayoutParams(activity.dp(10), activity.dp(10)).apply { marginEnd = activity.dp(8) })
        addView(chipText)
        setOnClickListener { show() }
    }

    val isRecording: Boolean get() = TrackRecorder.isRecording(activity)

    /** Adds the chip to the column of status chips under the top bar. */
    fun bind(chips: LinearLayout) {
        chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(6)
        })
        refreshChip()
    }

    fun onResume() {
        TrackRecorder.addListener(listener)
        // Still recording from before (the app was closed or the phone restarted).
        TrackRecorder.ensureService(activity)
        refreshChip()
        drawNow()
    }

    fun onPause() {
        TrackRecorder.removeListener(listener)
        handler.removeCallbacks(drawLater)
    }

    fun release() = onPause()

    fun onLocation(location: Location) {
        if (startWhenGpsArrives && hasLocationPermission()) {
            startWhenGpsArrives = false
            startRecording()
        }
    }

    fun navigationChanged() = refreshChip()

    // Recording ---------------------------------------------------------------

    private fun startRecording() {
        if (!hasLocationPermission()) {
            startWhenGpsArrives = true
            callbacks.startGps()
            callbacks.toast("Allow location and recording starts")
            return
        }
        callbacks.requestNotifications()
        TrackRecorder.start(activity)
        callbacks.toast("Recording track — it carries on with the screen off")
        refreshChip()
        drawNow()
    }

    private fun stopRecording() {
        val saved = TrackRecorder.stop(activity)
        callbacks.toast(
            if (saved == null) "Track discarded — the boat didn't move"
            else "Track saved: ${saved.name}, ${format().distance(saved.distanceM / METRES_PER_NM)}"
        )
        refreshChip()
        drawNow()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Chart -------------------------------------------------------------------

    private fun refreshChip() {
        if (!isRecording || callbacks.isNavigating()) {
            chip.visibility = View.GONE
            return
        }
        chipText.text = "Recording ${format().distance(TrackRecorder.distanceM(activity) / METRES_PER_NM)}"
        chip.visibility = View.VISIBLE
    }

    /** Redraws the trails after a style rebuild. */
    fun refreshMap() = drawNow()

    private fun drawThrottled() {
        val wait = DRAW_EVERY_MS - (SystemClock.elapsedRealtime() - lastDrawAt)
        handler.removeCallbacks(drawLater)
        if (wait <= 0) drawNow() else handler.postDelayed(drawLater, wait)
    }

    private fun drawNow() {
        lastDrawAt = SystemClock.elapsedRealtime()
        val source = mapHolder.style?.getSourceAs<GeoJsonSource>(MapLayerIds.TRACK_SOURCE) ?: return
        val features = mutableListOf<String>()
        if (isRecording) trail(TrackRecorder.points(activity), "live")?.let { features += it }
        if (shownId != null) trail(shownPoints, "saved")?.let { features += it }
        source.setGeoJson("""{"type":"FeatureCollection","features":[${features.joinToString(",")}]}""")
    }

    /** A track as a GeoJSON MultiLineString, thinned for drawing when it is long. */
    private fun trail(points: List<TrackPoint>, kind: String): String? {
        val lines = TrackMath.segments(points)
            .map { if (it.size > SIMPLIFY_ABOVE) TrackMath.simplify(it, SIMPLIFY_M) else it }
            .filter { it.size >= 2 }
        if (lines.isEmpty()) return null
        val coordinates = lines.joinToString(",") { line -> line.joinToString(",", "[", "]") { "[${it.lng},${it.lat}]" } }
        return """{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[$coordinates]},"properties":{"kind":"$kind"}}"""
    }

    private fun showOnChart(summary: TrackSummary) {
        scope.launch {
            val points = withContext(Dispatchers.IO) { store.points(summary.id) }
            if (points.size < 2) return@launch callbacks.toast("That track has no points")
            shownId = summary.id
            shownPoints = points
            drawNow()
            callbacks.closePanels()
            callbacks.fit(points.map { RoutePoint(it.lat, it.lng) })
        }
    }

    private fun hideFromChart() {
        shownId = null
        shownPoints = emptyList()
        drawNow()
    }

    // Panels ------------------------------------------------------------------

    fun show() {
        panel?.dismiss()
        val body = panels.body()
        val f = format()
        if (isRecording) {
            val started = TrackRecorder.startedMillis(activity)
            body.addView(TextView(activity).apply {
                text = "Recording · ${f.distance(TrackRecorder.distanceM(activity) / METRES_PER_NM)}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
                setPadding(activity.dp(2), 0, 0, activity.dp(2))
            })
            body.addView(panels.caption(
                "Started ${time(started)} · ${TrackMath.duration(System.currentTimeMillis() - started)} so far. " +
                    "Keeps recording with the screen off; stop it from here or the notification.",
                topPaddingDp = 0
            ))
            body.addView(button("Stop and save", primary = true) {
                stopRecording()
                show()
            }, fullWidth())
        } else {
            body.addView(panels.caption(
                "Records where the boat goes, even with the screen off. Show a trip on the chart later, or share it as a GPX file.",
                topPaddingDp = 0
            ))
            body.addView(button("Start recording", primary = true) {
                panel?.dismiss()
                startRecording()
            }, fullWidth())
        }
        shownId?.let { id ->
            val name = store.summary(id)?.name ?: "track"
            body.addView(button("Hide $name from the chart") {
                hideFromChart()
                show()
            }, fullWidth(top = 8))
        }

        body.addView(panels.sectionHeader("SAVED TRACKS"))
        val tracks = store.summaries()
        if (tracks.isEmpty()) body.addView(panels.caption("No saved tracks yet.", topPaddingDp = 0))
        tracks.forEach { track ->
            panels.addRow(body, panels.row(track.name, listLine(track)) { showTrack(track) })
        }
        panel = panels.showPanel("Tracks", body, onDismiss = { if (panel?.isShowing != true) panel = null })
    }

    private fun showTrack(track: TrackSummary) {
        val body = panels.body()
        val f = format()
        lateinit var dialog: Dialog
        body.addView(panels.caption(span(track), topPaddingDp = 0))
        val stats = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
            setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(8))
        }
        stats.addView(stat("Distance", f.distance(track.distanceM / METRES_PER_NM)))
        if (track.endMillis > track.startMillis) {
            stats.addView(stat("Time", TrackMath.duration(track.endMillis - track.startMillis)))
            stats.addView(stat("Moving", TrackMath.duration(track.movingMillis)))
            stats.addView(stat("Average speed", f.speed(track.averageSpeedMs * MS_TO_KNOTS)))
            stats.addView(stat("Top speed", f.speed(track.topSpeedMs * MS_TO_KNOTS)))
        }
        panels.addRow(body, stats)

        body.addView(button("Show on chart", primary = true) {
            dialog.dismiss()
            showOnChart(track)
        }, fullWidth(top = 4))
        val more = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        more.addView(button("Share GPX") { share(track) }, weighted())
        more.addView(button("Rename") {
            promptName(track) { name ->
                store.rename(track.id, name)
                dialog.dismiss()
                show()
            }
        }, weighted(start = 8))
        more.addView(button("Delete", destructive = true) {
            panels.confirm("Delete ${track.name}?", null) {
                store.delete(track.id)
                if (shownId == track.id) hideFromChart()
                dialog.dismiss()
                show()
            }
        }, weighted(start = 8))
        body.addView(more, fullWidth(top = 8))
        dialog = panels.showPanel(track.name, body)
    }

    private fun share(track: TrackSummary) {
        scope.launch {
            val intent = runCatching {
                withContext(Dispatchers.IO) { gpxExport.buildTrackShareIntent(track.name, store.points(track.id)) }
            }.onFailure { DiagnosticLog.logError(TAG, "track GPX export failed", it) }.getOrNull()
            if (intent == null) return@launch callbacks.toast("Couldn't create the GPX file")
            runCatching { activity.startActivity(intent) }.onFailure { callbacks.toast("No app available to receive the GPX file") }
        }
    }

    private fun promptName(track: TrackSummary, onName: (String) -> Unit) {
        val body = panels.body()
        val input = panels.input {
            setText(track.name)
            setSingleLine(true)
            hint = "Track name"
            selectAll()
        }
        panels.addRow(body, input)
        lateinit var dialog: Dialog
        dialog = panels.showPanel("Rename track", body, actionLabel = "Save", onAction = {
            val name = input.text.toString().trim()
            when {
                name.isEmpty() -> input.error = "Enter a name"
                store.isNameTaken(name, track.id) -> input.error = "A track called \"$name\" already exists"
                else -> {
                    dialog.dismiss()
                    onName(name)
                }
            }
        })
        input.requestFocus()
    }

    // Imported from GPX ---------------------------------------------------------

    /** Saves tracks read from a GPX file. @return how many were saved. */
    fun import(tracks: List<Pair<String?, List<TrackPoint>>>): Int {
        var saved = 0
        tracks.forEach { (name, points) ->
            val base = name?.takeIf { it.isNotBlank() } ?: "Imported track"
            val unique = if (!store.isNameTaken(base)) base
            else generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { !store.isNameTaken(it) }
            if (store.add(UUID.randomUUID().toString(), unique, points) != null) saved++
        }
        return saved
    }

    // Pieces --------------------------------------------------------------------

    private fun format() = NavFormat(settings().units)

    private fun listLine(track: TrackSummary): String = buildString {
        // A recorded track is named for when it started: the date isn't said twice.
        val start = Date(track.startMillis)
        if (track.startMillis > 0 && !track.name.startsWith(SimpleDateFormat("d MMM", Locale.US).format(start))) {
            append(SimpleDateFormat("d MMM yyyy", Locale.US).format(start)).append(" · ")
        }
        append(format().distance(track.distanceM / METRES_PER_NM))
        if (track.endMillis > track.startMillis) append(" · ").append(TrackMath.duration(track.endMillis - track.startMillis))
    }

    private fun span(track: TrackSummary): String {
        if (track.startMillis <= 0) return "${track.pointCount} points, no times recorded"
        val day = SimpleDateFormat("EEE d MMM yyyy", Locale.US)
        val sameDay = day.format(Date(track.startMillis)) == day.format(Date(track.endMillis))
        return if (sameDay) "${day.format(Date(track.startMillis))}, ${time(track.startMillis)} – ${time(track.endMillis)}"
        else "${day.format(Date(track.startMillis))} ${time(track.startMillis)} – ${day.format(Date(track.endMillis))} ${time(track.endMillis)}"
    }

    private fun time(millis: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

    private fun stat(label: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, activity.dp(5), 0, activity.dp(5))
        addView(TextView(activity).apply {
            text = label
            textSize = 13f
            setTextColor(palette.secondaryText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = value
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.primaryText)
        })
    }

    private fun button(label: String, primary: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        if (primary) applyPrimaryStyle(palette) else applySecondaryStyle(palette)
        if (destructive) setTextColor(palette.destructive)
        setOnClickListener { onClick() }
    }

    private fun fullWidth(top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(top)
        }

    private fun weighted(start: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dp(start) }

    private companion object {
        const val TAG = "TrackFeature"
        const val METRES_PER_NM = 1852.0
        const val MS_TO_KNOTS = 1.943844
        const val DRAW_EVERY_MS = 2_000L
        const val SIMPLIFY_ABOVE = 1_500
        const val SIMPLIFY_M = 2.0
        const val RECORD_RED = 0xFFEF4444.toInt()
    }
}
