package au.opendepth.app.fishing

import android.location.Location
import androidx.appcompat.app.AppCompatActivity
import au.opendepth.app.conditions.ConditionsFeature
import au.opendepth.app.data.WaypointStore
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.map.MapHolder
import au.opendepth.app.map.MarineZonesController
import au.opendepth.app.map.OverlayLayerController
import au.opendepth.app.map.ZoneMode
import au.opendepth.app.model.Waypoint
import au.opendepth.app.nav.NavUnits
import au.opendepth.app.storage.GpxExportService
import au.opendepth.app.ui.PanelDialogs
import kotlinx.coroutines.CoroutineScope

/**
 * The fishing tools, wired to the rest of the app in one place: the
 * structure finder (a depth layer's STRUCTURE button), the drift planner
 * and the chartplotter export (both in Settings). MainActivity builds this,
 * passes on GPS fixes and style rebuilds, and supplies [Callbacks].
 */
class FishingTools(
    private val activity: AppCompatActivity,
    panels: PanelDialogs,
    mapHolder: MapHolder,
    scope: CoroutineScope,
    private val overlays: OverlayLayerController,
    private val waypoints: WaypointStore,
    private val conditions: ConditionsFeature,
    private val zones: MarineZonesController,
    private val gpxExport: GpxExportService,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun units(): NavUnits
        fun variation(lat: Double, lng: Double): Double
        fun mapCentre(): Pair<Double, Double>?
        fun startGps()
        /** Lets go of the boat when the chart follows it. */
        fun stopFollowing()
        fun closeSidePanel()
        /** A waypoint was added: draw, list and save them again. */
        fun waypointsChanged()
        /** Steers to one point with the app's navigation. */
        fun navigateTo(lat: Double, lng: Double, name: String)
        fun toast(message: String)
    }

    val structure = StructureFeature(activity, panels, mapHolder, scope, object : StructureFeature.Callbacks {
        override fun depthUnit() = callbacks.units().depth
        override fun depthSource(layerId: String) = overlays.depthSourceFor(layerId)
        override fun tileZoom(layerId: String) = overlays.depthTileZoom(layerId)
        override fun closeSidePanel() = callbacks.closeSidePanel()
        override fun stopFollowing() = callbacks.stopFollowing()
        override fun saveWaypoint(name: String, lat: Double, lng: Double, description: String) = save(name, lat, lng, description)
        override fun toast(message: String) = callbacks.toast(message)
    })

    val drift = DriftFeature(activity, panels, mapHolder, scope, object : DriftFeature.Callbacks {
        override fun units() = callbacks.units()
        override fun variation(lat: Double, lng: Double) = callbacks.variation(lat, lng)
        override fun mapCentre() = callbacks.mapCentre()
        override fun waypoints(): List<Waypoint> = waypoints.all.toList()
        override fun startGps() = callbacks.startGps()
        override fun navigateTo(lat: Double, lng: Double, name: String) = callbacks.navigateTo(lat, lng, name)
        override fun saveWaypoint(name: String, lat: Double, lng: Double, description: String) = save(name, lat, lng, description)
        override fun conditionsAt(lat: Double, lng: Double) = conditions.areaCovering(lat, lng)
        override fun toast(message: String) = callbacks.toast(message)
    })

    val plotter = PlotterExport(activity, panels, mapHolder, scope, object : PlotterExport.Callbacks {
        override fun units() = callbacks.units()
        override fun waypoints(): List<Waypoint> = waypoints.all.toList()
        override fun structureMarks() = structure.shownMarks()
        override fun depthLayer(view: au.opendepth.app.model.GeoBounds) = overlays.stackTopFirst.firstNotNullOfOrNull { layer ->
            if (!layer.visible) return@firstNotNullOfOrNull null
            val source = overlays.depthSourceFor(layer.id) ?: return@firstNotNullOfOrNull null
            // On screen: the top layer may be a survey somewhere else altogether.
            if (ScreenDepths.partOf(view, source.bounds) == null) null else Triple(layer.name, source, overlays.depthTileZoom(layer.id))
        }
        override fun seaConditions() = conditions.shownForExport()?.let { (area, colour, lines) -> PlotterExport.SeaShown(area, colour, lines) }
        override fun zonesShown() = zones.mode != ZoneMode.OFF
        override fun shareIntent(gpx: String) = runCatching { gpxExport.buildPlotterShareIntent(gpx) }.getOrElse { error ->
            DiagnosticLog.logError(TAG, "plotter GPX file FAILED", error)
            activity.runOnUiThread { callbacks.toast("Couldn't create the GPX file") }
            null
        }
        override fun startShare(intent: android.content.Intent) = runCatching { activity.startActivity(intent) }.onFailure {
            callbacks.toast("No app available to receive the GPX file")
        }.isSuccess
        override fun toast(message: String) = callbacks.toast(message)
    })

    /** Puts the tools' marks back on a new style, after the waypoints went on. */
    fun install() {
        structure.install()
        drift.install()
    }

    fun onLocation(location: Location) = drift.onLocation(location)

    fun release() = drift.release()

    /** Saves a fishing waypoint under [name], or the first free "name 2", "name 3"... Returns the name used. */
    private fun save(name: String, lat: Double, lng: Double, description: String): String? {
        var candidate = name
        var n = 2
        while (waypoints.isNameTaken(candidate)) candidate = "$name ${n++}"
        return runCatching {
            waypoints.add(candidate, lat, lng, Waypoint.DEFAULT_COLOR, description, icon = "fish")
            callbacks.waypointsChanged()
            candidate
        }.onFailure { DiagnosticLog.logError(TAG, "save waypoint FAILED", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "FishingTools"
    }
}
