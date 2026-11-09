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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import au.opendepth.app.R

/**
 * Builds the app's opaque settings panels and confirmation dialogs.
 *
 * All of this UI is generated in code rather than inflated, so the styling is
 * centralised here to keep panels, rows and inputs consistent with each other.
 */
class PanelDialogs(private val context: Context) {

    private val openPanels = mutableListOf<Dialog>()

    /** Closes every panel opened by [showPanel], e.g. once a picked file starts loading. */
    fun dismissAll() {
        openPanels.toList().forEach { it.dismiss() }
    }

    private val palette = AppTheme.palette

    // Building blocks -------------------------------------------------------

    /** A vertical container sized and padded like a panel body. */
    fun body(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), 0, context.dp(16), context.dp(12))
        setBackgroundColor(palette.panel)
    }

    /** An accent-coloured section heading. */
    fun sectionHeader(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.accent)
        setPadding(context.dp(2), context.dp(18), 0, context.dp(8))
    }

    /** A small muted caption, used as a field label or explanatory note. */
    fun caption(text: String, topPaddingDp: Int = 4): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(context.dp(2), context.dp(topPaddingDp), 0, context.dp(8))
        }

    /** A tappable settings row showing a title and its current value. */
    fun row(
        title: String,
        value: String = "",
        destructive: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = if (value.isBlank()) title else "$title\n$value"
        textSize = 14f
        setTextColor(if (destructive) palette.destructive else palette.primaryText)
        background = context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
        setPadding(context.dp(16), context.dp(13), context.dp(16), context.dp(13))
        minHeight = context.dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setLineSpacing(context.dpF(2f), 1f)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Adds [view] to [parent] with the standard row spacing. */
    fun addRow(parent: LinearLayout, view: View, bottomMarginDp: Int = 9) {
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dp(bottomMarginDp) }
        )
    }

    fun input(configure: EditText.() -> Unit = {}): EditText = EditText(context).apply {
        applyInputStyle(palette)
        configure()
    }

    // Panels ----------------------------------------------------------------

    /**
     * Shows a full-width opaque panel with a title bar, scrolling content and
     * a footer. Returns the dialog so callers can dismiss it from an action.
     * With [overMap] it sits in the lower part of the screen without dimming,
     * so changes it makes to the map (colours) can be seen while editing.
     */
    fun showPanel(
        title: String,
        content: View,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        overMap: Boolean = false,
        /** Runs however the panel is closed (✕, Close, back), e.g. to save its settings. */
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context, R.style.Theme_SeaBathy_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        card.addView(panelHeader(title) { dialog.dismiss() })

        val contentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            addView(
                ScrollView(context).apply {
                    isFillViewport = false
                    isVerticalScrollBarEnabled = true
                    setBackgroundColor(Color.TRANSPARENT)
                    addView(
                        content,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        card.addView(
            contentCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (overMap) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                if (overMap) 1f else 0f
            ).apply {
                marginStart = context.dp(14)
                marginEnd = context.dp(14)
            }
        )

        card.addView(panelFooter(actionLabel, onAction) { dialog.dismiss() })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (overMap) clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            else addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = if (overMap) Gravity.BOTTOM else Gravity.CENTER
                dimAmount = if (overMap) 0f else DIM_AMOUNT
                windowAnimations = 0
            }
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnShowListener {
            val metrics = context.resources.displayMetrics
            dialog.window?.setLayout(
                (metrics.widthPixels * PANEL_WIDTH_FRACTION).toInt(),
                if (overMap) (metrics.heightPixels * OVER_MAP_HEIGHT_FRACTION).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.setOnDismissListener {
            openPanels.remove(dialog)
            onDismiss?.invoke()
        }

        applyReadableText(root, palette)
        dialog.show()
        openPanels.add(dialog)
        return dialog
    }

    /** Shows a single-select list panel and reports the chosen index. */
    fun showChoicePanel(
        title: String,
        labels: List<String>,
        selectedIndex: Int = -1,
        onChosen: (Int) -> Unit
    ) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(10))
        }

        lateinit var dialog: Dialog
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            list.addView(
                TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(if (selected) palette.onAccent else palette.primaryText)
                    background = context.roundedBackground(
                        if (selected) palette.accent else palette.raised,
                        palette.border,
                        UiRadius.CONTROL
                    )
                    setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
                    setOnClickListener {
                        dialog.dismiss()
                        onChosen(index)
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = context.dp(9) }
            )
        }

        dialog = showPanel(title, list)
    }

    private fun panelHeader(title: String, onClose: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20), context.dp(16), context.dp(12), context.dp(12))

            addView(
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTextColor(palette.primaryText)
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(closeButton(onClose))
        }

    fun closeButton(onClose: () -> Unit): TextView = TextView(context).apply {
        text = "✕"
        contentDescription = "Close"
        textSize = 19f
        gravity = Gravity.CENTER
        setTextColor(palette.primaryText)
        background = context.roundedBackground(palette.raised, palette.border, UiRadius.CONTROL)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        setOnClickListener { onClose() }
    }

    private fun panelFooter(
        actionLabel: String?,
        onAction: (() -> Unit)?,
        onClose: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(16))

        addView(
            Button(context).apply {
                text = "Close"
                applySecondaryStyle(palette)
                setOnClickListener { onClose() }
            }
        )

        if (actionLabel != null && onAction != null) {
            addView(
                Button(context).apply {
                    text = actionLabel
                    applyPrimaryStyle(palette)
                    setOnClickListener { onAction() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = context.dp(10) }
            )
        }
    }

    // Confirmation dialog -----------------------------------------------------

    /** Asks before an action that can't be undone. A plain dialog like [showPanel], not a themed AlertDialog. */
    fun confirm(
        title: String,
        message: String? = null,
        confirmLabel: String = "Delete",
        /** Red for actions that remove or stop something; the accent for going ahead (import, resume). */
        destructive: Boolean = true,
        onConfirm: () -> Unit
    ): Dialog {
        val dialog = Dialog(context, R.style.Theme_SeaBathy_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
            setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(
            TextView(context).apply {
                text = title
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
            }
        )

        if (message != null) {
            card.addView(
                TextView(context).apply {
                    text = message
                    textSize = 14f
                    setTextColor(palette.secondaryText)
                    setPadding(0, context.dp(10), 0, 0)
                }
            )
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(20), 0, 0)
        }

        buttonRow.addView(
            Button(context).apply {
                text = "Cancel"
                applySecondaryStyle(palette)
                setOnClickListener { dialog.dismiss() }
            }
        )

        buttonRow.addView(
            Button(context).apply {
                text = confirmLabel
                isAllCaps = false
                if (destructive) {
                    setTextColor(Color.WHITE)
                    background = context.roundedBackground(palette.destructive, null, UiRadius.CONTROL)
                } else {
                    applyPrimaryStyle(palette)
                }
                minHeight = context.dp(44)
                setPadding(context.dp(20), 0, context.dp(20), 0)
                setOnClickListener {
                    dialog.dismiss()
                    onConfirm()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = context.dp(10) }
        )

        card.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = DIM_AMOUNT
                windowAnimations = 0
            }
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * PANEL_WIDTH_FRACTION).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.show()
        return dialog
    }

    private companion object {
        const val DIM_AMOUNT = 0.72f
        const val PANEL_WIDTH_FRACTION = 0.96f
        const val OVER_MAP_HEIGHT_FRACTION = 0.58f
    }
}
