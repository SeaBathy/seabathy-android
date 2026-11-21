package au.opendepth.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI
import kotlin.math.PI
import kotlin.math.sin

/**
 * Buoys, beacons, lights, pipelines, submarine cables and mooring areas from free government
 * data (and OpenStreetMap), bundled in assets/navmarks. Off at every start, and only shown after the
 * user has ticked, each time they turn it on, that it is not for navigation: positions can be old or wrong and many marks are
 * missing. The data is built by testing/navmarks in the dev folder.
 */
class NavMarksController(private val mapHolder: MapHolder) {

    var visible: Boolean = false
        private set

    fun addTo(style: Style) {
        NavMarkIcons.KEYS.forEach { key -> style.addImage(key, NavMarkIcons.draw(key)) }
        style.addImage(CABLE_PATTERN, NavMarkIcons.cableWave())
        style.addSource(GeoJsonSource(LINES_SOURCE, URI("asset://navmarks/navlines.geojson")))
        style.addSource(GeoJsonSource(MARKS_SOURCE, URI("asset://navmarks/navmarks.geojson")))
        val shown = if (visible) Property.VISIBLE else Property.NONE
        val areaKinds = Expression.literal(arrayOf("area_anchorage", "area_mooring", "area_marina", "area_channel"))
        val areaColour = Expression.match(
            Expression.get("kind"),
            Expression.literal("#7C3AED"),
            Expression.stop("area_channel", "#64748B"),
            Expression.stop("area_mooring", "#0EA5E9"),
            Expression.stop("area_marina", "#0EA5E9")
        )
        style.addLayer(
            FillLayer(AREA_FILL, LINES_SOURCE)
                .withFilter(Expression.`in`(Expression.get("kind"), areaKinds))
                .withProperties(PropertyFactory.fillColor(areaColour), PropertyFactory.fillOpacity(0.12f), PropertyFactory.visibility(shown))
        )
        style.addLayer(
            LineLayer(AREA_LINE, LINES_SOURCE)
                .withFilter(Expression.`in`(Expression.get("kind"), areaKinds))
                .withProperties(
                    PropertyFactory.lineColor(areaColour), PropertyFactory.lineWidth(1.5f),
                    PropertyFactory.lineDasharray(arrayOf(3f, 2f)), PropertyFactory.visibility(shown)
                )
        )
        style.addLayer(
            LineLayer(PIPELINES, LINES_SOURCE)
                .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("pipeline")))
                .withProperties(
                    PropertyFactory.lineColor("#111111"),
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(5, 1.5f), Expression.stop(12, 3f))),
                    PropertyFactory.lineDasharray(arrayOf(0.1f, 2f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.visibility(shown)
                )
        )
        style.addLayer(
            LineLayer(CABLES, LINES_SOURCE)
                .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("cable")))
                .withProperties(PropertyFactory.linePattern(CABLE_PATTERN), PropertyFactory.lineWidth(7f), PropertyFactory.visibility(shown))
        )
        style.addLayer(
            SymbolLayer(MARKS, MARKS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconSize(
                    Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 0.5f), Expression.stop(10, 0.8f), Expression.stop(14, 1.15f), Expression.stop(17, 1.5f))
                ),
                PropertyFactory.visibility(shown)
            )
        )
    }

    fun setVisible(value: Boolean) {
        visible = value
        val style = mapHolder.style ?: return
        val shown = if (value) Property.VISIBLE else Property.NONE
        runCatching { LAYERS.forEach { style.getLayer(it)?.setProperties(PropertyFactory.visibility(shown)) } }
    }

    /** What was tapped at [point] on screen, as a title and detail lines, or null. */
    fun describeAt(point: PointF): Pair<String, String>? {
        if (!visible) return null
        val map = mapHolder.map ?: return null
        val box = RectF(point.x - TAP_PX, point.y - TAP_PX, point.x + TAP_PX, point.y + TAP_PX)
        val feature = runCatching {
            map.queryRenderedFeatures(box, MARKS).firstOrNull()
                ?: map.queryRenderedFeatures(box, PIPELINES, CABLES).firstOrNull()
                ?: map.queryRenderedFeatures(point, AREA_FILL).firstOrNull()
        }.getOrNull() ?: return null
        fun text(key: String) = feature.getStringProperty(key)?.takeIf { it.isNotBlank() && it != "null" }
        val title = text("label") ?: when (text("kind")) {
            "pipeline" -> "Pipeline"
            "cable" -> "Submarine cable"
            "area_anchorage" -> "Anchorage"
            "area_mooring" -> "Mooring area"
            "area_marina" -> "Marina"
            "area_channel" -> "Shipping channel"
            else -> "Mark"
        }
        val detail = listOfNotNull(text("name"), text("src")?.let { "Source: $it" }, NOT_FOR_NAVIGATION).joinToString("\n")
        return title to detail
    }

    companion object {
        const val CREDIT = "Nav marks: Transport for NSW, WA DoT, Vic Gov, Qld Gov, AMSA, NOPTA, Geoscience Australia, ACMA, © OpenStreetMap"
        const val NOT_FOR_NAVIGATION = "Positions may be old or wrong, and many marks are missing."
        private const val MARKS_SOURCE = "navmarks-src"
        private const val LINES_SOURCE = "navlines-src"
        /** The marks' own layer: other built-in points go under it, so buoys and beacons stay on top. */
        const val MARKS = "navmarks"
        private const val PIPELINES = "navlines-pipelines"
        private const val CABLES = "navlines-cables"
        private const val AREA_FILL = "navlines-area-fill"
        private const val AREA_LINE = "navlines-area-line"
        private const val CABLE_PATTERN = "navlines-cable-wave"
        private val LAYERS = listOf(AREA_FILL, AREA_LINE, PIPELINES, CABLES, MARKS)
        private const val TAP_PX = 24f
    }
}

