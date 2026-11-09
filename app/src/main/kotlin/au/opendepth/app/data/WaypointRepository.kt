package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.Waypoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class WaypointRepository(
    private val preferences: SharedPreferences,
    private val gson: Gson
) {
    fun load(): List<Waypoint> {
        val raw = preferences.getString(KEY_WAYPOINTS, "[]") ?: "[]"
        return runCatching {
            val type = object : TypeToken<List<Waypoint>>() {}.type
            gson.fromJson<List<Waypoint>>(WaypointJsonMigrations.addMissingIcon(raw), type)
                ?: emptyList()
        }.getOrElse { error ->
            preferences.preserveUnreadable(KEY_WAYPOINTS, raw, error)
            emptyList()
        }
    }

    fun save(waypoints: List<Waypoint>) {
        preferences.edit().putString(KEY_WAYPOINTS, gson.toJson(waypoints)).apply()
    }

    private companion object {
        const val KEY_WAYPOINTS = "wpts"
    }
}
