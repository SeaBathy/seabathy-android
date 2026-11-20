package au.opendepth.app.model

/** The kind of data backing an overlay entry in the layer stack. */
enum class LayerKind {
    /** An imported MBTiles archive held in app-private storage. */
    MBTILES,

    /** An imported GeoTIFF depth raster held in app-private storage. */
    GEOTIFF,

    /** A remote XYZ raster tile template. */
    XYZ
}

/**
 * Contour lines over a depth layer: lines of equal depth, with the depth
 * written on them. Off, and Auto, are also what a layer saved before this
 * existed reads back as.
 */
data class ContourSettings(
    val on: Boolean = false,
    /**
     * How far apart the lines are; [AUTO] picks a spacing to suit the depths
     * on screen. Kept in metres whatever the depth unit, as the shallow-water
     * mark is: it is picked as a round number of the unit chosen in Boat &
     * navigation (5 ft is kept as 1.524) and drawn as the nearest round
     * number of the unit chosen now (see `DepthContours.pickedIn`). Layers
     * saved when it was whole metres read back as the same metres.
     */
    val everyMetres: Double = AUTO
) {
    companion object {
        const val AUTO = 0.0
    }
}

/**
 * One layer in the stack.
 *
 * [source] is a file path, or a tile link for [LayerKind.XYZ]. [ramp] is for
 * GeoTIFF depth layers only, as is [contours] (null, as in layers saved
 * before it existed, is off). [maxZoom] is the most detail there is: the
 * provider's for XYZ, the file's for a GDAL layer. [gdalOnDemand] marks a
 * big GeoTIFF served through GDAL; [gdalCachePath] is its tile cache, kept
 * so tiles drawn before are reused after a restart.
 */
data class OverlayLayer(
    val id: String,
    val kind: LayerKind,
    val name: String,
    val source: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val attribution: String = "",
    val ramp: DepthRampSettings? = null,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
    val gdalOnDemand: Boolean = false,
    val gdalCachePath: String? = null,
    val contours: ContourSettings? = null
) {
    val sanitisedOpacity: Float
        get() = opacity.coerceIn(0f, 1f)

    /** [contours], or off where none were saved. */
    val contourSettings: ContourSettings
        get() = contours ?: ContourSettings()

    /**
     * Clamped to the range MapLibre accepts. Stopping at the provider's real
     * maximum makes the map overzoom the deepest available tiles instead of
     * requesting levels that only ever return 404s.
     */
    val sanitisedMaxZoom: Float
        get() = maxZoom.coerceIn(1, DEFAULT_MAX_ZOOM).toFloat()

    companion object {
        const val DEFAULT_MAX_ZOOM = 22
    }
}

/** Legacy persistence shape, retained so saved MBTiles layers still migrate. */
data class MbtLayer(
    val name: String = "",
    val path: String = "",
    val visible: Boolean = true,
    val opacity: Float = 1f
)
