package au.opendepth.app.ui

import android.widget.TextView
import au.opendepth.app.model.MapCredit
import au.opendepth.app.model.MapCredits

/**
 * "Map and depth credits", opened by tapping the credit line on the chart:
 * every credit in full, each on its own line, for the basemap, each layer on
 * the chart and the built-in data that is switched on.
 */
class MapCreditsPanel(private val panels: PanelDialogs) {

    fun show(credits: List<MapCredit>) {
        val body = panels.body()
        val lines = mutableListOf<TextView>()
        MapCredits.sections(credits).forEach { section ->
            body.addView(panels.sectionHeader(section.title))
            section.lines.forEach { line ->
                body.addView(panels.caption(line, topPaddingDp = 0).also { lines += it })
            }
        }
        body.addView(panels.caption(MapCredits.NOT_FOR_NAVIGATION_NOTE, topPaddingDp = 14))
        body.addView(panels.caption(MapCredits.ALL_SOURCES_NOTE, topPaddingDp = 0))
        panels.showPanel(MapCredits.PANEL_TITLE, body)
        // A panel draws any text that reads like "remove" or "delete" as a warning; a layer's
        // name or a credit may happen to, and is neither.
        lines.forEach { it.setTextColor(AppTheme.palette.primaryText) }
    }
}
