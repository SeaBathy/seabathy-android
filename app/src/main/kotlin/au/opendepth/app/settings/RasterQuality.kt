package au.opendepth.app.settings

import android.app.ActivityManager
import android.content.Context

/**
 * How finely an in-memory GeoTIFF is decoded: the longest edge, in cells, of
 * the depth grid and of the image drawn on the chart. Files larger than this
 * are downsampled on import, so a higher setting keeps more survey detail at
 * the cost of memory (roughly 8 bytes per cell while a layer is loaded).
 * Very large files (GDAL on-demand tiles) use [gdalTilePixels] instead: the
 * size each map tile is rendered at.
 */
enum class RasterQuality(
    val id: String,
    val label: String,
    val detail: String,
    val maxDimension: Int,
    /** Pixel size of on-demand tiles for large (GDAL) GeoTIFFs; see GdalTileSource.colourTileSize. */
    val gdalTilePixels: Int,
    /** Starting detail of the 3D view, in cells along the longer side. */
    val terrainDetail: Int
) {
    BATTERY("battery", "Battery saver", "older or low-memory phones", 1024, 256, 256),
    STANDARD("standard", "Standard", "most phones", 1600, 384, 512),
    HIGH("high", "High", "sharp — needs more memory", 2560, 512, 1024),
    MAXIMUM("maximum", "Maximum", "full detail — recent phones", 4096, 768, 2048);

    companion object {
        fun byId(id: String?): RasterQuality? = entries.firstOrNull { it.id == id }

        /** A starting point from the device's own memory class, before the user picks one. */
        fun defaultFor(context: Context): RasterQuality {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return STANDARD
            return when {
                manager.isLowRamDevice -> BATTERY
                manager.largeMemoryClass >= 512 -> HIGH
                else -> STANDARD
            }
        }
    }
}
