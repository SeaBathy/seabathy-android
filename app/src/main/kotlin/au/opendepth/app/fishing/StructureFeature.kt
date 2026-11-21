package au.opendepth.app.fishing

import android.app.Dialog
import android.graphics.PointF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.geotiff.DepthSource
import au.opendepth.app.map.GeoJson
import au.opendepth.app.map.MapHolder
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.model.OverlayLayer
import au.opendepth.app.nav.DepthUnit
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.UiRadius
import au.opendepth.app.ui.applyReadableText
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.roundedBackground
import au.opendepth.app.util.CoordinateFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.util.Locale

/**
 * "Find structure" on a depth layer (its STRUCTURE button in the layers
 * panel): looks through the depths on screen, or the whole layer when it is
 * small, for pinnacles, ledges and holes ([StructureFinder]), marks them on
 * the chart and lists them best first, cut to a range of depths. A row goes
 * to its mark; SAVE keeps it as a waypoint. Works offline, off the main thread.
 */
class StructureFeature(
    private val activity: AppCompatActivity,
    private val panels: PanelDialogs,
    private val mapHolder: MapHolder,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun depthUnit(): DepthUnit
        /** The layer's depths, or null when it has none (or its file is closed). */
        fun depthSource(layerId: String): DepthSource?
        /** The finest zoom a large survey's tiles are drawn at. */
        fun tileZoom(layerId: String): Int
        fun closeSidePanel()
        /** Lets go of the boat when the chart is following it, so the chart can move to a mark. */
        fun stopFollowing()
        /** Saves a waypoint, under a free name close to [name]; returns the name used, or null. */
        fun saveWaypoint(name: String, lat: Double, lng: Double, description: String): String?
        fun toast(message: String)
    }

    private val palette = AppTheme.palette
    private val layer = ToolMarksLayer(mapHolder, "structure-marks")

    private var marks: List<StructureFinder.Mark> = emptyList()
    private var cellMetres = 0.0
    private var whole = false
    private var layerName = ""
    /** What the marks were found in: the layer and the part of the chart, so the same search isn't run twice. */
    private var searchKey: String? = null
    private var band = 0
    private val saved = HashSet<Int>()
    private var job: Job? = null
    private var panel: Dialog? = null

    /** Puts the marks back on a new style: from the activity's style-installed hook. */
    fun install() = layer.install()

    /** The marks on the chart now (cut to the depth range picked), for the plotter export: names and descriptions. */
    fun shownMarks(): List<Triple<StructureFinder.Mark, String, String>> {
        if (layer.isEmpty) return emptyList()
        val unit = callbacks.depthUnit()
        val range = StructureFinder.bands(unit).getOrNull(band) ?: return emptyList()
        return marks.filter { range.holds(it) }.map { Triple(it, StructureFinder.name(it, unit), StructureFinder.describe(it, unit)) }
    }

    /** Looks for structure in [overlay]'s depths. */
    fun find(overlay: OverlayLayer) {
        if (!overlay.visible) {
            callbacks.toast("Turn ${overlay.name} on to look for structure in it")
            return
        }
        val source = callbacks.depthSource(overlay.id)
        if (source == null) {
            callbacks.toast("${overlay.name} has no depths to look in")
            return
        }
        val map = mapHolder.map ?: return
        val view = ScreenDepths.viewOf(map)
        if (view == null) {
            callbacks.toast("Move the chart off the date line to look for structure")
            return
        }
        val small = ScreenDepths.isSmall(source)
        val area: GeoBounds? = if (small) source.bounds else ScreenDepths.partOf(view.bounds, source.bounds)
        if (area == null) {
            callbacks.toast("${overlay.name} isn't on screen: go to it first")
            return
        }
        callbacks.closeSidePanel()
        val key = "${overlay.id}/" + if (small) "whole" else String.format(Locale.US, "%.5f/%.5f/%.5f/%.5f/%.1f", area.west, area.south, area.east, area.north, view.zoom)
        // The same layer and the same part of the chart: the list again, without looking again.
        if (key == searchKey && job?.isActive != true) {
            if (layer.isEmpty) drawMarks()
            showList()
            return
        }
        job?.cancel()
        callbacks.toast("Looking for structure in ${overlay.name}…")
        val tileZoom = callbacks.tileZoom(overlay.id)
        job = scope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val grid = withContext(Dispatchers.IO) { ScreenDepths.grid(source, area, view.zoom, tileZoom) { !isActive } }
                        ?: return@withContext null
                    val (cx, cy) = ScreenDepths.cellMetres(grid)
                    StructureFinder.find(
                        grid.values, grid.cols, grid.rows, cx, cy, grid.placement,
                        within = { lat, lng -> area.contains(lat, lng) },
                        cancelled = { !isActive }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.logError(TAG, "structure search FAILED: layer=${overlay.name}", error)
                null
            }
            if (result == null) {
                callbacks.toast("Couldn't read the depths of ${overlay.name} just now: try again")
                return@launch
            }
            DiagnosticLog.log(TAG, "structure: ${result.marks.size} marks on a ${result.cellMetres.toInt()} m grid of ${result.cellsWorked} cells")
            marks = result.marks
            cellMetres = result.cellMetres
            whole = small
            layerName = overlay.name
            searchKey = key
            band = 0
            saved.clear()
            drawMarks()
            showList()
        }
    }

    /** Takes the marks off the chart. */
    fun clear() {
        job?.cancel()
        layer.clear()
        marks = emptyList()
        searchKey = null
        saved.clear()
    }

    // List -------------------------------------------------------------------------

    private fun showList() {
        panel?.dismiss()
        val unit = callbacks.depthUnit()
        val bands = StructureFinder.bands(unit)
        if (band !in bands.indices) band = 0
        val body = panels.body()
        val grid = if (cellMetres >= 1000) String.format(Locale.US, "%.1f km", cellMetres / 1000) else "${Math.round(cellMetres)} m"
        body.addView(panels.caption(
            "An automatic guess from the chart depths, not a survey: check each mark on your sounder. " +
                (if (whole) "Looked through all of $layerName" else "Looked through the depths of $layerName on screen") +
                " on a $grid grid: zoom in to find smaller structure.",
            topPaddingDp = 0
        ))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        lateinit var bandRow: TextView
        bandRow = panels.row("Depths", bands[band].label) {
            panels.showChoicePanel("Depths", bands.map { it.label }, band) { i ->
                band = i
                bandRow.text = "Depths\n${bands[i].label}"
                fillList(list, unit)
                drawMarks()
            }
        }
        panels.addRow(body, bandRow)
        body.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fillList(list, unit)
        // A plain button, like the drift planner's "Clear the drift": the app shows "Clear" in red.
        body.addView(android.widget.Button(activity).apply {
            text = "Clear marks"
            isAllCaps = false
            applySecondaryStyle(palette)
            setOnClickListener {
                clear()
                panel?.dismiss()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(8)
        })
        lateinit var shown: Dialog
        // A dialog says it was dismissed a moment later: by then a new list may have replaced it.
        shown = panels.showPanel("Structure", body, overMap = true, onDismiss = { if (panel === shown) panel = null })
        panel = shown
    }

    private fun fillList(list: LinearLayout, unit: DepthUnit) {
        list.removeAllViews()
        val range = StructureFinder.bands(unit)[band]
        val shown = marks.withIndex().filter { range.holds(it.value) }
        when {
            marks.isEmpty() -> list.addView(panels.caption("No clear structure in these depths. Zoom in for smaller features, or look over rougher ground."))
            shown.isEmpty() -> list.addView(panels.caption("None in this depth range."))
        }
        for ((index, mark) in shown) panels.addRow(list, markRow(index, mark, unit))
        applyReadableText(list, palette)
    }

    /** One mark: tap to go to it, SAVE to keep it as a waypoint. */
    private fun markRow(index: Int, mark: StructureFinder.Mark, unit: DepthUnit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = activity.roundedBackground(palette.raised, palette.border, UiRadius.ROW)
        setPadding(activity.dp(14), activity.dp(10), activity.dp(10), activity.dp(10))
        minimumHeight = activity.dp(56)
        val description = StructureFinder.describe(mark, unit)
        val detail = CoordinateFormatter.format(mark.lat, mark.lng) +
            if (mark.kind == StructureFinder.Kind.LEDGE) " · ${Math.round(mark.lengthM * unit.perMetre)} ${unit.label} long" else ""
        addView(TextView(activity).apply {
            text = "${index + 1}. $description\n$detail"
            textSize = 13f
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val save = TextView(activity).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            minWidth = activity.dp(52)
            minHeight = activity.dp(30)
            background = activity.roundedBackground(palette.surface, palette.border, 8)
            setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6))
            fun showSaved(done: Boolean) {
                text = if (done) "SAVED" else "SAVE"
                isEnabled = !done
                alpha = if (done) 0.5f else 1f
                contentDescription = if (done) "Saved as a waypoint" else "Save as a waypoint"
            }
            showSaved(index in saved)
            setOnClickListener {
                val name = callbacks.saveWaypoint(
                    StructureFinder.name(mark, unit), mark.lat, mark.lng,
                    "$description. An automatic guess from the chart depths (SeaBathy structure finder): check it on the sounder."
                )
                if (name != null) {
                    saved += index
                    showSaved(true)
                    callbacks.toast("Saved $name")
                }
            }
        }
        addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = activity.dp(8)
        })
        isClickable = true
        isFocusable = true
        contentDescription = "Go to mark ${index + 1}: $description"
        setOnClickListener { goTo(mark) }
    }

    /**
     * Moves the chart to [mark], putting it in the part of the screen the
     * list leaves clear (the top), at the same zoom.
     */
    private fun goTo(mark: StructureFinder.Mark) {
        val map = mapHolder.map ?: return
        callbacks.stopFollowing()
        val target = LatLng(mark.lat, mark.lng)
        val height = activity.resources.displayMetrics.heightPixels
        val centre = runCatching {
            val at = map.projection.toScreenLocation(target)
            map.projection.fromScreenLocation(PointF(at.x, at.y + height * LIST_CLEAR_SHIFT))
        }.getOrNull() ?: target
        map.animateCamera(CameraUpdateFactory.newLatLng(centre))
    }

    // Chart ------------------------------------------------------------------------

    private fun drawMarks() {
        val unit = callbacks.depthUnit()
        val range = StructureFinder.bands(unit).getOrNull(band) ?: return
        val features = marks.withIndex().filter { range.holds(it.value) }.map { (index, mark) ->
            ToolMarksLayer.point(mark.lat, mark.lng, colourOf(mark.kind), "${index + 1}")
        }
        if (marks.isEmpty()) layer.clear() else layer.show(GeoJson.featureCollection(features))
    }

    private fun colourOf(kind: StructureFinder.Kind) = when (kind) {
        StructureFinder.Kind.PINNACLE -> "#FBBF24"
        StructureFinder.Kind.LEDGE -> "#F472B6"
        StructureFinder.Kind.HOLE -> "#60A5FA"
    }

    private companion object {
        const val TAG = "StructureFinder"

        /** The list covers the lower part of the screen: a mark gone to sits this share of the screen above the middle. */
        const val LIST_CLEAR_SHIFT = 0.29f
    }
}
