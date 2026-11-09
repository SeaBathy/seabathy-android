package au.opendepth.app.ui

import androidx.core.widget.doAfterTextChanged
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import au.opendepth.app.geotiff.DepthGrid
import au.opendepth.app.geotiff.DepthGridRenderer
import au.opendepth.app.geotiff.DepthPalette
import au.opendepth.app.model.DepthRampSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colours and depth range for a GeoTIFF depth layer. Each change goes to
 * [onRampChanged], which recolours the layer; the file is never reread.
 */
class DepthRampDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette
    private val handler = Handler(Looper.getMainLooper())

    fun show(
        layerName: String,
        dataShallowestMetres: Double,
        dataDeepestMetres: Double,
        initial: DepthRampSettings,
        hillshadeAvailable: Boolean = false,
        /** How the layer's depths are spread, when known: for Fit, and for saying how much a range shows. */
        histogram: au.opendepth.app.geotiff.DepthHistogram? = null,
        onRampChanged: (DepthRampSettings) -> Unit
    ) {
        var ramp = initial.normalised()

        // Allow a little headroom beyond the data so the user can deliberately
        // clip the ramp as well as widen it.
        val dataShallow = ceil(dataShallowestMetres)
        val dataDeep = floor(dataDeepestMetres)
        val padding = max(SLIDER_PADDING_METRES, (dataShallow - dataDeep) * 0.25)
        val upperBound = dataShallow + padding
        val lowerBound = dataDeep - padding

        val body = panels.body()

        body.addView(panels.sectionHeader("LAYER"))
        body.addView(
            panels.caption(
                "$layerName\nData range ${formatDepth(dataShallowestMetres)} to " +
                    "${formatDepth(dataDeepestMetres)}. Depths below sea level are negative " +
                    "(-10 = 10 m deep); land above it is +.",
                topPaddingDp = 0
            )
        )

        body.addView(panels.sectionHeader("PALETTE PREVIEW"))
        val legend = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background =
                context.roundedBackground(palette.surface, palette.border, UiRadius.CONTROL)
        }
        body.addView(
            legend,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(LEGEND_HEIGHT_DP)
            ).apply { bottomMargin = context.dp(4) }
        )
        val legendScale = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val shallowEndLabel = endLabel(Gravity.START)
        val deepEndLabel = endLabel(Gravity.END)
        legendScale.addView(shallowEndLabel, weightedParams())
        legendScale.addView(deepEndLabel, weightedParams())
        panels.addRow(body, legendScale)

        body.addView(panels.sectionHeader("DEPTH RANGE"))

        val shallowValue = valueLabel()
        val shallowSlider = depthSlider()
        val deepValue = valueLabel()
        val deepSlider = depthSlider()

        // How much of the layer the range shows: a range that misses the
        // survey makes it vanish, which looks like the layer failed to load.
        val coverage = panels.caption("", topPaddingDp = 0).apply { visibility = if (histogram == null) View.GONE else View.VISIBLE }
        body.addView(coverage)

        body.addView(panels.caption("Shallow end", topPaddingDp = 4))
        panels.addRow(body, shallowValue, bottomMarginDp = 2)
        panels.addRow(body, shallowSlider)

        body.addView(panels.caption("Deep end", topPaddingDp = 4))
        panels.addRow(body, deepValue, bottomMarginDp = 2)
        panels.addRow(body, deepSlider)

        body.addView(panels.sectionHeader("EXACT VALUES"))
        val shallowInput = numberInput("Shallow, e.g. -5")
        val deepInput = numberInput("Deep, e.g. -15")
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shallowInput, weightedParams())
            addView(
                deepInput,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = context.dp(8) }
            )
        }
        panels.addRow(body, inputRow)

        var hillshadeValue: TextView? = null
        var hillshadeSlider: SeekBar? = null
        var hillshadeInput: EditText? = null
        if (hillshadeAvailable) {
            body.addView(panels.sectionHeader("HILLSHADE"))
            body.addView(
                panels.caption(
                    "How strongly slopes and channels are shaded. 0 is flat.",
                    topPaddingDp = 0
                )
            )
            val value = valueLabel()
            val slider = depthSlider()
            hillshadeValue = value
            hillshadeSlider = slider
            panels.addRow(body, value, bottomMarginDp = 2)
            panels.addRow(body, slider)

            val input = numberInput("Exaggeration")
            hillshadeInput = input
            panels.addRow(body, input)
        }

        lateinit var paletteRow: TextView
        lateinit var invertRow: TextView
        lateinit var outsideRow: TextView

        // Guards the feedback loop between sliders, text fields and state.
        var updating = false

        /**
         * Repainting a full-resolution grid costs a few megabytes of pixel
         * writes, so slider drags coalesce into one repaint per frame budget
         * while discrete choices apply immediately.
         */
        fun publish(settings: DepthRampSettings, coalesce: Boolean) {
            handler.removeCallbacksAndMessages(RECOLOUR_TOKEN)
            if (coalesce) {
                handler.postAtTime(
                    { onRampChanged(settings) },
                    RECOLOUR_TOKEN,
                    SystemClock.uptimeMillis() + RECOLOUR_DELAY_MS
                )
            } else {
                onRampChanged(settings)
            }
        }

        fun apply(
            next: DepthRampSettings,
            syncSliders: Boolean,
            syncInputs: Boolean,
            coalesce: Boolean = false
        ) {
            ramp = next.normalised()
            updating = true

            legend.setImageBitmap(
                DepthGridRenderer.renderLegend(ramp, LEGEND_WIDTH_PX, LEGEND_HEIGHT_PX)
            )
            shallowEndLabel.text = formatDepth(ramp.shallowMetres)
            deepEndLabel.text = formatDepth(ramp.deepMetres)
            shallowValue.text = formatDepth(ramp.shallowMetres)
            deepValue.text = formatDepth(ramp.deepMetres)
            paletteRow.text =
                "Colour palette\n${DepthPalette.byId(ramp.paletteId).label}"
            invertRow.text =
                "Reverse ramp direction\n${if (ramp.invert) "On" else "Off"}"
            outsideRow.text = "Outside the range\n${outsideLabel(ramp.showOutside)}"
            if (histogram != null) {
                val share = histogram.fractionWithin(ramp.shallowMetres, ramp.deepMetres)
                val percent = (share * 100).let { if (it > 0 && it < 1) "under 1" else "%.0f".format(java.util.Locale.US, it) }
                coverage.text = when {
                    ramp.showOutside -> "$percent% of this layer is in this range; the rest is shown in the end colours."
                    share < NEARLY_NONE -> "Only $percent% of this layer is in this range, so it will look almost empty. Depths outside are hidden."
                    else -> "Shows $percent% of this layer. Depths outside the range are hidden."
                }
                coverage.setTextColor(if (!ramp.showOutside && share < NEARLY_NONE) palette.destructive else palette.secondaryText)
            }

            if (syncSliders) {
                shallowSlider.progress =
                    toProgress(ramp.shallowMetres, lowerBound, upperBound)
                deepSlider.progress =
                    toProgress(ramp.deepMetres, lowerBound, upperBound)
                hillshadeSlider?.progress = toProgress(
                    ramp.hillshadeExaggeration,
                    DepthRampSettings.MIN_HILLSHADE_EXAGGERATION,
                    DepthRampSettings.MAX_HILLSHADE_EXAGGERATION
                )
            }
            if (syncInputs) {
                shallowInput.setText(formatPlain(ramp.shallowMetres))
                deepInput.setText(formatPlain(ramp.deepMetres))
                hillshadeInput?.setText(formatPlain(ramp.hillshadeExaggeration))
            }
            hillshadeValue?.text = formatExaggeration(ramp.hillshadeExaggeration)

            updating = false
            publish(ramp, coalesce)
        }

        paletteRow = panels.row("Colour palette", DepthPalette.byId(ramp.paletteId).label) {
            panels.showChoicePanel(
                title = "Colour palette",
                labels = DepthPalette.ALL.map { it.label },
                selectedIndex = DepthPalette.ALL.indexOfFirst { it.id == ramp.paletteId }
            ) { index ->
                apply(
                    ramp.copy(paletteId = DepthPalette.ALL[index].id),
                    syncSliders = false,
                    syncInputs = false
                )
            }
        }

        invertRow = panels.row(
            "Reverse ramp direction",
            if (ramp.invert) "On" else "Off"
        ) {
            apply(ramp.copy(invert = !ramp.invert), syncSliders = false, syncInputs = false)
        }

        outsideRow = panels.row("Outside the range", outsideLabel(ramp.showOutside)) {
            apply(ramp.copy(showOutside = !ramp.showOutside), syncSliders = false, syncInputs = false)
        }

        body.addView(panels.sectionHeader("COLOURS"))
        panels.addRow(body, paletteRow)
        panels.addRow(body, invertRow)
        panels.addRow(body, outsideRow)

        body.addView(panels.sectionHeader("PRESETS"))
        PRESETS.forEach { preset ->
            panels.addRow(
                body,
                presetButton(preset.label) {
                    val next = preset.build(dataShallowestMetres, dataDeepestMetres, histogram)
                    apply(
                        ramp.copy(
                            shallowMetres = next.first,
                            deepMetres = next.second
                        ),
                        syncSliders = true,
                        syncInputs = true
                    )
                }
            )
        }

        shallowSlider.setOnSeekBarChangeListener(
            sliderListener { progress, dragging ->
                if (updating) return@sliderListener
                // The ends never meet or cross: an empty band hid the whole layer.
                val shallow = max(fromProgress(progress, lowerBound, upperBound), ramp.deepMetres + MIN_BAND_METRES)
                apply(
                    ramp.copy(shallowMetres = shallow),
                    syncSliders = shallow != fromProgress(progress, lowerBound, upperBound),
                    syncInputs = true,
                    coalesce = dragging
                )
            }
        )

        deepSlider.setOnSeekBarChangeListener(
            sliderListener { progress, dragging ->
                if (updating) return@sliderListener
                val deep = min(fromProgress(progress, lowerBound, upperBound), ramp.shallowMetres - MIN_BAND_METRES)
                apply(
                    ramp.copy(deepMetres = deep),
                    syncSliders = deep != fromProgress(progress, lowerBound, upperBound),
                    syncInputs = true,
                    coalesce = dragging
                )
            }
        )

        shallowInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !updating) {
                parseDepth(shallowInput.text.toString())?.let { entered ->
                    apply(
                        ramp.copy(shallowMetres = entered),
                        syncSliders = true,
                        syncInputs = true
                    )
                }
            }
        }
        deepInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !updating) {
                parseDepth(deepInput.text.toString())?.let { entered ->
                    apply(
                        ramp.copy(deepMetres = entered),
                        syncSliders = true,
                        syncInputs = true
                    )
                }
            }
        }

        // Focus loss alone is not enough: closing the panel straight after
        // typing (without pressing Done) never moves focus, and the typed
        // value was silently dropped. Apply each parseable edit as it is typed
        // instead; the focus listeners above still tidy the fields afterwards.
        shallowInput.doAfterTextChanged { text ->
            if (updating || !shallowInput.hasFocus()) return@doAfterTextChanged
            text?.toString()?.let(::parseDepth)?.let { entered ->
                apply(ramp.copy(shallowMetres = entered), syncSliders = true, syncInputs = false, coalesce = true)
            }
        }
        deepInput.doAfterTextChanged { text ->
            if (updating || !deepInput.hasFocus()) return@doAfterTextChanged
            text?.toString()?.let(::parseDepth)?.let { entered ->
                apply(ramp.copy(deepMetres = entered), syncSliders = true, syncInputs = false, coalesce = true)
            }
        }

        hillshadeSlider?.setOnSeekBarChangeListener(
            sliderListener { progress, dragging ->
                if (updating) return@sliderListener
                apply(
                    ramp.copy(
                        hillshadeExaggeration = fromProgress(
                            progress,
                            DepthRampSettings.MIN_HILLSHADE_EXAGGERATION,
                            DepthRampSettings.MAX_HILLSHADE_EXAGGERATION
                        )
                    ),
                    syncSliders = false,
                    syncInputs = true,
                    coalesce = dragging
                )
            }
        )

        hillshadeInput?.let { input ->
            input.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !updating) {
                    input.text.toString().toDoubleOrNull()?.let { entered ->
                        apply(
                            ramp.copy(
                                hillshadeExaggeration = DepthRampSettings.coerceHillshadeExaggeration(entered)
                            ),
                            syncSliders = true,
                            syncInputs = true
                        )
                    }
                }
            }
        }

        apply(ramp, syncSliders = true, syncInputs = true)
        panels.showPanel("Depth colours", body, overMap = true)
    }

    // Controls --------------------------------------------------------------

    private fun depthSlider(): SeekBar = SeekBar(context).apply {
        max = SLIDER_STEPS
        progressTintList = ColorStateList.valueOf(palette.accent)
        thumbTintList = ColorStateList.valueOf(palette.accent)
    }

    private fun valueLabel(): TextView = TextView(context).apply {
        textSize = 15f
        typeface = Typeface.MONOSPACE
        setTextColor(palette.accent)
        setPadding(context.dp(2), 0, 0, 0)
    }

    private fun endLabel(align: Int): TextView = TextView(context).apply {
        textSize = 10f
        gravity = align
        setTextColor(palette.secondaryText)
    }

    private fun numberInput(hintText: String): EditText = panels.input {
        hint = hintText
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        typeface = Typeface.MONOSPACE
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { view, actionId, _ ->
            val done = actionId == EditorInfo.IME_ACTION_DONE
            // Pressing the keyboard's Done action only dismisses the IME by
            // default — it does not move focus. Clearing focus here is what
            // actually triggers the existing onFocusChangeListener below,
            // which is where the entered value gets applied.
            if (done) hideKeyboardAndClearFocus(view)
            done
        }
    }

    private fun hideKeyboardAndClearFocus(view: View) {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun presetButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            applySecondaryStyle(palette)
            setOnClickListener { onClick() }
        }

    private fun weightedParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    )

    /**
     * Reports slider changes along with whether the touch is still in progress,
     * so callers can coalesce mid-drag work and commit on release.
     */
    private inline fun sliderListener(
        crossinline onChange: (progress: Int, dragging: Boolean) -> Unit
    ): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        private var dragging = false

        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress, dragging)
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            dragging = true
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            dragging = false
            onChange(bar.progress, false)
        }
    }

    // Value mapping ---------------------------------------------------------

    private fun toProgress(value: Double, lower: Double, upper: Double): Int {
        if (upper <= lower) return 0
        return (((value - lower) / (upper - lower)) * SLIDER_STEPS)
            .roundToInt()
            .coerceIn(0, SLIDER_STEPS)
    }

    private fun fromProgress(progress: Int, lower: Double, upper: Double): Double {
        val fraction = progress.toDouble() / SLIDER_STEPS
        return roundToTenth(lower + fraction * (upper - lower))
    }

    private fun roundToTenth(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    /** "-12.5 m" for a depth below sea level, "+3.0 m" for land above it, as typed in the exact values. */
    private fun formatDepth(metres: Double): String = when {
        metres < 0.0 -> String.format(Locale.US, "%.1f m", metres)
        metres > 0.0 -> String.format(Locale.US, "+%.1f m", metres)
        else -> "0.0 m"
    }

    /** A typed level: negative below sea level, positive above it. */
    private fun parseDepth(text: String): Double? =
        text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun outsideLabel(show: Boolean) =
        if (show) "Shown in the end colours" else "Hidden — only the range is drawn"

    private fun formatExaggeration(value: Double): String =
        String.format(Locale.US, "%.1fx", value)

    private fun formatPlain(metres: Double): String =
        if (abs(metres - metres.roundToInt()) < 0.05) {
            metres.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", metres)
        }

    /** A named shallow/deep pair offered as a one-tap range. */
    private class RangePreset(
        val label: String,
        val build: (dataShallowest: Double, dataDeepest: Double, histogram: au.opendepth.app.geotiff.DepthHistogram?) -> Pair<Double, Double>
    )

    private companion object {
        val RECOLOUR_TOKEN = Any()
        const val RECOLOUR_DELAY_MS = 90L
        const val SLIDER_STEPS = 1000
        const val SLIDER_PADDING_METRES = 5.0
        const val MIN_BAND_METRES = 1.0
        /** Under this share of a layer in range, warn that it will look empty. */
        const val NEARLY_NONE = 0.05
        const val LEGEND_WIDTH_PX = 512
        const val LEGEND_HEIGHT_PX = 24
        const val LEGEND_HEIGHT_DP = 30

        val PRESETS = listOf(
            RangePreset("Fit to this layer's depths") { shallowest, deepest, histogram ->
                au.opendepth.app.map.OverlayLayerController.fitRange(shallowest, deepest, histogram)
            },
            RangePreset("Reef and shallows: -1 m to -15 m") { _, _, _ -> -1.0 to -15.0 },
            RangePreset("Inshore detail: -10 m to -30 m") { _, _, _ -> -10.0 to -30.0 },
            RangePreset("Coastal: -6 m to -100 m") { _, _, _ -> -6.0 to -100.0 },
            RangePreset("Offshore: -20 m to -500 m") { _, _, _ -> -20.0 to -500.0 }
        )
    }
}
