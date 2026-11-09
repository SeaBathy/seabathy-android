package au.opendepth.app.model

import androidx.annotation.DrawableRes
import au.opendepth.app.R

/**
 * A waypoint symbol, drawn white on the waypoint's coloured dot. A short
 * list of common marine ones, not every chart-plotter symbol.
 */
class WaypointIcon(
    val id: String,
    val label: String,
    @param:DrawableRes val drawableRes: Int
) {
    companion object {
        const val DEFAULT_ICON_ID = "marker"

        val ALL: List<WaypointIcon> = listOf(
            WaypointIcon("marker", "Marker", R.drawable.ic_wpt_marker),
            WaypointIcon("diamond", "Diamond", R.drawable.ic_wpt_diamond),
            WaypointIcon("flag", "Flag", R.drawable.ic_wpt_flag),
            WaypointIcon("anchor", "Anchor", R.drawable.ic_wpt_anchor),
            WaypointIcon("fish", "Fishing spot", R.drawable.ic_wpt_fish),
            WaypointIcon("wreck", "Wreck", R.drawable.ic_wpt_wreck),
            WaypointIcon("mob", "Man overboard", R.drawable.ic_wpt_mob)
        )

        private val BY_ID = ALL.associateBy { it.id }

        val DEFAULT: WaypointIcon = BY_ID.getValue(DEFAULT_ICON_ID)

        fun byId(id: String?): WaypointIcon = BY_ID[id] ?: DEFAULT
    }
}
