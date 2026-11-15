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
    var icon: String = WaypointIcon.DEFAULT_ICON_ID,
    /** Photos taken at this mark, oldest first; null in waypoints saved before photos existed. */
    var photos: MutableList<WaypointPhoto>? = null
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

/**
 * A photo of a waypoint, with what the sea was doing when it was taken:
 * the tide, wind, waves, swell, current, sea temperature, sun and moon as
 * the app showed them, kept as text so they read the same later.
 */
data class WaypointPhoto(
    /** File name in the app's photos folder. */
    val file: String,
    val takenMillis: Long,
    val conditions: List<String> = emptyList()
)
