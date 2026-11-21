package au.opendepth.app.fishing

import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import au.opendepth.app.conditions.ColourChoice
import au.opendepth.app.conditions.ConditionsArea
import au.opendepth.app.conditions.Contours
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.NoTakeZones
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.Waypoint
import au.opendepth.app.nav.NavGeo
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.UiRadius
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * "Export for a chartplotter" (Settings, Waypoints and backup): what is on
 * the chart as a GPX file to copy to a plotter's SD card. Waypoints and the
 * structure finder's marks go in as waypoints; depth contours at depths of
 * the user's choosing (from the depth layer on top), the sea conditions'
 * temperature lines, band edges and temperature breaks when they're shown,
 * and no-take zone edges when the zones are on, go in as named tracks. Only
 * what is on screen, simplified and capped to suit a plotter ([PlotterGpx]).
 * Shared through the app's usual GPX path.
 */
class PlotterExport(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun units(): NavUnits
        fun waypoints(): List<Waypoint>
        /** The structure finder's marks on the chart: each with its name and description. */
        fun structureMarks(): List<Triple<StructureFinder.Mark, String, String>>
        /** The topmost depth layer shown with depths within [view]: its name, depths and the finest zoom its tiles are drawn at. */
        fun depthLayer(view: GeoBounds): Triple<String, DepthSource, Int>?
        /** Sea conditions on show (read only), or null when they're hidden. */
        fun seaConditions(): SeaShown?
        /** Whether the marine park zones are drawn. */
        fun zonesShown(): Boolean
        /** Writes the file for sharing and returns the share sheet's intent, or null when it couldn't. Writes a file: off the main thread. */
        fun shareIntent(gpx: String): android.content.Intent?
        /** Opens the share sheet; false when no app can take the file. Main thread. */
        fun startShare(intent: android.content.Intent): Boolean
        fun toast(message: String)
    }

    /** What the sea conditions show: the area, what its colours are, and the lines drawn over them (GeoJSON). */
    class SeaShown(val area: ConditionsArea, val colour: ColourChoice, val linesGeoJson: String?)

    private val palette = AppTheme.palette

    private var withWaypoints = true
    private var withMarks = true
    private var withContours = true
    private var withSea = true
    private var withBreaks = true
    private var withZones = true
    /** Contour depths, metres: suggested for the depths on screen until typed. */
    private var depthsM: List<Double> = emptyList()
    private var depthsTyped = false
    private var job: Job? = null
    private var suggestJob: Job? = null

    /** The depths last read for a screen, kept for the export straight after: the key says which layer and screen. */
    @Volatile
    private var screenGrid: Pair<String, au.opendepth.app.map.DepthContours.Grid>? = null

    fun show() {
        val map = mapHolder.map ?: return
        val view = ScreenDepths.viewOf(map)
        if (view == null) {
            callbacks.toast("Move the chart off the date line to export it")
            return
        }
        val box = view.bounds
        val unit = callbacks.units().depth
        val body = panels.body()
        body.addView(panels.caption(
            "Saves what's on the chart as a GPX file for a chartplotter: copy it to the plotter's SD card and import it there. " +
                "Points become waypoints, lines become tracks. Not for navigation.",
            topPaddingDp = 0
        ))
        body.addView(panels.sectionHeader("ON SCREEN"))

        val waypoints = callbacks.waypoints().filter { box.contains(it.lat, it.lng) }
        panels.addRow(body, switchRow("Waypoints", count(waypoints.size, "waypoint") + " on screen", withWaypoints && waypoints.isNotEmpty(), waypoints.isNotEmpty()) { withWaypoints = it })

        val marks = callbacks.structureMarks().filter { box.contains(it.first.lat, it.first.lng) }
        if (marks.isNotEmpty()) {
            panels.addRow(body, switchRow("Structure marks", count(marks.size, "mark") + " from Find structure", withMarks, true) { withMarks = it })
        }

        val depthLayer = callbacks.depthLayer(box)
        if (depthLayer != null) {
            val (name, source, tileZoom) = depthLayer
            if (depthsM.isEmpty()) depthsM = listOf(10.0, 20.0, 30.0).map { it / unit.perMetre }
            lateinit var depthsRow: TextView
            panels.addRow(body, switchRow("Depth contours", "From $name", withContours, true) { on ->
                withContours = on
                depthsRow.visibility = if (on) View.VISIBLE else View.GONE
            })
            depthsRow = panels.row("Contour depths", "${PlotterLines.depthsText(depthsM, unit)} ${unit.label}") {
                editDepths(unit) { depthsRow.text = "Contour depths\n${PlotterLines.depthsText(depthsM, unit)} ${unit.label}" }
            }
            depthsRow.visibility = if (withContours) View.VISIBLE else View.GONE
            panels.addRow(body, depthsRow)
            // Until depths are typed, they're suggested for the depths on screen, read off the main thread.
            if (!depthsTyped) {
                suggestJob?.cancel()
                suggestJob = scope.launch {
                    val part = ScreenDepths.partOf(box, source.bounds) ?: return@launch
                    val grid = runCatching { withContext(Dispatchers.IO) { gridFor(source, part, view.zoom, tileZoom) { !isActive } } }.getOrNull() ?: return@launch
                    val range = au.opendepth.app.map.DepthContours.depthsWithin(grid, part) ?: return@launch
                    val suggested = PlotterLines.suggestDepths((-range.second).coerceAtLeast(0.0), -range.first, unit)
                    if (suggested.isNotEmpty() && !depthsTyped) {
                        depthsM = suggested
                        depthsRow.text = "Contour depths\n${PlotterLines.depthsText(depthsM, unit)} ${unit.label}"
                    }
                }
            }
        }

        val sea = callbacks.seaConditions()
        // Read when exporting, off the main thread; here only whether any are drawn.
        if (sea?.linesGeoJson?.contains("\"type\":\"Feature\"") == true) {
            panels.addRow(body, switchRow("Sea conditions lines", "Temperature lines, band edges and eddies as shown", withSea, true) { withSea = it })
        }
        if (sea != null && sea.colour == ColourChoice.SEA_TEMP_BREAKS && sea.area.breaks != null) {
            panels.addRow(body, switchRow("Temperature breaks", "Where the sea temperature changes fastest", withBreaks, true) { withBreaks = it })
        }
        if (callbacks.zonesShown()) {
            panels.addRow(body, switchRow("No-take zone edges", "Marine park no-take zones on screen", withZones, true) { withZones = it })
        }
        body.addView(panels.caption(
            "Lines are simplified and the number of points kept to what plotters take. Check the file on your plotter: zoning and charts there are the official ones.",
            topPaddingDp = 8
        ))

        lateinit var dialog: android.app.Dialog
        dialog = panels.showPanel("Export for a chartplotter", body, actionLabel = "Export", onAction = {
            dialog.dismiss()
            export(view, waypoints, marks, depthLayer, sea)
        }, onDismiss = { suggestJob?.cancel() })
    }

    private fun export(
        view: ScreenDepths.View,
        waypoints: List<Waypoint>,
        marks: List<Triple<StructureFinder.Mark, String, String>>,
        depthLayer: Triple<String, DepthSource, Int>?,
        sea: SeaShown?
    ) {
        val box = view.bounds
        val unit = callbacks.units().depth
        val temperature = callbacks.units().temperature
        val zones = withZones && callbacks.zonesShown()
        val depths = if (withContours) depthsM else emptyList()
        job?.cancel()
        callbacks.toast("Making the GPX file…")
        job = scope.launch {
            val built = try {
                withContext(Dispatchers.Default) {
                    val points = ArrayList<PlotterGpx.Point>()
                    val lines = ArrayList<PlotterGpx.Line>()
                    if (withWaypoints) waypoints.forEach { points += PlotterGpx.Point(it.name, it.lat, it.lng, it.desc) }
                    if (withMarks) marks.forEach { (mark, name, description) -> points += PlotterGpx.Point(name, mark.lat, mark.lng, "$description. Automatic guess from chart depths.") }
                    if (depthLayer != null && depths.isNotEmpty()) {
                        val (layerName, source, tileZoom) = depthLayer
                        val part = ScreenDepths.partOf(box, source.bounds)
                        val grid = part?.let { withContext(Dispatchers.IO) { gridFor(source, it, view.zoom, tileZoom) { !isActive } } }
                        if (grid != null) {
                            for (d in depths) {
                                val name = PlotterLines.contourName(d, unit)
                                for (line in PlotterLines.contourAt(grid.values, grid.cols, grid.rows, d, grid.placement)) {
                                    PlotterGpx.clip(line, box.west, box.south, box.east, box.north).forEach { lines += PlotterGpx.Line(name, it, "From $layerName", GROUP_CONTOURS) }
                                }
                            }
                        }
                    }
                    val seaLines = if (withSea) sea?.linesGeoJson?.let { PlotterLines.seaLines(it, temperature.label) } else null
                    if (seaLines != null) {
                        seaLines.first.forEach { l -> PlotterGpx.clip(l.points, box.west, box.south, box.east, box.north).forEach { lines += PlotterGpx.Line(l.name, it, group = GROUP_SEA) } }
                        seaLines.second.filter { box.contains(it.lat, it.lng) }.forEach { points += PlotterGpx.Point(it.name, it.lat, it.lng) }
                    }
                    if (withBreaks && sea != null && sea.colour == ColourChoice.SEA_TEMP_BREAKS) {
                        sea.area.breaks?.let { breaks ->
                            val values = Contours.smooth(breaks.grid, breaks.values, 1)
                            val inView = ArrayList<Float>()
                            for (i in values.indices) {
                                val lat = breaks.grid.lats[i / breaks.grid.cols]
                                val lng = breaks.grid.lngs[i % breaks.grid.cols]
                                if (values[i].isFinite() && box.contains(lat, lng)) inView += values[i]
                            }
                            if (inView.isNotEmpty()) {
                                inView.sort()
                                // The sharpest sixth of the water on screen, and never gentler than 0.05 °C a km.
                                val threshold = maxOf(MIN_BREAK_PER_KM, inView[(inView.size * 0.85).toInt().coerceAtMost(inView.size - 1)].toDouble())
                                val label = String.format(Locale.US, "Temperature break (over %.2f %s/km)", temperature.ofDifference(threshold), temperature.label)
                                for (line in Contours.lines(breaks.grid, values, doubleArrayOf(threshold), minPoints = 6, rounding = 1)) {
                                    PlotterGpx.clip(line.points, box.west, box.south, box.east, box.north).forEach { lines += PlotterGpx.Line(label, it, group = GROUP_BREAKS) }
                                }
                            }
                        }
                    }
                    if (zones) {
                        val noTake = withContext(Dispatchers.IO) { runCatching { NoTakeZones.load(activity) }.getOrNull() }
                        noTake?.all?.filter { it.east >= box.west && it.west <= box.east && it.north >= box.south && it.south <= box.north }?.forEach { zone ->
                            val name = "No-take: " + listOf(zone.name, zone.type).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                            zone.polygons.forEach { rings ->
                                rings.forEach { ring -> PlotterGpx.clip(ring, box.west, box.south, box.east, box.north).forEach { lines += PlotterGpx.Line(name, it, group = GROUP_ZONES) } }
                            }
                        }
                    }
                    // A line may stray from the original by about a pixel or two of the chart as it is now.
                    val widthM = NavGeo.distanceM((box.south + box.north) / 2, box.west, (box.south + box.north) / 2, box.east)
                    PlotterGpx.build(
                        points, lines,
                        PlotterGpx.Limits(toleranceM = (widthM / TOLERANCE_PER_WIDTH).coerceAtLeast(1.0)),
                        description = "From SeaBathy. Not for navigation: structure marks are an automatic guess from chart depths; zones are a guide only."
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.logError(TAG, "plotter export FAILED", error)
                null
            } finally {
                // Used: a survey's depths for the screen are a few MB, not kept after.
                screenGrid = null
            }
            if (built == null) {
                callbacks.toast("Couldn't make the GPX file")
                return@launch
            }
            if (built.waypoints == 0 && built.tracks == 0) {
                callbacks.toast("Nothing to export on screen")
                return@launch
            }
            DiagnosticLog.log(TAG, "plotter GPX: ${built.waypoints} waypoints, ${built.tracks} tracks, ${built.trackPoints} points, ${built.leftOut} left out")
            val intent = withContext(Dispatchers.IO) { callbacks.shareIntent(built.gpx) } ?: return@launch
            if (callbacks.startShare(intent)) {
                callbacks.toast(
                    "GPX ready: ${count(built.waypoints, "waypoint")}, ${count(built.tracks, "track")}" +
                        if (built.leftOut > 0) ". ${built.leftOut} left out: more than a plotter takes." else ""
                )
            }
        }
    }

    private fun editDepths(unit: au.opendepth.app.nav.DepthUnit, done: () -> Unit) {
        val body = panels.body()
        body.addView(panels.caption("Depths in ${unit.label}, separated by commas. Up to ${PlotterLines.MAX_DEPTHS}.", topPaddingDp = 8))
        val input = panels.input {
            setText(PlotterLines.depthsText(depthsM, unit))
            setSingleLine(true)
            selectAll()
            hint = "10, 20, 30"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        panels.addRow(body, input)
        lateinit var dialog: android.app.Dialog
        dialog = panels.showPanel("Contour depths", body, actionLabel = "Save", onAction = {
            val parsed = PlotterLines.parseDepths(input.text.toString(), unit)
            if (parsed.isEmpty()) {
                callbacks.toast("Type at least one depth, like 20")
            } else {
                depthsM = parsed
                depthsTyped = true
                done()
                dialog.dismiss()
            }
        })
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** A row like [PanelDialogs.row], with a switch at its end instead of a value under its title (as in the contour lines sheet). */
    private fun switchRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = activity.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
            setPadding(activity.dp(16), activity.dp(10), activity.dp(12), activity.dp(10))
            minimumHeight = activity.dp(56)
            val switch = SwitchMaterial(activity).apply {
                isChecked = checked
                isEnabled = enabled
                contentDescription = title
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = title
                        textSize = 14f
                        setTextColor(palette.primaryText)
                    })
                    addView(TextView(activity).apply {
                        text = detail
                        textSize = 12f
                        setTextColor(palette.secondaryText)
                    })
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(switch)
            if (enabled) setOnClickListener { switch.toggle() } else alpha = 0.5f
        }

    private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"

    /** The depths of [source] under [part], read once for the suggestion and the export. Off the main thread. */
    private fun gridFor(source: DepthSource, part: GeoBounds, zoom: Double, tileZoom: Int, cancelled: () -> Boolean): au.opendepth.app.map.DepthContours.Grid? {
        val key = "${System.identityHashCode(source)}/${part.west}/${part.south}/${part.east}/${part.north}/$zoom"
        screenGrid?.takeIf { it.first == key }?.let { return it.second }
        return ScreenDepths.grid(source, part, zoom, tileZoom, cancelled)?.also { screenGrid = key to it }
    }

    private companion object {
        const val TAG = "PlotterExport"
        const val MIN_BREAK_PER_KM = 0.05

        /** Lines may stray by the chart's width over this. */
        const val TOLERANCE_PER_WIDTH = 3000.0

        /** The kinds of line, which take turns when there are more than a plotter's tracks. */
        const val GROUP_CONTOURS = "contours"
        const val GROUP_SEA = "sea"
        const val GROUP_BREAKS = "breaks"
        const val GROUP_ZONES = "zones"
    }
}
