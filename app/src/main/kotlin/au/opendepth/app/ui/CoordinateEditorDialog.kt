package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import au.opendepth.app.R
import au.opendepth.app.util.CoordinateFormatter

/**
 * Coordinate entry that accepts decimal degrees, degrees with decimal minutes
 * or full degrees-minutes-seconds, and previews the parse as the user types.
 */
class CoordinateEditorDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    fun show(
        title: String,
        initialLat: Double,
        initialLng: Double,
        positiveLabel: String,
        showMoveButton: Boolean = false,
        onMoveMap: ((Double, Double) -> Unit)? = null,
        onPositive: (Double, Double) -> Unit
    ) {
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
            setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
        }
        root.addView(card)

        card.addView(header(title) { dialog.dismiss() })

        card.addView(panels.sectionHeader("FORMAT"))
        val formatSpinner = formatSpinner()
        card.addView(
            formatSpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(50)
            )
        )

        card.addView(panels.sectionHeader("COORDINATES"))
        val input = panels.input {
            setText(CoordinateFormatter.formatDdm(initialLat, initialLng))
            hint = "Paste latitude and longitude"
            minLines = 2
            maxLines = 4
            setSelectAllOnFocus(true)
            typeface = Typeface.MONOSPACE
        }
        card.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(panels.sectionHeader("PREVIEW"))
        val preview = TextView(context).apply {
            text = CoordinateFormatter.formatDdm(initialLat, initialLng)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.primaryText)
            background =
                context.roundedBackground(palette.surface, palette.border, UiRadius.ROW)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        card.addView(preview)

        formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val parsed = CoordinateFormatter.parsePair(input.text.toString())
                    ?: (initialLat to initialLng)
                input.setText(
                    CoordinateFormatter.formatPair(
                        parsed.first,
                        parsed.second,
                        CoordinateFormatter.MODES[position]
                    )
                )
                preview.text = CoordinateFormatter.formatDdm(parsed.first, parsed.second)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                value: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                value: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                val parsed = CoordinateFormatter.parsePair(value?.toString().orEmpty())
                preview.text = if (parsed == null) {
                    "Enter valid coordinates"
                } else {
                    CoordinateFormatter.formatDdm(parsed.first, parsed.second)
                }
                preview.setTextColor(
                    if (parsed == null) palette.destructive else palette.primaryText
                )
            }

            override fun afterTextChanged(value: Editable?) = Unit
        })

        fun parsedOrError(): Pair<Double, Double>? =
            CoordinateFormatter.parsePair(input.text.toString()).also { parsed ->
                if (parsed == null) input.error = "Use DD, DDM or DMS coordinates"
            }

        card.addView(
            actions(
                positiveLabel = positiveLabel,
                showMoveButton = showMoveButton,
                onCancel = { dialog.dismiss() },
                onMove = {
                    val parsed = parsedOrError() ?: return@actions
                    onMoveMap?.invoke(parsed.first, parsed.second)
                    dialog.dismiss()
                },
                onPositive = {
                    val parsed = parsedOrError() ?: return@actions
                    onPositive(parsed.first, parsed.second)
                    dialog.dismiss()
                }
            )
        )

        dialog.setContentView(root)
        dialog.window?.apply {
            // Transparent window, like every other custom Dialog in this app
            // (WaypointEditDialog, PanelDialogs' showPanel/confirm), so only
            // the rounded card shows against the dimmed map.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = DIM_AMOUNT
            }
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun header(title: String, onClose: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(palette.primaryText)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(panels.closeButton(onClose))
        }

    private fun formatSpinner(): Spinner = Spinner(context).apply {
        background = context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
        adapter = PaletteSpinnerAdapter(context, CoordinateFormatter.MODE_LABELS)
    }

    private inline fun actions(
        positiveLabel: String,
        showMoveButton: Boolean,
        crossinline onCancel: () -> Unit,
        crossinline onMove: () -> Unit,
        crossinline onPositive: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(18), 0, 0)

        addView(
            Button(context).apply {
                text = "Cancel"
                applySecondaryStyle(palette)
                setOnClickListener { onCancel() }
            }
        )

        if (showMoveButton) {
            addView(
                Button(context).apply {
                    text = "Move map"
                    applySecondaryStyle(palette)
                    background = context.roundedBackground(
                        palette.surface,
                        palette.border,
                        UiRadius.CONTROL
                    )
                    setOnClickListener { onMove() }
                },
                spacedParams()
            )
        }

        addView(
            Button(context).apply {
                text = positiveLabel
                applyPrimaryStyle(palette)
                setOnClickListener { onPositive() }
            },
            spacedParams()
        )
    }

    private fun spacedParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = context.dp(8) }

    private companion object {
        const val DIM_AMOUNT = 0.72f
        const val WIDTH_FRACTION = 0.96f
    }
}

/**
 * Spinner adapter that paints both the collapsed row and the dropdown with the
 * app palette. The platform adapter leaves them theme-coloured, which reads as
 * dark text on a dark panel.
 */
class PaletteSpinnerAdapter(
    context: Context,
    values: List<String>
) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {

    private val palette = AppTheme.palette

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        (super.getView(position, convertView, parent) as TextView).apply {
            setTextColor(palette.primaryText)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }

    override fun getDropDownView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View = (super.getDropDownView(position, convertView, parent) as TextView).apply {
        setTextColor(palette.primaryText)
        setBackgroundColor(palette.surface)
        setPadding(context.dp(16), context.dp(15), context.dp(16), context.dp(15))
    }
}
