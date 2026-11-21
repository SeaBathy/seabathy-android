package au.opendepth.app.fishing

import android.app.Dialog
import android.graphics.Typeface
import android.location.Location
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import au.opendepth.app.conditions.ConditionsArea
import au.opendepth.app.conditions.ForecastField
import au.opendepth.app.map.GeoJson
import au.opendepth.app.map.MapHolder
import au.opendepth.app.model.Waypoint
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavGeo
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applyReadableText
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.util.CoordinateFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The drift planner (Settings, Boat and navigation): pick a mark (a waypoint
 * or the crosshair), drift near it for a minute or two with the engine out
 * of gear while the GPS measures the drift, then see where to start so the
 * drift carries the boat over the mark, the drift line, and how long it
 * spends over it; and go to the start with the usual navigation. Before a
 * trip, a saved sea conditions forecast gives a rough estimate instead.
 */
class DriftFeature(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun units(): NavUnits
        /** Magnetic variation at a place, east positive, for bearings in the chosen reference. */
        fun variation(lat: Double, lng: Double): Double
        fun mapCentre(): Pair<Double, Double>?
        fun waypoints(): List<Waypoint>
        /** GPS on, asking for permission when needed. */
        fun startGps()
        /** Steers to a point with the app's navigation. */
        fun navigateTo(lat: Double, lng: Double, name: String)
        /** Saves a waypoint, under a free name close to [name]; returns the name used, or null. */
        fun saveWaypoint(name: String, lat: Double, lng: Double, description: String): String?
        /** A saved sea conditions area covering the point (read only), or null. */
        fun conditionsAt(lat: Double, lng: Double): ConditionsArea?
        fun toast(message: String)
    }

    private class Target(val lat: Double, val lng: Double, val name: String)

    private val palette = AppTheme.palette
    private val layer = ToolMarksLayer(mapHolder, "drift-plan")

    private var target: Target? = null
    private var lastFix: Location? = null
    private var recording = false
    private var recordStarted = 0L
    /** When the test drift started, on the fixes' clock (time since boot), to tell fixes from before it. */
    private var recordStartedClock = 0L
    private val fixes = ArrayList<DriftPlan.Fix>()
    private var ticker: Job? = null
    private var measurement: DriftPlan.Measurement? = null
    private var drift: DriftPlan.Drift? = null
    private var leadSeconds = DriftPlan.DEFAULT_LEAD_S
    /** The forecast hour the estimate is for: 0 is now. */
    private var forecastHoursAhead = 0

    private var panel: Dialog? = null
    private var body: LinearLayout? = null
    private var status: TextView? = null

    /** Puts the drift back on a new style: from the activity's style-installed hook. */
    fun install() = layer.install()

    /** A GPS fix: kept for the boat's position, and recorded during a test drift. */
    fun onLocation(location: Location) {
        lastFix = location
        if (!recording) return
        val time = clockOf(location)
        // The phone's last known position can come in first, minutes old (just after the GPS is turned
        // on, or permission granted): it isn't part of this drift.
        if (!DriftPlan.takenSince(time, recordStartedClock)) return
        fixes += DriftPlan.Fix(location.latitude, location.longitude, time, if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0)
    }

    /**
     * When a fix was taken, as time since boot: the same clock for every
     * provider, and not upset by the phone's clock being set wrong.
     */
    private fun clockOf(location: Location): Long {
        if (location.elapsedRealtimeNanos > 0) return location.elapsedRealtimeNanos / 1_000_000
        val now = System.currentTimeMillis()
        val age = (now - (location.time.takeIf { it > 0 } ?: now)).coerceAtLeast(0)
        return SystemClock.elapsedRealtime() - age
    }

    /**
     * Keeps the screen on while a test drift records: with it off the GPS
     * stops. Set on the window's own view, so it adds to (and never clears)
     * the screen-on that navigation keeps.
     */
    private fun keepScreenOn(on: Boolean) {
        activity.window?.decorView?.keepScreenOn = on
    }

    fun show() {
        if (target == null) callbacks.mapCentre()?.let { (lat, lng) -> target = Target(lat, lng, CENTRE) }
        panel?.dismiss()
        drawPlan()
        val content = panels.body()
        body = content
        render()
        lateinit var shown: Dialog
        shown = panels.showPanel(TITLE, content, overMap = true, onDismiss = {
            // A dialog says it was dismissed a moment later: by then a new panel may have replaced it.
            if (panel === shown) {
                panel = null
                body = null
                status = null
                // Nothing planned: the mark isn't left on the chart.
                if (drift == null && !recording) layer.clear()
            }
        })
        panel = shown
    }

    fun release() {
        ticker?.cancel()
        recording = false
        keepScreenOn(false)
    }

    // Recording --------------------------------------------------------------------

    private fun startRecording() {
        if (target == null) {
            callbacks.toast("Pick the mark to drift over first")
            return
        }
        callbacks.startGps()
        fixes.clear()
        measurement = null
        recording = true
        recordStarted = System.currentTimeMillis()
        recordStartedClock = SystemClock.elapsedRealtime()
        keepScreenOn(true)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && recording) {
                val seconds = (System.currentTimeMillis() - recordStarted) / 1000.0
                status?.text = recordingText(seconds)
                if (seconds >= DriftPlan.MAX_SECONDS) {
                    stopRecording()
                    break
                }
                delay(1000)
            }
        }
        render()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        ticker?.cancel()
        keepScreenOn(false)
        val usable = DriftPlan.usable(fixes, startedMillis = recordStartedClock)
        val measured = DriftPlan.measure(usable)
        measurement = measured
        if (measured.problem == null) drift = measured.drift
        drawPlan()
        if (panel == null) {
            // Closed while it recorded: the result comes up by itself.
            show()
        } else {
            render()
        }
    }

    private fun recordingText(seconds: Double): String {
        val usable = DriftPlan.usable(fixes, startedMillis = recordStartedClock)
        val moved = if (usable.size >= 2) NavGeo.distanceM(usable.first().lat, usable.first().lng, usable.last().lat, usable.last().lng) else 0.0
        val left = (DriftPlan.MAX_SECONDS - seconds).coerceAtLeast(0.0)
        return if (usable.isEmpty()) "Recording ${clock(seconds)} · waiting for GPS"
        else "Recording ${clock(seconds)} · ${format().offset(moved)} so far · stops by itself in ${clock(left)}"
    }

    // Panel ------------------------------------------------------------------------

    private fun render() {
        val content = body ?: return
        content.removeAllViews()
        val f = format()
        content.addView(panels.caption(
            "Measures how the boat drifts, then shows where to start so the drift carries you over the mark. A guide only: watch the sounder and the water around you.",
            topPaddingDp = 0
        ))

        content.addView(panels.sectionHeader("MARK"))
        val t = target
        panels.addRow(content, panels.row("Drift over", t?.let { "${it.name} · ${CoordinateFormatter.format(it.lat, it.lng)}" } ?: "Not picked") { pickTarget() })

        content.addView(panels.sectionHeader("TEST DRIFT"))
        content.addView(panels.caption(
            "Near the mark, take the engine out of gear and let the boat drift. The GPS is recorded for up to two minutes, or until you stop it. Keep the screen on.",
            topPaddingDp = 0
        ))
        if (recording) {
            status = TextView(activity).apply {
                text = recordingText((System.currentTimeMillis() - recordStarted) / 1000.0)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(activity.dp(2), 0, 0, activity.dp(6))
            }
            content.addView(status)
            content.addView(button("Stop and measure", primary = true) {
                if (!DriftPlan.enough(DriftPlan.usable(fixes, startedMillis = recordStartedClock))) {
                    callbacks.toast("Keep drifting: at least ${DriftPlan.MIN_SECONDS.roundToInt()} seconds of GPS are needed")
                } else {
                    stopRecording()
                }
            }, buttonParams())
            content.addView(button("Cancel test drift") {
                recording = false
                ticker?.cancel()
                keepScreenOn(false)
                render()
            }, buttonParams())
        } else {
            status = null
            measurementText()?.let { content.addView(resultLine(it)) }
            content.addView(button(if (measurement == null) "Start test drift" else "Drift again", primary = drift == null) { startRecording() }, buttonParams())
        }

        forecastSection(content, f)

        val plan = t?.let { mark -> drift?.let { DriftPlan.plan(mark.lat, mark.lng, it, leadSeconds) } }
        if (plan != null) planSection(content, plan, f)
        applyReadableText(content, palette)
    }

    private fun measurementText(): String? {
        val m = measurement ?: return null
        val f = format()
        val d = m.drift
        return when (m.problem) {
            DriftPlan.Problem.TOO_FEW -> "Too few GPS fixes came in: check the GPS is on, and drift again."
            DriftPlan.Problem.TOO_SHORT -> "Too short to measure: drift for at least ${DriftPlan.MIN_SECONDS.roundToInt()} seconds."
            DriftPlan.Problem.TOO_LITTLE -> "The boat hardly moved (${f.offset(d?.metres ?: 0.0)}): the drift is too slow to plan with here, or the GPS too rough. Drift for longer to be sure."
            null -> d?.let {
                "Drifting ${f.current(it.speedMs * KNOTS_PER_MS)} towards ${DriftPlan.compass(it.towardsDegrees)} (${bearing(it.towardsDegrees)}), " +
                    "measured over ${clock(it.seconds)}."
            }
        }
    }

    private fun forecastSection(content: LinearLayout, f: NavFormat) {
        val t = target ?: return
        val area = callbacks.conditionsAt(t.lat, t.lng)
        val forecast = area?.forecast
        content.addView(panels.sectionHeader("FORECAST ESTIMATE"))
        if (forecast == null) {
            content.addView(panels.caption("No sea conditions forecast is saved for the mark. Download the area in Sea conditions for a rough estimate before you go.", topPaddingDp = 0))
            return
        }
        val now = System.currentTimeMillis()
        val hours = (0..MAX_HOURS_AHEAD step 3).filter { now + it * HOUR_MS <= forecast.endMillis }
        if (hours.isEmpty()) {
            content.addView(panels.caption("The saved forecast for the mark has run out: update it in Sea conditions.", topPaddingDp = 0))
            return
        }
        if (forecastHoursAhead !in hours) forecastHoursAhead = 0
        val time = now + forecastHoursAhead * HOUR_MS
        fun label(h: Int) = if (h == 0) "Now" else f.clock(now + h * HOUR_MS, now)
        lateinit var whenRow: TextView
        whenRow = panels.row("For", label(forecastHoursAhead)) {
            panels.showChoicePanel("For", hours.map { label(it) }, hours.indexOf(forecastHoursAhead)) { i ->
                forecastHoursAhead = hours[i]
                render()
            }
        }
        panels.addRow(content, whenRow)
        val ce = forecast.sample(ForecastField.CURRENT_U, t.lat, t.lng, time, coastal = true)
        val cn = forecast.sample(ForecastField.CURRENT_V, t.lat, t.lng, time, coastal = true)
        val we = forecast.sample(ForecastField.WIND_U, t.lat, t.lng, time, coastal = true)
        val wn = forecast.sample(ForecastField.WIND_V, t.lat, t.lng, time, coastal = true)
        val hasCurrent = ce.isFinite() && cn.isFinite()
        val hasWind = we.isFinite() && wn.isFinite()
        if (!hasCurrent && !hasWind) {
            content.addView(panels.caption("The saved forecast has no current or wind at the mark for then.", topPaddingDp = 0))
            return
        }
        val estimate = DriftPlan.estimate(
            if (hasCurrent) ce.toDouble() else 0.0, if (hasCurrent) cn.toDouble() else 0.0,
            if (hasWind) we.toDouble() else 0.0, if (hasWind) wn.toDouble() else 0.0
        )
        val parts = listOfNotNull(
            if (hasCurrent) {
                val c = DriftPlan.estimate(ce.toDouble(), cn.toDouble(), 0.0, 0.0)
                "current ${f.current(c.speedMs * KNOTS_PER_MS)} to ${DriftPlan.compass(c.towardsDegrees)}"
            } else "no current in this forecast",
            if (hasWind) {
                val w = DriftPlan.estimate(0.0, 0.0, we.toDouble(), wn.toDouble(), leeway = 1.0)
                "wind ${f.wind(w.speedMs * KNOTS_PER_MS)} from ${DriftPlan.compass(w.towardsDegrees + 180)}"
            } else null
        )
        content.addView(panels.caption(
            "Forecast at the mark: ${parts.joinToString(", ")}. A small boat would drift about " +
                "${f.current(estimate.speedMs * KNOTS_PER_MS)} towards ${DriftPlan.compass(estimate.towardsDegrees)}: " +
                "a rough estimate, as every boat drifts differently in the wind.",
            topPaddingDp = 0
        ))
        content.addView(button("Plan from the forecast") {
            measurement = null
            drift = estimate
            drawPlan()
            render()
        }, buttonParams())
    }

    private fun planSection(content: LinearLayout, plan: DriftPlan.Plan, f: NavFormat) {
        content.addView(panels.sectionHeader("WHERE TO START"))
        lateinit var leadRow: TextView
        leadRow = panels.row("Start up-drift by", leadLabel(leadSeconds, plan.drift.speedMs)) {
            val choices = DriftPlan.LEAD_CHOICES_S
            panels.showChoicePanel("Start up-drift by", choices.map { leadLabel(it, plan.drift.speedMs) }, choices.indexOf(leadSeconds)) { i ->
                leadSeconds = choices[i]
                drawPlan()
                render()
            }
        }
        panels.addRow(content, leadRow)
        val words = StringBuilder()
        words.append("Start ${f.offset(plan.leadMetres)} from the mark, ${bearing(plan.upDriftDegrees)} (${DriftPlan.compass(plan.upDriftDegrees)} of it). ")
        words.append("The drift reaches the mark after about ${minutes(plan.leadSeconds)} and stays within ${f.offset(plan.markRadiusM)} of it for about ${minutes(plan.overMarkSeconds)}.")
        lastFix?.let { fix ->
            val away = NavGeo.distanceM(fix.latitude, fix.longitude, plan.startLat, plan.startLng)
            val towards = NavGeo.bearing(fix.latitude, fix.longitude, plan.startLat, plan.startLng)
            words.append(" The start is ${f.offset(away)} from the boat, ${bearing(towards)}.")
        }
        if (plan.drift.estimate) words.append(" From the forecast: do a test drift on the spot to check it.")
        content.addView(resultLine(words.toString()))
        content.addView(button("Go to the start", primary = true) {
            panel?.dismiss()
            callbacks.navigateTo(plan.startLat, plan.startLng, START_NAME)
        }, buttonParams())
        content.addView(button("Save the start as a waypoint") {
            val name = callbacks.saveWaypoint(
                "Drift start", plan.startLat, plan.startLng,
                "Start of a drift over ${target?.name ?: "the mark"}: ${f.current(plan.drift.speedMs * KNOTS_PER_MS)} towards " +
                    "${DriftPlan.compass(plan.drift.towardsDegrees)}" + if (plan.drift.estimate) " (forecast estimate)." else " (test drift)."
            )
            if (name != null) callbacks.toast("Saved $name")
        }, buttonParams())
        content.addView(button("Clear the drift") {
            drift = null
            measurement = null
            // The mark goes too: the next drift starts from the crosshair, or a mark picked again.
            target = null
            layer.clear()
            render()
        }, buttonParams())
    }

    private fun pickTarget() {
        val centre = callbacks.mapCentre()
        val marks = callbacks.waypoints().let { all ->
            if (centre == null) all.take(MAX_WAYPOINT_CHOICES)
            else all.sortedBy { NavGeo.distanceM(centre.first, centre.second, it.lat, it.lng) }.take(MAX_WAYPOINT_CHOICES)
        }
        val f = format()
        val labels = listOf("The crosshair, in the middle of the chart") + marks.map { w ->
            if (centre == null) w.name else "${w.name} · ${f.offset(NavGeo.distanceM(centre.first, centre.second, w.lat, w.lng))} away"
        }
        panels.showChoicePanel("Drift over", labels) { i ->
            target = if (i == 0) {
                val (lat, lng) = callbacks.mapCentre() ?: return@showChoicePanel
                Target(lat, lng, CENTRE)
            } else {
                marks[i - 1].let { Target(it.lat, it.lng, it.name) }
            }
            drawPlan()
            render()
        }
    }

    // Chart ------------------------------------------------------------------------

    private fun drawPlan() {
        val t = target
        val plan = t?.let { mark -> drift?.let { DriftPlan.plan(mark.lat, mark.lng, it, leadSeconds) } }
        if (plan == null) {
            if (t == null) layer.clear()
            else layer.show(GeoJson.featureCollection(listOf(ToolMarksLayer.point(t.lat, t.lng, MARK_COLOUR, "Mark"))))
            return
        }
        layer.show(GeoJson.featureCollection(listOf(
            ToolMarksLayer.line(listOf(plan.startLat to plan.startLng, plan.targetLat to plan.targetLng, plan.endLat to plan.endLng), LINE_COLOUR, dashed = plan.drift.estimate),
            ToolMarksLayer.point(plan.targetLat, plan.targetLng, MARK_COLOUR, "Mark"),
            ToolMarksLayer.point(plan.startLat, plan.startLng, START_COLOUR, "Start")
        )))
    }

    // Pieces -----------------------------------------------------------------------

    private fun format() = NavFormat(callbacks.units())

    private fun bearing(trueDegrees: Double): String {
        val t = target
        return format().bearing(trueDegrees, if (t == null) 0.0 else callbacks.variation(t.lat, t.lng))
    }

    private fun leadLabel(seconds: Double, speedMs: Double): String =
        "${minutes(seconds)} of drift (${format().offset(seconds * speedMs)})"

    private fun minutes(seconds: Double): String {
        val m = seconds / 60
        return when {
            seconds < 60 -> "${seconds.roundToInt()} s"
            m < 10 && (m * 2).roundToInt() % 2 == 1 -> String.format(Locale.US, "%.1f min", m)
            else -> "${m.roundToInt()} min"
        }
    }

    private fun clock(seconds: Double): String {
        val s = seconds.roundToInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun resultLine(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setLineSpacing(activity.dp(2).toFloat(), 1f)
        setPadding(activity.dp(2), activity.dp(2), 0, activity.dp(6))
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        if (primary) applyPrimaryStyle(palette) else applySecondaryStyle(palette)
        setOnClickListener { onClick() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = activity.dp(8)
    }

    private companion object {
        const val TITLE = "Drift planner"
        const val CENTRE = "The crosshair"
        const val START_NAME = "Drift start"
        const val KNOTS_PER_MS = 1.943844
        const val HOUR_MS = 3_600_000L
        const val MAX_HOURS_AHEAD = 48
        const val MAX_WAYPOINT_CHOICES = 30
        const val LINE_COLOUR = "#38BDF8"
        const val MARK_COLOUR = "#FBBF24"
        const val START_COLOUR = "#22C55E"
    }
}
