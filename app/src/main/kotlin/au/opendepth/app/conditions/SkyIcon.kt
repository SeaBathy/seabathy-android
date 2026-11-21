package au.opendepth.app.conditions

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The small picture at the start of the values box's sun and moon lines. */
sealed class SkyIcon {
    /** What it looks like: the same key, the same picture. */
    abstract val key: String

    object Sun : SkyIcon() {
        override val key = "sun"
    }

    /**
     * The moon as it looks in the sky: [illumination] 0 new to 1 full, lit on the right while
     * [waxing] in the northern hemisphere and on the left south of the equator ([south]).
     */
    data class Moon(val illumination: Double, val waxing: Boolean, val south: Boolean) : SkyIcon() {
        override val key = "moon:${(illumination * 50).roundToInt()}:$waxing:$south"
    }

    fun drawable(sizePx: Int): Drawable = SkyIconDrawable(this, sizePx)
}

/** Drawn, not a bitmap: it stays sharp at any size and costs nothing to keep. */
private class SkyIconDrawable(private val icon: SkyIcon, private val sizePx: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val side = min(b.width(), b.height()).toFloat()
        when (icon) {
            is SkyIcon.Sun -> drawSun(canvas, cx, cy, side)
            is SkyIcon.Moon -> drawMoon(canvas, cx, cy, side, icon)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val r = side * 0.2f
        fill.color = SUN
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = SUN
        stroke.strokeWidth = side * 0.09f
        stroke.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cx + c * side * 0.32f, cy + s * side * 0.32f, cx + c * side * 0.44f, cy + s * side * 0.44f, stroke)
        }
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, side: Float, moon: SkyIcon.Moon) {
        val r = side * 0.42f
        // The dark part: just an outline, so the lit part is what reads.
        fill.color = DARK
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = RIM
        stroke.strokeWidth = side * 0.05f
        canvas.drawCircle(cx, cy, r, stroke)
        val k = moon.illumination.coerceIn(0.0, 1.0).toFloat()
        if (k < 0.02f) return
        fill.color = LIT
        if (k > 0.98f) {
            canvas.drawCircle(cx, cy, r, fill)
            return
        }
        // The lit half's edge, then back along the terminator: an ellipse as wide as the lit share is from a half.
        val rx = r * abs(1 - 2 * k)
        val path = Path().apply {
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f)
            arcTo(RectF(cx - rx, cy - r, cx + rx, cy + r), 90f, if (k < 0.5f) -180f else 180f)
            close()
        }
        // Lit on the right going north of the equator while waxing; mirrored for waning, and again south of it.
        val onRight = moon.waxing != moon.south
        canvas.save()
        if (!onRight) canvas.scale(-1f, 1f, cx, cy)
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SUN = 0xFFF2C14E.toInt()
        const val LIT = 0xFFE8E6DF.toInt()
        const val DARK = 0x33FFFFFF
        const val RIM = 0x66FFFFFF
    }
}
