package au.opendepth.app.terrain

import au.opendepth.app.model.GeoBounds

/**
 * A map that can be laid over the 3D terrain: an offline pack whose tiles
 * come from [readTile], or an online tile link ([urlTemplate], `{z}/{x}/{y}`).
 * Zooms are the range the map has tiles for. [group] places it in the 3D
 * view's Map list.
 */
class TerrainDrape(
    val id: String,
    val label: String,
    val minZoom: Int,
    val maxZoom: Int,
    val urlTemplate: String? = null,
    val group: Group = Group.LAYER,
    val attribution: String = "",
    /** Where the map has tiles, when known: an offline pack's area. */
    val bounds: GeoBounds? = null,
    /** The swatch colour in the Map list, as #RRGGBB; null lets the page pick one. */
    val colour: String? = null,
    /** One XYZ-addressed tile's image bytes, or null where there is none. Called off the main thread. */
    val readTile: ((z: Int, x: Int, y: Int) -> ByteArray?)? = null
) {
    enum class Group(val key: String) { DEFAULT("default"), LAYER("layer"), CUSTOM("custom") }

    val isOffline: Boolean get() = readTile != null
}
