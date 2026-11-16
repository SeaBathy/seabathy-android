package au.opendepth.app.map

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI

/** What a tap on the chart found, for an [au.opendepth.app.ui.InfoCard]. */
data class MapInfo(
    val badge: String?,
    val badgeColour: Int?,
    val title: String,
    val subtitle: String? = null,
    val rows: List<au.opendepth.app.ui.InfoCard.Row> = emptyList(),
    val paragraphs: List<String> = emptyList(),
    val fine: String? = null
)

/** Which marine park zones are drawn. */
enum class ZoneMode(val label: String) {
    OFF("Off"),
    NO_TAKE("No-take zones only"),
    ALL("All zones");
}

/**
 * Marine park zones (every Australian marine park, Commonwealth and state, from CAPAD),
 * reef and island names, and boat ramps, bundled in assets/zones. Built by testing/zones
 * in the dev folder. Zones are a guide only: the official zoning maps are the law.
 *
 * Zone classes ("c"): "nt" no-take (IUCN Ia/II, Victoria's marine sanctuaries), "lim"
 * limited fishing (IUCN IV, habitat protection), "gen" fishing under the usual rules.
 */
class MarineZonesController(private val mapHolder: MapHolder, private val prefs: SharedPreferences) {

    var mode: ZoneMode = runCatching { ZoneMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(ZoneMode.OFF)
        private set
    var names: Boolean = prefs.getBoolean(KEY_NAMES, false)
        private set
    var ramps: Boolean = prefs.getBoolean(KEY_RAMPS, false)
        private set
    /** Warn when the boat goes into a no-take zone (works with the layer hidden too). */
    var warn: Boolean = prefs.getBoolean(KEY_WARN, true)
        private set
    /** The not-official notice has been accepted once. */
    val acknowledged: Boolean get() = prefs.getBoolean(KEY_ACK, false)

    fun acknowledge() = prefs.edit().putBoolean(KEY_ACK, true).apply()

    fun setMode(value: ZoneMode) {
        mode = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
        apply()
    }

    fun setNames(value: Boolean) {
        names = value
        prefs.edit().putBoolean(KEY_NAMES, value).apply()
        apply()
    }

    fun setRamps(value: Boolean) {
        ramps = value
        prefs.edit().putBoolean(KEY_RAMPS, value).apply()
        apply()
    }

    fun setWarn(value: Boolean) {
        warn = value
        prefs.edit().putBoolean(KEY_WARN, value).apply()
    }

    val anyShown: Boolean get() = mode != ZoneMode.OFF || names || ramps

    /** The zone areas: under the buoys and the user's own marks. */
    fun addAreas(style: Style) {
        style.addSource(GeoJsonSource(ZONES_SOURCE, URI("asset://zones/zones.geojson")))
        val c = Expression.get("c")
        val colour = Expression.match(c, Expression.literal(GEN_COLOUR), Expression.stop("nt", NT_COLOUR), Expression.stop("lim", LIM_COLOUR))
        style.addLayer(FillLayer(ZONE_FILL, ZONES_SOURCE).withProperties(
            PropertyFactory.fillColor(colour),
            PropertyFactory.fillOpacity(Expression.match(c, Expression.literal(0.07f), Expression.stop("nt", 0.30f), Expression.stop("lim", 0.14f)))
        ))
        style.addLayer(LineLayer(ZONE_LINE, ZONES_SOURCE).withProperties(
            PropertyFactory.lineColor(colour),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(5, Expression.match(c, Expression.literal(0.6f), Expression.stop("nt", 1.2f))),
                Expression.stop(12, Expression.match(c, Expression.literal(1.2f), Expression.stop("nt", 2.6f))))),
            PropertyFactory.lineOpacity(0.9f)
        ))
        style.addLayer(SymbolLayer(ZONE_LABEL, ZONES_SOURCE).withProperties(
            PropertyFactory.textField(Expression.switchCase(
                Expression.eq(c, Expression.literal("nt")), Expression.concat(Expression.literal("NO TAKE\n"), Expression.get("n")),
                Expression.eq(c, Expression.literal("lim")), Expression.concat(Expression.literal("Limited fishing\n"), Expression.get("n")),
                Expression.get("n")
            )),
            PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
            PropertyFactory.textSize(Expression.match(c, Expression.literal(10f), Expression.stop("nt", 11f))),
            PropertyFactory.textColor(Expression.match(c, Expression.literal("#BFDBFE"), Expression.stop("nt", "#BBF7D0"), Expression.stop("lim", "#FEF08A"))),
            PropertyFactory.textHaloColor("#0B1622"),
            PropertyFactory.textHaloWidth(1.4f),
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_POINT)
        ).apply { minZoom = 8f })
        apply()
    }

    /** Names and ramps: above everything else on the chart. */
    fun addPoints(style: Style) {
        style.addSource(GeoJsonSource(PLACES_SOURCE, URI("asset://zones/places.geojson")))
        style.addSource(GeoJsonSource(RAMPS_SOURCE, URI("asset://zones/ramps.geojson")))
        style.addImage(RAMP_ICON, rampIcon())
        val k = Expression.get("k")
        val underwater = Expression.`in`(k, Expression.literal(arrayOf("reef", "shoal", "bank", "seamount")))
        style.addLayer(SymbolLayer(PLACE_LABEL, PLACES_SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("n")),
            PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(7, 9.5f), Expression.stop(13, 12.5f))),
            PropertyFactory.textColor(Expression.switchCase(underwater, Expression.color(Color.parseColor("#7DD3FC")), Expression.color(Color.parseColor("#F1F5F9")))),
            PropertyFactory.textHaloColor("#0B1622"),
            PropertyFactory.textHaloWidth(1.3f),
            PropertyFactory.textPadding(4f),
            // Numbered reefs ("Reef 19-131") only close in, so the names stay readable further out.
            PropertyFactory.symbolSortKey(Expression.match(k, Expression.literal(2f), Expression.stop("island", 0f), Expression.stop("reef", 1f)))
        ).withFilter(Expression.any(
            Expression.not(Expression.has("id")),
            Expression.gte(Expression.zoom(), Expression.literal(10))
        )).apply { minZoom = 7f })
        style.addLayer(SymbolLayer(RAMPS, RAMPS_SOURCE).withProperties(
            PropertyFactory.iconImage(RAMP_ICON),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 0.55f), Expression.stop(14, 1f))),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.textField(Expression.get("n")),
            PropertyFactory.textFont(arrayOf(MapStyleFactory.labelFont(style))),
            PropertyFactory.textSize(10.5f),
            PropertyFactory.textColor("#BAE6FD"),
            PropertyFactory.textHaloColor("#0B1622"),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textOptional(true)
        ).apply { minZoom = 8f })
        apply()
    }

    /** Shows and filters the layers for the current choices. */
    fun apply() {
        val style = mapHolder.style ?: return
        fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        val filter = if (mode == ZoneMode.NO_TAKE) Expression.eq(Expression.get("c"), Expression.literal("nt")) else Expression.literal(true)
        runCatching {
            listOf(ZONE_FILL, ZONE_LINE, ZONE_LABEL).forEach { id ->
                style.getLayer(id)?.let { layer ->
                    layer.setProperties(vis(mode != ZoneMode.OFF))
                    when (layer) {
                        is FillLayer -> layer.setFilter(filter)
                        is LineLayer -> layer.setFilter(filter)
                        is SymbolLayer -> layer.setFilter(filter)
                    }
                }
            }
            style.getLayer(PLACE_LABEL)?.setProperties(vis(names))
            style.getLayer(RAMPS)?.setProperties(vis(ramps))
        }
    }

    /** What was tapped: the strictest zone under the finger, a ramp or a name; null if none. */
    fun describeAt(point: PointF): MapInfo? {
        val map = mapHolder.map ?: return null
        val box = RectF(point.x - TAP_PX, point.y - TAP_PX, point.x + TAP_PX, point.y + TAP_PX)
        if (ramps) {
            runCatching { map.queryRenderedFeatures(box, RAMPS).firstOrNull() }.getOrNull()?.let { f ->
                val name = f.getStringProperty("n")?.takeIf { it.isNotBlank() } ?: "Boat ramp"
                return MapInfo(
                    badge = "Boat ramp", badgeColour = Color.parseColor("#0284C7"), title = name,
                    rows = listOfNotNull(au.opendepth.app.ui.InfoCard.Row("Fee", "Yes").takeIf { f.hasProperty("fee") }),
                    fine = "From OpenStreetMap: check it's open and suits your boat. $RAMPS_CREDIT"
                )
            }
        }
        if (mode != ZoneMode.OFF) {
            val zones = runCatching { map.queryRenderedFeatures(point, ZONE_FILL) }.getOrNull().orEmpty()
            val strictest = zones.maxByOrNull { RANK[it.getStringProperty("c")] ?: 0 }
            if (strictest != null) {
                return describeZone(strictest.getStringProperty("c"), strictest.getStringProperty("n"),
                    strictest.getStringProperty("t"), strictest.getStringProperty("s"), strictest.getStringProperty("i"))
            }
        }
        if (names) {
            runCatching { map.queryRenderedFeatures(box, PLACE_LABEL).firstOrNull() }.getOrNull()?.let { f ->
                val kind = f.getStringProperty("k").orEmpty().replaceFirstChar { it.uppercase() }
                return MapInfo(badge = kind, badgeColour = Color.parseColor("#7DD3FC"), title = f.getStringProperty("n") ?: kind, fine = NAMES_CREDIT)
            }
        }
        return null
    }

    companion object {
        const val CREDIT = "Marine parks: CAPAD 2024, DCCEEW (CC BY 4.0)"
        const val NAMES_CREDIT = "Names: Geoscience Australia gazetteer, GBRMPA (CC BY 4.0)"
        const val RAMPS_CREDIT = "Boat ramps: © OpenStreetMap contributors"
        const val NOT_OFFICIAL = "A guide only, from national data that can lag behind changes. The official zoning map and rules " +
            "for the park are the law: check them before you fish. Not for navigation."

        private const val KEY_MODE = "zones_mode"
        private const val KEY_NAMES = "zones_names"
        private const val KEY_RAMPS = "zones_ramps"
        private const val KEY_WARN = "zones_warn"
        private const val KEY_ACK = "zones_ack"
        private const val ZONES_SOURCE = "zones-src"
        private const val PLACES_SOURCE = "places-src"
        private const val RAMPS_SOURCE = "ramps-src"
        private const val ZONE_FILL = "zones-fill"
        private const val ZONE_LINE = "zones-line"
        private const val ZONE_LABEL = "zones-label"
        private const val PLACE_LABEL = "places-label"
        private const val RAMPS = "ramps"
        private const val RAMP_ICON = "ramp-icon"
        private const val TAP_PX = 22f
        const val NT_COLOUR = "#22C55E"
        const val LIM_COLOUR = "#EAB308"
        const val GEN_COLOUR = "#60A5FA"
        private val RANK = mapOf("nt" to 3, "lim" to 2, "gen" to 1)

        fun describeZone(c: String?, park: String?, type: String?, state: String?, iucn: String?): MapInfo {
            val (badge, colour, fishing) = when (c) {
                "nt" -> Triple("No-take zone", NT_COLOUR, "No fishing or collecting of any kind." + if (iucn == "Ia") " Some zones like this are also no entry." else "")
                "lim" -> Triple("Limited fishing", LIM_COLOUR, "Some fishing is allowed, with limits on gear and methods (for example no trawling).")
                else -> Triple("General use", GEN_COLOUR, "Fishing is allowed under the usual rules for the park.")
            }
            val waters = state?.let { if (it == "COM") "Commonwealth" else it }
            return MapInfo(
                badge = badge, badgeColour = Color.parseColor(colour),
                title = park?.takeIf { it.isNotBlank() } ?: badge,
                subtitle = listOfNotNull(type, waters).joinToString(" · "),
                rows = listOf(au.opendepth.app.ui.InfoCard.Row("Fishing", fishing)),
                fine = "$NOT_OFFICIAL\n$CREDIT"
            )
        }

        /** A boat ramp: a rounded blue square with a ramp and a hull on it. */
        fun rampIcon(): Bitmap {
            val s = 2f
            val bmp = Bitmap.createBitmap((22 * s).toInt(), (22 * s).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.parseColor("#0284C7")
            c.drawRoundRect(RectF(1 * s, 1 * s, 21 * s, 21 * s), 5 * s, 5 * s, p)
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * s
            c.drawRoundRect(RectF(1 * s, 1 * s, 21 * s, 21 * s), 5 * s, 5 * s, p)
            p.style = Paint.Style.FILL
            // The ramp: a slope into the water.
            c.drawPath(Path().apply { moveTo(4 * s, 17 * s); lineTo(18 * s, 17 * s); lineTo(18 * s, 13 * s); close() }, p)
            // The hull on it.
            c.drawPath(Path().apply { moveTo(7 * s, 9 * s); lineTo(16 * s, 9 * s); lineTo(14 * s, 12 * s); lineTo(9 * s, 12 * s); close() }, p)
            return bmp
        }
    }
}

