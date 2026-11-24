package au.opendepth.app.ui

import android.content.Context
import android.widget.TextView
import au.opendepth.app.settings.DepthDisplayMode
import au.opendepth.app.settings.VisualSettings
import au.opendepth.app.util.CoordinateFormatter
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
        val onBackup: () -> Unit,
        val onWaypointFromPhoto: () -> Unit = {},
        val showGrid: () -> Boolean,
        val onShowGridChanged: (Boolean) -> Unit,
        val showNavMarks: () -> Boolean,
        /** Turning on asks for the not-for-navigation confirmation first; [done] gets the new state. */
        val onShowNavMarksChanged: (Boolean, done: (Boolean) -> Unit) -> Unit,
        val onGetSeabed: () -> Unit,
        val zones: au.opendepth.app.map.MarineZonesController? = null,
        /** Asks once that the zones are a guide only; [done] runs when accepted. */
        val confirmZones: (done: () -> Unit) -> Unit = { it() },
        val onZonesChanged: () -> Unit = {},
        val showScaleBar: () -> Boolean = { true },
        val onShowScaleBarChanged: (Boolean) -> Unit = {},
        /** How positions are written: one of [CoordinateFormatter.MODES]. */
        val positionFormat: () -> String = { CoordinateFormatter.MODE_DDM },
        val onPositionFormatChanged: (String) -> Unit = {},
        /** A position to show each format with (the middle of the chart), as latitude and longitude. */
        val positionExample: () -> Pair<Double, Double>? = { null },
        /** Opens the Storage screen: what the app keeps on the phone. */
        val onStorage: () -> Unit = {},
        /** Opens the drift planner over the chart. */
        val onDriftPlanner: (() -> Unit)? = null,
        /** Saves what's on the chart as a GPX file for a chartplotter. */
        val onPlotterExport: (() -> Unit)? = null,
        /** Wrecks, artificial reefs, shark gear, fishing closures and NSW reefs (Australia page). */
        val fishing: au.opendepth.app.map.FishingLayersController? = null
    )

    /**
     * Settings: a short menu of categories, each opening its own page, so nothing is a long scroll.
     * Australia-only layers (marine park zones, reef names, ramps, buoys) have their own page,
     * since the rest of the app works anywhere in the world.
     */
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
            rows[ROW_SCALE_BAR]?.text = "Scale bar\n" +
                if (callbacks.showScaleBar()) "On" else "Off"
            rows[ROW_POSITION_FORMAT]?.text = "Position format\n" +
                CoordinateFormatter.labelFor(callbacks.positionFormat())
            rows[ROW_NAV_MARKS]?.text = "Buoys, beacons, pipelines (beta)\n" +
                if (callbacks.showNavMarks()) "On" else "Off"
        }

        /** Closes the open page and the menu: for rows that hand over to a screen on the chart. */
        fun closeAll() {
            categoryDialog?.dismiss()
            settingsDialog?.dismiss()
        }

        lateinit var openCategory: (Int) -> Unit

        /** Rebuilds the open page after a change that alters its rows. */
        fun reopen() {
            categoryDialog?.dismiss()
            openCategory(currentCategory)
        }

        fun mapDisplay(body: android.widget.LinearLayout) {
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

            rows[ROW_SCALE_BAR] = panels.row(
                "Scale bar",
                if (callbacks.showScaleBar()) "On" else "Off"
            ) {
                panels.showChoicePanel(
                    title = "Scale bar",
                    labels = listOf("Off", "On"),
                    selectedIndex = if (callbacks.showScaleBar()) 1 else 0
                ) { index ->
                    callbacks.onShowScaleBarChanged(index == 1)
                    refresh()
                }
            }.also { panels.addRow(body, it) }

            rows[ROW_POSITION_FORMAT] = panels.row(
                "Position format",
                CoordinateFormatter.labelFor(callbacks.positionFormat())
            ) {
                val example = callbacks.positionExample()
                panels.showChoicePanel(
                    title = "Position format",
                    labels = CoordinateFormatter.choiceLabels(example?.first, example?.second),
                    selectedIndex = CoordinateFormatter.MODES.indexOf(callbacks.positionFormat())
                ) { index ->
                    callbacks.onPositionFormatChanged(CoordinateFormatter.MODES[index])
                    refresh()
                }
            }.also { panels.addRow(body, it) }
        }

        fun depths(body: android.widget.LinearLayout) {
            panels.addRow(
                body,
                panels.row("Get seabed depths", "Draw the area on the chart. Free depth data, anywhere in the world") {
                    closeAll()
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

            fun limitLabel(mb: Int) = if (mb == 0) "Auto (about ${au.opendepth.app.storage.HighDetailDepths.autoLimitMb()} MB on this phone now)" else "$mb MB"
            lateinit var limitRow: TextView
            limitRow = panels.row("High-detail download size", limitLabel(au.opendepth.app.storage.HighDetailDepths.limitChoice(context))) {
                val choices = au.opendepth.app.storage.HighDetailDepths.LIMIT_CHOICES
                panels.showChoicePanel(
                    title = "High-detail download size",
                    labels = choices.map { if (it == 0) limitLabel(0) + " — follows the phone's free memory" else "$it MB" + if (it >= 500) " — phones with plenty of memory" else "" },
                    selectedIndex = choices.indexOf(au.opendepth.app.storage.HighDetailDepths.limitChoice(context)).coerceAtLeast(0)
                ) { index ->
                    au.opendepth.app.storage.HighDetailDepths.setLimitChoice(context, choices[index])
                    limitRow.text = "High-detail download size\n${limitLabel(choices[index])}"
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
        }

        fun maps(body: android.widget.LinearLayout) {
            rows[ROW_XYZ] = panels.row("Online map layers", onlineLayersDetail()) {
                xyzDialog.show(callbacks.xyz)
            }.also { panels.addRow(body, it) }
            panels.addRow(
                body,
                panels.row(
                    "Offline maps",
                    "Draw the area on the chart, then download it"
                ) {
                    dataSourceDialog.show(callbacks.dataSources)
                }
            )
        }

        fun boat(body: android.widget.LinearLayout) {
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
                panels.row("Anchor alarm", "Alarm if the boat drags") {
                    closeAll()
                    callbacks.onAnchor()
                }
            )
            panels.addRow(
                body,
                panels.row("Tracks", "Record where the boat goes, share as GPX") {
                    closeAll()
                    callbacks.onTracks()
                }
            )
            callbacks.onDriftPlanner?.let { open ->
                panels.addRow(body, panels.row("Drift planner", "Where to start a drift so it passes over a mark") {
                    closeAll()
                    open()
                })
            }
        }

        fun tidesAndSea(body: android.widget.LinearLayout) {
            panels.addRow(
                body,
                panels.row("Tides", "Heights, highs and lows, offline") {
                    closeAll()
                    callbacks.onTides()
                }
            )
            panels.addRow(
                body,
                panels.row("Sea conditions", "Currents, waves, wind, sea temperature, offline") {
                    closeAll()
                    callbacks.onConditions()
                }
            )
        }

        fun australia(body: android.widget.LinearLayout) {
            body.addView(
                panels.caption(
                    "These layers cover Australian waters only. Everything else in SeaBathy works anywhere in the world.",
                    topPaddingDp = 0
                )
            )
            callbacks.zones?.let { zones ->
                panels.addRow(body, panels.row("Marine park zones", zones.mode.label + if (zones.mode == au.opendepth.app.map.ZoneMode.OFF) ": no-take zones in green" else "") {
                    val modes = au.opendepth.app.map.ZoneMode.entries
                    panels.showChoicePanel("Marine park zones", modes.map { it.label }, modes.indexOf(zones.mode)) { i ->
                        fun set() {
                            zones.setMode(modes[i])
                            callbacks.onZonesChanged()
                            reopen()
                        }
                        if (modes[i] != au.opendepth.app.map.ZoneMode.OFF && !zones.acknowledged) callbacks.confirmZones { set() } else set()
                    }
                })
                panels.addRow(body, panels.row("Reef and island names", if (zones.names) "On" else "Off: named reefs, islands, shoals and banks") {
                    zones.setNames(!zones.names)
                    callbacks.onZonesChanged()
                    reopen()
                })
                panels.addRow(body, panels.row("Boat ramps", if (zones.ramps) "On" else "Off") {
                    zones.setRamps(!zones.ramps)
                    callbacks.onZonesChanged()
                    reopen()
                })
                panels.addRow(body, panels.row("Warn me in a no-take zone", if (zones.warn) "On: when the GPS is on, even with the zones hidden" else "Off") {
                    zones.setWarn(!zones.warn)
                    reopen()
                })
            }
            callbacks.fishing?.let { fishing ->
                fun toggle(title: String, on: Boolean, off: String, set: (Boolean) -> Unit) =
                    panels.addRow(body, panels.row(title, if (on) "On" else "Off: $off") {
                        set(!on)
                        callbacks.onZonesChanged()
                        reopen()
                    })
                toggle("Shipwrecks", fishing.wrecks, "about 5,700 wrecks. Many positions are approximate", fishing::setWrecks)
                toggle("Artificial reefs", fishing.reefs, "reefs built for fishing in Queensland, and the Gold Coast reef", fishing::setReefs)
                toggle("Shark nets and drumlines", fishing.sharkGear, "Queensland Shark Control Program gear", fishing::setSharkGear)
                toggle("Fishing closures", fishing.closures,
                    "closed areas, fishing limits, no-anchoring and fish habitat areas (Qld, WA, Great Barrier Reef)", fishing::setClosures)
                toggle("NSW seabed reefs", fishing.nswReefs,
                    "reef mapped from lidar and sonar. Loads as you zoom in, kept for offline", fishing::setNswReefs)
            }
            rows[ROW_NAV_MARKS] = panels.row(
                "Buoys, beacons, pipelines (beta)",
                if (callbacks.showNavMarks()) "On" else "Off"
            ) {
                panels.showChoicePanel(
                    title = "Buoys, beacons, pipelines (beta)",
                    labels = listOf("Off", "On"),
                    selectedIndex = if (callbacks.showNavMarks()) 1 else 0
                ) { index ->
                    callbacks.onShowNavMarksChanged(index == 1) { refresh() }
                }
            }.also { panels.addRow(body, it) }
        }

        fun dataAndBackup(body: android.widget.LinearLayout) {
            panels.addRow(
                body,
                panels.row("Backup and restore", "Waypoints, photos, routes, tracks and settings; daily to a folder") {
                    closeAll()
                    callbacks.onBackup()
                }
            )
            panels.addRow(
                body,
                panels.row("Waypoint from a photo", "Where a gallery photo was taken, with the tide and sea then") {
                    closeAll()
                    callbacks.onWaypointFromPhoto()
                }
            )
            panels.addRow(
                body,
                panels.row("Import GPX, KML or KMZ", "From a plotter, Google Earth or another app") {
                    callbacks.onImportGpx()
                }
            )
            panels.addRow(
                body,
                panels.row("Export waypoints and route", "Share as a GPX file") {
                    callbacks.onExportGpx()
                }
            )
            callbacks.onPlotterExport?.let { export ->
                panels.addRow(body, panels.row("Export for a chartplotter", "What's on screen, with contours and marks, as GPX for an SD card") {
                    closeAll()
                    export()
                })
            }
            panels.addRow(
                body,
                panels.row("Storage", "What the app keeps on this phone") {
                    callbacks.onStorage()
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
        }

        fun about(body: android.widget.LinearLayout) {
            panels.addRow(
                body,
                panels.row("Help and guide", "How to use SeaBathy, on seabathy.com") {
                    openLink(AppLinks.GUIDE_URL)
                }
            )
            panels.addRow(
                body,
                panels.row("Share SeaBathy", "Send a link to a friend or your fishing club") {
                    shareApp()
                }
            )
            if (au.opendepth.app.BuildConfig.PLAY_STORE) {
                panels.addRow(
                    body,
                    panels.row("Rate SeaBathy on Google Play", "Reviews help other boaters find the app") {
                        rateApp()
                    }
                )
            }
            panels.addRow(
                body,
                panels.row("Safety and terms", "Not for navigation: what SeaBathy is and isn't") {
                    SafetyTerms.show(context)
                }
            )
            panels.addRow(
                body,
                panels.row("Privacy policy", "No account, no ads, no tracking: what the app sends and why") {
                    openPrivacyPolicy()
                }
            )
            panels.addRow(
                body,
                panels.row("Licences and credits", "Open-source libraries and data sources") {
                    showLicences()
                }
            )
        }

        val categories = listOf(
            Triple("Map display", "Crosshair, text sizes, waypoint names, grid, scale bar, position format", ::mapDisplay),
            Triple("Depths", "Seabed downloads, your own files, depth colours", ::depths),
            Triple("Online and offline maps", "Satellite and other map layers, save maps offline", ::maps),
            Triple("Boat and navigation", "Boat, units, routes, anchor alarm, tracks, drift planner", ::boat),
            Triple("Tides and sea conditions", "Tide charts, wind, waves, currents, sea temperature", ::tidesAndSea),
            Triple("Australia", "Marine park zones, closures, wrecks, reefs, boat ramps, buoys", ::australia),
            Triple("Waypoints and backup", "Backup, photos, GPX and KML, storage, debug log", ::dataAndBackup),
            Triple(
                "About",
                if (au.opendepth.app.BuildConfig.PLAY_STORE) "Help, share, rate, safety and terms, privacy, licences"
                else "Help, share, safety and terms, privacy, licences",
                ::about
            )
        )

        openCategory = { index ->
            currentCategory = index
            val (title, _, build) = categories[index]
            val page = panels.body()
            rows.clear()
            build(page)
            refresh()
            categoryDialog = panels.showPanel(title, page)
        }

        val menu = panels.body()
        categories.forEachIndexed { index, (title, detail, _) ->
            panels.addRow(menu, panels.row(title, detail) { openCategory(index) })
        }
        settingsDialog = panels.showPanel("Settings", menu)
    }

    private var categoryDialog: android.app.Dialog? = null
    private var currentCategory = 0

    private fun openPrivacyPolicy() = openLink(PRIVACY_POLICY_URL)

    /** Opens [url] in the browser; with none, says the address so it can be typed. */
    private fun openLink(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(context, url, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Android's own share sheet with a short line and the link: nothing else is sent anywhere. */
    private fun shareApp() {
        val link = AppLinks.shareUrl(au.opendepth.app.BuildConfig.PLAY_STORE, context.packageName)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "SeaBathy")
            .putExtra(android.content.Intent.EXTRA_TEXT, AppLinks.shareText(link))
        val chooser = android.content.Intent.createChooser(send, "Share SeaBathy")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }.onFailure {
            android.widget.Toast.makeText(context, link, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** The Play Store app on SeaBathy's page, or the page in the browser without it. */
    private fun rateApp() {
        val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AppLinks.marketUri(context.packageName)))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure { openLink(AppLinks.storeWebUrl(context.packageName)) }
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
        const val PRIVACY_POLICY_URL = "https://seabathy.com/privacy"
        const val ROW_CROSSHAIR = "crosshair"
        const val ROW_COORDINATE = "coordinate"
        const val ROW_DEPTH_MODE = "depthMode"
        const val ROW_COORD_SIZE = "coordinateSize"
        const val ROW_DEPTH_SIZE = "depthSize"
        const val ROW_XYZ = "xyz"
        const val ROW_WAYPOINT_NAMES = "waypointNames"
        const val ROW_GRID = "grid"
        const val ROW_SCALE_BAR = "scaleBar"
        const val ROW_POSITION_FORMAT = "positionFormat"
        const val ROW_NAV_MARKS = "nav_marks"

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
