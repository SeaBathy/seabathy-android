package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.DataSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists the user's configured offline data sources, seeded with
 * [DataSource.DEFAULT_PRESET] on first-ever launch so the app is usable
 * without the user having to add anything first.
 */
class DataSourceRepository(
    private val preferences: SharedPreferences,
    private val gson: Gson
) {
    fun load(): List<DataSource> {
        val raw = preferences.getString(KEY_SOURCES, null)
            ?: return listOf(DataSource.DEFAULT_PRESET).also(::save)

        return runCatching {
            val type = object : TypeToken<List<DataSource>>() {}.type
            gson.fromJson<List<DataSource>>(raw, type) ?: emptyList()
        }.getOrElse { error ->
            preferences.preserveUnreadable(KEY_SOURCES, raw, error)
            emptyList()
        }
    }

    fun save(sources: List<DataSource>) {
        preferences.edit().putString(KEY_SOURCES, gson.toJson(sources)).apply()
    }

    companion object {
        // v2 holds tile links. Pre-1.0 v1 held single-file COG links, which aren't
        // tile links, so there is nothing to migrate.
        private const val KEY_SOURCES = "offline_data_sources_v2"

        fun newId(): String = "source_" + java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
