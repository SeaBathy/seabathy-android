package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.RoutePoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The planned route, saved as it is edited. It used to live only in memory,
 * so the system closing the app in the background (or a dark-mode switch
 * rebuilding the screen) lost it.
 */
class RouteRepository(
    private val preferences: SharedPreferences,
    private val gson: Gson
) {
    fun load(): List<RoutePoint> {
        val raw = preferences.getString(KEY_ROUTE, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<RoutePoint>>() {}.type
            gson.fromJson<List<RoutePoint>>(raw, type).orEmpty()
                .filter { it.lat.isFinite() && it.lng.isFinite() }
        }.getOrElse { error ->
            preferences.preserveUnreadable(KEY_ROUTE, raw, error)
            emptyList()
        }
    }

    fun save(points: List<RoutePoint>) {
        preferences.edit().putString(KEY_ROUTE, gson.toJson(points)).apply()
    }

    /** Which saved route the plan is, so editing it can update that route. */
    fun saveLink(id: String?, name: String?) {
        preferences.edit().putString(KEY_LINK_ID, id).putString(KEY_LINK_NAME, name).apply()
    }

    fun loadSavedId(): String? = preferences.getString(KEY_LINK_ID, null)
    fun loadSavedName(): String? = preferences.getString(KEY_LINK_NAME, null)

    private companion object {
        const val KEY_ROUTE = "route"
        const val KEY_LINK_ID = "route_saved_id"
        const val KEY_LINK_NAME = "route_saved_name"
    }
}
