package au.opendepth.app.model

/**
 * One credit for something on the chart: what it is the credit of ([name]:
 * a layer's own name, or what a built-in set of data is), and the wording
 * its owner asks for ([text]).
 */
data class MapCredit(val group: Group, val name: String, val text: String) {
    enum class Group { BASEMAP, LAYER, BUILT_IN }
}

/**
 * The credits for what is on the chart: the one line under it, and the full
 * list shown when that line is tapped. Both are made from the same
 * [MapCredit]s, so neither can name something the other leaves out.
 */
object MapCredits {

    /** Always first in the credit line on the chart: the agreement's short form, never out of sight. */
    const val NOT_FOR_NAVIGATION = "Not for navigation"

    const val SEPARATOR = " • "

    /** The built-in satellite map's credit, as its owner words it. */
    const val EOX_CLOUDLESS = "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH " +
        "(Contains modified Copernicus Sentinel data 2024)"

    /** The same map's credit as older versions saved it with layers and offline packs. */
    const val EOX_CLOUDLESS_BEFORE = "Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH " +
        "(Contains modified Copernicus Sentinel data 2024)"

    /**
     * A saved credit as it is shown now: the built-in satellite map's old
     * wording becomes its new one. Anything else, such as a credit somebody
     * typed themselves, is shown exactly as saved.
     */
    fun current(saved: String): String = if (saved.trim() == EOX_CLOUDLESS_BEFORE) EOX_CLOUDLESS else saved

    /**
     * The credit line on the chart: "Not for navigation", then each different credit once. A
     * layer built from several sources keeps them as one text joined by "; ", so the parts are
     * compared one by one: two such layers both filled from GMRT name it only once.
     */
    fun line(credits: List<MapCredit>): String =
        (listOf(NOT_FOR_NAVIGATION) + credits.flatMap { it.text.split("; ") }.map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
            .joinToString(SEPARATOR)

    /**
     * Words that keep a credit in the short line ([compactLine]), from each provider's terms:
     * OpenStreetMap and the map built from it (OSM's attribution guidelines: visible on the
     * map), EOX (its credit "clearly visible" wherever its pictures show), Open-Meteo (credit
     * next to where its data shows) and Copernicus (the Copernicus Marine licence, and the
     * "contains modified Copernicus Sentinel data" notice). The rest are CC BY or public
     * domain: credited "in any reasonable manner", the full list one tap away.
     */
    private val ALWAYS_SHOWN = listOf("OpenStreetMap", "OpenMapTiles", "OpenFreeMap", "EOX", "Open-Meteo", "Copernicus")

    /**
     * The short credit line the full one ([line]) shrinks to a few seconds after it changes,
     * or once the chart is moved, as OSM's attribution guidelines allow: "Not for navigation",
     * the basemap's credit, the credits in [ALWAYS_SHOWN] (the sea conditions' named
     * shortly), then how many more there are and "ⓘ". Tapping it shows the full list.
     */
    fun compactLine(credits: List<MapCredit>): String {
        val all = credits.flatMap { c -> c.text.split("; ").map { c.group to it.trim() } }
            .filter { it.second.isNotEmpty() }
            .distinctBy { it.second }
        val (kept, hidden) = all.partition { (group, text) -> group == MapCredit.Group.BASEMAP || ALWAYS_SHOWN.any { it in text } }
        val end = if (hidden.isEmpty()) "ⓘ" else "+${hidden.size} more ⓘ"
        return (listOf(NOT_FOR_NAVIGATION) + kept.map { shortened(it.second) }.distinct() + end).joinToString(SEPARATOR)
    }

    /** The sea conditions' long credit, by the names that must stay in sight; anything else as it is. */
    private fun shortened(text: String): String {
        if (!text.startsWith(SEA_PREFIX)) return text
        val names = buildList {
            if ("Open-Meteo" in text) add("Open-Meteo")
            if ("Copernicus Marine" in text) add("Copernicus Marine")
            if ("Copernicus Sentinel" in text) add("contains modified Copernicus Sentinel data")
        }
        return SEA_PREFIX + names.joinToString(", ") + " and others"
    }

    private const val SEA_PREFIX = "Sea: "

    /** One heading of the full list, with the credits under it, each on its own line. */
    data class Section(val title: String, val lines: List<String>)

    /**
     * The full list, in the order of the credit line: the basemap, each layer
     * on the chart by its name, then the built-in data that is switched on.
     * A heading with nothing under it is left out.
     */
    fun sections(credits: List<MapCredit>): List<Section> {
        val shown = credits.filter { it.text.isNotBlank() }
        fun of(group: MapCredit.Group) = shown.filter { it.group == group }
        return listOf(
            // One basemap at a time: its credit needs no name in front.
            Section(BASEMAP_TITLE, of(MapCredit.Group.BASEMAP).map { it.text.trim() }.distinct()),
            Section(LAYERS_TITLE, of(MapCredit.Group.LAYER).map(::named)),
            Section(BUILT_IN_TITLE, of(MapCredit.Group.BUILT_IN).map(::named).distinct())
        ).filter { it.lines.isNotEmpty() }
    }

    private fun named(credit: MapCredit): String =
        if (credit.name.isBlank()) credit.text.trim() else "${credit.name.trim()}: ${credit.text.trim()}"

    const val PANEL_TITLE = "Map and depth credits"
    const val BASEMAP_TITLE = "BASEMAP"
    const val LAYERS_TITLE = "LAYERS ON THE CHART"
    const val BUILT_IN_TITLE = "BUILT-IN DATA"

    /** Closes the full list. */
    const val NOT_FOR_NAVIGATION_NOTE = "Not for navigation."
    const val ALL_SOURCES_NOTE = "Every source is listed under Settings → About → Licences and credits."

    /** Said once on each form where somebody adds a map or link of their own. */
    const val OWN_MAP_NOTE = "Only add a map whose terms allow it, and type the credit its owner asks for."
}
