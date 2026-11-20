package au.opendepth.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import au.opendepth.app.map.OverlayLayerController
import au.opendepth.app.model.LayerKind
import au.opendepth.app.model.OverlayLayer
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

/**
 * Renders the overlay stack into the side panel.
 *
 * Rows are listed top-of-stack first, matching what is drawn on the map, and
 * every layer kind gets the same visibility, opacity and ordering controls.
 */
class LayerPanelBinder(
    private val context: Context,
    private val container: LinearLayout,
    private val controller: OverlayLayerController,
    private val panels: PanelDialogs,
    private val onEditDepthColours: (OverlayLayer) -> Unit,
    private val onOpen3d: ((OverlayLayer) -> Unit)? = null,
    /** Contour lines for a depth layer: on or off, and how far apart. */
    private val onEditContours: ((OverlayLayer) -> Unit)? = null,
    /** Tapping a layer's name goes to it on the map. */
    private val onShowLayer: ((OverlayLayer) -> Unit)? = null
) {
    private val palette = AppTheme.palette

    fun rebuild() {
        container.removeAllViews()

        val layers = controller.stackTopFirst
        val unavailable = controller.unavailableLayers
        if (layers.isEmpty() && unavailable.isEmpty()) {
            container.addView(emptyState())
            return
        }

        layers.forEach { layer -> container.addView(layerRow(layer), rowParams()) }
        // Listed last: they sit below the loaded layers in the saved stack.
        unavailable.forEach { layer -> container.addView(unavailableRow(layer), rowParams()) }
    }

    /** A saved layer that failed to load this session (see OverlayLayerController.unavailableLayers). */
    private fun unavailableRow(layer: OverlayLayer): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.roundedBackground(palette.surface, palette.border, UiRadius.ROW)
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        tag = layer.id

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = layer.name
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(palette.secondaryText)
                    }
                )
                addView(
                    TextView(context).apply {
                        text = "Couldn't load — will try again next launch"
                        textSize = 10f
                        setTextColor(palette.secondaryText)
                    }
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        addView(
            chip("REMOVE", "Remove ${layer.name}", destructive = true) {
                panels.confirm(
                    title = "Remove ${layer.name}?",
                    message = "Remove this layer from the map? Your original file is not deleted.",
                    confirmLabel = "Remove"
                ) { controller.remove(layer.id) }
            }
        )
    }

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = context.dp(8) }

    private fun emptyState(): View = TextView(context).apply {
        text = "No map layers loaded yet"
        textSize = 12f
        setTextColor(palette.secondaryText)
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
    }

    private fun layerRow(layer: OverlayLayer): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.roundedBackground(palette.surface, palette.border, UiRadius.ROW)
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(12))
        tag = layer.id

        // Shared with the slider so the "NN%" in it tracks the thumb live;
        // nothing else rebuilds this row while a drag is in progress.
        val subtitle = TextView(context).apply {
            text = subtitleFor(layer)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(palette.secondaryText)
        }

        addView(titleRow(layer, subtitle))
        // Explicit MATCH_PARENT: a bare SeekBar added with no LayoutParams
        // gets LinearLayout's default WRAP_CONTENT width — its narrow
        // intrinsic width — so most of a drag across the row would land
        // outside the actual track and the thumb would appear to stick.
        addView(
            opacitySlider(layer, subtitle),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        addView(controlRow(layer))
    }

    private fun titleRow(layer: OverlayLayer, subtitle: TextView): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = layer.name
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(palette.primaryText)
                    }
                )
                addView(subtitle)
                onShowLayer?.let { show ->
                    contentDescription = "Go to ${layer.name} on the map"
                    setOnClickListener { show(layer) }
                }
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        addView(
            SwitchMaterial(context).apply {
                isChecked = layer.visible
                contentDescription = "Show ${layer.name}"
                setOnCheckedChangeListener { _, checked ->
                    // A hidden survey's file is closed: it takes a moment to open before it shows.
                    if (checked && controller.isAsleep(layer.id)) {
                        android.widget.Toast.makeText(context, "Opening ${layer.name}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    controller.setVisible(layer.id, checked)
                }
            }
        )
    }

    private fun opacitySlider(layer: OverlayLayer, subtitle: TextView): View = SeekBar(context).apply {
        max = OPACITY_STEPS
        progress = (layer.sanitisedOpacity * OPACITY_STEPS).roundToInt()
        contentDescription = "Opacity for ${layer.name}"
        progressTintList = ColorStateList.valueOf(palette.accent)
        thumbTintList = ColorStateList.valueOf(palette.accent)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val opacity = progress.toFloat() / OPACITY_STEPS
                    controller.setOpacity(layer.id, opacity, persistNow = false)
                    subtitle.text = subtitleFor(layer, opacity)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            // Only write to storage once the drag finishes, not per pixel.
            override fun onStopTrackingTouch(bar: SeekBar) {
                controller.setOpacity(
                    layer.id,
                    bar.progress.toFloat() / OPACITY_STEPS,
                    persistNow = true
                )
            }
        })
    }

    /**
     * Ordering and remove on one row, depth tools on a second: all six chips
     * on one row need ~310dp, more than the 300dp side panel, and pushed DEL
     * off the edge of the card.
     */
    private fun controlRow(layer: OverlayLayer): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            // Up and down only when there's somewhere to go: none with one layer, and the top
            // layer's ▲ and the bottom layer's ▼ are dimmed.
            val stack = controller.stackTopFirst
            if (stack.size > 1) {
                val index = stack.indexOfFirst { it.id == layer.id }
                addView(chip("▲", "Move ${layer.name} up") { controller.move(layer.id, 1) }.apply { setUsable(index > 0) })
                addView(chip("▼", "Move ${layer.name} down") { controller.move(layer.id, -1) }.apply { setUsable(index < stack.size - 1) })
            }

            addView(
                View(context),
                LinearLayout.LayoutParams(0, 1, 1f)
            )

            addView(chip("RENAME", "Rename ${layer.name}") { renameLayer(layer) })

            addView(
                chip("REMOVE", "Remove ${layer.name}", destructive = true) {
                    panels.confirm(
                        title = "Remove ${layer.name}?",
                        message = "Remove this layer from the map? Your original file is not deleted.",
                        confirmLabel = "Remove"
                    ) { controller.remove(layer.id) }
                }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 }
            )
        })

        // Depth layers: their own tools on a second row (a hidden one too, though its file is closed).
        if (controller.isDepthLayer(layer)) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(6), 0, 0)
                addView(wideChip(chip("COLOURS", "Depth colours for ${layer.name}") {
                    onEditDepthColours(layer)
                }, last = onOpen3d == null && onEditContours == null))
                onEditContours?.let { edit ->
                    addView(wideChip(chip("CONTOURS", "Contour lines for ${layer.name}") { edit(layer) }, last = onOpen3d == null))
                }
                onOpen3d?.let { open ->
                    addView(wideChip(chip("3D VIEW", "Show ${layer.name} in 3D") { open(layer) }, last = true))
                }
            })
        }
    }

    /** A chip that can't do anything right now: dimmed and not tappable. */
    private fun TextView.setUsable(usable: Boolean) {
        isEnabled = usable
        alpha = if (usable) 1f else 0.35f
    }

    /**
     * Gives a layer a name of the user's own. Only the name shown in the app changes: the file
     * keeps its name, so nothing on the phone's storage is touched.
     */
    private fun renameLayer(layer: OverlayLayer) {
        val body = panels.body()
        val input = panels.input {
            setText(layer.name)
            setSingleLine(true)
            selectAll()
            hint = "Layer name"
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_NAME_LENGTH))
        }
        panels.addRow(body, input)
        lateinit var dialog: android.app.Dialog
        dialog = panels.showPanel("Rename layer", body, actionLabel = "Save", onAction = {
            val name = input.text.toString().trim()
            if (name.isNotEmpty() && name != layer.name) controller.rename(layer.id, name)
            dialog.dismiss()
        })
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** Stretches a depth-tool chip to share its row equally, for a bigger touch target. */
    private fun wideChip(view: TextView, last: Boolean = false): TextView = view.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = if (last) 0 else context.dp(5)
        }
    }

    private fun chip(
        label: String,
        description: String,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        contentDescription = description
        minWidth = context.dp(38)
        minHeight = context.dp(28)
        setTextColor(if (destructive) palette.destructive else palette.primaryText)
        background = context.roundedBackground(palette.raised, palette.border, 8)
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = context.dp(5) }
        setOnClickListener { onClick() }
    }

    private fun subtitleFor(
        layer: OverlayLayer,
        opacityFraction: Float = layer.sanitisedOpacity
    ): String {
        val opacity = formatOpacity(opacityFraction)
        return when (layer.kind) {
            LayerKind.MBTILES -> "MBTiles • $opacity"

            LayerKind.GEOTIFF -> {
                val ramp = layer.ramp
                if (controller.isDepthLayer(layer) && ramp != null) {
                    "Depth GeoTIFF • ${ramp.shallowMetres.roundToInt()} to " +
                        "${ramp.deepMetres.roundToInt()} m • $opacity"
                } else {
                    "Image GeoTIFF • $opacity"
                }
            }

            LayerKind.XYZ -> "Online tiles • $opacity"
        }
    }

    internal companion object {
        const val OPACITY_STEPS = 100
        const val MAX_NAME_LENGTH = 60

        fun formatOpacity(fraction: Float): String =
            "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"
    }
}
