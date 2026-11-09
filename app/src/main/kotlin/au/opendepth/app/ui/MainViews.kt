package au.opendepth.app.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import au.opendepth.app.R
import org.maplibre.android.maps.MapView

/**
 * Resolves the activity layout once instead of calling findViewById from a
 * hundred scattered places.
 */
class MainViews(activity: Activity) {

    val mapView: MapView = activity.findViewById(R.id.mapView)

    val topBar: LinearLayout = activity.findViewById(R.id.topBar)
    val statusBarSpacer: View = activity.findViewById(R.id.statusBarSpacer)
    val btnMenu: ImageButton = activity.findViewById(R.id.btnMenu)
    val coordinates: TextView = activity.findViewById(R.id.tvCoords)
    val btnGps: ImageButton = activity.findViewById(R.id.btnGps)

    val crosshairContainer: FrameLayout = activity.findViewById(R.id.crosshairContainer)
    val crosshairDot: TextView = activity.findViewById(R.id.crosshairDot)
    val crosshairDepth: TextView = activity.findViewById(R.id.tvCrosshairDepth)

    val hint: TextView = activity.findViewById(R.id.tvHint)
    val mapAttribution: TextView = activity.findViewById(R.id.tvMapAttribution)

    val routeCard: LinearLayout = activity.findViewById(R.id.routeCard)
    val routeInfo: TextView = activity.findViewById(R.id.tvRouteInfo)
    val btnRouteUndo: Button = activity.findViewById(R.id.btnRouteUndo)
    val btnRouteClear: Button = activity.findViewById(R.id.btnRouteClear)
    val btnRouteGpx: ImageButton = activity.findViewById(R.id.btnRouteGpx)
    val btnSpeedSourceGps: Button = activity.findViewById(R.id.btnSpeedSourceGps)
    val btnSpeedSourceCustom: Button = activity.findViewById(R.id.btnSpeedSourceCustom)
    val customSpeedInput: EditText = activity.findViewById(R.id.etCustomSpeed)
    val btnRouteSave: Button = activity.findViewById(R.id.btnRouteSave)
    val btnRouteLibrary: Button = activity.findViewById(R.id.btnRouteLibrary)
    val btnRouteLegs: Button = activity.findViewById(R.id.btnRouteLegs)
    val btnRouteReverse: Button = activity.findViewById(R.id.btnRouteReverse)
    val btnRouteGo: Button = activity.findViewById(R.id.btnRouteGo)
    val navBarSpacer: View = activity.findViewById(R.id.navBarSpacer)

    val measureCard: LinearLayout = activity.findViewById(R.id.measureCard)
    val measureInfo: TextView = activity.findViewById(R.id.tvMeasure)
    val btnMeasureClear: Button = activity.findViewById(R.id.btnMeasureClear)
    val measureNavSpacer: View = activity.findViewById(R.id.measureNavSpacer)

    val panelScrim: FrameLayout = activity.findViewById(R.id.panelScrim)
    val sidePanel: LinearLayout = activity.findViewById(R.id.sidePanel)
    val panelStatusSpacer: View = activity.findViewById(R.id.panelStatusSpacer)
    val btnPanelClose: ImageButton = activity.findViewById(R.id.btnPanelClose)
    val layersList: LinearLayout = activity.findViewById(R.id.layersList)
    val waypointsList: LinearLayout = activity.findViewById(R.id.waypointsList)
    val btnSettings: Button = activity.findViewById(R.id.btnSettings)
    val basemapSpinner: Spinner = activity.findViewById(R.id.spinBasemap)

    val waypointSheetScrim: FrameLayout = activity.findViewById(R.id.wptDialogBg)
    val waypointSheet: LinearLayout = activity.findViewById(R.id.wptDialog)
    val waypointName: EditText = activity.findViewById(R.id.etWptName)
    val waypointDescription: EditText = activity.findViewById(R.id.etWptDesc)
    val waypointCoordinates: TextView = activity.findViewById(R.id.tvWptCoords)
    val btnWaypointCancel: Button = activity.findViewById(R.id.btnWptCancel)
    val btnWaypointSave: Button = activity.findViewById(R.id.btnWptSave)
    val waypointNavSpacer: View = activity.findViewById(R.id.wptNavSpacer)

    val colorSwatches: List<View> = listOf(
        R.id.col0,
        R.id.col1,
        R.id.col2,
        R.id.col3,
        R.id.col4,
        R.id.col5
    ).map { activity.findViewById(it) }

    val iconPicker: LinearLayout = activity.findViewById(R.id.iconPicker)

    val toolButtons: Map<ToolMode, ImageButton> = ToolMode.SELECTABLE.associateWith { mode ->
        activity.findViewById(mode.buttonId)
    }

    /** Applies the generated palette to the views declared in the layout. */
    fun applyPalette(palette: UiPalette = AppTheme.palette) {
        val context = topBar.context

        topBar.setBackgroundColor(
            Color.argb(
                TOP_BAR_ALPHA,
                Color.red(palette.background),
                Color.green(palette.background),
                Color.blue(palette.background)
            )
        )
        sidePanel.setBackgroundColor(palette.panel)

        coordinates.setTextColor(palette.accent)
        coordinates.background =
            context.roundedBackground(palette.surface, palette.border, UiRadius.CONTROL)

        btnSettings.setTextColor(palette.primaryText)
        btnSettings.background =
            context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)

        basemapSpinner.background =
            context.roundedBackground(palette.raised, palette.border, UiRadius.CONTROL)

        waypointSheet.background =
            context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
        waypointName.applyInputStyle(palette)
        waypointDescription.applyInputStyle(palette)
        waypointCoordinates.setTextColor(palette.primaryText)
        waypointCoordinates.background =
            context.roundedBackground(palette.surface, palette.border, UiRadius.ROW)
        btnWaypointCancel.applySecondaryStyle(palette)
        btnWaypointSave.applyWaypointSaveStyle()

        toolButtons.values.plus(btnGps).forEach { button ->
            button.background =
                context.roundedBackground(palette.raised, palette.border, UiRadius.CONTROL)
        }
    }

    private companion object {
        /** Keeps the top bar slightly translucent over the map. */
        const val TOP_BAR_ALPHA = 235
    }
}
