package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.RoutePoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/** A named route kept for later. */
data class SavedRoute(
    val id: String,
    val name: String,
    val points: List<RoutePoint>,
    val updatedMillis: Long
)

/** Every saved route, newest edits first. */
class RouteLibrary(
    private val preferences: SharedPreferences,
    private val gson: Gson
) {
    private val routes: MutableList<SavedRoute> = load().toMutableList()

    val all: List<SavedRoute> get() = routes.sortedByDescending { it.updatedMillis }

    fun byId(id: String): SavedRoute? = routes.firstOrNull { it.id == id }

    fun isNameTaken(name: String, exceptId: String? = null): Boolean =
        routes.any { it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) }

    /** A name not used yet: [base], or "[base] (2)", "(3)"… */
    fun freeName(base: String): String {
        val clean = base.trim().ifBlank { "Route" }
        if (!isNameTaken(clean)) return clean
        return generateSequence(2) { it + 1 }.map { "$clean ($it)" }.first { !isNameTaken(it) }
    }

    /** Saves a new route, or replaces the one with [id]. Returns it. */
    fun save(id: String?, name: String, points: List<RoutePoint>): SavedRoute {
        val route = SavedRoute(id ?: UUID.randomUUID().toString(), name.trim(), points.toList(), System.currentTimeMillis())
        val index = routes.indexOfFirst { it.id == route.id }
        if (index >= 0) routes[index] = route else routes += route
        persist()
        return route
    }

    fun rename(id: String, name: String) {
        val route = byId(id) ?: return
        save(id, name, route.points)
    }

    fun delete(id: String) {
        if (routes.removeAll { it.id == id }) persist()
    }

    private fun load(): List<SavedRoute> {
        val raw = preferences.getString(KEY_ROUTES, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<SavedRoute>>() {}.type
            gson.fromJson<List<SavedRoute>>(raw, type).orEmpty().filter { it.points.isNotEmpty() }
        }.getOrElse { error ->
            preferences.preserveUnreadable(KEY_ROUTES, raw, error)
            emptyList()
        }
    }

    private fun persist() {
        preferences.edit().putString(KEY_ROUTES, gson.toJson(routes)).apply()
    }

    private companion object {
        const val KEY_ROUTES = "saved_routes"
    }
}
