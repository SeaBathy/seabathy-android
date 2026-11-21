package au.opendepth.app.settings

import au.opendepth.app.model.MapCredits

/**
 * A well-known XYZ raster tile endpoint offered as a quick-add overlay.
 *
 * Every entry here is a public endpoint whose provider permits use in a free
 * app with attribution. The attribution string is applied to the layer
 * automatically so the on-map credit is never left blank. Services whose
 * terms need an account or licence for app use are not built in; people can
 * still add a map link of their own, if its terms allow it, with the credit
 * they type.
 */
data class XyzTilePreset(
    val id: String,
    val label: String,
    val description: String,
    val urlTemplate: String,
    val attribution: String,
    val category: Category,
    val maxZoom: Int = 19,
    /** False for a layer that is shown online only and never saved for offline use. */
    val offline: Boolean = true,
    /** An overlay with gaps (a coastal strip, say): offered as a layer, not as a basemap. */
    val overlayOnly: Boolean = false
) {
    enum class Category { SATELLITE, TOPOGRAPHIC, STREET, NAUTICAL }
}

object XyzTilePresets {

    private const val GIBS_CREDIT = "Imagery: NASA Global Imagery Browse Services (GIBS), part of NASA's ESDIS"

    /** NASA's daily picture of the world: "default" is always the latest. */
    private fun gibsTrueColour(layer: String) =
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/$layer/default/default/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpg"

    /** What MapLibre (and the offline downloader) puts a web-mercator tile's corners in, for a WMS link. */
    const val BBOX_TOKEN = "{bbox-epsg-3857}"

    /**
     * Geoscience Australia's DEA Tidal Composites (CC BY 4.0, data to 2024, the latest year in the
     * service's capabilities): Sentinel-2 at the lowest or highest 15% of tides, 10 m, the whole
     * Australian coast. A WMS map, asked for one web-mercator tile at a time.
     */
    private fun deaTidal(style: String) =
        "https://ows.dea.ga.gov.au/?service=WMS&version=1.3.0&request=GetMap&layers=ga_s2_tidal_composites_cyear_3" +
            "&styles=$style&crs=EPSG:3857&time=2024-01-01&width=256&height=256&format=image/png&transparent=true" +
            "&bbox=$BBOX_TOKEN"

    const val DEA_TIDAL_CREDIT = "DEA Tidal Composites © Commonwealth of Australia (Geoscience Australia), CC BY 4.0, " +
        "doi:10.26186/150381; contains modified Copernicus Sentinel data"

    /** The wording the Copernicus Marine licence asks for, with the product's DOI. */
    const val COPERNICUS_SDB_CREDIT = "Generated using E.U. Copernicus Marine Service Information; https://doi.org/10.48670/mds-00364"

    val ALL: List<XyzTilePreset> = listOf(
        XyzTilePreset(
            id = "eox_s2cloudless",
            label = "Sentinel-2 satellite",
            description = "Cloud-free satellite picture of the world, 10 m detail (EOX, 2024). Can be saved offline",
            urlTemplate = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg",
            attribution = MapCredits.EOX_CLOUDLESS,
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 15
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
            id = "dea_tidal_low",
            label = "Coast at low tide (DEA)",
            description = "Cloud-free satellite picture of the Australian coast at low tide, 10 m (2024): banks, " +
                "channels and reef flats. Offshore Great Barrier Reef reefs aren't in it. Can be saved offline",
            urlTemplate = deaTidal("low_true"),
            attribution = DEA_TIDAL_CREDIT,
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 15,
            overlayOnly = true
        ),
        XyzTilePreset(
            id = "dea_tidal_high",
            label = "Coast at high tide (DEA)",
            description = "The same at high tide, to compare with the low-tide picture. Australian coast only",
            urlTemplate = deaTidal("high_true"),
            attribution = DEA_TIDAL_CREDIT,
            category = XyzTilePreset.Category.SATELLITE,
            maxZoom = 15,
            overlayOnly = true
        ),
        XyzTilePreset(
            id = "cmems_sdb_coastal",
            label = "Satellite-estimated shallow depths (Copernicus)",
            description = "Depths of 0 to 40 m estimated from satellite pictures, about 100 m detail, coasts " +
                "worldwide: pale is shallow, dark is deeper. An estimate, not for navigation. Online only",
            urlTemplate = "https://wmts.marine.copernicus.eu/teroWmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=BATHYMETRY_GLO_PHY_COASTAL_L4_MY_016_001/cmems_obs-sdb_glo_phy_comp_my_100m-l4-s2_static_202511/height" +
                "&STYLE=cmap:ice,range:-40/0&TILEMATRIXSET=EPSG:3857&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/png",
            attribution = COPERNICUS_SDB_CREDIT,
            category = XyzTilePreset.Category.NAUTICAL,
            maxZoom = 10,
            offline = false,
            overlayOnly = true
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
        )
    )

    /** The preset used by the one-tap satellite overlay action. */
    val DEFAULT_SATELLITE: XyzTilePreset = ALL.first { it.id == "eox_s2cloudless" }

    val SATELLITE: List<XyzTilePreset> =
        ALL.filter { it.category == XyzTilePreset.Category.SATELLITE }

    fun byId(id: String?): XyzTilePreset? = ALL.firstOrNull { it.id == id }

    /** The built-in layers that may be offered as a basemap: not the ones with gaps. */
    val BASEMAPS: List<XyzTilePreset> = ALL.filterNot { it.overlayOnly }

    /** The built-in layer that is shown online only, for this link; null for any other link. */
    fun onlineOnly(urlTemplate: String): XyzTilePreset? =
        ALL.firstOrNull { !it.offline && it.urlTemplate == urlTemplate.trim() }

    /**
     * Whether a link says which tile it wants: `{z}`, `{x}` and `{y}`, or a WMS
     * link's [BBOX_TOKEN] (the tile's corners in web mercator).
     */
    fun hasTileTokens(url: String): Boolean =
        (url.contains("{z}") && url.contains("{x}") && url.contains("{y}")) || url.contains(BBOX_TOKEN)

    /**
     * Validates an XYZ template. MapLibre substitutes `{z}`, `{x}` and `{y}`
     * (or a WMS link's [BBOX_TOKEN]), so a template missing them would
     * silently request nothing.
     */
    fun validationError(urlTemplate: String): String? {
        val url = urlTemplate.trim()
        return when {
            url.isBlank() -> "Enter an XYZ tile URL"

            !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true) ->
                "URL must start with http:// or https://"

            !hasTileTokens(url) ->
                "URL must contain {z}, {x} and {y}"

            else -> null
        }
    }
}
