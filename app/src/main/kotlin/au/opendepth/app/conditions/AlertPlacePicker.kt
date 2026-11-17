package au.opendepth.app.conditions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.UiRadius
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.dpF
import au.opendepth.app.ui.roundedBackground
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Picks a good-weather alert's place: the map moves under a circle round the
 * crosshair (the middle of the map), a slider sets its radius, and the bar
 * says what it means. The circle passes every touch through to the map.
 */
class AlertPlacePicker(
    private val context: Context,
    private val root: ViewGroup,
    private val map: MapLibreMap,
    private val onDone: (lat: Double, lng: Double, radiusKm: Double) -> Unit,
    private val onCancel: () -> Unit
) {
    private val palette = AppTheme.palette
    private var overlay: Circle? = null
    private var bar: LinearLayout? = null
    private var radiusIndex = RADII.indexOf(10.0)
    private lateinit var radiusText: TextView
    private val cameraListener = MapLibreMap.OnCameraMoveListener { overlay?.invalidate() }

    /** Starts over [start] (an existing place being moved) with its radius, or where the map is. */
    fun show(start: Pair<Double, Double>? = null, radiusKm: Double? = null) {
        radiusKm?.let { r -> radiusIndex = RADII.indices.minByOrNull { kotlin.math.abs(RADII[it] - r) } ?: radiusIndex }
        start?.let { (lat, lng) -> map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lng))) }
        val o = Circle(context)
        root.addView(o, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        o.elevation = context.dpF(30f)
        overlay = o
        bar = buildBar().also {
            root.addView(it, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                setMargins(context.dp(12), 0, context.dp(12), context.dp(24))
            })
            it.elevation = context.dpF(32f)
        }
        map.addOnCameraMoveListener(cameraListener)
        // Zoomed so the circle fits comfortably.
        fitZoom()
    }

    fun dismiss() {
        map.removeOnCameraMoveListener(cameraListener)
        overlay?.let { root.removeView(it) }
        bar?.let { root.removeView(it) }
        overlay = null
        bar = null
    }

    private val radiusKm: Double get() = RADII[radiusIndex]

    private fun fitZoom() {
        val target = map.cameraPosition.target ?: return
        val widthPx = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val metresPerPx = map.projection.getMetersPerPixelAtLatitude(target.latitude)
        val circlePx = radiusKm * 1000 / metresPerPx
        // The circle about two thirds of the screen across.
        val want = widthPx / 3.0
        val zoomChange = kotlin.math.ln(want / circlePx) / kotlin.math.ln(2.0)
        if (kotlin.math.abs(zoomChange) > 0.4) {
            map.animateCamera(CameraUpdateFactory.zoomTo((map.cameraPosition.zoom + zoomChange).coerceIn(3.0, 14.0)))
        }
    }

    private fun buildBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            isClickable = true
        }
        bar.addView(TextView(context).apply {
            text = "ALERT PLACE"
            textSize = 11f
            setTextColor(palette.accent)
            letterSpacing = 0.08f
        })
        bar.addView(TextView(context).apply {
            text = "Move the map to put the circle over the water you go out on"
            textSize = 16f
            setTextColor(palette.primaryText)
            setPadding(0, context.dp(2), 0, 0)
        })
        radiusText = TextView(context).apply {
            textSize = 13f
            setTextColor(palette.secondaryText)
            setPadding(0, context.dp(8), 0, 0)
        }
        bar.addView(radiusText)
        bar.addView(SeekBar(context).apply {
            max = RADII.size - 1
            progress = radiusIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    radiusIndex = value
                    updateRadius()
                    this@AlertPlacePicker.overlay?.invalidate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = fitZoom()
            })
        })
        updateRadius()
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, context.dp(6), 0, 0)
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
        buttons.addView(button("Use this place", true) {
            val target = map.cameraPosition.target ?: return@button
            dismiss()
            onDone(target.latitude, target.longitude, radiusKm)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10)
        })
        bar.addView(buttons)
        return bar
    }

    private fun updateRadius() {
        radiusText.text = "Within ${radiusKm.toInt()} km: the worst wind and waves anywhere in the circle count"
    }

    /** The circle round the middle of the map; touches go through to the map. */
    @SuppressLint("ViewConstructor")
    private inner class Circle(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 25, 198, 179) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(2.5f)
            color = palette.accent
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }

        override fun onDraw(canvas: Canvas) {
            val target = map.cameraPosition.target ?: return
            val centre = map.projection.toScreenLocation(target)
            val r = (radiusKm * 1000 / map.projection.getMetersPerPixelAtLatitude(target.latitude)).toFloat()
            canvas.drawCircle(centre.x, centre.y, r, fill)
            canvas.drawCircle(centre.x, centre.y, r, edge)
            canvas.drawCircle(centre.x, centre.y, context.dpF(4f), dot)
        }
    }

    companion object {
        val RADII = listOf(2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 50.0)
    }
}
