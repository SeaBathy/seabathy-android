package au.opendepth.app.ui

import au.opendepth.app.R

/** The map interaction currently armed by the top-bar tool buttons. */
enum class ToolMode(val buttonId: Int, val hintResId: Int) {
    NONE(0, 0),
    WAYPOINT(R.id.btnWpt, R.string.hint_waypoint),
    ROUTE(R.id.btnRoute, R.string.hint_route),
    MEASURE(R.id.btnMeasure, R.string.hint_measure);

    companion object {
        /** Every mode that has a top-bar button, for tint updates. */
        val SELECTABLE = listOf(WAYPOINT, ROUTE, MEASURE)
    }
}
