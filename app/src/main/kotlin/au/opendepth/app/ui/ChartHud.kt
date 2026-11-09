package au.opendepth.app.ui

import android.view.View
import au.opendepth.app.settings.VisualSettings
import au.opendepth.app.util.CoordinateFormatter

/**
 * The readouts drawn over the map: coordinates, crosshair, depth, tool hint and
 * the data attribution line.
 */
class ChartHud(private val views: MainViews) {

    private var visual = VisualSettings()

    /** Applies the user's colour and text-size choices to every readout. */
    fun applyVisualSettings(settings: VisualSettings) {
        visual = settings

        val accent = AppTheme.palette.accent
        views.crosshairDot.setTextColor(parseColorOrDefault(settings.cursorColor, accent))
        views.coordinates.setTextColor(
            parseColorOrDefault(settings.coordinateColor, accent)
        )
        views.coordinates.textSize = settings.coordinateTextSize
        views.crosshairDepth.textSize = settings.depthTextSize
    }

    fun showCoordinates(lat: Double, lng: Double) {
        views.coordinates.text = CoordinateFormatter.formatDdm(lat, lng)
    }

    fun showAttribution(text: String) {
        views.mapAttribution.text = text
        views.mapAttribution.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private var hintMessage: String? = null

    fun showHint(message: String?) {
        hintMessage = message
        views.hint.text = message.orEmpty()
        refreshHintVisibility()
    }

    /**
     * The route and measure cards are taller than the hint's bottom margin,
     * so the hint would sit behind them; they carry the same instruction,
     * so it is hidden while either is up.
     */
    private fun refreshHintVisibility() {
        val cardShown = views.routeCard.visibility == View.VISIBLE || views.measureCard.visibility == View.VISIBLE
        views.hint.visibility = if (hintMessage.isNullOrBlank() || cardShown) View.GONE else View.VISIBLE
    }

    fun setCrosshairVisible(visible: Boolean) {
        views.crosshairContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Shows a depth readout, or hides it when [depth] is empty. */
    fun showDepth(depth: String) {
        views.crosshairDepth.text = depth
        views.crosshairDepth.visibility = if (depth.isEmpty()) View.GONE else View.VISIBLE
    }

    fun showRouteSummary(summary: String?) {
        views.routeCard.visibility = if (summary == null) View.GONE else View.VISIBLE
        views.routeInfo.text = summary.orEmpty()
        refreshHintVisibility()
    }

    fun showMeasureSummary(summary: String?) {
        views.measureCard.visibility = if (summary == null) View.GONE else View.VISIBLE
        views.measureInfo.text = summary.orEmpty()
        refreshHintVisibility()
    }
}
