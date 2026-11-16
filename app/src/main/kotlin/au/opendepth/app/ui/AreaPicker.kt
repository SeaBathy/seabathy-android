package au.opendepth.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Lets the user draw a box on the chart: drag with one finger to draw it, two fingers still
 * move and zoom the map. The box stays on the chart as the map moves. A bar at the bottom shows
 * the box's size (and anything [describe] adds, such as the download size), with Cancel and the
 * confirm button. Boxes larger than [maxKm] across are shown in red and can't be confirmed.
 */
class AreaPicker(
    private val context: Context,
    private val root: ViewGroup,
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val maxKm: Double,
    private val confirmLabel: String,
    /** A line about the box (download size, say); call the callback on the main thread. null clears it. */
    private val describe: (Box, (String?) -> Unit) -> Unit,
    private val onConfirm: (Box) -> Unit,
    private val onCancel: () -> Unit
) {
    data class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
        val widthKm: Double get() = (east - west) * 111.32 * cos(Math.toRadians((south + north) / 2))
        val heightKm: Double get() = (north - south) * 110.57
    }

    private val palette = AppTheme.palette
    private var box: Box? = null
    private var overlay: Overlay? = null
    private var bar: LinearLayout? = null
    private lateinit var sizeText: TextView
    private lateinit var infoText: TextView
    private lateinit var confirm: Button
    private val cameraListener = MapLibreMap.OnCameraMoveListener { overlay?.invalidate() }
    private var describeToken = 0

    /** Shows the picker, starting with [initial] (or nothing, for the user to draw). */
    fun show(initial: Box?) {
        val o = Overlay(context)
        root.addView(o, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        o.elevation = context.dpF(30f)
        overlay = o
        bar = buildBar().also {
            root.addView(it, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(context.dp(12), 0, context.dp(12), context.dp(24))
            })
            it.elevation = context.dpF(32f)
        }
        map.addOnCameraMoveListener(cameraListener)
        setBox(initial)
    }

    fun dismiss() {
        map.removeOnCameraMoveListener(cameraListener)
        overlay?.let { root.removeView(it) }
        bar?.let { root.removeView(it) }
        overlay = null
        bar = null
    }

    private fun setBox(b: Box?) {
        box = b
        overlay?.invalidate()
        val token = ++describeToken
        if (b == null) {
            sizeText.text = "Drag on the chart to draw a box"
            infoText.text = "Two fingers move and zoom the map."
            enable(false)
            return
        }
        val tooBig = max(b.widthKm, b.heightKm) > maxKm
        sizeText.text = String.format(Locale.US, "%s × %s", km(b.widthKm), km(b.heightKm))
        if (tooBig) {
            infoText.text = String.format(Locale.US, "Too big: up to about %.0f km across. Draw a smaller box.", maxKm)
            enable(false)
            return
        }
        infoText.text = "Working out the download…"
        enable(true)
        describe(b) { line -> if (token == describeToken) infoText.text = line ?: "" }
    }

    private fun km(v: Double) = if (v < 10) String.format(Locale.US, "%.1f km", v) else String.format(Locale.US, "%.0f km", v)

    private fun enable(on: Boolean) {
        confirm.isEnabled = on
        confirm.alpha = if (on) 1f else 0.45f
    }

    private fun buildBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            isClickable = true // touches on the bar don't draw a box under it
        }
        bar.addView(TextView(context).apply {
            text = "HIGH-DETAIL DEPTHS"
            textSize = 11f
            setTextColor(palette.accent)
            letterSpacing = 0.08f
        })
        sizeText = TextView(context).apply { textSize = 17f; setTextColor(palette.primaryText); setPadding(0, context.dp(2), 0, 0) }
        infoText = TextView(context).apply { textSize = 13f; setTextColor(palette.secondaryText); setPadding(0, context.dp(2), 0, context.dp(8)) }
        bar.addView(sizeText)
        bar.addView(infoText)
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) palette.onAccent else palette.primaryText)
            background = context.roundedBackground(if (primary) palette.accent else palette.raised, null, UiRadius.CONTROL)
            setPadding(context.dp(18), 0, context.dp(18), 0)
            setOnClickListener { onClick() }
        }
        buttons.addView(button("Cancel", false) { dismiss(); onCancel() })
        confirm = button(confirmLabel, true) { box?.let { b -> dismiss(); onConfirm(b) } }
        buttons.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10)
        })
        bar.addView(buttons)
        return bar
    }

    /** Draws the box and turns one-finger drags into a new box; anything else goes to the map. */
    @SuppressLint("ViewConstructor")
    private inner class Overlay(context: Context) : View(context) {
        private val fill = Paint().apply { color = Color.argb(46, 25, 198, 179) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(2.5f)
            color = palette.accent
        }
        private val dashed = Paint(edge).apply { pathEffect = DashPathEffect(floatArrayOf(context.dpF(8f), context.dpF(6f)), 0f) }
        private val shade = Paint().apply { color = Color.argb(70, 0, 0, 0) }
        private var start: PointF? = null
        private var drag: RectF? = null
        private var forwarding = false
        private var firstDown: MotionEvent? = null

        override fun onDraw(canvas: Canvas) {
            val r = drag ?: box?.let { screenRect(it) }
            if (r == null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
                return
            }
            // Darken outside the box so it stands out.
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, shade)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, r.top, r.left, r.bottom, shade)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, shade)
            canvas.drawRect(r, fill)
            val tooBig = box?.let { max(it.widthKm, it.heightKm) > maxKm } == true && drag == null
            edge.color = if (tooBig) palette.destructive else palette.accent
            canvas.drawRect(r, if (drag != null) dashed else edge)
        }

        private fun screenRect(b: Box): RectF {
            val p = map.projection
            val a = p.toScreenLocation(LatLng(b.north, b.west))
            val c = p.toScreenLocation(LatLng(b.south, b.east))
            return RectF(min(a.x, c.x), min(a.y, c.y), max(a.x, c.x), max(a.y, c.y))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (forwarding) {
                mapView.dispatchTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = PointF(e.x, e.y)
                    firstDown?.recycle()
                    firstDown = MotionEvent.obtain(e)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Two fingers: this gesture is for the map. Hand it over from the first touch.
                    drag = null
                    start = null
                    forwarding = true
                    firstDown?.let { mapView.dispatchTouchEvent(it) }
                    mapView.dispatchTouchEvent(e)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> start?.let { s ->
                    if (drag != null || abs(e.x - s.x) + abs(e.y - s.y) > context.dpF(12f)) {
                        drag = RectF(min(s.x, e.x), min(s.y, e.y), max(s.x, e.x), max(s.y, e.y))
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val r = drag
                    drag = null
                    start = null
                    if (r != null && r.width() > context.dpF(24f) && r.height() > context.dpF(24f)) {
                        val p = map.projection
                        val nw = p.fromScreenLocation(PointF(r.left, r.top))
                        val se = p.fromScreenLocation(PointF(r.right, r.bottom))
                        setBox(Box(min(nw.latitude, se.latitude), min(nw.longitude, se.longitude),
                            max(nw.latitude, se.latitude), max(nw.longitude, se.longitude)))
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { drag = null; start = null; invalidate() }
            }
            return true
        }
    }
}
