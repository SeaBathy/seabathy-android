package au.opendepth.app.conditions

import android.app.Dialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MapLayerIds
import au.opendepth.app.map.MapStyleFactory
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.SeaSpeedUnit
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sea conditions for fishing: surface currents (tidal flow included), waves,
 * swell and wind forecast hourly for up to 8 days, plus the latest satellite
 * sea temperature and chlorophyll. Downloaded for the area on screen and
 * saved, so it works offline at sea. Shown as a colour layer (with its own
 * opacity) and arrows over the chart, a time slider to step through the
 * forecast, and the values under the crosshair.
 */
class ConditionsFeature(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val preferences: SharedPreferences,
    private val units: () -> NavUnits,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun toast(message: String)
        fun showArea(bounds: GeoBounds)
        /** The data credits changed (shown or hidden). */
        fun creditsChanged()
        /** Working internet now (checked, not just a network). */
        fun isOnline(): Boolean
        /** The phone's last known position, for following the GPS before this app has had a fix. */
        fun lastKnownPosition(): Pair<Double, Double>?
        /** Turns the GPS on, if it's off. */
        fun startGps()
        /** Navigating a route: the navigation display has the top of the chart. */
        fun isNavigating(): Boolean
    }

    private val store = ConditionsStore(File(activity.filesDir, "conditions"))

    /**
     * Reading and drawing the weather run on their own thread (short jobs, at
     * normal priority, so the picture comes up quickly); downloads on another
     * at background priority, so a long download and its parsing never take
     * the processor from the chart's survey tiles, nor hold up a picture.
     */
    private val work = conditionsThread("sea-conditions", android.os.Process.THREAD_PRIORITY_DEFAULT)
    private val downloads = conditionsThread("sea-conditions-download", android.os.Process.THREAD_PRIORITY_BACKGROUND)
    private val client = ConditionsClient.forApp(preferences)

    /** What to download and when, one at a time: see [WeatherEngine]. */
    private val engine: WeatherEngine by lazy {
        WeatherEngine(
            store = store,
            net = object : WeatherNet {
                private fun grid(south: Double, west: Double, north: Double, east: Double, perSide: Int): GeoGrid =
                    GeoGrid.covering(south, west, north, east, max(MIN_STEP_DEG, max(north - south, east - west) / (perSide - 1)), perSide)

                override fun forecast(south: Double, west: Double, north: Double, east: Double, days: Int, userAsked: Boolean, keepSea: ForecastGrid?, onProgress: (Double) -> Unit): ForecastGrid =
                    client.forecast(
                        grid(south, west, north, east, MAX_POINTS_PER_SIDE), days,
                        grid(south, west, north, east, MAX_WIND_POINTS_PER_SIDE), userAsked, keepSea, onProgress
                    )

                override fun backupForecast(south: Double, west: Double, north: Double, east: Double, days: Int): ForecastGrid =
                    client.backupForecast(grid(south, west, north, east, MAX_POINTS_PER_SIDE), days, grid(south, west, north, east, MAX_WIND_POINTS_PER_SIDE))
                override fun satellite(kind: SatelliteKind, south: Double, west: Double, north: Double, east: Double, depthM: Int) =
                    client.satellite(kind, south, west, north, east, depthM)
                override fun seaAt(points: List<Pair<Double, Double>>) = client.seaAt(points)
                override fun latestRuns() = client.latestRuns(windModelHere())
                override fun coastline(south: Double, west: Double, north: Double, east: Double) {
                    if (coastline.fetch(south, west, north, east) > 0) throw java.io.IOException("some coastline tiles didn't come")
                }
            },
            kv = object : WeatherKeyValues {
                // Read directly: asked on every GPS fix. (A value saved as another type by an old version: the slow way.)
                override fun get(key: String): String? =
                    runCatching { preferences.getString(key, null) }.getOrElse { preferences.all[key]?.toString() }
                override fun put(key: String, value: String?) {
                    preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
                }
            },
            settings = { WeatherSettings(days, withSatellite, belowDepthM, keepUpdated) },
            clock = System::currentTimeMillis,
            online = ::online,
            scope = scope,
            io = downloads,
            parallel = Dispatchers.IO,
            timer = { delayMs, run ->
                val r = Runnable(run)
                ticker.postDelayed(r, delayMs)
                ({ ticker.removeCallbacks(r) })
            },
            events = object : WeatherEvents {
                override fun saved(area: ConditionsArea, isSpot: Boolean) = onSaved(area, isSpot)
                override fun stateChanged(state: WeatherState) {
                    updateBox()
                    refreshDialog()
                }
                override fun finished(area: ConditionsArea?, problems: List<String>, userAsked: Boolean, note: String?, alreadyLatest: Boolean) =
                    onFinished(area, problems, userAsked, note, alreadyLatest)
                override fun coastlineReady() {
                    boatReadingCache = null
                    maskCache = null
                    areaSea = null
                    flowGridKey = null
                    lastPictureKey = null
                    if (shown) redraw()
                }
            }
        )
    }
    /**
     * The wind model the service uses where the spot is, asked once for each stretch of water (and
     * again after a week): so the right model's new runs are watched for. Null when not known.
     * On the download thread.
     */
    private fun windModelHere(): String? {
        val at = engine.spotArea?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 } ?: gpsFix ?: return null
        val place = String.format(Locale.US, "%.0f,%.0f", at.first / 2, at.second / 2)
        val saved = preferences.getString(KEY_WIND_MODEL, null)?.split('|')
        if (saved != null && saved.size == 3 && saved[0] == place && System.currentTimeMillis() - (saved[2].toLongOrNull() ?: 0L) in 0 until WIND_MODEL_KEEP_MS) {
            return saved[1].ifEmpty { null }
        }
        val model = runCatching { client.windModelAt(at.first, at.second) }.getOrElse { return saved?.getOrNull(1)?.ifEmpty { null } }
        preferences.edit().putString(KEY_WIND_MODEL, "$place|${model.orEmpty()}|${System.currentTimeMillis()}").apply()
        DiagnosticLog.log(TAG, "the wind here comes from ${model ?: "a model that isn't one of the usual three"}: its new runs are watched for")
        return model
    }

    private val palette = AppTheme.palette

    private var shown = preferences.getBoolean(KEY_SHOWN, false)
    private var areaId: String? = preferences.getString(KEY_AREA, null)
    private var colour = runCatching { ColourChoice.valueOf(preferences.getString(KEY_COLOUR, null)!!) }.getOrNull()
        ?.takeIf { it != ColourChoice.NONE } ?: ColourChoice.CURRENT
    /** The lines always go with what the map shows: wind lines on wind, the current under the rest. */
    private val arrows = ArrowChoice.SAME

    /** The flow lines' choice with "same as the map layer" worked out. */
    private val flow: ArrowChoice get() = arrows.resolve(colour)
    private var opacity = preferences.getFloat(KEY_OPACITY, 0.65f).coerceIn(0f, 1f)
    private var days = preferences.getInt(KEY_DAYS, DEFAULT_DAYS).coerceIn(1, ConditionsClient.MAX_DAYS)
    private var withSatellite = preferences.getBoolean(KEY_SATELLITE, true)
    /** How far down "temperature below the surface" is downloaded for, in metres. */
    private var belowDepthM = preferences.getInt(KEY_BELOW_DEPTH, ConditionsArea.DEFAULT_BELOW_DEPTH_M)
    /** A range for the temperature colours, in °C; null stretches them to the area. */
    private var temperatureRange: Pair<Double, Double>? = preferences.getString(KEY_TEMP_RANGE, null)?.split(',')
        ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 && it[0] < it[1] }?.let { it[0] to it[1] }
    /** Temperature lines: 0 off, -1 automatic, else the step in the user's degrees. */
    private var contourStep: Double = preferences.getFloat(KEY_CONTOUR_STEP, -1f).toDouble()
    /** A temperature band to chase, in °C: shown bright, the rest dimmed; null for none. */
    private var targetBand: Pair<Double, Double>? = preferences.getString(KEY_BAND, null)?.split(',')
        ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 && it[0] < it[1] }?.let { it[0] to it[1] }
    private var eddyMode = runCatching { EddyMode.valueOf(preferences.getString(KEY_EDDIES, null)!!) }.getOrDefault(EddyMode.WITH_SEA_LEVEL)
    private var directionStyle = runCatching { DirectionStyle.valueOf(preferences.getString(KEY_STYLE, null)!!) }.getOrDefault(DirectionStyle.FLOW)
    private var boxShown = preferences.getBoolean(KEY_BOX, true)

    // The spot: where the box reads conditions, and what is kept downloaded.
    private var followGps = preferences.getBoolean(KEY_SPOT_GPS, true)
    private var fixedSpot: Pair<Double, Double>? = preferences.getString(KEY_SPOT, null)?.split(',')
        ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    /** The latest GPS position: this run's, or the one remembered from last time until there's a fix. */
    private var gpsFix: Pair<Double, Double>? = preferences.getString(KEY_LAST_FIX, null)?.split(',')
        ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    private var fixSavedAt = 0L
    /** Hours between automatic updates of the spot's download; 0 = only when asked. */
    private var autoHours = preferences.getInt(KEY_AUTO_HOURS, if (preferences.getBoolean(KEY_AUTO, true)) 6 else 0)
    private val keepUpdated: Boolean get() = autoHours > 0
    private var boxOpen = preferences.getBoolean(KEY_BOX_OPEN, false)
    private var onlineAt = 0L
    private var onlineNow = true
    private var picking = false
    /** The map's parent, for the alert place picker. */
    private var boundRoot: ViewGroup? = null
    /** The area kept around the spot, when there is one (the engine keeps it). */
    private val spotArea: ConditionsArea? get() = engine.spotArea

    /** Stepping through the forecast by itself. */
    private var playing = false
    private val play = object : Runnable {
        override fun run() {
            if (!playing) return
            step(1, wrap = true, fromPlay = true)
            ticker.postDelayed(this, PLAY_STEP_MS)
        }
    }

    /** Following the clock, or a time picked on the slider. */
    private var live = true
    private var pickedMillis = 0L
    private val timeMillis: Long get() = if (live) System.currentTimeMillis() else pickedMillis

    private var area: ConditionsArea? = null
    private var drawJob: Job? = null
    /** A download is under way (the engine's). */
    private val downloading: Boolean get() = engine.busy
    /** What's downloading now, or why a download waits (the free limit, a retry), for the settings sheet. */
    private val status: String? get() = when (val st = engine.state) {
        is WeatherState.Working -> st.what
        is WeatherState.Waiting -> st.reason.replaceFirstChar { it.uppercase() }
        else -> null
    }
    private var dialog: Dialog? = null
    private var dialogBody: LinearLayout? = null
    private var cameraListening = false

    /** The last picture drawn, kept to put back after a style change; and what it was drawn from. */
    private var lastBitmap: Bitmap? = null
    private var lastQuad: LatLngQuad? = null
    /** What the last picture covers (south, west, north, east): the whole area, or the part on screen. */
    private var lastRegion: DoubleArray? = null
    /** Where the sea ends, to cut the picture at the shore. */
    private val coastline = Coastline(activity)
    /** The last coastline mask, by region and size: it doesn't change with the time shown. */
    private var maskCache: Pair<String, Bitmap?>? = null
    private var coastFetching = false
    private var coastTriedAt = 0L
    private var lastPictureKey: String? = null
    /** What the drawing under way is of. */
    private var drawingKey: String? = null
    private var drawingArrows: ArrowChoice? = null
    private var drawingContours: String? = null
    private var contourCache: Pair<String, String>? = null
    /** The lines now on the map, so an unchanged set isn't sent again each forecast step. */
    private var shownContours: String? = null
    private var drawingStyle: DirectionStyle? = null
    private var scaleCache: Pair<String, ColourScale>? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (shown && live) redraw() else updateBox()
            engine.checkRun()
            engine.setSpot(spotTarget())
            area?.let { engine.keepFresh(it) }
            ticker.postDelayed(this, TICK_MS)
        }
    }

    // Views ------------------------------------------------------------------------

    private val title = TextView(activity).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.primaryText)
        maxLines = 2
    }
    private val legendBar = View(activity)
    private val legendLow = legendLabel(Gravity.START)
    private val legendMid = legendLabel(Gravity.CENTER)
    private val legendHigh = legendLabel(Gravity.END)
    private val legend = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(legendBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8)).apply { topMargin = activity.dp(4) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(legendLow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(legendMid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(legendHigh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }
    private val slider = SeekBar(activity).apply {
        progressTintList = ColorStateList.valueOf(palette.accent)
        thumbTintList = ColorStateList.valueOf(palette.accent)
        contentDescription = "Forecast time"
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val forecast = area?.forecast ?: return
                setPlaying(false)
                live = false
                pickedMillis = forecast.times[progress.coerceIn(0, forecast.times.size - 1)]
                redraw()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }
    private val nowButton = smallButton("Now") {
        setPlaying(false)
        live = true
        redraw()
    }
    private val playButton = smallButton(PLAY) { setPlaying(!playing) }.apply {
        contentDescription = "Play the forecast"
        // Wide enough for "Pause" too, so the slider doesn't jump.
        minWidth = activity.dp(66)
        minimumWidth = activity.dp(66)
    }
    private val timeRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(playButton)
        addView(smallButton("◀") { step(-1) }.apply { contentDescription = "An hour earlier" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = activity.dp(4) })
        addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallButton("▶") { step(1) }.apply { contentDescription = "An hour later" })
        addView(nowButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dp(38)).apply { marginStart = activity.dp(4) })
    }

    /** Says when what's shown is old saved data. */
    private val cardWarning = warningText()

    /** The card at the bottom while conditions are shown: what and when, the colour key, and the time slider. */
    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.roundedBackground(0xEE111827.toInt(), palette.border, 14)
        setPadding(activity.dp(12), activity.dp(8), activity.dp(8), activity.dp(6))
        elevation = activity.dp(5).toFloat()
        visibility = View.GONE
        isClickable = true
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallButton("⚙") { show() }.apply { contentDescription = "Sea conditions settings" })
            addView(smallButton("✕") { setShown(false) }.apply { contentDescription = "Hide sea conditions" })
        }
        addView(head)
        addView(cardWarning)
        addView(legend)
        addView(timeRow)
    }

    /**
     * The box under the tide chip: wind, waves and current at the spot, or
     * everything at the crosshair while the map layer is on. Tap for settings.
     */
    private val boxTitle = TextView(activity).apply {
        textSize = 11f
        setTextColor(palette.secondaryText)
    }
    private val boxWarning = warningText()
    /** Green: the latest forecast. Amber: updating. Red: out of date (tap to update). */
    private val boxDot = View(activity).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(FRESH_COLOUR) }
        visibility = View.GONE
        setOnClickListener { freshnessTapped() }
    }
    /** The area the box's dot is about. */
    private var dotArea: ConditionsArea? = null
    /** Opens the box to everything, or closes it to the wind only. */
    private val boxToggle = TextView(activity).apply {
        textSize = 16f
        setTextColor(palette.primaryText)
        setPadding(activity.dp(12), 0, 0, 0)
        minWidth = activity.dp(36)
        gravity = Gravity.CENTER
        text = if (boxOpen) OPEN_MARK else CLOSED_MARK
        contentDescription = if (boxOpen) "Show less" else "Show waves, swell, current and sea"
        setOnClickListener {
            boxOpen = !boxOpen
            preferences.edit().putBoolean(KEY_BOX_OPEN, boxOpen).apply()
            text = if (boxOpen) OPEN_MARK else CLOSED_MARK
            contentDescription = if (boxOpen) "Show less" else "Show waves, swell, current and sea"
            updateBox()
        }
    }
    private val boxRows = List(BOX_ROWS) { BoxRow() }
    private val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.roundedBackground(0xE6111827.toInt(), palette.border, 14)
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(7))
        elevation = activity.dp(4).toFloat()
        visibility = View.GONE
        contentDescription = "Sea conditions — open the settings"
        setOnClickListener { show() }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // A small dot with a bigger touch area round it.
            addView(android.widget.FrameLayout(activity).apply {
                setPadding(0, activity.dp(4), activity.dp(7), activity.dp(4))
                addView(boxDot, android.widget.FrameLayout.LayoutParams(activity.dp(9), activity.dp(9), Gravity.CENTER))
                setOnClickListener { freshnessTapped() }
            })
            addView(boxTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(boxToggle)
        })
        boxRows.forEach { addView(it.view) }
        addView(boxWarning)
    }

    /** The box's size, for keeping the map's compass clear of it. */
    val boxView: View get() = box

    /** The card along the bottom while sea conditions are shown, for keeping the chart's scale bar above it. */
    val cardView: View get() = card

    /** One line of the box. Tapping it shows that on the map (or hides it, when it's already showing). */
    private inner class BoxRow {
        var layer: ColourChoice? = null
        /** The area the line's values came from: tapping shows that one. */
        var from: ConditionsArea? = null
        val arrow = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val text = TextView(activity).apply {
            textSize = 13f
            setTextColor(palette.primaryText)
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(4), activity.dp(3), activity.dp(6), activity.dp(3))
            minimumHeight = activity.dp(30)
            isClickable = true
            addView(arrow, LinearLayout.LayoutParams(activity.dp(18), activity.dp(18)).apply { marginEnd = activity.dp(6) })
            addView(text)
            setOnClickListener { layer?.let { toggleLayer(boxStep(it, from), from) } }
        }

        fun highlight(on: Boolean) {
            view.background = if (on) activity.roundedBackground(0x3319C6B3, palette.accent, 8) else null
            text.setTextColor(if (on) palette.accent else palette.primaryText)
        }

        /** What the picture shows: the arrow, or a sun or moon by its [SkyIcon.key]. */
        private var iconKey: String? = null

        fun showIcon(icon: SkyIcon?) {
            val key = icon?.key ?: ARROW_ICON
            if (key == iconKey) return
            iconKey = key
            if (icon == null) arrow.setImageBitmap(arrowImage) else arrow.setImageDrawable(icon.drawable(activity.dp(18)))
        }
    }

    private val arrowImage: Bitmap by lazy { arrowBitmap() }

    /**
     * The box's line was tapped: show that on the map, from the area [from]
     * the box was reading (the spot's, usually); or hide it if it's showing.
     */
    private fun toggleLayer(layer: ColourChoice, from: ConditionsArea? = null) {
        if (shown && colour == layer && (from == null || from.id == area?.id)) {
            setShown(false)
            return
        }
        colour = layer
        preferences.edit().putString(KEY_COLOUR, colour.name).apply()
        lastPictureKey = null
        if (from != null && from.id != area?.id) {
            area = from
            areaId = from.id
            preferences.edit().putString(KEY_AREA, from.id).apply()
            scaleCache = null
            callbacks.creditsChanged()
        }
        if (area == null) area = spotArea
        if (!shown) setShown(true) else redraw()
        refreshDialog()
    }

    /**
     * What a tap on a box line shows: its [layer], except that the Sea line steps through its
     * maps, sea temperature, then chlorophyll (when the area has it), then off. Giving back what
     * already shows is what makes [toggleLayer] hide it.
     */
    private fun boxStep(layer: ColourChoice, from: ConditionsArea?): ColourChoice {
        val a = from ?: area ?: return layer
        val sameArea = from == null || from.id == area?.id
        if (!shown || !sameArea || rowNameOf(layer) != "Sea" || rowNameOf(colour) != "Sea") return layer
        if (colour == ColourChoice.CHLOROPHYLL) return colour
        return if (SatelliteKind.CHLOROPHYLL in a.satellites) ColourChoice.CHLOROPHYLL else colour
    }

    /** The flow lines, drawn over the map and under everything else. */
    private val flowLines = FlowLinesView(activity, mapHolder)

    // Setup ------------------------------------------------------------------------

    private var topViews: () -> List<View> = { emptyList() }

    /**
     * Adds the flow lines over [mapView], the bottom card, and the values box
     * under the lowest of [topViews] on the right; [bottomViews] are kept clear of.
     */
    fun bind(root: ViewGroup, mapView: View, topViews: () -> List<View>, bottomViews: List<View>) {
        this.topViews = topViews
        boundRoot = root
        // Every change of the camera, however it came about: a mouse wheel moves the map with no
        // gesture, so nothing says it has come to rest. A moment after the last change counts as that.
        (mapView as? org.maplibre.android.maps.MapView)?.addOnCameraDidChangeListener {
            ticker.removeCallbacks(cameraSettled)
            ticker.postDelayed(cameraSettled, CAMERA_SETTLE_MS)
        }
        root.addView(flowLines, root.indexOfChild(mapView) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flowLines.visibility = View.GONE
        root.addView(card, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginStart = activity.dp(8)
            marginEnd = activity.dp(8)
            bottomMargin = activity.dp(CARD_BOTTOM_DP)
        })
        root.addView(box, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = activity.dp(10)
        })
        root.addView(pickBanner, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            marginStart = activity.dp(8)
            marginEnd = activity.dp(8)
        })
        boxRows.forEach { it.showIcon(null) }
        // Sit above the route, measure or hint card when one is up, rather than behind it.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val rootHeight = root.height
            if (rootHeight == 0) return@addOnGlobalLayoutListener
            // The box sits just under the tide chip (or the top bar, or the navigation display).
            val below = (this.topViews().filter { it.visibility == View.VISIBLE }.maxOfOrNull { it.bottom } ?: 0) + activity.dp(6)
            (box.layoutParams as ViewGroup.MarginLayoutParams).let { if (it.topMargin != below) { it.topMargin = below; box.requestLayout() } }
            (pickBanner.layoutParams as ViewGroup.MarginLayoutParams).let { if (it.topMargin != below) { it.topMargin = below; pickBanner.requestLayout() } }
            val clear = bottomViews.filter { it.visibility == View.VISIBLE && it.height > 0 }
                .maxOfOrNull { rootHeight - it.top } ?: 0
            val lift = max(activity.dp(CARD_BOTTOM_DP), clear + activity.dp(6))
            val params = card.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != lift) {
                params.bottomMargin = lift
                card.requestLayout()
            }
        }
        scope.launch {
            val started = System.nanoTime()
            val (picked, spot) = withContext(work) { pickArea() to store.load(SPOT_ID) }
            DiagnosticLog.log(TAG, "saved areas read in ${(System.nanoTime() - started) / 1_000_000} ms")
            area = picked ?: spot
            engine.start(spot)
            // Last time's picture straight away; the fresh one replaces it when drawn.
            val choice = colour
            if (shown && picked != null && lastBitmap == null) {
                withContext(work) { loadPreview(picked, choice) }?.let { showPicture(picked, it, "preview") }
            }
            if (shown) redraw() else updateBox()
            callbacks.creditsChanged()
            removeOldAreas()
            // Let the chart and its layers load first; the update can wait a moment.
            delay(STARTUP_DOWNLOAD_DELAY_MS)
            startupDone = true
            engine.setSpot(spotTarget())
            engine.checkRun(force = true)
        }
    }

    /**
     * What the sea is doing at a point now, as the values box words it, for
     * a waypoint photo's record. The sun and moon are always there; the rest
     * when a downloaded area covers the point.
     */
    fun snapshot(lat: Double, lng: Double, now: Long = System.currentTimeMillis(), withSatellite: Boolean = true): List<String> {
        val format = NavFormat(units())
        val a = listOfNotNull(area, spotArea).firstOrNull { it.contains(lat, lng) }
        var list = if (a != null) values(a, lat, lng, now, format) else skyValues(lat, lng, now)
        // The satellite layers are of today: not for an older photo.
        if (!withSatellite) list = list.filter { it.row !in SATELLITE_ROWS }
        return list.map { it.spoken }
    }

    /** Read only, for the drift planner: the saved area (on show, or round the spot) covering a point, or null. */
    fun areaCovering(lat: Double, lng: Double): ConditionsArea? = listOfNotNull(area, spotArea).firstOrNull { it.contains(lat, lng) }

    /** Read only, for the plotter export: the area on show, what its colours show, and the lines drawn over it (GeoJSON); null when hidden. */
    fun shownForExport(): Triple<ConditionsArea, ColourChoice, String?>? = area?.takeIf { shown }?.let { Triple(it, colour, shownContours) }

    /** A GPS fix: the spot follows it when set to. */
    fun onLocation(lat: Double, lng: Double) {
        gpsFix = lat to lng
        // Remembered now and then, not on every fix.
        val now = System.currentTimeMillis()
        if (now - fixSavedAt > FIX_SAVE_MS) {
            fixSavedAt = now
            preferences.edit().putString(KEY_LAST_FIX, String.format(Locale.US, "%.5f,%.5f", lat, lng)).apply()
        }
        if (!followGps) return
        updateBox()
        if (startupDone) engine.setSpot(spotTarget())
    }

    /**
     * The network says it has working internet. Android says so on every
     * small change (signal, network type), not just when it comes back, so
     * only a return from offline (seen by [online]) checks straight away.
     */
    fun onConnectivityRestored() {
        if (!wasOffline) return
        wasOffline = false
        onlineAt = 0L
        DiagnosticLog.log(TAG, "back online: checking for updates")
        engine.onOnline()
        updateBox()
    }

    /** Seen offline since the last check; cleared when back online. */
    private var wasOffline = false

    /** The saved data has been read and the start-up check done. */
    private var startupDone = false

    fun onResume() {
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, TICK_MS)
        if (shown) redraw() else updateBox()
        // Back to the app after a while: is there a newer forecast? (At start-up
        // the saved data is still being read: the start-up check comes after.)
        if (startupDone) {
            engine.checkRun()
            engine.setSpot(spotTarget())
            // The alert's check may have saved a forecast while the app was away.
            scope.launch {
                withContext(work) {
                    store.forget(ConditionsAlerts.AREA_PREFIX)
                    store.summaries()
                }
                showNewestHere()
            }
        }
    }

    fun onPause() {
        ticker.removeCallbacks(tick)
        setPlaying(false)
        // No drawing while the app is in the background.
        stopFlowLines()
    }

    /** Units changed in Settings: the box and the card show their values again in the new ones. */
    fun unitsChanged() {
        val a = area
        if (shown && a != null) refreshCard(a)
        updateBox()
        // The temperature lines' labels and steps follow the unit.
        if (shown) redraw()
    }

    fun release() {
        work.close()
        downloads.close()
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(play)
        ticker.removeCallbacks(cameraSettled)
        drawJob?.cancel()
    }

    /** The style was rebuilt: everything custom is gone, so put the layers back. */
    fun onStyleInstalled() {
        listenToCamera()
        val style = mapHolder.style ?: return
        runCatching {
            if (style.getImage(ARROW_IMAGE) == null) style.addImage(ARROW_IMAGE, arrowBitmap())
            if (style.getSource(ARROW_SOURCE) == null) style.addSource(GeoJsonSource(ARROW_SOURCE, MapLayerIds.EMPTY_GEOJSON))
            if (style.getLayer(ARROW_LAYER) == null) {
                style.addLayerAbove(
                    SymbolLayer(ARROW_LAYER, ARROW_SOURCE).withProperties(
                        PropertyFactory.iconImage(ARROW_IMAGE),
                        PropertyFactory.iconRotate(Expression.get("rot")),
                        PropertyFactory.iconSize(Expression.get("size")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                        PropertyFactory.iconOpacity(arrowOpacity())
                    ),
                    MapLayerIds.OVERLAY_ANCHOR
                )
            }
        }.onFailure { DiagnosticLog.logError(TAG, "installing arrow layer FAILED", it) }
        installContourLayers(style)
        // A new style starts with an empty source.
        shownContours = null
        // A new style has none of the layers: put the picture back (drawn
        // before, or saved last time) rather than drawing it again.
        val a = area
        val bitmap = lastBitmap
        if (shown && a != null && bitmap != null) showPicture(a, bitmap, lastPictureKey ?: "", lastRegion)
        if (shown) redraw() else clearMap()
    }

    /** The map has stopped moving: the box, the newest data here, and the picture for what's on screen now. */
    private val cameraSettled = Runnable {
        updateBox()
        showNewestHere()
        // Zoomed in or out, or moved on.
        val a = area
        if (shown && a != null && lastRegion?.let { regionKey(it) } != regionKey(pictureRegion(a))) redraw()
    }

    private fun listenToCamera() {
        if (cameraListening) return
        val map = mapHolder.map ?: return
        cameraListening = true
        map.addOnCameraMoveListener { updateBox() }
        map.addOnCameraIdleListener {
            ticker.removeCallbacks(cameraSettled)
            cameraSettled.run()
        }
    }

    /** The credits for whatever is shown, for the attribution line. */
    fun credits(): List<String> {
        val a = area ?: return emptyList()
        if (!shown) return emptyList()
        val sources = buildList {
            val f = a.forecast
            // The main service: all of the forecast, its sea alone under the backup's wind, or under the backup its wind alone (the one with gusts).
            if (f != null && (!a.isBackup || f.has(ForecastField.GUST))) add("Open-Meteo (CC BY 4.0): Copernicus Marine, Météo-France, ECMWF, DWD, NOAA")
            // The backup forecast, or its wind standing in for the main one's.
            if (f != null && a.isBackup) add("NOAA GFS and WaveWatch III (PacIOOS)")
            else if (f != null && a.windFromBackup) add("NOAA GFS (PacIOOS)")
            if (a.satellites.keys.any { it != SatelliteKind.TEMP_BELOW }) add("NOAA CoastWatch")
            if (SatelliteKind.SEA_TEMP in a.satellites) add("NASA JPL MUR")
            if (SatelliteKind.SEA_LEVEL in a.satellites || SatelliteKind.CHLOROPHYLL in a.satellites) add("contains modified Copernicus Sentinel data")
            if (SatelliteKind.SEA_LEVEL in a.satellites) add("AVISO+")
            if (SatelliteKind.TEMP_BELOW in a.satellites) add("HYCOM")
        }
        return if (sources.isEmpty()) emptyList() else listOf("Sea: " + sources.joinToString(", "))
    }

    // Showing ----------------------------------------------------------------------

    private fun setShown(value: Boolean) {
        shown = value
        preferences.edit().putBoolean(KEY_SHOWN, value).apply()
        if (value) redraw() else clearMap()
        callbacks.creditsChanged()
        refreshDialog()
    }

    private fun clearMap() {
        drawJob?.cancel()
        setPlaying(false)
        card.visibility = View.GONE
        stopFlowLines()
        updateBox()
        val style = mapHolder.style ?: return
        runCatching {
            style.getLayer(IMAGE_LAYER)?.let { style.removeLayer(it) }
            style.getSource(IMAGE_SOURCE)?.let { style.removeSource(it) }
            style.getSourceAs<GeoJsonSource>(ARROW_SOURCE)?.setGeoJson(MapLayerIds.EMPTY_GEOJSON)
            style.getSourceAs<GeoJsonSource>(CONTOUR_SOURCE)?.setGeoJson(MapLayerIds.EMPTY_GEOJSON)
        }
        shownContours = null
        lastPictureKey = null
    }

    private fun pickArea(): ConditionsArea? {
        val t0 = System.nanoTime()
        val summaries = store.summaries()
        val t1 = System.nanoTime()
        val id = areaId?.takeIf { wanted -> summaries.any { it.id == wanted } } ?: summaries.firstOrNull()?.id
        return id?.let { store.load(it) }.also {
            DiagnosticLog.log(TAG, "list ${(t1 - t0) / 1_000_000} ms, area ${(System.nanoTime() - t1) / 1_000_000} ms")
        }
    }

    /** Draws the colour picture and arrows for the current choices and time, off the main thread. */
    private fun redraw() {
        val a = area
        if (!shown || a == null) {
            clearMap()
            return
        }
        refreshCard(a)
        updateBox()
        val time = timeMillis
        val choice = colour
        val arrowChoice = flow
        showFlowLines(a, time)
        // A satellite picture doesn't change with the time; a range set for the colours changes any temperature's.
        val region = pictureRegion(a)
        val key = "${a.id}:${a.fetchedMillis}:$choice:${regionKey(region)}:" + (if (choice.forecast) (time / PICTURE_STEP_MS) else 0) +
            (if (choice.isTemperature) ":$temperatureRange:$targetBand" else "")
        // Already drawing this very picture (a style change asks again at start-up): let it finish.
        val u = units()
        val contourKey = "$contourStep:$targetBand:$eddyMode:${u.temperature}"
        if (key == drawingKey && drawJob?.isActive == true && arrowChoice == drawingArrows && directionStyle == drawingStyle && contourKey == drawingContours) return
        drawingContours = contourKey
        drawingKey = key
        drawingArrows = arrowChoice
        drawingStyle = directionStyle
        drawJob?.cancel()
        drawJob = scope.launch {
            val satellite = choice.satellite?.let { a.satellites[it] }
            val picture = if (choice == ColourChoice.NONE || (choice.satellite != null && satellite == null) || (choice.forecast && a.forecast == null)) {
                null
            } else if (key == lastPictureKey) {
                lastBitmap
            } else {
                withContext(work) {
                    val started = System.nanoTime()
                    val sampler = sampler(a, choice, time, clipped = true) ?: return@withContext null
                    val maxSide = satellite?.let { min(MAX_PICTURE_SIDE, max(it.grid.rows, it.grid.cols) * 2) } ?: FORECAST_PICTURE_SIDE
                    val band = targetBand?.takeIf { choice.isTemperature }?.let { it.first..it.second }
                    // The values change gently from place to place, so they're worked out small;
                    // the shore is then cut at the size of the screen ([sharpen]).
                    val side = if (isWhole(a, region)) max(64, maxSide) else VALUES_REGION_SIDE
                    val pixels = ConditionsPicture.render(region[0], region[1], region[2], region[3], side, scaleFor(a, choice), sampler, band) { !isActive }
                    if (!isActive) return@withContext null
                    sharpen(a, region, pixels).also {
                        DiagnosticLog.log(TAG, "picture $choice ${pixels.width}x${pixels.height}, at the shore ${it.width}x${it.height}, in ${(System.nanoTime() - started) / 1_000_000} ms")
                    }
                }
            }
            val arrowJson = if (directionStyle.arrows) withContext(work) { arrowGeoJson(a, arrowChoice, time, region) } else MapLayerIds.EMPTY_GEOJSON
            // The lines don't change with the forecast time: worked out once per area and settings.
            val linesKey = "${a.id}:${a.fetchedMillis}:$choice:$contourKey"
            val contourJson = contourCache?.takeIf { it.first == linesKey }?.second ?: withContext(work) {
                runCatching { contourGeoJson(a, choice, u) }.onFailure { DiagnosticLog.logError(TAG, "contours FAILED", it) }.getOrNull()
            }?.also { contourCache = linesKey to it } ?: MapLayerIds.EMPTY_GEOJSON
            val newLines = contourJson !== shownContours
            showPicture(a, picture, key, region)
            mapHolder.style?.getSourceAs<GeoJsonSource>(ARROW_SOURCE)?.setGeoJson(arrowJson)
            if (newLines) {
                mapHolder.style?.getSourceAs<GeoJsonSource>(CONTOUR_SOURCE)?.setGeoJson(contourJson)
                shownContours = contourJson
            }
            if (picture != null && isWhole(a, region)) savePreview(a, choice, picture)
        }
    }

    private fun showPicture(a: ConditionsArea, bitmap: Bitmap?, key: String, region: DoubleArray? = null) {
        val r = region ?: doubleArrayOf(a.south, a.west, a.north, a.east)
        val style = mapHolder.style
        if (style == null) {
            // Drawn before the map was ready: kept, and put on when it is.
            if (bitmap != null) {
                lastBitmap = bitmap
                lastPictureKey = key
                lastRegion = r
            }
            return
        }
        if (bitmap == null) {
            runCatching {
                style.getLayer(IMAGE_LAYER)?.let { style.removeLayer(it) }
                style.getSource(IMAGE_SOURCE)?.let { style.removeSource(it) }
            }
            lastPictureKey = null
            return
        }
        val quad = LatLngQuad(LatLng(r[2], r[1]), LatLng(r[2], r[3]), LatLng(r[0], r[3]), LatLng(r[0], r[1]))
        runCatching {
            val source = style.getSourceAs<ImageSource>(IMAGE_SOURCE)
            if (source == null) {
                style.addSource(ImageSource(IMAGE_SOURCE, quad, bitmap))
            } else {
                if (lastQuad != quad) source.setCoordinates(quad)
                if (bitmap !== lastBitmap || lastPictureKey == null) source.setImage(bitmap)
            }
            if (style.getLayer(IMAGE_LAYER) == null) {
                val layer = RasterLayer(IMAGE_LAYER, IMAGE_SOURCE).withProperties(
                    PropertyFactory.rasterOpacity(opacity),
                    PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
                    PropertyFactory.rasterFadeDuration(0f)
                )
                // Under the arrows, above every imported layer.
                val above = listOf(CONTOUR_CASING_LAYER, CONTOUR_LINE_LAYER, ARROW_LAYER).firstOrNull { style.getLayer(it) != null }
                if (above != null) style.addLayerBelow(layer, above)
                else style.addLayerAbove(layer, MapLayerIds.OVERLAY_ANCHOR)
            }
        }.onFailure { DiagnosticLog.logError(TAG, "showing the picture FAILED", it) }
        lastBitmap = bitmap
        lastQuad = quad
        lastPictureKey = key
        lastRegion = r
    }

    /**
     * What the picture covers: the screen and half as much again, inside the area. The picture
     * in place is kept while the screen is still inside it and it isn't much bigger than the
     * screen: so a small move draws nothing new, and zooming in gets a sharper one.
     */
    private fun pictureRegion(a: ConditionsArea): DoubleArray {
        val whole = doubleArrayOf(a.south, a.west, a.north, a.east)
        val b = mapHolder.map?.projection?.visibleRegion?.latLngBounds ?: return whole
        val latSpan = b.latitudeNorth - b.latitudeSouth
        val lngSpan = b.longitudeEast - b.longitudeWest
        if (latSpan <= 0 || lngSpan <= 0 || lngSpan > 180) return whole
        lastRegion?.let { r ->
            // Inside it, or at the area's own edge where there's nothing further to draw.
            val inside = (b.latitudeSouth >= r[0] || r[0] <= a.south) && (b.latitudeNorth <= r[2] || r[2] >= a.north) &&
                (b.longitudeWest >= r[1] || r[1] <= a.west) && (b.longitudeEast <= r[3] || r[3] >= a.east)
            val sharpEnough = (r[2] - r[0]) <= REGION_KEEP * latSpan && (r[3] - r[1]) <= REGION_KEEP * lngSpan
            if (inside && sharpEnough && r[0] >= a.south && r[2] <= a.north && r[1] >= a.west && r[3] <= a.east) return r
        }
        val cLat = (b.latitudeSouth + b.latitudeNorth) / 2
        val cLng = (b.longitudeWest + b.longitudeEast) / 2
        val s = max(a.south, cLat - REGION_REACH * latSpan)
        val n = min(a.north, cLat + REGION_REACH * latSpan)
        val w = max(a.west, cLng - REGION_REACH * lngSpan)
        val e = min(a.east, cLng + REGION_REACH * lngSpan)
        if (s >= n || w >= e || (s <= a.south && n >= a.north && w <= a.west && e >= a.east)) return whole
        return doubleArrayOf(s, w, n, e)
    }

    /**
     * [pixels] (the values, worked out small) as the picture for the map: stretched to
     * [sharpSide] and cut at the shore at that size, so the coast is as sharp as the screen
     * whatever the data's own spacing: the land see-through, the layers under it showing as
     * before. On the drawing thread.
     */
    private fun sharpen(a: ConditionsArea, region: DoubleArray, pixels: ConditionsPicture.Pixels): Bitmap {
        val small = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        val scale = max(1f, sharpSide.toFloat() / max(pixels.width, pixels.height))
        val width = max(2, (pixels.width * scale).roundToInt())
        val height = max(2, (pixels.height * scale).roundToInt())
        val mask = maskFor(a, region, width, height) ?: return small
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        // The values are the paint, stretched smoothly to this size; the sea (an alpha picture) is
        // the shape it's laid on through. Nothing lands where the mask is empty: the land.
        val values = android.graphics.BitmapShader(small, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        values.setLocalMatrix(android.graphics.Matrix().apply { setScale(width / pixels.width.toFloat(), height / pixels.height.toFloat()) })
        canvas.drawBitmap(mask, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { shader = values })
        small.recycle()
        return out
    }

    /** The picture's longer side on the map, in pixels: as sharp as the phone's memory comfortably allows. */
    private val sharpSide: Int by lazy {
        val memory = (activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        when {
            memory >= 256 -> 2048
            memory >= 128 -> 1536
            else -> 1024
        }
    }

    /** The sea at a point, by the same shore the colours are cut at: the picture's own block first, then the whole area's. Null when there's no coastline to tell. */
    private fun seaAt(a: ConditionsArea, lat: Double, lng: Double): Boolean? =
        seaMask?.isSea(lat, lng) ?: areaSea?.takeIf { it.first == blockKey(a) }?.second?.isSea(lat, lng) ?: a.isSea(lat, lng)

    /** An area's block: the spot keeps its name when the boat moves on and its block is made afresh. */
    private fun blockKey(a: ConditionsArea) = "${a.id}:${a.south},${a.west},${a.north},${a.east}"

    /** The whole of [a]'s sea at a few hundred metres, kept until the coastline changes: for flow lines and arrows away from the picture's block. On the drawing thread. */
    private fun areaSeaFor(a: ConditionsArea): SeaMask? {
        areaSea?.takeIf { it.first == blockKey(a) }?.let { return it.second }
        val widthDeg = a.east - a.west
        val heightDeg = Math.toDegrees(Coastline.mercatorY(a.north) - Coastline.mercatorY(a.south))
        val width = if (widthDeg >= heightDeg) AREA_SEA_SIDE else max(2, (AREA_SEA_SIDE * widthDeg / heightDeg).roundToInt())
        val height = if (widthDeg >= heightDeg) max(2, (AREA_SEA_SIDE * heightDeg / widthDeg).roundToInt()) else AREA_SEA_SIDE
        val mask = runCatching { coastline.mask(a.south, a.west, a.north, a.east, width, height) }.getOrNull()
            ?.let { SeaMask(it, width, height, a.south, a.west, a.north, a.east) }
        areaSea = blockKey(a) to mask
        return mask
    }

    @Volatile private var seaMask: SeaMask? = null
    @Volatile private var areaSea: Pair<String, SeaMask?>? = null

    private fun isWhole(a: ConditionsArea, r: DoubleArray) = r[0] == a.south && r[1] == a.west && r[2] == a.north && r[3] == a.east

    private fun regionKey(r: DoubleArray) = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", r[0], r[1], r[2], r[3])

    /**
     * The shore cut-out for a [width]x[height] picture of [region]: OpenStreetMap's coastline in
     * the tiles that are saved (the rest fetched now, then the picture is drawn again), the
     * built-in outline in the others meanwhile. On the drawing thread.
     */
    private fun maskFor(a: ConditionsArea, region: DoubleArray, width: Int, height: Int): Bitmap? {
        val (saved, needed) = coastline.saved(region[0], region[1], region[2], region[3])
        val key = "${regionKey(region)}:${width}x$height:$saved"
        maskCache?.takeIf { it.first == key }?.let { return it.second }
        if (!coastline.covers(a.south, a.west, a.north, a.east)) scope.launch { fetchCoastline(a) }
        val started = System.nanoTime()
        val mask = runCatching { coastline.maskBitmap(region[0], region[1], region[2], region[3], width, height) }
            .onFailure { DiagnosticLog.logError(TAG, "coastline mask FAILED", it) }.getOrNull()
        // The same shore, for the flow lines and arrows.
        seaMask = mask?.let { SeaMask(Coastline.bytesOf(it), width, height, region[0], region[1], region[2], region[3]) }
        DiagnosticLog.log(TAG, "coastline mask ${width}x$height (OpenStreetMap in $saved of $needed tiles) in ${(System.nanoTime() - started) / 1_000_000} ms")
        maskCache = key to mask
        return mask
    }

    /** Saves the coastline for [a] when it isn't saved yet (online, and not more often than every few minutes), then draws again. */
    private suspend fun fetchCoastline(a: ConditionsArea) {
        val now = System.currentTimeMillis()
        if (coastFetching || now - coastTriedAt < COAST_RETRY_MS || !online()) return
        coastFetching = true
        coastTriedAt = now
        val failed = try {
            withContext(Dispatchers.IO) {
                runCatching { coastline.fetch(a.south, a.west, a.north, a.east) }
                    .onFailure { DiagnosticLog.logError(TAG, "coastline FAILED", it) }.getOrDefault(-1)
            }
        } finally {
            // However it ends (cancelled too), it's free for the next try.
            coastFetching = false
        }
        if (failed == 0) {
            maskCache = null
            areaSea = null
            flowGridKey = null
            lastPictureKey = null
            if (shown) redraw()
        }
    }

    // The last picture, saved so the next start shows colours at once ----------

    private val previewFile get() = File(File(activity.filesDir, "conditions"), "preview.png")

    private fun previewKey(a: ConditionsArea, choice: ColourChoice) = "${a.id}:${a.fetchedMillis}:$choice"

    /** Saves [bitmap] for the next start, at most every few minutes, off the main thread. */
    private fun savePreview(a: ConditionsArea, choice: ColourChoice, bitmap: Bitmap) {
        val key = previewKey(a, choice)
        val now = System.currentTimeMillis()
        if (key == preferences.getString(KEY_PREVIEW, null) && now - previewSavedAt < PICTURE_STEP_MS) return
        previewSavedAt = now
        scope.launch(work) {
            runCatching {
                val temp = File(previewFile.parentFile, "preview.tmp")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (temp.renameTo(previewFile)) preferences.edit().putString(KEY_PREVIEW, key).apply()
            }.onFailure { DiagnosticLog.logError(TAG, "saving the preview FAILED", it) }
        }
    }

    /** The picture saved last time, when it's of [a] with [choice]. Off the main thread. */
    private fun loadPreview(a: ConditionsArea, choice: ColourChoice): Bitmap? {
        if (preferences.getString(KEY_PREVIEW, null) != previewKey(a, choice)) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(previewFile.path) }.getOrNull()
    }

    private var previewSavedAt = 0L

    private fun applyOpacity() {
        val style = mapHolder.style ?: return
        runCatching {
            style.getLayer(IMAGE_LAYER)?.setProperties(PropertyFactory.rasterOpacity(opacity))
            style.getLayer(ARROW_LAYER)?.setProperties(PropertyFactory.iconOpacity(arrowOpacity()))
        }
    }

    /** Arrows stay readable when the colours are faded right down. */
    private fun arrowOpacity(): Float = max(0.55f, opacity)

    /**
     * What the colours show at a point. [clipped]: the picture is cut at the shore afterwards
     * (the coastline), so the values reach right to it. Otherwise the satellite temperature's
     * 1 km coastline masks the land, when it's saved.
     */
    private fun sampler(a: ConditionsArea, choice: ColourChoice, time: Long, clipped: Boolean = false): ConditionsPicture.Sampler? {
        if (clipped) return rawSampler(a, choice, time, coastal = true)
        val raw = rawSampler(a, choice, time, coastal = a.satellites.containsKey(SatelliteKind.SEA_TEMP)) ?: return null
        if (choice == ColourChoice.SEA_TEMP_SATELLITE || choice == ColourChoice.SEA_TEMP_BREAKS || !a.satellites.containsKey(SatelliteKind.SEA_TEMP)) return raw
        return ConditionsPicture.Sampler { lat, lng -> if (a.isSea(lat, lng) == false) Float.NaN else raw.at(lat, lng) }
    }

    private fun rawSampler(a: ConditionsArea, choice: ColourChoice, time: Long, coastal: Boolean): ConditionsPicture.Sampler? {
        val f = a.forecast
        return when (choice) {
            ColourChoice.NONE -> null
            // With a finer coastline to cut at, the satellite's values are carried the last cell or two to the shore.
            ColourChoice.SEA_TEMP_SATELLITE -> {
                val sat = (if (coastal) a.toShore(SatelliteKind.SEA_TEMP) else a.satellites[SatelliteKind.SEA_TEMP]) ?: return null
                ConditionsPicture.Sampler { lat, lng -> if (coastal) sat.sampleToShore(lat, lng) else sat.sample(lat, lng) }
            }
            ColourChoice.SEA_TEMP_BREAKS -> {
                val breaks = (if (coastal) a.breaksToShore else a.breaks) ?: return null
                ConditionsPicture.Sampler { lat, lng -> if (coastal) breaks.sampleToShore(lat, lng) else breaks.sample(lat, lng) }
            }
            ColourChoice.CHLOROPHYLL -> {
                val sat = (if (coastal) a.toShore(SatelliteKind.CHLOROPHYLL) else a.satellites[SatelliteKind.CHLOROPHYLL]) ?: return null
                ConditionsPicture.Sampler { lat, lng -> if (coastal) sat.sampleToShore(lat, lng) else sat.sample(lat, lng) }
            }
            ColourChoice.WATER_CLARITY -> {
                val sat = (if (coastal) a.toShore(SatelliteKind.WATER_CLARITY) else a.satellites[SatelliteKind.WATER_CLARITY]) ?: return null
                ConditionsPicture.Sampler { lat, lng -> WaterClarity.visibilityMetres(if (coastal) sat.sampleToShore(lat, lng) else sat.sample(lat, lng)) }
            }
            // Coarse: with a finer coastline to hide the land, they reach the shore.
            ColourChoice.TEMP_BELOW, ColourChoice.SEA_LEVEL, ColourChoice.SALINITY -> {
                val sat = a.satellites[choice.satellite] ?: return null
                ConditionsPicture.Sampler { lat, lng -> if (coastal) sat.sampleToShore(lat, lng) else sat.sample(lat, lng) }
            }
            ColourChoice.CURRENT -> f?.let { magnitude(it, ForecastField.CURRENT_U, ForecastField.CURRENT_V, time, KNOTS_PER_MS, coastal) }
            ColourChoice.WIND -> f?.let { magnitude(it, ForecastField.WIND_U, ForecastField.WIND_V, time, KNOTS_PER_MS, coastal) }
            ColourChoice.WAVES -> f?.let { ConditionsPicture.Sampler { lat, lng -> it.sample(ForecastField.WAVE_HEIGHT, lat, lng, time, coastal) } }
            ColourChoice.SWELL -> f?.let { ConditionsPicture.Sampler { lat, lng -> it.sample(ForecastField.SWELL_HEIGHT, lat, lng, time, coastal) } }
            ColourChoice.SEA_TEMP_FORECAST -> f?.let { ConditionsPicture.Sampler { lat, lng -> it.sample(ForecastField.SEA_TEMP, lat, lng, time, coastal) } }
        }
    }

    private fun magnitude(f: ForecastGrid, u: ForecastField, v: ForecastField, time: Long, factor: Double, coastal: Boolean) =
        ConditionsPicture.Sampler { lat, lng ->
            val east = f.sample(u, lat, lng, time, coastal)
            val north = f.sample(v, lat, lng, time, coastal)
            if (east.isNaN() || north.isNaN()) Float.NaN else (hypot(east, north) * factor).toFloat()
        }

    /** The colour range; the stretched ones come from all of the area's values, so they hold still as the time changes. */
    private fun scaleFor(a: ConditionsArea, choice: ColourChoice): ColourScale {
        val range = temperatureRange.takeIf { choice.isTemperature }
        val key = "${a.id}:${a.fetchedMillis}:$choice:$range"
        scaleCache?.takeIf { it.first == key }?.let { return it.second }
        val scale = if (range != null) {
            ColourScale.temperatureRange(range.first, range.second)
        } else {
            val values = when (choice) {
                ColourChoice.SEA_TEMP_BREAKS -> a.breaks?.values
                ColourChoice.SEA_TEMP_FORECAST -> a.forecast?.fields?.get(ForecastField.SEA_TEMP)
                else -> choice.satellite?.let { a.satellites[it]?.values }
            }
            ColourScale.forChoice(choice, values)
        }
        scaleCache = key to scale
        return scale
    }

    /** Moving flow lines for the chosen direction, at [time]; off when not chosen. */
    private fun showFlowLines(a: ConditionsArea, time: Long) {
        val f = a.forecast
        val choice = flow
        val fields = choice.fields
        if (!directionStyle.flow || f == null || fields == null) {
            stopFlowLines()
            return
        }
        val (u, v) = fields
        val heightField = when (choice) {
            ArrowChoice.WAVES -> ForecastField.WAVE_HEIGHT
            ArrowChoice.SWELL -> ForecastField.SWELL_HEIGHT
            else -> null
        }
        // Following the clock, worked out afresh every 10 minutes (the forecast is hourly), not on every redraw.
        val at = if (live) time / PICTURE_STEP_MS * PICTURE_STEP_MS else time
        val key = "${a.id}:${a.fetchedMillis}:$choice:$at"
        if (key == flowGridKey) return
        flowGridKey = key
        flowGridJob?.cancel()
        // The flow is worked out once onto a grid, off the main thread: the specks then read the
        // grid every frame instead of the forecast, which keeps the map and panels smooth.
        flowGridJob = scope.launch {
            val grid = withContext(work) {
                // The shore the colours are cut at: the lines stop at it too.
                val sea = areaSeaFor(a)
                val masked = sea != null || a.satellites.containsKey(SatelliteKind.SEA_TEMP)
                FlowGrid.build(a.south, a.west, a.north, a.east, FLOW_GRID_SIDE) { lat, lng, out ->
                    if ((sea?.isSea(lat, lng) ?: a.isSea(lat, lng)) == false) return@build false
                    val east = f.sample(u, lat, lng, at, coastal = masked)
                    val north = f.sample(v, lat, lng, at, coastal = masked)
                    if (east.isNaN() || north.isNaN()) return@build false
                    // Waves and swell store a direction only: their height sets the speed.
                    var scale = 1f
                    if (heightField != null) {
                        scale = f.sample(heightField, lat, lng, at, coastal = masked)
                        if (scale.isNaN()) return@build false
                    }
                    out[0] = east.toDouble() * scale
                    out[1] = north.toDouble() * scale
                    true
                }
            }
            // Each speck is tested against the sharp shore as it moves: none runs over an island or a headland.
            val field = FlowLinesView.Field { lat, lng, out -> seaAt(a, lat, lng) != false && grid.at(lat, lng, out) }
            flowLines.show(field, choice.reference, a.south, a.west, a.north, a.east)
        }
    }

    private fun stopFlowLines() {
        flowGridJob?.cancel()
        flowGridKey = null
        flowLines.stop()
    }

    private var flowGridKey: String? = null
    private var flowGridJob: kotlinx.coroutines.Job? = null

    /** About a dozen arrows across [region] (the part of the area being drawn), each where there's flow at [time]. */
    private fun arrowGeoJson(a: ConditionsArea, choice: ArrowChoice, time: Long, region: DoubleArray): String {
        val f = a.forecast ?: return MapLayerIds.EMPTY_GEOJSON
        val (u, v) = choice.fields ?: return MapLayerIds.EMPTY_GEOJSON
        val reference = choice.reference
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        // Evenly over what's drawn, not on the forecast's own points: zoomed in, those can all be off the screen.
        // No more than about a dozen across: the flow lines show the detail.
        val kmLng = KM_PER_DEGREE * max(0.01, kotlin.math.cos(Math.toRadians((region[0] + region[2]) / 2)))
        val tallKm = (region[2] - region[0]) * KM_PER_DEGREE
        val wideKm = (region[3] - region[1]) * kmLng
        val spacingKm = max(tallKm, wideKm) / MAX_ARROWS_PER_SIDE
        if (spacingKm <= 0) return MapLayerIds.EMPTY_GEOJSON
        val rows = max(1, (tallKm / spacingKm).roundToInt())
        val cols = max(1, (wideKm / spacingKm).roundToInt())
        for (r in 0 until rows) for (c in 0 until cols) {
            val lat = region[0] + (r + 0.5) * (region[2] - region[0]) / rows
            val lng = region[1] + (c + 0.5) * (region[3] - region[1]) / cols
            if (seaAt(a, lat, lng) == false) continue
            val flow = f.vector(u, v, lat, lng, time) ?: continue
            val strength = when (choice) {
                ArrowChoice.WAVES -> f.sample(ForecastField.WAVE_HEIGHT, lat, lng, time).toDouble()
                ArrowChoice.SWELL -> f.sample(ForecastField.SWELL_HEIGHT, lat, lng, time).toDouble()
                else -> flow.speed
            }
            if (!strength.isFinite() || strength < 0.01) continue
            val size = 0.3 + 0.7 * min(1.0, strength / reference)
            if (!first) sb.append(',')
            first = false
            sb.append(String.format(Locale.US, """{"type":"Feature","geometry":{"type":"Point","coordinates":[%.5f,%.5f]},"properties":{"rot":%.1f,"size":%.2f}}""", lng, lat, flow.towardsDegrees, size))
        }
        return sb.append("]}").toString()
    }

    // Bottom card ------------------------------------------------------------------

    private fun refreshCard(a: ConditionsArea) {
        card.visibility = View.VISIBLE
        val forecast = a.forecast
        val usesForecast = (colour.forecast || (directionStyle != DirectionStyle.NONE && flow != ArrowChoice.NONE)) && forecast != null
        // No slider with this layer: back to the time now, not an hour picked earlier that couldn't be changed here.
        if (!usesForecast && !live) {
            setPlaying(false)
            live = true
        }
        val time = timeMillis
        // "Wind speed", not "Wind speed · wind flow": the lines are the layer's own.
        val ownFlow = colour in OWN_FLOW && flow == ArrowChoice.SAME.resolve(colour)
        val layerName = if (colour == ColourChoice.TEMP_BELOW) "Temperature ${NavFormat(units()).depth(a.belowDepthM.toDouble())} down (model)" else colour.label
        val what = listOfNotNull(
            layerName.takeIf { colour != ColourChoice.NONE },
            flow.takeIf { it != ArrowChoice.NONE && forecast != null && !ownFlow }?.short
        ).joinToString(" · ").ifEmpty { "Sea conditions" }
        val satellite = colour.satellite?.let { a.satellites[it] }
        val source = if (colour == ColourChoice.TEMP_BELOW) "model" else "satellite"
        val whenText = when {
            colour.satellite != null && satellite == null ->
                if (withSatellite) "No ${colour.satellite?.label?.lowercase(Locale.US)} saved here: update the area"
                else "Satellite sea maps are off: turn them on in ⚙, then update"
            colour.forecast && forecast == null -> "No forecast saved for this area"
            usesForecast && time > forecast.endMillis + ForecastGrid.HOUR_MS -> "The forecast ran out ${day(forecast.endMillis)}: update it"
            usesForecast -> clock(time) + (if (live) " (now)" else "") + (satellite?.let { " · $source ${day(it.dateMillis)}" } ?: "")
            satellite != null -> if (colour == ColourChoice.TEMP_BELOW) "Ocean model's estimate for ${day(satellite.dateMillis)}"
                else when (colour.satellite) {
                    SatelliteKind.SEA_TEMP -> "Satellite map for ${day(satellite.dateMillis)} (whole-day average)"
                    SatelliteKind.CHLOROPHYLL -> "Satellite map of the 5 days to ${day(satellite.dateMillis)}"
                    SatelliteKind.WATER_CLARITY -> "Satellite estimate, 5 days to ${day(satellite.dateMillis)}"
                    else -> "Satellite map for ${day(satellite.dateMillis)}"
                }
            else -> ""
        }
        title.text = if (whenText.isEmpty()) "$what — ${a.name}" else "$what\n$whenText"
        showWarning(cardWarning, a)

        // Colour key.
        if (colour == ColourChoice.NONE || (colour.satellite != null && satellite == null) || (colour.forecast && forecast == null)) {
            legend.visibility = View.GONE
        } else {
            legend.visibility = View.VISIBLE
            val scale = scaleFor(a, colour)
            val band = targetBand?.takeIf { colour.isTemperature }
            legendBar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, IntArray(32) {
                val c = scale.colourAt(it / 31.0)
                if (band == null || scale.valueAt(it / 31.0) in band.first..band.second) c else ConditionsPicture.dim(c)
            }).apply {
                cornerRadius = activity.dp(3).toFloat()
            }
            legendLow.text = legendValue(scale.valueAt(0.0))
            legendMid.text = legendValue(scale.valueAt(0.5))
            // The fixed ranges show anything stronger in the top colour.
            legendHigh.text = legendValue(scale.valueAt(1.0)) + if (colour.fixedRange) "+" else ""
        }

        // Time slider, for the forecast.
        if (usesForecast) {
            timeRow.visibility = View.VISIBLE
            slider.max = forecast.times.size - 1
            val hour = forecast.times.indexOfFirst { it >= time - ForecastGrid.HOUR_MS / 2 }.let { if (it < 0) forecast.times.size - 1 else it }
            slider.progress = hour
            nowButton.alpha = if (live) 0.45f else 1f
        } else {
            timeRow.visibility = View.GONE
        }
    }

    private fun legendValue(value: Double): String {
        val u = units()
        return when (colour) {
            ColourChoice.CURRENT -> legendSpeed(value, u.current)
            ColourChoice.WIND -> legendSpeed(value, u.wind)
            ColourChoice.WAVES, ColourChoice.SWELL -> legendNumber(u.waves.of(value), u.waves.label)
            ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_FORECAST, ColourChoice.TEMP_BELOW -> NavFormat(u).temperature(value)
            ColourChoice.SEA_TEMP_BREAKS -> String.format(Locale.US, "%.2f %s/km", u.temperature.ofDifference(value), u.temperature.label)
            ColourChoice.CHLOROPHYLL -> String.format(Locale.US, if (value < 1) "%.2f" else "%.1f", value) + " mg/m³"
            ColourChoice.SEA_LEVEL -> NavFormat(u).seaLevel(value)
            ColourChoice.SALINITY -> String.format(Locale.US, "%.1f PSU", value)
            ColourChoice.WATER_CLARITY -> WaterClarity.text(value, NavFormat(u))
            ColourChoice.NONE -> ""
        }
    }

    private fun step(hours: Int, wrap: Boolean = false, fromPlay: Boolean = false) {
        if (!fromPlay) setPlaying(false)
        val forecast = area?.forecast ?: return
        val now = timeMillis
        val current = forecast.times.indexOfFirst { it >= now - ForecastGrid.HOUR_MS / 2 }.let { if (it < 0) forecast.times.size - 1 else it }
        var next = current + hours
        // Playing goes round again from the start; stepping stops at the ends.
        if (wrap && next >= forecast.times.size) next = 0
        next = next.coerceIn(0, forecast.times.size - 1)
        live = false
        pickedMillis = forecast.times[next]
        redraw()
    }

    private fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        ticker.removeCallbacks(play)
        playButton.text = if (value) PAUSE else PLAY
        playButton.contentDescription = if (value) "Pause" else "Play the forecast"
        if (value) ticker.postDelayed(play, PLAY_STEP_MS)
    }

    // Values box --------------------------------------------------------------------

    private fun updateBox() {
        fillBox()
        // What the box says changed. When that happens while the screen is being laid out (a camera
        // move does it), Android keeps the box at its old size and the new words are cut off: so
        // it's measured afresh straight after.
        val says = buildString {
            append(box.visibility).append('|').append(boxTitle.text).append('|').append(boxWarning.visibility).append(boxWarning.text)
            for (row in boxRows) append('|').append(row.view.visibility).append(row.text.text)
        }
        if (says == boxSaid) return
        boxSaid = says
        box.removeCallbacks(remeasureBox)
        box.post(remeasureBox)
    }

    private var boxSaid = ""
    private val remeasureBox = Runnable {
        fun fresh(view: View) {
            view.forceLayout()
            if (view is ViewGroup) for (i in 0 until view.childCount) fresh(view.getChildAt(i))
        }
        fresh(box)
        box.requestLayout()
    }

    private fun fillBox() {
        val map = mapHolder.map
        val target = map?.cameraPosition?.target
        // While navigating, the navigation display already fills the top of the chart.
        if (!boxShown || map == null || callbacks.isNavigating()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val time = timeMillis
        val format = NavFormat(units())
        val loaded = listOfNotNull(area, spotArea)
        // Sea, by OpenStreetMap's coastline where it's saved (else the satellite's, else the forecast's),
        // with values to show.
        val hasSea = { a: ConditionsArea, lat: Double, lng: Double ->
            val sea = coastline.isSea(lat, lng)
            sea != false && values(a, lat, lng, time, format, sea).any { it.row !in SKY_ROWS }
        }
        val boat = spot()
        // The map layer on, the crosshair over sea with data: everything there. Otherwise the boat (or
        // the set spot): its own reading, or the nearest sea when it's on land. Whatever is saved
        // shows, old or not, while anything downloads.
        val atCrosshair = if (shown && target != null) {
            WeatherReadings.best(loaded, target.latitude, target.longitude, time, nearest = false, hasSea = hasSea)
        } else null
        val reading = atCrosshair ?: boat?.let { b -> boatReading(loaded, b, time, hasSea) }
        if (reading == null) {
            boxTitle.text = when (val st = engine.state) {
                is WeatherState.Working -> "Sea conditions · downloading…"
                is WeatherState.Waiting -> "Sea conditions · ${st.reason}"
                else -> when {
                    boat == null && followGps -> "Sea conditions · waiting for the GPS"
                    keepUpdated && !online() -> "Sea conditions · none saved here (offline)"
                    engine.noSeaInReach -> "Sea conditions · no sea within ${SpotBlock.RINGS_KM.last().toInt()} km"
                    else -> "Sea conditions · tap to set up"
                }
            }
            boxRows.forEach { it.view.visibility = View.GONE }
            boxWarning.visibility = View.GONE
            showFreshness(null)
            return
        }
        val a = reading.area
        val lat = reading.lat
        val lng = reading.lng
        val wanted: Set<String>?
        if (reading === atCrosshair) {
            wanted = if (boxOpen) null else setOfNotNull("Wind", rowNameOf(colour))
            boxTitle.text = "At the crosshair · " + if (live) "now" else clock(time)
        } else {
            wanted = if (boxOpen) null else setOfNotNull("Wind", rowNameOf(colour).takeIf { shown })
            boxTitle.text = (
                if (reading.distanceKm >= 0.5) String.format(Locale.US, if (reading.distanceKm < 10) "Nearest sea, %.1f km" else "Nearest sea, %.0f km", reading.distanceKm)
                else if (followGps) "At the boat" else "At your spot"
                ) + " · " + if (live) "now" else clock(time)
        }
        showFreshness(a)
        // The card already says when the layer's area is old.
        if (shown && a === area) boxWarning.visibility = View.GONE else showWarning(boxWarning, a)
        // Wind first (it's what the box shows closed), then the rest in a fixed order.
        val values = values(a, lat, lng, time, format, coastline.isSea(lat, lng))
            .filter { wanted == null || it.row in wanted }
            .sortedBy { ROW_ORDER.indexOf(it.row).let { i -> if (i < 0) ROW_ORDER.size else i } }
        val bearing = map.cameraPosition.bearing
        boxRows.forEachIndexed { i, row ->
            val value = values.getOrNull(i)
            if (value == null) {
                row.view.visibility = View.GONE
                return@forEachIndexed
            }
            row.view.visibility = View.VISIBLE
            row.text.text = value.text
            row.layer = layerOfRow(value.row, a)
            row.view.contentDescription = value.spoken + if (row.layer != null) ". Tap to show it on the map" else ""
            row.from = a
            // The showing layer's own line (chlorophyll and the temperature breaks are the Sea line's).
            row.highlight(shown && row.layer != null && value.row == rowNameOf(colour))
            row.showIcon(value.icon)
            if (value.icon != null) {
                row.arrow.visibility = View.VISIBLE
                row.arrow.rotation = 0f
            } else if (value.towardsDegrees == null) {
                row.arrow.visibility = View.INVISIBLE
            } else {
                row.arrow.visibility = View.VISIBLE
                // As it points on the chart, which may be turned.
                row.arrow.rotation = (value.towardsDegrees - bearing).toFloat()
            }
        }
        if (values.isEmpty()) {
            val forecast = a.forecast
            boxTitle.text = if (forecast != null && time > forecast.endMillis + ForecastGrid.HOUR_MS) "The forecast has run out: update it"
            else "Sea conditions · nothing at this time"
        }
    }

    /** The last boat reading worked out, and what for: the box is updated on every map move. */
    private var boatReadingCache: Pair<String, WeatherReadings.Reading?>? = null

    /** What the box shows for the boat: its own reading, or on land the nearest sea (remembered until something changes). */
    private fun boatReading(
        loaded: List<ConditionsArea>, b: Pair<Double, Double>, time: Long, hasSea: (ConditionsArea, Double, Double) -> Boolean
    ): WeatherReadings.Reading? {
        val key = loaded.joinToString { "${it.id}:${it.fetchedMillis}:${it.satellites.size}" } +
            String.format(Locale.US, "|%.4f,%.4f|%d|%b", b.first, b.second, time / (15 * 60_000L), coastline.isSea(b.first, b.second))
        boatReadingCache?.takeIf { it.first == key }?.let { return it.second }
        val r = WeatherReadings.best(loaded, b.first, b.second, time, nearest = false, hasSea = hasSea)
            // On land (at the ramp, on an island): the nearest water by the coastline, read there.
            ?: nearestSeaByCoast(loaded, b)?.let { sea ->
                WeatherReadings.best(loaded, sea.first, sea.second, time, nearest = false, hasSea = hasSea)?.let { r ->
                    r.copy(distanceKm = au.opendepth.app.nav.NavGeo.distanceM(b.first, b.second, r.lat, r.lng) / 1000)
                }
            }
            // Without a coastline to tell: the nearest forecast point at sea.
            ?: WeatherReadings.best(loaded, b.first, b.second, time, hasSea = hasSea)
            ?: engine.nearestSeaFor(b)?.let { sea ->
                WeatherReadings.best(loaded, sea.first, sea.second, time, hasSea = hasSea)?.let { r ->
                    r.copy(distanceKm = au.opendepth.app.nav.NavGeo.distanceM(b.first, b.second, r.lat, r.lng) / 1000)
                }
            }
        boatReadingCache = key to r
        return r
    }

    /**
     * The nearest sea to a point on land, by the coastline. (The nearest forecast point at sea can
     * be 30 km off: a boat at the ramp read "nearest sea, 32 km".) From the area's sea mask, which
     * is worked out in the background the first time it's wanted; null until then.
     */
    private fun nearestSeaByCoast(loaded: List<ConditionsArea>, b: Pair<Double, Double>): Pair<Double, Double>? {
        val a = loaded.firstOrNull { it.contains(b.first, b.second) } ?: return null
        val sea = areaSea?.takeIf { it.first == blockKey(a) }
        if (sea == null) {
            if (!areaSeaWanted) {
                areaSeaWanted = true
                scope.launch {
                    try {
                        withContext(work) { areaSeaFor(a) }
                    } finally {
                        areaSeaWanted = false
                    }
                    boatReadingCache = null
                    updateBox()
                }
            }
            return null
        }
        // Water by the exact coastline too, or the box would have nothing to read there.
        return sea.second?.nearestSea(b.first, b.second, WeatherReadings.SHORE_KM) { lat, lng -> coastline.isSea(lat, lng) != false }
    }

    private var areaSeaWanted = false

    /**
     * Where the spot's area is kept: the spot (the boat, or the set spot), or while there's no
     * position, the middle of what's saved for it.
     */
    private fun spotTarget(): Pair<Double, Double>? =
        spot() ?: spotArea?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 }

    /** An area was saved by the engine: shown straight away where it belongs. */
    private fun onSaved(saved: ConditionsArea, isSpot: Boolean) {
        // The very first download: show it on the map, so it's clear it worked. Once only:
        // after that the layer stays as the user leaves it.
        if (!preferences.contains(KEY_SHOWN) && saved.forecast != null) {
            if (!preferences.contains(KEY_COLOUR)) {
                colour = ColourChoice.WIND
                preferences.edit().putString(KEY_COLOUR, colour.name).apply()
            }
            area = saved
            areaId = saved.id
            setShown(true)
        }
        val current = area
        // The map shows it when it's what's on the map, or the spot's and nothing else is shown.
        if (current == null || current.id == saved.id || (isSpot && !shown)) {
            area = saved
            areaId = saved.id
            preferences.edit().putString(KEY_AREA, saved.id).apply()
            lastPictureKey = null
            scaleCache = null
            maskCache = null
        }
        if (shown && area?.id == saved.id) redraw() else updateBox()
        callbacks.creditsChanged()
        refreshDialog()
    }

    /** A download finished: messages for one the user asked for; the first ever shows on the map. */
    private fun onFinished(result: ConditionsArea?, problems: List<String>, userAsked: Boolean, note: String?, alreadyLatest: Boolean) {
        if (result == null) {
            if (userAsked) callbacks.toast("Couldn't get the sea conditions. " + problems.joinToString(" "))
            updateBox()
            refreshDialog()
            return
        }
        if (userAsked) {
            val where = if (result.id == SPOT_ID) SPOT_NAME else result.name
            val saved = when {
                // Nothing newer was out: no download was spent on the same forecast.
                alreadyLatest && problems.isEmpty() -> "$where already has the latest forecast. Nothing newer is out yet."
                problems.isEmpty() -> "Sea conditions saved for $where."
                else -> "Saved ${result.name}, but: " + problems.joinToString(" ")
            }
            callbacks.toast(listOfNotNull(saved, note).joinToString(" "))
            if (result.id != SPOT_ID) live = true
        }
        if (userAsked && result.id != SPOT_ID) {
            area = result
            areaId = result.id
            preferences.edit().putString(KEY_AREA, result.id).apply()
            lastPictureKey = null
            if (!shown) setShown(true) else redraw()
        }
        updateBox()
        removeOldAreas()
        refreshDialog()
    }

    /** Working internet, asked of the phone at most every few seconds (this runs as the map moves). */
    private fun online(): Boolean {
        val now = System.currentTimeMillis()
        if (now - onlineAt > ONLINE_CHECK_MS) {
            onlineNow = callbacks.isOnline()
            onlineAt = now
            if (!onlineNow) wasOffline = true
        }
        return onlineNow
    }

    /** [view] shows a note when [a] is old saved data (offline, or not updated for a while); hidden otherwise. */
    private fun showWarning(view: TextView, a: ConditionsArea) {
        val note = listOfNotNull(
            when {
                // The sea forecast is the usual one; the wind is the backup's.
                a.windFromBackup -> "Backup wind: coarser, with no gusts"
                !a.isBackup -> null
                // The wind is the usual one; the waves are the backup's.
                a.forecast?.has(ForecastField.GUST) == true -> "Backup waves and swell: coarser, and no currents"
                else -> "Backup forecast: coarser, with no gusts or currents"
            },
            staleNote(a.fetchedMillis, System.currentTimeMillis(), online()),
            // Why the spot's update waits (the free limit, a failed download) while its saved data shows.
            (engine.state as? WeatherState.Waiting)?.takeIf { it.untilMillis > 0 && a.id == SPOT_ID && !a.wantsMainAgain && online() }
                ?.reason?.replaceFirstChar { it.uppercase() }
        ).joinToString("\n").ifEmpty { null }
        view.text = note.orEmpty()
        view.visibility = if (note == null) View.GONE else View.VISIBLE
    }

    // The spot --------------------------------------------------------------------

    /**
     * Where the spot is now: the boat when following the GPS (this app's
     * latest fix, or the phone's last known position before that), else the
     * set point. Null while there's no position at all.
     */
    private fun spot(): Pair<Double, Double>? =
        if (followGps) gpsFix ?: lastKnown() else fixedSpot

    private var lastKnownAt = 0L
    private var lastKnownValue: Pair<Double, Double>? = null

    /** The phone's last known position, asked at most every half minute (this runs as the map moves). */
    private fun lastKnown(): Pair<Double, Double>? {
        val now = System.currentTimeMillis()
        if (now - lastKnownAt > LAST_KNOWN_MS) {
            lastKnownValue = callbacks.lastKnownPosition()
            lastKnownAt = now
        }
        return lastKnownValue
    }

    private fun spotLabel(): String =
        if (followGps) "Following the GPS" else fixedSpot?.let { (lat, lng) -> defaultName(lat, lng, lat, lng) } ?: "Not set"

    /** The box line ([Value.row]) that shows [layer]. */
    private fun rowNameOf(layer: ColourChoice): String? = when (layer) {
        ColourChoice.WIND -> "Wind"
        ColourChoice.CURRENT -> "Current"
        ColourChoice.WAVES -> "Waves"
        ColourChoice.SWELL -> "Swell"
        ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_BREAKS, ColourChoice.SEA_TEMP_FORECAST, ColourChoice.CHLOROPHYLL -> "Sea"
        ColourChoice.TEMP_BELOW -> "Depth"
        ColourChoice.SEA_LEVEL, ColourChoice.SALINITY, ColourChoice.WATER_CLARITY -> "Water"
        ColourChoice.NONE -> null
    }

    /** What tapping a box line shows on the map. */
    private fun layerOfRow(name: String, a: ConditionsArea): ColourChoice? = when (name) {
        "Wind" -> ColourChoice.WIND
        "Current" -> ColourChoice.CURRENT
        "Waves" -> ColourChoice.WAVES
        "Swell" -> ColourChoice.SWELL
        "Sea" -> when {
            SatelliteKind.SEA_TEMP in a.satellites -> ColourChoice.SEA_TEMP_SATELLITE
            a.forecast?.has(ForecastField.SEA_TEMP) == true -> ColourChoice.SEA_TEMP_FORECAST
            SatelliteKind.CHLOROPHYLL in a.satellites -> ColourChoice.CHLOROPHYLL
            else -> null
        }
        // Keeps the layer showing when it's one of this line's.
        "Depth" -> ColourChoice.TEMP_BELOW
        "Water" -> if (rowNameOf(colour) == "Water" && a.satellites[colour.satellite] != null) colour else when {
            SatelliteKind.SEA_LEVEL in a.satellites -> ColourChoice.SEA_LEVEL
            SatelliteKind.SALINITY !in a.satellites && SatelliteKind.WATER_CLARITY in a.satellites -> ColourChoice.WATER_CLARITY
            else -> ColourChoice.SALINITY
        }
        else -> null
    }

    /**
     * Keeps the area round the spot downloaded: when online, if there's none,
     * it's older than the update interval, or the spot has moved out of its middle.
     */
    private fun freshnessOf(a: ConditionsArea): ConditionsPolicy.Freshness = ConditionsPolicy.freshness(
        a, engine.latestRun, System.currentTimeMillis(), engine.isWorkingOn(a.id), engine.runSeen(a.id)
    )

    /** The box's dot for [a]: green, amber or red; hidden with nothing on show. */
    private fun showFreshness(a: ConditionsArea?) {
        dotArea = a
        if (a == null) {
            boxDot.visibility = View.GONE
            return
        }
        val state = freshnessOf(a)
        (boxDot.background as? GradientDrawable)?.setColor(
            when (state) {
                ConditionsPolicy.Freshness.CURRENT -> FRESH_COLOUR
                ConditionsPolicy.Freshness.UPDATING -> UPDATING_COLOUR
                ConditionsPolicy.Freshness.OUT_OF_DATE -> OLD_COLOUR
            }
        )
        boxDot.contentDescription = when (state) {
            ConditionsPolicy.Freshness.CURRENT -> "Up to date"
            ConditionsPolicy.Freshness.UPDATING -> "Updating"
            ConditionsPolicy.Freshness.OUT_OF_DATE -> "Out of date: tap to update"
        }
        boxDot.visibility = View.VISIBLE
    }

    /** Tapping the dot: says how fresh the data is, and updates it when it's out of date. */
    private fun freshnessTapped() {
        val a = dotArea ?: return
        val now = System.currentTimeMillis()
        val age = "forecast downloaded ${ago(a.fetchedMillis, now)}"
        when (freshnessOf(a)) {
            ConditionsPolicy.Freshness.UPDATING -> callbacks.toast("Updating the sea conditions…")
            ConditionsPolicy.Freshness.CURRENT -> callbacks.toast("Up to date: $age")
            ConditionsPolicy.Freshness.OUT_OF_DATE -> when {
                !online() -> callbacks.toast("Out of date ($age). No internet: it updates when you're back online")
                a.id == SPOT_ID -> {
                    callbacks.toast("Out of date ($age): updating")
                    engine.setSpot(spotTarget())
                    engine.evaluate(userAsked = true)?.let { callbacks.toast(it) }
                }
                else -> {
                    callbacks.toast("Out of date ($age): updating")
                    engine.downloadArea(a.south, a.west, a.north, a.east, a.id, userAsked = true)?.let { callbacks.toast(it) }
                }
            }
        }
        updateBox()
    }

    /**
     * Removes saved areas that are of no more use: forecasts that ran out
     * days ago, and older copies wholly covered by a newer download (the
     * spot's area is replaced by each update instead, never removed here).
     */
    private fun removeOldAreas() {
        scope.launch {
            val removed = withContext(work) {
                val now = System.currentTimeMillis()
                ConditionsPolicy.toRemove(store.summaries(), SPOT_ID, now).onEach { (summary, reason) ->
                    store.delete(summary.id)
                    preferences.edit().remove(KEY_SATELLITES_AT + summary.id)
                        .remove(WeatherEngine.KEY_SATELLITES_TRIED + summary.id).apply()
                    DiagnosticLog.log(TAG, "removed ${summary.name}: " + if (reason == ConditionsPolicy.Reason.EXPIRED) "its forecast ran out" else "a newer download covers it")
                }.also {
                    // The coastline goes with the last area that needed it.
                    if (it.isNotEmpty()) coastline.prune(store.summaries().map { s -> doubleArrayOf(s.south, s.west, s.north, s.east) })
                }
            }
            if (removed.any { it.first.id == area?.id }) {
                area = null
                showNewestHere()
            }
            if (removed.isNotEmpty()) refreshDialog()
        }
    }

    /**
     * The map shows the newest saved data where it's looking: panning into
     * water another (newer) area covers switches to that one, online or not.
     */
    private fun showNewestHere() {
        if (!shown) return
        val target = mapHolder.map?.cameraPosition?.target ?: return
        val summaries = store.cachedSummaries() ?: return
        val newest = ConditionsPolicy.newestCovering(target.latitude, target.longitude, summaries) ?: return
        val current = area
        if (current != null && newest.id == current.id) return
        if (current != null && current.contains(target.latitude, target.longitude) && newest.fetchedMillis <= current.fetchedMillis) return
        scope.launch {
            val loaded = withContext(work) { store.load(newest.id) } ?: return@launch
            if (!shown) return@launch
            DiagnosticLog.log(TAG, "map now shows ${loaded.name}, the newest data here")
            area = loaded
            areaId = loaded.id
            preferences.edit().putString(KEY_AREA, loaded.id).apply()
            scaleCache = null
            redraw()
            callbacks.creditsChanged()
        }
    }

    private fun setSpot() {
        val options = listOf("Follow the GPS", "Choose on the map")
        panels.showChoicePanel("Spot", options, if (followGps) 0 else 1) { i ->
            if (i == 0) {
                followGps = true
                if (gpsFix == null) callbacks.startGps()
                spotChanged()
            } else {
                startPicking()
            }
        }
    }

    /** Puts the map in charge of choosing: the sheet closes and a banner says what to do. */
    private fun startPicking() {
        dialog?.dismiss()
        picking = true
        // Start from the current spot, so "set" without moving keeps it.
        fixedSpot?.let { (lat, lng) ->
            mapHolder.map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(LatLng(lat, lng)))
        }
        pickBanner.visibility = View.VISIBLE
    }

    private fun endPicking(set: Boolean) {
        if (!picking) return
        picking = false
        pickBanner.visibility = View.GONE
        if (!set) return
        val target = mapHolder.map?.cameraPosition?.target ?: return
        followGps = false
        fixedSpot = target.latitude to target.longitude
        preferences.edit().putString(KEY_SPOT, String.format(Locale.US, "%.5f,%.5f", target.latitude, target.longitude)).apply()
        spotChanged()
        callbacks.toast("Spot set")
    }

    private fun spotChanged() {
        preferences.edit().putBoolean(KEY_SPOT_GPS, followGps).apply()
        engine.setSpot(spotTarget())
        updateBox()
        refreshDialog()
    }

    /** Shown while choosing the spot on the map. */
    private val pickBanner: LinearLayout by lazy {
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.roundedBackground(0xF0111827.toInt(), palette.accent, 14, strokeWidthDp = 2)
            setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
            elevation = activity.dp(6).toFloat()
            visibility = View.GONE
            isClickable = true
            addView(TextView(activity).apply {
                text = "Choose the spot"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
            })
            addView(TextView(activity).apply {
                text = "Move the map so the crosshair is on the spot, then tap Set."
                textSize = 13f
                setTextColor(palette.secondaryText)
            })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(activity).apply {
                text = "Cancel"
                isAllCaps = false
                applySecondaryStyle(palette)
                setOnClickListener { endPicking(set = false) }
            }, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
            row.addView(Button(activity).apply {
                text = "Set"
                isAllCaps = false
                applyPrimaryStyle(palette)
                setOnClickListener { endPicking(set = true) }
            }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { marginStart = activity.dp(8) })
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(8)
            })
        }
    }

    // Settings sheet ---------------------------------------------------------------

    fun show() {
        if (picking) endPicking(set = false)
        // The list of saved areas is read off the main thread, then the sheet fills in.
        if (store.cachedSummaries() == null) {
            scope.launch {
                withContext(Dispatchers.IO) { store.summaries() }
                refreshDialog()
            }
        }
        dialog?.dismiss()
        val body = panels.body()
        dialogBody = body
        fill(body)
        dialog = panels.showPanel("Sea conditions", body, onDismiss = {
            dialog = null
            dialogBody = null
        })
    }

    // Good-weather alerts ---------------------------------------------------------

    private fun alertLabel(r: AlertRules): String {
        if (!r.enabled) return "Off: tell me when wind and waves suit me"
        val u = units()
        val days = when (r.weekdays) {
            AlertRules.ALL_DAYS -> "any day"
            AlertRules.WEEKENDS -> "weekends"
            else -> r.weekdays.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
        }
        val where = when (r.places.size) {
            0 -> "your spot"
            1 -> r.places[0].name
            else -> "${r.places.size} places"
        }
        return "On for $where: wind ≤ ${u.wind.format(r.windMaxKn)}, waves ≤ ${wavesText(r.waveMaxM)}, ${r.minHours} h+, $days"
    }

    private fun hourText(h: Int) = String.format(Locale.US, "%02d:00", h)

    private fun hoursLabel(r: AlertRules) =
        "${r.fromHour?.let { hourText(it) } ?: "Sunrise"} to ${r.toHour?.let { hourText(it) } ?: "sunset"}"

    private fun saveLabel(mode: String) = when (mode) {
        AlertRules.SAVE_WIFI -> "On Wi-Fi only"
        AlertRules.SAVE_OFF -> "Off"
        else -> "Yes, on any connection"
    }

    /** Notifications this app may post: off, the alert can't say anything. */
    private fun notificationsAllowed() = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled()

    /** A plain account of the forecast for whoever wonders why it's "only" every six hours. */
    private fun showHowItWorks() {
        val body = panels.body()
        fun part(title: String, text: String) {
            body.addView(panels.sectionHeader(title))
            body.addView(TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTextColor(palette.primaryText)
                setLineSpacing(activity.dp(3).toFloat(), 1f)
                setPadding(activity.dp(2), 0, activity.dp(2), activity.dp(4))
            })
        }
        part(
            "WHERE IT COMES FROM",
            "Weather centres run computer models of the wind and the sea. SeaBathy shows their forecasts, free, through " +
                "Open-Meteo: wind from ECMWF (Europe), NOAA (USA) or DWD (Germany), whichever covers your waters best; " +
                "waves, swell and currents from Météo-France and Copernicus Marine."
        )
        part(
            "HOW OFTEN IT CHANGES",
            "The centres bring out a new wind forecast every 6 hours, waves every 12 and currents once a day. That " +
                "schedule is theirs, not the app's. Each forecast runs hour by hour for days ahead, so what you see stays " +
                "current in between; a new one only refines it."
        )
        part(
            "WHAT THE APP DOWNLOADS",
            "Online, it checks every ${RUN_CHECK_MS / 60_000} minutes whether anything newer is out, and downloads only what has " +
                "changed, for ${SPOT_RADIUS_KM.roundToInt()} km round your spot. Nothing newer, nothing downloaded. It's all saved on " +
                "the phone, so it works offline."
        )
        part(
            "SATELLITE MAPS",
            "Sea temperature, its breaks, chlorophyll, sea level and salinity are measured by satellites: one picture a day, " +
                "usually 2 to 5 days old by the time it's published. They don't change with the forecast, so the time slider is hidden while " +
                "one shows; the card says which day it is of. Temperature at depth is an ocean model's estimate for today."
        )
        part(
            "DIRECTION",
            "The moving lines and arrows show where the wind or water is going. The values box says where the wind and " +
                "waves come from, as marine forecasts do."
        )
        part(
            "THE FREE LIMIT",
            "The free service allows each internet connection a set number of requests a day. Normal use takes a small " +
                "part of it. If it's ever reached, a backup forecast from NOAA (coarser, and without currents) fills in " +
                "until the main one is back."
        )
        part(
            "HOW FAR TO TRUST IT",
            "A forecast is a model's best estimate, not a measurement. The next day or two are the most reliable, and " +
                "close to shore the real wind can differ. Check the official marine forecast and warnings before you go."
        )
        panels.showPanel("How the forecast works", body)
    }

    /** The alert's settings: places, limits, hours, days, and saving the forecast. */
    private fun showAlerts() {
        val r = ConditionsAlerts.load(preferences)
        val u = units()
        fun update(new: AlertRules) {
            ConditionsAlerts.save(activity, preferences, new)
            store.forget(ConditionsAlerts.AREA_PREFIX)
            if (new.enabled && !r.enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            }
            refreshDialog()
            showAlerts()
        }
        val body = panels.body()
        body.addView(panels.caption(
            "Every ${ConditionsAlerts.CHECK_HOURS} hours Android wakes a small check for a few seconds (the app doesn't run, " +
                "so it costs next to no battery) that looks at the forecast for your places. It tells you when a day looks " +
                "good, and when one stops looking good, with a short breakdown. Nothing is sent between 9 pm and 7 am.",
            topPaddingDp = 0
        ))
        panels.addRow(body, panels.row("Alert", if (r.enabled) "On" else "Off") { update(r.copy(enabled = !r.enabled)) })
        if (r.enabled && !notificationsAllowed()) {
            panels.addRow(body, panels.row("Notifications are off", "Tap to allow them for SeaBathy, or the alert can't tell you") {
                runCatching {
                    activity.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                    )
                }
            })
        }

        body.addView(panels.sectionHeader("PLACES"))
        if (r.places.isEmpty()) {
            body.addView(panels.caption(
                "Add the water you go out on. Until you do, the alert checks your spot " +
                    "(${if (followGps) "the boat's last position" else "the spot you set"}).",
                topPaddingDp = 0
            ))
        }
        r.places.forEach { place ->
            panels.addRow(body, panels.row(place.name, "Within ${place.radiusKm.toInt()} km · tap to change") {
                val options = listOf("Show on the map", "Move or resize", "Rename", "Remove")
                panels.showChoicePanel(place.name, options, -1) { i ->
                    when (i) {
                        0 -> {
                            panels.dismissAll()
                            showAlertPlace(place.lat, place.lng, place.radiusKm)
                        }
                        1 -> pickAlertPlace(place)
                        2 -> nameAlertPlace(place.name) { name -> update(r.copy(places = r.places.map { if (it.id == place.id) it.copy(name = name) else it })) }
                        else -> panels.confirm("Remove ${place.name}?", "The alert stops checking it, and its saved forecast is removed.", "Remove") {
                            update(r.copy(places = r.places.filter { it.id != place.id }))
                        }
                    }
                }
            })
        }
        if (r.places.size < AlertRules.MAX_PLACES) {
            panels.addRow(body, panels.row("Add a place", "Choose it on the map, with how far round it to check") { pickAlertPlace(null) })
        }

        body.addView(panels.sectionHeader("GOOD MEANS"))
        panels.addRow(body, panels.row("Wind at most", u.wind.format(r.windMaxKn)) {
            val choices = if (u.wind == SeaSpeedUnit.BEAUFORT) BEAUFORT_WIND_LIMITS else listOf(5.0, 8.0, 10.0, 12.0, 15.0, 18.0, 20.0, 25.0)
            panels.showChoicePanel("Wind at most", choices.map { u.wind.format(it) }, tick(choices, r.windMaxKn, u)) { i -> update(r.copy(windMaxKn = choices[i])) }
        })
        panels.addRow(body, panels.row("Gusts at most", if (r.gustMaxKn > 0) u.wind.format(r.gustMaxKn) else "Any") {
            val choices = listOf(0.0) + if (u.wind == SeaSpeedUnit.BEAUFORT) BEAUFORT_GUST_LIMITS else listOf(10.0, 12.0, 15.0, 18.0, 20.0, 25.0, 30.0)
            panels.showChoicePanel("Gusts at most", choices.map { if (it > 0) u.wind.format(it) else "Any (no gust limit)" }, tick(choices, r.gustMaxKn, u)) { i ->
                update(r.copy(gustMaxKn = choices[i]))
            }
        })
        panels.addRow(body, panels.row("Waves at most", wavesText(r.waveMaxM)) {
            val choices = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
            panels.showChoicePanel("Waves at most", choices.map { wavesText(it) }, choices.indexOf(r.waveMaxM)) { i -> update(r.copy(waveMaxM = choices[i])) }
        })
        panels.addRow(body, panels.row("For at least", "${r.minHours} hours in a row") {
            val choices = listOf(2, 3, 4, 5, 6, 8)
            panels.showChoicePanel("For at least", choices.map { "$it hours" }, choices.indexOf(r.minHours)) { i -> update(r.copy(minHours = choices[i])) }
        })
        panels.addRow(body, panels.row("Between", hoursLabel(r)) {
            val starts = listOf<Int?>(null, 4, 5, 6, 7, 8, 9, 10)
            panels.showChoicePanel("From", starts.map { it?.let { h -> hourText(h) } ?: "Sunrise" }, starts.indexOf(r.fromHour)) { i ->
                val from = starts[i]
                val ends = listOf<Int?>(null, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20).filter { it == null || from == null || it >= from + 2 }
                panels.showChoicePanel("Until", ends.map { it?.let { h -> hourText(h) } ?: "Sunset" }, ends.indexOf(r.toHour)) { j ->
                    update(r.copy(fromHour = from, toHour = ends[j]))
                }
            }
        })
        panels.addRow(body, panels.row("Look ahead", "Today and the next ${r.daysAhead} days") {
            val choices = (1..7).toList()
            panels.showChoicePanel("Look ahead", choices.map { "Today and the next $it day${if (it == 1) "" else "s"}" }, choices.indexOf(r.daysAhead)) { i -> update(r.copy(daysAhead = choices[i])) }
        })
        val dayText = when (r.weekdays) {
            AlertRules.ALL_DAYS -> "Any day"
            AlertRules.WEEKENDS -> "Weekends"
            else -> r.weekdays.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
        }
        panels.addRow(body, panels.row("Days", dayText) {
            val labels = listOf("Any day", "Weekends") + (1..7).map { d -> (if (d in r.weekdays && r.weekdays != AlertRules.ALL_DAYS) "✓ " else "") + DAY_NAMES_LONG[d - 1] }
            panels.showChoicePanel("Days (tap a day to add or remove it)", labels, -1) { i ->
                val days = when (i) {
                    0 -> AlertRules.ALL_DAYS
                    1 -> AlertRules.WEEKENDS
                    else -> {
                        val d = i - 1
                        val from = if (r.weekdays == AlertRules.ALL_DAYS) emptySet() else r.weekdays
                        (if (d in from) from - d else from + d).ifEmpty { AlertRules.ALL_DAYS }
                    }
                }
                update(r.copy(weekdays = days))
            }
        })

        body.addView(panels.sectionHeader("READY WHEN YOU OPEN THE APP"))
        panels.addRow(body, panels.row("Save the forecast when it looks good", saveLabel(r.saveForecast)) {
            val choices = listOf(AlertRules.SAVE_ANY, AlertRules.SAVE_WIFI, AlertRules.SAVE_OFF)
            panels.showChoicePanel("Save the forecast when it looks good", choices.map { saveLabel(it) }, choices.indexOf(r.saveForecast)) { i ->
                update(r.copy(saveForecast = choices[i]))
            }
        })
        body.addView(panels.caption(
            "When a day looks good, the check also saves the forecast for about 200 km round the place (no satellite " +
                "pictures), so it's on the map as soon as you tap the alert, even offline. Each place keeps one copy, " +
                "replaced each time, and old forecasts are removed by themselves 2 days after they run out.",
            topPaddingDp = 0
        ))

        panels.addRow(body, panels.row("Check now", "Look at the forecast straight away") {
            scope.launch {
                val now = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) { runCatching { ConditionsAlerts.check(preferences, now) } }
                val found = result.getOrNull()
                when {
                    result.isFailure -> callbacks.toast((result.exceptionOrNull() as? LimitReachedException)?.message ?: "Couldn't get the forecast: check the connection")
                    found == null -> callbacks.toast("Add a place first, or set a spot (Sea conditions → Spot)")
                    else -> {
                        // What's shown here counts as told, so the next notification brings only news.
                        ConditionsAlerts.remember(preferences, found.second)
                        val text = found.second.joinToString("\n\n") { (place, good) ->
                            place.name + "\n" + if (good.isEmpty()) "Nothing inside your limits in the next ${found.first.daysAhead} days"
                            else good.joinToString("\n") { ConditionsAlerts.line(it, u) }
                        }
                        panels.confirm("Good days coming up", text + "\n\nThe worst of the forecast across each place. Check it before you go.",
                            confirmLabel = "OK", destructive = false) {}
                    }
                }
            }
        })
        body.addView(panels.caption(
            "The checks use about ${ConditionsAlerts.callsPerDay(r)} forecast calls a day (each point checked is one call " +
                "for the sea and one for the wind). Saving the forecast when a day looks good costs about 500 calls more, " +
                "at most once a day for each place. Always check the latest forecast and warnings.",
            topPaddingDp = 4
        ))
        alertsDialog?.dismiss()
        alertsDialog = panels.showPanel("Good-weather alert", body)
    }

    /** Chooses a place on the map ([editing]: moves or resizes that one), then names a new one. */
    private fun pickAlertPlace(editing: AlertPlace?) {
        val root = boundRoot ?: return
        val map = mapHolder.map ?: return
        panels.dismissAll()
        alertsDialog = null
        AlertPlacePicker(activity, root, map, onDone = { lat, lng, radiusKm ->
            val r = ConditionsAlerts.load(preferences)
            if (editing != null) {
                ConditionsAlerts.save(activity, preferences, r.copy(places = r.places.map {
                    if (it.id == editing.id) it.copy(lat = lat, lng = lng, radiusKm = radiusKm) else it
                }))
                showAlerts()
            } else {
                nameAlertPlace("Place ${r.places.size + 1}") { name ->
                    val place = AlertPlace(UUID.randomUUID().toString().take(8), name, lat, lng, radiusKm)
                    val now = ConditionsAlerts.load(preferences)
                    ConditionsAlerts.save(activity, preferences, now.copy(places = (now.places + place).take(AlertRules.MAX_PLACES)))
                    callbacks.toast("$name added")
                    showAlerts()
                }
            }
        }, onCancel = { showAlerts() }).show(editing?.let { it.lat to it.lng }, editing?.radiusKm)
    }

    private fun nameAlertPlace(current: String, onName: (String) -> Unit) {
        val body = panels.body()
        val input = panels.input {
            setText(current)
            setSingleLine(true)
            selectAll()
            hint = "Name, e.g. Home reef"
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        panels.addRow(body, input)
        lateinit var dialog: android.app.Dialog
        dialog = panels.showPanel("Name this place", body, actionLabel = "Save", onAction = {
            val name = input.text.toString().trim().ifEmpty { current }
            dialog.dismiss()
            onName(name)
        })
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /**
     * An alert's place on the map, from a tapped notification or the place list: the sea conditions
     * layer on, the map over the place, and the newest saved forecast there (the alert's check may
     * have just saved it).
     */
    fun showAlertPlace(lat: Double, lng: Double, radiusKm: Double) {
        store.forget(ConditionsAlerts.AREA_PREFIX)
        val b = boxAround(lat, lng, max(radiusKm * 1.5, 15.0))
        callbacks.showArea(GeoBounds(west = b[1], south = b[0], east = b[3], north = b[2]))
        scope.launch {
            val summaries = withContext(work) { store.summaries() }
            // Of the copies saved in the last day, the one reaching furthest (the alert's own reaches its
            // good days; My spot's may be newer but shorter); else the newest.
            val now = System.currentTimeMillis()
            val reaching = summaries.filter { lat in it.south..it.north && lng in it.west..it.east && now - it.fetchedMillis in 0..ALERT_COPY_FRESH_MS }
                .maxWithOrNull(compareBy<ConditionsStore.Summary>({ it.forecastUntilMillis ?: 0L }, { it.fetchedMillis }))
            val newest = reaching ?: ConditionsPolicy.newestCovering(lat, lng, summaries)
            val loaded = newest?.let { s -> withContext(work) { store.load(s.id) } }
            if (loaded != null) {
                area = loaded
                areaId = loaded.id
                preferences.edit().putString(KEY_AREA, loaded.id).apply()
                lastPictureKey = null
                scaleCache = null
            }
            if (!shown) setShown(true) else redraw()
            callbacks.creditsChanged()
            if (loaded == null) callbacks.toast("No forecast saved here yet: open Sea conditions to download it")
        }
    }

    private var alertsDialog: android.app.Dialog? = null

    /** Which of the alert's speed [choices] (knots) is [saved]: in Beaufort, the one of the same force. */
    private fun tick(choices: List<Double>, saved: Double, u: NavUnits): Int =
        if (u.wind != SeaSpeedUnit.BEAUFORT || saved <= 0) choices.indexOf(saved)
        else choices.indexOfFirst { it > 0 && SeaSpeedUnit.beaufort(it) == SeaSpeedUnit.beaufort(saved) }

    /** A wave height in the chosen unit: "1.5 m". */
    private fun wavesText(metres: Double): String {
        val u = units().waves
        return String.format(Locale.US, if (u.of(metres) < 10) "%.1f %s" else "%.0f %s", u.of(metres), u.label)
    }

    private fun refreshDialog() {
        dialogBody?.let { fill(it) }
    }

    private fun fill(body: LinearLayout) {
        body.removeAllViews()
        body.addView(panels.caption(
            "Wind, waves, swell and currents (tidal flow included) for up to 8 days, plus sea temperature and its " +
                "breaks, chlorophyll, sea level, salinity and water clarity from satellites, and temperature at depth from an ocean model. " +
                "Worldwide, and saved on the phone for use offline.",
            topPaddingDp = 8
        ))

        val summaries = store.cachedSummaries().orEmpty()
        panels.addRow(body, panels.row("Show on the map", if (shown) colour.label else "Off") {
            // The forecast's sea temperature isn't offered, but when the Sea line showed it, it's ticked as what shows.
            val choices = if (shown && colour != ColourChoice.NONE && colour !in MAP_CHOICES) MAP_CHOICES + colour else MAP_CHOICES
            val labels = listOf("Off") + choices.map { it.label }
            panels.showChoicePanel("Show on the map", labels, if (shown) choices.indexOf(colour) + 1 else 0) { i ->
                if (i == 0) {
                    setShown(false)
                } else if (area == null && spotArea == null && summaries.isEmpty()) {
                    callbacks.toast("Nothing downloaded yet: tap Update now")
                } else if (!(shown && colour == choices[i - 1])) {
                    // Picking what already shows keeps it: only a box line's second tap hides it.
                    toggleLayer(choices[i - 1])
                }
                refreshDialog()
            }
        })
        body.addView(panels.caption(
            "Or tap a line in the box on the chart: tap the Sea line again for chlorophyll, and once more to hide it. " +
                "Wind, current, waves and swell are forecasts: the time slider steps " +
                "through them, with moving lines for their direction. The satellite maps are one picture each, of the day the " +
                "satellite saw (2 to 5 days ago), and temperature at depth is a model's for today: no slider with those. " +
                "Temperature breaks are where warm and cold water meet, often where fish gather.",
            topPaddingDp = 0
        ))

        body.addView(panels.sectionHeader("SPOT"))
        panels.addRow(body, panels.row("Spot", spotLabel()) { setSpot() })
        panels.addRow(body, panels.row("Update automatically", autoLabel(autoHours)) {
            val choices = listOf(1, 0)
            panels.showChoicePanel("Update automatically", choices.map { autoLabel(it) }, if (keepUpdated) 0 else 1) { i ->
                autoHours = choices[i]
                preferences.edit().putInt(KEY_AUTO_HOURS, autoHours).apply()
                engine.checkRun(force = true)
                engine.setSpot(spotTarget())
                refreshDialog()
            }
        })
        body.addView(panels.caption(
            "Online, the app checks for a new forecast every ${RUN_CHECK_MS / 60_000} minutes and when you open it, and downloads " +
                "${SPOT_RADIUS_KM.roundToInt()} km round the spot when there is one. Each update replaces the last; " +
                "old copies and forecasts that ran out are removed. Offline, the latest saved data is shown.",
            topPaddingDp = 0
        ))
        panels.addRow(body, panels.row(if (downloading) "Downloading…" else "Update now", "${dayText(days)} of forecast" + if (withSatellite) " + satellite" else "") {
            if (downloading) return@row
            engine.setSpot(spotTarget())
            engine.evaluate(userAsked = true)?.let { callbacks.toast(it) }
        })
        panels.addRow(body, panels.row("How the forecast works", "Where it comes from and how often it changes") { showHowItWorks() })

        body.addView(panels.sectionHeader("ALERTS"))
        panels.addRow(body, panels.row("Good-weather alert", alertLabel(ConditionsAlerts.load(preferences))) { showAlerts() })

        body.addView(panels.sectionHeader("ON THE MAP"))
        panels.addRow(body, panels.row("Direction shown as", directionStyle.label) {
            val choices = DirectionStyle.entries
            panels.showChoicePanel("Direction shown as", choices.map { it.label }, choices.indexOf(directionStyle)) { i ->
                directionStyle = choices[i]
                preferences.edit().putString(KEY_STYLE, directionStyle.name).apply()
                redraw()
                refreshDialog()
            }
        })
        panels.addRow(body, panels.row("Values box on the chart", if (boxShown) "On: at the boat or spot; at the crosshair while a layer shows" else "Off") {
            boxShown = !boxShown
            preferences.edit().putBoolean(KEY_BOX, boxShown).apply()
            updateBox()
            refreshDialog()
        })
        panels.addRow(body, panels.row("Sea temperature colours", temperatureRangeLabel()) { pickTemperatureRange() })
        panels.addRow(body, panels.row("Temperature lines", contourStepLabel()) { pickContourStep() })
        panels.addRow(body, panels.row("Target temperature band", targetBandLabel()) { pickTargetBand() })
        panels.addRow(body, panels.row("Eddy outlines", eddyMode.label) {
            val choices = EddyMode.entries
            panels.showChoicePanel("Eddy outlines", choices.map { it.label }, choices.indexOf(eddyMode)) { i ->
                eddyMode = choices[i]
                preferences.edit().putString(KEY_EDDIES, eddyMode.name).apply()
                redraw()
                refreshDialog()
            }
        })
        panels.addRow(body, panels.row("Temperature at depth", "${NavFormat(units()).depth(belowDepthM.toDouble())} down") {
            val choices = BELOW_DEPTHS
            val format = NavFormat(units())
            panels.showChoicePanel("How far down", choices.map { format.depth(it.toDouble()) }, choices.indexOf(belowDepthM)) { i ->
                if (choices[i] == belowDepthM) return@showChoicePanel
                belowDepthM = choices[i]
                preferences.edit().putInt(KEY_BELOW_DEPTH, belowDepthM).apply()
                refreshDialog()
                (area ?: spotArea)?.let { target ->
                    engine.downloadDepth(target.id)?.let { callbacks.toast(it) }
                } ?: callbacks.toast("Saved: the next download gets the temperature at that depth")
            }
        })
        val opacityLabel = panels.caption("Colour opacity ${(opacity * 100).roundToInt()}%", topPaddingDp = 4)
        body.addView(opacityLabel)
        body.addView(SeekBar(activity).apply {
            max = 100
            progress = (opacity * 100).roundToInt()
            contentDescription = "Colour opacity"
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    opacity = progress / 100f
                    opacityLabel.text = "Colour opacity $progress%"
                    applyOpacity()
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    preferences.edit().putFloat(KEY_OPACITY, opacity).apply()
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = activity.dp(6)
        })

        body.addView(panels.sectionHeader("DOWNLOADED AREAS"))
        if (summaries.isEmpty()) body.addView(panels.caption("None yet.", topPaddingDp = 0))
        val format = NavFormat(units())
        for (s in summaries) {
            val active = s.id == area?.id
            val forecastText = s.forecastUntilMillis?.let { until ->
                if (until < System.currentTimeMillis()) "forecast ran out ${day(until)}" else "forecast to ${day(until)}"
            }
            val satText = when (s.satellites.size) {
                0 -> null
                1 -> s.satellites.first().label.lowercase(Locale.US)
                else -> "${s.satellites.size} satellite maps"
            }
            val detail = listOfNotNull(
                if (active && shown) "Showing" else null,
                forecastText,
                satText,
                "got ${format.clock(s.fetchedMillis)}",
                String.format(Locale.US, "%.0f KB", s.bytes / 1024.0)
            ).joinToString(" · ")
            panels.addRow(body, panels.row((if (active) "● " else "") + (if (s.id == SPOT_ID) SPOT_NAME else s.name), detail) { areaOptions(s) })
        }

        body.addView(panels.sectionHeader("DOWNLOADS"))
        panels.addRow(body, panels.row(if (downloading) "Downloading…" else "Save the area on screen", "As well as the spot: another fishing ground") {
            if (downloading) return@row
            downloadScreenArea()
        })
        panels.addRow(body, panels.row("Days of forecast to save", "${dayText(days)} (up to ${ConditionsClient.MAX_DAYS})") {
            val choices = listOf(1, 2, 3, 5, 7, 8)
            panels.showChoicePanel("Days of forecast", choices.map { dayText(it) }, choices.indexOf(days)) { i ->
                val more = choices[i] > days
                days = choices[i]
                preferences.edit().putInt(KEY_DAYS, days).apply()
                // Downloads use it from the next one: say how to get the extra days now.
                if (more) callbacks.toast("Saved: tap Update now to get ${dayText(days)} straight away")
                refreshDialog()
            }
        })
        panels.addRow(body, panels.row("Satellite sea maps", if (withSatellite) "Yes: sea temperature and breaks, chlorophyll, sea level, salinity, water clarity (2 to 10 days old), and temperature at depth (model)" else "No: forecast only") {
            withSatellite = !withSatellite
            preferences.edit().putBoolean(KEY_SATELLITE, withSatellite).apply()
            refreshDialog()
        })
        status?.let { body.addView(panels.caption(it, topPaddingDp = 2)) }
        body.addView(panels.caption(spacingNote(area ?: spotArea), topPaddingDp = 8))
    }

    /** How far apart the forecast's points are where it shows: My spot's wide block gets fewer than a small saved area. */
    private fun spacingNote(a: ConditionsArea?): String {
        val g = a?.forecast?.grid
        val km = if (g != null && g.rows > 1 && g.cols > 1) {
            val midLat = (g.lats.first() + g.lats.last()) / 2
            val latKm = abs(g.lats[1] - g.lats[0]) * KM_PER_DEGREE
            val lngKm = abs(g.lngs[1] - g.lngs[0]) * KM_PER_DEGREE * kotlin.math.cos(Math.toRadians(midLat))
            (latKm + lngKm) / 2
        } else null
        val shore = "Forecasts are less accurate close to shore and in channels."
        return when {
            km == null -> "Forecast points are 9 to 30 km apart (closest in a small saved area), with the colours blended between them. $shore"
            km < 12 -> "Forecast points here are about ${km.roundToInt()} km apart. $shore"
            else -> "Forecast points here are about ${km.roundToInt()} km apart, with the colours blended between them; a saved area " +
                "about 130 km across or smaller gets points about 9 km apart. $shore"
        }
    }

    private fun temperatureRangeLabel(): String {
        val range = temperatureRange ?: return "Automatic: stretched to the area, so small breaks show"
        val format = NavFormat(units())
        return "${format.temperature(range.first)} to ${format.temperature(range.second)}"
    }

    // Temperature lines, target band and eddy outlines -------------------------------

    private fun contourStepLabel(): String {
        val unit = units().temperature
        return when {
            contourStep == 0.0 -> "Off"
            contourStep < 0 -> "Automatic spacing"
            else -> "Every ${stepText(contourStep)} ${unit.label}"
        }
    }

    private fun pickContourStep() {
        val unit = units().temperature
        val values = listOf(-1.0) + Contours.STEPS.toList() + listOf(0.0)
        val labels = values.map { if (it < 0) "Automatic spacing" else if (it == 0.0) "Off" else "Every ${stepText(it)} ${unit.label}" }
        panels.showChoicePanel("Temperature lines", labels, values.indexOf(contourStep).coerceAtLeast(0)) { i ->
            contourStep = values[i]
            preferences.edit().putFloat(KEY_CONTOUR_STEP, contourStep.toFloat()).apply()
            redraw()
            refreshDialog()
        }
    }

    private fun targetBandLabel(): String {
        val band = targetBand ?: return "Off: set one to chase a temperature"
        val format = NavFormat(units())
        return "${format.temperature(band.first)} to ${format.temperature(band.second)}"
    }

    private fun pickTargetBand() {
        panels.showChoicePanel("Target temperature band", listOf("Off", "Set the band"), if (targetBand == null) 0 else 1) { i ->
            if (i == 0) applyTargetBand(null) else editTargetBand()
        }
    }

    private fun editTargetBand() {
        val unit = units().temperature
        val body = panels.body()
        val (startLow, startHigh) = targetBand
            ?: (scaleWithoutRange()?.let { s -> val mid = (s.low + s.high) / 2; (mid - 0.5) to (mid + 0.5) } ?: (22.0 to 24.0))
        fun field(hint: String, celsius: Double) = panels.input {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%.1f", unit.of(celsius)))
            this.hint = hint
        }
        body.addView(panels.caption("Water in this range shows bright with bold edges; the rest is dimmed. Works on the sea temperature maps.", topPaddingDp = 4))
        body.addView(panels.caption("From (${unit.label})", topPaddingDp = 8))
        val low = field("From", startLow)
        panels.addRow(body, low)
        body.addView(panels.caption("To (${unit.label})", topPaddingDp = 8))
        val high = field("To", startHigh)
        panels.addRow(body, high)
        lateinit var d: Dialog
        d = panels.showPanel("Target temperature band", body, actionLabel = "Save", onAction = {
            val a = low.text.toString().toDoubleOrNull()
            val b = high.text.toString().toDoubleOrNull()
            if (a == null || b == null || a >= b) {
                callbacks.toast("Enter two temperatures, the coolest first")
            } else {
                applyTargetBand(unit.toCelsius(a) to unit.toCelsius(b))
                d.dismiss()
            }
        })
    }

    private fun applyTargetBand(band: Pair<Double, Double>?) {
        targetBand = band
        preferences.edit().apply {
            if (band == null) remove(KEY_BAND) else putString(KEY_BAND, String.format(Locale.US, "%.3f,%.3f", band.first, band.second))
        }.apply()
        val a = area
        if (shown && a != null) refreshCard(a)
        redraw()
        refreshDialog()
    }

    /** The map's lines for [choice]: temperature lines, the band's edges, eddy outlines. Off the main thread. */
    private fun contourGeoJson(a: ConditionsArea, choice: ColourChoice, u: NavUnits): String {
        val unit = u.temperature
        val lines = mutableListOf<Pair<ContourLine, Pair<String, String>>>()
        var eddies = emptyList<EddyCentre>()
        val tempGrid = when (choice) {
            ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_BREAKS -> a.satellites[SatelliteKind.SEA_TEMP]
            ColourChoice.TEMP_BELOW -> a.satellites[SatelliteKind.TEMP_BELOW]
            else -> null
        }
        if (tempGrid != null && (contourStep != 0.0 || targetBand != null)) {
            val smooth = Contours.smooth(tempGrid.grid, tempGrid.values, if (tempGrid.grid.size > 40_000) 2 else 1)
            if (contourStep != 0.0) {
                Contours.range(smooth)?.let { (lo, hi) ->
                    val low = unit.of(lo)
                    val high = unit.of(hi)
                    val step = if (contourStep < 0) Contours.autoStep(high - low) else contourStep
                    val levels = Contours.levels(low, high, step)
                    val inCelsius = DoubleArray(levels.size) { unit.toCelsius(levels[it]) }
                    for (line in Contours.lines(tempGrid.grid, smooth, inCelsius, minPoints = 6, rounding = 2, minShare = MIN_LINE_SHARE)) {
                        val inUnit = unit.of(line.level)
                        val major = Contours.isMajor(inUnit, step, levels.size)
                        lines += line to ((if (major) "temp" else "minor") to stepText(inUnit) + "°")
                    }
                }
            }
            targetBand?.let { (lo, hi) ->
                // From the unsmoothed values, so the edges sit exactly where the bright water ends.
                for (line in Contours.lines(tempGrid.grid, tempGrid.values, doubleArrayOf(lo, hi), minPoints = 4, rounding = 1, minShare = MIN_LINE_SHARE)) {
                    lines += line to ("band" to String.format(Locale.US, "%.1f%s", unit.of(line.level), unit.label))
                }
            }
        }
        val level = a.satellites[SatelliteKind.SEA_LEVEL]
        if (level != null && (eddyMode == EddyMode.ALWAYS || (eddyMode == EddyMode.WITH_SEA_LEVEL && choice == ColourChoice.SEA_LEVEL))) {
            // On the sea level map in red and blue; over other maps white, the way IMOS OceanCurrent draws them.
            val light = choice != ColourChoice.SEA_LEVEL
            // Kept inside the area: the coarse sea level grid reaches a little past it.
            val g = level.grid
            val inside = FloatArray(level.values.size) { i ->
                val lat = g.lats[i / g.cols]
                val lng = g.lngs[i % g.cols]
                if (lat < a.south || lat > a.north || lng < a.west || lng > a.east) Float.NaN else level.values[i]
            }
            for (line in Contours.lines(g, inside, EDDY_LEVELS_CM, minPoints = 4, rounding = 2)) {
                val kind = (if (line.level > 0) "warm" else "cold") + if (light) "_light" else ""
                lines += line to (kind to NavFormat(u).seaLevel(line.level))
            }
            eddies = Contours.eddies(g, inside)
        }
        if (lines.isEmpty() && eddies.isEmpty()) return MapLayerIds.EMPTY_GEOJSON
        val format = NavFormat(u)
        return Contours.geoJson(lines, eddies) { format.seaLevel(it.valueCm) }
    }

    private fun installContourLayers(style: org.maplibre.android.maps.Style) {
        runCatching {
            if (style.getSource(CONTOUR_SOURCE) == null) style.addSource(GeoJsonSource(CONTOUR_SOURCE, MapLayerIds.EMPTY_GEOJSON))
            val below = if (style.getLayer(ARROW_LAYER) != null) ARROW_LAYER else null
            fun add(layer: org.maplibre.android.style.layers.Layer) {
                if (style.getLayer(layer.id) != null) return
                if (below != null) style.addLayerBelow(layer, below) else style.addLayerAbove(layer, MapLayerIds.OVERLAY_ANCHOR)
            }
            val kind = Expression.get("kind")
            val isLine = Expression.eq(Expression.geometryType(), Expression.literal("LineString"))
            val isCold = Expression.any(Expression.eq(kind, Expression.literal("cold")), Expression.eq(kind, Expression.literal("cold_light")))
            // Wider with zoom, from the width at zoom 5 to twice it at zoom 11.
            // Zoom must be the outer expression: a width for each kind at zoom 5, doubled by zoom 11.
            fun width(default: Float, vararg cases: Pair<String, Float>): Expression {
                fun at(k: Float) = Expression.match(kind, Expression.literal(default * k),
                    *cases.map { Expression.stop(it.first, Expression.literal(it.second * k)) }.toTypedArray())
                return Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(5, at(1f)), Expression.stop(11, at(2f)))
            }
            // A faint light edge under the dark temperature lines keeps them visible over dark blue water.
            add(LineLayer(CONTOUR_CASING_LAYER, CONTOUR_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.color(0x8CFFFFFF.toInt())),
                PropertyFactory.lineWidth(width(1.8f, "temp" to 2.6f)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            ).withFilter(Expression.all(isLine, Expression.any(Expression.eq(kind, Expression.literal("temp")), Expression.eq(kind, Expression.literal("minor"))))))
            add(LineLayer(CONTOUR_LINE_LAYER, CONTOUR_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.match(kind, Expression.color(0xE60B1622.toInt()),
                    Expression.stop("minor", Expression.color(0xCC0B1622.toInt())),
                    Expression.stop("band", Expression.color(0xFFFFFFFF.toInt())),
                    Expression.stop("warm", Expression.color(0xFF8E1616.toInt())),
                    Expression.stop("warm_light", Expression.color(0xE6FFFFFF.toInt())))),
                PropertyFactory.lineWidth(width(1.1f, "minor" to 0.6f, "band" to 2.4f, "warm" to 1.5f, "warm_light" to 1.3f)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            ).withFilter(Expression.all(isLine, Expression.not(isCold))))
            add(LineLayer(CONTOUR_DASH_LAYER, CONTOUR_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.match(kind, Expression.color(0xFF1B3A8C.toInt()),
                    Expression.stop("cold_light", Expression.color(0xE6FFFFFF.toInt())))),
                PropertyFactory.lineWidth(width(1.5f, "cold_light" to 1.3f)),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f))
            ).withFilter(Expression.all(isLine, isCold)))
            val font = arrayOf(MapStyleFactory.labelFont(style))
            add(SymbolLayer(CONTOUR_LABEL_LAYER, CONTOUR_SOURCE).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(320f),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(font),
                PropertyFactory.textSize(Expression.match(kind, Expression.literal(10.5f), Expression.stop("minor", Expression.literal(9f)), Expression.stop("band", Expression.literal(11f)))),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#0B1622"),
                PropertyFactory.textHaloWidth(1.3f),
                PropertyFactory.textKeepUpright(true),
                PropertyFactory.textMaxAngle(35f)
            ).withFilter(Expression.all(isLine, Expression.neq(Expression.get("label"), Expression.literal("")))))
            add(SymbolLayer(EDDY_LABEL_LAYER, CONTOUR_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(font),
                PropertyFactory.textSize(15f),
                PropertyFactory.textColor(Expression.match(kind, Expression.color(0xFF1E4FD8.toInt()), Expression.stop("warm", Expression.color(0xFFD8281E.toInt())))),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true)
            ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))))
        }.onFailure { DiagnosticLog.logError(TAG, "installing contour layers FAILED", it) }
    }

    private fun pickTemperatureRange() {
        panels.showChoicePanel(
            "Sea temperature colours",
            listOf("Automatic: stretched to the area", "Set the range"),
            if (temperatureRange == null) 0 else 1
        ) { i ->
            if (i == 0) applyTemperatureRange(null) else editTemperatureRange()
        }
    }

    private fun editTemperatureRange() {
        val unit = units().temperature
        val body = panels.body()
        val (startLow, startHigh) = temperatureRange ?: (scaleWithoutRange()?.let { it.low to it.high } ?: (20.0 to 30.0))
        fun field(hint: String, celsius: Double) = panels.input {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%.1f", unit.of(celsius)))
            this.hint = hint
        }
        body.addView(panels.caption("Coolest (${unit.label})", topPaddingDp = 8))
        val low = field("Coolest", startLow)
        panels.addRow(body, low)
        body.addView(panels.caption("Warmest (${unit.label})", topPaddingDp = 8))
        val high = field("Warmest", startHigh)
        panels.addRow(body, high)
        lateinit var d: Dialog
        d = panels.showPanel("Sea temperature colours", body, actionLabel = "Save", onAction = {
            val a = low.text.toString().toDoubleOrNull()
            val b = high.text.toString().toDoubleOrNull()
            if (a == null || b == null || a >= b) {
                callbacks.toast("Enter two temperatures, the coolest first")
            } else {
                applyTemperatureRange(unit.toCelsius(a) to unit.toCelsius(b))
                d.dismiss()
            }
        })
    }

    /** The automatic range for what's on the map now, as a starting point. */
    private fun scaleWithoutRange(): ColourScale? {
        val a = area ?: spotArea ?: return null
        return ColourScale.forChoice(ColourChoice.SEA_TEMP_SATELLITE, a.satellites[SatelliteKind.SEA_TEMP]?.values ?: return null)
    }

    private fun applyTemperatureRange(range: Pair<Double, Double>?) {
        temperatureRange = range
        preferences.edit().apply {
            if (range == null) remove(KEY_TEMP_RANGE)
            else putString(KEY_TEMP_RANGE, String.format(Locale.US, "%.3f,%.3f", range.first, range.second))
        }.apply()
        scaleCache = null
        lastPictureKey = null
        if (shown) redraw()
        refreshDialog()
    }

    private fun areaOptions(s: ConditionsStore.Summary) {
        val options = listOf("Show this area", "Go to it on the map", "Update (download again)", "Rename", "Delete")
        panels.showChoicePanel(s.name, options) { i ->
            when (i) {
                0 -> selectArea(s.id, goTo = false)
                1 -> selectArea(s.id, goTo = true)
                2 -> scope.launch {
                    val saved = withContext(Dispatchers.IO) { store.load(s.id) } ?: return@launch
                    engine.downloadArea(saved.south, saved.west, saved.north, saved.east, saved.id, userAsked = true)?.let { callbacks.toast(it) }
                }
                3 -> rename(s)
                4 -> panels.confirm("Delete ${s.name}?", "Its saved sea conditions are removed from the phone.") {
                    if (s.id == SPOT_ID) engine.spotDeleted()
                    val wasShown = area?.id == s.id
                    if (wasShown) area = null
                    scope.launch {
                        val next = withContext(Dispatchers.IO) {
                            store.delete(s.id)
                            if (wasShown) pickArea() else null
                        }
                        if (wasShown) {
                            area = next
                            if (area == null) setShown(false) else redraw()
                            callbacks.creditsChanged()
                        }
                        updateBox()
                        refreshDialog()
                    }
                }
            }
        }
    }

    private fun selectArea(id: String, goTo: Boolean) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load(id) } ?: run {
                callbacks.toast("Couldn't read that area")
                return@launch
            }
            area = loaded
            areaId = id
            preferences.edit().putString(KEY_AREA, id).apply()
            lastPictureKey = null
            if (goTo) callbacks.showArea(GeoBounds(loaded.west, loaded.south, loaded.east, loaded.north))
            if (!shown) setShown(true) else redraw()
            callbacks.creditsChanged()
            refreshDialog()
        }
    }

    private fun rename(s: ConditionsStore.Summary) {
        val body = panels.body()
        val input = panels.input {
            setText(s.name)
            setSingleLine(true)
            setSelection(text.length)
        }
        panels.addRow(body, input)
        lateinit var d: Dialog
        d = panels.showPanel("Rename area", body, actionLabel = "Save", onAction = {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                scope.launch {
                    val renamed = withContext(Dispatchers.IO) { runCatching { store.rename(s.id, name) }.getOrNull() }
                    if (renamed != null && area?.id == s.id) {
                        area = renamed
                        redraw()
                    }
                    refreshDialog()
                }
            }
            d.dismiss()
        })
    }

    // Downloading ------------------------------------------------------------------

    private fun downloadScreenArea() {
        val bounds = mapHolder.map?.projection?.visibleRegion?.latLngBounds ?: return
        var south = bounds.latitudeSouth
        var north = bounds.latitudeNorth
        var west = bounds.longitudeWest
        var east = bounds.longitudeEast
        // The date line on screen: the data services stop there, so take the side the crosshair is on.
        val crossesDateLine = east < west || abs(east - west) > 180
        if (crossesDateLine) {
            if ((mapHolder.map?.cameraPosition?.target?.longitude ?: 0.0) >= 0) east = ConditionsArea.MAX_LNG else west = -ConditionsArea.MAX_LNG
        }
        // Zoomed out too far: take the middle of the screen, the most an area can be.
        val tooBig = north - south > MAX_SPAN_DEG || east - west > MAX_SPAN_DEG
        // At least a few forecast points across, even zoomed right in.
        fun fit(lo: Double, hi: Double): Pair<Double, Double> {
            val mid = (lo + hi) / 2
            val span = (hi - lo).coerceIn(MIN_SPAN_DEG, MAX_SPAN_DEG)
            return mid - span / 2 to mid + span / 2
        }
        fit(south, north).let { south = it.first.coerceAtLeast(-ConditionsArea.MAX_LAT); north = it.second.coerceAtMost(ConditionsArea.MAX_LAT) }
        fit(west, east).let { west = it.first.coerceAtLeast(-ConditionsArea.MAX_LNG); east = it.second.coerceAtMost(ConditionsArea.MAX_LNG) }
        val note = when {
            tooBig -> "This was the middle ${(MAX_SPAN_DEG * 111).roundToInt()} km of the screen, the most one area holds."
            crossesDateLine -> "This was the side of the date line the crosshair is on."
            else -> null
        }
        engine.downloadArea(south, west, north, east, replacingId = null, userAsked = true, note = note)?.let { callbacks.toast(it) }
    }

    // Helpers ----------------------------------------------------------------------

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minWidth = activity.dp(40)
        minimumWidth = activity.dp(40)
        minHeight = activity.dp(38)
        minimumHeight = activity.dp(38)
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
        if (label == "Now") applyPrimaryStyle(palette) else applySecondaryStyle(palette)
        setOnClickListener { onClick() }
    }

    private fun warningText(): TextView = TextView(activity).apply {
        textSize = 11f
        setTextColor(WARNING)
        visibility = View.GONE
        setPadding(0, activity.dp(3), 0, 0)
    }

    private fun legendLabel(align: Int): TextView = TextView(activity).apply {
        textSize = 11f
        setTextColor(palette.secondaryText)
        gravity = align
    }

    /** A white arrow with a dark edge, pointing up (north); the layer turns it. */
    private fun arrowBitmap(): Bitmap {
        val size = activity.dp(30)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.06f)
            lineTo(s * 0.82f, s * 0.46f)
            lineTo(s * 0.6f, s * 0.46f)
            lineTo(s * 0.6f, s * 0.94f)
            lineTo(s * 0.4f, s * 0.94f)
            lineTo(s * 0.4f, s * 0.46f)
            lineTo(s * 0.18f, s * 0.46f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.1f
            strokeJoin = Paint.Join.ROUND
            color = 0xE60B1622.toInt()
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        })
        return bitmap
    }

    companion object {
        private const val TAG = "SeaBathyConditions"

        private const val KEY_SHOWN = "conditions_shown"
        private const val KEY_WIND_MODEL = "conditions_wind_model"
        private const val WIND_MODEL_KEEP_MS = 7 * 24 * 3_600_000L
        private const val KEY_AREA = "conditions_area"
        private const val KEY_COLOUR = "conditions_colour"
        private const val KEY_OPACITY = "conditions_opacity"
        const val KEY_DAYS = "conditions_days"
        /** Days of forecast downloaded unless the user picks more: enough for the next trip, and quick. */
        const val DEFAULT_DAYS = 2
        /** + an area's id: when its satellite pictures were last fetched. */
        private const val KEY_SATELLITES_AT = WeatherEngine.KEY_SATELLITES_AT
        private const val KEY_SATELLITE = "conditions_satellite"
        private const val KEY_STYLE = "conditions_direction_style"
        private const val KEY_BOX = "conditions_box"
        private const val KEY_SPOT_GPS = "conditions_spot_gps"
        private const val KEY_SPOT = "conditions_spot"
        private const val KEY_AUTO = "conditions_keep_updated"
        private const val KEY_AUTO_HOURS = "conditions_update_hours"
        private const val KEY_BOX_OPEN = "conditions_box_open"
        private const val KEY_LAST_FIX = "conditions_last_fix"
        private const val KEY_PREVIEW = "conditions_preview"
        private const val KEY_BELOW_DEPTH = "conditions_below_depth_m"
        private const val KEY_TEMP_RANGE = "conditions_temperature_range"
        /** Depths the ocean model reports exactly, in metres. */
        private val BELOW_DEPTHS = listOf(10, 20, 30, 50, 100, 150, 200, 300)
        /** How often, at most, to ask whether there's a new forecast. */
        private const val RUN_CHECK_MS = 15 * 60_000L
        private const val FIX_SAVE_MS = 60_000L
        private const val STARTUP_DOWNLOAD_DELAY_MS = 2_000L
        private const val ONLINE_CHECK_MS = 5_000L
        private const val LAST_KNOWN_MS = 30_000L
        private const val CLOSED_MARK = "▾"
        private val ROW_ORDER = listOf("Wind", "Current", "Waves", "Swell", "Sea", "Depth", "Water", "Sun", "Moon")
        /** What "Show on the map" offers, in order (the forecast's own sea temperature is left out: the satellite's is better). */
        private val MAP_CHOICES = listOf(
            ColourChoice.WIND, ColourChoice.CURRENT, ColourChoice.WAVES, ColourChoice.SWELL,
            ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_BREAKS, ColourChoice.TEMP_BELOW,
            ColourChoice.CHLOROPHYLL, ColourChoice.SEA_LEVEL, ColourChoice.SALINITY, ColourChoice.WATER_CLARITY
        )
        private const val OPEN_MARK = "▴"

        /** The area kept round the spot has this id, so each update replaces it. */
        const val SPOT_ID = "spot"
        const val SPOT_NAME = "My spot"
        private const val SPOT_RADIUS_KM = 200.0
        private const val FRESH_COLOUR = 0xFF22C55E.toInt()
        private const val UPDATING_COLOUR = 0xFFF59E0B.toInt()
        private const val OLD_COLOUR = 0xFFEF4444.toInt()
        /** Cells a side in the flow lines' grid: about 3 km apart over a 400 km area. */
        private const val FLOW_GRID_SIDE = 128
        private const val WARNING = 0xFFF5B942.toInt()

        private const val IMAGE_SOURCE = "conditions-image-src"
        private const val CONTOUR_SOURCE = "conditions-contours-src"
        private const val CONTOUR_LINE_LAYER = "conditions-contours"
        private const val CONTOUR_CASING_LAYER = "conditions-contours-casing"
        /** Temperature rings smaller than this share of the area are noise, not features. */
        private const val MIN_LINE_SHARE = 0.03
        private const val CONTOUR_DASH_LAYER = "conditions-contours-cold"
        private const val CONTOUR_LABEL_LAYER = "conditions-contour-labels"
        private const val EDDY_LABEL_LAYER = "conditions-eddy-labels"
        /** Sea level lines drawn round eddies, cm from average. */
        private val EDDY_LEVELS_CM = doubleArrayOf(-30.0, -20.0, -10.0, 10.0, 20.0, 30.0)
        private const val KEY_CONTOUR_STEP = "conditions_contour_step"
        private const val KEY_BAND = "conditions_target_band"
        private const val KEY_EDDIES = "conditions_eddies"

        /** "0.25", "0.5", "1", "24.5": no trailing zeros. */
        private fun stepText(value: Double): String =
            java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        private var astroCache: Pair<String, Astro.Day>? = null

        /** The sun and moon for the day [time] falls in, at about [lat]/[lng] (cached: the box asks often). */
        private fun astroDay(lat: Double, lng: Double, time: Long): Astro.Day {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = time
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val key = String.format(Locale.US, "%d:%.1f:%.1f", cal.timeInMillis, lat, lng)
            astroCache?.takeIf { it.first == key }?.let { return it.second }
            return Astro.day(cal.timeInMillis, lat, lng).also { astroCache = key to it }
        }

        private fun hhmm(millis: Long?): String = if (millis == null) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        private fun shortDay(millis: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

        private const val IMAGE_LAYER = "conditions-image"
        private const val ARROW_SOURCE = "conditions-arrows-src"
        private const val ARROW_LAYER = "conditions-arrows"
        private const val ARROW_IMAGE = "conditions-arrow"

        private const val KNOTS_PER_MS = 1.943844
        /** Largest area: about 500 km a side, so the forecast points stay close enough to be useful. */
        private const val MAX_SPAN_DEG = 4.5
        private const val MIN_SPAN_DEG = 0.3
        /** The current model is about 0.08° apart; closer points add nothing. */
        const val MIN_STEP_DEG = 0.08
        /**
         * Sea points: 16 a side is 256 of the sea forecast's 600 calls a minute, which leaves
         * room for the tides (the same service).
         */
        const val MAX_POINTS_PER_SIDE = 16
        /**
         * The wind is asked on the same points: 256 calls of its own service, which counts apart
         * from the sea forecast's. It was 10 a side, on the thought that wind is smooth; it isn't
         * by the shore, and blended from points 44 km apart it read 3 knots light there.
         */
        const val MAX_WIND_POINTS_PER_SIDE = MAX_POINTS_PER_SIDE
        private const val MAX_ARROWS_PER_SIDE = 12
        private const val KM_PER_DEGREE = 111.2
        /** A saved copy round an alert's place this new can be the one its notification opens. */
        private const val ALERT_COPY_FRESH_MS = 24 * 3_600_000L
        /** "At most force N" in knots: just under force N+1 (forces 2 to 6 for the wind, 3 to 7 for gusts). */
        private val BEAUFORT_WIND_LIMITS = listOf(6.9, 10.9, 16.9, 21.9, 27.9)
        private val BEAUFORT_GUST_LIMITS = listOf(10.9, 16.9, 21.9, 27.9, 33.9)
        private const val BOX_ROWS = 9
        private val SATELLITE_ROWS = setOf("Sea", "Depth", "Water")
        /** Always there, wherever the point: not sea data. */
        private val SKY_ROWS = setOf("Sun", "Moon")
        private const val ARROW_ICON = "arrow"
        private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        private val DAY_NAMES_LONG = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        private const val PLAY_STEP_MS = 650L
        private const val PLAY = "Play"
        private const val PAUSE = "Pause"
        private const val FORECAST_PICTURE_SIDE = 320
        /** Zoomed in: the values for the part on screen are worked out at this size, then stretched and cut at the shore ([sharpen]). */
        private const val VALUES_REGION_SIDE = 384
        /** The map counts as at rest this long after its last move. */
        private const val CAMERA_SETTLE_MS = 350L
        /** The picture reaches this much of the screen's span each way from its middle (0.5 is the screen itself). */
        private const val REGION_REACH = 0.75
        /** A picture this many times the screen's span or more is too coarse to keep: a new one is drawn. */
        private const val REGION_KEEP = 2.2
        /** The whole area's sea, for the flow lines and the nearest water: this many pixels on its longer side. */
        private const val AREA_SEA_SIDE = 1024
        /** Coastline tiles that didn't come are asked for again no sooner than this. */
        private const val COAST_RETRY_MS = 5 * 60_000L
        private const val MAX_PICTURE_SIDE = 512
        /** The forecast picture is redrawn as the time moves on by this much. */
        private const val PICTURE_STEP_MS = 10 * 60_000L
        private const val TICK_MS = 60_000L
        private const val CARD_BOTTOM_DP = 96

        /** In the phone's language at the time, as it can change while the app runs. */
        private fun clock(millis: Long): String = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(millis))

        private fun day(millis: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(millis))

        private fun conditionsThread(name: String, priority: Int) = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread({
                android.os.Process.setThreadPriority(priority)
                runnable.run()
            }, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

        fun dayText(days: Int): String = if (days == 1) "1 day" else "$days days"

        fun autoLabel(hours: Int): String = if (hours > 0) "When there's a new forecast" else "Off (only when you tap Update now)"

        /** How long ago, said simply. */
        fun ago(millis: Long, nowMillis: Long): String {
            val minutes = (nowMillis - millis) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 90 -> "$minutes min ago"
                minutes < 48 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} days ago"
            }
        }

        /**
         * The point of the grid [lats] x [lngs] nearest [lat]/[lng] for which [matches] is true,
         * or null when none is. Pure, unit-tested.
         */
        fun nearestMatching(lats: DoubleArray, lngs: DoubleArray, lat: Double, lng: Double, matches: (Double, Double) -> Boolean): Pair<Double, Double>? {
            val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05)
            val points = ArrayList<Pair<Double, Double>>(lats.size * lngs.size)
            for (pLat in lats) for (pLng in lngs) points += pLat to pLng
            points.sortBy { (pLat, pLng) -> (pLat - lat) * (pLat - lat) + (pLng - lng) * (pLng - lng) * cosLat * cosLat }
            return points.firstOrNull { (pLat, pLng) -> matches(pLat, pLng) }
        }

        /**
         * South, west, north and east of a square [radiusKm] each way from
         * [lat]/[lng], stopping at the date line (the data services do too).
         */
        fun boxAround(lat: Double, lng: Double, radiusKm: Double): DoubleArray {
            val dLat = radiusKm / 111.0
            val dLng = radiusKm / (111.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            return doubleArrayOf(
                (lat - dLat).coerceAtLeast(-ConditionsArea.MAX_LAT), (lng - dLng).coerceAtLeast(-ConditionsArea.MAX_LNG),
                (lat + dLat).coerceAtMost(ConditionsArea.MAX_LAT), (lng + dLng).coerceAtMost(ConditionsArea.MAX_LNG)
            )
        }

        /**
         * The note for data downloaded at [fetchedMillis]: says how old it is
         * when offline (after half an hour) or when it hasn't updated for half a day.
         */
        fun staleNote(fetchedMillis: Long, nowMillis: Long, online: Boolean): String? {
            val age = nowMillis - fetchedMillis
            val ago = ago(fetchedMillis, nowMillis)
            return when {
                !online && age > 30 * 60_000L -> "Offline: saved data from $ago (${clock(fetchedMillis)})"
                age > ConditionsPolicy.MAX_AGE_MS -> "Old data: downloaded $ago"
                else -> null
            }
        }

        /** A legend number: whole above 10, one decimal below, plain 0. */
        private fun legendSpeed(knots: Double, unit: SeaSpeedUnit): String =
            if (unit == SeaSpeedUnit.BEAUFORT) unit.format(knots) else legendNumber(knots * unit.perKnot, unit.label)

        private fun legendNumber(value: Double, unit: String): String = when {
            abs(value) < 0.05 -> "0 $unit"
            abs(value) >= 10 -> "${value.roundToInt()} $unit"
            else -> String.format(Locale.US, "%.1f %s", value, unit)
        }

        /** Layers whose own direction the "same as the map layer" lines show. */
        private val OWN_FLOW = setOf(ColourChoice.WIND, ColourChoice.WAVES, ColourChoice.SWELL, ColourChoice.CURRENT)

        fun defaultName(south: Double, west: Double, north: Double, east: Double): String {
            val lat = (south + north) / 2
            val lng = (west + east) / 2
            return String.format(Locale.US, "%.2f°%s %.2f°%s", abs(lat), if (lat < 0) "S" else "N", abs(lng), if (lng < 0) "W" else "E")
        }

        private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        fun compass(degrees: Double): String = POINTS[(((degrees % 360) + 360) % 360 / 45 + 0.5).toInt() % 8]

        /**
         * One line of the values box: an arrow pointing [towardsDegrees] (none for null), its text, and which
         * [row] it is ("Wind", "Sea", "Depth", "Water"...), which picks the layer a tap on it shows.
         */
        data class Value(
            val towardsDegrees: Double?,
            val text: String,
            val row: String = text.substringBefore(' '),
            /** A small picture in the arrow's place: the sun and moon lines'. */
            val icon: SkyIcon? = null,
            /** The line in full words, for screen readers and a photo's record (the sun and moon lines are short). */
            val spoken: String = text
        )

        /**
         * The values at a point and time, in the user's units. With satellite
         * temperature saved, its coastline says where the sea is, so the
         * forecast reaches the shore the way the colours do.
         */
        fun values(
            a: ConditionsArea, lat: Double, lng: Double, time: Long, format: NavFormat,
            /** Whether the point is sea by the coastline the colours are cut at; null when that can't tell (then the satellite temperature's cells say). */
            sea: Boolean? = null
        ): List<Value> {
            val out = mutableListOf<Value>()
            val isSea = sea ?: a.isSea(lat, lng)
            val masked = sea != null || a.satellites.containsKey(SatelliteKind.SEA_TEMP)
            val f = a.forecast?.takeIf { !masked || isSea != false }
            if (f != null) {
                f.vector(ForecastField.CURRENT_U, ForecastField.CURRENT_V, lat, lng, time, masked)?.let {
                    out += Value(it.towardsDegrees, "Current ${format.current(it.speed * KNOTS_PER_MS)} to ${compass(it.towardsDegrees)}")
                }
                f.vector(ForecastField.WIND_U, ForecastField.WIND_V, lat, lng, time, masked)?.let { wind ->
                    val gust = f.sample(ForecastField.GUST, lat, lng, time, masked)
                    out += Value(
                        wind.towardsDegrees,
                        "Wind ${format.wind(wind.speed * KNOTS_PER_MS)} from ${compass(wind.fromDegrees)}" +
                            if (gust.isFinite() && gust > wind.speed * 1.15) ", gusts ${format.wind(gust * KNOTS_PER_MS)}" else ""
                    )
                }
                wave(f, "Waves", ForecastField.WAVE_HEIGHT, ForecastField.WAVE_PERIOD, ForecastField.WAVE_U, ForecastField.WAVE_V, lat, lng, time, format, masked)?.let { out += it }
                wave(f, "Swell", ForecastField.SWELL_HEIGHT, ForecastField.SWELL_PERIOD, ForecastField.SWELL_U, ForecastField.SWELL_V, lat, lng, time, format, masked)?.let { out += it }
            }
            val water = mutableListOf<String>()
            val satTemp = (if (sea == true) a.toShore(SatelliteKind.SEA_TEMP)?.sampleToShore(lat, lng) else null)?.takeIf { it.isFinite() }
                ?: a.satellites[SatelliteKind.SEA_TEMP]?.sample(lat, lng)
            val modelTemp = f?.sample(ForecastField.SEA_TEMP, lat, lng, time, masked)
            when {
                satTemp != null && satTemp.isFinite() -> water += "Surface temp ${format.temperature(satTemp.toDouble())}" +
                    (a.satellites[SatelliteKind.SEA_TEMP]?.let { " (${shortDay(it.dateMillis)})" } ?: "")
                modelTemp != null && modelTemp.isFinite() -> water += "Surface temp ${format.temperature(modelTemp.toDouble())} (forecast)"
            }
            a.satellites[SatelliteKind.CHLOROPHYLL]?.sample(lat, lng)?.takeIf { it.isFinite() && isSea != false }?.let {
                water += String.format(Locale.US, if (it < 1) "chlorophyll %.2f mg/m³" else "chlorophyll %.1f mg/m³", it)
            }
            if (water.isNotEmpty()) out += Value(null, water.joinToString(" · "), "Sea")

            // The coarser layers, which change slowly: the temperature at depth, then salinity and sea level.
            if (isSea != false) {
                fun coarse(kind: SatelliteKind) = a.satellites[kind]?.let { if (masked) it.sampleToShore(lat, lng) else it.sample(lat, lng) }?.takeIf { it.isFinite() }
                coarse(SatelliteKind.TEMP_BELOW)?.let {
                    out += Value(null, "Sea temp ${format.depth(a.belowDepthM.toDouble())} down ${format.temperature(it.toDouble())}", "Depth")
                }
                val surface = listOfNotNull(
                    coarse(SatelliteKind.SALINITY)?.let { String.format(Locale.US, "Salinity %.1f", it) },
                    coarse(SatelliteKind.SEA_LEVEL)?.let { "sea level ${format.seaLevelVsAverage(it.toDouble())}" },
                    coarse(SatelliteKind.WATER_CLARITY)?.let { WaterClarity.visibilityMetres(it) }?.takeIf { it.isFinite() }
                        ?.let { "visibility about ${WaterClarity.text(it.toDouble(), format)} (estimate)" }
                )
                if (surface.isNotEmpty()) out += Value(null, surface.joinToString(" · ").replaceFirstChar { it.uppercase() }, "Water")
            }

            out += skyValues(lat, lng, time)
            return out
        }

        /** The sun, moon and solunar lines: worked out here, not downloaded. */
        fun skyValues(lat: Double, lng: Double, time: Long): List<Value> {
            val out = mutableListOf<Value>()
            val sky = astroDay(lat, lng, time)
            // A small sun and the moon as it looks tonight start the lines, so the words can be few.
            out += Value(
                null, "${hhmm(sky.sunrise)} – ${hhmm(sky.sunset)} · first light ${hhmm(sky.firstLight)}", "Sun", SkyIcon.Sun,
                spoken = "Sun up ${hhmm(sky.sunrise)} · down ${hhmm(sky.sunset)} · first light ${hhmm(sky.firstLight)}"
            )
            val bites = buildList {
                if (sky.majors.isNotEmpty()) add("major " + sky.majors.joinToString(", ") { hhmm(it) })
                if (sky.minors.isNotEmpty()) add("minor " + sky.minors.joinToString(", ") { hhmm(it) })
            }
            val lit = "${(sky.illumination * 100).roundToInt()}%"
            out += Value(
                null, (listOf(lit) + bites).joinToString(" · "), "Moon", SkyIcon.Moon(sky.illumination, sky.waxing, south = lat < 0),
                spoken = "Moon $lit ${sky.phaseName}" + if (bites.isEmpty()) "" else " · solunar ${bites.joinToString(" · ")}"
            )
            return out
        }

        private fun wave(
            f: ForecastGrid, name: String, height: ForecastField, period: ForecastField, u: ForecastField, v: ForecastField,
            lat: Double, lng: Double, time: Long, format: NavFormat, coastal: Boolean
        ): Value? {
            val h = f.sample(height, lat, lng, time, coastal)
            if (!h.isFinite()) return null
            val p = f.sample(period, lat, lng, time, coastal)
            val flow = f.vector(u, v, lat, lng, time, coastal)
            return Value(
                flow?.towardsDegrees,
                "$name ${format.waveHeight(h.toDouble())}" +
                    (if (p.isFinite()) String.format(Locale.US, ", %.0f s", p) else "") +
                    (flow?.let { " from ${compass(it.fromDegrees)}" } ?: "")
            )
        }
    }
}

/** When eddies are outlined on the map. */
enum class EddyMode(val label: String) {
    WITH_SEA_LEVEL("With the sea level map"),
    ALWAYS("On every sea map (when saved)"),
    OFF("Off")
}
