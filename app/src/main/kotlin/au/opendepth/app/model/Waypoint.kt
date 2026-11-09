package au.opendepth.app.model

/** A user-placed mark on the chart. */
data class Waypoint(
    val id: String,
    var name: String,
    var lat: Double,
    var lng: Double,
    var color: String = DEFAULT_COLOR,
    var desc: String? = null,
    /** One of [WaypointIcon.ALL]'s ids; see [WaypointIcon.byId] for how an unrecognised or missing value is handled. */
    var icon: String = WaypointIcon.DEFAULT_ICON_ID
) {
    companion object {
        const val DEFAULT_COLOR = "#EF4444"

        val PALETTE = listOf(
            "#EF4444",
            "#F97316",
            "#EAB308",
            "#22C55E",
            "#3B82F6",
            "#A855F7"
        )
    }
}
