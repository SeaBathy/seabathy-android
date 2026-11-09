package au.opendepth.app.tides

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import au.opendepth.app.R
import au.opendepth.app.nav.HeightUnit
import au.opendepth.app.nav.NavFormat
import au.opendepth.app.nav.NavGeo
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.UiRadius
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the tide screen shows. */
data class TideView(
    val settings: TideSettings,
    /** The saved place's name, or null when following the boat. */
    val placeName: String?,
    val forecast: TideForecast?,
    val loading: Boolean,
    /** Long-range tides are being downloaded and analysed. */
    val saving: Boolean,
    val error: String?,
    /** When the place's saved tides end, even if that has passed; null when none are saved. */
    val savedUntilMillis: Long? = null,
    /** Following the boat with no GPS fix yet: the last known position is used. */
    val noFix: Boolean = false,
    /** Following the boat, and no position at all yet. */
    val noPosition: Boolean = false
)

/**
 * The tide panels: the tide screen (now, a chart you can drag along, highs
 * and lows, offline storage), the "where" picker, place search and settings.
 */
class TideDialog(
    private val context: Context,
    private val panels: PanelDialogs,
    private val units: () -> NavUnits
) {
    private val palette = AppTheme.palette
    private var main: Dialog? = null

    /** The day the chart shows, kept while the screen is redrawn; 0 = today. */
    private var dayOffset = 0

    interface MainActions {
        fun changeLocation()
        fun refresh()
        fun settings()
        fun saveOffline(period: SavePeriod)
        fun deleteSaved()
        /** Following the boat: save where it is as a place. */
        fun saveHereAsPlace()
    }

    val isShowing: Boolean get() = main?.isShowing == true

    fun closeMain() {
        main?.dismiss()
        main = null
    }

    fun showMain(view: TideView, actions: MainActions, resetDay: Boolean = false) {
        val format = NavFormat(units())
        if (resetDay) dayOffset = 0
        closeMain()
        val body = panels.body()
        val s = view.settings
        val now = System.currentTimeMillis()
        val forecast = view.forecast
        val offset = s.shownOffset(forecast)
        val curve = forecast?.curve?.takeIf { it.covers(now) }

        // Where ----------------------------------------------------------------
        val where = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
            setPadding(context.dp(14), context.dp(10), context.dp(10), context.dp(10))
        }
        val whereText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        whereText.addView(text(view.placeName ?: "Following you", 16f, bold = true).also {
            icon(it, if (view.placeName == null) R.drawable.ic_follow_me else R.drawable.ic_place_pin)
        })
        whereText.addView(text(whereLine(view, now), 12f, muted = true))
        where.addView(whereText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        where.addView(button("Change") { actions.changeLocation() })
        panels.addRow(body, where)

        when {
            s.mode == TideMode.OFF -> body.addView(panels.caption("Tides are off. Turn them on in Settings."))
            s.mode == TideMode.MANUAL -> nowCard(body, format.tideHeight(s.manualHeightM), "Manual height", null)
            (view.loading || view.saving) && curve == null ->
                body.addView(text("Getting tides…", 15f, bold = true).apply { setPadding(0, context.dp(16), 0, context.dp(16)) })
            curve == null -> {
                body.addView(text(view.error ?: "No tides downloaded for here yet.", 14f).apply { setPadding(0, context.dp(10), 0, context.dp(8)) })
                panels.addRow(body, button("Get tides", primary = true) { actions.refresh() })
            }
            else -> {
                val h = curve.heightAt(now)!! + offset
                val rate = curve.rateMPerHour(now) ?: 0.0
                val trend = when {
                    rate > 0.02 -> "▲ Rising ${format.tideRate(rate)}"
                    rate < -0.02 -> "▼ Falling ${format.tideRate(-rate)}"
                    else -> "■ Slack"
                }
                val next = curve.nextExtremes(now, 1).firstOrNull()?.let {
                    "${if (it.isHigh) "High" else "Low"} ${clock(it.timeMillis, now)} · " +
                        "${format.tideHeight(it.heightM + offset)} · in ${inHours(it.timeMillis - now)}"
                }
                nowCard(body, format.tideHeight(h), trend, next)
                // With tides saved offline, a failed forecast update (no signal at sea) doesn't matter.
                val savedAhead = (view.savedUntilMillis ?: 0L) > now
                if (view.error != null && !savedAhead) body.addView(panels.caption("Couldn't update: ${view.error}"))
                chartSection(body, forecast, offset, now)
            }
        }

        if (s.mode == TideMode.MODEL) offlineSection(body, view, actions)

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (s.mode == TideMode.MODEL) {
            buttons.addView(button("Refresh") { actions.refresh() }, LinearLayout.LayoutParams(0, context.dp(46), 1f))
        }
        buttons.addView(button("Settings") { actions.settings() }, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply {
            if (s.mode == TideMode.MODEL) marginStart = context.dp(8)
        })
        body.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(14)
        })
        body.addView(
            panels.caption(
                (if (s.showsChartDatum(forecast)) "Heights above the lowest tide (chart datum, LAT), like a tide table. "
                else "Heights from mean sea level — lows read below zero until the lowest tide here is worked out (needs a connection, once). ") +
                    "Modelled tides, worked out from Open-Meteo data (CC BY 4.0, E.U. Copernicus Marine Service). " +
                    "Not an official tide table. Not for navigation."
            )
        )
        // The old panel's dismiss runs after this one is shown: only clear [main] if it is still this panel.
        var shown: Dialog? = null
        shown = panels.showPanel("Tides", body, onDismiss = { if (main === shown) main = null })
        main = shown
    }

    /** "Tide point 10.8 km NE of Cairns · updated 21:41" */
    private fun whereLine(view: TideView, now: Long): String {
        val s = view.settings
        if (s.mode == TideMode.MANUAL) return "Manual tide from your tide table"
        if (s.mode == TideMode.OFF) return "Tides off"
        val f = view.forecast ?: return when {
            view.placeName != null -> "Saved place"
            view.noPosition -> "Waiting for the GPS"
            else -> "Tides where you are, as you travel"
        }
        val here = if (view.noFix) "your last position" else "you"
        val of = view.placeName ?: here
        val km = f.modelDistanceM / 1000
        val point = if (km < 1) {
            "Tide point at ${view.placeName ?: if (view.noFix) "your last position" else "your position"}"
        } else {
            val dir = compass(NavGeo.bearing(f.requestLat, f.requestLng, f.modelLat, f.modelLng))
            String.format(Locale.US, "Tide point %.1f km %s of %s", km, dir, of)
        }
        return "$point · updated ${clock(f.fetchedMillis, now)}"
    }

    // Chart -----------------------------------------------------------------------

    private fun chartSection(body: LinearLayout, forecast: TideForecast, offset: Double, now: Long) {
        val curve = forecast.curve
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(section, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(10)
        })
        val lastDay = (((curve.endMillis ?: now) - startOfDay(now, 0)) / DAY_MS).toInt() - 1
        val firstDay = -1
        dayOffset = dayOffset.coerceIn(firstDay, maxOf(0, lastDay))

        fun render() {
            section.removeAllViews()
            val nav = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val prev = iconButton(R.drawable.ic_chevron_left, "Previous day") { dayOffset--; render() }.apply {
                isEnabled = dayOffset > firstDay
                alpha = if (isEnabled) 1f else 0.3f
            }
            val next = iconButton(R.drawable.ic_chevron_right, "Next day") { dayOffset++; render() }.apply {
                isEnabled = dayOffset < lastDay
                alpha = if (isEnabled) 1f else 0.3f
            }
            val label = text(dayLabel(dayOffset, now), 15f, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { pickDate(now, firstDay, lastDay) { picked -> dayOffset = picked; render() } }
                contentDescription = "Choose a date"
            }
            nav.addView(prev, LinearLayout.LayoutParams(context.dp(48), context.dp(44)))
            nav.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (dayOffset != 0) {
                nav.addView(button("Today") { dayOffset = 0; render() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(40)).apply {
                    marginEnd = context.dp(6)
                })
            }
            nav.addView(next, LinearLayout.LayoutParams(context.dp(48), context.dp(44)))
            section.addView(nav)

            val start = if (dayOffset == 0) now - 3 * HOUR_MS else startOfDay(now, dayOffset)
            section.addView(TideGraphView(context).apply {
                this.curve = curve
                heightFormat = NavFormat(units())
                offsetM = offset
                nowMillis = now
                windowStartMillis = start
                nights = nights(forecast, start - DAY_MS, start + 3 * DAY_MS)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(4)
            })
            section.addView(panels.caption(sunAndMoon(forecast, startOfDay(now, dayOffset) + DAY_MS / 2), topPaddingDp = 6))
            section.addView(panels.caption("Drag along the chart to see the tide at any time.", topPaddingDp = 2))

            section.addView(panels.sectionHeader("HIGHS AND LOWS"))
            val dayFormat = SimpleDateFormat("EEEE d MMM", Locale.US)
            val from = startOfDay(now, dayOffset)
            curve.extremes(from, from + 3 * DAY_MS)
                .groupBy { dayFormat.format(Date(it.timeMillis)) }
                .forEach { (day, items) -> dayRow(section, day, items, offset, now) }
        }
        render()
    }

    /** "Sunrise 05:41 · Sunset 18:22 · Full moon, spring tides" for the day around [noon]. */
    private fun sunAndMoon(forecast: TideForecast, noon: Long): String {
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val sun = SunMoon.sunTimes(noon, forecast.requestLat, forecast.requestLng)
            ?.let { (rise, set) -> "Sunrise ${hm.format(Date(rise))} · Sunset ${hm.format(Date(set))} · " }
            .orEmpty()
        val moon = SunMoon.phaseName(noon)
        return sun + (SunMoon.springOrNeap(noon)?.let { "$moon, $it" } ?: moon)
    }

    /** Sunset-to-sunrise spans between [from] and [to], for shading the chart. */
    private fun nights(forecast: TideForecast, from: Long, to: Long): List<Pair<Long, Long>> {
        val days = generateSequence(startOfDay(from, 0) + DAY_MS / 2) { it + DAY_MS }.takeWhile { it < to + DAY_MS }
        val suns = days.mapNotNull { SunMoon.sunTimes(it, forecast.requestLat, forecast.requestLng) }.toList()
        return suns.zipWithNext { today, tomorrow -> today.second to tomorrow.first }
    }

    private fun pickDate(now: Long, firstDay: Int, lastDay: Int, onDay: (Int) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(now, dayOffset) }
        DatePickerDialog(
            context,
            android.R.style.Theme_DeviceDefault_Dialog_Alert,
            { _, y, m, d ->
                val chosen = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
                onDay(((chosen - startOfDay(now, 0)) / DAY_MS).toInt().coerceIn(firstDay, lastDay))
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = startOfDay(now, firstDay)
            datePicker.maxDate = startOfDay(now, lastDay) + DAY_MS - 1
        }.show()
    }

    private fun dayLabel(offset: Int, now: Long): String = when (offset) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(Date(startOfDay(now, offset)))
    }

    // Offline ---------------------------------------------------------------------

    private fun offlineSection(body: LinearLayout, view: TideView, actions: MainActions) {
        body.addView(panels.sectionHeader("OFFLINE"))
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("d MMM yyyy", Locale.US)
        val f = view.forecast
        val until = view.savedUntilMillis
        val bytes = f?.storedBytes ?: 0L
        val forecastEnd = f?.curve?.endMillis?.takeIf { until == null || until < now }
        val line = when {
            view.saving -> "Saving tides… this takes a few seconds."
            view.placeName == null ->
                "Following you uses the live forecast${forecastEnd?.let { " (to ${dayMonth(it)})" }.orEmpty()}. " +
                    "Save offline to keep tides for this spot."
            until != null && until >= now -> "Saved offline until ${date.format(Date(until))} · ${size(bytes)}"
            until != null -> "Saved tides ran out on ${date.format(Date(until))}. Renew to keep them offline."
            forecastEnd != null -> "Live forecast to ${dayMonth(forecastEnd)} · ${size(bytes)}"
            else -> "Nothing saved yet."
        }
        body.addView(text(line, 14f).apply { setPadding(context.dp(2), 0, 0, context.dp(6)) })
        if (view.saving) return
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val label = when {
            until == null -> "Save offline"
            until < now -> "Renew"
            else -> "Save longer"
        }
        row.addView(button(label, primary = until == null || until < now) {
            // Each choice shows the day it runs to, and what's saved now, so a
            // shorter pick can't quietly cut saved tides back.
            val periods = SavePeriod.entries
            val today = java.time.ZonedDateTime.now()
            val ends = periods.map { it.endFrom(today).toInstant().toEpochMilli() }
            val current = until?.takeIf { it >= now }
            val title = if (current == null) "Keep tides offline for" else "Keep tides offline for (saved to ${date.format(Date(current))} now)"
            panels.showChoicePanel(title, periods.mapIndexed { i, p -> "${p.label} · to ${date.format(Date(ends[i]))}" }, -1) { i ->
                if (current != null && ends[i] < current) {
                    panels.confirm(
                        "Keep fewer tides?",
                        "Tides here are saved to ${date.format(Date(current))}. This keeps them only to ${date.format(Date(ends[i]))}.",
                        confirmLabel = "Keep fewer"
                    ) { actions.saveOffline(periods[i]) }
                } else {
                    actions.saveOffline(periods[i])
                }
            }
        }.also { icon(it, R.drawable.ic_save_offline) }, LinearLayout.LayoutParams(0, context.dp(46), 1f))
        if (bytes > 0 && view.placeName != null) {
            row.addView(button("Delete") {
                panels.confirm("Delete saved tides?", "They can be downloaded again any time.", confirmLabel = "Delete") {
                    actions.deleteSaved()
                }
            }.also { icon(it, R.drawable.ic_delete) }, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply {
                marginStart = context.dp(8)
            })
        }
        body.addView(row)
    }

    private fun dayMonth(millis: Long) = SimpleDateFormat("EEE d MMM", Locale.US).format(Date(millis))

    // Where picker ------------------------------------------------------------------

    interface LocationActions {
        fun followMe()
        fun choose(place: TidePlace)
        fun pickOnMap()
        fun search()
        fun delete(place: TidePlace)
    }

    /** Where tides come from: follow the boat, a saved place, or a new one. */
    fun showLocationPicker(places: List<TidePlace>, selectedId: String?, savedUntil: (TidePlace) -> Long?, actions: LocationActions) {
        val body = panels.body()
        lateinit var dialog: Dialog
        body.addView(panels.caption("Tides use the nearest ocean point to where you choose.", topPaddingDp = 0))

        panels.addRow(body, option(R.drawable.ic_follow_me, "Follow me", "Where the boat is, as you travel", selectedId == null) {
            dialog.dismiss(); actions.followMe()
        })
        panels.addRow(body, option(R.drawable.ic_map_pick, "Pick on the map", "A harbour, reef or ramp", false) {
            dialog.dismiss(); actions.pickOnMap()
        })
        panels.addRow(body, option(R.drawable.ic_search, "Search a place", "A town or harbour by name", false) {
            dialog.dismiss(); actions.search()
        })

        if (places.isNotEmpty()) {
            body.addView(panels.sectionHeader("SAVED PLACES"))
            val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)
            places.forEach { place ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val now = System.currentTimeMillis()
                val detail = when (val until = savedUntil(place)) {
                    null -> "Live forecast only"
                    in now..Long.MAX_VALUE -> "Offline until ${dateFormat.format(Date(until))}"
                    else -> "Offline tides ran out ${dateFormat.format(Date(until))}"
                }
                row.addView(option(R.drawable.ic_place_pin, place.name, detail, place.id == selectedId) {
                    dialog.dismiss(); actions.choose(place)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(iconButton(R.drawable.ic_delete, "Remove ${place.name}") {
                    panels.confirm("Remove ${place.name}?", "Its saved tides are deleted too.", confirmLabel = "Remove") {
                        dialog.dismiss()
                        actions.delete(place)
                    }
                }, LinearLayout.LayoutParams(context.dp(48), context.dp(56)).apply { marginStart = context.dp(6) })
                panels.addRow(body, row)
            }
        }
        dialog = panels.showPanel("Tides for", body)
    }

    /** Place search: type a name, pick from the results. */
    fun showSearch(search: (String, (List<PlaceResult>?, String?) -> Unit) -> Unit, onChosen: (PlaceResult) -> Unit) {
        val body = panels.body()
        lateinit var dialog: Dialog
        val input = panels.input {
            hint = "Town, harbour or bay"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        panels.addRow(body, input)
        val status = panels.caption("", topPaddingDp = 0)
        body.addView(status)
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)

        fun run() {
            val query = input.text.toString().trim()
            if (query.length < 2) {
                status.text = "Type at least two letters."
                return
            }
            status.text = "Searching…"
            results.removeAllViews()
            search(query) { found, error ->
                results.removeAllViews()
                when {
                    error != null -> status.text = error
                    found.isNullOrEmpty() -> status.text = "Nothing found for \"$query\"."
                    else -> {
                        status.text = "Tap one to save it."
                        found.forEach { r ->
                            panels.addRow(results, option(R.drawable.ic_place_pin, r.name, r.detail.ifBlank { String.format(Locale.US, "%.3f, %.3f", r.lat, r.lng) }, false) {
                                dialog.dismiss(); onChosen(r)
                            })
                        }
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { run(); true } else false
        }
        panels.addRow(body, button("Search", primary = true) { run() })
        dialog = panels.showPanel("Search a place", body)
        input.requestFocus()
    }

    /** Asks for a name for a new place. */
    fun promptName(suggested: String, fromOpenStreetMap: Boolean = false, onName: (String) -> Unit) {
        val body = panels.body()
        lateinit var dialog: Dialog
        val input = panels.input {
            setText(suggested)
            setSingleLine(true)
            selectAll()
        }
        panels.addRow(body, input)
        if (fromOpenStreetMap) body.addView(panels.caption("Suggested name from © OpenStreetMap contributors (ODbL).", topPaddingDp = 2))
        dialog = panels.showPanel("Name this spot", body, actionLabel = "Save", onAction = {
            dialog.dismiss()
            onName(input.text.toString().trim().ifBlank { suggested })
        })
        input.requestFocus()
    }

    /** Tide settings; [autoChartDatumM] is chart datum below mean sea level as worked out here, if it is. */
    fun showSettings(settings: TideSettings, autoChartDatumM: Double?, onSave: (TideSettings) -> Unit) {
        val body = panels.body()
        var mode = settings.mode
        var datum = settings.datum
        val modes = listOf(TideMode.MODEL, TideMode.MANUAL, TideMode.OFF)
        lateinit var modeRow: TextView
        modeRow = panels.row("Tides from", modeLabel(mode)) {
            panels.showChoicePanel("Tides from", modes.map(::modeLabel), modes.indexOf(mode)) { i ->
                mode = modes[i]
                modeRow.text = "Tides from\n${modeLabel(mode)}"
            }
        }
        panels.addRow(body, modeRow)
        lateinit var datumRow: TextView
        datumRow = panels.row("Survey depths are from", datumLabel(datum)) {
            panels.showChoicePanel(
                "Survey depths are from",
                listOf(datumLabel(TideDatum.MEAN_SEA_LEVEL), datumLabel(TideDatum.CHART_DATUM)),
                datum.ordinal
            ) { i ->
                datum = TideDatum.entries[i]
                datumRow.text = "Survey depths are from\n${datumLabel(datum)}"
            }
        }
        panels.addRow(body, datumRow)
        val height = units().tide
        body.addView(panels.caption("Lowest tide (chart datum) below mean sea level, ${height.label}", topPaddingDp = 2))
        val datumOffset = numberInput(height.of(settings.chartDatumBelowMslM)).apply {
            if (settings.chartDatumAuto) setText("")
            hint = autoChartDatumM?.let { String.format(Locale.US, "Auto: %.2f here", height.of(it)) } ?: "Auto"
        }
        val datumShown = datumOffset.text.toString()
        panels.addRow(body, datumOffset)
        body.addView(panels.caption("Leave empty to work it out from the tide model. Type your tide table's value (0–${if (height == HeightUnit.FEET) "32" else "10"} ${height.label}) to use that instead.", topPaddingDp = 0))
        body.addView(panels.caption("Manual tide height, from a tide table (${height.label})", topPaddingDp = 2))
        val manual = numberInput(height.of(settings.manualHeightM), signed = true)
        val manualShown = manual.text.toString()
        // Left as shown keeps the exact value: rounding through another unit would slowly change it.
        fun typedMetres(input: android.widget.EditText, shown: String, original: Double): Double? {
            val text = input.text.toString()
            return if (text == shown && text.isNotBlank()) original
            else text.replace(',', '.').toDoubleOrNull()?.let(height::toMetres)
        }
        panels.addRow(body, manual)
        val correct = check("Add the tide to depths and alarms", settings.correctDepths)
        panels.addRow(body, correct, bottomMarginDp = 2)
        val chip = check("Show the tide on the chart", settings.showChip)
        panels.addRow(body, chip, bottomMarginDp = 2)
        panels.showPanel("Tide settings", body, onDismiss = {
            // Anything empty or out of reason (a slip of the finger) means auto / unchanged.
            val typedDatum = typedMetres(datumOffset, datumShown, settings.chartDatumBelowMslM)?.takeIf(::validChartDatum)
            onSave(
                settings.copy(
                    mode = mode,
                    datum = datum,
                    chartDatumAuto = typedDatum == null,
                    chartDatumBelowMslM = typedDatum ?: settings.chartDatumBelowMslM,
                    manualHeightM = typedMetres(manual, manualShown, settings.manualHeightM)?.takeIf(::validTideHeight) ?: settings.manualHeightM,
                    showChip = chip.isChecked,
                    correctDepths = correct.isChecked
                )
            )
        })
    }

    // Pieces --------------------------------------------------------------------------

    private fun nowCard(body: LinearLayout, big: String, line: String, next: String?) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(0xFF0F1A27.toInt(), palette.border, 14)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        }
        card.addView(text("NOW", 11f, muted = true, bold = true))
        card.addView(text(big, 34f, bold = true).apply { setTextColor(0xFF38BDF8.toInt()) })
        card.addView(text(line, 14f, bold = true))
        next?.let { card.addView(text("Next: $it", 13f, muted = true).apply { setPadding(0, context.dp(4), 0, 0) }) }
        body.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(10)
        })
    }

    private fun dayRow(body: LinearLayout, day: String, items: List<TideExtreme>, offset: Double, now: Long) {
        body.addView(text(day, 13f, bold = true).apply { setPadding(context.dp(2), context.dp(6), 0, context.dp(4)) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.take(4).forEachIndexed { i, e ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = context.roundedBackground(palette.raised, if (e.isHigh) 0xFF38BDF8.toInt() else palette.border, UiRadius.CONTROL)
                setPadding(context.dp(4), context.dp(6), context.dp(4), context.dp(6))
                alpha = if (e.timeMillis < now) 0.45f else 1f
            }
            cell.addView(text(if (e.isHigh) "▲ High" else "▼ Low", 11f, muted = true).apply { gravity = Gravity.CENTER })
            cell.addView(text(clock(e.timeMillis, null), 15f, bold = true).apply { gravity = Gravity.CENTER })
            cell.addView(text(NavFormat(units()).tideHeight(e.heightM + offset), 12f).apply { gravity = Gravity.CENTER })
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = context.dp(6)
            })
        }
        // Same cell width on days with fewer than four tides.
        repeat(4 - items.size.coerceAtMost(4)) {
            row.addView(LinearLayout(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = context.dp(6) })
        }
        body.addView(row)
    }

    private fun option(iconRes: Int, title: String, detail: String, selected: Boolean, onClick: () -> Unit): TextView =
        panels.row(title, detail, onClick = onClick).apply {
            icon(this, iconRes)
            if (selected) background = context.roundedBackground(palette.raised, palette.accent, UiRadius.ROW, strokeWidthDp = 2)
        }

    /** A tinted icon at the start of [view]: accent on rows, the text's colour on buttons. */
    private fun icon(view: TextView, res: Int) {
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return
        drawable.setTint(if (view is Button) view.currentTextColor else palette.accent)
        val size = context.dp(22)
        drawable.setBounds(0, 0, size, size)
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.compoundDrawablePadding = context.dp(10)
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(palette.primaryText)
        background = context.roundedBackground(palette.raised, palette.border, UiRadius.CONTROL)
        contentDescription = description
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (muted) palette.secondaryText else palette.primaryText)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        if (primary) applyPrimaryStyle(palette) else applySecondaryStyle(palette)
        setOnClickListener { onClick() }
    }

    private fun modeLabel(mode: TideMode) = when (mode) {
        TideMode.MODEL -> "Forecast (free, works offline once saved)"
        TideMode.MANUAL -> "Manual height"
        TideMode.OFF -> "Off"
    }

    private fun datumLabel(datum: TideDatum) = when (datum) {
        TideDatum.MEAN_SEA_LEVEL -> "Mean sea level"
        TideDatum.CHART_DATUM -> "Chart datum (lowest tide, LAT)"
    }

    private fun numberInput(value: Double, signed: Boolean = false) = panels.input {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        setSingleLine(true)
        setText(String.format(Locale.US, "%.2f", value))
    }

    private fun check(label: String, checked: Boolean) = CheckBox(context).apply {
        text = label
        isChecked = checked
        textSize = 14f
        setTextColor(palette.primaryText)
        buttonTintList = ColorStateList.valueOf(palette.accent)
    }

    private fun clock(millis: Long, now: Long?): String {
        val sameDay = now == null || SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(millis)) == it.format(Date(now)) }
        return SimpleDateFormat(if (sameDay) "HH:mm" else "EEE HH:mm", Locale.US).format(Date(millis))
    }

    private fun inHours(ms: Long): String {
        val minutes = (abs(ms) / 60_000.0).roundToInt()
        return if (minutes >= 60) "${minutes / 60}h ${String.format(Locale.US, "%02d", minutes % 60)}m" else "${minutes}m"
    }

    private fun size(bytes: Long) = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB" else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun compass(degrees: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees + 22.5) / 45).toInt() % 8]

    /** Local midnight [offset] days from [now]'s day. */
    private fun startOfDay(now: Long, offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
