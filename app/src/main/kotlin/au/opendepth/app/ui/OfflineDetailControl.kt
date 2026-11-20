package au.opendepth.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.download.OfflineDetail

/**
 * The DETAIL control in the offline map's area picker: − and + with the zoom between them
 * and what it means on the ground. The picker shows, under it, how many tiles the drawn box
 * needs at that detail, and refuses a box that needs too many.
 *
 * The detail follows the box (as deep as stays a sensible size, see [OfflineDetail.suggested])
 * until the person steps it; after that it is theirs.
 */
class OfflineDetailControl(
    private val context: Context,
    /** The most detail the map has: [OfflineDetail.topZoom]. */
    private val top: Int,
    /** Where the chart is looking, for what a zoom means on the ground before a box is drawn. */
    private val chartLatitude: () -> Double
) : AreaPicker.Extra {

    private val palette = AppTheme.palette
    private val limits = OfflineDetail.limits(top)

    /** The deepest zoom to save. */
    var detail: Int = limits.last
        private set

    private var stepped = false
    private var latitude: Double? = null
    private var zoomText: TextView? = null
    private var meaningText: TextView? = null
    private var less: Button? = null
    private var more: Button? = null

    override fun view(changed: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(8), 0, context.dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = "DETAIL"
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(palette.accent)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(10)
            }
        )
        fun stepButton(label: String, description: String, by: Int) = Button(context).apply {
            text = label
            textSize = 18f
            contentDescription = description
            applySecondaryStyle(palette)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                stepped = true
                detail = OfflineDetail.step(detail, by, top)
                show()
                changed()
            }
        }
        val buttonParams = { LinearLayout.LayoutParams(context.dp(STEP_BUTTON_DP), context.dp(STEP_BUTTON_DP)) }
        less = stepButton("−", "Less detail: fewer tiles", -1).also { row.addView(it, buttonParams()) }
        row.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                zoomText = TextView(context).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(palette.primaryText)
                }.also { addView(it) }
                meaningText = TextView(context).apply {
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(palette.secondaryText)
                }.also { addView(it) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        more = stepButton("+", "More detail: many more tiles", 1).also { row.addView(it, buttonParams()) }
        show()
        return row
    }

    override fun judge(box: AreaPicker.Box): AreaPicker.Verdict {
        latitude = (box.south + box.north) / 2
        val bounds = OfflineDetail.boundsOf(box.south, box.west, box.north, box.east)
        if (!bounds.isValid) {
            show()
            return AreaPicker.Verdict("That box can't be saved. Draw it again.", ok = false)
        }
        if (!stepped) detail = OfflineDetail.suggested(bounds, top)
        show()
        val tiles = OfflineDetail.tiles(bounds, detail)
        return AreaPicker.Verdict(OfflineDetail.countLine(tiles), OfflineDetail.fits(tiles))
    }

    private fun show() {
        zoomText?.text = "Zoom $detail"
        meaningText?.text = OfflineDetail.meaning(detail, latitude ?: chartLatitude())
        less?.let { enable(it, detail > limits.first) }
        more?.let { enable(it, detail < limits.last) }
    }

    private fun enable(button: Button, on: Boolean) {
        button.isEnabled = on
        button.alpha = if (on) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val STEP_BUTTON_DP = 44
        const val DISABLED_ALPHA = 0.45f
    }
}
