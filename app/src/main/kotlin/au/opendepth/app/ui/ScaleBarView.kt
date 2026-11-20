package au.opendepth.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The chart's scale bar: a line with a tick at each end and the distance it
 * stands for written above it. A light line and text over a dark casing, as
 * the route line is drawn, so it reads over a dark basemap and over bright
 * depth colours alike.
 *
 * Only draws what it is given ([show]); [ChartScaleBar] works the distance out.
 */
class ScaleBarView(context: Context) : View(context) {

    private val edge = context.dpF(EDGE_DP)
    private val tick = context.dpF(TICK_DP)
    private val gap = context.dpF(GAP_DP)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.palette.primaryText
        // In sp, like the rest of the chart's text, so it follows the phone's font size.
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, TEXT_SP, context.resources.displayMetrics
        )
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textCasing = Paint(text).apply {
        color = CASING
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(TEXT_CASING_DP)
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.palette.primaryText
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(LINE_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lineCasing = Paint(line).apply {
        color = CASING
        strokeWidth = context.dpF(LINE_CASING_DP)
    }
    private val shape = Path()

    private val textHeight = text.fontMetrics.let { it.descent - it.ascent }
    private val baseline = edge - text.fontMetrics.ascent

    private var label = ""
    private var barPx = 0f

    /** The longest the bar is ever drawn, in pixels: the stretch of chart the distance is worked out for. */
    val maxBarPx: Float = context.dpF(MAX_BAR_DP)

    /** Where the bar starts, and the row it lies on, within this view. */
    val barStartX: Float get() = edge
    val barRowY: Float get() = edge + textHeight + gap + tick

    init {
        // Read out as its distance; nothing to tap.
        isClickable = false
        isFocusable = false
    }

    /** Draws a bar [barPx] long that stands for [label]. Does nothing when that is what is drawn already. */
    fun show(label: String, barPx: Float) {
        if (label == this.label && abs(barPx - this.barPx) < HALF_PIXEL) return
        if (label != this.label) contentDescription = "Scale: $label"
        this.label = label
        this.barPx = barPx
        invalidate()
    }

    /** Nothing to measure (the chart is not ready): draws nothing. */
    fun clear() {
        if (label.isEmpty()) return
        label = ""
        barPx = 0f
        contentDescription = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            ceil(maxBarPx + 2 * edge).toInt(),
            ceil(edge + textHeight + gap + tick + edge).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (label.isEmpty() || barPx <= 0f) return
        canvas.drawText(label, edge, baseline, textCasing)
        canvas.drawText(label, edge, baseline, text)

        val left = barStartX
        val right = left + barPx
        val row = barRowY
        shape.rewind()
        shape.moveTo(left, row - tick)
        shape.lineTo(left, row)
        shape.lineTo(right, row)
        shape.lineTo(right, row - tick)
        canvas.drawPath(shape, lineCasing)
        canvas.drawPath(shape, line)
    }

    private companion object {
        /** The dark outline the chart's route and measure lines are drawn over. */
        val CASING = Color.parseColor("#D90B1622")

        const val MAX_BAR_DP = 100f
        const val TEXT_SP = 11f
        const val TEXT_CASING_DP = 3f
        const val LINE_DP = 2f
        const val LINE_CASING_DP = 4.5f
        const val TICK_DP = 5f
        const val GAP_DP = 2f

        /** Room round the drawing for the casing. */
        const val EDGE_DP = 3f
        const val HALF_PIXEL = 0.5f
    }
}
