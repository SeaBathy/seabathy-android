package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.tiles.WebMercatorTiles
import java.util.Locale

/**
 * The download screen for the area on screen: pick zooms, then "Assess"
 * (nothing is fetched before that), then "Download". Changing the zooms
 * after assessing turns Download off until assessed again, so the download
 * always matches the estimate.
 */
class DownloadZoneDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    fun show(
        sourceName: String,
        bounds: GeoBounds,
        defaultMinZoom: Int,
        defaultMaxZoom: Int,
        areaNote: String = "The area on screen. To change it, close this, move the map and open it again.",
        onAssess: (zoomRange: IntRange, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) -> Unit,
        onConfirm: (zoomRange: IntRange) -> Unit
    ) {
        val body = panels.body()
        lateinit var dialog: Dialog

        body.addView(panels.sectionHeader("AREA"))
        body.addView(
            panels.caption(
                "The area on screen. To change it, close this, move the map and open it again.\n\n" +
                    "$sourceName\n" +
                    "West ${formatCoord(bounds.west)}   East ${formatCoord(bounds.east)}\n" +
                    "South ${formatCoord(bounds.south)}   North ${formatCoord(bounds.north)}",
                topPaddingDp = 0
            )
        )

        body.addView(panels.sectionHeader("ZOOM RANGE"))
        body.addView(
            panels.caption(
                "A higher max zoom has more detail, but many more tiles.",
                topPaddingDp = 0
            )
        )
        val minZoomInput = zoomInput("Min zoom", defaultMinZoom)
        val maxZoomInput = zoomInput("Max zoom", defaultMaxZoom)
        val zoomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(minZoomInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                maxZoomInput,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(8)
                }
            )
        }
        body.addView(zoomRow)

        body.addView(panels.sectionHeader("SIZE ESTIMATE"))
        val statusLabel = TextView(context).apply {
            textSize = 13f
            setTextColor(palette.secondaryText)
            text = "Tap Assess to see how big the download is. Nothing is downloaded yet."
            setPadding(0, 0, 0, context.dp(12))
        }
        body.addView(statusLabel)

        lateinit var confirmButton: Button

        // The range the shown estimate is for: Download only ever starts this range.
        var assessedRange: IntRange? = null

        fun resetToUnassessed() {
            assessedRange = null
            confirmButton.isEnabled = false
            statusLabel.setTextColor(palette.secondaryText)
            statusLabel.text = "Zoom range changed — tap Assess again."
        }

        val zoomChangedWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = resetToUnassessed()
        }
        minZoomInput.addTextChangedListener(zoomChangedWatcher)
        maxZoomInput.addTextChangedListener(zoomChangedWatcher)

        val assessButton = Button(context).apply {
            text = "Assess"
            applySecondaryStyle(palette)
            setOnClickListener {
                val zoomRange = readZoomRange(minZoomInput.text.toString(), maxZoomInput.text.toString())
                if (zoomRange == null) {
                    statusLabel.setTextColor(palette.destructive)
                    statusLabel.text = "Zooms run 0–${WebMercatorTiles.MAX_SANE_ZOOM}, min no higher than max."
                    return@setOnClickListener
                }

                isEnabled = false
                text = "Assessing…"
                statusLabel.setTextColor(palette.secondaryText)
                statusLabel.text = "Checking a few tiles…"
                onAssess(
                    zoomRange,
                    { message ->
                        isEnabled = true
                        // The fields changed while sampling: this estimate is for a range no longer shown.
                        if (readZoomRange(minZoomInput.text.toString(), maxZoomInput.text.toString()) != zoomRange) {
                            text = "Assess"
                            return@onAssess
                        }
                        assessedRange = zoomRange
                        text = "Re-assess"
                        statusLabel.setTextColor(palette.secondaryText)
                        statusLabel.text = message
                        confirmButton.isEnabled = true
                    },
                    { message ->
                        isEnabled = true
                        text = "Assess"
                        statusLabel.setTextColor(palette.destructive)
                        statusLabel.text = message
                        confirmButton.isEnabled = false
                    }
                )
            }
        }

        // Looked tappable while disabled, so tapping it before Assess seemed to do nothing.
        confirmButton = object : AppCompatButton(context) {
            override fun setEnabled(enabled: Boolean) {
                super.setEnabled(enabled)
                alpha = if (enabled) 1f else DISABLED_ALPHA
            }
        }.apply {
            text = "Download"
            applyPrimaryStyle(palette)
            isEnabled = false
            setOnClickListener {
                val zoomRange = assessedRange
                    ?.takeIf { it == readZoomRange(minZoomInput.text.toString(), maxZoomInput.text.toString()) }
                    ?: return@setOnClickListener
                dialog.dismiss()
                onConfirm(zoomRange)
            }
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                assessButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                confirmButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(8)
                }
            )
        }
        body.addView(buttonRow)

        dialog = panels.showPanel("Download an offline map", body)
    }

    private fun zoomInput(hintText: String, defaultValue: Int) = panels.input {
        hint = hintText
        setText(defaultValue.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
    }

    private fun readZoomRange(minText: String, maxText: String): IntRange? {
        val min = minText.toIntOrNull() ?: return null
        val max = maxText.toIntOrNull() ?: return null
        if (min < 0 || max > WebMercatorTiles.MAX_SANE_ZOOM || min > max) return null
        return min..max
    }

    private fun formatCoord(value: Double): String = String.format(Locale.US, "%.4f", value)
}

/** How a disabled Download button is shown. */
private const val DISABLED_ALPHA = 0.4f
