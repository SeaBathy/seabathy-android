package au.opendepth.app.conditions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import au.opendepth.app.conditions.ConditionsPicture.latitudeOf
import au.opendepth.app.conditions.ConditionsPicture.mercatorY
import au.opendepth.app.map.MapHolder
import org.maplibre.android.geometry.LatLng
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Moving flow lines over the chart: specks that drift with the current (or
 * the wind, waves or swell) and leave short fading trails, so the flow's
 * paths show. Drawn on a view over the map, not by the map itself.
 *
 * The specks and their trails live in web-Mercator coordinates, so the
 * trails stay put while the map is panned or zoomed. Each frame one
 * transform from Mercator to the screen is taken from the map (three
 * projected points); the trails are then drawn as lines by the graphics
 * hardware, a few batches a frame, with nothing allocated per frame.
 */
// Made in code only (it needs the map), never from a layout file.
@SuppressLint("ViewConstructor")
class FlowLinesView(context: Context, private val mapHolder: MapHolder) : View(context) {

    /** The flow at a point: writes the east and north parts (any unit) into [out]; false where there's none (land, off the area). */
    fun interface Field {
        fun at(lat: Double, lng: Double, out: DoubleArray): Boolean
    }

    private var field: Field? = null
    /** The strength that moves a speck at full speed. */
    private var reference = 1.0
    private var south = 0.0
    private var west = 0.0
    private var north = 0.0
    private var east = 0.0
    private var southY = 0.0
    private var northY = 0.0

    private var running = false
    private var lastFrame = 0L

    // Specks: x is longitude and y Mercator northing, both in radians. Each
    // keeps its last TRAIL positions in a ring (newest at head).
    private var count = 0
    private var trailX = DoubleArray(0)
    private var trailY = DoubleArray(0)
    private var head = IntArray(0)
    private var length = IntArray(0)
    private var ages = IntArray(0)
    private var lifetimes = IntArray(0)
    private val flow = DoubleArray(2)
    private val random = Random(SystemClock.uptimeMillis())

