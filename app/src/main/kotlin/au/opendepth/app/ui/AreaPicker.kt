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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lets the user pick an area on the chart. It starts empty: drag with one finger to draw a box,
 * then drag the box to move it or a corner to resize it (dragging outside it draws a new one).
 * Two fingers still move and zoom the map, and the box stays on the chart as the map moves. A bar
 * at the bottom shows the box's size (and anything [describe] adds, such as the download size),
 * with Cancel and the confirm button. Boxes larger than [maxKm] across are shown in red and
 * can't be confirmed.
 *
 * A picker whose limit isn't the box's size alone (the offline map's, where it is the number
 * of tiles) passes no [maxKm] and an [extra] control: that decides which boxes are refused.
 */
class AreaPicker(
    private val context: Context,
    private val root: ViewGroup,
    private val mapView: MapView,
    private val map: MapLibreMap,
    /** The most the box may span either way, or null for no such limit. */
    private val maxKm: Double?,
    private val confirmLabel: String,
    /** A line about the box (download size, say); call the callback on the main thread. null clears it. */
    private val describe: (Box, (String?) -> Unit) -> Unit,
    private val onConfirm: (Box) -> Unit,
    private val onCancel: () -> Unit,
    /** The small heading in the bar. */
    private val title: String = "HIGH-DETAIL DEPTHS",
    /** A control of the caller's in the bar, which then says what a box means in place of [describe]. */
    private val extra: Extra? = null,
    /**
     * In place of [describe]: a line about the box that can also refuse it (no data there, say).
     * Call the callback on the main thread.
     */
    private val judge: ((Box, (Verdict) -> Unit) -> Unit)? = null
) {
    /** What an [Extra] says about a box: the line shown under its control, and whether the box can be confirmed. */
    class Verdict(val text: String, val ok: Boolean)

    /** A control added to the bar, under the box's size: the offline map's DETAIL. */
    interface Extra {
        /** The control. Call [changed] when it changes what [judge] answers, so the box is asked about again. */
        fun view(changed: () -> Unit): View

        /** Asked for every box shown, also while one is being dragged. */
        fun judge(box: Box): Verdict
    }

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
    private lateinit var hintText: TextView
    private lateinit var confirm: Button
    private val cameraListener = MapLibreMap.OnCameraMoveListener { overlay?.invalidate() }
    private var describeToken = 0
    /** The [extra] refused the box last shown. */
    private var refused = false

    /** Shows the picker, starting with [initial], or empty (the usual) for the user to draw. */
    fun show(initial: Box? = null) {
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
            sizeText.text = "Drag a finger across the chart to draw the area"
            infoText.setTextColor(palette.secondaryText)
            infoText.text = if (maxKm != null) {
                String.format(Locale.US, "Up to about %.0f km across. Two fingers move and zoom the map.", maxKm)
            } else {
                "Two fingers move and zoom the map."
            }
            hintText.visibility = View.GONE
            enable(false)
            return
        }
        // With a control of its own in the bar, a short screen has no room for the hint as well.
        hintText.visibility = if (extra != null && isShortScreen()) View.GONE else View.VISIBLE
        showSize(b)
        if (maxKm != null && max(b.widthKm, b.heightKm) > maxKm) {
            infoText.text = String.format(Locale.US, "Too big: up to about %.0f km across. Make the box smaller.", maxKm)
            enable(false)
            return
        }
        if (extra != null) {
            // [showSize] has asked it, and shown its answer.
            enable(!refused)
            return
        }
        infoText.text = "Working out the download…"
        enable(true)
        val judging = judge
        if (judging != null) {
            refused = false
            infoText.setTextColor(palette.secondaryText)
            judging(b) { verdict ->
                if (token == describeToken) {
                    refused = !verdict.ok
                    infoText.text = verdict.text
                    infoText.setTextColor(if (verdict.ok) palette.secondaryText else palette.destructive)
                    enable(verdict.ok)
                    overlay?.invalidate()
                }
            }
            return
        }
        describe(b) { line -> if (token == describeToken) infoText.text = line ?: "" }
    }

    private fun showSize(b: Box) {
        sizeText.text = String.format(Locale.US, "%s × %s", km(b.widthKm), km(b.heightKm))
        val verdict = extra?.judge(b) ?: return
        refused = !verdict.ok
        infoText.text = verdict.text
        infoText.setTextColor(if (verdict.ok) palette.secondaryText else palette.destructive)
    }

    /** Whether [b] can't be confirmed: wider than [maxKm], or refused by the [extra] when it was last shown. */
    private fun tooBig(b: Box): Boolean = (maxKm != null && max(b.widthKm, b.heightKm) > maxKm) || refused

    private fun isShortScreen(): Boolean =
        context.resources.displayMetrics.heightPixels < context.dp(SHORT_SCREEN_DP)

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
            text = title
            textSize = 11f
            setTextColor(palette.accent)
            letterSpacing = 0.08f
        })
        sizeText = TextView(context).apply { textSize = 17f; setTextColor(palette.primaryText); setPadding(0, context.dp(2), 0, 0) }
        infoText = TextView(context).apply { textSize = 13f; setTextColor(palette.secondaryText); setPadding(0, context.dp(2), 0, 0) }
        hintText = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.secondaryText)
            alpha = 0.8f
            text = "Drag the box to move it, or a corner to resize it."
        }
        bar.addView(sizeText)
        extra?.let { bar.addView(it.view { box?.let(::setBox) }) }
        bar.addView(infoText)
        bar.addView(hintText)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, context.dp(8), 0, 0)
        }
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

    /**
     * Draws the box with a handle on each corner. One finger draws, moves or resizes it;
     * anything else goes to the map.
     */
    @SuppressLint("ViewConstructor")
    private inner class Overlay(context: Context) : View(context) {
        private val fill = Paint().apply { color = Color.argb(46, 25, 198, 179) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(2.5f)
            color = palette.accent
        }
        private val dashed = Paint(edge).apply { pathEffect = DashPathEffect(floatArrayOf(context.dpF(8f), context.dpF(6f)), 0f) }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(2.5f)
        }
        private val shade = Paint().apply { color = Color.argb(70, 0, 0, 0) }
        private val handleRadius = context.dpF(9f)
        private val grabRadius = context.dpF(36f)
        private val minSide = context.dpF(24f)

        private var mode = Mode.DRAW
        private var start: PointF? = null
        /** The corner that stays put while resizing. */
        private var anchor: PointF? = null
        /** The box as it was when a move started. */
        private var moveFrom: RectF? = null
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
            val shown = drag?.let { toBox(it) } ?: box
            val tooBig = shown?.let { tooBig(it) } == true
            val colour = if (tooBig) palette.destructive else palette.accent
            edge.color = colour
            dashed.color = colour
            canvas.drawRect(r, if (drag != null && mode == Mode.DRAW) dashed else edge)
            handleEdge.color = colour
            for (c in corners(r)) {
                canvas.drawCircle(c.x, c.y, handleRadius, handleFill)
                canvas.drawCircle(c.x, c.y, handleRadius, handleEdge)
            }
        }

        private fun corners(r: RectF) = listOf(
            PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom)
        )

        private fun screenRect(b: Box): RectF {
            val p = map.projection
            val a = p.toScreenLocation(LatLng(b.north, b.west))
            val c = p.toScreenLocation(LatLng(b.south, b.east))
            return RectF(min(a.x, c.x), min(a.y, c.y), max(a.x, c.x), max(a.y, c.y))
        }

        private fun toBox(r: RectF): Box {
            val p = map.projection
            val nw = p.fromScreenLocation(PointF(r.left, r.top))
            val se = p.fromScreenLocation(PointF(r.right, r.bottom))
            return Box(min(nw.latitude, se.latitude), min(nw.longitude, se.longitude),
                max(nw.latitude, se.latitude), max(nw.longitude, se.longitude))
        }

        private fun rectOf(a: PointF, x: Float, y: Float) = RectF(min(a.x, x), min(a.y, y), max(a.x, x), max(a.y, y))

        /** What a touch at (x, y) does: grab a corner, move the box, or draw a new one. */
        private fun startGesture(x: Float, y: Float) {
            val r = box?.let { screenRect(it) }
            anchor = null
            moveFrom = null
            mode = Mode.DRAW
            if (r == null) return
            val near = corners(r).minByOrNull { hypot(it.x - x, it.y - y) }
            if (near != null && hypot(near.x - x, near.y - y) <= grabRadius) {
                mode = Mode.RESIZE
                anchor = PointF(if (near.x == r.left) r.right else r.left, if (near.y == r.top) r.bottom else r.top)
            } else if (r.contains(x, y)) {
                mode = Mode.MOVE
                moveFrom = RectF(r)
            }
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
                    startGesture(e.x, e.y)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Two fingers: this gesture is for the map. Hand it over from the first touch.
                    drag = null
                    start = null
                    forwarding = true
                    firstDown?.let { mapView.dispatchTouchEvent(it) }
                    mapView.dispatchTouchEvent(e)
                    box?.let { showSize(it) }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> start?.let { s ->
                    val threshold = if (mode == Mode.RESIZE) context.dpF(4f) else context.dpF(10f)
                    if (drag != null || abs(e.x - s.x) + abs(e.y - s.y) > threshold) {
                        drag = when (mode) {
                            Mode.DRAW -> rectOf(s, e.x, e.y)
                            Mode.RESIZE -> rectOf(anchor ?: s, e.x, e.y)
                            Mode.MOVE -> RectF(moveFrom ?: return@let).apply { offset(e.x - s.x, e.y - s.y) }
                        }
                        drag?.let { showSize(toBox(it)) }
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val r = drag
                    drag = null
                    start = null
                    if (r != null && r.width() > minSide && r.height() > minSide) {
                        setBox(toBox(r))
                    } else {
                        box?.let { showSize(it) }
                        // No box yet: the extra's line goes back to the opening hint.
                        if (box == null && extra != null) setBox(null)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    drag = null
                    start = null
                    box?.let { showSize(it) }
                    invalidate()
                }
            }
            return true
        }
    }

    private enum class Mode { DRAW, MOVE, RESIZE }

    private companion object {
        /** A screen shorter than this (a phone on its side) is short of room for the bar. */
        const val SHORT_SCREEN_DP = 480
    }
}
