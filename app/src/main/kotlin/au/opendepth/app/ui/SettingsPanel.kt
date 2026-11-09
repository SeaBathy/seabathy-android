package au.opendepth.app.ui

import android.content.Context
import android.widget.TextView
import au.opendepth.app.settings.DepthDisplayMode
import au.opendepth.app.settings.VisualSettings
import kotlin.math.roundToInt

/**
 * The Settings panel.
 *
 * Each row keeps a direct reference to its own label, so refreshing a value
 * after a change is a single assignment rather than a scan of the panel's
 * children looking for matching text.
 */
class SettingsPanel(
    private val context: Context,
    private val panels: PanelDialogs,
    private val xyzDialog: XyzTileDialog,
    private val dataSourceDialog: DataSourceDialog
) {
    data class Callbacks(
        val currentVisual: () -> VisualSettings,
        val onVisualChanged: (VisualSettings) -> Unit,
        val customBasemapConfigured: () -> Boolean,
        val xyz: XyzTileDialog.Callbacks,
        val onLoadMbtiles: () -> Unit,
        val onLoadGeoTiff: () -> Unit,
        val onEditDepthColours: () -> Unit,
        val onExportGpx: () -> Unit,
        val onShareDebugLog: () -> Unit,
        val rasterQuality: () -> au.opendepth.app.settings.RasterQuality,
        val onRasterQualityChanged: (au.opendepth.app.settings.RasterQuality) -> Unit,
        val showWaypointNames: () -> Boolean,
        val onShowWaypointNamesChanged: (Boolean) -> Unit,
        val dataSources: DataSourceDialog.Callbacks,
        val onNavigationSettings: () -> Unit,
        val onRoutes: () -> Unit,
        val onTides: () -> Unit,
        val onAnchor: () -> Unit,
        val onTracks: () -> Unit,
        val onConditions: () -> Unit,
        val onImportGpx: () -> Unit,
        val showGrid: () -> Boolean,
        val onShowGridChanged: (Boolean) -> Unit,
        val onGetSeabed: () -> Unit
    )

    fun show(callbacks: Callbacks) {
        val body = panels.body()
        val rows = mutableMapOf<String, TextView>()

        fun visual() = callbacks.currentVisual()
        fun onlineLayersDetail() =
            if (callbacks.customBasemapConfigured()) "Custom basemap set" else "Satellite, topo, or your own tile link"

        fun refresh() {
            val settings = visual()
            rows[ROW_CROSSHAIR]?.text =
                "Crosshair colour\n${colourName(settings.cursorColor)}"
            rows[ROW_COORDINATE]?.text =
                "Coordinate colour\n${colourName(settings.coordinateColor)}"
            rows[ROW_DEPTH_MODE]?.text =
                "Crosshair depth\n${DepthDisplayMode.labelFor(settings.depthDisplayMode)}"
            rows[ROW_COORD_SIZE]?.text =
                "Coordinate text size\n${settings.coordinateTextSize.roundToInt()} sp"
            rows[ROW_DEPTH_SIZE]?.text =
                "Depth text size\n${settings.depthTextSize.roundToInt()} sp"
            rows[ROW_XYZ]?.text = "Online map layers\n" + onlineLayersDetail()
            rows[ROW_WAYPOINT_NAMES]?.text = "Show waypoint names on map\n" +
                if (callbacks.showWaypointNames()) "On" else "Off"
            rows[ROW_GRID]?.text = "Latitude and longitude grid\n" +
                if (callbacks.showGrid()) "On" else "Off"
        }

        body.addView(panels.sectionHeader("MAP DISPLAY"))

        rows[ROW_CROSSHAIR] = panels.row(
            "Crosshair colour",
            colourName(visual().cursorColor)
        ) {
            panels.showChoicePanel(
                title = "Crosshair colour",
                labels = COLOUR_NAMES,
                selectedIndex = COLOUR_VALUES.indexOf(visual().cursorColor)
            ) { index ->
                callbacks.onVisualChanged(visual().copy(cursorColor = COLOUR_VALUES[index]))
                refresh()
            }
        }.also { panels.addRow(body, it) }

        rows[ROW_COORDINATE] = panels.row(
            "Coordinate colour",
            colourName(visual().coordinateColor)
        ) {
            panels.showChoicePanel(
                title = "Coordinate colour",
                labels = COLOUR_NAMES,
                selectedIndex = COLOUR_VALUES.indexOf(visual().coordinateColor)
            ) { index ->
                callbacks.onVisualChanged(
                    visual().copy(coordinateColor = COLOUR_VALUES[index])
                )
                refresh()
            }
        }.also { panels.addRow(body, it) }

        rows[ROW_DEPTH_MODE] = panels.row(
            "Crosshair depth",
            DepthDisplayMode.labelFor(visual().depthDisplayMode)
        ) {
            panels.showChoicePanel(
                title = "Crosshair depth",
                labels = DepthDisplayMode.LABELS,
                selectedIndex = DepthDisplayMode.VALUES.indexOf(visual().depthDisplayMode)
            ) { index ->
                callbacks.onVisualChanged(
                    visual().copy(depthDisplayMode = DepthDisplayMode.VALUES[index])
                )
                refresh()
            }
        }.also { panels.addRow(body, it) }

        rows[ROW_COORD_SIZE] = panels.row(
            "Coordinate text size",
            "${visual().coordinateTextSize.roundToInt()} sp"
        ) {
            panels.showChoicePanel(
                title = "Coordinate text size",
                labels = COORDINATE_SIZES.map { "$it sp" },
                selectedIndex = COORDINATE_SIZES.indexOf(
                    visual().coordinateTextSize.roundToInt()
                )
            ) { index ->
                callbacks.onVisualChanged(
                    visual().copy(coordinateTextSize = COORDINATE_SIZES[index].toFloat())
                )
                refresh()
            }
        }.also { panels.addRow(body, it) }

        rows[ROW_DEPTH_SIZE] = panels.row(
            "Depth text size",
            "${visual().depthTextSize.roundToInt()} sp"
        ) {
            panels.showChoicePanel(
                title = "Depth text size",
                labels = DEPTH_SIZES.map { "$it sp" },
                selectedIndex = DEPTH_SIZES.indexOf(visual().depthTextSize.roundToInt())
            ) { index ->
                callbacks.onVisualChanged(
                    visual().copy(depthTextSize = DEPTH_SIZES[index].toFloat())
                )
                refresh()
            }
        }.also { panels.addRow(body, it) }

        rows[ROW_WAYPOINT_NAMES] = panels.row(
            "Show waypoint names on map",
            if (callbacks.showWaypointNames()) "On" else "Off"
        ) {
            panels.showChoicePanel(
                title = "Show waypoint names on map",
                labels = listOf("Off", "On"),
                selectedIndex = if (callbacks.showWaypointNames()) 1 else 0
            ) { index ->
                callbacks.onShowWaypointNamesChanged(index == 1)
                refresh()
            }
        }.also { panels.addRow(body, it) }

        rows[ROW_GRID] = panels.row(
            "Latitude and longitude grid",
            if (callbacks.showGrid()) "On" else "Off"
        ) {
            panels.showChoicePanel(
                title = "Latitude and longitude grid",
                labels = listOf("Off", "On"),
                selectedIndex = if (callbacks.showGrid()) 1 else 0
            ) { index ->
                callbacks.onShowGridChanged(index == 1)
                refresh()
            }
        }.also { panels.addRow(body, it) }

        body.addView(panels.sectionHeader("MAP LAYERS"))
        panels.addRow(
            body,
            panels.row("Get seabed depths for this area", "Free depth data, anywhere in the world") {
                settingsDialog?.dismiss()
                callbacks.onGetSeabed()
            }
        )
        panels.addRow(
            body,
            panels.row("Load MBTiles file", "Charts and map packs (.mbtiles)") {
                callbacks.onLoadMbtiles()
            }
        )
        panels.addRow(
            body,
            panels.row("Load GeoTIFF", "Depth surveys (.tif)") {
                callbacks.onLoadGeoTiff()
            }
        )
        val qualities = au.opendepth.app.settings.RasterQuality.entries
        lateinit var qualityRow: TextView
        qualityRow = panels.row("GeoTIFF quality", callbacks.rasterQuality().label) {
            panels.showChoicePanel(
                title = "GeoTIFF quality",
                labels = qualities.map { "${it.label} — ${it.detail}" },
                selectedIndex = qualities.indexOf(callbacks.rasterQuality())
            ) { index ->
                callbacks.onRasterQualityChanged(qualities[index])
                qualityRow.text = "GeoTIFF quality\n${qualities[index].label}"
            }
        }.also { panels.addRow(body, it) }

        body.addView(panels.sectionHeader("DEPTH COLOURS"))
        body.addView(
            panels.caption(
                "Colours and depth range for survey layers. Depths outside the range are hidden.",
                topPaddingDp = 0
            )
        )
        panels.addRow(
            body,
            panels.row(
                "Survey colours",
                "Palette, depth range, shading"
            ) {
                callbacks.onEditDepthColours()
            }
        )

        body.addView(panels.sectionHeader("ONLINE MAPS"))
        rows[ROW_XYZ] = panels.row("Online map layers", onlineLayersDetail()) {
            xyzDialog.show(callbacks.xyz)
        }.also { panels.addRow(body, it) }

        body.addView(panels.sectionHeader("OFFLINE DATA"))
        panels.addRow(
            body,
            panels.row(
                "Offline maps",
                "Download the area on screen"
            ) {
                dataSourceDialog.show(callbacks.dataSources)
            }
        )

        body.addView(panels.sectionHeader("NAVIGATION"))
        panels.addRow(
            body,
            panels.row("Boat & navigation", "Speed, fuel, draft, units, alarms") {
                callbacks.onNavigationSettings()
            }
        )
        panels.addRow(
            body,
            panels.row("Saved routes", "Load, go, rename, export") {
                callbacks.onRoutes()
            }
        )
        panels.addRow(
            body,
            panels.row("Tides", "Heights, highs and lows, offline") {
                settingsDialog?.dismiss()
                callbacks.onTides()
            }
        )
        panels.addRow(
            body,
            panels.row("Anchor alarm", "Alarm if the boat drags") {
                settingsDialog?.dismiss()
                callbacks.onAnchor()
            }
        )
        panels.addRow(
            body,
            panels.row("Tracks", "Record where the boat goes, share as GPX") {
                settingsDialog?.dismiss()
                callbacks.onTracks()
            }
        )
        panels.addRow(
            body,
            panels.row("Sea conditions", "Currents, waves, wind, sea temperature, offline") {
                settingsDialog?.dismiss()
                callbacks.onConditions()
            }
        )

        body.addView(panels.sectionHeader("DATA"))
        panels.addRow(
            body,
            panels.row("Import GPX", "From a plotter or another app") {
                callbacks.onImportGpx()
            }
        )
        panels.addRow(
            body,
            panels.row("Export waypoints and route", "Share as a GPX file") {
                callbacks.onExportGpx()
            }
        )
        body.addView(
            panels.caption(
                "If something goes wrong, share this log so it can be fixed.",
                topPaddingDp = 0
            )
        )
        panels.addRow(
            body,
            panels.row("Export debug log", "What the app did, and any errors") {
                callbacks.onShareDebugLog()
            }
        )
        panels.addRow(
            body,
            panels.row("Licences and credits", "Open-source libraries and data sources") {
                showLicences()
            }
        )

        refresh()
        settingsDialog = panels.showPanel("Settings", body)
    }

    private fun showLicences() {
        val body = panels.body()
        body.addView(panels.caption(context.assets.open("licenses.txt").bufferedReader().use { it.readText() }))
        panels.showPanel("Licences and credits", body)
    }

    /** The open Settings sheet; tides, anchor and tracks close it, as they work on the chart. */
    private var settingsDialog: android.app.Dialog? = null

    private fun colourName(value: String): String {
        val index = COLOUR_VALUES.indexOf(value)
        return if (index >= 0) COLOUR_NAMES[index] else "Custom"
    }

    private companion object {
        const val ROW_CROSSHAIR = "crosshair"
        const val ROW_COORDINATE = "coordinate"
        const val ROW_DEPTH_MODE = "depthMode"
        const val ROW_COORD_SIZE = "coordinateSize"
        const val ROW_DEPTH_SIZE = "depthSize"
        const val ROW_XYZ = "xyz"
        const val ROW_WAYPOINT_NAMES = "waypointNames"
        const val ROW_GRID = "grid"

        val COLOUR_NAMES = listOf(
            "Marine teal",
            "Green",
            "Orange",
            "Red",
            "Purple",
            "White"
        )
        val COLOUR_VALUES = listOf(
            "#19C6B3",
            "#22C55E",
            "#F97316",
            "#EF4444",
            "#A855F7",
            "#F4F6F8"
        )

        val COORDINATE_SIZES = listOf(11, 12, 13, 14, 15, 16)
        val DEPTH_SIZES = listOf(10, 11, 12, 13, 14, 15, 16)
    }
}
