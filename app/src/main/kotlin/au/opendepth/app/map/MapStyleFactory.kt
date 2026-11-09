package au.opendepth.app.map

import au.opendepth.app.settings.BasemapType
import com.google.gson.Gson
import org.maplibre.android.maps.Style

/** Builds the base style drawn underneath every overlay. */
object MapStyleFactory {

    private val gson = Gson()

    // OpenFreeMap: OpenStreetMap data, free, with no account or key.
    private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
    // Real OpenStreetMap detail (coastline, harbours, roads, place names) down
    // to street zoom. MapLibre's demo style only has country outlines and
    // stops at zoom 6, which is too coarse for use on the water.
    private const val OSM_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val EMPTY_BACKGROUND = "#111418"

    /**
     * Bundled glyphs for the styles built here. Without a "glyphs" entry a
     * style cannot draw any text, so waypoint names never appeared on the
     * None or Custom basemaps — and being local, they also work offline.
     */
    private const val BUNDLED_GLYPHS = "asset://glyphs/{fontstack}/{range}.pbf"

    /** The font for the app's own labels: each style's server has its own, and a missing one draws nothing. */
    fun labelFont(style: Style): String =
        if (style.uri.contains("openfreemap")) "Noto Sans Regular" else "Open Sans Regular"

    fun create(type: String, customUrl: String): Style.Builder = when (type) {
        BasemapType.DARK -> Style.Builder().fromUri(DARK_STYLE_URL)
        BasemapType.OSM -> Style.Builder().fromUri(OSM_STYLE_URL)
        BasemapType.CUSTOM ->
            if (customUrl.isBlank()) emptyStyle() else customStyle(customUrl)

        else -> emptyStyle()
    }

    /** True for a basemap type whose style document itself has to be fetched
     * over the network ([create] uses `fromUri` for it) rather than built
     * from local JSON, i.e. one that can fail outright with no connectivity. */
    fun requiresNetwork(type: String): Boolean =
        type == BasemapType.DARK || type == BasemapType.OSM

    /** A blank, network-free canvas for [MapStyleCoordinator] to fall back to
     * when a remote basemap's style document can't be fetched, so the user's
     * own offline overlays still have something local to attach to. */
    fun offlineFallback(): Style.Builder = emptyStyle()

    /** A blank canvas, used when the user only wants their own overlays. */
    private fun emptyStyle(): Style.Builder = Style.Builder().fromJson(
        gson.toJson(
            mapOf(
                "version" to 8,
                "glyphs" to BUNDLED_GLYPHS,
                "sources" to emptyMap<String, Any>(),
                "layers" to listOf(
                    mapOf(
                        "id" to "bg",
                        "type" to "background",
                        "paint" to mapOf("background-color" to EMPTY_BACKGROUND)
                    )
                )
            )
        )
    )

    private fun customStyle(urlTemplate: String): Style.Builder = Style.Builder().fromJson(
        gson.toJson(
            mapOf(
                "version" to 8,
                "glyphs" to BUNDLED_GLYPHS,
                "sources" to mapOf(
                    "custom" to mapOf(
                        "type" to "raster",
                        "tiles" to listOf(urlTemplate),
                        "tileSize" to 256,
                        "maxzoom" to 22
                    )
                ),
                "layers" to listOf(
                    mapOf("id" to "custom", "type" to "raster", "source" to "custom")
                )
            )
        )
    )
}
