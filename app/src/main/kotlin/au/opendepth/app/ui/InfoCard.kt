package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import au.opendepth.app.R

/**
 * The app's own information card, in place of Android's grey system dialogs: a dark
 * rounded card with an optional coloured badge, a title and subtitle, labelled rows,
 * plain paragraphs, small print under a divider, and buttons in the app's style.
 */
class InfoCard(private val context: Context) {

    private val palette = AppTheme.palette

    /** A labelled line: "Type" → "Marine park, QLD". [swatch] draws a colour square before the label. */
    data class Row(val label: String, val value: String, val swatch: Int? = null)

    /** [closes] false leaves the card open when the button is tapped: its [onClick] closes it (the dialog [show] returns) once it can. */
    class Action(
        val label: String,
        val primary: Boolean = false,
        val destructive: Boolean = false,
        val closes: Boolean = true,
        val onClick: () -> Unit = {}
    )

    var badge: String? = null
    var badgeColour: Int = palette.accent
    var title: String = ""
    var subtitle: String? = null
    val rows = mutableListOf<Row>()
    val paragraphs = mutableListOf<String>()
    var fine: String? = null
    /** Extra content (a tick box, say) under the paragraphs. */
    var extra: View? = null
    val actions = mutableListOf<Action>()
    var onDismiss: (() -> Unit)? = null

    /** The buttons shown, in [actions] order, so a caller can enable one later. */
    val buttons = mutableListOf<Button>()

    fun show(): Dialog {
        val dialog = Dialog(context, R.style.Theme_SeaBathy_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
            setPadding(context.dp(CARD_PADDING_DP), context.dp(18), context.dp(CARD_PADDING_DP), context.dp(14))
        }
        badge?.let { text ->
            card.addView(TextView(context).apply {
                this.text = text.uppercase()
                textSize = 11f
                letterSpacing = 0.08f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (luminance(badgeColour) > 0.55) Color.parseColor("#0B1622") else Color.WHITE)
                background = context.roundedBackground(badgeColour, null, 20)
                setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(10)
            })
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.primaryText)
        })
        subtitle?.let { s ->
            card.addView(TextView(context).apply {
                text = s
                textSize = 13.5f
                setTextColor(palette.secondaryText)
                setPadding(0, context.dp(3), 0, 0)
            })
        }
        if (rows.isNotEmpty()) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = context.roundedBackground(palette.surface, null, UiRadius.ROW)
                setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            }
            rows.forEachIndexed { i, row ->
                val line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, context.dp(8), 0, context.dp(8))
                }
                row.swatch?.let { colour ->
                    line.addView(View(context).apply { background = context.roundedBackground(colour, null, 3) },
                        LinearLayout.LayoutParams(context.dp(12), context.dp(12)).apply { marginEnd = context.dp(10) })
                }
                line.addView(TextView(context).apply {
                    text = row.label
                    textSize = 13.5f
                    setTextColor(palette.secondaryText)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (row.value.isEmpty()) 1f else 0.42f))
                if (row.value.isNotEmpty()) {
                    line.addView(TextView(context).apply {
                        text = row.value
                        textSize = 14.5f
                        setTextColor(palette.primaryText)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
                }
                box.addView(line)
                if (i < rows.lastIndex) box.addView(View(context).apply { setBackgroundColor(palette.border) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            card.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(14)
            })
        }
        for (p in paragraphs) {
            card.addView(TextView(context).apply {
                text = p
                textSize = 15f
                setLineSpacing(0f, 1.15f)
                setTextColor(palette.primaryText)
                setPadding(0, context.dp(12), 0, 0)
            })
        }
        extra?.let { card.addView(it) }
        fine?.let { f ->
            card.addView(View(context).apply { setBackgroundColor(palette.border) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = context.dp(16) })
            card.addView(TextView(context).apply {
                text = f
                textSize = 12f
                setLineSpacing(0f, 1.1f)
                setTextColor(palette.secondaryText)
                setPadding(0, context.dp(10), 0, 0)
            })
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(14), 0, 0)
        }
        val list = actions.ifEmpty { listOf(Action("OK", primary = true)) }
        for (a in list) {
            val b = Button(context).apply {
                text = a.label
                if (a.primary) applyPrimaryStyle(palette) else applySecondaryStyle(palette)
                if (a.destructive) setTextColor(palette.destructive)
                setOnClickListener {
                    if (a.closes) dialog.dismiss()
                    a.onClick()
                }
            }
            buttons += b
            buttonRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(8)
            })
        }
        card.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fitButtons(buttonRow, list, cardWidth() - 2 * context.dp(CARD_PADDING_DP))

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(card)
        }
        dialog.setContentView(scroll)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = 0.6f
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(cardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    private fun cardWidth(): Int = minOf((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), context.dp(460))

    /**
     * Three buttons can be wider than a narrow phone. When the row doesn't fit in [available]
     * the buttons are drawn closer together, and if that isn't enough, one under another: the
     * main one on top and the first (Cancel, usually) at the bottom. Cards whose buttons fit are
     * left exactly as they were.
     */
    private fun fitButtons(row: LinearLayout, shown: List<Action>, available: Int) {
        fun tooWide(): Boolean {
            val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            row.measure(free, free)
            return row.measuredWidth > available
        }
        if (buttons.size < 2 || !tooWide()) return
        for (button in buttons) {
            button.minWidth = 0
            button.minimumWidth = 0
            button.setPadding(context.dp(TIGHT_PADDING_DP), 0, context.dp(TIGHT_PADDING_DP), 0)
        }
        if (!tooWide()) return
        row.removeAllViews()
        row.orientation = LinearLayout.VERTICAL
        // sortedByDescending keeps the order of equals: the rest stay last-to-first.
        for (index in shown.indices.reversed().sortedByDescending { shown[it].primary }) {
            row.addView(buttons[index], LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            })
        }
    }

    private fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private companion object {
        const val CARD_PADDING_DP = 20
        /** Either side of a button's label when the usual spacing doesn't fit. */
        const val TIGHT_PADDING_DP = 12
    }
}
