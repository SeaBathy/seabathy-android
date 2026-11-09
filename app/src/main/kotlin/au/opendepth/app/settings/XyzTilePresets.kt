package au.opendepth.app.settings

/**
 * A well-known XYZ raster tile endpoint offered as a quick-add overlay.
 *
 * Every entry here is a public endpoint whose provider permits general use
 * with attribution. The attribution string is applied to the layer
 * automatically so the on-map credit is never left blank.
 */
data class XyzTilePreset(
    val id: String,
    val label: String,
    val description: String,
    val urlTemplate: String,
    val attribution: String,
    val category: Category,
    val maxZoom: Int = 19
) {
    enum class Category { SATELLITE, TOPOGRAPHIC, STREET, NAUTICAL }
}

object XyzTilePresets {

    private const val GIBS_CREDIT = "Imagery: NASA Global Imagery Browse Services (GIBS), part of NASA's ESDIS"

    /** NASA's daily picture of the world: "default" is always the latest. */
    private fun gibsTrueColour(layer: String) =
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/$layer/default/default/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpg"

    val ALL: List<XyzTilePreset> = listOf(
        XyzTilePreset(
            id = "esri_world_imagery",
            label = "Esri World Imagery",
            description = "High resolution satellite and aerial imagery",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Imagery/MapServer/tile/{z}/{y}/{x}",
            attribution = "Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community · Powered by Esri",
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 19
        ),
        XyzTilePreset(
            id = "gibs_true_colour_n20",
            label = "Latest true colour (NOAA-20)",
            description = "Yesterday or today's satellite pass, about 250 m: water colour, clouds, river plumes. Worldwide",
            urlTemplate = gibsTrueColour("VIIRS_NOAA20_CorrectedReflectance_TrueColor"),
            attribution = GIBS_CREDIT,
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 9
        ),
        XyzTilePreset(
            id = "gibs_true_colour_n21",
            label = "Latest true colour (NOAA-21)",
            description = "The same from another satellite, passing at another time: try it if clouds hide the first",
            urlTemplate = gibsTrueColour("VIIRS_NOAA21_CorrectedReflectance_TrueColor"),
            attribution = GIBS_CREDIT,
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 9
        ),
        XyzTilePreset(
            id = "esri_world_ocean",
            label = "Esri Ocean Basemap",
            description = "Bathymetric shaded relief and ocean features",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "Ocean/World_Ocean_Base/MapServer/tile/{z}/{y}/{x}",
            attribution = "Esri, GEBCO, NOAA, National Geographic · Powered by Esri",
            category = XyzTilePreset.Category.NAUTICAL,
            maxZoom = 13
        ),
        XyzTilePreset(
            id = "usgs_imagery",
            label = "USGS Imagery",
            description = "Public domain aerial imagery, best over the USA",
            urlTemplate = "https://basemap.nationalmap.gov/arcgis/rest/services/" +
                "USGSImageryOnly/MapServer/tile/{z}/{y}/{x}",
            attribution = "USGS The National Map",
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 16
        ),
        XyzTilePreset(
            id = "openstreetmap",
            label = "OpenStreetMap",
            description = "Standard OSM street map",
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "(c) OpenStreetMap contributors",
            category = XyzTilePreset.Category.STREET,
            maxZoom = 19
        ),
        XyzTilePreset(
            id = "opentopomap",
            label = "OpenTopoMap",
            description = "Topographic contours and hillshading",
            urlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            attribution = "(c) OpenStreetMap contributors, SRTM, OpenTopoMap (CC-BY-SA)",
            category = XyzTilePreset.Category.TOPOGRAPHIC,
            maxZoom = 17
        ),
        XyzTilePreset(
            id = "esri_world_topo",
            label = "Esri World Topographic",
            description = "Land detail, roads and place names",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
            attribution = "Esri, HERE, Garmin, USGS, NGA · Powered by Esri",
            category = XyzTilePreset.Category.TOPOGRAPHIC,
            maxZoom = 19
        )
    )

    /** The preset used by the one-tap satellite overlay action. */
    val DEFAULT_SATELLITE: XyzTilePreset = ALL.first { it.id == "esri_world_imagery" }

    val SATELLITE: List<XyzTilePreset> =
        ALL.filter { it.category == XyzTilePreset.Category.SATELLITE }

    fun byId(id: String?): XyzTilePreset? = ALL.firstOrNull { it.id == id }

    /**
     * Validates an XYZ template. MapLibre substitutes `{z}`, `{x}` and `{y}`,
     * so a template missing any of them would silently request nothing.
     */
    fun validationError(urlTemplate: String): String? {
        val url = urlTemplate.trim()
        return when {
            url.isBlank() -> "Enter an XYZ tile URL"

            !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true) ->
                "URL must start with http:// or https://"

            !url.contains("{z}") || !url.contains("{x}") || !url.contains("{y}") ->
                "URL must contain {z}, {x} and {y}"

            else -> null
        }
    }
}
