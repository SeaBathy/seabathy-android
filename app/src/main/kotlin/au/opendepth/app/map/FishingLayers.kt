package au.opendepth.app.map

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.ui.InfoCard
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Australian fishing layers, each on its own switch (Settings → Australia), each with a card when
 * a feature is tapped and a credit while it is shown:
 *
 * - Shipwrecks, bundled in assets/fishing/wrecks.geojson: the national database (AUCHD) with the
 *   WA Museum's and SA DEW's own records merged in.
 * - Artificial reefs (Queensland, Gold Coast) and Queensland Shark Control Program gear, in
 *   assets/fishing/sites.geojson.
 * - Fishing closures and management areas (GBRMPA, Queensland Fish Habitat Areas, WA DPIRD
 *   orders), in assets/fishing/closures.geojson.
 * - NSW seabed reefs, fetched for the area on the chart and kept for offline ([NswReefTiles]).
 *
 * The bundled files are built by tools/fishing_layers. A layer's data is only read once it is
 * first turned on. These are guides: the tap cards say what each thing is and to check the rules.
 */
class FishingLayersController(
    context: Context,
    private val mapHolder: MapHolder,
    private val prefs: SharedPreferences
) {
    var wrecks: Boolean = prefs.getBoolean(KEY_WRECKS, false)
        private set
    var reefs: Boolean = prefs.getBoolean(KEY_REEFS, false)
        private set
    var sharkGear: Boolean = prefs.getBoolean(KEY_SHARK, false)
        private set
    var closures: Boolean = prefs.getBoolean(KEY_CLOSURES, false)
        private set
    var nswReefs: Boolean = prefs.getBoolean(KEY_NSW_REEFS, false)
        private set

    val anyShown: Boolean get() = wrecks || reefs || sharkGear || closures || nswReefs

    fun setWrecks(value: Boolean) = set(KEY_WRECKS, value) { wrecks = it }
    fun setReefs(value: Boolean) = set(KEY_REEFS, value) { reefs = it }
    fun setSharkGear(value: Boolean) = set(KEY_SHARK, value) { sharkGear = it }
    fun setClosures(value: Boolean) = set(KEY_CLOSURES, value) { closures = it }
    fun setNswReefs(value: Boolean) = set(KEY_NSW_REEFS, value) {
        nswReefs = it
        // Switched off: whatever is being fetched stops at the next tile.
        if (it) refreshNswReefs() else reefRequest.incrementAndGet()
    }

    private fun set(key: String, value: Boolean, assign: (Boolean) -> Unit) {
        prefs.edit().putBoolean(key, value).apply()
        assign(value)
        apply()
    }

    /** The credit of each layer on show. */
    fun credits(): List<String> = listOfNotNull(
        FishingInfo.WRECKS_CREDIT.takeIf { wrecks },
        FishingInfo.REEFS_CREDIT.takeIf { reefs },
        FishingInfo.SHARK_CREDIT.takeIf { sharkGear },
        FishingInfo.CLOSURES_CREDIT.takeIf { closures },
        FishingInfo.NSW_REEF_CREDIT.takeIf { nswReefs }
    )

    // The map ---------------------------------------------------------------------------------------

    /** Bundled files whose data this style has been given. */
    private val loaded = mutableSetOf<String>()

    /**
     * Puts the layers on a style that has just loaded: the areas just above the user's own layers
     * (under the marine park zones), the points under the buoys and beacons, reef and island
     * names and boat ramps (which are placed first, so a wreck icon or a closure's name gives way
     * to them). Every source starts empty; [apply] gives each its data once its layer is turned on.
     */
    fun install() {
        val style = mapHolder.style ?: return
        // A style is installed once: a second call for the same one only refreshes it.
        val fresh = style.getLayer(WRECKS) == null
        if (fresh) loaded.clear()
        if (fresh) runCatching {
            listOf(WRECKS_SOURCE, SITES_SOURCE, CLOSURES_SOURCE, NSW_SOURCE).forEach { id ->
                if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, MapLayerIds.EMPTY_GEOJSON))
            }
            style.addImage(WRECK_ICON, wreckIcon())
            style.addImage(REEF_ICON, reefIcon())
            // Each area goes just above the one before it, the first just above the user's layers.
            var previous: String? = null
            fun area(layer: Layer) {
                val anchor = previous ?: MapLayerIds.OVERLAY_ANCHOR
                if (style.getLayer(anchor) != null) style.addLayerAbove(layer, anchor) else style.addLayer(layer)
                previous = layer.id
            }
            // Under the marks (and so the names and ramps above them); under the tracks if there are none.
            val pointsBelow = listOf(NavMarksController.MARKS, MapLayerIds.TRACK_CASING).firstOrNull { style.getLayer(it) != null }
            fun point(layer: Layer) {
                if (pointsBelow != null) style.addLayerBelow(layer, pointsBelow) else style.addLayer(layer)
            }
            areaLayers().forEach(::area)
            pointLayers(style).forEach(::point)
        }.onFailure { DiagnosticLog.log(TAG, "install failed: ${it.message}") }
        watchCamera()
        apply()
        lastNswJson?.let { json -> runCatching { style.getSourceAs<GeoJsonSource>(NSW_SOURCE)?.setGeoJson(json) } }
        refreshNswReefs()
    }

    private fun areaLayers(): List<Layer> {
        val k = Expression.get("k")
        val colour = Expression.match(k, Expression.literal(LIMITS_COLOUR),
            Expression.stop("closed", CLOSED_COLOUR), Expression.stop("anchoring", ANCHOR_COLOUR), Expression.stop("habitat", HABITAT_COLOUR))
        return listOf(
            FillLayer(NSW_FILL, NSW_SOURCE).withProperties(
                PropertyFactory.fillColor(NSW_REEF_COLOUR),
                PropertyFactory.fillOpacity(0.3f)
            ).apply { minZoom = 9.5f },
            LineLayer(NSW_LINE, NSW_SOURCE).withProperties(
                PropertyFactory.lineColor(NSW_REEF_LINE),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 0.5f), Expression.stop(15, 1.4f)))
            ).apply { minZoom = 10f },
            FillLayer(CLOSURES_FILL, CLOSURES_SOURCE).withProperties(
                PropertyFactory.fillColor(colour),
                PropertyFactory.fillOpacity(Expression.match(k, Expression.literal(0.16f), Expression.stop("habitat", 0.10f)))
            ),
            LineLayer(CLOSURES_LINE, CLOSURES_SOURCE).withProperties(
                PropertyFactory.lineColor(colour),
                PropertyFactory.lineOpacity(0.9f),
                // Dashed, so a closure reads apart from the marine park zones' solid edges.
                PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(6, 0.8f), Expression.stop(12, 1.8f)))
            ),
            FillLayer(REEF_AREA_FILL, SITES_SOURCE).withFilter(Expression.eq(k, Expression.literal("reef_area"))).withProperties(
                PropertyFactory.fillColor(REEF_COLOUR),
                PropertyFactory.fillOpacity(0.25f)
            ).apply { minZoom = 10f },
            LineLayer(REEF_AREA_LINE, SITES_SOURCE).withFilter(Expression.eq(k, Expression.literal("reef_area"))).withProperties(
                PropertyFactory.lineColor(REEF_LINE),
                PropertyFactory.lineWidth(1.2f)
            ).apply { minZoom = 10f }
        )
    }

    private fun pointLayers(style: Style): List<Layer> {
        val k = Expression.get("k")
        val a = Expression.get("a")
        val font = arrayOf(MapStyleFactory.labelFont(style))
        return listOf(
            SymbolLayer(CLOSURES_LABEL, CLOSURES_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("n")),
                PropertyFactory.textFont(font),
                PropertyFactory.textSize(10f),
                PropertyFactory.textColor(Expression.match(k, Expression.literal("#FED7AA"),
                    Expression.stop("closed", "#FECACA"), Expression.stop("anchoring", "#E9D5FF"), Expression.stop("habitat", "#BAE6FD"))),
                PropertyFactory.textHaloColor(HALO),
                PropertyFactory.textHaloWidth(1.3f),
                PropertyFactory.textOptional(true),
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_POINT)
            ).apply { minZoom = 10f },
            LineLayer(SHARK_NETS, SITES_SOURCE).withFilter(Expression.all(
                Expression.eq(k, Expression.literal("net")), Expression.eq(Expression.geometryType(), Expression.literal("LineString"))
            )).withProperties(
                PropertyFactory.lineColor(SHARK_COLOUR),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(9, 2f), Expression.stop(15, 4f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            ).apply { minZoom = 9f },
            CircleLayer(SHARK_GEAR, SITES_SOURCE).withFilter(Expression.all(
                Expression.`in`(k, Expression.literal(arrayOf("drum", "net"))), Expression.eq(Expression.geometryType(), Expression.literal("Point"))
            )).withProperties(
                PropertyFactory.circleColor(SHARK_COLOUR),
                PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(9, 2.5f), Expression.stop(15, 5f))),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1f)
            ).apply { minZoom = 9f },
            SymbolLayer(REEF_POINTS, SITES_SOURCE).withFilter(Expression.eq(k, Expression.literal("reef"))).withProperties(
                PropertyFactory.iconImage(REEF_ICON),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(7, 0.55f), Expression.stop(13, 0.95f))),
                PropertyFactory.textField(Expression.step(Expression.zoom(), Expression.literal(""), Expression.stop(10, Expression.get("n")))),
                PropertyFactory.textFont(font),
                PropertyFactory.textSize(10.5f),
                PropertyFactory.textColor("#E2E8F0"),
                PropertyFactory.textHaloColor(HALO),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                PropertyFactory.textOptional(true)
            ).apply { minZoom = 7f },
            SymbolLayer(WRECKS, WRECKS_SOURCE).withProperties(
                PropertyFactory.iconImage(WRECK_ICON),
                PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(7, 0.45f), Expression.stop(12, 0.8f), Expression.stop(16, 1f))),
                // Rough positions are faded, and give way to better ones where they crowd.
                PropertyFactory.iconOpacity(Expression.match(a, Expression.literal(1f), Expression.stop("p1", 0.5f), Expression.stop("nf", 0.55f))),
                PropertyFactory.symbolSortKey(Expression.match(a, Expression.literal(2f),
                    Expression.stop("f", 0f), Expression.stop("g", 0f), Expression.stop("ae", 1f), Expression.stop("ch", 1f),
                    Expression.stop("p3", 1f), Expression.stop("p1", 3f), Expression.stop("nf", 3f))),
                PropertyFactory.textField(Expression.step(Expression.zoom(), Expression.literal(""),
                    Expression.stop(12, Expression.coalesce(Expression.get("n"), Expression.literal(""))))),
                PropertyFactory.textFont(font),
                PropertyFactory.textSize(10.5f),
                PropertyFactory.textColor("#FCD9A8"),
                PropertyFactory.textHaloColor(HALO),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
                PropertyFactory.textOptional(true)
            ).apply { minZoom = 7f }
        )
    }

    /** Shows each layer as switched, giving a layer its data the first time it is shown. */
    fun apply() {
        val style = mapHolder.style ?: return
        fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        fun load(source: String, file: String) {
            if (loaded.add(source)) runCatching { style.getSourceAs<GeoJsonSource>(source)?.setUri("asset://fishing/$file") }
        }
        runCatching {
            if (wrecks) load(WRECKS_SOURCE, "wrecks.geojson")
            if (reefs || sharkGear) load(SITES_SOURCE, "sites.geojson")
            if (closures) load(CLOSURES_SOURCE, "closures.geojson")
            style.getLayer(WRECKS)?.setProperties(vis(wrecks))
            listOf(REEF_POINTS, REEF_AREA_FILL, REEF_AREA_LINE).forEach { style.getLayer(it)?.setProperties(vis(reefs)) }
            listOf(SHARK_GEAR, SHARK_NETS).forEach { style.getLayer(it)?.setProperties(vis(sharkGear)) }
            listOf(CLOSURES_FILL, CLOSURES_LINE, CLOSURES_LABEL).forEach { style.getLayer(it)?.setProperties(vis(closures)) }
            listOf(NSW_FILL, NSW_LINE).forEach { style.getLayer(it)?.setProperties(vis(nswReefs)) }
        }
    }

    // Taps ------------------------------------------------------------------------------------------

    // A tap is asked in this order: these points, then the marine park zones' ramps and zones
    // (MainActivity), then these areas, so a fishing area never hides a no-take zone or a ramp.

    private fun props(f: org.maplibre.geojson.Feature) = f.properties() ?: JsonObject()

    /** What was tapped among the points: shark gear, a wreck or an artificial reef's icon; null if none. */
    fun describePointsAt(point: PointF): MapInfo? {
        val map = mapHolder.map ?: return null
        val box = RectF(point.x - TAP_PX, point.y - TAP_PX, point.x + TAP_PX, point.y + TAP_PX)
        fun nearest(vararg layers: String) = runCatching { map.queryRenderedFeatures(box, *layers) }.getOrNull().orEmpty()
            .minByOrNull { f ->
                val p = f.geometry() as? org.maplibre.geojson.Point ?: return@minByOrNull Float.MAX_VALUE
                val s = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(p.latitude(), p.longitude()))
                (s.x - point.x) * (s.x - point.x) + (s.y - point.y) * (s.y - point.y)
            }
        if (sharkGear) nearest(SHARK_GEAR, SHARK_NETS)?.let { return FishingInfo.sharkGear(props(it)) }
        if (wrecks) nearest(WRECKS)?.let { return FishingInfo.wreck(props(it)) }
        if (reefs) nearest(REEF_POINTS)?.let { return FishingInfo.reef(props(it)) }
        return null
    }

    /** The smallest closure under the finger (the most particular), or null. */
    private fun closureAt(point: PointF): org.maplibre.geojson.Feature? {
        if (!closures) return null
        val map = mapHolder.map ?: return null
        return runCatching { map.queryRenderedFeatures(point, CLOSURES_FILL) }.getOrNull().orEmpty()
            .minByOrNull { it.getNumberProperty("sz")?.toDouble() ?: Double.MAX_VALUE }
    }

    /**
     * What was tapped among the areas, after the marine park zones: the smallest closure, an
     * artificial reef's outline, or an NSW reef; null if none.
     */
    fun describeAreasAt(point: PointF): MapInfo? {
        val map = mapHolder.map ?: return null
        closureAt(point)?.let { return FishingInfo.closure(props(it)) }
        if (reefs) runCatching { map.queryRenderedFeatures(point, REEF_AREA_FILL).firstOrNull() }.getOrNull()
            ?.let { return FishingInfo.reef(props(it)) }
        if (nswReefs && runCatching { map.queryRenderedFeatures(point, NSW_FILL).isNotEmpty() }.getOrDefault(false)) {
            return FishingInfo.nswReef()
        }
        return null
    }

    /** A zone's (or ramp's) card, with a line naming the closure under the finger: shown first, a zone doesn't hide it. */
    fun withClosureAt(point: PointF, info: MapInfo): MapInfo =
        closureAt(point)?.let { info.copy(rows = info.rows + FishingInfo.alsoHere(props(it))) } ?: info

    // NSW reefs: fetched for the chart on screen, kept for offline ----------------------------------

    private val reefStore = NswReefStore(File(context.applicationContext.filesDir, NswReefStore.DIR)) { url -> download(url) }
    private val reefLoader = NswReefLoader(reefStore)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nsw-reefs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    /** Each look at the chart is one request; a newer one (or switching off) stops an older one. */
    private val reefRequest = AtomicInteger()
    private var lastNswJson: String? = null
    private var watched: org.maplibre.android.maps.MapLibreMap? = null

    private fun watchCamera() {
        val map = mapHolder.map ?: return
        if (map === watched) return
        watched = map
        map.addOnCameraIdleListener { refreshNswReefs() }
    }

    /**
     * Shows the NSW reefs for the chart on screen: those kept on the phone at once, then fetches
     * the rest of the tiles over the screen, a few at a time, showing each batch as it comes
     * ([NswReefLoader]). The map is only given the outlines again when they change: the camera
     * rests about once a second while it follows the boat.
     */
    private fun refreshNswReefs() {
        if (!nswReefs) return
        val map = mapHolder.map ?: return
        if (map.cameraPosition.zoom < NSW_LOAD_ZOOM) return
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val tiles = NswReefTiles.tilesFor(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
            .take(NSW_MAX_TILES)
        if (tiles.isEmpty()) return
        val request = reefRequest.incrementAndGet()
        // Refused once released: the app is closing.
        runCatching {
            worker.execute {
                reefLoader.load(
                    tiles,
                    current = { request == reefRequest.get() && nswReefs },
                    show = { shown -> publish(request, shown) },
                    onError = { t, e -> DiagnosticLog.log(TAG, "NSW reef tile ${t.name} not fetched: ${e.javaClass.simpleName} ${e.message}") }
                )
            }
        }
    }

    private fun publish(request: Int, tiles: Map<String, List<NswReefTiles.Reef>>) {
        val names = tiles.keys.toSet()
        val json = NswReefTiles.featureCollection(tiles.values)
        main.post {
            if (request != reefRequest.get()) return@post
            lastNswJson = json
            reefLoader.shown = names
            runCatching { mapHolder.style?.getSourceAs<GeoJsonSource>(NSW_SOURCE)?.setGeoJson(json) }
        }
    }

    /** The internet is back: tiles are fetched again straight away. */
    fun onConnectivityRestored() {
        reefLoader.online()
        refreshNswReefs()
    }

    /**
     * Deletes the NSW reef outlines kept on the phone (Settings → Storage), and returns the bytes
     * freed. Off the main thread. Looking at the NSW coast with the layer on fetches them again.
     */
    fun clearReefOutlines(): Long {
        reefRequest.incrementAndGet()
        reefLoader.forget()
        return reefStore.clear()
    }

    /** The app is closing: fetching stops and the worker ends. */
    fun release() {
        reefRequest.incrementAndGet()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun download(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", "SeaBathy (Android reef outlines)").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("the reef service answered ${r.code}")
            r.body?.string() ?: throw IOException("the reef service sent nothing")
        }

    companion object {
        private const val TAG = "SeaBathyFishing"
        private const val KEY_WRECKS = "fishing_wrecks"
        private const val KEY_REEFS = "fishing_reefs"
        private const val KEY_SHARK = "fishing_shark_gear"
        private const val KEY_CLOSURES = "fishing_closures"
        private const val KEY_NSW_REEFS = "fishing_nsw_reefs"

        private const val WRECKS_SOURCE = "fishing-wrecks-src"
        private const val SITES_SOURCE = "fishing-sites-src"
        private const val CLOSURES_SOURCE = "fishing-closures-src"
        private const val NSW_SOURCE = "fishing-nswreef-src"
        private const val WRECKS = "fishing-wrecks"
        private const val REEF_POINTS = "fishing-reefs"
        private const val REEF_AREA_FILL = "fishing-reef-area-fill"
        private const val REEF_AREA_LINE = "fishing-reef-area-line"
        private const val SHARK_GEAR = "fishing-shark-gear"
        private const val SHARK_NETS = "fishing-shark-nets"
        private const val CLOSURES_FILL = "fishing-closures-fill"
        private const val CLOSURES_LINE = "fishing-closures-line"
        private const val CLOSURES_LABEL = "fishing-closures-label"
        private const val NSW_FILL = "fishing-nswreef-fill"
        private const val NSW_LINE = "fishing-nswreef-line"
        private const val WRECK_ICON = "fishing-wreck-icon"
        private const val REEF_ICON = "fishing-reef-icon"
        private const val TAP_PX = 22f

        /** NSW reef tiles are fetched from this zoom in (about 10 tiles on a phone's screen). */
        private const val NSW_LOAD_ZOOM = 11.0
        /** A tablet's screen at the zoom they load from, turned 45 degrees, needs up to about 64. */
        private const val NSW_MAX_TILES = 64

        const val CLOSED_COLOUR = "#EF4444"
        const val LIMITS_COLOUR = "#F97316"
        const val ANCHOR_COLOUR = "#A855F7"
        const val HABITAT_COLOUR = "#38BDF8"
        const val WRECK_COLOUR = "#B45309"
        const val REEF_COLOUR = "#64748B"
        private const val REEF_LINE = "#CBD5E1"
        const val SHARK_COLOUR = "#EA580C"
        const val NSW_REEF_COLOUR = "#E0B35A"
        private const val NSW_REEF_LINE = "#F4CF7A"
        private const val HALO = "#0B1622"

        private val client by lazy {
            OkHttpClient.Builder().dns(au.opendepth.app.util.FallbackDns)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** A wreck: a square in rust with the chart's wreck sign (a hull with three masts) in white. */
        fun wreckIcon(): Bitmap = squareIcon(WRECK_COLOUR) { c, p, s ->
            p.strokeWidth = 1.6f * s
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(5 * s, 12 * s, 17 * s, 12 * s, p)
            for (x in floatArrayOf(8f, 11f, 14f)) c.drawLine(x * s, 8.5f * s, x * s, 15.5f * s, p)
        }

        /** An artificial reef: a slate square with three white blocks stacked, as reef modules are laid. */
        fun reefIcon(): Bitmap = squareIcon(REEF_COLOUR) { c, p, s ->
            p.style = Paint.Style.FILL
            c.drawRect(6 * s, 12 * s, 10.5f * s, 16.5f * s, p)
            c.drawRect(11.5f * s, 12 * s, 16 * s, 16.5f * s, p)
            c.drawRect(8.75f * s, 6.5f * s, 13.25f * s, 11 * s, p)
        }

        /** A small square with a white edge, in the boat ramp icon's size and shape. */
        private fun squareIcon(colour: String, draw: (Canvas, Paint, Float) -> Unit): Bitmap {
            val s = 2f
            val bmp = Bitmap.createBitmap((22 * s).toInt(), (22 * s).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.parseColor(colour)
            c.drawRoundRect(RectF(1 * s, 1 * s, 21 * s, 21 * s), 4 * s, 4 * s, p)
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * s
            c.drawRoundRect(RectF(1 * s, 1 * s, 21 * s, 21 * s), 4 * s, 4 * s, p)
            draw(c, p, s)
            return bmp
        }
    }
}

/**
 * The cards for the fishing layers' features, from their properties (as built by
 * tools/fishing_layers), and the layers' credits. No Android in the wording, so it is unit tested.
 */
object FishingInfo {

    const val WRECKS_CREDIT = "Shipwrecks: Australasian Underwater Cultural Heritage Database, © Commonwealth of Australia " +
        "(DCCEEW), CC BY 3.0 AU, via Seamap Australia; WA Museum, SA DEW (CC BY 4.0)"
    const val REEFS_CREDIT = "Artificial reefs: © State of Queensland (CC BY 4.0); City of Gold Coast (CC BY 3.0 AU)"
    const val SHARK_CREDIT = "Shark control gear: © State of Queensland, Shark Control Program (CC BY 4.0)"
    const val CLOSURES_CREDIT = "Fishing closures: GBRMPA (CC BY 4.0); © State of Queensland (CC BY 4.0); WA DPIRD (CC BY 4.0)"
    const val NSW_REEF_CREDIT = "NSW seabed reef extent 2023: © State of NSW (DCCEEW), CC BY 4.0"

    /** Every layer's credit, for the tests that check each one has one. */
    val ALL_CREDITS = listOf(WRECKS_CREDIT, REEFS_CREDIT, SHARK_CREDIT, CLOSURES_CREDIT, NSW_REEF_CREDIT)

    const val WRECK_NOTE = "Positions are often approximate, and some wrecks are in rivers or ashore. Wrecks lost 75 or " +
        "more years ago are protected: don't disturb them or take anything from them."
    const val CHECK_RULES = "A guide only, not the law: check the current rules before you fish or anchor here."
    const val SHARK_NOTE = "Queensland Shark Control Program gear. Keep clear and don't touch it: interfering with shark " +
        "control gear is against the law."
    const val SHARK_POSITIONS = "Positions as published in April 2024. Gear is moved and changed, so it may not be exactly here."

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.number(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun colour(hex: String): Int = Color.parseColor(hex)

    /** What a wreck record says of how its position was found ([code] as built), or null. */
    fun positionNote(code: String?): String? = when (code) {
        "p1" -> "Rough: to about 10 km"
        "p2" -> "Approximate: to about 1 km"
        "p3" -> "To about 100 m, and may still be approximate"
        "f" -> "From the wreck site, which has been found"
        "nf" -> "Not found: where it was reported lost"
        "g" -> "GPS fix on the wreck"
        "ae" -> "From an aerial photo"
        "ch" -> "From a chart or old map"
        "chk" -> "Needs checking"
        else -> null
    }

    /** A depth range in metres: "20 m", or "12 to 18 m". */
    fun depthText(from: Double?, to: Double?): String? {
        val lo = from?.takeIf { it > 0 }
        val hi = to?.takeIf { it > 0 } ?: return lo?.let { "${metres(it)} m" }
        return if (lo == null || lo == hi) "${metres(hi)} m" else "${metres(lo)} to ${metres(hi)} m"
    }

    private fun metres(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    fun wreck(p: JsonObject): MapInfo {
        val aircraft = p.has("air")
        val badge = if (aircraft) "Aircraft wreck" else "Shipwreck"
        val record = when (p.text("s")) {
            "W" -> "WA Museum"
            "S" -> "SA Department for Environment and Water"
            else -> "Australasian Underwater Cultural Heritage Database"
        } + if (p.has("also")) ", and the national database" else ""
        return MapInfo(
            badge = badge, badgeColour = colour(FishingLayersController.WRECK_COLOUR),
            title = p.text("n") ?: if (aircraft) "Unnamed aircraft" else "Unnamed wreck",
            subtitle = listOfNotNull(p.number("y")?.toInt()?.let { "Lost $it" }, p.text("t")).joinToString(" · ").ifEmpty { null },
            rows = listOfNotNull(
                p.text("l")?.let { InfoCard.Row("Where", it) },
                depthText(p.number("d0"), p.number("d1"))?.let { InfoCard.Row("Depth", it) },
                positionNote(p.text("a"))?.let { InfoCard.Row("Position", it) },
                InfoCard.Row("Record", record)
            ),
            fine = "$WRECK_NOTE\n$WRECKS_CREDIT"
        )
    }

    fun reef(p: JsonObject): MapInfo {
        val goldCoast = p.text("src") == "G"
        return MapInfo(
            badge = "Artificial reef", badgeColour = colour(FishingLayersController.REEF_COLOUR),
            title = p.text("n") ?: "Artificial reef",
            subtitle = p.text("l"),
            rows = listOfNotNull(
                p.number("ha")?.let { InfoCard.Row("Size", "${it.toLong()} ha") },
                p.number("yb")?.let { InfoCard.Row("Built", it.toLong().toString()) }
            ),
            paragraphs = listOf(
                if (goldCoast) "Built to protect the beach, from big sand-filled bags. Also a fishing and dive spot."
                else "Built for recreational fishing. Special rules limit some activities here: check the current rules before you fish."
            ),
            fine = REEFS_CREDIT
        )
    }

    fun sharkGear(p: JsonObject): MapInfo {
        val net = p.text("k") == "net"
        return MapInfo(
            badge = "Shark control", badgeColour = colour(FishingLayersController.SHARK_COLOUR),
            title = p.text("n") ?: if (net) "Shark net" else "Drumline",
            subtitle = listOfNotNull(p.text("b"), p.text("ar")).distinct().joinToString(" · ").ifEmpty { null },
            paragraphs = listOf(SHARK_NOTE),
            fine = "$SHARK_POSITIONS\n$SHARK_CREDIT"
        )
    }

    /** The badge and its colour for a closure's kind. */
    fun closureBadge(kind: String?): Pair<String, String> = when (kind) {
        "closed" -> "Closed area" to FishingLayersController.CLOSED_COLOUR
        "anchoring" -> "No anchoring" to FishingLayersController.ANCHOR_COLOUR
        "habitat" -> "Fish habitat" to FishingLayersController.HABITAT_COLOUR
        else -> "Fishing limits" to FishingLayersController.LIMITS_COLOUR
    }

    /** A closure as one line on another card: "Also here" → "Closed area: HMAS Swan Wreck Site. Check the current rules." */
    fun alsoHere(p: JsonObject): InfoCard.Row {
        val badge = closureBadge(p.text("k")).first
        return InfoCard.Row("Also here", badge + (p.text("n")?.let { ": $it" } ?: "") + ". Check the current rules.")
    }

    fun closure(p: JsonObject): MapInfo {
        val (badge, hex) = closureBadge(p.text("k"))
        val src = p.text("src")
        return MapInfo(
            badge = badge, badgeColour = colour(hex),
            title = p.text("n") ?: badge,
            subtitle = when (src) {
                "GBRMPA" -> "Great Barrier Reef Marine Park"
                "Qld" -> "Queensland"
                "WA" -> "Western Australia"
                else -> null
            },
            rows = listOfNotNull(
                p.text("w")?.let { InfoCard.Row("Rules", it) },
                p.text("o")?.let { InfoCard.Row(if (src == "Qld") "Reference" else "Under", it) }
            ),
            paragraphs = listOf(CHECK_RULES),
            fine = CLOSURES_CREDIT
        )
    }

    fun nswReef(): MapInfo = MapInfo(
        badge = "Reef", badgeColour = colour(FishingLayersController.NSW_REEF_COLOUR),
        title = "Rocky reef",
        subtitle = "NSW seabed reef extent 2023",
        paragraphs = listOf("Mapped from marine lidar, multibeam sonar and aerial photos. A guide only: reef can reach " +
            "further than the outline shows."),
        fine = NSW_REEF_CREDIT
    )
}
