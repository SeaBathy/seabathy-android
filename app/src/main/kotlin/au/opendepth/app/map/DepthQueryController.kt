package au.opendepth.app.map

import android.graphics.RectF
import au.opendepth.app.diagnostics.DiagnosticLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.abs

/**
 * Produces the depth readout under the crosshair.
 *
 * An imported GeoTIFF gives an exact sounding, so it is preferred. Otherwise
 * the nearest rendered contour feature from a vector MBTiles layer is used,
 * which only bounds the depth and is reported as such.
 */
class DepthQueryController(
    private val mapHolder: MapHolder,
    private val overlays: OverlayLayerController
) {
    /**
     * Formatted readout, or an empty string when nothing is known. Call on
     * the main thread: the raster samples run on [Dispatchers.IO] (a GDAL
     * read can wait seconds behind a tile render holding its dataset lock),
     * the rendered-contour fallback back on the main thread.
     */
    suspend fun query(
        latitude: Double,
        longitude: Double,
        format: (metres: Double) -> String = { String.format(Locale.US, if (it > 0) "+%.1f m" else "%.1f m", abs(it)) }
    ): String {
        depthMetres(latitude, longitude)?.let { return format(it) }
        val contourMetres = nearestContourMetres(latitude, longitude)
        return if (contourMetres == null) "" else ">${contourMetres}m"
    }

    /** Survey depth at a point from the visible depth layers, metres (negative down), or null. */
    suspend fun depthMetres(latitude: Double, longitude: Double): Double? {
        val sources = overlays.visibleDepthSourcesTopFirst()
        val metres = if (sources.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                // A source closed by a layer removal mid-query just has no answer;
                // a real read error is logged (first one only: it recurs on every camera move).
                sources.firstNotNullOfOrNull { source ->
                    runCatching { source.sampleMetres(latitude, longitude) }
                        .onFailure { error ->
                            if (sampleFailureLogged.compareAndSet(false, true)) {
                                DiagnosticLog.logError(TAG, "depth sample FAILED (later failures not logged)", error)
                            }
                        }
                        .getOrNull()
                }
            }
        }
        return metres
    }

    private fun nearestContourMetres(latitude: Double, longitude: Double): Int? {
        val map = mapHolder.map ?: return null
        val layerIds = overlays.contourLayerIds()
        if (layerIds.isEmpty()) return null

        val point = map.projection.toScreenLocation(LatLng(latitude, longitude))
        for (radius in SEARCH_RADII) {
            val bounds = RectF(
                point.x - radius,
                point.y - radius,
                point.x + radius,
                point.y + radius
            )

            var shallowest: Int? = null
            for (feature in map.queryRenderedFeatures(bounds, *layerIds)) {
                val raw = CONTOUR_PROPERTIES
                    .firstNotNullOfOrNull { feature.getNumberProperty(it) }
                    ?.toInt()
                    ?: continue

                val metres = abs(raw)
                if (metres > 0 && (shallowest == null || metres < shallowest)) {
                    shallowest = metres
                }
            }

            if (shallowest != null) return shallowest
        }

        return null
    }

    private val sampleFailureLogged = AtomicBoolean(false)

    private companion object {
        const val TAG = "DepthQuery"
        val SEARCH_RADII = floatArrayOf(40f, 80f, 150f, 300f)
        val CONTOUR_PROPERTIES = listOf("Contour", "contour", "depth", "DEPTH")
    }
}
