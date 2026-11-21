package au.opendepth.app.nav

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.data.RouteLibrary
import au.opendepth.app.data.SavedRoute
import au.opendepth.app.map.RouteDepthCheck
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.applyPrimaryStyle
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import au.opendepth.app.ui.UiRadius

/** The saved-routes list and the leg-by-leg plan. */
class RouteDialogs(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    interface LibraryActions {
        fun load(route: SavedRoute)
        fun navigate(route: SavedRoute)
        fun exportGpx(route: SavedRoute)
        fun importGpx()
        fun deleted(route: SavedRoute)
        /** The plan on the chart, to save; null when it has fewer than two points. */
        fun saveCurrent(): Unit?
    }

    fun showLibrary(library: RouteLibrary, settings: NavSettings, actions: LibraryActions) {
        val body = panels.body()
        lateinit var dialog: Dialog
        val format = NavFormat(settings.units)

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(smallButton("Save current route", primary = true) {
            if (actions.saveCurrent() != null) dialog.dismiss()
        }, weighted())
        top.addView(smallButton("Import GPX") { dialog.dismiss(); actions.importGpx() }, weighted(start = 8))
        panels.addRow(body, top)

        val routes = library.all
        if (routes.isEmpty()) {
            body.addView(panels.caption("No saved routes yet. Plan one on the chart and save it, or import a GPX file."))
        }
        routes.forEach { route ->
            val plan = RoutePlan(route.points, settings.cruiseSpeedKn, settings.fuelBurnLph)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = context.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
                setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            }
            card.addView(TextView(context).apply {
                text = route.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
            })
            card.addView(TextView(context).apply {
                text = buildString {
                    append("${route.points.size} points • ${format.distance(plan.totalNm)} • ${format.duration(plan.totalHours)}")
                    plan.totalFuelLitres?.let { append(" • ${format.fuel(it)}") }
                }
                textSize = 12f
                setTextColor(palette.secondaryText)
            })
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(smallButton("Go", primary = true) { dialog.dismiss(); actions.navigate(route) }, weighted())
            buttons.addView(smallButton("Load") { dialog.dismiss(); actions.load(route) }, weighted(start = 6))
            buttons.addView(smallButton("Reverse") {
                library.save(route.id, route.name, route.points.reversed())
                dialog.dismiss()
                showLibrary(library, settings, actions)
            }, weighted(start = 6))
            card.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            })
            val more = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            more.addView(smallButton("Rename") {
                promptName("Rename route", route.name, library, route.id) { name ->
                    library.rename(route.id, name)
                    dialog.dismiss()
                    showLibrary(library, settings, actions)
                }
            }, weighted())
            more.addView(smallButton("GPX") { actions.exportGpx(route) }, weighted(start = 6))
            more.addView(smallButton("Delete", destructive = true) {
                panels.confirm("Delete ${route.name}?", null) {
                    library.delete(route.id)
                    actions.deleted(route)
                    dialog.dismiss()
                    showLibrary(library, settings, actions)
                }
            }, weighted(start = 6))
            card.addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(6)
            })
            panels.addRow(body, card)
        }
        dialog = panels.showPanel("Saved routes", body)
    }

    /** Asks for a route name; [onName] gets a trimmed name not used by another route. */
    fun promptName(title: String, initial: String, library: RouteLibrary, exceptId: String?, onName: (String) -> Unit) {
        val body = panels.body()
        val input = panels.input {
            setText(initial)
            setSingleLine(true)
            hint = "Route name"
            selectAll()
        }
        panels.addRow(body, input)
        lateinit var dialog: Dialog
        dialog = panels.showPanel(title, body, actionLabel = "Save", onAction = {
            val name = input.text.toString().trim()
            when {
                name.isEmpty() -> input.error = "Enter a name"
                library.isNameTaken(name, exceptId) -> input.error = "A route called \"$name\" already exists"
                else -> {
                    dialog.dismiss()
                    onName(name)
                }
            }
        })
        input.requestFocus()
    }

    /**
     * Every leg with distance, bearing, time, arrival and fuel, the totals,
     * and the shallowest water per leg against the boat's draft + margin.
     */
    fun showLegs(
        title: String,
        plan: RoutePlan,
        speedKn: Double,
        settings: NavSettings,
        variation: (Double, Double) -> Double,
        departureMillis: Long,
        legDepths: List<RouteDepthCheck?>?,
        tideM: Double?,
        tideWindow: RouteTideWindow? = null
    ) {
        val body = panels.body()
        val f = NavFormat(settings.units)
        body.addView(
            panels.caption(
                "Departing now at ${f.speed(speedKn)}" +
                    (if (settings.fuelSet) ", burning ${f.fuel(settings.fuelBurnLph)}/h" else "") +
                    (if (tideM != null) ". Depths include the tide now" else ". Depths are survey depths (no tide)") +
                    ".",
                topPaddingDp = 0
            )
        )
        val minDepth = settings.minimumDepthM
        plan.legs.forEachIndexed { index, leg ->
            val name = leg.to.name?.takeIf { it.isNotBlank() } ?: "Point ${leg.number + 1}"
            val variationHere = variation(leg.from.lat, leg.from.lng)
            val depth = legDepths?.getOrNull(index)
            val water = depth?.let { -it.shallowestMetres + (tideM ?: 0.0) }
            val shallow = water != null && water < minDepth
            val text = buildString {
                append("Leg ${leg.number} → $name\n")
                append("${f.bearing(leg.bearingTrue, variationHere)}   ${f.distance(leg.distanceNm)}   ${f.duration(leg.hours)}")
                append("   arrive ${f.clock(plan.arrivalMillis(leg, departureMillis), departureMillis)}")
                leg.fuelLitres?.let { append("   ${f.fuel(it)}") }
                append("\nTotal ${f.distance(leg.cumulativeNm)}")
                when {
                    depth == null && legDepths != null -> append("   •   no survey on this leg")
                    water != null -> append("   •   least depth ${f.depth(water)}${if (shallow) "  ⚠ less than ${f.depth(minDepth)}" else ""}")
                }
            }
            val row = TextView(context).apply {
                this.text = text
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(if (shallow) 0xFFFF6B6B.toInt() else palette.primaryText)
                background = context.roundedBackground(palette.raised, if (shallow) 0xFFEF4444.toInt() else palette.border, UiRadius.ROW)
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            }
            panels.addRow(body, row, bottomMarginDp = 6)
        }
        body.addView(panels.sectionHeader("TOTAL"))
        body.addView(TextView(context).apply {
            text = buildString {
                append("${plan.legs.size} legs, ${f.distance(plan.totalNm)}, ${f.duration(plan.totalHours)}")
                plan.totalHours?.let { append("\nArrive ${f.clock(departureMillis + (it * RoutePlan.HOUR_MS).toLong(), departureMillis)}") }
                plan.totalFuelLitres?.let { fuel ->
                    append("\nFuel ${f.fuel(fuel)}")
                    val usable = settings.usableFuelLitres
                    if (usable > 0) {
                        append(if (fuel > usable) "  ⚠ more than the usable ${f.fuel(usable)}" else ", leaving ${f.fuel(usable - fuel)} plus reserve")
                    }
                }
                val worst = legDepths?.filterNotNull()?.maxByOrNull { it.shallowestMetres }
                worst?.let {
                    val water = -it.shallowestMetres + (tideM ?: 0.0)
                    append("\nLeast depth on route ${f.depth(water)}")
                    if (water < minDepth) append("  ⚠ below your ${f.depth(minDepth)} (draft + margin)")
                }
            }
            textSize = 14f
            setTextColor(palette.primaryText)
            gravity = Gravity.START
        })
        tideWindow?.let { tideWindowSection(body, it, settings, f) }
        panels.showPanel(title, body)
    }

    /**
     * When the tide gives enough water over the route's shallowest charted point
     * (draft + margin), today and tomorrow: a summary, a bar and the times.
     */
    private fun tideWindowSection(body: LinearLayout, w: RouteTideWindow, settings: NavSettings, f: NavFormat) {
        body.addView(panels.sectionHeader("TIDE WINDOW"))
        val minDepth = settings.minimumDepthM
        val where = "Shallowest charted point: leg ${w.legNumber}, " +
            (if (w.chartDepthM < 0) "dries ${f.depth(-w.chartDepthM)}" else f.depth(w.chartDepthM)) + "."
        val tides = w.tides
        val result = w.result
        if (tides !is RouteTides.Known || result == null) {
            body.addView(plainText("$where ${(tides as? RouteTides.Missing)?.reason ?: "The tides don't cover now here."}"))
            return
        }
        val offset = tides.shownOffsetM
        val needed = result.neededTideM + offset
        val endLabel = if (result.knownUntilMillis >= w.untilMillis) "the end of tomorrow" else f.clock(result.knownUntilMillis, w.fromMillis)
        val summary = if (result.alwaysClear) {
            "$where Enough water for your ${f.depth(minDepth)} (draft + margin) at every tide until $endLabel."
        } else {
            "$where Your ${f.depth(minDepth)} (draft + margin) needs a tide of ${f.tideHeight(needed)} or more." +
                if (!result.neverClear) "" else "\n⚠ Not enough water until $endLabel: the highest tide, " +
                    "${f.tideHeight(result.highestTideM + offset)} at ${f.clock(result.highestAtMillis, w.fromMillis)}, " +
                    "gives ${f.depth(w.chartDepthM + result.highestTideM)}."
        }
        body.addView(plainText(summary).apply { if (result.neverClear) setTextColor(0xFFFF6B6B.toInt()) })
        body.addView(TideWindowBar(context).apply {
            fromMillis = w.fromMillis
            toMillis = w.untilMillis
            knownUntilMillis = result.knownUntilMillis
            periods = result.periods
            markMillis = w.arrivalMillis
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
            bottomMargin = context.dp(4)
        })
        if (!result.alwaysClear && !result.neverClear) body.addView(plainText(periodList(result, w, f), mono = true))
        w.passage?.let { body.addView(passageText(it, w, f, minDepth)) }
        body.addView(panels.caption(tides.source, topPaddingDp = 6))
        val covered = if (w.coveredFraction < 0.98) "Only ${(w.coveredFraction * 100).toInt()}% of the route has charted depths: shallower water may be missed. " else ""
        body.addView(
            panels.caption(
                covered + "A guide only: charted depths can be wrong or out of date, and the weather changes the real tide. Not for navigation.",
                topPaddingDp = 0
            )
        )
    }

    /**
     * Leaving now, the water at each leg's shallowest charted point as the boat gets
     * there: the least of them, and every one short of draft + margin.
     */
    private fun passageText(p: TideWindow.Passage, w: RouteTideWindow, f: NavFormat, minDepth: Double): TextView {
        val worst = p.worst
        val unknown = if (p.unknownLegs.isEmpty()) "" else " No tides for leg${if (p.unknownLegs.size > 1) "s" else ""} ${p.unknownLegs.joinToString(", ")}."
        val text = when {
            worst == null -> "Leaving now: no tides for the legs' shallowest points, so the water as you pass them isn't checked."
            p.short.isNotEmpty() -> {
                val others = p.short.drop(1).map { it.legNumber }
                "Leaving now, \u26a0 not enough water at leg ${worst.legNumber}'s shallowest point: about ${f.depth(worst.waterM)} " +
                    "when you get there at ${f.clock(worst.arrivalMillis, w.fromMillis)}, under your ${f.depth(minDepth)}" +
                    (if (others.isEmpty()) "." else "; short at leg${if (others.size > 1) "s" else ""} ${others.joinToString(", ")} too.") +
                    unknown
            }
            else -> "Leaving now, each leg's shallowest charted point has at least ${f.depth(worst.waterM)} as you pass it " +
                "(least at leg ${worst.legNumber}, about ${f.clock(worst.arrivalMillis, w.fromMillis)})." + unknown
        }
        return plainText(text).apply { if (worst == null || p.short.isNotEmpty()) setTextColor(0xFFFF6B6B.toInt()) }
    }

    /** "Today     10:20–15:45  from 22:30", a line a day. */
    private fun periodList(result: TideWindow.Result, w: RouteTideWindow, f: NavFormat): String {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val today = day.format(java.util.Date(w.fromMillis))
        return result.periods.groupBy { day.format(java.util.Date(it.startMillis)) }.entries.joinToString("\n") { (key, periods) ->
            val label = if (key == today) "Today" else "Tomorrow"
            label.padEnd(10) + periods.joinToString("  ") { p ->
                val open = p.endMillis >= result.knownUntilMillis && result.knownUntilMillis >= w.untilMillis
                if (open) "from " + f.clock(p.startMillis, p.startMillis) else f.clock(p.startMillis, p.startMillis) + "–" + f.clock(p.endMillis, p.startMillis)
            }
        }
    }

    private fun plainText(value: String, mono: Boolean = false) = TextView(context).apply {
        text = value
        textSize = if (mono) 13f else 14f
        if (mono) typeface = Typeface.MONOSPACE
        setTextColor(palette.primaryText)
        setPadding(context.dp(2), context.dp(2), 0, context.dp(2))
    }

    private fun smallButton(label: String, primary: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) =
        Button(context).apply {
            text = label
            if (primary) applyPrimaryStyle(palette) else applySecondaryStyle(palette)
            if (destructive) setTextColor(palette.destructive)
            textSize = 13f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun weighted(start: Int = 0) =
        LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { marginStart = context.dp(start) }
}
