package au.opendepth.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** The fixed graphite colour scheme shared by every panel and dialog. */
data class UiPalette(
    val background: Int,
    val panel: Int,
    val surface: Int,
    val raised: Int,
    val inputFill: Int,
    val border: Int,
    val focusBorder: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val destructive: Int
) {
    companion object {
        val GRAPHITE = UiPalette(
            background = 0xFF111418.toInt(),
            panel = 0xFF181D22.toInt(),
            surface = 0xFF20262D.toInt(),
            raised = 0xFF29313A.toInt(),
            // Input fields sit darker than the surface they are placed on so
            // the field edge always reads, whichever panel hosts it.
            inputFill = 0xFF0D1116.toInt(),
            border = 0xFF3A434D.toInt(),
            focusBorder = 0xFF19C6B3.toInt(),
            primaryText = 0xFFF4F6F8.toInt(),
            secondaryText = 0xFFAEB7C2.toInt(),
            accent = 0xFF19C6B3.toInt(),
            onAccent = 0xFF06231F.toInt(),
            destructive = 0xFFEF5350.toInt()
        )
    }
}

/** Corner radii used across the generated UI, in dp. */
object UiRadius {
    const val CARD = 18
    const val ROW = 12
    const val CONTROL = 10
}

object AppTheme {
    val palette: UiPalette = UiPalette.GRAPHITE
}

// View helpers -------------------------------------------------------------

/** Converts dp to whole pixels for this context's density. */
fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/** Converts dp to fractional pixels, for stroke widths and radii. */
fun Context.dpF(value: Float): Float =
    value * resources.displayMetrics.density

fun Context.roundedBackground(
    fill: Int,
    stroke: Int? = null,
    radiusDp: Int = UiRadius.ROW,
    strokeWidthDp: Int = 1
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fill)
    cornerRadius = dpF(radiusDp.toFloat())
    if (stroke != null) setStroke(dp(strokeWidthDp).coerceAtLeast(1), stroke)
}

fun Context.circleBackground(fill: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
}

/** Parses a hex colour, falling back rather than throwing on bad input. */
fun parseColorOrDefault(value: String?, fallback: Int): Int = runCatching {
    Color.parseColor(value)
}.getOrDefault(fallback)

/**
 * Styles a text input so it always sits on an explicit dark fill with a
 * visible border. Without this, programmatically created fields inherit the
 * dialog background and the text appears to float on the panel.
 */
fun EditText.applyInputStyle(
    palette: UiPalette = AppTheme.palette,
    radiusDp: Int = UiRadius.ROW
) {
    setTextColor(palette.primaryText)
    setHintTextColor(palette.secondaryText)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, INPUT_TEXT_SIZE_SP)
    background = context.roundedBackground(palette.inputFill, palette.border, radiusDp)
    setPadding(
        context.dp(14),
        context.dp(12),
        context.dp(14),
        context.dp(12)
    )
    // Highlight the border while editing so the active field is obvious.
    setOnFocusChangeListener { _, hasFocus ->
        background = context.roundedBackground(
            palette.inputFill,
            if (hasFocus) palette.focusBorder else palette.border,
            radiusDp,
            if (hasFocus) 2 else 1
        )
    }
}

/** Styles a primary (accent filled) action button. */
fun Button.applyPrimaryStyle(palette: UiPalette = AppTheme.palette) {
    isAllCaps = false
    setTextColor(palette.onAccent)
    background = context.roundedBackground(palette.accent, null, UiRadius.CONTROL)
    minHeight = context.dp(44)
    setPadding(context.dp(20), 0, context.dp(20), 0)
}

/** Styles a secondary (outlined) action button. */
fun Button.applySecondaryStyle(palette: UiPalette = AppTheme.palette) {
    isAllCaps = false
    setTextColor(palette.primaryText)
    background = context.roundedBackground(palette.raised, palette.border, UiRadius.CONTROL)
    minHeight = context.dp(44)
    setPadding(context.dp(18), 0, context.dp(18), 0)
}

/**
 * Styles the "Save waypoint" button on the new-waypoint creation sheet: a
 * solid green fill with black text, so it reads clearly as a button box
 * (like [PanelDialogs.confirm]'s solid red destructive button) rather than
 * as plain text. Kept separate from [applyPrimaryStyle] so other Save
 * buttons keep the teal accent style.
 */
fun Button.applyWaypointSaveStyle() {
    isAllCaps = false
    setTextColor(Color.BLACK)
    background = context.roundedBackground(WAYPOINT_SAVE_GREEN, null, UiRadius.CONTROL)
    minHeight = context.dp(44)
    setPadding(context.dp(20), 0, context.dp(20), 0)
}

/** Matches [au.opendepth.app.model.Waypoint.PALETTE]'s own green and `@color/green`. */
private const val WAYPOINT_SAVE_GREEN = 0xFF22C55E.toInt()

/**
 * Walks a generated view tree and forces readable text colours.
 *
 * Programmatic views inherit whatever the host theme decides, which on some
 * OEM builds is dark-on-dark. Buttons and labels whose text reads as a
 * destructive action are tinted accordingly.
 */
fun applyReadableText(root: View, palette: UiPalette = AppTheme.palette) {
    when (root) {
        is EditText -> {
            root.setTextColor(palette.primaryText)
            root.setHintTextColor(palette.secondaryText)
        }

        is Button -> root.setTextColor(
            if (isDestructiveLabel(root.text?.toString(), includeClear = true)) {
                palette.destructive
            } else {
                palette.primaryText
            }
        )

        is TextView -> root.setTextColor(
            if (isDestructiveLabel(root.text?.toString(), includeClear = false)) {
                palette.destructive
            } else {
                palette.primaryText
            }
        )
    }

    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            applyReadableText(root.getChildAt(index), palette)
        }
    }
}

private fun isDestructiveLabel(label: String?, includeClear: Boolean): Boolean {
    val text = label.orEmpty()
    if (text.isEmpty()) return false
    return text.contains("delete", ignoreCase = true) ||
        text.contains("remove", ignoreCase = true) ||
        (includeClear && text.contains("clear", ignoreCase = true))
}

private const val INPUT_TEXT_SIZE_SP = 15f
