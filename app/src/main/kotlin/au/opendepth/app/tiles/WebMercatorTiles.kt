package au.opendepth.app.tiles

import au.opendepth.app.geotiff.MapProjection
import au.opendepth.app.model.GeoBounds
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Web Mercator tile maths (EPSG:3857, z/x/y with y = 0 at the north), as
 * MapLibre uses for `{z}/{x}/{y}` sources. Built on
 * [MapProjection.WebMercator]. No I/O, so unit tested.
 */
object WebMercatorTiles {
    /** MapLibre's raster tiles, like this app's existing MBTiles/XYZ layers, are always this size. */
    const val TILE_SIZE = 256

    /** Half the length, in projected metres, of the full EPSG:3857 world square. */
    private val HALF_WORLD_METRES = PI * MapProjection.EARTH_RADIUS

    /** See [tileBounds]'s doc: a sub-millimetre nudge inward from the exact world edge, to absorb floating-point rounding at the +/-180/+/-90 boundary. */
    private const val EDGE_EPSILON_METRES = 1e-3

    /** The `z`/`x`/`y` tile's real-world bounding box in WGS84 degrees, or null if out of range for `z`. */
    fun tileBounds(z: Int, x: Int, y: Int): GeoBounds? {
        if (z < 0 || z > MAX_SANE_ZOOM) return null
        val tilesPerAxis = 1L shl z
        if (x < 0 || y < 0 || x >= tilesPerAxis || y >= tilesPerAxis) return null

        val tileSizeMetres = (2.0 * HALF_WORLD_METRES) / tilesPerAxis
        val minX = -HALF_WORLD_METRES + x * tileSizeMetres
        val maxX = minX + tileSizeMetres
        val maxY = HALF_WORLD_METRES - y * tileSizeMetres
        val minY = maxY - tileSizeMetres

        // A world-edge corner can round to just past ±180/±90 (say
        // -180.00000000000003) and fail MapProjection's range check, so it's moved a
        // fraction of a millimetre inward.
        val southWest = MapProjection.WebMercator.inverse(minX + EDGE_EPSILON_METRES, minY + EDGE_EPSILON_METRES)
            ?: return null
        val northEast = MapProjection.WebMercator.inverse(maxX - EDGE_EPSILON_METRES, maxY - EDGE_EPSILON_METRES)
            ?: return null

        return GeoBounds(
            west = southWest[1],
            south = southWest[0],
            east = northEast[1],
            north = northEast[0]
        )
    }

    /** The inclusive x/y tile index range at one zoom level that together cover a bounding box. */
    class TileRange(val z: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val tileCount: Long
            get() = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
    }

    /** The tiles at zoom [z] that overlap [bounds], for the offline download. The inverse of [tileBounds]. */
    fun tileRangeCovering(bounds: GeoBounds, z: Int): TileRange {
        val tilesPerAxis = (1L shl z).coerceAtLeast(1)
        val maxIndex = (tilesPerAxis - 1).toInt()
        val tileSizeMetres = (2.0 * HALF_WORLD_METRES) / tilesPerAxis

        fun xAt(lng: Double): Int {
            val x = MapProjection.WebMercator.forward(0.0, lng)[0]
            return (((x + HALF_WORLD_METRES) / tileSizeMetres).toInt()).coerceIn(0, maxIndex)
        }

        fun yAt(lat: Double): Int {
            val y = MapProjection.WebMercator.forward(lat, 0.0)[1]
            return (((HALF_WORLD_METRES - y) / tileSizeMetres).toInt()).coerceIn(0, maxIndex)
        }

        val xWest = xAt(bounds.west)
        val xEast = xAt(bounds.east)
        // y grows southward (north edge -> smaller y), so the north bound
        // gives the smaller tile-y index and the south bound the larger one.
        val yNorth = yAt(bounds.north)
        val ySouth = yAt(bounds.south)

        return TileRange(
            z = z,
            minX = minOf(xWest, xEast),
            maxX = maxOf(xWest, xEast),
            minY = minOf(yNorth, ySouth),
            maxY = maxOf(yNorth, ySouth)
        )
    }

    /** Ground resolution, in metres per pixel, of one [TILE_SIZE]-pixel-wide tile at zoom [z]. */
    fun metresPerPixelAtZoom(z: Int): Double =
        (2.0 * HALF_WORLD_METRES) / (TILE_SIZE.toDouble() * (1L shl z))

    /**
     * The highest zoom that still shows real detail: the smallest zoom whose
     * ground size per pixel is at or below [sourceMetresPerPixel].
     */
    fun nativeMaxZoomForGroundResolution(sourceMetresPerPixel: Double): Int {
        if (sourceMetresPerPixel <= 0.0 || !sourceMetresPerPixel.isFinite()) return MAX_SANE_ZOOM
        // A tiny epsilon so a resolution that is exactly a zoom level's doesn't
        // round up one zoom.
        val ratio = ln(metresPerPixelAtZoom(0) / sourceMetresPerPixel) / ln(2.0)
        val zoom = ceil(ratio - 1e-9).toInt()
        return zoom.coerceIn(0, MAX_SANE_ZOOM)
    }

    /** No real raster or MapLibre style needs a slippy zoom past this; keeps a degenerate input from looping/allocating absurdly. */
    const val MAX_SANE_ZOOM = 22
}
