package au.opendepth.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.settings.XyzTilePreset

/**
 * A tappable row describing an [XyzTilePreset], shared by the overlay
 * quick-add list and the custom base map picker.
 */
internal fun tilePresetRow(
    context: Context,
    palette: UiPalette,
    preset: XyzTilePreset,
    actionLabel: String,
    onChoose: () -> Unit
): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
    setPadding(context.dp(14), context.dp(11), context.dp(10), context.dp(11))
    isClickable = true
    setOnClickListener { onChoose() }

    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = preset.label
                    textSize = 14f
                    setTextColor(palette.primaryText)
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
            addView(
                TextView(context).apply {
                    text = preset.description
                    textSize = 11f
                    setTextColor(palette.secondaryText)
                }
            )
            addView(
                TextView(context).apply {
                    text = tileCategoryLabel(preset.category)
                    textSize = 10f
                    setTextColor(palette.accent)
                }
            )
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )

    addView(
        TextView(context).apply {
            text = actionLabel
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent)
            background = context.roundedBackground(palette.accent, null, UiRadius.CONTROL)
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            setOnClickListener { onChoose() }
        }
    )
}

internal fun tileCategoryLabel(category: XyzTilePreset.Category): String = when (category) {
    XyzTilePreset.Category.SATELLITE -> "Satellite imagery"
    XyzTilePreset.Category.TOPOGRAPHIC -> "Topographic"
    XyzTilePreset.Category.STREET -> "Street map"
    XyzTilePreset.Category.NAUTICAL -> "Nautical"
}