    /** One batch of segments per trail step, oldest faintest. */
    private val batches = Array(TRAIL - 1) { FloatArray(0) }
    private val batchSizes = IntArray(TRAIL - 1)
    private val paints = Array(TRAIL - 1) { step ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Step 0 is the newest segment.
            color = Color.argb(NEWEST_ALPHA - (NEWEST_ALPHA - OLDEST_ALPHA) * step / (TRAIL - 2), 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
    }

    // Mercator (radians) to screen pixels: screen = m * merc + t.
    private var m00 = 0.0
    private var m01 = 0.0
    private var m10 = 0.0
    private var m11 = 0.0
    private var tx = 0.0
    private var ty = 0.0
    private var haveTransform = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** What to draw: [field] over the area, a speck moving fastest at [reference] strength. Null stops it. */
    fun show(field: Field?, reference: Double, south: Double, west: Double, north: Double, east: Double) {
        val newArea = south != this.south || west != this.west || north != this.north || east != this.east
        this.field = field
        this.reference = reference.coerceAtLeast(1e-6)
        this.south = south
        this.west = west
        this.north = north
        this.east = east
        southY = mercatorY(south)
        northY = mercatorY(north)
        if (field == null) {
            stop()
            return
        }
        if (newArea) count = 0
        visibility = VISIBLE
        if (!running) {
            running = true
            postOnAnimation(frame)
        }
    }

    fun stop() {
        running = false
        removeCallbacks(frame)
        count = 0
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A new screen size wants a different number of specks.
        count = 0
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame >= FRAME_MS) {
                lastFrame = now
                step()
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    /** Moves every speck one frame along the flow. */
    private fun step() {
        val field = field ?: return
        haveTransform = takeTransform()
        if (!haveTransform || width == 0 || height == 0) return
        if (count == 0) seed()
        val det = m00 * m11 - m01 * m10
        if (det == 0.0) return
        val maxStep = resources.displayMetrics.density * MAX_STEP_DP

        for (i in 0 until count) {
            ages[i]++
            val at = i * TRAIL + head[i]
            val x = trailX[at]
            val y = trailY[at]
            if (ages[i] > lifetimes[i] || !field.at(latitudeOf(y), Math.toDegrees(x), flow)) {
                respawn(i)
                continue
            }
            val strength = hypot(flow[0], flow[1])
            if (strength < 1e-6) continue
            // Which way east-and-north points on the screen (the map may be turned).
            val sx = m00 * flow[0] + m01 * flow[1]
            val sy = m10 * flow[0] + m11 * flow[1]
            val len = hypot(sx, sy)
            if (len == 0.0) continue
            val pixels = maxStep * min(1.0, sqrt(strength / reference))
            val dx = sx / len * pixels
            val dy = sy / len * pixels
            // Back to Mercator through the inverse transform.
            val nx = x + (m11 * dx - m01 * dy) / det
            val ny = y + (-m10 * dx + m00 * dy) / det
            val screenX = m00 * nx + m01 * ny + tx
            val screenY = m10 * nx + m11 * ny + ty
            // Off the screen, or out of the area (where the colours stop): start again.
            val nLng = Math.toDegrees(nx)
            if (screenX < 0 || screenY < 0 || screenX > width || screenY > height ||
                nLng < west || nLng > east || ny < southY || ny > northY
            ) {
                respawn(i)
                continue
            }
            head[i] = (head[i] + 1) % TRAIL
            trailX[i * TRAIL + head[i]] = nx
            trailY[i * TRAIL + head[i]] = ny
            if (length[i] < TRAIL) length[i]++
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || !haveTransform || count == 0) return
        batchSizes.fill(0)
        for (i in 0 until count) {
            val n = length[i]
            if (n < 2) continue
            var k = head[i]
            var x0 = m00 * trailX[i * TRAIL + k] + m01 * trailY[i * TRAIL + k] + tx
            var y0 = m10 * trailX[i * TRAIL + k] + m11 * trailY[i * TRAIL + k] + ty
            for (s in 0 until n - 1) {
                k = (k - 1 + TRAIL) % TRAIL
                val x1 = m00 * trailX[i * TRAIL + k] + m01 * trailY[i * TRAIL + k] + tx
                val y1 = m10 * trailX[i * TRAIL + k] + m11 * trailY[i * TRAIL + k] + ty
                val batch = batches[s]
                val b = batchSizes[s]
                batch[b] = x0.toFloat()
                batch[b + 1] = y0.toFloat()
                batch[b + 2] = x1.toFloat()
                batch[b + 3] = y1.toFloat()
                batchSizes[s] = b + 4
                x0 = x1
                y0 = y1
            }
        }
        val width = resources.displayMetrics.density * LINE_DP
        for (s in batches.indices) {
            if (batchSizes[s] == 0) continue
            paints[s].strokeWidth = width
            canvas.drawLines(batches[s], 0, batchSizes[s], paints[s])
        }
    }

    /** Reads the map's current Mercator-to-screen transform. False while the map can't say. */
    private fun takeTransform(): Boolean {
        val map = mapHolder.map ?: return false
        val projection = map.projection
        val centre = map.cameraPosition.target ?: return false
        val lat = centre.latitude.coerceIn(-80.0, 80.0)
        val lng = centre.longitude
        val d = 0.01
        val p0: PointF = projection.toScreenLocation(LatLng(lat, lng))
        val pe: PointF = projection.toScreenLocation(LatLng(lat, lng + Math.toDegrees(d)))
        val y0 = mercatorY(lat)
        val pn: PointF = projection.toScreenLocation(LatLng(latitudeOf(y0 + d), lng))
        m00 = (pe.x - p0.x) / d
        m10 = (pe.y - p0.y) / d
        m01 = (pn.x - p0.x) / d
        m11 = (pn.y - p0.y) / d
        val x0 = Math.toRadians(lng)
        tx = p0.x - (m00 * x0 + m01 * y0)
        ty = p0.y - (m10 * x0 + m11 * y0)
        return m00.isFinite() && m11.isFinite()
    }

    private fun seed() {
        count = (width.toLong() * height / PIXELS_PER_SPECK).toInt().coerceIn(MIN_SPECKS, MAX_SPECKS)
        if (trailX.size < count * TRAIL) {
            trailX = DoubleArray(count * TRAIL)
            trailY = DoubleArray(count * TRAIL)
            head = IntArray(count)
            length = IntArray(count)
            ages = IntArray(count)
            lifetimes = IntArray(count)
            for (s in batches.indices) batches[s] = FloatArray(count * 4)
        }
        for (i in 0 until count) {
            respawn(i)
            // Start part way through their lives, so they don't all fade at once.
            ages[i] = random.nextInt(lifetimes[i].coerceAtLeast(1))
        }
    }

    /** Puts a speck somewhere on screen over the area's water, with no trail yet. */
    private fun respawn(i: Int) {
        lifetimes[i] = MIN_LIFE + random.nextInt(MAX_LIFE - MIN_LIFE)
        ages[i] = 0
        head[i] = 0
        length[i] = 1
        val det = m00 * m11 - m01 * m10
        val field = field
        if (det != 0.0 && field != null) {
            repeat(SPAWN_TRIES) {
                val sx = random.nextDouble() * width - tx
                val sy = random.nextDouble() * height - ty
                val x = (m11 * sx - m01 * sy) / det
                val y = (-m10 * sx + m00 * sy) / det
                val lat = latitudeOf(y)
                val lng = Math.toDegrees(x)
                if (lat in south..north && lng in west..east && field.at(lat, lng, flow)) {
                    trailX[i * TRAIL] = x
                    trailY[i * TRAIL] = y
                    return
                }
            }
        }
        // Nowhere found this time (the area is mostly off screen): park it off screen until next try.
        trailX[i * TRAIL] = Math.toRadians(west) - 1.0
        trailY[i * TRAIL] = mercatorY(south)
        ages[i] = lifetimes[i]
        length[i] = 0
    }

    companion object {
        /** Positions kept per speck: its trail. */
        private const val TRAIL = 12
        private const val FRAME_MS = 40L
        private const val NEWEST_ALPHA = 230
        private const val OLDEST_ALPHA = 20
        private const val LINE_DP = 1.3f
        private const val MAX_STEP_DP = 2.4f
        private const val PIXELS_PER_SPECK = 5200L
        private const val MIN_SPECKS = 200
        private const val MAX_SPECKS = 900
        private const val MIN_LIFE = 50
        private const val MAX_LIFE = 140
        private const val SPAWN_TRIES = 6
    }
}
