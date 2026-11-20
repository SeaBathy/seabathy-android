package au.opendepth.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import au.opendepth.app.model.Waypoint
import au.opendepth.app.model.WaypointIcon

/**
 * A waypoint's colour and symbol choices, drawn the same wherever they are
 * offered (the new-waypoint sheet and the waypoint editor): the chosen colour
 * larger than the rest, the chosen symbol on the accent.
 */
object WaypointSwatches {

    /** A colour swatch and the space around it, as the new-waypoint sheet lays them out, in dp. */
    const val COLOUR_SIZE_DP = 36
    const val COLOUR_MARGIN_DP = 5

    /** A symbol swatch and the space around it, in dp. */
    const val ICON_SIZE_DP = 40
    const val ICON_MARGIN_DP = 4

    private const val SELECTED_SCALE = 1.25f

    /** Names for [Waypoint.PALETTE], in its order, for screen readers. */
    private val COLOUR_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple")

    fun colourName(index: Int): String = COLOUR_NAMES.getOrElse(index) { "Colour ${index + 1}" }

    /**
     * How much smaller than usual swatches must be drawn for [count] of them, each [naturalCell]
     * wide with its margins, to fit in a row [available] wide: 1 when they fit as they are.
     */
    fun fitScale(available: Int, count: Int, naturalCell: Int): Float {
        if (count <= 0 || naturalCell <= 0 || available <= 0) return 1f
        return (available.toFloat() / (count * naturalCell)).coerceAtMost(1f)
    }

    /** Gives swatches laid out elsewhere (one per [Waypoint.PALETTE] colour) their colour's name. */
    fun nameColours(swatches: List<View>) {
        swatches.forEachIndexed { index, swatch -> swatch.contentDescription = colourName(index) }
    }

    /** Adds a round swatch for each [Waypoint.PALETTE] colour to [row]; [scale] below 1 shrinks them to fit. */
    fun addColours(row: LinearLayout, scale: Float = 1f): List<View> {
        val context = row.context
        val size = (context.dp(COLOUR_SIZE_DP) * scale).toInt()
        val margin = (context.dp(COLOUR_MARGIN_DP) * scale).toInt()
        return Waypoint.PALETTE.mapIndexed { index, hex ->
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(parseColorOrDefault(hex, AppTheme.palette.destructive))
                    setStroke(context.dp(2), COLOUR_RING)
                }
                contentDescription = colourName(index)
                row.addView(this, LinearLayout.LayoutParams(size, size).apply { setMargins(margin, margin, margin, margin) })
            }
        }
    }

    /** Draws the swatch of [selected] larger; none when it isn't one of [Waypoint.PALETTE]. */
    fun showColour(swatches: List<View>, selected: String?) {
        swatches.forEachIndexed { index, swatch ->
            val chosen = Waypoint.PALETTE.getOrNull(index).equals(selected, ignoreCase = true)
            val scale = if (chosen) SELECTED_SCALE else 1f
            swatch.scaleX = scale
            swatch.scaleY = scale
            swatch.isSelected = chosen
        }
    }

    /** Adds a swatch for each of [WaypointIcon.ALL] to [row], so the list lives in one place. */
    fun addIcons(row: LinearLayout, scale: Float = 1f): List<ImageView> {
        val context = row.context
        val size = (context.dp(ICON_SIZE_DP) * scale).toInt()
        val margin = (context.dp(ICON_MARGIN_DP) * scale).toInt()
        val inset = size / 5
        return WaypointIcon.ALL.map { icon ->
            ImageView(context).apply {
                setImageResource(icon.drawableRes)
                contentDescription = icon.label
                background = iconBackground(this, chosen = false)
                setPadding(inset, inset, inset, inset)
                row.addView(
                    this,
                    LinearLayout.LayoutParams(size, size).apply {
                        marginStart = margin
                        marginEnd = margin
                    }
                )
            }
        }
    }

    /** Puts the swatch of the symbol [selectedId] on the accent. */
    fun showIcon(swatches: List<ImageView>, selectedId: String?) {
        val selected = WaypointIcon.byId(selectedId).id
        swatches.forEachIndexed { index, swatch ->
            val chosen = WaypointIcon.ALL[index].id == selected
            swatch.background = iconBackground(swatch, chosen)
            swatch.isSelected = chosen
        }
    }

    private fun iconBackground(view: View, chosen: Boolean) = view.context.roundedBackground(
        if (chosen) AppTheme.palette.accent else AppTheme.palette.surface,
        AppTheme.palette.border,
        UiRadius.CONTROL
    )

    /** The faint ring round a colour swatch, as in `res/drawable/col_*.xml`. */
    private const val COLOUR_RING = 0x40FFFFFF
}
