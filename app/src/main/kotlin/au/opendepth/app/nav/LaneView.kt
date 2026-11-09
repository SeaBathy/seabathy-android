package au.opendepth.app.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import au.opendepth.app.ui.dpF
import kotlin.math.abs

/**
 * The chartplotter "highway": the track line running away from the boat
 * into the distance, its edges at the off-course limit, and the boat drawn
 * offset by its cross-track error. Red outside the lane.
 */
class LaneView(context: Context) : View(context) {

    /** Cross-track error, metres; positive = boat to starboard of the line. */
    var xteM: Double = 0.0
        set(value) { field = value; invalidate() }

    /** Half the lane width: the off-course limit, metres. */
    var limitM: Double = 100.0
        set(value) { field = value.coerceAtLeast(1.0); invalidate() }

    /** Turn needed to the waypoint, degrees; positive = to starboard. Rotates the boat. */
    var steerDegrees: Double? = null
        set(value) { field = value; invalidate() }

    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(2f)
        color = 0xFFE2E8F0.toInt()
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(3f)
        color = 0xFFE040FB.toInt()
        pathEffect = DashPathEffect(floatArrayOf(context.dpF(10f), context.dpF(8f)), 0f)
    }
    private val boatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boatEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(1.5f)
        color = 0xFF0B1622.toInt()
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val outside = abs(xteM) > limitM
        val cx = w / 2
        // Lane: wide at the bottom (near the boat), narrowing to the horizon.
        val bottomHalf = w * 0.30f
        val topHalf = w * 0.06f
        val top = h * 0.08f
        // The lane slides opposite to the boat's offset, so the boat stays centred.
        val shift = (-xteM / limitM).toFloat().coerceIn(-2.2f, 2.2f) * bottomHalf
        lanePaint.color = if (outside) 0x66EF4444 else 0x4419C6B3
        path.reset()
        path.moveTo(cx + shift - bottomHalf, h)
        path.lineTo(cx + shift * 0.2f - topHalf, top)
        path.lineTo(cx + shift * 0.2f + topHalf, top)
        path.lineTo(cx + shift + bottomHalf, h)
        path.close()
        canvas.drawPath(path, lanePaint)
        canvas.drawLine(cx + shift - bottomHalf, h, cx + shift * 0.2f - topHalf, top, edgePaint)
        canvas.drawLine(cx + shift + bottomHalf, h, cx + shift * 0.2f + topHalf, top, edgePaint)
        canvas.drawLine(cx + shift, h, cx + shift * 0.2f, top, centrePaint)

        // Boat, pointing up the lane and turned by the steering needed.
        boatPaint.color = if (outside) 0xFFEF4444.toInt() else 0xFFFFB020.toInt()
        val size = h * 0.22f
        val by = h - size * 0.9f
        canvas.save()
        canvas.rotate(-(steerDegrees ?: 0.0).toFloat().coerceIn(-60f, 60f) * 0.5f, cx, by)
        path.reset()
        path.moveTo(cx, by - size)
        path.lineTo(cx + size * 0.45f, by + size * 0.5f)
        path.lineTo(cx, by + size * 0.25f)
        path.lineTo(cx - size * 0.45f, by + size * 0.5f)
        path.close()
        canvas.drawPath(path, boatPaint)
        canvas.drawPath(path, boatEdge)
        canvas.restore()
    }
}
