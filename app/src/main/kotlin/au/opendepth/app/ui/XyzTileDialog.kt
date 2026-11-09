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
 * The "Online map layers" screen: ready-made tile layers, or your own tile
 * link as a layer or as the basemap.
 */
class XyzTileDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    data class Callbacks(
        val currentBasemapUrl: () -> String,
        val currentBasemapAttribution: () -> String,
        val onAddPreset: (XyzTilePreset) -> Unit,
        val onAddOverlay: (name: String, url: String, attribution: String) -> Unit,
        val onSaveBasemap: (url: String, attribution: String) -> Unit,
        val onClearBasemap: () -> Unit
    )

    fun show(callbacks: Callbacks) {
        val body = panels.body()
        lateinit var dialog: Dialog

        body.addView(panels.sectionHeader("ADD A LAYER"))
        body.addView(
            panels.caption(
                "Adds it to your layers, with the same show/hide and opacity as your own maps.",
                topPaddingDp = 0
            )
        )

        XyzTilePresets.ALL.forEach { preset ->
            panels.addRow(
                body,
                tilePresetRow(context, palette, preset, actionLabel = "ADD") {
                    dialog.dismiss()
                    callbacks.onAddPreset(preset)
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

        body.addView(fieldLabel("Tile URL template"))
        val urlInput = panels.input {
            hint = "https://example.com/tiles/{z}/{x}/{y}.png"
            setText(callbacks.currentBasemapUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
            typeface = Typeface.MONOSPACE
        }
        panels.addRow(body, urlInput)

        body.addView(fieldLabel("Attribution"))
        val attributionInput = panels.input {
            hint = "Provider credit shown on the map"
            setText(callbacks.currentBasemapAttribution())
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

        fun validated(): String? {
            val url = urlInput.text.toString().trim()
            val error = XyzTilePresets.validationError(url)
            if (error != null) {
                errorLabel.text = error
                errorLabel.visibility = View.VISIBLE
                return null
            }
            errorLabel.visibility = View.GONE
            return url
        }

        panels.addRow(
            body,
            actionButton("Add as a layer", primary = true) {
                val url = validated() ?: return@actionButton
                dialog.dismiss()
                callbacks.onAddOverlay(
                    "Custom tiles",
                    url,
                    attributionInput.text.toString().trim()
                )
            }
        )

        panels.addRow(
            body,
            actionButton("Use as the basemap", primary = false) {
                val url = validated() ?: return@actionButton
                dialog.dismiss()
                callbacks.onSaveBasemap(url, attributionInput.text.toString().trim())
            }
        )

        panels.addRow(
            body,
            actionButton("Clear the custom basemap", primary = false, destructive = true) {
                panels.confirm(
                    title = "Clear the custom basemap?",
                    message = "Its link and credit are removed.",
                    confirmLabel = "Clear"
                ) {
                    urlInput.setText("")
                    attributionInput.setText("")
                    errorLabel.visibility = View.GONE
                    callbacks.onClearBasemap()
                }
            }
        )

        dialog = panels.showPanel("Online map layers", body)
    }

    private fun fieldLabel(text: String): TextView = panels.caption(text, topPaddingDp = 10)

    private fun actionButton(
        label: String,
        primary: Boolean,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        if (primary) applyPrimaryStyle(palette) else applySecondaryStyle(palette)
        if (destructive) setTextColor(palette.destructive)
        setOnClickListener { onClick() }
    }
}
