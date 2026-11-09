package au.opendepth.app.tides

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.ui.dpF
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The tide over the next day and a half: a smooth curve with high and low
 * water marked, a "now" marker, day and hour labels. Heights in metres.
 */
class TideGraphView(context: Context) : View(context) {

    var curve: TideCurve? = null
        set(value) { field = value; invalidate() }

    /** Added to every height (chart datum offset), so the graph matches the readouts. */
    /** Heights in the units chosen in Settings. */
    var heightFormat: NavFormat = NavFormat(NavUnits())
    var offsetM: Double = 0.0
        set(value) { field = value; invalidate() }

    var nowMillis: Long = System.currentTimeMillis()

    /** Left edge of the chart; [AFTER_MS] + [BEFORE_MS] are shown from here. Defaults to just before now. */
    var windowStartMillis: Long = nowMillis - BEFORE_MS
        set(value) { field = value; cursorMillis = null; invalidate() }

    /** Sunset-to-sunrise spans, shaded darker. */
    var nights: List<Pair<Long, Long>> = emptyList()
        set(value) { field = value; invalidate() }

    /** Where the finger is (or was last), shown with its time and height. */
    private var cursorMillis: Long? = null

    /** Told about the time under the finger while dragging. */
    var onScrub: ((timeMillis: Long, heightM: Double) -> Unit)? = null

    private var plotLeft = 0f
    private var plotRight = 0f

