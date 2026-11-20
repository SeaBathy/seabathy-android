package au.opendepth.app.download

import au.opendepth.app.model.DataSource
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.settings.XyzTilePresets
import au.opendepth.app.tiles.WebMercatorTiles
import java.util.Locale
import kotlin.math.cos

/**
 * The DETAIL of an offline map: the deepest zoom that is saved. The area
 * picker steps it, counts the tiles a drawn box needs at it, and refuses a box
 * that needs too many. Nothing here touches Android, so it is unit tested.
 */
object OfflineDetail {

    /** A download starts at this zoom unless its detail is lower: see [zoomRange]. */
    const val DEFAULT_MIN_ZOOM = 10

    /** How many zooms further out than a detail below [DEFAULT_MIN_ZOOM] are saved with it. */
    private const val LEVELS_ABOVE_LOW_DETAIL = 4

    /** The least detail the picker steps down to. */
    const val LEAST_ZOOM = 3

    /** A map link the app knows nothing about is offered no deeper than this (about 1 m a pixel). */
    const val UNKNOWN_TOP_ZOOM = 17

    /** Sentinel-2 cloudless is 10 m imagery: zoom 14 is its full detail, and deeper only repeats it. */
    const val SENTINEL2_TOP_ZOOM = 14

    /** More than this is almost always a mistake (an area or zoom range far too big): refused. */
    const val MAX_TILES = 200_000L

    /** The detail first offered for an area stops here (tens of MB of imagery). */
    const val SUGGESTED_TILES = 30_000L

    /**
     * The most detail the map at [urlTemplate] has: what the app knows of its built-in maps,
     * and [UNKNOWN_TOP_ZOOM] for a link the person added.
     */
    fun topZoom(urlTemplate: String): Int = when {
        urlTemplate == DataSource.DEFAULT_PRESET.urlTemplate -> SENTINEL2_TOP_ZOOM
        else -> XyzTilePresets.ALL.firstOrNull { it.urlTemplate == urlTemplate }?.maxZoom?.coerceAtMost(UNKNOWN_TOP_ZOOM)
            ?: UNKNOWN_TOP_ZOOM
    }

    /**
     * The zooms saved at [detail]: from [DEFAULT_MIN_ZOOM] in to the detail. A detail lower
     * than that is saved with the few zooms further out, so the map still shows when the chart
     * is zoomed out from it.
     */
    fun zoomRange(detail: Int): IntRange {
        val deepest = detail.coerceIn(0, WebMercatorTiles.MAX_SANE_ZOOM)
        val first = if (deepest >= DEFAULT_MIN_ZOOM) DEFAULT_MIN_ZOOM else (deepest - LEVELS_ABOVE_LOW_DETAIL).coerceAtLeast(0)
        return first..deepest
    }

    /** A drawn box as the bounds a download takes, kept inside what a map tile can cover. */
    fun boundsOf(south: Double, west: Double, north: Double, east: Double): GeoBounds = GeoBounds(
        west = west.coerceIn(-180.0, 180.0),
        south = south.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
        east = east.coerceIn(-180.0, 180.0),
        north = north.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    )

    /** Where a Web Mercator map ends, north and south. */
    private const val MAX_LATITUDE = 85.0511

    /** How many tiles [bounds] needs at [detail]. */
    fun tiles(bounds: GeoBounds, detail: Int): Long =
        OfflineDownloadEstimator.estimateTiles(bounds, zoomRange(detail)).tileCount

    /** The least and most detail the picker offers for a map whose own most is [top]. */
    fun limits(top: Int): IntRange {
        val most = top.coerceIn(0, WebMercatorTiles.MAX_SANE_ZOOM)
        return minOf(LEAST_ZOOM, most)..most
    }

    /** [detail] moved [by] steps, kept within [limits]. */
    fun step(detail: Int, by: Int, top: Int): Int = (detail + by).coerceIn(limits(top).first, limits(top).last)

    /**
     * The detail first offered for [bounds]: as deep as the map goes ([top]) while the download
     * stays a sensible size ([SUGGESTED_TILES]).
     */
    fun suggested(bounds: GeoBounds, top: Int): Int {
        val range = limits(top)
        var detail = range.last
        while (detail > range.first && tiles(bounds, detail) > SUGGESTED_TILES) detail--
        return detail
    }

    /** Whether a download of [tiles] tiles is allowed. */
    fun fits(tiles: Long): Boolean = tiles in 1..MAX_TILES

    /** The line under the DETAIL control: how many tiles the box needs, or why it can't be saved. */
    fun countLine(tiles: Long): String = when {
        tiles <= 0L -> "Nothing to save at this detail."
        tiles > MAX_TILES ->
            "${OfflineDownloadEstimator.formatTileCount(tiles)} tiles is too many (the most is " +
                "${OfflineDownloadEstimator.formatTileCount(MAX_TILES)}). Make the box smaller or lower the detail."
        else ->
            "${OfflineDownloadEstimator.formatTileCount(tiles)} ${if (tiles == 1L) "tile" else "tiles"}, roughly " +
                "${OfflineDownloadEstimator.formatBytes(tiles * OfflineDownloadEstimator.ASSUMED_AVERAGE_TILE_BYTES)}."
    }

    /** What a zoom means on the ground at [latitude]: "about 10 m detail". */
    fun meaning(zoom: Int, latitude: Double): String {
        val metres = WebMercatorTiles.metresPerPixelAtZoom(zoom.coerceIn(0, WebMercatorTiles.MAX_SANE_ZOOM)) *
            cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0)))
        val size = when {
            metres >= 10_000 -> String.format(Locale.US, "%.0f km", metres / 1000)
            metres >= 1_000 -> String.format(Locale.US, "%.1f km", metres / 1000)
            metres >= 2 -> String.format(Locale.US, "%.0f m", metres)
            else -> String.format(Locale.US, "%.1f m", metres)
        }
        return "about $size detail"
    }
}
