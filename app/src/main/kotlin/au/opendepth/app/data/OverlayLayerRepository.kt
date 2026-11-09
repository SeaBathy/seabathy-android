package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.LayerKind
import au.opendepth.app.model.MbtLayer
import au.opendepth.app.model.OverlayLayer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Persists the ordered overlay stack: imported MBTiles, imported GeoTIFFs and
 * remote XYZ overlays all live in one list so their draw order is a single
 * user-visible concept.
 *
 * Layers saved by pre-1.0 builds under the MBTiles-only key are migrated on
 * first load and the old key is then cleared.
 */
class OverlayLayerRepository(
    private val preferences: SharedPreferences,
    private val gson: Gson
) {
    fun load(): List<OverlayLayer> {
        val stored = readStored()
        if (stored != null) return stored

        val migrated = migrateLegacyMbtLayers()
        if (migrated.isNotEmpty()) save(migrated)
        preferences.edit().remove(KEY_LEGACY_MBT_LAYERS).apply()
        return migrated
    }

    fun save(layers: List<OverlayLayer>) {
        preferences.edit().putString(KEY_LAYERS, gson.toJson(layers)).apply()
    }

    private fun readStored(): List<OverlayLayer>? {
        val raw = preferences.getString(KEY_LAYERS, null) ?: return null
        return runCatching {
            val type = object : TypeToken<List<OverlayLayer>>() {}.type
            gson.fromJson<List<OverlayLayer>>(
                OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw),
                type
            )
                ?.filter { it.id.isNotBlank() && it.source.isNotBlank() }
                ?: emptyList()
        }.getOrElse { error ->
            preferences.preserveUnreadable(KEY_LAYERS, raw, error)
            emptyList()
        }
    }

    private fun migrateLegacyMbtLayers(): List<OverlayLayer> {
        val raw = preferences.getString(KEY_LEGACY_MBT_LAYERS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<MbtLayer>>() {}.type
            gson.fromJson<List<MbtLayer>>(raw, type)
                ?.filter { it.path.isNotBlank() }
                ?.map { legacy ->
                    OverlayLayer(
                        id = newLayerId(),
                        kind = LayerKind.MBTILES,
                        name = legacy.name.ifBlank { "MBTiles layer" },
                        source = legacy.path,
                        visible = legacy.visible,
                        opacity = legacy.opacity.coerceIn(0f, 1f)
                    )
                }
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val KEY_LAYERS = "overlay_layers_v2"
        private const val KEY_LEGACY_MBT_LAYERS = "mbt_layers"

        /**
         * MapLibre source and layer ids are derived from this, so it has to be
         * a plain identifier with no separators that could collide.
         */
        fun newLayerId(): String =
            "layer_" + UUID.randomUUID().toString().replace("-", "")
    }
}