    private val d = { v: Float -> context.dpF(v) }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = WATER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = d(1f)
        color = 0x1FFFFFFF
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = d(1f)
        color = 0x40FFFFFF
        pathEffect = DashPathEffect(floatArrayOf(d(4f), d(4f)), 0f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(11f)
        color = MUTED
    }
    private val dayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(12f)
        color = 0xFFE2E8F0.toInt()
        isFakeBoldText = true
    }
    private val markTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(12f)
        color = 0xFFF8FAFC.toInt()
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val markHeight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(11f)
        color = MUTED
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(2f)
        color = CARD
    }
    private val nowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = d(2f)
        color = NOW
    }
    private val nowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(11f)
        color = NOW
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val hourFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dayFormat = SimpleDateFormat("EEE d", Locale.US)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, d(250f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, width, height), d(14f), d(14f), cardPaint)
        val curve = curve ?: return
        val from = windowStartMillis
        val to = windowStartMillis + BEFORE_MS + AFTER_MS
        val samples = (0..SAMPLES).mapNotNull { i ->
            val t = from + (to - from) * i / SAMPLES
            curve.heightAt(t)?.let { t to it + offsetM }
        }
        if (samples.size < 2) return

        // Plot area, leaving room for labels around it.
        val left = d(36f)
        val right = width - d(12f)
        plotLeft = left
        plotRight = right
        val top = d(46f)
        val bottom = height - d(62f)
        val minH = floor(samples.minOf { it.second } * 2) / 2
        val maxH = ceil(samples.maxOf { it.second } * 2) / 2
        val range = (maxH - minH).coerceAtLeast(0.5)
        fun x(t: Long) = left + (t - from).toFloat() / (to - from) * (right - left)
        fun y(v: Double) = top + ((maxH - v) / range * (bottom - top)).toFloat()

        // Night, behind everything else.
        for ((dusk, dawn) in nights) {
            if (dawn <= from || dusk >= to) continue
            canvas.drawRect(x(maxOf(dusk, from)), top - d(6f), x(minOf(dawn, to)), bottom, nightPaint)
        }

        // Height lines every half metre (whole metres for big tides), labelled at the left.
        val step = heightFormat.tideChartStep(range)
        val perMetre = heightFormat.tideUnitsPerMetre()
        var level = ceil(minH * perMetre / step - 1e-6) * step
        while (level / perMetre <= maxH + 1e-6) {
            val lineY = y(level / perMetre)
            canvas.drawLine(left, lineY, right, lineY, gridPaint)
            canvas.drawText(String.format(Locale.US, if (step < 1) "%.1f" else "%.0f", level + 0.0), d(8f), lineY + d(4f), axisText)
            level += step
        }

        // Midnights (dashed, with the day) and 6-hourly ticks along the bottom.
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        while (cal.timeInMillis < to) {
            val t = cal.timeInMillis
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour == 0) {
                // Midnight: a dashed line, and the new day's name in the time row.
                canvas.drawLine(x(t), top - d(6f), x(t), bottom, dayPaint)
                if (kotlin.math.abs(x(t) - x(nowMillis)) > d(30f)) {
                    val label = dayFormat.format(Date(t))
                    canvas.drawText(label, (x(t) - dayText.measureText(label) / 2).coerceIn(left, right - dayText.measureText(label)), bottom + d(52f), dayText)
                }
            } else if (hour % 6 == 0 && kotlin.math.abs(x(t) - x(nowMillis)) > d(26f)) {
                // On their own row below the low-water labels; NOW takes the slot it falls in.
                val label = String.format(Locale.US, "%02d:00", hour)
                canvas.drawText(label, x(t) - axisText.measureText(label) / 2, bottom + d(52f), axisText)
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }

        // The curve, with a soft fill below it.
        val path = Path()
        samples.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        val fill = Path(path).apply {
            lineTo(x(samples.last().first), bottom)
            lineTo(x(samples.first().first), bottom)
            close()
        }
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, 0x6638BDF8, 0x0838BDF8, Shader.TileMode.CLAMP)
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        // High and low water: a dot and its time and height.
        curve.extremes(from, to).forEach { e ->
            val v = e.heightM + offsetM
            val cx = x(e.timeMillis)
            val cy = y(v)
            dotPaint.color = if (e.isHigh) HIGH else LOW
            canvas.drawCircle(cx, cy, d(5f), dotPaint)
            canvas.drawCircle(cx, cy, d(5f), dotRing)
            // Already past: just the dot, so its label doesn't crowd the NOW marker.
            if (e.timeMillis < nowMillis) return@forEach
            val lx = cx.coerceIn(left + d(22f), right - d(22f))
            val time = hourFormat.format(Date(e.timeMillis))
            val metres = heightFormat.tideMark(v)
            if (e.isHigh) {
                canvas.drawText(time, lx, cy - d(22f), markTime)
                canvas.drawText(metres, lx, cy - d(9f), markHeight)
            } else {
                canvas.drawText(time, lx, cy + d(21f), markTime)
                canvas.drawText(metres, lx, cy + d(34f), markHeight)
            }
        }

        // Now, when it's on this chart.
        val nx = x(nowMillis)
        if (nowMillis in from..to) curve.heightAt(nowMillis)?.let { h ->
            val ny = y(h + offsetM)
            canvas.drawLine(nx, top - d(4f), nx, bottom, nowLine)
            dotPaint.color = NOW
            canvas.drawCircle(nx, ny, d(6f), dotPaint)
            canvas.drawCircle(nx, ny, d(6f), dotRing)
            canvas.drawText("NOW", nx.coerceIn(left + d(14f), right - d(14f)), bottom + d(52f), nowText)
        }

        cursorMillis?.let { t ->
            val h = curve.heightAt(t) ?: return@let
            val rate = curve.rateMPerHour(t) ?: 0.0
            val arrow = if (rate > 0.02) "▲" else if (rate < -0.02) "▼" else "■"
            drawCursor(canvas, x(t), y(h + offsetM), top, bottom, "${cursorFormat.format(Date(t))}   ${heightFormat.tideHeight(h + offsetM)} $arrow")
        }
    }

    private fun drawCursor(canvas: Canvas, x: Float, y: Float, top: Float, bottom: Float, label: String) {
        canvas.drawLine(x, top - d(4f), x, bottom, cursorLine)
        dotPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, d(6f), dotPaint)
        canvas.drawCircle(x, y, d(6f), dotRing)
        val w = bubbleText.measureText(label) + d(16f)
        val bx = (x - w / 2).coerceIn(d(4f), width - w - d(4f))
        val rect = RectF(bx, d(4f), bx + w, d(28f))
        canvas.drawRoundRect(rect, d(8f), d(8f), bubblePaint)
        canvas.drawText(label, rect.centerX(), rect.bottom - d(7f), bubbleText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val curve = curve ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Keep the scrolling panel from taking the drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                if (plotRight <= plotLeft) return true
                val span = BEFORE_MS + AFTER_MS
                val fraction = ((event.x - plotLeft) / (plotRight - plotLeft)).coerceIn(0f, 1f)
                val t = windowStartMillis + (span * fraction).toLong()
                cursorMillis = t
                curve.heightAt(t)?.let { onScrub?.invoke(t, it + offsetM) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private val nightPaint = Paint().apply { color = 0x73000000 }
    private val cursorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = d(1.5f)
        color = 0xCCFFFFFF.toInt()
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(13f)
        color = 0xFF0F1A27.toInt()
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val cursorFormat = SimpleDateFormat("EEE HH:mm", Locale.US)

    private companion object {
        const val BEFORE_MS = 3 * 3_600_000L
        const val AFTER_MS = 33 * 3_600_000L
        const val SAMPLES = 240
        const val CARD = 0xFF0F1A27.toInt()
        const val WATER = 0xFF38BDF8.toInt()
        const val HIGH = 0xFF38BDF8.toInt()
        const val LOW = 0xFF94A3B8.toInt()
        const val NOW = 0xFFFFB020.toInt()
        const val MUTED = 0xFF94A3B8.toInt()
    }
}
