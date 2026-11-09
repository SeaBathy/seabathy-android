package au.opendepth.app.data

import au.opendepth.app.model.Waypoint
import au.opendepth.app.model.WaypointIcon
import java.util.UUID

/**
 * The in-memory waypoint collection and its persistence.
 *
 * [all] is a live view of the backing list, so renderers that hold onto it see
 * edits without needing to be handed a new list each time.
 */
class WaypointStore(private val repository: WaypointRepository) {

    private val items = mutableListOf<Waypoint>()

    val all: List<Waypoint> get() = items

    fun load() {
        items.clear()
        items.addAll(repository.load())
    }

    fun save() = repository.save(items)

    fun byId(id: String): Waypoint? = items.firstOrNull { it.id == id }

    fun isNameTaken(name: String): Boolean =
        items.any { it.name.equals(name, ignoreCase = true) }

    fun add(
        name: String,
        lat: Double,
        lng: Double,
        color: String,
        description: String?,
        icon: String = WaypointIcon.DEFAULT_ICON_ID
    ): Waypoint {
        val waypoint = Waypoint(
            id = UUID.randomUUID().toString(),
            name = name,
            lat = lat,
            lng = lng,
            color = color,
            desc = description?.takeIf { it.isNotBlank() },
            icon = icon
        )
        items += waypoint
        return waypoint
    }

    fun remove(waypoint: Waypoint) {
        items.remove(waypoint)
    }

    /** The next free "WPT n" name, for a waypoint saved without one. */
    fun nextDefaultName(): String {
        var index = 1
        var candidate: String
        do {
            candidate = "WPT $index"
            index++
        } while (isNameTaken(candidate))
        return candidate
    }
}
