package au.opendepth.app.data

import au.opendepth.app.model.WaypointIcon
import com.google.gson.JsonParser

/**
 * Waypoints saved before 1.0 have no icon. Gson skips Kotlin defaults and
 * would leave it null, so the default is written into the raw JSON first.
 */
object WaypointJsonMigrations {

    fun addMissingIcon(raw: String): String {
        val waypoints = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return raw

        var patched = false
        for (element in waypoints) {
            val waypoint = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (!waypoint.has("icon")) {
                waypoint.addProperty("icon", WaypointIcon.DEFAULT_ICON_ID)
                patched = true
            }
        }

        return if (patched) waypoints.toString() else raw
    }
}
