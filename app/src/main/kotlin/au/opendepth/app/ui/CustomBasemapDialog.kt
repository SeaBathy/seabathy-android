package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.TextView
import au.opendepth.app.settings.XyzTilePreset
import au.opendepth.app.settings.XyzTilePresets

/**
 * Shown when "Custom" is picked as the basemap: a ready-made tile source or
 * your own link, applied at once along with its credit.
 */
class CustomBasemapDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    data class Callbacks(
        val onPresetChosen: (XyzTilePreset) -> Unit,
        val onCustomLinkChosen: (url: String, attribution: String) -> Unit
    )

    /** [onDismissed] fires however the picker closes, including a plain cancel. */
    fun show(callbacks: Callbacks, onDismissed: () -> Unit = {}) {
        val body = panels.body()
        lateinit var dialog: Dialog

        body.addView(panels.sectionHeader("READY-MADE"))
        body.addView(
            panels.caption(
                "Its credit is shown on the map for you.",
                topPaddingDp = 0
            )
        )

        XyzTilePresets.ALL.forEach { preset ->
            panels.addRow(
                body,
                tilePresetRow(context, palette, preset, actionLabel = "USE") {
                    dialog.dismiss()
                    callbacks.onPresetChosen(preset)
                }
            )
        }

        body.addView(panels.sectionHeader("YOUR OWN TILE LINK"))
        body.addView(
            panels.caption(
                "A link with {z}, {x} and {y} in it. Check the provider's licence allows this.",
                topPaddingDp = 0
            )
        )

        body.addView(panels.caption("Tile URL template", topPaddingDp = 10))
        val urlInput = panels.input {
            hint = "https://example.com/tiles/{z}/{x}/{y}.png"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
            typeface = Typeface.MONOSPACE
        }
        panels.addRow(body, urlInput)

        body.addView(panels.caption("Attribution", topPaddingDp = 10))
        val attributionInput = panels.input {
            hint = "Provider credit shown on the map"
            setSingleLine(false)
            minLines = 2
        }
        panels.addRow(body, attributionInput)

        val errorLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.destructive)
            visibility = View.GONE
            setPadding(context.dp(2), 0, 0, context.dp(6))
        }
        body.addView(errorLabel)

        panels.addRow(
            body,
            Button(context).apply {
                text = "Use as the basemap"
                applyPrimaryStyle(palette)
                setOnClickListener {
                    val url = urlInput.text.toString().trim()
                    val error = XyzTilePresets.validationError(url)
                    if (error != null) {
                        errorLabel.text = error
                        errorLabel.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    callbacks.onCustomLinkChosen(url, attributionInput.text.toString().trim())
                }
            }
        )

        dialog = panels.showPanel("Custom basemap", body)
        dialog.setOnDismissListener { onDismissed() }
    }
}
