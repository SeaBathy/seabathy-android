package au.opendepth.app.ui

import android.graphics.PointF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import au.opendepth.app.map.ChartScale
import au.opendepth.app.map.MapHolder
import au.opendepth.app.nav.DistanceUnit
import au.opendepth.app.nav.NavGeo
import org.maplibre.android.camera.CameraPosition
import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps the scale bar on the chart: where it sits, and what it says.
 *
 * The distance is worked out from the map itself: the ground distance
 * between two points on the bar's own row of the screen, so it is right at
 * any latitude, with the chart turned, and tilted (where the scale changes
 * up the screen). It is worked out again as the chart moves, but only when
 * something it depends on has changed.
 *
 * It sits at the bottom left, just above whichever of [above] is highest on
 * screen now (the credit line, or a route, measure, hint or sea conditions
 * card), so it neither covers nor is covered by them, none of them moves
 * for it, and it clears the system bars as they do.
 */
class ChartScaleBar(
    private val root: ViewGroup,
    private val mapView: View,
    private val mapHolder: MapHolder,
    private val above: List<View>,
    private val unit: () -> DistanceUnit
) {
    val view = ScaleBarView(root.context)

    private val context = root.context
    private val from = PointF()
    private val to = PointF()

    private var leftInset = 0
    private var bottomInset = 0

    // What the bar was last worked out for: nothing is done while these stand.
    private var lastZoom = Double.NaN
    private var lastLatitude = Double.NaN
    private var lastBearing = Double.NaN
    private var lastTilt = Double.NaN
    private var lastRow = Float.NaN
    private var lastUnit: DistanceUnit? = null

    /** Adds the bar to the chart, under [before] in the drawing order (cards and panels stay on top of it). */
    fun bind(before: View, shown: Boolean) {
        root.addView(
            view,
            root.indexOfChild(before).coerceAtLeast(0),
            CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = context.dp(SIDE_DP)
                bottomMargin = context.dp(BOTTOM_DP)
            }
        )
        view.visibility = if (shown) View.VISIBLE else View.GONE
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            leftInset = bars.left
            bottomInset = bars.bottom
            place()
            insets
        }
        ViewCompat.requestApplyInsets(view)
        // Above the credit line, or a card when one is up, wherever those are now.
        root.viewTreeObserver.addOnGlobalLayoutListener { place() }
        // It has moved to another row of the screen: under tilt that is another scale.
        view.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ -> if (top != oldTop) update() }
    }

    fun setShown(shown: Boolean) {
        view.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) {
            forget()
            update()
        }
    }

    /**
     * The chart moved, or the distance unit changed: the bar is worked out
     * again if anything it depends on did. [position] is the camera's, when
     * the caller has just read it (it is read every frame of a move).
     */
    fun update(position: CameraPosition? = null) {
        if (view.visibility != View.VISIBLE || view.width == 0) return
        val map = mapHolder.map ?: return
        val camera = position ?: map.cameraPosition
        val latitude = camera.target?.latitude ?: return
        val row = view.top + view.barRowY - mapView.top
        val unit = unit()
        if (camera.zoom == lastZoom && latitude == lastLatitude && camera.bearing == lastBearing &&
            camera.tilt == lastTilt && row == lastRow && unit == lastUnit
        ) return

        val start = view.left + view.barStartX - mapView.left
        from.set(start, row)
        to.set(start + view.maxBarPx, row)
        val span = runCatching {
            val a = map.projection.fromScreenLocation(from)
            val b = map.projection.fromScreenLocation(to)
            // Off the edge of the world (zoomed right out), there is no distance to give.
            if (abs(a.latitude) >= MAX_LATITUDE || abs(b.latitude) >= MAX_LATITUDE) Double.NaN
            else NavGeo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude)
        }.getOrDefault(Double.NaN)

        lastZoom = camera.zoom
        lastLatitude = latitude
        lastBearing = camera.bearing
        lastTilt = camera.tilt
        lastRow = row
        lastUnit = unit

        val bar = ChartScale.pick(span, unit)
        if (bar == null) view.clear() else view.show(bar.label, (view.maxBarPx * bar.metres / span).toFloat())
    }

    private fun forget() {
        lastZoom = Double.NaN
    }

    private fun place() {
        val rootHeight = root.height
        if (rootHeight == 0) return
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        val highest = above.filter { it.visibility == View.VISIBLE && it.height > 0 }.maxOfOrNull { rootHeight - it.top }
        // With nothing under it, where the credit line would be, clear of the system bar.
        val bottom = if (highest != null) highest + context.dp(GAP_DP) else max(context.dp(BOTTOM_DP), bottomInset + context.dp(GAP_DP))
        val side = context.dp(SIDE_DP) + leftInset
        if (params.bottomMargin != bottom || params.marginStart != side) {
            params.bottomMargin = bottom
            params.marginStart = side
            view.requestLayout()
        }
    }

    private companion object {
        /** As the credit line: the same distance in from the left, and the same height off the bottom when alone. */
        const val SIDE_DP = 8
        const val BOTTOM_DP = 72
        const val GAP_DP = 6

        /** Just inside the chart's own limit (about 85.05°): a screen point at or past this is off the edge of the world. */
        const val MAX_LATITUDE = 85.0
    }
}
