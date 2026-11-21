package au.opendepth.app.fishing

import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.map.DepthContours
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.nav.NavGeo
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The depths of a depth layer under the chart on screen, as one grid, the
 * way its contour lines are worked out ([au.opendepth.app.map.DepthContourLayers]):
 * a large survey's from the tiles its colours are drawn from, at about the
 * detail drawn at the map's zoom; one held in memory from its own cells.
 * Used by the structure finder and the plotter export.
 */
object ScreenDepths {

    /** The part of the chart on screen, and the map's zoom. Null across the date line, which these tools leave alone. */
    class View(val bounds: GeoBounds, val zoom: Double)

    /** Read on the main thread. */
    fun viewOf(map: MapLibreMap): View? {
        val box = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return null
        val west = box.longitudeWest
        val east = box.longitudeEast
        if (!(east > west) || !(box.latitudeNorth > box.latitudeSouth)) return null
        return View(GeoBounds(west, box.latitudeSouth, east, box.latitudeNorth), map.cameraPosition.zoom)
    }

    /** The part of [layer] on screen, or null when none of it is. */
    fun partOf(view: GeoBounds, layer: GeoBounds): GeoBounds? {
        val w = max(view.west, layer.west)
        val e = min(view.east, layer.east)
        val s = max(view.south, layer.south)
        val n = min(view.north, layer.north)
        return if (e > w && n > s) GeoBounds(w, s, e, n) else null
    }

    /**
     * True for a layer small enough to look at whole: held in memory, and
     * no more cells a side than a screen's grid has anyway.
     */
    fun isSmall(source: DepthSource): Boolean {
        if (source is GdalTileSource) return false
        val grid = runCatching { source.previewGrid() }.getOrNull() ?: return false
        return max(grid.cols, grid.rows) <= SMALL_SIDE
    }

    /**
     * The depths of [source] under [part], or null with too few to use.
     * [maxTileZoom] is the finest zoom a large survey's tiles are drawn at.
     * Reads the file: call off the main thread. Stops (returning null) once
     * [cancelled] says so.
     */
    fun grid(source: DepthSource, part: GeoBounds, mapZoom: Double, maxTileZoom: Int, cancelled: () -> Boolean = { false }): DepthContours.Grid? {
        if (source !is GdalTileSource) return DepthContours.gridFromCells(source.previewGrid(), part)
        val tiles = DepthContours.tilesUnder(part, (mapZoom.roundToInt() + 1).coerceIn(0, max(0, maxTileZoom)))
        val size = source.colourTileSize
        var cut = false
        val grid = DepthContours.gridFromTiles(tiles, size) { x, y ->
            if (cut || cancelled() || source.isClosed) {
                cut = true
                null
            } else {
                runCatching { source.tileDepths(tiles.z, x, y, size) }.getOrNull()
            }
        }
        // Closed under the read (hidden or removed), or no longer wanted: what was read has holes.
        return if (cut) null else grid
    }

    /** The size of [grid]'s cells in metres at its middle: east-west, then north-south. */
    fun cellMetres(grid: DepthContours.Grid): Pair<Double, Double> {
        val x = (grid.cols - 1) / 2.0
        val y = (grid.rows - 1) / 2.0
        val p = grid.placement
        val lat = p.lat(y)
        val lng = p.lng(x)
        return NavGeo.distanceM(lat, lng, lat, p.lng(x + 1)) to NavGeo.distanceM(lat, lng, p.lat(y + 1), lng)
    }

    /** A layer held in memory with no more than this many cells a side is looked at whole. */
    private const val SMALL_SIDE = 700
}
