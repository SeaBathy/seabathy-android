package au.opendepth.app.download

import au.opendepth.app.model.GeoBounds

/**
 * Fetches a few real tiles spread over the area so the download size can be
 * estimated from real tile sizes. [fetch] returns null for a failed fetch;
 * tests pass a fake one.
 */
class TileSampler(private val fetch: (url: String) -> ByteArray?) {

    class Result(val sampledSizes: List<Int>) {
        /** Null only if every sample failed — callers fall back to a flat assumption in that case, never to a zero/negative estimate. */
        val averageBytes: Long?
            get() = if (sampledSizes.isEmpty()) null else sampledSizes.map { it.toLong() }.average().toLong()
    }

    /**
     * About [sampleCount] tiles, at least one per zoom level and spread across
     * each level. Spreading over the whole list instead would pick almost only
     * the highest zoom, which has most of the tiles.
     */
    fun sample(
        urlTemplate: String,
        bounds: GeoBounds,
        zoomRange: IntRange,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): Result {
        val byZoom = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange).groupBy { it.first }
        if (byZoom.isEmpty()) return Result(emptyList())

        val perZoom = (sampleCount / byZoom.size).coerceAtLeast(1)
        val picks = byZoom.values.flatMap { candidatesForOneZoom -> spreadPicks(candidatesForOneZoom, perZoom) }
        val sizes = picks.mapNotNull { (z, x, y) -> fetch(urlFor(urlTemplate, z, x, y))?.size }
        return Result(sizes)
    }

    companion object {
        const val DEFAULT_SAMPLE_COUNT = 8

        /** [count] picks spread evenly through [candidates] (the first few would all be the lowest zoom). */
        internal fun <T> spreadPicks(candidates: List<T>, count: Int): List<T> {
            if (candidates.isEmpty() || count <= 0) return emptyList()
            val n = count.coerceAtMost(candidates.size)
            return (0 until n).map { i -> candidates[(i * candidates.size) / n] }
        }

        /**
         * Substitutes `{z}`/`{x}`/`{y}` by token, not position — correct regardless of the template's own
         * placeholder order (e.g. EOX's `{z}/{y}/{x}`) — and a WMS link's `{bbox-epsg-3857}` with the tile's
         * corners in web-mercator metres, as MapLibre does.
         */
        internal fun urlFor(urlTemplate: String, z: Int, x: Int, y: Int): String {
            val url = urlTemplate
                .replace("{z}", z.toString())
                .replace("{x}", x.toString())
                .replace("{y}", y.toString())
            return if (url.contains(BBOX)) url.replace(BBOX, bbox3857(z, x, y)) else url
        }

        private const val BBOX = "{bbox-epsg-3857}"

        /** Half the width of the web-mercator world, in metres. */
        private const val ORIGIN_SHIFT = 20037508.342789244

        /** A tile's west, south, east and north in web-mercator metres, as a WMS BBOX. */
        internal fun bbox3857(z: Int, x: Int, y: Int): String {
            val size = 2 * ORIGIN_SHIFT / (1 shl z)
            val west = -ORIGIN_SHIFT + x * size
            val north = ORIGIN_SHIFT - y * size
            fun f(v: Double) = String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            return "${f(west)},${f(north - size)},${f(west + size)},${f(north)}"
        }
    }
}
