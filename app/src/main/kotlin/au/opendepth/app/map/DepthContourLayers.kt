package au.opendepth.app.map

import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.gdal.GdalTileSource
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.nav.DepthUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Puts depth layers' contour lines on the chart: for each layer with them
 * on, a line layer and a label layer just above the layer's own colours,
 * following its opacity, hiding and removal. The lines themselves are
 * worked out by [DepthContours], off the main thread, each time the chart
 * comes to rest; a set that arrives after the chart has moved on is dropped.
 *
 * Owned by [OverlayLayerController], which says when a layer goes onto a
 * style or comes off it. Everything here but the working out runs on the
 * main thread.
 */
class DepthContourLayers(
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    /** The unit the depths are written in, as chosen in Boat & navigation. */
    private val depthUnit: () -> DepthUnit
) {
    /** A depth layer with its lines on, and what is needed to work them out. */
    private class Entry(var layer: OverlayLayer, val source: DepthSource, val maxTileZoom: Int) {
        val id: String = layer.id
    }

    /**
     * What was worked out for one layer: which depths and interval ([key]),
     * the lines unless they are as before, and for a GDAL layer the depths
     * gathered from its tiles ([tiles]), kept for next time if the layer
     * still has its lines on.
     */
    private class Built(val key: String, val geoJson: String?, val tiles: DepthContours.Grid? = null)

    /** A tile read came back empty because the layer's file was closed under it (hidden or removed). */
    private class ReadCutShort : Exception()

    /** The part of the chart on screen. [east] is past 180 when the date line is on screen. */
    private class View(val west: Double, val south: Double, val east: Double, val north: Double, val centreLng: Double, val zoom: Double) {
        /** The part of [bounds] on screen, or null when none of it is. */
        fun partOf(bounds: GeoBounds): GeoBounds? {
            // The layer may be shown a whole turn of the world away from its own longitudes.
            val turn = Math.round((centreLng - (bounds.west + bounds.east) / 2) / 360.0) * 360.0
            val w = max(bounds.west, west - turn)
            val e = min(bounds.east, east - turn)
            val s = max(bounds.south, south)
            val n = min(bounds.north, north)
            return if (e > w && n > s) GeoBounds(w, s, e, n) else null
        }
    }

    private val attached = LinkedHashMap<String, Entry>()

    /** The lines last drawn for a layer, put straight back when the style is rebuilt. */
    private val lastDrawn = HashMap<String, String>()

    /** What those lines were worked out from, so the same ones are not worked out twice. */
    private val builtKeys = HashMap<String, String>()

    /**
     * The depths last gathered from a GDAL layer's tiles (a couple of MB at
     * most): while the chart rests on the same tiles they are not gathered
     * again. Read and written by the run in progress, off the main thread.
     */
    private val tileGrids = java.util.concurrent.ConcurrentHashMap<String, DepthContours.Grid>()

    /** Only the one run at a time touches it. */
    private val scratch = DepthContours.Scratch()

    private var pending: Job? = null
    private var running = false
    private var again = false

    /** The layer whose lines the run in progress is working out just now, if any. */
    private var buildingFor: String? = null

    /** Counts every call for new lines: a run that finds it changed has been overtaken. */
    private var asked = 0
    private var failuresLogged = 0

    /**
     * Puts [layer]'s contour layers on [style], just above its own colours,
     * or takes them off when its lines are off. [source] is its depths;
     * [maxTileZoom] the finest zoom its tiles are drawn at (a GDAL layer).
     */
    fun attach(style: Style, layer: OverlayLayer, source: DepthSource?, maxTileZoom: Int) {
        detach(style, layer.id)
        if (source == null || !layer.contourSettings.on) {
            forget(layer.id)
            return
        }
        val sourceId = MapLayerIds.contourSourceFor(layer.id)
        val lineId = MapLayerIds.contourLineFor(layer.id)
        style.addSource(GeoJsonSource(sourceId, lastDrawn[layer.id] ?: DepthContours.EMPTY))
        val line = LineLayer(lineId, sourceId).withProperties(
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineColor(LINE_COLOUR),
            // Every fifth line is heavier, as in the 3D view.
            PropertyFactory.lineWidth(Expression.switchCase(MAJOR, Expression.literal(1.6f), Expression.literal(0.9f)))
        )
        val label = SymbolLayer(MapLayerIds.contourLabelFor(layer.id), sourceId).withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            PropertyFactory.symbolSpacing(260f),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
            PropertyFactory.textSize(11f),
            PropertyFactory.textPadding(4f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#0B1220"),
            PropertyFactory.textHaloWidth(1.4f),
            PropertyFactory.textKeepUpright(true),
            PropertyFactory.textMaxAngle(35f)
        )
        val colours = MapLayerIds.layerFor(layer.id)
        if (style.getLayer(colours) != null) {
            style.addLayerAbove(line, colours)
        } else {
            style.addLayerBelow(line, MapLayerIds.OVERLAY_ANCHOR)
        }
        style.addLayerAbove(label, lineId)

        attached[layer.id] = Entry(layer, source, maxTileZoom)
        applyPaint(layer)
        if (layer.id !in lastDrawn) builtKeys.remove(layer.id)
        refresh(0)
    }

    /** Takes a layer's contour layers off [style]. What was drawn is kept, for when they go back on. */
    fun detach(style: Style, id: String) {
        attached.remove(id)
        stopRunFor(id)
        // No lines on the chart: the working memory for tracing them is let go.
        if (attached.isEmpty() && !running) scratch.release()
        runCatching { style.removeLayer(MapLayerIds.contourLabelFor(id)) }
        runCatching { style.removeLayer(MapLayerIds.contourLineFor(id)) }
        runCatching { style.removeSource(MapLayerIds.contourSourceFor(id)) }
    }

    /** The lines follow their layer's opacity, and go when it is hidden. */
    fun applyPaint(layer: OverlayLayer) {
        val entry = attached[layer.id] ?: return
        val wasHidden = !entry.layer.visible
        entry.layer = layer
        val style = mapHolder.style ?: return
        val opacity = layer.sanitisedOpacity
        val shown = PropertyFactory.visibility(if (layer.visible) Property.VISIBLE else Property.NONE)
        runCatching {
            style.getLayer(MapLayerIds.contourLineFor(layer.id))?.setProperties(
                PropertyFactory.lineOpacity(
                    Expression.switchCase(MAJOR, Expression.literal(0.8f * opacity), Expression.literal(0.5f * opacity))
                ),
                shown
            )
            style.getLayer(MapLayerIds.contourLabelFor(layer.id))?.setProperties(PropertyFactory.textOpacity(opacity), shown)
        }
        // Nothing was worked out while it was hidden.
        if (wasHidden && layer.visible) refresh(0)
    }

    /** Forgets a layer that was hidden or removed, or whose lines were turned off. */
    fun forget(id: String) {
        attached.remove(id)
        stopRunFor(id)
        lastDrawn.remove(id)
        builtKeys.remove(id)
        tileGrids.remove(id)
    }

    /**
     * A run working out [id]'s lines is stopped: the layer came off the map
     * and its file may be closing, so what it read could have holes. The
     * other layers' lines are worked out again straight after.
     */
    private fun stopRunFor(id: String) {
        if (buildingFor != id) return
        buildingFor = null
        pending?.cancel()
        if (attached.isNotEmpty()) refresh(RETRY_MS)
    }

    /** Works the lines out again shortly: the chart came to rest, or a setting or the depth unit changed. */
    fun refresh(delayMs: Long = SETTLE_MS) {
        if (attached.isEmpty()) return
        asked++
        // A run in progress is for where the chart was: cancelled, its lines are never drawn.
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            run()
        }
    }

    fun release() {
        pending?.cancel()
        pending = null
        attached.clear()
        lastDrawn.clear()
        builtKeys.clear()
        tileGrids.clear()
        if (!running) scratch.release()
    }

    /** One run at a time: a call during one waits for it, then runs once more. */
    private suspend fun run() {
        if (running) {
            again = true
            return
        }
        running = true
        try {
            update()
        } finally {
            running = false
            if (attached.isEmpty()) scratch.release()
            if (again) {
                again = false
                refresh(RETRY_MS)
            }
        }
    }

    private suspend fun update() {
        val view = viewOf(mapHolder.map ?: return) ?: return
        val unit = depthUnit()
        val ticket = asked
        for (entry in attached.values.toList()) {
            val id = entry.id
            // Hidden or removed since the run began.
            if (!entry.layer.visible || attached[id] !== entry) continue
            val previous = builtKeys[id]
            val everyMetres = entry.layer.contourSettings.everyMetres
            buildingFor = id
            val built = try {
                withContext(Dispatchers.Default) { build(entry, everyMetres, view, unit, previous) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cut: ReadCutShort) {
                // Its file was closed under the read: nothing it read is kept.
                continue
            } catch (error: Exception) {
                // A file that could not be read just now: the next move tries again, and
                // the other layers still get their lines.
                if (failuresLogged++ < MAX_LOGGED_FAILURES) {
                    DiagnosticLog.logError(TAG, "contour lines FAILED: layer=${entry.layer.name}", error)
                }
                continue
            } finally {
                if (buildingFor == id) buildingFor = null
            }
            // Hidden or removed while these were worked out: nothing is kept for it.
            if (attached[id] !== entry) continue
            // The depths gathered whole, kept while the chart rests on the same tiles.
            built.tiles?.let { tileGrids[id] = it }
            // The chart has moved on while these were worked out: they are for somewhere else.
            if (ticket != asked) return
            if (built.geoJson == null) continue
            // The style may have been rebuilt meanwhile: then the lines go on with the layer, next time round.
            val drawn = runCatching {
                mapHolder.style?.getSourceAs<GeoJsonSource>(MapLayerIds.contourSourceFor(id))?.setGeoJson(built.geoJson)
            }.getOrNull()
            if (drawn == null) continue
            lastDrawn[id] = built.geoJson
            builtKeys[id] = built.key
        }
    }

    /** Runs off the main thread: reads the depths under the screen and traces them. */
    private suspend fun build(entry: Entry, everyMetres: Double, view: View, unit: DepthUnit, previous: String?): Built {
        fun nothing(tiles: DepthContours.Grid? = null) = Built(NO_LINES, if (previous == NO_LINES) null else DepthContours.EMPTY, tiles)
        val part = view.partOf(entry.source.bounds) ?: return nothing()
        val source = entry.source
        val tiles = if (source is GdalTileSource) tileGrid(entry.id, source, part, view.zoom, entry.maxTileZoom) else null
        val grid = (if (source is GdalTileSource) tiles else DepthContours.gridFromCells(source.previewGrid(), part))
            ?: return nothing()
        // Auto goes by the depths on screen, so close in on a flat seabed the lines come closer.
        val depths = DepthContours.depthsWithin(grid, part)
        if (depths == null || depths.second <= depths.first) return nothing(tiles)
        // The spacing is a round number of the depth unit, so the lines are labelled 5, 10, 15 ft, not 16, 33, 49.
        val step = DepthContours.stepFor(depths.first, depths.second, DepthContours.pickedIn(everyMetres, unit), unit)
        val key = "${grid.key}/$step/${unit.name}"
        if (key == previous) return Built(key, null, tiles)
        return Built(key, DepthContours.geoJson(grid, step, unit, scratch), tiles)
    }

    /**
     * A GDAL layer's depths under the screen, from the same tiles its
     * colours are drawn from, so the lines sit where the colours change and
     * mostly nothing is read from the file again. Kept by [update] only once
     * gathered whole: throws [ReadCutShort] when the file was closed under
     * the read, and stops when the run is cancelled.
     */
    private suspend fun tileGrid(
        id: String,
        source: GdalTileSource,
        part: GeoBounds,
        mapZoom: Double,
        maxTileZoom: Int
    ): DepthContours.Grid? {
        // The zoom the map draws 256-pixel tiles at: one finer than its own, to the nearest.
        val tiles = DepthContours.tilesUnder(part, (mapZoom.roundToInt() + 1).coerceIn(0, maxTileZoom))
        val size = source.colourTileSize
        val key = DepthContours.tilesKey(tiles, size)
        tileGrids[id]?.takeIf { it.key == key }?.let { return it }
        return withContext(Dispatchers.IO) {
            DepthContours.gridFromTiles(tiles, size) { x, y ->
                // Reading a big survey can take a while: stop as soon as the chart has moved on,
                // or the layer is hidden or removed.
                ensureActive()
                source.tileDepths(tiles.z, x, y, size).also { depths ->
                    // Empty because the file was closed, not because the tile misses the survey:
                    // the grid would have holes where there are depths.
                    if (depths == null && source.isClosed) throw ReadCutShort()
                }
            }
        }
    }

    private fun viewOf(map: MapLibreMap): View? {
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return null
        val position = map.cameraPosition
        val west = bounds.longitudeWest
        val east = if (bounds.longitudeEast < west) bounds.longitudeEast + 360.0 else bounds.longitudeEast
        if (!(bounds.latitudeNorth > bounds.latitudeSouth) || !(east > west)) return null
        return View(
            west = west,
            south = bounds.latitudeSouth,
            east = east,
            north = bounds.latitudeNorth,
            centreLng = position.target?.longitude ?: (west + east) / 2,
            zoom = position.zoom
        )
    }

    private companion object {
        const val TAG = "DepthContours"
        const val LINE_COLOUR = "#05121F"

        /** After the chart comes to rest, before the lines are worked out: a second touch usually follows a first. */
        const val SETTLE_MS = 120L
        const val RETRY_MS = 60L
        const val MAX_LOGGED_FAILURES = 5

        /** Stands for "no lines here" where a key says what was drawn. */
        const val NO_LINES = "none"

        val MAJOR: Expression = Expression.eq(Expression.get("major"), Expression.literal(true))
    }
}
