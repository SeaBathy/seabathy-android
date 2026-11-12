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
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
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
    private val client = ConditionsClient()
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
    private var days = preferences.getInt(KEY_DAYS, 7).coerceIn(1, ConditionsClient.MAX_DAYS)
    private var withSatellite = preferences.getBoolean(KEY_SATELLITE, true)
    /** How far down "temperature below the surface" is downloaded for, in metres. */
    private var belowDepthM = preferences.getInt(KEY_BELOW_DEPTH, ConditionsArea.DEFAULT_BELOW_DEPTH_M)
    /** A range for the temperature colours, in °C; null stretches them to the area. */
    private var temperatureRange: Pair<Double, Double>? = preferences.getString(KEY_TEMP_RANGE, null)?.split(',')
        ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 && it[0] < it[1] }?.let { it[0] to it[1] }
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
    private var lastAutoAttempt = 0L
    /** When the forecast models last had a new run, as last checked; and when that was. */
    private var latestRun: Long? = null
    private var lastRunCheck = 0L
    /** No download starts before this: Open-Meteo allows 600 requests a minute, and one area is about 360. */
    private var nextDownloadAllowed = 0L
    private val retrySpot = Runnable { keepSpotUpdated(force = true) }
    /** The area kept around the spot, when there is one. */
    private var spotArea: ConditionsArea? = null

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
    private var downloadJob: Job? = null
    /** Set for the whole download, cleared before the sheet is redrawn at the end (the job is still active then). */
    private var downloading = false
    private var status: String? = null
    private var dialog: Dialog? = null
    private var dialogBody: LinearLayout? = null
    private var cameraListening = false

    /** The last picture drawn, kept to put back after a style change; and what it was drawn from. */
    private var lastBitmap: Bitmap? = null
    private var lastQuad: LatLngQuad? = null
    private var lastPictureKey: String? = null
    /** What the drawing under way is of. */
    private var drawingKey: String? = null
    private var drawingArrows: ArrowChoice? = null
    private var drawingStyle: DirectionStyle? = null
    private var scaleCache: Pair<String, ColourScale>? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (shown && live) redraw() else updateBox()
            checkForUpdates()
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
            addView(boxTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(boxToggle)
        })
        boxRows.forEach { addView(it.view) }
        addView(boxWarning)
    }

    /** The box's size, for keeping the map's compass clear of it. */
    val boxView: View get() = box

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
            setOnClickListener { layer?.let { toggleLayer(it, from) } }
        }

        fun highlight(on: Boolean) {
            view.background = if (on) activity.roundedBackground(0x3319C6B3, palette.accent, 8) else null
            text.setTextColor(if (on) palette.accent else palette.primaryText)
        }
    }

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
        boxRows.forEach { it.arrow.setImageBitmap(arrowBitmap()) }
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
            area = picked
            spotArea = spot
            // Last time's picture straight away; the fresh one replaces it when drawn.
            val choice = colour
            if (shown && picked != null && lastBitmap == null) {
                withContext(work) { loadPreview(picked, choice) }?.let { showPicture(picked, it, "preview") }
            }
            if (shown) redraw() else updateBox()
            callbacks.creditsChanged()
            removeOldAreas()
            // Let the chart and its layers load first; the update can wait a few seconds.
            delay(STARTUP_DOWNLOAD_DELAY_MS)
            startupDone = true
            checkForUpdates(force = true)
        }
    }

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
        keepSpotUpdated()
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
        checkForUpdates(force = true)
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
        if (startupDone) checkForUpdates()
    }

    fun onPause() {
        ticker.removeCallbacks(tick)
        setPlaying(false)
        // No drawing while the app is in the background.
        flowLines.stop()
    }

    /** Units changed in Settings: the box and the card show their values again in the new ones. */
    fun unitsChanged() {
        val a = area
        if (shown && a != null) refreshCard(a)
        updateBox()
    }

    fun release() {
        ticker.removeCallbacks(retryShown)
        work.close()
        downloads.close()
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(play)
        ticker.removeCallbacks(retrySpot)
        drawJob?.cancel()
        downloadJob?.cancel()
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
        // A new style has none of the layers: put the picture back (drawn
        // before, or saved last time) rather than drawing it again.
        val a = area
        val bitmap = lastBitmap
        if (shown && a != null && bitmap != null) showPicture(a, bitmap, lastPictureKey ?: "")
        if (shown) redraw() else clearMap()
    }

    private fun listenToCamera() {
        if (cameraListening) return
        val map = mapHolder.map ?: return
        cameraListening = true
        map.addOnCameraMoveListener { updateBox() }
        map.addOnCameraIdleListener {
            updateBox()
            showNewestHere()
        }
    }

    /** The credits for whatever is shown, for the attribution line. */
    fun credits(): List<String> {
        val a = area ?: return emptyList()
        if (!shown) return emptyList()
        val sources = buildList {
            if (a.forecast != null) add("Open-Meteo (CC BY 4.0): Copernicus Marine, Météo-France, ECMWF, DWD, NOAA")
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
        flowLines.stop()
        updateBox()
        val style = mapHolder.style ?: return
        runCatching {
            style.getLayer(IMAGE_LAYER)?.let { style.removeLayer(it) }
            style.getSource(IMAGE_SOURCE)?.let { style.removeSource(it) }
            style.getSourceAs<GeoJsonSource>(ARROW_SOURCE)?.setGeoJson(MapLayerIds.EMPTY_GEOJSON)
        }
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
        val key = "${a.id}:${a.fetchedMillis}:$choice:" + (if (choice.forecast) (time / PICTURE_STEP_MS) else 0) +
            (if (choice.isTemperature) ":$temperatureRange" else "")
        // Already drawing this very picture (a style change asks again at start-up): let it finish.
        if (key == drawingKey && drawJob?.isActive == true && arrowChoice == drawingArrows && directionStyle == drawingStyle) return
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
                    val sampler = sampler(a, choice, time) ?: return@withContext null
                    val maxSide = satellite?.let { min(MAX_PICTURE_SIDE, max(it.grid.rows, it.grid.cols) * 2) } ?: FORECAST_PICTURE_SIDE
                    val pixels = ConditionsPicture.render(a.south, a.west, a.north, a.east, max(64, maxSide), scaleFor(a, choice), sampler) { !isActive }
                    Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888).also {
                        DiagnosticLog.log(TAG, "picture $choice ${pixels.width}x${pixels.height} in ${(System.nanoTime() - started) / 1_000_000} ms")
                    }
                }
            }
            val arrowJson = if (directionStyle.arrows) withContext(work) { arrowGeoJson(a, arrowChoice, time) } else MapLayerIds.EMPTY_GEOJSON
            showPicture(a, picture, key)
            mapHolder.style?.getSourceAs<GeoJsonSource>(ARROW_SOURCE)?.setGeoJson(arrowJson)
            if (picture != null) savePreview(a, choice, picture)
        }
    }

    private fun showPicture(a: ConditionsArea, bitmap: Bitmap?, key: String) {
        val style = mapHolder.style
        if (style == null) {
            // Drawn before the map was ready: kept, and put on when it is.
            if (bitmap != null) {
                lastBitmap = bitmap
                lastPictureKey = key
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
        val quad = LatLngQuad(LatLng(a.north, a.west), LatLng(a.north, a.east), LatLng(a.south, a.east), LatLng(a.south, a.west))
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
                if (style.getLayer(ARROW_LAYER) != null) style.addLayerBelow(layer, ARROW_LAYER)
                else style.addLayerAbove(layer, MapLayerIds.OVERLAY_ANCHOR)
            }
        }.onFailure { DiagnosticLog.logError(TAG, "showing the picture FAILED", it) }
        lastBitmap = bitmap
        lastQuad = quad
        lastPictureKey = key
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
     * What the colours show at a point. With satellite temperature saved,
     * its 1 km coastline masks the land, so the coarse forecast reaches the
     * shore and the 9 km chlorophyll stops at it, instead of in blocks.
     */
    private fun sampler(a: ConditionsArea, choice: ColourChoice, time: Long): ConditionsPicture.Sampler? {
        val raw = rawSampler(a, choice, time, coastal = a.satellites.containsKey(SatelliteKind.SEA_TEMP)) ?: return null
        if (choice == ColourChoice.SEA_TEMP_SATELLITE || choice == ColourChoice.SEA_TEMP_BREAKS || !a.satellites.containsKey(SatelliteKind.SEA_TEMP)) return raw
        return ConditionsPicture.Sampler { lat, lng -> if (a.isSea(lat, lng) == false) Float.NaN else raw.at(lat, lng) }
    }

    private fun rawSampler(a: ConditionsArea, choice: ColourChoice, time: Long, coastal: Boolean): ConditionsPicture.Sampler? {
        val f = a.forecast
        return when (choice) {
            ColourChoice.NONE -> null
            ColourChoice.SEA_TEMP_SATELLITE -> {
                val sat = a.satellites[SatelliteKind.SEA_TEMP] ?: return null
                ConditionsPicture.Sampler { lat, lng -> sat.sample(lat, lng) }
            }
            ColourChoice.SEA_TEMP_BREAKS -> {
                val breaks = a.breaks ?: return null
                ConditionsPicture.Sampler { lat, lng -> breaks.sample(lat, lng) }
            }
            ColourChoice.CHLOROPHYLL -> {
                val sat = a.satellites[SatelliteKind.CHLOROPHYLL] ?: return null
                ConditionsPicture.Sampler { lat, lng -> sat.sample(lat, lng) }
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
            flowLines.stop()
            return
        }
        val (u, v) = fields
        val masked = a.satellites.containsKey(SatelliteKind.SEA_TEMP)
        val heightField = when (choice) {
            ArrowChoice.WAVES -> ForecastField.WAVE_HEIGHT
            ArrowChoice.SWELL -> ForecastField.SWELL_HEIGHT
            else -> null
        }
        flowLines.show(
            FlowLinesView.Field { lat, lng, out ->
                if (masked && a.isSea(lat, lng) == false) return@Field false
                val east = f.sample(u, lat, lng, time, coastal = masked)
                val north = f.sample(v, lat, lng, time, coastal = masked)
                if (east.isNaN() || north.isNaN()) return@Field false
                // Waves and swell store a direction only: their height sets the speed.
                var scale = 1f
                if (heightField != null) {
                    scale = f.sample(heightField, lat, lng, time, coastal = masked)
                    if (scale.isNaN()) return@Field false
                }
                out[0] = east.toDouble() * scale
                out[1] = north.toDouble() * scale
                true
            },
            choice.reference, a.south, a.west, a.north, a.east
        )
    }

    private fun arrowGeoJson(a: ConditionsArea, choice: ArrowChoice, time: Long): String {
        val f = a.forecast ?: return MapLayerIds.EMPTY_GEOJSON
        val (u, v) = choice.fields ?: return MapLayerIds.EMPTY_GEOJSON
        val reference = choice.reference
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        // No more than about a dozen arrows across: the flow lines show the detail.
        val stride = max(1, (max(f.grid.rows, f.grid.cols) + MAX_ARROWS_PER_SIDE - 1) / MAX_ARROWS_PER_SIDE)
        for (r in 0 until f.grid.rows step stride) for (c in 0 until f.grid.cols step stride) {
            val lat = f.grid.lats[r]
            val lng = f.grid.lngs[c]
            if (a.isSea(lat, lng) == false) continue
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
        val time = timeMillis
        val usesForecast = (colour.forecast || (directionStyle != DirectionStyle.NONE && flow != ArrowChoice.NONE)) && forecast != null
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
            colour.satellite != null && satellite == null -> "No ${colour.satellite?.label?.lowercase(Locale.US)} saved here: update the area"
            colour.forecast && forecast == null -> "No forecast saved for this area"
            usesForecast && time > forecast.endMillis + ForecastGrid.HOUR_MS -> "The forecast ran out ${day(forecast.endMillis)}: update it"
            usesForecast -> clock(time) + (if (live) " (now)" else "") + (satellite?.let { " · $source ${day(it.dateMillis)}" } ?: "")
            satellite != null -> "$source, ${day(satellite.dateMillis)}"
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
            legendBar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, IntArray(16) { scale.colourAt(it / 15.0) }).apply {
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
        val map = mapHolder.map
        val target = map?.cameraPosition?.target
        // While navigating, the navigation display already fills the top of the chart.
        if (!boxShown || map == null || callbacks.isNavigating()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val time = timeMillis
        val a: ConditionsArea
        val lat: Double
        val lng: Double
        val wanted: Set<String>?
        val shownArea = area
        if (shown && shownArea != null && target != null && shownArea.contains(target.latitude, target.longitude)) {
            // The map layer is on: everything, where the crosshair is.
            a = shownArea
            lat = target.latitude
            lng = target.longitude
            wanted = if (boxOpen) null else setOfNotNull("Wind", rowNameOf(colour))
            boxTitle.text = "At the crosshair · " + if (live) "now" else clock(time)
        } else {
            val spot = spot()
            val data = listOfNotNull(spotArea, shownArea).firstOrNull { spot != null && it.contains(spot.first, spot.second) }
            if (spot == null || data == null) {
                boxTitle.text = when {
                    downloading -> "Sea conditions · downloading…"
                    spot == null && followGps -> "Sea conditions · waiting for the GPS"
                    keepUpdated && !online() -> "Sea conditions · none saved here (offline)"
                    else -> "Sea conditions · tap to set up"
                }
                boxRows.forEach { it.view.visibility = View.GONE }
                boxWarning.visibility = View.GONE
                return
            }
            a = data
            lat = spot.first
            lng = spot.second
            wanted = if (boxOpen) null else setOfNotNull("Wind", rowNameOf(colour).takeIf { shown })
            boxTitle.text = (if (followGps) "At the boat" else "At your spot") + " · " + if (live) "now" else clock(time)
        }
        // The card already says when the layer's area is old.
        if (shown && a === shownArea) boxWarning.visibility = View.GONE else showWarning(boxWarning, a)
        // Wind first (it's what the box shows closed), then the rest in a fixed order.
        val values = values(a, lat, lng, time, NavFormat(units()))
            .filter { wanted == null || it.text.substringBefore(' ') in wanted }
            .sortedBy { ROW_ORDER.indexOf(it.text.substringBefore(' ')).let { i -> if (i < 0) ROW_ORDER.size else i } }
        val bearing = map.cameraPosition.bearing
        boxRows.forEachIndexed { i, row ->
            val value = values.getOrNull(i)
            if (value == null) {
                row.view.visibility = View.GONE
                return@forEachIndexed
            }
            row.view.visibility = View.VISIBLE
            row.text.text = value.text
            row.layer = layerOfRow(value.text.substringBefore(' '), a)
            row.from = a
            row.highlight(shown && row.layer != null && (row.layer == colour || (colour == ColourChoice.CHLOROPHYLL && row.layer?.satellite != null)))
            if (value.towardsDegrees == null) {
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
            else "No sea data here (on land?)"
        }
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
        val note = staleNote(a.fetchedMillis, System.currentTimeMillis(), online())
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

    /** The box line that shows [layer], by its first word. */
    private fun rowNameOf(layer: ColourChoice): String? = when (layer) {
        ColourChoice.WIND -> "Wind"
        ColourChoice.CURRENT -> "Current"
        ColourChoice.WAVES -> "Waves"
        ColourChoice.SWELL -> "Swell"
        ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_BREAKS, ColourChoice.SEA_TEMP_FORECAST, ColourChoice.CHLOROPHYLL -> "Sea"
        ColourChoice.TEMP_BELOW, ColourChoice.SEA_LEVEL, ColourChoice.SALINITY -> "Water"
        ColourChoice.NONE -> null
    }

    /** What tapping a box line shows on the map. */
    private fun layerOfRow(name: String, a: ConditionsArea): ColourChoice? = when (name) {
        "Wind" -> ColourChoice.WIND
        "Current" -> ColourChoice.CURRENT
        "Waves" -> ColourChoice.WAVES
        "Swell" -> ColourChoice.SWELL
        "Sea" -> if (SatelliteKind.SEA_TEMP in a.satellites) ColourChoice.SEA_TEMP_SATELLITE else ColourChoice.SEA_TEMP_FORECAST
        // Keeps the layer showing when it's one of this line's.
        "Water" -> if (rowNameOf(colour) == "Water" && a.satellites[colour.satellite] != null) colour else when {
            SatelliteKind.TEMP_BELOW in a.satellites -> ColourChoice.TEMP_BELOW
            SatelliteKind.SEA_LEVEL in a.satellites -> ColourChoice.SEA_LEVEL
            else -> ColourChoice.SALINITY
        }
        else -> null
    }

    /**
     * Keeps the area round the spot downloaded: when online, if there's none,
     * it's older than the update interval, or the spot has moved out of its middle.
     */
    private fun keepSpotUpdated(force: Boolean = false) {
        if (!keepUpdated || downloading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoAttempt < AUTO_RETRY_MS) return
        // No position yet (GPS off, just started): keep the last spot's area fresh, or the
        // area on the map's, rather than nothing.
        val (lat, lng) = spot() ?: (spotArea ?: area)?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 } ?: run {
            DiagnosticLog.log(TAG, "spot not updated: no GPS position yet and nothing saved to go by")
            return
        }
        val saved = spotArea
        val fresh = saved != null && !ConditionsPolicy.needsUpdate(saved.fetchedMillis, latestRun, now) && saved.middleContains(lat, lng)
        if (fresh) return
        if (!online()) {
            DiagnosticLog.log(TAG, "spot needs an update but there's no internet: showing the saved data")
            return
        }
        if (now < nextDownloadAllowed) {
            // Too soon after the last one: try again when it's allowed.
            ticker.removeCallbacks(retrySpot)
            ticker.postDelayed(retrySpot, nextDownloadAllowed - now + 500)
            return
        }
        lastAutoAttempt = now
        DiagnosticLog.log(
            TAG,
            "updating the spot: " + when {
                saved == null -> "nothing saved yet"
                !saved.middleContains(lat, lng) -> "the spot moved out of the saved area's middle"
                else -> "saved ${ago(saved.fetchedMillis, now)}, newest forecast run ${latestRun?.let { ago(it, now) } ?: "unknown"}"
            }
        )
        val b = boxAround(lat, lng, SPOT_RADIUS_KM)
        download(b[0], b[1], b[2], b[3], replacingId = SPOT_ID, quiet = true)
    }

    /**
     * When online, at most every [RUN_CHECK_MS] (or now, with [force]):
     * asks whether a newer forecast run is out, then updates the spot's area
     * and the area on the map when theirs is older. Offline it does nothing:
     * the saved data stays on show, marked as old.
     */
    private fun checkForUpdates(force: Boolean = false) {
        if (!keepUpdated) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRunCheck < RUN_CHECK_MS) return
        if (!online()) return
        lastRunCheck = now
        scope.launch {
            val run = withContext(downloads) { runCatching { client.latestRunMillis() }.getOrNull() }
            if (run != null) latestRun = run
            DiagnosticLog.log(TAG, "update check: newest forecast run ${run?.let { ago(it, System.currentTimeMillis()) } ?: "couldn't be read"}")
            keepSpotUpdated(force = true)
            updateShownArea()
        }
    }

    /** The area on the map, when it isn't the spot's: updated the same way, after any download under way. */
    private fun updateShownArea() {
        val a = area ?: return
        if (a.id == SPOT_ID || !keepUpdated) return
        val now = System.currentTimeMillis()
        if (!ConditionsPolicy.needsUpdate(a.fetchedMillis, latestRun, now) || !online()) return
        if (downloading || now < nextDownloadAllowed) {
            ticker.removeCallbacks(retryShown)
            ticker.postDelayed(retryShown, max(nextDownloadAllowed - now, 5_000L) + 500)
            return
        }
        DiagnosticLog.log(TAG, "updating ${a.name}: saved ${ago(a.fetchedMillis, now)}")
        download(a.south, a.west, a.north, a.east, replacingId = a.id, quiet = true)
    }

    private val retryShown = Runnable { updateShownArea() }

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
                    DiagnosticLog.log(TAG, "removed ${summary.name}: " + if (reason == ConditionsPolicy.Reason.EXPIRED) "its forecast ran out" else "a newer download covers it")
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
        keepSpotUpdated(force = true)
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

    private fun refreshDialog() {
        dialogBody?.let { fill(it) }
    }

    private fun fill(body: LinearLayout) {
        body.removeAllViews()
        body.addView(panels.caption(
            "Wind, waves, swell and currents (tidal flow included) for up to 8 days, plus sea temperature and its " +
                "breaks, chlorophyll, sea level and salinity from satellites, and temperature at depth from an ocean model. " +
                "Worldwide, and saved on the phone for use offline.",
            topPaddingDp = 8
        ))

        val summaries = store.cachedSummaries().orEmpty()
        panels.addRow(body, panels.row("Show on the map", if (shown) colour.label else "Off") {
            val choices = MAP_CHOICES
            val labels = listOf("Off") + choices.map { it.label }
            panels.showChoicePanel("Show on the map", labels, if (shown) choices.indexOf(colour) + 1 else 0) { i ->
                if (i == 0) {
                    setShown(false)
                } else if (area == null && spotArea == null && summaries.isEmpty()) {
                    callbacks.toast("Nothing downloaded yet: tap Update now")
                } else {
                    toggleLayer(choices[i - 1])
                }
                refreshDialog()
            }
        })
        body.addView(panels.caption(
            "Or tap a line in the box on the chart. Each shows its own direction lines; sea temperature and chlorophyll " +
                "show the current's. Temperature breaks are where warm and cold water meet, often where fish gather.",
            topPaddingDp = 0
        ))

        body.addView(panels.sectionHeader("SPOT"))
        panels.addRow(body, panels.row("Spot", spotLabel()) { setSpot() })
        panels.addRow(body, panels.row("Update automatically", autoLabel(autoHours)) {
            val choices = listOf(1, 0)
            panels.showChoicePanel("Update automatically", choices.map { autoLabel(it) }, if (keepUpdated) 0 else 1) { i ->
                autoHours = choices[i]
                preferences.edit().putInt(KEY_AUTO_HOURS, autoHours).apply()
                checkForUpdates(force = true)
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
            val (lat, lng) = spot() ?: spotArea?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 } ?: run {
                callbacks.toast("Waiting for the GPS: set a spot, or turn the GPS on")
                return@row
            }
            if (!callbacks.isOnline()) {
                callbacks.toast("No internet: the saved data is still shown")
                return@row
            }
            val b = boxAround(lat, lng, SPOT_RADIUS_KM)
            lastAutoAttempt = System.currentTimeMillis()
            download(b[0], b[1], b[2], b[3], replacingId = SPOT_ID)
        })

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
        panels.addRow(body, panels.row("Temperature at depth", "${NavFormat(units()).depth(belowDepthM.toDouble())} down") {
            val choices = BELOW_DEPTHS
            val format = NavFormat(units())
            panels.showChoicePanel("How far down", choices.map { format.depth(it.toDouble()) }, choices.indexOf(belowDepthM)) { i ->
                if (choices[i] == belowDepthM) return@showChoicePanel
                belowDepthM = choices[i]
                preferences.edit().putInt(KEY_BELOW_DEPTH, belowDepthM).apply()
                refreshDialog()
                refreshBelowDepth()
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

        body.addView(panels.sectionHeader("SAVED AREAS"))
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
                else -> "${s.satellites.size} ocean layers"
            }
            val detail = listOfNotNull(
                if (active && shown) "Showing" else null,
                forecastText,
                satText,
                "got ${format.clock(s.fetchedMillis)}",
                String.format(Locale.US, "%.0f KB", s.bytes / 1024.0)
            ).joinToString(" · ")
            panels.addRow(body, panels.row((if (active) "● " else "") + s.name, detail) { areaOptions(s) })
        }

        body.addView(panels.sectionHeader("DOWNLOADS"))
        panels.addRow(body, panels.row(if (downloading) "Downloading…" else "Save the area on screen", "As well as the spot: another fishing ground") {
            if (downloading) return@row
            downloadScreenArea()
        })
        panels.addRow(body, panels.row("Days of forecast to save", "${dayText(days)} (up to ${ConditionsClient.MAX_DAYS})") {
            val choices = listOf(1, 2, 3, 5, 7, 8)
            panels.showChoicePanel("Days of forecast", choices.map { dayText(it) }, choices.indexOf(days)) { i ->
                days = choices[i]
                preferences.edit().putInt(KEY_DAYS, days).apply()
                refreshDialog()
            }
        })
        panels.addRow(body, panels.row("Satellite and ocean layers", if (withSatellite) "Yes (usually 2 to 5 days old)" else "No") {
            withSatellite = !withSatellite
            preferences.edit().putBoolean(KEY_SATELLITE, withSatellite).apply()
            refreshDialog()
        })
        status?.let { body.addView(panels.caption(it, topPaddingDp = 2)) }
        body.addView(panels.caption(
            "Forecast model points are about 8 km apart, so it's less accurate close to shore and in channels. Not for navigation.",
            topPaddingDp = 8
        ))
    }

    private fun temperatureRangeLabel(): String {
        val range = temperatureRange ?: return "Automatic: stretched to the area, so small breaks show"
        val format = NavFormat(units())
        return "${format.temperature(range.first)} to ${format.temperature(range.second)}"
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
                    download(saved.south, saved.west, saved.north, saved.east, replacingId = saved.id)
                }
                3 -> rename(s)
                4 -> panels.confirm("Delete ${s.name}?", "Its saved sea conditions are removed from the phone.") {
                    if (s.id == SPOT_ID) {
                        spotArea = null
                        // Not straight back: it downloads again at the next update.
                        lastAutoAttempt = System.currentTimeMillis()
                    }
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
        download(south, west, north, east, replacingId = null, note = note)
    }

    /** Downloads an area; [replacingId] updates that saved area (otherwise one with the same bounds is replaced). */
    /**
     * After "How far down" changes: downloads only the temperature at the new depth for the area on the map (or
     * the spot's), so it shows straight away without downloading the whole forecast again.
     */
    private fun refreshBelowDepth() {
        val target = area ?: spotArea
        val depthText = NavFormat(units()).depth(belowDepthM.toDouble())
        when {
            target == null -> { callbacks.toast("Saved: the next download gets the temperature $depthText down"); return }
            downloading -> { callbacks.toast("Saved: the next download gets the temperature $depthText down"); return }
            !callbacks.isOnline() -> { callbacks.toast("No internet: the temperature $depthText down comes with the next update"); return }
        }
        val a = target ?: return
        val depth = belowDepthM
        downloading = true
        status = "Downloading the temperature $depthText down…"
        refreshDialog()
        downloadJob = scope.launch {
            val updated = withContext(downloads) {
                runCatching {
                    val grid = client.satellite(SatelliteKind.TEMP_BELOW, a.south, a.west, a.north, a.east, depth)
                    ConditionsArea(
                        id = a.id, name = a.name, south = a.south, west = a.west, north = a.north, east = a.east,
                        fetchedMillis = System.currentTimeMillis(), forecast = a.forecast,
                        satellites = a.satellites + (SatelliteKind.TEMP_BELOW to grid), belowDepthM = depth
                    ).also { store.save(it) }
                }
            }
            status = null
            downloading = false
            updated.onSuccess { fresh ->
                if (spotArea?.id == fresh.id) spotArea = fresh
                if (area?.id == fresh.id) {
                    area = fresh
                    lastPictureKey = null
                    scaleCache = null
                }
                if (shown) redraw() else updateBox()
                callbacks.toast("Temperature $depthText down updated for ${fresh.name}")
            }.onFailure {
                DiagnosticLog.logError(TAG, "temperature at depth download FAILED", it)
                callbacks.toast("Couldn't get the temperature $depthText down: ${message(it)}")
            }
            refreshDialog()
        }
        downloadJob?.invokeOnCompletion { downloading = false }
    }

    private fun download(
        south: Double, west: Double, north: Double, east: Double, replacingId: String?, note: String? = null,
        /** An automatic update: no messages unless it gets nothing. */
        quiet: Boolean = false
    ) {
        if (downloading) return
        val now = System.currentTimeMillis()
        if (now < nextDownloadAllowed) {
            if (!quiet) callbacks.toast("Just downloaded: try again in ${(nextDownloadAllowed - now + 999) / 1000} s")
            return
        }
        nextDownloadAllowed = now + MIN_DOWNLOAD_GAP_MS
        downloading = true
        updateBox()
        val dayCount = days
        val satellites = withSatellite
        val depth = belowDepthM
        status = "Downloading the forecast…"
        refreshDialog()
        downloadJob = scope.launch {
            val problems = mutableListOf<String>()
            val result = withContext(downloads) {
                // Named: that one (even if it isn't saved yet). Otherwise one already covering these bounds.
                val replacing = if (replacingId != null) store.load(replacingId)
                else store.summaries().firstOrNull { it.sameBoundsAs(south, west, north, east) }?.let { store.load(it.id) }
                val span = max(north - south, east - west)
                val grid = GeoGrid.covering(south, west, north, east, max(MIN_STEP_DEG, span / (MAX_POINTS_PER_SIDE - 1)), MAX_POINTS_PER_SIDE)
                val windGrid = GeoGrid.covering(south, west, north, east, max(MIN_STEP_DEG, span / (MAX_WIND_POINTS_PER_SIDE - 1)), MAX_WIND_POINTS_PER_SIDE)
                val forecast = runCatching {
                    client.forecast(grid, dayCount, windGrid) { p ->
                        scope.launch(Dispatchers.Main) {
                            status = "Downloading the forecast… ${(p * 100).roundToInt()}%"
                            refreshDialog()
                        }
                    }
                }.onFailure {
                    DiagnosticLog.logError(TAG, "forecast download FAILED", it)
                    problems += "Forecast: ${message(it)}"
                }.getOrNull()
                val sats = LinkedHashMap<SatelliteKind, SatelliteGrid>()
                if (satellites) {
                    for (kind in SatelliteKind.entries) {
                        scope.launch(Dispatchers.Main) {
                            status = "Downloading ${kind.label.lowercase(Locale.US)}…"
                            refreshDialog()
                        }
                        runCatching { client.satellite(kind, south, west, north, east, depth) }
                            .onSuccess { sats[kind] = it }
                            .onFailure {
                                DiagnosticLog.logError(TAG, "${kind.name} download FAILED", it)
                                // Salinity and the rest often have nothing near a coast: only said for the main ones.
                                if (kind.required) problems += "${kind.label}: ${message(it)}"
                            }
                    }
                }
                if (forecast == null && sats.isEmpty()) return@withContext null
                // Keep what an update couldn't get again rather than losing it, unless it was for another depth.
                val keptSats = replacing?.satellites?.filterKeys { it !in sats && !(it == SatelliteKind.TEMP_BELOW && replacing.belowDepthM != depth) }.orEmpty()
                val newArea = ConditionsArea(
                    id = replacing?.id ?: replacingId ?: UUID.randomUUID().toString(),
                    name = replacing?.name ?: if (replacingId == SPOT_ID) SPOT_NAME else defaultName(south, west, north, east),
                    south = south, west = west, north = north, east = east,
                    fetchedMillis = System.currentTimeMillis(),
                    forecast = forecast ?: replacing?.forecast,
                    satellites = sats + keptSats,
                    belowDepthM = depth
                )
                runCatching { store.save(newArea) }.onFailure { problems += "Saving: ${message(it)}" }.map { newArea }.getOrNull()
            }
            status = null
            downloading = false
            if (result == null) {
                if (!quiet) callbacks.toast("Couldn't get the sea conditions. " + problems.joinToString(" "))
                updateBox()
            } else {
                if (result.id == SPOT_ID) spotArea = result
                // An update of the spot only changes the map if the spot's area is the one on it.
                val showIt = !quiet || area == null || area?.id == result.id
                if (showIt) {
                    area = result
                    areaId = result.id
                    preferences.edit().putString(KEY_AREA, result.id).apply()
                    lastPictureKey = null
                    scaleCache = null
                }
                if (!quiet) {
                    live = true
                    if (!shown) setShown(true) else redraw()
                    val saved = if (problems.isEmpty()) "Sea conditions saved for ${result.name}." else "Saved ${result.name}, but: " + problems.joinToString(" ")
                    callbacks.toast(listOfNotNull(saved, note).joinToString(" "))
                } else if (shown) {
                    redraw()
                } else {
                    updateBox()
                }
                callbacks.creditsChanged()
                removeOldAreas()
            }
            refreshDialog()
        }
        // However it ends (cancelled, or an error), it isn't downloading any more.
        downloadJob?.invokeOnCompletion { downloading = false }
    }

    private fun message(error: Throwable): String = when (error) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "no internet connection"
        is java.net.SocketTimeoutException -> "the service didn't answer in time"
        else -> error.message ?: error.javaClass.simpleName
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
        private const val KEY_AREA = "conditions_area"
        private const val KEY_COLOUR = "conditions_colour"
        private const val KEY_OPACITY = "conditions_opacity"
        private const val KEY_DAYS = "conditions_days"
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
        private const val STARTUP_DOWNLOAD_DELAY_MS = 8_000L
        private const val ONLINE_CHECK_MS = 5_000L
        private const val LAST_KNOWN_MS = 30_000L
        private const val CLOSED_MARK = "▾"
        private val ROW_ORDER = listOf("Wind", "Current", "Waves", "Swell", "Sea", "Water")
        /** What "Show on the map" offers, in order (the forecast's own sea temperature is left out: the satellite's is better). */
        private val MAP_CHOICES = listOf(
            ColourChoice.WIND, ColourChoice.CURRENT, ColourChoice.WAVES, ColourChoice.SWELL,
            ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_BREAKS, ColourChoice.TEMP_BELOW,
            ColourChoice.CHLOROPHYLL, ColourChoice.SEA_LEVEL, ColourChoice.SALINITY
        )
        private const val OPEN_MARK = "▴"

        /** The area kept round the spot has this id, so each update replaces it. */
        const val SPOT_ID = "spot"
        private const val SPOT_NAME = "Round my spot"
        private const val SPOT_RADIUS_KM = 200.0
        /** Between automatic tries, so a failing service isn't asked again and again. */
        private const val AUTO_RETRY_MS = 15 * 60_000L
        private const val WARNING = 0xFFF5B942.toInt()

        private const val IMAGE_SOURCE = "conditions-image-src"
        private const val IMAGE_LAYER = "conditions-image"
        private const val ARROW_SOURCE = "conditions-arrows-src"
        private const val ARROW_LAYER = "conditions-arrows"
        private const val ARROW_IMAGE = "conditions-arrow"

        private const val KNOTS_PER_MS = 1.943844
        /** Largest area: about 500 km a side, so the forecast points stay close enough to be useful. */
        private const val MAX_SPAN_DEG = 4.5
        private const val MIN_SPAN_DEG = 0.3
        /** The current model is about 0.08° apart; closer points add nothing. */
        private const val MIN_STEP_DEG = 0.08
        /**
         * Sea points: 16 a side is 256, and the wind's 10 a side 100, so one
         * download is about 360 of Open-Meteo's 600 requests a minute, which
         * leaves room for the tides (the same service).
         */
        private const val MAX_POINTS_PER_SIDE = 16
        private const val MAX_WIND_POINTS_PER_SIDE = 10
        /** Between downloads, so two never land in the same minute of Open-Meteo's limit. */
        private const val MIN_DOWNLOAD_GAP_MS = 75_000L
        private const val MAX_ARROWS_PER_SIDE = 12
        private const val BOX_ROWS = 6
        private const val PLAY_STEP_MS = 650L
        private const val PLAY = "Play"
        private const val PAUSE = "Pause"
        private const val FORECAST_PICTURE_SIDE = 320
        private const val MAX_PICTURE_SIDE = 512
        /** The forecast picture is redrawn as the time moves on by this much. */
        private const val PICTURE_STEP_MS = 10 * 60_000L
        private const val TICK_MS = 5 * 60_000L
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

        /** One line of the values box: an arrow pointing [towardsDegrees] (none for null) and its text. */
        data class Value(val towardsDegrees: Double?, val text: String)

        /**
         * The values at a point and time, in the user's units. With satellite
         * temperature saved, its coastline says where the sea is, so the
         * forecast reaches the shore the way the colours do.
         */
        fun values(a: ConditionsArea, lat: Double, lng: Double, time: Long, format: NavFormat): List<Value> {
            val out = mutableListOf<Value>()
            val masked = a.satellites.containsKey(SatelliteKind.SEA_TEMP)
            val f = a.forecast?.takeIf { !masked || a.isSea(lat, lng) != false }
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
            val satTemp = a.satellites[SatelliteKind.SEA_TEMP]?.sample(lat, lng)
            val modelTemp = f?.sample(ForecastField.SEA_TEMP, lat, lng, time, masked)
            when {
                satTemp != null && satTemp.isFinite() -> water += "Surface ${format.temperature(satTemp.toDouble())}"
                modelTemp != null && modelTemp.isFinite() -> water += "Surface ${format.temperature(modelTemp.toDouble())}"
            }
            a.satellites[SatelliteKind.CHLOROPHYLL]?.sample(lat, lng)?.takeIf { it.isFinite() && a.isSea(lat, lng) != false }?.let {
                water += String.format(Locale.US, if (it < 1) "chlorophyll %.2f mg/m³" else "chlorophyll %.1f mg/m³", it)
            }
            if (water.isNotEmpty()) out += Value(null, water.joinToString(" · "))

            // The coarser layers: one line, as they change slowly.
            if (a.isSea(lat, lng) != false) {
                fun coarse(kind: SatelliteKind) = a.satellites[kind]?.let { if (masked) it.sampleToShore(lat, lng) else it.sample(lat, lng) }?.takeIf { it.isFinite() }
                coarse(SatelliteKind.TEMP_BELOW)?.let {
                    out += Value(null, "${format.depth(a.belowDepthM.toDouble())} down ${format.temperature(it.toDouble())}")
                }
                val surface = listOfNotNull(
                    coarse(SatelliteKind.SALINITY)?.let { String.format(Locale.US, "Salinity %.1f", it) },
                    coarse(SatelliteKind.SEA_LEVEL)?.let { "sea level ${format.seaLevel(it.toDouble())}" }
                )
                if (surface.isNotEmpty()) out += Value(null, surface.joinToString(" · "))
            }
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
