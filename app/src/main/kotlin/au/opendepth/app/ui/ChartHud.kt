package au.opendepth.app.ui

import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
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
        views.coordinates.text = CoordinateFormatter.format(lat, lng)
    }

    private var fullCredits = ""
    private var shortCredits = ""
    private var creditsInFull = false
    private val shrinkCredits = Runnable {
        creditsInFull = false
        views.mapAttribution.text = shortCredits
    }

    /**
     * The credit line: in [full] when what it credits changes (the map first shows, a layer is
     * turned on), then [short] after [CREDITS_FULL_MS] or once the chart is moved
     * ([shrinkAttribution]), as OSM's attribution guidelines allow. The short line still names
     * OpenStreetMap and the sources whose terms ask to be seen ([MapCredits.compactLine]);
     * tapping either shows the full list. Screen readers always get the full line.
     */
    fun showAttribution(full: String, short: String) {
        val line = views.mapAttribution
        val changed = full != fullCredits
        fullCredits = full
        shortCredits = short
        line.contentDescription = "$CREDITS_DESCRIPTION $full"
        line.visibility = if (full.isBlank()) View.GONE else View.VISIBLE
        if (changed) {
            creditsInFull = true
            line.text = full
            line.removeCallbacks(shrinkCredits)
            line.postDelayed(shrinkCredits, CREDITS_FULL_MS)
        } else if (!creditsInFull) {
            line.text = short
        }
    }

    /** The chart was moved by hand: the credit line goes short at once. */
    fun shrinkAttribution() {
        if (!creditsInFull) return
        views.mapAttribution.removeCallbacks(shrinkCredits)
        shrinkCredits.run()
    }

    /**
     * Tapping the credit line runs [action] (the full list of credits). The
     * line is drawn exactly as before; it is only a few lines of small text,
     * so the room that takes the tap is made finger-sized by growing the
     * view downwards, where nothing is drawn, without moving its top.
     */
    fun onAttributionTapped(action: () -> Unit) {
        val line = views.mapAttribution
        line.setOnClickListener { action() }
        val context = line.context
        val extra = context.dp(MIN_TAP_DP) - (line.lineHeight + line.paddingTop + line.paddingBottom)
        val box = line.background
        val margins = line.layoutParams as? ViewGroup.MarginLayoutParams
        if (extra <= 0 || box == null || margins == null || margins.bottomMargin < extra) return
        val left = line.paddingLeft
        val top = line.paddingTop
        val right = line.paddingRight
        val bottom = line.paddingBottom
        // The same box, drawn over the same part of the view as before.
        line.background = InsetDrawable(box, 0, 0, 0, extra)
        // Setting a background that has insets resets the padding: put back, with the room below.
        line.setPadding(left, top, right, bottom + extra)
        margins.bottomMargin -= extra
        line.layoutParams = margins
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

    private companion object {
        /** The least height, in dp, that takes a tap comfortably. */
        const val MIN_TAP_DP = 48

        /** Read out before the credit line's own text, saying what a tap on it does. */
        const val CREDITS_DESCRIPTION = "Map and depth credits, tap for the full list."

        /** How long the credit line stays in full after it changes: OSM's guidelines allow it to shrink after five seconds. */
        const val CREDITS_FULL_MS = 5_000L
    }
}
