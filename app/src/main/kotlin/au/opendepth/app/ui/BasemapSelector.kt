package au.opendepth.app.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import au.opendepth.app.map.MapStyleCoordinator
import au.opendepth.app.settings.BasemapType

/** Drives the basemap spinner at the foot of the layers panel. */
class BasemapSelector(
    private val context: Context,
    private val views: MainViews,
    private val styleCoordinator: MapStyleCoordinator,
    private val customBasemapDialog: CustomBasemapDialog
) {
    fun bind() {
        views.basemapSpinner.adapter =
            PaletteSpinnerAdapter(context, OPTIONS.map { it.second })
        sync()

        views.basemapSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selected = OPTIONS[position].first

                    // Always opens the picker, even if a custom basemap is
                    // already configured, so it can also be used to switch
                    // between presets or edit the saved link.
                    if (selected == BasemapType.CUSTOM) {
                        openCustomBasemapPicker()
                        return
                    }

                    if (selected == styleCoordinator.currentType) return
                    styleCoordinator.rebuild(selected)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    /** Puts the spinner back in step with the coordinator's current basemap. */
    fun sync() {
        val index = OPTIONS
            .indexOfFirst { it.first == styleCoordinator.currentType }
            .coerceAtLeast(0)
        views.basemapSpinner.setSelection(index, false)
    }

    private fun openCustomBasemapPicker() {
        customBasemapDialog.show(
            CustomBasemapDialog.Callbacks(
                onPresetChosen = { preset ->
                    styleCoordinator.saveCustomBasemap(preset.urlTemplate, preset.attribution)
                    styleCoordinator.rebuild(BasemapType.CUSTOM)
                    sync()
                },
                onCustomLinkChosen = { url, attribution ->
                    styleCoordinator.saveCustomBasemap(url, attribution)
                    styleCoordinator.rebuild(BasemapType.CUSTOM)
                    sync()
                }
            ),
            onDismissed = { sync() }
        )
    }

    private companion object {
        /** Basemap key paired with its spinner label. */
        val OPTIONS = listOf(
            BasemapType.DARK to "Dark",
            BasemapType.OSM to "OpenStreetMap",
            BasemapType.CUSTOM to "Custom",
            BasemapType.NONE to "None"
        )
    }
}
