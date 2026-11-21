package au.opendepth.app.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import au.opendepth.app.ui.dpF
import java.util.Calendar

/**
 * A route's tide window as a bar from now to the end of tomorrow: enough water in
 * the tide graph's blue, not enough dim, unknown tides fainter still; midnight and
 * every six hours marked, and when the boat would get to the shallowest point.
 */
class TideWindowBar(context: Context) : View(context) {

    var fromMillis: Long = 0
    var toMillis: Long = 0
    var knownUntilMillis: Long = 0
    var periods: List<TideWindow.Period> = emptyList()
    /** Leaving now, when the boat reaches the shallowest point; null without a speed. */
    var markMillis: Long? = null

    private val d = { v: Float -> context.dpF(v) }
    private val shallowPaint = Paint().apply { color = 0x40FFFFFF }
    private val unknownPaint = Paint().apply { color = 0x14FFFFFF }
    private val clearPaint = Paint().apply { color = CLEAR }
    private val midnightPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = d(1.5f)
    }
    private val hourPaint = Paint().apply {
        color = 0x66000000
        strokeWidth = d(1f)
    }
    private val markPaint = Paint().apply {
        color = MARK
        strokeWidth = d(2.5f)
    }
    private val hourText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(10f)
        color = MUTED
        textAlign = Paint.Align.CENTER
    }
    private val dayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = d(11f)
        color = 0xFFE2E8F0.toInt()
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), d(36f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val span = (toMillis - fromMillis).toFloat()
        if (span <= 0f) return
        val w = width.toFloat()
        val barBottom = d(18f)
        fun x(t: Long) = ((t - fromMillis) / span * w).coerceIn(0f, w)
        canvas.drawRect(0f, 0f, w, barBottom, unknownPaint)
        canvas.drawRect(0f, 0f, x(knownUntilMillis), barBottom, shallowPaint)
        periods.forEach { canvas.drawRect(x(it.startMillis), 0f, x(it.endMillis), barBottom, clearPaint) }
        // Every six hours, a tick and the hour; at midnight, the day.
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        val textY = barBottom + d(13f)
        while (cal.timeInMillis < toMillis) {
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour % 6 == 0) {
                val px = x(cal.timeInMillis)
                if (hour == 0) {
                    canvas.drawLine(px, 0f, px, barBottom + d(3f), midnightPaint)
                    val day = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
                    canvas.drawText(day, px.coerceIn(d(12f), w - d(12f)), textY, dayText)
                } else {
                    canvas.drawLine(px, 0f, px, barBottom, hourPaint)
                    if (px > d(10f) && px < w - d(10f)) canvas.drawText(String.format(java.util.Locale.US, "%02d", hour), px, textY, hourText)
                }
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
        markMillis?.takeIf { it in fromMillis..toMillis }?.let {
            val px = x(it)
            canvas.drawLine(px, -d(1f), px, barBottom + d(1f), markPaint)
        }
    }

    private companion object {
        const val CLEAR = 0xFF38BDF8.toInt()
        const val MARK = 0xFFFFB020.toInt()
        const val MUTED = 0xFF94A3B8.toInt()
        val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }
}
