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
import android.widget.TextView
import au.opendepth.app.R
import au.opendepth.app.model.Waypoint
import au.opendepth.app.util.CoordinateFormatter

/** Edits a waypoint's name, notes and position. A plain dialog like [PanelDialogs.showPanel], not a themed AlertDialog. */
class WaypointEditDialog(
    private val context: Context,
    private val panels: PanelDialogs,
    private val coordinateEditor: CoordinateEditorDialog
) {
    private val palette = AppTheme.palette

    /**
     * [isNameTaken] reports whether another waypoint already uses a name, so
     * a rename is held to the same uniqueness rule as creating one.
     */
    fun show(
        waypoint: Waypoint,
        isNameTaken: (String) -> Boolean,
        onChanged: () -> Unit,
        onJumpTo: (Waypoint) -> Unit,
        onDelete: (Waypoint) -> Unit
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
            setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
        }
        root.addView(card)

        card.addView(
            TextView(context).apply {
                text = "Waypoint"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
            }
        )

        val nameInput = panels.input {
            setText(waypoint.name)
            hint = "Name"
            setSingleLine(true)
            selectAll()
        }
        card.addView(
            nameInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(14) }
        )

        val coordinates = TextView(context).apply {
            text = coordinateLabel(waypoint)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.accent)
            setPadding(0, context.dp(10), 0, context.dp(10))
            setOnClickListener {
                coordinateEditor.show(
                    title = "Waypoint coordinates",
                    initialLat = waypoint.lat,
                    initialLng = waypoint.lng,
                    positiveLabel = "Save"
                ) { latitude, longitude ->
                    waypoint.lat = latitude
                    waypoint.lng = longitude
                    text = coordinateLabel(waypoint)
                    onChanged()
                }
            }
        }
        card.addView(coordinates)

        val descriptionInput = panels.input {
            setText(waypoint.desc.orEmpty())
            hint = "Description / notes"
            minLines = 2
            maxLines = 4
        }
        card.addView(descriptionInput)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(18), 0, 0)
        }

        buttonRow.addView(
            Button(context).apply {
                text = "Delete"
                isAllCaps = false
                setTextColor(palette.destructive)
                background = ColorDrawable(Color.TRANSPARENT)
                setOnClickListener {
                    panels.confirm(
                        title = "Delete ${waypoint.name}?",
                        confirmLabel = "Delete"
                    ) {
                        dialog.dismiss()
                        onDelete(waypoint)
                    }
                }
            }
        )

        buttonRow.addView(
            View(context),
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        buttonRow.addView(
            Button(context).apply {
                text = "Jump"
                applySecondaryStyle(palette)
                setOnClickListener {
                    dialog.dismiss()
                    onJumpTo(waypoint)
                }
            },
            spacedParams()
        )

        buttonRow.addView(
            Button(context).apply {
                text = "Save"
                applyPrimaryStyle(palette)
                setOnClickListener {
                    val name = nameInput.text.toString().trim().ifBlank { waypoint.name }
                    if (!name.equals(waypoint.name, ignoreCase = true) && isNameTaken(name)) {
                        nameInput.error = "A waypoint called \"$name\" already exists"
                        return@setOnClickListener
                    }
                    waypoint.name = name
                    waypoint.desc = descriptionInput.text.toString().trim().takeIf { it.isNotBlank() }
                    onChanged()
                    dialog.dismiss()
                }
            },
            spacedParams()
        )

        card.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.apply {
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

    private fun spacedParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = context.dp(8) }

    private fun coordinateLabel(waypoint: Waypoint): String =
        "${CoordinateFormatter.formatDdm(waypoint.lat, waypoint.lng)}   •   EDIT COORDINATES"

    private companion object {
        const val DIM_AMOUNT = 0.72f
        const val WIDTH_FRACTION = 0.96f
    }
}
