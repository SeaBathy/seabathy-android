package au.opendepth.app.tides

import android.content.SharedPreferences
import android.graphics.Typeface
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tides: where they are for (follow the boat, or a saved place picked on
 * the map or found by name), downloading and caching them, the chip on the
 * chart, and the tide screens. Depth readouts ask [tideNow].
 */
class TideFeature(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    private val scope: CoroutineScope,
    preferences: SharedPreferences,
    gson: Gson,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        /** For picking a spot on the map. */
        fun mapCentre(): Pair<Double, Double>?
        /** The newest position the phone remembers, before this app's GPS has a fix. */
        fun lastKnownPosition(): Pair<Double, Double>?
        /** Turns GPS on (asking for permission if needed). */
        fun startGps()
        fun isNavigating(): Boolean
        /** The units chosen in Settings. */
        fun units(): NavUnits
        /** Tide heights changed: depth readouts that include the tide should update. */
        fun tidesChanged()
        fun closePanels()
        fun toast(message: String)
    }

    private val repository = TideSettingsRepository(preferences)
    var settings: TideSettings = repository.load()
        private set
    private val places = TidePlaceStore(preferences, gson)
    private val store = TideStore(activity)
    private val dialog = TideDialog(activity, panels) { callbacks.units() }
    private val palette = AppTheme.palette

    private var lastFix: Pair<Double, Double>? = null
    private var refreshJob: Job? = null
    private var lowestJob: Job? = null
    private var lowestFailedAt = 0L
    private var saveJob: Job? = null
    /** True while long-range tides download; the job itself is still "active" when it redraws the screen. */
    private var saving = false
    private var lastError: String? = null
    private var lastFailedAt = 0L
    /** "Follow me" was chosen before a GPS fix arrived: fetch on the first one. */
    private var waitingForFix = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshChip()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private val chip = TextView(activity).apply {
        textSize = 12f
        setTextColor(palette.primaryText)
        background = activity.roundedBackground(0xE6111827.toInt(), palette.border, 16)
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
        elevation = activity.dp(4).toFloat()
        maxLines = 1
        visibility = View.GONE
        contentDescription = "Tide — open the tide screen"
        setOnClickListener { show() }
    }

    /** The tide chip under the top bar, for laying out what sits below it. */
    val chipView: View get() = chip

    /** Shown while picking a tide spot on the map. */
    private val pickBanner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.roundedBackground(0xF0111827.toInt(), palette.accent, 14, strokeWidthDp = 2)
        setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
        elevation = activity.dp(6).toFloat()
        visibility = View.GONE
        isClickable = true
        addView(TextView(activity).apply {
            text = "Pick a tide spot"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.primaryText)
        })
        addView(TextView(activity).apply {
            text = "Put the centre dot on the water, then save."
            textSize = 13f
            setTextColor(palette.secondaryText)
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(activity).apply {
            text = "Cancel"
            isAllCaps = false
            applySecondaryStyle(palette)
            setOnClickListener { endMapPick() }
        }, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
        row.addView(Button(activity).apply {
            text = "Save this spot"
            isAllCaps = false
            applyPrimaryStyle(palette)
            setOnClickListener { saveMapPick() }
        }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { marginStart = activity.dp(8) })
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(8)
        })
    }

    private val key: String get() = settings.placeId ?: TideStore.FOLLOW_KEY
    private val place: TidePlace? get() = places.byId(settings.placeId)
    private val forecast: TideForecast? get() = store.forecast(key)

    // Setup and lifecycle -----------------------------------------------------------

    fun bind(root: ViewGroup, topBar: View) {
        // A saved place that was deleted elsewhere falls back to following.
        if (settings.placeId != null && place == null) update(settings.copy(placeId = null))
        root.addView(chip, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = activity.dp(10)
        })
        root.addView(pickBanner, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            marginStart = activity.dp(8)
            marginEnd = activity.dp(8)
        })
        topBar.addOnLayoutChangeListener { bar, _, _, _, _, _, _, _, _ ->
            val below = bar.bottom + activity.dp(6)
            for (view in listOf(chip, pickBanner)) {
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                if (params.topMargin != below) {
                    params.topMargin = below
                    view.requestLayout()
                }
            }
        }
        scope.launch {
            store.load(key)
            refreshChip()
            refreshIfNeeded()
        }
    }

    fun onResume() {
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        refreshIfNeeded()
    }

    fun onPause() = ticker.removeCallbacks(tick)

    fun onDestroy() = ticker.removeCallbacks(tick)

    fun onLocation(location: Location) {
        lastFix = location.latitude to location.longitude
        // The first fix replaces tides fetched for the phone's last known position
        // if the boat is somewhere else now (needsRefresh checks the distance).
        waitingForFix = false
        refreshIfNeeded()
    }

    /** Startup: fetches tides if the saved ones don't cover where they're wanted. */
    fun onMapReady() = refreshIfNeeded()

    /** Tide above the survey datum now, when tides are applied to depths. */
    fun tideNow(): Double? =
        if (!settings.correctDepths) null else settings.heightAboveDatum(forecast, System.currentTimeMillis())

    /**
     * The tide to add at [lat]/[lng]: as [tideNow], but only near where the
     * tides are for. A saved place hundreds of km away has another place's
     * tides, and adding them would make the depth wrong. A typed-in tide
     * (manual) has no place, so it is taken to be for here.
     */
    fun tideNear(lat: Double, lng: Double): Double? = tideNow()?.takeIf { coversPoint(lat, lng) }

    /** True when [lat]/[lng] is close enough to the tide spot for its tides to hold. */
    fun coversPoint(lat: Double, lng: Double): Boolean {
        if (settings.mode != TideMode.MODEL) return true
        val f = forecast ?: return false
        return au.opendepth.app.nav.NavGeo.distanceM(lat, lng, f.requestLat, f.requestLng) <= TIDE_REACH_M
    }

    /** The tide now as a tide table gives it (above chart datum), for showing. */
    fun tideShown(): Double? = settings.shownHeight(forecast, System.currentTimeMillis())

    // Screens ---------------------------------------------------------------------------

    /** The tide screen; the first time, it asks where the tides should be for. */
    fun show() {
        if (!settings.locationChosen) {
            showLocationPicker()
            return
        }
        showMain(resetDay = true)
    }

    private fun showMain(resetDay: Boolean = false) {
        dialog.showMain(
            TideView(
                settings = settings,
                placeName = place?.name,
                forecast = forecast,
                loading = refreshJob?.isActive == true,
                saving = saving,
                error = lastError,
                savedUntilMillis = store.savedUntil(key),
                noFix = place == null && lastFix == null,
                noPosition = place == null && position() == null
            ),
            object : TideDialog.MainActions {
                override fun changeLocation() = showLocationPicker()
                override fun refresh() = this@TideFeature.refresh(manual = true)
                override fun saveOffline(period: SavePeriod) = this@TideFeature.saveOffline(period)
                override fun deleteSaved() {
                    store.deleteSaved(key)
                    lastError = null
                    refreshChip()
                    callbacks.tidesChanged()
                    showMain()
                }

                override fun saveHereAsPlace() {
                    val (lat, lng) = position() ?: return callbacks.toast("No position yet")
                    nameAndSave(lat, lng)
                }
                override fun settings() {
                    dialog.closeMain()
                    dialog.showSettings(settings, forecast?.lowestTideM?.let { -it }) { updated ->
                        val modeChanged = updated.mode != settings.mode
                        update(updated)
                        callbacks.tidesChanged()
                        if (modeChanged && updated.mode == TideMode.MODEL) refreshIfNeeded()
                        showMain()
                    }
                }
            },
            resetDay
        )
    }

    private fun showLocationPicker() {
        dialog.closeMain()
        dialog.showLocationPicker(places.all, settings.placeId, { store.savedUntil(it.id) }, object : TideDialog.LocationActions {
            override fun followMe() {
                select(null)
                if (lastFix == null) {
                    waitingForFix = true
                    callbacks.startGps()
                    callbacks.toast("Finding your position…")
                    // Until GPS answers, the phone's last known position is used.
                }
            }

            override fun choose(place: TidePlace) = select(place.id)
            override fun pickOnMap() = startMapPick()
            override fun search() = showSearch()
            override fun delete(place: TidePlace) {
                places.delete(place.id)
                store.forget(place.id)
                if (settings.placeId == place.id) select(null) else showLocationPicker()
            }
        })
    }

    private fun showSearch() {
        dialog.showSearch(
            search = { query, done ->
                scope.launch {
                    runCatching { store.search(query) }
                        .onSuccess { done(it, null) }
                        .onFailure { done(null, it.message ?: "Search failed") }
                }
            },
            onChosen = { result ->
                val saved = places.add(result.name, result.lat, result.lng)
                select(saved.id)
            }
        )
    }

    private fun startMapPick() {
        callbacks.closePanels()
        pickBanner.visibility = View.VISIBLE
        chip.visibility = View.GONE
    }

    private fun endMapPick() {
        pickBanner.visibility = View.GONE
        refreshChip()
    }

    private fun saveMapPick() {
        val centre = callbacks.mapCentre() ?: run {
            callbacks.toast("The map isn't ready yet")
            return
        }
        endMapPick()
        nameAndSave(centre.first, centre.second)
    }

    /**
     * Suggests a name from the nearest town (OpenStreetMap), lets the user
     * change it, saves and selects the place, then runs [then].
     */
    private fun nameAndSave(lat: Double, lng: Double, then: (() -> Unit)? = null) {
        scope.launch {
            val nearby = runCatching { store.nearbyName(lat, lng) }.getOrNull()
            val suggested = nearby ?: String.format(Locale.US, "Spot %.3f, %.3f", lat, lng)
            dialog.promptName(suggested, fromOpenStreetMap = nearby != null) { name ->
                val saved = places.add(name, lat, lng)
                select(saved.id)
                then?.invoke()
            }
        }
    }

    /**
     * Downloads a year of tides for the place and keeps [period] of
     * predictions offline. Following the boat, the spot is saved as a place first.
     */
    private fun saveOffline(period: SavePeriod) {
        val target = place ?: run {
            val (lat, lng) = position() ?: return callbacks.toast("No position yet — turn on GPS")
            nameAndSave(lat, lng) { saveOffline(period) }
            return
        }
        if (saving) return
        saving = true
        saveJob = scope.launch {
            showMain()
            runCatching { store.saveOffline(target.id, target.lat, target.lng, period) }
                .onSuccess {
                    lastError = null
                    val until = it.savedUntilMillis?.let { ms -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms)) }
                    callbacks.toast("Tides saved offline until $until")
                }
                .onFailure { lastError = it.message ?: "Couldn't save tides" }
            saving = false
            refreshChip()
            callbacks.tidesChanged()
            if (key == target.id) showMain()
        }
    }

    /** Switches tides to [placeId] (null = follow me) and shows them. */
    private fun select(placeId: String?) {
        // Choosing a place means wanting its forecast, whatever the source was before.
        update(settings.copy(placeId = placeId, locationChosen = true, mode = TideMode.MODEL))
        lastError = null
        scope.launch {
            store.load(key)
            callbacks.tidesChanged()
            refreshChip()
            showMain()
            refreshIfNeeded(force = forecast == null)
        }
    }

    // Downloading -------------------------------------------------------------------------

    /**
     * Where tides are wanted now: the saved place, or the boat. Before a GPS
     * fix, the phone's last known position, then where the saved tides are for.
     */
    private fun position(): Pair<Double, Double>? =
        place?.let { it.lat to it.lng } ?: lastFix ?: callbacks.lastKnownPosition()
            ?: forecast?.let { it.requestLat to it.requestLng }

    private fun refreshIfNeeded(force: Boolean = false) {
        if (settings.mode != TideMode.MODEL) return
        val (lat, lng) = position() ?: return
        // Don't retry a failing download on every GPS fix.
        if (!force && System.currentTimeMillis() - lastFailedAt < RETRY_MS) return
        if (force || store.needsRefresh(key, lat, lng)) refresh(manual = false) else ensureLowest()
    }

    /**
     * Works out chart datum (the lowest tide) for where the tides are, once
     * per place, so heights read like a tide table instead of going below zero.
     */
    private fun ensureLowest() {
        if (settings.mode != TideMode.MODEL || lowestJob?.isActive == true) return
        val (lat, lng) = position() ?: return
        if (!store.needsLowest(key, lat, lng)) return
        if (System.currentTimeMillis() - lowestFailedAt < RETRY_MS) return
        val forKey = key
        lowestJob = scope.launch {
            runCatching { store.findLowest(forKey, lat, lng) }
                .onSuccess {
                    if (forKey != key) return@onSuccess
                    refreshChip()
                    callbacks.tidesChanged()
                    if (dialog.isShowing) showMain()
                }
                .onFailure { error ->
                    lowestFailedAt = System.currentTimeMillis()
                    DiagnosticLog.log(TAG, "chart datum failed: ${error.javaClass.simpleName}: ${error.message}")
                }
        }
    }

    private fun refresh(manual: Boolean) {
        if (refreshJob?.isActive == true) return
        val (lat, lng) = position() ?: run {
            if (manual) callbacks.toast("No position yet — turn on GPS")
            return
        }
        val forKey = key
        refreshJob = scope.launch {
            if (dialog.isShowing && forecast == null) showMain()
            runCatching { store.refresh(forKey, lat, lng) }
                .onSuccess {
                    lastError = null
                    if (manual) callbacks.toast("Tides updated")
                }
                .onFailure { error ->
                    lastFailedAt = System.currentTimeMillis()
                    lastError = error.message ?: "Couldn't download tides"
                    if (manual) callbacks.toast(lastError.orEmpty())
                    DiagnosticLog.log(TAG, "tide refresh failed: ${error.javaClass.simpleName}: ${error.message}")
                }
            if (forKey != key) return@launch
            refreshChip()
            callbacks.tidesChanged()
            if (dialog.isShowing || manual) showMain()
            ensureLowest()
        }
    }

    // Chip ------------------------------------------------------------------------------------

    fun refreshChip() {
        val s = settings
        val now = System.currentTimeMillis()
        if (!s.showChip || s.mode == TideMode.OFF || callbacks.isNavigating() || pickBanner.visibility == View.VISIBLE) {
            chip.visibility = View.GONE
            return
        }
        val where = place?.name?.let { if (it.length > 14) it.take(13) + "…" else it }?.plus(" ").orEmpty()
        val format = NavFormat(callbacks.units())
        chip.text = when (s.mode) {
            TideMode.MANUAL -> "Tide ${format.tideHeight(s.manualHeightM)} (manual)"
            else -> {
                val curve = forecast?.curve
                val height = s.shownHeight(forecast, now)
                if (curve == null || height == null) {
                    if (!s.locationChosen) "Tides: tap to set up" else "${where}Tide —"
                } else {
                    val rate = curve.rateMPerHour(now) ?: 0.0
                    val arrow = if (rate > 0.02) "▲" else if (rate < -0.02) "▼" else "■"
                    val next = curve.nextExtremes(now, 1).firstOrNull()?.let {
                        " • ${if (it.isHigh) "H" else "L"} ${SimpleDateFormat("HH:mm", Locale.US).format(Date(it.timeMillis))}"
                    }.orEmpty()
                    "${where.ifEmpty { "Tide " }}$arrow ${format.tideHeight(height)}$next"
                }
            }
        }
        chip.visibility = View.VISIBLE
    }

    private fun update(updated: TideSettings) {
        settings = updated
        repository.save(updated)
    }

    private companion object {
        /** How far a place's tides are used: beyond this the tide is someone else's, metres. */
        const val TIDE_REACH_M = 50_000.0

        const val TAG = "TideFeature"
        const val TICK_MS = 60_000L
        const val RETRY_MS = 5 * 60_000L
    }
}
