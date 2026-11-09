package au.opendepth.app.nav

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.location.Location
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import au.opendepth.app.R
import au.opendepth.app.map.GeoJson
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MapLayerIds
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The anchor alarm on screen: drop the anchor at the boat (or the centre
 * dot), pick a swing circle, see it on the chart with a chip showing how far
 * the boat is from the anchor. The watching itself is [AnchorMonitor], run by
 * [AnchorWatchService], so it carries on with the screen off.
 */
class AnchorFeature(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    private val mapHolder: MapHolder,
    private val settings: () -> NavSettings,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun mapCentre(): Pair<Double, Double>?
        fun startGps()
        fun requestNotifications()
        fun isNavigating(): Boolean
        fun toast(message: String)
    }

    private val palette = AppTheme.palette
    private val monitor = AnchorMonitor

    /** The app's own latest fix, for dropping the anchor at the boat. */
    private var lastFix: Location? = null
    private var alert: android.app.Dialog? = null
    private var panel: android.app.Dialog? = null
    private val listener = AnchorMonitor.Listener { refresh() }

    val isWatching: Boolean get() = monitor.isWatching(activity)

    private val chipText = TextView(activity).apply {
        textSize = 13f
        setTextColor(palette.primaryText)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
    }
    private val chip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(10), activity.dp(6), activity.dp(12), activity.dp(6))
        elevation = activity.dp(4).toFloat()
        visibility = View.GONE
        contentDescription = "Anchor alarm — open"
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_anchor_line)
            imageTintList = ColorStateList.valueOf(palette.primaryText)
        }, LinearLayout.LayoutParams(activity.dp(18), activity.dp(18)).apply { marginEnd = activity.dp(6) })
        addView(chipText)
        setOnClickListener { show() }
    }

    /** Adds the chip to the column of status chips under the top bar. */
    fun bind(chips: LinearLayout) {
        chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(6)
        })
        // Still anchored from last time: GPS on for the chart (the watch has its own).
        if (isWatching) callbacks.startGps()
        refresh()
    }

    fun onResume() {
        monitor.addListener(listener)
        monitor.ensureService(activity)
        refresh()
    }

    fun onPause() = monitor.removeListener(listener)

    fun release() {
        onPause()
        dismissAlert()
    }

    fun onLocation(location: Location) {
        lastFix = location
        // Location may only now be allowed (anchored at the centre dot before GPS was on).
        monitor.ensureService(activity)
    }

    fun refreshMap() {
        val source = mapHolder.style?.getSourceAs<GeoJsonSource>(MapLayerIds.ANCHOR_SOURCE) ?: return
        val a = monitor.anchor(activity)
        source.setGeoJson(if (a == null) MapLayerIds.EMPTY_GEOJSON else GeoJson.featureCollection(listOf(circle(a), GeoJson.point(a.lat, a.lng))))
    }

    // Panel -------------------------------------------------------------------

    fun show() {
        panel?.dismiss()
        val body = panels.body()
        val w = monitor.watch.takeIf { isWatching }
        var radius = w?.anchor?.radiusM ?: monitor.lastRadiusM(activity)
        val f = format()
        if (w == null) {
            body.addView(panels.caption(
                "Sounds an alarm if the boat swings outside the circle or GPS stops. Keeps watching with the screen off or another app open.",
                topPaddingDp = 0
            ))
        } else {
            val s = monitor.status
            val line = when {
                monitor.alarm == AnchorMonitor.Alarm.NO_GPS -> "No GPS for over a minute"
                s == null -> "Waiting for GPS…"
                s.gpsPoor -> "GPS is rough right now (${f.offset(s.distanceM)} from anchor)"
                else -> "Boat ${f.offset(s.distanceM)} from anchor · furthest ${f.offset(w.maxDistanceM)}"
            }
            body.addView(TextView(activity).apply {
                text = line
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (monitor.alarm != null) DRAG_RED else palette.primaryText)
                setPadding(activity.dp(2), 0, 0, activity.dp(2))
            })
            body.addView(panels.caption("Anchored at ${SimpleDateFormat("HH:mm", Locale.US).format(Date(w.anchor.setMillis))}", topPaddingDp = 0))
        }

        lateinit var radiusRow: TextView
        radiusRow = panels.row("Alarm circle", f.offset(radius)) {
            val choices = AnchorWatch.RADII_M
            panels.showChoicePanel("Alarm circle", choices.map { f.offset(it) }, choices.indexOf(radius)) { i ->
                radius = choices[i]
                monitor.setLastRadiusM(activity, radius)
                radiusRow.text = "Alarm circle\n${f.offset(radius)}"
                monitor.anchor(activity)?.let { setAnchor(it.copy(radiusM = radius), keepStats = true) }
            }
        }
        panels.addRow(body, radiusRow)
        body.addView(panels.caption("Allow for your anchor line plus a boat length.", topPaddingDp = 0))

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(button(if (w == null) "Drop anchor at the boat" else "Move anchor to the boat", primary = w == null) {
            val fix = lastFix
            if (fix == null) {
                callbacks.startGps()
                callbacks.toast("Waiting for GPS — try again in a moment")
                return@button
            }
            setAnchor(Anchor(fix.latitude, fix.longitude, radius, System.currentTimeMillis()))
            panel?.dismiss()
        })
        buttons.addView(button("Use the centre dot") {
            val (lat, lng) = callbacks.mapCentre() ?: return@button
            callbacks.startGps()
            setAnchor(Anchor(lat, lng, radius, System.currentTimeMillis()))
            panel?.dismiss()
        }, buttonParams())
        if (w != null) {
            buttons.addView(button("Stop anchor alarm") {
                panels.confirm("Stop the anchor alarm?", null, confirmLabel = "Stop") {
                    setAnchor(null)
                    panel?.dismiss()
                }
            }, buttonParams())
        }
        body.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(10)
        })
        val shown = panels.showPanel("Anchor alarm", body, onDismiss = { if (panel?.isShowing != true) panel = null })
        panel = shown
    }

    // Watching ------------------------------------------------------------------

    private fun setAnchor(anchor: Anchor?, keepStats: Boolean = false) {
        val wasWatching = isWatching
        if (anchor != null) {
            callbacks.startGps()
            callbacks.requestNotifications()
        }
        monitor.set(activity, anchor, keepStats)
        if (anchor != null && !wasWatching) callbacks.toast("Anchor alarm on — ${format().offset(anchor.radiusM)} circle")
        if (anchor == null) callbacks.toast("Anchor alarm off")
        refreshMap()
        refresh()
    }

    /** The chip, and the in-app alert while the alarm sounds. */
    private fun refresh() {
        val w = monitor.watch.takeIf { isWatching }
        val alarm = monitor.alarm
        val ringing = alarm != null && !monitor.silenced
        if (ringing) showAlert(alarm!!) else dismissAlert()
        // While it rings, the chart shows over the lock screen so Silence is one tap away.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(ringing)
            activity.setTurnScreenOn(ringing)
        }
        if (w == null || callbacks.isNavigating()) {
            chip.visibility = View.GONE
            return
        }
        val s = monitor.status
        val f = format()
        chipText.text = when {
            alarm == AnchorMonitor.Alarm.NO_GPS -> "Anchor · no GPS"
            s == null -> "Anchor · waiting for GPS"
            s.gpsPoor -> "Anchor · GPS rough"
            else -> "Anchor ${f.offset(s.distanceM)} / ${f.offset(w.anchor.radiusM)}"
        }
        val red = alarm != null
        chip.background = activity.roundedBackground(if (red) DRAG_RED else 0xE6111827.toInt(), if (red) DRAG_RED else palette.border, 16)
        chip.visibility = View.VISIBLE
    }

    private fun showAlert(alarm: AnchorMonitor.Alarm) {
        if (alert?.isShowing == true) return
        val f = format()
        val (title, message) = when (alarm) {
            AnchorMonitor.Alarm.DRAGGING -> "Anchor dragging" to monitor.draggingText(activity, f)
            AnchorMonitor.Alarm.NO_GPS -> "No GPS" to
                "SeaBathy lost GPS for over a minute, so it couldn't watch the anchor. Check GPS is on."
        }
        alert = panels.confirm(title, message, confirmLabel = "Silence") { monitor.silence() }
    }

    private fun dismissAlert() {
        alert?.takeIf { it.isShowing }?.dismiss()
        alert = null
    }

    /** Navigation started or stopped: the chip gives way to the navigation display. */
    fun navigationChanged() = refresh()

    // Pieces --------------------------------------------------------------------

    private fun format() = NavFormat(settings().units)

    /** The swing circle as a GeoJSON polygon. */
    private fun circle(a: Anchor): String {
        val metresPerDegLat = 111_320.0
        val metresPerDegLng = metresPerDegLat * cos(Math.toRadians(a.lat))
        val ring = (0..CIRCLE_POINTS).joinToString(",") { i ->
            val angle = 2 * Math.PI * i / CIRCLE_POINTS
            val lat = a.lat + a.radiusM * cos(angle) / metresPerDegLat
            val lng = a.lng + a.radiusM * sin(angle) / metresPerDegLng
            "[$lng,$lat]"
        }
        return """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$ring]]},"properties":{}}"""
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
        const val CIRCLE_POINTS = 72
        const val DRAG_RED = 0xFFDC2626.toInt()
    }
}
