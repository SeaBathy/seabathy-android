package au.opendepth.app.tides

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/** A spot the user wants tides for: a harbour, a reef, a ramp. */
data class TidePlace(val id: String, val name: String, val lat: Double, val lng: Double)

/** The user's saved tide places, in the order they were added. */
class TidePlaceStore(private val preferences: SharedPreferences, private val gson: Gson) {

    private val places: MutableList<TidePlace> = load().toMutableList()

    val all: List<TidePlace> get() = places.toList()

    fun byId(id: String?): TidePlace? = places.firstOrNull { it.id == id }

    fun add(name: String, lat: Double, lng: Double): TidePlace {
        val place = TidePlace(UUID.randomUUID().toString(), freeName(name), lat, lng)
        places += place
        persist()
        return place
    }

    fun rename(id: String, name: String) {
        val index = places.indexOfFirst { it.id == id }
        if (index < 0 || name.isBlank()) return
        places[index] = places[index].copy(name = name.trim())
        persist()
    }

    fun delete(id: String) {
        if (places.removeAll { it.id == id }) persist()
    }

    private fun freeName(base: String): String {
        val clean = base.trim().ifBlank { "Tide spot" }
        if (places.none { it.name.equals(clean, ignoreCase = true) }) return clean
        return generateSequence(2) { it + 1 }.map { "$clean ($it)" }
            .first { candidate -> places.none { it.name.equals(candidate, ignoreCase = true) } }
    }

    private fun load(): List<TidePlace> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TidePlace>>(raw, object : TypeToken<List<TidePlace>>() {}.type).orEmpty()
                .filter { it.lat.isFinite() && it.lng.isFinite() }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        preferences.edit().putString(KEY, gson.toJson(places)).apply()
    }

    private companion object {
        const val KEY = "tide_places"
    }
}