/**
 * The mark icons, drawn the way they look from the boat in IALA region A: beacons as a pile
 * with their topmark, buoys as a floating shape (can for port, cone for starboard, pillar
 * for the rest), lights as a post with a lamp. Keys are "category|structure".
 */
object NavMarkIcons {
    val KEYS = listOf(
        "card_e|beacon", "card_e|buoy", "card_n|beacon", "card_n|buoy", "card_s|beacon", "card_s|buoy", "card_w|beacon", "card_w|buoy",
        "isolated|beacon", "isolated|buoy", "isolated|virtual", "lead|beacon", "lead|light", "lighthouse|light", "light|light",
        "mooring|beacon", "mooring|buoy", "other|beacon", "other|buoy", "other|other", "other|virtual", "port|beacon", "port|buoy",
        "port|virtual", "safe|beacon", "safe|buoy", "special|beacon", "special|buoy", "special|virtual", "stbd|beacon", "stbd|buoy",
        "stbd|virtual", "wreck|other", "pref_port|beacon", "pref_port|buoy", "pref_stbd|beacon", "pref_stbd|buoy"
    )

    private const val S = 2f // drawn at 2x for sharp icons
    private val RED = Color.parseColor("#E3262F")
    private val GREEN = Color.parseColor("#17A34A")
    private val YEL = Color.parseColor("#F5C518")
    private val BLK = Color.parseColor("#111111")
    private val GREY = Color.parseColor("#94A3B8")
    private val ORANGE = Color.parseColor("#F97316")
    private val MAG = Color.parseColor("#D6249F")
    private val WATER = Color.parseColor("#38BDF8")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = BLK; strokeJoin = Paint.Join.ROUND }

    private fun bodyColours(cat: String): IntArray = when (cat) {
        "port" -> intArrayOf(RED)
        "stbd" -> intArrayOf(GREEN)
        "pref_port" -> intArrayOf(RED, GREEN, RED)
        "pref_stbd" -> intArrayOf(GREEN, RED, GREEN)
        "card_n" -> intArrayOf(BLK, YEL)
        "card_s" -> intArrayOf(YEL, BLK)
        "card_e" -> intArrayOf(BLK, YEL, BLK)
        "card_w" -> intArrayOf(YEL, BLK, YEL)
        "isolated" -> intArrayOf(BLK, RED, BLK)
        "safe" -> intArrayOf(RED, Color.WHITE, RED)
        "special" -> intArrayOf(YEL)
        "lead" -> intArrayOf(ORANGE)
        "mooring" -> intArrayOf(Color.parseColor("#F8FAFC"))
        else -> intArrayOf(GREY)
    }

    private fun Canvas.tri(x: Float, y: Float, s: Float, up: Boolean, colour: Int) {
        val p = Path()
        if (up) { p.moveTo(x, y - s); p.lineTo(x + s, y + s * .7f); p.lineTo(x - s, y + s * .7f) }
        else { p.moveTo(x, y + s); p.lineTo(x + s, y - s * .7f); p.lineTo(x - s, y - s * .7f) }
        p.close(); fill.color = colour; drawPath(p, fill); drawPath(p, stroke)
    }

    private fun Canvas.ball(x: Float, y: Float, r: Float, colour: Int) {
        fill.color = colour; drawCircle(x, y, r, fill); drawCircle(x, y, r, stroke)
    }

    private fun Canvas.bands(rect: RectF, colours: IntArray) {
        val h = rect.height() / colours.size
        colours.forEachIndexed { i, c -> fill.color = c; drawRect(rect.left, rect.top + i * h, rect.right, rect.top + (i + 1) * h + .5f, fill) }
    }

    private fun Canvas.topmark(cat: String, x: Float, y: Float) {
        when (cat) {
            "card_n" -> { tri(x, y - 7, 4f, true, BLK); tri(x, y + 1, 4f, true, BLK) }
            "card_s" -> { tri(x, y - 7, 4f, false, BLK); tri(x, y + 1, 4f, false, BLK) }
            "card_e" -> { tri(x, y - 8, 4f, true, BLK); tri(x, y + 2, 4f, false, BLK) }
            "card_w" -> { tri(x, y - 6, 4f, false, BLK); tri(x, y + 1, 4f, true, BLK) }
            "isolated" -> { ball(x, y - 7, 3.2f, BLK); ball(x, y, 3.2f, BLK) }
            "safe" -> ball(x, y - 3, 4f, RED)
            "special" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YEL; strokeWidth = 3f; style = Paint.Style.STROKE }
                drawLine(x - 4, y - 7, x + 4, y + 1, p); drawLine(x + 4, y - 7, x - 4, y + 1, p)
            }
            "port", "pref_port" -> { fill.color = RED; drawRect(x - 4.5f, y - 8, x + 4.5f, y + 1, fill); drawRect(x - 4.5f, y - 8, x + 4.5f, y + 1, stroke) }
            "stbd", "pref_stbd" -> tri(x, y - 3, 5.5f, true, GREEN)
        }
    }

    private val TOPMARKED = setOf("port", "stbd", "pref_port", "pref_stbd", "card_n", "card_e", "card_s", "card_w", "isolated", "safe", "special")

    fun draw(key: String): Bitmap {
        val (cat, struct) = key.split("|").let { it[0] to it.getOrElse(1) { "other" } }
        val w = 34; val h = 40
        val bmp = Bitmap.createBitmap((w * S).toInt(), (h * S).toInt(), Bitmap.Config.ARGB_8888)
        bmp.density = 320 // 2x, so MapLibre shows it at half its pixel size
        val c = Canvas(bmp); c.scale(S, S)
        val x = 17f; val base = 34f
        when {
            cat == "wreck" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLK; strokeWidth = 2f }
                c.drawLine(6f, 22f, 28f, 22f, p); c.drawLine(12f, 15f, 12f, 29f, p); c.drawLine(17f, 13f, 17f, 31f, p); c.drawLine(22f, 15f, 22f, 29f, p)
            }
            struct == "light" -> {
                if (cat == "lighthouse") {
                    val p = Path().apply { moveTo(x - 4, base); lineTo(x - 2.5f, 18f); lineTo(x + 2.5f, 18f); lineTo(x + 4, base); close() }
                    fill.color = Color.WHITE; c.drawPath(p, fill); c.drawPath(p, stroke)
                    fill.color = RED; c.drawRect(x - 3.5f, 15f, x + 3.5f, 19f, fill); c.drawRect(x - 3.5f, 15f, x + 3.5f, 19f, stroke)
                } else {
                    fill.color = Color.parseColor("#334155"); c.drawRect(x - 1.5f, 20f, x + 1.5f, base, fill); c.drawRect(x - 1.5f, 20f, x + 1.5f, base, stroke)
                    c.ball(x, 19f, 3.2f, if (cat == "lead") ORANGE else Color.parseColor("#FDE047"))
                    fill.color = BLK; c.drawRect(x - 5, base - 1, x + 5, base + 1, fill)
                }
            }
            struct == "virtual" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MAG; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f) }
                c.drawCircle(x, 22f, 9f, p); c.topmark(cat, x, 25f)
            }
            struct == "beacon" -> {
                c.bands(RectF(x - 2, 15f, x + 2, base), bodyColours(cat)); c.drawRect(x - 2, 15f, x + 2, base, stroke)
                when {
                    cat in TOPMARKED -> c.topmark(cat, x, 12f)
                    cat == "lead" -> c.tri(x, 9f, 5f, true, ORANGE)
                    else -> c.ball(x, 11f, 3f, GREY)
                }
                fill.color = BLK; c.drawRect(x - 6, base - 1, x + 6, base + 1, fill)
            }
            struct == "buoy" -> {
                val shape = Path().apply {
                    when (cat) {
                        "port" -> addRect(x - 7, base - 15, x + 7, base - 2, Path.Direction.CW)
                        "stbd" -> { moveTo(x - 8, base - 2); lineTo(x, base - 18); lineTo(x + 8, base - 2); close() }
                        "mooring", "other" -> addCircle(x, base - 8, 7f, Path.Direction.CW)
                        else -> { moveTo(x - 8, base - 2); lineTo(x - 3, base - 20); lineTo(x + 3, base - 20); lineTo(x + 8, base - 2); close() }
                    }
                }
                c.save(); c.clipPath(shape); c.bands(RectF(x - 9, base - 21, x + 9, base - 1), bodyColours(cat)); c.restore()
                c.drawPath(shape, stroke)
                if (cat !in setOf("port", "stbd", "mooring", "other")) c.topmark(cat, x, base - 24)
                val wave = Path().apply { moveTo(x - 11, base); quadTo(x - 5, base - 2, x, base); quadTo(x + 5, base + 2, x + 11, base) }
                c.drawPath(wave, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = WATER; strokeWidth = 1.5f })
            }
            else -> c.ball(x, 22f, 5f, GREY)
        }
        return bmp
    }

    /** A short red wave, repeated along submarine cables. */
    fun cableWave(): Bitmap {
        val w = 16; val h = 7
        val bmp = Bitmap.createBitmap((w * S).toInt(), (h * S).toInt(), Bitmap.Config.ARGB_8888)
        bmp.density = 320
        val c = Canvas(bmp); c.scale(S, S)
        val p = Path()
        for (i in 0..w) {
            val y = h / 2f + sin(i.toDouble() / w * PI * 2).toFloat() * 2.2f
            if (i == 0) p.moveTo(0f, y) else p.lineTo(i.toFloat(), y)
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#DC2626"); strokeWidth = 1.4f })
        return bmp
    }
}
