package au.opendepth.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.MainActivity
import au.opendepth.app.map.DepthContours
import au.opendepth.app.model.ContourSettings
import au.opendepth.app.nav.DepthUnit
import au.opendepth.app.nav.NavSettingsRepository
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Contour lines for one depth layer: on or off, and how far apart, in
 * round numbers of the depth unit chosen in Boat & navigation. Each change
 * goes to the caller at once, and the panel leaves the chart in view, so the
 * lines can be seen while choosing.
 */
class ContourLinesDialog(
    private val context: Context,
    private val panels: PanelDialogs,
    /** The unit depths are shown in. Boat & navigation saves it as soon as it is changed, so the saved one is current. */
    private val depthUnit: () -> DepthUnit = {
        NavSettingsRepository(
            context.applicationContext.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        ).load().units.depth
    }
) {
    private val palette = AppTheme.palette

    fun show(layerName: String, initial: ContourSettings, onChanged: (ContourSettings) -> Unit) {
        var settings = initial
        val unit = depthUnit()
        val choices = DepthContours.choices(unit)
        val body = panels.body()

        body.addView(panels.sectionHeader("LAYER"))
        body.addView(panels.caption(layerName, topPaddingDp = 0))

        body.addView(panels.sectionHeader("CONTOUR LINES"))
        lateinit var everyRow: TextView
        panels.addRow(
            body,
            switchRow(TITLE, "Lines of equal depth, with the depth written on them", settings.on) { on ->
                settings = settings.copy(on = on)
                everyRow.visibility = if (on) View.VISIBLE else View.GONE
                onChanged(settings)
            }
        )
        everyRow = panels.row(EVERY, everyLabel(settings.everyMetres, unit)) {
            panels.showChoicePanel(
                title = EVERY,
                labels = listOf(AUTO_LABEL) + choices.map { DepthContours.spacingLabel(it, unit) },
                // Auto is first; a spacing picked in another unit shows as the nearest round number of this one.
                selectedIndex = choices.indexOf(DepthContours.pickedIn(settings.everyMetres, unit)) + 1
            ) { index ->
                settings = settings.copy(
                    everyMetres = if (index == 0) ContourSettings.AUTO else DepthContours.metresFor(choices[index - 1], unit)
                )
                everyRow.text = "$EVERY\n${everyLabel(settings.everyMetres, unit)}"
                onChanged(settings)
            }
        }
        // How far apart only matters once the lines are on.
        everyRow.visibility = if (settings.on) View.VISIBLE else View.GONE
        panels.addRow(body, everyRow)

        panels.showPanel(TITLE, body, overMap = true)
    }

    private fun everyLabel(everyMetres: Double, unit: DepthUnit): String {
        val picked = DepthContours.pickedIn(everyMetres, unit)
        return if (picked == DepthContours.AUTO) AUTO_LABEL else DepthContours.spacingLabel(picked, unit)
    }

    /** A row like [PanelDialogs.row], with a switch at its end instead of a value under its title. */
    private fun switchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
            setPadding(context.dp(16), context.dp(10), context.dp(12), context.dp(10))
            minimumHeight = context.dp(56)

            val switch = SwitchMaterial(context).apply {
                isChecked = checked
                contentDescription = title
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = title
                            textSize = 14f
                            setTextColor(palette.primaryText)
                        }
                    )
                    addView(
                        TextView(context).apply {
                            text = detail
                            textSize = 12f
                            setTextColor(palette.secondaryText)
                        }
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(switch)
            // The whole row is the target, not only the small switch.
            setOnClickListener { switch.toggle() }
        }

    private companion object {
        const val TITLE = "Contour lines"
        const val EVERY = "A line every"
        const val AUTO_LABEL = "Auto: to suit the depths on screen"
    }
}
