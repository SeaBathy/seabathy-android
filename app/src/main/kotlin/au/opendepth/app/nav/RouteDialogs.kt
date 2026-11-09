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
        tideM: Double?
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
        panels.showPanel(title, body)
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