/**
 * Knows where the no-take zones are (read once from the bundled data) and says when a
 * position is inside one. Pure geometry: ray casting over each polygon's rings.
 */
class NoTakeZones private constructor(private val zones: List<Zone>) {

    class Zone(val name: String, val type: String, val west: Double, val south: Double, val east: Double, val north: Double, val polygons: List<List<DoubleArray>>)

    /** The no-take zone containing the point, or null. */
    fun at(lat: Double, lng: Double): Zone? = zones.firstOrNull { z ->
        lat >= z.south && lat <= z.north && lng >= z.west && lng <= z.east && z.polygons.any { rings -> inside(rings, lng, lat) }
    }

    companion object {
        fun load(context: Context): NoTakeZones = parse(context.assets.open("zones/zones.geojson").use { it.readBytes().decodeToString() })

        fun parse(json: String): NoTakeZones {
            val out = mutableListOf<Zone>()
            val features = JSONObject(json).getJSONArray("features")
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val p = f.getJSONObject("properties")
                if (p.optString("c") != "nt") continue
                val g = f.optJSONObject("geometry") ?: continue
                val coords = g.getJSONArray("coordinates")
                val polygons = when (g.optString("type")) {
                    "Polygon" -> listOf(rings(coords))
                    "MultiPolygon" -> (0 until coords.length()).map { rings(coords.getJSONArray(it)) }
                    else -> continue
                }
                var w = 180.0; var s = 90.0; var e = -180.0; var n = -90.0
                for (poly in polygons) {
                    val outer = poly.firstOrNull() ?: continue
                    for (k in outer.indices step 2) {
                        w = minOf(w, outer[k]); e = maxOf(e, outer[k]); s = minOf(s, outer[k + 1]); n = maxOf(n, outer[k + 1])
                    }
                }
                out += Zone(p.optString("n"), p.optString("t"), w, s, e, n, polygons)
            }
            return NoTakeZones(out)
        }

        private fun rings(poly: JSONArray): List<DoubleArray> = (0 until poly.length()).map { r ->
            val ring = poly.getJSONArray(r)
            DoubleArray(ring.length() * 2).also { a ->
                for (k in 0 until ring.length()) {
                    val pt = ring.getJSONArray(k)
                    a[2 * k] = pt.getDouble(0)
                    a[2 * k + 1] = pt.getDouble(1)
                }
            }
        }

        /** Inside the outer ring and outside every hole. */
        internal fun inside(rings: List<DoubleArray>, x: Double, y: Double): Boolean {
            if (rings.isEmpty() || !inRing(rings[0], x, y)) return false
            for (h in 1 until rings.size) if (inRing(rings[h], x, y)) return false
            return true
        }

        private fun inRing(r: DoubleArray, x: Double, y: Double): Boolean {
            var inside = false
            var j = r.size / 2 - 1
            for (i in 0 until r.size / 2) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
