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

        /** Substitutes `{z}`/`{x}`/`{y}` by token, not position — correct regardless of the template's own placeholder order (e.g. EOX's `{z}/{y}/{x}`). */
        internal fun urlFor(urlTemplate: String, z: Int, x: Int, y: Int): String =
            urlTemplate
                .replace("{z}", z.toString())
                .replace("{x}", x.toString())
                .replace("{y}", y.toString())
    }
}
