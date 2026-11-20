package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.DataSource
import au.opendepth.app.model.MapCredits
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
        }.map(::withCurrentCredit)
    }

    /**
     * The built-in source as an older version saved it carries its old
     * credit: new downloads from it take the credit as it is worded now.
     */
    private fun withCurrentCredit(source: DataSource): DataSource {
        // Read from saved text, where a missing entry comes back null whatever the type says.
        val saved: String? = source.attribution
        return if (saved != null && MapCredits.current(saved) != saved) source.copy(attribution = MapCredits.current(saved)) else source
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
