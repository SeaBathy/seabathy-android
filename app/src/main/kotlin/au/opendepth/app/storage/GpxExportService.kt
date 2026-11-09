package au.opendepth.app.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import au.opendepth.app.model.RoutePoint
import au.opendepth.app.model.Waypoint
import au.opendepth.app.track.TrackPoint
import au.opendepth.app.util.GpxExporter
import java.io.File

/** Writes a GPX file into shareable storage and hands it to the share sheet. */
class GpxExportService(private val context: Context) {

    /**
     * @return the share intent, or null when there is nothing to export.
     */
    fun buildShareIntent(
        waypoints: List<Waypoint>,
        routePoints: List<RoutePoint>,
        routeName: String = "Route"
    ): Intent? {
        if (waypoints.isEmpty() && routePoints.size < 2) return null
        // A readable name for wherever it is shared to (it was a raw timestamp).
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
        return share("seabathy_$stamp.gpx", GpxExporter.build(waypoints, routePoints, routeName))
    }

    /** A recorded track, in a file named after it. */
    fun buildTrackShareIntent(name: String, points: List<TrackPoint>): Intent? {
        if (points.size < 2) return null
        val fileName = name.replace(Regex("[^A-Za-z0-9 ._-]+"), " ").trim().replace(Regex("\\s+"), "_").ifEmpty { "track" }
        return share("seabathy_track_$fileName.gpx", GpxExporter.buildTrack(name, points))
    }

    private fun share(fileName: String, gpx: String): Intent {
        val exportDir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
        // Earlier exports have already been handed to the share sheet; without
        // this every export is kept in private storage forever.
        exportDir.listFiles { file -> file.extension == "gpx" }?.forEach { it.delete() }
        val file = File(exportDir, fileName)
        file.writeText(gpx)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = GPX_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Export GPX"
        )
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val GPX_MIME_TYPE = "application/gpx+xml"
    }
}
