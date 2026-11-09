package au.opendepth.app.data

import au.opendepth.app.model.DepthRampSettings
import com.google.gson.JsonParser

/**
 * One-off schema patches applied to the raw persisted overlay-layer JSON
 * before it is deserialised, kept as a pure string-to-string transform (no
 * `SharedPreferences`/Android dependency) so it stays plain-JVM testable.
 */
object OverlayLayerJsonMigrations {

    /**
     * Layers saved before 1.0 have no `hillshadeExaggeration` in their `ramp`.
     * Gson skips Kotlin defaults and would read it as 0 (no shading), so the
     * default is written into the raw JSON first: only there can "missing" be
     * told apart from a real 0. JSON of any other shape is returned unchanged.
     */
    fun addMissingHillshadeExaggeration(raw: String): String {
        val layers = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return raw

        var patched = false
        for (element in layers) {
            val layer = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val ramp = layer.get("ramp")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (!ramp.has("hillshadeExaggeration")) {
                ramp.addProperty(
                    "hillshadeExaggeration",
                    DepthRampSettings.DEFAULT_HILLSHADE_EXAGGERATION
                )
                patched = true
            }
        }

        return if (patched) layers.toString() else raw
    }
}
